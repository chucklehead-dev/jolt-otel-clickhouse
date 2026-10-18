;; Isolated-process experiment. The RF completion must seal BEFORE an outer
;; transducer completion can inspect/transform its result, matching core/into.
(let ((original into-xform))
  (define (candidate to xform from)
    (if (or (pvec? to) (pmap? to) (pset? to))
        (let* ((metadata (jolt-meta to))
               (rf (case-lambda
                     ((acc) (jolt-with-meta (jolt-persistent! acc) metadata))
                     ((acc x) (jolt-conj! acc x))))
               (initial (jolt-transient-new to))
               (xrf (jolt-invoke1 xform rf))
               (res (into-fold xrf initial from)))
          (jolt-invoke1 xrf res))
        (let* ((xrf (jolt-invoke1 xform jolt-conj))
               (res (into-fold xrf to from)))
          (jolt-invoke1 xrf res))))
  (lambda (enabled?)
    (set! into-xform (if enabled? candidate original))
    enabled?))
