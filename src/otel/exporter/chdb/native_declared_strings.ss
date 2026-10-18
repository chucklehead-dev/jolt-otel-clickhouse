;; Pure speculation only. Unsupported keys/policies decline before any user
;; converter, membership, hash or equality callback. Generic collection remains
;; responsible for aliases, custom keys, changed roots and larger wanted sets.
(let ((string-var (jolt-var "clojure.core" "string?")))
  (define (string-entry? key ignored ok) (and ok (string? key)))
  (define (collect-entry! key value context)
    (when (vector-ref context 3)
      (if (not (string? key)) (vector-set! context 3 #f)
          (when (pset-contains? (vector-ref context 0) key)
            (let ((slots (vector-ref context 1)) (used (vector-ref context 2)))
              ;; String keys in a builtin immutable map are unique: no aliases
              ;; can normalize onto this key while this path is admitted.
              (vector-set! slots used key)
              (vector-set! slots (+ used 1) (make-pvec (vector value)))
              (vector-set! context 2 (+ used 2))))))
    context)
  (define (collect m wanted key-var contains-var original-key)
    (and original-key (pmap? m) (<= (pmap-cnt m) 128)
         (pset? wanted) (<= (pset-count wanted) 8)
         (eq? (var-cell-deref key-var) original-key)
         (eq? (var-cell-deref contains-var) jolt-contains?)
         (eq? (var-cell-deref string-var) jolt-string?)
         (pmap-fold-seq-order (pset-m wanted) string-entry? #t)
         (let* ((slots (make-vector (* 2 (pset-count wanted)) #f))
                (context (vector wanted slots 0 #t)))
           (pmap-fold-seq-order m collect-entry! context)
           (and (vector-ref context 3)
                ;; These reads are not a cross-thread redefinition lock. No
                ;; callback runs in the admitted scan; changed policy declines
                ;; without replaying observable work.
                (eq? (var-cell-deref key-var) original-key)
                (eq? (var-cell-deref contains-var) jolt-contains?)
                (eq? (var-cell-deref string-var) jolt-string?)
                (let ((used (vector-ref context 2)))
                  (make-pmap (if (= used (vector-length slots)) slots
                                 (vec-copy-range slots 0 used))
                             (quotient used 2)))))))
  ;; Capture stable Var references, NOT their values, once per factory. A
  ;; five-argument Clojure call otherwise uses the variadic invocation bridge
  ;; on the qualified runtime, creating a rest list for every attribute map.
  (case-lambda
    ((key-var contains-var original-key)
     (lambda (m wanted) (collect m wanted key-var contains-var original-key)))
    ((m wanted key-var contains-var original-key)
     (collect m wanted key-var contains-var original-key))))
