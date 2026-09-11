(ns otel.exporter.chdb-property-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hegel.core :as h]
            [hegel.generator :as g]
            [hegel.stateful :as hs]
            [hegel.trace :as ht]
            [jdbc.core :as jdbc]
            [otel.any-value :as any]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.chdb.explorer :as explorer]
            [otel.exporter.chdb.schema :as schema]
            [otel.logs :as logs]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as sdk-logs]))

(def ^:private signals [:spans :logs :metrics])

(defn- fail! [origin message data]
  (throw (ex-info message (assoc data :hegel/origin origin))))

(defn- check! [condition origin message data]
  (when-not condition (fail! origin message data)))

(defn- signal-export! [exporter signal]
  (case signal
    :spans (export/export-spans! exporter [])
    :logs (sdk-logs/export-logs! exporter [])
    :metrics (export/export-metrics! exporter {} [])))

(defn- signal-shutdown! [exporter signal]
  (case signal
    :spans (export/shutdown-exporter! exporter)
    :logs (sdk-logs/shutdown-log-exporter! exporter)
    :metrics (export/shutdown-metric-exporter! exporter)))

(defn- lifecycle-step [op signal]
  (fn [{:keys [exporter expected closed] :as state}]
    (let [actual (case op
                   :export (signal-export! exporter signal)
                   :shutdown (signal-shutdown! exporter signal))
          expected-result (if (= op :shutdown)
                            true
                            (and (contains? expected signal)
                                 (not (contains? closed signal))))]
      (check! (= expected-result actual)
              "otel-exporter/lifecycle-result"
              "signal operation disagreed with the lifecycle model"
              {:operation op :signal signal
               :expected expected-result :actual actual})
      (cond-> state (= op :shutdown) (update :closed conj signal)))))

(defn- lifecycle-invariant
  [{:keys [exporter expected closed close-count]}]
  (let [should-close? (every? closed expected)
        snapshot @(:state exporter)]
    (and (= (if should-close? 1 0) @close-count)
         (= should-close? (:connection-close-claimed? snapshot))
         (= (if should-close? :closed :open)
            (:connection-close-status snapshot))
         (= should-close? (:connection-closed? snapshot))
         (= closed (:closed-signals snapshot)))))

(defn- record-event! [journal event]
  (swap! journal
         (fn [events]
           (conj events (assoc event :seq (inc (count events))))))
  nil)

(defn- traced-shutdown!
  [journal exporter signal operation-id expected-result]
  (record-event! journal {:kind :shutdown :phase :enter
                          :operation-id operation-id :signal signal})
  (try
    (let [result (signal-shutdown! exporter signal)]
      (record-event! journal {:kind :shutdown :phase :return
                              :operation-id operation-id :signal signal
                              :result result :expected-result expected-result})
      result)
    (catch Throwable error
      (record-event! journal {:kind :shutdown :phase :throw
                              :operation-id operation-id :signal signal})
      (throw error))))

(defn- shutdown-history-rule [expected-operation-count]
  (ht/event-model
   :owned-connection-close-history
   {:initial {:active #{} :completed #{} :close-count 0 :valid? true}
    :step
    (fn [model event]
      (case [(:kind event) (:phase event)]
        [:shutdown :enter]
        (let [operation-id (:operation-id event)]
          (-> model
              (update :active conj operation-id)
              (update :valid? #(and %
                                    (not (contains? (:active model) operation-id))
                                    (not (contains? (:completed model) operation-id))))))

        [:shutdown :return]
        (let [operation-id (:operation-id event)]
          (-> model
              (update :active disj operation-id)
              (update :completed conj operation-id)
              (update :valid? #(and %
                                    (contains? (:active model) operation-id)
                                    (= (:expected-result event) (:result event))))))

        [:connection :close]
        (update model :close-count inc)

        (assoc model :valid? false)))
    :invariant (fn [model _]
                 (and (:valid? model) (<= (:close-count model) 1)))
    :final (fn [model]
             (and (:valid? model)
                  (empty? (:active model))
                  (= expected-operation-count (count (:completed model)))
                  (= 1 (:close-count model))))}))

(defn- await! [value origin]
  (let [result (deref value 2000 ::timeout)]
    (check! (not= ::timeout result) origin "concurrent shutdown timed out" {})
    result))

(defn- close-race-history-property []
  (h/run-test!
   {:name "otel exporter concurrent close history"
    :database "" :verbosity :quiet :derandomize? true :test-cases 72}
   (fn [_]
     (let [expected (h/draw! (g/set {:min-size 1 :max-size 3}
                                    (g/sampled-from signals)))
           terminal-signal (h/draw! (g/sampled-from (vec expected)))
           racing-signals
           (h/draw! (g/vector {:min-size 1 :max-size 8}
                              (g/sampled-from signals)))
           journal (atom [])
           operation-id (atom -1)
           close-count (atom 0)
           close-entered (promise)
           release-close (promise)
           connection
           {:close
            (fn [_]
              (let [invocation (swap! close-count inc)]
                (record-event! journal {:kind :connection :phase :close
                                        :invocation invocation})
                ;; Hold the winning call open while the caller executes every
                ;; generated racing shutdown. A pre-fix exporter enters .close
                ;; again here; the claimed exporter returns without doing so.
                (when (= 1 invocation)
                  (deliver close-entered true)
                  (await! release-close
                          "otel-exporter/close-race-release-timeout"))))}
           exporter (chdb-export/->ChdbExporter
                     connection true expected
                     (atom {:closed-signals #{}
                            :connection-close-claimed? false
                            :connection-close-status :open
                            :connection-closed? false :last-error nil}))
           invoke! (fn [signal]
                     (traced-shutdown! journal exporter signal
                                       (swap! operation-id inc) true))
           ;; Leave exactly one expected signal open. The generated racing
           ;; history can then mix all three protocol shutdown entry points.
           _ (doseq [signal (disj expected terminal-signal)]
               (check! (invoke! signal)
                       "otel-exporter/pre-terminal-shutdown-result"
                       "non-terminal signal shutdown failed"
                       {:signal signal :expected expected}))
           winner (future (invoke! terminal-signal))]
       (try
         (await! close-entered "otel-exporter/close-race-enter-timeout")
         (doseq [signal racing-signals]
           (check! (invoke! signal)
                   "otel-exporter/racing-shutdown-result"
                   "shutdown racing an accepted close did not succeed"
                   {:signal signal :expected expected}))
         (finally
           (deliver release-close true)))
       (check! (await! winner "otel-exporter/close-race-winner-timeout")
               "otel-exporter/close-race-winner-result"
               "winning shutdown did not complete successfully"
               {:signal terminal-signal})
       (doseq [signal (conj racing-signals terminal-signal)]
         (check! (invoke! signal)
                 "otel-exporter/repeated-shutdown-result"
                 "repeated shutdown did not preserve close success"
                 {:signal signal :expected expected}))
       (let [events @journal
             operation-count (inc @operation-id)]
         (ht/check! events
                    [(ht/contiguous-sequence :close-history-contiguous)
                     (shutdown-history-rule operation-count)]
                    {:max-events 48}))))))

