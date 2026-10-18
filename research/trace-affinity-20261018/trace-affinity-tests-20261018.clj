(require '[clojure.test :refer [deftest is run-tests]])
(load-file "/home/chuck/ai-src/evidence/trace-affinity-helpers-20261018.clj")

(def records
  (vec (for [trace-index (range 64) span-index (range 8)]
         {:span-context {:trace-id (format "%032x" (inc trace-index))
                         :span-id (format "%016x" (inc span-index))}
          :name (str "span-" span-index) :attributes {:index span-index}})))

(defn route-controls [router]
  (let [seen (atom [])]
    (doseq [record records]
      (router [0 1] record (fn [processor span] (swap! seen conj [processor span]))))
    {:exactly-once (= records (mapv second @seen))
     :trace-affinity (every? (fn [[_ xs]] (= 1 (count (set (map first xs)))))
                             (group-by #(get-in (second %) [:span-context :trace-id]) @seen))
     :both-lanes-used (= #{0 1} (set (map first @seen)))}))

(deftest fixed-trace-affinity-and-nonvacuous-routing-controls
  (is (= {:exactly-once true :trace-affinity true :both-lanes-used true}
         (route-controls otel.research.trace-affinity/route!)))
  (let [next-lane (atom -1)
        bad (route-controls (fn [processors span emit!]
                              (emit! (nth processors (mod (swap! next-lane inc) 2)) span)))]
    (is (:exactly-once bad))
    (is (false? (:trace-affinity bad))))
  (let [bad (route-controls (fn [processors span emit!]
                              (doseq [processor processors] (emit! processor span))))]
    (is (false? (:exactly-once bad)))
    (is (false? (:trace-affinity bad)))))

(deftest missing-context-and-fixed-lane-domain
  (doseq [span [{} {:span-context nil} {:span-context {:trace-id 42}}]]
    (is (= 0 (otel.research.trace-affinity/lane span 2))))
  (doseq [span records] (is (= 0 (otel.research.trace-affinity/lane span 1))))
  (let [seen (atom 0)]
    (is (= :rejected
           (try (otel.research.trace-affinity/route! [] (first records)
                                                   (fn [& _] (swap! seen inc)))
                :accepted
                (catch clojure.lang.ExceptionInfo _ :rejected))))
    (is (zero? @seen))))

(deftest grouped-fixture-has-distinct-sibling-spans
  (let [base (mapv (fn [i] {:span-context {:trace-id (format "%032x" (inc i))
                                         :span-id (format "%016x" (inc i))}
                           :attributes {:index i}}) (range 512))
        grouped (otel.research.trace-affinity/group-traces base)]
    (is (= 64 (count (set (map #(get-in % [:span-context :trace-id]) grouped)))))
    (is (= (mapv #(dissoc % :span-context) base) (mapv #(dissoc % :span-context) grouped)))
    (is (= (mapv #(get-in % [:span-context :span-id]) base)
           (mapv #(get-in % [:span-context :span-id]) grouped)))
    (doseq [siblings (partition 8 grouped)]
      (is (= 1 (count (set (map #(get-in % [:span-context :trace-id]) siblings)))))
      (is (= 8 (count (set (map #(get-in % [:span-context :span-id]) siblings))))))))

(let [r (run-tests 'user)]
  (when-not (zero? (+ (:fail r) (:error r)))
    (throw (ex-info "Trace-affinity controls failed" r))))
