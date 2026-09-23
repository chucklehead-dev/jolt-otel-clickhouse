(ns otel.exporter.chdb-typed-span-abba
  "Opt-in same-source typed span encoder comparison; never a test-suite entrypoint."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local]
            [jdbc.chdb.native :as native]
            [otel.sdk.export :as export]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb-ordinary-transport-benchmark :as fixture]))

(def warmups 5)
(def measured 100)
(def batch-size 512)
(def spans (fixture/spans "typed-abba"))

(defn- insist [condition label]
  (when-not condition
    (throw (ex-info "Typed span benchmark control failed" {:label label}))))

(defn- nanos-summary [times]
  (let [ordered (vec (sort times))
        n (count ordered)]
    (insist (= measured n) :sample-count)
    (insist (every? pos? ordered) :positive-times)
    {:count n :samples-nanos times :total-nanos (reduce + 0 times)
     :p50-nanos (nth ordered 49) :p95-nanos (nth ordered 94)
     :p99-nanos (nth ordered 98) :max-nanos (peek ordered)}))

(defn- measure [f]
  (nanos-summary
   (mapv (fn [_]
           (let [start (System/nanoTime)]
             (insist (f) :measured-call)
             (- (System/nanoTime) start)))
         (range measured))))

(defn- store-options [root phase]
  {:namespace-backend (local/local-backend (str root "/objects"))
   :object-id "typed-span-abba"
   :scratch-parent (str root "/scratch-" phase)})

(defn- utf8-bytes [text]
  (alength (.getBytes text "UTF-8")))

(defn- canonical [value]
  (cond
    (map? value)
    (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
          (map (fn [[k v]] [k (canonical v)]) value))
    (sequential? value) (mapv canonical value)
    :else value))

