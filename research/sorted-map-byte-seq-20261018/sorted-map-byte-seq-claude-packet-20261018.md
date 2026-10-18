Read-only source review; no tools, edits, publication, credentials or telemetry. Review this exact bounded uncommitted patch based on casselc/data.json 8d557cc. Does the seq-based map byte-emitter preserve stock write-object semantics for custom tagged-table :seq operations, lazy next, key conversion, JSONWriter method replacement, actual StringWriter observation/replacement, exceptions and reentry? Existing per-value site/root checks, flush/sync and restorable row context are unchanged. New branch applies only htable? with selected stock map writer; no tree/type-tag inspection. All 57 byte/segmented/focused tests pass973 assertions; do not treat green tests as proof. Sorted SDK workload component improved ~29% p50, no Durable claim. Identify concrete bugs or missing test obligations, <=800 words. All source is inline below.

DIFF:
diff --git a/.github/workflows/jolt.yml b/.github/workflows/jolt.yml
index 9a4d227..3d6bc89 100644
--- a/.github/workflows/jolt.yml
+++ b/.github/workflows/jolt.yml
@@ -54,7 +54,7 @@ jobs:
         run: |
           "$WRITER_JOLT_BIN" -Srepro -A:jolt-native-test \
             -Sdeps '{:paths ["src/main/clojure" "src/test/clojure"]}' \
-            -e "(require 'clojure.data.json-byte-writer-test 'clojure.data.json-segmented-batch-test 'clojure.test) (let [r (clojure.test/run-tests 'clojure.data.json-byte-writer-test 'clojure.data.json-segmented-batch-test)] (System/exit (if (and (pos? (:test r)) (zero? (+ (:fail r) (:error r)))) 0 1)))"
+            -e "(require 'clojure.data.json-byte-writer-test 'clojure.data.json-sorted-byte-test 'clojure.data.json-segmented-batch-test 'clojure.test) (let [r (clojure.test/run-tests 'clojure.data.json-byte-writer-test 'clojure.data.json-sorted-byte-test 'clojure.data.json-segmented-batch-test)] (System/exit (if (and (pos? (:test r)) (zero? (+ (:fail r) (:error r)))) 0 1)))"
 
   jvm:
     name: JVM Clojure 1.11.4 (Linux x86_64)
diff --git a/CHANGELOG.md b/CHANGELOG.md
index 1634d72..8a7c93d 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -2,6 +2,12 @@
 
 ## Unreleased — experimental, fork-only
 
+- Keep tagged-table maps such as sorted maps on the explicit native byte
+  emitter after selecting their stock map JSONWriter. Traverse the receiver's
+  live sequence operations rather than a private tree, publishing and syncing
+  its real writer around callbacks. Preserve custom operations, lazy-next
+  ordering, method replacement, reentry and errors; no default-backend change.
+
 - Emit short clean ASCII strings directly into available native scratch without
   fragment-cache hashing. Publish the cursor only after full qualification;
   escaped/Unicode strings and buffer-boundary cases keep the existing cached
