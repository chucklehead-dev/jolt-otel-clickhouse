Read-only semantic review, INLINE SOURCE ONLY, no tools/edits/publication/credentials/data. Exact exportera1bedfcc50d84dd59597fe12dec144231b282920 based20999f9. New SDK optional capability try-sorted-scalar-string-map normalizes qualified sorted inputs to same plain map as original per-entry conversion. SDK7878a2e source separately reviewed; six original scalar roots checked natively, stock ops identity and bounded node geometry qualification, no callbacks/comparator on admission; ordinary core/private roots trusted, no cross-thread redefinition lock. Consumer tries new Var then old HAMT Var, each guarded BEFORE and AFTER call by original exporter key/value function identities. Absent new API or unsupported/custom ops retain old conversion. Critical late-change test proves second helper is not called after the first changes policy, and ordinary fallback runs current per-entry converter once. Tests5/21 plus wider54/969 and actual published-pin gate pass. SDK full471/4089 and focused6/78; JVM/BB decline gates pass. Component2k16pairs14/16 faster, no persistence claim yet (separate actual SDK/Durable run currently in progress). Need <=500words concrete regressions/compatibility gaps or no findings. Check source-visible late mutation/fallback/order, default unchanged, dependency oracle witnesses and CI actual gating. Full diff below.

commit a1bedfcc50d84dd59597fe12dec144231b282920
Author:     Chuck Cassel <619504+casselc@users.noreply.github.com>
AuthorDate: Sun Oct 18 08:37:35 2026 -0700
Commit:     Chuck Cassel <619504+casselc@users.noreply.github.com>
CommitDate: Sun Oct 18 08:37:35 2026 -0700

    perf: consume SDK-owned sorted scalar normalization

 .github/workflows/tests.yml                        |  1 +
 CHANGELOG.md                                       |  6 ++
 deps.edn                                           |  9 ++-
 src/otel/exporter/chdb.clj                         | 11 +++-
 test/otel/exporter/chdb_dependency_test.clj        | 20 ++++---
 .../exporter/chdb_sdk_sorted_scalar_map_test.clj   | 68 ++++++++++++++++++++++
 6 files changed, 102 insertions(+), 13 deletions(-)
diff --git a/.github/workflows/tests.yml b/.github/workflows/tests.yml
index e4717ea..a5d4e5c 100644
--- a/.github/workflows/tests.yml
+++ b/.github/workflows/tests.yml
@@ -50,6 +50,7 @@ jobs:
           "$task_bin" -Srepro -M:native-json-byte-batch:native-attributes-test --require-string-output
           "$task_bin" -Srepro -M:sdk-scalar-map-test
           "$task_bin" -Srepro -M:native-json-byte-batch:sorted-event-encoding-test
+          "$task_bin" -Srepro -M:native-json-byte-batch:sdk-sorted-scalar-map-test
           "$task_bin" -Srepro -M:native-json-byte-batch:owned-output-test
 
   scoped-s3-sdk:
diff --git a/CHANGELOG.md b/CHANGELOG.md
index a1382a4..25a5b86 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -2,6 +2,12 @@
 
 ## Unreleased
 
+- Use the SDK's optional sorted scalar-map normalization in the native backend
+  while original converter policies remain installed. Decline custom operations
+  and unsupported inputs before callbacks; keep the normal conversion with
+  older SDKs or changed policies. Typed source values, JSON dispatch and Durable
+  acknowledgements remain unchanged.
+
 - Advance the optional native JSON pin to retain byte emission for sorted
   event attributes while honoring live map sequence operations and JSONWriter
   callbacks. Add actual SDK-ended span/event coverage for named and compact
diff --git a/deps.edn b/deps.edn
index 97be58e..d4fe8b5 100644
--- a/deps.edn
+++ b/deps.edn
@@ -5,8 +5,8 @@
          :git/sha "3d766fa295b0e98c30f275d0a334db92e6dd998c"}
         io.github.casselc/otel
         {:git/url "https://github.com/casselc/otel.git"
-         :git/sha "697f58bd5b4925f72339694bedbace97bdc869bb"
-         ;; Includes SDK-owned scalar-map conversion, ready-batch scheduling
+         :git/sha "7878a2eb4340519d6843d13490a5a7c99ebade8a"
+         ;; Includes SDK-owned HAMT/sorted scalar conversion, ready-batch scheduling
          ;; and batch<=queue validation.
          ;; Keep its canonical crypto declaration excluded and select it
          ;; directly below so this exporter owns the exact provider and SHA,
@@ -19,7 +19,10 @@
         org.clojure/data.json
         {:git/url "https://github.com/casselc/data.json.git"
          :git/sha "0f51b99101bc5e840f957c073f87b6f877309a25"}}
