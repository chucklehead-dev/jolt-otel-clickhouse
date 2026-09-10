(ns otel.exporter.chdb-dependency-test
  "Checks the resolved crypto implementation rather than rereading deps.edn."
  (:require [clojure.string :as str]
            [jolt.process :as process]))

(def ^:private canonical-root
  "https___github.com_jolt-lang_jolt-crypto.git/5effcc89a3258499a79a2a3d69edad9e7800d1bf/src")

(defn- crypto-roots [classpath]
  (->> (str/split (str classpath) #":")
       (filter #(str/includes? % "jolt-crypto"))
       vec))

(defn- exact-resolution? [classpath expected-root]
  (let [roots (crypto-roots classpath)]
    (and (= 1 (count roots))
         (str/includes? (first roots) expected-root))))

(defn run [check]
  (println "clean jolt-crypto dependency resolution")
  (let [child (process/process ["jolt" "-Srepro" "-Spath"]
                               {:out :string :err :string})
        result (deref child 60000 ::timeout)]
    (when (= ::timeout result)
      (try (process/destroy-tree child) (catch Throwable _ nil)))
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
               (exact-resolution? classpath canonical-root))
        (check "resolution check rejects a wrong repository URL"
               false
               (exact-resolution?
                classpath
                "https___github.com_casselc_jolt-crypto.git/5effcc89a3258499a79a2a3d69edad9e7800d1bf/src"))
        (check "resolution check rejects a wrong full SHA"
               false
               (exact-resolution?
                classpath
                "https___github.com_jolt-lang_jolt-crypto.git/8bd234142d56dd75d36d58065a311f29fa08611e/src"))))))
