#!/usr/bin/env python3
"""Cache checksum-locked TLS archives for native builds and offline use."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "app/src/main/cpp/cmake/dependencies.lock.json"


def fetch(backend, directory):
    dependency = json.loads(LOCK.read_text())[backend]
    directory.mkdir(parents=True, exist_ok=True)
    name = dependency["revision"]
    if not name.startswith(backend + "-"):
        name = backend + "-" + name
    archive = directory / (name + ".tar.gz")
    if not archive.exists():
        partial = archive.with_suffix(".part")
        try:
            with urllib.request.urlopen(dependency["url"], timeout=120) as response, partial.open("wb") as target:
                while block := response.read(1024 * 1024):
                    target.write(block)
            if hashlib.sha256(partial.read_bytes()).hexdigest() != dependency["sha256"]:
                raise ValueError(f"{backend}: archive SHA-256 mismatch")
            partial.replace(archive)
        finally:
            partial.unlink(missing_ok=True)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != dependency["sha256"]:
        raise ValueError(f"{archive}: archive SHA-256 mismatch; remove the damaged cache file")
    return archive.resolve()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("backend", choices=("openssl", "all"))
    parser.add_argument("--directory", type=Path, default=ROOT / "build/tls-archives")
    args = parser.parse_args()
    backends = ("openssl",) if args.backend == "all" else (args.backend,)
    for backend in backends:
        print(fetch(backend, args.directory))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError) as error:
        sys.exit(str(error))
