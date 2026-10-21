;; Public Chez API, no profiler/fork/source patch/global replacement required.
;; This is a capability/correctness probe, NOT an overhead benchmark.
(require '[jolt.scheme :as scheme])
(def run
  (scheme/eval-string
    "(lambda (n timed?)
       (let ((cc (make-cost-center 'bounded-stock-probe)))
         (let* ((answer (with-cost-center timed? cc
                          (lambda ()
                            (let loop ((i 0) (total 0))
                              (if (fx=? i n) total
                                  (loop (fx+ i 1) (fx+ total i)))))))
                (time (cost-center-time cc))
                (nanos (+ (* 1000000000 (time-second time)) (time-nanosecond time)))
                (values-ok?
                  (call-with-values
                    (lambda () (with-cost-center cc (lambda () (values 7 9))))
                    (lambda (a b) (and (= a 7) (= b 9)))))
                (error-ok?
                  (guard (e ((condition? e)
                             (and (who-condition? e) (eq? (condition-who e) 'bounded-probe)
                                  (message-condition? e) (string=? (condition-message e) \"expected\"))))
                    (with-cost-center cc (lambda () (error 'bounded-probe \"expected\")))
                    #f)))
           (reset-cost-center! cc)
           (let ((reset-time (cost-center-time cc)))
             (jolt-vector answer nanos values-ok? error-ok?
               (and (zero? (time-second reset-time)) (zero? (time-nanosecond reset-time))))))))"))
(let [n 1000000 expected (quot (* n (dec n)) 2)
      timed (run n true) untimed (run n false)]
  (assert (= expected (first timed) (first untimed)))
  (assert (pos? (second timed)))
  (assert (zero? (second untimed)))
  (assert (= [true true true] (subvec timed 2) (subvec untimed 2)))
  (prn {:scope :stock-public-cost-center-capability :iterations n
        :timed-cpu-nanos (second timed) :untimed-cpu-nanos (second untimed)
        :same-result true :multiple-values-preserved true :error-propagates true
        :reset-verified true :allocation-counts-qualified false
        :instruction-counts-qualified false :overhead-qualified false
        :process-cpu-qualified false :function-attribution false}))
