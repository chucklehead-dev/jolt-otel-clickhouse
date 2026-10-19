(ns otel.scalar-policy-anchor-decode-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io] [clojure.string :as str]
            [jolt.scheme :as scheme] [otel.any-value :as any]))

(def policy-root (Object.))
(def policy-root-1 (Object.))
(def policy-root-2 (Object.))
(def policy-root-3 (Object.))
(def policy-root-4 (Object.))
(def policy-root-5 (Object.))

(deftest immutable-anchor-storage-decodes-once-but-policy-vars-stay-live
  ;; The injected counter observes decoding only, not map-node access. The old
  ;; code instead decodes this storage during every before/after policy scan.
  (doseq [[resource sorted?] [["otel/scalar_string_map.ss" false]
                              ["otel/sorted_scalar_string_map.ss" true]]]
    (let [source (slurp (io/resource resource))
          observed (str/replace source "(pvec-nth! bindings i)"
                     "(begin (set! decodes (+ decodes 1)) (pvec-nth! bindings i))")
          stage (scheme/eval-string
                  (str "(let ((decodes 0)) (let ((factory " observed ")) "
                       "(jolt-vector factory (lambda () decodes))))"))
          factory (nth stage 0) count-decodes (nth stage 1)
          anchors [[#'policy-root policy-root] [#'policy-root-1 policy-root-1]
                   [#'policy-root-2 policy-root-2] [#'policy-root-3 policy-root-3]
                   [#'policy-root-4 policy-root-4] [#'policy-root-5 policy-root-5]]
          convert (if sorted?
                    (factory anchors 100 @#'any/scalar-sorted-map-prototype)
                    (factory anchors 100))
          input (into (if sorted? (sorted-map) {})
                      (map (fn [i] [(str "field-" i) (case (mod i 3) 0 "value" 1 false i)]))
                      (range 17))
          first-result (convert input) second-result (convert input)]
      (is (= 6 (count-decodes)))
      (is (= first-result second-result))
      (is (not (identical? first-result second-result)))
      (doseq [[root _] anchors]
        (with-redefs-fn {root (Object.)}
          #(is (false? (boolean (convert input))))))
      (is (= first-result (convert input)))
      (is (= 6 (count-decodes))))))

(defn -main [& _]
  (let [result (run-tests 'otel.scalar-policy-anchor-decode-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
