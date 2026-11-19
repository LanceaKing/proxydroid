"""Reject TLS selections and local archives that cannot match pinned libevent."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

CPP = Path(__file__).resolve().parents[2] / "app/src/main/cpp"
CMAKE = shutil.which("cmake")


@unittest.skipUnless(CMAKE, "CMake is required for native configuration checks")
class TlsConfigurationTest(unittest.TestCase):
    def configure_tls(self, directory, *arguments):
        source = directory / "source"
        source.mkdir()
        (source / "CMakeLists.txt").write_text(
            'cmake_minimum_required(VERSION 3.22.1)\nproject(tls_selection C)\n'
            f'include("{CPP.as_posix()}/cmake/tls.cmake")\n')
        return subprocess.run([CMAKE, "-S", str(source), "-B", str(directory / "build"), *arguments],
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def test_unsupported_and_conflicting_selections_fail_configuration(self):
        cases = [
            ([f"-DTLS_BACKEND={name}"], "not supported by libevent")
            for name in ("boringssl", "wolfssl", "mbedtls", "unknown")
        ] + [
            (["-DEVENT__DISABLE_OPENSSL=ON"], "conflicts with EVENT__DISABLE_OPENSSL"),
            (["-DEVENT__DISABLE_MBEDTLS=OFF"], "has no mbedTLS backend"),
            (["-DPROXYDROID_TLS_BACKEND=openssl"], "was removed"),
        ]
        for arguments, message in cases:
            with self.subTest(arguments=arguments), tempfile.TemporaryDirectory() as temporary:
                result = self.configure_tls(Path(temporary), *arguments)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn(message, result.stdout)

    def test_missing_and_corrupt_archives_fail_configuration(self):
        for exists in (False, True):
            with self.subTest(exists=exists), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                archive = directory / "an archive.tar.gz"
                if exists:
                    archive.write_bytes(b"wrong upstream archive")
                result = self.configure_tls(directory, "-DPROXYDROID_TLS_ARCHIVE=" + archive.as_uri())
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn("SHA-256 mismatch" if exists else "Missing TLS archive", result.stdout)

    def test_missing_submodule_fails_before_dependency_discovery(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            source = directory / "source"
            source.mkdir()
            shutil.copyfile(CPP / "CMakeLists.txt", source / "CMakeLists.txt")
            result = subprocess.run([CMAKE, "-S", str(source), "-B", str(directory / "build")],
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertIn("Missing cntlm submodule", result.stdout)


if __name__ == "__main__":
    unittest.main()
