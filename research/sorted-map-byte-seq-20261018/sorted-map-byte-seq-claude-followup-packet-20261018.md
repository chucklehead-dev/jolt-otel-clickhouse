Read-only followup of the exact sorted-map byte-sequence patch0960c8f at /home/chuck/ai-src/worktrees/data-json-sorted-map-byte-seq-20261018. No tools/edits/publication. Your first review claimed custom key/value options were ignored. The initial packet omitted the surrounding existing factory guard; it admits only call-options eq? stock default options. Nondefault calls go to existing djn-write!, whose option guard declines to portable stock. pmap-fold is a raw map traversal, never applies valuefn. Production code is unchanged since review. New options/callback tests and throw-seq/next cleanup, tagged continuation/list-entry tests all passed:10tests34assertions. Read guards below and the new tests: reconcile prior findings1-4,6-7 against actual admission/stock ordering. Missing htable in child no-flush whitelist is intentional: unknown equality/observation retains flush. Do not recommend dropping it without contract evidence. Source review only, <=500words concrete remaining defects or test gaps. Full original packet remains contextual in prior review not available here; below all new evidence needed to correct option claims.

      (unless (or (null? endings)
                  (and (equal? (car endings) "\n")
                       (or (null? (cdr endings))
                           (and (null? (cddr endings))
                                (vector? (cadr endings))
                                (= (vector-length (cadr endings)) 4)
                                (bytevector? (vector-ref (cadr endings) 1))))))
        (error 'byte-writer "only a row newline terminator is supported"))
      (let ((ending (if (null? endings) "" (car endings)))
            (call-batch (and (pair? endings) (pair? (cdr endings)) (cadr endings))))
        (if (not (and (pvec? call-stock) (fx=? (pvec-count call-stock) 11)
                      (eq? (pvec-nth! call-stock 10) abi-tag)
                      (string-writer? call-writer)
                      (eq? call-options (pvec-nth! call-stock 9))
                      ;; Cache only immutable default-option qualification,
                      ;; never dispatch/callback decisions or mutable values.
                      (or (eq? call-options qualified-defaults)
                          (and (for-all (lambda (k)
                                          (eq? (pmap-fast-get call-options k pmap-absent) #t))
                                        djn-escape-keys)
                               (begin (set! qualified-defaults call-options) #t)))))
            (begin (djn-write! value call-writer call-options call-stock)
                   (unless (string=? ending "") (djn-sb-append! call-writer ending))
                   (append-fallback! call-writer call-batch)
                   (vector-set! sink-reuse-proof 0 #f))
            (if (djn-empty-default-vector! value call-writer call-options call-stock)
                (begin
                  ;; Preserve the earlier native empty-vector win, including
                  ;; live custom vector dispatch, before allocating row state.
                  (unless (string=? ending "") (djn-sb-append! call-writer ending))
                  (append-fallback! call-writer call-batch)
                  (vector-set! sink-reuse-proof 0 #f)
                  jolt-nil)
            (let ((previous (and writer
                                 (vector writer options stock buf used flush-epoch scratch
                                         batch row-start exposed? ever-exposed?
                                         row-value row-ending row-previous row-loan row-complete?)))
                  (loan (or spare
                            (vector (make-bytevector 65536) (make-bytevector 21)))))
              (set! spare #f)
              (set! writer call-writer) (set! options call-options) (set-stock! call-stock)
              (set! buf (vector-ref loan 0)) (set! scratch (vector-ref loan 1))
              (set! used 0) (set! flush-epoch 0)
              (set! batch call-batch)
              (set! row-start (if batch (djn-byte-batch-size batch) 0))
              (set! exposed? #f) (set! ever-exposed? #f)
              (set! row-value value) (set! row-ending ending)
              (set! row-previous previous) (set! row-loan loan)
              (set! row-complete? #f)
              (dynamic-wind jolt-finally-in run-row! finish-row!)
              jolt-nil)))))) sink-reusable?)))
djn-make-byte-buffer-writer

(define (djn-checked-flags options defaults)
  ;; Only the three boolean escaping switches may differ. Unknown/custom
  ;; options (including callbacks) fall back BEFORE any output. Identity checks
  ;; intentionally prefer a conservative fallback over calling user equality.
  (and (pmap? options)
       (fx=? (pmap-cnt options) (pmap-cnt defaults))
       (pmap-fold-fwd options
         (lambda (k v ok)
           (and ok
                (if (memq k djn-escape-keys)
                    (boolean? v)
                    (eq? v (pmap-fast-get defaults k pmap-absent))))) #t)
       (list->vector
         (append (map (lambda (k) (pmap-fast-get options k pmap-absent))
                      djn-escape-keys)
                 '(#f)))))

(define (djn-flags options defaults)
  (or (and (pmap? options) (eq? options defaults)
           ;; Public write-str without options passes this captured immutable
           ;; map. Read its actual flags; never cache the call-local scratch.
           (let ((unicode? (pmap-fast-get options (car djn-escape-keys) pmap-absent))
                 (slash? (pmap-fast-get options (cadr djn-escape-keys) pmap-absent))
                 (js? (pmap-fast-get options (caddr djn-escape-keys) pmap-absent)))
             (and (boolean? unicode?) (boolean? slash?) (boolean? js?)
                  (vector unicode? slash? js? #f))))
    (is (= ["{\"a\":42}" 0] (probe mutant)))))

(deftest nondefault-options-retain-the-portable-path-and-callbacks
  (doseq [mode [:keys :values :omit]]
    (let [run (fn [native?]
                (let [effects (atom [])
                      transform (fn value [k v]
                                  (swap! effects conj [:value k v])
                                  (if (and (= mode :omit) (= k "b")) value (str v "!")))
                      key-fn (fn [k] (swap! effects conj [:key k]) (str "prefix-" k))
                      opts (case mode :keys [:key-fn key-fn] [:value-fn transform])
                      result (binding [json/*experimental-native-writer*
                                       (when native? (native/load-payload-byte-buffer-writer!))]
                               (apply json/write-str (sorted-map "a" 42 "b" false) opts))]
                  [result @effects]))]
      (is (= (run false) (run true)))))
  (let [value (sorted-map "a" (:value-fn json/default-write-options) "b" 42)]
    (is (= "{\"b\":42}" (candidate value)))))

(deftest throwing-seq-and-next-preserve-partial-output-and-allow-reuse
  (doseq [site [:seq :next]]
    (let [write (native/load-payload-byte-buffer-writer!)
          run (fn [native?]
                (let [out (java.io.StringWriter.) value (sorted-map "ignored" false)
                      ops (host/ref-get value :ops)]
                  (host/ref-put! value :ops
                    (assoc ops :seq
                      (fn [_]
                        (if (= site :seq)
                          (do (.write out " ") (throw (ex-info "expected-seq" {})))
                          (cons ["a" 42]
                                (lazy-seq (.write out " ")
                                          (throw (ex-info "expected-next" {}))))))))
                  [(try (if native?
                          (write value out json/default-write-options @#'json/native-writer-stock)
                          (json/write value out))
                        :missing-error (catch Throwable _ :threw))
                   (.toString out)]))]
      (is (= (run false) (run true)))
      (let [out (java.io.StringWriter.)]
        (write (sorted-map "ok" true) out json/default-write-options @#'json/native-writer-stock)
        (is (= "{\"ok\":true}" (.toString out)))))))

(deftest custom-seq-can-return-a-tagged-continuation-and-list-entries
  (let [run (fn [native?]
              (let [effects (atom []) out (java.io.StringWriter.)
                    outer (sorted-map "outer" 1) continuation (sorted-map "inner" 2)
                    entries (list (list "a" 42) (list "z" false))]
                (host/ref-put! continuation :ops
                  (assoc (host/ref-get continuation :ops)
                         :seq (fn [_] (swap! effects conj [:continuation-seq (.toString out)]) entries)
                         :first (fn [_] (swap! effects conj [:continuation-first (.toString out)])
                                  (first entries))))
                (host/ref-put! outer :ops
                  (assoc (host/ref-get outer :ops) :seq
                         (fn [_] (swap! effects conj [:outer-seq (.toString out)]) continuation)))
                (if native?
                  ((native/load-payload-byte-buffer-writer!) outer out json/default-write-options
                                                             @#'json/native-writer-stock)
                  (json/write outer out))
                [(.toString out) @effects]))]
    (is (= (run false) (run true)))))

(defn -main [& _]
  (let [r (run-tests 'clojure.data.json-sorted-byte-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))

