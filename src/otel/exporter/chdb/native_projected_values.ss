;; The original projector can return two native values instead of allocating a
;; pair. Its continuation captures NO output storage and is safe to retain.
;; Changed roots preserve the ordinary live two-argument pair contract.
(let ()
  (define (return-pair value status) (values value status))
  (define (emit plan collected project-var original)
    (let* ((n (pvec-cnt plan)) (out (make-vector (fx* n 2))))
      (let loop ((i 0))
        (if (fx=? i n)
            (make-pvec out)
            (let* ((field (pvec-nth! plan i))
                   (location (pvec-nth! field 0))
                   (key (pvec-nth! field 1))
                   (type (pvec-nth! field 2))
                   (inputs (jolt-get (pvec-nth! collected location) key empty-pvec))
                   ;; Input lookup can have effects: dereference afterwards.
                   (projector (var-cell-deref project-var))
                   (offset (fx* i 2)))
              (if (and original (eq? projector original))
                  (let-values (((value status)
                                (jolt-invoke3 projector type inputs return-pair)))
                    (vector-set! out offset value)
                    (vector-set! out (fx+ offset 1) status))
                  (let ((pair (jolt-invoke2 projector type inputs)))
                    ;; Replacements may return lists, short pairs or nil.
                    (vector-set! out offset (jolt-nth pair 0 jolt-nil))
                    (vector-set! out (fx+ offset 1) (jolt-nth pair 1 jolt-nil))))
              (loop (fx+ i 1)))))))
  (case-lambda
    ((plan collected project-var) (emit plan collected project-var #f))
    ((plan collected project-var original) (emit plan collected project-var original))))
