#!/usr/bin/env python3
"""One-arm, non-comparative acknowledged Durable phase profile.

This intentionally does not share the historical ABBA launcher's provenance
rules: it profiles exactly the checked-out current source once, records that
source and dependency provenance, and never describes the result as an A/B
comparison.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

REPO = Path(__file__).resolve().parents[1]
COMPILER = Path("/home/chuck/ai-src/worktrees/jolt-v0810-durable-wal-private-hint/target/release/jolt")
WRAPPER = Path("/home/chuck/ai-src/tools/jolt-with-chez-10.4.1")
DRIVER = Path("/home/chuck/ai-src/qualification-worktrees/jolt-chdb-9ec4d6b-durable")
NATIVE = Path("/home/chuck/.cache/chdb-rust/v26.7.3/linux-x86_64-libchdb/libchdb.so")
FIXTURE = Path("/home/chuck/ai-src/evidence/direct-encoder-canonical-no-durable-20260922T0415Z/fixture-batch-0.edn")
OTEL = Path("/home/chuck/.jolt/gitlibs/https___github.com_casselc_otel.git/8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2")
DATA_JSON_CLASS_PATH = "d8763cb8b38771285f5111dad9316762cd02a700/src/main/clojure"
HARNESS_SOURCES = (
    REPO / "bench/otel/exporter/scalar_abba.clj",
    REPO / "bench/otel/exporter/scalar_abba_support.clj",
    REPO / "scripts/qualify-current-untyped-phase.py",
)

def require(ok, label):
    if not ok:
        raise RuntimeError(label)

def sha(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()

def lock(path=Path("/tmp/jolt-current-durable-phase.lock")):
    handle = open(path, "a")
    try:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except Exception:
        handle.close()
        raise
    return handle

def prepare(root):
    root = root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    require(not list(root.iterdir()), "evidence root must be empty")
    (root / "launch.claim").touch(exist_ok=False)
    for name in ("objects", "scratch-writer", "scratch-reader", "cache-writer", "cache-reader"):
        directory = root / name
        directory.mkdir()
        require(directory.is_dir() and os.access(directory, os.W_OK), "unusable scratch/output directory")
    return root

def deps():
    return ("{:paths [" + json.dumps(str(REPO / "src")) + ' "bench"] :deps {'
            "io.github.chucklehead-dev/jolt-chdb {:local/root " + json.dumps(str(DRIVER)) + "} "
            "io.github.casselc/otel {:local/root " + json.dumps(str(OTEL)) +
            " :exclusions [jolt-lang/jolt-crypto]}}}")

def command(*args):
    return [str(WRAPPER), str(COMPILER), "-Srepro", "-Sdeps", deps(), *args]

def environment(root, phase):
    env = os.environ.copy()
    # This launcher owns the receipt opt-in.  Do not inherit a shell switch.
    env.pop("BENCH_PHASE_RECEIPTS", None)
    env.update(PATH=str(COMPILER.parent) + os.pathsep + env.get("PATH", ""),
               OTEL_TEST_JOLT_WRAPPER=str(WRAPPER), JOLT_CHDB_LIB=str(NATIVE),
               JOLT_CACHE_DIR=str(root / ("cache-" + phase)), BENCH_ARM="CURRENT",
               BENCH_FROZEN_FIXTURE=str(FIXTURE), BENCH_FROZEN_FIXTURE_SHA256=sha(FIXTURE),
               BENCH_OTEL_SOURCE_SHA256=sha(OTEL / "src/otel/any_value.clj"),
               BENCH_EXPORTER_SOURCE_SHA256=sha(REPO / "src/otel/exporter/chdb.clj"))
    if phase == "writer":
        env["BENCH_PHASE_RECEIPTS"] = "1"
    return env

def run_child(cmd, log, env, timeout=300):
    with open(log, "w") as output:
        result = subprocess.run(["timeout", "--signal=TERM", "--kill-after=5s", str(timeout), *cmd],
                                cwd=REPO, env=env, stdout=output, stderr=subprocess.STDOUT)
    require(result.returncode == 0, f"child failed ({result.returncode}): {log}")

def normalize_classpath(text):
    """Fail closed unless the child resolved exactly the intended providers."""
    source = str(REPO / "src")
    lines = [line for line in text.splitlines() if line.startswith(source + ":")]
    require(len(lines) == 1, "one resolved current classpath")
    paths = lines[0].split(":")
    providers = [path for path in paths if (Path(path) / "otel/any_value.clj").is_file()]
    require(providers == [str(OTEL / "src")], "fixed OTel source")
    exporters = [path for path in paths if (Path(path) / "otel/exporter/chdb.clj").is_file()]
    require(exporters == [source], "exactly one selected current exporter source")
    require(str(DRIVER / "src") in paths, "qualified driver resolved")
    require(any(DATA_JSON_CLASS_PATH in path for path in paths), "data.json pin")
    return [path.replace(source, "<EXPORTER-SRC>") for path in paths]

def current_provenance(classpath):
    source_paths = [REPO / "src/otel/exporter/chdb.clj", REPO / "deps.edn"]
    require(not git(REPO, "diff", "--name-only", "--", "src", "deps.edn"),
            "current exporter source or deps are dirty")
    require(not git(DRIVER, "status", "--porcelain", "--untracked-files=no"), "driver dirty")
    require(not git(OTEL, "status", "--porcelain", "--untracked-files=no"), "OTel dirty")
    version = subprocess.check_output([str(WRAPPER), str(COMPILER), "--version"], text=True).strip()
    require(version == "jolt v0.8.10-7-g23d3bb04", "runtime version")
    return {"comparison": {"kind": "one-arm-current-phase-profile", "comparative": False,
                            "reason": "current source is measured once; no historical A/B claim"},
            "exporter": {"head": git(REPO, "rev-parse", "HEAD"), "branch": git(REPO, "branch", "--show-current"),
                         "status": git(REPO, "status", "--porcelain", "--untracked-files=no").splitlines(),
                         "source-sha256": {str(path.relative_to(REPO)): sha(path) for path in source_paths}},
            "harness": {"source-sha256": {str(path.relative_to(REPO)): sha(path)
                                             for path in HARNESS_SOURCES},
                        "resolved-classpath": classpath},
            "compiler": {"path": str(COMPILER), "version": version, "sha256": sha(COMPILER),
                         "wrapper": str(WRAPPER), "wrapper-sha256": sha(WRAPPER)},
            "dependencies": {"edn": deps(), "driver-head": git(DRIVER, "rev-parse", "HEAD"),
                             "otel-head": git(OTEL, "rev-parse", "HEAD"),
                             "otel-any-value-sha256": sha(OTEL / "src/otel/any_value.clj"),
                             "native": str(NATIVE), "native-sha256": sha(NATIVE)},
            "fixture": {"path": str(FIXTURE), "sha256": sha(FIXTURE)},
            "diagnostic-receipts": {"enabled": True,
                                    "scope": "writer aggregate phase receipts only",
                                    "retention": "counts, nanos, and spans only",
                                    "perturbs-measured-total": True,
                                    "comparison-use": "diagnostic; excluded from throughput comparison claims"}}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["preflight", "run"])
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    custody = lock()
    root = prepare(args.root)
    terminal = {"status": "failed", "started": time.time(),
                "comparison": "non-comparative-one-arm-current-phase-profile"}
    try:
        classpath = None
        for flag in ("-Spath", "-Sdescribe"):
            name = "classpath" if flag == "-Spath" else "describe"
            run_child(command(flag), root / f"{name}.log", environment(root, "writer"))
            if flag == "-Spath":
                classpath = normalize_classpath((root / f"{name}.log").read_text())
        provenance = current_provenance(classpath)
        (root / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
        if args.mode == "run":
            for phase in ("writer", "reader"):
                cmd = command("-m", "otel.exporter.scalar-abba", phase, str(root))
                (root / f"{phase}.command.json").write_text(json.dumps(cmd) + "\n")
                run_child(cmd, root / f"{phase}.log", environment(root, phase))
                report = root / f"{phase}-report.edn"
                require(report.is_file(), "missing terminal report")
                (root / f"{phase}.complete").write_text(sha(report) + "\n")
            digest = (root / "reader.expanded-digest").read_text().strip()
            require(len(digest) == 64 and all(char in "0123456789abcdef" for char in digest),
                    "fresh reader expanded digest format")
            require(current_provenance(classpath) == provenance, "provenance changed during profile")
            (root / "result.json").write_text(json.dumps({"comparison": provenance["comparison"],
                                                           "writer-report-sha256": sha(root / "writer-report.edn"),
                                                           "reader-report-sha256": sha(root / "reader-report.edn"),
                                                           "fresh-reader-expanded-digest": digest,
                                                           "diagnostic-receipts": provenance["diagnostic-receipts"]}, indent=2) + "\n")
        # Rehash the exact executed harness and launcher before declaring a
        # preflight or run green; a changed source invalidates its provenance.
        require(current_provenance(classpath) == provenance, "provenance changed before green terminal")
        terminal.update(status="green", mode=args.mode)
    finally:
        terminal["finished"] = time.time()
        (root / "terminal.json").write_text(json.dumps(terminal) + "\n")
        custody.close()

if __name__ == "__main__":
    main()
