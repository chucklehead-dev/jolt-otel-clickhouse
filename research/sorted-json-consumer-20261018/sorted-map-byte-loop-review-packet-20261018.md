Read-only narrow source review of casselc/data.json a18ae06. Use INLINE SOURCE ONLY. No tools or file access needed, do not ask questions. Return <=300words: concrete bug with witness, or no defect found under stated assumptions. Review ONLY new observe/emit-seq-map loop against included stock write-object and call-site admission. Existing serial factory's reentry/restoration/exception-cleanup machinery is unchanged and outside this review; tests cover those separately. The new branch is ONLY when htable? and the already-selected live JSONWriter equals stock map writer. Fast factory requires options eq? captured default option map; nondefault key/value-fn calls go to existing portable path. Stock key/value default identities are therefore guaranteed, but omission sentinel semantics still apply. Tag/ops can be customized: sequence operations are invoked not bypassed. Question: are seq/first/nth/key conversion/next/value write order, comma handling, exceptions and writer publication equivalent to stock? Existing flush publishes pending bytes, sync adopts callback writer edits, observe flushes then runs operation then syncs. Default-options and custom-options controls, lazy next and throwing seq/next, tagged continuations/list entries, method replacement and branch mutant all passed. Do not claim options transformations missing without checking included identity gate. Source/tests only, no credentials/telemetry, no edits/publication.

  (define (append-fallback! target call-batch)
    (when call-batch
      (let ((bytes (string->utf8 (djn-sb-str target))))
        (djn-byte-batch-append! call-batch bytes 0 (bytevector-length bytes)))))
  (define (byte! b)
    (when (fx=? used 65536) (flush-buffer!))
    ;; Private 64KiB scratch, used0..65536; flush resets used before a store.
    ;; Callers emit ASCII literals/digits/escaped characters only. Never apply
    ;; this unchecked store to arbitrary public byte arrays or callback values.
    (#3%bytevector-u8-set! buf used b)
    (set! used (fx+ used 1)))
  (define (ascii! s)
    (do ((i 0 (fx+ i 1))) ((fx=? i (string-length s)))
      (byte! (char->integer (#3%string-ref s i)))))
  (define (u16! cp)
  (define (invoke! impl x)
    (flush!)
    (let ((result (jolt-invoke3 impl x writer options)))
      (sync!) result))
  ;; The visitor uses only the restorable factory context and its arguments.
  ;; Construct it once, not once per map; nested emits retain their own fold
  ;; accumulator and reentrant rows restore the surrounding writer context.
  (define (emit-map-entry! k child printed?)
    (let ((name (if (string? k) k
                    (begin (flush!)
                      (let ((name (jolt-invoke1 (vector-ref stock-slots 10) k)))
                        (sync!) name)))))
      ;; Unknown equality representations may themselves be observable;
      ;; preserve the stock sentinel check and its flush boundary.
      (unless (or (jolt-nil? child) (string? child) (boolean? child)
                  (number? child) (keyword? child) (symbol-t? child)
                  (pvec? child) (pmap? child) (procedure? child)) (flush!))
      (unless (string? name)
        (flush!)
        (jolt-throw (host-new "Exception" "JSON object keys must be strings")))
      (let ((omit? (jolt=2 (vector-ref stock-slots 9) child)))
        (sync!)
        (if omit? printed?
            (begin (when printed? (byte! 44))
                   (string! name) (byte! 58) (emit! child) #t)))))
  ;; Use the receiver's public seq operation, never its private tree. Tagged
  ;; tables may carry custom operations, and lazy sequence callbacks can inspect
  ;; or replace the actual writer. Publish/synchronize at every such boundary.
  (define (observe thunk)
    (flush!)
    (let ((result (thunk))) (sync!) result))
  (define (emit-seq-map! x)
    (byte! 123)
    (let loop ((xs (observe (lambda () (jolt-seq x)))) (printed? #f))
      (unless (jolt-nil? (observe (lambda () (jolt-seq xs))))
        (let* ((entry (observe (lambda () (jolt-first xs))))
               (key (observe (lambda () (jolt-nth entry 0 jolt-nil))))
               (child (observe (lambda () (jolt-nth entry 1 jolt-nil))))
               (name (if (string? key) key
                         (observe (lambda ()
                           (jolt-invoke1 (vector-ref stock-slots 10) key)))))
               ;; Stock write-object realizes next after key conversion but
               ;; before validating the key or invoking the child JSONWriter.
               (next (observe (lambda () (jolt-next xs))))
               (printed-next? (emit-map-entry! name child printed?)))
          (unless (jolt-nil? (observe (lambda () (jolt-seq next))))
            (loop next printed-next?)))))
    (byte! 125))
  (define (emit! x)
    (let ((root (var-cell-root djn-method-cell)))
      (if (not (eq? root (vector-ref stock-slots 0)))
          (invoke! root x)
          (let ((impl (site x)))
            (sync!)
            (cond
              ((and (string? x) (eq? impl (vector-ref stock-slots 6))) (string! x))
              ((and (jolt-nil? x) (eq? impl (vector-ref stock-slots 1))) (ascii! "null"))
              ((and (boolean? x) (eq? impl (vector-ref stock-slots 2)))
               (ascii! (if x "true" "false")))
              ((and (integer? x) (exact? x)
                    (or (eq? impl (vector-ref stock-slots 2)) (eq? impl (vector-ref stock-slots 4))))
               (integer! x))
              ((and (flonum? x) (finite? x) (eq? impl (vector-ref stock-slots 3)))
               (ascii! (jolt-flonum->string x)))
              ((and (or (keyword? x) (symbol-t? x)) (eq? impl (vector-ref stock-slots 5)))
               (string! (if (keyword? x) (keyword-t-name x) (symbol-t-name x))))
              ((and (pvec? x) (eq? impl (vector-ref stock-slots 8)))
               (byte! 91)
               (do ((i 0 (fx+ i 1))) ((fx=? i (pvec-count x)))
                 (unless (fx=? i 0) (byte! 44)) (emit! (pvec-nth! x i)))
               (byte! 93))
              ((and (pmap? x) (eq? impl (vector-ref stock-slots 7)))
               (byte! 123)
               (pmap-fold-seq-order x emit-map-entry! #f)
               (byte! 125))
              ;; The already-selected stock map method establishes map
              ;; semantics. Inspect no user tag/equality and bypass no :ops.
              ((and (htable? x) (eq? impl (vector-ref stock-slots 7)))
               (emit-seq-map! x))
              (else (invoke! impl x)))))))

    ;; One set of dynamic-wind closures per serial factory, not per row.
    ;; Reentrant writes save and restore these invocation fields with the
    ;; existing writer/scratch context. Idle factories retain no input row.
    (define (run-row!)
      (emit! row-value) (ascii! row-ending) (set! row-complete? #t))
    (define (flush-row!)
      (vector-set! sink-reuse-proof 0 #f)
      (sync!) (flush-buffer!)
      (when (and batch ever-exposed?)
        (sb-set! writer (djn-byte-batch-text batch row-start)))
      ;; Publish only after successful flush, before restoring the outer row.
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
(defn- write-object [m ^Appendable out options]
  (let [key-fn (get options :key-fn)
        value-fn (get options :value-fn)
        default-key? (identical? key-fn default-write-key-fn)
        default-value? (identical? value-fn default-value-fn)
        indent (get options :indent)
        opts (cond-> options
               indent (update :indent-depth inc))]
    (.append out \{)
    (when (and indent (seq m))
      (write-indent out opts))
    ;; Keep the map view once. In Jolt, seq/first/next on a map each build a
    ;; complete entry view; iterating the seq avoids rebuilding it per object.
    (loop [x (seq m), have-printed-kv false]
      (when (seq x)
        (let [[k v] (first x)
              out-key (if (and default-key? (string? k)) k (key-fn k))
              out-value (if default-value? v (value-fn k v))
              nxt (next x)]
          (when-not (string? out-key)
            (throw (Exception. "JSON object keys must be strings")))
          (if-not (= value-fn out-value)
            (do
              (when have-printed-kv
                (.append out \,)
                (when indent
                  (write-indent out opts)))
              (write-string out-key out opts)
              (.append out \:)
              (when indent
                (.append out \space))
              (-write out-value out opts)
              (when (seq nxt)
                (recur nxt true)))
            (when (seq nxt)
              (recur nxt have-printed-kv))))))
    (when (and indent (seq m))
      (write-indent out options)))
  (.append out \}))

(defn- write-array [s ^Appendable out options]
  (let [indent (get options :indent)
        opts (cond-> options

