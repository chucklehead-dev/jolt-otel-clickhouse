;; Invocation-owned storage; built-in maps up to 128 entries are admitted.
;; Keep normalized-key callbacks in seq order, even for undeclared attributes.
;; The output retains ALL matching values, not last-value-wins: duplicates are
;; invalid typed values even when their normalized key and value are equal.
(lambda (m wanted key-var contains-var)
  (and (pmap? m) (fx<=? (pmap-cnt m) 128)
    (if (or (hnode? (pmap-root m)) (fx>? (pmap-cnt m) 8))
      ;; A wide input usually matches only a few declared keys. Keep up to
      ;; eight string-key groups in invocation-owned slots rather than paying
      ;; transient-map setup for each row. Unknown normalized key types and
      ;; ninth groups switch to the established builder without reconverting.
      (let* ((capacity (if (pset? wanted) (fxmin 16 (fx* 2 (pset-count wanted))) 16))
             (slots (make-vector capacity #f)) (used 0) (out #f))
        (define (promote!)
          (set! out (jolt-transient-new empty-pmap))
          (let loop ((i 0))
            (unless (fx=? i used)
              ;; Previously admitted keys are primitive strings, so replay
              ;; has no user hash/equality callbacks to repeat or delay.
              (tmap-put! out (vector-ref slots i) (vector-ref slots (fx+ i 1)))
              (loop (fx+ i 2))))
          (set! slots #f))
        (define (put! key value)
          (if out
              (tmap-put! out key (pvec-conj (t-get out key empty-pvec) value))
              (if (not (string? key))
                  (begin (promote!) (put! key value))
                  (let ((at (amap-index slots used key)))
                    (cond
                      ((fx>=? at 0)
                       (vector-set! slots (fx+ at 1)
                                    (pvec-conj (vector-ref slots (fx+ at 1)) value)))
                      ((fx<? used capacity)
                       (vector-set! slots used key)
                       (vector-set! slots (fx+ used 1) (make-pvec (vector value)))
                       (set! used (fx+ used 2)))
                      (else (promote!) (put! key value)))))))
        (pmap-fold-seq-order m
          (lambda (k value acc)
            (let ((key (jolt-invoke1 (var-cell-deref key-var) k)))
              (when (jolt-truthy? (jolt-invoke2 (var-cell-deref contains-var) wanted key))
                (put! key value))
              acc)) #f)
        (if out (jolt-persistent! out)
            (make-pmap (vec-copy-range slots 0 used) (fxquotient used 2))))
    (let* ((root (pmap-root m)) (n (vector-length root))
           (slots (make-vector n #f)))
      (let loop ((i 0) (used 0))
        (if (fx=? i n)
            (make-pmap (if (fx=? used n) slots (vec-copy-range slots 0 used))
                       (fxquotient used 2))
            (let ((key (jolt-invoke1 (var-cell-deref key-var) (vector-ref root i))))
              (if (jolt-truthy? (jolt-invoke2 (var-cell-deref contains-var) wanted key))
                  (let ((at (amap-index slots used key))
                        (value (vector-ref root (fx+ i 1))))
                    (if (fx<? at 0)
                        (begin
                          (vector-set! slots used key)
                          (vector-set! slots (fx+ used 1) (make-pvec (vector value)))
                          (loop (fx+ i 2) (fx+ used 2)))
                        (begin
                          (vector-set! slots (fx+ at 1)
                                       (pvec-conj (vector-ref slots (fx+ at 1)) value))
                          (loop (fx+ i 2) used))))
                  (loop (fx+ i 2) used)))))))))
