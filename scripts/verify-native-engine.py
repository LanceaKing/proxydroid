#!/usr/bin/env python3
"""Verify APK shared engines and record the current local source snapshot."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
ABIS = {"armeabi-v7a": (40, 19), "arm64-v8a": (183, 21),
        "x86": (3, 19), "x86_64": (62, 21)}
JNI = {"Java_org_proxydroid_engine_NativeEngine_" + name for name in (
    "nativeCreate", "nativeStart", "nativeStop", "nativeDestroy", "nativeStatus",
    "nativeDiagnostics", "nativeBuildInfo", "nativeHashes", "nativeDetect")}
API = {"redsocks_engine_" + name for name in (
    "create", "start", "request_stop", "join", "destroy", "get_status",
    "validate", "diagnostics", "hashes", "detect", "build_info")}


def output(command, cwd=None):
    return subprocess.check_output(command, cwd=cwd, text=True, stderr=subprocess.PIPE)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def find_readelf(explicit):
    if explicit:
        # LLVM selects its output format from argv[0]; keep the llvm-readelf alias.
        return str(Path(explicit).absolute())
    found = shutil.which("llvm-readelf")
    if found:
        return found
    ndks = [Path(os.environ[key]) for key in ("ANDROID_NDK_ROOT", "ANDROID_NDK_HOME")
            if os.environ.get(key)]
    sdks = [Path(os.environ[key]) for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME")
            if os.environ.get(key)]
    properties = ROOT / "local.properties"
    if properties.exists():
        match = re.search(r"^sdk\.dir=(.+)$", properties.read_text(), re.MULTILINE)
        if match:
            sdks.append(Path(match[1].strip()))
    for sdk in sdks:
        ndks.extend(sorted((sdk / "ndk").glob("*"), reverse=True))
    for ndk in ndks:
        for candidate in ndk.glob("toolchains/llvm/prebuilt/*/bin/llvm-readelf"):
            if candidate.is_file():
                return str(candidate)
    raise ValueError("llvm-readelf unavailable; supply --readelf from your Android NDK")


def source_record(directory):
    def git(*args):
        return output(["git", *args], cwd=directory).strip()
    # Include untracked implementation files and deletions, but no ignored builds.
    names = output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
                   cwd=directory).split("\0")
    digest = hashlib.sha256()
    for name in sorted(set(filter(None, names))):
        path = directory / name
        if path.is_dir():  # Nested repositories have their own records below.
            continue
        digest.update(name.encode() + b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest() if path.exists() else b"deleted")
    return {"commit": git("rev-parse", "HEAD"),
            "branch": git("branch", "--show-current"),
            "dirty": bool(git("status", "--porcelain", "--untracked-files=all")),
            "worktree_sha256": digest.hexdigest()}


def elf_notes(data):
    is32 = data[4] == 1
    shoff = struct.unpack_from("<I" if is32 else "<Q", data, 32 if is32 else 40)[0]
    entry_size, count = struct.unpack_from("<HH", data, 46 if is32 else 58)
    notes = {}
    for index in range(count):
        section = shoff + index * entry_size
        if struct.unpack_from("<I", data, section + 4)[0] != 7:  # SHT_NOTE
            continue
        offset, length = struct.unpack_from("<II" if is32 else "<QQ", data, section + (16 if is32 else 24))
        end = offset + length
        require(end <= len(data), "ELF note extends outside library")
        while offset + 12 <= end:
            name_length, value_length, kind = struct.unpack_from("<III", data, offset)
            offset += 12
            name = data[offset:offset + name_length].rstrip(b"\0")
            offset += (name_length + 3) & ~3
            require(offset + value_length <= end, "Truncated ELF note")
            notes[(name, kind)] = data[offset:offset + value_length]
            offset += (value_length + 3) & ~3
    require((b"Android", 1) in notes and (b"GNU", 3) in notes, "Android identity/build-ID notes missing")
    return notes[(b"Android", 1)], notes[(b"GNU", 3)].hex()


def verify(apk, readelf):
    redsocks = ROOT / "app/src/main/cpp/redsocks"
    cntlm_version = (redsocks / "cntlm/VERSION").read_text().strip()
    openssl_version = re.search(r"set\(PROXYDROID_OPENSSL_VERSION ([\d.]+)\)",
                              (ROOT / "app/src/main/cpp/openssl.cmake").read_text())[1]
    libraries = {}
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(prefix="pdroid-elf-") as temporary:
        names = archive.namelist()
        legacy = [name for name in names if re.fullmatch(
            r"assets/(?:[^/]+/)?(?:redsocks|cntlm|gost|proxy\.sh|gost\.sh|redsocks-build\.json)", name)]
        require(not legacy, "Legacy engine assets remain: " + ", ".join(legacy))
        packaged = {name.split("/")[1] for name in names
                    if re.fullmatch(r"lib/[^/]+/libredsocks\.so", name)}
        require(packaged == set(ABIS), "Shared-engine ABIs differ: " + repr(packaged))
        for abi, (machine, minimum) in ABIS.items():
            member = "lib/" + abi + "/libredsocks.so"
            data = archive.read(member)
            require(data[:4] == b"\x7fELF" and data[5] == 1, member + ": expected little-endian ELF")
            require(struct.unpack_from("<HH", data, 16) == (3, machine), member + ": ELF type/machine mismatch")
            entry = struct.unpack_from("<I" if data[4] == 1 else "<Q", data, 24)[0]
            require(entry == 0, member + ": unexpected executable entry point")
            path = Path(temporary) / (abi + ".so")
            path.write_bytes(data)
            dynamic = output([readelf, "-dW", str(path)])
            symbols = output([readelf, "--dyn-syms", "--wide", str(path)])
            headers = output([readelf, "-lW", str(path)])
            needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
            require("libc.so" in needed and set(needed) <= {"libc.so", "libm.so", "libdl.so", "liblog.so"},
                    member + ": unexpected runtime dependencies " + repr(needed))
            require("TEXTREL" not in dynamic, member + ": text relocations present")
            require(re.findall(r"\(SONAME\).*?\[(.*?)\]", dynamic) == ["libredsocks.so"], member + ": SONAME mismatch")
            exported = {line.split()[-1].split("@")[0] for line in symbols.splitlines()
                        if " GLOBAL " in line and " UND " not in line}
            require((JNI | API) <= exported, member + ": missing JNI/engine exports " + repr((JNI | API) - exported))
            require("main" not in exported, member + ": standalone main was linked")
            for line in symbols.splitlines():
                if " UND " in line:
                    require(line.split()[-1].split("@")[0] not in {"getifaddrs", "freeifaddrs", "getrandom"},
                            member + ": unavailable minimum-API import " + line.strip())
            alignments = [int(line.split()[-1], 16) for line in headers.splitlines()
                          if line.strip().startswith("LOAD ")]
            require(alignments and min(alignments) >= 16384, member + ": load alignment below 16 KiB")
            android, build_id = elf_notes(data)
            android_api = struct.unpack_from("<I", android)[0]
            require(android_api == minimum, member + ": Android API mismatch " + str(android_api))
            require(("OpenSSL " + openssl_version).encode() in data, member + ": OpenSSL version mismatch")
            require(("redsocks shared engine; cntlm " + cntlm_version).encode() in data,
                    member + ": cntlm build identity mismatch")
            libraries[abi] = {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
                              "android_api": android_api, "ndk": android[4:68].split(b"\0")[0].decode(),
                              "needed": needed, "jni_exports": len(JNI),
                              "build_id": build_id}
    return {"verified": True, "apk": str(apk), "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "openssl": openssl_version, "cntlm": cntlm_version,
            "sources": {"proxydroid": source_record(ROOT), "redsocks": source_record(redsocks),
                        "cntlm": source_record(redsocks / "cntlm")}, "libraries": libraries,
            "scope": "APK/ELF checks and source snapshot at verification time; device behavior is not exercised"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="?", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--readelf", help="path to the Android NDK llvm-readelf binary")
    parser.add_argument("--output", type=Path, help="write the JSON report to this path")
    args = parser.parse_args()
    try:
        report = json.dumps(verify(args.apk.resolve(), find_readelf(args.readelf)), indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(report)
        else:
            print(report, end="")
    except (OSError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print("Native engine verification failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
