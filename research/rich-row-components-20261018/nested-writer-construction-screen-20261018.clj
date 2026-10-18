(require '[clojure.data.json :as json]
         '[clojure.data.json.jolt-native :as native]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench])
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(let [events (mapv :events (nested-sorted-sdk-shape
                            (with-redefs [fixture/batch-size 5000]
                              (fixture/spans "typed-compact-prefix"))))
      refs (binding [json/*experimental-native-writer* nil] (mapv json/write-str events))
      indices (vec (range 5000))
      sample #'bench/counter-sample delta #'bench/counter-delta
      cases [[:constructor (fn [_] (java.io.StringWriter.))
              (fn [values] (and (= "" (str (first values))) (= "" (str (last values)))))]
             [:empty (fn [_] (native/write-str [])) #(every? (fn [v] (= "[]" v)) %)]
             [:one-scalar (fn [_] (native/write-str [0])) #(every? (fn [v] (= "[0]" v)) %)]
             [:event (fn [i] (native/write-str (nth events i))) #(= refs %)]]
      run! (fn [[arm f check] ordinal]
             (let [result (atom nil)
                   bytes (native/write-prefixed-batch-bytes! ""
                           (lazy-seq
                             (let [before (sample) start (System/nanoTime)
                                   values (mapv f indices)
                                   end (System/nanoTime) after (sample)]
                               (assert (= 5000 (count values)))
                               (assert (check values))
                               (reset! result {:arm arm :ordinal ordinal :nanos (- end start)
                                               :counters (delta before after)}))
                             nil) 0)]
               (assert (zero? (alength bytes)))
               @result))]
  (doseq [entry cases] (run! entry -1))
  (System/gc)
  (let [observations (vec (mapcat (fn [i]
                                   (mapv (fn [entry]
                                           (let [result (run! entry i)]
                                             (println :writer-construction (:arm result) i (:nanos result))
                                             (flush) result))
                                         (if (even? i) cases (reverse cases)))) (range 16)))]
    (spit (first *command-line-args*)
          (pr-str {:scope :nested-writer-construction-component-not-durable
                   :runtime "7098a8d6" :exporter "4d48871" :json "96f3e47"
                   :rows 5000 :rounds 16 :all-output-controls true :tail-qualified false
                   :overlapping-components-not-additive true :observations observations}))))
