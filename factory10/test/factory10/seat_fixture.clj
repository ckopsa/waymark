(ns factory10.seat-fixture
  "The seats a factory runs with, as rows (ticket cd402fb6, part 3 of
  epic 400609cf).

  The seats are rows in a database, so the law in this repository
  cannot be read for cracks without some. These four are that some:
  one seat of each sort the house keeps, shaped as the store holds
  them. `factory10.cracks-gate-test` runs the cracks audit over the
  factory's kinds and these seats, and fails on a crack its allowlist
  does not name. They are not the live rows: a seat that changes on
  the house changes here by hand.

  NOT YET GENERATED (ticket 42259e4b). The house now prints its active
  seats in this shape, and `from-export` reads that document into the
  rows the audit takes:

    curl -H \"Authorization: Bearer $TOKEN\" \\
      https://work.kopsa.info/api/-/cracks/seats

  A person's bearer reads it, or a grant that sees the `seat` kind
  whole. Until the four below are replaced by that answer, they are
  still written by hand."
  (:require [waymark10.machine :as machine]
            [waymark10.server.cracks :as cracks]
            [waymark10.server.seats :as seats]))

(defn- seat
  "A seat row; `fires`, when given, the wake entries its door
  subscriptions carry, held as ::cracks/fires as `from-export` reads them."
  ([name data]
   {:id name :state :active :data (assoc data :name name)})
  ([name data fires]
   (assoc (seat name data) ::cracks/fires fires)))

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
                 {:kind "change" :actions ["submit" "stall" "discard"]}]}
        [{:kind "ticket" :actions ["groom"] :filter {:state "open"}}
         {:kind "change" :actions ["fail" "rework" "unstick"]}]))

(def judge-seat
  "Reads a ticket in review and sends it back or finishes it."
  (seat "judge-seat"
        {:walk "ticket"
         :scope [{:kind "ticket"
                  :actions ["return" "finish"]
                  :filter {:state "in_review"}}]}
        [{:kind "ticket"
          :actions ["review" "rejoin_review"]
          :filter {:state "in_review"}}]))

(def ops-seat
  "Classifies a red run. It is written with no fires, so it is woken as
  every walk seat with none is: by a row that arrives under its walk's
  filter."
  (seat "ops-seat"
        {:walk "ci_run"
         :cadence_seconds 900
         :scope [{:kind "ci_run"
                  :actions ["classify_infra" "classify_base_red"
                            "classify_this_change" "supersede"]
                  :filter {:state "red"}}]}))

(def onboarding-seat
  "Works an org_plan with its person: asks the audit's questions, puts
  the plan up and asks to go live, which is held for the owner (ticket
  cd22be20). The film is the director's (ticket e552de65)."
  (seat "onboarding-seat"
        {:walk "org_plan"
         :cadence_seconds 3600
         :scope [{:kind "org_plan"
                  :actions ["ask" "propose" "go_live"]}]}
        [{:kind "org_plan" :actions ["create" "answer" "propose"]}]))

(def director-seat
  "Films a proposed org_plan and hands in the take, which moves it to
  filming for the engine's film pass to land (ticket e552de65). It is
  woken when a plan is put up."
  (seat "director"
        {:walk "org_plan"
         :cadence_seconds 3600
         :scope [{:kind "org_plan"
                  :actions ["hand_in_film"]
                  :filter {:state "proposed"}}]}
        [{:kind "org_plan" :actions ["propose"]}]))

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

(defn from-export
  "The seat rows of a seats document, as `GET /api/-/cracks/seats`
  answers it and `waymark10.server.cracks/seat-export` builds it. A
  seat that prints `fires` carries them as `with-fires` hands them to
  the audit, and is judged by them and not by its walk's default."
  [doc]
  (mapv (fn [s]
          (cond-> (seat (:name s) (dissoc s :name :fires))
            (contains? s :fires) (assoc ::cracks/fires (:fires s))))
        (:seats doc)))

(defn wakes
  "[seats subs]: `seat-rows` with each one's fires written as its
  owner's door subscriptions, one active row for each entry, and the
  fires left off the row, so that only the rows wake them."
  [seat-rows]
  [(mapv #(dissoc % ::cracks/fires) seat-rows)
   (into []
         (mapcat (fn [s]
                   (map-indexed (fn [i e]
                                  {:id (str (:id s) "-wake-" i)
                                   :state :active
                                   :data (seats/wake-subscription s e)})
                                (::cracks/fires s))))
         seat-rows)])

(defn fired
  "`seat-rows` as the audit is handed them: woken by their door
  subscriptions (`wakes`), read through `cracks/with-fires`. Without
  `rdefs`, as `cracks/fired-in` hands them to a read whose seats' door
  subscriptions agree with the fires they are written with: a seat
  that has some carries them as ::fires; one that has none is left to
  its walk's default."
  ([seat-rows]
   (vec seat-rows))
  ([rdefs seat-rows]
   (let [[rows subs] (wakes seat-rows)]
     (cracks/with-fires (into {} (map (juxt :kind identity)) rdefs) rows subs))))

(defn seats
  "The fixture's seats, over the kinds in `rdefs`."
  [rdefs]
  [code-seat judge-seat ops-seat onboarding-seat director-seat (mayor rdefs)])
