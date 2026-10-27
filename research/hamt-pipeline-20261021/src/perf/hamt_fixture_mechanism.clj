(ns perf.hamt-fixture-mechanism
  "Source-only mechanism check using the actual rich fixture and exporter handoff.
  Private probes are deliberate here: no timings, SDK worker or Durable writer."
  (:require [otel.any-value :as any]
            [otel.exporter.chdb :as exporter]
            [perf.public-pipeline :as fixture]))

(defn- check [ok label]
  (when-not ok (throw (ex-info "HAMT fixture mechanism failed" {:label label}))))

(defn- arm [enabled? rows]
  (reset! exporter/research-hamt-selection enabled?)
  (let [context (#'exporter/load-experimental-span-codec! true true true true true)
        project (#'exporter/payload-attrs-converter ((:payload-cache-factory context)))
        original @#'any/original-try-scalar-string-map
        copies (atom {:span 0 :other 0}) views (atom {:span 0 :other 0})
        payloads
        (with-redefs [any/original-try-scalar-string-map
                      (fn [v]
                        (swap! copies update (if (= 16 (count v)) :span :other) inc)
                        (original v))]
          (mapv (fn [span]
                  (mapv (fn [[kind input]]
                          (let [out (project input)]
                            (check out :nonempty-projection)
                            (when-not (map? out) (swap! views update kind inc))
                            out))
                        [[:span (:attributes span)]
                         [:other (get-in span [:resource :attributes])]
                         [:other (get-in span [:scope :attributes])]
                         [:other (get-in span [:events 0 :attributes])]])) rows))]
    (check (= (if enabled? 1 (count rows)) (:span @copies)) :plain-copy-control)
    (check (= (if enabled? (dec (count rows)) 0) (:span @views)) :plain-view-activation)
    (check (pos? (:other @views)) :sorted-view-activation)
    {:counts {:enabled? enabled? :copying-helper-calls @copies :opaque-views @views}
     :bytes (vec ((:encode context) "" payloads identity 1000000))}))

(defn -main []
  (let [rows (fixture/fixture 8) disabled (arm false rows) enabled (arm true rows)]
    (check (= (:bytes disabled) (:bytes enabled)) :all-nested-payload-byte-equivalence)
    (prn {:scope :source-rich-fixture-exporter-handoff-mechanism
          :passed true :rows (count rows) :both-arms-original-policy-anchors true
          :worker-and-durable-used false :timing-claim false
          :arms [(:counts disabled) (:counts enabled)]})))
