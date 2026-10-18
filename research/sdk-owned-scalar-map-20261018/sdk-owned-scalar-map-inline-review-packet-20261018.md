Read-only code review; all source is included below. Do not use tools, edit, execute commands, delegate, or publish. Review only the optional SDK-owned scalar-map optimization and exporter consumer. Return at most 500 words with concrete bugs or missing controls, not general caution. A previous broader file-reading review timed out with no report; this is a smaller self-contained task, not a retry of a denied export. SDK private anchors are source-owned; private implementation/core mutation and atomic cross-thread redefinition are explicitly outside the claim. Original public SDK helpers/limits/bounds and original exporter key/value converters must stay live, including replacements installed before lazy initialization. Unsupported hosts/shapes must decline without dropping data. Native fixnums fit Int64 and existing jolt-fixnum->string formats decimal correctly on the pinned runtime. No JSON writer or WAL implementation is changed. SDK commit a4d594a; exporter e23d535, local only. Native SDK gate 4/63, Jolt AnyValue17/388, exporter4/20 plus existing21/607 and24/321 pass; isolated BB/JVM helper7 each pass, NOT full SDK portability. Component source-owned implementation base p50 23.03->16.17ms/5k, full68.43->67.03, mean paired full ratio0.923; no Durable win claimed. Note SDK helper public API itself is invoked live, not bypassed; exported private anchors remain a trusted implementation boundary.


## SDK source-owned anchors and optional API
```
    (and (sequential? value) (not (string? value)))
    (canonical-array value options depth state)

    :else (fail! :unsupported-type {})))

;; Identity guards the shortcut's fixed budget assumptions. Replacing defaults
;; (even with an equal map) conservatively restores the generic options path.
(def ^:private scalar-default-limits default-limits)

(defn- default-scalar-result [value]
  (cond
    (string? value)
    (let [size (string-bytes value)]
      (when (<= size (:max-bytes scalar-default-limits))
        {:value value :nodes 1 :bytes size :truncated? false}))

    (boolean? value)
    {:value value :nodes 1 :bytes 1 :truncated? false}

    (and (integer? value) (<= min-int64 value max-int64))
    {:value value :nodes 1 :bytes 8 :truncated? false}))

(defn canonicalize
  "Return a non-throwing normalization result for one value.

  A successful result contains :value, :nodes, :bytes and :truncated?. An
  invalid or over-budget value contains :error and bounded explanatory data.
  Callers that instrument application code can therefore drop/report a bad
  value without changing the application's return or exception behavior."
  ([value]
   (or (when (identical? default-limits scalar-default-limits)
         (default-scalar-result value))
       (canonicalize value default-limits)))
  ([value options]
   (let [options (limits options)]
     (try
       (let [[value state]
             (canonicalize* value options 0
                            {:nodes 0 :bytes 0 :truncated? false})]
         (assoc state :value value))
       (catch Throwable error
         (if (:otel.any-value/error (ex-data error))
           {:error (:reason (ex-data error))
            :details (dissoc (ex-data error) :otel.any-value/error :reason)}
           (throw error)))))))

(def ^:private scalar-text-canonicalizer canonicalize)

(defn try-scalar-string
  "Return canonical scalar text without a normalization-result map, or nil
  when the caller must use normal canonicalization. Only unchanged bounded
  strings, Booleans and admitted Int64 integers qualify. Decline is not an
  invalid-value verdict: structured/special/floating values and changed limits
  or canonicalizer roots must retain the normal path. Does not cache values,
  truncate, stringify unsupported objects, or change canonicalize's API."
  [value]
  (when (and (identical? default-limits scalar-default-limits)
             (identical? canonicalize scalar-text-canonicalizer))
    (cond
      (string? value)
      (when (<= (string-bytes value) (:max-bytes scalar-default-limits)) value)

      (boolean? value) (str value)

      (and (integer? value) (<= min-int64 value max-int64)) (str value))))

(def ^:private scalar-text-helper try-scalar-string)

(defn default-scalar-string?
  "True only while scalar text uses the SDK's original helper, canonicalizer
  and limits. Lets callers qualify a side-effect-free admission scan without
  treating an arbitrary helper installed before their own startup as stock.
  Does not invoke the helper or admit a value; try-scalar-string still owns
  per-value bounds. Private SDK implementation roots are trusted, not sealed
  against deliberate mutation; this is not a cross-thread redefinition lock."
  []
  (and (identical? try-scalar-string scalar-text-helper)
       (identical? canonicalize scalar-text-canonicalizer)
       (identical? default-limits scalar-default-limits)))

(defn default-string-value-max-chars
  "Return the character bound for the unchanged scalar string policy, or nil.

  Pure builtin-string admission scans can check this bound while walking their
  input, instead of calling the scalar formatter for every value. Count using
  the same host character semantics as this SDK; this is not a UTF-8 byte bound.
  Changed helpers, canonicalizers, limits or byte counters decline without
  invoking them. This is not a cross-thread redefinition lock or permission to
  skip generic normalization for other value types."
  []
  (when (and (identical? try-scalar-string scalar-text-helper)
             (identical? canonicalize scalar-text-canonicalizer)
             (identical? default-limits scalar-default-limits)
             (identical? string-bytes original-string-bytes))
    (quot (:max-bytes scalar-default-limits) 4)))

(def ^:private scalar-map-min-int64 min-int64)
(def ^:private scalar-map-max-int64 max-int64)

(def ^:private scalar-string-map-converter
  (delay
    (try
      ;; Optional host-specific implementation. Ordinary JVM/BB loading never
      ;; requires jolt.scheme; unsupported runtimes simply retain scalar calls.
      (require 'otel.any-value-native)
      ((ns-resolve 'otel.any-value-native 'load-string-map-converter!)
       [[#'try-scalar-string scalar-text-helper]
        [#'canonicalize scalar-text-canonicalizer]
        [#'default-limits scalar-default-limits]
        [#'string-bytes original-string-bytes]
        [#'min-int64 scalar-map-min-int64]
        [#'max-int64 scalar-map-max-int64]]
       (quot (:max-bytes scalar-default-limits) 4))
      (catch Throwable _ (constantly nil)))))

(defn try-scalar-string-map
  "Return a bounded builtin map's scalar text values, or nil to decline.

  This optional host optimization accepts string-key maps containing bounded
  strings, Booleans and native fixnums only while the SDK's original scalar
  helper, canonicalizer, limits, byte counter and Int64 bounds remain installed.
  It preserves map kind/order, drops metadata like ordinary fresh conversion,
  and never mutates input nodes or calls replacement helpers. Unsupported maps,
  hosts, values and policies must use the caller's ordinary scalar conversion;
  nil is not an invalid-value verdict. No canonicalization or capture API changes.
  Private implementation roots and ordinary core behavior remain trusted; this
  is not an atomic cross-thread redefinition lock."
  [value]
  (or (@scalar-string-map-converter value) nil))
```

