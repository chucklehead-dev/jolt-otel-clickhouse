(require '[clojure.edn :as edn] '[cheshire.core :as json])
(with-open [r (clojure.java.io/reader (first *command-line-args*))]
  (let [n (reduce (fn [n line]
                    (let [{:keys [expected wire ascii-wire]} (edn/read-string line)]
                      (assert (= expected (json/parse-string wire)))
                      (assert (= expected (json/parse-string ascii-wire)))
                      (inc n))) 0 (line-seq r))]
    (assert (= 180 n))
    (println (pr-str {:independent-parser :babashka-native-cheshire :cases n :variants 2 :pass true}))))
