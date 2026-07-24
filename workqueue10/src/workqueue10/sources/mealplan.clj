(ns workqueue10.sources.mealplan
  "mealplan10's prep_task rows as a TaskSource — pulled from the
  AUTHORITY, not from choreplan10's prep_task mirror (a mirror of a
  mirror lags two hops; the queue drinks upstream). Discovery is
  ?state=pending,scheduled — the open work, every assignee: the queue
  is the whole family's, where choreplan's mirror slices one
  assignee's feed.

  The translation:
  - machine state pending/scheduled → open, done → done, cancelled →
    dropped (an unknown state throws — loud, never silent).
  - title folds task_type + meal_name (\"thaw: Traeger brisket\") so
    the row reads standalone in a mixed queue; date context rides
    due_at, which mealplan already mints as a real instant.

  Push is the shared push-plan: only Done travels, as prep_task's own
  :complete — the same translation choreplan10's boundary makes,
  from one hop closer."
  (:require [workqueue10.sources.waymark :as wm]))

(set! *warn-on-reflection* true)

(defn prep->task
  "One prep_task envelope → the canonical task doc."
  [env]
  (let [d (:data env)]
    {:title (str (:task_type d) ": " (:meal_name d))
     :assignee (:assignee d)
     :due_at (:due_at d)
     :status (case (:state env)
               "pending" "open"
               "scheduled" "open"
               "done" "done"
               "cancelled" "dropped")
     :detail (:notes d)}))

(defn http-source
  "The real boundary over a running mealplan10 engine.
  config: :url, :principal, :token, :token-fn (see
  sources.waymark/http-source)."
  [{:keys [url principal token token-fn]}]
  (wm/http-source {:url url
                   :kind-path "prep_tasks"
                   :discover-query "state=pending,scheduled"
                   :row->task prep->task
                   :principal principal
                   :token token
                   :token-fn token-fn}))

(defn engine-source
  "The stage-2 fold: prep_task lives in THIS engine — the confluence
  drinks in-process. config: :engine-ref, :ui-base, :principal (see
  sources.waymark/engine-source)."
  [{:keys [engine-ref ui-base principal]}]
  (wm/engine-source {:engine-ref engine-ref
                     :ui-base ui-base
                     :kind-path "prep_tasks"
                     :discover-query "state=pending,scheduled"
                     :row->task prep->task
                     :principal principal}))
