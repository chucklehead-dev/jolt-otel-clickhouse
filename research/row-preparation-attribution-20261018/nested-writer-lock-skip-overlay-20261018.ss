;; UPPER BOUND ONLY: omits synchronized StringWriter seams on an isolated
;; single-thread fixture. NOT race-safe, NOT a product patch or adoption proof.
(let ((install djn-sb-install-or-append!) (text djn-sb-str) (append djn-sb-append!))
  (define (unsafe-install writer value)
    (if (= (sb-length writer) 0) (sb-set! writer value) (sb-append! writer value)))
  (lambda (enabled?)
    (set! djn-sb-install-or-append! (if enabled? unsafe-install install))
    (set! djn-sb-str (if enabled? sb-str text))
    (set! djn-sb-append! (if enabled? sb-append! append))
    enabled?))
