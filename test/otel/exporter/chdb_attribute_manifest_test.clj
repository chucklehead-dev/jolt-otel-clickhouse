(ns otel.exporter.chdb-attribute-manifest-test
  (:require [clojure.string :as str]
            [otel.attribute-schema :as attribute-schema]
            [otel.attribute-schema.discovery :as discovery]
            [otel.exporter.chdb-attribute-bundle-fixture :as bundle-fixture]
            [otel.exporter.chdb.attribute-identity :as identity]
            [otel.exporter.chdb.attribute-manifest :as manifest]))

(def ^:private binding
  {:dataset-id "telemetry-prod"
   :application-id "naval-battle"
   :lineage "game-v1"
   :version 7})

(defn- reviewed [authority source entries]
  {:schema manifest/reviewed-fragment-schema
   :authority authority
   :source source
   :entries
   (mapv
    (fn [{:keys [location] :as entry}]
      (merge
       (case location
         :log-attributes {:signal :logs :table "otel_logs"}
         :metric-attributes {:signal :metrics :table "otel_metrics_gauge"}
         {:signal :spans :table "otel_traces"})
       entry))
    entries)})

(defn- inferred [source text]
  (attribute-schema/analyze-source
   (attribute-schema/read-forms source text)))

(defn- compile* [fragments]
  (manifest/compile-manifest (assoc binding :fragments (vec fragments))))

(defn- compile-bundle*
  ([bundle]
   (manifest/compile-bundle-manifest (assoc binding :bundle bundle)))
  ([bundle reviewed-fragments]
   (manifest/compile-bundle-manifest
    (assoc binding :bundle bundle :reviewed-fragments reviewed-fragments))))

(defn- artifact [revision]
  (bundle-fixture/revision-artifact
   "io.github.example/checkout-lib"
   "https://github.com/example/checkout-lib"
   revision))

(defn- bundle-item [artifact path fragment]
  {:artifact artifact :path path :fragment fragment})

(defn- thrown-data [f]
  (try (f) nil (catch Throwable error (ex-data error))))

