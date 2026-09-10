(ns otel.exporter.chdb-attribute-manifest-test
  (:require [clojure.string :as str]
            [otel.attribute-schema :as attribute-schema]
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
   :entries entries})

(defn- inferred [source text]
  (attribute-schema/analyze-source
   (attribute-schema/read-forms source text)))

(defn- compile* [fragments]
  (manifest/compile-manifest (assoc binding :fragments (vec fragments))))

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
           "129f39bf15b38ed4f51fc7ac52556cb648d52a8a15d0922fec842637d4dc1bb4"
           (:checksum compiled))
    (check "all initial promoted types use the closed ClickHouse map"
           #{"String" "Bool" "Int64"}
           (set (map :clickhouse-type (:fields compiled))))
    (check "compiler vocabularies are closed"
           [#{:semantic-convention :advice :runtime-reviewed}
            #{:span-attributes :resource-attributes :scope-attributes
              :log-attributes :metric-attributes}
            {:string "String" :boolean "Bool" :int64 "Int64"}]
           [manifest/authorities manifest/locations manifest/clickhouse-types])
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
                               "sp_game_score_")
                (str/includes? (get-in dashed [:physical :value-column])
                               "sp_game_score_")))
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
    (check "physical identifier mutation fails validation"
           :otel.exporter.chdb.attribute-manifest/invalid-manifest-field
           (:type
            (thrown-data
             #(manifest/validate-manifest
               (assoc-in compiled [:fields 0 :physical :value-column]
                         "av_attacker_controlled"))))))

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
  (let [compiled (compile* [])]
    (check "checksum mutation fails validation"
           :otel.exporter.chdb.attribute-manifest/checksum-mismatch
           (:type
            (thrown-data
             #(manifest/validate-manifest
               (assoc compiled :checksum (apply str (repeat 64 "0")))))))))
