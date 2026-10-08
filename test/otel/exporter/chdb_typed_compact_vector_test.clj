(ns otel.exporter.chdb-typed-compact-vector-test
  (:require [clojure.test :refer [deftest is]]
            [otel.sdk.export :as sdk]
            [otel.exporter.chdb :as e]
            [otel.exporter.chdb.attribute-projection :as p]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb-attribute-projection-test :as fixture]
            [otel.exporter.chdb-compact-span-layout-test :as spans]
            [otel.exporter.chdb-compact-wire-test :as wire]))

(defn plans [mixed?]
  (let [target (Object.)
        installation (if mixed?
                       (@#'fixture/installed (@#'fixture/mixed-compiled) target)
                       (@#'fixture/installed target))
        capability (:descriptor-set installation)
        fields (p/confirmed-span-fields capability target)
        columns (into spans/columns
                      (mapcat #(vector (get-in % [:physical :value-column])
                                       (get-in % [:physical :status-column])) fields))]
    {:target target :capability capability :columns columns
     :map-projector (p/trace-projector capability target)
     :vector-projector (p/trace-vector-projector capability target)}))

(deftest vector-projection-retains-values-statuses-and-location-isolation
  (doseq [mixed? [false true]]
    (let [{:keys [columns map-projector vector-projector]} (plans mixed?)]
      (doseq [attributes [{} {"checkout.name" "" "checkout.complete" false "checkout.count" 9007199254740993}
                          {"checkout.name" nil "checkout.complete" 1 "checkout.count" 9223372036854775808N}
                          (array-map :checkout.count 7 "checkout.count" 8)
                          {"shared.location" "span"}]]
        (let [span (assoc (@#'fixture/span attributes)
                         :resource {:attributes {"shared.location" "resource"}}
                         :scope {:attributes {"shared.location" ""}})
              expected (mapv (map-projector span) (subvec columns (count spans/columns)))]
          (is (= expected (vector-projector span))))))))

(deftest vector-capability-is-target-fenced
  (let [{:keys [capability]} (plans false)]
    (is (= :otel.exporter.chdb.attribute-projection/target-mismatch
           (:type (wire/error-data #(p/trace-vector-projector capability (Object.))))))))

(defn state-for [plan]
  (let [columns (:columns plan)]
    (atom {:durable? true :insert-format :json-compact-each-row
           :typed-span-projector (:map-projector plan)
           :typed-span-vector-plan (select-keys plan [:columns :map-projector :vector-projector])
           :span-insert-columns columns
           :compact-plans {"otel_traces" (wire/confirmed-plan :connection "otel_traces" columns)}})))

(deftest direct-typed-vector-sql-matches-generic-and-retains-callback-order
  (let [{:keys [columns map-projector] :as plan} (plans false)
        records [(assoc spans/base-span :attributes {"checkout.count" 5 "checkout.name" "é😀\n/"}
                                       :events [{:timestamp-unix-nano 3 :attributes {:x [1 true]}}]
                                       :links [{:attributes {:x 4}}])]
        build (fn [direct?]
                (let [state (state-for plan) sql (atom []) effects (atom [])
                      old-attrs @#'e/attrs old-key @#'p/key-string]
                  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch}
                    (with-redefs [e/execute-durable-sql! (fn [_ text] (swap! sql conj text))
                                  e/attrs (fn [source] (swap! effects conj [:attrs source]) (old-attrs source))
                                  p/key-string (fn [key] (swap! effects conj [:typed-key key]) (old-key key))]
                      (if direct?
                        (@#'e/insert-direct-typed-span-batch! :connection state records)
                        (@#'e/insert-batch! :connection state "otel_traces" columns "insert into otel_traces"
                                           (map #(@#'e/span-row % map-projector) records)))))
                  [@sql @effects]))]
    (is (= (build false) (build true)))))

(deftest changed-plan-is-rejected-before-execution
  (let [plan (plans false) state (state-for plan) calls (atom 0)
        vector-projector (:vector-projector plan)]
    (swap! state assoc-in [:typed-span-vector-plan :vector-projector]
           (fn [span]
             (swap! state assoc :typed-span-projector identity)
             (vector-projector span)))
    (with-bindings {#'e/*json-backend* :native-guarded-byte-batch}
      (with-redefs [e/execute-durable-sql! (fn [& _] (swap! calls inc))]
        (is (= :otel.exporter.chdb/invalid-direct-typed-span-plan
               (:type (wire/error-data #(@#'e/insert-direct-typed-span-batch!
                                         :connection state [(@#'fixture/span {})])))))))
    (is (zero? @calls))))

(deftest typed-selection-is-bound-to-base-order-and-live-projector
  (let [plan (plans false) state (state-for plan) snapshot @state]
    (with-bindings {#'e/*json-backend* :native-guarded-byte-batch}
      (is (@#'e/direct-typed-span-layout? snapshot))
      (doseq [changed [(assoc snapshot :typed-span-projector identity)
                       (assoc snapshot :durable? false)
                       (assoc snapshot :insert-format :json-each-row)
                       (assoc snapshot :span-insert-columns (vec (reverse (:columns plan))))]]
        (is (not (@#'e/direct-typed-span-layout? changed))))
      (with-redefs [schema/clickstack-trace-insert-columns (vec (reverse schema/clickstack-trace-insert-columns))]
        (is (not (@#'e/direct-typed-span-layout? snapshot)))))
    (with-bindings {#'e/*json-backend* :configured}
      (is (not (@#'e/direct-typed-span-layout? snapshot))))))

(deftest typed-vector-overflow-keeps-later-input-unrealized
  (let [state (state-for (plans false)) effects (atom []) executions (atom 0)
        records (lazy-seq (cons (@#'fixture/span {})
                               (lazy-seq (swap! effects conj :later) (list (@#'fixture/span {})))))]
    (with-bindings {#'e/*json-backend* :native-guarded-byte-batch}
      (with-redefs [e/max-insert-bytes 1
                    e/execute-durable-sql! (fn [& _] (swap! executions inc))]
        (is (= {:limit 1}
               (wire/error-data #(@#'e/insert-direct-typed-span-batch! :connection state records))))))
    (is (empty? @effects))
    (is (zero? @executions))))

(deftest public-exporter-selects-direct-or-fallback-by-live-projector
  (doseq [changed? [false true]]
    (let [state (state-for (plans false)) calls (atom [])]
      (swap! state assoc :json-backend :native-guarded-byte-batch :closed-signals #{} :in-flight 0)
      (when changed? (swap! state assoc :typed-span-projector identity))
      (with-redefs [e/insert-direct-typed-span-batch! (fn [& _] (swap! calls conj :direct))
                    e/insert-batch! (fn [& _] (swap! calls conj :maps))]
        (is (true? (sdk/export-spans! (e/->ChdbExporter :connection false #{:spans} state)
                                     [(@#'fixture/span {})]))))
      (is (= [(if changed? :maps :direct)] @calls))
      (is (zero? (:in-flight @state))))))
