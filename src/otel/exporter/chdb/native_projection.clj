(ns otel.exporter.chdb.native-projection
  "Internal bounded projection for the explicitly selected native byte backend."
  (:require [clojure.java.io :as io] [jolt.scheme :as scheme]))

(defmacro ^:private source [resource]
  (if-let [url (io/resource resource)]
    (slurp url)
    (throw (ex-info "Missing native projection resource" {}))))

(def ^:private declared
  (source "otel/exporter/chdb/native_declared_attributes.ss"))
(def ^:private projected
  (source "otel/exporter/chdb/native_projected_values.ss"))

(defn load-declared-collector!
  "Return a bounded built-in-map collector, declining unsupported layouts.
  Resolve converter and membership Vars per key in source sequence order.
  Preserve every declared duplicate; mutable storage is invocation-owned."
  [key-var contains-var]
  (let [collect (scheme/eval-string declared)]
    (fn [attributes wanted] (collect attributes wanted key-var contains-var))))

(defn load-positional-output!
  "Build private positional output once; resolve the live projector per field.
  Preserve ordinary pair destructuring, field order and reentrant calls."
  [project-var]
  (let [emit (scheme/eval-string projected)]
    (fn [plan collected] (emit plan collected project-var))))
