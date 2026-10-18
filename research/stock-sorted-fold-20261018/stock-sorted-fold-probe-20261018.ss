;; Diagnostic runtime-owned traversal prototype, NOT a product capability.
;; Bootstrap/private core roots are trusted; a compiler integration must own
;; the stock-ops anchor and geometry rather than copying this into data.json.
;; Admission is pure/bounded, never calls a comparator or per-object op.
;; Return convention: #f declines; jolt-nil is an admitted empty tree, which
;; is truthy in Scheme and immediately folds to the supplied accumulator.
(define task-stock-sorted-ops (var-cell-root (jolt-var "clojure.core" "sm-ops")))
(define task-sorted-tag-key (keyword "jolt" "type"))
(define task-sorted-map-tag (keyword "jolt" "sorted-map"))
(define task-sorted-ops-key (keyword #f "ops"))
(define task-sorted-tree-key (keyword #f "tree"))
(define task-sorted-count-key (keyword #f "cnt"))
(define (task-stock-sorted-scalar-tree m)
  (and (htable? m)
       (eq? (jolt-ref-get m task-sorted-tag-key) task-sorted-map-tag)
       (eq? (jolt-ref-get m task-sorted-ops-key) task-stock-sorted-ops)
       (let ((count (jolt-ref-get m task-sorted-count-key))
             (tree (jolt-ref-get m task-sorted-tree-key)) (seen 0))
         (define (walk node)
           (or (jolt-nil? node)
               (and (pvec? node) (= (pvec-count node) 5) (< seen 128)
                    (begin (set! seen (+ seen 1)) #t)
                    (string? (pvec-nth! node 1))
                    (let ((value (pvec-nth! node 2)))
                      (or (string? value) (boolean? value) (fixnum? value)))
                    (walk (pvec-nth! node 3)) (walk (pvec-nth! node 4)))))
         (and (fixnum? count) (<= 0 count 128) (walk tree) (= count seen) tree))))
(define (task-stock-sorted-fold tree visit acc)
  (if (jolt-nil? tree) acc
      (let* ((left (task-stock-sorted-fold (pvec-nth! tree 3) visit acc))
             (next (visit (pvec-nth! tree 1) (pvec-nth! tree 2) left)))
        (task-stock-sorted-fold (pvec-nth! tree 4) visit next))))
