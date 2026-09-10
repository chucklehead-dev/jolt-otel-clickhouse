(ns otel.exporter.chdb.json
  "JSONEachRow payload writing for the chDB exporter.

  `clojure.data.json` is not used on this path. It writes through a
  `java.io.StringWriter`, and on Jolt a host-object method call the compiler
  cannot prove costs roughly 500 ns: the argument vector is allocated, the
  dispatcher walks its arm list to the jhost arm, the tag and the method name
  are both string-hashed, and the rest args are converted to a list twice.
  Jolt direct-emits interop for exactly three type tags -- String, Keyword and
  StringBuilder -- so data.json's JVM-correct `^Appendable`/`^CharSequence`
  hints select nothing and every append takes the slow path. Measured on Jolt
  0.8.3 with a 512-row batch of ClickStack log rows: data.json 183-220 ms,
  the writer below 12-16 ms.

  Three things make the difference, in order of size:

    * append into a `^StringBuilder`, never a Writer, so the compiler emits
      `sb-append!` directly (~30 ns) instead of dispatching (~500 ns);
    * append whole strings, not characters -- a 64-character bulk append costs
      19.9 ns against 55.6 ns for one character; and
    * skip the escaping loop entirely for strings that need no escaping, which
      is nearly every telemetry string. Scanning with unchecked int comparisons
      and then appending the string in one call replaces a per-character emit.

  Column names are compiled once into constant fragments (`,\"TraceId\":`), so a
  row costs one bulk append per column instead of a quote, an escape scan, an
  append and a colon.

  Output differs textually from data.json in two ways, both semantically
  irrelevant and both deliberate: `/` is not escaped as `\\/`, and non-ASCII is
  emitted as UTF-8 rather than `\\uXXXX`. Both are valid JSON, ClickHouse parses
  both, and avoiding them keeps the common path on the bulk-append branch."
  (:require [clojure.string :as str]))

;; --- strings ----------------------------------------------------------------

(defn- needs-escape?
  "True at the first character JSON requires escaping. Unchecked int
  comparisons: a boxed char comparison costs more than the append it guards."
  [^String s]
  (let [n (.length s)]
    (loop [i 0]
      (if (>= i n)
        false
        (let [c (int (.charAt s i))]
          (if (or (== c 34) (== c 92) (< c 0x20))
            true
            (recur (unchecked-inc i))))))))

(defn- append-escaped! [^StringBuilder sb ^String s]
  (let [n (.length s)]
    (loop [i 0]
      (when (< i n)
        (let [c (int (.charAt s i))]
          (cond
            (== c 34) (.append sb "\\\"")
            (== c 92) (.append sb "\\\\")
            (== c 10) (.append sb "\\n")
            (== c 13) (.append sb "\\r")
            (== c 9) (.append sb "\\t")
            (== c 8) (.append sb "\\b")
            (== c 12) (.append sb "\\f")
            (< c 0x20) (.append sb (format "\\u%04x" c))
            :else (.append sb (char c))))
        (recur (unchecked-inc i))))))

(defn write-string!
  "One quoted JSON string. Bulk-appends when nothing needs escaping."
  [^StringBuilder sb ^String s]
  (.append sb \")
  (if (needs-escape? s)
    (append-escaped! sb s)
    (.append sb s))
  (.append sb \"))

;; --- values -----------------------------------------------------------------

(declare write-value!)

(defn- write-map! [^StringBuilder sb m]
  (.append sb \{)
  (loop [es (seq m) first? true]
    (when-let [e (first es)]
      (when-not first? (.append sb \,))
      (let [k (key e)]
        (write-string! sb (if (string? k)
                            k
                            (if (keyword? k) (subs (str k) 1) (str k)))))
      (.append sb \:)
      (write-value! sb (val e))
      (recur (next es) false)))
  (.append sb \}))

(defn- write-array! [^StringBuilder sb xs]
  (.append sb \[)
  (loop [ys (seq xs) first? true]
    (when-let [y (first ys)]
      (when-not first? (.append sb \,))
      (write-value! sb y)
      (recur (next ys) false)))
  (.append sb \]))

(defn write-value! [^StringBuilder sb v]
  (cond
    (string? v) (write-string! sb v)
    (nil? v) (.append sb "null")
    (integer? v) (.append sb (long v))
    (number? v) (.append sb (str v))
    (true? v) (.append sb "true")
    (false? v) (.append sb "false")
    (map? v) (write-map! sb v)
    (sequential? v) (write-array! sb v)
    (keyword? v) (write-string! sb (subs (str v) 1))
    :else (write-string! sb (str v))))

(defn write-str
  "Drop-in for `clojure.data.json/write-str` on this exporter's value shapes."
  ^String [v]
  (let [sb (StringBuilder. 64)]
    (write-value! sb v)
    (.toString sb)))

;; --- compiled row writers ---------------------------------------------------

(defn compile-insert
  "Precompute everything about a signal's insert that does not vary per row: the
  table, its column order, and one constant fragment per column carrying that
  column's separator, quoted name and colon.

  `columns` must be exactly the key set the row producer emits; `write-rows`
  checks that on the first row of every batch. The driver builds the statement
  from the table and column names itself, so nothing here is SQL."
  [table columns]
  (let [cols (vec columns)]
    {:table table
     :columns cols
     :column-set (set cols)
     ;; A Durable connection records statements in its WAL and replays them on
     ;; recovery, so that path needs the statement rather than table+columns.
     ;; Built once here; the ordinary path never uses it.
     :statement (str "insert into " table
                     (when (seq cols)
                       (str " (" (clojure.string/join ", " cols) ")")))
     ;; {"Timestamp": for the first column, ,"TraceId": for the rest.
     :fragments (vec (map-indexed
                      (fn [i c] (str (if (zero? i) "{\"" ",\"") c "\":"))
                      cols))}))

(defn- check-row!
  "One check per batch, not per row. A row producer that gains or loses a column
  without the compiled spec following would otherwise write a payload chDB
  silently accepts with a column missing."
  [compiled row]
  (when (not= (count row) (count (:columns compiled)))
    (throw (ex-info "chDB insert row does not match its compiled column spec"
                    {:type ::column-mismatch
                     :expected (:columns compiled)
                     :missing (vec (remove (set (keys row)) (:columns compiled)))
                     :unexpected (vec (remove (:column-set compiled) (keys row)))}))))

(defn write-rows
  "The JSONEachRow body: one JSON object per row and nothing else. The INSERT
  statement is the driver's to build, so that it never has to read this data
  as SQL."
  ^String [compiled rows]
  (when-let [row (first rows)]
    (check-row! compiled row))
  (let [fragments (:fragments compiled)
        columns (:columns compiled)
        n (count columns)
        sb (StringBuilder. (max 256 (* (count rows) 1024)))]
    (doseq [row rows]
      (loop [i 0]
        (when (< i n)
          (.append sb ^String (nth fragments i))
          (write-value! sb (get row (nth columns i)))
          (recur (unchecked-inc i))))
      (.append sb "}\n"))
    (.toString sb)))
