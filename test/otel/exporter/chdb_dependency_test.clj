(ns otel.exporter.chdb-dependency-test
  "Checks resolved dependency implementations rather than rereading deps.edn."
  (:require [clojure.string :as str]
            [jolt.process :as process]))

(def ^:private canonical-root
  "https___github.com_jolt-lang_jolt-crypto.git/5effcc89a3258499a79a2a3d69edad9e7800d1bf/src")

(def ^:private otel-root
  "https___github.com_casselc_otel.git/0e701ceff526d159884fadae98dcca61272ef6e0/")

(def ^:private data-json-source-root
  "https___github.com_casselc_data.json.git/97298fd8a67a6d4ee3eb1346d5e184beb9565b90/src/main/clojure")

(def ^:private wrong-coordinate
  "{:deps {jolt-lang/jolt-crypto {:git/url \"https://github.com/casselc/jolt-crypto.git\" :git/sha \"8bd234142d56dd75d36d58065a311f29fa08611e\"}}}")

(def ^:private wrong-root
  "https___github.com_casselc_jolt-crypto.git/8bd234142d56dd75d36d58065a311f29fa08611e/src")

(def ^:private wrong-data-json-coordinate
  "{:deps {org.clojure/data.json {:git/url \"https://github.com/casselc/data.json.git\" :git/sha \"932444043c0c06f9e295ba4963419b2481e9dd07\"}}}")

(def ^:private wrong-data-json-source-root
  "https___github.com_casselc_data.json.git/932444043c0c06f9e295ba4963419b2481e9dd07/src/main/clojure")

