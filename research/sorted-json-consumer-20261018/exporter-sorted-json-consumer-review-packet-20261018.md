Read-only review, INLINE SOURCE ONLY; no tools/edits/publication/credentials/data. Target exact owned exporter commit a5e4eb027fb32423b786c968adbc205f6c58badb based5a7617a. Review just pin/CI/tests/changelog. Native alias moves JSON8d->a18ae06; ordinary0f JSON dependency unchanged, SDK/driver unchanged. New test uses actual SDK provider/ended span records with nonempty sorted event/resource attributes; compares named+compact exact full bytes against ordinary configured JSON, checks scalar values/Unicode and exact newline-inclusive budget with prefix excluded. Pin oracle updated currenta18/prior8d, its actual sole-source-resolution checks unchanged. Focused2/12, wider native attr/projection47/940 pass. JSON a18 source loop separately reviewed no defects under default-options and existing serial cleanup assumptions; full nativeCI green; independent older-stack Durable reader verified175k physical rows with exact event ticks. Source excerpt below is complete commit. Return <=400words concrete regressions or insufficient scope claims, or no findings. Tests are semantic-equivalence tests not expected red on previous compatible JSON pin; library has separate positive-path mutant and this dependency gate rejects prior resolved source. Don't substitute a performance timing threshold for that split.

commit a5e4eb027fb32423b786c968adbc205f6c58badb
Author:     Chuck Cassel <619504+casselc@users.noreply.github.com>
AuthorDate: Sun Oct 18 07:27:03 2026 -0700
Commit:     Chuck Cassel <619504+casselc@users.noreply.github.com>
CommitDate: Sun Oct 18 07:27:03 2026 -0700

    perf: qualify sorted-event native JSON dependency

 .github/workflows/tests.yml                        |  1 +
 CHANGELOG.md                                       |  6 ++
 deps.edn                                           |  7 ++-
 test/otel/exporter/chdb_dependency_test.clj        |  6 +-
 .../exporter/chdb_sorted_event_encoding_test.clj   | 65 ++++++++++++++++++++++
 5 files changed, 80 insertions(+), 5 deletions(-)
diff --git a/.github/workflows/tests.yml b/.github/workflows/tests.yml
index fce9446..e4717ea 100644
--- a/.github/workflows/tests.yml
+++ b/.github/workflows/tests.yml
@@ -49,6 +49,7 @@ jobs:
           rm -f -- "$RUNNER_TEMP/projection-smoke.edn"
           "$task_bin" -Srepro -M:native-json-byte-batch:native-attributes-test --require-string-output
           "$task_bin" -Srepro -M:sdk-scalar-map-test
+          "$task_bin" -Srepro -M:native-json-byte-batch:sorted-event-encoding-test
           "$task_bin" -Srepro -M:native-json-byte-batch:owned-output-test
 
   scoped-s3-sdk:
diff --git a/CHANGELOG.md b/CHANGELOG.md
index aec3d7a..a1382a4 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -2,6 +2,12 @@
 
 ## Unreleased
 
+- Advance the optional native JSON pin to retain byte emission for sorted
+  event attributes while honoring live map sequence operations and JSONWriter
+  callbacks. Add actual SDK-ended span/event coverage for named and compact
+  rows, Unicode, Boolean values and exact payload limits. Ordinary JSON,
+  schema/driver/SDK pins and Durable acknowledgement semantics are unchanged.
+
 - Use the SDK's optional bounded scalar-map conversion for native attribute
   shaping while original exporter converters remain installed. Older SDKs,
   unsupported values and changed policies retain the existing conversion;
diff --git a/deps.edn b/deps.edn
index a6c1a04..97be58e 100644
--- a/deps.edn
+++ b/deps.edn
@@ -19,7 +19,10 @@
         org.clojure/data.json
         {:git/url "https://github.com/casselc/data.json.git"
          :git/sha "0f51b99101bc5e840f957c073f87b6f877309a25"}}
- :aliases {:sdk-scalar-map-test
+ :aliases {:sorted-event-encoding-test
+           {:extra-paths ["test"]
+            :main-opts ["-m" "otel.exporter.chdb-sorted-event-encoding-test"]}
+           :sdk-scalar-map-test
            {:extra-paths ["test"]
             :main-opts ["-m" "otel.exporter.chdb-sdk-scalar-map-test"]}
            :native-attributes-test
@@ -58,7 +61,7 @@
            :native-json-byte-batch
            {:extra-deps {org.clojure/data.json
                          {:git/url "https://github.com/casselc/data.json.git"
-                          :git/sha "8d557cc18eb9526ed29ae5613089c3793d5973fa"}}}
+                          :git/sha "a18ae06c787e6e03709bd78f1b92911130a613f1"}}}
            :benchmark {:extra-paths ["bench"]
                        :main-opts ["-m" "otel.exporter.chdb-benchmark"]}
            :typed-log-test
