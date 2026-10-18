Read-only source review using INLINE SOURCE ONLY; no tools, edits, publication, credentials or telemetry. Exact casselc/otel commit1fa26141faad237e80cd7c22bcecae5038558446 based697f58b, a new OPTIONAL try-sorted-scalar-string-map. Existing HAMT/map-kind API unchanged. Need <=700words concrete defects/counterexamples or no findings. Scope: stock-operation identity admission, bounds/tree traversal order, source-owned live SDK roots and no trial callbacks, fresh plain-map kind/order/meta vs into{}, unknown layout decline, lazy optional loader JVM/BB behavior. Ordinary core behavior and private SDK/runtime roots are explicitly trusted; no atomic cross-thread redefinition or adversarial private-mutation guarantee. Prototype is defining-SDK-created sorted map3markers; geometry qualification independent of current SDK policy so a preinstalled temporary policy replacement doesn't cache permanent decline. Node colors are not used by normal in-order seq and needn't be validated for normalization; malformed node shape/count are bounded and rejected. Custom comparators may exist, but stock :ops required and comparator never called. Every accepted key intrinsic String and value bounded String/Boolean/native fixnum; no value conversion callback. Geometry/traversal tests6/78 pass with preinstalled public-helper change, other public policies, custom operations, armed comparator, metadata, hash collision order and wrong-geometry source mutant. Consumer tests separate5/21 pass; do not treat them as proof. Initial same-byte component14/16pairs faster (~15%mean ratio benefit), no persistence claim yet. This is a non-security semantic review; source below is authoritative. Watch for compatibility/trust assumptions not covered by record UID alone.

commit 1fa26141faad237e80cd7c22bcecae5038558446
Author:     Chuck Cassel <619504+casselc@users.noreply.github.com>
AuthorDate: Sun Oct 18 08:29:42 2026 -0700
Commit:     Chuck Cassel <619504+casselc@users.noreply.github.com>
CommitDate: Sun Oct 18 08:29:42 2026 -0700

    perf: add guarded sorted scalar map normalization

 .github/workflows/tests.yml                 |   2 +
 CHANGELOG.md                                |   7 ++
 deps.edn                                    |   5 +-
 resources/otel/sorted_scalar_string_map.ss  |  64 ++++++++++++++++++
 src/otel/any_value.clj                      |  37 ++++++++++
 src/otel/any_value_native.clj               |  29 ++++++++
 test/otel/sorted_scalar_string_map_test.clj | 100 ++++++++++++++++++++++++++++
 7 files changed, 243 insertions(+), 1 deletion(-)