## Native kernel
```
;; Policy anchors are supplied by their SDK owner, never captured in a consumer.
;; Admission and conversion contain no user callbacks. Unsupported structures
;; decline before allocating output or invoking any converter.
(lambda (bindings maximum)
  (define (same-policy?)
    (let loop ((i 0))
      (or (= i (pvec-cnt bindings))
          (let ((entry (pvec-nth! bindings i)))
            (and (eq? (var-cell-deref (pvec-nth! entry 0)) (pvec-nth! entry 1))
                 (loop (+ i 1)))))))
  (define (admitted? node)
    (and (hnode? node)
      (let ((arr (hnode-arr node)))
        (let loop ((i 0))
          (or (= i (vector-length arr))
              (let ((child (vector-ref arr i)))
                (and (if (hnode? child) (admitted? child)
                         (and (pair? child) (string? (car child))
                              (let ((v (cdr child)))
                                (or (boolean? v) (fixnum? v)
                                    (and (string? v) (<= (string-length v) maximum))))))
                     (loop (+ i 1)))))))))
  (define (text v)
    (cond ((string? v) v) ((eq? v #t) "true") ((eq? v #f) "false")
          (else (jolt-fixnum->string v))))
  (define (copy-node node)
    (let* ((arr (hnode-arr node)) (n (vector-length arr)) (out (make-vector n)))
      (let loop ((i 0))
        (unless (= i n)
          (let ((child (vector-ref arr i)))
            (vector-set! out i
              (if (hnode? child) (copy-node child)
                  (let ((value (text (cdr child))))
                    (if (eq? value (cdr child)) child (cons (car child) value))))))
          (loop (+ i 1))))
      (make-hnode (hnode-bm node) out)))
  (lambda (m)
    (and (pmap? m) (> (pmap-cnt m) 8) (<= (pmap-cnt m) 128)
         (same-policy?) (admitted? (pmap-root m)) (same-policy?)
         (make-pmap (copy-node (pmap-root m)) (pmap-cnt m)))))
```

