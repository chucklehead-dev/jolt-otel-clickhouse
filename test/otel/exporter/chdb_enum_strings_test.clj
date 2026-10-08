(ns otel.exporter.chdb-enum-strings-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [otel.exporter.chdb :as exporter]))

(deftest standard-keyword-enums-match-collector-spelling
  (doseq [[input expected] [[:unspecified "Unspecified"] [:internal "Internal"]
                           [:server "Server"] [:client "Client"]
                           [:producer "Producer"] [:consumer "Consumer"]
                           [:unset "Unset"] [:ok "Ok"] [:error "Error"]]]
    (is (= expected (#'exporter/otel-enum-string input :unset)))))

(deftest fallback-and-nonstandard-inputs-retain-original-conversion
  (doseq [fallback [:unset :internal :custom/fallback "FALLBACK"]
          input [nil false :custom/server :SERVER :custom-value "SERVER" ""
                 'server 'custom/OK :ok :error]]
    (is (= (str/capitalize (name (or input fallback)))
           (#'exporter/otel-enum-string input fallback)))))

(deftest invalid-inputs-still-fail
  (doseq [input [true 1 [] {}]]
    (is (instance? Throwable
                   (try (#'exporter/otel-enum-string input :unset)
                        nil (catch Throwable error error))))))

(defn -main [& _]
  (let [r (run-tests 'otel.exporter.chdb-enum-strings-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
