(ns dayplan10.resources.decision
  "The Decision resource: the unit of intention on a block
  (docs/spec-dayplan.md § decision). *I'll do X in that block*, in
  four kinds — pick (choose one from a queue: tonight's film), agenda
  (bring this up: the deck estimate), prepare (get this ready: the bag
  by the door), work (do this: the porch railing). text is the
  sentence; subject is an optional ADDRESS (/api/media/01H…,
  /api/tasks/01H…) that must name a row this house serves; launch is
  what Go does beyond recording — a link the card opens, a Home
  Assistant service with its data, or a note the card shows; prep is
  one sentence of what must be ready the evening before; order is
  where it sits among the block's decisions. A decision belongs to
  the block, never to a span (fork b): a span door never moves one.

  START IS THE VERDICT. It takes no input, so demand/effort renders
  it \"assent\" — the class the household calls a tap (demand.clj has
  no narrower word, and none is needed) — and it rides the card under
  the feed's ≤-selection rule with no special case. Its handler fires
  the Home Assistant service when launch.type is service, through the
  fn the app's boot hands the engine as (:services ctx) :home-assistant
  — workqueue10.main wires it over sources.homeassistant/call-service!
  — and its guard REFUSES the start when no Home Assistant is wired,
  reading the boot's feature token: a start that recorded *went*
  while the room stayed dark would be a record lying about the room.

  THE SUBJECT RESOLVES at the create door (waymark-79f's rule): the
  shape, the plural, and the row, through the one checker insight's
  citations read (insight/unresolved-addresses) — one sentence about
  addresses, written once. An address that names nothing is refused
  naming it.

  PREP IS A TASK, not a kind, and not a task minted through task's
  create door — task is a mirror kind whose births push to a pocket
  authority. The precedent is the thaw task: a native row the queue
  DRINKS through a TaskSource (workqueue10.sources.dayplan mirrors
  decisions?state=planned,started&has_prep=true into the queue, titled
  by prep, due the evening before the block's date). has_prep is the
  derived fact that discovery filters on; date and member are stamped
  at birth from the block and its plan so the mirror reads the row
  standalone, and member_handle rides as the ref's :carry so the task's
  assignee lands on a person.

  Two endings said out loud through :over — done and changed are the
  work accomplished (the decision said *this*, the day said *that*,
  and change keeps both), skipped is the work let go — and done and
  changed are tombs: a finished decision stays finished, and reopen is
  skip's honest reverse alone. Skipping the block cascades here
  through its :owns edge.

  Spelled :schema + :create-schema + :actions (block's dialect): the
  optional fields at create have no :fields spelling, and changed_to
  is written by change and by no form."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.types :as t]
            [workqueue10.resources.insight :as insight]))

;; ── the create walls ────────────────────────────────────────────────

(defguardfn launch-says-how
  {:judges [:launch]
   :vars [:why]
   :open "A launch is a type and the one field its type reads; the schema can say which keys exist and nothing about which pair agrees, so the door judges the pairing and this sentence names it."
   :explain "A launch says what Go does: a link carries its href, a service names the Home Assistant service it fires (light/turn_on), a note carries its text. Here: {why}."}
  [_row inp _ctx]
  (let [{:keys [type href service text]} (:launch inp)]
    (cond
      (nil? (:launch inp)) (t/allow)

      (and (= "href" type) (str/blank? (str href)))
      (t/deny {:vars {:why "a link with no href"}})

      (and (= "service" type) (str/blank? (str service)))
      (t/deny {:vars {:why "a service launch that names no service"}})

      (and (= "text" type) (str/blank? (str text)))
      (t/deny {:vars {:why "a note with no text"}})

      :else (t/allow))))

(defguardfn subject-resolves
  {:judges [:subject]
   :reads [:storage]
   :vars [:subject]
   :open "A subject is an address — /api/media/01H…, /api/tasks/01H… — and the schema can say it is a string and nothing more; the door reads the row it names, so the legal answers are the house's own rows and not a vocabulary."
   :explain "The subject is the address of a row this house serves, and nothing stands at {subject}. Name a row that exists — /api/media/01H…, /api/tasks/01H… — or leave the subject blank and say it in the text."}
  [_row inp ctx]
  (let [s (some-> (:subject inp) str str/trim not-empty)]
    (if (and s (seq (insight/unresolved-addresses [s] ctx)))
      (t/deny {:vars {:subject s}})
      (t/allow))))

(defguardfn on-a-planned-block
  {:judges [:block_id] :reads [:block]
   :vars [:why]
   :explain "A decision sits on a block of the day that is still planned: {why}."}
  [_row inp ctx]
  (if (nil? (:read ctx))
    (t/allow)
    (let [block ((:read ctx) :block (:block_id inp))]
      (cond
        (nil? block)
        (t/deny {:vars {:why "no block stands at that id — add the block to the day first"}
                 :errors {:block_id ["block not found"]}})

        (not= :planned (:state block))
        (t/deny {:vars {:why (str "the " (get-in block [:data :context_name])
                                  " block is " (name (:state block))
                                  "; un-skip or reopen it before deciding into it")}})

        :else (t/allow)))))

;; ── the start wall ──────────────────────────────────────────────────

(def ^:private home-assistant-feature "home_assistant")

(defn- home-assistant-wired?
  "The boot's word that a Home Assistant stands behind this engine —
  the feature token workqueue10.main declares beside the caller it
  hands the handler. A token rather than the caller itself so the
  wall is judged where a scenario can judge it: the check tier reads
  :services.features and nothing else of the wiring."
  [ctx]
  (let [fs (set (:features (:services ctx)))]
    (or (contains? fs home-assistant-feature)
        (contains? fs (keyword home-assistant-feature)))))

(defguardfn home-assistant-is-wired
  {:reads [:services.features]
   :vars [:service]
   :explain "Go would fire {service} in Home Assistant, and this engine has no Home Assistant wired — set WORKQUEUE10_HA_URL and WORKQUEUE10_HA_TOKEN, or change the launch to a link or a note. A start that recorded 'went' while the room stayed dark would be a record lying about the room."}
  [row _inp ctx]
  (let [{:keys [type service]} (get-in row [:data :launch])]
    (if (and (= "service" type) (not (home-assistant-wired? ctx)))
      (t/deny {:vars {:service (str service)}})
      (t/allow))))

;; ── the handlers ────────────────────────────────────────────────────

(defhandler fire-launch [row _inp ctx]
  ;; THE ROOM FIRST, THEN THE RECORD. A service launch fires through
  ;; the caller the boot wired — inside the write, so a Home Assistant
  ;; that answers 4xx or does not answer refuses the start and nothing
  ;; is recorded. Exactly once: start is idempotent, so a replay is the
  ;; stored answer and never re-fires. A link or a note fires nothing
  ;; here — the card carries it.
  (let [{:keys [type service data]} (get-in row [:data :launch])]
    (when (= "service" type)
      (let [fire! (get-in ctx [:services :home-assistant])]
        (when (nil? fire!)
          ;; the belt under the guard's braces: the boot declared the
          ;; feature and handed no caller
          (throw (ex-info (str "home_assistant is declared wired but no caller "
                               "reached the engine's :services — " service
                               " cannot fire")
                          {:service service})))
        (fire! service (or data {}))))
    row))

(defhandler record-change [row inp _ctx]
  (assoc-in row [:data :changed_to] (:changed_to inp)))

;; ── the birth ───────────────────────────────────────────────────────

(defn- stamp-day-and-member
  "The decision takes its date from its block and its member from the
  block's plan, so the prep mirror reads the row standalone — the
  block's own stamp-from-the-plan, one edge further down."
  [row ctx]
  (let [block ((:read ctx) :block (get-in row [:data :block_id]))
        plan (some->> (get-in block [:data :plan_id]) ((:read ctx) :day_plan))]
    (-> row
        (assoc-in [:data :date] (get-in block [:data :date]))
        (assoc-in [:data :member] (get-in plan [:data :member])))))

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; The create-door scenarios read the world (:storage, :block) and are
;; CONFORMANCE-tier — proved through the HTTP door in the dayplan10
;; conformance suite. The start-door scenarios read only the boot's
;; feature tokens and are judged at declaration time, where a scenario
;; sees no wiring at all — which is exactly the case the wall exists
;; to refuse.

(def ^:private a-block "01HZQ7Y7F2R3W4V5X6Y7Z8A9F0")

(def ^:private a-service-launch
  {:type "service" :service "light/turn_on" :data {:entity_id "light.porch"}})

(defscenario a-subject-is-a-row-that-stands
  "A subject is an address, and an address names a row this house
   serves — the shape, the plural, and the row itself (waymark-79f).
   One that names nothing is refused naming it, before the block is
   even consulted."
  {:kind    :decision
   :attempt :create
   :input   {:block_id a-block :kind "pick" :text "Tonight's film"
             :subject "/api/tasks/01HZQ7Y7F2R3W4V5X6Y7Z8A9F1" :order 1}
   :as      {:id "colton" :type :person}
   :expect  {:refused :subject-resolves
             :because "nothing stands at"}})

(defscenario a-launch-says-how
  "A service launch that names no service could fire nothing; the
   door refuses the pair that does not agree, at the one place the fix
   is a single field."
  {:kind    :decision
   :attempt :create
   :input   {:block_id a-block :kind "work" :text "Lights for the porch"
             :launch {:type "service"} :order 1}
   :as      {:id "colton" :type :person}
   :expect  {:refused :launch-says-how
             :because "names no service"}})

(defscenario go-needs-the-room-wired
  "Go on a service launch fires Home Assistant, and an engine with no
   Home Assistant refuses the start rather than recording a verdict
   the room never saw."
  {:kind    :decision
   :attempt :start
   :row     {:state :planned
             :data {:block_id a-block :kind "work" :text "Porch lights on"
                    :launch a-service-launch :order 1}}
   :as      {:id "colton" :type :person}
   :expect  {:refused :home-assistant-is-wired
             :because "no Home Assistant wired"}})

(defscenario a-link-fires-nothing-and-needs-no-wiring
  "A link launch is the card's to open; Go records the verdict and
   asks nothing of the house's wiring."
  {:kind    :decision
   :attempt :start
   :row     {:state :planned
             :data {:block_id a-block :kind "pick" :text "Tonight's film"
                    :launch {:type "href" :href "https://letterboxd.com/"} :order 1}}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario change-keeps-both-sentences
  "Change takes what the decision became and lands in changed — the
   decision said this, the day said that, and both are kept."
  {:kind    :decision
   :attempt :change
   :row     {:state :started
             :data {:block_id a-block :kind "agenda" :text "The deck estimate"
                    :order 2}}
   :input   {:changed_to "The fence quote instead — the deck waits on the permit"}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario a-finished-decision-stays-finished
  "Done is a tomb: there is no door out of it, and the machine itself
   says so."
  {:kind    :decision
   :attempt :start
   :row     {:state :done
             :data {:block_id a-block :kind "work" :text "The porch railing"
                    :order 1}}
   :as      {:id "colton" :type :person}
   :expect  {:refused :out-of-state}})

;; ── the declaration ─────────────────────────────────────────────────

(def ^:private kind-choices
  {"pick" "Pick — choose one from a queue (tonight's film)"
   "agenda" "Agenda — bring this up (the deck estimate)"
   "prepare" "Prepare — get this ready (the bag by the door)"
   "work" "Work — do this (the porch railing)"})

(def ^:private launch-form
  [:map
   [:type {:x-display {:label "What Go does"
                       :choices {"href" "Opens a link"
                                 "service" "Fires a Home Assistant service"
                                 "text" "Shows a note"}}}
    [:enum "href" "service" "text"]]
   [:href {:optional true
           :x-display {:label "Link"
                       :help "The address the card opens — for a link launch."}}
    [:maybe [:string {:max 500}]]]
   [:service {:optional true
              :x-display {:label "Service"
                          :help "The Home Assistant service, domain/service — light/turn_on."}}
    [:maybe [:string {:min 1 :max 120}]]]
   [:data {:optional true
           :x-display {:label "Service data"
                       :help "The service call's data — {entity_id light.porch}."}}
    [:maybe [:map-of :keyword :any]]]
   [:text {:optional true
           :x-display {:label "Note"
                       :help "What the card shows when you go — 'the drill is in the blue case'."}}
    [:maybe [:string {:max 500}]]]])

(defresource decision
  {:kind :decision
   :plural "decisions"
   :nav :secondary
   :states [:planned :started :done :skipped :changed]
   :initial :planned
   ;; done and changed are where a decision's story ends; skipped is
   ;; not — reopen is skip's honest reverse
   :terminal #{:done :changed}
   :over {:accomplished #{:done :changed} :let-go #{:skipped}}
   :summary "{data.text} · {state}"
   :label-template "{data.text}"
   ;; state is no entry, so it filters from here; block_id, kind, date,
   ;; member and has_prep carry their own :filter (one home per concern)
   :filterable {:state #{:eq :in}}
   :deviations
   ["subject is judged by subject-resolves and launch by launch-says-how, each with an :open acknowledging that an address and a launch pairing have no schema grammar — the effort-honesty check warns on both; the legal subjects are the house's own rows and the legal launches are the pairs the sentence names."]
   :schema [:map
            [:block_id {:kind :block :filter #{:eq}
                        :label :block_name
                        :x-display {:label "Which block"
                                    :help "The block this decision belongs to — the context's presence on the day; never a span."}}
             :waymark/ref]
            [:kind {:filter #{:eq :in}
                    :x-display {:label "Kind"
                                :choices kind-choices}}
             [:enum "pick" "agenda" "prepare" "work"]]
            [:text {:examples ["Tonight's film" "The deck estimate with the contractor"]
                    :x-display {:label "The decision"
                                :help "One sentence: what you mean to do in this block."}}
             [:string {:min 1 :max 240}]]
            [:subject {:optional true
                       :examples ["/api/media/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
                       :x-display {:label "Subject"
                                   :help "The row this is about, as an address — /api/media/01H…, /api/tasks/01H…. It has to be a row this house serves."}}
             [:maybe [:string {:max 200}]]]
            [:launch {:optional true
                      :x-display {:label "Launch"
                                  :help "What Go does beyond recording: open a link, fire a Home Assistant service, or show a note."}}
             [:maybe launch-form]]
            [:prep {:optional true
                    :examples ["Bag packed and by the door"]
                    :x-display {:label "Ready the evening before"
                                :help "One sentence of what must be ready the evening before — it becomes a task in the queue, due at six."}}
             [:maybe [:string {:max 240}]]]
            [:order {:sort :default
                     :x-display {:label "Order"
                                 :help "Where this sits among the block's decisions — lower comes first."}}
             [:int {:min 0 :max 1000}]]
            ;; written by change and by no form
            [:changed_to {:optional true
                          :x-display {:label "What it became"
                                      :help "Written by Change: what the day made of the decision. The original text stands beside it."}}
             [:maybe [:string {:max 240}]]]
            ;; stamped at birth from the block and its plan, so the prep
            ;; mirror reads the row standalone
            [:date {:optional true :filter #{:eq :range}
                    :x-display {:label "Day" :hidden true}}
             [:maybe :waymark/date]]
            [:member {:optional true :kind :member :filter #{:eq}
                      :label :member_name
                      :carry {:handle :member_handle}
                      :x-display {:label "Whose" :hidden true}}
             [:maybe :waymark/ref]]
            ;; the discovery filter the prep mirror reads
            [:has_prep {:optional true :filter #{:eq}
                        :derived {:over [:prep]
                                  :expr '(is-set (var :prep))}
                        :x-display {:label "Has prep" :hidden true}}
             [:maybe :boolean]]]
   ;; the create form asks what a person (or their planning chat)
   ;; decides: the block, the kind, the sentence, and the optional
   ;; subject, launch and prep — the date and member come from the block
   :create-schema [:map
                   [:block_id {:kind :block
                               :x-display {:label "Which block"
                                           :help "The block this decision belongs to."}}
                    :waymark/ref]
                   [:kind {:x-display {:label "Kind"
                                       :choices kind-choices}}
                    [:enum "pick" "agenda" "prepare" "work"]]
                   [:text {:examples ["Tonight's film"]
                           :x-display {:label "The decision"
                                       :help "One sentence: what you mean to do in this block."}}
                    [:string {:min 1 :max 240}]]
                   [:subject {:optional true
                              :examples ["/api/media/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
                              :x-display {:label "Subject"
                                          :help "The row this is about, as an address — /api/media/01H…, /api/tasks/01H…."}}
                    [:maybe [:string {:max 200}]]]
                   [:launch {:optional true
                             :x-display {:label "Launch"
                                         :help "What Go does beyond recording: open a link, fire a Home Assistant service, or show a note."}}
                    [:maybe launch-form]]
                   [:prep {:optional true
                           :examples ["Bag packed and by the door"]
                           :x-display {:label "Ready the evening before"
                                       :help "One sentence of what must be ready the evening before — it becomes a task in the queue, due at six."}}
                    [:maybe [:string {:max 240}]]]
                   [:order {:x-display {:label "Order"
                                        :help "Where this sits among the block's decisions — lower comes first."}}
                    [:int {:min 0 :max 1000}]]]
   ;; SHAPE FIRST, WORLD NEXT: the launch pairing reads nothing; the
   ;; subject reads the row it names; the block wall reads the day
   :create-guards [launch-says-how subject-resolves on-a-planned-block]
   :on-create stamp-day-and-member
   :actions
   {:start
    {:from #{:planned} :to :started
     :guards [home-assistant-is-wired]
     :handler fire-launch
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Starting is the verdict: the record says you went, and a service launch has fired."}
     :display {:label "Go" :style :primary :order 1
               :description "Tapping Go is the verdict — the record says you went; a link opens, a service fires, a note shows"}}

    :finish
    {:from #{:started :planned} :to :done
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Done is the record; a finished decision stays finished."}
     :display {:label "Done" :order 2}}

    :skip
    {:from #{:planned :started} :to :skipped
     :undo :reopen
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Skip" :order 3}}

    :reopen
    {:from #{:skipped} :to :planned
     :undo :skip
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Reopen" :order 4}}

    :change
    {:from #{:planned :started} :to :changed
     :input [:map [:changed_to {:x-display {:widget "prose"
                                            :label "What it became"
                                            :help "What the day made of this decision — the original text stands beside it."}}
                   [:string {:min 1 :max 240}]]]
     :handler record-change
     ;; changed_to is empty in every state this door runs from — a
     ;; first value welded onto a blank, not a rewrite — so the
     ;; edit-shape heuristic is waived rather than answered with a
     ;; prefill of nothing (waymark-01f's own case)
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Changing keeps both sentences — what was decided and what it became — and ends the decision as changed."}
     :display {:label "Change" :order 5}}}})
