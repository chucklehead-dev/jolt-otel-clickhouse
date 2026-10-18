#!/usr/bin/env bash
set -euo pipefail
test "$#" -eq 1
task_label=$1
task_evidence=/home/chuck/ai-src/evidence
task_control=/home/chuck/ai-src/worktrees/exporter-live-empty-link-writers-20261018
case "$task_label" in
  control-A1|control-A2)
    task_exporter=$task_control
    task_exporter_sha=473edbb06fa11070968de35f8b953f62cb4b6cd3 ;;
  candidate-B|paired)
    task_exporter=/home/chuck/ai-src/worktrees/exporter-declared-string-collector-20261018
    task_exporter_sha=$(git -C "$task_exporter" rev-parse e1723f4) ;;
  paired-v2)
    task_exporter=/home/chuck/ai-src/worktrees/exporter-declared-string-collector-20261018
    task_exporter_sha=57415464e2685703dc8a8d49a017ed5f12317ef0 ;;
  *) exit 64 ;;
esac
task_paired=false
case "$task_label" in paired|paired-v2) task_paired=true ;; esac
task_fixture=/home/chuck/ai-src/worktrees/chdb-current-typed-storage-0821-20261014
task_json=/home/chuck/ai-src/worktrees/data-json-qualified-8d557cc-20261017
task_sdk=/home/chuck/ai-src/worktrees/otel-batch-threshold-wakeup-20261017
for task_entry in "$task_exporter:$task_exporter_sha" "$task_control:473edbb06fa11070968de35f8b953f62cb4b6cd3" "$task_fixture:3fd96c4691c5db49ed4d3ef55622aaa6b9074127" "$task_json:8d557cc18eb9526ed29ae5613089c3793d5973fa" "$task_sdk:37c684a2c370fc82fde82331635973bce87a52b3"; do
  task_path=${task_entry%:*}
  test "$(git -C "$task_path" rev-parse HEAD)" = "${task_entry##*:}"
  git -C "$task_path" diff --quiet HEAD
done
test "$(git -C "$task_control" rev-parse 'HEAD^{tree}')" = db48a5c47ec3ec41629a70b735afcd19f05f0740
task_report=$task_evidence/declared-string-sdk-$task_label-20261018
test ! -e "$task_report.writer.log"
task_root=$(mktemp -d /tmp/declared-string-sdk-20261018.XXXXXXXX)
printf 'arm=%s owned_store=%s\n' "$task_label" "$task_root"
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
test "$(sha256sum "$JOLT_CHDB_LIB" | cut -d ' ' -f1)" = a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557
export SDK_STORAGE_ARM=local SDK_STREAM_ARM=stream SDK_EXPORT_CALLERS=1
export CHDB_BENCH_OWNED_OUTPUT=true CHDB_BENCH_SAMPLE_COUNTERS=true
task_deps="{:paths [\"src\" \"resources\" \"test\" \"bench\" \"$task_fixture/bench\" \"$task_fixture/test\"] :deps {org.clojure/data.json {:local/root \"$task_json\"} io.github.casselc/otel {:local/root \"$task_sdk\"}}}"
task_writer=$task_evidence/sdk-trace-affinity-screen-20261018.clj
if test "$task_paired" = true; then
  task_writer=$task_evidence/declared-string-sdk-paired-screen-20261018.clj
fi
cd "$task_exporter"
if timeout --signal=TERM --kill-after=5s 180s "$task_evidence/jolt-measured-1ae-20261015.sh" \
  -Srepro -Sdeps "$task_deps" "$task_writer" \
  "$task_root" 1 saturated > "$task_report.writer.log" 2>&1; then
  cp "$task_root/writer.edn" "$task_report.writer.edn"
  cmp "$task_root/writer.edn" "$task_report.writer.edn"
  if test "$task_paired" = true; then
    cp "$task_root/paired.edn" "$task_report.paired.edn"
    cmp "$task_root/paired.edn" "$task_report.paired.edn"
  fi
else
  task_code=$?
  if test -f "$task_root/progress.edn"; then
    cp "$task_root/progress.edn" "$task_report.incomplete.edn"
    cmp "$task_root/progress.edn" "$task_report.incomplete.edn"
  fi
  exit "$task_code"