## Native loader and fences
```
(ns otel.any-value-native
  "Optional SDK-owned bounded primitive-map conversion on Jolt/Chez."
  (:require [clojure.java.io :as io] [jolt.scheme :as scheme]))

(defmacro ^:private source []
  (if-let [url (io/resource "otel/scalar_string_map.ss")]
    (str "(begin "
         "(unless (and "
         "(eq? (record-type-uid (record-type-descriptor pmap)) 'chez-pmap-v6) "
         "(equal? (record-type-field-names (record-type-descriptor pmap)) '#(root cnt hasheq meta)) "
         "(eq? (record-type-uid (record-type-descriptor hnode)) 'chez-hnode-v1) "
         "(equal? (record-type-field-names (record-type-descriptor hnode)) '#(bm arr))) "
         "(error 'scalar-string-map \"Unsupported map layout\")) "
         "(unless (for-all (lambda (entry) "
         "(and (top-level-bound? (car entry)) "
         "(procedure? (top-level-value (car entry))) "
         "(bitwise-bit-set? (procedure-arity-mask (top-level-value (car entry))) (cadr entry)))) "
         "'((make-pmap 2) (var-cell-deref 1) (jolt-fixnum->string 1))) "
         "(error 'scalar-string-map \"Unsupported runtime helpers\")) "
         (slurp url) ")")
    (throw (ex-info "Missing native scalar-map resource" {}))))

(def ^:private converter-source (source))

(defn load-string-map-converter!
  "Internal: policy anchors come from otel.any-value's defining namespace."
  [bindings maximum]
  (let [convert ((scheme/eval-string converter-source) bindings maximum)]
    (fn [value] (convert value))))
```

## Exporter consumer
```
(def ^:private sdk-scalar-map-converter
  ;; Keep the Var live. Older SDKs simply lack the optional map helper and
  ;; retain the existing native/generic conversion without a dependency floor.
  (delay (ns-resolve 'otel.any-value 'try-scalar-string-map)))

(defn- sdk-scalar-attrs [m]
  (when (and (identical? key-string original-attribute-key-string)
             (identical? value-string original-attribute-value-string))
    (when-let [convert-var @sdk-scalar-map-converter]
      (let [convert @convert-var
            result (convert m)]
        ;; A replaced SDK helper can change exporter policy before returning.
        ;; Do not accept its result past a changed per-entry conversion root.
        (when (and (identical? key-string original-attribute-key-string)
                   (identical? value-string original-attribute-value-string))
          result)))))

(defn- attrs [m]
  (if (reusable-string-attrs? m)
    ;; Match the old fresh map's metadata without copying immutable entries.
    (with-meta m nil)
    (or (when (= :native-guarded-byte-batch *json-backend*)
          (or (sdk-scalar-attrs m) (@small-attribute-transform m)))
        (into {} (map (fn [[k v]] [(key-string k) (value-string v)])) (or m {})))))

(defn- service-name [resource fallback]
  (let [attributes (:attributes resource)]
    (cond
      (contains? attributes "service.name")
      (value-string (get attributes "service.name"))

      (contains? attributes :service.name)
      (value-string (get attributes :service.name))

      :else fallback)))

(def ^:dynamic ^:private *timestamp-wire* :unix-nanos)

(defn- timestamp [nanos]
  ;; Pinned libchdb package 26.7.3 / SQL engine 26.7.2.1 reads JSONEachRow
  ;; integer DateTime64(9) values as raw nanosecond ticks (pre-26.8 semantics).
  ;; 26.9 uses canonical UTC ISO strings instead; both retain the same bounded
  ;; source domain, including epoch zero. Selection belongs to each exporter.
  (when-not (and (integer? nanos) (<= 0 nanos 9223372036854775807))
    (throw (ex-info "Telemetry timestamp exceeds DateTime64 nanosecond domain"
                    {:type ::invalid-timestamp-nanos})))
  (case *timestamp-wire*
    :unix-nanos nanos
    :raw-ticks nanos
    :iso-utc (.toString (java.time.Instant/ofEpochSecond
```

