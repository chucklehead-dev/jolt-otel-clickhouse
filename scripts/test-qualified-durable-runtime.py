"""Offline public artifact protocol controls; no real compiler or credentials."""
import hashlib
import json
import os
import pathlib
import subprocess
import tempfile
import warnings
import zipfile

SCRIPT = pathlib.Path(__file__).resolve().with_name("fetch-qualified-durable-runtime.sh")
ROOT = pathlib.Path(tempfile.mkdtemp(prefix="exporter-runtime-artifact-controls."))
MOCK = ROOT / "mock"
MOCK.mkdir()
GH_SOURCE = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
assert sys.argv[1:4] == ["api", "--hostname", "github.com"]
assert "GH_DEBUG" not in os.environ and "GH_TRACE" not in os.environ
endpoint = sys.argv[4]
control = os.environ["OFFLINE_CONTROL"]
sha = "1" * 40
if endpoint == "repos/casselc/jolt/actions/runs/1":
    print(json.dumps({"repository": {"full_name": "casselc/jolt"},
        "head_repository": {"full_name": "casselc/jolt"},
        "head_sha": "2" * 40 if control == "wrong-workflow" else sha,
        "path": ".github/workflows/durable-runtime-artifact.yml",
        "run_attempt": 2 if control == "wrong-run-attempt" else 1,
        "event": "push", "head_branch": "integration/aspects",
        "status": "completed", "conclusion": "success"}))
elif endpoint == "repos/casselc/jolt/actions/artifacts/2":
    digest = (os.environ["QUALIFIED_RUNTIME_ARTIFACT_SHA256"] if control == "wrong-archive"
              else os.environ["OFFLINE_ACTUAL_ARCHIVE_HASH"])
    print(json.dumps({"id": 2, "name": "durable-runtime-aea91781-linux-x64",
        "expired": control == "expired", "digest": "sha256:" + digest,
        "workflow_run": {"id": 9 if control == "wrong-artifact-run" else 1,
                         "head_sha": sha, "head_repository_id": 1310562894}}))
elif endpoint == "repos/casselc/jolt/actions/artifacts/2/zip":
    sys.stdout.buffer.write(pathlib.Path(os.environ["OFFLINE_ARCHIVE"]).read_bytes())
else:
    raise AssertionError("unexpected public fixture endpoint")
'''
(MOCK / "gh").write_text(GH_SOURCE)
(MOCK / "gh").chmod(0o755)
BINARY = b'#!/bin/sh\nprintf "%s\\n" synthetic-runtime-executed > "$FIXTURE_EXEC_SENTINEL"\n'
BINARY_HASH = hashlib.sha256(BINARY).hexdigest()
LINES = [
    "schema=1", "repository=casselc/jolt",
    "compiler_source=aea91781bbab68bf174fef4a689bb00dcf834ded",
    "compiler_tree=a31de1596fcabd0e45fbcbc528842805acea0ee7",
    "workflow_source=" + "1" * 40,
    "workflow_path=.github/workflows/durable-runtime-artifact.yml",
    "run_id=1", "run_attempt=1", "binary_sha256=" + BINARY_HASH,
    "chez_version=10.4.1", "runner_os=Linux", "runner_arch=X64",
    "require_buildlib=1", "gate=pass", "ranged_append_ascii=98",
]
CONTROLS = ["valid", "wrong-archive", "wrong-binary", "wrong-run-attempt",
            "wrong-workflow", "expired", "duplicate-members", "missing-pin",
            "duplicate-pin", "wrong-manifest-hash", "wrong-artifact-run"]
failures = 0
for control in CONTROLS:
    lines = list(LINES)
    if control == "missing-pin":
        lines.remove(LINES[3])
    if control == "duplicate-pin":
        lines.append(LINES[2])
    manifest = ("\n".join(lines) + "\n").encode()
    manifest_hash = hashlib.sha256(manifest).hexdigest()
    if control == "wrong-manifest-hash":
        manifest_hash = "0" * 64
    sums = f"{BINARY_HASH}  jolt\n{manifest_hash}  build-manifest.txt\n".encode()
    archive = ROOT / f"{control}.zip"
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as target:
            target.writestr("jolt", BINARY)
            target.writestr("build-manifest.txt", manifest)
            target.writestr("SHA256SUMS", sums)
            if control == "duplicate-members":
                target.writestr("jolt", BINARY)
    actual_archive_hash = hashlib.sha256(archive.read_bytes()).hexdigest()
    sentinel = ROOT / f"{control}.executed"
    environment = {
        "PATH": f"{MOCK}:/usr/bin:/bin", "TMPDIR": str(ROOT), "LC_ALL": "C",
        "GH_HOST": "not-github.invalid", "GH_DEBUG": "public-fixture", "GH_TRACE": "public-fixture",
        "QUALIFIED_RUNTIME_RUN_ID": "1", "QUALIFIED_RUNTIME_RUN_ATTEMPT": "1",
        "QUALIFIED_RUNTIME_ARTIFACT_ID": "2", "QUALIFIED_RUNTIME_WORKFLOW_SHA": "1" * 40,
        "QUALIFIED_RUNTIME_ARTIFACT_SHA256": "0" * 64 if control == "wrong-archive" else actual_archive_hash,
        "QUALIFIED_RUNTIME_BINARY_SHA256": "0" * 64 if control == "wrong-binary" else BINARY_HASH,
        "OFFLINE_CONTROL": control, "OFFLINE_ARCHIVE": str(archive),
        "OFFLINE_ACTUAL_ARCHIVE_HASH": actual_archive_hash, "FIXTURE_EXEC_SENTINEL": str(sentinel),
    }
    result = subprocess.run(["bash", str(SCRIPT)], env=environment, capture_output=True, timeout=10)
    log = result.stdout + result.stderr
    (ROOT / f"{control}.log").write_bytes(log)
    evidence = [line.removeprefix("QUALIFIED_RUNTIME_EVIDENCE_ROOT=")
                for line in result.stdout.decode().splitlines()
                if line.startswith("QUALIFIED_RUNTIME_EVIDENCE_ROOT=")]
    assert len(evidence) == 1, "control must reach its intended artifact boundary"
    binary_path = pathlib.Path(evidence[0]) / "jolt"
    executable = os.access(binary_path, os.X_OK)
    executed = sentinel.exists()
    if control == "valid":
        passed = result.returncode == 0 and executed and executable
    else:
        passed = result.returncode != 0 and not executed and not executable
    if control == "wrong-archive":
        # Metadata agrees with the independent pin, so this witnesses bytes,
        # not only an earlier mismatch of declared API digests.
        passed = passed and b"computed checksum did NOT match" in log
    failures += not passed
    print(f"control={control} exit={result.returncode} executed={executed} executable={executable} pass={passed}")
print(f"SYNTHETIC-CONTROLS={len(CONTROLS)} FAILURES={failures} ROOT={ROOT}")
assert len(CONTROLS) == 11 and failures == 0
