(ns otel.exporter.chdb-dependency-test
  "Checks the resolved crypto implementation rather than rereading deps.edn."
  (:require [clojure.string :as str]
            [jolt.process :as process]))

(def ^:private canonical-root
  "https___github.com_jolt-lang_jolt-crypto.git/5effcc89a3258499a79a2a3d69edad9e7800d1bf/src")

(def ^:private wrong-coordinate
  "{:deps {jolt-lang/jolt-crypto {:git/url \"https://github.com/casselc/jolt-crypto.git\" :git/sha \"8bd234142d56dd75d36d58065a311f29fa08611e\"}}}")

(def ^:private wrong-root
  "https___github.com_casselc_jolt-crypto.git/8bd234142d56dd75d36d58065a311f29fa08611e/src")

(defn- crypto-roots [classpath]
  (->> (str/split (str classpath) #":")
       (filter #(str/includes? % "jolt-crypto"))
       vec))

(defn- exact-resolution? [classpath expected-root]
  (let [roots (crypto-roots classpath)]
    (and (= 1 (count roots))
         (str/includes? (first roots) expected-root))))

(defn- dependency-report [extra-args]
  (let [child (process/process (into ["jolt" "-Srepro"]
                                     (concat extra-args ["-Spath"]))
                               {:out :string :err :string})
        result (deref child 60000 ::timeout)]
    (when (= ::timeout result)
      (try (process/destroy-tree child) (catch Throwable _ nil)))
    result))

(defn run [check]
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
               (exact-resolution? classpath canonical-root))))
    (let [wrong (dependency-report ["-Sdeps" wrong-coordinate])]
      (check "wrong-coordinate dependency report completes"
             true
             (and (map? wrong) (zero? (:exit wrong))))
      (when (map? wrong)
        (check "mutation resolves exactly from the wrong repository and SHA"
               true
               (exact-resolution? (:out wrong) wrong-root))
        (check "canonical oracle rejects the real wrong-coordinate resolution"
               false
               (exact-resolution? (:out wrong) canonical-root))))))
