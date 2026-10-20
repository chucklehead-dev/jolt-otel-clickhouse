(ns jolt.traversal.experimental
  "Explicit low-allocation collection folds on stock Jolt. No JSON dependency.
  Source-mode qualified on official 0.8.23; private layouts are not a stable ABI.
  Full synchronous folds, NOT reduce-kv: Reduced does not terminate traversal."
  (:require [clojure.java.io :as io] [jolt.scheme :as scheme]))

(def ^:private seal (Object.))
(defmacro ^:private embedded []
  (if-let [resource (io/resource "jolt/traversal/kernel.ss")]
    (slurp resource)
    (throw (ex-info "Missing experimental traversal resource" {}))))
(def ^:private source (embedded))
(def ^:private backend
  (delay
    ;; Definitions are lexical; loading this library installs no runtime helpers.
    (let [[map-fold vector-fold records-fold pmap-fold sorted-map? record? qualify]
          (scheme/eval-string
            (str "(let () " source
                 "\n(jolt-vector ffc-map-fold ffc-vector-fold ffc-records-fold ffc-pmap-fold ffc-stock-sorted-map? ffc-stock-record?"
                 " (lambda (m v s)"
                 " (and (eq? (record-type-uid (record-rtd m)) 'chez-pmap-v6)"
                 "      (equal? (record-type-field-names (record-rtd m)) '#(root cnt hasheq meta))"
                 "      (eq? (record-type-uid (record-rtd v)) 'chez-pvec-v4)"
                 "      (eq? (record-type-uid (record-rtd s)) 'chez-htable-v2)"
                 "      (eq? (record-type-uid (record-type-descriptor hcoll)) 'chez-hcoll-v1)"
                 "      (equal? (record-type-field-names (record-type-descriptor hcoll)) '#(hash alist))"
                 "      (eq? (record-type-uid (record-rtd empty-hnode)) 'chez-hnode-v1)"
                 "      (equal? (record-type-field-names (record-rtd empty-hnode)) '#(bm arr))))) )"))]
      (when-not (qualify {} [] (sorted-map))
        (throw (ex-info "Unsupported experimental traversal runtime" {:type ::unavailable})))
      [map-fold vector-fold records-fold pmap-fold sorted-map? record?])))

(deftype ^:private Traversal [tag map-fold vector-fold records-fold pmap-fold sorted-map? record?])
(defn open-traversal
  "Return a stateless traversal capability. No native resources need closing.
  Calls have independent accumulators; callbacks own any mutable state they use."
  []
  (let [[m v r p s r?] @backend] (Traversal. seal m v r p s r?)))
(defn- checked [traversal]
  (when-not (and (instance? Traversal traversal) (identical? seal (.-tag traversal)))
    (throw (ex-info "Invalid experimental traversal" {:type ::invalid-traversal})))
  traversal)

(defn fold-map
  "Visit (key value accumulator) in seq order for ordinary persistent maps or
  stock sorted maps or ordinary defrecords, without constructing entries/sequences. No comparator is
  called. Unsupported representations reject; callback exceptions stop the fold.
  Callback allocations/effects are not prevented or rolled back. Reduced is an
  ordinary accumulator, not an early-return instruction. Inputs must stay immutable."
  [traversal m visit initial]
  ((.-map-fold (checked traversal)) m visit initial))

(defn fold-vector
  "Visit (value accumulator) in index order, without a seq cursor. Persistent
  vectors only; callback exceptions stop traversal. This is a full fold."
  [traversal values visit initial]
  ((.-vector-fold (checked traversal)) values visit initial))

(defn fold-records
  "Vector fast path, otherwise an incremental sequential cursor. The latter
  may allocate and follows the source's realization behavior. After a callback
  throws, no next record is requested. Nil is empty. This is a full fold."
  [traversal values visit initial]
  (checked traversal)
  (when-not (or (nil? values) (sequential? values))
    (throw (ex-info "Expected sequential records" {:type ::invalid-records})))
  ((.-records-fold traversal) values visit initial))

(defn native-kernels
  "Internal bridge: [map-fold vector-fold records-fold stock-sorted-map? stock-record?]. Capture
  these once in a Scheme kernel, not through per-element Clojure adapters.
  Same full-fold contracts as the public functions; version-sensitive, trusted."
  [traversal]
  (checked traversal)
  [(.-map-fold traversal) (.-vector-fold traversal) (.-records-fold traversal)
   (.-sorted-map? traversal) (.-record? traversal)])

(defn native-pmap-folder
  "Internal trusted (ordinary-pmap visitor initial) full fold for SDK injection.
  Accepts no sorted maps; installs no globals. Caller must preserve immutability."
  [traversal]
  (.-pmap-fold (checked traversal)))
