;; Internal source-only kernel. Caller preserves ordinary attrs classifiers.
;; Array mode <=8 entries cannot promote even if normalized keys collapse.
;; Storage belongs to this invocation; reentrant converters share no scratch.
;; Wider built-in maps use the existing ordered fold, up to 128 entries.
;; This is an optimization admission bound, not an attribute capture limit.
(lambda (m key-var value-var)
  (and (pmap? m) (fx<=? (pmap-cnt m) 128)
    (if (or (hnode? (pmap-root m)) (fx>? (pmap-cnt m) 8))
        ;; Reuse the runtime's exact seq-order fold, including collision
        ;; buckets. The editable map is invocation-owned and freezes through
        ;; the same promotion rules as the ordinary transient reducer.
        (let ((out (jolt-transient-new empty-pmap)))
          (pmap-fold-seq-order m
            (lambda (k v acc)
              (let* ((key (jolt-invoke1 (var-cell-deref key-var) k))
                     (value (jolt-invoke1 (var-cell-deref value-var) v)))
                (tmap-put! out key value)
                out)) out)
          (jolt-persistent! out))
      (let* ((root (pmap-root m)) (n (vector-length root))
           (slots (make-vector n #f)))
      (let loop ((i 0) (used 0))
        (if (fx=? i n)
            (make-pmap (if (fx=? used n) slots (vec-copy-range slots 0 used))
                       (fxquotient used 2))
            (let* ((key (jolt-invoke1 (var-cell-deref key-var) (vector-ref root i)))
                   (value (jolt-invoke1 (var-cell-deref value-var) (vector-ref root (fx+ i 1))))
                   (at (amap-index slots used key)))
              (if (fx<? at 0)
                  (begin (vector-set! slots used key)
                         (vector-set! slots (fx+ used 1) value)
                         (loop (fx+ i 2) (fx+ used 2)))
                  (begin (vector-set! slots (fx+ at 1) value)
                         (loop (fx+ i 2) used))))))))))
