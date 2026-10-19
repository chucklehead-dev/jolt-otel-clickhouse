;; Untimed allocation attribution only. Fixed-arity wrappers preserve arguments,
;; results and callback order; no omission/classification/publication bypasses.
;; Accept allocation deltas only when the WHOLE pass reports zero collections.
(define research-allocation-enabled? #f)
(define research-allocation-counters (make-vector 12 0))
(define research-key-policy
  (pmap-fast-get (var-deref "clojure.data.json" "default-write-options")
                (keyword #f "key-fn") pmap-absent))
(define research-omission-policy
  (pmap-fast-get (var-deref "clojure.data.json" "default-write-options")
                (keyword #f "value-fn") pmap-absent))
(define-syntax research-measure-allocation
  (syntax-rules ()
    ((_ slot body)
     (if research-allocation-enabled?
         (let* ((before (bytes-allocated)) (result body)
                (amount (- (bytes-allocated) before)))
           (vector-set! research-allocation-counters slot
             (+ 1 (vector-ref research-allocation-counters slot)))
           (vector-set! research-allocation-counters (+ slot 1)
             (+ amount (vector-ref research-allocation-counters (+ slot 1))))
           result)
         body))))
(let ((original make-protocol-method-site))
  (set! make-protocol-method-site
    (lambda (proto method . hooks)
      (let ((resolve (apply original proto method hooks)))
        (if (and (string=? proto "clojure.data.json/JSONWriter")
                 (string=? method "-write"))
            (lambda (value) (research-measure-allocation 0 (resolve value)))
            resolve)))))
(let ((original jolt-invoke1))
  (set! jolt-invoke1
    (lambda (f value)
      (if (eq? f research-key-policy)
          (research-measure-allocation 2 (original f value))
          (original f value)))))
(let ((original jolt=2))
  (set! jolt=2
    (lambda (a b)
      (if (eq? a research-omission-policy)
          (research-measure-allocation 4 (original a b))
          (original a b)))))
(let ((original sorted-map-fold-seq-order))
  (set! sorted-map-fold-seq-order
    (lambda (value visit seed)
      (research-measure-allocation 6 (original value visit seed)))))
(let ((original pmap-fold-seq-order))
  (set! pmap-fold-seq-order
    (lambda (value visit seed)
      (research-measure-allocation 8 (original value visit seed)))))
(define (research-install-writer-publication-probe!)
  (let ((original djn-sb-install-or-append!))
    (set! djn-sb-install-or-append!
      (lambda (writer text)
        (research-measure-allocation 10 (original writer text))))))
(define (research-allocation-toggle! enabled?)
  (vector-fill! research-allocation-counters 0)
  (set! research-allocation-enabled? enabled?)
  enabled?)
(define (research-allocation-report)
  (format "~s" (vector->list research-allocation-counters)))
