(require '[otel.any-value :as any] '[otel.sdk.tracer :as sdk]
         '[otel.sdk.export :as export]
         '[otel.exporter.memory :as memory] '[otel.resource :as resource]
         '[otel.trace :as trace] '[jolt.scheme :as scheme])
(let [input (into {} (map (fn [i] [(str "key-" i) (case (mod i 3) 0 "s" 1 false i)]) (range 16)))
      sink (memory/exporter)
      provider (sdk/tracer-provider {:resource (resource/resource input)
                                     :processors [(export/simple-processor sink)]})
      tracer (sdk/get-tracer provider {:name "shape-probe" :attributes input})
      builtin? (scheme/eval-string "(lambda (m) (pmap? m))")]
  (try
    (trace/with-span [sp tracer "shape-probe" {:attributes input}]
      (trace/add-event! sp "shape-event" input))
    (let [span (first (memory/spans sink))
          inputs {:span (:attributes span)
                  :resource (get-in span [:resource :attributes])
                  :scope (get-in span [:scope :attributes])
                  :event (get-in span [:events 0 :attributes])}
          report (into {} (for [[location attrs] inputs]
                            [location {:type (str (type attrs)) :count (count attrs)
                                       :values-equal (= input attrs) :builtin-hamt (builtin? attrs)
                                       :native-selected (some? (any/try-scalar-string-map attrs))}]))]
      (assert (every? :values-equal (vals report)))
      (prn {:scope :actual-sdk-ended-span-not-throughput :locations report}))
    (finally (assert (sdk/shutdown! provider)))))
