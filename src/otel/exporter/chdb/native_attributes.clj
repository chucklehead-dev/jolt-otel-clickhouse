(ns otel.exporter.chdb.native-attributes
  "Internal source-only bounded attribute projection for the native byte option."
  (:require [clojure.java.io :as io] [jolt.scheme :as scheme]))

(defmacro ^:private native-source []
  (let [url (io/resource "otel/exporter/chdb/native_attributes.ss")
        clone-url (io/resource "otel/exporter/chdb/native_attribute_value_clone.ss")]
    (when-not (and url clone-url)
      (throw (ex-info "Missing native attribute projection resource" {})))
    (str "(let ((clone " (slurp clone-url) ") (generic " (slurp url) ")) "
         "(lambda (m key-var value-var) "
         "(or (clone m key-var value-var) (generic m key-var value-var))))")))

(def ^:private source (native-source))

(defmacro ^:private declared-source []
  (if-let [url (io/resource "otel/exporter/chdb/native_declared_attributes.ss")]
    (slurp url)
    (throw (ex-info "Missing native declared-attribute resource" {}))))

(def ^:private declared (declared-source))

(defn load-declared-collector!
  "Internal bounded built-in map collection, or false on declined layouts. Converter Vars
  stay live per key; raw matching values and duplicates are preserved. No
  shared scratch is used; input map storage is never modified or reused as
  output storage. Wide maps with small string-key outputs use private slots;
  extra admitted keys or unusual normalized keys keep ordered builder fallback."
  [key-var contains-var]
  (let [collect (scheme/eval-string declared)]
    (fn [m wanted] (collect m wanted key-var contains-var))))

(defn load-transform!
  "Return a stateless transform, or false per declined input. Converter Vars
  are resolved for each entry, including bindings and changes during callbacks.
  Caller retains the existing classification boundary before invoking it.
  Bounded collision-free wide string-key HAMTs can copy their immutable shape;
  changed keys switch to ordinary building without repeating converters."
  [key-var value-var]
  (let [project (scheme/eval-string source)]
    (fn [m] (project m key-var value-var))))
