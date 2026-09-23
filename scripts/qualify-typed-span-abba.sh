#!/usr/bin/env bash
set -euo pipefail

# Opt-in local, serial, same-source Durable comparison. No retries.
[[ $# == 1 && -d $1 ]] || { echo 'usage: qualify-typed-span-abba.sh EVIDENCE-PARENT' >&2; exit 64; }
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
parent=$(cd "$1" && pwd -P)
wrapper=${JOLT_WRAPPER:?set pinned Chez wrapper}
jolt=${JOLT_BIN:?set Jolt executable}
lib=${JOLT_CHDB_LIB:?set qualified libchdb}
header=${JOLT_CHDB_HEADER:?set matching header}
[[ -x $wrapper && -x $jolt && -f $lib && -f $header ]] || exit 2
[[ -z $(git -C "$repo" status --porcelain=v1 --untracked-files=all) ]] || {
  echo 'benchmark worktree must be clean' >&2; exit 2;
}
[[ $(git -C "$repo" rev-parse HEAD) == ${TYPED_ABBA_EXPECTED_HEAD:?set exact candidate HEAD} ]] || exit 2
[[ $(sha256sum "$jolt" | cut -d' ' -f1) == ${TYPED_ABBA_JOLT_SHA256:?} ]] || exit 2
[[ $(sha256sum "$lib" | cut -d' ' -f1) == ${TYPED_ABBA_LIB_SHA256:?} ]] || exit 2
[[ $(sha256sum "$header" | cut -d' ' -f1) == ${TYPED_ABBA_HEADER_SHA256:?} ]] || exit 2
[[ $(git -C "$repo" show HEAD:deps.edn | rg -o '0f51b99101bc5e840f957c073f87b6f877309a25' | wc -l) == 1 ]] || exit 2
root=$(mktemp -d "$parent/typed-span-abba.XXXXXXXX")
echo "EVIDENCE_ROOT=$root"
status=failed
finish() {
  local code=$?
  trap - EXIT
  git -C "$repo" status --porcelain=v1 --untracked-files=all > "$root/status.after" || true
  sha256sum "$repo/deps.edn" "$repo/src/otel/exporter/chdb.clj" \
    "$repo/bench/otel/exporter/chdb_typed_span_abba.clj" \
    "$repo/scripts/qualify-typed-span-abba.sh" > "$root/source.after.sha256" || true
  printf '%s exit=%s\n' "$status" "$code" > "$root/terminal.txt"
  exit "$code"
}
trap finish EXIT
cd "$repo"
git rev-parse HEAD HEAD^{tree} > "$root/source-commit.txt"
sha256sum "$wrapper" "$jolt" "$lib" "$header" deps.edn \
  src/otel/exporter/chdb.clj bench/otel/exporter/chdb_typed_span_abba.clj \
  scripts/qualify-typed-span-abba.sh > "$root/provenance.sha256"
"$wrapper" > "$root/chez-preflight.txt"
"$wrapper" "$jolt" --version > "$root/jolt-version.txt"
JOLT_CACHE_DIR="$root/preflight-cache" "$wrapper" "$jolt" -A:benchmark -Spath > "$root/classpath.txt"
JOLT_CACHE_DIR="$root/preflight-cache" "$wrapper" "$jolt" -A:benchmark -Sdescribe > "$root/describe.txt"
for ordinal in 1 2 3 4; do
  case "$ordinal" in 1|4) arm=A ;; 2|3) arm=B ;; esac
  cell="$root/$ordinal-$arm"
  mkdir -p "$cell/objects" "$cell/scratch-writer" "$cell/scratch-reader"
  for phase in writer reader; do
    cache="$cell/cache-$phase"
    printf '%s\n' "$wrapper $jolt -A:benchmark -m otel.exporter.chdb-typed-span-abba $phase $arm $cell" \
      > "$cell/$phase.command.txt"
    set +e
    JOLT_CACHE_DIR="$cache" /usr/bin/time -v -o "$cell/$phase.resources" \
      timeout --signal=TERM --kill-after=5s 600s \
      "$wrapper" "$jolt" -A:benchmark -m otel.exporter.chdb-typed-span-abba \
      "$phase" "$arm" "$cell" > "$cell/$phase.log" 2>&1
    child_status=$?
    set -e
    printf '%s\n' "$child_status" > "$cell/$phase.exit-status"
    [[ $child_status == 0 ]] || { echo "child failed: $ordinal-$arm $phase" >&2; exit "$child_status"; }
    [[ -s "$cell/$phase-report.edn" ]] || { echo 'missing report' >&2; exit 1; }
    sha256sum "$cell/$phase-report.edn" "$cell/$phase.log" "$cell/$phase.resources" \
      > "$cell/$phase-artifacts.sha256"
  done
done

# Compare bounded public report fields without retaining payloads or rows.
bb -e '(require (quote [clojure.edn :as edn]))
       (let [roots *command-line-args*
             writers (mapv #(edn/read-string (slurp (str % "/writer-report.edn"))) roots)
             readers (mapv #(edn/read-string (slurp (str % "/reader-report.edn"))) roots)
             same? (fn [key values] (= 1 (count (set (map key values)))))]
         (when-not (and (= ["A" "B" "B" "A"] (mapv :route writers))
                        (every? #(= 100 (get-in % [:encoding :count])) writers)
                        (every? #(= 100 (get-in % [:public-durable-confirmed :count])) writers)
                        (same? :payload-sha256 writers)
                        (same? :payload-utf8-bytes writers)
                        (same? :columns-sha256 writers)
                        (same? :full-row-sha256 readers)
                        (every? #(= {:groups 512 :rows 53760 :copies 105
                                     :exact-nanos true :typed-values-status true}
                                    (dissoc % :full-row-sha256)) readers))
           (throw (ex-info "typed ABBA cross-arm receipt mismatch" {})))
         (println :typed-abba-green :arms 4 :samples-per-arm 100
                  :payload-sha256 (:payload-sha256 (first writers))
                  :full-row-sha256 (:full-row-sha256 (first readers))))' \
  "$root/1-A" "$root/2-B" "$root/3-B" "$root/4-A" > "$root/comparison.log"
status=green
echo "QUALIFIED_EVIDENCE_ROOT=$root"
