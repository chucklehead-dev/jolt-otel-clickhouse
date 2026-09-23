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

current_spec = importlib.util.spec_from_file_location(
    "current_launcher", Path(__file__).with_name("qualify-current-untyped-phase.py"))
current_launcher = importlib.util.module_from_spec(current_spec)
current_spec.loader.exec_module(current_launcher)

class Contracts(unittest.TestCase):
    def test_actual_public_path_and_activation_witness(self):
        source = (launcher.REPO / "bench/otel/exporter/scalar_abba.clj").read_text()
        self.assertIn("op #(export/export-spans! writer spans)", source)
        self.assertNotIn("prototype/export-spans!", source)
        self.assertNotIn("otel.exporter.untyped-encoder", source)
        self.assertIn("production encoder activation", source)
        self.assertIn(":production-encoder-witnessed?", source)

    def test_phase_receipts_are_opt_in_and_aggregate_only(self):
        source = (launcher.REPO / "bench/otel/exporter/scalar_abba.clj").read_text()
        self.assertIn("BENCH_PHASE_RECEIPTS", source)
        self.assertIn("exporter/durable-phase-receipts", source)
        self.assertIn(":durable-phase-receipts phase-receipts", source)
        self.assertIn(":phase-receipts @phase-receipts", source)
        self.assertIn("reset! phase-receipts receipt-baseline", source)
        self.assertNotIn(":payload phase-receipts", source)

    def test_phase_receipt_environment_is_absent_by_default(self):
        with patch.dict("os.environ", {"BENCH_PHASE_RECEIPTS": "stale"}, clear=True):
            ordinary = launcher.environment("A", Path("/tmp/cell"), "writer")
            profiled = launcher.environment("A", Path("/tmp/cell"), "writer", True)
            reader = launcher.environment("A", Path("/tmp/cell"), "reader", True)
        self.assertNotIn("BENCH_PHASE_RECEIPTS", ordinary)
        self.assertEqual("1", profiled["BENCH_PHASE_RECEIPTS"])
        self.assertNotIn("BENCH_PHASE_RECEIPTS", reader)

    def test_current_phase_launcher_is_one_arm_and_non_comparative(self):
        source = Path(current_launcher.__file__).read_text()
        self.assertIn('BENCH_ARM="CURRENT"', source)
        self.assertIn('"comparative": False', source)
        self.assertIn("one-arm-current-phase-profile", source)
        self.assertIn('for phase in ("writer", "reader")', source)
        self.assertIn("fresh-reader-expanded-digest", source)
        self.assertNotIn("ORDER = (\"A\", \"B\", \"B\", \"A\")", source)

    def test_current_phase_provenance_fails_closed_and_marks_receipts_diagnostic(self):
        source = Path(current_launcher.__file__).read_text()
        self.assertIn("def normalize_classpath(text):", source)
        self.assertIn("exactly one selected current exporter source", source)
        self.assertIn("fixed OTel source", source)
        self.assertIn("qualified driver resolved", source)
        self.assertIn("data.json pin", source)
        self.assertIn("resolved-classpath", source)
        self.assertIn("HARNESS_SOURCES", source)
        self.assertIn("provenance changed before green terminal", source)
        self.assertIn("perturbs-measured-total", source)

    def test_current_phase_data_json_pin_tracks_root_declaration(self):
        self.assertEqual(
            current_launcher.declared_data_json_source_root(),
            "https___github.com_casselc_data.json.git/"
            "0f51b99101bc5e840f957c073f87b6f877309a25/src/main/clojure")
        with tempfile.TemporaryDirectory() as parent:
            root = Path(parent)
            declaration = ('org.clojure/data.json '
                           '{:git/url "https://github.com/casselc/data.json.git" '
                           ':git/sha "' + "a" * 40 + '"}')
            with patch.object(current_launcher, "REPO", root):
                (root / "deps.edn").write_text("{:deps {" + declaration + "}}")
                self.assertIn("/" + "a" * 40 + "/src/main/clojure",
                              current_launcher.declared_data_json_source_root())
                for source in ("{:deps {}}",
                               "{:deps {" + declaration + " " + declaration + "}}",
                               "{:deps {org.clojure/data.json "
                               '{:git/url "https://github.com/clojure/data.json.git" '
                               ':git/sha "' + "a" * 40 + '"}}'):
                    (root / "deps.edn").write_text(source)
                    with self.assertRaises(RuntimeError):
                        current_launcher.declared_data_json_source_root()

    def test_phase_receipt_key_set_is_exact(self):
        source = (launcher.REPO / "bench/otel/exporter/scalar_abba.clj").read_text()
        self.assertIn("aggregate phase receipt key set", source)
        self.assertIn(":payload-built", source)
        self.assertIn(":native-execute-returned", source)
        self.assertIn(":persistence-barrier-returned", source)

    def test_same_sources_dependencies_and_candidate_pin(self):
        self.assertEqual(launcher.deps("A").replace(str(launcher.ROOTS["A"] / "src"), "<SRC>"),
                         launcher.deps("B").replace(str(launcher.ROOTS["B"] / "src"), "<SRC>"))
        self.assertIn("8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2", str(launcher.OTEL))
        self.assertEqual(launcher.B, "6fdc6cba06fdb635c09695a5c0a40250c866b03e")

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
                if cmd[-1] == "otel.exporter.chdb-untyped-encoder-test":
                    return
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
                if cmd[-1] == "otel.exporter.chdb-untyped-encoder-test":
                    return
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
