;; Untimed, isolated mechanism/control probe; never a product runtime overlay.
(require '[jolt.scheme :as scheme])
(scheme/eval-string
  "(define research-dl-emits 0)
   (define research-exporter-dl-emits 0)
   (let ((original jolt-direct-link-emit))
     (set! jolt-direct-link-emit
       (lambda (node ctx)
         (set! research-dl-emits (+ research-dl-emits 1))
         (when (string=? (chez-current-ns) \"otel.exporter.chdb\")
           (set! research-exporter-dl-emits (+ research-exporter-dl-emits 1)))
         (original node ctx))))")
(binding [*compiler-options* {:direct-linking true}]
  (require 'otel.exporter.chdb)
  (eval '(defn research-link-leaf [x] (* x x)))
  (eval '(defn research-link-caller [x] (research-link-leaf x))))
(require '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture])
(let [exporter-emits (scheme/eval-string "research-exporter-dl-emits")
      total-emits (scheme/eval-string "research-dl-emits")
      splice-active? (with-redefs-fn {(resolve 'research-link-leaf) (fn [_] 99)}
                       (fn [] (= 9 ((resolve 'research-link-caller) 3))))
      span (first (fixture/spans "typed-compact-prefix"))
      key-root-live? (with-bindings {#'e/*timestamp-wire* :raw-ticks}
                       (with-redefs-fn {#'e/key-string (fn [_] "KEY-CONTROL")}
                         (fn [] (= "7" (get (nth (#'e/span-row
                                                    (assoc span :attributes {:linkage-key 7}) nil true) 11)
                                             "KEY-CONTROL")))))]
  (assert (pos? total-emits))
  (assert splice-active?)
  (prn {:scope :untimed-latest-runtime-linkage-mechanism-not-performance
        :runtime-source "18dce94a" :exporter "4d48871"
        :total-direct-link-emits total-emits :exporter-direct-link-emits exporter-emits
        :small-callee-splice-control splice-active? :key-root-live? key-root-live?}))
