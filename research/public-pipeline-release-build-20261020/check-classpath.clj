;; Babashka, invoked from app/. Validate actual selected source roots, not
;; merely the requested coordinates in deps.edn. Negative control uses a real
;; unpinned consumer resolution, not mocked metadata or a guessed dependency.
(require '[clojure.string :as str] '[clojure.java.io :as io])
(let [[file expected] *command-line-args*
      paths (mapv #(.getCanonicalPath (io/file %)) (str/split (str/trim (slurp file)) #":"))
      families [{:family "jolt-chdb.git/" :ends ["https___github.com_chucklehead-dev_jolt-chdb.git/3314b79e8a234fafdf5231439a0323c878a40040/src"
                                               "https___github.com_chucklehead-dev_jolt-chdb.git/3314b79e8a234fafdf5231439a0323c878a40040/resources"]}
                {:family "otel.git/" :ends ["https___github.com_casselc_otel.git/14f58e5a57d6cd1760451a426e40d8cddcf53949/src"
                                           "https___github.com_casselc_otel.git/14f58e5a57d6cd1760451a426e40d8cddcf53949/resources"]}
                {:family "jolt-crypto.git/" :ends ["https___github.com_jolt-lang_jolt-crypto.git/5effcc89a3258499a79a2a3d69edad9e7800d1bf/src"]}
                {:family "data.json.git/" :ends ["https___github.com_casselc_data.json.git/0f51b99101bc5e840f957c073f87b6f877309a25/src/main/clojure"]}]
      checks (mapv (fn [{:keys [family ends]}]
                     (let [actual (filterv #(str/includes? % family) paths)]
                       {:family family :actual actual
                        :ok (and (= (count actual) (count ends) (count (set actual)))
                                 (every? (fn [suffix] (= 1 (count (filter #(str/ends-with? % (str "/" suffix)) actual)))) ends))})) families)
      accepted (every? :ok checks)]
  (assert (contains? #{"accept" "reject"} expected))
  (assert (= (= expected "accept") accepted) "Real consumer resolution did not match expected verdict")
  (prn {:scope :consumer-resolved-roots :expected expected :accepted accepted
        :all-active-classpath-under-home (every? #(str/starts-with? % "/home/chuck/") paths)
        :checks checks}))
