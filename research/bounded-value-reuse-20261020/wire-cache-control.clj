;; Functional mechanism witness, NOT a timed benchmark or native storage test.
(require '[otel.any-value :as av] '[jolt.codec.scalar-text.experimental :as shapes]
         '[jolt.traversal.experimental :as traversal] '[jolt.scheme :as scheme]
         '[clojure.string :as str])
(let [[output source-path] *command-line-args*
      source (slurp source-path)
      instrumented (str/replace source "(if cached (fragment! cached depth)"
                     "(if cached (begin (set! wire-hits (+ wire-hits 1)) (fragment! cached depth))")
      _ (assert (not= source instrumented))
      walk (traversal/open-traversal)
      [encode box reset-hits! hits]
      (apply (scheme/eval-string
               (str "(lambda (ffc-map-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record?)"
                    " (let ((wire-hits 0)) (let () " instrumented
                    " (jolt-vector ffc-json-cached-project-prefixed-ascii-lines-bytes"
                    " ffc-json-scalar-map-box (lambda () (set! wire-hits 0)) (lambda () wire-hits)))))"))
             (traversal/native-kernels walk))
      fixture (fn [] (into (sorted-map)
                      (map (fn [i] [(str "field-" i) (case (mod i 3) 0 "λ😀" 1 false i)])) (range 17)))
      rows (mapv (fn [_] (fixture)) (range 100))
      run (fn [reuse?]
            (let [factory ((if reuse? shapes/native-value-reuse-plan-factory shapes/native-view-plan-factory) box)
                  convert (av/scalar-text-payload-projector
                            (traversal/native-pmap-folder walk) {:shape-plan-factory factory})]
              (assert convert) (reset-hits!)
              (let [bytes (encode "SQL\n" rows (fn [m] [(convert m)]) 1000000 128)]
                [bytes (hits)])))
      [old old-hits] (run false) [new new-hits] (run true)
      equal? (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))")]
  (assert (equal? old new))
  (assert (= 0 old-hits)) (assert (= 98 new-hits))
  (spit output (pr-str {:scope :actual-encoder-fragment-hit-control :maps 100
                       :baseline-wire-hits old-hits :value-reuse-wire-hits new-hits
                       :full-output-bytes-equal true :output-bytes (alength old)
                       :instrumented-existing-fragment-branch true
                       :timed false :native-storage false}))
  (println :wire-cache-control old-hits new-hits :bytes (alength old)))
