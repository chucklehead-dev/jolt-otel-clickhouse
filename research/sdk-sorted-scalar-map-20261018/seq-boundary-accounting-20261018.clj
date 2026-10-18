(require '[clojure.data.json :as json] '[clojure.data.json.jolt-native :as native]
         '[clojure.string :as str] '[jolt.scheme :as scheme])

(defn replace-required [source old new]
  (assert (str/includes? source old))
  (str/replace source old new))

;; Counters only: all existing flush/sync/sequence/writer calls still execute.
;; Logical publication character counts are not claims about physical memory
;; copies, allocation rate, or timing. This disposable process changes no files.
(let [original (force @#'native/byte-factory)
      source (slurp "/home/chuck/ai-src/worktrees/data-json-sorted-map-byte-seq-20261018/src/main/clojure/clojure/data/json/jolt_byte_writer.ss")
      _ (scheme/eval-string
          "(begin (define task-seq-account (make-vector 7 0))
             (define (task-seq-note! i n)
               (vector-set! task-seq-account i (+ n (vector-ref task-seq-account i))))
             (define (task-seq-reset!) (vector-fill! task-seq-account 0))
             (define (task-seq-count i) (vector-ref task-seq-account i)))")
      instrumented
      (-> source
          (replace-required "(define (observe thunk)"
                            "(define (observe thunk) (task-seq-note! 0 1)")
          (replace-required "(unless (fx=? used 0)"
                            "(unless (fx=? used 0) (task-seq-note! 1 1) (task-seq-note! 2 used)")
          (replace-required "(define (flush!)"
                            "(define (flush!) (task-seq-note! 3 1)")
          (replace-required "(sb-set! writer (djn-byte-batch-text batch row-start))"
                            "(let ((text (djn-byte-batch-text batch row-start)))
                               (task-seq-note! 4 1) (task-seq-note! 5 (string-length text))
                               (sb-set! writer text))")
          (replace-required "(djn-byte-batch-replace-row! batch row-start (djn-sb-str writer))"
                            "(let ((text (djn-sb-str writer)))
                               (task-seq-note! 6 (string-length text))
                               (djn-byte-batch-replace-row! batch row-start text))"))
      counted (scheme/eval-string instrumented)
      reset! (scheme/proc "task-seq-reset!") count-at (scheme/proc "task-seq-count")
      labels [:sequence-observations :nonempty-buffer-flushes :flushed-utf8-bytes
              :publication-flush-calls :batch-row-publications :batch-published-chars
              :batch-resynchronized-chars]
      scenarios (for [n [0 1 8 17 64 128]
                      context [:nested-string :direct-batch]]
                  [context n (into (sorted-map) (map (fn [i] [(str "field-" i) i]) (range n)))])
      operation (fn [factory context value]
                  (with-redefs-fn {#'native/byte-factory (delay factory)}
                    #(case context
                       :direct-batch (String. (native/write-prefixed-batch-bytes! "" [value] 1048576) "UTF-8")
                       :nested-string
                       (binding [json/*experimental-native-writer* (native/load-payload-byte-buffer-writer!)]
                         (json/write-str value)))))
      results (mapv (fn [[context n value]]
                      (let [expected (operation original context value)]
                        ;; Warm the live classification sites, then count only
                        ;; the subsequent full operation including final flush.
                        (operation counted context value)
                        (reset!)
                        (let [actual (operation counted context value)]
                          (assert (= expected actual))
                          {:context context :entries n :output-chars (count actual)
                           :full-byte-parity true
                           :counters (into {} (map-indexed (fn [i label] [label (count-at i)]) labels))})))
                    scenarios)
      report {:scope :logical-sequence-publication-accounting
              :timing-qualified false :physical-copy-count-qualified false
              :custom-boundaries-preserved true :results results}]
  (spit (first *command-line-args*) (pr-str report))
  (doseq [result results] (prn result)))
