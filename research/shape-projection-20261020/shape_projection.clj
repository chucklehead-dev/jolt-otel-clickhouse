(ns shape-projection
  "Research-only schema-fixed scalar projection on stock Jolt. No registration."
  (:require [clojure.java.io :as io] [jolt.scheme :as scheme]))

(def source (slurp (io/resource "shape_projection.ss")))
(def factory (delay (scheme/eval-string source)))

(defn compile-plan
  "Compile ordered, unique String keys (at most 128) into a private template.
  bindings must be the owner's ORIGINAL [Var expected-value] anchors, not a
  snapshot sampled from caller data. prototype must be the owner's trusted
  stock sorted-map prototype. maximum is its scalar String character limit.
  Returns a function: admitted sorted map -> fresh metadata-free plain map;
  unsupported shape/policy/value -> nil. Booleans/fixnums become text.
  No comparator or converter callbacks, cache, global mutation, source wrapper
  retention, concurrency or persistence behavior. Runtime internals are trusted.
  This is NOT a public SDK API or a default-string-budget bypass permission."
  [ordered-keys bindings maximum prototype]
  (let [convert (@factory bindings maximum prototype ordered-keys)]
    (fn [m] (or (convert m) nil))))

(def ^:private plan-dispatch-factory
  (delay
    (scheme/eval-string
      "(lambda (plans)
         (let* ((n (pvec-count plans)) (native (make-vector n)))
           (do ((i 0 (+ i 1))) ((= i n))
             (vector-set! native i (pvec-nth! plans i)))
           (lambda (m)
             (let loop ((i 0))
               (if (= i n) jolt-nil
                   (let ((result ((vector-ref native i) m)))
                     (if (or (eq? result #f) (jolt-nil? result))
                         (loop (+ i 1)) result)))))))")))

(defn cached-converter
  "Research composition with an existing SDK-owned root cache. Plans are
  explicit/schema-fixed. Caller passes ORIGINAL SDK fallback functions. A
  successful shape projection still goes through the SDK store's admission,
  live policy guards and existing retention budget. No extra input cache."
  [cache plans plain-fallback sorted-fallback]
  (let [{:keys [current? lookup store!]} cache
        dispatch (@plan-dispatch-factory plans)]
    (assert (every? fn? [current? lookup store!]))
    (fn [m]
      (when (current?)
        (or (lookup m)
            (when-let [result (or (plain-fallback m) (dispatch m) (sorted-fallback m))]
              (store! m result)))))))
