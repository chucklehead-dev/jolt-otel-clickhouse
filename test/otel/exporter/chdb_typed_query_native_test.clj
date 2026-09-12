(ns otel.exporter.chdb-typed-query-native-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
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
(def boolean-key "checkout.complete")
(def unknown-key "checkout.dynamic")
(def int64-exact-above-double 9007199254740993)
(def int64-min -9223372036854775808)
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
              "(trace/set-attribute! span " (pr-str boolean-key) " false)\n"
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

(defn- schema-binding [installation key]
  (let [field (manifest-field installation key)]
    {:attribute-key (:key field)
     :attribute-type (:type field)
     :field-id (:id field)
     :manifest-version (get-in field [:identity :version])}))

(defn- coverage-request [installation key]
  {:signal :spans :attribute-key key
   :schema-binding (schema-binding installation key)
   :start-unix-nano timestamp-base
   :end-unix-nano (+ timestamp-base 1000)})

(defn run [check]
  (println "native capability-bound typed span round trip")
  (with-open [connection (jdbc/connection "chdb::memory:")]
    (schema/ensure-schema! connection)
    (let [object-backend (backend/memory-backend)
          installation
          (installer/install-approved!
           object-backend (compiled-manifest)
           {:target connection
            :observe-columns #(observe-columns connection)
            :execute-ddl! #(jdbc/execute! connection %)})
          descriptor-set (:descriptor-set installation)
          reacquired-descriptor-set
          (:descriptor-set
           (installer/acquire-active!
            object-backend
            {:dataset-id "telemetry-prod" :application-id "checkout"
             :lineage "checkout-v1" :version 1}
            {:target connection
             :observe-columns #(observe-columns connection)}))
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
               [(span 1 {malicious-key int64-max empty-key ""
                         boolean-key false})
                (span 2 {malicious-key "not-an-int"
                         boolean-key "not-a-bool"})
                (span 3 {})
                (span 5 {malicious-key int64-exact-above-double})
                (span 7 {malicious-key int64-min})])
      (export! legacy-exporter
               [(span 4 {malicious-key "legacy" empty-key "legacy-note"
                         boolean-key false})
                (span 6 {malicious-key 7})])
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
                  :source :generic-fallback :typed-status 0 :value "7"}
                 {:attribute-key malicious-key :count 1 :signal :spans
                  :source :generic-fallback :typed-status 0 :value "legacy"}
                 {:attribute-key malicious-key :count 1 :signal :spans
                  :source :typed :typed-status 3 :value (str int64-max)}
                 {:attribute-key malicious-key :count 1 :signal :spans
                  :source :typed :typed-status 3 :value (str int64-min)}
                 {:attribute-key malicious-key :count 1 :signal :spans
                  :source :typed :typed-status 3
                  :value (str int64-exact-above-double)}
                 {:attribute-key malicious-key :count 1 :signal :spans
                  :source :generic-fallback :typed-status 4
                  :value "not-an-int"}
                 {:attribute-key empty-key :count 1 :signal :spans
                  :source :generic-fallback :typed-status 0
                  :value "legacy-note"}
                 {:attribute-key empty-key :count 1 :signal :spans
                  :source :typed :typed-status 2 :value ""}])
               actual)
        (check "status-1 absent rows are not published"
               false
               (boolean (some #(= 1 (:typed-status %)) actual))))

      (let [base-filter
            {:signal :spans
             :start-unix-nano timestamp-base
             :end-unix-nano (+ timestamp-base 1000)
             :limit 20 :max-text-length 256}
            boolean-result
            (explorer/typed-span-filtered-traces
             connection descriptor-set
             (merge base-filter {:attribute-key boolean-key
                                 :operator :eq :value false}))
            empty-result
            (explorer/typed-span-filtered-traces
             connection descriptor-set
             (merge base-filter {:attribute-key empty-key
                                 :operator :eq :value ""}))
            exact-int64-result
            (explorer/typed-span-filtered-traces
             connection descriptor-set
             (merge base-filter {:attribute-key malicious-key
                                 :operator :eq
                                 :value int64-exact-above-double}))
            lower-int64-result
            (explorer/typed-span-filtered-traces
             connection descriptor-set
             (merge base-filter {:attribute-key malicious-key
                                 :operator :gte
                                 :value int64-exact-above-double}))
            upper-int64-result
            (explorer/typed-span-filtered-traces
             connection descriptor-set
             (merge base-filter {:attribute-key malicious-key
                                 :operator :lt
                                 :value (inc int64-min)}))
            standalone-boolean
            (explorer/typed-span-coverage
             connection descriptor-set
             (coverage-request installation boolean-key))
            standalone-string
            (explorer/typed-span-coverage
             connection descriptor-set
             (coverage-request installation empty-key))
            standalone-int64
            (explorer/typed-span-coverage
             connection descriptor-set
             (coverage-request installation malicious-key))
            reacquired-int64
            (explorer/typed-span-coverage
             connection reacquired-descriptor-set
             (coverage-request installation malicious-key))]
        (check "native Boolean false and present-empty string filters are typed"
               [[false 3] ["" 2]]
               [[(get-in boolean-result [:matches 0 :attribute-value])
                 (get-in boolean-result [:matches 0 :typed-status])]
                [(get-in empty-result [:matches 0 :attribute-value])
                 (get-in empty-result [:matches 0 :typed-status])]])
        (check "native coverage separates invalid, absent, and historical rows"
               [{:absent 3 :historical-untyped-fallback 1
                 :historical-untyped-unavailable 1 :invalid 1
                 :present-empty 0 :total 7 :valid 1}
                {:absent 4 :historical-untyped-fallback 1
                 :historical-untyped-unavailable 1 :invalid 0
                :present-empty 1 :total 7 :valid 0}]
               [(:coverage boolean-result) (:coverage empty-result)])
        (check "native Int64 equality remains exact above double precision"
               [[int64-exact-above-double 3]]
               (mapv (juxt :attribute-value :typed-status)
                     (:matches exact-int64-result)))
        (check "native Int64 lower bound excludes smaller valid and fallback rows"
               [int64-exact-above-double int64-max]
               (mapv :attribute-value (:matches lower-int64-result)))
        (check "native Int64 upper bound retains the signed minimum"
               [int64-min]
               (mapv :attribute-value (:matches upper-int64-result)))
        (check "native Int64 filter coverage remains honest"
               {:absent 1 :historical-untyped-fallback 2
                :historical-untyped-unavailable 0 :invalid 1
                :present-empty 0 :total 7 :valid 3}
               (:coverage exact-int64-result))
        (check "standalone native Boolean, string, and Int64 coverage equals filtered coverage"
               [(:coverage boolean-result)
                (:coverage empty-result)
                (:coverage exact-int64-result)]
               (mapv :coverage
                     [standalone-boolean standalone-string standalone-int64]))
        (check "standalone native coverage returns each exact schema binding"
               [(schema-binding installation boolean-key)
                (schema-binding installation empty-key)
                (schema-binding installation malicious-key)]
               (mapv #(select-keys % [:attribute-key :attribute-type
                                      :field-id :manifest-version])
                     [standalone-boolean standalone-string standalone-int64]))
        (check "acquire-only native coverage preserves identity and counts"
               standalone-int64 reacquired-int64))

      (let [base-request
            {:signal :spans :attribute-key malicious-key
             :group-by [] :aggregates [:count :min :max]
             :start-unix-nano timestamp-base
             :end-unix-nano (+ timestamp-base 1000)
             :limit 20}
            valid-only
            (explorer/typed-span-int64-aggregates
             connection descriptor-set
             (assoc base-request :predicate {:gte 0}))
            exact-range
            (explorer/typed-span-int64-aggregates
             connection descriptor-set
             (assoc base-request
                    :predicate {:gte int64-exact-above-double
                                :lt (inc int64-exact-above-double)}))
            maximum
            (explorer/typed-span-int64-aggregates
             connection descriptor-set
             (assoc base-request :predicate {:gte int64-max}))
            minimum
            (explorer/typed-span-int64-aggregates
             connection descriptor-set
             (assoc base-request :predicate {:lt (inc int64-min)}))
            empty-range
            (explorer/typed-span-int64-aggregates
             connection descriptor-set
             (assoc base-request :predicate {:gte 1 :lt 2}))
            query-var
            (ns-resolve 'otel.exporter.chdb.explorer
                        'typed-span-int64-aggregate-query)
            original-query @query-var
            status-stripped
            (with-redefs-fn
              {query-var
               (fn [request]
                 (str/replace (original-query request)
                              #"  AND `as_[^`]+` = 3\n" ""))}
              #(explorer/typed-span-int64-aggregates
                connection descriptor-set
                (assoc base-request :predicate {:gte 0})))]
        (check "status-3 guard excludes numeric fallback and invalid defaults"
               [{:count 2 :min int64-exact-above-double :max int64-max
                 :source :typed :typed-status 3}]
               (mapv #(select-keys % [:count :min :max :source :typed-status])
                     valid-only))
        (check "typed Int64 range remains exact above double precision"
               [[[1 int64-exact-above-double int64-exact-above-double]] true]
               [(mapv (juxt :count :min :max) exact-range)
                (> int64-exact-above-double 9007199254740992)])
        (check "one-sided predicate retains the signed Int64 maximum"
               [[1 int64-max int64-max]]
               (mapv (juxt :count :min :max) maximum))
        (check "one-sided predicate retains the signed Int64 minimum"
               [[1 int64-min int64-min]]
               (mapv (juxt :count :min :max) minimum))
        (check "empty typed numeric range returns no synthetic aggregate row"
               [] empty-range)
        (check "removing the status guard admits non-valid physical defaults"
               false (= valid-only status-stripped)))

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
