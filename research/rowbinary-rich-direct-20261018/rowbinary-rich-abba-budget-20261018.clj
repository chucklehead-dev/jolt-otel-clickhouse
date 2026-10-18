(require '[jdbc.chdb :as rb-rich-driver] '[jdbc.chdb.native :as rb-rich-native]
         '[jdbc.chdb.durable :as rb-rich-durable])
(defn rb-rich-budget-operations []
  ;; Explicit experiment configuration on BOTH writer and replay reader;
  ;; not a library default or a change to the Durable record format.
  (let [open (:open-native! (#'rb-rich-durable/default-open-operations))]
    {:open-native!
     (fn [scratch]
       (let [handle (open scratch)]
         (try
           (rb-rich-driver/execute-any handle "SET max_query_size = 16777216" [])
           (assert (= [[16777216]]
                      (:rows (rb-rich-driver/execute-any
                               handle "SELECT getSetting('max_query_size') AS v" []))))
           handle
           (catch Throwable error (rb-rich-native/close! handle) (throw error)))))}))
