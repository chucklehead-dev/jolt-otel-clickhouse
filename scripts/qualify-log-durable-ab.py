#!/usr/bin/env python3
"""Pinned public LogRecordExporter -> chDB Durable A/B qualification.

The first execution should be `smoke` (five measured batches per arm). The
`qualify` mode requires at least 100 measured batches per arm and is deliberately
separate so the heavy slot can be coordinated. Neither mode retries a child.
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
CONTROL = Path("/home/chuck/ai-src/worktrees/jolt-otel-clickhouse-log-control")
DRIVER = Path("/home/chuck/ai-src/worktrees/chdb-datajson-default-writer-512")
OTEL = Path("/home/chuck/.jolt/gitlibs/https___github.com_casselc_otel.git/8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2")
COMPILER = Path("/home/chuck/ai-src/worktrees/jolt-v0810-durable-wal-private-hint/target/release/jolt")
WRAPPER = Path("/home/chuck/ai-src/tools/jolt-with-chez-10.4.1")
NATIVE = Path("/home/chuck/.cache/chdb-rust/v26.7.3/linux-x86_64-libchdb/libchdb.so")
CONTROL_SHA = "96d56e2a136d61f0f0222304a0fdb5afefedfcfb"
DRIVER_SHA = "060bcb49933e3d15b99098c5b345302b31fe9836"
OTEL_SHA = "8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2"
COMPILER_SHA256 = "7de7a1e4d0dbc77d16165982c98785ff98482335377dc2bf33667339cf7997b5"
NATIVE_SHA256 = "36ad4e999882821ef13cf2d0f52c93f48e6ee1b4498b35a201c23ce4b8d15bf5"
DATA_JSON_SHA = "0f51b99101bc5e840f957c073f87b6f877309a25"
HARNESS_FILES = (
    REPO / "bench/otel/exporter/log_durable_ab.clj",
    REPO / "scripts/qualify-log-durable-ab.py",
)


def require(condition, label):
    if not condition:
        raise RuntimeError(label)


def sha(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def pins(candidate_sha):
    require(git(CONTROL, "rev-parse", "HEAD") == CONTROL_SHA, "control head")
    require(git(REPO, "rev-parse", "HEAD") == candidate_sha, "candidate head")
    require(git(DRIVER, "rev-parse", "HEAD") == DRIVER_SHA, "provisional chDB #204 head")
    require(git(OTEL, "rev-parse", "HEAD") == OTEL_SHA, "OTel head")
    for root in (CONTROL, REPO, DRIVER, OTEL):
        require(not git(root, "status", "--porcelain", "--untracked-files=no"),
                f"dirty source: {root}")
    require(sha(COMPILER) == COMPILER_SHA256, "compiler binary hash")
    require(sha(NATIVE) == NATIVE_SHA256, "libchdb hash")
    require(DATA_JSON_SHA in (REPO / "deps.edn").read_text(), "candidate data.json pin")
    require(DATA_JSON_SHA in (CONTROL / "deps.edn").read_text(), "control data.json pin")
    require(DATA_JSON_SHA in (DRIVER / "deps.edn").read_text(), "driver data.json pin")
    return {
        "control": CONTROL_SHA,
        "candidate": candidate_sha,
        "driver_provisional_chdb_204": DRIVER_SHA,
        "otel": OTEL_SHA,
        "compiler_sha256": COMPILER_SHA256,
        "libchdb_sha256": NATIVE_SHA256,
        "data_json": DATA_JSON_SHA,
        "exporter_source_sha256": {
            arm: sha(root / "src/otel/exporter/chdb.clj")
            for arm, root in (("A", CONTROL), ("B", REPO))
        },
        "harness_sha256": {
            str(path.relative_to(REPO)): sha(path) for path in HARNESS_FILES
        },
    }


def deps(arm):
    exporter = CONTROL if arm == "A" else REPO
    return (
        "{:paths [" + json.dumps(str(exporter / "src")) + " "
        + json.dumps(str(REPO / "bench")) + "] :deps {"
        + "io.github.chucklehead-dev/jolt-chdb {:local/root " + json.dumps(str(DRIVER)) + "} "
        + "io.github.casselc/otel {:local/root " + json.dumps(str(OTEL))
        + " :exclusions [jolt-lang/jolt-crypto]}}}"
    )


def command(arm, *args):
    return [str(WRAPPER), str(COMPILER), "-Srepro", "-Sdeps", deps(arm), *args]


def environment(cell, arm, phase):
    env = os.environ.copy()
    env.pop("BENCH_PHASE_RECEIPTS", None)
    env.update(
        PATH=str(COMPILER.parent) + os.pathsep + env.get("PATH", ""),
        JOLT_CACHE_DIR=str(cell / ("cache-" + phase)),
        JOLT_CHDB_LIB=str(NATIVE),
        BENCH_ARM=arm,
        OTEL_TEST_JOLT_WRAPPER=str(WRAPPER),
    )
    return env


def run_child(cmd, log, env, timeout=300):
    with open(log, "w") as output:
        result = subprocess.run(
            ["timeout", "--signal=TERM", "--kill-after=5s", str(timeout), *cmd],
            cwd=REPO, env=env, stdout=output, stderr=subprocess.STDOUT,
        )
    require(log.stat().st_size <= 1024 * 1024, f"unbounded child log: {log}")
    require(result.returncode == 0, f"child exited {result.returncode}: {log}")


def classpath(arm, text):
    exporter = CONTROL if arm == "A" else REPO
    paths = [line.split(":") for line in text.splitlines()
             if line.startswith(str(exporter / "src") + ":")]
    require(len(paths) == 1, "one resolved classpath")
    paths = paths[0]
    require([path for path in paths if (Path(path) / "otel/exporter/chdb.clj").is_file()]
            == [str(exporter / "src")], "exactly one exporter provider")
    require([path for path in paths if (Path(path) / "otel/any_value.clj").is_file()]
            == [str(OTEL / "src")], "fixed OTel provider")
    require(str(DRIVER / "src") in paths, "provisional #204 driver provider")
    json_paths = [path for path in paths if "casselc_data.json.git/" in path]
    require(len(json_paths) == 1 and DATA_JSON_SHA in json_paths[0],
            "one exact data.json provider")
    return [path.replace(str(exporter / "src"), "<EXPORTER-SRC>") for path in paths]


def prepare(root):
    root = root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    require(not list(root.iterdir()), "new evidence root must be empty")
    (root / "launch.claim").touch(exist_ok=False)
    for arm in ("A", "B"):
        cell = root / arm
        cell.mkdir()
        for name in ("objects", "scratch-writer", "scratch-reader",
                     "cache-writer", "cache-reader"):
            (cell / name).mkdir()
    return root


def run_arm(root, arm, samples):
    cell = root / arm
    for phase in ("writer", "reader"):
        cmd = command(arm, "-m", "otel.exporter.log-durable-ab",
                      phase, str(cell), arm, str(samples))
        (cell / f"{phase}.command.json").write_text(json.dumps(cmd) + "\n")
        run_child(cmd, cell / f"{phase}.log", environment(cell, arm, phase))
        receipt = cell / f"{phase}-report.edn"
        require(receipt.is_file(), f"missing {phase} terminal report")
        (cell / f"{phase}.complete").write_text(sha(receipt) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("preflight", "smoke", "qualify"))
    parser.add_argument("root", type=Path)
    parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--samples", type=int, default=100)
    args = parser.parse_args()
    require(len(args.candidate_sha) == 40 and all(c in "0123456789abcdef" for c in args.candidate_sha),
            "exact candidate SHA required")
    require(args.mode != "qualify" or 100 <= args.samples <= 500,
            "qualification requires 100-500 samples per arm")
    samples = 5 if args.mode == "smoke" else args.samples
    with open("/tmp/jolt-log-durable-ab.lock", "a") as custody:
        fcntl.flock(custody.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        initial = pins(args.candidate_sha)
        root = prepare(args.root)
        terminal = {"status": "failed", "mode": args.mode, "started": time.time()}
        try:
            resolved = []
            for arm in ("A", "B"):
                cell = root / arm
                log = cell / "classpath.log"
                run_child(command(arm, "-Spath"), log, environment(cell, arm, "writer"))
                resolved.append(classpath(arm, log.read_text()))
                run_child(command(arm, "-e", "(require 'otel.exporter.log-durable-ab) (println :harness-load-ok)"),
                          cell / "harness-load.log", environment(cell, arm, "writer"))
            require(resolved[0] == resolved[1], "non-exporter classpath mismatch")
            (root / "pins.json").write_text(json.dumps(initial, indent=2) + "\n")
            if args.mode != "preflight":
                for arm in ("A", "B"):
                    run_arm(root, arm, samples)
                sql = [(root / arm / "public-sql.sha256").read_text().strip()
                       for arm in ("A", "B")]
                digests = [(root / arm / "reader.digest").read_text().strip()
                           for arm in ("A", "B")]
                require(sql[0] == sql[1], "A/B public SQL bytes differ")
                require(digests[0] == digests[1], "A/B fresh-reader full rows differ")
                (root / "result.json").write_text(json.dumps({
                    "samples_per_arm": samples, "rows_per_batch": 512,
                    "public_sql_sha256": sql[0], "fresh_reader_full_row_sha256": digests[0],
                    "scope": "local Durable public log exporter; provisional chDB #204",
                }, indent=2) + "\n")
            require(pins(args.candidate_sha) == initial, "pins changed during run")
            terminal["status"] = "green"
        finally:
            terminal["finished"] = time.time()
            (root / "terminal.json").write_text(json.dumps(terminal) + "\n")


if __name__ == "__main__":
    main()
