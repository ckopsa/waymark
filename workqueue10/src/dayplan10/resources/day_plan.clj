(ns dayplan10.resources.day-plan
  "The DayPlan resource: one date, one member, one shape — a workday
  or a day off — and the blocks the day is made of (docs/spec-dayplan.md
  § day_plan). The shape is the one thing the clock decides: Monday
  through Friday is a workday, the weekend is off, and a caller who
  names a shape at the create door overrides it. One plan per (member,
  date) is declared :unique — the index refuses the duplicate as a 409.

  MATERIALISATION is this kind's :on-create, plan.clj's fan-out one
  module over: for every active context whose default_shapes holds the
  plan's shape, one block is born through the ctx :create door — in
  default_order, carrying the context's default windows on this date
  as instants in the household zone (dayplan10.zone) — and the block's
  own create births its spans, the grandchildren landing right after
  it. Nothing re-reads a template into a day that exists: after birth
  the day's blocks are the truth.

  The doors: set marks the plan decided; replan opens a set plan again
  while at least one window is still ahead and refuses, naming the
  close door, once the day is spent; reshape names the other shape and
  re-materialises the windows still ahead — spans that have begun are
  history and are not touched, a block whose context survives into the
  new shape keeps its row (and, one slice on, its decisions) and gets
  the new shape's ahead windows, a block whose context leaves the
  shape is skipped unless one of its windows has already begun; close
  ends the day, its blocks and spans standing as the record (the
  plan_day precedent: no cascade out of a close).

  Spelled :schema + :actions with a :create-schema that leaves the
  shape optional — the create form asks only what a person decides."
  (:require [dayplan10.zone :as zone]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario expr-guard]]
            [waymark10.types :as t])
  (:import (java.time DayOfWeek Instant LocalDate)))

;; ── the shape default ───────────────────────────────────────────────

