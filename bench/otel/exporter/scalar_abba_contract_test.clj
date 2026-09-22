(ns otel.exporter.scalar-abba-contract-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [jolt.host :as host]
            [otel.exporter.scalar-abba-support :as support]
            [otel.exporter.scalar-abba :as abba]))

(defn rejected? [f]
  (try (f) false (catch Throwable _ true)))

(deftest exact-host-chez-identification
  (is (abba/qualified-chez? "Chez Scheme Version 10.4.1"))
  (is (abba/qualified-chez? (host/scheme-version)))
  (doseq [version [nil "" "10.4.1" "Chez Scheme Version 10.4.0"
                   "Chez Scheme Version 10.5.0" "Chez Scheme Version 10.4.10"
                   "Chez Scheme Version 10.4.1-dev" "Chez Scheme Version 10.4.1\n"
                   " Chez Scheme Version 10.4.1" 10.4]]
    (is (not (abba/qualified-chez? version)))))

(deftest publication-and-wal-contract
  (is (nil? (abba/validate-sample! 0 {:wal-count 0} {:wal-count 1} [:committed])))
  (is (nil? (abba/validate-sample! 104 {:wal-count 104} {:wal-count 105} [:reconciled])))
  (doseq [[before after pubs] [[1 2 [:committed]] [0 0 [:committed]]
                              [0 2 [:committed]] [0 1 []]
                              [0 1 [:empty]] [0 1 [:queued]]
                              [0 1 [:committed :committed]]]]
    (is (rejected? #(abba/validate-sample! 0 {:wal-count before} {:wal-count after} pubs)))))

(deftest timing-excludes-counter-reads-and-calls-once
  (let [events (atom [])
        ticks (atom [10 30])
        counter (fn [] (swap! events conj :counter)
                  {:cpu-nanos 0 :real-nanos 0 :gc-count 0 :gc-real-nanos 0
                   :gc-bytes 0 :live-heap-bytes 0})
        clock (fn [] (swap! events conj :clock)
                (let [t (first @ticks)] (swap! ticks subvec 1) t))
        result (abba/measured-call #(do (swap! events conj :operation) true) clock counter)]
    (is (= 20 (:nanos result)))
    (is (= [:counter :clock :operation :clock :counter] @events))
    (is (every? zero? (vals (:counters result))))))

(deftest expanded-recovery-digest-keeps-array-order-and-legacy-json
  (let [row {:linksjson "[]" :attributes [{"b" "2" "a" "1"}] :ids ["a" "b"]}]
    (is (= (support/expanded-rows-digest [row])
           (support/expanded-rows-digest [(assoc row :attributes [{"a" "1" "b" "2"}])])))
    (is (not= (support/expanded-rows-digest [row])
              (support/expanded-rows-digest [(assoc row :ids ["b" "a"])])))
    (is (not= (support/expanded-rows-digest [row])
              (support/expanded-rows-digest [(assoc row :linksjson "[{}]")])))))

(defn -main [& _]
  (let [r (run-tests 'otel.exporter.scalar-abba-contract-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
