#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
literate_spec="$repo_root/formal/quint/durable-export-ack.md"
target="$repo_root/target/formal/quint"
model="$target/durableExportAck.qnt"
tests="$target/durableExportAckTest.qnt"
required_quint_version=0.32.0
lmt_revision=62fe18f2f6a6e11c158ff2b2209e1082a4fcd59c

if ! command -v lmt >/dev/null 2>&1
then
  echo "lmt is required; install with:" >&2
  echo "  go install github.com/driusan/lmt@$lmt_revision" >&2
  exit 1
fi

if ! command -v quint >/dev/null 2>&1
then
  echo "Quint $required_quint_version is required" >&2
  exit 1
fi

actual_quint_version=$(quint --version)
if [[ "$actual_quint_version" != "$required_quint_version" ]]
then
  echo "expected Quint $required_quint_version, found $actual_quint_version" >&2
  exit 1
fi

mkdir -p "$target"
rm -f "$model" "$tests"
(
  cd "$repo_root"
  lmt "${literate_spec#$repo_root/}"
)

quint typecheck "$model"
quint typecheck "$tests"

quint test "$tests" \
  --main durableExportAckCorrectedTest \
  --match '.*Test' \
  --backend typescript \
  --verbosity 1

"$repo_root/scripts/generate-durable-export-itf.sh"
cmp "$target/durable-export-success.itf.json" \
  "$repo_root/formal/quint/traces/durable-export-success.itf.json"

quint test "$tests" \
  --main durableExportAckMutantTest \
  --match '.*Test' \
  --backend typescript \
  --verbosity 1

sample_log="$target/corrected-sampled.log"
quint run "$model" \
  --main durableExportAckCorrected \
  --invariants acknowledgementIsDurable barrierFollowsInsert emptyBatchSkipsPersistence resultIsUnambiguous \
  --witnesses emptySuccessReached insertFailureReached barrierFailureReached durableSuccessReached \
  --max-steps 3 \
  --max-samples 10000 \
  --backend typescript \
  --verbosity 1 | tee "$sample_log"

for witness in emptySuccessReached insertFailureReached barrierFailureReached durableSuccessReached
do
  if ! grep -Eq "^${witness} was witnessed in [1-9][0-9]* trace" "$sample_log"
  then
    echo "required witness was not reached: $witness" >&2
    exit 1
  fi
done

quint verify "$model" \
  --main durableExportAckCorrected \
  --invariant exporterSafety \
  --max-steps 3 \
  --backend apalache \
  --apalache-version 0.56.1 \
  --verbosity 1

mutant_log="$target/pre-barrier-ack-mutant.log"
set +e
quint verify "$model" \
  --main durableExportAckMutant \
  --invariant acknowledgementIsDurable \
  --max-steps 2 \
  --backend apalache \
  --apalache-version 0.56.1 \
  --out-itf "$target/pre-barrier-ack-mutant.itf.json" \
  --verbosity 1 >"$mutant_log" 2>&1
mutant_status=$?
set -e

if [[ $mutant_status -eq 0 ]] || ! grep -Eq '^\[violation\] Found an issue' "$mutant_log"
then
  cat "$mutant_log" >&2
  echo "pre-barrier acknowledgement mutant did not produce a counterexample" >&2
  exit 1
fi

echo "[expected counterexample] pre-barrier acknowledgement mutant"
cat "$mutant_log"
