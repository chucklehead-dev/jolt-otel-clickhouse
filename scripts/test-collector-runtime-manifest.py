"""Synthetic contract controls; no compiler, hosted API, or credentials."""
import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("verify-collector-runtime-manifest.py")
SPEC = importlib.util.spec_from_file_location("manifest", SCRIPT)
manifest = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(manifest)
PROFILE = "sorted-collector-7c"
# Independent producer-format fixture, not generated from the verifier's policy.
# Dropping/changing a required claim in that policy must break the positive case.
FIXED_LINES = """schema=1
repository=casselc/jolt
compiler_flavor=plain
compiler_source=7c57cf7e5bca789efe31706d4f9830df8a08306a
compiler_tree=d5dca7ebaba66cf429d2c63219026b8415675dec
workflow_path=.github/workflows/durable-runtime-artifact.yml
runner_os=Linux
runner_arch=X64
chez_version=10.4.1
require_buildlib=1
binary_version=jolt v0.8.17-49-g7c57cf7e
gate=ci-pass
ranged_append_ascii=98
fixed_vector_constructor=pass
fixed_vector_constructor_control=old-rejected
sorted_dispatch_allocation=pass
sorted_dispatch_control=old-rejected
"""
EXPECTED = {"workflow_source": "1" * 40, "run_id": "1", "run_attempt": "1",
            "binary_sha256": "2" * 64}
AUX = {"image_os": "ubuntu24", "image_version": "synthetic",
       "chez_sha256": "3" * 64, "cc_version": "cc synthetic",
       "cc_sha256": "4" * 64,
       "build_command": "make CHEZ=/opt/chez/bin/chez -j2 -Oline ci",
       "chez_configure": "--threads --disable-x11 CFLAGS+=-fPIC"}


def fixture():
    fields = {**EXPECTED, **AUX}
    return (FIXED_LINES + "\n".join(key + "=" + value for key, value in fields.items()) +
            "\nsubmodules_begin\n " + "5" * 40 +
            " lib/cli (synthetic-description)\nsubmodules_end\n").encode()


class ManifestControls(unittest.TestCase):
    def rejected(self, data, profile=PROFILE, expected=None):
        with self.assertRaisesRegex(ValueError, "^invalid collector runtime manifest$"):
            manifest.validate(data, profile, EXPECTED if expected is None else expected)

    def test_complete_current_contract(self):
        self.assertTrue(manifest.validate(fixture(), PROFILE, EXPECTED))

    def test_every_fixed_claim_is_required_and_exact(self):
        for line in FIXED_LINES.splitlines():
            key, value = line.split("=", 1)
            with self.subTest(key=key, mutation="missing"):
                self.rejected(fixture().replace((key + "=" + value + "\n").encode(), b""))
            with self.subTest(key=key, mutation="wrong"):
                self.rejected(fixture().replace((key + "=" + value + "\n").encode(),
                                               (key + "=wrong\n").encode()))

    def test_duplicate_identical_or_conflicting_key(self):
        for line in ("gate=ci-pass", "gate=pass", "compiler_source=" + "0" * 40):
            self.rejected(fixture().replace(b"submodules_begin", (line + "\nsubmodules_begin").encode()))

    def test_provenance_mismatches(self):
        for key in EXPECTED:
            with self.subTest(key=key):
                wrong = dict(EXPECTED)
                wrong[key] = "9" if key in ("run_id", "run_attempt") else "9" * len(wrong[key])
                self.rejected(fixture(), expected=wrong)

    def test_invalid_expected_pins_and_profiles(self):
        for key in EXPECTED:
            for value in ("", "0", "../unexpected", "synthetic-secret-marker"):
                wrong = dict(EXPECTED)
                wrong[key] = value
                self.rejected(fixture(), expected=wrong)
        self.rejected(fixture(), expected={})
        for profile in ("", "latest", "bf8", "plain"):
            self.rejected(fixture(), profile=profile)

    def test_format_and_size(self):
        for data in (fixture()[:-1], fixture().replace(b"\n", b"\r\n"),
                     b"x" * 65537 + b"\n", fixture() + b"unexpected=1\n",
                     fixture().replace(b"gate=ci-pass", b"unknown=ci-pass"),
                     fixture().replace(b"cc_version=cc synthetic", b"cc_version=\x00marker"),
                     fixture().replace(b"cc_version=cc synthetic", b"cc_version=\xff")):
            self.rejected(data)

    def test_submodule_section_is_clean_bounded_and_unambiguous(self):
        for data in (fixture().replace(b"submodules_end\n", b""),
                     fixture().replace(b"submodules_begin\n", b"submodules_begin\nsubmodules_begin\n"),
                     fixture().replace(b" " + b"5" * 40, b"-" + b"5" * 40),
                     fixture().replace(b"lib/cli", b"../cli"),
                     fixture().replace(b"lib/cli", b"/cli"),
                     fixture().replace(b"lib/cli", b"lib//cli"),
                     fixture().replace(b"submodules_end", b" " + b"6" * 40 + b" lib/cli\nsubmodules_end")):
            self.rejected(data)

    def test_cli_has_fixed_credential_free_output(self):
        with tempfile.TemporaryDirectory(prefix="collector-manifest-controls.") as root:
            path = pathlib.Path(root) / "manifest"
            args = [sys.executable, str(SCRIPT), str(path), "--profile", PROFILE]
            for key, value in EXPECTED.items():
                args.extend(["--" + key.replace("_", "-"), value])
            path.write_bytes(fixture())
            good = subprocess.run(args, capture_output=True, timeout=10)
            self.assertEqual(good.returncode, 0)
            self.assertEqual(good.stdout, b"collector-runtime-manifest-pass\n")
            path.write_bytes(fixture().replace(b"gate=ci-pass", b"gate=synthetic-secret-marker"))
            bad = subprocess.run(args, capture_output=True, timeout=10)
            self.assertEqual(bad.returncode, 1)
            self.assertEqual(bad.stdout, b"")
            self.assertEqual(bad.stderr, b"collector-runtime-manifest-rejected\n")
            path.unlink()
            absent = subprocess.run(args, capture_output=True, timeout=10)
            self.assertEqual(absent.returncode, 1)
            self.assertEqual(absent.stderr, b"collector-runtime-manifest-rejected\n")
            malformed = subprocess.run(args + ["--unexpected=synthetic-secret-marker"],
                                       capture_output=True, timeout=10)
            self.assertEqual(malformed.returncode, 2)
            self.assertEqual(malformed.stdout, b"")
            self.assertEqual(malformed.stderr, b"collector-runtime-manifest-rejected\n")


if __name__ == "__main__":
    unittest.main()
