;; Diagnostic only: source-owned exporter helper identity plus SDK policy query.
;; Not a public/default encoder; arbitrary core redefinition is not qualified.
(lambda (value-var original policy-var original-policy)
  (lambda (v)
    (let ((current (var-cell-deref value-var)))
      (if (and (eq? current original)
               (eq? (var-cell-deref policy-var) original-policy))
          (let ((maximum (jolt-invoke0 (var-cell-deref policy-var))))
            (if (jolt-truthy? maximum)
                (cond
                  ((and (string? v) (<= (string-length v) maximum)) v)
                  ((eq? v #t) "true")
                  ((eq? v #f) "false")
                  ;; Chez fixnums fit Int64 on this qualified 64-bit target.
                  ;; Other integer representations retain the real SDK path.
                  ((fixnum? v) (number->string v))
                  (else (jolt-invoke1 current v)))
                (jolt-invoke1 current v)))
          (jolt-invoke1 current v)))))