fi
# Independent reader always uses the unchanged accepted exporter, never the
# candidate's projection implementation. It verifies every physical field.
cd "$task_control"
export CHDB_BENCH_METADATA_READER=stock
timeout --signal=TERM --kill-after=5s 120s "$task_evidence/jolt-measured-1ae-20261015.sh" \
  -Srepro -Sdeps "$task_deps" "$task_evidence/sdk-trace-affinity-reader-20261018.clj" \
  "$task_root" > "$task_report.reader.log" 2>&1
cp "$task_root/reader.edn" "$task_report.reader.edn"
cmp "$task_root/reader.edn" "$task_report.reader.edn"
bb -e '(let [[prefix sdk-root] *command-line-args*
             w (clojure.edn/read-string (slurp (str prefix ".writer.edn")))
             r (clojure.edn/read-string (slurp (str prefix ".reader.edn")))
             batches (:batches w)]
         (assert (= [160000 5000 32 1 1 1 10000 625 8 "saturated"]
                    (mapv w [:rows :batch-size :rounds :lanes :distinct-exporter-count
                             :shared-connection-owner-count :total-queue-capacity
                             :trace-count :siblings-per-trace :arrival])))
         (assert (= [{:queue-size 0 :attempted-span-count 160000 :exported-span-count 160000
                      :failed-span-count 0 :dropped-count 0}] (:stats w)))
         (assert (= [160000] (:expected-lane-counts w)))
         (assert (and (:per-trace-counts-checked w) (:per-trace-callback-order-checked w)))
         (assert (= #{0} (set (map :lane batches))))
         (assert (<= 1 (count batches) 256))
         (assert (every? #(and (:ok %) (<= 1 (:rows %) 5000)) batches))
         (assert (= 160000 (reduce + (map :rows batches))))
         (assert (= (+ 3 (count batches)) (:stream-calls w)))
         (assert (every? (fn [[a b]] (<= (:end-nanos a) (:start-nanos b)))
                         (partition 2 1 (sort-by :start-nanos batches))))
         (assert (= (str "file:" sdk-root "/src/otel/sdk/export.clj") (:sdk-source w)))
         (assert (and (false? (:force-flush-before-measurement w)) (false? (:tail-qualified w))))
         (assert (and (:passed r) (:full-physical-rows-equal r) (:typed-values-status r)
                      (= [175000 5000 625 35] (mapv r [:rows :groups :trace-count :per-record-multiplicity]))))
         (prn {:verified true :confirmed-rows-per-second (:confirmed-rows-per-second w)
               :actual-export-batches (count batches) :recovered-rows (:rows r)
               :independent-unchanged-reader true :tail-qualified false}))' \
  "$task_report" "$task_sdk" | tee "$task_report.validation.edn"
if test "$task_paired" = true; then
  bb -e '(let [[prefix] *command-line-args*
                w (clojure.edn/read-string (slurp (str prefix ".writer.edn")))
                p (clojure.edn/read-string (slurp (str prefix ".paired.edn")))
                xs (:observations p) batches (sort-by :start-nanos (:batches w))]
            (assert (= 32 (count xs) (count batches)))
            (assert (= (vec (range 32)) (mapv :ordinal xs)))
            (assert (= (vec (take 32 (cycle [:baseline :candidate :candidate :baseline]))) (mapv :arm xs)))
            (assert (every? #(= 5000 (:rows %)) xs))
            (doseq [[x b] (map vector xs batches)]
              (assert (<= (:start-nanos b) (:start-nanos x) (:end-nanos x) (:end-nanos b))))
            (assert (= 64 (count (:phases p))))
            (assert (= {:projection-and-encoding 32 :native-and-confirm 32}
                       (frequencies (map :phase (:phases p)))))
            (doseq [phase (:phases p)]
              (let [x (nth xs (:ordinal phase))]
                (assert (= (:arm x) (:arm phase)))
                (assert (<= (:start-nanos x) (:start-nanos phase) (:end-nanos phase) (:end-nanos x)))))
            (prn {:paired-controls-verified true :samples-per-arm 16 :tail-qualified false}))' \
    "$task_report" | tee "$task_report.paired-validation.edn"
fi
du -sh "$task_root"
[[ "$task_root" =~ ^/tmp/declared-string-sdk-20261018\.[A-Za-z0-9]+$ ]]
rm -rf -- "$task_root"