- :aliases {:sorted-event-encoding-test
+ :aliases {:sdk-sorted-scalar-map-test
+           {:extra-paths ["test"]
+            :main-opts ["-m" "otel.exporter.chdb-sdk-sorted-scalar-map-test"]}
+           :sorted-event-encoding-test
            {:extra-paths ["test"]
             :main-opts ["-m" "otel.exporter.chdb-sorted-event-encoding-test"]}
            :sdk-scalar-map-test
diff --git a/src/otel/exporter/chdb.clj b/src/otel/exporter/chdb.clj
index 22d8da2..c70251e 100644
--- a/src/otel/exporter/chdb.clj
+++ b/src/otel/exporter/chdb.clj
@@ -110,10 +110,13 @@
   ;; retain the existing native/generic conversion without a dependency floor.
   (delay (ns-resolve 'otel.any-value 'try-scalar-string-map)))
 
-(defn- sdk-scalar-attrs [m]
+(def ^:private sdk-sorted-scalar-map-converter
+  (delay (ns-resolve 'otel.any-value 'try-sorted-scalar-string-map)))
+
+(defn- try-sdk-scalar-attrs [m converter]
   (when (and (identical? key-string original-attribute-key-string)
              (identical? value-string original-attribute-value-string))
-    (when-let [convert-var @sdk-scalar-map-converter]
+    (when-let [convert-var @converter]
       (let [convert @convert-var
             result (convert m)]
         ;; A replaced SDK helper can change exporter policy before returning.
@@ -122,6 +125,10 @@
                    (identical? value-string original-attribute-value-string))
           result)))))
 
+(defn- sdk-scalar-attrs [m]
+  (or (try-sdk-scalar-attrs m sdk-sorted-scalar-map-converter)
+      (try-sdk-scalar-attrs m sdk-scalar-map-converter)))
+
 (defn- attrs [m]
   (if (reusable-string-attrs? m)
     ;; Match the old fresh map's metadata without copying immutable entries.
diff --git a/test/otel/exporter/chdb_dependency_test.clj b/test/otel/exporter/chdb_dependency_test.clj
index 747f1da..25ead2c 100644
--- a/test/otel/exporter/chdb_dependency_test.clj
+++ b/test/otel/exporter/chdb_dependency_test.clj
@@ -7,7 +7,7 @@
   "https___github.com_jolt-lang_jolt-crypto.git/5effcc89a3258499a79a2a3d69edad9e7800d1bf/src")
 
 (def ^:private otel-root
-  "https___github.com_casselc_otel.git/697f58bd5b4925f72339694bedbace97bdc869bb/")
+  "https___github.com_casselc_otel.git/7878a2eb4340519d6843d13490a5a7c99ebade8a/")
 
 (def ^:private prior-otel-root
   "https___github.com_casselc_otel.git/37c684a2c370fc82fde82331635973bce87a52b3/")
@@ -96,7 +96,7 @@
 
 (defn run-oracle-controls! [check]
   ;; Freeze the approved witness independently of the implementation oracle.
-  (let [fixture-root "https___github.com_casselc_otel.git/697f58bd5b4925f72339694bedbace97bdc869bb/"
+  (let [fixture-root "https___github.com_casselc_otel.git/7878a2eb4340519d6843d13490a5a7c99ebade8a/"
         root (str "/public-fixture/a/" fixture-root)
         other (str "/public-fixture/b/" fixture-root)
         source (str root "src")
@@ -109,27 +109,31 @@
            (boolean (oracle (str source ":" root "src/../resources"))))
     (check "SDK old reviewed SHA is rejected" false
            (boolean (oracle (str/replace source
-                             "697f58bd5b4925f72339694bedbace97bdc869bb"
+                             "7878a2eb4340519d6843d13490a5a7c99ebade8a"
                              "8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2"))))
     (check "SDK before owned scalar-map capability is rejected" false
            (boolean (oracle (str/replace source
-                             "697f58bd5b4925f72339694bedbace97bdc869bb"
+                             "7878a2eb4340519d6843d13490a5a7c99ebade8a"
                              "37c684a2c370fc82fde82331635973bce87a52b3"))))
+    (check "SDK before sorted scalar normalization is rejected" false
+           (boolean (oracle (str/replace source
+                             "7878a2eb4340519d6843d13490a5a7c99ebade8a"
+                             "697f58bd5b4925f72339694bedbace97bdc869bb"))))
     (check "SDK before batch-ready wakeup is rejected" false
            (boolean (oracle (str/replace source
-                             "697f58bd5b4925f72339694bedbace97bdc869bb"
+                             "7878a2eb4340519d6843d13490a5a7c99ebade8a"
                              "d93846e40b837dcecb546fb59f6cb05ca639f054"))))
     (check "SDK before per-reader policy is rejected" false
            (boolean (oracle (str/replace source
-                             "697f58bd5b4925f72339694bedbace97bdc869bb"
+                             "7878a2eb4340519d6843d13490a5a7c99ebade8a"
                              "ff8e2e4ab8fe0b8b3f8410cb202d47b1c29267a1"))))
     (check "SDK before definition-owned helper qualification is rejected" false
            (boolean (oracle (str/replace source
-                             "697f58bd5b4925f72339694bedbace97bdc869bb"
+                             "7878a2eb4340519d6843d13490a5a7c99ebade8a"
                              "e8435b381ee0cafd7d1cb5cb7b8639c551544042"))))
     (check "SDK before bounded string admission is rejected" false
            (boolean (oracle (str/replace source
-                             "697f58bd5b4925f72339694bedbace97bdc869bb"
+                             "7878a2eb4340519d6843d13490a5a7c99ebade8a"
                              "14ffd741351373f07d96aa070b0f9853d7daf8ab"))))
     (check "SDK distinct checkout at same SHA is rejected" false
            (boolean (oracle (str source ":" other "src"))))
diff --git a/test/otel/exporter/chdb_sdk_sorted_scalar_map_test.clj b/test/otel/exporter/chdb_sdk_sorted_scalar_map_test.clj
new file mode 100644
index 0000000..86930dd
--- /dev/null
+++ b/test/otel/exporter/chdb_sdk_sorted_scalar_map_test.clj
@@ -0,0 +1,68 @@
+(ns otel.exporter.chdb-sdk-sorted-scalar-map-test
+  (:require [clojure.test :refer [deftest is run-tests]]
+            [otel.any-value :as any] [otel.exporter.chdb :as e]
+            [otel.exporter.chdb-sdk-scalar-map-test :refer [fixture oracle convert]]
+            [jolt.host :as host]))
+
+(defn sorted-fixture [n] (into (sorted-map) (fixture n)))
+
+(deftest source-owned-sorted-path-precedes-native-and-generic-fallbacks
+  (let [m (with-meta (sorted-fixture 16) {:source true}) expected (oracle m)]
+    (with-redefs [e/small-attribute-transform
+                  (delay (fn [_] (throw (ex-info "must-not-fallback" {}))))]
+      (let [actual (convert m)]
+        (is (= expected actual))
+        (is (= (type expected) (type actual)))
+        (is (= (vec expected) (vec actual)))
+        (is (nil? (meta actual)))
+        (is (= {:source true} (meta m)))))))
+
+(deftest unsupported-inputs-and-missing-helper-retain-normal-conversion
+  (doseq [m [(sorted-fixture 129) (assoc (sorted-fixture 16) "nil" nil)
+             (assoc (sorted-fixture 16) "float" 1.25)
+             (assoc (sorted-fixture 16) "nested" [1 false])
+             (sorted-map :alias 42) (assoc (sorted-fixture 9) "Aa" 1 "BB" 2)]]
+    (is (= (vec (oracle m)) (vec (convert m)))))
+  (with-redefs [e/sdk-sorted-scalar-map-converter (delay nil)]
+    (is (= (oracle (sorted-fixture 16)) (convert (sorted-fixture 16))))))
+
+(deftest custom-operations-are-not-tried-before-fallback
+  (let [m (sorted-fixture 16) calls (atom 0)
+        entries (vec m) ops (host/ref-get m :ops)]
+    (host/ref-put! m :ops (assoc ops :seq (fn [_] (swap! calls inc) (seq entries))))
+    (is (= (into {} (map (fn [[k v]] [k (any/try-scalar-string v)])) entries) (convert m)))
+    (is (= 1 @calls))))
+
+(deftest changed-exporter-and-sdk-policies-remain-live
+  (let [m (sorted-fixture 16) seen (atom [])]
+    (with-redefs [e/value-string (fn [v] (swap! seen conj v) "changed")]
+      (is (= (zipmap (keys m) (repeat "changed")) (convert m)))
+      (is (= (vec (vals m)) @seen)))
+    (reset! seen [])
+    (with-redefs [any/try-scalar-string (fn [v] (swap! seen conj v) "sdk-change")]
+      (is (= (zipmap (keys m) (repeat "sdk-change")) (convert m)))
+      (is (= (vec (vals m)) @seen)))
+    (with-redefs [e/key-string (fn [k] (str "new-" k))]
+      (is (= (oracle m) (convert m))))))
+
+(deftest late-policy-change-does-not-call-a-second-sdk-helper-or-accept-result
+  (let [m (sorted-fixture 16) sdk-var (ns-resolve 'otel.any-value 'try-sorted-scalar-string-map)
+        actual @sdk-var original @#'e/value-string seen (atom [])]
+    (with-redefs-fn
+      {#'e/value-string original
+       #'any/try-scalar-string-map (fn [_] (throw (ex-info "second-helper-must-not-run" {})))
+       sdk-var (fn [value]
+                 (let [result (actual value)]
+                   (alter-var-root #'e/value-string
+                                   (constantly (fn [v] (swap! seen conj v) "late")))
+                   result))}
+      #(do (is (= (zipmap (keys m) (repeat "late")) (convert m)))
+           (is (= (vec (vals m)) @seen))))))
+
+(defn -main [& _]
+  (let [sdk-var (ns-resolve 'otel.any-value 'try-sorted-scalar-string-map)]
+    (assert (and sdk-var (some? (@sdk-var (sorted-fixture 16))))
+            "strict gate requires the new SDK sorted-map capability"))
+  (let [r (run-tests 'otel.exporter.chdb-sdk-sorted-scalar-map-test)]
+    (assert (= 5 (:test r)))
+    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))

