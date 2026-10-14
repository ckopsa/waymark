(ns factory10.ask-contract-test
  "factory10's ticket keeps the kit's ask contract
  (`factorykit.ask-contract`, ticket 50fd9aba). Each clause gets a
  memory engine of its own from `dev/scratch!`. An ending is a
  person's `drop`.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is]]
            [factory10.main :as main]
            [factory10.mirror :as mirror]
            [factory10.resources.ticket :as ticket]
            [factorykit.ask-contract :as contract]
            [waymark10.dev :as dev]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))

(def ^:private hands
  {:person person
   :model (t/principal {:id "a-model" :type :agent})})

(defn- a-hand []
  (let [eng (dev/scratch! (vec (main/resources)))
        row (fn [id]
              (store/with-tx (:storage eng)
                (fn [tx] (store/load-row (:storage eng) tx :ticket (str id) {}))))
        walk! (fn [hand id door input]
                (:row (inv/invoke! eng :ticket (str id) door input
                                   {:principal (hands hand)
                                    :if-match (inv/etag :ticket (str id)
                                                        (:version (row id)))})))
        change (fn [cid]
                 (store/with-tx (:storage eng)
                   (fn [tx] (store/load-row (:storage eng) tx :change (str cid) {}))))
        ;; the change a sit mints beside the ticket, written `stuck` as a
        ;; seat's stall leaves it, with no pull request
        stick! (fn [id]
                 (let [st (:storage eng)
                       cid (str (:id (:row (inv/create! eng :change
                                                        {:change_id (str "ticket:" id)
                                                         :born_from (str "ticket:" id)
                                                         :repository "ckopsa/waymark"
                                                         :title "A change the contract sticks"
                                                         :head_branch (str "bench/" id)}
                                                        {:principal mirror/source-principal}))))]
                   (store/with-tx st
                     (fn [tx]
                       (let [c (store/load-row st tx :change cid {})]
                         (store/save-row! st tx :change
                                          (assoc c :state :stuck
                                                 :version (inc (long (:version c))))
                                          (:version c)))))
                   cid))]
    {:declaration ticket/ticket
     :link ticket/link
     :mint! #(:row (inv/create! eng :ticket
                                {:title "An ask the contract builds"
                                 :type "task"
                                 :priority 2
                                 :repo "ckopsa/waymark"}
                                {:principal person}))
     :row row
     :walk! walk!
     :end! #(walk! :person % :drop {:close_reason "Let go by the contract."})
     :stick! stick!
     :change change}))

(deftest factory10s-ticket-keeps-the-kits-contract
  (is (= [] (contract/failures a-hand))))
