"""Synthetic real-launcher receipt controls; no Jolt, native engine or network."""
import hashlib
import os
import pathlib
import shutil
import subprocess
import tempfile

SOURCE = pathlib.Path(__file__).resolve().parents[1]
ROOT = pathlib.Path(tempfile.mkdtemp(prefix="ordinary-receipt-controls."))
ENV = {"PATH": "/usr/bin:/bin", "HOME": str(ROOT), "LC_ALL": "C",
       "GIT_AUTHOR_NAME": "Public fixture", "GIT_AUTHOR_EMAIL": "fixture@example.invalid",
       "GIT_COMMITTER_NAME": "Public fixture", "GIT_COMMITTER_EMAIL": "fixture@example.invalid"}

def git(path, *args):
    return subprocess.check_output(["git", "-C", str(path), *args], env=ENV).decode().strip()

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

provider = ROOT / "provider"
provider.mkdir()
for marker in ["jdbc/chdb.clj", "otel/sdk/metrics.clj", "clojure/data/json.clj",
               "jolt/crypto.clj", "db/jdbc.clj", "deps.edn"]:
    target = provider / marker
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("public fixture\n")
git(provider, "init", "-q")
git(provider, "add", ".")
git(provider, "commit", "-qm", "public provider fixture")
revision = git(provider, "rev-parse", "HEAD")
checks = 0
for control in ["late-child-failure", "partial-writer-failure", "child-receipt-write-failure",
                "record-phase-writer-failure", "non-record-writer-failure",
                "registry-readback-failure", "registry-readback-receipt-write-failure",
                "success", "receipt-publication-failure"]:
    work = ROOT / control
    invocation_ledger = ROOT / f"{control}-compiler-invocations"
    for name in ["scripts", "bench/otel/exporter", "src/otel/exporter", "tools", "native", "evidence"]:
        (work / name).mkdir(parents=True, exist_ok=True)
    launcher = work / "scripts/qualify-ordinary-transport-benchmark.sh"
    shutil.copyfile(SOURCE / "scripts/qualify-ordinary-transport-benchmark.sh", launcher)
    shutil.copyfile(SOURCE / "bench/otel/exporter/chdb_ordinary_transport_benchmark.clj",
                    work / "bench/otel/exporter/chdb_ordinary_transport_benchmark.clj")
    (work / "src/otel/exporter/chdb.clj").write_text("public fixture\n")
    (work / "deps.edn").write_text("{}\n")
    binary = work / "tools/jolt"
    binary.write_text(f'''#!/usr/bin/env python3
import os, pathlib, sys
with open({str(invocation_ledger)!r}, "a") as marker:
    marker.write("public fake compiler invoked\\n")
args = sys.argv[1:]
if "-Spath" in args:
    print({str(provider)!r})
elif "-e" in args and "TASK_DRIVER_DEPS" in args[-1]:
    print({revision!r})
elif "-e" in args and "doseq [lib" in args[-1]:
    print("\\n".join([{revision!r}] * 4))
elif "-e" in args and "BENCH_FIXTURE" in args[-1]:
    mode, route = os.environ["TASK_MODE"], os.environ["TASK_ROUTE"]
    print(":benchmark-stage :require :enter", flush=True)
    print(":benchmark-stage :require :return", flush=True)
    print(":benchmark-stage :fixture :enter", flush=True)
    print(":benchmark-stage :fixture :return", flush=True)
    print(":benchmark-stage :main :enter", flush=True)
    if mode == "writer":
        print(":sample-observation :index 0 :batch-rows 512 :latency-nanos 1000", flush=True)
    if {control!r} in ["partial-writer-failure", "child-receipt-write-failure"] and mode == "writer" and route == "candidate":
        print(":sample-observation :index 1 :batch-rows 512 :latency-nanos 1001", flush=True)
        if {control!r} == "child-receipt-write-failure":
            # Block exactly this owned fixture receipt, without filling a disk
            # or relying on readonly permissions under a privileged runner.
            blocked = pathlib.Path(os.environ["TASK_GRAPH"]).with_suffix(".exit-status")
            assert blocked.is_file() and blocked.read_text() == "running\\n"
            blocked.unlink()
            blocked.mkdir()
        sys.exit(37)
    if {control!r} == "late-child-failure" and mode == "reader" and route == "candidate":
        sys.exit(37)
    if {control!r} in ["record-phase-writer-failure", "registry-readback-failure", "registry-readback-receipt-write-failure"] and mode == "writer" and route == "candidate":
        print(":benchmark-red-diagnostic :category :migration-failed :phase :record :version 1 :statement-index :unknown", flush=True)
        sys.exit(37)
    if {control!r} == "non-record-writer-failure" and mode == "writer" and route == "candidate":
        print(":benchmark-red-diagnostic :category :migration-failed :phase :statement :version 1 :statement-index 0", flush=True)
        sys.exit(37)
    if mode == "registry-readback":
        if {control!r} == "registry-readback-failure":
            sys.exit(53)
        if {control!r} == "registry-readback-receipt-write-failure":
            blocked = pathlib.Path(os.environ["TASK_GRAPH"]).with_suffix(".exit-status")
            assert blocked.is_file() and blocked.read_text() == "running\\n"
            blocked.unlink()
            blocked.mkdir()
        print(":registry-readback :version 1 :status :exact-one", flush=True)
        print(":benchmark-stage :main :return", flush=True)
        sys.exit(0)
    if mode == "writer":
        print(":payload-control serialization public")
        print(":payload-control preencoded public")
        print(f":writer-green :route :{{route}} :rows 25600")
    else:
        print(":fresh-reader-green :groups 1024 :rows 25600 :full-rows-equal true :exact-nanos true :typed-values-status true")
    print(":benchmark-stage :main :return", flush=True)
else:
    sys.exit(91)
''')
    wrapper = work / "tools/wrapper"
    wrapper.write_text('#!/bin/sh\nexec "$@"\n')
    (work / "tools/pgrep").write_text("#!/bin/sh\nexit 1\n")
    sha = work / "tools/sha256sum"
    sha.write_text(f'''#!/bin/sh
case "$*" in
  *comparison-status.txt*)
    test {control!r} != receipt-publication-failure || exit 71 ;;
esac
exec /usr/bin/sha256sum "$@"
''')
    for item in [binary, wrapper, work / "tools/pgrep", sha]:
        item.chmod(0o755)
    lib, header = work / "native/libchdb.so", work / "native/chdb.h"
    lib.write_text("public native stub\n")
    header.write_text("public header stub\n")
    git(work, "init", "-q")
    git(work, "add", ".")
    git(work, "commit", "-qm", "public launcher fixture")
    output = ROOT / f"output-{control}"
    output.mkdir()
    env = dict(ENV, PATH=f"{work / 'tools'}:/usr/bin:/bin", TMPDIR=str(output),
               JOLT_BIN=str(binary), JOLT_WRAPPER=str(wrapper), JOLT_CHDB_LIB=str(lib),
               JOLT_CHDB_HEADER=str(header), JOLT_EXPECTED_BINARY_SHA256=digest(binary),
               JOLT_EXPECTED_WRAPPER_SHA256=digest(wrapper),
               JOLT_CHDB_EXPECTED_LIBRARY_SHA256=digest(lib),
               JOLT_CHDB_EXPECTED_HEADER_SHA256=digest(header))
    result = subprocess.run(["bash", str(launcher), "--bounded-inner"], env=env,
                            capture_output=True, timeout=15)
    (work / "run.log").write_bytes(result.stdout + result.stderr)
    roots = [line.removeprefix("EVIDENCE_ROOT=") for line in result.stdout.decode().splitlines()
             if line.startswith("EVIDENCE_ROOT=")]
    assert len(roots) == 1
    evidence = pathlib.Path(roots[0])
    expected = 37 if control in ["late-child-failure", "partial-writer-failure", "child-receipt-write-failure",
                                 "record-phase-writer-failure", "non-record-writer-failure",
                                 "registry-readback-failure", "registry-readback-receipt-write-failure"] else 1 if control == "receipt-publication-failure" else 0
    assert result.returncode == expected
    assert (evidence / "exit-status.txt").read_text().strip() == str(expected)
    assert (evidence / "loaded-source.after.sha256").is_file()
    assert (evidence / "available-artifacts.sha256").is_file()
    assert b":sample-observation" in (evidence / "A1-writer.log").read_bytes()
    comparison = (evidence / "comparison-status.txt").read_text().strip()
    if control == "late-child-failure":
        assert (evidence / "B1-reader.exit-status").read_text().strip() == "37"
        assert comparison == "unqualified-incomplete-or-failed"
        assert not (evidence / "B2-writer.exit-status").exists()
    elif control == "partial-writer-failure":
        assert (evidence / "B1-writer.exit-status").read_text().strip() == "37"
        partial = (evidence / "B1-writer.log").read_text()
        assert partial.count(":sample-observation") == 2
        assert ":index 0 " in partial and ":index 1 " in partial
        assert ":region-result" not in partial and ":writer-green" not in partial
        assert not (evidence / "B1-reader.exit-status").exists()
        assert comparison == "unqualified-incomplete-or-failed"
    elif control == "child-receipt-write-failure":
        assert (evidence / "B1-writer.exit-status").is_dir()
        assert (evidence / "observed-child-failure.txt").read_text().strip() == "B1-writer 37"
        assert (evidence / "primary-exit-status.txt").read_text().strip() == "37"
        assert (evidence / "B1-writer.log").read_text().count(":sample-observation") == 2
        assert not (evidence / "B1-reader.exit-status").exists()
        assert comparison == "unqualified-evidence-publication-failed"
    elif control == "receipt-publication-failure":
        assert (evidence / "primary-exit-status.txt").read_text().strip() == "0"
        assert comparison == "unqualified-evidence-publication-failed"
    elif control in ["record-phase-writer-failure", "registry-readback-failure", "registry-readback-receipt-write-failure"]:
        assert (evidence / "B1-writer.exit-status").read_text().strip() == "37"
        assert (evidence / "primary-exit-status.txt").read_text().strip() == "37"
        assert (evidence / "observed-child-failure.txt").read_text().strip() == "B1-writer 37"
        assert not (evidence / "B1-reader.exit-status").exists()
        assert not (evidence / "B2-writer.exit-status").exists()
        if control == "record-phase-writer-failure":
            readback = evidence / "B1-registry-readback.exit-status"
            assert readback.read_text().strip() == "0"
            log = (evidence / "B1-registry-readback.log").read_text()
            assert ":registry-readback :version 1 :status :exact-one" in log
            assert comparison == "unqualified-incomplete-or-failed"
        elif control == "registry-readback-failure":
            assert (evidence / "B1-registry-readback.exit-status").read_text().strip() == "53"
            assert comparison == "unqualified-incomplete-or-failed"
        else:
            assert (evidence / "B1-registry-readback.exit-status").is_dir()
            assert comparison == "unqualified-evidence-publication-failed"
    elif control == "non-record-writer-failure":
        assert (evidence / "B1-writer.exit-status").read_text().strip() == "37"
        assert not (evidence / "B1-registry-readback.exit-status").exists()
        assert not (evidence / "B1-reader.exit-status").exists()
        assert not (evidence / "B2-writer.exit-status").exists()
        assert comparison == "unqualified-incomplete-or-failed"
    else:
        assert comparison == "all-arms-compared-source-parity-passed"
        assert len(list(evidence.glob("*.exit-status"))) == 8
    for label in ["A1-writer"] + (["B1-registry-readback"] if control in ["record-phase-writer-failure", "registry-readback-failure", "registry-readback-receipt-write-failure"] else []):
        log = (evidence / f"{label}.log").read_text()
        assert ":benchmark-stage :require :enter" in log
        assert ":benchmark-stage :require :return" in log
        assert ":benchmark-stage :fixture :enter" in log
        assert ":benchmark-stage :fixture :return" in log
        assert ":benchmark-stage :main :enter" in log
    assert (b"ORDINARY_TRANSPORT_ABBA_GREEN=" in result.stdout) == (expected == 0)
    checks += 1
    print(f"control={control} exit={result.returncode} pass=True evidence={evidence}")
# The same real launcher must reject command intent before any fake compiler
# invocation or effect-capable child. Reuse the final owned fixture unchanged.
before = invocation_ledger.read_bytes()
result = subprocess.run(["bash", str(launcher), "--provenance-only"], env=env,
                        capture_output=True, timeout=15)
assert result.returncode == 64
assert result.stdout == b"invalid-benchmark-arguments\n"
assert result.stderr == b""
assert invocation_ledger.read_bytes() == before
assert b"CHILD_BEGIN=" not in result.stdout and b"EVIDENCE_ROOT=" not in result.stdout
checks += 1
print("control=unknown-cli-argument exit=64 compiler-invocations=0 pass=True")
assert checks == 10
print(f"SYNTHETIC-RECEIPT-CONTROLS={checks} FAILURES=0 ROOT={ROOT}")
