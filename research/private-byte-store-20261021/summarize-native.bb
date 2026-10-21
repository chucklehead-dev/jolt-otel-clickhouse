(require '[clojure.edn :as edn] '[clojure.string :as str])
(def expected-binaries
  {:baseline "702dda478703aae460a28c8ac8276485d16e33b175357331deb13a42831c50eb"
   :candidate "8176d81601da6969e92408f0b05574240d4197893c8aae62038763402ebfe461"})
(defn quantile [xs p]
  (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))
(let [root (or (first *command-line-args*) "research/private-byte-store-20261021/native-results")
      runs
      (mapv (fn [[label mode]]
              (let [path (str root "/" label "/")
                    w (edn/read-string (slurp (str path "writer.edn")))
                    r (edn/read-string (slurp (str path "reader.edn")))
                    v (edn/read-string (slurp (str path "validated.edn")))
                    controls (edn/read-string (slurp (str path "controls.edn")))
                    sha (first (str/split (slurp (str path "binary.sha256")) #"\s+"))]
                (assert (= (expected-binaries mode) sha))
                (assert (and (:verified v) (:passed r) (:positive-control controls)))
                (assert (= 9 (count (:rejected-controls controls))))
                (assert (= [160000 175000 5000 32 3 "fused"]
                           (mapv w [:rows :stored-rows :batch-size :rounds :warmups :field-slots-arm])))
                (assert (= 175000 (:rows r) (:independently-recovered-rows v)))
                {:label label :mode mode :binary-sha256 sha
                 :window-rows-per-second (:confirmed-rows-per-second w)
                 :p50-p90-p95-p99-ms (:p50-p90-p95-p99-ms w)
                 :estimated-bytes-per-row (:estimated-bytes-per-row w)
                 :window-nanos (:confirmed-window-nanos w) :rows (:rows w)
                 :independently-recovered-rows (:rows r) :samples (:samples w)}))
            [["A1" :baseline] ["B1" :candidate] ["B2" :candidate] ["A2" :baseline]])
      summary
      (into {} (for [[mode group] (group-by :mode runs)]
                 (let [ms (mapv #(/ (quantile (map :nanos (mapcat :samples group)) %) 1e6)
                                [0.5 0.9 0.95 0.99])]
                   [mode {:window-rows-per-second (/ (* 1e9 (reduce + (map :rows group)))
                                                     (reduce + (map :window-nanos group)))
                          :p50-p90-p95-p99-ms ms
                          :inverse-latency-rows-per-second (mapv #(/ 5e6 %) ms)
                          :mean-run-estimated-bytes-per-row
                          (/ (reduce + (map :estimated-bytes-per-row group)) (count group))}])))
      percent (fn [a b] (* 100.0 (dec (/ b a))))]
  (prn {:scope :two-native-binary-private-byte-store-abba
        :runs (mapv #(dissoc % :samples) runs) :summary summary
        :forward-window-rate-change-percent
        (percent (:window-rows-per-second (nth runs 0)) (:window-rows-per-second (nth runs 1)))
        :reverse-window-rate-change-percent
        (percent (:window-rows-per-second (nth runs 3)) (:window-rows-per-second (nth runs 2)))
        :pooled-window-rate-change-percent
        (percent (get-in summary [:baseline :window-rows-per-second])
                 (get-in summary [:candidate :window-rows-per-second]))
        :independently-recovered-rows (reduce + (map :independently-recovered-rows runs))
        :same-binary false :constructor-wrapper-same true :per-row-probes false
        :sdk-queue false :s3 false :causal-throughput-qualified false
        :population-tail-qualified false :target-qualified false}))
