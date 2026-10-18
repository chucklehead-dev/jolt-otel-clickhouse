#!/usr/bin/env bash
set -euo pipefail
test "$#" -eq 1
case "$1" in
  saturated-A1|saturated-A2) task_lanes=1; task_arrival=saturated ;;
  saturated-B) task_lanes=2; task_arrival=saturated ;;
  paced-A1|paced-A2) task_lanes=1; task_arrival=paced ;;
  paced-B) task_lanes=2; task_arrival=paced ;;
  *) exit 64 ;;
esac
task_label=$1
task_evidence=/home/chuck/ai-src/evidence
task_exporter=/home/chuck/ai-src/worktrees/exporter-bounded-insert-budget-20261017
task_fixture=/home/chuck/ai-src/worktrees/chdb-current-typed-storage-0821-20261014
task_json=/home/chuck/ai-src/worktrees/data-json-qualified-8d557cc-20261017
task_sdk=/home/chuck/ai-src/worktrees/otel-batch-threshold-wakeup-20261017
for task_entry in "$task_exporter:67e28b59bfd215da982682dd9c59d51d52d460ed" "$task_fixture:3fd96c4691c5db49ed4d3ef55622aaa6b9074127" "$task_json:8d557cc18eb9526ed29ae5613089c3793d5973fa" "$task_sdk:37c684a2c370fc82fde82331635973bce87a52b3"; do
  task_path=${task_entry%:*}
  test "$(git -C "$task_path" rev-parse HEAD)" = "${task_entry##*:}"
  git -C "$task_path" diff --quiet HEAD
done
task_report=$task_evidence/sdk-trace-affinity-$task_label-20261018
test ! -e "$task_report.writer.log"
task_root=$(mktemp -d /tmp/sdk-trace-affinity-20261018.XXXXXXXX)
printf 'arm=%s lanes=%s arrival=%s owned_store=%s\n' "$task_label" "$task_lanes" "$task_arrival" "$task_root"
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
test "$(sha256sum "$JOLT_CHDB_LIB" | cut -d ' ' -f1)" = a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557
export SDK_STORAGE_ARM=local SDK_STREAM_ARM=stream SDK_EXPORT_CALLERS=1
export CHDB_BENCH_OWNED_OUTPUT=true CHDB_BENCH_SAMPLE_COUNTERS=true
task_deps="{:paths [\"src\" \"resources\" \"test\" \"bench\" \"$task_fixture/bench\" \"$task_fixture/test\"] :deps {org.clojure/data.json {:local/root \"$task_json\"} io.github.casselc/otel {:local/root \"$task_sdk\"}}}"
cd "$task_exporter"
# A failed writer leaves categorical evidence and its exact owned store. Do
# not run recovery/cleanup or silently retry a failed arrival/capacity gate.
if timeout --signal=TERM --kill-after=5s 180s "$task_evidence/jolt-measured-1ae-20261015.sh" \
  -Srepro -Sdeps "$task_deps" "$task_evidence/sdk-trace-affinity-screen-20261018.clj" \
  "$task_root" "$task_lanes" "$task_arrival" > "$task_report.writer.log" 2>&1; then
  cp "$task_root/writer.edn" "$task_report.writer.edn"
  cmp "$task_root/writer.edn" "$task_report.writer.edn"
else
  task_code=$?
  if test -f "$task_root/progress.edn"; then
    cp "$task_root/progress.edn" "$task_report.incomplete.edn"
    cmp "$task_root/progress.edn" "$task_report.incomplete.edn"
  fi
  exit "$task_code"
fi
export CHDB_BENCH_METADATA_READER=stock
timeout --signal=TERM --kill-after=5s 120s "$task_evidence/jolt-measured-1ae-20261015.sh" \
  -Srepro -Sdeps "$task_deps" "$task_evidence/sdk-trace-affinity-reader-20261018.clj" \
  "$task_root" > "$task_report.reader.log" 2>&1
cp "$task_root/reader.edn" "$task_report.reader.edn"
cmp "$task_root/reader.edn" "$task_report.reader.edn"
bb -e '(let [[prefix lanes arrival] *command-line-args* lanes (parse-long lanes)
              w (clojure.edn/read-string (slurp (str prefix ".writer.edn")))
              r (clojure.edn/read-string (slurp (str prefix ".reader.edn")))
              batches (:batches w) stats (:stats w) expected (:expected-lane-counts w)]
          (assert (= [160000 5000 32 lanes lanes 1 10000 625 8 arrival]
                     (mapv w [:rows :batch-size :rounds :lanes :distinct-exporter-count
                              :shared-connection-owner-count :total-queue-capacity
                              :trace-count :siblings-per-trace :arrival])))
          (assert (and (= lanes (count stats) (count expected)) (= 160000 (reduce + expected))))
          (doseq [[s n] (map vector stats expected)]
            (assert (= {:queue-size 0 :attempted-span-count n :exported-span-count n
                        :failed-span-count 0 :dropped-count 0} s)))
          (assert (and (:per-trace-counts-checked w) (:per-trace-callback-order-checked w)))
          (assert (= (set (range lanes)) (set (map :lane batches))))
          (assert (<= 1 (count batches) 256))
          (assert (every? #(and (:ok %) (<= 1 (:rows %) 5000)) batches))
          (assert (= 160000 (reduce + (map :rows batches))))
          (assert (= (+ 3 (count batches)) (:stream-calls w)))
          (doseq [[_ xs] (group-by :lane batches)]
            (assert (every? (fn [[a b]] (<= (:end-nanos a) (:start-nanos b)))
                            (partition 2 1 (sort-by :start-nanos xs)))))
          (assert (= "file:/home/chuck/ai-src/worktrees/otel-batch-threshold-wakeup-20261017/src/otel/sdk/export.clj" (:sdk-source w)))
          (assert (and (false? (:force-flush-before-measurement w)) (false? (:tail-qualified w))))
          (when (= arrival "paced")
            (assert (= 20000 (:target-arrival-rows-per-second w)))
            (assert (zero? (:load-generator-capacity-wait-nanos w))))
          (assert (and (:passed r) (:full-physical-rows-equal r) (:typed-values-status r)
                       (= [175000 5000 625 35] (mapv r [:rows :groups :trace-count :per-record-multiplicity]))))
          (prn {:verified true :lanes lanes :arrival arrival :recovered-rows (:rows r)
                :confirmed-rows-per-second (:confirmed-rows-per-second w)
                :actual-export-batches (count batches) :trace-affinity-and-callback-order true
                :tail-qualified false}))' \
  "$task_report" "$task_lanes" "$task_arrival" | tee "$task_report.validation.edn"
du -sh "$task_root"
[[ "$task_root" =~ ^/tmp/sdk-trace-affinity-20261018\.[A-Za-z0-9]+$ ]]
rm -rf -- "$task_root"
