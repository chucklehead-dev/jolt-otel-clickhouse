(ns otel.exporter.chdb.native-attributes
  "Internal source-only bounded attribute projection for the native byte option."
  (:require [clojure.java.io :as io] [jolt.scheme :as scheme]))

(defmacro ^:private native-source []
  (if-let [url (io/resource "otel/exporter/chdb/native_attributes.ss")]
    (slurp url)
    (throw (ex-info "Missing native attribute projection resource" {}))))

(def ^:private source (native-source))

(defmacro ^:private declared-source []
  (if-let [url (io/resource "otel/exporter/chdb/native_declared_attributes.ss")]
    (slurp url)
    (throw (ex-info "Missing native declared-attribute resource" {}))))

(def ^:private declared (declared-source))

(defn load-declared-collector!
  "Internal small-map collection, or false on declined layouts. Converter Vars
  stay live per key; raw matching values and duplicates are preserved. No
  shared scratch is used; input map storage is never modified or reused as
  output storage."
  [key-var contains-var]
  (let [collect (scheme/eval-string declared)]
    (fn [m wanted] (collect m wanted key-var contains-var))))

(defn load-transform!
  "Return a stateless transform, or false per declined input. Converter Vars
  are resolved for each entry, including bindings and changes during callbacks.
  Caller retains the existing classification boundary before invoking it."
  [key-var value-var]
  (let [project (scheme/eval-string source)]
    (fn [m] (project m key-var value-var))))
