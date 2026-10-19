(require '[clojure.test :refer [deftest is run-tests]]
         '[clojure.java.io :as io] '[clojure.string :as str])
(load-file "/home/chuck/ai-src/evidence/trace-affinity-scaling-helpers-20261019.clj")

(def records
  (vec (for [trace-index (range 64) sibling (range 8)]
         {:span-context {:trace-id (format "%032x" (inc trace-index))
                         :span-id (format "%016x" (inc sibling))}
          :ordinal sibling})))

(defn routing [lanes router]
  (let [seen (atom [])]
    (doseq [record records]
      (router (vec (range lanes)) record
              (fn [owner span] (swap! seen conj [owner span]))))
    {:once (= records (mapv second @seen))
     :all-lanes (= (set (range lanes)) (set (map first @seen)))
     :affinity (every? #(= 1 (count (set (map first %))))
                       (vals (group-by #(get-in (second %) [:span-context :trace-id]) @seen)))}))

(deftest bounded-scaling-router-and-nonvacuous-controls
  (doseq [n [1 2 4]]
    (is (= {:once true :all-lanes true :affinity true}
           (routing n otel.research.trace-affinity/route!)))
    (doseq [missing [{} {:span-context nil} {:span-context {:trace-id 42}}]]
      (is (= 0 (otel.research.trace-affinity/lane missing n))))
    (when (> n 1)
      (let [counter (atom -1)]
        (is (false? (:affinity (routing n (fn [owners record emit!]
                                           (emit! (nth owners (mod (swap! counter inc) n)) record)))))))
      (is (false? (:once (routing n (fn [owners record emit!]
                                      (doseq [owner owners] (emit! owner record)))))))))
  (doseq [invalid [0 3 5 -1]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (otel.research.trace-affinity/lane (first records) invalid)))))

;; Evaluate only the exact sampler declaration from the frozen screen, not its
;; require/setup/benchmark forms. This tests the production research parser on
;; the actual selected runtime before creating a native database.
(let [forms (with-open [r (java.io.PushbackReader.
                           (io/reader "/home/chuck/ai-src/evidence/rich-sdk-scaling-screen-20261019.clj"))]
              (loop [result []]
                (let [form (read {:eof ::eof} r)]
                  (if (= ::eof form) result (recur (conj result form))))))
      definitions (filter #(and (seq? %) (= 'defn (first %)) (= 'process-sample (second %))) forms)]
  (assert (= 1 (count definitions)))
  (eval (first definitions)))

(deftest whole-process-cpu-and-memory-parser-is-live
  (let [before (process-sample) after (process-sample)]
    (doseq [sample [before after]]
      (is (every? #(and (integer? %) (not (neg? %))) (map sample [:user-ticks :system-ticks])))
      (is (every? #(and (integer? %) (pos? %)) (map sample [:thread-count :rss-kib :highwater-kib]))))
    (is (<= (+ (:user-ticks before) (:system-ticks before))
            (+ (:user-ticks after) (:system-ticks after))))))

(let [result (run-tests 'user)]
  (assert (= 2 (:test result)))
  (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))
