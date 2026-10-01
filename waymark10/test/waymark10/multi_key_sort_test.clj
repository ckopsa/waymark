(ns waymark10.multi-key-sort-test
  "A kind's :sortable :default may name several keys, and sort= may
  take a comma list: rows order by the first key, then by the next
  among its ties, and id breaks the last tie. A one-field default reads
  exactly as it always did. Driven over the in-memory twin."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [waymark10.resource :as r]
            [waymark10.server.collections :as collections]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def job
  "Walked by priority first, then by title within a priority."
  (r/resource
   {:kind :job
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:max 80}]]
             [:priority :int]]
    :filterable {:state #{:eq :in}}
    :sortable {:fields [:priority :title :created_at]
               :default ["priority" "title"]}
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A finished job is history."}}}}))

(def chore
  "The one-field default, unchanged."
  (r/resource
   {:kind :chore
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:max 80}]]]
    :filterable {:state #{:eq :in}}
    :sortable {:fields [:title] :default "-title"}
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A finished chore is history."}}}}))

(def ^:dynamic *eng* nil)

(use-fixtures :each
  (fn [f]
    (binding [*eng* (inv/engine {:storage (memory/storage)
                                 :resources [job chore]})]
      (f))))

(def colton (t/principal {:id "colton" :display "Colton"}))
(def opts {:principal colton})

(defn- rdef [kind] (get (inv/resources *eng*) kind))

(defn- envelope [kind params]
  (collections/envelope *eng* (rdef kind) params
                        {:principal colton
                         :now (java.time.Instant/now)
                         :resources (inv/resources *eng*)}))

(defn- items [kind params]
  (get-in (envelope kind params) ["data" "items"]))

(defn- titles [kind params]
  (mapv #(re-find #"^\S+" (get % "summary")) (items kind params)))

(defn- problem-of [thunk]
  (try (thunk) nil
       (catch Exception e (ex-data e))))

(defn- jobs! []
  (doseq [[p t] [[2 "kiwi"] [1 "pear"] [1 "apple"] [2 "fig"]]]
    (inv/create! *eng* :job {:title t :priority p} opts)))

(deftest parse-query-reads-several-keys
  (testing "a one-field default keeps its one-map shape"
    (is (= {:field :title :desc true}
           (:sort (collections/parse-query (rdef :chore) {})))))
  (testing "a vector default orders by each key in turn"
    (is (= {:field :priority :desc false
            :then [{:field :title :desc false}]}
           (:sort (collections/parse-query (rdef :job) {})))))
  (testing "sort= takes a comma list, each key with its own direction"
    (is (= {:field :priority :desc true
            :then [{:field :title :desc false}]}
           (:sort (collections/parse-query (rdef :job)
                                           {"sort" "-priority, title"})))))
  (testing "an unsortable key in the list is one 422 naming it"
    (let [p (problem-of #(collections/parse-query
                          (rdef :job) {"sort" "priority,state"}))
          [msg] (get-in p [:errors "sort"])]
      (is (= :schema-invalid (:waymark10/problem p)))
      (is (re-find #"^state cannot sort" (str msg)))))
  (testing "a single unsortable key keeps its old sentence"
    (let [p (problem-of #(collections/parse-query
                          (rdef :job) {"sort" "state"}))]
      (is (re-find #"^must be one of" (str (first (get-in p [:errors "sort"]))))))))

(deftest multi-key-order-over-the-twin
  (jobs!)
  (testing "the two-field default: priority, then title within it"
    (is (= ["apple" "pear" "fig" "kiwi"] (titles :job {}))))
  (testing "a descending second key"
    (is (= ["pear" "apple" "kiwi" "fig"] (titles :job {"sort" "priority,-title"}))))
  (testing "a descending first key"
    (is (= ["fig" "kiwi" "apple" "pear"] (titles :job {"sort" "-priority,title"}))))
  (testing "one key still sorts alone"
    (is (= ["apple" "fig" "kiwi" "pear"] (titles :job {"sort" "title"}))))
  (testing "the query schema advertises the default as one comma list"
    (is (= "priority,title"
           (get-in (envelope :job {})
                   ["actions" "query" "input" "properties" "sort" "default"])))))

(deftest one-field-default-unchanged
  (doseq [t ["alpha" "gamma" "beta"]]
    (inv/create! *eng* :chore {:title t} opts))
  (is (= ["gamma" "beta" "alpha"] (titles :chore {})))
  (is (= "-title"
         (get-in (envelope :chore {})
                 ["actions" "query" "input" "properties" "sort" "default"]))))

(deftest ties-still-break-on-id
  (dotimes [_ 3]
    (inv/create! *eng* :job {:title "same" :priority 1} opts))
  (let [ids #(mapv (fn [i] (re-find #"[^/]+$" (get i "self"))) (items :job %))
        all (ids {})
        paged (into [] (mapcat #(ids {"page[size]" "1" "page[number]" (str %)}))
                    [1 2 3])]
    (testing "a tie on every key still orders the same way twice"
      (is (= 3 (count (set all))))
      (is (= all (ids {}))))
    (testing "and pages walk it once, with no overlap"
      (is (= all paged)))))
