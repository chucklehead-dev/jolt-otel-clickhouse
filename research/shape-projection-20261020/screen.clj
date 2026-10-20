(require '[clojure.java.io :as io] '[clojure.string :as str]
         '[shape-projection :as shape] '[otel.any-value :as any]
         '[jolt.codec.experimental :as codec]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jdbc.chdb.durable.digest :as digest])

(defn q [xs p] (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))
(defn sdk-bindings []
  [[#'any/try-scalar-string @#'any/scalar-text-helper]
   [#'any/canonicalize @#'any/scalar-text-canonicalizer]
   [#'any/default-limits @#'any/scalar-default-limits]
   [#'any/string-bytes @#'any/original-string-bytes]
   [#'any/min-int64 @#'any/scalar-map-min-int64]
   [#'any/max-int64 @#'any/scalar-map-max-int64]])

(let [rows 5000 samples 16 c (codec/open-codec)
      fixture (fn [n]
                (into (sorted-map "service.name" "producer-shape" "flag" false "count" 42)
                      (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value-λ" 1 false i)]))
                      (range (- n 3))))
      shapes (into {} (for [width [17 32] sharing [:shared :unshared]]
                       (let [m (fixture width)
                             inputs (if (= sharing :shared) (vec (repeat rows m))
                                        (mapv (fn [_] (into (sorted-map) m)) (range rows)))
                             convert (shape/compile-plan (vec (keys m)) (sdk-bindings)
                                       (quot (:max-bytes @#'any/scalar-default-limits) 4)
                                       @#'any/scalar-sorted-map-prototype)]
                         [[width sharing] {:inputs inputs :convert convert}])))
      encode (fn [values] (codec/encode-prefixed-records c "" values vector 67108864))
      hashes (into {} (for [[id {:keys [inputs]}] shapes]
                        [id (digest/sha256-bytes (encode (mapv any/try-sorted-scalar-string-map inputs)))]))
      run (fn [id mode]
            (let [{:keys [inputs convert]} (shapes id)]
              (mapv (if (= mode :baseline) any/try-sorted-scalar-string-map convert) inputs)))
      cells (vec (for [id (sort (keys shapes)) mode [:baseline :template]] [id mode]))
      _ (doseq [[id {:keys [inputs convert]}] shapes]
          (let [m (first inputs) changed (assoc m "count" 999)]
            (assert (= (vec (any/try-sorted-scalar-string-map changed)) (vec (convert changed))))
            (assert (not= (hashes id) (digest/sha256-bytes (encode (assoc (run id :template) 0 (convert changed)))))))
          (assert (= (hashes id) (digest/sha256-bytes (encode (run id :template))))))
      _ (doseq [_ (range 3) [id mode] cells] (run id mode))
      observations
      (vec (for [round (range samples) [id mode] (if (even? round) cells (reverse cells))]
             (let [before (#'bench/counter-sample) start (System/nanoTime)
                   result (run id mode) nanos (- (System/nanoTime) start)
                   after (#'bench/counter-sample)]
               (assert (= rows (count result)))
               (assert (= (hashes id) (digest/sha256-bytes (encode result))))
               {:round round :shape id :mode mode :nanos nanos
                :counters (#'bench/counter-delta before after)})))
      report {:scope :fixed-schema-sorted-scalar-projection-component
              :rows rows :samples-per-cell samples :runtime :stock-jolt-0.8.23
              :sdk-source (str (io/resource "otel/any_value.clj"))
              :codec-source (str (io/resource "jolt/codec/experimental.clj"))
              :plan-source (str (io/resource "shape_projection.ss"))
              :fixture :synthetic-scalar-attributes-not-live-sdk-capture
              :compiled-schema-cost :outside-timing :includes-result-batch-vector true
              :live-sdk-policy-checks true :full-byte-equivalence true
              :changed-value-controls true :durable-io false :database-io false
              :tail-qualified false :hashes hashes :observations observations
              :summary (into {} (for [[id mode] cells
                                      :let [xs (filter #(and (= id (:shape %)) (= mode (:mode %))) observations)]]
                                  [[id mode] {:p50-ms (/ (q (map :nanos xs) 0.5) 1e6)
                                              :p90-ms (/ (q (map :nanos xs) 0.9) 1e6)
                                              :p95-ms (/ (q (map :nanos xs) 0.95) 1e6)
                                              :p99-ms (/ (q (map :nanos xs) 0.99) 1e6)
                                              :median-estimated-bytes-per-map
                                              (/ (q (map #(get-in % [:counters :scheme-heap-bytes-allocated]) xs) 0.5)
                                                 (double rows))}]))}]
  (assert (str/includes? (:sdk-source report) "3366a3322c3ea4024057f6278e50c999864171e8"))
  (assert (str/includes? (:codec-source report) "7ab2bedcb2e1845efdf6ed5c55ae9d2164338a8d"))
  (spit (first *command-line-args*) (pr-str report))
  (prn (:summary report)))
