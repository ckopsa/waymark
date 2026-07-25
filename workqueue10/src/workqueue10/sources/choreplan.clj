(ns workqueue10.sources.choreplan
  "choreplan10's chore_run rows as a TaskSource: the household's
  standing chores, one run per ask, discovered by ?state=due (the
  open work — history never backfills; an already-mirrored run that
  closes upstream still syncs by per-row pull).

  The translation:
  - state due/done/skipped → status open/done/dropped (an unknown
    state throws — a fourth chore_run state should fail loudly here,
    not map silently).
  - due_date (a DATE — chores are due \"sometime today\") → due_at =
    the midnight ENDING the due day, UTC. End-of-day, not start:
    overdue then flips exactly when choreplan's own date-typed law
    flips it ((< due_date (date-of now)) — the morning after), and a
    date-only chore sorts AFTER the day's clock-timed prep tasks,
    which is what a flexible all-day chore deserves. UTC because
    choreplan's law is UTC — parity over local-midnight cosmetics.
  - title is the ref's engine-maintained label copy (chore_name);
    detail is the chore's standing instructions (chore_notes, the
    :carry field) — the run reads standalone, no hop to the chore.

  Push is the shared push-plan: only Done travels, as chore_run's own
  :complete."
  (:require [workqueue10.sources.waymark :as wm])
  (:import (java.time LocalDate)))

(set! *warn-on-reflection* true)

(defn- day-end
  "The due day's closing midnight, UTC — the instant the chore
  becomes overdue under choreplan's own law."
  [date-str]
  (str (.plusDays (LocalDate/parse ^String date-str) 1) "T00:00:00Z"))

(defn run->task
  "One chore_run envelope → the canonical task doc."
  [env]
  (let [d (:data env)]
    {:title (:chore_name d)
     :assignee_name (:assignee d)
     :due_at (some-> (:due_date d) day-end)
     :status (case (:state env)
               "due" "open"
               "done" "done"
               "skipped" "dropped")
     :detail (:chore_notes d)}))

(defn http-source
  "The real boundary over a running choreplan10 engine.
  config: :url, :principal, :token, :token-fn (see
  sources.waymark/http-source)."
  [{:keys [url principal token token-fn]}]
  (wm/http-source {:url url
                   :kind-path "chore_runs"
                   :discover-query "state=due"
                   :row->task run->task
                   :principal principal
                   :token token
                   :token-fn token-fn}))

(defn engine-source
  "The stage-1 fold: chore_run lives in THIS engine — the confluence
  drinks in-process. config: :engine-ref, :ui-base, :principal (see
  sources.waymark/engine-source)."
  [{:keys [engine-ref ui-base principal]}]
  (wm/engine-source {:engine-ref engine-ref
                     :ui-base ui-base
                     :kind-path "chore_runs"
                     :discover-query "state=due"
                     :row->task run->task
                     :principal principal}))
