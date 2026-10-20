;; Independent traversal backend, qualified on official Chez Jolt v0.8.23.
;; Representation access is centralized here, not added to the runtime.
;; Synchronous full folds; exceptions stop traversal. No Reduced convention yet.
;; No entries/seqs are constructed; collision buckets consume recursion stack.
(define (ffc-pmap-fold m visit acc)
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

;; Source-mode qualification only: core's private operations and immutable
;; sorted tree representation are trusted. No custom per-object ops bypass.
;; A standalone build must separately prove retention of this defining Var.
(define ffc-sorted-ops-cell (jolt-var "clojure.core" "sm-ops"))
(define (ffc-stock-sorted-map? m)
  (and (htable-sorted-map? m)
       (let ((ops (var-cell-root ffc-sorted-ops-cell)))
         (and (pmap? ops) (eq? ops (jolt-ref-get m kw-ops))))))

(define (ffc-map-fold m visit acc)
  (cond
   ((pmap? m) (ffc-pmap-fold m visit acc))
   ((ffc-stock-sorted-map? m)
    ;; Capture the immutable tree before callbacks; no comparator invocation.
    (let walk ((tree (jolt-ref-get m kw-tree)) (a acc))
      (if (jolt-nil? tree) a
          (let* ((left (walk (sc-nd-left tree) a))
                 (next (visit (sc-nd-key tree) (sc-nd-val tree) left)))
            (walk (sc-nd-right tree) next)))))
   (else (error 'ffc-map-fold "unsupported map representation"))))

(define (ffc-vector-fold v visit acc)
  (unless (pvec? v) (error 'ffc-vector-fold "expected a persistent vector"))
  (let ((n (pvec-count v)))
    (let loop ((i 0) (a acc))
      (if (fx=? i n) a
          ;; Resolve once per leaf, not once per element. Use the runtime's
          ;; resolver so sliced/relaxed vectors retain their actual leaf
          ;; offsets; assuming every leaf starts at i mod 32 is incorrect.
          (let-values (((chunk offset) (pv-leaf-for v i)))
            (let ((run (fxmin (fx- (vector-length chunk) offset) (fx- n i))))
              (let leaf ((j 0) (a a))
                (if (fx=? j run) (loop (fx+ i run) a)
                    (leaf (fx+ j 1)
                          (visit (vector-ref chunk (fx+ offset j)) a))))))))))

;; Vector SDK batches keep the allocation-free cursor. Other sequential sources
;; are consumed lazily: request next only after visit returned successfully.
;; This is a records source, not an alternative JSON value-shape convention.
(define (ffc-records-fold records visit acc)
  (if (pvec? records) (ffc-vector-fold records visit acc)
      (let loop ((remaining (jolt-seq records)) (a acc))
        (if (jolt-nil? remaining) a
            (let ((next-a (visit (seq-first remaining) a)))
              (loop (seq-more remaining) next-a))))))