(defn- close-failure-history-property []
  (h/run-test!
   {:name "otel exporter terminal close failure history"
    :database "" :verbosity :quiet :derandomize? true :test-cases 48}
   (fn [_]
     (let [signal (h/draw! (g/sampled-from signals))
           repetitions (h/draw! (g/integer 1 8))
           journal (atom [])
           close-count (atom 0)
           failure (ex-info "synthetic connection close failure"
                            {:type ::synthetic-close-failure})
           connection
           {:close
            (fn [_]
              (let [invocation (swap! close-count inc)]
                (record-event! journal {:kind :connection :phase :close
                                        :invocation invocation})
                (throw failure)))}
           exporter (chdb-export/->ChdbExporter
                     connection true #{signal}
                     (atom {:closed-signals #{}
                            :connection-close-claimed? false
                            :connection-close-status :open
                            :connection-closed? false :last-error nil}))]
       (check! (false? (traced-shutdown! journal exporter signal 0 false))
               "otel-exporter/close-failure-result"
               "the shutdown which observed close failure did not return false"
               {:signal signal})
       (doseq [operation-id (range 1 (inc repetitions))]
         (check! (false? (traced-shutdown! journal exporter signal operation-id false))
                 "otel-exporter/close-failure-repeat-result"
                 "repeated shutdown retried or forgot a terminal close failure"
                 {:signal signal :operation-id operation-id}))
       (let [snapshot @(:state exporter)]
         (check! (and (= :failed (:connection-close-status snapshot))
                      (false? (:connection-closed? snapshot))
                      (identical? failure (:connection-close-error snapshot))
                      (identical? failure (:last-error snapshot)))
                 "otel-exporter/close-failure-state"
                 "terminal close failure was not retained diagnostically"
                 {:signal signal :state snapshot}))
       (ht/check! @journal
                  [(ht/contiguous-sequence :close-failure-history-contiguous)
                   (shutdown-history-rule (inc repetitions))]
                  {:max-events 24})))))

(defn- lifecycle-property []
  (h/run-test!
   {:name "otel exporter per-signal lifecycle"
    :database "" :verbosity :quiet :derandomize? true
    :test-cases 80 :stateful-step-count 36}
   (fn [_]
     (let [expected (h/draw! (g/set {:min-size 1 :max-size 3}
                                    (g/sampled-from signals)))
           close-count (atom 0)
           connection {:close (fn [_] (swap! close-count inc))}
           exporter (chdb-export/->ChdbExporter
                     connection true expected
                     (atom {:closed-signals #{}
                            :connection-close-claimed? false
                            :connection-close-status :open
                            :connection-closed? false
                            :last-error nil}))]
       (hs/run!
        {:initial-state {:exporter exporter :expected expected
                         :closed #{} :close-count close-count}
         :rules [(hs/rule :export-spans (lifecycle-step :export :spans))
                 (hs/rule :export-logs (lifecycle-step :export :logs))
                 (hs/rule :export-metrics (lifecycle-step :export :metrics))
                 (hs/rule :shutdown-spans (lifecycle-step :shutdown :spans))
                 (hs/rule :shutdown-logs (lifecycle-step :shutdown :logs))
                 (hs/rule :shutdown-metrics (lifecycle-step :shutdown :metrics))]
         :invariants [(hs/invariant :owned-connection-closes-once
                                    lifecycle-invariant)]})))))

(defn- sample-span []
  {:name "durable-span" :kind :internal
   :start-time-unix-nano 1 :end-time-unix-nano 2
   :span-context {:trace-id "11111111111111111111111111111111"
                  :span-id "2222222222222222"}
   :resource {:attributes {}} :scope {:name "durable-test"}
   :attributes {} :events [] :links [] :status {:code :unset}})

(defn- sample-log []
  {:timestamp-unix-nano 1 :trace-id "" :span-id ""
   :resource {:attributes {}} :scope {:name "durable-test"}
   :attributes {} :body "durable-log"})

(defn- invoke-batch! [exporter signal non-empty?]
  (case signal
    :spans (export/export-spans! exporter
                                 (if non-empty? [(sample-span)] []))
    :logs (sdk-logs/export-logs! exporter
                                 (if non-empty? [(sample-log)] []))
    :metrics
    (export/export-metrics!
     exporter {:attributes {}}
     (if non-empty?
       [{:scope {:name "durable-test"}
         :metrics [{:name "durable.metric" :type :gauge
                    :data-points [{:value 1.0 :time-unix-nano 1
                                   :attributes {}}]}]}]
       []))))

(defn- batch-history-rule [non-empty? expected-result]
  (ht/event-model
   :durable-export-batch-history
   {:initial {:entered? false :inserted? false :insert-failed? false
              :barrier-attempted? false
              :barrier-completed? false :barrier-failed? false
              :returned? false :valid? true}
    :step
    (fn [model event]
      (case [(:kind event) (:phase event)]
        [:export :enter]
        (-> model
            (assoc :entered? true)
            (update :valid? #(and % (not (:entered? model))
                                  (= non-empty? (:non-empty? event)))))

        [:insert :return]
        (-> model
            (assoc :inserted? true)
            (update :valid? #(and % (:entered? model) non-empty?
                                  (not (:inserted? model))
                                  (not (:insert-failed? model)))))

        [:insert :throw]
        (-> model
            (assoc :insert-failed? true)
            (update :valid? #(and % (:entered? model) non-empty?
                                  (not (:inserted? model)))))

        [:barrier :enter]
        (-> model
            (assoc :barrier-attempted? true)
            (update :valid? #(and % (:inserted? model)
                                  (not (:barrier-attempted? model)))))

        [:barrier :return]
        (-> model
            (assoc :barrier-completed? true)
            (update :valid? #(and % (:barrier-attempted? model)
                                  (not (:barrier-failed? model)))))

        [:barrier :throw]
        (-> model
            (assoc :barrier-failed? true)
            (update :valid? #(and % (:barrier-attempted? model)
                                  (not (:barrier-completed? model)))))

        [:export :return]
        (-> model
            (assoc :returned? true)
            (update :valid?
                    #(and % (:entered? model) (not (:returned? model))
                          (= expected-result (:result event))
                          (if (and non-empty? (:result event))
                            (:barrier-completed? model)
                            true))))

        (assoc model :valid? false)))
    :invariant
    (fn [model _]
      (and (:valid? model)
           (not (and (:insert-failed? model)
                     (or (:barrier-completed? model)
                         (:barrier-failed? model))))))
    :final
    (fn [model]
      (and (:valid? model) (:returned? model)
           (if non-empty?
             (or (:insert-failed? model)
                 (:barrier-completed? model)
                 (:barrier-failed? model))
             (and (not (:inserted? model))
                  (not (:insert-failed? model))
                  (not (:barrier-attempted? model))
                  (not (:barrier-completed? model))
                  (not (:barrier-failed? model))))))}))

