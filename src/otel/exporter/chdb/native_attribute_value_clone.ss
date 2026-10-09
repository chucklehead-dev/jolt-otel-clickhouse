;; Bounded hash-map value transform. Only collision-free HAMTs with string
;; keys and >8 entries can keep their geometry. Converters stay live, in seq
;; order. A changed key switches immediately to the original empty-map builder;
;; preceding pure string-key insertions replay without rerunning converters.
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
      ;; One invocation-owned slot per admitted entry replaces the linked
      ;; replay ledger. Replay still happens in original conversion order.
      (let ((converted (make-vector (pmap-cnt m))) (used 0) (fallback #f))
        (define (convert! child)
          (let* ((key (jolt-invoke1 (var-cell-deref key-var) (car child)))
                 (value (jolt-invoke1 (var-cell-deref value-var) (cdr child)))
                 (pair (cons key value)))
            (cond
              (fallback (tmap-put! fallback key value))
              ((eq? key (car child))
               (vector-set! converted used pair)
               (set! used (+ used 1)))
              (else
                (set! fallback (jolt-transient-new empty-pmap))
                (let replay ((i 0))
                  (unless (= i used)
                    (let ((old (vector-ref converted i)))
                      (tmap-put! fallback (car old) (cdr old)))
                    (replay (+ i 1))))
                (set! converted #f)
                ;; Preserve hashing/equality effects of this changed key before
                ;; observing the next converter. No rows/keys are reconverted.
                (tmap-put! fallback key value)))
            pair))
        (define (walk node)
          (let* ((arr (hnode-arr node)) (n (vector-length arr))
                 (out (make-vector n)))
            (let loop ((i 0))
              (unless (= i n)
                (let ((child (vector-ref arr i)))
                  (vector-set! out i (if (hnode? child) (walk child) (convert! child))))
                (loop (+ i 1))))
            (make-hnode (hnode-bm node) out)))
        (let ((root (walk (pmap-root m))))
          (if fallback (jolt-persistent! fallback)
              (make-pmap root (pmap-cnt m))))))))
