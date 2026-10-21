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

(def ^:private trace-field-slots
  (source "otel/exporter/chdb/native_trace_field_slots.ss"))

(defn load-trace-field-slots!
  "Internal bounded default-policy trace projector, or nil for unsupported plans.
  Declines unsupported inputs/policies without callbacks; the caller retains its
  existing indexed fallback. Schema has <=8 distinct fields in <=3 locations.
  Original classifier stays live; no output storage is passed to user code."
  [locations plan key-var contains-var original-key project-var original-projector folder]
  (let [factory (if folder
                  ((scheme/eval-string (str "(lambda (pmap-fold-seq-order) " trace-field-slots ")")) folder)
                  (scheme/eval-string trace-field-slots))]
    (or (factory locations plan key-var contains-var original-key project-var original-projector) nil)))

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
   (load-declared-collector! key-var contains-var original-key nil))
  ([key-var contains-var original-key folder]
   (let [evaluate (fn [source]
                    (if folder
                      ((scheme/eval-string (str "(lambda (pmap-fold-seq-order) " source ")")) folder)
                      (scheme/eval-string source)))
         collect (evaluate declared)
         fast ((evaluate string-declared) key-var contains-var original-key)]
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