## SDK native controls
```
(ns otel.scalar-string-map-test
  "Explicit native qualification; run with :scalar-map-native-test.
  This gate fails rather than skips if the optional native capability is absent."
  (:require [clojure.test :refer [deftest is testing] :as t]
            [jolt.scheme :as scheme]
            [otel.any-value :as any]))

(defn fixture [n]
  (into {} (map (fn [i] [(str "key-" i) (case (mod i 3) 0 "λ\n\"" 1 false i)]) (range n))))

(defn scalar-oracle [m]
  (into {} (map (fn [[k v]] [k (any/try-scalar-string v)]) m)))

(deftest primitive-maps-retain-values-order-kind-and-input
  (doseq [n [9 16 32 128]]
    (let [input (with-meta (fixture n) {:source true})
          before (vec input) result (any/try-scalar-string-map input)]
      (is (map? result))
      (is (= (scalar-oracle input) result))
      (is (= (vec (keys input)) (vec (keys result))))
      (is (= (type input) (type result)))
      (is (nil? (meta result)))
      (is (= before (vec input)))
      (is (= {:source true} (meta input)))))
  (let [extremes ((scheme/eval-string "(lambda () (jolt-vector (most-negative-fixnum) (most-positive-fixnum)))"))]
    (doseq [v (into [true false "" "😀"] extremes)]
      (let [m (assoc (fixture 16) "value" v)]
        (is (= (scalar-oracle m) (any/try-scalar-string-map m)))))))

(deftest unsupported-shapes-decline-without-converting
  (doseq [m [nil {} (fixture 8) (fixture 129) (sorted-map "x" 3)
             (apply array-map (mapcat identity (fixture 16)))
             (assoc (fixture 16) :alias 1)
             (assoc (fixture 16) "nil" nil)
             (assoc (fixture 16) "float" 1.5)
             (assoc (fixture 16) "record" any/empty-value)
             (assoc (fixture 16) "bytes" (any/bytes [0 255]))
             (assoc (fixture 16) "nested" {:x 3})
             (assoc (fixture 16) "big" any/max-int64)]]
    (is (nil? (any/try-scalar-string-map m))))
  (is (= (hash "Aa") (hash "BB")))
  (is (nil? (any/try-scalar-string-map (assoc (fixture 16) "Aa" 1 "BB" 2)))))

(deftest source-owned-policy-roots-decline-without-invoking-replacements
  (let [m (fixture 16) calls (atom 0)
        replacement (fn [& _] (swap! calls inc) "custom")]
    (doseq [v [#'any/try-scalar-string #'any/canonicalize #'any/string-bytes]]
      (with-redefs-fn {v replacement}
        #(is (nil? (any/try-scalar-string-map m)))))
    (with-redefs [any/default-limits (into {} any/default-limits)]
      (is (nil? (any/try-scalar-string-map m))))
    (with-redefs [any/min-int64 -10]
      (is (nil? (any/try-scalar-string-map m))))
    (with-redefs [any/max-int64 10]
      (is (nil? (any/try-scalar-string-map m))))
    (is (zero? @calls))
    (is (= (scalar-oracle m) (any/try-scalar-string-map m)))))

(deftest character-boundaries-match-the-sdk-scalar-policy
  (let [maximum (any/default-string-value-max-chars)]
    (doseq [unit ["a" "😀"]]
      (let [text (apply str (repeat maximum unit))
            admitted (assoc (fixture 16) "text" text)
            declined (assoc admitted "text" (str text unit))]
        (is (= text (any/try-scalar-string text)))
        (is (= (scalar-oracle admitted) (any/try-scalar-string-map admitted)))
        (is (nil? (any/try-scalar-string-map declined)))))))

(defn -main [& _]
  ;; Force the lazy native loader under a PREINSTALLED replacement. Its anchors
  ;; must come from the SDK definition, not this later loading context.
  (let [calls (atom 0)]
    (with-redefs [any/try-scalar-string (fn [& _] (swap! calls inc) "same-text")]
      (assert (nil? (any/try-scalar-string-map (fixture 16))))
      (assert (zero? @calls))))
  (assert (some? (any/try-scalar-string-map (fixture 16)))
          "native scalar-map capability must actually run in this explicit gate")
  (let [{:keys [test pass fail error]} (t/run-tests 'otel.scalar-string-map-test)]
    (assert (and (>= test 4) (>= pass 60)))
    (System/exit (if (zero? (+ fail error)) 0 1))))
```

