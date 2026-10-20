(ns jolt.codec.reader.experimental
  "Explicit experimental String JSON reader on stock Jolt. No data.json dependency.
  Numeric conversion and fallback semantics belong to the supplied pure adapter."
  (:refer-clojure :exclude [read-string])
  (:require [clojure.java.io :as io] [jolt.scheme :as scheme]))

(def ^:private seal (Object.))
(defmacro ^:private embedded [path]
  (if-let [resource (io/resource path)] (slurp resource)
    (throw (ex-info "Missing experimental reader resource" {:type ::unavailable}))))
(def ^:private whole-source (embedded "jolt/codec/reader/whole.ss"))
(def ^:private token-source (embedded "jolt/codec/reader/token.ss"))

(def ^:private backend
  (delay
    (let [parse (scheme/eval-string whole-source)
          token (scheme/eval-string token-source)
          source "{\"a\":[false,null,7,\"\\uD83D\\uDE80\"]}"
          probe (parse source token
                       (fn [text] (if (= "7" text) 7 false)))]
      ;; This checks a small runtime/representation surface, not conformance.
      (when-not (= [{"a" [false nil 7 "🚀"]} (count source)] probe)
        (throw (ex-info "Unsupported experimental reader runtime" {:type ::unavailable})))
      [parse token])))

(deftype ^:private Reader [tag parse token number-reader fallback-reader])

(defn open-reader
  "Capture an explicit reader capability; no global state or native close.
  Requires :number-reader and :fallback-reader, each (source-string options-map).
  Number conversion MUST be pure, strict JSON-token conversion with the caller's
  numeric semantics: it may be attempted before a later whole-document decline.
  Fallback runs at most once and owns errors, unsupported options and malformed
  inputs. Both callbacks must use the same String-keyed JSON representation.
  Each parse owns its cursor/results; callbacks own their concurrency behavior."
  [options]
  (when-not (and (map? options)
                 (= #{:number-reader :fallback-reader} (set (keys options)))
                 (ifn? (:number-reader options)) (ifn? (:fallback-reader options)))
    (throw (ex-info "Invalid experimental reader options" {:type ::invalid-options})))
  (let [[parse token] @backend]
    (Reader. seal parse token (:number-reader options) (:fallback-reader options))))

(defn- checked [reader]
  (when-not (and (instance? Reader reader) (identical? seal (.-tag reader)))
    (throw (ex-info "Invalid experimental reader" {:type ::invalid-reader})))
  reader)

(defn try-read-string
  "Attempt native decoding; return [value end] on success, nil on decline.
  End is a positive scalar-character offset before trailing whitespace, not a
  UTF-8 byte offset. False/nil decoded values are inside the result vector.
  Does not invoke fallback. Number conversion is pure and may precede a later
  decline. Callback/unknown options decline before number conversion. Returned
  data is independently owned; structural checks do not prove backend semantics."
  ([reader source] (try-read-string reader source {}))
  ([reader source options]
   (checked reader)
   (when-not (and (string? source) (map? options))
     (throw (ex-info "Expected String input and reader options map" {:type ::invalid-input})))
   (if-not (every? #{:bigdec :eof-error? :eof-value} (keys options))
       nil
       (let [number-reader (.-number-reader reader)
             decode-number (fn [text]
                             (try (let [value (number-reader text options)]
                                    (if (number? value) value false))
                                  (catch Exception _ false)))
             result ((.-parse reader) source (.-token reader) decode-number)]
         (if (or (nil? result) (false? result))
           nil
           (do
             (when-not (and (vector? result) (= 2 (count result))
                            (integer? (second result))
                            (< 0 (second result))
                            (<= (second result) (count source)))
               (throw (ex-info "Invalid experimental reader result" {:type ::invalid-result})))
             result))))))

(defn read-string
  "Read one immutable String with ordinary String-keyed Jolt maps/vectors.
  Default/number/eof options are forwarded to both adapters. Other options,
  including key/value/extra-data callbacks, bypass the native path entirely.
  Duplicate keys, depth over 64 and unsupported/malformed tokens decline to
  fallback, preserving its behavior rather than narrowing the accepted domain.
  Native success returns the decoded value, including false/nil; no borrowed
  input slices or shared mutable parser scratch are exposed. Not a Reader API."
  ([reader source] (read-string reader source {}))
  ([reader source options]
   (if-let [result (try-read-string reader source options)]
     (first result)
     ((.-fallback-reader reader) source options))))
