#!/usr/bin/env bash
# Local functional gate only; no performance or full lifecycle qualification.
set -euo pipefail
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"
driver_root=${1:?driver source root required}
sdk_root=${2:?SDK source root required}
for task_path in "$driver_root" "$sdk_root"; do
  # These roots become EDN string literals. Reject rather than misquote an
  # unsupported path; never interpolate arbitrary source code into -Sdeps.
  [[ "$task_path" =~ ^/[A-Za-z0-9_./-]+$ ]] || exit 90
  test -d "$task_path/src"
done
task_jolt=${JOLT_BIN:?explicit stock Jolt binary required}
task_lib=${JOLT_CHDB_LIB:?explicit native library required}
wrapper=/home/chuck/ai-src/tools/jolt-with-chez-10.4.1
printf '%s  %s\n' 7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94 "$task_jolt" | sha256sum -c -
printf '%s  %s\n' a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557 "$task_lib" | sha256sum -c -
task_root=$(mktemp -d /tmp/chdb-preparation-native-20261021.XXXXXXXX)
printf 'EVIDENCE_ROOT=%s\n' "$task_root"
override="{:paths [\"src\" \"test\"] :deps {io.github.chucklehead-dev/jolt-chdb {:local/root \"$driver_root\"} io.github.casselc/otel {:local/root \"$sdk_root\" :exclusions [jolt-lang/jolt-crypto]}}}"
base=("$wrapper" env JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0 JOLT_CHDB_LIB="$task_lib"
      "$task_jolt" -Srepro -Sdeps "$override" -A:stock-span-codec)
sources=(src/otel/exporter/chdb.clj src/otel/exporter/chdb/preparation.clj
         test/otel/exporter/chdb_preparation_native_smoke.clj
         test/otel/exporter/chdb_preparation_adapter_test.clj
         "$driver_root/src/jdbc/chdb/durable/writer.clj"
         "$driver_root/src/jdbc/chdb/durable.clj"
         "$driver_root/src/jdbc/chdb/owned_statement.clj"
         "$driver_root/resources/jdbc/chdb/owned_ascii_wal.ss"
         "$sdk_root/src/otel/sdk/export.clj" "$sdk_root/src/otel/sdk/lifecycle.clj"
         "$sdk_root/src/otel/sdk/preparation.clj" "$wrapper" "$task_jolt" "$task_lib")
sha256sum "${sources[@]}" > "$task_root/source-before.sha256"
"${base[@]}" -Spath > "$task_root/classpath.txt"
for ownership in owned borrowed; do
  mkdir "$task_root/$ownership"
  timeout 120 "${base[@]}" -m otel.exporter.chdb-preparation-native-smoke \
    writer "$task_root/$ownership" "$ownership" > "$task_root/$ownership-writer.log" 2>&1
  timeout 120 "${base[@]}" -m otel.exporter.chdb-preparation-native-smoke \
    reader "$task_root/$ownership" > "$task_root/$ownership-reader.log" 2>&1
  grep -q '^:native-preparation-writer-confirmed' "$task_root/$ownership-writer.log"
  grep -q '^:native-preparation-reader-confirmed' "$task_root/$ownership-reader.log"
done
sha256sum "${sources[@]}" > "$task_root/source-after.sha256"
cmp "$task_root/source-before.sha256" "$task_root/source-after.sha256"
printf 'NATIVE_FUNCTIONAL_SMOKE_PASSED=%s\n' "$task_root"
# Deliberately no EXIT cleanup: preserve failures and the first native result.
# Retention is decided after both ownership/readback gates and source checks.