(defn shape-of
  "The clock's pick for a date: the weekend is off, every other day is
  a workday. The one seam an inferred context would ever hook into
  (the spec's recorded punt) — one function, and nothing else moves."
  [^LocalDate date]
  (if (contains? #{DayOfWeek/SATURDAY DayOfWeek/SUNDAY} (.getDayOfWeek date))
    "off"
    "workday"))

;; ── reading the templates ───────────────────────────────────────────

(defn- contexts-of-shape
  "The active contexts that materialise for a shape, in default_order
  then name — the order the day's blocks are minted in."
  [ctx shape]
  (->> ((:find ctx) :context {:state "active"} {:limit 500})
       (filter #(some #{shape} (get-in % [:data :default_shapes])))
       (sort-by (juxt #(long (or (get-in % [:data :default_order]) 0))
                      #(str (get-in % [:data :name]))))))

(defn- windows-on
  "A context's default windows as instants on a date, malformed pairs
  dropped (the context's own door refuses those; this is the belt)."
  [context ^LocalDate date]
  (into [] (keep #(zone/window->instants date %))
        (get-in context [:data :default_spans])))

(defn- ahead?
  "A window still ahead of the clock — one that has not begun."
  [{:keys [^Instant starts_at]} ^Instant now]
  (.isAfter starts_at now))

;; ── create ──────────────────────────────────────────────────────────

(defn- materialise-blocks
  "A blank shape takes the weekday's; then one block per active context
  of the shape, born WITH its windows — the block's create births the
  spans, so a span's block_id reads a real parent. The spans cannot
  collide by construction: context's no-overlap-in-shape holds that
  two active templates of one shape never share a minute, so the
  windows this reads are clear of each other before any is minted."
  [row ctx]
  (let [^LocalDate date (get-in row [:data :date])
        shape (or (get-in row [:data :shape]) (shape-of date))]
    (doseq [c (contexts-of-shape ctx shape)]
      ((:create ctx) :block {:plan_id (:id row)
                             :context_id (:id c)
                             :windows (windows-on c date)}))
    (assoc-in row [:data :shape] shape)))

;; ── the guards ──────────────────────────────────────────────────────

(defguardfn something-still-ahead
  {:reads [:span :now]
   :remedies [:day_plan/close]
   :explain "Every window of this day has passed — there is nothing ahead to replan. Close the day instead; its blocks stand as the record."}
  [row _inp ctx]
  (if (nil? (:find ctx))
    (t/allow)
    (let [now (:now ctx)
          spans ((:find ctx) :span {:plan_id (:id row) :state "planned"} {:limit 500})]
      (if (some #(ahead? (:data %) now) spans)
        (t/allow)
        (t/deny)))))

(def a-different-shape
  (expr-guard {:name :a-different-shape
               :when '(not= (input :shape) (data :shape))
               :explain "This is already a {shape} day; name the other shape to reshape it."
               :vars {:shape '(data :shape)}}))

;; ── reshape ─────────────────────────────────────────────────────────

(defhandler rematerialise-ahead [row inp ctx]
  ;; THE WINDOWS STILL AHEAD ARE REPLACED; nothing that has begun moves.
  ;; Three passes, each through the child's own door so the log reads
  ;; the reshape as one correlated story: (1) every planned span of the
  ;; day that has not begun is skipped, whatever its block; (2) each
  ;; context of the new shape gets its ahead windows — onto its
  ;; standing block when it survives, onto a new block when the shape
  ;; brings it; (3) a planned block whose context left the shape is
  ;; skipped, unless one of its windows already began, in which case
  ;; the block stays as the record of the morning that happened.
  (let [now ^Instant (:now ctx)
        ^LocalDate date (get-in row [:data :date])
        shape (:shape inp)
        pid (:id row)
        spans ((:find ctx) :span {:plan_id pid} {:limit 500})
        blocks ((:find ctx) :block {:plan_id pid :state "planned"} {:limit 500})
        begun-blocks (into #{}
                           (comp (remove #(ahead? (:data %) now))
                                 (map #(get-in % [:data :block_id])))
                           spans)
        contexts (contexts-of-shape ctx shape)
        surviving (set (map :id contexts))
        block-of (into {} (map (juxt #(str (get-in % [:data :context_id])) identity))
                       blocks)]
    ;; (1) the ahead spans go
    (doseq [s spans
            :when (and (= :planned (:state s)) (ahead? (:data s) now))]
      ((:invoke ctx) :span (:id s) :skip nil))
    ;; (2) the new shape's ahead windows arrive
    (doseq [c contexts
            :let [windows (filterv #(ahead? % now) (windows-on c date))]]
      (if-some [b (block-of (str (:id c)))]
        (doseq [w windows]
          ((:create ctx) :span (assoc w :block_id (:id b) :plan_id pid)))
        ((:create ctx) :block {:plan_id pid :context_id (:id c)
                               :windows windows})))
    ;; (3) the blocks the shape left behind
    (doseq [b blocks
            :when (and (not (contains? surviving (str (get-in b [:data :context_id]))))
                       (not (contains? begun-blocks (:id b))))]
      ((:invoke ctx) :block (:id b) :skip nil))
    (assoc-in row [:data :shape] shape)))

;; ── the law, written down as scenarios ──────────────────────────────

(defscenario reshape-names-the-other-shape
  "Reshaping a workday into a workday would skip and re-mint every
   window ahead for nothing; the door asks for the other shape."
  {:kind    :day_plan
   :attempt :reshape
   :row     {:state :set
             :data {:date "2099-01-05" :member "01HZQ7Y7F2R3W4V5X6Y7Z8A9C1"
                    :shape "workday"}}
   :input   {:shape "workday"}
   :as      {:id "colton" :type :person}
   :expect  {:refused :a-different-shape
             :because "already a workday day"}})

(defscenario a-spent-day-is-not-replanned
  "Once every window of the day has passed there is nothing left to
   replan, and the refusal names the door that fits — close."
  {:kind    :day_plan
   :attempt :replan
   :row     {:state :set
             :data {:date "2020-01-06" :member "01HZQ7Y7F2R3W4V5X6Y7Z8A9C2"
                    :shape "workday"}}
   :at      "2026-09-05T12:00:00Z"
   :as      {:id "colton" :type :person}
   :expect  {:refused :something-still-ahead
             :remedies [:day_plan/close]}})

;; ── the declaration ─────────────────────────────────────────────────

(defresource day-plan
  {:kind :day_plan
   :plural "day_plans"
   :nav :primary
   :states [:drafting :set :closed]
   :initial :drafting
   :terminal #{:closed}
   :summary "{data.date} · {data.shape} · {state}"
   :label-template "{data.date}"
   :unique [[:member :date]]
   ;; state is no entry, so it filters from here; date, member and shape
   ;; carry their own :filter/:sort (one home per concern)
   :filterable {:state #{:eq :in}}
   :schema [:map
            [:date {:filter #{:eq :range} :sort :default-desc
                    :x-display {:label "Which day"
                                :help "The day this plan is for — one plan per member per date."}}
             :waymark/date]
            [:member {:kind :member :filter #{:eq} :label :member_name
                      :x-display {:label "Whose day"
                                  :help "The member this day belongs to; the feed reads their current block."}}
             :waymark/ref]
            [:shape {:filter #{:eq}
                     :x-display {:label "Shape of the day"
                                 :choices {"workday" "A workday — the weekday default"
                                           "off" "A day off — the weekend default"}}}
             [:enum "workday" "off"]]
            [:notes {:optional true
                     :examples ["Dentist at two — keep the afternoon light."]
                     :x-display {:widget "prose"
                                 :label "Notes for the day"
                                 :help "Anything that shapes the whole day and no one block — an appointment, a guest, a late start."}}
             [:maybe [:string {:max 2000}]]]]
   ;; the create form asks only what a person decides: a blank shape
   ;; takes the weekday's
   :create-schema [:map
                   [:date {:x-display {:label "Which day"
                                       :help "The day this plan is for — one plan per member per date."}}
                    :waymark/date]
                   [:member {:kind :member
                             :x-display {:label "Whose day"
                                         :help "The member this day belongs to."}}
                    :waymark/ref]
                   [:shape {:optional true
                            :x-display {:label "Shape of the day"
                                        :help "Leave it blank and the weekday decides: Monday to Friday a workday, the weekend off."
                                        :choices {"workday" "A workday"
                                                  "off" "A day off"}}}
                    [:maybe [:enum "workday" "off"]]]
                   [:notes {:optional true
                            :examples ["Dentist at two — keep the afternoon light."]
                            :x-display {:widget "prose"
                                        :label "Notes for the day"
                                        :help "Anything that shapes the whole day and no one block."}}
                    [:maybe [:string {:max 2000}]]]]
   :on-create materialise-blocks
   :owns {:blocks {:kind :block :via :plan_id}}
   :links [{:rel "blocks" :owns :block :embed true
            :summary "The day's blocks, each a context's presence on the day"}]
   :actions
   {:set
    {:from #{:drafting} :to :set
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Setting the day marks the plan decided; replan opens it again while a window is still ahead."}
     :display {:label "Set the day" :style :primary :order 1}}

    :replan
    {:from #{:set} :to :drafting
     :guards [something-still-ahead]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Replanning opens the day again; what has already begun stays as it happened."}
     :display {:label "Replan" :order 2}}

    :reshape
    {:from #{:drafting :set} :to :drafting
     :input [:map [:shape {:x-display {:label "New shape"
                                       :choices {"workday" "A workday"
                                                 "off" "A day off"}}}
                   [:enum "workday" "off"]]]
     :guards [a-different-shape]
     :edit {:prefill [:shape]}
     :handler rematerialise-ahead
     ;; the reshape's whole blast radius, advertised: windows ahead
     ;; are skipped and re-minted, blocks arrive or are let go — each
     ;; :may because a shape with nothing ahead has nothing to do
     :touches [{:kind :span :action :skip :may true}
               {:kind :span :action :create :may true}
               {:kind :block :action :create :may true}
               {:kind :block :action :skip :may true}]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Reshaping replaces the windows still ahead with the new shape's; what has begun is history and does not move."}
     :display {:label "Reshape" :order 3}}

    :close
    {:from #{:drafting :set} :to :closed
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Closing ends the day; its blocks and spans stay readable as the record."}
     :display {:label "Close the day" :order 9}}}})
