(ns waymark10.server.readings
  "Readings (docs/spec-measures.md §4, build step 4): one
  `measure_reading` row per declared measure per aligned window — the
  house's number over that window, judged against the goal in force.
  Engine-owned and read-only: its create door and its seal are walled
  to the engine's own hand, and the maintainer's measure sweep
  (maintainer/sweep-measures!) is the only writer. An open reading is
  updated in place by each sweep in its window (a maintenance write,
  no transition); the first sweep after the window ends writes its
  final values and seals it, and a sealed reading is never written
  again.

  Opted into like `goal`: an app puts `measure_reading` in its
  resources vector beside it."
  (:require [waymark10.guards :as g]
            [waymark10.measures :as measures]
            [waymark10.resource :refer [defresource]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def reader
  "The system actor that writes and seals readings."
  (t/principal {:id "waymark10-readings" :type :system :display "Readings"}))

(g/defguard the-engine-reads-it
  {:reads [:principal]
   :hide true
   :explain "A reading is written by the engine's measure sweep; no hand at the wire creates, edits or seals one."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(defresource measure-reading
  {:kind :measure_reading
   :plural "measure_readings"
   :nav :secondary
   :states [:open :sealed]
   :initial :open
   :terminal #{:sealed}
   :summary "{data.measure} {data.value} · {data.verdict} · {data.window_start}"
   :label-template "{data.measure} {data.window_start}"
   :schema
   [:map
    [:measure {:x-display {:raw true :label "Measure"
                           :help "The measure's token, <kind>.<name>."}}
     [:string {:min 3 :max 120}]]
    [:window_start {:x-display {:label "Window from"
                                :help "The window's first day, in the house's zone."}}
     :waymark/date]
    [:window_end {:x-display {:label "Window until"
                              :help "The first day after the window, in the house's zone."}}
     :waymark/date]
    [:among {:x-display {:label "Rows"
                         :help "How many :among rows the window holds."}}
     [:int {:min 0}]]
    [:met {:optional true
           :x-display {:label "Met"
                       :help "For a rate: how many rows met its :where."}}
     [:maybe [:int {:min 0}]]]
    [:unknown {:optional true
               :x-display {:label "Unknown"
                           :help "For a rate: how many rows its :where could not judge; they count as not met."}}
     [:maybe [:int {:min 0}]]]
    [:value {:optional true
             :x-display {:label "Value"
                         :help "The stat over the window; a rate is a fraction. Empty when the window holds no rows or was not read."}}
     [:maybe :decimal]]
    [:goal_id {:optional true
               :not-a-ref "The goal judged against, copied: a restated or retired goal never rewrites a verdict."
               :x-display {:raw true :label "Goal"
                           :help "The goal in force when the reading was judged."}}
     [:maybe [:string {:max 80}]]]
    [:op {:optional true
          :x-display {:label "Held to" :help "The goal's op, copied."}}
     [:maybe (into [:enum] measures/goal-ops)]]
    [:target {:optional true
              :x-display {:label "Target" :help "The goal's target, copied."}}
     [:maybe :decimal]]
    [:verdict {:x-display {:label "Verdict"
                           :help "met or missed against the goal; unjudged with no goal, below :at-least rows, or when the window was not read."}}
     [:enum "met" "missed" "unjudged"]]
    [:evidence {:x-display {:label "Evidence"
                            :help "Up to five ids of rows that did not meet :where, newest first — or why the window was not read."}}
     [:vector {:max 5} [:string {:max 200}]]]
    [:swept_at {:x-display {:label "Swept"
                            :help "The last sweep that wrote it."}}
     :waymark/instant]
    [:told_at {:optional true
               :x-display {:label "Told"
                           :help "When the miss notice for this window went out; empty until then."}}
     [:maybe :waymark/instant]]
    [:told {:optional true
            :x-display {:widget "prose" :label "Notice"
                        :help "The sentence the miss notice said; empty until it went out."}}
     [:maybe [:string {:max 1000}]]]
    [:tell {:optional true
            :x-display {:raw true :label "Told to"
                        :help "The measure's :tell seat, by name, when the notice went out."}}
     [:maybe [:string {:max 120}]]]
    [:tell_member {:optional true
                   :not-a-ref "A member id read from a person seat's owner when the notice went out; the notice rule addresses it."
                   :x-display {:raw true :label "Told person"
                               :help "The member a person seat's notice went to; empty for a model seat, which is woken instead."}}
     [:maybe [:string {:max 80}]]]]
   :create-guards [the-engine-reads-it]
   :filterable {:state #{:eq :in}
                :measure #{:eq :in}
                :verdict #{:eq :in}
                :window_start #{:eq :range}}
   :sortable {:fields [:window_start] :default "window_start"}
   :actions
   {:seal {:from #{:open} :to :sealed
           :guards [the-engine-reads-it]
           :safety {:idempotent true :reversible false :confirm false
                    :one-way "A sealed reading is the window's final number and is never written again."}
           :display {:label "Seal" :order 9
                     :description "Close the window's reading with its final values"}}
    ;; A MISS TELLS ONCE PER WINDOW (§4): the sweep stamps told_at and
    ;; moves the reading through this door one time; the engine's
    ;; notice rule hears it for a person seat, and a model seat is
    ;; woken on measure_reading/missed.
    :missed {:from #{:open} :to :open
             :guards [the-engine-reads-it]
             :safety {:idempotent false :reversible false :confirm false
                      :one-way "The miss notice for this window went out once and is not taken back."}
             :display {:label "Missed" :order 8
                       :description "Tell the measure's seat the window missed its goal"}}}})
