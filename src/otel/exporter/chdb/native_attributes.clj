(ns otel.exporter.chdb.native-attributes
  "Internal source-only exact-slot attribute projection for the native byte option."
  (:require [clojure.java.io :as io] [jolt.scheme :as scheme]))

(defmacro ^:private native-source []
  (if-let [url (io/resource "otel/exporter/chdb/native_attributes.ss")]
    (slurp url)
    (throw (ex-info "Missing native attribute projection resource" {}))))

(def ^:private source (native-source))

(defn load-transform!
  "Return a stateless transform, or false per declined input. Converter Vars
  are resolved for each entry, including bindings and changes during callbacks.
  Caller retains the existing classification boundary before invoking it."
  [key-var value-var]
  (let [project (scheme/eval-string source)]
    (fn [m] (project m key-var value-var))))
