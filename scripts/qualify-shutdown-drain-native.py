#!/usr/bin/env python3
"""Bounded two-process native #94 gate. Explicit local run; no CI auto-run."""

import hashlib
import os
from pathlib import Path
import signal
import subprocess
import tempfile


EXPORTER = Path(__file__).resolve().parent.parent
DRIVER_SHA = "bb421e2c485d4c9340e836169e5ea8e9fb5e3785"
JOLT_SHA256 = "c66e2e57dd4b6fbdba958e5cabcf0791ba268f1d374b1155ac580076c7b3060b"
LIB_SHA256 = "36ad4e999882821ef13cf2d0f52c93f48e6ee1b4498b35a201c23ce4b8d15bf5"
WRAPPER_SHA256 = "fb1830e3392a038205b7190d9d5c76597a1b1e73211ed0305163c5f2ce8079fc"


def require(ok, label):
    if not ok:
        raise RuntimeError(label)


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def run(label, command, env, root, timeout=180):
    log = root / f"{label}.log"
    with log.open("wb") as out:
        child = subprocess.Popen(command, cwd=EXPORTER, env=env, stdout=out,
                                 stderr=subprocess.STDOUT, start_new_session=True)
        try:
            result = child.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
            raise RuntimeError(f"{label} timed out; see {log}")
    require(result == 0, f"{label} exited {result}; see {log}")
    require(log.stat().st_size <= 1024 * 1024, f"{label} log exceeded 1 MiB")
    return log.read_text()


def main():
    jolt = Path(os.environ["JOLT_BIN"]).resolve()
    library = Path(os.environ["JOLT_CHDB_LIB"]).resolve()
    driver = Path(os.environ["JOLT_CHDB_SOURCE_ROOT"]).resolve()
    wrapper = Path("/home/chuck/ai-src/tools/jolt-with-chez-10.4.1")
    require(all(path.is_file() for path in (jolt, library, wrapper)), "missing binary or library")
    require(digest(jolt) == JOLT_SHA256, "wrong Jolt c66 binary")
    require(digest(library) == LIB_SHA256, "wrong libchdb 26.7.3")
    require(digest(wrapper) == WRAPPER_SHA256, "wrong Chez 10.4.1 wrapper")
    require(git(driver, "rev-parse", "HEAD") == DRIVER_SHA, "wrong chDB bb421e2 source")
    # The exact source override must not be shadowed by local tracked/untracked
    # edits. Jolt may create only its known untracked dependency marker.
    dirty = subprocess.check_output(
        ["git", "-C", str(driver), "status", "--porcelain=v1", "-z",
         "--untracked-files=all", "--ignored=matching"]).split(b"\0")
    require(all(record in (b"", b"?? .jolt-git-ok") for record in dirty),
            "dirty or shadowed chDB source override")
    require(not git(EXPORTER, "status", "--porcelain=v1", "--untracked-files=all"),
            "dirty exporter qualification source")
    require(git(EXPORTER, "rev-parse", "HEAD") ==
            "4caf4dcbb575b565904476edc4e7446c41f5fcaa" or
            git(EXPORTER, "merge-base", "4caf4dcbb575b565904476edc4e7446c41f5fcaa", "HEAD") ==
            "4caf4dcbb575b565904476edc4e7446c41f5fcaa", "wrong exporter ancestry")

    root = Path(tempfile.mkdtemp(prefix="exporter-94-native-", dir="/tmp"))
    for name in ("objects", "scratch-writer", "scratch-reader", "cache"):
        (root / name).mkdir()
    print(f"EVIDENCE_ROOT={root}", flush=True)
    (root / "source.txt").write_text(
        f"exporter={git(EXPORTER, 'rev-parse', 'HEAD')}\n"
        f"driver={DRIVER_SHA}\njolt-sha256={JOLT_SHA256}\nlib-sha256={LIB_SHA256}\n")
    env = {"HOME": os.environ["HOME"], "PATH": f"{jolt.parent}:/usr/local/bin:/usr/bin:/bin",
           "LANG": "C.UTF-8", "JOLT_CHDB_LIB": str(library),
           "JOLT_CACHE_DIR": str(root / "cache"), "JOLT_NO_USER_DEPS": "1"}
    if "JOLT_GITLIBS_DIR" in os.environ:
        env["JOLT_GITLIBS_DIR"] = os.environ["JOLT_GITLIBS_DIR"]
    override = "{:deps {io.github.chucklehead-dev/jolt-chdb {:local/root " + (
        '"' + str(driver).replace('\\', '\\\\').replace('"', '\\"') + '"') + "}}}"
    base = [str(wrapper), str(jolt), "-Srepro", "-Sdeps", override, "-A:test"]
    path = run("classpath", base + ["-Spath"], env, root, timeout=90).strip().splitlines()[-1]
    providers = [Path(entry).resolve() for entry in path.split(":")
                 if (Path(entry) / "jdbc" / "chdb.clj").is_file()]
    require(providers == [(driver / "src").resolve()],
            "chDB override not the unique resolved provider")
    (root / "classpath-provider.txt").write_text(str(driver) + "\n")
    for phase in ("writer", "reader"):
        output = run(phase, base + ["-m", "otel.exporter.chdb-shutdown-drain-native-test",
                                    phase, str(root)], env, root)
        marker = f":shutdown-race-native-{'writer' if phase == 'writer' else 'fresh-reader'}-confirmed"
        require(output.count(marker) == 1, f"missing unique {phase} receipt")
    require(":full-physical-row-parity true" in (root / "reader.edn").read_text(),
            "fresh reader parity receipt absent")
    print(f"SHUTDOWN_RACE_NATIVE_QUALIFIED={root}", flush=True)


if __name__ == "__main__":
    main()
