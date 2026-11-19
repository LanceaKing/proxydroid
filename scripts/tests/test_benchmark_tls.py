"""Prevent limited or unhealthy measurements from becoming a formal comparison."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
spec = importlib.util.spec_from_file_location("benchmark_tls", Path(__file__).resolve().parents[1] / "benchmark-tls.py")
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


class BenchmarkReportTest(unittest.TestCase):
    def test_failed_run_retains_the_sample_and_cleans_the_device_directory(self):
        for phase in ("warmup", "measurement"):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                ca = directory / "ca.pem"
                ca.write_text("fixture")
                args = argparse.Namespace(
                    output=directory / "run", backends=["openssl"], versions=["1.2"],
                    ciphers=["aes"], auth=["none"], concurrency=[1], modes=["forward"],
                    base_port=9443, rounds=1, seconds=1, iterations=1, warmup=1,
                    transport="adb-reverse", server="127.0.0.1", ca=ca,
                    serial="fixture", abi="arm64-v8a", build_type="RelWithDebInfo", sdk=None)
                invalid = {"valid": False, "errors": 2, "sent_bytes": 100, "received_bytes": 50}
                failure = benchmark.SampleError("incomplete stream", invalid)
                shell = mock.Mock(return_value=argparse.Namespace(stdout="arm64-v8a"))
                responses = [failure] if phase == "warmup" else [{"valid": True}, failure]
                with mock.patch.multiple(
                    benchmark, sdk_path=mock.Mock(return_value=directory), shell=shell,
                    adb=mock.Mock(), prepare=mock.Mock(return_value={}),
                    thermal=mock.Mock(return_value={"status": 0}), source_record=mock.Mock(return_value={}),
                    invoke=mock.Mock(side_effect=responses)
                ):
                    with self.assertRaisesRegex(benchmark.SampleError, "incomplete stream"):
                        benchmark.benchmark(args)
                report = json.loads((args.output / "report.json").read_text())
                self.assertFalse(report["complete"])
                self.assertFalse(report["formal"])
                self.assertEqual(report["error"], "incomplete stream")
                self.assertEqual(len(report["samples"]), 1)
                sample = report["samples"][0]
                self.assertFalse(sample["accepted"])
                self.assertEqual(sample["phase"], phase)
                self.assertEqual(sample["result"], invalid)
                self.assertEqual(json.loads((args.output / "samples.jsonl").read_text()), sample)
                self.assertEqual(shell.call_args.args[1:4], ("rm", "-rf", "--"))
                self.assertTrue(shell.call_args.args[-1].startswith("/data/local/tmp/proxydroid-tls-bench-"))

    def test_controlled_status_requires_complete_healthy_measurements(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = argparse.Namespace(
                output=Path(temporary), backends=["openssl"],
                versions=["1.2", "1.3"], ciphers=["aes", "chacha"], auth=["none", "ntlm"],
                concurrency=[1, 16, 64], modes=["handshake", "resume", "short", "forward"],
                base_port=9443, rounds=5, seconds=30, iterations=200, warmup=10, transport="lan")
            metric = {name: 1 for name in ("p50_ms", "p95_ms", "mbps_bidirectional", "cpu_seconds", "cpu_seconds_per_gib", "peak_rss_kib")}
            samples = [{"backend": backend, "case": case, "accepted": True,
                        "thermal_before": {"status": 0}, "thermal_after": {"status": 0}, "result": metric}
                       for _ in range(5) for backend in args.backends for case in benchmark.cases(args)]
            baseline = {"scope": "fixture", "samples": samples, "complete": True}
            benchmark.summarize(args, baseline)
            self.assertTrue(baseline["controlled_lan"])
            self.assertFalse(baseline["formal"])  # One supported backend is not a comparison.
            scenarios = ("usb_tunnel", "device_loopback", "unknown_temperature", "incomplete", "missing_round")
            for scenario in scenarios:
                with self.subTest(scenario=scenario):
                    current_args, report = copy.deepcopy(args), copy.deepcopy(baseline)
                    if scenario == "usb_tunnel":
                        current_args.transport = "adb-reverse"
                    elif scenario == "device_loopback":
                        current_args.transport = "loopback"
                        report["reference"] = {"location": "selected Android device, IPv4 loopback"}
                    elif scenario == "unknown_temperature":
                        report["samples"][0]["thermal_after"]["status"] = None
                    elif scenario == "incomplete":
                        report["complete"] = False
                    elif scenario == "missing_round":
                        report["samples"].pop()
                    benchmark.summarize(current_args, report)
                    self.assertFalse(report["formal"])
                    self.assertFalse(report["controlled_lan"])
                    self.assertEqual(report["controlled_loopback"], scenario == "device_loopback")
                    if scenario == "device_loopback":
                        del report["reference"]
                        benchmark.summarize(current_args, report)
                        self.assertFalse(report["controlled_loopback"])

    def test_process_timeout_retains_partial_output_and_the_exact_command(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = argparse.Namespace(output=Path(temporary), serial="fixture", server="127.0.0.1")
            case = {"mode": "forward", "version": "1.2", "cipher": "aes", "auth": "none", "concurrency": 64, "port": 8888}
            failure = subprocess.TimeoutExpired("adb", 60, output=b"partial JSON", stderr=b"full diagnostic\n")
            with mock.patch.object(benchmark.subprocess, "run", side_effect=failure):
                with self.assertRaisesRegex(benchmark.SampleError, "process timeout"):
                    benchmark.invoke(args, "openssl", case, "/fixture", 1, 1)
            directory = args.output / "invocations"
            self.assertEqual(next(directory.glob("*.stdout")).read_text(), "partial JSON")
            self.assertEqual(next(directory.glob("*.stderr")).read_text(), "full diagnostic\n")
            record = json.loads(next(directory.glob("*.json")).read_text())
            self.assertTrue(record["timed_out"])
            self.assertIsNone(record["exit_code"])
            self.assertEqual(record["command"][3], "/fixture/openssl/tls-benchmark")


if __name__ == "__main__":
    unittest.main()
