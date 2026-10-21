(ns jolt.codec.scalar-text.experimental
  "Bounded scalar-text shape plans on stock Jolt; explicit trusted owner bridge.
  No SDK, exporter, data.json, global registration or dependency on their Vars."
  (:require [clojure.java.io :as io] [jolt.scheme :as scheme]))

(defmacro ^:private embedded []
  (let [plan (io/resource "jolt/codec/scalar_text/plan.ss")
        planner (io/resource "jolt/codec/scalar_text/planner.ss")]
    (when-not (and plan planner)
      (throw (ex-info "Missing scalar-text plan resources" {})))
    (str "(lambda (view-box compile-view?) (let ((compile-one (" (slurp plan) " view-box compile-view?))) " (slurp planner) "))")))

(def ^:private source (embedded))
(def ^:private factory-maker (delay (scheme/eval-string source)))
(def ^:private factory (delay (@factory-maker false false)))

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

(defn native-view-plan-factory
  "Research-only trusted encoder bridge. Same admission/anchors/bounds as the
  plain-map plan, but emits an opaque view through the supplied native box.
  NOT suitable for scalar-string-map-payload-converter: it promises plain maps.
  Box must be callback-free and retain only template/independently owned slots."
  [native-box]
  (when-not (ifn? native-box)
    (throw (ex-info "Expected native view constructor" {:type ::invalid-view-box})))
  (@factory-maker native-box false))

(defn native-compiled-view-plan-factory
  "Research-only additive variant: native-factory compiles each template once
  to a value-slot constructor. Admission, order and budgets are unchanged.
  Existing plain-map and uncompiled-view factories retain their contracts."
  [native-factory]
  (when-not (ifn? native-factory)
    (throw (ex-info "Expected native schema compiler" {:type ::invalid-view-box})))
  (@factory-maker native-factory true))
