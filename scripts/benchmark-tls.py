#!/usr/bin/env python3
"""Build the reference echo proxy, or measure the supported TLS backend on an explicit device."""
import argparse
import datetime
import hashlib
import itertools
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import socket
import statistics
import subprocess
import sys
import time
import uuid

from native_sources import source_record

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / "app/src/main/cpp"
BACKENDS = ("openssl",)


class SampleError(RuntimeError):
    def __init__(self, message, record=None):
        super().__init__(message)
        self.record = record


def run(command, **kwargs):
    return subprocess.run([str(x) for x in command], check=True, text=True, **kwargs)


def server(args):
    directory = args.directory.resolve()
    pki = directory / "pki"
    pki.mkdir(parents=True, exist_ok=True)
    certificate = pki / "server.pem"
    valid = certificate.exists() and subprocess.run(
        ["openssl", "x509", "-in", str(certificate), "-checkend", "3600", "-noout"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0 and subprocess.run(
        ["openssl", "verify", "-x509_strict", "-CAfile", str(pki / "ca.pem"),
         "-verify_hostname", "bench.proxy", str(certificate)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not valid:
        def openssl(*arguments):
            run(["openssl", *arguments], cwd=pki, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2", "-subj", "/CN=ProxyDroid benchmark CA",
                "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign",
                "-keyout", "ca.key", "-out", "ca.pem")
        openssl("req", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=bench.proxy", "-keyout", "server.key", "-out", "server.csr")
        (pki / "extensions").write_text("subjectAltName=DNS:bench.proxy\nextendedKeyUsage=serverAuth\n"
                                       "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                                       "subjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n")
        openssl("x509", "-req", "-in", "server.csr", "-CA", "ca.pem", "-CAkey", "ca.key", "-CAcreateserial", "-days", "2", "-extfile", "extensions", "-out", "server.pem")
        for key in pki.glob("*.key"):
            key.chmod(0o600)
    configure = ["cmake", "-S", CPP / "benchmarks", "-B", directory / "cmake", "-DCMAKE_BUILD_TYPE=Release"]
    if args.openssl_root:
        configure.append(f"-DOPENSSL_ROOT_DIR={args.openssl_root}")
    run(configure)
    run(["cmake", "--build", directory / "cmake", "--parallel", "4"])
    print(f"CA for the device runner: {pki / 'ca.pem'}", flush=True)
    if getattr(args, "prepare_only", False):
        return 0
    return run([directory / "cmake/tls-bench-server", args.bind, args.base_port, certificate, pki / "server.key"]).returncode


def adb(args, *command, **kwargs):
    return run(["adb", "-s", args.serial, *command], **kwargs)


def shell(args, *command, **kwargs):
    return adb(args, "shell", shlex.join([str(x) for x in command]), **kwargs)


def thermal(args):
    result = {"status": None, "battery_c": None}
    text = shell(args, "dumpsys", "thermalservice", stdout=subprocess.PIPE).stdout
    match = re.search(r"Thermal Status:\s*(\d+)", text, re.IGNORECASE)
    if match:
        result["status"] = int(match[1])
    text = shell(args, "dumpsys", "battery", stdout=subprocess.PIPE).stdout
    match = re.search(r"temperature:\s*(-?\d+)", text)
    if match:
        result["battery_c"] = int(match[1]) / 10
    return result


def cases(args):
    for mode, version, cipher in itertools.product(args.modes, args.versions, args.ciphers):
        variants = [(1, "none")] if mode in ("handshake", "resume") else itertools.product(args.concurrency, args.auth)
        for concurrency, auth in variants:
            # Four version/cipher combinations, each with a none/NTLM port pair.
            port = args.base_port + (int(version == "1.3") * 2 + int(cipher == "chacha")) * 2 + int(auth == "ntlm")
            yield {"mode": mode, "version": version, "cipher": cipher, "concurrency": concurrency, "auth": auth, "port": port}


def case_key(case):
    return "/".join(str(case[key]) for key in ("mode", "version", "cipher", "auth", "concurrency"))


def profile_index(case):
    return (int(case["version"] == "1.3") * 2 + int(case["cipher"] == "chacha")) * 2 + int(case["auth"] == "ntlm")


def reference_profile(case):
    return "network" if case["mode"] == "network" else profile_index(case)


class LocalReference:
    """Own a local reference process and reuse one reachable LAN port."""
    def __init__(self, args):
        self.args, self.process, self.profile, self.log = args, None, None, None
        self.directory = args.output / "reference"
        self.directory.mkdir(exist_ok=True)
        self.record = {"executable": str(args.reference_server.resolve()),
                       "sha256": hashlib.sha256(args.reference_server.read_bytes()).hexdigest(),
                       "bind": args.server, "port": args.base_port, "starts": []}

    def close(self):
        if self.process:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
            self.process = None
        if self.log:
            self.log.close()
            self.log = None
        self.profile = None

    def select(self, case):
        selected = reference_profile(case)
        if selected == self.profile:
            if self.process.poll() is not None:
                raise RuntimeError("Local reference server exited unexpectedly")
            return
        self.close()
        if hashlib.sha256(self.args.reference_server.read_bytes()).hexdigest() != self.record["sha256"]:
            raise RuntimeError("Local reference executable changed during the comparison")
        log_path = self.directory / f"server-{len(self.record['starts'])+1:04d}-profile-{selected}.log"
        command = [self.args.reference_server.resolve(), self.args.server, str(self.args.base_port),
                   self.args.ca.resolve().parent / "server.pem", self.args.ca.resolve().parent / "server.key", str(selected)]
        self.log = log_path.open("w")
        self.process = subprocess.Popen([str(x) for x in command], stdout=self.log, stderr=subprocess.STDOUT)
        self.record["starts"].append({"profile": selected, "pid": self.process.pid, "log": str(log_path),
                                      "started_at": datetime.datetime.now(datetime.timezone.utc).isoformat()})
        until = time.monotonic() + 10
        while time.monotonic() < until:
            if self.process.poll() is not None:
                raise RuntimeError(f"Local reference failed: {log_path.read_text()}")
            if '"ready":true' in log_path.read_text():
                self.profile = selected
                return
            time.sleep(.05)
        raise RuntimeError(f"Local reference did not become ready: {log_path}")

    def usage(self):
        result = run(["ps", "-p", str(self.process.pid), "-o", "cputime=,rss="], stdout=subprocess.PIPE)
        cpu, rss = result.stdout.split()
        seconds = 0.0
        for field in cpu.split(":"):
            seconds = seconds*60 + float(field)
        return {"pid": self.process.pid, "monotonic_seconds": time.monotonic(),
                "cpu_seconds": seconds, "rss_kib": int(rss)}


class DeviceReference(LocalReference):
    """Run the same fixed OpenSSL reference on the selected device's loopback."""
    def __init__(self, args, remote):
        super().__init__(args)
        self.remote = remote
        self.executable = remote + "/tls-bench-server"
        self.pid_file = remote + "/reference.pid"
        self.pid, self.ready = None, None
        self.record["location"] = "selected Android device, IPv4 loopback"
        self.record["cpu_mask"] = getattr(args, "reference_cpu_mask", None)
        for source, target in ((args.reference_server, self.executable),
                               (args.ca.parent / "server.pem", remote + "/server.pem"),
                               (args.ca.parent / "server.key", remote + "/server.key")):
            adb(args, "push", source.resolve(), target, stdout=subprocess.DEVNULL)
        shell(args, "chmod", "700", self.executable)
        shell(args, "chmod", "600", remote + "/server.key")

    def close(self):
        try:
            if self.process and self.process.poll() is None:
                pid = self.pid or int(shell(self.args, "cat", self.pid_file, stdout=subprocess.PIPE).stdout)
                command = shell(self.args, "cat", f"/proc/{pid}/cmdline", stdout=subprocess.PIPE).stdout
                if not command.startswith(self.executable + "\0"):
                    raise RuntimeError("Device reference PID no longer belongs to this benchmark")
                shell(self.args, "kill", "-TERM", str(pid))
                try:
                    self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    shell(self.args, "kill", "-KILL", str(pid))
        finally:
            super().close()
            self.pid, self.ready = None, None

    def select(self, case):
        selected = reference_profile(case)
        if selected == self.profile:
            if self.process.poll() is not None:
                raise RuntimeError("Device reference server exited unexpectedly")
            return
        self.close()
        if hashlib.sha256(self.args.reference_server.read_bytes()).hexdigest() != self.record["sha256"]:
            raise RuntimeError("Device reference executable changed during the comparison")
        log_path = self.directory / f"server-{len(self.record['starts'])+1:04d}-profile-{selected}.log"
        command = [self.executable, "127.0.0.1", str(self.args.base_port), self.remote + "/server.pem",
                   self.remote + "/server.key", str(selected)]
        if getattr(self.args, "reference_cpu_mask", None):
            command = ["taskset", self.args.reference_cpu_mask, *command]
        script = f"echo $$ > {shlex.quote(self.pid_file)}; exec {shlex.join(command)}"
        self.log = log_path.open("w")
        self.process = subprocess.Popen(["adb", "-s", self.args.serial, "shell", shlex.join(["sh", "-c", script])],
                                        stdout=self.log, stderr=subprocess.STDOUT)
        until = time.monotonic() + 10
        while time.monotonic() < until:
            if self.process.poll() is not None:
                raise RuntimeError(f"Device reference failed: {log_path.read_text()}")
            for line in log_path.read_text().splitlines():
                if line.startswith('{"ready":true'):
                    self.ready = json.loads(line)
                    self.pid, self.profile = int(self.ready["pid"]), selected
                    self.record["starts"].append({"profile": selected, "pid": self.pid, "log": str(log_path),
                                                  "ready": self.ready,
                                                  "started_at": datetime.datetime.now(datetime.timezone.utc).isoformat()})
                    return
            time.sleep(.05)
        raise RuntimeError(f"Device reference did not become ready: {log_path}")

    def usage(self):
        stat = shell(self.args, "cat", f"/proc/{self.pid}/stat", stdout=subprocess.PIPE).stdout
        fields = stat.rsplit(")", 1)[1].split()  # fields[0] is proc(5)'s field 3.
        return {"pid": self.pid, "monotonic_seconds": time.monotonic(),
                "cpu_seconds": (int(fields[11])+int(fields[12])) / self.ready["clock_ticks"],
                "rss_kib": int(fields[21]) * self.ready["page_size"] / 1024}


def sdk_path(explicit):
    if explicit:
        return explicit.resolve()
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk and (ROOT / "local.properties").exists():
        match = re.search(r"^sdk\.dir=(.+)$", (ROOT / "local.properties").read_text(), re.MULTILINE)
        if match:
            sdk = match[1].strip()
    if not sdk:
        raise ValueError("Supply --sdk or ANDROID_HOME")
    return Path(sdk).resolve()


def backend_directory(args, backend):
    return (getattr(args, "native_directory", None) or args.output / "native") / backend


def prepare(args, backend, remote, sdk):
    directory = backend_directory(args, backend)
    directory.mkdir(parents=True, exist_ok=True)
    archive = subprocess.check_output([sys.executable, str(ROOT / "scripts/fetch-native-deps.py"), backend], text=True).strip()
    cmake = sdk / "cmake/3.22.1/bin/cmake"
    ndk = sdk / "ndk/25.1.8937393"
    if not cmake.exists():
        raise ValueError("Android SDK CMake 3.22.1 is required")
    platform = 19 if args.abi in ("armeabi-v7a", "x86") else 21
    configure = [cmake, "-S", CPP, "-B", directory, "-G", "Ninja",
                 f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
                 f"-DANDROID_ABI={args.abi}", f"-DANDROID_PLATFORM=android-{platform}",
                 f"-DCMAKE_BUILD_TYPE={args.build_type}", f"-DTLS_BACKEND={backend}",
                 f"-DPROXYDROID_TLS_ARCHIVE={Path(archive).as_uri()}", "-DBUILD_TESTING=OFF", "-DPROXYDROID_BUILD_BENCHMARKS=ON"]
    targets = ["tls-benchmark"]
    if backend == "openssl" and args.transport == "loopback":
        targets.append("tls-bench-server")
    with (directory / "build.log").open("w") as log:
        run(configure, stdout=log, stderr=subprocess.STDOUT)
        run([cmake, "--build", directory, "--target", *targets, "--parallel", "4"], stdout=log, stderr=subprocess.STDOUT)
    strip = next(ndk.glob("toolchains/llvm/prebuilt/*/bin/llvm-strip"))
    artifact = directory / "libredsocks.so"
    run([strip, "--strip-unneeded", "-o", artifact, directory / "redsocks/libredsocks.so"])
    target = f"{remote}/{backend}"
    shell(args, "mkdir", "-p", target)
    adb(args, "push", artifact, f"{target}/libredsocks.so", stdout=subprocess.DEVNULL)
    adb(args, "push", directory / "benchmarks/tls-benchmark", f"{target}/tls-benchmark", stdout=subprocess.DEVNULL)
    shell(args, "chmod", "700", f"{target}/tls-benchmark")
    driver = directory / "benchmarks/tls-benchmark"
    record = {"stripped_engine_bytes": artifact.stat().st_size, "engine_sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
              "driver_sha256": hashlib.sha256(driver.read_bytes()).hexdigest(),
              "tls": json.loads((directory / "tls-build.json").read_text())}
    snapshot = args.output / "artifacts" / backend
    snapshot.mkdir(parents=True, exist_ok=True)
    for path in (artifact, driver, directory / "tls-build.json", directory / "CMakeCache.txt", directory / "build.log"):
        shutil.copy2(path, snapshot / path.name)
    if args.apk_directory:
        apk = args.apk_directory / f"{backend}.apk"
        if not apk.is_file():
            raise ValueError(f"Missing {apk}")
        record.update(apk_bytes=apk.stat().st_size, apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                      apk_path=str(apk.resolve()), apk_scope="Supplied APK; its build variant must be compared separately")
    return record


def invoke(args, backend, case, remote, seconds, iterations):
    environment = [f"LD_LIBRARY_PATH={remote}/{backend}", f"TMPDIR={remote}"]
    if getattr(args, "engine_cpu_mask", None):
        environment.append("PROXYDROID_BENCH_ENGINE_CPU_MASK=" + args.engine_cpu_mask)
    command = ["env", *environment, f"{remote}/{backend}/tls-benchmark",
               case["mode"], args.server, case["port"], f"{remote}/ca.pem", case["version"], case["concurrency"], seconds, iterations, case["auth"]]
    mask = getattr(args, "client_cpu_mask", None)
    if case["mode"] in ("handshake", "resume"):
        mask = getattr(args, "engine_cpu_mask", None) or mask
    if mask:
        command = ["taskset", mask, *command]
    directory = args.output / "invocations"
    directory.mkdir(exist_ok=True)
    stem = directory / (str(time.time_ns())+"-"+backend+"-"+case_key(case).replace("/", "-"))
    failure = None
    try:
        result = subprocess.run(["adb", "-s", args.serial, "shell", shlex.join([str(x) for x in command])],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=max(60, seconds+60, iterations*5))
        stdout, stderr, exit_code = result.stdout, result.stderr, result.returncode
    except subprocess.TimeoutExpired as error:
        failure = error
        def decoded(value):
            return value.decode(errors="replace") if isinstance(value, bytes) else value or ""
        stdout, stderr, exit_code = decoded(error.stdout), decoded(error.stderr), None
    Path(str(stem)+".stdout").write_text(stdout)
    Path(str(stem)+".stderr").write_text(stderr)
    Path(str(stem)+".json").write_text(json.dumps({"command": [str(x) for x in command],
                                                     "exit_code": exit_code, "timed_out": failure is not None,
                                                     "seconds": seconds, "iterations": iterations}, indent=2)+"\n")
    if failure:
        raise SampleError(f"{backend} {case_key(case)} exceeded its process timeout (logs {stem})") from failure
    try:
        record = json.loads(stdout)
    except json.JSONDecodeError as error:
        raise SampleError(f"{backend} {case_key(case)} returned invalid JSON (exit {exit_code}, logs {stem}): "
                          f"{stdout[-2000:]} {stderr[-2000:]}") from error
    if exit_code or not isinstance(record, dict) or not record.get("valid"):
        raise SampleError(f"{backend} {case_key(case)} failed (exit {exit_code}, logs {stem}): "
                          f"{stdout[-2000:]} {stderr[-2000:]}", record)
    return record


def summarize(args, report):
    groups = {}
    for sample in report["samples"]:
        if sample["accepted"]:
            groups.setdefault((sample["backend"], case_key(sample["case"])), []).append(sample)
    rows = []
    metrics = ("p50_ms", "p95_ms", "mbps_bidirectional", "cpu_seconds", "cpu_seconds_per_gib", "peak_rss_kib")
    for (backend, key), samples in sorted(groups.items()):
        row = {"backend": backend, "case": key, "rounds": len(samples)}
        for metric in metrics:
            values = [sample["result"][metric] for sample in samples]
            row[metric] = statistics.median(values)
            row[metric+"_min"] = min(values)
            row[metric+"_max"] = max(values)
        rows.append(row)
    report["summary"] = rows
    full_matrix = (len(args.backends) == len(BACKENDS) and set(args.backends) == set(BACKENDS) and set(args.versions) == {"1.2", "1.3"} and
                   set(args.ciphers) == {"aes", "chacha"} and set(args.auth) == {"none", "ntlm"} and
                   set(args.concurrency) == {1, 16, 64} and set(args.modes) == {"handshake", "resume", "short", "forward"})
    controlled = (report["complete"] and full_matrix and
                        args.rounds >= 5 and args.seconds >= 30 and args.iterations >= 200 and args.warmup >= 10 and
                        len(rows) == len(list(cases(args))) * len(BACKENDS) and
                        all(row["rounds"] >= 5 for row in rows) and
                        all(sample["thermal_before"]["status"] == sample["thermal_after"]["status"] == 0
                            for sample in report["samples"] if sample["accepted"]))
    report["controlled_lan"] = controlled and args.transport == "lan"
    report["formal"] = report["controlled_lan"] and len(set(args.backends)) > 1
    report["controlled_loopback"] = controlled and args.transport == "loopback" and bool(report.get("reference"))
    (args.output / "report.json").write_text(json.dumps(report, indent=2)+"\n")
    lines = ["# TLS backend evaluation", "", f"Scope: {report['scope']}", "",
             f"Complete: {report['complete']}; controlled LAN matrix: {report['controlled_lan']}; formal backend comparison: {report['formal']}; controlled device loopback matrix: {report['controlled_loopback']}.", "",
             "| Backend | Case | Rounds | P95 ms | Mbit/s both directions | CPU s/GiB | Peak RSS KiB |",
             "|---|---|---:|---:|---:|---:|---:|"]
    for row in rows:
        latency = "n/a" if row["case"].startswith("forward/") else f"{row['p95_ms']:.3f}"
        lines.append(f"| {row['backend']} | {row['case']} | {row['rounds']} | {latency} | {row['mbps_bidirectional']:.3f} | {row['cpu_seconds_per_gib']:.3f} | {row['peak_rss_kib']:.0f} |")
    if report.get("error"):
        lines += ["", "The run stopped with an error; failed samples are retained in samples.jsonl and report.json."]
    lines += ["", "Supported backends follow the pinned libevent release. One backend cannot establish a backend comparison.",
              "", "CPU/RSS for short/forward belong to the engine child. Handshake/resume use an isolated TLS harness, including its driver overhead.",
              "", "Rows measure different workloads and must not be averaged into a single score. Link/server saturation requires a separate capacity check; the runner cannot prove it."]
    (args.output / "report.md").write_text("\n".join(lines)+"\n")


def benchmark(args):
    if min(args.rounds, args.seconds, args.iterations) < 1 or args.warmup < 1 or not 1024 <= args.base_port <= 65527:
        raise ValueError("Invalid duration, count or base port")
    if not args.backends or len(set(args.backends)) != len(args.backends) or any(backend not in BACKENDS for backend in args.backends):
        raise ValueError("Select supported libevent TLS backends exactly once: " + ", ".join(BACKENDS))
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    args.server = socket.gethostbyname(args.server)
    if args.transport == "loopback" and args.server != "127.0.0.1":
        raise ValueError("Device loopback requires --server 127.0.0.1")
    if args.transport == "loopback" and "openssl" not in args.backends and not getattr(args, "reference_server", None):
        raise ValueError("Loopback without an openssl build requires --reference-server ANDROID_EXECUTABLE")
    if getattr(args, "reference_cpu_mask", None) and args.transport != "loopback":
        raise ValueError("--reference-cpu-mask requires --transport loopback")
    sdk = sdk_path(args.sdk)
    abis = shell(args, "getprop", "ro.product.cpu.abilist", stdout=subprocess.PIPE).stdout.strip().split(",")
    if args.abi not in abis:
        raise ValueError(f"Device ABIs {abis} do not include {args.abi}")
    remote = "/data/local/tmp/proxydroid-tls-bench-"+uuid.uuid4().hex
    report = {"schema_version": 1, "started_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "serial": args.serial, "abi": args.abi, "server": args.server, "base_port": args.base_port,
              "ca_sha256": hashlib.sha256(args.ca.read_bytes()).hexdigest(),
              "build_type": args.build_type, "parameters": {key: getattr(args, key) for key in
                  ("backends", "rounds", "seconds", "iterations", "warmup", "versions", "ciphers", "concurrency", "auth", "modes", "transport")},
              "scope": ("Native engine on a physical device using " +
                        {"lan": "a controlled LAN echo proxy", "adb-reverse": "ADB reverse for correctness smoke only",
                         "loopback": "an OpenSSL echo proxy on the same device over IPv4 loopback; reference and clients share CPU/thermal capacity"}[args.transport] +
                        "; app routing and old-Android runtime are not exercised"),
              "sources": {name: source_record(CPP / name) for name in ("redsocks", "cntlm", "libevent")},
              "artifacts": {}, "samples": [], "complete": False}
    report["benchmark_sources"] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                                    for path in (Path(__file__).resolve(), CPP / "benchmarks/client.c", CPP / "benchmarks/server.c",
                                                 CPP / "benchmarks/common.h", CPP / "benchmarks/CMakeLists.txt")}
    report["parameters"]["cpu_masks"] = {name: getattr(args, name + "_cpu_mask", None)
                                         for name in ("engine", "client", "reference")}
    reference = None
    try:
        shell(args, "mkdir", "-p", remote)
        adb(args, "push", args.ca.resolve(), f"{remote}/ca.pem", stdout=subprocess.DEVNULL)
        for backend in args.backends:
            print(f"Building {backend}", flush=True)
            report["artifacts"][backend] = prepare(args, backend, remote, sdk)
        if args.transport == "loopback":
            if not getattr(args, "reference_server", None):
                args.reference_server = backend_directory(args, "openssl") / "benchmarks/tls-bench-server"
            reference = DeviceReference(args, remote)
        elif getattr(args, "reference_server", None):
            reference = LocalReference(args)
        if reference:
            report["reference"] = reference.record
            report["parameters"]["single_port"] = True
            shutil.copy2(args.reference_server, args.output / "artifacts/tls-bench-server")
        with (args.output / "samples.jsonl").open("w") as raw:
            for round_index in range(args.rounds):
                order = args.backends[round_index % len(args.backends):] + args.backends[:round_index % len(args.backends)]
                for case in cases(args):
                    if reference:
                        case["port"] = args.base_port
                        reference.select(case)
                    for backend in order:
                        print(f"Round {round_index+1}/{args.rounds}: {backend} {case_key(case)}", flush=True)
                        for attempt in range(3):
                            before = thermal(args)
                            phase = "cooldown"
                            try:
                                for cooldown in range(120 if args.transport == "loopback" else 12):
                                    if before["status"] in (0, None):
                                        break
                                    time.sleep(5)
                                    before = thermal(args)
                                else:
                                    raise RuntimeError("Thermal throttling persisted throughout cooldown")
                                phase = "warmup"
                                invoke(args, backend, case, remote, args.warmup, min(20, args.iterations))
                                before = thermal(args)
                                phase = "measurement"
                                reference_before = reference.usage() if reference else None
                                result = invoke(args, backend, case, remote, args.seconds, args.iterations)
                                reference_after = reference.usage() if reference else None
                            except Exception as error:
                                sample = {"backend": backend, "case": case, "round": round_index+1, "attempt": attempt+1,
                                          "thermal_before": before, "accepted": False, "phase": phase, "error": str(error)}
                                if isinstance(error, SampleError) and error.record is not None:
                                    sample["result"] = error.record
                                raw.write(json.dumps(sample)+"\n"); raw.flush()
                                report["samples"].append(sample)
                                raise
                            after = thermal(args)
                            accepted = before["status"] in (0, None) and after["status"] in (0, None)
                            sample = {"backend": backend, "case": case, "round": round_index+1, "attempt": attempt+1,
                                      "thermal_before": before, "thermal_after": after, "accepted": accepted, "result": result}
                            if reference:
                                sample.update(reference_before=reference_before, reference_after=reference_after)
                            raw.write(json.dumps(sample)+"\n"); raw.flush()
                            report["samples"].append(sample)
                            if accepted:
                                break
                        else:
                            raise RuntimeError("Thermal throttling persisted; rerun after the device cools")
                summarize(args, report)
            report["complete"] = True
    except BaseException as error:
        report["error"] = str(error)
        if not str(error):
            report["error"] = type(error).__name__
        raise
    finally:
        report["ended_at"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        try:
            if reference:
                reference.close()
        finally:
            try:
                summarize(args, report)
            finally:
                shell(args, "rm", "-rf", "--", remote)
    print(args.output / "report.md")
    return 0


def cpu_mask(value):
    try:
        mask = int(value, 16)
    except ValueError as error:
        raise argparse.ArgumentTypeError("CPU mask must be hexadecimal") from error
    if mask <= 0 or mask >= 1 << 64:
        raise argparse.ArgumentTypeError("CPU mask must select 1 to 64 CPU bits")
    return format(mask, "x")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    reference = commands.add_parser("server", help="build and run the controlled reference proxy")
    reference.add_argument("--bind", default="0.0.0.0")
    reference.add_argument("--base-port", type=int, default=9443)
    reference.add_argument("--directory", type=Path, default=ROOT / "build/benchmark-server")
    reference.add_argument("--openssl-root", type=Path)
    reference.add_argument("--prepare-only", action="store_true", help="generate certificates and build the server without opening ports")
    reference.set_defaults(function=server)
    runner = commands.add_parser("run", help="build drivers and measure an explicitly selected device")
    runner.add_argument("--serial", required=True)
    runner.add_argument("--server", required=True, help="routable reference server IPv4 address")
    runner.add_argument("--transport", choices=("lan", "adb-reverse", "loopback"), default="lan",
                        help="separate LAN, USB correctness smoke, and reference-on-device loopback measurements")
    runner.add_argument("--base-port", type=int, default=9443)
    runner.add_argument("--reference-server", type=Path,
                        help="own this reference executable on --base-port; for loopback upload an Android executable (default: build it with openssl); CA directory needs server.pem/server.key")
    runner.add_argument("--ca", type=Path, required=True)
    runner.add_argument("--sdk", type=Path)
    runner.add_argument("--abi", choices=("armeabi-v7a", "arm64-v8a", "x86", "x86_64"), default="arm64-v8a")
    runner.add_argument("--backends", nargs="+", choices=BACKENDS, default=list(BACKENDS))
    runner.add_argument("--build-type", choices=("Release", "RelWithDebInfo"), default="RelWithDebInfo")
    runner.add_argument("--modes", nargs="+", choices=("handshake", "resume", "short", "forward"), default=["handshake", "resume", "short", "forward"])
    runner.add_argument("--versions", nargs="+", choices=("1.2", "1.3"), default=["1.2", "1.3"])
    runner.add_argument("--ciphers", nargs="+", choices=("aes", "chacha"), default=["aes", "chacha"])
    runner.add_argument("--auth", nargs="+", choices=("none", "ntlm"), default=["none", "ntlm"])
    runner.add_argument("--concurrency", type=int, nargs="+", choices=(1, 16, 64), default=[1, 16, 64])
    runner.add_argument("--rounds", type=int, default=5)
    runner.add_argument("--seconds", type=int, default=30)
    runner.add_argument("--iterations", type=int, default=200)
    runner.add_argument("--warmup", type=int, default=10)
    runner.add_argument("--apk-directory", type=Path, help="optional directory containing BACKEND.apk files for size/hash reporting")
    runner.add_argument("--output", type=Path, default=ROOT / "build/tls-evaluation")
    runner.add_argument("--native-directory", type=Path, help="optional reusable native build cache, separate from reports")
    runner.add_argument("--engine-cpu-mask", type=cpu_mask,
                        help="hex affinity mask for the engine child and the isolated TLS handshake/resume harness")
    runner.add_argument("--client-cpu-mask", type=cpu_mask,
                        help="hex affinity mask for the short/forward load generator")
    runner.add_argument("--reference-cpu-mask", type=cpu_mask,
                        help="hex affinity mask for the reference on the same Android device")
    runner.set_defaults(function=benchmark)
    args = parser.parse_args()
    return args.function(args)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        sys.exit(str(error))
