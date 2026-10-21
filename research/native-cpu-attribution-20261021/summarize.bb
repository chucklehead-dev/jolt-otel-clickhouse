(require '[clojure.string :as str] '[clojure.edn :as edn] '[clojure.java.io :as io])
(defn read-text [path]
  (if (str/ends-with? path ".gz")
    (with-open [stream (java.util.zip.GZIPInputStream. (io/input-stream path))]
      (slurp stream))
    (slurp path)))
(defn ensure! [ok label]
  (when-not ok (throw (ex-info "Invalid CPU sample evidence" {:label label}))))
(defn parse-sample [line]
  (let [[_ comm pid tid seconds fraction period ip symbol dso offset]
        (re-matches #"\s*(.*?)\s+(\d+)/(\d+)\s+(\d+)\.(\d{9}):\s+(\d+)\s+([0-9a-f]+)\s+(.*?)\s+\((.*)\+0x([0-9a-f]+)\)" line)]
    (ensure! comm :sample-format)
    {:comm comm :pid (parse-long pid) :tid (parse-long tid)
     :ns (+ (* (parse-long seconds) 1000000000) (parse-long fraction))
     :period (parse-long period) :dso dso
     :offset (Long/parseUnsignedLong offset 16)}))
(defn metrics [samples total]
  {:samples (count samples) :period-ns (reduce + (map :period samples))
   :period-percent (* 100.0 (/ (reduce + (map :period samples)) total))})
(let [[sample-path writer-path output] *command-line-args*
      samples (mapv parse-sample (str/split-lines (read-text sample-path)))
      writer (edn/read-string (slurp writer-path))
      total (reduce + (map :period samples))
      start (apply min (map :ns samples)) end (apply max (map :ns samples))
      pid (:pid (first samples))
      dso-name #(if (str/starts-with? (:dso %) "/tmp/perf-") :anonymous-jit
                   (last (str/split (:dso %) #"/")))
      copy? #(and (str/ends-with? (:dso %) "/libchdb.so")
                  (<= 0x9020400 (:offset %) (dec 0x902044a)))
      copies (filterv copy? samples)
      report {:scope :whole-process-user-cpu-sampling :samples (count samples)
              :period-ns total :first-sample-ns start :last-sample-ns end :pid pid
              :whole-profile-dsos (into {} (for [[dso values] (group-by dso-name samples)]
                                           [dso (metrics values total)]))
              :threads (into (sorted-map)
                         (for [[tid values] (group-by :tid samples)]
                           [tid (assoc (metrics values total)
                                       :main-thread? (= pid tid)
                                       :names (vec (sort (distinct (map :comm values)))))]))
              :native-copy-loop {:file-offset-range [0x9020400 0x902044a]
                                 :elf-vma-range [0x9021400 0x902144a]
                                 :metrics (metrics copies total)
                                 :first-sample-ns (when (seq copies) (apply min (map :ns copies)))
                                 :last-sample-ns (when (seq copies) (apply max (map :ns copies)))
                                 :tids (vec (sort (distinct (map :tid copies))))}
              :two-second-bins
              (into (sorted-map)
                (for [[bin values] (group-by #(quot (- (:ns %) start) 2000000000) samples)]
                  [bin {:samples (count values) :period-ns (reduce + (map :period values))
                        :dsos (into {} (map (fn [[dso xs]] [dso (count xs)]) (group-by dso-name values)))
                        :copy-loop-samples (count (filter copy? values))}]))
              :writer-geometry (select-keys writer [:stored-rows :rounds :warmups :batch-size])
              :clock-domains-aligned false :per-callback-attribution false
              :function-level-jit-attribution false :population-tail-qualified false}]
  (ensure! (seq samples) :nonempty)
  (ensure! (every? #(and (= pid (:pid %)) (pos? (:period %))) samples) :one-process-positive-periods)
  (ensure! (= [175000 32 3 5000] (mapv writer [:stored-rows :rounds :warmups :batch-size])) :writer-geometry)
  (spit output (pr-str report))
  (prn (select-keys report [:samples :period-ns :whole-profile-dsos :threads :native-copy-loop])))
