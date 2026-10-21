;; One-process source-only ABBA. Temporary codec factory bindings are used ONLY
;; during context construction, never during timing or SDK policy conversion.
(require '[clojure.java.shell :as shell] '[clojure.string :as str]
         '[perf.public-pipeline :as app] '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[jolt.codec.experimental :as codec] '[jolt.host :as host]
         '[jolt.scheme :as scheme])

(def baseline-revision "581b502")
(defn replace-once [source needle replacement]
  (assert (= 2 (count (str/split source (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
  (str/replace source needle replacement))
(let [{:keys [exit out]} (shell/sh "git" "show"
                           (str baseline-revision ":incubator/codec/src/jolt/codec/json.ss"))
      _ (assert (zero? exit))
      source (slurp "incubator/codec/src/jolt/codec/experimental.clj")
      source (replace-once source "(ns jolt.codec.experimental" "(ns research.integer-baseline")
      source (replace-once source "(def ^:private json-source (embedded \"jolt/codec/json.ss\"))"
                           (str "(def ^:private json-source " (pr-str out) ")"))]
  (binding [*ns* *ns*] (load-string source)))

(defn quantile [xs p]
  (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))
(defn counters []
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes) :cpu (host/cpu-nanos)
   :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos)})
(def bytes-equal?
  (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))"))

(let [construct #(#'e/load-experimental-span-codec! true true true true true)
      replacements (into {} (for [[name var] (ns-publics 'jolt.codec.experimental)
                                  :let [baseline (ns-resolve 'research.integer-baseline name)]
                                  :when baseline] [var @baseline]))
      contexts {:baseline (with-redefs-fn replacements construct) :candidate (construct)}
      target (Object.) installation (#'installed/installed (app/approved) target)
      typed (projection/trace-vector-projector (:descriptor-set installation) target true
                                               (:folder (:candidate contexts)))
      rows (app/fixture 5000)
      encode (fn [mode values]
               (let [context (get contexts mode)
                     convert (#'e/payload-attrs-converter ((:payload-cache-factory context)))
                     project (fn [span]
                               (into (#'e/span-row span nil true (:nested context) convert) (typed span)))]
                 (with-bindings {#'e/*timestamp-wire* :raw-ticks
                                 #'e/*json-backend* :native-guarded-byte-batch
                                 #'e/*experimental-attribute-transform* (:attributes context)}
                   ((:encode context) "SQL\n" values project 67108864))))
      expected (encode :baseline rows)
      _ (assert (bytes-equal? expected (encode :candidate rows)))
      _ (doseq [mode [:baseline :candidate]]
          (assert (not (bytes-equal? expected (encode mode (assoc-in rows [0 :start-time-unix-nano] 0))))))
      order [:baseline :candidate :candidate :baseline]
      _ (doseq [mode order] (encode mode rows))
      observations
      (mapv (fn [ordinal]
              (let [mode (nth order (mod ordinal 4))
                    before (counters) start (System/nanoTime)
                    output (encode mode rows) elapsed (- (System/nanoTime) start) after (counters)]
                (assert (bytes-equal? expected output))
                {:ordinal ordinal :mode mode :nanos elapsed
                 :estimated-bytes (- (+ (:heap after) (:gc-bytes after)) (+ (:heap before) (:gc-bytes before)))
                 :cpu-nanos (- (:cpu after) (:cpu before))
                 :gc-count (- (:gc-count after) (:gc-count before))
                 :gc-nanos (- (:gc-nanos after) (:gc-nanos before))})) (range 32))
      summary
      (into {} (for [[mode samples] (group-by :mode observations)]
                 [mode {:p50-p90-p95-p99-ms
                        (mapv #(/ (quantile (map :nanos samples) %) 1e6) [0.5 0.9 0.95 0.99])
                        :median-estimated-bytes-per-row
                        (/ (quantile (map :estimated-bytes samples) 0.5) 5000.0)}]))]
  (spit (first *command-line-args*)
    (pr-str {:scope :rich-source-integer-block-copy :baseline-revision baseline-revision
             :rows 5000 :samples-per-arm 16 :output-bytes (alength expected)
             :full-output-bytes-equal true :changed-timestamp-controls true
             :sdk-policy-and-typed-projection-included true :private-component-probes true
             :observer-callbacks false :durable-io false :release-optimization-qualified false
             :tail-qualified false :summary summary :observations observations}))
  (prn summary))