diff --git a/test/otel/exporter/chdb_dependency_test.clj b/test/otel/exporter/chdb_dependency_test.clj
index 92d3080..0cebe0c 100644
--- a/test/otel/exporter/chdb_dependency_test.clj
+++ b/test/otel/exporter/chdb_dependency_test.clj
@@ -28,7 +28,7 @@
   "https___github.com_casselc_data.json.git/0f51b99101bc5e840f957c073f87b6f877309a25/src/main/clojure")
 
 (def ^:private native-data-json-source-root
-  "https___github.com_casselc_data.json.git/8d557cc18eb9526ed29ae5613089c3793d5973fa/src/main/clojure")
+  "https___github.com_casselc_data.json.git/a18ae06c787e6e03709bd78f1b92911130a613f1/src/main/clojure")
 
 (def ^:private wrong-coordinate
   "{:deps {jolt-lang/jolt-crypto {:git/url \"https://github.com/casselc/jolt-crypto.git\" :git/sha \"8bd234142d56dd75d36d58065a311f29fa08611e\"}}}")
@@ -215,8 +215,8 @@
     ;; This guards selection of the new checkpoint, not ABI compatibility:
     ;; the old revision remains a valid native backend when chosen explicitly.
     (check "native byte alias coordinate oracle rejects prior pin" false
-           (oracle (str/replace source "8d557cc18eb9526ed29ae5613089c3793d5973fa"
-                                "a0172a0f1418632c760af98ef5280dd234d21e85")))
+           (oracle (str/replace source "a18ae06c787e6e03709bd78f1b92911130a613f1"
+                                "8d557cc18eb9526ed29ae5613089c3793d5973fa")))
     (check "native byte alias resolution completes without warnings" true
            (and (map? result) (zero? (:exit result))
                 (str/blank? (str (:err result)))))
diff --git a/test/otel/exporter/chdb_sorted_event_encoding_test.clj b/test/otel/exporter/chdb_sorted_event_encoding_test.clj
new file mode 100644
index 0000000..3a1bd12
--- /dev/null
+++ b/test/otel/exporter/chdb_sorted_event_encoding_test.clj
@@ -0,0 +1,65 @@
+(ns otel.exporter.chdb-sorted-event-encoding-test
+  (:require [clojure.test :refer [deftest is run-tests]]
+            [clojure.string :as str]
+            [clojure.data.json :as json]
+            [clojure.data.json.jolt-native :as native]
+            [otel.exporter.chdb :as e]
+            [otel.sdk.tracer :as sdk] [otel.sdk.export :as export]
+            [otel.exporter.memory :as memory] [otel.resource :as resource]
+            [otel.trace :as trace]))
+
+(defn ended-spans []
+  (let [attrs (into {"service.name" "sorted-event-test" "flag" false "count" 42}
+                    (map (fn [i] [(str "extra-" i)
+                                  (case (mod i 3) 0 "λ\n\"😀" 1 false i)]) (range 14)))
+        sink (memory/exporter)
+        provider (sdk/tracer-provider {:resource (resource/resource attrs)
+                                       :processors [(export/simple-processor sink)]})
+        tracer (sdk/get-tracer provider {:name "sorted-event-test" :attributes attrs})]
+    (try
+      (dotimes [i 2]
+        (trace/with-span [span tracer (str "scenario-" i) {:attributes attrs}]
+          (trace/add-event! span "event" attrs)))
+      (finally (assert (sdk/shutdown! provider))))
+    (memory/spans sink)))
+
+(defn stock-payload [spans compact?]
+  (with-bindings {#'e/*json-backend* :configured #'e/*timestamp-wire* :raw-ticks}
+    (apply str (map #(str (json/write-str (@#'e/span-row % nil compact?)) "\n") spans))))
+
+(defn native-payload [spans compact? prefix budget]
+  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch #'e/*timestamp-wire* :raw-ticks}
+    (native/write-prefixed-batch-bytes!
+      prefix (map #(@#'e/span-row % nil compact?) spans) budget)))
+
+(deftest actual-sdk-sorted-events-have-identical-named-and-compact-bytes
+  (let [spans (ended-spans)]
+    (is (= 2 (count spans)))
+    (is (every? #(sorted? (get-in % [:resource :attributes])) spans))
+    (is (every? #(sorted? (get-in % [:events 0 :attributes])) spans))
+    (doseq [compact? [false true]]
+      (let [expected (stock-payload spans compact?)
+            actual (String. (native-payload spans compact? "" 100000) "UTF-8")]
+        (is (= expected actual))
+        (is (= 2 (count (str/split-lines actual))))))
+    (let [row (json/read-str (first (str/split-lines
+                                    (String. (native-payload spans false "" 100000) "UTF-8"))))
+          event (first (json/read-str (get row "EventsJSON")))]
+      (is (false? (get-in event ["attributes" "flag"])))
+      (is (= 42 (get-in event ["attributes" "count"])))
+      (is (= "λ\n\"😀" (get-in event ["attributes" "extra-0"]))))))
+
+(deftest event-rich-output-honors-exact-newline-inclusive-byte-limit
+  (let [spans (ended-spans) prefix "prefix-not-in-budget "
+        expected (stock-payload spans true)
+        budget (alength (.getBytes expected "UTF-8"))]
+    (is (= (str prefix expected)
+           (String. (native-payload spans true prefix budget) "UTF-8")))
+    (is (= ::native/output-limit
+           (:type (ex-data (try (native-payload spans true prefix (dec budget))
+                               nil (catch Throwable error error))))))))
+
+(defn -main [& _]
+  (let [r (run-tests 'otel.exporter.chdb-sorted-event-encoding-test)]
+    (assert (and (= 2 (:test r)) (>= (:pass r) 12)))
+    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))

