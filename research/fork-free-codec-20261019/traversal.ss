;; Research backend, qualified only against the official Chez Jolt v0.8.23.
;; Representation access is centralized here, not added to the runtime.
;; Synchronous full folds; exceptions stop traversal. No Reduced convention yet.
;; No entries/seqs are constructed; collision buckets consume recursion stack.
(define (ffc-map-fold m visit acc)
  (unless (pmap? m) (error 'ffc-map-fold "expected an ordinary persistent map"))
  (let ((root (pmap-root m)))
    (if (not (hnode? root))
        (let ((n (vector-length root)))
          (let loop ((i 0) (a acc))
            (if (fx=? i n) a
                (loop (fx+ i 2) (visit (vector-ref root i)
                                      (vector-ref root (fx+ i 1)) a)))))
        (let walk ((node root) (a acc))
          (let ((children (hnode-arr node)))
            (let loop ((i 0) (a a))
              (if (fx=? i (vector-length children)) a
                  (let* ((child (vector-ref children i))
                         (next
                          (cond
                           ((hnode? child) (walk child a))
                           ((hcoll? child)
                            ;; Stock seq reverses the physical collision alist;
                            ;; callback effects must follow that same order.
                            (let backward ((pairs (hcoll-alist child)))
                              (if (null? pairs) a
                                  (let ((next (backward (cdr pairs))))
                                    (visit (caar pairs) (cdar pairs) next)))))
                           (else (visit (car child) (cdr child) a)))))
                    (loop (fx+ i 1) next)))))))))

(define (ffc-vector-fold v visit acc)
  (unless (pvec? v) (error 'ffc-vector-fold "expected a persistent vector"))
  (let ((n (pvec-count v)))
    (let loop ((i 0) (a acc))
      (if (fx=? i n) a
          (loop (fx+ i 1) (visit (pvec-nth! v i) a))))))
