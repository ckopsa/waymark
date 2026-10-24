(ns waymark10.row-judge-test
  "Ticket a5ba0927: one filter, two readings, one answer. Each filter
  runs through `collections/parse-query` to SQL over Postgres and
  through `collections/row-matches?` over the same rows in memory, and
  the two name the same rows: :absent-as, each suffix, presence, the
  comma :in split, an array field and an OR of maps. Needs the test
  database (WAYMARK10_TEST_DSN)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.collections :as collections]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:private parcel
  (r/resource
   {:kind :rj_parcel
    :plural "rj_parcels"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:domain {:optional true} [:maybe [:string {:max 40}]]]
             [:size {:optional true} [:maybe :int]]
             [:due {:optional true} [:maybe :waymark/date]]
             [:note {:optional true} [:maybe [:string {:max 80}]]]
             [:tags {:optional true} [:vector [:waymark/vocab {:open true}]]]]
    :filterable {:state #{:eq :in}
                 :domain #{:eq :in :ne}
                 :size #{:range}
                 :due #{:after :before}
                 :note #{:set :contains}}
    :absent-as {:domain "factory"}
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A finished parcel is history."}}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- with-st [f]
  (let [st (pg/storage db/dsn)]
    (try (f st) (finally (pg/close! st)))))

(defn- fresh! []
  (with-st
    (fn [st]
      (store/with-tx st
        (fn [tx]
          (doseq [table ["rj_parcels" "waymark10_transitions"
                         "waymark10_idempotency" "waymark10_drafts"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")])))))))

(def ^:private seeds
  [{:title "bare"}
   {:title "home" :domain "home" :size 3 :due "2026-10-01"
    :note "fragile glass" :tags ["red" "blue"]}
   {:title "factory" :domain "factory" :size 7 :due "2026-11-15"
    :tags ["green"]}
   {:title "yard" :domain "yard" :size 12 :note "by the gate"}])

(def ^:private filters
  "Each filter map, and the titles both readings must name."
  [[{:domain "factory"} #{"bare" "factory"}]
   [{:domain "home"} #{"home"}]
   [{:domain "home,factory"} #{"bare" "home" "factory"}]
   [{:domain_ne "factory"} #{"home" "yard"}]
   [{:domain_ne "home"} #{"bare" "factory" "yard"}]
   [{:size_gte "7"} #{"factory" "yard"}]
   [{:size_lte "7"} #{"home" "factory"}]
   [{:due_after "2026-10-15"} #{"factory"}]
   [{:due_before "2026-10-15"} #{"home"}]
   [{:note_contains "glass"} #{"home"}]
   [{:note_set "true"} #{"home" "yard"}]
   [{:note_set "false"} #{"bare" "factory"}]
   [{:tags "red"} #{"home"}]
   [{:tags "green,red"} #{"home" "factory"}]
   [{:tags "purple"} #{}]
   [{:state "done"} #{"yard"}]
   [{:state :open :domain "factory"} #{"bare" "factory"}]])

(deftest each-filter-reads-the-same-in-sql-and-in-memory
  (fresh!)
  (with-st
    (fn [st]
      (let [eng (inv/engine {:storage st :resources [parcel]})
            rdef (get (inv/resources eng) :rj_parcel)
            ids (into {} (map (fn [d] [(:title d)
                                       (get-in (inv/create! eng :rj_parcel d
                                                            {:principal elena})
                                               [:row :id])]))
                      seeds)
            _ (inv/invoke! eng :rj_parcel (get ids "yard") :finish {}
                           {:principal elena})
            search #(store/with-tx st (fn [tx] (store/search-rows st tx :rj_parcel % {:all true})))
            rows (search [])
            titles #(into #{} (map (fn [row] (get-in row [:data :title]))) %)
            sql (fn [fm]
                  (titles (search (:conds (collections/parse-query
                                           rdef (collections/filter-params fm)
                                           {:defaults? false})))))
            judged (fn [fms] (titles (filter #(collections/row-matches? rdef % fms) rows)))]
        (is (= 4 (count rows)))
        (doseq [[fm want] filters]
          (testing (pr-str fm)
            (is (= want (sql fm)) "the SQL reading")
            (is (= (sql fm) (judged [fm])) "the row judge agrees")))
        (testing "several maps are any of them"
          (is (= #{"home" "yard"} (judged [{:domain "home"} {:size_gte "10"}]))))
        (testing "a filter the query refuses matches nothing"
          (is (= #{} (judged [{:colour "red"}]))))))))
