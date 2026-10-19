(require '[clojure.edn :as edn])
(defn quantiles [values scale]
  (let [ordered (vec (sort values))]
    (assert (seq ordered))
    (into {} (for [[label p] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
               [label (/ (double (nth ordered (dec (int (Math/ceil (* p (count ordered))))))) scale)]))))
(defn valid-os-sample? [sample]
  (and (every? #(and (integer? %) (not (neg? %))) (map sample [:user-ticks :system-ticks]))
       (every? #(and (integer? %) (pos? %)) (map sample [:thread-count :rss-kib :highwater-kib]))))
(defn valid-os-window? [os]
  (and (integer? (:ticks-per-second os)) (pos? (:ticks-per-second os))
       (valid-os-sample? (:before os)) (valid-os-sample? (:after os))
       (every? #(and (integer? %) (not (neg? %))) (map os [:user-ticks-delta :system-ticks-delta]))
       (pos? (+ (:user-ticks-delta os) (:system-ticks-delta os)))
       (= (:user-ticks-delta os) (- (get-in os [:after :user-ticks]) (get-in os [:before :user-ticks])))
       (= (:system-ticks-delta os) (- (get-in os [:after :system-ticks]) (get-in os [:before :system-ticks])))
       (<= (get-in os [:before :highwater-kib]) (get-in os [:after :highwater-kib]))))

(let [arms
      (mapv (fn [[index lanes]]
              (let [prefix (str "/home/chuck/ai-src/evidence/rich-sdk-scaling-20261019-" index "-lanes-" lanes)
                    w (edn/read-string (slurp (str prefix ".writer.edn")))
                    r (edn/read-string (slurp (str prefix ".reader.edn")))
                    v (edn/read-string (slurp (str prefix ".validation.edn")))
                    batches (:batches w) os (:os-process-window w)
                    _ (assert (valid-os-window? os))
                    ;; Independent stronger metadata controls. These maps are
                    ;; never written back as real execution/recovery receipts.
                    _ (assert (not (valid-os-window? (assoc os :system-ticks-delta -1))))
                    _ (assert (not (valid-os-window? (assoc-in os [:before :rss-kib] nil))))
                    cpu-seconds (/ (+ (:user-ticks-delta os) (:system-ticks-delta os))
                                   (double (:ticks-per-second os)))
                    wall-seconds (/ (:confirmed-window-nanos w) 1e9)
                    overlap? (boolean
                               (some true? (for [a batches b batches :when (not= (:lane a) (:lane b))]
                                             (and (< (:start-nanos a) (:end-nanos b))
                                                  (< (:start-nanos b) (:end-nanos a))))))]
                (assert (and (:verified v) (:passed r) (= 175000 (:rows r))))
                (assert (= [160000 20000 lanes lanes 1]
                           (mapv w [:rows :total-queue-capacity :lanes :distinct-exporter-count
                                    :shared-connection-owner-count])))
                (assert (= (> lanes 1) overlap?))
                {:ordinal index :lanes lanes :callbacks (count batches)
                 :actual-streams (:stream-calls w) :callback-overlap-observed overlap?
                 :rows (:rows w) :window-nanos (:confirmed-window-nanos w)
                 :confirmed-window-rows-per-second (:confirmed-rows-per-second w)
                 :process-cpu-seconds cpu-seconds :effective-cpu-cores (/ cpu-seconds wall-seconds)
                 :process-cpu-us-per-row (/ (* cpu-seconds 1e6) (:rows w))
                 :queue-capacity-per-lane (:queue-capacity-per-lane w)
                 :expected-lane-counts (:expected-lane-counts w)
                 :callback-ms (quantiles (map :nanos batches) 1e6)
                 :callback-rows (quantiles (map :rows batches) 1.0)
                 :callback-rows-min-max [(apply min (map :rows batches)) (apply max (map :rows batches))]
                 :rss-mib-before (/ (get-in os [:before :rss-kib]) 1024.0)
                 :rss-mib-after (/ (get-in os [:after :rss-kib]) 1024.0)
                 :process-lifetime-highwater-mib-after (/ (get-in os [:after :highwater-kib]) 1024.0)
                 :estimated-allocated-bytes (get-in w [:counter-delta :scheme-heap-bytes-allocated])
                 :gc (select-keys (:counter-delta w) [:gc-count :process-gc-cpu-nanos :process-gc-real-nanos])
                 :recovered-rows (:rows r)})) [[0 1] [1 2] [2 4] [3 4] [4 2] [5 1]])
      controls (filter #(= 1 (:lanes %)) arms)
      control-ratio (/ (apply max (map :confirmed-window-rows-per-second controls))
                       (apply min (map :confirmed-window-rows-per-second controls)))
      report {:scope :rich-sdk-1-2-4-fixed-queue-scaling-not-product-default
              :runtime "7098a8d6" :exporter "2c54b98" :sdk "7878a2e" :json "2172061"
              :driver "3d766fa" :native-library "26.9.0" :rows-per-source-batch 5000
              :order [1 2 4 4 2 1] :total-queue-capacity 20000 :source-overlay false
              :control-drift-ratio control-ratio :control-drift-observed (> control-ratio 1.1)
              :pooled-lane-speedup-qualified false
              :before-and-after-os-metadata-validated true
              :negative-tick-and-missing-before-rss-controls-rejected true
              :old-stack-independent-full-recovery true
              :total-recovered-rows (reduce + (map :recovered-rows arms))
              :tail-qualified false :end-to-end-span-latency-measured false :s3-qualified false
              :warmups-on-first-exporter-only 3 :arms arms}]
  (assert (= 1050000 (:total-recovered-rows report)))
  (spit "/home/chuck/ai-src/evidence/rich-sdk-scaling-20261019.summary.edn" (pr-str report))
  (doseq [arm arms] (prn arm))
  (prn (dissoc report :arms)))