## Exporter controls
```
(ns otel.exporter.chdb-sdk-scalar-map-test
  (:require [clojure.test :refer [deftest is] :as t]
            [otel.any-value :as any]
            [otel.exporter.chdb :as e]))

(defn fixture [n]
  (into {} (map (fn [i] [(str "key-" i) (case (mod i 3) 0 "λ\n\"" 1 false i)]) (range n))))

(defn oracle [m]
  (into {} (map (fn [[k v]] [(@#'e/key-string k) (@#'e/value-string v)]) m)))

(defn convert [m]
  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch}
    (@#'e/attrs m)))

(deftest stock-sdk-path-is-selected-before-the-native-fallback
  (let [m (with-meta (fixture 16) {:source true})]
    (with-redefs [e/small-attribute-transform
                  (delay (fn [_] (throw (ex-info "fallback-must-not-run" {}))))]
      (let [result (convert m)]
        (is (= (oracle m) result))
        (is (= (vec (keys m)) (vec (keys result))))
        (is (nil? (meta result)))
        (is (= {:source true} (meta m)))))))

(deftest unsupported-input-and-missing-sdk-helper-retain-conversion
  (doseq [m [(fixture 8) (fixture 129) (assoc (fixture 16) :alias 42)
             (assoc (fixture 16) "float" 1.25)
             (assoc (fixture 16) "nil" nil)
             (assoc (fixture 16) "nested" {"x" [1 false]})
             (assoc (fixture 16) "Aa" 1 "BB" 2)]]
    (is (= (oracle m) (convert m))))
  (with-redefs [e/sdk-scalar-map-converter (delay nil)]
    (is (= (oracle (fixture 16)) (convert (fixture 16))))))

(deftest changed-per-entry-and-sdk-policies-remain-live
  (let [m (fixture 16) seen (atom [])]
    (with-redefs [e/value-string (fn [v] (swap! seen conj v) "changed")]
      (is (= (zipmap (keys m) (repeat "changed")) (convert m)))
      (is (= (vec (vals m)) @seen)))
    (reset! seen [])
    (with-redefs [any/try-scalar-string (fn [v] (swap! seen conj v) "sdk-change")]
      (is (= (zipmap (keys m) (repeat "sdk-change")) (convert m)))
      (is (= (vec (vals m)) @seen)))
    (with-redefs [e/key-string (fn [k] (str "new-" k))]
      (is (= (oracle m) (convert m))))
    (with-redefs [any/default-limits (into {} any/default-limits)]
      (is (= (oracle m) (convert m))))))

(deftest late-exporter-policy-change-after-sdk-call-falls-back
  (let [m (fixture 16) original @#'e/value-string
        sdk-var (ns-resolve 'otel.any-value 'try-scalar-string-map)
        sdk-original @sdk-var
        seen (atom [])]
    (with-redefs-fn
      {#'e/value-string original
       sdk-var (fn [value]
                 (let [converted (sdk-original value)]
                   (alter-var-root #'e/value-string
                                   (constantly (fn [v] (swap! seen conj v) "late")))
                   converted))}
      #(do (is (= (zipmap (keys m) (repeat "late")) (convert m)))
           (is (= (vec (vals m)) @seen))))))

(defn -main [& _]
  (let [sdk-var (ns-resolve 'otel.any-value 'try-scalar-string-map)]
    (assert (and sdk-var (some? (@sdk-var (fixture 16))))
            "explicit gate requires the new SDK native capability"))
  (let [{:keys [test pass fail error]} (t/run-tests 'otel.exporter.chdb-sdk-scalar-map-test)]
    (assert (and (>= test 4) (>= pass 20)))
    (System/exit (if (zero? (+ fail error)) 0 1))))
```
