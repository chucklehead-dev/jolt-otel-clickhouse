(require '[clojure.string :as str] '[jolt.host :as host]
         '[jolt.codec.experimental :as codec])
(defn replace-once [source needle replacement]
  (assert (= 2 (count (str/split source (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
  (str/replace source needle replacement))
(defn frozen-writer [namespace root]
  (let [source (slurp (str root "/experimental.clj"))
        json (slurp (str root "/json.ss"))
        source (replace-once source "(ns jolt.codec.experimental" (str "(ns " namespace))
        source (replace-once source "(def ^:private json-source (embedded \"jolt/codec/json.ss\"))"
                             (str "(def ^:private json-source " (pr-str json) ")"))]
    (binding [*ns* *ns*] (load-string source))
    (let [c ((ns-resolve namespace 'open-codec))
          emit @(ns-resolve namespace 'write-nested)]
      (fn [value] (emit c value)))))
(let [c (codec/open-codec)
      writers {:old (frozen-writer 'research.pre-flat-codec
                     "/home/chuck/ai-src/worktrees/exporter-traversal-module-20261019/incubator/codec/src/jolt/codec")
               :dynamic (frozen-writer 'research.dynamic-flat-codec
                         "/home/chuck/ai-src/worktrees/exporter-flat-map-wire-cache-20261019/incubator/codec/src/jolt/codec")
               :specialized #(codec/write-nested c %)}
      value [{"name" "event" "attributes" {"flag" false "count" 42 "service" "producer"}}]
      expected ((writers :old) value)
      _ (doseq [[_ f] writers]
          (assert (= expected (f value)))
          (dotimes [_ 500] (f value)))
      samples (mapv (fn [i]
                      (let [kind (nth [:old :dynamic :specialized :specialized :dynamic :old] (mod i 6))
                            emit (writers kind)
                            before (+ (host/gc-bytes) (host/bytes-allocated))
                            start (System/nanoTime)
                            _ (dotimes [_ 5000] (emit value))
                            nanos (- (System/nanoTime) start)
                            after (+ (host/gc-bytes) (host/bytes-allocated))]
                        {:ordinal i :mode kind :calls 5000 :nanos nanos
                         :estimated-bytes-per-call (/ (- after before) 5000.0)})) (range 18))]
  (spit (first *command-line-args*) (pr-str {:scope :scalar-cache-state-allocation-probe
                                          :full-string-equality true :samples samples}))
  (doseq [kind [:old :dynamic :specialized]]
    (let [s (filter #(= kind (:mode %)) samples)]
      (prn kind :estimated-bytes-per-call (mapv :estimated-bytes-per-call s)))))
