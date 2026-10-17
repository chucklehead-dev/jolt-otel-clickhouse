;; Bounded invocation-private hash-map value transform. Completed output leaves
;; provide seq-order replay after a changed key, without a second entry ledger.
;; Reuse immutable input leaf pairs only when BOTH converted references match.
(let ()
  (define (admitted-node? node)
    (let ((arr (hnode-arr node)))
      (let loop ((i 0))
        (or (= i (vector-length arr))
            (let ((child (vector-ref arr i)))
              (and (if (hnode? child) (admitted-node? child)
                       (and (pair? child) (string? (car child))))
                   (loop (+ i 1))))))))
  (lambda (m key-var value-var)
    (and (pmap? m) (> (pmap-cnt m) 8) (<= (pmap-cnt m) 128)
         (hnode? (pmap-root m)) (admitted-node? (pmap-root m))
      (let ((partial-root #f) (fallback #f))
        (define (replay! node)
          ;; Every populated leaf preceding this change has a primitive string
          ;; key identical to its input key. Holes are private #f sentinels.
          (let ((arr (hnode-arr node)))
            (let loop ((i 0))
              (unless (= i (vector-length arr))
                (let ((child (vector-ref arr i)))
                  (cond ((hnode? child) (replay! child))
                        ((pair? child) (tmap-put! fallback (car child) (cdr child)))))
                (loop (+ i 1))))))
        (define (convert! child)
          (let* ((key (jolt-invoke1 (var-cell-deref key-var) (car child)))
                 (value (jolt-invoke1 (var-cell-deref value-var) (cdr child)))
                 (pair (if (and (eq? key (car child)) (eq? value (cdr child)))
                           child (cons key value))))
            (cond
              (fallback (tmap-put! fallback key value))
              ((eq? key (car child)) #f)
              (else
                (set! fallback (jolt-transient-new empty-pmap))
                (replay! partial-root)
                ;; The changed key's hashing/equality effects occur now,
                ;; before the next conversion. No callback is replayed.
                (tmap-put! fallback key value)))
            pair))
        (define (walk node parent index)
          (let* ((arr (hnode-arr node)) (n (vector-length arr))
                 (out (make-vector n #f))
                 (copy (make-hnode (hnode-bm node) out)))
            ;; Link this private node BEFORE traversing it, so any mid-subtree
            ;; fallback can replay all completed leaves in exact seq order.
            (if parent (vector-set! parent index copy) (set! partial-root copy))
            (let loop ((i 0))
              (unless (= i n)
                (let ((child (vector-ref arr i)))
                  (if (hnode? child) (walk child out i)
                      (vector-set! out i (convert! child))))
                (loop (+ i 1))))
            copy))
        (let ((root (walk (pmap-root m) #f 0)))
          (if fallback (jolt-persistent! fallback)
              (make-pmap root (pmap-cnt m))))))))
