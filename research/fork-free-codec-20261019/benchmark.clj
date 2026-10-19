;; Component qualification only. This does NOT measure the SDK/exporter/Durable.
;; The ordinary data.json dependency is the independent reference and baseline,
;; not a dependency of either traversal.ss or json.ss.
(require '[clojure.data.json :as json] '[jolt.host :as host]
         '[jolt.scheme :as scheme] '[clojure.string :as str])
(def here (first *command-line-args*))
(scheme/eval-string (slurp (str here "/traversal.ss")))
(scheme/eval-string (slurp (str here "/json.ss")))
(def encode (scheme/proc "ffc-json-bytes"))
(def lines (scheme/proc "ffc-json-lines-bytes"))
(defn text [x] (String. (encode x 67108864 128) "UTF-8"))
(def checks (atom 0))
(defn check [x] (assert x (str "reference check " (inc @checks))) (swap! checks inc))
(doseq [n (range 180)]
  (let [x {"id" n "negative" (- n) "int64" -9223372036854775808
           "double" (/ (double n) 7.0) "text" (str "é😀\n\"\\/" n)
           "values" [nil false true ["nested" n]]}]
    (check (= (json/write-str x :escape-unicode false) (text x)))
    (check (= x (json/read-str (text x))))))

;; A deterministic entry-allocation control, restored even on failure. The
;; baseline MUST trip the constructor trap, while the direct fold must not.
(def entry-control
  (scheme/eval-string
    "(lambda (m) (let ((original make-map-entry) (n 0))
       (dynamic-wind
        (lambda () (set! make-map-entry (lambda (k v) (set! n (+ n 1)) (original k v))))
        (lambda ()
          (let ((walked (ffc-map-fold m (lambda (k v a) (+ a 1)) 0)))
            (let ((direct n))
              (seq->list (jolt-seq m))
              (jolt-vector walked direct n))))
        (lambda () (set! make-map-entry original)))))"))
(doseq [n [0 1 8 65 1100]]
  (let [m (into {} (map (fn [i] [(str "key-" i) i]) (range n)))
        [visited direct normal] (entry-control m)]
    (check (= n visited)) (check (= 0 direct)) (check (= n normal))))

(def rows
  (mapv (fn [i]
          {"TraceId" (str "000000000000000000000000" i)
           "SpanId" (str "00000000" i) "SpanName" "POST /agent/turn"
           "Timestamp" (+ 1700000000000000000 i) "Duration" (* 1000000 (inc i))
           "ResourceAttributes" {"service.name" "codec-fixture" "local" true}
           "SpanAttributes" (into {"flag" false "text" "é漢字😀\n\"\\/"}
                                  (map (fn [j] [(str "field-" j) (+ j i)]) (range 24)))
           "Events" [{"name" "tool.start" "attrs" {"tool" "search" "attempt" 1}}
                     {"name" "tool.end" "attrs" {"success" true "tokens" 135}}]
           "Links" [] "StatusCode" 0})
        (range 5000)))
(defn ordinary [records]
  (let [out (StringBuilder.)]
    (doseq [row records] (.append out (json/write-str row :escape-unicode false)) (.append out "\n"))
    (.getBytes (.toString out) "UTF-8")))
(def reference (String. (ordinary rows) "UTF-8"))
(check (= reference (String. (lines rows 67108864 128) "UTF-8")))
(check (not= reference (String. (lines (assoc rows 0 {"changed" true}) 67108864 128) "UTF-8")))
(defn counters []
  {:live (host/bytes-allocated) :collected (host/gc-bytes) :gc (host/gc-count)})
(defn run-one [mode]
  (let [before (counters) start (System/nanoTime)
        result (case mode :ordinary (ordinary rows) :direct (lines rows 67108864 128))
        elapsed (- (System/nanoTime) start) after (counters)]
    ;; Full equality outside the timed/counter interval, no retained payloads.
    (check (= reference (String. result "UTF-8")))
    {:mode mode :nanos elapsed :bytes (alength result)
     :estimated-allocation (- (+ (:live after) (:collected after))
                              (+ (:live before) (:collected before)))
     :gc-count (- (:gc after) (:gc before))}))
(doseq [mode [:ordinary :direct :direct :ordinary]] (run-one mode))
(System/gc)
(def observations
  (mapv (fn [i]
          (let [sample (assoc (run-one (nth [:ordinary :direct :direct :ordinary] (mod i 4)))
                              :ordinal i)]
            (println (pr-str sample)) (flush) sample)) (range 24)))
(defn quantile [xs fraction]
  (nth (vec (sort xs)) (max 0 (dec (long (Math/ceil (* fraction (count xs))))))))
(def summary
  (into {} (for [mode [:ordinary :direct]
                 :let [samples (filter #(= mode (:mode %)) observations)]]
             [mode {:samples (count samples)
                    :ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                                    [label (/ (quantile (map :nanos samples) q) 1e6)]))
                    :median-estimated-bytes-per-row
                    (/ (quantile (map :estimated-allocation samples) 0.5) 5000.0)}])))
(spit (second *command-line-args*)
      (pr-str {:scope :stock-jolt-explicit-codec-component :runtime "official-v0.8.23"
               :reference-json "clojure/data.json@1fce9070ee851f49a6c4ce6e1c0bb0db4d7594a0"
               :rows 5000 :checks @checks :summary summary :observations observations
               :all-full-outputs-equal true :compiler-fork false :data-json-fork false
               :allocation-estimate-separate-nonatomic-statistics true
               :durable-qualified false :tail-qualified false}))
(println :summary (pr-str summary))
