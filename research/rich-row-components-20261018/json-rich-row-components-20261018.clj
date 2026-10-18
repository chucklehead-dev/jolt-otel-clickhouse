;; Batch-level component screen. Measurements overlap and are not additive.
;; Every pass runs inside the real byte writer's normal nested-JSON binding.
(require '[clojure.data.json.jolt-native :as native]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench])
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(load-file "/home/chuck/ai-src/evidence/trace-affinity-helpers-20261018.clj")
(let [target (Object.) installation (#'installed/installed (fixture/approved) target)
      projector (projection/trace-vector-projector (:descriptor-set installation) target true)
      spans (otel.research.trace-affinity/group-traces
              (mapv (fn [span]
                      (update span :attributes into
                              (map (fn [i] [(str "extra-" i)
                                            (case (mod i 3) 0 "value" 1 false i)])) (range 12)))
                    (nested-sorted-sdk-shape
                      (with-redefs [fixture/batch-size 5000]
                        (fixture/spans "typed-compact-prefix")))))
      base (fn [span] (#'e/span-row span nil true))
      row (fn [span] (into (base span) (projector span)))
      components
      [[:resource-attrs #( #'e/attrs (get-in % [:resource :attributes])) #(nth % 8)]
       [:span-attrs #( #'e/attrs (:attributes %)) #(nth % 11)]
       [:event-json #( #'e/nested-json-string (:events %)) #(nth % 22)]
       [:event-columns #( #'e/event-columns (:events %) true) #(subvec % 15 18)]
       [:typed-projector projector #(subvec % 24)]
       [:base-row base #(subvec % 0 24)]
       [:full-row row identity]]
      sample #'bench/counter-sample delta #'bench/counter-delta
      run! (fn [[label f expected] measured? ordinal]
             (let [result (atom nil)
                   encoded
                   (native/write-prefixed-batch-bytes!
                     ""
                     (lazy-seq
                       (let [before (when measured? (sample)) start (System/nanoTime)
                             values (mapv f spans)
                             end (System/nanoTime) after (when measured? (sample))]
                         (assert (= 5000 (count values)))
                         (doseq [index [0 4999]]
                           (assert (= (expected (row (nth spans index))) (nth values index))))
                         (reset! result {:component label :ordinal ordinal
                                         :nanos (- end start)
                                         :counters (when measured? (delta before after))})
                         nil)) 0)]
               (assert (zero? (alength encoded)))
               @result))]
  (with-bindings {#'e/*timestamp-wire* :raw-ticks #'e/*json-backend* :native-guarded-byte-batch}
    (dotimes [_ 2] (doseq [component components] (run! component false -1)))
    (System/gc)
    (let [events (vec (mapcat (fn [round]
                               (mapv (fn [component]
                                       (let [result (run! component true round)]
                                         (println :row-component :round round :component (:component result)
                                                  :nanos (:nanos result))
                                         (flush) result))
                                     (if (even? round) components (reverse components))))
                             (range 16)))]
      (assert (= 112 (count events)))
      (assert (every? #(= 16 (val %)) (frequencies (map :component events))))
      (spit "/home/chuck/ai-src/evidence/json-rich-row-components-20261018.edn"
            (pr-str {:scope :overlapping-row-component-screen-not-durable
                     :runtime "7098a8d6" :exporter "4d48871" :sdk "7878a2e" :json "96f3e47"
                     :rows-per-pass 5000 :rounds 16 :native-batch-binding-context true
                     :first-last-output-controls true :durable-execution false
                     :tail-qualified false :events events})))))
