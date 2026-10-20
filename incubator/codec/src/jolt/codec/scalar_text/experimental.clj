(ns jolt.codec.scalar-text.experimental
  "Bounded scalar-text shape plans on stock Jolt; explicit trusted owner bridge.
  No SDK, exporter, data.json, global registration or dependency on their Vars."
  (:require [clojure.java.io :as io] [jolt.scheme :as scheme]))

(defmacro ^:private embedded []
  (let [plan (io/resource "jolt/codec/scalar_text/plan.ss")
        planner (io/resource "jolt/codec/scalar_text/planner.ss")]
    (when-not (and plan planner)
      (throw (ex-info "Missing scalar-text plan resources" {})))
    (str "(let ((compile-one " (slurp plan) ")) " (slurp planner) ")")))

(def ^:private source (embedded))
(def ^:private factory (delay (scheme/eval-string source)))

(defn native-plan-factory
  "Internal trusted bridge, qualified on stock Jolt0.8.23, not a stable ABI.
  Returns (original-bindings maximum trusted-sorted-prototype max-plans max-key-chars)
  -> a fresh synchronous planner, with maximum 8 schemas / 8192 retained key
  characters / 128 keys per schema. Original bindings are [Var expected-value]
  pairs supplied by the policy owner, never caller-current root snapshots.

  Planner operations: (0 map) -> fresh plain scalar-text map or false to decline;
  (1 map) -> learn a validated schema, true/false; (2 nil) -> [schema-count chars].
  The owner controls learning: normally call it only after its existing fallback
  accepts. Learning independently checks key geometry, scalar values and live
  policy. Lookups enforce current policy before and after conversion. No map
  entries/seqs or per-input hash insertions on learned-plan lookup. Booleans and
  native Int64-range fixnums become text; bounded Strings are preserved.

  Retains schema keys/ordinal hash geometry, never source map wrappers, trees or
  values. No additional input-value cache. Budget counters are monotonic and
  count characters, not RSS. Input and schema Strings must remain immutable.
  Not safe for concurrent use or atomic cross-thread policy redefinitions.
  Unsupported layouts/constructor options throw; unsupported input declines.
  This is not authorization to skip an owner's canonicalization policy."
  [] @factory)
