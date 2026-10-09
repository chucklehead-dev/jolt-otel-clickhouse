;; Fill invocation-owned positional output once rather than repeatedly copying
;; a persistent vector's growing tail. Plans/collected values remain immutable.
;; Invoke the live status projector once per field, in established field order.
;; Nothing borrowed or mutable is published until the completed result is sealed.
(lambda (plan collected project-var)
  (let* ((n (pvec-cnt plan)) (out (make-vector (fx* n 2))))
    (let loop ((i 0))
      (if (fx=? i n)
          (make-pvec out)
          (let* ((field (pvec-nth! plan i))
                 (location (pvec-nth! field 0))
                 (key (pvec-nth! field 1))
                 (type (pvec-nth! field 2))
                 (values (jolt-get (pvec-nth! collected location) key empty-pvec))
                 (pair (jolt-invoke2 (var-cell-deref project-var) type values))
                 (offset (fx* i 2)))
            ;; Generic nth with defaults preserves vector-destructuring behavior
            ;; for live projector replacements returning lists, short pairs or nil.
            (vector-set! out offset (jolt-nth pair 0 jolt-nil))
            (vector-set! out (fx+ offset 1) (jolt-nth pair 1 jolt-nil))
            (loop (fx+ i 1)))))))
