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

  ONE SHAPE, NO SHARED MINUTE. Two active templates of one shape whose
  default windows overlap would collide in every plan of that shape —
  the plan's create mints a span per window, and span's no-overlap
  door refuses the second, so the PLAN fails wholesale and no day of
  that shape can be planned. The invariant therefore lives at the
  template door: no-overlap-in-shape (create, revise) and
  restores-clear-of-the-shape (restore, retire's undo) read the other
  active contexts and refuse a window that shares a minute with one
  of a shape they both serve. Touching ends are fine, the same rule
  span applies to instants; different shapes may overlap freely.

  feed_recipe_id is declared and read by nothing yet — a block that
  wants its own recipe under its decisions is a later bead over a ref
  that already exists (the spec's recorded punt).

  Spelled :schema + :actions: every field is authored and none is a
  lifecycle phase, and revise is an ordinary door with an input rather
  than a generated editor."
  (:require [dayplan10.zone :as zone]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.types :as t])
  (:import (java.time LocalTime)))

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

;; ── the shape wall: reading the other templates ─────────────────────

(defn- as-revised
  "The template as it would stand after a door: the keys the caller
  named overwrite, an absent key leaves the stored value — the ONE
  merge revise-template writes with, so the wall judges exactly what
  the handler would store. A create has no row, a restore no input;
  the same merge answers both."
  [row inp]
  (merge (:data row) (into {} (filter (comp some? val)) inp)))

(defn- clock-windows
  "A template's windows as [from to] LocalTime pairs, a pair the clock
  cannot read dropped (windows-read-as-clock-times refuses those one
  guard earlier; this is the belt)."
  [windows]
  (into []
        (keep (fn [{:keys [from to]}]
                (when-some [f (zone/clock-time from)]
                  (when-some [t (zone/clock-time to)]
                    [f t]))))
        windows))

(defn- overlap?
  "Two clock windows of one day share a minute; touching ends do not
  — span's no-overlap-in-plan rule, on clock times."
  [[^LocalTime f1 ^LocalTime t1] [^LocalTime f2 ^LocalTime t2]]
  (and (.isBefore f1 t2) (.isBefore f2 t1)))

