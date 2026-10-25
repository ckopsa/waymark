(ns factory10.change-contract-test
  "factory10's change keeps the kit's change contract
  (`factorykit.change-contract`, epic 459ed2eb step 2d). Each clause
  gets a memory engine of its own from `dev/scratch!`, and the sweep is
  told the hour it sweeps at. The memory store holds no index, so the
  second mint of a `change_id` is not tried here; the clause reads the
  declared index alone.

  The ask is a ticket. Its regroom is the person's `groom` and then
  the step a code seat's next sit takes on the change it finds closed
  (`waymark10.server.mcp/reopened-change`), called as the sit calls it.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is]]
            [factory10.main :as main]
            [factory10.mirror :as mirror]
            [factory10.resources.change :refer [change]]
            [factory10.sources.forge :as forge]
            [factorykit.change-contract :as contract]
            [waymark10.dev :as dev]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.mirror :as wm-mirror]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))

(def ^:private hands
  {:source mirror/source-principal
   :person person
   :model (t/principal {:id "a-model" :type :agent})})

(defn- a-hand []
  (let [eng (dev/scratch! (vec (main/resources)))
        row (fn [kind id]
              (store/with-tx (:storage eng)
                (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))
        ticket-of (fn [ask] (subs (str ask) (count "ticket:")))
        moves! (fn [ask door]
                 (inv/invoke! eng :ticket (ticket-of ask) door {}
                              {:principal person}))]
    {:declaration change
     :now (Instant/now)
     :indexes? false
     :repository "ckopsa/waymark"
     :mint! (fn [fields]
              (:row (inv/create! eng :change fields
                                 {:principal mirror/source-principal})))
     :row #(row :change %)
     :walk! (fn [hand id door input]
              (:row (inv/invoke! eng :change (str id) door input
                                 {:principal (hands hand)
                                  :if-match (inv/etag :change (str id)
                                                      (:version (row :change id)))})))
     :ask! (fn []
             (let [made (:row (inv/create! eng :ticket
                                           {:title "An ask the contract builds"
                                            :type "task"
                                            :priority 2
                                            :repo "ckopsa/waymark"}
                                           {:principal person}))
                   ask (str "ticket:" (:id made))]
               (moves! ask :groom)
               ask))
     :unqueue! #(moves! % :ungroom)
     :ask-state #(some-> (:state (row :ticket (ticket-of %))) name keyword)
     :sweep! #(forge/sweep-reader-changes! eng (fn [& _]) %)
     :regroom! (fn [ask closed]
                 (moves! ask :groom)
                 (#'mcp/reopened-change eng closed))
     ;; the mirror's own birth, as discovery mints one (ticket 56c97770)
     :pull-request! (fn []
                      (str (:id (:row (inv/create! eng :pull_request
                                                   {:external_id (str "ckopsa/waymark#"
                                                                      (inc (rand-int 1000000)))}
                                                   {:principal wm-mirror/system-observer
                                                    :mint? true})))))}))

(deftest factory10s-change-keeps-the-kits-contract
  (is (= [] (contract/failures a-hand))))
