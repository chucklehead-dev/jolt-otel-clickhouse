;; Diagnostic only: one synthetic SDK/Durable run. Observer timings are not
;; throughput qualification. Aggregate metadata only; no JSON bodies retained.
(require '[clojure.data.json :as json]
         '[clojure.edn :as edn])
(let [[root] *command-line-args*
      original json/read-str
      calls (atom {:calls 0 :characters 0 :nanos 0 :max-characters 0})
      observe (fn [& args]
                (let [start (System/nanoTime)]
                  (try (apply original args)
                       (finally
                         (let [elapsed (- (System/nanoTime) start)
                               n (count (first args))]
                           (swap! calls (fn [state]
                                          (-> state
                                              (update :calls inc)
                                              (update :characters + n)
                                              (update :nanos + elapsed)
                                              (update :max-characters max n)))))))))]
  (with-redefs [json/read-str observe]
    (binding [*command-line-args* [root "on"]]
      (load-file "research/nested-fragments-20261019/durable-arm.clj")))
  (let [writer (edn/read-string (slurp (str root "/writer.edn")))
        result {:scope :instrumented-whole-process-json-read-str
                :includes-startup-and-close true :timing-overhead-included true
                :stock-jolt "0.8.23" :json-reader :ordinary
                :rows (:rows writer) :confirmed-window-nanos (:confirmed-window-nanos writer)
                :profile @calls
                :whole-process-reader-nanos-over-window
                (/ (double (:nanos @calls)) (:confirmed-window-nanos writer))}]
    (spit (str root "/metadata-profile.edn") (pr-str result))
    (prn result)))
