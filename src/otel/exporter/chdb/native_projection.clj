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
(def ^:private string-declared
  (source "otel/exporter/chdb/native_declared_strings.ss"))

(defn load-declared-collector!
  "Return a bounded built-in-map collector, declining unsupported layouts.
  Resolve converter and membership Vars per key in source sequence order.
  Preserve every declared duplicate; mutable storage is invocation-owned.
  The internal third argument must be the defining namespace's original
  string-identity normalizer, not a consumer snapshot. While that normalizer
  and stock membership are live, pure string-key maps can be collected without
  redundant callbacks. All other cases retain the ordered generic path."
  ([key-var contains-var] (load-declared-collector! key-var contains-var false))
  ([key-var contains-var original-key]
   (let [collect (scheme/eval-string declared)
         fast ((scheme/eval-string string-declared) key-var contains-var original-key)]
     (fn [attributes wanted]
       (or (when original-key (fast attributes wanted))
           (collect attributes wanted key-var contains-var))))))

(defn load-positional-output!
  "Build private positional output once; resolve the live projector per field.
  Preserve ordinary pair destructuring, field order and reentrant calls.
  The internal second argument is the defining namespace's original projector,
  which supports a continuation. Other live roots keep the two-argument path."
  ([project-var] (load-positional-output! project-var false))
  ([project-var original-projector]
   (let [factory (scheme/eval-string
                   (str "(let ((emit " projected ")) "
                        "(lambda (project-var original) "
                        "(when original "
                        "(unless (and (top-level-bound? 'jolt-invoke3) "
                        "(procedure? (top-level-value 'jolt-invoke3)) "
                        "(bitwise-bit-set? (procedure-arity-mask (top-level-value 'jolt-invoke3)) 4)) "
                        "(error 'native-projection \"Runtime continuation bridge is unavailable\"))) "
                        "(lambda (plan collected) (emit plan collected project-var original))))"))
         emit (factory project-var original-projector)]
     (fn [plan collected] (emit plan collected)))))
