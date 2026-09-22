import importlib.util
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location("launcher", Path(__file__).with_name("qualify-untyped-abba.py"))
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)

class Contracts(unittest.TestCase):
    def test_same_sources_dependencies_and_candidate_pin(self):
        self.assertEqual(launcher.deps("A"), launcher.deps("B"))
        self.assertIn("8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2", str(launcher.OTEL))
        self.assertEqual(launcher.B, "b492e8811e575f156e7b5c1ae383ba81e1a3bdd3")

    def test_parallel_launcher_is_rejected(self):
        with tempfile.TemporaryDirectory() as parent:
            path = Path(parent) / "lock"
            with launcher.exclusive_lock(path):
                with self.assertRaises(BlockingIOError):
                    launcher.exclusive_lock(path)
            with launcher.exclusive_lock(path):
                pass

    def test_fresh_scratch_and_reuse_rejected(self):
        with tempfile.TemporaryDirectory() as parent:
            root = launcher.prepare(Path(parent) / "evidence")
            for i, arm in enumerate(launcher.ORDER):
                for name in ("objects", "scratch-writer", "scratch-reader", "cache-writer", "cache-reader"):
                    self.assertTrue((root / f"{i+1}-{arm}" / name).is_dir())
            with self.assertRaises(RuntimeError):
                launcher.prepare(root)

    def test_existing_file_is_not_destroyed(self):
        with tempfile.TemporaryDirectory() as parent:
            marker = Path(parent) / "keep"
            marker.write_text("owned")
            with self.assertRaises(RuntimeError):
                launcher.prepare(parent)
            self.assertEqual(marker.read_text(), "owned")

    def test_serial_order_and_terminal_reports(self):
        with tempfile.TemporaryDirectory() as parent:
            root = launcher.prepare(Path(parent) / "evidence")
            calls = []
            def fake(cmd, log, env):
                phase, cell = cmd[-2:]
                calls.append((Path(cell).name, phase))
                self.assertTrue((Path(cell) / "scratch-writer").is_dir())
                self.assertTrue((Path(cell) / "scratch-reader").is_dir())
                (Path(cell) / f"{phase}-report.edn").write_text("{}")
                if phase == "reader":
                    (Path(cell) / "reader.expanded-digest").write_text("a" * 64)
            with patch.object(launcher, "environment", return_value={}):
                launcher.execute(root, fake)
            self.assertEqual(calls, [(f"{i+1}-{a}", p) for i, a in enumerate(launcher.ORDER) for p in ("writer", "reader")])

    def test_failure_never_retries_or_starts_next_child(self):
        with tempfile.TemporaryDirectory() as parent:
            root = launcher.prepare(Path(parent) / "evidence")
            calls = []
            def fail(cmd, log, env):
                calls.append(cmd)
                raise RuntimeError("deliberate")
            with patch.object(launcher, "environment", return_value={}):
                with self.assertRaises(RuntimeError):
                    launcher.execute(root, fail)
            self.assertEqual(len(calls), 1)

    def test_success_without_report_rejected(self):
        with tempfile.TemporaryDirectory() as parent:
            root = launcher.prepare(Path(parent) / "evidence")
            with patch.object(launcher, "environment", return_value={}):
                with self.assertRaises(RuntimeError):
                    launcher.execute(root, lambda *args: None)

    def test_only_exporter_source_changes(self):
        self.assertEqual(launcher.deps("A").replace(str(launcher.ROOTS["A"] / "src"), "<EXPORTER>"),
                         launcher.deps("B").replace(str(launcher.ROOTS["B"] / "src"), "<EXPORTER>"))

    def test_expanded_digest_mismatch_stops_next_cell(self):
        with tempfile.TemporaryDirectory() as parent:
            root = launcher.prepare(Path(parent) / "evidence")
            calls = []
            def fake(cmd, log, env):
                phase, cell = cmd[-2:]
                calls.append((Path(cell).name, phase))
                (Path(cell) / f"{phase}-report.edn").write_text("{}")
                if phase == "reader":
                    (Path(cell) / "reader.expanded-digest").write_text(("a" if Path(cell).name == "1-A" else "b") * 64)
            with patch.object(launcher, "environment", return_value={}):
                with self.assertRaises(RuntimeError):
                    launcher.execute(root, fake)
            self.assertEqual(len(calls), 4)

if __name__ == "__main__":
    unittest.main()