(defn- by-key [compiled key]
  (first (filter #(= key (:key %)) (:fields compiled))))

(defn run [check]
  (println "deterministic typed attribute manifests")
  (let [reviewed-a
        (reviewed
         :semantic-convention "conventions/game.edn"
         [{:location :span-attributes :key "game.score" :type :int64}
          {:location :span-attributes :key "game-score" :type :int64}
          {:location :scope-attributes :key "build.channel" :type :string}])
        reviewed-b
        (reviewed
         :advice "advice/game.edn"
         [{:location :span-attributes :key "game.score" :type :int64}])
        source
        (inferred
         "src/game.clj"
         "(ns game (:require [otel.trace :as trace]))
          (trace/set-attributes! span {\"zero\" 0 \"disabled\" false
                                      \"minimum\" -9223372036854775808
                                      \"maximum\" 9223372036854775807})")
        compiled (compile* [reviewed-a reviewed-b source])
        permuted
        (compile*
         [source reviewed-b
          (update reviewed-a :entries #(vec (reverse %)))])
        score (by-key compiled "game.score")
        dashed (by-key compiled "game-score")]
    (check "fragment and entry order cannot change the manifest"
           compiled permuted)
    (check "canonical rendering is byte-identical"
           (manifest/render compiled) (manifest/render permuted))
    (check "compiled output uses the closed manifest schema"
           manifest/manifest-schema (:schema compiled))
    (check "checksum is lowercase SHA-256"
           true (boolean (re-matches #"[0-9a-f]{64}" (:checksum compiled))))
    (check "known manifest checksum is stable"
           "3ec476fd8d3c0e275d952e26045c7c2648491b6b4b184333dfbb541e0aa264e1"
           (:checksum compiled))
    (check "all initial promoted types use the closed ClickHouse map"
           #{"String" "Bool" "Int64"}
           (set (map :clickhouse-type (:fields compiled))))
    (check "compiler vocabularies are closed"
           [#{:semantic-convention :advice :runtime-reviewed}
            #{:span-attributes :resource-attributes :scope-attributes
              :log-attributes :metric-attributes}
            #{:spans :logs :metrics}
            {"otel_logs" :logs
             "otel_metrics_gauge" :metrics
             "otel_metrics_histogram" :metrics
             "otel_metrics_sum" :metrics
             "otel_traces" :spans}
            {:string "String" :boolean "Bool" :int64 "Int64"}]
           [manifest/authorities manifest/locations manifest/signals
            manifest/table-signals manifest/clickhouse-types])
    (check "integer and Boolean literals remain typed"
           [[:boolean "Bool"] [:int64 "Int64"]]
           (->> ["disabled" "zero"]
                (map #(select-keys (by-key compiled %) [:type :clickhouse-type]))
                (map (juxt :type :clickhouse-type))
                vec))
    (check "signed Int64 boundaries are promotable"
           [:int64 :int64]
           (mapv #(get-in (by-key compiled %) [:type]) ["minimum" "maximum"]))
    (check "identical declarations merge ordered provenance"
           [:semantic-convention :advice]
           (mapv :authority (:provenance score)))
    (check "sanitization collisions retain distinct digest suffixes"
           true
           (and (not= (get-in score [:physical :value-column])
                      (get-in dashed [:physical :value-column]))
                (str/includes? (get-in score [:physical :value-column])
                               "tr_sp_game_score_")
                (str/includes? (get-in dashed [:physical :value-column])
                               "tr_sp_game_score_")))
    (check "physical identifiers are bounded"
           true
           (every? #(<= (count %) 63)
                   (mapcat (comp vals :physical) (:fields compiled))))
    (check "manifest has no ambient path, clock, or schema URL"
           true
           (let [rendered (manifest/render compiled)]
             (and (not (str/includes? rendered "/home/"))
                  (not (str/includes? rendered ":timestamp"))
                  (not (str/includes? rendered "schema-url")))))
    (check "trusted deployment binding changes physical identity"
           true
           (let [other (manifest/compile-manifest
                        (assoc binding :application-id "naval-battle-canary"
                               :fragments [reviewed-a reviewed-b source]))]
             (not= (mapv :physical (:fields compiled))
                   (mapv :physical (:fields other)))))
    (let [same-key "deployment.environment.name"
          target-entries
          (vec
           (for [[signal table] [[:spans "otel_traces"]
                                 [:logs "otel_logs"]
                                 [:metrics "otel_metrics_gauge"]]
                 location [:resource-attributes :scope-attributes]]
             {:signal signal :table table :location location
              :key same-key :type :string}))
          targeted (manifest/compile-manifest
                    (assoc binding :fragments
                           [{:schema manifest/reviewed-fragment-schema
                             :authority :advice :source "advice/targets.edn"
                             :entries target-entries}]))]
      (check "trace, log, and metric resource/scope identities are disjoint"
             [6 6 6]
             [(count (:fields targeted))
              (count (set (map :id (:fields targeted))))
              (count (set (map :physical (:fields targeted))))]))
    (let [cross-signal
          (manifest/compile-manifest
           (assoc binding :fragments
                  [{:schema manifest/reviewed-fragment-schema
                    :authority :advice :source "advice/cross-signal.edn"
                    :entries
                    [{:signal :spans :table "otel_traces"
                      :location :resource-attributes
                      :key "shared" :type :int64}
                     {:signal :logs :table "otel_logs"
                      :location :resource-attributes
                      :key "shared" :type :string}]}]))]
      (check "same location/key on distinct signals does not type-conflict"
             #{[:spans "otel_traces" :int64]
               [:logs "otel_logs" :string]}
             (set (map (juxt :signal :table :type) (:fields cross-signal)))))
    (let [tables ["otel_metrics_gauge"
                  "otel_metrics_sum"
                  "otel_metrics_histogram"]
          manifests
          (mapv
           (fn [table]
             (manifest/compile-manifest
              (assoc binding :fragments
                     [{:schema manifest/reviewed-fragment-schema
                       :authority :advice
                       :source "advice/metric-tables.edn"
                       :entries
                       [{:signal :metrics :table table
                         :location :metric-attributes
                         :key "game.score" :type :int64}]}])))
           tables)
          fields (mapv (comp first :fields) manifests)]
      (check "gauge, sum, and histogram identities are disjoint"
             [3 3 3]
             [(count (set (map :checksum manifests)))
              (count (set (map :id fields)))
              (count (set (map :physical fields)))])
      (check "metric table codes remain visible in physical columns"
             ["av_mg_mt_" "av_ms_mt_" "av_mh_mt_"]
             (mapv (fn [field]
                     (let [prefix (str "av_" (identity/target-code field) "_")]
                       (subs (get-in field [:physical :value-column])
                             0 (count prefix))))
                   fields)))
    (check "physical identifier mutation fails validation"
           :otel.exporter.chdb.attribute-manifest/invalid-manifest-field
           (:type
            (thrown-data
             #(manifest/validate-manifest
               (assoc-in compiled [:fields 0 :physical :value-column]
                         "av_attacker_controlled")))))
    (check "cross-signal table mutation fails before checksum acceptance"
           :otel.exporter.chdb.attribute-manifest/invalid-manifest-field
           (:type
            (thrown-data
             #(manifest/validate-manifest
               (assoc-in compiled [:fields 0 :signal] :logs)))))
    (check "legacy compiled manifests require explicit migration"
           :otel.exporter.chdb.attribute-manifest/legacy-manifest
           (:type
            (thrown-data
             #(manifest/validate-manifest
               (assoc compiled :schema manifest/legacy-manifest-schema))))))

  (let [problem-fragment
        (inferred
         "src/shared.clj"
         "(ns bundled (:require [otel.trace :as trace]))
          (trace/set-attribute! span \"bundle.count\" (long value))
          (trace/set-attribute! span \"bundle.unknown\" value)
          (trace/set-attribute! span \"bundle.invalid\" 9223372036854775808)
          (trace/set-attribute! span \"bundle.ratio\" 1.5)
          (trace/set-attribute! span \"bundle.mixed\" (long value))
          (trace/set-attribute! span \"bundle.mixed\" (double value))
          (trace/set-attribute! span dynamic-key 1)")
        flag-fragment
        (inferred
         "src/shared.clj"
         "(ns bundled.flag (:require [otel.trace :as trace]))
          (trace/set-attribute! span \"bundle.flag\" false)")
        first-item
        (bundle-item
         (artifact "1111111111111111111111111111111111111111")
         "META-INF/otel/attribute-schema/checkout-lib.edn"
         problem-fragment)
        second-item
        (bundle-item
         (bundle-fixture/revision-artifact
          "local/checkout-advice"
          "https://github.com/example/checkout-advice"
          "2222222222222222222222222222222222222222")
         "META-INF/otel/attribute-schema/checkout-advice.edn"
         flag-fragment)
        forward-bundle (bundle-fixture/discovered-bundle
                        [first-item second-item])
        reverse-bundle (bundle-fixture/discovered-bundle
                        [second-item first-item])
        compiled (compile-bundle* forward-bundle)
        permuted (compile-bundle* reverse-bundle)
        diagnostics (set (map :code (:diagnostics compiled)))]
    (check "bundle compiler emits the distinct closed v3 manifest"
           manifest/bundle-manifest-schema (:schema compiled))
    (check "shuffled discovery input yields byte-identical bundle manifests"
           (manifest/render compiled) (manifest/render permuted))
    (check "compact provenance retains all identities without the merged schema"
           [(:fragments forward-bundle)
            (discovery/content-sha256 (discovery/render forward-bundle))
            false]
           [(get-in compiled [:bundle :fragments])
            (get-in compiled [:bundle :sha256])
            (contains? (:bundle compiled) :attribute-schema)])
    (check "safe bundle evidence promotes only the supported scalar fields"
           #{["bundle.count" :int64] ["bundle.flag" :boolean]}
           (set (map (juxt :key :type) (:fields compiled))))
    (check "unknown dynamic invalid conflicting and unsupported evidence stays diagnostic"
           #{:unknown-inference :dynamic-key :invalid-inference
             :conflicting-inference :unsupported-type}
           diagnostics)
    (let [reviewed-fragment
          (reviewed :advice "operator/checkout.edn"
                    [{:location :span-attributes
                      :key "bundle.reviewed" :type :string}])
          with-reviewed (compile-bundle* forward-bundle [reviewed-fragment])]
      (check "optional reviewed declarations remain an explicit operator input"
             [:string :advice]
             [(get-in (by-key with-reviewed "bundle.reviewed") [:type])
              (get-in (by-key with-reviewed "bundle.reviewed")
                      [:provenance 0 :authority])]))
    (check "tampered bundle fails through the upstream validator before a manifest exists"
           :invalid-bundle
           (:reason
            (thrown-data
             #(compile-bundle* (assoc forward-bundle :unchecked true)))))
    (let [mismatch
          (inferred
           "src/private.clj"
           "(ns private (:require [otel.resource :as resource]))
            (resource/resource {:service.name 42})")]
      (check "render-time semantic-convention tampering cannot enter a manifest"
             :mismatch
             (:otel.semantic-conventions/error
              (thrown-data
               #(compile-bundle*
                 (assoc forward-bundle :attribute-schema mismatch))))))
    (check "bundle input cannot inject a registry lifecycle state"
           :otel.exporter.chdb.attribute-manifest/invalid-input
           (:type
            (thrown-data
             #(manifest/compile-bundle-manifest
               (assoc binding :bundle forward-bundle :state :active))))))

  (let [fragment
        (inferred
         "src/shared.clj"
         "(ns bundled.identity (:require [otel.trace :as trace]))
          (trace/set-attribute! span \"bundle.identity\" (long value))")
        make-bundle
        (fn [revision]
          (bundle-fixture/discovered-bundle
           [(bundle-item
             (artifact revision)
             "META-INF/otel/attribute-schema/checkout-lib.edn"
             fragment)]))
        first-bundle (make-bundle "1111111111111111111111111111111111111111")
        second-bundle (make-bundle "3333333333333333333333333333333333333333")
        first-manifest (compile-bundle* first-bundle)
        second-manifest (compile-bundle* second-bundle)]
    (check "artifact-identity-only bundle drift changes the manifest checksum"
           [true true true]
           [(= (:fields first-manifest) (:fields second-manifest))
            (not= (get-in first-manifest [:bundle :sha256])
                  (get-in second-manifest [:bundle :sha256]))
            (not= (:checksum first-manifest) (:checksum second-manifest))])
    (let [provenance-var
          (ns-resolve 'otel.exporter.chdb.attribute-manifest
                      'bundle-provenance)
          mutant-distinct?
          (with-redefs-fn
            {provenance-var
             (fn [_]
               (sorted-map :fragments []
                           :schema discovery/bundle-schema-id
                           :sha256 (apply str (repeat 64 "0"))))}
            #(not= (:checksum (compile-bundle* first-bundle))
                   (:checksum (compile-bundle* second-bundle))))]
      (check "identity-stripping mutant defeats the checksum distinction"
             false mutant-distinct?)))

  (let [problem-source
        (inferred
         "src/problem.clj"
         "(ns problem (:require [otel.trace :as trace]))
          (trace/set-attribute! span \"mixed\" (long value))
          (trace/set-attribute! span \"mixed\" (double value))
          (trace/set-attribute! span \"unknown\" value)
          (trace/set-attribute! span \"overflow\" 9223372036854775808)
          (trace/set-attribute! span \"ratio\" 1.5)
          (trace/set-attribute! span dynamic-key 1)")
        compiled (compile* [problem-source])]
    (check "unsafe inference never promotes a field" [] (:fields compiled))
    (check "unknown invalid conflict unsupported and dynamic stay explicit"
           #{:conflicting-inference :unknown-inference :invalid-inference
             :unsupported-type :dynamic-key}
           (set (map :code (:diagnostics compiled))))
    (check "int64 and double conflict is not widened"
           [:double :int64]
           (:types (first (filter #(= "mixed" (:key %))
                                  (:diagnostics compiled))))))

  (let [resource-source
        (inferred
         "src/resource.clj"
         "(ns resource (:require [otel.resource :as resource]))
          (resource/resource {\"deployment.environment.name\" \"prod\"
                              \"unknown\" value})")
        compiled (compile* [resource-source])]
    (check "target ambiguity does not mask stronger inference diagnostics"
           [[] {"deployment.environment.name" :ambiguous-target
                "unknown" :unknown-inference}]
           [(:fields compiled)
            (into {} (map (juxt :key :code) (:diagnostics compiled)))]))

  (let [left (reviewed :advice "advice/left.edn"
                       [{:location :log-attributes :key "attempt" :type :int64}])
        right (reviewed :runtime-reviewed "review/right.edn"
                        [{:location :log-attributes :key "attempt" :type :string}])
        forward (thrown-data #(compile* [left right]))
        reverse-order (thrown-data #(compile* [right left]))]
    (check "reviewed type conflict fails closed"
           :otel.exporter.chdb.attribute-manifest/type-conflict (:type forward))
    (check "conflict diagnostics are traversal-order independent"
           forward reverse-order))

  (check "untrusted ClickHouse types are rejected"
         :otel.exporter.chdb.attribute-manifest/invalid-type
         (:type
          (thrown-data
           #(compile*
             [(reviewed :advice "advice/bad.edn"
                        [{:location :span-attributes :key "x"
                          :type "Int64 CODEC(NONE)"}])]))))
  (check "absolute provenance paths are rejected"
         :otel.attribute-schema/invalid-schema-source
         (:type
          (thrown-data
           #(compile*
             [(reviewed :advice "/tmp/bad.edn"
                        [{:location :span-attributes :key "x" :type :int64}])]))))
  (check "telemetry schema URLs cannot enter trusted binding"
         :otel.exporter.chdb.attribute-manifest/invalid-input
         (:type
          (thrown-data
           #(manifest/compile-manifest
             (assoc binding :fragments []
                    :schema-url "https://telemetry.invalid/select-me")))))
  (check "legacy reviewed fragments are not guessed into a v2 target"
         :otel.exporter.chdb.attribute-manifest/legacy-reviewed-fragment
         (:type
          (thrown-data
           #(compile*
             [{:schema manifest/legacy-reviewed-fragment-schema
               :authority :advice :source "advice/legacy.edn"
               :entries [{:location :resource-attributes
                          :key "ambiguous" :type :string}]}]))))
  (check "a signal cannot authorize another signal's physical table"
         :otel.exporter.chdb.attribute-manifest/invalid-target
         (:type
          (thrown-data
           #(manifest/compile-manifest
             (assoc binding :fragments
                    [{:schema manifest/reviewed-fragment-schema
                      :authority :advice :source "advice/wrong-table.edn"
                      :entries
                      [{:signal :logs :table "otel_traces"
                        :location :resource-attributes
                        :key "service.name" :type :string}]}])))))
  (doseq [[label binding-key malformed]
          [["malformed dataset identity is rejected" :dataset-id "bad/dataset"]
           ["malformed application identity is rejected" :application-id ""]
           ["malformed lineage identity is rejected" :lineage "bad lineage"]]]
    (check label
           :otel.exporter.chdb.attribute-manifest/invalid-binding
           (:type
            (thrown-data
             #(manifest/compile-manifest
               (assoc binding binding-key malformed :fragments []))))))
  (doseq [[label malformed]
          [["zero manifest version is rejected" 0]
           ["negative manifest version is rejected" -1]
           ["overflowing manifest version is rejected" 9223372036854775808]]]
    (check label
           :otel.exporter.chdb.attribute-manifest/invalid-binding
           (:type
            (thrown-data
             #(manifest/compile-manifest
               (assoc binding :version malformed :fragments []))))))
  (check "attribute keys longer than 256 characters are rejected"
         :otel.exporter.chdb.attribute-manifest/invalid-key
         (:type
          (thrown-data
           #(compile*
             [(reviewed :advice "advice/oversized-key.edn"
                        [{:location :span-attributes
                          :key (apply str (repeat 257 "k"))
                          :type :string}])]))))
  (check "more than 4096 fragments are rejected before consumption"
         :otel.exporter.chdb.attribute-manifest/invalid-input
         (:type
          (thrown-data
           #(compile*
             (repeat 4097
                     (reviewed :advice "advice/empty.edn" []))))))
  (check "exactly 4096 fragments compile"
         [[] []]
         (let [compiled
               (compile*
                (repeat 4096
                        (reviewed :advice "advice/empty.edn" [])))]
           [(:fields compiled) (:diagnostics compiled)]))
  (let [entry {:location :span-attributes :key "shared" :type :string}]
    (check "more than 65536 total entries are rejected before compilation"
           :otel.exporter.chdb.attribute-manifest/too-many-entries
           (:type
            (thrown-data
             #(compile*
               [(reviewed :advice "advice/first.edn"
                          (vec (repeat 32768 entry)))
                (reviewed :advice "advice/second.edn"
                          (vec (repeat 32769 entry)))]))))
    (check "exactly 65536 total entries compile"
           [1 "shared" [:advice]]
           (let [compiled
                 (compile*
                  [(reviewed :advice "advice/maximum.edn"
                             (vec (repeat 65536 entry)))])]
             [(count (:fields compiled))
              (get-in compiled [:fields 0 :key])
              (mapv :authority (get-in compiled [:fields 0 :provenance]))])))
  (let [max-binding-id (str "b" (apply str (repeat 127 "x")))
        max-key (apply str (repeat 256 "k"))
        compiled
        (manifest/compile-manifest
         {:dataset-id max-binding-id
          :application-id max-binding-id
          :lineage max-binding-id
          :version 9223372036854775807
          :fragments
          [(reviewed :advice "advice/boundary.edn"
                     [{:location :metric-attributes
                       :key max-key
                       :type :int64}])]})]
    (check "maximum valid binding version and key boundaries compile"
           [max-binding-id max-binding-id max-binding-id
            9223372036854775807 max-key]
           [(:dataset-id compiled) (:application-id compiled)
            (:lineage compiled) (:version compiled)
            (get-in compiled [:fields 0 :key])]))
  (let [compiled (compile* [])]
    (check "checksum mutation fails validation"
           :otel.exporter.chdb.attribute-manifest/checksum-mismatch
           (:type
            (thrown-data
             #(manifest/validate-manifest
               (assoc compiled :checksum (apply str (repeat 64 "0")))))))))