(defn- dependency-roots [classpath dependency]
  (->> (str/split (str classpath) #":")
       (filter #(str/includes? % dependency))
       vec))

(defn- exact-resolution? [classpath dependency expected-root]
  (let [roots (dependency-roots classpath dependency)]
    (and (= 1 (count roots))
         (str/includes? (first roots) expected-root))))

(defn- exact-concrete-root? [classpath dependency expected-root]
  ;; Compare the canonical concrete source root. A matching SHA anywhere in a
  ;; classpath entry is insufficient: data.json's declared root is
  ;; src/main/clojure and it must be the sole provider selected by the resolver.
  (try
    (let [roots (mapv #(.getCanonicalPath (java.io.File. %))
                      (dependency-roots classpath dependency))]
      (and (= 1 (count roots))
           (= 1 (count (set roots)))
           (str/ends-with? (first roots) (str "/" expected-root))))
    (catch Exception _ false)))

(defn- exact-coordinate? [classpath dependency expected-root]
  ;; SDK src and resources are one provider only when they share the same
  ;; canonical checkout. This is a bounded SDK oracle, not full-graph uniqueness.
  (try
    (let [roots (mapv #(.getCanonicalPath (java.io.File. %))
                      (dependency-roots classpath dependency))
          checkouts (mapv (fn [path]
                            (when (or (str/ends-with? path "/src")
                                      (str/ends-with? path "/resources"))
                              (.getParent (java.io.File. path)))) roots)]
      (and (seq roots)
           (= (count roots) (count (set roots)))
           (every? some? checkouts)
           (= 1 (count (set checkouts)))
           (every? #(str/ends-with? (str % "/") (str "/" expected-root))
                   checkouts)))
    (catch Exception _ false)))

(defn- child-test-executable
  ([] (child-test-executable (System/getenv "JOLT_TEST_CHILD_EXECUTABLE")))
  ([selected]
   (if (nil? selected)
     "jolt"
     (do
       (when-not (and (not (str/blank? selected))
                      (.isAbsolute (java.io.File. selected)))
         (throw (ex-info "Invalid dependency test executable"
                         {:type ::invalid-child-test-executable})))
       selected))))

(defn run-oracle-controls! [check]
  ;; Freeze the approved witness independently of the implementation oracle.
  (let [fixture-root "https___github.com_casselc_otel.git/0e701ceff526d159884fadae98dcca61272ef6e0/"
        root (str "/public-fixture/a/" fixture-root)
        other (str "/public-fixture/b/" fixture-root)
        source (str root "src")
        resources (str root "resources")
        oracle #(exact-coordinate? % "casselc_otel.git" otel-root)]
    (check "SDK source provider is accepted" true (boolean (oracle source)))
    (check "SDK src/resources share one checkout" true
           (boolean (oracle (str source ":" resources))))
    (check "SDK canonical aliases share one checkout" true
           (boolean (oracle (str source ":" root "src/../resources"))))
    (check "SDK old reviewed SHA is rejected" false
           (boolean (oracle (str/replace source
                             "0e701ceff526d159884fadae98dcca61272ef6e0"
                             "87d3ac1a9b26ec6c0bf0c44d3b5aff4c66ccb5a0"))))
    (check "SDK distinct checkout at same SHA is rejected" false
           (boolean (oracle (str source ":" other "src"))))
    (check "SDK repeated source provider is rejected" false
           (boolean (oracle (str source ":" source))))
    (check "SDK unexpected checkout child is rejected" false
           (boolean (oracle (str root "test"))))
    (check "SDK missing provider is rejected" false (boolean (oracle "./test")))
    (check "ordinary users retain child executable fallback" "jolt"
           (child-test-executable nil))
    (check "explicit absolute child executable is retained" "/public-fixture/jolt"
           (child-test-executable "/public-fixture/jolt"))
    (doseq [selected ["jolt" "" " "]]
      (check "relative or blank selected child executable is rejected" true
             (try (child-test-executable selected) false
                  (catch Exception _ true))))))

(defn- run-data-json-oracle-controls! [check]
  ;; The JSON provider has source only. Its root must still be singular and
  ;; exact: this is a classpath receipt, not a declaration-only assertion.
  (let [fixture-root (str "/public-fixture/a/" data-json-source-root)
        root (str "/public-fixture/b/" data-json-source-root)
        source fixture-root
        oracle #(exact-concrete-root? % "casselc_data.json.git" data-json-source-root)]
    (check "data.json source provider is accepted" true (boolean (oracle source)))
    (check "data.json canonical source root is accepted" true
           (boolean (oracle (str fixture-root "/../clojure"))))
    (check "data.json old SHA is rejected" false
           (boolean (oracle (str/replace source
                                      "97298fd8a67a6d4ee3eb1346d5e184beb9565b90"
                                      "932444043c0c06f9e295ba4963419b2481e9dd07"))))
    (check "data.json distinct checkout at same SHA is rejected" false
           (boolean (oracle (str source ":" root))))
    (check "data.json repeated source provider is rejected" false
           (boolean (oracle (str source ":" source))))
    (check "data.json missing provider is rejected" false (boolean (oracle "./test")))))

(defn- dependency-report [extra-args]
  (let [child (process/process (into [(child-test-executable) "-Srepro"]
                                     (concat extra-args ["-Spath"]))
                               {:out :string :err :string})
        result (deref child 60000 ::timeout)]
    (when (= ::timeout result)
      (try (process/destroy-tree child) (catch Throwable _ nil)))
    result))

(defn run [check]
  (run-oracle-controls! check)
  (run-data-json-oracle-controls! check)
  (println "clean jolt-crypto dependency resolution")
  (let [result (dependency-report [])]
    (check "dependency report completes"
           true
           (and (map? result) (zero? (:exit result))))
    (check "dependency report has no resolution warning"
           true
           (and (map? result) (str/blank? (str (:err result)))))
    (when (map? result)
      (let [classpath (:out result)]
        (check "crypto resolves once from canonical upstream at the full SHA"
               true
               (exact-resolution? classpath "jolt-crypto" canonical-root))
        (check "OTel resolves once from casselc/otel at the reviewed full SHA"
               true
               (exact-coordinate? classpath "casselc_otel.git" otel-root))
        (check "data.json resolves once from casselc/data.json at the reviewed full SHA"
               true
               (exact-concrete-root? classpath "casselc_data.json.git" data-json-source-root))))
    (let [wrong (dependency-report ["-Sdeps" wrong-coordinate])]
      (check "wrong-coordinate dependency report completes"
             true
             (and (map? wrong) (zero? (:exit wrong))))
      (when (map? wrong)
        (check "mutation resolves exactly from the wrong repository and SHA"
               true
               (exact-resolution? (:out wrong) "jolt-crypto" wrong-root))
        (check "canonical oracle rejects the real wrong-coordinate resolution"
               false
               (exact-resolution? (:out wrong) "jolt-crypto" canonical-root))))
    (let [wrong (dependency-report ["-Sdeps" wrong-data-json-coordinate])]
      (check "wrong-SHA data.json dependency report completes"
             true
             (and (map? wrong) (zero? (:exit wrong))))
      (when (map? wrong)
        (check "wrong-SHA mutation selects one old data.json checkout"
               true
               (exact-concrete-root? (:out wrong) "casselc_data.json.git" wrong-data-json-source-root))
        (check "current data.json oracle rejects the real wrong-SHA resolution"
               false
               (exact-concrete-root? (:out wrong) "casselc_data.json.git" data-json-source-root))))))
