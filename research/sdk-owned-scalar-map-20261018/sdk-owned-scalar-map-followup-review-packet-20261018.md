Read-only followup review, at most 350 words. All material is in this prompt; do not use tools, commands, edits, delegation, or publication. Review only SDK delta a4d594a..697f58b below: prior initial scalar-map review found no concrete exporter bug, requested fixnum width fence, clear policy ownership, malformed-loader fallback. Those changes and checksum-pinned stock0.8.23 CI are the bounded followup. Source-owned SDK policy anchors remain checked natively before/after callback-free admission. Unsupported feature returns nil and callers use unchanged conversion. Private/core mutation and atomic cross-thread root swaps are NOT qualified. Current hardened helper5/66 passes qualified1ae AND stock0.8.22; fullSDK470/4086 at0cd passed; CI static controls2/17 pass. New stock0.8.23 hosted job is not yet credited. Archive SHA90031be43241e61d0788a2f1bbd3da56d8f4324ae9fa87271f9637b43a6071f2 came from exact GitHub release asset digest, installer SHA737e4f3198f7bb41094b7cb9dc95029ac142694a4d3a829d4dcf64d7676e8a70 from exact source a0ac71c3 install. Existing floor job/min-version remain0.8.6; no measured compiler replacement. Identify concrete defects in the delta, or say none; do not invent UTF8-vs-char mismatch: SDK string-bytes is exactly4*count and Jolt count/Chez string-length share codepoints (astral tests pass).

