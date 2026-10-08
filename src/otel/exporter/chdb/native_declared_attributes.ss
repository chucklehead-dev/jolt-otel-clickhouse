;; Invocation-owned storage; only the small array-map representation is admitted.
;; Keep normalized-key callbacks in seq order, even for undeclared attributes.
;; The output retains ALL matching values, not last-value-wins: duplicates are
;; invalid typed values even when their normalized key and value are equal.
(lambda (m wanted key-var contains-var)
  (and (pmap? m) (not (hnode? (pmap-root m))) (fx<=? (pmap-cnt m) 8)
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
                  (loop (fx+ i 2) used))))))))
