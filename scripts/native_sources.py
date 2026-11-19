#!/usr/bin/env python3
"""Directory-scoped source identities, also usable from the native build."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def git(directory, *args):
    return subprocess.check_output(["git", "-C", str(directory), *args], stderr=subprocess.PIPE)


def source_record(directory):
    directory = Path(directory).resolve()
    vendor = directory / "VENDOR.json"
    provenance = json.loads(vendor.read_text()) if vendor.is_file() else None
    repository_root = None
    try:
        names = git(directory, "ls-files", "--cached", "--others", "--exclude-standard", "-z").decode().split("\0")
        if not any(names):
            # An exported source tree can sit below another repository's
            # ignored build directory. That repository does not own it.
            raise FileNotFoundError("No files in the enclosing Git worktree")
        dirty = bool(git(directory, "status", "--porcelain", "--untracked-files=all", "--", ".").strip())
        commit = git(directory, "rev-parse", "HEAD").decode().strip()
        branch = git(directory, "branch", "--show-current").decode().strip()
        repository_root = Path(git(directory, "rev-parse", "--show-toplevel").decode().strip()).resolve()
    except (subprocess.CalledProcessError, FileNotFoundError):
        # Source archives do not require a .git directory to build.
        names = [str(p.relative_to(directory)) for p in directory.rglob("*")
                 if ".git" not in p.relative_to(directory).parts and
                 not any(part in {"build", ".cxx", "__pycache__"}
                         for part in p.relative_to(directory).parts[:-1])]
        dirty, commit, branch = None, None, None
    digest = hashlib.sha256()
    for name in sorted(set(filter(None, names))):
        path = directory / name
        if path.is_symlink():
            kind, data = b"link", os.fsencode(os.readlink(path))
        elif path.is_file():
            kind, data = (b"executable" if path.stat().st_mode & 0o111 else b"file"), path.read_bytes()
        else:
            continue  # Deleted sources and directories are not build inputs.
        digest.update(os.fsencode(name) + b"\0" + kind + b"\0" + hashlib.sha256(data).digest())
    own_repository = repository_root == directory
    record = {"kind": "vendored" if provenance and not own_repository else "git", "dirty": dirty,
              "worktree_sha256": digest.hexdigest()}
    if provenance:
        record.update(upstream_commit=provenance.get("upstream_commit"), provenance=provenance)
    if own_repository or not provenance:
        record.update(commit=commit, branch=branch)
    return record


def source_version(directory, with_dependencies=False):
    directory = Path(directory)
    record = source_record(directory)
    if record["kind"] == "vendored":
        base = record["upstream_commit"] or "unverified"
        version = "vendor/" + base[:12] + "+src." + record["worktree_sha256"][:12]
    else:
        version = "git/" + (record["commit"] or "archive")[:12] + ("-dirty" if record["dirty"] else "")
    if with_dependencies:
        cpp = directory.resolve().parent
        components = {"redsocks": directory, "cntlm": cpp / "cntlm",
                      "libevent": cpp / "libevent", "duktape": cpp / "cntlm/duktape"}
        version += "; sources " + " ".join(name+"="+source_record(path)["worktree_sha256"]
                                            for name, path in components.items())
    return version


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--with-dependencies", action="store_true")
    args = parser.parse_args()
    print(source_version(args.directory, args.with_dependencies))
