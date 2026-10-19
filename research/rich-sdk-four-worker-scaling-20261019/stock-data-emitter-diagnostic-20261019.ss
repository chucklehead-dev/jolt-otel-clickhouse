;; DIAGNOSTIC ONLY: closed builtin-data serializer, deliberately bypassing
;; JSONWriter roots/methods/receiver predicates and public key callbacks.
;; Not compatible with the selected backend's extension contracts; never a
;; proposed transparent optimization. Same escaping/sink/budget helpers remain.
(define (emit-stock-data-entry! k child printed?)
  (let ((name (cond ((string? k) k)
                    ((keyword? k) (keyword-t-name k))
                    ((symbol-t? k) (symbol-t-name k))
                    (else (error 'stock-data-emitter "unsupported diagnostic key")))))
    (if (eq? (vector-ref stock-slots 9) child) printed?
        (begin
          (when printed? (byte! 44))
          (string! name) (byte! 58) (emit! child) #t))))
(define (emit-stock-data! x)
  (cond
    ((string? x) (string! x))
    ((jolt-nil? x) (ascii! "null"))
    ((boolean? x) (ascii! (if x "true" "false")))
    ((and (integer? x) (exact? x)) (integer! x))
    ((and (flonum? x) (finite? x)) (ascii! (jolt-flonum->string x)))
    ((keyword? x) (string! (keyword-t-name x)))
    ((symbol-t? x) (string! (symbol-t-name x)))
    ((pvec? x)
     (byte! 91)
     (do ((i 0 (fx+ i 1))) ((fx=? i (pvec-count x)))
       (unless (fx=? i 0) (byte! 44)) (emit! (pvec-nth! x i)))
     (byte! 93))
    ((pmap? x)
     (byte! 123) (pmap-fold-seq-order x emit-stock-data-entry! #f) (byte! 125))
    ((and (htable? x) djn-stock-sorted-map-folder
          ((vector-ref djn-stock-sorted-map-folder 0) x))
     (byte! 123)
     ((vector-ref djn-stock-sorted-map-folder 1) x emit-stock-data-entry! #f)
     (byte! 125))
    (else (error 'stock-data-emitter "unsupported diagnostic value"))))
