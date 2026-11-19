"""Source identities cover Git dependencies, vendored children and exports."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from native_sources import source_record, source_version


class SourceIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.component = self.root / "vendor"
        self.component.mkdir()
        (self.component / "VENDOR.json").write_text(json.dumps({"upstream_commit": None}))
        (self.component / "source.c").write_text("int value;\n")
        (self.root / ".gitignore").write_text("build/\n")
        self.git("init", "-q")
        self.git("add", ".")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "fixture")

    def git(self, *args, directory=None):
        return subprocess.check_output(["git", "-C", str(directory or self.root), *args], stderr=subprocess.PIPE)

    def initialize_component_repository(self):
        self.git("init", "-q", directory=self.component)
        self.git("add", ".", directory=self.component)
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "component",
                 directory=self.component)

    def test_component_repository_records_its_commit_and_local_changes(self):
        self.initialize_component_repository()
        before = source_record(self.component)
        self.assertEqual(before["kind"], "git")
        self.assertEqual(before["commit"], self.git("rev-parse", "HEAD", directory=self.component).decode().strip())
        self.assertNotEqual(before["commit"], self.git("rev-parse", "HEAD").decode().strip())
        self.assertFalse(before["dirty"])
        (self.root / "unrelated.txt").write_text("dirty parent\n")
        self.assertEqual(before, source_record(self.component))
        (self.component / "source.c").write_text("int changed;\n")
        after = source_record(self.component)
        self.assertTrue(after["dirty"])
        self.assertNotEqual(before["worktree_sha256"], after["worktree_sha256"])

    def test_git_version_keeps_dependency_markers(self):
        self.initialize_component_repository()
        for name in ("cntlm", "cntlm/duktape", "libevent"):
            directory = self.root / name
            directory.mkdir(parents=True, exist_ok=True)
            (directory / "source.c").write_text("int dependency;\n")
        components = {"redsocks": self.component, "cntlm": self.root / "cntlm",
                      "duktape": self.root / "cntlm/duktape", "libevent": self.root / "libevent"}
        version = source_version(self.component, with_dependencies=True)
        self.assertTrue(version.startswith("git/"))
        for name, directory in components.items():
            self.assertIn(name + "=" + source_record(directory)["worktree_sha256"], version)

    def test_parent_changes_do_not_change_component_identity(self):
        before = source_record(self.component)
        (self.root / "unrelated.txt").write_text("dirty parent\n")
        self.assertEqual(before, source_record(self.component))
        self.assertEqual(before["kind"], "vendored")
        self.assertIsNone(before["upstream_commit"])
        self.assertNotIn("commit", before)
        self.assertFalse(before["dirty"])

    def test_effective_sources_modes_links_and_ignored_builds(self):
        before = source_record(self.component)["worktree_sha256"]
        build = self.component / "build"
        build.mkdir()
        (build / "artifact.o").write_bytes(b"ignored")
        self.assertEqual(before, source_record(self.component)["worktree_sha256"])
        source = self.component / "source.c"
        source.chmod(0o755)
        executable = source_record(self.component)["worktree_sha256"]
        self.assertNotEqual(before, executable)
        (self.component / "link").symlink_to("source.c")
        self.assertNotEqual(executable, source_record(self.component)["worktree_sha256"])
        source.unlink()
        self.assertTrue(source_record(self.component)["dirty"])

    def test_source_archive_without_git(self):
        archive = self.root / "export"
        archive.mkdir()
        (archive / "VENDOR.json").write_text(json.dumps({"upstream_commit": "a" * 40}))
        # A standalone export outside a Git worktree still records its files.
        with tempfile.TemporaryDirectory() as outside:
            path = Path(outside)
            (path / "VENDOR.json").write_bytes((archive / "VENDOR.json").read_bytes())
            record = source_record(path)
            self.assertEqual(record["upstream_commit"], "a" * 40)
            self.assertIsNone(record["dirty"])
            self.assertEqual(len(record["worktree_sha256"]), 64)

    def test_export_inside_an_ignored_parent_keeps_its_content_identity(self):
        tests = self.component / "tests"
        tests.mkdir()
        (tests / "build").write_text("#!/bin/sh\nexit 0\n")
        self.git("add", "vendor/tests/build")
        archive = self.root / "build" / "export"
        shutil.copytree(self.component, archive)
        before, after = source_record(self.component), source_record(archive)
        self.assertEqual(before["worktree_sha256"], after["worktree_sha256"])
        self.assertIsNone(after["dirty"])


if __name__ == "__main__":
    unittest.main()
