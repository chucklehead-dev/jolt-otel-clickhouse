(require '[jolt.codec.experimental :as codec] '[jolt.traversal.experimental :as traversal]
         '[jolt.scheme :as scheme] '[clojure.string :as str])
(defn backend [source keyed?]
  (let [walk (traversal/open-traversal)
        factory (scheme/eval-string
                  (str "(lambda (ffc-map-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record?) (let () " source
                       " (jolt-vector ffc-json-ascii-bytes ffc-json-project-prefixed-ascii-lines-bytes"
                       " ffc-json-nested-text ffc-json-cached-project-prefixed-ascii-lines-bytes"
                       " ffc-json-row-box ffc-json-row-append ffc-json-row-width ffc-json-nested-scope "
                       (if keyed? "ffc-json-key-project-prefixed-ascii-lines-bytes ffc-json-key-cached-project-prefixed-ascii-lines-bytes" "#f #f") ")))") )
        [scalar project nested cached box append width scope key-project key-cached]
        (apply factory (traversal/native-kernels walk))]
    [scalar project (traversal/native-pmap-folder walk) nested cached box append width scope key-project key-cached]))

(let [original (backend (slurp "research/key-fragments-20261020/baseline-json.ss") false)
      current @@#'codec/backend
      content-source (str/replace @#'codec/json-source
                       "(unless key-table (set! key-table (make-weak-eq-hashtable)))"
                       "(unless key-table (set! key-table (make-hashtable string-hash string=?)))")
      content (backend content-source true)
      encoders {:original (nth original 1) :off (nth current 1)
                :identity (nth current 9) :content (nth content 9)}
      copy-string (scheme/eval-string "(lambda (s) (string-copy s))")
      equal-bytes? (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))")
      short-keys (mapv #(str "key-" %) (range 8))
      long-keys (mapv #(str "telemetry.resource.attribute.field-" %) (range 8))
      samples (atom [])]
  (doseq [shape [:shared-short :fresh-equal :unique :shared-long]]
    (let [rows (mapv (fn [i] (into {} (map-indexed
                           (fn [j key] [(case shape :fresh-equal (copy-string key)
                                                   :unique (str key "-" i) key) j])
                           (if (= shape :shared-long) long-keys short-keys)))) (range 5000))
          encode (fn [arm] ((encoders arm) "" rows identity 67108864 128))
          expected (encode :original)]
      (doseq [arm (keys encoders)] (assert (equal-bytes? expected (encode arm))))
      (dotimes [round 8]
        (doseq [arm (if (even? round) [:original :off :identity :content] [:content :identity :off :original])]
          (let [start (System/nanoTime) out (encode arm) elapsed (- (System/nanoTime) start)]
            (assert (equal-bytes? expected out))
            (swap! samples conj {:shape shape :round round :arm arm :nanos elapsed}))))))
  (spit (first *command-line-args*) (pr-str {:scope :generic-key-controls :rows 5000 :samples @samples
                                           :all-output-bytes-identical true :durable-throughput-qualified false}))
  (doseq [[[shape arm] xs] (sort-by (comp str key) (group-by (juxt :shape :arm) @samples))]
    (let [ordered (vec (sort (map :nanos xs)))]
      (prn {:shape shape :arm arm :p50-ms (/ (nth ordered 3) 1e6)}))))
