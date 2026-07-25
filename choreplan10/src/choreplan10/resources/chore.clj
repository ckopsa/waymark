(ns choreplan10.resources.chore
  "The Chore resource: the standing chore — a thing the household
  keeps having to do (dishes, trash, mow), pinned to where it happens
  and who usually owns it. A chore is NOT an occurrence: \"take out
  the trash this Thursday\" is a chore_run row referencing this kind
  by :chore_id (see choreplan10.resources.chore-run for that split —
  the run has its own due/done/skipped lifecycle, so it earns its own
  rows the same way eveningplan10's sessions do).

  :area and :assignee are open vocab, not enums — the household's own
  usage IS the option list (rooms and family members arrive by being
  typed once, then facet/filter for free, the meal-themes pattern).

  update is one :edit declaration — prefill, the If-Match fence, and
  the shared draft are a single concept (mealplan10's update_recipe
  pattern)."
  (:require [waymark10.dsl :refer [defaction defhandler defresource
                                   one-of]]))

(defhandler apply-details [row inp _ctx]
  (update row :data merge
          (select-keys inp [:name :area :assignee :cadence :notes])))

;; queueing work IS minting a run — the verb lives on the chore (the
;; household manager's screen), and the ctx :create door births the
;; chore_run through the same transaction; :touches advertises the
;; birth. Queueing twice mints twice — regret is one Skip on the run.
(defhandler queue-run [row inp ctx]
  ((:create ctx) :chore_run {:chore_id (:id row)
                             :due_date (:due_date inp)})
  row)

(defaction queue
  {:from #{:active} :to :active
   :input [:map [:due_date {:x-display {:label "Due by"}} :waymark/date]]
   :touches [{:kind :chore_run :action :create}]
   :safety {:idempotent false :reversible false :confirm false
            :one-way "A queued run the household thinks better of is one Skip away — the record stays honest."}
   :handler queue-run
   :display {:label "Queue a run" :style :primary :order 1
             :description "Put this chore on the worklist: mint a run due by a date"}})

;; a named safety value: an idempotent in-place overwrite — nothing to
;; confirm, and "reverse" is just writing the field again
(def ^:private overwrite
  {:idempotent true :reversible false :confirm false})

(defaction update-details
  {:from #{:active} :to :active
   :input [:map
           [:name [:string {:min 1 :max 120}]]
           [:area {:optional true} [:maybe [:waymark/vocab {:open true}]]]
           [:assignee {:optional true} [:maybe [:waymark/vocab {:open true}]]]
           [:cadence (one-of :daily :weekly :monthly :as_needed)]
           [:notes {:optional true :x-display {:widget "prose"}}
            [:maybe [:string {:max 2000}]]]]
   :edit {:prefill [:name :area :assignee :cadence :notes]}
   ;; the overwrite writes the WHOLE detail set and is declared
   ;; non-reversible, so the log has to carry what was written — an
   ;; audit of the blank-form era (waymark-wnh) found 13 of these
   ;; transitions and not one recoverable value behind them.
   :record true
   :safety overwrite
   :handler apply-details
   :display {:label "Update details" :order 2}})

(defresource chore
  {:kind :chore
   :states [:active :paused :retired]
   :initial :active
   :terminal #{:retired}
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   :schema [:map
            [:name {:sort :default} [:string {:min 1 :max 120}]]
            ;; one declaration (design §6): membership filtering and
            ;; observed-value facets derive from the vocab itself
            [:area {:optional true} [:maybe [:waymark/vocab {:open true}]]]
            [:assignee {:optional true} [:maybe [:waymark/vocab {:open true}]]]
            [:cadence {:filter #{:eq}} (one-of :daily :weekly :monthly :as_needed)]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :filterable {:state #{:eq :in}}
   :owns {:runs {:kind :chore_run :via :chore_id}}
   :links [{:rel "runs" :owns :chore_run
            :summary "This chore's runs" :embed true}]
   :actions
   {:queue queue
    :update-details update-details

    :pause
    {:from #{:active} :to :paused
     :safety {:idempotent true :reversible true :confirm false}
     :display {:description "Take the chore out of the rotation for a while"}}

    ;; :pause claims :reversible true, which demands a real
    ;; :paused → :active edge to point at — this is it
    :resume
    {:from #{:paused} :to :active
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Resuming just returns to active; nothing external changes."}}

    :retire
    {:from #{:active :paused} :to :retired
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Retiring removes the chore from the rotation for good; its runs stay readable as a record."}}}})