diff --git a/src/main/clojure/clojure/data/json/jolt_byte_writer.ss b/src/main/clojure/clojure/data/json/jolt_byte_writer.ss
index a2c549d..2cfbd7a 100644
--- a/src/main/clojure/clojure/data/json/jolt_byte_writer.ss
+++ b/src/main/clojure/clojure/data/json/jolt_byte_writer.ss
@@ -202,6 +202,29 @@
         (if omit? printed?
             (begin (when printed? (byte! 44))
                    (string! name) (byte! 58) (emit! child) #t)))))
+  ;; Use the receiver's public seq operation, never its private tree. Tagged
+  ;; tables may carry custom operations, and lazy sequence callbacks can inspect
+  ;; or replace the actual writer. Publish/synchronize at every such boundary.
+  (define (observe thunk)
+    (flush!)
+    (let ((result (thunk))) (sync!) result))
+  (define (emit-seq-map! x)
+    (byte! 123)
+    (let loop ((xs (observe (lambda () (jolt-seq x)))) (printed? #f))
+      (unless (jolt-nil? (observe (lambda () (jolt-seq xs))))
+        (let* ((entry (observe (lambda () (jolt-first xs))))
+               (key (observe (lambda () (jolt-nth entry 0 jolt-nil))))
+               (child (observe (lambda () (jolt-nth entry 1 jolt-nil))))
+               (name (if (string? key) key
+                         (observe (lambda ()
+                           (jolt-invoke1 (vector-ref stock-slots 10) key)))))
+               ;; Stock write-object realizes next after key conversion but
+               ;; before validating the key or invoking the child JSONWriter.
+               (next (observe (lambda () (jolt-next xs))))
+               (printed-next? (emit-map-entry! name child printed?)))
+          (unless (jolt-nil? (observe (lambda () (jolt-seq next))))
+            (loop next printed-next?)))))
+    (byte! 125))
   (define (emit! x)
     (let ((root (var-cell-root djn-method-cell)))
       (if (not (eq? root (vector-ref stock-slots 0)))
@@ -229,6 +252,10 @@
                (byte! 123)
                (pmap-fold-seq-order x emit-map-entry! #f)
                (byte! 125))
+              ;; The already-selected stock map method establishes map
+              ;; semantics. Inspect no user tag/equality and bypass no :ops.
+              ((and (htable? x) (eq? impl (vector-ref stock-slots 7)))
+               (emit-seq-map! x))
               (else (invoke! impl x)))))))
 
     ;; One set of dynamic-wind closures per serial factory, not per row.

NEW TEST FILE / CONTEXT / STOCK IMPLEMENTATION:
(ns clojure.data.json-sorted-byte-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
            [jolt.host :as host]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [jolt.scheme :as scheme]))

(defn candidate [value]
  (binding [json/*experimental-native-writer* (native/load-payload-byte-buffer-writer!)]
    (json/write-str value)))

(deftest sorted-values-and-order
  (doseq [value [(sorted-map) (sorted-map "b" false "a" "é\n😀" "c" nil)
                 (sorted-map-by #(compare %2 %1) "b" [42] "a" true)
                 [(sorted-map "nested" (sorted-map "x" 42))]]]
    (is (= (json/write-str value) (candidate value)))
    (is (= (str (json/write-str value) "\n")
           (native/write-batch-text! [value] 10000)))))

(deftest traversal-does-not-invoke-comparator
  (let [armed (atom false) calls (atom 0)
        value (sorted-map-by (fn [a b]
                               (when @armed
                                 (swap! calls inc)
                                 (throw (ex-info "unexpected comparison" {})))
                               (compare a b)) "b" false "a" 42)]
    (reset! armed true)
    (is (= "{\"a\":42,\"b\":false}" (candidate value)))
    (is (zero? @calls))))

(deftest custom-table-seq-sees-published-prefix-and-can-replace-it
  (let [value (sorted-map "original" 42)
        ops (host/ref-get value :ops)
        writer (java.io.StringWriter.) seen (atom [])]
    ;; Use the supported table operation surface, not a forged native tree.
    (host/ref-put! value :ops
                   (assoc ops :seq
                          (fn [_]
                            (swap! seen conj (.toString writer))
                            (.write writer " ")
                            (seq [["replacement" false]]))))
    ((native/load-payload-byte-buffer-writer!) value writer json/default-write-options
                                               @#'json/native-writer-stock)
    (is (= ["{"] @seen))
    (is (= "{ \"replacement\":false}" (.toString writer)))))

(deftest child-callback-reentry-and-exception-recovery
  (let [seen (atom nil)
        child (reify json/JSONWriter
                (-write [_ out _]
                  (reset! seen (.toString out))
                  (.write out (candidate (sorted-map "inner" true)))))
        value (sorted-map "a" child "z" 42)]
    (is (= "{\"a\":{\"inner\":true},\"z\":42}" (candidate value)))
    (is (= "{\"a\":" @seen)))
  (let [write (native/load-payload-byte-buffer-writer!)
        bad (sorted-map "x" (reify json/JSONWriter
                              (-write [_ _ _] (throw (ex-info "expected" {})))))]
    (is (thrown? Throwable
          (write bad (java.io.StringWriter.) json/default-write-options @#'json/native-writer-stock)))
    (let [out (java.io.StringWriter.)]
      (write (sorted-map "ok" true) out json/default-write-options @#'json/native-writer-stock)
      (is (= "{\"ok\":true}" (.toString out))))))

(deftest custom-sequence-next-and-child-effects-match-stock
  (let [run (fn [write]
              (let [out (java.io.StringWriter.) effects (atom [])
                    child (reify json/JSONWriter
                            (-write [_ writer _]
                              (swap! effects conj [:child (.toString writer)])
                              (.write writer "42")))
                    value (sorted-map "ignored" false)
                    ops (host/ref-get value :ops)]
                (host/ref-put! value :ops
                  (assoc ops :seq
                    (fn [_]
                      (swap! effects conj [:seq (.toString out)])
                      (cons ["a" child]
                            (lazy-seq
                              (swap! effects conj [:next (.toString out)])
                              (list ["z" false]))))))
                (write value out)
                [(.toString out) @effects]))
        stock (run #(json/write %1 %2))
        native (run #((native/load-payload-byte-buffer-writer!)
                      %1 %2 json/default-write-options @#'json/native-writer-stock))]
    (is (= stock native))
    (is (= [[:seq "{"] [:next "{"] [:child "{\"a\":"]] (second native)))))

(deftest live-map-method-and-dispatcher-are-not-bypassed
  (let [value (sorted-map "a" 42) seen (atom [])
        original (nth @#'json/native-writer-stock 7)]
    (try
      (extend java.util.Map json/JSONWriter
              {:-write (fn [receiver out _]
                         (swap! seen conj (identical? value receiver))
                         (.write out "\"custom-map\""))})
      (is (= "\"custom-map\"" (candidate value)))
      (is (= [true] @seen))
      (finally (extend java.util.Map json/JSONWriter {:-write original})))
    (with-redefs [json/-write (fn [_ out _] (.write out "\"custom-root\""))]
      (is (= "\"custom-root\"" (candidate value))))))

(deftest native-map-sequence-branch-has-a-same-output-negative-control
  (native/load-payload-byte-buffer-writer!)
  (let [source (slurp (io/resource "clojure/data/json/jolt_byte_writer.ss"))
        observed (string/replace source "(define (emit-seq-map! x)"
                                  "(define (emit-seq-map! x) (set! visits (+ visits 1))")
        mutant (string/replace observed "               (emit-seq-map! x))"
                                "               (invoke! impl x))")
        probe (fn [body]
                (let [factory (scheme/eval-string
                                (str "(let ((visits 0)) " body
                                     " (jolt-vector djn-make-byte-buffer-writer (lambda () visits)))"))
                      out (java.io.StringWriter.)]
                  (((nth factory 0)) (sorted-map "a" 42) out json/default-write-options
                                      @#'json/native-writer-stock)
                  [(.toString out) ((nth factory 1))]))]
    (is (not= source observed))
    (is (not= observed mutant))
    (is (= ["{\"a\":42}" 1] (probe observed)))
    (is (= ["{\"a\":42}" 0] (probe mutant)))))

(defn -main [& _]
  (let [r (run-tests 'clojure.data.json-sorted-byte-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
                    (append-range! scratch start 21))
                  (digits q (fx- i 1))))))))
  (define (invoke! impl x)
    (flush!)
    (let ((result (jolt-invoke3 impl x writer options)))
      (sync!) result))
  ;; The visitor uses only the restorable factory context and its arguments.
  ;; Construct it once, not once per map; nested emits retain their own fold
  ;; accumulator and reentrant rows restore the surrounding writer context.
  (define (emit-map-entry! k child printed?)
    (let ((name (if (string? k) k
                    (begin (flush!)
                      (let ((name (jolt-invoke1 (vector-ref stock-slots 10) k)))
                        (sync!) name)))))
      ;; Unknown equality representations may themselves be observable;
      ;; preserve the stock sentinel check and its flush boundary.
      (unless (or (jolt-nil? child) (string? child) (boolean? child)
                  (number? child) (keyword? child) (symbol-t? child)
                  (pvec? child) (pmap? child) (procedure? child)) (flush!))
      (unless (string? name)
        (flush!)
        (jolt-throw (host-new "Exception" "JSON object keys must be strings")))
      (let ((omit? (jolt=2 (vector-ref stock-slots 9) child)))
        (sync!)
        (if omit? printed?
            (begin (when printed? (byte! 44))
                   (string! name) (byte! 58) (emit! child) #t)))))
  ;; Use the receiver's public seq operation, never its private tree. Tagged
  ;; tables may carry custom operations, and lazy sequence callbacks can inspect
  ;; or replace the actual writer. Publish/synchronize at every such boundary.
  (define (observe thunk)
    (flush!)
    (let ((result (thunk))) (sync!) result))
  (define (emit-seq-map! x)
    (byte! 123)
    (let loop ((xs (observe (lambda () (jolt-seq x)))) (printed? #f))
      (unless (jolt-nil? (observe (lambda () (jolt-seq xs))))
        (let* ((entry (observe (lambda () (jolt-first xs))))
               (key (observe (lambda () (jolt-nth entry 0 jolt-nil))))
               (child (observe (lambda () (jolt-nth entry 1 jolt-nil))))
               (name (if (string? key) key
                         (observe (lambda ()
                           (jolt-invoke1 (vector-ref stock-slots 10) key)))))
               ;; Stock write-object realizes next after key conversion but
               ;; before validating the key or invoking the child JSONWriter.
               (next (observe (lambda () (jolt-next xs))))
               (printed-next? (emit-map-entry! name child printed?)))
          (unless (jolt-nil? (observe (lambda () (jolt-seq next))))
            (loop next printed-next?)))))
    (byte! 125))
  (define (emit! x)
    (let ((root (var-cell-root djn-method-cell)))
      (if (not (eq? root (vector-ref stock-slots 0)))
          (invoke! root x)
          (let ((impl (site x)))
            (sync!)
            (cond
              ((and (string? x) (eq? impl (vector-ref stock-slots 6))) (string! x))
              ((and (jolt-nil? x) (eq? impl (vector-ref stock-slots 1))) (ascii! "null"))
              ((and (boolean? x) (eq? impl (vector-ref stock-slots 2)))
               (ascii! (if x "true" "false")))
              ((and (integer? x) (exact? x)
                    (or (eq? impl (vector-ref stock-slots 2)) (eq? impl (vector-ref stock-slots 4))))
               (integer! x))
              ((and (flonum? x) (finite? x) (eq? impl (vector-ref stock-slots 3)))
               (ascii! (jolt-flonum->string x)))
              ((and (or (keyword? x) (symbol-t? x)) (eq? impl (vector-ref stock-slots 5)))
               (string! (if (keyword? x) (keyword-t-name x) (symbol-t-name x))))
              ((and (pvec? x) (eq? impl (vector-ref stock-slots 8)))
               (byte! 91)
               (do ((i 0 (fx+ i 1))) ((fx=? i (pvec-count x)))
                 (unless (fx=? i 0) (byte! 44)) (emit! (pvec-nth! x i)))
               (byte! 93))
              ((and (pmap? x) (eq? impl (vector-ref stock-slots 7)))
               (byte! 123)
               (pmap-fold-seq-order x emit-map-entry! #f)
               (byte! 125))
              ;; The already-selected stock map method establishes map
              ;; semantics. Inspect no user tag/equality and bypass no :ops.
              ((and (htable? x) (eq? impl (vector-ref stock-slots 7)))
               (emit-seq-map! x))
              (else (invoke! impl x)))))))

    ;; One set of dynamic-wind closures per serial factory, not per row.
    ;; Reentrant writes save and restore these invocation fields with the
    ;; existing writer/scratch context. Idle factories retain no input row.
    (define (run-row!)
      (emit! row-value) (ascii! row-ending) (set! row-complete? #t))
    (define (flush-row!)
      (vector-set! sink-reuse-proof 0 #f)
      (sync!) (flush-buffer!)
      (when (and batch ever-exposed?)
        (sb-set! writer (djn-byte-batch-text batch row-start)))
      ;; Publish only after successful flush, before restoring the outer row.
      ;; A throwing call cannot return this proof through the bound adapter.
      (vector-set! sink-reuse-proof 0
                   (and row-complete? batch (not ever-exposed?) #t)))
(defn- write-object [m ^Appendable out options]
  (let [key-fn (get options :key-fn)
        value-fn (get options :value-fn)
        default-key? (identical? key-fn default-write-key-fn)
        default-value? (identical? value-fn default-value-fn)
        indent (get options :indent)
        opts (cond-> options
               indent (update :indent-depth inc))]
    (.append out \{)
    (when (and indent (seq m))
      (write-indent out opts))
    ;; Keep the map view once. In Jolt, seq/first/next on a map each build a
    ;; complete entry view; iterating the seq avoids rebuilding it per object.
    (loop [x (seq m), have-printed-kv false]
      (when (seq x)
        (let [[k v] (first x)
              out-key (if (and default-key? (string? k)) k (key-fn k))
              out-value (if default-value? v (value-fn k v))
              nxt (next x)]
          (when-not (string? out-key)
            (throw (Exception. "JSON object keys must be strings")))
          (if-not (= value-fn out-value)
            (do
              (when have-printed-kv
                (.append out \,)
                (when indent
                  (write-indent out opts)))
              (write-string out-key out opts)
              (.append out \:)
              (when indent
                (.append out \space))
              (-write out-value out opts)
              (when (seq nxt)
                (recur nxt true)))
            (when (seq nxt)
              (recur nxt have-printed-kv))))))
    (when (and indent (seq m))
      (write-indent out options)))
  (.append out \}))

(defn- write-array [s ^Appendable out options]
  (let [indent (get options :indent)
        opts (cond-> options
                  indent (update :indent-depth inc))]
    (.append out \[)
    (when (and indent (seq s))

