(ns jolt.codec.scalar-text.experimental
  "Bounded scalar-text shape plans on stock Jolt; explicit trusted owner bridge.
  No SDK, exporter, data.json, global registration or dependency on their Vars."
  (:require [clojure.java.io :as io] [jolt.scheme :as scheme]
            [jolt.traversal.experimental :as traversal]))

(defmacro ^:private embedded []
  (let [plan (io/resource "jolt/codec/scalar_text/plan.ss")
        planner (io/resource "jolt/codec/scalar_text/planner.ss")]
    (when-not (and plan planner)
      (throw (ex-info "Missing scalar-text plan resources" {})))
    (str "(lambda (view-box) (let ((compile-one (" (slurp plan) " view-box))) " (slurp planner) "))")))

(def ^:private source (embedded))
(def ^:private factory-maker (delay (scheme/eval-string source)))
(def ^:private factory (delay (@factory-maker false)))

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
  (@factory-maker native-box))

(defmacro ^:private embedded-value-reuse []
  (slurp (io/resource "jolt/codec/scalar_text/value_reuse.ss")))
(def ^:private value-reuse-source (embedded-value-reuse))
(def ^:private value-reuse-maker
  (delay
    (scheme/eval-string
      (str "(lambda (maker original-box map-fold)"
           " (lambda (bindings maximum prototype max-plans max-key-chars)"
           " (let* ((cache (" value-reuse-source " original-box map-fold))"
           " (planner ((maker (pvec-nth! cache 0)) bindings maximum prototype max-plans max-key-chars)))"
           " (lambda (op m) (if (= op 3) ((pvec-nth! cache 1)) (planner op m))))))"))))

(defn native-value-reuse-plan-factory
  "Experimental encoder-only output strategy. Same SDK-owned original anchors,
  learned sorted-map admission and8schema/8192key-character bounds as the view
  factory. NEVER supply to the plain-map-returning converter. No defaults change.

  Separately retains one first raw scalar snapshot per template, up to8 entries
  and16384 charged key/value characters per serial planner. On a repeat it
  materializes text once and returns fresh plain-map wrappers sharing a private
  immutable root, allowing an existing wire-map cache to reuse bytes. Changed
  values or a full cache use the original opaque view. NO source wrappers/trees
  or metadata retained; immutable scalar Strings may be retained for the planner
  lifetime. Charges conservatively cover numeric/Boolean text, not hard RSS.

  All calls still pass original shape/value/policy guards before cache access.
  Inputs/templates/slots and native codec box are trusted, as in the view factory.
  Operator3 reports [entries charged-characters hits materializations] for tests;
  standard SDK operations0/1/2 are unchanged. Stock-layout dependent, serial,
  not a cross-thread redefinition lock or production performance qualification."
  [native-box]
  (when-not (ifn? native-box)
    (throw (ex-info "Expected native view constructor" {:type ::invalid-view-box})))
  (@value-reuse-maker @factory-maker native-box
    (first (traversal/native-kernels (traversal/open-traversal)))))
