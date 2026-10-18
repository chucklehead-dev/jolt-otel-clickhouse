Read-only bounded review, maximum 250 words. All source is included below: no tools, commands, edits, delegation or publication. Review only exporter pin/CI delta e23d535..5a7617a. Initial exporter e23d535 and SDK697f58b already received completed source reviews with no concrete functional defects remaining. SDK exact-head hosted CI39128818354 succeeded, including stock Jolt0.8.23 native5/66 and AnyValue17/388 plus existing aggregate. This delta activates the reviewed SDK by exact public Git SHA, adds strict native exporter4/20 gate to the existing qualified-runtime CI, and retargets dependency provenance controls to697f58b while preserving earlier negatives and adding immediately prior37c684a2. Old-coordinate rejection means not our qualified pin, not ABI incompatibility: runtime capability lookup still permits old SDK fallback. Local pin-focused4/20 passes without local root override. Maintained aggregate printed all checks passed, but its terminal handle disappeared; no exit0 receipt credited. Hosted exporter CI39129795124 is running at exact5a7617a. Check only for pin mismatch, lost controls, malformed alias/CI step, accidental behavior changes. No performance/model/floor change is claimed from this delta. Report concrete issues or none.

```diff
diff --git a/.github/workflows/tests.yml b/.github/workflows/tests.yml
index b635e39..fce9446 100644
--- a/.github/workflows/tests.yml
+++ b/.github/workflows/tests.yml
@@ -48,6 +48,7 @@ jobs:
           "$task_bin" -Srepro -A:native-projection-test -m otel.exporter.chdb-native-projection-benchmark "$RUNNER_TEMP/projection-smoke.edn" 100 2
           rm -f -- "$RUNNER_TEMP/projection-smoke.edn"
           "$task_bin" -Srepro -M:native-json-byte-batch:native-attributes-test --require-string-output
+          "$task_bin" -Srepro -M:sdk-scalar-map-test
           "$task_bin" -Srepro -M:native-json-byte-batch:owned-output-test
 
   scoped-s3-sdk:
diff --git a/deps.edn b/deps.edn
index a0c71f1..a6c1a04 100644
--- a/deps.edn
+++ b/deps.edn
@@ -5,8 +5,9 @@
          :git/sha "3d766fa295b0e98c30f275d0a334db92e6dd998c"}
         io.github.casselc/otel
         {:git/url "https://github.com/casselc/otel.git"
-         :git/sha "37c684a2c370fc82fde82331635973bce87a52b3"
-         ;; Includes ready-batch scheduling and batch<=queue validation.
+         :git/sha "697f58bd5b4925f72339694bedbace97bdc869bb"
+         ;; Includes SDK-owned scalar-map conversion, ready-batch scheduling
+         ;; and batch<=queue validation.
          ;; Keep its canonical crypto declaration excluded and select it
          ;; directly below so this exporter owns the exact provider and SHA,
          ;; independent of dependency traversal order.
@@ -18,7 +19,10 @@
         org.clojure/data.json
         {:git/url "https://github.com/casselc/data.json.git"
          :git/sha "0f51b99101bc5e840f957c073f87b6f877309a25"}}
- :aliases {:native-attributes-test
+ :aliases {:sdk-scalar-map-test
+           {:extra-paths ["test"]
+            :main-opts ["-m" "otel.exporter.chdb-sdk-scalar-map-test"]}
+           :native-attributes-test
            {:extra-paths ["test" "bench"]
             :main-opts ["-m" "otel.exporter.chdb-native-attributes-test"]}
            :native-projection-test
diff --git a/test/otel/exporter/chdb_dependency_test.clj b/test/otel/exporter/chdb_dependency_test.clj
index 52fb899..92d3080 100644
--- a/test/otel/exporter/chdb_dependency_test.clj
+++ b/test/otel/exporter/chdb_dependency_test.clj
@@ -7,13 +7,13 @@
   "https___github.com_jolt-lang_jolt-crypto.git/5effcc89a3258499a79a2a3d69edad9e7800d1bf/src")
 
 (def ^:private otel-root
-  "https___github.com_casselc_otel.git/37c684a2c370fc82fde82331635973bce87a52b3/")
+  "https___github.com_casselc_otel.git/697f58bd5b4925f72339694bedbace97bdc869bb/")
 
 (def ^:private prior-otel-root
-  "https___github.com_casselc_otel.git/d93846e40b837dcecb546fb59f6cb05ca639f054/")
+  "https___github.com_casselc_otel.git/37c684a2c370fc82fde82331635973bce87a52b3/")
 
 (def ^:private prior-otel-coordinate
-  "{:deps {io.github.casselc/otel {:git/url \"https://github.com/casselc/otel.git\" :git/sha \"d93846e40b837dcecb546fb59f6cb05ca639f054\" :exclusions [jolt-lang/jolt-crypto]}}}")
+  "{:deps {io.github.casselc/otel {:git/url \"https://github.com/casselc/otel.git\" :git/sha \"37c684a2c370fc82fde82331635973bce87a52b3\" :exclusions [jolt-lang/jolt-crypto]}}}")
 
 (def ^:private chdb-root
   "https___github.com_chucklehead-dev_jolt-chdb.git/3d766fa295b0e98c30f275d0a334db92e6dd998c/")
@@ -96,7 +96,7 @@
 
 (defn run-oracle-controls! [check]
   ;; Freeze the approved witness independently of the implementation oracle.
-  (let [fixture-root "https___github.com_casselc_otel.git/37c684a2c370fc82fde82331635973bce87a52b3/"
+  (let [fixture-root "https___github.com_casselc_otel.git/697f58bd5b4925f72339694bedbace97bdc869bb/"
         root (str "/public-fixture/a/" fixture-root)
         other (str "/public-fixture/b/" fixture-root)
         source (str root "src")
@@ -109,23 +109,27 @@
            (boolean (oracle (str source ":" root "src/../resources"))))
     (check "SDK old reviewed SHA is rejected" false
            (boolean (oracle (str/replace source
-                             "37c684a2c370fc82fde82331635973bce87a52b3"
+                             "697f58bd5b4925f72339694bedbace97bdc869bb"
                              "8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2"))))
+    (check "SDK before owned scalar-map capability is rejected" false
+           (boolean (oracle (str/replace source
+                             "697f58bd5b4925f72339694bedbace97bdc869bb"
+                             "37c684a2c370fc82fde82331635973bce87a52b3"))))
     (check "SDK before batch-ready wakeup is rejected" false
            (boolean (oracle (str/replace source
-                             "37c684a2c370fc82fde82331635973bce87a52b3"
+                             "697f58bd5b4925f72339694bedbace97bdc869bb"
                              "d93846e40b837dcecb546fb59f6cb05ca639f054"))))
     (check "SDK before per-reader policy is rejected" false
            (boolean (oracle (str/replace source
-                             "37c684a2c370fc82fde82331635973bce87a52b3"
+                             "697f58bd5b4925f72339694bedbace97bdc869bb"
                              "ff8e2e4ab8fe0b8b3f8410cb202d47b1c29267a1"))))
     (check "SDK before definition-owned helper qualification is rejected" false
            (boolean (oracle (str/replace source
-                             "37c684a2c370fc82fde82331635973bce87a52b3"
+                             "697f58bd5b4925f72339694bedbace97bdc869bb"
                              "e8435b381ee0cafd7d1cb5cb7b8639c551544042"))))
     (check "SDK before bounded string admission is rejected" false
            (boolean (oracle (str/replace source
-                             "37c684a2c370fc82fde82331635973bce87a52b3"
+                             "697f58bd5b4925f72339694bedbace97bdc869bb"
                              "14ffd741351373f07d96aa070b0f9853d7daf8ab"))))
     (check "SDK distinct checkout at same SHA is rejected" false
            (boolean (oracle (str source ":" other "src"))))
```