diff --git a/resources/otel/sorted_scalar_string_map.ss b/resources/otel/sorted_scalar_string_map.ss
new file mode 100644
index 0000000..81d1a15
--- /dev/null
+++ b/resources/otel/sorted_scalar_string_map.ss
@@ -0,0 +1,64 @@
+;; SDK-owned policy checks; ordinary core behavior/private runtime roots are
+;; trusted. Accept only the defining SDK's stock sorted-map operation table.
+;; Unknown/custom operations decline before invoking a callback or comparator.
+(lambda (bindings maximum prototype)
+  (let ((tag (keyword "jolt" "type")) (sorted-tag (keyword "jolt" "sorted-map"))
+        (ops-key (keyword #f "ops")) (tree-key (keyword #f "tree"))
+        (count-key (keyword #f "cnt")))
+    (unless (and (htable? prototype) (eq? (jolt-ref-get prototype tag) sorted-tag)
+                 (pmap? (jolt-ref-get prototype ops-key)))
+      (error 'sorted-scalar-string-map "Unsupported sorted-map prototype"))
+    (let ((stock-ops (jolt-ref-get prototype ops-key)))
+      (define (same-policy?)
+        (let loop ((i 0))
+          (or (= i (pvec-count bindings))
+              (let ((entry (pvec-nth! bindings i)))
+                (and (eq? (var-cell-deref (pvec-nth! entry 0)) (pvec-nth! entry 1))
+                     (loop (+ i 1)))))))
+      (define (admitted-tree? tree count)
+        (let ((seen 0))
+          (define (walk node)
+            (or (jolt-nil? node)
+                (and (pvec? node) (= (pvec-count node) 5) (< seen 128)
+                     (begin (set! seen (+ seen 1)) #t)
+                     (string? (pvec-nth! node 1))
+                     (let ((v (pvec-nth! node 2)))
+                       (or (boolean? v) (fixnum? v)
+                           (and (string? v) (<= (string-length v) maximum))))
+                     (walk (pvec-nth! node 3)) (walk (pvec-nth! node 4)))))
+          (and (walk tree) (= seen count))))
+      (define (text value)
+        (cond ((string? value) value) ((eq? value #t) "true") ((eq? value #f) "false")
+              (else (jolt-fixnum->string value))))
+      (define (copy-tree! tree out)
+        (unless (jolt-nil? tree)
+          (copy-tree! (pvec-nth! tree 3) out)
+          (tmap-put! out (pvec-nth! tree 1) (text (pvec-nth! tree 2)))
+          (copy-tree! (pvec-nth! tree 4) out)))
+      ;; The wrapper UID alone does not attest key/value/child positions. This
+      ;; defining-SDK prototype has distinct keys/values and both subtrees.
+      ;; Qualify geometry/order independently of live policy-root admission:
+      ;; a temporary policy replacement must not permanently disable loading.
+      (unless
+        (let ((tree (jolt-ref-get prototype tree-key)))
+          (and (eqv? (jolt-ref-get prototype count-key) 3)
+               (admitted-tree? tree 3)
+               (let ((out (jolt-transient-new empty-pmap)))
+                 (copy-tree! tree out)
+                 (let ((result (jolt-persistent! out)))
+                   (and (pmap? result) (= (pmap-cnt result) 3) (pmap-array? result)
+                        (let ((root (pmap-root result)))
+                          (and (= (vector-length root) 6)
+                               (equal? root
+                                 '#("$scalar-probe-a" "7" "$scalar-probe-b" "probe-text"
+                                    "$scalar-probe-c" "false")))))))))
+        (error 'sorted-scalar-string-map "Unsupported sorted-node geometry"))
+      (lambda (m)
+        (and (htable? m) (eq? (jolt-ref-get m tag) sorted-tag)
+             (eq? (jolt-ref-get m ops-key) stock-ops) (same-policy?)
+             (let ((count (jolt-ref-get m count-key)) (tree (jolt-ref-get m tree-key)))
+               (and (fixnum? count) (<= 0 count 128)
+                    (admitted-tree? tree count) (same-policy?)
+                    (let ((out (jolt-transient-new empty-pmap)))
+                      (copy-tree! tree out)
+                      (jolt-persistent! out)))))))))
diff --git a/src/otel/any_value.clj b/src/otel/any_value.clj
index bad395e..acbda50 100644
--- a/src/otel/any_value.clj
+++ b/src/otel/any_value.clj
@@ -300,3 +300,40 @@
   ;; The native kernel owns the live SDK-root checks supplied by this defining
   ;; namespace; repeating them here would add a second policy boundary.
   (or (@scalar-string-map-converter value) nil))
+
+;; Constructed in this defining namespace under the same trusted ordinary-core
+;; assumption as the existing native helper; not sampled from caller input.
+(def ^:private scalar-sorted-map-prototype
+  (sorted-map "$scalar-probe-a" 7 "$scalar-probe-b" "probe-text" "$scalar-probe-c" false))
+
+(defn- load-sorted-scalar-string-map-converter []
+  (try
+    (require 'otel.any-value-native)
+    ((ns-resolve 'otel.any-value-native 'load-sorted-string-map-converter!)
+     [[#'try-scalar-string scalar-text-helper]
+      [#'canonicalize scalar-text-canonicalizer]
+      [#'default-limits scalar-default-limits]
+      [#'string-bytes original-string-bytes]
+      [#'min-int64 scalar-map-min-int64]
+      [#'max-int64 scalar-map-max-int64]]
+     (quot (:max-bytes scalar-default-limits) 4)
+     scalar-sorted-map-prototype)
+    (catch Throwable _ (constantly nil))))
+
+(def ^:private sorted-scalar-string-map-converter
+  (delay (load-sorted-scalar-string-map-converter)))
+
+(defn try-sorted-scalar-string-map
+  "Return a fresh plain scalar-text map from an admitted sorted map, or nil.
+
+  Unlike try-scalar-string-map, this deliberately normalizes to the same plain
+  map as (into {} (map scalar-conversion) input); it does not preserve sorted
+  map kind. Only stock operations, string keys, bounded strings/Booleans/native
+  fixnums and at most 128 entries qualify under the unchanged SDK policy.
+  Unknown operations/layouts, malformed node shapes/counts, changed policies and unsupported
+  hosts decline without invoking callbacks. No comparator is called. Metadata
+  is dropped, input is untouched, and nil requires the caller's normal path.
+  Ordinary core behavior and private implementation roots remain trusted;
+  this is not a seal against their mutation or a cross-thread redefinition lock."
+  [value]
+  (or (@sorted-scalar-string-map-converter value) nil))
diff --git a/src/otel/any_value_native.clj b/src/otel/any_value_native.clj
index a44322e..fa08089 100644
--- a/src/otel/any_value_native.clj
+++ b/src/otel/any_value_native.clj
@@ -30,3 +30,32 @@
   [bindings maximum]
   (let [convert ((scheme/eval-string converter-source) bindings maximum)]
     (fn [value] (convert value))))
+
+(defmacro ^:private sorted-source []
+  (if-let [url (io/resource "otel/sorted_scalar_string_map.ss")]
+    (str "(begin "
+         "(unless (and "
+         "(eq? (record-type-uid (record-type-descriptor pmap)) 'chez-pmap-v6) "
+         "(equal? (record-type-field-names (record-type-descriptor pmap)) '#(root cnt hasheq meta)) "
+         "(eq? (record-type-uid (record-type-descriptor htable)) 'chez-htable-v2) "
+         "(equal? (record-type-field-names (record-type-descriptor htable)) '#(h hc))) "
+         "(error 'sorted-scalar-string-map \"Unsupported table layout\")) "
+         "(unless (for-all (lambda (entry) "
+         "(and (top-level-bound? (car entry)) (procedure? (top-level-value (car entry))) "
+         "(bitwise-bit-set? (procedure-arity-mask (top-level-value (car entry))) (cadr entry)))) "
+         "'((jolt-ref-get 2) (pvec-count 1) (pvec-nth! 2) (pmap-array? 1) (var-cell-deref 1) "
+         "(jolt-fixnum->string 1) (jolt-transient-new 1) (tmap-put! 3) (jolt-persistent! 1))) "
+         "(error 'sorted-scalar-string-map \"Unsupported runtime helpers\")) "
+         "(unless (and (>= (most-negative-fixnum) -9223372036854775808) "
+         "(<= (most-positive-fixnum) 9223372036854775807)) "
+         "(error 'sorted-scalar-string-map \"Native fixnums exceed Int64\")) "
+         (slurp url) ")")
+    (throw (ex-info "Missing native sorted-map resource" {}))))
+
+(def ^:private sorted-converter-source (sorted-source))
+
+(defn load-sorted-string-map-converter!
+  "Internal: defining SDK supplies policy anchors and the trusted core prototype."
+  [bindings maximum prototype]
+  (let [convert ((scheme/eval-string sorted-converter-source) bindings maximum prototype)]
+    (fn [value] (convert value))))
diff --git a/test/otel/sorted_scalar_string_map_test.clj b/test/otel/sorted_scalar_string_map_test.clj
new file mode 100644
index 0000000..1f12337
--- /dev/null
+++ b/test/otel/sorted_scalar_string_map_test.clj
@@ -0,0 +1,100 @@
+(ns otel.sorted-scalar-string-map-test
+  (:require [clojure.test :refer [deftest is run-tests]]
+            [otel.any-value :as any] [jolt.host :as host] [jolt.scheme :as scheme]
+            [clojure.java.io :as io] [clojure.string :as str]))
+
+(defn fixture [n]
+  (into (sorted-map) (map (fn [i] [(str "key-" i) (case (mod i 3) 0 "λ😀" 1 false i)])) (range n)))
+
+(defn oracle [m]
+  (into {} (map (fn [[k v]] [k (any/try-scalar-string v)])) m))
+
+(def preinstalled-result
+  (with-redefs [any/try-scalar-string (fn [_] (throw (ex-info "must-not-call" {})))]
+    (any/try-sorted-scalar-string-map (fixture 9))))
+
+(deftest admitted-map-matches-plain-map-kind-order-metadata-and-values
+  (is (nil? preinstalled-result))
+  (doseq [n [0 1 8 9 16 128]]
+    (let [m (with-meta (fixture n) {:source n}) before (vec m)
+          expected (oracle m) result (any/try-sorted-scalar-string-map m)]
+      (is (= expected result))
+      (is (= (type expected) (type result)))
+      (is (= (vec expected) (vec result)))
+      (is (nil? (meta result)))
+      (is (= before (vec m)))
+      (is (= {:source n} (meta m)))))
+  (doseq [m [(assoc (fixture 9) "Aa" 1 "BB" 2)
+             (into (sorted-map-by #(compare %2 %1)) (fixture 16))]]
+    (is (= (vec (oracle m)) (vec (any/try-sorted-scalar-string-map m)))))
+  (let [[lo hi] (scheme/eval-string "(jolt-vector (most-negative-fixnum) (most-positive-fixnum))")
+        m (sorted-map "lo" lo "hi" hi)]
+    (is (= (oracle m) (any/try-sorted-scalar-string-map m)))))
+
+(deftest unsupported-shapes-and-policy-roots-decline-without-helper-calls
+  (doseq [m [nil {} (hash-map "a" 1) (sorted-set "a") (fixture 129)
+             (sorted-map :a 1) (assoc (fixture 9) "nil" nil)
+             (assoc (fixture 9) "float" 1.25) (assoc (fixture 9) "nested" [1])
+             (assoc (fixture 9) "wide" 9223372036854775807)]]
+    (is (nil? (any/try-sorted-scalar-string-map m))))
+  (let [m (fixture 16) calls (atom 0)]
+    (with-redefs [any/try-scalar-string (fn [_] (swap! calls inc) "changed")]
+      (is (nil? (any/try-sorted-scalar-string-map m))))
+    (with-redefs [any/canonicalize (fn [_] (swap! calls inc) {:value "changed"})]
+      (is (nil? (any/try-sorted-scalar-string-map m))))
+    (with-redefs [any/string-bytes (fn [_] (swap! calls inc) 0)]
+      (is (nil? (any/try-sorted-scalar-string-map m))))
+    (with-redefs [any/default-limits (into {} any/default-limits)]
+      (is (nil? (any/try-sorted-scalar-string-map m))))
+    (with-redefs [any/min-int64 -1] (is (nil? (any/try-sorted-scalar-string-map m))))
+    (with-redefs [any/max-int64 1] (is (nil? (any/try-sorted-scalar-string-map m))))
+    (is (zero? @calls))))
+
+(deftest unknown-table-operations-decline-without-trial-callbacks
+  (doseq [operation [:seq :count :get]]
+    (let [m (fixture 16) calls (atom 0)]
+      (host/ref-put! m :ops
+                     (assoc (host/ref-get m :ops) operation
+                            (fn [& _] (swap! calls inc) (throw (ex-info "custom-op" {})))))
+      (is (nil? (any/try-sorted-scalar-string-map m)))
+      (is (zero? @calls)))))
+
+(deftest comparator-is-never-called-during-admission-or-copy
+  (let [armed (atom false) calls (atom 0)
+        m (into (sorted-map-by (fn [a b]
+                                (when @armed (swap! calls inc) (throw (ex-info "comparison" {})))
+                                (compare b a))) (fixture 16))
+        expected (oracle m)]
+    (reset! armed true)
+    (is (= expected (any/try-sorted-scalar-string-map m)))
+    (is (zero? @calls))))
+
+(deftest malformed-node-shapes-and-counts-decline
+  (doseq [[field value] [[:cnt -1] [:cnt 0] [:cnt "one"]
+                         [:tree [nil "a" 1 nil]] [:tree [nil "a" 1 nil nil :extra]]
+                         [:tree [nil :not-string 1 nil nil]]
+                         [:tree [nil "a" 1 :not-a-node nil]]]]
+    (let [m (sorted-map "a" 1)]
+      (host/ref-put! m field value)
+      (is (nil? (any/try-sorted-scalar-string-map m)))))
+  (let [m (fixture 1) maximum (any/default-string-value-max-chars)]
+    (is (some? (any/try-sorted-scalar-string-map (assoc m "text" (apply str (repeat maximum "x"))))))
+    (is (nil? (any/try-sorted-scalar-string-map (assoc m "text" (apply str (repeat (inc maximum) "x"))))))))
+
+(deftest loader-geometry-probe-has-an-order-sensitive-negative-control
+  (let [source (slurp (io/resource "otel/sorted_scalar_string_map.ss"))
+        mutant (str/replace source "(copy-tree! (pvec-nth! tree 3) out)"
+                                   "(copy-tree! (pvec-nth! tree 4) out)")
+        ;; Replace only the left visit; the right subtree is now visited twice.
+        ;; A mere wrapper UID check would accept this source unchanged.
+        prototype @#'any/scalar-sorted-map-prototype
+        factory (scheme/eval-string source)]
+    (is (not= (count source) 0))
+    (is (not= source mutant))
+    (is (some? ((factory [] 100 prototype) (fixture 9))))
+    (is (thrown? Throwable ((scheme/eval-string mutant) [] 100 prototype)))))
+
+(defn -main [& _]
+  (let [r (run-tests 'otel.sorted-scalar-string-map-test)]
+    (assert (= 6 (:test r)))
+    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))

