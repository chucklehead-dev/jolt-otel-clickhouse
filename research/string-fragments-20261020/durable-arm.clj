;; Actual SDK queue/exporter/Durable pipeline; deterministic SDK-shaped records,
;; not live tracer/app capture. Equal but distinct resource/scope/event roots.
(require '[clojure.string :as str] '[clojure.edn :as edn]
         '[clojure.java.io :as io] '[jolt.scheme :as scheme]
         '[jolt.codec.experimental :as codec] '[jdbc.chdb.native :as native]
         '[jolt.codec.scalar-text.experimental :as shapes]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture])
(def support "/home/chuck/ai-src/worktrees/exporter-stock-nested-fragments-20261019/")
(load-file (str support "research/library-nested-text-20261019/nested-sorted-sdk-fixture-20261018.clj"))
(let [[root arm] *command-line-args*
      _ (assert (contains? #{"off" "on"} arm))
      source (slurp (str support "research/library-nested-text-20261019/rich-sdk-scaling-screen-20261019.clj"))
      replacements [["'[clojure.data.json.jolt-native :as json-native]" ""]
                    [":operations {:json-reader (json-native/load-reader!)}" ":operations {}"]
                    ["/home/chuck/ai-src/evidence/trace-affinity-scaling-helpers-20261019.clj"
                     (str support "research/library-nested-text-20261019/trace-affinity-scaling-helpers-20261019.clj")]
                    [":owned-statement-output? true"
                     (str ":owned-statement-output? true :experimental-span-codec? true"
                          " :experimental-span-payload-cache? true :experimental-span-flat-map-cache? true"
                          " :experimental-span-nested-fragment-cache? true"
                          " :experimental-span-shape-plans? " true)]]
      staged (reduce (fn [text [needle replacement]]
                       (assert (= 2 (count (str/split text (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
                       (str/replace text needle replacement)) source replacements)
      original-open codec/open-codec
      original fixture/spans factory (shapes/native-plan-factory)
      constructors (atom 0) latest (atom nil)
      observed-factory (fn [& args]
                         (let [dispatch (apply factory args)]
                           (swap! constructors inc) (reset! latest dispatch) dispatch))
      same-root? (scheme/eval-string "(lambda (a b) (eq? (jolt-ref-get a kw-tree) (jolt-ref-get b kw-tree)))")
      make-records (fn [region]
                     (let [shared (nested-sorted-sdk-shape (original region))
                           distinct (mapv (fn [span]
                                            (-> span
                                                (update-in [:resource :attributes] #(into (sorted-map) %))
                                                (update-in [:scope :attributes] #(into (sorted-map) %))
                                                (update :events (fn [events]
                                                                  (mapv #(update % :attributes (fn [m] (into (sorted-map) m))) events))))) shared)]
                       (assert (= shared distinct))
                       (when (> (count distinct) 1)
                         (assert (same-root? (get-in shared [0 :resource :attributes]) (get-in shared [1 :resource :attributes])))
                         (assert (not (same-root? (get-in distinct [0 :resource :attributes]) (get-in distinct [1 :resource :attributes])))))
                       distinct))]
  (assert (str/includes? (str (io/resource "otel/any_value.clj")) "14f58e5a57d6cd1760451a426e40d8cddcf53949"))
  (assert (str/includes? (str (io/resource "jolt/codec/scalar_text/experimental.clj")) "/home/chuck/ai-src/worktrees/exporter-codec-string-fragments-20261020"))
  (native/ensure-loaded!)
  (assert (= "26.9.0" (native/chdb-version)))
  (with-redefs [fixture/spans make-records shapes/native-plan-factory (fn [] observed-factory)
                codec/open-codec (fn [options] (original-open (assoc options :string-fragment-cache? (= arm "on"))))]
    (binding [*command-line-args* [root "1" "saturated"]] (load-string staged)))
  (let [path (str root "/writer.edn") data (edn/read-string (slurp path))
        stats (when-let [dispatch @latest] (dispatch 2 nil))]
    (assert (= 36 @constructors))
    (assert (<= 1 (first stats) 8))
    (spit path (pr-str (assoc data :string-fragments-arm arm :shape-plans-arm "on" :sharing :equal-distinct-roots
                             :shape-planner-constructors @constructors :last-shape-stats stats
                             :producer-representation-controls true :runtime "official-stock-0.8.23"
                             :sdk-source (str (io/resource "otel/any_value.clj"))
                             :codec-source (str (io/resource "jolt/codec/scalar_text/experimental.clj"))
                             :exporter-source (str (io/resource "otel/exporter/chdb.clj")))))))
