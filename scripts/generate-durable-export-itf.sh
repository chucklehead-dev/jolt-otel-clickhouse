#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
model="$repo_root/target/formal/quint/durableExportAck.qnt"
target="$repo_root/target/formal/quint"
raw="$target/durable-export-success-raw.itf.json"
normalized="$target/durable-export-success.itf.json"
log="$target/durable-export-success.log"

for tool in quint jq
do
  if ! command -v "$tool" >/dev/null 2>&1
  then
    echo "$tool is required" >&2
    exit 1
  fi
done

if [[ ! -f "$model" ]]
then
  echo "tangled model is missing; run scripts/check-durable-export-quint.sh" >&2
  exit 1
fi

rm -f "$raw" "$normalized" "$log"
set +e
quint run "$model" \
  --main durableExportAckCorrected \
  --invariant 'not(durableSuccessReached)' \
  --max-steps 3 \
  --max-samples 10000 \
  --seed 1 \
  --backend typescript \
  --mbt \
  --out-itf "$raw" \
  --verbosity 1 >"$log" 2>&1
status=$?
set -e

if [[ $status -eq 0 ]] || ! grep -Eq '^\[violation\] Found an issue' "$log"
then
  cat "$log" >&2
  echo "durable success witness was not reached" >&2
  exit 1
fi

jq -c '{
  "#meta": {
    format: "ITF",
    "format-description": "https://apalache-mc.org/docs/adr/015adr-trace.html",
    source: "formal/quint/durable-export-ack.md",
    status: "witness",
    description: "Negated-invariant witness for a durable successful export"
  },
  vars: .vars,
  states: .states
}' "$raw" >"$normalized"

echo "[witness] reached durable successful export and wrote normalized ITF"
