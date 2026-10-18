;; Diagnostic over public runtime reduction; never reads a tree or :ops.
(lambda (key-var value-var)
  (let ((tag (keyword "jolt" "type")) (sorted-map-tag (keyword "jolt" "sorted-map")))
    (define (supported? m)
      (and (htable? m) (eq? (jolt-ref-get m tag) sorted-map-tag)))
    (define (convert m)
      (and (supported? m)
           (jolt-persistent!
             (into-fold
               (lambda (out entry)
                 (let* ((k (jolt-nth entry 0 jolt-nil))
                        (v (jolt-nth entry 1 jolt-nil))
                        (key (jolt-invoke1 (var-cell-deref key-var) k))
                        (value (jolt-invoke1 (var-cell-deref value-var) v)))
                   (tmap-put! out key value)
                   out))
               (jolt-transient-new empty-pmap) m))))
    (jolt-vector supported? convert)))
