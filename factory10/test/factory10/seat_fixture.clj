(ns factory10.seat-fixture
  "The seats a factory runs with, as rows (ticket cd402fb6, part 3 of
  epic 400609cf).

  The seats are rows in a database, so the law in this repository
  cannot be read for cracks without some. These four are that some:
  one seat of each sort the house keeps, shaped as the store holds
  them. `factory10.cracks-gate-test` runs the cracks audit over the
  factory's kinds and these seats, and fails on a crack its allowlist
  does not name. They are not the live rows: a seat that changes on
  the house changes here by hand."
  (:require [waymark10.machine :as machine]))

(defn- seat [name data]
  {:id name :state :active :data (assoc data :name name)})

(def code-seat
  "Builds an open ticket and submits the change beside it. It is woken
  when a ticket is groomed into its walk and when its change comes
  back to it; the hourly cadence is the net under both."
  (seat "code-seat"
        {:walk "ticket"
         :cadence_seconds 3600
         :scope [{:kind "ticket"
                  :actions ["complete" "ungroom"]
                  :filter {:state "open"}}
                 {:kind "change" :actions ["submit" "stall" "discard"]}]
         :wake_on [{:kind "ticket" :actions ["groom"] :filter {:state "open"}}
                   {:kind "change" :actions ["fail" "rework" "unstick"]}]}))

(def judge-seat
  "Reads a ticket in review and sends it back or finishes it."
  (seat "judge-seat"
        {:walk "ticket"
         :scope [{:kind "ticket"
                  :actions ["return" "finish"]
                  :filter {:state "in_review"}}]
         :wake_on [{:kind "ticket"
                    :actions ["review" "rejoin_review"]
                    :filter {:state "in_review"}}]}))

(def ops-seat
  "Classifies a red run. It wrote no wake_on, so it is woken as every
  walk seat with none is: by a row that arrives under its walk's
  filter."
  (seat "ops-seat"
        {:walk "ci_run"
         :cadence_seconds 900
         :scope [{:kind "ci_run"
                  :actions ["classify_infra" "classify_base_red"
                            "classify_this_change" "supersede"]
                  :filter {:state "red"}}]}))

(defn mayor
  "The seat a person sits in. It holds every door of every kind in
  `rdefs`, so its scope is read from the law and not written here: a
  new door is the mayor's the day it is declared."
  [rdefs]
  (seat "mayor"
        {:mode "interactive"
         :scope (vec (for [r (sort-by (comp name :kind) rdefs)]
                       {:kind (name (:kind r))
                        :actions (into ["create"]
                                       (sort (map (comp name :name)
                                                  (machine/actions-seq r))))}))}))

(defn seats
  "The fixture's seats, over the kinds in `rdefs`."
  [rdefs]
  [code-seat judge-seat ops-seat (mayor rdefs)])
