#!/usr/bin/env python3
"""One serial public Durable ABBA qualification. Never retries a child."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

BASE = "ebac0eb1c0ac070b3591675d15d09acadef81988"
A = BASE
B = "b492e8811e575f156e7b5c1ae383ba81e1a3bdd3"
REPO = Path(__file__).resolve().parents[1]
COMPILER = Path("/home/chuck/ai-src/worktrees/jolt-v0810-durable-wal-private-hint/target/release/jolt")
WRAPPER = Path("/home/chuck/ai-src/tools/jolt-with-chez-10.4.1")
DRIVER = Path("/home/chuck/ai-src/qualification-worktrees/jolt-chdb-9ec4d6b-durable")
NATIVE = Path("/home/chuck/.cache/chdb-rust/v26.7.3/linux-x86_64-libchdb/libchdb.so")
FIXTURE = Path("/home/chuck/ai-src/evidence/direct-encoder-canonical-no-durable-20260922T0415Z/fixture-batch-0.edn")
ROOTS = {
    "A": REPO,
    "B": REPO,
}
OTEL = Path("/home/chuck/.jolt/gitlibs/https___github.com_casselc_otel.git/8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2")
PINS = {
    COMPILER: "7de7a1e4d0dbc77d16165982c98785ff98482335377dc2bf33667339cf7997b5",
    NATIVE: "36ad4e999882821ef13cf2d0f52c93f48e6ee1b4498b35a201c23ce4b8d15bf5",
    FIXTURE: "1783f6cd64505dd0a4dabbe871f392d7d9cf4937dc6a979d53225677dff2e2bc",
}
ORDER = ("A", "B", "B", "A")

def exclusive_lock(path=Path("/tmp/jolt-public-durable-abba.lock")):
    lock = open(path, "a")
    try:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except Exception:
        lock.close()
        raise
    return lock

def require(ok, label):
    if not ok:
        raise RuntimeError(label)

def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()

def prepare(root):
    root = Path(root).resolve()
    root.mkdir(parents=True, exist_ok=True)
    require(not list(root.iterdir()), "evidence root must be empty")
    # Atomically claim custody; another launcher may not use the same root.
    (root / "launch.claim").touch(exist_ok=False)
    for i, arm in enumerate(ORDER):
        cell = root / f"{i + 1}-{arm}"
        for name in ("objects", "scratch-writer", "scratch-reader", "cache-writer", "cache-reader"):
            directory = cell / name
            directory.mkdir(parents=True)
            require(directory.is_dir() and os.access(directory, os.W_OK), "unusable scratch/output directory")
    return root

def deps(arm):
    return ('{:paths [' + json.dumps(str(ROOTS[arm] / "src")) + ' "bench"] :deps {'
            'io.github.chucklehead-dev/jolt-chdb {:local/root ' + json.dumps(str(DRIVER)) + '} '
            'io.github.casselc/otel {:local/root ' + json.dumps(str(OTEL)) +
            ' :exclusions [jolt-lang/jolt-crypto]}}}')

def command(arm, *args):
    return [str(WRAPPER), str(COMPILER), "-Srepro", "-Sdeps", deps(arm), *args]

def environment(arm, cell, phase):
    env = os.environ.copy()
    env.update(PATH=str(COMPILER.parent) + os.pathsep + env.get("PATH", ""),
               OTEL_TEST_JOLT_WRAPPER=str(WRAPPER), JOLT_CHDB_LIB=str(NATIVE),
               JOLT_CACHE_DIR=str(cell / ("cache-" + phase)), BENCH_ARM=arm,
               BENCH_FROZEN_FIXTURE=str(FIXTURE), BENCH_FROZEN_FIXTURE_SHA256=PINS[FIXTURE],
               BENCH_OTEL_SOURCE_SHA256=sha(OTEL / "src/otel/any_value.clj"),
               BENCH_EXPORTER_SOURCE_SHA256=sha(ROOTS[arm] / "src/otel/exporter/chdb.clj"),
               BENCH_PROTOTYPE_SOURCE_SHA256=sha(REPO / "bench/otel/exporter/untyped_encoder.clj"))
    return env

def normalize_classpath(text, arm):
    lines = [line for line in text.splitlines() if line.startswith(str(ROOTS[arm] / "src") + ":")]
    require(len(lines) == 1, "one resolved classpath")
    paths = lines[0].split(":")
    providers = [p for p in paths if (Path(p) / "otel/any_value.clj").is_file()]
    require(providers == [str(OTEL / "src")], "fixed OTel source")
    exporters = [p for p in paths if (Path(p) / "otel/exporter/chdb.clj").is_file()]
    require(exporters == [str(ROOTS[arm] / "src")], "exactly one selected exporter source")
    require(str(DRIVER / "src") in paths, "qualified driver resolved")
    require(any("e7f97a9b5ecf7fa00787375fff4176a082fe9b98/src/main/clojure" in p for p in paths), "data.json pin")
    return [p.replace(str(ROOTS[arm] / "src"), "<EXPORTER-SRC>") for p in paths]

def pins():
    require(not git(REPO, "status", "--porcelain", "--untracked-files=no"), "exporter worktree dirty")
    require(not git(REPO, "diff", BASE, "--", "src", "deps.edn"), "exporter production source differs from origin-main base")
    require(git(DRIVER, "rev-parse", "HEAD") == "9ec4d6bd5db9a9dc150e3ed6b497e554e3070f75", "driver commit")
    require(not git(DRIVER, "status", "--porcelain", "--untracked-files=no"), "driver dirty")
    require(git(OTEL, "rev-parse", "HEAD") == "8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2", "fixed OTel commit")
    require(not git(OTEL, "status", "--porcelain", "--untracked-files=no"), "OTel dirty")
    require(git(REPO, "merge-base", "--is-ancestor", B, "HEAD") == "", "prototype ancestor")
    require(not git(ROOTS["B"], "status", "--porcelain", "--untracked-files=no"), "candidate dirty")
    changed = git(ROOTS["B"], "diff", "--name-only", A, B).splitlines()
    require(set(changed) == {"bench/UNTYPED_ENCODER.md", "bench/otel/exporter/untyped_encoder.clj", "bench/otel/exporter/untyped_encoder_test.clj"}, "only audited exporter change")
    require(not git(REPO, "diff", B, "--", "bench/otel/exporter/untyped_encoder.clj"), "prototype source unchanged")
    for path, expected in PINS.items():
        require(sha(path) == expected, "artifact hash: " + str(path))
    return {"exporter-base": BASE, "harness-commit": git(REPO, "rev-parse", "HEAD"),
            "exporter-arms": {"A": A, "B": B}, "otel": git(OTEL, "rev-parse", "HEAD"), "driver": git(DRIVER, "rev-parse", "HEAD"),
            "hashes": {str(p): h for p, h in PINS.items()},
            "exporter-source-sha256": sha(REPO / "src/otel/exporter/chdb.clj"),
            "prototype-sha256": sha(REPO / "bench/otel/exporter/untyped_encoder.clj")}

def run_child(cmd, log, env, timeout=300):
    with open(log, "w") as output:
        # timeout fails the experiment; subprocess.run reaps its direct child.
        result = subprocess.run(["timeout", "--signal=TERM", "--kill-after=5s", str(timeout), *cmd],
                                cwd=REPO, env=env, stdout=output, stderr=subprocess.STDOUT)
    require(result.returncode == 0, f"child failed ({result.returncode}): {log}")

def execute(root, run=run_child):
    digests = []
    for index, arm in enumerate(ORDER):
        cell = root / f"{index + 1}-{arm}"
        for phase in ("writer", "reader"):
            cmd = command(arm, "-m", "otel.exporter.scalar-abba", phase, str(cell))
            (cell / f"{phase}.command.json").write_text(json.dumps(cmd) + "\n")
            run(cmd, cell / f"{phase}.log", environment(arm, cell, phase))
            require((cell / f"{phase}-report.edn").is_file(), "missing terminal report")
            (cell / f"{phase}.complete").write_text(sha(cell / f"{phase}-report.edn") + "\n")
        digest = (cell / "reader.expanded-digest").read_text().strip()
        require(len(digest) == 64 and all(c in "0123456789abcdef" for c in digest), "expanded reader digest format")
        digests.append(digest)
        require(len(set(digests)) == 1, "expanded event/link digest differs across arms")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["preflight", "run"])
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    lock = exclusive_lock()  # Held for the entire one-shot launcher lifetime.
    evidence = pins()
    root = prepare(args.root)
    status = {"status": "failed", "started": time.time()}
    try:
        (root / "pins.json").write_text(json.dumps(evidence, indent=2) + "\n")
        resolved = []
        for index, arm in enumerate(("A", "B")):
            cell = root / ("1-A" if arm == "A" else "2-B")
            env = environment(arm, cell, "writer")
            run_child(command(arm, "-Spath"), root / f"{arm}.classpath.log", env)
            run_child(command(arm, "-Sdescribe"), root / f"{arm}.describe.log", env)
            resolved.append(normalize_classpath((root / f"{arm}.classpath.log").read_text(), arm))
        require(resolved[0] == resolved[1], "non-exporter-source difference")
        version = subprocess.check_output([str(WRAPPER), str(COMPILER), "--version"], text=True).strip()
        require(version == "jolt v0.8.10-7-g23d3bb04", "runtime version")
        (root / "version.txt").write_text(version + "\n")
        if args.mode == "run":
            execute(root)
            require(pins() == evidence, "pins changed during qualification")
        status["status"] = "green"
        status["mode"] = args.mode
    finally:
        status["finished"] = time.time()
        (root / "terminal.json").write_text(json.dumps(status) + "\n")

if __name__ == "__main__":
    main()