(defn- other-active-templates
  "Every active context but this row — the set the shape wall holds."
  [ctx row]
  (->> ((:find ctx) :context {:state "active"} {:limit 500})
       (remove #(= (str (:id %)) (str (:id row))))))

(defn- collision
  "The first window of this template that shares a minute with a
  window of another active template of a shape they both serve →
  {:mine [f t] :theirs [f t] :shape … :other name}, nil when every
  shape's day is clear."
  [template others]
  (let [mine (clock-windows (:default_spans template))
        shapes (set (:default_shapes template))]
    (first
     (for [o others
           :let [shared (sort (filter shapes (get-in o [:data :default_shapes])))]
           :when (seq shared)
           m mine
           theirs (clock-windows (get-in o [:data :default_spans]))
           :when (overlap? m theirs)]
       {:mine m :theirs theirs :shape (first shared)
        :other (str (get-in o [:data :name]))}))))

(defn- collision-vars
  "The refusal's words: the window in the way and the template it
  belongs to, as the household reads a clock."
  [{:keys [mine theirs shape other]}]
  {:from (str (first mine)) :to (str (second mine))
   :theirs (str (first theirs) "–" (second theirs))
   :shape shape :other other})

(defn- clear-of-the-shape
  "The verdict both doors share: the template as it would stand,
  against the other active templates. The storage-free probe
  advertises optimistically, as span's no-overlap-in-plan does — the
  write path always carries the consult."
  [row inp ctx]
  (if (nil? (:find ctx))
    (t/allow)
    (if-some [hit (collision (as-revised row inp) (other-active-templates ctx row))]
      (t/deny {:vars (collision-vars hit)})
      (t/allow))))

(defguardfn no-overlap-in-shape
  {:judges [:default_spans :default_shapes] :reads [:context]
   :vars [:from :to :theirs :shape :other]
   :remedies [:context/retire :context/revise]
   :open "Which windows are free is a fact about the other active templates of the shape, read at the door; the schema can publish no vocabulary for it — every minute the shape's other templates leave clear is legal, and this sentence names the rule."
   :explain "{from}–{to} overlaps {other}'s {theirs}, another active template of a {shape} day; retire or revise {other}, or move this window. Templates that share a shape never share a minute — a plan of that day mints every one of them, and the second window would refuse the whole plan."}
  [row inp ctx]
  (clear-of-the-shape row inp ctx))

;; the same law at retire's undo, declared apart because restore takes
;; no input and a guard that judges fields cannot sit on an input-less
;; door (checks.clj guard-declarations); with no :judges the probe
;; judges it on the row alone, so a restore the door would refuse is
;; never advertised
(defguardfn restores-clear-of-the-shape
  {:reads [:context]
   :vars [:from :to :theirs :shape :other]
   :remedies [:context/retire :context/revise]
   :explain "This template's {from}–{to} overlaps {other}'s {theirs}, another active template of a {shape} day, so it cannot come back as it stands; retire or revise {other} first, then restore this one."}
  [row _inp ctx]
  (clear-of-the-shape row nil ctx))

;; ── the revise handler ──────────────────────────────────────────────

(defhandler revise-template [row inp _ctx]
  ;; the keys the caller named overwrite; an absent key leaves the
  ;; stored value standing (clearing an optional field is not what
  ;; revise is for — retire the context and author another). The same
  ;; merge the shape wall judged, so what lands is what was admitted.
  (assoc row :data (as-revised row inp)))

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

;; the shape wall reads the other templates (:reads [:context]), so
;; these two are CONFORMANCE-tier: the given row is staged through the
;; real door and the verdict is the one a client sees. Their windows
;; sit in the evening of a day OFF, clear of the workday the scenarios
;; above spell and of the one-minute night windows the conformance
;; walker mints (dayplan10.conformance-test).

(defscenario two-templates-of-one-shape-never-share-a-minute
  "A second template of a day off whose window reaches into the
   Shop's would collide in every plan of that shape — the plan's
   create would refuse itself one row in — so the template is refused
   here, naming the template in the way and the two doors that would
   clear it."
  {:kind    :context
   :attempt :create
   :given   [{:kind :context :state :active
              :data {:name "Scenario shop" :default_shapes ["off"]
                     :default_spans [{:from "21:00" :to "23:00"}]
                     :default_order 5}}]
   :input   {:name "Scenario evening" :default_shapes ["off"]
             :default_spans [{:from "22:00" :to "23:30"}]
             :default_order 6}
   :as      {:id "colton" :type :person}
   :expect  {:refused :no-overlap-in-shape
             :because "retire or revise Scenario shop"
             :remedies [:context/retire :context/revise]}})

(defscenario touching-windows-are-not-an-overlap
  "A template that ends exactly where the next begins is admitted —
   the day may have seams, it may not have collisions; the same rule
   span applies to instants, on clock times."
  {:kind    :context
   :attempt :create
   :given   [{:kind :context :state :active
              :data {:name "Scenario dinner" :default_shapes ["off"]
                     :default_spans [{:from "17:00" :to "18:00"}]
                     :default_order 5}}]
   :input   {:name "Scenario night" :default_shapes ["off"]
             :default_spans [{:from "18:00" :to "19:00"}]
             :default_order 6}
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
   ["default_spans is judged by windows-read-as-clock-times, whose :open acknowledges that a vector of {from to} pairs has no schema grammar for HH:MM or for order — the effort-honesty check warns on it; there is no vocabulary to publish, the legal answers are every minute of the day."
    "default_spans and default_shapes are judged again by no-overlap-in-shape against the OTHER active templates (:reads [:context]); its :open acknowledges the same missing grammar — the legal windows are every minute the shape's other templates leave clear, a fact read at the door and publishable by no schema."]
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
   ;; the grammar first, then the shape wall — a window the clock
   ;; cannot read is refused before anything is compared to it
   :create-guards [windows-read-as-clock-times no-overlap-in-shape]
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
     ;; the shape wall judges the template AS IT WOULD STAND — the
     ;; input merged over the row — so a revise that names only the
     ;; shapes is still held to the windows it keeps
     :guards [windows-read-as-clock-times no-overlap-in-shape]
     :edit {:prefill [:name :default_shapes :default_spans :default_order :with :seam :feed_recipe_id]}
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
     ;; retire's undo re-enters the active set, so it meets the same
     ;; wall a create does: the shape may have filled its window since
     :guards [restores-clear-of-the-shape]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Restore" :order 8}}}})
