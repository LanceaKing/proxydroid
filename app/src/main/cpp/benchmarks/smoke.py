"""Exercise real TLS/resumption and engine echo/NTLM without external services."""
import argparse
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time


class PacedProxy:
    """Limit aggregate upstream traffic without changing the TLS endpoints."""
    def __init__(self, target, bytes_per_second=8*1024*1024):
        self.target, self.rate = target, bytes_per_second
        self.socket = socket.socket()
        self.socket.bind(('127.0.0.1', 0))
        self.socket.listen(128)
        self.socket.settimeout(.1)
        self.port = self.socket.getsockname()[1]
        self.stopping = threading.Event()
        self.lock = threading.Lock()
        self.next_send = time.monotonic()
        self.peers, self.threads, self.errors = [], [], []
        self.acceptor = threading.Thread(target=self.accept, daemon=True)
        self.acceptor.start()

    def transfer(self, source, destination, paced):
        try:
            while not self.stopping.is_set():
                data = source.recv(16384)
                if not data:
                    destination.shutdown(socket.SHUT_WR)
                    return
                if paced:
                    with self.lock:
                        slot = max(time.monotonic(), self.next_send)
                        self.next_send = slot+len(data)/self.rate
                    if self.stopping.wait(max(0, slot-time.monotonic())):
                        return
                destination.sendall(data)
        except OSError as error:
            if not self.stopping.is_set():
                self.errors.append(error)

    def accept(self):
        while not self.stopping.is_set():
            try:
                client, _ = self.socket.accept()
            except socket.timeout:
                continue
            except OSError:
                if not self.stopping.is_set():
                    raise
                return
            peer = socket.create_connection(('127.0.0.1', self.target), timeout=10)
            client.settimeout(10)
            self.peers.extend((client, peer))
            for source, destination, paced in ((client, peer, True), (peer, client, False)):
                worker = threading.Thread(target=self.transfer, args=(source, destination, paced), daemon=True)
                self.threads.append(worker)
                worker.start()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.stopping.set()
        self.socket.close()
        self.acceptor.join(2)
        for peer in self.peers:
            try:
                peer.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            peer.close()
        until = time.monotonic()+3
        for worker in self.threads:
            worker.join(max(0, until-time.monotonic()))
        assert not self.acceptor.is_alive() and not any(worker.is_alive() for worker in self.threads)
        assert not self.errors, self.errors


def free_ports():
    for base in range(19443, 29443, 8):
        reservations = []
        try:
            for port in range(base, base+8):
                sock = socket.socket()
                reservations.append(sock)
                sock.bind(("127.0.0.1", port))
            return base
        except OSError:
            pass
        finally:
            for sock in reservations:
                sock.close()
    raise RuntimeError("No free reference port range")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="pdroid-bench-test-") as temporary:
        root = Path(temporary)
        def openssl(*arguments):
            subprocess.run(["openssl", *arguments], cwd=root, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=Benchmark CA", "-keyout", "ca.key", "-out", "ca.pem")
        openssl("req", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=bench.proxy", "-keyout", "server.key", "-out", "server.csr")
        (root / "extensions").write_text("subjectAltName=DNS:bench.proxy\nextendedKeyUsage=serverAuth\n")
        openssl("x509", "-req", "-in", "server.csr", "-CA", "ca.pem", "-CAkey", "ca.key", "-CAcreateserial", "-days", "1", "-extfile", "extensions", "-out", "server.pem")
        base = free_ports()
        with (root / "server.log").open("w+") as log:
            peer = subprocess.Popen([str(args.server), "127.0.0.1", str(base), str(root / "server.pem"), str(root / "server.key")], stdout=subprocess.PIPE, stderr=log, text=True)
            try:
                ready = json.loads(peer.stdout.readline())
                assert ready["ready"]
                for version in ("1.2", "1.3"):
                    for cipher in range(2):
                        port = base + (int(version == "1.3")*2+cipher)*2
                        for mode in ("handshake", "resume", "short", "forward"):
                            for auth in (("none",) if mode in ("handshake", "resume") else ("none", "ntlm")):
                                command = [str(args.client), mode, "127.0.0.1", str(port+int(auth=="ntlm")), str(root / "ca.pem"), version, "1", "1", "3", auth]
                                print(f"Checking {version} cipher={cipher} {mode} {auth}", flush=True)
                                result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=25)
                                assert result.returncode == 0, (command, result.stdout, result.stderr)
                                sample = json.loads(result.stdout)
                                assert sample["valid"] and sample["errors"] == 0, sample
                                if mode == "resume":
                                    assert sample["reused"] == 3, sample
                                if mode == "forward":
                                    assert sample["sent_bytes"] == sample["received_bytes"] > 0, sample
                # A short, saturated transport must drain within the existing
                # I/O deadline rather than queue hundreds of MiB on localhost.
                with PacedProxy(base+5) as paced:
                    command = [str(args.client), 'forward', '127.0.0.1', str(paced.port),
                               str(root / 'ca.pem'), '1.3', '64', '1', '3', 'ntlm']
                    result = subprocess.run(command, text=True, capture_output=True, timeout=15)
                    assert result.returncode == 0, (command, result.stdout, result.stderr)
                    sample = json.loads(result.stdout)
                    assert sample['valid'] and sample['errors'] == 0 and sample['requests'] == 64, sample
                    assert sample['sent_bytes'] == sample['received_bytes'] > 0, sample
                    assert sample['seconds'] < 10, sample
                    assert 0 < sample['peak_inflight_bytes'] <= sample['stream_window_bytes'], sample
            finally:
                peer.terminate()
                try:
                    peer.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    peer.kill(); peer.wait()
                if peer.returncode not in (-15, 0):
                    log.seek(0)
                    print(log.read())


if __name__ == "__main__":
    main()
