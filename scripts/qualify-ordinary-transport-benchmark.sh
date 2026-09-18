#!/usr/bin/env bash
set -euo pipefail
# Validate intent before provider inspection, subprocesses or native work.
# Metadata-only mode is the documented environment setting, not a CLI flag.
case "${1:-}" in
  '') [[ $# == 0 ]] || { echo invalid-benchmark-arguments; exit 64; } ;;
  --bounded-inner) [[ $# == 1 ]] || { echo invalid-benchmark-arguments; exit 64; } ;;
  --attest-classpath) [[ $# == 7 ]] || { echo invalid-benchmark-arguments; exit 64; } ;;
  *) echo invalid-benchmark-arguments; exit 64 ;;
esac
clean_provider() {
  local status line
  status=$(git -C "$1" status --porcelain=v1 --untracked-files=all) || return 1
  while IFS= read -r line; do
    # Jolt's pinned resolver publishes cached checkouts with this exact marker.
    # Never permit a tracked modification or any other untracked source file.
    [[ -z "$line" || "$line" == '?? .jolt-git-ok' ]] || return 1
  done <<< "$status"
}
# Called inside every bounded child, before loading the benchmark/native fixture.
attest_graph() {
  local classpath=$1 declared=$2 output=$3 binary=$4 mode=$5 reviewed=$6
  local entry provider marker expected checkout revision candidate
  local -a lines roots pins providers markers expected_pins db_pins
  mapfile -t lines < "$classpath"; mapfile -t pins < "$declared"
  [[ ${#lines[@]} == 1 && ${#pins[@]} == 4 ]] || { echo malformed-graph-receipt; return 1; }
  for expected in "${pins[@]}"; do [[ "$expected" =~ ^[a-f0-9]{40}$ ]] || return 1; done
  IFS=: read -r -a roots <<< "${lines[0]}"
  providers=(driver sdk json crypto db)
  markers=(jdbc/chdb.clj otel/sdk/metrics.clj clojure/data/json.clj jolt/crypto.clj db/jdbc.clj)
  expected_pins=("${pins[0]}" "${pins[1]}" "${pins[2]}" "${pins[3]}" "")
  [[ "$mode" != reviewed-source ]] || expected_pins[0]=$reviewed
  printf 'driver-mode %s\nbinary ' "$mode" > "$output"
  sha256sum "$binary" >> "$output"
  printf 'wrapper ' >> "$output"
  sha256sum "$TASK_WRAPPER" >> "$output"
  for entry in 0 1 2 3 4; do
    provider=${providers[$entry]}; marker=${markers[$entry]}; checkout=
    for candidate in "${roots[@]}"; do
      [[ ! -f "$candidate/$marker" ]] || {
        [[ -z "$checkout" ]] || { echo ambiguous-source-provider; return 1; }
        checkout=$(git -C "$candidate" rev-parse --show-toplevel)
      }
    done
    [[ -n "$checkout" ]] && clean_provider "$checkout" || { echo missing-or-dirty-provider; return 1; }
    revision=$(git -C "$checkout" rev-parse HEAD); expected=${expected_pins[$entry]}
    [[ "$revision" == "$expected" ]] || { echo source-pin-mismatch; return 1; }
    printf '%s %s %s\n' "$provider" "$revision" "$checkout" >> "$output"
    if [[ "$provider" == driver ]]; then
      TASK_DRIVER_DEPS="$checkout/deps.edn" "$TASK_WRAPPER" "$binary" -Srepro -e \
        '(require (quote clojure.edn)) (println (get-in (clojure.edn/read-string (slurp (System/getenv "TASK_DRIVER_DEPS"))) [:deps (quote jolt-lang/db) :git/sha]))' > "$declared.db"
      mapfile -t db_pins < "$declared.db"
      [[ ${#db_pins[@]} == 1 && "${db_pins[0]}" =~ ^[a-f0-9]{40}$ ]] || return 1
      expected_pins[4]=${db_pins[0]}
    fi
  done
}
if [[ "${1:-}" == --attest-classpath ]]; then
  attest_graph "$2" "$3" "$4" "$5" "$6" "$7"
  exit
fi
[[ "$(uname -s)" == Linux ]] || { echo unsupported-benchmark-host; exit 1; }
script=$(realpath "$0")
worktree=$(cd "$(dirname "$script")/.." && pwd -P)
fixture="$worktree/bench/otel/exporter/chdb_ordinary_transport_benchmark.clj"
jolt=$(realpath "${JOLT_BIN:?set qualified Jolt}")
wrapper=$(realpath "${JOLT_WRAPPER:?set Chez 10.4.1 wrapper}")
lib=$(realpath "${JOLT_CHDB_LIB:?set qualified library}")
header=$(realpath "${JOLT_CHDB_HEADER:?set matched header}")
driver_mode=${JOLT_ORDINARY_DRIVER_MODE:-root-pin}
case "$driver_mode" in root-pin|reviewed-source) ;; *) echo invalid-driver-mode; exit 1 ;; esac
driver= driver_rev= options=()
if [[ "$driver_mode" == reviewed-source ]]; then
  driver=$(realpath "${JOLT_CHDB_SOURCE_ROOT:?set clean reviewed driver}")
  driver_rev=${JOLT_EXPECTED_DRIVER_REV:?set reviewed full driver commit}
  [[ "$driver_rev" =~ ^[a-f0-9]{40}$ ]]
  [[ "$(git -C "$driver" rev-parse HEAD)" == "$driver_rev" ]]
  clean_provider "$driver"
  options=(-Sdeps "{:deps {io.github.chucklehead-dev/jolt-chdb {:local/root \"$driver\"}}}")
fi
[[ "$(dirname "$lib")" == "$(dirname "$header")" ]]
[[ "$driver" != *'"'* && "$driver" != *'\'* && "$driver" != *$'\n'* ]]
[[ -x "$jolt" && -x "$wrapper" ]]
for spec in "${JOLT_EXPECTED_BINARY_SHA256:?}:$jolt" "${JOLT_EXPECTED_WRAPPER_SHA256:?}:$wrapper" "${JOLT_CHDB_EXPECTED_LIBRARY_SHA256:?}:$lib" "${JOLT_CHDB_EXPECTED_HEADER_SHA256:?}:$header"; do
  hash=${spec%%:*}; path=${spec#*:}
  [[ "$hash" =~ ^[a-f0-9]{64}$ ]]
  printf '%s  %s\n' "$hash" "$path" | sha256sum -c -
done
if pgrep -x jolt >/dev/null; then echo runtime-slot-occupied; exit 1; fi
if [[ "${1:-}" != --bounded-inner ]]; then
  outer=$(mktemp -d "${TMPDIR:-/tmp}/ordinary-transport-outer.XXXXXXXX")
  echo "OUTER_EVIDENCE_ROOT=$outer"
  set +e
  /usr/bin/time -v -o "$outer/resources.txt" timeout --signal=TERM --kill-after=5s 360s bash "$script" --bounded-inner > "$outer/outer.log" 2>&1
  status=$?
  set -e
  printf '%s\n' "$status" > "$outer/exit-status.txt"
  sha256sum "$outer/outer.log" "$outer/resources.txt"
  exit "$status"
fi
root=$(mktemp -d "${TMPDIR:-/tmp}/ordinary-transport-abba.XXXXXXXX")
echo "EVIDENCE_ROOT=$root"
cd "$worktree"
active_child=
observed_child_failure=
observed_child_label=
child_receipt_failed=0
green_marker=
printf 'pending\n' > "$root/comparison-status.txt"
finalize_evidence() {
  local primary=$? receipt=0 path
  local -a available=()
  trap - EXIT HUP INT TERM
  set +e
  if [[ -n "$observed_child_failure" ]]; then
    # Receipt I/O failure must never replace an already observed child failure.
    primary=$observed_child_failure
    printf '%s %s\n' "$observed_child_label" "$observed_child_failure" > "$root/observed-child-failure.txt" || receipt=1
  fi
  [[ "$child_receipt_failed" == 0 ]] || receipt=1
  # Never replace an observed child exit with the enclosing shell's status.
  if [[ -n "$active_child" ]]; then
    printf 'unobserved-interrupted\n' > "$root/$active_child.exit-status"
  fi
  git status --porcelain=v1 > "$root/status.after" || receipt=1
  sha256sum src/otel/exporter/chdb.clj deps.edn > "$root/loaded-source.after.sha256" || receipt=1
  sha256sum "$fixture" "$script" > "$root/harness-source.after.sha256" || receipt=1
  printf '%s\n' "$primary" > "$root/primary-exit-status.txt" || receipt=1
  printf '%s\n' "$primary" > "$root/exit-status.txt" || receipt=1
  if [[ "$primary" != 0 ]]; then
    printf 'unqualified-incomplete-or-failed\n' > "$root/comparison-status.txt" || receipt=1
  fi
  # Fixed public receipt families only; never traverse stores or compiler caches.
  shopt -s nullglob
  for path in "$root"/*.log "$root"/*.resources "$root"/*.classpath \
      "$root"/*.declared "$root"/*.declared.db "$root"/*.graph \
      "$root"/*.payload-controls "$root"/*.exit-status "$root"/status.before \
      "$root"/status.after "$root"/loaded-source.*.sha256 \
      "$root"/harness-source*.sha256 "$root"/exporter-source.txt \
      "$root"/driver-mode.txt "$root"/comparison-status.txt "$root"/exit-status.txt \
      "$root"/primary-exit-status.txt "$root"/observed-child-failure.txt; do
    [[ ! -f "$path" || -L "$path" ]] || available+=("$path")
  done
  if (( ${#available[@]} )); then
    sha256sum "${available[@]}" > "$root/available-artifacts.sha256" || receipt=1
  fi
  if [[ "$receipt" != 0 ]]; then
    [[ "$primary" != 0 ]] || primary=1
    printf 'unqualified-evidence-publication-failed\n' > "$root/comparison-status.txt"
    printf '%s\n' "$primary" > "$root/exit-status.txt"
    # Best effort refreshed hashes, never masking the receipt failure.
    sha256sum "${available[@]}" > "$root/available-artifacts.sha256"
  fi
  if [[ "$primary" == 0 && -n "$green_marker" ]]; then printf '%s\n' "$green_marker"; fi
  printf 'EVIDENCE_EXIT=%s RECEIPT_STATUS=%s\n' "$primary" "$receipt"
  exit "$primary"
}
trap finalize_evidence EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
git rev-parse HEAD > "$root/exporter-source.txt"
printf '%s\n' "$driver_mode" > "$root/driver-mode.txt"
sha256sum "$fixture" "$script" > "$root/harness-source.sha256"
git status --porcelain=v1 > "$root/status.before"
sha256sum src/otel/exporter/chdb.clj deps.edn > "$root/loaded-source.before.sha256"
expression='(try
  (println :benchmark-stage :require :enter)
  (require (quote jdbc.chdb))
  (when-not (ns-resolve (quote jdbc.chdb) (quote insert-json-rows!))
    (println :required-driver-row-api-missing) (System/exit 1))
  (println :benchmark-stage :require :return)
  (println :benchmark-stage :fixture :enter)
  (load-file (System/getenv "BENCH_FIXTURE"))
  (println :benchmark-stage :fixture :return)
  (println :benchmark-stage :main :enter)
  ((ns-resolve (quote otel.exporter.chdb-ordinary-transport-benchmark) (quote -main))
   (System/getenv "TASK_MODE") (System/getenv "TASK_ROUTE") (System/getenv "TASK_DB") "benchmark")
  (println :benchmark-stage :main :return)
  (catch Throwable _ (println :ordinary-benchmark-launch-failed) (System/exit 1)))'
child() {
  local mode=$1 route=$2 db=$3 label=$4
  local cache status
  cache=$(mktemp -d "$root/cache-$label.XXXXXXXX")
  active_child=$label
  printf 'running\n' > "$root/$label.exit-status"
  printf 'CHILD_BEGIN=%s\n' "$label"
  set +e
  /usr/bin/time -v -o "$root/$label.resources" timeout --signal=TERM --kill-after=5s 60s \
    env -i HOME="${HOME:?}" PATH="$(dirname "$jolt"):${HOME}/.local/bin:/usr/local/bin:/usr/bin:/bin" LANG=C.UTF-8 \
    JOLT_CACHE_DIR="$cache" JOLT_CHDB_LIB="$lib" BENCH_FIXTURE="$fixture" \
    TASK_MODE="$mode" TASK_ROUTE="$route" TASK_DB="$db" TASK_EXPRESSION="$expression" \
    TASK_CLASSPATH="$root/$label.classpath" TASK_GRAPH="$root/$label.graph" \
    TASK_DECLARED="$root/$label.declared" TASK_SCRIPT="$script" TASK_BINARY="$jolt" TASK_WRAPPER="$wrapper" \
    TASK_DRIVER_MODE="$driver_mode" TASK_REVIEWED_REV="$driver_rev" TASK_PROVENANCE_ONLY="${JOLT_ORDINARY_PROVENANCE_ONLY:-0}" \
    bash -c 'set -euo pipefail
      "$@" -Spath > "$TASK_CLASSPATH"
      "$@" -e '\''(require (quote clojure.edn)) (let [d (:deps (clojure.edn/read-string (slurp "deps.edn")))] (doseq [lib [(quote io.github.chucklehead-dev/jolt-chdb) (quote io.github.casselc/otel) (quote org.clojure/data.json) (quote jolt-lang/jolt-crypto)]] (println (get-in d [lib :git/sha]))))'\'' > "$TASK_DECLARED"
      bash "$TASK_SCRIPT" --attest-classpath "$TASK_CLASSPATH" "$TASK_DECLARED" "$TASK_GRAPH" "$TASK_BINARY" "$TASK_DRIVER_MODE" "$TASK_REVIEWED_REV"
      [[ "$TASK_PROVENANCE_ONLY" != 1 ]] || { echo ordinary-provenance-qualified; exit 0; }
      "$@" -e "$TASK_EXPRESSION"' _ \
    "$wrapper" "$jolt" -Srepro "${options[@]}" -A:test > "$root/$label.log" 2>&1
  status=$?
  set -e
  if [[ "$status" != 0 && -z "$observed_child_failure" ]]; then
    # The first terminal writer exit owns the primary result. A subsequent
    # recovery-readback receipt may fail, but must never mask that writer.
    observed_child_failure=$status
    observed_child_label=$label
  fi
  if printf '%s\n' "$status" > "$root/$label.exit-status"; then
    :
  else
    child_receipt_failed=1
    [[ "$status" != 0 ]] || status=1
  fi
  active_child=
  printf 'CHILD_END=%s EXIT=%s\n' "$label" "$status"
  return "$status"
}
terminal-record-failure() {
  local label=$1
  [[ -f "$root/$label.exit-status" ]] || return 1
  [[ "$(<"$root/$label.exit-status")" != 0 ]] || return 1
  grep -Fxq ':benchmark-red-diagnostic :category :migration-failed :phase :record :version 1 :statement-index :unknown' \
    "$root/$label.log"
}
observe-terminal-record-failure() {
  local db=$1 label=$2 status
  # A fresh, read-only child observes only the closed v1 registry cardinality.
  # It never runs the full reader because setup did not complete.
  if child registry-readback recovery "$db" "$label"; then
    status=0
  else
    status=$?
  fi
  [[ "$status" == 0 ]] || return "$status"
  if ! grep -Eq '^:registry-readback :version 1 :status :(absent|exact-one|duplicate|unavailable)$' \
      "$root/$label.log"; then
    child_receipt_failed=1
    return 1
  fi
}
if [[ "${JOLT_ORDINARY_PROVENANCE_ONLY:-0}" == 1 ]]; then
  child probe candidate "$root/probe-unused" provenance-only
  grep -Fxq ordinary-provenance-qualified "$root/provenance-only.log"
  printf 'provenance-only-not-performance-qualified\n' > "$root/comparison-status.txt"
  green_marker=ORDINARY_PROVENANCE_ONLY_GREEN=no-native-or-throughput-claim
  exit 0
fi
for arm in A1 B1 B2 A2; do
  case "$arm" in A*) route=legacy ;; B*) route=candidate ;; esac
  db=$(mktemp -d "$root/storage-$arm.XXXXXXXX")
  if child writer "$route" "$db" "$arm-writer"; then
    writer_status=0
  else
    writer_status=$?
  fi
  if [[ "$writer_status" != 0 ]]; then
    if terminal-record-failure "$arm-writer"; then
      # The original writer status remains primary even if this receipt fails.
      observe-terminal-record-failure "$db" "$arm-registry-readback" || true
    fi
    exit "$writer_status"
  fi
  grep -Fxq ":writer-green :route :$route :rows 25600" "$root/$arm-writer.log"
  grep '^:payload-control' "$root/$arm-writer.log" > "$root/$arm.payload-controls"
  [[ "$(wc -l < "$root/$arm.payload-controls")" == 2 ]]
  child reader "$route" "$db" "$arm-reader"
  grep -Fxq ':fresh-reader-green :groups 1024 :rows 25600 :full-rows-equal true :exact-nanos true :typed-values-status true' "$root/$arm-reader.log"
done
for arm in A1 B1 B2 A2; do
  cmp "$root/A1-writer.graph" "$root/$arm-writer.graph"
  cmp "$root/A1-writer.graph" "$root/$arm-reader.graph"
  cmp "$root/A1.payload-controls" "$root/$arm.payload-controls"
done
git status --porcelain=v1 > "$root/status.after"
sha256sum src/otel/exporter/chdb.clj deps.edn > "$root/loaded-source.after.sha256"
cmp "$root/status.before" "$root/status.after"
cmp "$root/loaded-source.before.sha256" "$root/loaded-source.after.sha256"
sha256sum "$fixture" "$script" > "$root/harness-source.after.sha256"
cmp "$root/harness-source.sha256" "$root/harness-source.after.sha256"
printf 'all-arms-compared-source-parity-passed\n' > "$root/comparison-status.txt"
green_marker=ORDINARY_TRANSPORT_ABBA_GREEN=no-p99-allocation-or-Durable-target-claim
sha256sum "$root"/*.log "$root"/*.resources "$root"/*.payload-controls "$root"/*.graph
# Retain owned stores and safe aggregate logs; no automatic retries/deletion.
