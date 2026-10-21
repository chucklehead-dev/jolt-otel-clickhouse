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
for ownership in Owned Borrowed; do
  quint test "$tests" --main "boundedPreparation${ownership}LifecycleTest" --match '.*Test' \
    --backend typescript --seed 141 --verbosity 1
done

# Preserve core reachability separately: lifecycle choices intentionally allow
# shutdown before any acceptance, and premature worker death can quarantine.
sample_log=target/formal/quint/bounded-preparation-core-sampled.log
quint run "$model" --main boundedPreparationOwned --step coreStep \
  --invariants noEarlySuccess boundedReservations coveredEncoding orderedPublication onePublisher countersAgree retirementIsSettled flushNeverConfusesAdmissionWithSuccess \
  --witnesses accepted overlapReached fullSlotsReached publicationNotRetiredReached allRetiredReached failureReached failureBehindPublicationReached fenceActiveReached cancellationActiveReached blockedAdmissionUnderCancellationReached drainAfterCancellationReached flushPendingWorkReached flushSucceededReached flushFailedReached \
  --max-steps 40 --max-samples 10000 --seed 141 \
  --backend typescript --verbosity 1 | tee "$sample_log"
for witness in accepted overlapReached fullSlotsReached publicationNotRetiredReached allRetiredReached failureReached failureBehindPublicationReached fenceActiveReached cancellationActiveReached blockedAdmissionUnderCancellationReached drainAfterCancellationReached flushPendingWorkReached flushSucceededReached flushFailedReached; do
  if ! grep -Eq "^${witness} was witnessed in [1-9][0-9]* trace" "$sample_log"; then
    echo "required witness was not reached: $witness" >&2
    exit 1
  fi
done

# Full lifecycle step in BOTH concrete ownership instances. This is sampled
# safety/reachability only, not exhaustive checking or a liveness guarantee.
lifecycle_witnesses=(shutdownStartedReached preparerTerminalReached waitStartedReached
  waitFailedReached sdkSettledReached exporterCloseStartedReached exporterCloseFailedReached
  connectionCloseStartedReached connectionCloseFailedReached publisherTerminalReached
  shutdownSucceededReached shutdownFailedReached stableProofReached cleanupReached)
for ownership in Owned Borrowed; do
  lifecycle_log="target/formal/quint/bounded-preparation-${ownership}-lifecycle-sampled.log"
  quint run "$model" --main "boundedPreparation${ownership}" \
    --invariants noEarlySuccess boundedReservations coveredEncoding orderedPublication onePublisher countersAgree retirementIsSettled flushNeverConfusesAdmissionWithSuccess exporterCloseIsOwned waitSuccessIsTermination resourceProofIsStable cleanupRequiresProof closeIsSingleUse \
    --witnesses "${lifecycle_witnesses[@]}" overlapReached \
    --max-steps 60 --max-samples 3000 --seed 141 --backend typescript --verbosity 1 | tee "$lifecycle_log"
  for witness in "${lifecycle_witnesses[@]}"; do
    if ! grep -Eq "^${witness} was witnessed in [1-9][0-9]* trace" "$lifecycle_log"; then
      echo "required ${ownership} lifecycle witness was not reached: $witness" >&2
      exit 1
    fi
  done
done
