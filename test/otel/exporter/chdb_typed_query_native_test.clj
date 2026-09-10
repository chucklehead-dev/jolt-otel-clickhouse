(ns otel.exporter.chdb-typed-query-native-test
  (:require [clojure.data.json :as json]
            [db.jdbc]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb-attribute-bundle-fixture :as bundle-fixture]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.explorer :as explorer]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.memory :as memory]
            [otel.otlp.encode :as otlp-encode]
            [otel.otlp.http-receiver :as receiver]
            [otel.otlp.json :as otlp-json]
            [otel.resource :as resource]
            [otel.sdk.export :as export]
            [otel.sdk.tracer :as sdk-tracer]
            [otel.trace :as trace]))

(def malicious-key "checkout.count') OR 1=1 --")
(def empty-key "checkout.note")
(def unknown-key "checkout.dynamic")
(def int64-max 9223372036854775807)
(def timestamp-base 1700000000000000000)

(defn- compiled-manifest []
  (let [fragment
        (bundle-fixture/inferred
         "src/checkout.clj"
         (str "(ns checkout (:require [otel.trace :as trace]))\n"
              "(trace/set-attribute! span " (pr-str malicious-key)
              " (long value))\n"
              "(trace/set-attribute! span " (pr-str empty-key) " \"\")\n"
              "(trace/set-attribute! span " (pr-str unknown-key)
              " dynamic-value)"))
        bundle
        (bundle-fixture/discovered-bundle
         [{:artifact
           (bundle-fixture/revision-artifact
            "io.github.example/checkout"
            "https://github.com/example/checkout"
            "6666666666666666666666666666666666666666")
           :path "META-INF/otel/attribute-schema/checkout.edn"
           :fragment fragment}])]
    (manifest/compile-bundle-manifest
     {:dataset-id "telemetry-prod" :application-id "checkout"
      :lineage "checkout-v1" :version 1 :bundle bundle})))

(defn- observe-columns [connection]
  [{:columns (into {}
                   (map (juxt :name :type))
                   (jdbc/fetch connection "DESCRIBE TABLE otel_traces"))
    :signal :spans
    :table "otel_traces"}])

(defn- span [n attributes]
  {:name (str "typed-native-" n) :kind :internal
   :start-time-unix-nano (+ timestamp-base n)
   :end-time-unix-nano (+ timestamp-base n 1)
   :span-context
   {:trace-id (str "0000000000000000000000000000000" n)
    :span-id (str "000000000000000" n)}
   :resource {:attributes {}} :scope {:name "typed-native-test"}
   :attributes attributes :events [] :links [] :status {:code :unset}})

(defn- export! [exporter spans]
  (when-not (export/export-spans! exporter spans)
    (throw (or (chdb-export/last-error exporter)
               (ex-info "native typed span export failed" {})))))

(defn- canonical-equivalence-span []
  (let [memory-exporter (memory/exporter)
        provider
        (sdk-tracer/tracer-provider
         {:resource (resource/resource {"service.name" "typed-receiver"})
          :processors [(export/simple-processor memory-exporter)]})
        tracer (sdk-tracer/get-tracer
                provider
                {:name "typed-receiver-test" :version "1"
                 :attributes {"scope.attribute" true}})
        current
        (trace/start-span
         tracer "typed-direct-receiver-equivalence"
         {:attributes {malicious-key int64-max empty-key ""
                       unknown-key "fallback-only"}
          :start-timestamp (+ timestamp-base 2000)})
        linked
        (trace/span-context
         {:trace-id "10000000000000000000000000000000"
          :span-id "1000000000000000"
          :trace-flags 1})]
    (trace/add-event! current "canonical-event" {"event.empty" ""}
                      (+ timestamp-base 2001))
    (trace/add-link! current linked {"link.valid" true})
    (trace/end! current (+ timestamp-base 2002))
    (first (memory/spans memory-exporter))))

(defn- parse-json-body [request limit]
  (let [body (:body request)
        encoded-bytes (alength (.getBytes body "UTF-8"))]
    (when (> encoded-bytes limit)
      (throw (receiver/body-too-large limit encoded-bytes)))
    {:value (json/read-str body) :encoded-bytes encoded-bytes}))

(defn- rows-for-trace [connection trace-id]
  (jdbc/fetch connection
              ["select * from otel_traces where TraceId=?" trace-id]))

(defn- manifest-field [installation key]
  (first (filter #(= key (:key %))
                 (get-in installation [:record :manifest :fields]))))

