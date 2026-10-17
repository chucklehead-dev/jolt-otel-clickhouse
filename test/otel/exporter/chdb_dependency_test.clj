(ns otel.exporter.chdb-dependency-test
  "Checks resolved dependency implementations rather than rereading deps.edn."
  (:require [clojure.string :as str]
            [jolt.process :as process]))

(def ^:private canonical-root
  "https___github.com_jolt-lang_jolt-crypto.git/5effcc89a3258499a79a2a3d69edad9e7800d1bf/src")

(def ^:private otel-root
  "https___github.com_casselc_otel.git/14ffd741351373f07d96aa070b0f9853d7daf8ab/")

(def ^:private prior-otel-root
  "https___github.com_casselc_otel.git/8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2/")

(def ^:private prior-otel-coordinate
  "{:deps {io.github.casselc/otel {:git/url \"https://github.com/casselc/otel.git\" :git/sha \"8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2\" :exclusions [jolt-lang/jolt-crypto]}}}")

(def ^:private chdb-root
  "https___github.com_chucklehead-dev_jolt-chdb.git/3d766fa295b0e98c30f275d0a334db92e6dd998c/")

(def ^:private prior-chdb-root
  "https___github.com_chucklehead-dev_jolt-chdb.git/677219027d68702a18ee826b55ee1f2ccc3d172d/")

(def ^:private prior-chdb-coordinate
  "{:deps {io.github.chucklehead-dev/jolt-chdb {:git/url \"https://github.com/chucklehead-dev/jolt-chdb.git\" :git/sha \"677219027d68702a18ee826b55ee1f2ccc3d172d\"}}}")

(def ^:private data-json-source-root
  "https___github.com_casselc_data.json.git/0f51b99101bc5e840f957c073f87b6f877309a25/src/main/clojure")

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
  (let [fixture-root "https___github.com_casselc_otel.git/14ffd741351373f07d96aa070b0f9853d7daf8ab/"
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
                             "14ffd741351373f07d96aa070b0f9853d7daf8ab"
                             "8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2"))))
    (check "SDK before per-reader policy is rejected" false
           (boolean (oracle (str/replace source
                             "14ffd741351373f07d96aa070b0f9853d7daf8ab"
                             "ff8e2e4ab8fe0b8b3f8410cb202d47b1c29267a1"))))
    (check "SDK before definition-owned helper qualification is rejected" false
           (boolean (oracle (str/replace source
                             "14ffd741351373f07d96aa070b0f9853d7daf8ab"
                             "e8435b381ee0cafd7d1cb5cb7b8639c551544042"))))
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
                                      "0f51b99101bc5e840f957c073f87b6f877309a25"
                                      "932444043c0c06f9e295ba4963419b2481e9dd07"))))
    (check "data.json distinct checkout at same SHA is rejected" false
           (boolean (oracle (str source ":" root))))
    (check "data.json repeated source provider is rejected" false
           (boolean (oracle (str source ":" source))))
    (check "data.json missing provider is rejected" false (boolean (oracle "./test")))))

(defn- run-chdb-oracle-controls! [check]
  (let [fixture-root "https___github.com_chucklehead-dev_jolt-chdb.git/3d766fa295b0e98c30f275d0a334db92e6dd998c/"
        root (str "/public-fixture/a/" fixture-root)
        other (str "/public-fixture/b/" fixture-root)
        source (str root "src") resources (str root "resources")
        oracle #(boolean (exact-coordinate? % "chucklehead-dev_jolt-chdb.git" chdb-root))]
    (check "chDB source provider is accepted" true (oracle source))
    (check "chDB source/resources share one checkout" true
           (oracle (str source ":" resources)))
    (check "chDB prior revision is rejected" false
           (oracle (str/replace source
                                "3d766fa295b0e98c30f275d0a334db92e6dd998c"
                                "677219027d68702a18ee826b55ee1f2ccc3d172d")))
    (check "chDB previous root pin is rejected" false
           (oracle (str/replace source
                                "3d766fa295b0e98c30f275d0a334db92e6dd998c"
                                "af3a902be39fc466246b39864cdf77879032c87a")))
    (check "chDB duplicate source provider is rejected" false
           (oracle (str source ":" source)))
    (check "chDB before prefixed encoder API is rejected" false
           (oracle (str/replace source
                                "3d766fa295b0e98c30f275d0a334db92e6dd998c"
                                "bad991ad191ffb57d90ff86795ccbeb3992bf186")))
    (check "chDB distinct checkout at same SHA is rejected" false
           (oracle (str source ":" other "resources")))
    (check "chDB unexpected checkout child is rejected" false (oracle (str root "test")))
    (check "chDB missing provider is rejected" false (oracle "./test"))))

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
  (run-chdb-oracle-controls! check)
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
               (exact-concrete-root? classpath "casselc_data.json.git" data-json-source-root))
        (check "chDB resolves once from the reviewed full SHA"
               true
               (exact-coordinate? classpath "chucklehead-dev_jolt-chdb.git" chdb-root))))
    (let [prior (dependency-report ["-Sdeps" prior-otel-coordinate])]
      (check "prior SDK dependency report completes" true
             (and (map? prior) (zero? (:exit prior))))
      (when (map? prior)
        (check "prior SDK mutation selects the real old checkout" true
               (exact-coordinate? (:out prior) "casselc_otel.git" prior-otel-root))
        (check "current SDK oracle rejects the prior checkout" false
               (exact-coordinate? (:out prior) "casselc_otel.git" otel-root))))
    (let [prior (dependency-report ["-Sdeps" prior-chdb-coordinate])]
      (check "prior chDB dependency report completes" true
             (and (map? prior) (zero? (:exit prior))))
      (when (map? prior)
        (check "prior chDB mutation selects the real old checkout" true
               (exact-coordinate? (:out prior) "chucklehead-dev_jolt-chdb.git" prior-chdb-root))
        (check "current chDB oracle rejects the prior checkout" false
               (exact-coordinate? (:out prior) "chucklehead-dev_jolt-chdb.git" chdb-root))))
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
