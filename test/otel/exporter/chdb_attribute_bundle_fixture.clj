(ns otel.exporter.chdb-attribute-bundle-fixture
  (:require [otel.attribute-schema :as attribute-schema]
            [otel.attribute-schema.discovery :as discovery]))

(defn inferred
  "Analyze one source string into a canonical attribute-schema fragment."
  [source text]
  (attribute-schema/analyze-source
   (attribute-schema/read-forms source text)))

(defn discovered-bundle
  "Build a real validated discovery bundle from explicit test artifact inputs.

  Each item contains :artifact, :path, and an attribute-schema :fragment."
  [items]
  (let [prepared
        (mapv
         (fn [{:keys [artifact path fragment]}]
           (let [text (attribute-schema/render fragment)]
             {:index
              {:schema discovery/index-schema-id
               :artifact artifact
               :fragments [{:path path
                            :sha256 (discovery/content-sha256 text)}]}
              :path path
              :text text}))
         items)
        resources (into {} (map (juxt :path :text)) prepared)]
    (discovery/discover (mapv (comp pr-str :index) prepared)
                        #(get resources %))))

(defn revision-artifact [package repository revision]
  {:package package :repository repository :revision revision})
