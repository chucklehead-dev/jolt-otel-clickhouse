;; Untimed observer: determine whether actual SDK views amortize prefix plans.
(load-file "research/template-key-prefixes-20261021/prototype.clj")
(require '[perf.public-pipeline :as app]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed])
(def observations (atom []))
(def observed-source
  (-> candidate-source
      (replace-once "(let ((plan (template-key-plan template)))"
                    "(let ((plan (template-key-plan template))) (ffc-prefix-observe 2 (if plan 1 0) (pmap-cnt template) template-attempts)")
      (replace-once "(define (key! k)"
                    "(define (key! k) (ffc-prefix-observe 0 0 0 0)")
      (replace-once "(make-jolt-array out 'byte)"
                    "(begin (ffc-prefix-observe 1 template-attempts template-bytes (if template-cache (hashtable-size template-cache) 0)) (make-jolt-array out 'byte))")))
(let [native (compile-prefix-native observed-source
                                   (replace-once observed-source "(fx<? template-attempts 8)" "(fx<? template-attempts 0)")
                                   (fn [event a b c] (swap! observations conj [event a b c])))
      contexts (with-redefs [prefix-native native]
                 (into {} (map (fn [arm] [arm (prefix-context arm)]) [:original :disabled :prefix])))
      target (Object.) installation (#'installed/installed (app/approved) target)
      typed (projection/trace-vector-projector (:descriptor-set installation) target true
                                               (:folder (:original contexts)))
      encode (fn [arm rows]
               (let [context (contexts arm)
                     convert (#'exporter/payload-attrs-converter ((:payload-cache-factory context)))
                     project (fn [span] (into (#'exporter/span-row span nil true (:nested context) convert)
                                              (typed span)))]
                 (with-bindings {#'exporter/*timestamp-wire* :raw-ticks
                                 #'exporter/*json-backend* :native-guarded-byte-batch
                                 #'exporter/*experimental-attribute-transform* (:attributes context)}
                   ((:encode context) "SQL\n" rows project 67108864))))
      rows (app/fixture 32)
      original (encode :original rows)
      _ (reset! observations [])
      disabled (encode :disabled rows)
      _ (assert (bytes-equal? original disabled))
      disabled-key-calls (count (filter #(zero? (first %)) @observations))
      _ (reset! observations [])
      candidate (encode :prefix rows)
      _ (assert (bytes-equal? original candidate))
      stats (filter #(= 1 (first %)) @observations)
      plans (filter #(= 2 (first %)) @observations)
      report {:scope :untimed-rich-sdk-prefix-cache-mechanism :rows 32
              :full-output-bytes-equal true :output-bytes (alength original)
              :key-writer-calls (count (filter #(zero? (first %)) @observations))
              :disabled-key-writer-calls disabled-key-calls
              :encoder-invocations (count stats)
              :template-plan-attempts (reduce + (map second stats))
              :prefix-bytes-compiled (reduce + (map #(nth % 2) stats))
              :scalar-map-visits (count plans)
              :selected-plan-visits (count (filter #(= 1 (second %)) plans))
              :invocation-stat-frequencies (frequencies stats)
              :visit-stat-frequencies (frequencies plans)}]
  (spit (first *command-line-args*) (pr-str report))
  (prn report))
