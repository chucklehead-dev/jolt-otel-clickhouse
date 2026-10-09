"""Offline manifest contract; not artifact authentication or permission to execute."""
import argparse
import pathlib
import re
import sys

PROFILE = "sorted-collector-7c"
FIXED = {
    "schema": "1",
    "repository": "casselc/jolt",
    "compiler_flavor": "plain",
    "compiler_source": "7c57cf7e5bca789efe31706d4f9830df8a08306a",
    "compiler_tree": "d5dca7ebaba66cf429d2c63219026b8415675dec",
    "workflow_path": ".github/workflows/durable-runtime-artifact.yml",
    "runner_os": "Linux",
    "runner_arch": "X64",
    "chez_version": "10.4.1",
    "require_buildlib": "1",
    "binary_version": "jolt v0.8.17-49-g7c57cf7e",
    "gate": "ci-pass",
    "ranged_append_ascii": "98",
    "fixed_vector_constructor": "pass",
    "fixed_vector_constructor_control": "old-rejected",
    "sorted_dispatch_allocation": "pass",
    "sorted_dispatch_control": "old-rejected",
}
EXTERNAL = {"workflow_source", "run_id", "run_attempt", "binary_sha256"}
AUXILIARY = {"image_os", "image_version", "chez_sha256", "cc_version", "cc_sha256",
             "build_command", "chez_configure"}
KEYS = set(FIXED) | EXTERNAL | AUXILIARY


def invalid():
    raise ValueError("invalid collector runtime manifest")


class QuietParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse normally echoes rejected arguments; keep malformed CLI input
        # on the same fixed diagnostic boundary as rejected manifest content.
        print("collector-runtime-manifest-rejected", file=sys.stderr)
        raise SystemExit(2)


def validate(data, profile, expected):
    """Validate a closed manifest against independently supplied expected pins.

    The caller must separately authenticate run/event/branch/repository, artifact
    association, ZIP digest and exact members, binary digest, and SHA256SUMS.
    Successful validation does not authorize execution or credential exposure.
    """
    if profile != PROFILE or set(expected) != EXTERNAL:
        invalid()
    for key in ("workflow_source", "binary_sha256"):
        size = 40 if key == "workflow_source" else 64
        if not re.fullmatch(r"[a-f0-9]{%d}" % size, expected[key]):
            invalid()
    for key in ("run_id", "run_attempt"):
        if not re.fullmatch(r"[1-9][0-9]*", expected[key]):
            invalid()
    if len(data) > 65536 or not data.endswith(b"\n"):
        invalid()
    try:
        text = data.decode("ascii")
    except UnicodeDecodeError:
        invalid()
    if any(ord(char) < 32 and char != "\n" for char in text):
        invalid()
    fields, modules = {}, set()
    section = False
    closed = False
    for line in text.splitlines():
        if line == "submodules_begin":
            if section or closed:
                invalid()
            section = True
        elif line == "submodules_end":
            if not section:
                invalid()
            section, closed = False, True
        elif section:
            # Clean initialized gitlinks only, with bounded relative paths.
            match = re.fullmatch(r" [a-f0-9]{40} ([A-Za-z0-9_./-]+)(?: \([^\n]+\))?", line)
            if not match:
                invalid()
            path = match[1]
            if path.startswith("/") or any(part in ("", ".", "..") for part in path.split("/")) or path in modules:
                invalid()
            modules.add(path)
        else:
            if closed or len(line) > 1024 or "=" not in line:
                invalid()
            key, value = line.split("=", 1)
            if key not in KEYS or key in fields or not value:
                invalid()
            fields[key] = value
    if section or not closed or not modules or set(fields) != KEYS:
        invalid()
    if any(fields[key] != value for key, value in {**FIXED, **expected}.items()):
        invalid()
    for key in ("chez_sha256", "cc_sha256"):
        if not re.fullmatch(r"[a-f0-9]{64}", fields[key]):
            invalid()
    return True


def main():
    parser = QuietParser(description=__doc__)
    parser.add_argument("manifest", type=pathlib.Path)
    parser.add_argument("--profile", required=True)
    for key in sorted(EXTERNAL):
        parser.add_argument("--" + key.replace("_", "-"), required=True)
    args = parser.parse_args()
    try:
        with args.manifest.open("rb") as source:
            data = source.read(65537)
        validate(data, args.profile, {key: getattr(args, key) for key in EXTERNAL})
    except (ValueError, OSError):
        # Never echo a rejected field, file body, path, or exception cause.
        print("collector-runtime-manifest-rejected", file=sys.stderr)
        return 1
    print("collector-runtime-manifest-pass")
    return 0


if __name__ == "__main__":
    sys.exit(main())
