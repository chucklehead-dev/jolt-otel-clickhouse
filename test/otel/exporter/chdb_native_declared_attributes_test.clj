(ns otel.exporter.chdb-native-declared-attributes-test
  (:require [clojure.test :refer [deftest is]]
            [otel.exporter.chdb.attribute-projection :as p]
            [otel.exporter.chdb.native-attributes :as native]
            [otel.exporter.chdb-typed-compact-vector-test :as vectors]
            [otel.exporter.chdb-attribute-projection-test :as fixture]
            [otel.exporter.chdb-compact-span-layout-test :as spans]))

(defn observe [f span]
  (let [calls (atom []) original @#'p/key-string]
    (with-redefs [p/key-string (fn [key] (swap! calls conj key) (original key))]
      [(f span) @calls])))

(deftest native-collection-is-equivalent-and-declines-other-map-layouts
  (doseq [mixed? [false true]]
    (let [{:keys [capability target columns map-projector vector-projector]} (vectors/plans mixed?)
          native-projector (p/trace-vector-projector capability target true)
          inputs [{}
                  (array-map "checkout.count" 9007199254740993 "checkout.name" "" "checkout.complete" false)
                  (array-map "checkout.count" nil "checkout.complete" "wrong" :unused {:x 1})
                  (array-map :unused "keep" :checkout.count 7 "checkout.count" 7)
                  (into {} (map (fn [n] [(str "unused-" n) n]) (range 20)))
                  (into (hash-map "Aa" 1 "BB" 2)
                        (map (fn [n] [(str "unused-" n) n]) (range 20)))
                  (array-map "shared.location" "span")]]
      (doseq [attrs inputs]
        (let [span (assoc (@#'fixture/span attrs)
                         :resource {:attributes (array-map "shared.location" "resource")}
                         :scope {:attributes (array-map "shared.location" "")})]
          (is (= (observe vector-projector span) (observe native-projector span)))
          (is (= (mapv (map-projector span) (subvec columns (count spans/columns)))
                 (native-projector span))))))))

(deftest direct-small-map-collection-preserves-duplicates-and-live-vars
  (let [collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains?)
        wanted #{"checkout.count"}
        attrs (array-map :unused "keep" :checkout.count nil "checkout.count" false)]
    (is (= {"checkout.count" [nil false]} (collect attrs wanted)))
    (is (false? (collect (into {} (map (fn [n] [(str n) n]) (range 20))) wanted)))
    (let [calls (atom []) original @#'p/key-string
          replacement (fn [key] (swap! calls conj [:new key]) (original key))
          first-key (fn [key]
                      (swap! calls conj [:old key])
                      (alter-var-root #'p/key-string (constantly replacement))
                      (original key))]
      (with-redefs [p/key-string first-key]
        (is (= {"checkout.count" [nil false]} (collect attrs wanted)))
        (is (= [[:old :unused] [:new :checkout.count] [:new "checkout.count"]] @calls))))))

(deftest reentrant-and-failed-collections-do-not-share-storage
  (let [collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains?)
        original @#'p/key-string wanted #{"checkout.count"}
        nested (atom nil)
        attrs (array-map :checkout.count 1 :unused 0 "checkout.count" 2)]
    (with-redefs [p/key-string (fn [key]
                                (when (= :unused key)
                                  (reset! nested (collect {"checkout.count" 999} wanted)))
                                (original key))]
      (is (= {"checkout.count" [1 2]} (collect attrs wanted))))
    (is (= {"checkout.count" [999]} @nested))
    (with-redefs [p/key-string (fn [_] (throw (ex-info "converter failed" {:reason :test})))]
      (is (thrown? clojure.lang.ExceptionInfo (collect attrs wanted))))
    (is (= {"checkout.count" [1 2]} (collect attrs wanted)))
    (is (= {"checkout.count" [999]} @nested))))

(deftest actual-native-projector-selects-small-map-collector
  (let [{:keys [capability target]} (vectors/plans false)
        selected (atom 0)
        collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains?)
        project (p/trace-vector-projector capability target true)]
    (with-redefs [p/small-declared-collector
                  (delay (fn [attrs wanted]
                           (let [out (collect attrs wanted)]
                             (when out (swap! selected inc)) out)))]
      (is (vector? (project (@#'fixture/span {"checkout.count" 7}))))
      (is (pos? @selected)))))