(defn writer! [route root]
  (insist (#{"A" "B"} route) :route)
  (native/ensure-loaded!)
  (insist (= "26.7.3" (native/chdb-version)) :native-version)
  (with-open [connection
              (jdbc/connection
               (durable/writer-dbspec
                (merge (store-options root "writer")
                       {:owner "typed-span-abba" :instance "writer-1"
                        :database "otel" :lease-ttl-ms 900000
                        :heartbeat-interval-ms 300000})))]
    (schema/ensure-schema! connection)
    (let [{:keys [projector capability fields]} (fixture/setup connection)
          checkpoint (durable/checkpoint! connection)
          _ (insist (= :committed (:status checkpoint)) :schema-checkpoint)
          owner (exporter/exporter
                 {:connection connection :durable? true :create-schema? false
                  :signals #{:spans} :typed-span-descriptors capability})]
      (try
        (let [state (:state owner)
              initial @state
              encoder (:typed-span-encoder initial)
              typed-projector (:typed-span-projector initial)
              _ (insist (and (ifn? encoder) (ifn? typed-projector)
                             (= (fixture/columns fields)
                                (:span-insert-columns initial)))
                        :confirmed-typed-encoder)
              _ (when (= route "A")
                  (swap! state assoc :typed-span-encoder nil))
              selected @state
              _ (insist (identical? typed-projector (:typed-span-projector selected))
                        :typed-projector-preserved)
              _ (insist (= (= route "B") (ifn? (:typed-span-encoder selected)))
                        :selected-route)
              rows (mapv #(#'exporter/span-row % typed-projector) spans)
              old-encode #(#'exporter/json-each-row-payload rows)
              new-encode #(#'exporter/untyped-span-payload encoder spans)
              old-payload (old-encode)
              new-payload (new-encode)
              _ (insist (= old-payload new-payload) :exact-payload-bytes)
              _ (insist (and (= batch-size (count rows))
                             (<= (utf8-bytes old-payload) (* 8 1024 1024)))
                        :payload-bound)
              encode (if (= route "A") old-encode new-encode)]
          (dotimes [_ warmups] (encode))
          (let [encoding (measure #(do (encode) true))
                row-map-calls (atom 0)
                original @#'exporter/span-row]
            ;; First public warmup is an untimed branch witness. The A arm
            ;; must build exactly one typed row map per span; B must build none.
            (with-redefs [exporter/span-row
                          (fn [span projector]
                            (swap! row-map-calls inc)
                            (original span projector))]
              (insist (export/export-spans! owner spans) :witness-confirmed))
            (insist (= (if (= route "A") batch-size 0) @row-map-calls)
                    :canonical-row-map-branch)
            (dotimes [_ (dec warmups)]
              (insist (export/export-spans! owner spans) :warmup-confirmed))
            (let [durable-timing
                  (measure #(export/export-spans! owner spans))
                  report {:route route :batch-size batch-size :warmups warmups
                          :measured measured :writer-rows (* batch-size (+ warmups measured))
                          :typed-projector-confirmed true
                          :row-map-witness-calls @row-map-calls
                          :payload-utf8-bytes (utf8-bytes old-payload)
                          :payload-sha256 (fixture/digest old-payload)
                          :columns-sha256 (fixture/digest (pr-str (fixture/columns fields)))
                          :encoding encoding :public-durable-confirmed durable-timing}]
              (spit (str root "/writer-report.edn") (pr-str report))
              (println :writer-green :route route :rows (:writer-rows report)
                       :payload-sha256 (:payload-sha256 report)))))
        (finally
          (insist (export/shutdown-exporter! owner) :shutdown))))))

(defn reader! [root]
  (native/ensure-loaded!)
  (insist (= "26.7.3" (native/chdb-version)) :native-version)
  (with-open [connection (jdbc/connection
                         (durable/snapshot-dbspec (store-options root "reader")))]
    (let [fields (:fields (fixture/approved))
          cols (remove #{"Timestamp"} (fixture/columns fields))
          query (str "SELECT " (str/join ", " (map fixture/sql-column cols))
                     ", toString(toUnixTimestamp64Nano(Timestamp)) AS ticks, count() AS copies"
                     " FROM otel_traces GROUP BY ALL ORDER BY TraceId")
          actual (jdbc/fetch connection query {:max-rows 513})
          expected
          (vec (for [[index span] (map-indexed vector spans)]
                 (let [generic (#'exporter/span-row span nil)
                       promoted
                       (into {} (mapcat (fn [field]
                                          [[(get-in field [:physical :value-column])
                                            (get-in span [:attributes (:key field)])]
                                           [(get-in field [:physical :status-column]) 3]])
                                        fields))
                       row (merge generic promoted)]
                   (assoc (into {} (map (fn [column]
                                          [(keyword (str/lower-case column))
                                           (get row column)]) cols))
                          :ticks (str (+ fixture/base-nanos index))
                          :copies (+ warmups measured)))))
          n (:n (jdbc/fetch-one connection "SELECT count() AS n FROM otel_traces"))]
      (insist (= batch-size (count actual)) :full-row-count)
      (insist (= (* batch-size (+ warmups measured)) n) :total-row-count)
      (insist (= expected actual) :full-physical-row-parity)
      (doseq [field fields]
        (insist (= (if (= :boolean (:type field)) "Bool" "Int64")
                   (:physical_type
                    (jdbc/fetch-one connection
                                    (str "SELECT toTypeName("
                                         (fixture/sql-column
                                          (get-in field [:physical :value-column]))
                                         ") AS physical_type FROM otel_traces LIMIT 1"))))
                :physical-type))
      (let [report {:groups (count actual) :rows n :copies (+ warmups measured)
                    :full-row-sha256 (fixture/digest (pr-str (canonical actual)))
                    :exact-nanos true :typed-values-status true}]
        (spit (str root "/reader-report.edn") (pr-str report))
        (println :fresh-reader-green :groups (:groups report) :rows n
                 :full-row-sha256 (:full-row-sha256 report))))))

(defn -main [phase route root]
  (try
    (case phase
      "writer" (writer! route root)
      "reader" (reader! root)
      (insist false :phase))
    (catch Throwable error
      ;; Do not print exception strings or ex-data: these may carry SQL/rows.
      (println :typed-span-abba-failed :phase phase
               :label (or (:label (ex-data error)) :unknown))
      (System/exit 1))))
