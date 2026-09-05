(ns workqueue10.sources.dayplan
  "The day plan's prep as a TaskSource — decisions whose :prep is set,
  mirrored INTO the queue (docs/spec-dayplan.md, fork e: *prep is a
  task, not a kind*). The thaw-task pattern followed literally: task
  is a mirror kind whose create door admits only the pocket
  authorities, so a decision's prep never passes through it — the
  queue DRINKS the decision the way it drinks a prep_task, through
  the generic waymark source over this same engine.

  Discovery is ?state=planned,started&has_prep=true — the decisions
  still ahead whose prep sentence is set (has_prep is the kind's own
  derived fact, so the filter is one indexed read). Identity is the
  decision's row id, namespaced by the confluence's tag the way every
  source's is: the queue row reads \"day_plan:<decision-id>\".

  The translation:
  - title IS the prep sentence; detail says which decision it readies
    (\"For: Bag by the door — Workday · 2026-01-06\") so the task
    reads standalone in a mixed queue.
  - due_at is the evening before the block's date — 18:00 in the
    household zone (dayplan10.zone), a real instant, the way mealplan
    already mints thaw tasks.
  - assignee_name is the plan's member, by handle (the ref's :carry
    on the decision), so the task's assignee lands on a person.
  - planned/started → open (the prep still stands to be done; a block
    under way is still that day), done → done; a decision SKIPPED or
    CHANGED reads as GONE ({:status 404}, homeassistant.clj's gone
    rows) — its prep is nothing to do now — and the queue's :on-gone
    policy drops the task on the next pass.

  PUSH: only Done travels under the shared push-plan, and a decision
  has no door for *the prep is ready* — finishing the decision is a
  different verdict from packing the bag. So :complete REFUSES with a
  sentence rather than landing on the wrong door; the queue row lands
  conflicted and resolve_conflict decides. Recorded as the slice's
  open question (a `ready` fact on decision would close it), not
  papered over with a :noop that the next pull would silently undo."
  (:require [dayplan10.zone :as zone]
            [workqueue10.confluence :as conf]
            [workqueue10.sources.waymark :as wm])
  (:import (java.time LocalDate LocalTime)))

(set! *warn-on-reflection* true)

(def ^:private the-evening (LocalTime/of 18 0))

(defn evening-before
  "The prep's due instant: six in the evening, in the household zone,
  on the day before the block's date. nil when the decision carries
  no date (a block minted without a plan — the guard refuses it, this
  is the belt)."
  [date]
  (when-some [^LocalDate d (some-> date str not-empty LocalDate/parse)]
    (str (zone/at (.minusDays d 1) the-evening))))

(defn decision->task
  "One decision envelope → the canonical task doc. A skipped or
  changed decision throws gone ({:status 404}) — the sentinel the
  source's pull-many turns into :gone and the queue's :on-gone reads."
  [env]
  (let [d (:data env)
        state (str (:state env))]
    (when (contains? #{"skipped" "changed"} state)
      (throw (ex-info (str "decision " (:self env) " was " state
                           " — its prep is nothing to do now")
                      {:status 404})))
    {:title (:prep d)
     :assignee_name (:member_handle d)
     :due_at (evening-before (:date d))
     :status (case state
               "planned" "open"
               "started" "open"
               "done" "done")
     :detail (str "For: " (:text d)
                  (when-some [b (:block_name d)] (str " — " b)))}))

(defrecord DayplanSource [inner]
  conf/TaskSource
  (source-discover [_] (conf/source-discover inner))
  (source-pull [_ id] (conf/source-pull inner id))
  (source-pull-many [_ ids] (conf/source-pull-many inner ids))
  (source-push [_ id document]
    (let [[doc etag] (conf/source-pull inner id)]
      (case (conf/push-plan document (:status doc))
        :noop etag
        :complete
        (throw (ex-info (str "a prep task is its decision's shadow, and the "
                             "decision has no door for 'the prep is ready' — "
                             "Go or Done on the decision is a different verdict. "
                             "Recorded open (docs/spec-dayplan.md, fork e); "
                             "resolve keep=remote keeps the task open.")
                        {:decision id})))))
  (source-create [_ _document]
    (throw (ex-info (str "decisions accept no births from the queue — a prep "
                         "task is written on its decision, in the day plan")
                    {}))))

(defn engine-source
  "The in-process boundary: decision lives in THIS engine, so the
  confluence drinks it the way it drinks prep_task — the waymark
  source's engine transport, wrapped only where the push differs.
  config: :engine-ref, :ui-base, :principal (see
  sources.waymark/engine-source)."
  [{:keys [engine-ref ui-base principal]}]
  (->DayplanSource
   (wm/engine-source {:engine-ref engine-ref
                      :ui-base ui-base
                      :kind-path "decisions"
                      :discover-query "state=planned,started&has_prep=true"
                      :row->task decision->task
                      :principal principal})))
