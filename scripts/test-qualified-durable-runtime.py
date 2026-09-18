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
if "PAIR_API_LEDGER" in os.environ:
    with open(os.environ["PAIR_API_LEDGER"], "a") as ledger:
        ledger.write(endpoint + "\n")
    run = os.environ["QUALIFIED_RUNTIME_RUN_ID"]
    artifact = os.environ["QUALIFIED_RUNTIME_ARTIFACT_ID"]
    sha = os.environ["QUALIFIED_RUNTIME_WORKFLOW_SHA"]
    if endpoint == f"repos/casselc/jolt/actions/runs/{run}":
        print(json.dumps({"repository": {"full_name": "casselc/jolt"},
            "head_repository": {"full_name": "casselc/jolt"}, "head_sha": sha,
            "path": ".github/workflows/durable-runtime-artifact.yml",
            "run_attempt": 1, "event": "push", "head_branch": "integration/aspects",
            "status": "in_progress" if control == "unfinished-provider" else "completed",
            "conclusion": None if control == "unfinished-provider" else "success"}))
    elif endpoint == f"repos/casselc/jolt/actions/artifacts/{artifact}":
        print(json.dumps({"id": int(artifact), "name": os.environ["PAIR_ARTIFACT_NAME"],
            "expired": False, "digest": "sha256:" + os.environ["QUALIFIED_RUNTIME_ARTIFACT_SHA256"],
            "workflow_run": {"id": int(run), "head_sha": sha,
                             "head_repository_id": 1310562894}}))
    elif endpoint == f"repos/casselc/jolt/actions/artifacts/{artifact}/zip":
        sys.stdout.buffer.write(pathlib.Path(os.environ["OFFLINE_ARCHIVE"]).read_bytes())
    else:
        raise AssertionError("unexpected paired fixture endpoint")
    sys.exit(0)
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
BINARY = b'''#!/usr/bin/python3
import os, pathlib, sys
pathlib.Path(os.environ["FIXTURE_EXEC_SENTINEL"]).write_text(str(pathlib.Path.cwd()))
assert sys.argv[1:3] == ["-Srepro", "-e"] and len(sys.argv) == 4
assert '(assert (= [98] (vec (.toByteArray out))))' in sys.argv[3]
if (pathlib.Path.cwd() / "deps.edn").exists():
    sys.exit(42)  # Synthetic caller graph would reject this runtime.
if os.environ["OFFLINE_CONTROL"] == "unsupported-canary":
    sys.exit(43)  # Protocol control, not a real runtime semantics proof.
'''
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
CALLER = ROOT / "unrelated-caller"
CALLER.mkdir()
(CALLER / "deps.edn").write_text('{:jolt/min-version "999.0.0" :deps {unrelated/missing {:mvn/version "0.0.0"}}}')
CONTROLS = ["valid", "unsupported-canary", "wrong-archive", "wrong-binary", "wrong-run-attempt",
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
    result = subprocess.run(["bash", str(SCRIPT)], env=environment, cwd=CALLER,
                            capture_output=True, timeout=10)
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
        passed = (result.returncode == 0 and executed and executable
                  and sentinel.read_text() == str(binary_path.parent)
                  and f"QUALIFIED_RUNTIME_BIN={binary_path}".encode() in result.stdout)
        # Exact historical boundary: same authenticated fixture, same canary,
        # same caller and predicate, with ONLY the new cwd isolation removed.
        historical_source = SCRIPT.read_text()
        start = '(\ncd "$root"\ntimeout --signal=TERM'
        end = "(println :qualified-runtime-ranged-append-pass))'\n)\nprintf"
        assert historical_source.count(start) == historical_source.count(end) == 1
        historical_source = historical_source.replace(start, 'timeout --signal=TERM', 1)
        historical_source = historical_source.replace(end, end.replace("\n)\n", "\n"), 1)
        historical_script = ROOT / "historical-caller-boundary.sh"
        historical_script.write_text(historical_source)
        historical = subprocess.run(["bash", str(historical_script)], env=environment,
                                    cwd=CALLER, capture_output=True, timeout=10)
        (ROOT / "historical-caller-boundary.log").write_bytes(historical.stdout + historical.stderr)
        red = (historical.returncode == 42 and sentinel.read_text() == str(CALLER)
               and b"QUALIFIED_RUNTIME_BIN=" not in historical.stdout)
        passed = passed and red
        print(f"caller-boundary-red exit={historical.returncode} pass={red}")
    elif control == "unsupported-canary":
        passed = (result.returncode == 43 and executed and executable
                  and sentinel.read_text() == str(binary_path.parent)
                  and b"QUALIFIED_RUNTIME_BIN=" not in result.stdout)
    else:
        passed = result.returncode != 0 and not executed and not executable
    if control == "wrong-archive":
        # Metadata agrees with the independent pin, so this witnesses bytes,
        # not only an earlier mismatch of declared API digests.
        passed = passed and b"computed checksum did NOT match" in log
    failures += not passed
    print(f"control={control} exit={result.returncode} executed={executed} executable={executable} pass={passed}")
print(f"SYNTHETIC-CONTROLS={len(CONTROLS)} FAILURES={failures} ROOT={ROOT}")
assert len(CONTROLS) == 12 and failures == 0

# Selection controls do NOT substitute synthetic hashes for fixed public pins.
# Correct profiles reach ZIP bytes, which must reject this synthetic archive
# before executable mode/probe. The original controls above cover full archive
# and manifest acceptance with a fake child; neither set runs a real compiler.
PROFILES = {
    "baseline-09a2": ("10504073187", "durable-runtime-09a2baac-linux-x64",
        "2dba59b6c96787e27b9edaaabafc4e3624b0e7bb380d0ec83a6a3bf352980f78",
        "1ea6a9e222411379a6129ec25930f18062e3fe5be6bef886dc2fb15fe081642a"),
    "string-writer-c5d": ("10504849823", "durable-runtime-c5d444e4-linux-x64",
        "49ac4be188348f5a7c72148ae1da63719914442f056ce25c89972fae8ce1f314",
        "c250124902495885fc417bc9bf559f5fe5a44701f3a3e4c0a56062a98e07d165"),
}
COMPILER_PINS = {
    "baseline-09a2": ("09a2baac9714f98b994473f64fd239f431a9fffb",
                      "4c2fb3c2b00fe085ce3920a1de558c65d3b8f979"),
    "string-writer-c5d": ("c5d444e4d074767f507fe86b203b6dde6c309fc5",
                          "555b5a9d9745be2a9f34041022c5376db5eb41f0"),
}
# Source-coupled closed mapping checks: these assert selection, not execution
# of either real paired binary or authentication of a synthetic manifest.
script_source = SCRIPT.read_text()
assert "profile=${QUALIFIED_RUNTIME_PROFILE-aea}" in script_source
assert "compiler=aea91781bbab68bf174fef4a689bb00dcf834ded" in script_source
assert "compiler_tree=a31de1596fcabd0e45fbcbc528842805acea0ee7" in script_source
for profile, pins in PROFILES.items():
    block = script_source.split(f"  {profile})\n", 1)[1].split(";;", 1)[0]
    compiler, tree = COMPILER_PINS[profile]
    for expected in (f"compiler={compiler}", f"compiler_tree={tree}",
                     f"artifact_name={pins[1]}", f"pair_artifact={pins[0]}",
                     f"pair_archive={pins[2]}", f"pair_binary={pins[3]}"):
        assert block.splitlines().count("    " + expected) == 1
pair_controls = ["selection", "unfinished-provider", "cross-name", "wrong-run",
                 "wrong-attempt", "wrong-controller", "cross-artifact",
                 "cross-archive", "cross-binary", "unknown-profile", "empty-profile"]
pair_failures = 0
for profile, pins in PROFILES.items():
    other = next(value for key, value in PROFILES.items() if key != profile)
    for control in pair_controls:
        prefix = f"{profile}-{control}"
        ledger = ROOT / f"{prefix}.api"
        sentinel = ROOT / f"{prefix}.executed"
        environment = {
            "PATH": f"{MOCK}:/usr/bin:/bin", "TMPDIR": str(ROOT), "LC_ALL": "C",
            "QUALIFIED_RUNTIME_PROFILE": profile,
            "QUALIFIED_RUNTIME_RUN_ID": "35237991514",
            "QUALIFIED_RUNTIME_RUN_ATTEMPT": "1",
            "QUALIFIED_RUNTIME_WORKFLOW_SHA": "1fea9ae8becb8b5ada545d32b032cc4de91c52cc",
            "QUALIFIED_RUNTIME_ARTIFACT_ID": pins[0],
            "QUALIFIED_RUNTIME_ARTIFACT_SHA256": pins[2],
            "QUALIFIED_RUNTIME_BINARY_SHA256": pins[3],
            "PAIR_ARTIFACT_NAME": pins[1], "PAIR_API_LEDGER": str(ledger),
            "OFFLINE_CONTROL": control, "OFFLINE_ARCHIVE": str(archive),
            "FIXTURE_EXEC_SENTINEL": str(sentinel),
        }
        changes = {
            "wrong-run": ("QUALIFIED_RUNTIME_RUN_ID", "1"),
            "wrong-attempt": ("QUALIFIED_RUNTIME_RUN_ATTEMPT", "2"),
            "wrong-controller": ("QUALIFIED_RUNTIME_WORKFLOW_SHA", "1" * 40),
            "cross-artifact": ("QUALIFIED_RUNTIME_ARTIFACT_ID", other[0]),
            "cross-archive": ("QUALIFIED_RUNTIME_ARTIFACT_SHA256", other[2]),
            "cross-binary": ("QUALIFIED_RUNTIME_BINARY_SHA256", other[3]),
            "cross-name": ("PAIR_ARTIFACT_NAME", other[1]),
            "unknown-profile": ("QUALIFIED_RUNTIME_PROFILE", "arbitrary-compiler"),
            "empty-profile": ("QUALIFIED_RUNTIME_PROFILE", ""),
        }
        if control in changes:
            key, value = changes[control]
            environment[key] = value
        result = subprocess.run(["bash", str(SCRIPT)], env=environment,
                                capture_output=True, timeout=10)
        log = result.stdout + result.stderr
        (ROOT / f"{prefix}.log").write_bytes(log)
        requests = ledger.read_text().splitlines() if ledger.exists() else []
        expected = 3 if control == "selection" else 1 if control == "unfinished-provider" else 2 if control == "cross-name" else 0
        evidence = [line.split("=", 1)[1] for line in result.stdout.decode().splitlines()
                    if line.startswith("QUALIFIED_RUNTIME_EVIDENCE_ROOT=")]
        executable = any(os.access(pathlib.Path(root) / "jolt", os.X_OK) for root in evidence)
        passed = result.returncode != 0 and not sentinel.exists() and not executable and len(requests) == expected
        if control == "selection":
            passed = passed and b"computed checksum did NOT match" in log
        pair_failures += not passed
        print(f"pair={prefix} exit={result.returncode} requests={len(requests)} pass={passed}")
print(f"PAIR-SELECTION-CONTROLS={len(PROFILES) * len(pair_controls)} FAILURES={pair_failures}")
assert pair_failures == 0
