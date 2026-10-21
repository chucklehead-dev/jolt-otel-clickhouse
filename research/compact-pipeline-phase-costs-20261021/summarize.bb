(require '[clojure.edn :as edn])

(let [[root] *command-line-args*
      read-report #(edn/read-string (slurp (str root "/" % ".edn")))
      writer (read-report "writer") reader (read-report "reader")
      report (read-report "phase-costs") samples (:measured-samples report)
      phases (cond-> [:normalize-project-encode :wal-preparation :prepare-total]
               (every? :owned-snapshot samples) (conj :owned-snapshot))
      q (fn [values p]
          (nth (vec (sort values)) (dec (long (Math/ceil (* p (count values)))))))
      total (fn [phase metric] (reduce + (map #(get-in % [phase metric]) samples)))
      callback-total (reduce + (map :nanos (:samples writer)))]
  (assert (= [175000 5000 32 3]
             (mapv writer [:stored-rows :batch-size :rounds :warmups])))
  (assert (= [35 32 3 false]
             (mapv report [:completed-preparations :confirmed-callbacks :warmup-count :publication-observed])))
  (assert (= (vec (range 32)) (mapv :round samples)))
  (assert (= (vec (range 32)) (mapv :round (:samples writer))))
  (assert (every? #(= (set (conj phases :round)) (set (keys %))) samples))
  (assert (every? #(and (true? (:ok %)) (= 5000 (:rows %)) (pos? (:nanos %))) (:samples writer)))
  (assert (every? (fn [s] (every? #(pos? (get-in s [% :nanos])) phases)) samples))
  (assert (= [true 175000 5000 625 true true 35 true]
             (mapv reader [:passed :rows :groups :trace-count :full-physical-rows-equal
                           :typed-values-status :per-record-multiplicity :deterministic-tie-order])))
  (assert (every? true? (map (fn [w s] (>= (:nanos w) (get-in s [:prepare-total :nanos])))
                            (:samples writer) samples)))
  (prn {:scope :diagnostic-not-a-causal-throughput-comparison
        :independently-recovered-rows (:rows reader)
        :phase-observations 32 :private-boundary-probes true
        :allocation-counters-non-atomic true :per-row-probes false
        :mean-callback-ms (/ callback-total 32e6)
        :mean-outside-preparation-ms (/ (- callback-total (total :prepare-total :nanos)) 32e6)
        :outside-preparation-is-not-isolated-publication true
        :phases
        (into {} (for [phase phases]
                   [phase {:mean-ms (/ (total phase :nanos) 32e6)
                           :p50-p90-p95-p99-ms (mapv #(/ (q (map (fn [s] (get-in s [phase :nanos])) samples) %) 1e6)
                                                       [0.5 0.9 0.95 0.99])
                           :percent-callback-time (* 100.0 (/ (total phase :nanos) callback-total))
                           :estimated-bytes-per-row (/ (total phase :estimated-allocated-bytes) 160000.0)
                           :cpu-ms (/ (total phase :cpu-nanos) 1e6)
                           :gc-ms (/ (total phase :gc-nanos) 1e6)}]))
        :inclusive-phases-do-not-sum true
        :target-qualified false :population-tail-qualified false}))
