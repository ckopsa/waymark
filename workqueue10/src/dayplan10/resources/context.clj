(ns dayplan10.resources.context
  "The Context resource: the template a day is made from, never what a
  day is (docs/spec-dayplan.md § context). *Workday*, *Shop*,
  *Evening* — each says which shapes of day it belongs to
  (default_shapes), the clock windows it usually occupies
  (default_spans, local HH:MM pairs — a template says *nine to noon*,
  not an instant), where it sits among the day's blocks
  (default_order), who is usually in it (with), and the sentence its
  block reads when its work is done (seam). A day plan's create reads
  the active contexts of its shape ONCE and mints blocks and spans from
  them; after that the day's blocks are the truth, and revising or
  retiring a context changes tomorrow's plan and no plan that exists.

  feed_recipe_id is declared and read by nothing yet — a block that
  wants its own recipe under its decisions is a later bead over a ref
  that already exists (the spec's recorded punt).

  Spelled :schema + :actions: every field is authored and none is a
  lifecycle phase, and revise is an ordinary door with an input rather
  than a generated editor."
  (:require [dayplan10.zone :as zone]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.types :as t]))

;; ── the windows guard ───────────────────────────────────────────────

(defn window-problems
  "Why a vector of template windows is not a day's worth of clock
  windows — nil when it is. Each pair parses as HH:MM, starts before it
  ends, and the pairs run in order without touching each other's time
  (a template that overlapped itself would refuse its own
  materialisation at span's no-overlap door, one row in). Public so
  the materialisation can trust what the door admitted."
  [windows]
  (when (sequential? windows)
    (let [parsed (mapv (fn [w] [(zone/clock-time (:from w)) (zone/clock-time (:to w))])
                       windows)]
      (cond
        (some #(some nil? %) parsed)
        "every window is a pair of 24-hour clock times, HH:MM"

        (some (fn [[f t]] (not (.isBefore ^java.time.LocalTime f ^java.time.LocalTime t)))
              parsed)
        "a window starts before it ends"

        (some (fn [[[_ t1] [f2 _]]]
                (.isBefore ^java.time.LocalTime f2 ^java.time.LocalTime t1))
              (partition 2 1 parsed))
        "the windows run in order and do not overlap each other"))))

(defguardfn windows-read-as-clock-times
  {:judges [:default_spans]
   :vars [:why]
   :open "The windows are a vector of {from to} clock-time pairs; the schema can say they are five-character strings and nothing more, so the door judges the grammar — HH:MM, start before end, in order — and this sentence names it."
   :explain "Each default window is a pair of 24-hour clock times with the start before the end, in order and not overlapping — 09:00 to 12:00, then 13:00 to 17:00. Here: {why}."}
  [_row inp _ctx]
  (if-some [why (window-problems (:default_spans inp))]
    (t/deny {:vars {:why why}})
    (t/allow)))

;; ── the revise handler ──────────────────────────────────────────────

(defhandler revise-template [row inp _ctx]
  ;; the keys the caller named overwrite; an absent key leaves the
  ;; stored value standing (clearing an optional field is not what
  ;; revise is for — retire the context and author another)
  (update row :data merge (into {} (filter (comp some? val)) inp)))

;; ── the law, written down as scenarios ──────────────────────────────

(def ^:private a-workday-template
  {:name "Workday"
   :default_shapes ["workday"]
   :default_order 1})

(defscenario a-window-is-a-pair-of-clock-times
  "A template window is spelled as the clock reads it — 09:00, not
   9am — and the door says which grammar it wants rather than storing
   a string the materialisation would later have to guess at."
  {:kind    :context
   :attempt :create
   :input   (assoc a-workday-template
                   :default_spans [{:from "9am00" :to "12:00"}])
   :as      {:id "colton" :type :person}
   :expect  {:refused :windows-read-as-clock-times
             :because "24-hour clock times"}})

(defscenario a-template-does-not-overlap-itself
  "Two windows of one template that share a minute would refuse their
   own materialisation one row in (span's no-overlap door), so the
   template is refused here, where the fix is one edit."
  {:kind    :context
   :attempt :create
   :input   (assoc a-workday-template
                   :default_spans [{:from "09:00" :to "12:00"}
                                   {:from "11:30" :to "17:00"}])
   :as      {:id "colton" :type :person}
   :expect  {:refused :windows-read-as-clock-times
             :because "do not overlap"}})

(defscenario a-well-formed-template-is-admitted
  "Nine to noon, then one to five: the ordinary workday template goes
   straight in."
  {:kind    :context
   :attempt :create
   :input   (assoc a-workday-template
                   :default_spans [{:from "09:00" :to "12:00"}
                                   {:from "13:00" :to "17:00"}])
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

;; ── the declaration ─────────────────────────────────────────────────

(def ^:private window-form
  [:map
   [:from {:x-display {:label "From"
                       :help "When the window opens, as the clock reads it — 09:00."}}
    [:string {:min 5 :max 5}]]
   [:to {:x-display {:label "To"
                     :help "When it closes — 12:00. Start before end, windows in order."}}
    [:string {:min 5 :max 5}]]])

(defresource context
  {:kind :context
   :plural "contexts"
   :nav :secondary
   :states [:active :retired]
   :initial :active
   ;; retire/restore are an :undo pair, so neither ending is a tomb
   :terminal #{}
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   :unique [[:name]]
   ;; state is no entry, so it filters from here; name and default_order
   ;; carry their own :filter/:sort (one home per concern)
   :filterable {:state #{:eq :in}}
   :deviations
   ["default_spans is judged by windows-read-as-clock-times, whose :open acknowledges that a vector of {from to} pairs has no schema grammar for HH:MM or for order — the effort-honesty check warns on it; there is no vocabulary to publish, the legal answers are every minute of the day."]
   :schema [:map
            [:name {:filter #{:eq :contains} :sort true
                    :x-display {:label "Name"
                                :help "What the house calls this stretch of the day — Workday, Shop, Evening."}}
             [:string {:min 1 :max 80}]]
            [:default_shapes {:x-display {:label "Which kinds of day"
                                          :help "The shapes of day this context is part of by default: a workday, a day off, or both."
                                          :choices {"workday" "A workday"
                                                    "off" "A day off"}}}
             [:vector {:min 1} [:enum "workday" "off"]]]
            [:default_spans {:x-display {:label "Usual windows"
                                         :help "The clock windows this context usually occupies — 09:00 to 12:00, then 13:00 to 17:00. A plan mints one span per window on its date."}}
             [:vector {:min 1} window-form]]
            [:default_order {:sort :default
                             :x-display {:label "Order in the day"
                                         :help "Where this block sits among the day's blocks when a plan is minted — lower comes first."}}
             [:int {:min 0 :max 1000}]]
            [:with {:optional true
                    :x-display {:label "Usually with"
                                :help "The members usually in this block — the evening is with the kids. Read by nothing yet; here so a planning chat can say who a block is for."}}
             [:maybe [:vector :waymark/ref]]]
            [:seam {:optional true
                    :examples ["That's the workday. Whatever is left keeps until tomorrow."]
                    :x-display {:label "Seam sentence"
                                :help "What the block reads when its work is done — the context's own 'that's everything'."}}
             [:maybe [:string {:max 240}]]]
            [:feed_recipe_id {:optional true :kind :feed_recipe
                              :x-display {:label "Own recipe"
                                          :help "A feed recipe for this block's own section. Declared ahead of any reader; nothing consults it yet."}}
             [:maybe :waymark/ref]]]
   :create-guards [windows-read-as-clock-times]
   :actions
   {:revise
    {:from #{:active} :to :active
     ;; the authored fields again, each optional: what is named
     ;; overwrites, what is left out stands
     :input [:map
             [:name {:optional true
                     :x-display {:label "Name"
                                 :help "What the house calls this stretch of the day."}}
              [:maybe [:string {:min 1 :max 80}]]]
             [:default_shapes {:optional true
                               :x-display {:label "Which kinds of day"
                                           :help "The shapes of day this context is part of by default."
                                           :choices {"workday" "A workday"
                                                     "off" "A day off"}}}
              [:maybe [:vector {:min 1} [:enum "workday" "off"]]]]
             [:default_spans {:optional true
                              :x-display {:label "Usual windows"
                                          :help "The clock windows, HH:MM pairs in order — 09:00 to 12:00, then 13:00 to 17:00."}}
              [:maybe [:vector {:min 1} window-form]]]
             [:default_order {:optional true
                              :x-display {:label "Order in the day"
                                          :help "Where the block sits among the day's blocks — lower comes first."}}
              [:maybe [:int {:min 0 :max 1000}]]]
             [:with {:optional true
                     :x-display {:label "Usually with"
                                 :help "The members usually in this block."}}
              [:maybe [:vector :waymark/ref]]]
             [:seam {:optional true
                     :x-display {:widget "prose"
                                 :label "Seam sentence"
                                 :help "What the block reads when its work is done."}}
              [:maybe [:string {:max 240}]]]
             [:feed_recipe_id {:optional true :kind :feed_recipe
                               :x-display {:label "Own recipe"
                                           :help "A feed recipe for this block's own section; nothing consults it yet."}}
              [:maybe :waymark/ref]]]
     :guards [windows-read-as-clock-times]
     :handler revise-template
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Revise" :order 1}}

    :retire
    {:from #{:active} :to :retired
     :undo :restore
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Retire" :order 8}}

    :restore
    {:from #{:retired} :to :active
     :undo :retire
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Restore" :order 8}}}})
