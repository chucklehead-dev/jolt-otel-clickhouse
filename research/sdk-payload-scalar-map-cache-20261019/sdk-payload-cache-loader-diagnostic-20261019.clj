(require '[otel.any-value :as any] '[otel.any-value-native :as native]
         '[jolt.scheme :as scheme])
(prn :loader-probes
  (scheme/eval-string
    "(apply jolt-vector
       (map (lambda (name)
         (jolt-vector name (top-level-bound? name)
           (and (top-level-bound? name) (procedure? (top-level-value name)))
           (and (top-level-bound? name) (procedure? (top-level-value name))
                (procedure-arity-mask (top-level-value name)))))
        '(pvec-count pvec-nth! var-cell-deref pmap? pmap-root pmap-cnt make-pmap
          htable? jolt-ref-get make-weak-eq-hashtable hashtable-ref hashtable-set!)))"))
(try
  (let [factory (scheme/eval-string @#'native/payload-cache-converter-source)]
    (prn :factory-present (fn? factory))
    (prn :dispatch (factory (#'any/scalar-string-map-payload-bindings) 64 65536)))
  (catch Throwable e (prn :loader-failure {:message (.getMessage e) :class (.getName (class e))})))
