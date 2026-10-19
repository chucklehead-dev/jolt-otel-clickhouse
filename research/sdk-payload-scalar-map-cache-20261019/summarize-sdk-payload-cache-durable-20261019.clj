(require '[clojure.edn :as edn])
(defn quantiles [values]
  (let [ordered (vec (sort values))]
    (assert (seq ordered))
    (into {} (for [[label p] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
               [label (/ (double (nth ordered (dec (int (Math/ceil (* p (count ordered))))))) 1e6)]))))
(let [arms
      (mapv (fn [[index arm]]
              (let [prefix (str "/home/chuck/ai-src/evidence/sdk-payload-cache-durable-fixed-20261019-" index "-" arm)
                    w (edn/read-string (slurp (str prefix ".writer.edn")))
                    r (edn/read-string (slurp (str prefix ".reader.edn")))
                    v (edn/read-string (slurp (str prefix ".validation.edn")))
                    os (:os-process-window w)
                    cpu (/ (+ (:user-ticks-delta os) (:system-ticks-delta os))
                           (double (:ticks-per-second os)))
                    callback-ms (quantiles (map :nanos (:batches w)))]
                (assert (= [arm "c474e02" "7098a8d6" "26.9.0" true]
                           (mapv w [:payload-scalar-cache-arm :sdk-cache-source :runtime
                                    :native-library :exporter-cache-local-wiring])))
                (assert (and (:verified v) (:passed r) (:full-physical-rows-equal r)))
                (assert (= [160000 32 5000 1 20000] (mapv w [:rows :rounds :batch-size :lanes :total-queue-capacity])))
                (assert (= 32 (count (:batches w))))
                (assert (every? #(= 5000 (:rows %)) (:batches w)))
                (assert (= 175000 (:rows r)))
                (assert (= (:confirmed-rows-per-second w)
                           (/ 160000e9 (:confirmed-window-nanos w))))
                (doseq [field [:user-ticks :system-ticks]]
                  (assert (<= 0 (get-in os [:before field]) (get-in os [:after field]))))
                (assert (= (:user-ticks-delta os)
                           (- (get-in os [:after :user-ticks]) (get-in os [:before :user-ticks]))))
                (assert (= (:system-ticks-delta os)
                           (- (get-in os [:after :system-ticks]) (get-in os [:before :system-ticks]))))
                (assert (every? pos? (for [when [:before :after] field [:rss-kib :highwater-kib :thread-count]]
                                      (get-in os [when field]))))
                {:ordinal index :arm arm :rows (:rows w) :window-nanos (:confirmed-window-nanos w)
                 :confirmed-window-rows-per-second (:confirmed-rows-per-second w)
                 :callback-ms callback-ms
                 :batch-rows-per-second-at-latency-quantile
                 (into {} (map (fn [[q ms]] [q (/ 5000000.0 ms)])) callback-ms)
                 :estimated-bytes-per-row (/ (get-in w [:counter-delta :scheme-heap-bytes-allocated]) 160000.0)
                 :process-cpu-us-per-row (/ (* cpu 1e6) 160000.0)
                 :process-lifetime-highwater-mib (/ (get-in os [:after :highwater-kib]) 1024.0)
                 :recovered-rows (:rows r)})) [[0 "off"] [1 "on"] [2 "on"] [3 "off"]])
      group-rate (fn [arm]
                   (let [selected (filter #(= arm (:arm %)) arms)]
                     (/ (* 1e9 (reduce + (map :rows selected)))
                        (reduce + (map :window-nanos selected)))))
      baseline-rates (map :confirmed-window-rows-per-second (filter #(= "off" (:arm %)) arms))
      drift (/ (apply max baseline-rates) (apply min baseline-rates))
      report {:scope :local-rich-sdk-payload-cache-abba-not-product-adoption
              :sdk "c474e02" :exporter "d0aac4e" :runtime "7098a8d6" :json "2172061"
              :driver "3d766fa" :native-library "26.9.0" :order ["off" "on" "on" "off"]
              :samples-per-arm 32 :batch-size 5000 :total-queue-capacity 20000
              :pooled-off-rows-per-second (group-rate "off") :pooled-on-rows-per-second (group-rate "on")
              :observed-pooled-rate-ratio (/ (group-rate "on") (group-rate "off"))
              :control-drift-ratio drift :control-drift-observed (> drift 1.1)
              :total-independently-recovered-rows (reduce + (map :recovered-rows arms))
              :old-stack-independent-full-recovery true :source-overlay false
              :local-source-overrides true :tail-qualified false :target-qualified false
              :end-to-end-span-latency-measured false :s3-qualified false :arms arms}]
  (assert (= 700000 (:total-independently-recovered-rows report)))
  (spit "/home/chuck/ai-src/evidence/sdk-payload-cache-durable-abba-20261019.summary.edn" (pr-str report))
  (doseq [arm arms] (prn arm))
  (prn (dissoc report :arms)))