(defn run [check]
  (println "native capability-bound typed span round trip")
  (with-open [connection (jdbc/connection "chdb::memory:")]
    (schema/ensure-schema! connection)
    (let [installation
          (installer/install-approved!
           (backend/memory-backend) (compiled-manifest)
           {:target connection
            :observe-columns #(observe-columns connection)
            :execute-ddl! #(jdbc/execute! connection %)})
          descriptor-set (:descriptor-set installation)
          typed-exporter
          (chdb-export/exporter
           {:connection connection :create-schema? false :signals #{:spans}
            :typed-span-descriptors descriptor-set})
          legacy-exporter
          (chdb-export/exporter
           {:connection connection :create-schema? false :signals #{:spans}})]
      ;; Statuses 3/2, 4, and 1 are emitted through the confirmed projector.
      ;; A capability-free export leaves additive status columns at their
      ;; status-0 defaults and exercises the historical generic fallback.
      (export! typed-exporter
               [(span 1 {malicious-key int64-max empty-key ""})
                (span 2 {malicious-key "not-an-int"})
                (span 3 {})])
      (export! legacy-exporter [(span 4 {malicious-key "legacy"})])
      (let [actual
            (sort-by (juxt :attribute-key :typed-status :value)
                     (explorer/typed-span-values
                      connection descriptor-set
                      {:signal :spans :keys [malicious-key empty-key]
                       :start-unix-nano timestamp-base
                       :end-unix-nano (+ timestamp-base 1000)
                       :limit 20 :max-text-length 256}))]
        (check "real DDL, JSON inserts, Map lookup, and typed query round trip"
               (sort-by
                (juxt :attribute-key :typed-status :value)
                [{:attribute-key malicious-key :count 1 :signal :spans
                  :source :generic-fallback :typed-status 0 :value "legacy"}
                 {:attribute-key malicious-key :count 1 :signal :spans
                  :source :typed :typed-status 3 :value (str int64-max)}
                 {:attribute-key malicious-key :count 1 :signal :spans
                  :source :generic-fallback :typed-status 4
                  :value "not-an-int"}
                 {:attribute-key empty-key :count 1 :signal :spans
                  :source :typed :typed-status 2 :value ""}])
               actual)
        (check "status-1 absent rows are not published"
               false
               (boolean (some #(= 1 (:typed-status %)) actual))))

      (let [source (canonical-equivalence-span)
            trace-id (get-in source [:span-context :trace-id])
            direct-ok (export/export-spans! typed-exporter [source])
            direct-rows (rows-for-trace connection trace-id)
            direct-row (first direct-rows)
            payload (otlp-json/write-str
                     (otlp-encode/traces-request [source]))
            encoded-bytes (alength (.getBytes payload "UTF-8"))
            handler (receiver/handler
                     {:parse-body parse-json-body
                      :exporter typed-exporter})
            response
            (handler {:request-method :post
                      :uri receiver/traces-path
                      :headers {"content-type" "application/json"
                                "content-length" (str encoded-bytes)}
                      :body payload})
            received-rows (rows-for-trace connection trace-id)
            int-field (manifest-field installation malicious-key)
            empty-field (manifest-field installation empty-key)
            physical-key #(keyword (get-in % [:physical %2]))]
        (check "direct typed export succeeds before receiver ingestion"
               [true 1] [(boolean direct-ok) (count direct-rows)])
        (check "real OTLP JSON reaches the receiver without partial success"
               [200 "{}"] [(:status response) (:body response)])
        (check "direct and receiver paths persist identical full physical rows"
               [2 true]
               [(count received-rows)
                (every? #(= direct-row %) received-rows)])
        (check "equivalent rows retain large-int and present-empty statuses"
               [int64-max 3 "" 2]
               [(get direct-row (physical-key int-field :value-column))
                (get direct-row (physical-key int-field :status-column))
                (get direct-row (physical-key empty-field :value-column))
                (get direct-row (physical-key empty-field :status-column))])
        (check "generic attribute compatibility survives both ingestion paths"
               {malicious-key (str int64-max) empty-key ""
                unknown-key "fallback-only"}
               (:spanattributes direct-row))
        (check "causal nested values begin and remain free of synthetic zero counts"
               [false false false false]
               [(contains? (first (:events source)) :dropped-attributes-count)
                (contains? (first (:links source)) :dropped-attributes-count)
                (.contains (:eventsjson direct-row) "dropped-attributes-count")
                (.contains (:linksjson direct-row) "dropped-attributes-count")])))))
