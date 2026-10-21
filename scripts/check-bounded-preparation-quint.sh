#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"
if [[ $(quint --version) != 0.32.0 ]]; then
  echo 'Quint 0.32.0 is required' >&2
  exit 1
fi
command -v lmt >/dev/null
mkdir -p target/formal/quint
lmt formal/quint/bounded-preparation.md
model=target/formal/quint/boundedPreparation.qnt
tests=target/formal/quint/boundedPreparation_test.qnt
quint typecheck "$model"
quint typecheck "$tests"
quint test "$tests" --main boundedPreparationCoreTest --match '.*Test' \
  --backend typescript --seed 141 --verbosity 1

# Ownership-specific shutdown transitions are NOT implemented yet. Check both
# instances resolve; core sampled coverage is identical until that extension.
quint run "$model" --main boundedPreparationBorrowed --max-steps 40 \
  --max-samples 100 --seed 141 --backend typescript --verbosity 1
sample_log=target/formal/quint/bounded-preparation-core-sampled.log
quint run "$model" --main boundedPreparationOwned \
  --invariants noEarlySuccess boundedReservations coveredEncoding orderedPublication onePublisher countersAgree retirementIsSettled \
  --witnesses accepted overlapReached fullSlotsReached publicationNotRetiredReached allRetiredReached failureReached failureBehindPublicationReached fenceActiveReached cancellationActiveReached blockedAdmissionUnderCancellationReached drainAfterCancellationReached \
  --max-steps 40 --max-samples 10000 --seed 141 \
  --backend typescript --verbosity 1 | tee "$sample_log"
for witness in accepted overlapReached fullSlotsReached publicationNotRetiredReached allRetiredReached failureReached failureBehindPublicationReached fenceActiveReached cancellationActiveReached blockedAdmissionUnderCancellationReached drainAfterCancellationReached; do
  if ! grep -Eq "^${witness} was witnessed in [1-9][0-9]* trace" "$sample_log"; then
    echo "required witness was not reached: $witness" >&2
    exit 1
  fi
done