(defn- durable-barrier-history-property []
  (h/run-test!
   {:name "durable exporter acknowledgement history"
    :database "" :verbosity :quiet :derandomize? true :test-cases 120}
   (fn [_]
     (let [signal (h/draw! (g/sampled-from signals))
           outcome (h/draw! (g/sampled-from
                             [:empty :insert-failure
                              :barrier-failure :committed
                              :reconciled :unconfirmed]))
           non-empty? (not= :empty outcome)
           expected-result (contains? #{:empty :committed :reconciled} outcome)
           journal (atom [])
           failure (ex-info "synthetic persistence failure"
                            {:type ::synthetic-persistence-failure})
           state (atom {:closed-signals #{}
                        :connection-close-claimed? false
                        :connection-close-status :open
                        :connection-closed? false
                        :persistence-barrier
                        (fn [_]
                          (record-event! journal {:kind :barrier :phase :enter})
                          (if (= :barrier-failure outcome)
                            (do
                              (record-event! journal
                                             {:kind :barrier :phase :throw})
                              (throw failure))
                            (do
                              (record-event! journal
                                             {:kind :barrier :phase :return})
                              {:status outcome})))
                        :durable? true
                        :last-error nil})
           exporter (chdb-export/->ChdbExporter :fake false #{signal} state)]
       (record-event! journal {:kind :export :phase :enter
                               :non-empty? non-empty?})
       (let [result
             (with-redefs
              [jdbc/execute!
               (fn [& _]
                 (if (= :insert-failure outcome)
                   (do
                     (record-event! journal {:kind :insert :phase :throw})
                     (throw failure))
                   (record-event! journal {:kind :insert :phase :return})))]
              (invoke-batch! exporter signal non-empty?))]
         (record-event! journal {:kind :export :phase :return :result result})
         (check! (= expected-result result)
                 "otel-exporter/durable-result"
                 "export result disagreed with insert/barrier outcome"
                 {:signal signal :outcome outcome})
         (when-not result
           (let [last-error (chdb-export/last-error exporter)]
             (check! (if (= :unconfirmed outcome)
                       (= :otel.exporter.chdb/durable-barrier-unconfirmed
                          (:type (ex-data last-error)))
                       (identical? failure last-error))
                     "otel-exporter/durable-last-error"
                     "failed persistence boundary did not retain its cause"
                     {:signal signal :outcome outcome})))
         (ht/check! @journal
                    [(ht/contiguous-sequence :durable-history-contiguous)
                     (batch-history-rule non-empty? expected-result)]
                    {:max-events 8}))))))

(defn- durable-itf-replay-property []
  (h/run-test!
   {:name "Quint durable export success replay"
    :database "" :verbosity :quiet :derandomize? true :test-cases 1}
   (fn [_]
     (let [trace (json/read-str
                  (slurp "formal/quint/traces/durable-export-success.itf.json"))
           model-states (get trace "states")
           expected-actions (mapv #(get % "mbt::actionTaken") model-states)
           final-model-state (last model-states)
           state-key (first (filter #(str/ends-with? % "::state")
                                    (keys final-model-state)))
           expected-final (get final-model-state state-key)
           implementation-actions (atom ["init"])
           exporter
           (chdb-export/->ChdbExporter
            :fake false #{:spans}
            (atom {:closed-signals #{}
                   :connection-close-claimed? false
                   :connection-close-status :open
                   :connection-closed? false
                   :persistence-barrier
                   (fn [_]
                     (swap! implementation-actions conj "barrierSuccess"))
                   :last-error nil}))]
       (let [result
             (with-redefs [jdbc/execute!
                           (fn [& _]
                             (swap! implementation-actions conj "insertSuccess")
                             {:count 1})]
               (export/export-spans! exporter [(sample-span)]))]
         (when result
           (swap! implementation-actions conj "returnSuccess"))
         (check! (= expected-actions @implementation-actions)
                 "otel-exporter/quint-itf-actions"
                 "implementation boundaries diverged from the Quint ITF trace"
                 {:expected expected-actions
                  :actual @implementation-actions})
         (let [actual-final
               {"barrierAttempted" true
                "durable" result
                "inserted" true
                "phase" {"tag" (if result "Succeeded" "Failed")
                         "value" {"#tup" []}}
                "returnedFailure" (not result)
                "returnedSuccess" result
                "wrote" true}]
           (check! (= expected-final actual-final)
                   "otel-exporter/quint-itf-final-state"
                   "implementation result diverged from the Quint state oracle"
                   {:expected expected-final :actual actual-final})))))))

(defn- wire-json-property []
  (h/run-test!
   {:name "otel exporter JSON safety and correlation"
    :database "" :verbosity :quiet :derandomize? true :test-cases 160}
   (fn [_]
     (let [trace-id (h/draw! (g/string {:min-size 32 :max-size 32
                                        :alphabet "0123456789abcdef"}))
           span-id (h/draw! (g/string {:min-size 16 :max-size 16
                                       :alphabet "0123456789abcdef"}))
           attr-key (h/draw! (g/string {:max-size 24}))
           text (h/draw! (g/string {:max-size 80}))
           trace-flags (h/draw! (g/integer 0 1024))
           severity-number (h/draw! (g/integer 0 1024))
           nested [text {"line\nbreak" text}]
           attributes {attr-key nested :plain text}
           event {:name text :timestamp-unix-nano 1000000002
                  :attributes attributes}
           link {:span-context {:trace-id trace-id :span-id span-id
                                :trace-state [["vendor" "state"]]}
                 :attributes {:relation text}}
           span {:span-context {:trace-id trace-id :span-id span-id
                                :trace-state [["root" "sampled"]]}
                 :parent-span-id "" :name text :kind :server
                 :start-time-unix-nano 1000000001
                 :end-time-unix-nano 1000000011
                 :status {:code :ok :description text}
                 :scope {:name text :version "1"}
                 :resource {:attributes {:service.name text}}
                 :attributes attributes :events [event] :links [link]}
           log {:timestamp-unix-nano 0
                :observed-time-unix-nano 1000000002
                :trace-id trace-id :span-id span-id :trace-flags trace-flags
                :severity-text "INFO" :severity-number severity-number :body nested
                :event-name text
                :scope {:name text :version "1" :schema-url "scope-schema"
                        :attributes attributes}
                :resource {:schema-url "resource-schema"
                           :attributes {:service.name text :resource attributes}}
                :attributes attributes}
           captured (atom [])
           exporter (chdb-export/->ChdbExporter
                     {} false #{:spans :logs :metrics}
                     (atom {:closed-signals #{}
                            :connection-closed? false :last-error nil}))]
       (with-redefs [jdbc/execute!
                     (fn [_ statement]
                       (let [[table payload]
                             (str/split
                              statement #" FORMAT JSONEachRow\n" 2)]
                         (swap! captured conj
                                [table (vec (remove str/blank?
                                                    (str/split-lines payload)))])
                         0))]
         (check! (export/export-spans! exporter [span])
                 "otel-exporter/span-export" "span export failed" {})
         (check! (sdk-logs/export-logs! exporter [log])
                 "otel-exporter/log-export" "log export failed" {}))
       (let [[[span-table span-lines] [log-table log-lines]] @captured
             span-wire (json/read-str (first span-lines))
             log-wire (json/read-str (first log-lines))]
         (check! (= "insert into otel_traces" span-table)
                 "otel-exporter/table-routing" "signal used the wrong table" {})
         (check! (= (str "insert into otel_logs ("
                         (str/join ", " schema/clickstack-log-insert-columns)
                         ")")
                    log-table)
                 "otel-exporter/log-insert-columns"
                 "log insert columns differ from the pinned collector order" {})
         (check! (= [trace-id span-id trace-id span-id]
                    [(get span-wire "TraceId") (get span-wire "SpanId")
                     (get log-wire "TraceId") (get log-wire "SpanId")])
                 "otel-exporter/correlation" "wire rows lost correlation IDs" {})
         (check! (= (json/write-str nested) (get log-wire "Body"))
                 "otel-exporter/log-body" "structured log body was not JSON-safe" {})
         (check! (= "1.000000002"
                    (get log-wire "Timestamp"))
                 "otel-exporter/log-observed-time"
                 "zero event time did not fall back to observed time" {})
         (check! (= [(bit-and trace-flags 0xff)
                     (bit-and severity-number 0xff)]
                    [(get log-wire "TraceFlags")
                     (get log-wire "SeverityNumber")])
                 "otel-exporter/log-uint8"
                 "log uint8 fields differ from collector conversion" {})
         (check! (= ["resource-schema" "scope-schema" text "1" text]
                    [(get log-wire "ResourceSchemaUrl")
                     (get log-wire "ScopeSchemaUrl")
                     (get log-wire "ScopeName")
                     (get log-wire "ScopeVersion")
                     (get log-wire "EventName")])
                 "otel-exporter/log-metadata"
                 "canonical log metadata was lost" {})
         (check! (= (json/write-str nested)
                    (get-in log-wire ["LogAttributes" attr-key]))
                 "otel-exporter/log-attributes"
                 "structured log attributes were not string-normalized" {})
         (check! (= 1 (count (json/read-str (get span-wire "EventsJSON"))))
                 "otel-exporter/span-events" "span event JSON did not round-trip" {})
         (check! (= [text] (get span-wire "Events.Name"))
                 "otel-exporter/nested-event-name" "nested event name was lost" {})
         (check! (= [trace-id] (get span-wire "Links.TraceId"))
                 "otel-exporter/nested-link-trace" "nested link trace ID was lost" {})
         (check! (= (json/write-str nested)
                    (get-in span-wire ["Events.Attributes" 0 attr-key]))
                 "otel-exporter/nested-event-attributes"
                 "nested event attributes were not string-normalized" {})
         (check! (= ["vendor=state"] (get span-wire "Links.TraceState"))
                 "otel-exporter/nested-link-state" "nested link trace state was not raw" {})
         (check! (= "root=sampled" (get span-wire "TraceState"))
                 "otel-exporter/trace-state" "span trace state was not raw" {})
         (check! (= ["Server" "Ok"]
                    [(get span-wire "SpanKind") (get span-wire "StatusCode")])
                 "otel-exporter/trace-enums"
                 "span enum strings differ from collector pdata values" {})
         (check! (= (json/write-str nested)
                    (get-in span-wire ["SpanAttributes" attr-key]))
                 "otel-exporter/attributes" "structured attribute did not round-trip" {}))))))

(defn direct-structured-log-body-property []
  (h/run-test!
   {:name "direct structured log body export"
    :database "" :verbosity :quiet :derandomize? true :test-cases 1}
   (fn [_]
     (let [captured (atom [])
           exporter (chdb-export/->ChdbExporter
                     {} false #{:logs}
                     (atom {:closed-signals #{}
                            :connection-closed? false :last-error nil}))
           provider (sdk-logs/logger-provider
                     {:processors [(sdk-logs/simple-processor exporter)]})
           logger (sdk-logs/get-logger provider {:name "structured-body"})]
       (with-redefs [jdbc/execute! (fn [_ statement]
                                     (swap! captured conj statement)
                                     0)]
         (logs/emit! logger
                     {:severity :info
                      ;; Insertion order intentionally differs from canonical
                      ;; key order. The new OTel SDK pin must normalize this
                      ;; before the direct exporter sees it.
                      :body (array-map
                             :z [:ready]
                             :a {:state 'phase/joined
                                 :empty any/empty-value
                                 :bytes (any/bytes [0 255])
                                 :array [any/empty-value
                                         (any/bytes [1 2 3])]})
                      :attributes
                      {:empty any/empty-value
                       :bytes (any/bytes [0 255])
                       :structured {:empty any/empty-value}}})
         (logs/emit! logger {:severity :info :body :ready})
         (logs/emit! logger {:severity :info :body any/empty-value})
         (logs/emit! logger {:severity :info
                             :body (any/bytes [0 255])})
         (logs/emit! logger {:severity :info :body {:a nil}})
         (logs/emit! logger {:severity :info
                             :body (inc any/max-int64)}))
       (let [wires (mapv (fn [statement]
                           (let [[_ payload]
                                 (str/split statement
                                            #" FORMAT JSONEachRow\n" 2)]
                             (json/read-str (str/trim payload))))
                         @captured)
             body (get (first wires) "Body")]
         (check! (= 6 (count @captured))
                 "otel-exporter/direct-structured-body-count"
                 "six SDK logs did not produce exactly six direct inserts" {})
         (check! (= "{\"a\":{\"array\":[null,\"AQID\"],\"bytes\":\"AP8=\",\"empty\":null,\"state\":\"phase\\/joined\"},\"z\":[\"ready\"]}"
                    body)
                 "otel-exporter/direct-structured-body"
                 "canonical SDK map body did not retain JSON text in Body String"
                 {:actual body})
         (check! (= "ready" (get (second wires) "Body"))
                 "otel-exporter/direct-scalar-body"
                 "canonical SDK keyword body retained its application syntax"
                 {:actual (get (second wires) "Body")})
         (check! (= ["" "AP8="]
                    (mapv #(get % "Body") (take 2 (drop 2 wires))))
                 "otel-exporter/direct-special-body"
                 "empty and byte bodies differed from collector AsString semantics"
                 {:actual (mapv #(get % "Body") (take 2 (drop 2 wires)))})
         (check! (= {"bytes" "AP8="
                     "empty" ""
                     "structured" "{\"empty\":null}"}
                    (get (first wires) "LogAttributes"))
                 "otel-exporter/direct-special-attributes"
                 "special attributes differed from collector AsString semantics"
                 {:actual (get (first wires) "LogAttributes")})
         (check! (= [(pr-str {:a nil}) (pr-str (inc any/max-int64))]
                    (mapv #(get % "Body") (drop 4 wires)))
                 "otel-exporter/direct-malformed-body-fallback"
                 "unrepresentable direct bodies lost OTel's readable fallback"
                 {:actual (mapv #(get % "Body") (drop 4 wires))}))))))

(defn- metric-wire-property []
  (h/run-test!
   {:name "otel exporter canonical metric wire rows"
    :database "" :verbosity :quiet :derandomize? true :test-cases 120}
   (fn [_]
     (let [text (h/draw! (g/string {:max-size 48}))
           value (h/draw! (g/integer -1000 1000))
           resource {:schema-url "resource-schema"
                     :attributes {:service.name text :resource.value value}}
           scope {:name text :version "1" :schema-url "scope-schema"
                  :attributes {:scope.value value}}
           collected
           [{:scope scope
             :metrics
             [{:type :gauge :name "g" :description text :unit "1"
               :data-points [{:attributes {:k value}
                              :time-unix-nano 2000000002 :value value}]}
              {:type :sum :name "s" :description text :unit "1"
               :temporality :delta :monotonic? false
               :data-points [{:attributes {:k value}
                              :start-time-unix-nano 1000000001
                              :time-unix-nano 2000000002 :value value}]}
              {:type :histogram :name "h" :description text :unit "ms"
               :temporality :cumulative :explicit-bounds [10.0]
               :data-points [{:attributes {:k value}
                              :start-time-unix-nano 1000000001
                              :time-unix-nano 2000000002 :count 1
                              :sum value :bucket-counts [1 0]
                              :min value :max value}]}]}]
           captured (atom [])
           exporter (chdb-export/->ChdbExporter
                     {} false #{:metrics}
                     (atom {:closed-signals #{}
                            :connection-closed? false :last-error nil}))]
       (with-redefs [jdbc/execute!
                     (fn [_ statement]
                       (let [[query payload]
                             (str/split statement #" FORMAT JSONEachRow\n" 2)]
                         (swap! captured conj
                                [query (json/read-str
                                        (first (remove str/blank?
                                                       (str/split-lines payload))))])
                         0))]
         (check! (export/export-metrics! exporter resource collected)
                 "otel-exporter/metric-export" "metric export failed" {}))
       (let [rows (into {} (map (fn [[query row]]
                                  [(get row "MetricName") [query row]]))
                        @captured)]
         (check! (= #{"g" "s" "h"} (set (keys rows)))
                 "otel-exporter/metric-routing"
                 "supported metric kinds did not each produce one row" {})
         (doseq [[kind metric-name] [[:gauge "g"] [:sum "s"] [:histogram "h"]]]
           (let [[query row] (get rows metric-name)]
             (check! (= (str "insert into " (get schema/metric-table-names kind)
                             " (" (str/join ", "
                                             (get schema/clickstack-metric-insert-columns kind))
                             ")")
                        query)
                     "otel-exporter/metric-insert-columns"
                     "metric insert columns differ from the pinned collector" {:kind kind})
             (check! (= [0 [] [] [] [] []]
                        [(get row "Flags")
                         (get row "Exemplars.FilteredAttributes")
                         (get row "Exemplars.TimeUnix")
                         (get row "Exemplars.Value")
                         (get row "Exemplars.SpanId")
                         (get row "Exemplars.TraceId")])
                     "otel-exporter/metric-unmodeled-defaults"
                     "unmodeled metric fields did not use canonical empty defaults"
                     {:kind kind})
             (check! (= ["resource-schema" "scope-schema" text "1"
                          {"scope.value" (str value)} 0]
                        [(get row "ResourceSchemaUrl") (get row "ScopeSchemaUrl")
                         (get row "ScopeName") (get row "ScopeVersion")
                         (get row "ScopeAttributes")
                         (get row "ScopeDroppedAttrCount")])
                     "otel-exporter/metric-metadata"
                     "representable metric metadata was lost" {:kind kind})))
         (check! (= 0 (get-in rows ["g" 1 "StartTimeUnix"]))
                 "otel-exporter/gauge-zero-start"
                 "absent gauge start time was fabricated" {})
         (check! (= [1 2]
                    [(get-in rows ["s" 1 "AggregationTemporality"])
                     (get-in rows ["h" 1 "AggregationTemporality"])])
                 "otel-exporter/metric-temporality"
                 "metric temporality codes differ from pdata" {}))))))

(def ^:private series-kinds [:gauge :sum :histogram])
(def ^:private series-groups
  [:service-name :metric-unit :scope-name :deployment-environment])
(def ^:private series-buckets [:none :1m :5m :15m :1h])
(def ^:private scalar-series-aggregates
  [:count :sum :min :max :avg :p50 :p95 :p99])
(def ^:private histogram-series-aggregates [:count :sum :avg])
(def ^:private group-result-keys
  {:service-name :servicename
   :metric-unit :metricunit
   :scope-name :scopename
   :deployment-environment :deploymentenvironment})
(def ^:private group-output-keys
  {:service-name :service-name
   :metric-unit :metric-unit
   :scope-name :scope-name
   :deployment-environment :deployment-environment})

(defn- ordered-selection [order selected]
  (vec (filter selected order)))

(defn- metric-series-query-property []
  (h/run-test!
   {:name "bounded metric series query grammar"
    :database "" :verbosity :quiet :derandomize? true :test-cases 160}
   (fn [_]
     (let [kind (h/draw! (g/sampled-from series-kinds))
           aggregate-order (if (= :histogram kind)
                             histogram-series-aggregates
                             scalar-series-aggregates)
           aggregates (ordered-selection
                       aggregate-order
                       (h/draw! (g/set {:min-size 1
                                        :max-size (count aggregate-order)}
                                       (g/sampled-from aggregate-order))))
           groups (ordered-selection
                   series-groups
                   (h/draw! (g/set {:min-size 0
                                     :max-size (count series-groups)}
                                    (g/sampled-from series-groups))))
           bucket (h/draw! (g/sampled-from series-buckets))
           metric-name (h/draw!
                        (g/sampled-from
                         ["queue.depth" "http.server.duration"
                          "metric' OR 1 = 1 --" "line\nbreak" "unicode.λ"]))
           limit (h/draw! (g/integer 1 explorer/max-result-limit))
           start 1700000000000000000
           end (+ start 60000000000)
           row (merge
                (into {} (map (fn [group]
                                [(get group-result-keys group) (name group)]))
                      groups)
                (into {} (map (fn [aggregate]
                                [aggregate (if (= :count aggregate) 2 2.5)]))
                      aggregates)
                (when (not= :none bucket)
                  {:bucketstart start}))
           calls (atom [])
           request {:metric-kind kind :metric-name metric-name
                    :group-by groups :bucket bucket :aggregates aggregates
                    :start-unix-nano start :end-unix-nano end :limit limit}]
       (with-redefs [jdbc/fetch
                     (fn [connection sqlvec options]
                       (swap! calls conj [connection sqlvec options])
                       [row])]
         (let [result (explorer/metric-series :fake-connection request)
               [[connection [sql & params] options]] @calls
               output (first result)
               expected-keys
               (into (set aggregates)
                     (concat (map group-output-keys groups)
                             (when (not= :none bucket)
                               [:bucket-start-unix-nano])))]
           (check! (= 1 (count @calls))
                   "otel-explorer/series-query-count"
                   "a valid generated series request did not execute exactly once"
                   {:request request :calls @calls})
           (check! (= :fake-connection connection)
                   "otel-explorer/series-connection"
                   "metric series did not use the supplied connection"
                   {:request request})
           (check! (= {:max-rows limit} options)
                   "otel-explorer/series-row-bound"
                   "metric series did not propagate its hard result bound"
                   {:request request :options options})
           (check! (and (not (str/includes? sql metric-name))
                        (some #(= metric-name %) params))
                   "otel-explorer/series-parameterization"
                   "caller metric name escaped the JDBC parameter boundary"
                   {:request request :sql sql :params params})
           (check! (= expected-keys (set (keys output)))
                   "otel-explorer/series-output-shape"
                   "metric series output differs from the selected recipe"
                   {:request request :output output})))))))

(defn- counter-property-row [start time value]
  {:starttimenano start :timenano time :servicename "api"
   :streamservice "api" :streammetricunit "{request}"
   :streamscopename "property.metrics" :streamscopeversion "1"
   :streamresourceschemaurl "" :streamscopeschemaurl ""
   :streamresourceattributes {} :streamscopeattributes {}
   :streamattributes {}
   :value (double value) :aggregationtemporality 2 :ismonotonic true})

(defn- cumulative-counter-property []
  (h/run-test!
   {:name "reset-aware cumulative counter series"
    :database "" :verbosity :quiet :derandomize? true :test-cases 160}
   (fn [_]
     (let [point-count (h/draw! (g/integer 1 20))
           increments (h/draw! (g/vector {:size point-count}
                                         (g/integer 0 1000000)))
           durations (h/draw! (g/vector {:size point-count}
                                        (g/integer 1 60)))
           resets (h/draw! (g/vector {:size (dec point-count)} (g/boolean)))
           aggregates-set (h/draw! (g/set {:min-size 1 :max-size 2}
                                          (g/sampled-from [:increase :rate])))
           aggregates (vec (filter aggregates-set [:increase :rate]))
           base 1700000000000000000
           {:keys [rows elapsed reset-count]}
           (loop [index 0, previous-time base, epoch-start base,
                  cumulative 0, rows [], elapsed 0, reset-count 0]
             (if (= index point-count)
               {:rows rows :elapsed elapsed :reset-count reset-count}
               (let [reset? (or (zero? index) (nth resets (dec index)))
                     epoch-start (if reset? previous-time epoch-start)
                     cumulative (if reset? (nth increments index)
                                    (+ cumulative (nth increments index)))
                     duration-nanos (* (nth durations index) 1000000000)
                     time (+ previous-time duration-nanos)]
                 (recur (inc index) time epoch-start cumulative
                        (conj rows (counter-property-row epoch-start time cumulative))
                        (+ elapsed duration-nanos)
                        (+ reset-count (if reset? 1 0))))))
           total-increase (double (reduce + increments))
           request {:metric-kind :sum :temporality :cumulative
                    :monotonic? true :metric-name "counter' private"
                    :group-by [:service-name] :bucket :none
                    :aggregates aggregates :start-unix-nano base
                    :end-unix-nano (+ base (* 21 60 1000000000)) :limit 10}
           calls (atom [])]
       (with-redefs [jdbc/fetch
                     (fn [connection sqlvec options]
                       (swap! calls conj [connection sqlvec options])
                       rows)]
         (let [result (explorer/cumulative-counter-series
                       :fake-connection request)
               output (first result)
               [_ [sql & params] options] (first @calls)]
           (check! (= 1 (count @calls))
                   "otel-explorer/counter-query-count"
                   "counter property did not execute one bounded source query" {})
           (check! (and (if (some #{:increase} aggregates)
                          (= total-increase (:increase output))
                          (not (contains? output :increase)))
                        (= reset-count (:reset-count output))
                        (= point-count (:interval-count output))
                        (= elapsed (:observed-duration-nanos output))
                        (or (not (some #{:rate} aggregates))
                            (= (/ total-increase (/ (double elapsed) 1000000000.0))
                               (:rate output))))
                   "otel-explorer/counter-model"
                   "counter increase/rate differs from the generated reset model"
                   {:request request :output output})
           (check! (and (not (str/includes? sql "counter' private"))
                        (some #(= "counter' private" %) params)
                        (= {:max-rows 10001} options))
                   "otel-explorer/counter-bounds-and-parameters"
                   "counter source query lost parameterization or its hard row cap"
                   {:sql sql :params params :options options})))))))

(def ^:private histogram-property-types
  {:starttimetype "DateTime" :timetype "DateTime" :counttype "UInt64"
   :sumtype "Float64" :bucketcountstype "Array(UInt64)"
   :explicitboundstype "Array(Float64)" :mintype "Float64"
   :maxtype "Float64" :temporalitytype "Int32" :flagstype "UInt32"
   :scopedroppedattrcounttype "UInt32"})

(defn- histogram-property-row [epoch-start time bounds bucket-counts]
  (let [count (reduce + 0 bucket-counts)]
    (merge histogram-property-types
           {:starttimenano epoch-start :timenano time :servicename "api"
            :streamservice "api" :streammetricdescription "property histogram"
            :streammetricunit "ms" :streamscopename "property.metrics"
            :streamscopeversion "1" :streamscopedroppedattrcount 0
            :streamresourceschemaurl ""
            :streamscopeschemaurl "" :streamresourceattributes {}
            :streamscopeattributes {} :streamattributes {}
            ;; Negative observations make cumulative Sum decrease while the
            ;; structural cumulative Count/BucketCounts remain monotonic.
            :count count :sum (* -5.0 count) :bucketcounts bucket-counts
            :explicitbounds bounds :min -100.0 :max 0.0
            :aggregationtemporality 2 :flags 0})))

(defn- model-quantile-bucket [quantile bucket-counts]
  (let [rank (* quantile (double (reduce + 0 bucket-counts)))]
    (loop [index 0, cumulative 0]
      (let [next (+ cumulative (nth bucket-counts index))]
        (if (or (>= next rank) (= index (dec (count bucket-counts))))
          index
          (recur (inc index) next))))))

(defn- cumulative-histogram-property []
  (h/run-test!
   {:name "reset-aware cumulative explicit histogram model"
    :database "" :verbosity :quiet :derandomize? true :test-cases 160}
   (fn [_]
     (let [point-count (h/draw! (g/integer 1 16))
           finite-bound-count (h/draw! (g/integer 1 5))
           bucket-count (inc finite-bound-count)
           bounds (mapv #(double (* 10 (inc %))) (range finite-bound-count))
           increments (h/draw!
                       (g/vector {:size point-count}
                                 (g/vector {:size bucket-count}
                                           (g/integer 0 1000))))
           durations (h/draw! (g/vector {:size point-count}
                                        (g/integer 1 60)))
           resets (h/draw! (g/vector {:size (dec point-count)} (g/boolean)))
           base 1700000000000000000
           {:keys [rows elapsed reset-count]}
           (loop [index 0, previous-time base, epoch-start base,
                  cumulative (vec (repeat bucket-count 0)), rows [], elapsed 0,
                  reset-count 0]
             (if (= index point-count)
               {:rows rows :elapsed elapsed :reset-count reset-count}
               (let [reset? (or (zero? index) (nth resets (dec index)))
                     epoch-start (if reset? previous-time epoch-start)
                     cumulative (if reset? (nth increments index)
                                    (mapv + cumulative (nth increments index)))
                     duration (* (nth durations index) 1000000000)
                     time (+ previous-time duration)]
                 (recur (inc index) time epoch-start cumulative
                        (conj rows (histogram-property-row
                                    epoch-start time bounds cumulative))
                        (+ elapsed duration)
                        (+ reset-count (if reset? 1 0))))))
           expected-buckets (reduce (fn [acc xs] (mapv + acc xs))
                                    (vec (repeat bucket-count 0)) increments)
           expected-count (reduce + 0 expected-buckets)
           request {:metric-kind :histogram :temporality :cumulative
                    :metric-name "histogram' private"
                    :group-by [:service-name] :bucket :none
                    :aggregates [:count :sum :avg :p50 :p95 :p99]
                    :start-unix-nano base
                    :end-unix-nano (+ base (* 17 60 1000000000)) :limit 10}
           calls (atom [])]
       (with-redefs [jdbc/fetch
                     (fn [connection sqlvec options]
                       (swap! calls conj [connection sqlvec options]) rows)]
         (let [[output] (explorer/cumulative-histogram-series
                         :fake-connection request)
               [_ [sql & params] options] (first @calls)]
           (check! (= {:count expected-count
                       :sum (* -5.0 expected-count)
                       :avg (when (pos? expected-count) -5.0)
                       :interval-count point-count :reset-count reset-count
                       :observed-duration-nanos elapsed
                       :explicit-bounds bounds}
                      (select-keys output [:count :sum :avg :interval-count
                                           :reset-count :observed-duration-nanos
                                           :explicit-bounds]))
                   "otel-explorer/histogram-model"
                   "histogram reconstruction differs from generated interval model"
                   {:expected-count expected-count})
           (doseq [[aggregate quantile] [[:p50 0.5] [:p95 0.95] [:p99 0.99]]]
             (let [actual (get output aggregate)]
               (if (zero? expected-count)
                 (check! (nil? actual)
                         "otel-explorer/histogram-empty-quantile"
                         "empty histogram produced a quantile" {})
                 (let [index (model-quantile-bucket quantile expected-buckets)]
                   (check! (and (= quantile (:quantile actual))
                                (= (nth expected-buckets index)
                                   (:bucket-observation-count actual))
                                (= (when (pos? index) (nth bounds (dec index)))
                                   (:lower-bound actual))
                                (= (when (< index (count bounds)) (nth bounds index))
                                   (:upper-bound actual))
                                (= (or (zero? index) (= index (count bounds)))
                                   (nil? (:estimate actual))))
                           "otel-explorer/histogram-quantile-model"
                           "bounded quantile selected the wrong reconstructed bucket"
                           {:aggregate aggregate :bucket-index index})))))
           (check! (and (= 1 (count @calls))
                        (not (str/includes? sql "histogram' private"))
                        (some #(= "histogram' private" %) params)
                        (= {:max-rows 10001} options))
                   "otel-explorer/histogram-bounds-and-parameters"
                   "histogram source query lost parameterization or hard bounds"
                   {})))))))

(defn- large-histogram-rank-property []
  (h/run-test!
   {:name "cumulative histogram exact rank above 2^53"
    :database "" :verbosity :quiet :derandomize? true :test-cases 80}
   (fn [_]
     (let [first-bucket (h/draw! (g/integer 9007199254740992
                                            9007199254840992))
           second-bucket (inc first-bucket)
           total (+ first-bucket second-bucket)
           base 1700000000000000000
           row (histogram-property-row base (+ base 1000000000)
                                       [0.0] [first-bucket second-bucket])
           request {:metric-kind :histogram :temporality :cumulative
                    :metric-name "large.histogram" :group-by [] :bucket :none
                    :aggregates [:p50] :start-unix-nano base
                    :end-unix-nano (+ base 2000000000) :limit 1}]
       (with-redefs [jdbc/fetch (fn [& _] [row])]
         (let [quantile (:p50
                         (first (explorer/cumulative-histogram-series
                                 :fake-connection request)))]
           (check! (and (= 0.0 (:lower-bound quantile))
                        (nil? (:upper-bound quantile))
                        (:upper-unbounded? quantile)
                        (= second-bucket (:bucket-observation-count quantile))
                        (= total (:rank-numerator quantile))
                        (= 2 (:rank-denominator quantile)))
                   "otel-explorer/histogram-exact-large-rank"
                   "p50 bucket selection lost integer precision above 2^53"
                   {:first-bucket first-bucket})))))))

(defn run-properties! []
  [{:label "per-signal lifecycle swarm" :result (lifecycle-property)}
   {:label "durable acknowledgement history"
    :result (durable-barrier-history-property)}
   {:label "Quint Durable success ITF replay"
    :result (durable-itf-replay-property)}
   {:label "concurrent close history" :result (close-race-history-property)}
   {:label "terminal close failure history" :result (close-failure-history-property)}
   {:label "direct structured log body"
    :result (direct-structured-log-body-property)}
   {:label "JSON safety and correlation" :result (wire-json-property)}
   {:label "canonical metric wire rows" :result (metric-wire-property)}
   {:label "bounded metric series query grammar"
    :result (metric-series-query-property)}
   {:label "reset-aware cumulative counter series"
    :result (cumulative-counter-property)}
   {:label "reset-aware cumulative explicit histogram model"
    :result (cumulative-histogram-property)}
   {:label "cumulative histogram exact rank above 2^53"
    :result (large-histogram-rank-property)}])
