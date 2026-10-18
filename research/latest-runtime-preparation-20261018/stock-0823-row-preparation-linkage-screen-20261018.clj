;; Research only: actual exporter row preparation on latest-release source.
;; The configured ordinary JSON path needs no optional native writer helpers.
;; Direct-linking is NOT a proposed product default: live Var controls follow.
(let [arm (first *command-line-args*)]
  (assert (contains? #{"open" "direct"} arm))
  (binding [*compiler-options* (if (= arm "direct") {:direct-linking true} {})]
    (require 'otel.exporter.chdb)))
(require '[clojure.data.json :as json]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jolt.host :as host]
         '[jdbc.chdb.durable.digest :as digest])
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(let [spans (nested-sorted-sdk-shape
              (with-redefs [fixture/batch-size 5000]
                (fixture/spans "typed-compact-prefix")))
      sample #'bench/counter-sample delta #'bench/counter-delta
      row (fn [span] (#'e/span-row span nil true))
      reference (with-bindings {#'e/*timestamp-wire* :raw-ticks}
                  (mapv row spans))
      reference-hash (digest/sha256-bytes
                       (.getBytes (json/write-str reference) "UTF-8"))
      run! (fn [i]
             (let [before (sample) start (System/nanoTime)
                   values (mapv row spans)
                   end (System/nanoTime) after (sample)]
               (assert (= reference values))
               {:ordinal i :nanos (- end start) :counters (delta before after)}))]
  (with-bindings {#'e/*timestamp-wire* :raw-ticks #'e/*json-backend* :configured}
    (dotimes [_ 3] (run! -1))
    (System/gc)
    (let [observations (mapv (fn [i]
                               (let [result (run! i)]
                                 (println :row-preparation :arm (first *command-line-args*)
                                          :ordinal i :nanos (:nanos result))
                                 (flush) result)) (range 16))
          attribute-root-live?
          (with-redefs-fn {#'e/value-string (fn [_] "LINKAGE-CONTROL")}
            (fn [] (= "LINKAGE-CONTROL"
                      (get (nth (row (assoc (first spans)
                                             :attributes {"linkage-control" 7})) 11)
                           "linkage-control"))))
          json-root-live?
          (with-redefs [json/write-str (fn [_] "LINKAGE-CONTROL")]
            (= "LINKAGE-CONTROL" (nth (row (first spans)) 22)))]
      (spit (second *command-line-args*)
            (pr-str {:scope :stock-0823-configured-json-row-preparation-not-durable
                     :arm (first *command-line-args*) :exporter "4d48871"
                     :runtime-source "18dce94a" :scheme-version (host/scheme-version)
                     :json "96f3e47" :backend :configured :rows 5000
                     :row-reference-sha256 reference-hash :all-rows-equal true
                     :attribute-root-live? attribute-root-live?
                     :json-root-live? json-root-live?
                     :tail-qualified false :observations observations})))))