```diff
diff --git a/.github/workflows/tests.yml b/.github/workflows/tests.yml
index 2cd8ccd..ceddcb4 100644
--- a/.github/workflows/tests.yml
+++ b/.github/workflows/tests.yml
@@ -53,3 +53,42 @@ jobs:
         run: jolt -Srepro -A:test -m hegel.install
       - name: Run aggregate tests
         run: jolt -Srepro -M:test -m otel.test-runner
+
+  native-scalar-map:
+    runs-on: ubuntu-latest
+    timeout-minutes: 15
+    env:
+      JOLT_CACHE_DIR: ${{ github.workspace }}/.jolt-cache-v0.8.23
+      JOLT_GITLIBS_DIR: ${{ github.workspace }}/.jolt-gitlibs-v0.8.23
+      JOLT_VERSION: 0.8.23
+      JOLT_SOURCE_COMMIT: a0ac71c3f638fe55158bd01b4ecbb0dd3d7545e5
+      JOLT_INSTALLER_SHA256: 737e4f3198f7bb41094b7cb9dc95029ac142694a4d3a829d4dcf64d7676e8a70
+      JOLT_ARCHIVE_SHA256: 90031be43241e61d0788a2f1bbd3da56d8f4324ae9fa87271f9637b43a6071f2
+    steps:
+      - name: Check out source
+        uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
+        with:
+          persist-credentials: false
+      - name: Install checksum-pinned stock Jolt
+        shell: bash
+        run: |
+          set -euo pipefail
+          installer="$RUNNER_TEMP/install-jolt"
+          install_dir="$RUNNER_TEMP/jolt-bin"
+          curl -fsSL --retry 2 --retry-all-errors \
+            "https://raw.githubusercontent.com/jolt-lang/jolt/$JOLT_SOURCE_COMMIT/install" \
+            -o "$installer"
+          echo "$JOLT_INSTALLER_SHA256  $installer" | sha256sum -c -
+          bash "$installer" --dir "$install_dir" --version "$JOLT_VERSION" \
+            --checksum "$JOLT_ARCHIVE_SHA256"
+          echo "$install_dir" >> "$GITHUB_PATH"
+      - name: Assert stock Jolt provenance
+        shell: bash
+        run: |
+          set -euo pipefail
+          test "$(jolt --version)" = "jolt v$JOLT_VERSION"
+          jolt -Srepro -Sdescribe
+      - name: Require the real native scalar-map capability
+        run: jolt -Srepro -M:scalar-map-native-test
+      - name: Retain ordinary AnyValue semantics
+        run: jolt -Srepro -M:test -m otel.test-runner any-value
diff --git a/CHANGELOG.md b/CHANGELOG.md
index d1a9efb..5a81b8d 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -8,6 +8,8 @@
   scalar conversion. Preserve values, bounds, map order and input ownership.
   Use portable Throwable catching in AnyValue normalization so its host
   fallback can load on Babashka/JVM as well as Jolt.
+  Qualify the real native capability separately on checksum-pinned stock Jolt
+  0.8.23 without changing the existing runtime floor or floor test lane.
 
 - Release the span batch worker's wait when a complete batch is queued, using
   the existing bounded wait slices without producer blocking or normal-use
diff --git a/resources/otel/ci-toolchain.edn b/resources/otel/ci-toolchain.edn
index 03c1a1d..e6dc3a2 100644
--- a/resources/otel/ci-toolchain.edn
+++ b/resources/otel/ci-toolchain.edn
@@ -3,6 +3,11 @@
   :source-commit "c2a494272cf82785ec8803426b495366000de572"
   :installer-sha256 "6e9e16d1db9875fd3e52e5143eab71375c00f44f3b5d321d61e9996a3c948736"
   :linux-x86-64-archive-sha256 "3dab6373f2028efaf7a0e41231fc316de866e55454023c037e9d5981be6358c2"}
+ :native-scalar-map
+ {:version "0.8.23"
+  :source-commit "a0ac71c3f638fe55158bd01b4ecbb0dd3d7545e5"
+  :installer-sha256 "737e4f3198f7bb41094b7cb9dc95029ac142694a4d3a829d4dcf64d7676e8a70"
+  :linux-x86-64-archive-sha256 "90031be43241e61d0788a2f1bbd3da56d8f4324ae9fa87271f9637b43a6071f2"}
  :checkout
  {:revision "11bd71901bbe5b1630ceea73d27597364c9af683"
   :release "v4.2.2"}}
diff --git a/resources/otel/scalar_string_map.ss b/resources/otel/scalar_string_map.ss
index 8e41910..662e4c2 100644
--- a/resources/otel/scalar_string_map.ss
+++ b/resources/otel/scalar_string_map.ss
@@ -1,6 +1,9 @@
 ;; Policy anchors are supplied by their SDK owner, never captured in a consumer.
 ;; Admission and conversion contain no user callbacks. Unsupported structures
 ;; decline before allocating output or invoking any converter.
+;; SDK string-bytes charges exactly 4 * host character count, not actual UTF8
+;; bytes. Jolt count and Chez string-length both count the same codepoints.
+;; The loader separately fences the host's entire fixnum range within Int64.
 (lambda (bindings maximum)
   (define (same-policy?)
     (let loop ((i 0))
diff --git a/src/otel/any_value.clj b/src/otel/any_value.clj
index e588909..bad395e 100644
--- a/src/otel/any_value.clj
+++ b/src/otel/any_value.clj
@@ -266,21 +266,23 @@
 (def ^:private scalar-map-min-int64 min-int64)
 (def ^:private scalar-map-max-int64 max-int64)
 
+(defn- load-scalar-string-map-converter []
+  (try
+    ;; Optional host-specific implementation. Ordinary JVM/BB loading never
+    ;; requires jolt.scheme; unsupported runtimes simply retain scalar calls.
+    (require 'otel.any-value-native)
+    ((ns-resolve 'otel.any-value-native 'load-string-map-converter!)
+     [[#'try-scalar-string scalar-text-helper]
+      [#'canonicalize scalar-text-canonicalizer]
+      [#'default-limits scalar-default-limits]
+      [#'string-bytes original-string-bytes]
+      [#'min-int64 scalar-map-min-int64]
+      [#'max-int64 scalar-map-max-int64]]
+     (quot (:max-bytes scalar-default-limits) 4))
+    (catch Throwable _ (constantly nil))))
+
 (def ^:private scalar-string-map-converter
-  (delay
-    (try
-      ;; Optional host-specific implementation. Ordinary JVM/BB loading never
-      ;; requires jolt.scheme; unsupported runtimes simply retain scalar calls.
-      (require 'otel.any-value-native)
-      ((ns-resolve 'otel.any-value-native 'load-string-map-converter!)
-       [[#'try-scalar-string scalar-text-helper]
-        [#'canonicalize scalar-text-canonicalizer]
-        [#'default-limits scalar-default-limits]
-        [#'string-bytes original-string-bytes]
-        [#'min-int64 scalar-map-min-int64]
-        [#'max-int64 scalar-map-max-int64]]
-       (quot (:max-bytes scalar-default-limits) 4))
-      (catch Throwable _ (constantly nil)))))
+  (delay (load-scalar-string-map-converter)))
 
 (defn try-scalar-string-map
   "Return a bounded builtin map's scalar text values, or nil to decline.
@@ -295,4 +297,6 @@
   Private implementation roots and ordinary core behavior remain trusted; this
   is not an atomic cross-thread redefinition lock."
   [value]
+  ;; The native kernel owns the live SDK-root checks supplied by this defining
+  ;; namespace; repeating them here would add a second policy boundary.
   (or (@scalar-string-map-converter value) nil))
diff --git a/src/otel/any_value_native.clj b/src/otel/any_value_native.clj
index a11dbc4..a44322e 100644
--- a/src/otel/any_value_native.clj
+++ b/src/otel/any_value_native.clj
@@ -17,6 +17,9 @@
          "(bitwise-bit-set? (procedure-arity-mask (top-level-value (car entry))) (cadr entry)))) "
          "'((make-pmap 2) (var-cell-deref 1) (jolt-fixnum->string 1))) "
          "(error 'scalar-string-map \"Unsupported runtime helpers\")) "
+         "(unless (and (>= (most-negative-fixnum) -9223372036854775808) "
+         "(<= (most-positive-fixnum) 9223372036854775807)) "
+         "(error 'scalar-string-map \"Native fixnums exceed Int64\")) "
          (slurp url) ")")
     (throw (ex-info "Missing native scalar-map resource" {}))))
 
diff --git a/test/otel/ci_workflow_test.clj b/test/otel/ci_workflow_test.clj
index cb69b2c..21c06ad 100644
--- a/test/otel/ci_workflow_test.clj
+++ b/test/otel/ci_workflow_test.clj
@@ -44,6 +44,24 @@
                       "jolt -Srepro -M:test -m otel.test-runner")
        (not (str/includes? workflow "joltc"))))
 
+(defn- native-scalar-map-qualified? [toolchain workflow]
+  (and (workflow-matches-toolchain?
+         (assoc toolchain :jolt (:native-scalar-map toolchain)) workflow)
+       (str/includes? workflow "  native-scalar-map:")
+       (str/includes? workflow "jolt -Srepro -M:scalar-map-native-test")
+       (str/includes? workflow "jolt -Srepro -M:test -m otel.test-runner any-value")))
+
+(deftest native-scalar-map-has-an-independent-pinned-capability-gate
+  (let [toolchain (edn/read-string (slurp toolchain-path))
+        workflow (slurp workflow-path)]
+    (is (native-scalar-map-qualified? toolchain workflow))
+    (is (not (native-scalar-map-qualified?
+               (assoc-in toolchain [:native-scalar-map :linux-x86-64-archive-sha256]
+                         (apply str (repeat 64 "0"))) workflow)))
+    (is (not (native-scalar-map-qualified?
+               toolchain (str/replace workflow "jolt -Srepro -M:scalar-map-native-test"
+                                      "true"))))))
+
 (deftest hosted-workflow-is-pinned-and-reproducible
   (let [toolchain (edn/read-string (slurp toolchain-path))
         deps (edn/read-string (slurp deps-path))
diff --git a/test/otel/scalar_string_map_test.clj b/test/otel/scalar_string_map_test.clj
index 2a4634e..7c823d0 100644
--- a/test/otel/scalar_string_map_test.clj
+++ b/test/otel/scalar_string_map_test.clj
@@ -3,6 +3,7 @@
   This gate fails rather than skips if the optional native capability is absent."
   (:require [clojure.test :refer [deftest is testing] :as t]
             [jolt.scheme :as scheme]
+            [otel.any-value-native :as native]
             [otel.any-value :as any]))
 
 (defn fixture [n]
@@ -66,6 +67,13 @@
         (is (= (scalar-oracle admitted) (any/try-scalar-string-map admitted)))
         (is (nil? (any/try-scalar-string-map declined)))))))
 
+(deftest malformed-optional-native-code-declines-without-losing-the-fallback
+  (with-redefs [native/converter-source "("]
+    (let [decline (@#'any/load-scalar-string-map-converter)]
+      (is (nil? (decline (fixture 16))))
+      (is (= "42" (any/try-scalar-string 42)))))
+  (is (some? (any/try-scalar-string-map (fixture 16)))))
+
 (defn -main [& _]
   ;; Force the lazy native loader under a PREINSTALLED replacement. Its anchors
   ;; must come from the SDK definition, not this later loading context.
@@ -76,5 +84,5 @@
   (assert (some? (any/try-scalar-string-map (fixture 16)))
           "native scalar-map capability must actually run in this explicit gate")
   (let [{:keys [test pass fail error]} (t/run-tests 'otel.scalar-string-map-test)]
-    (assert (and (>= test 4) (>= pass 60)))
+    (assert (and (>= test 5) (>= pass 66)))
     (System/exit (if (zero? (+ fail error)) 0 1))))
```
