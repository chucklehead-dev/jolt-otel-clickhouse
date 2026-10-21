(load-file "/home/chuck/ai-src/worktrees/exporter-pipeline-phase-costs-20261021/research/key-fragment-cache-20261021/prototype.clj")
(require '[clojure.data.json :as json])

(def equal-bytes?
  (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))"))
(defn throws? [f] (try (f) false (catch Throwable _ true)))

(let [[old-lines old-scalar old-scope] (nth key-cache-native 0)
      [new-lines new-scalar new-scope] (nth key-cache-native 1)
      same-a (String. (.getBytes "same" "UTF-8") "UTF-8")
      same-b (String. (.getBytes "same" "UTF-8") "UTF-8")
      unicode "quote\"/slash\\newline\nλ漢字😀"
      large-key (apply str (repeat 65 "k"))
      wide (into (sorted-map) (map (fn [i] [(str "key-" i) i]) (range 128)))
      inputs [nil false true 42 unicode [] {} {same-a 1}
              [{same-a 1} {same-b 2} {same-a nil}]
              (array-map "first" 1 "second" false)
              (array-map "second" false "first" 1)
              {unicode [unicode nil true -9223372036854775808]}
              [wide wide {large-key 7} {large-key 9}]
              [(sorted-map "a" 1 "b" 2) (sorted-map "b" 3 "c" 4)]
              [{:ns/key 1 'ns/key 2} {:ns/key 3 'ns/key 4}]]]
  (assert (not (identical? same-a same-b)))
  (doseq [value inputs]
    (assert (equal-bytes? (old-scalar value 67108864 128) (new-scalar value 67108864 128))))
  (doseq [record-count [2 129 1000]
          limit-offset [0 -1]]
    (let [records (mapv #(array-map "varying" % "escaped" unicode) (range record-count))
          prefix "SQL\n" expected (old-lines prefix records identity 67108864 128)
          maximum (+ (- (alength expected) (count prefix)) limit-offset)
          a #(old-lines prefix records identity maximum 128)
          b #(new-lines prefix records identity maximum 128)]
      (if (zero? limit-offset)
        (assert (equal-bytes? (a) (b)))
        (do (assert (throws? a)) (assert (throws? b))))))
  (doseq [encode [old-lines new-lines]]
    (let [seen (atom [])
          first-row (alength (encode "" [{same-a 1}] identity 4096 128))]
      (assert (throws? #(encode "" [{same-a 1} {same-b 2} :must-not-run]
                                 (fn [row] (swap! seen conj row) row) (+ first-row 2) 128)))
      (assert (= 2 (count @seen)))))
  ;; Hits never skip value encoding, current depth or fresh output ownership.
  (let [records [{same-a "first"} {same-b "second"}]
        first-output (new-lines "" records identity 4096 128)
        frozen (aclone first-output)
        changed (new-lines "" [{same-a "changed"} {same-b "second"}] identity 4096 128)]
    (assert (not (equal-bytes? first-output changed)))
    (assert (equal-bytes? first-output frozen))
    (assert (throws? #(new-lines "" records identity 4096 0)))
    (assert (throws? #(old-lines "" records identity 4096 0))))
  ;; Actual shared nested scope; outputs survive close and a retained producer
  ;; rejects. Repeated names carry DIFFERENT values, so this is not value reuse.
  (let [[old-prepare old-finish] (old-scope)
        [new-prepare new-finish] (new-scope)
        values [{unicode 1} {unicode 2} {unicode false}]
        old-texts (mapv #(old-prepare % 4096 128) values)
        new-texts (mapv #(new-prepare % 4096 128) values)]
    (old-finish) (new-finish)
    (assert (equal-bytes? (old-scalar old-texts 32768 128) (new-scalar new-texts 32768 128)))
    (assert (throws? #(new-prepare {} 4096 128))))
  ;; A hit-only throwing mutant must pass a single unique key and fail only
  ;; after repeated content. This witnesses the intended cached-byte branch.
  (let [mutant-source (replace-once key-cache-candidate-source
                                   "(key-fragment! cached)"
                                   "(error 'key-cache-hit \"hit witness\")")
        mutant (compile-key-cache-native mutant-source)
        scalar (nth (nth mutant 1) 1)]
    (scalar {same-a 1} 4096 128)
    (assert (throws? #(scalar [{same-a 1} {same-b 2}] 4096 128))))
  ;; Expose only scalar cache counters in a separately compiled TEST variant.
  ;; No instrumentation or getter is present in the timed candidate.
  (let [instrument (fn [source]
                     (replace-once source
                       "(ffc-fragments-digits-set! fragments #f)))))"
                       "(ffc-fragments-digits-set! fragments #f))\n     (lambda () (jolt-vector (ffc-fragments-key-count fragments) (ffc-fragments-key-bytes fragments))))))"))
        observed (compile-key-cache-native (instrument key-cache-candidate-source))
        scope (nth (nth observed 1) 2)
        [prepare finish stats] (scope)]
    (prepare wide 32768 128)
    (assert (= 64 (nth (stats) 0)))
    (assert (<= (nth (stats) 1) 65536))
    (let [before (stats)]
      (prepare {large-key 17} 32768 128)
      (assert (= before (stats))))
    (finish)
    (assert (= [0 0] (stats)))
    (let [bad (compile-key-cache-native
                (instrument (replace-once key-cache-candidate-source
                                         "(fx<? key-count 64)" "(fx<? key-count 65)")))
          [prepare finish stats] ((nth (nth bad 1) 2))]
      (prepare wide 32768 128)
      (assert (= 65 (nth (stats) 0)))
      (assert (throws? #(assert (<= (nth (stats) 0) 64))))
      (finish)))
  (println :key-fragment-controls-green :scalar-cases (count inputs)
           :limit-and-chunk-cases 6 :equal-distinct-keys true :hit-only-mutant true
           :scope-budget-and-clear true :off-by-one-retention-mutant true :no-later-row true))
