(require '[otel.any-value :as av] '[jolt.codec.experimental :as codec]
         '[jolt.codec.scalar-text.experimental :as shapes]
         '[clojure.test :refer [deftest is run-tests]])
(def c (codec/open-codec {:flat-map-cache? true}))
(def folder (codec/native-map-folder c))
(defn fixture [] (into (sorted-map) (map (fn [i] [(str "field-" i) (case (mod i 3) 0 "λ😀" 1 false i)])) (range 17)))
(defn new-projector []
  (let [factory (shapes/native-value-reuse-plan-factory (codec/native-scalar-text-view-box))
        state (volatile! nil)
        tracked (fn [bindings maximum prototype schemas chars]
                  (let [p (factory bindings maximum prototype schemas chars)]
                    (vreset! state p) p))]
    [(av/scalar-text-payload-projector folder {:shape-plan-factory tracked})
     (fn [] (@state 3 nil))]))

(deftest sdk-owned-admission-preserves-changed-values
  (let [[project stats] (new-projector) m (fixture)]
    (is (ifn? project)) (is (map? (project m)))
    (let [same (project (into (sorted-map) m))]
      (is (map? same)) (is (pos? (nth (stats) 2)))
      (is (= (codec/write-nested c (av/try-sorted-scalar-string-map m)) (codec/write-nested c same))))
    (doseq [value ["changed" 999 true]]
      (let [changed (assoc m "field-2" value) actual (project changed)]
        (is (some? actual))
        (is (= (codec/write-nested c (av/try-sorted-scalar-string-map changed))
               (codec/write-nested c actual)))))))

(deftest all-eight-live-sdk-policy-anchors-fail-closed
  (doseq [v [#'av/try-scalar-string #'av/canonicalize #'av/default-limits
             #'av/string-bytes #'av/min-int64 #'av/max-int64
             #'av/try-scalar-string-map #'av/try-sorted-scalar-string-map]]
    (let [[project stats] (new-projector) m (fixture)]
      (is (map? (project m))) (is (map? (project (into (sorted-map) m))))
      (let [before (stats)]
        (with-redefs-fn {v (Object.)}
          #(do (is (nil? (project m)))
               (is (nil? (project (into (sorted-map) m))))
               (is (= before (stats)))))
        (is (map? (project (into (sorted-map) m))))))))

(deftest preinstalled-policy-replacements-are-not-blessed-by-construction
  (doseq [v [#'av/try-scalar-string #'av/canonicalize #'av/default-limits
             #'av/string-bytes #'av/min-int64 #'av/max-int64
             #'av/try-scalar-string-map #'av/try-sorted-scalar-string-map]]
    (with-redefs-fn {v (Object.)}
      #(let [[project _] (new-projector)]
         (is (or (nil? project) (nil? (project (fixture)))))))))

(let [r (run-tests 'user)]
  (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))
