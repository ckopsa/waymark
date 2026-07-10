(ns waymark10.server.drafts
  "The draft sub-resource (phase 7): the composition surface for :edit
  actions with :draft policies. One row per (kind, resource, action,
  audience) — audience is \"shared\" for a :shared policy, else the
  acting principal's id, decided once in audience-of (waymark9
  drafts.py's discipline, minus the Audience value type).

  PUT saves partial input values (validated against the action's
  input schema without requiredness — a draft may be incomplete,
  never smuggle unknown fields), stamped with the row's CURRENT
  version as base_version; GET answers {values, base_version,
  prefill} where prefill carries the row's current values for the
  declared :edit :prefill fields; DELETE discards.

  Recorded choices and punts:
  - consumption rides finish!'s transaction (waymark10.server.invoke):
    the write that lands the effort deletes its draft in the same
    commit — the smallest honest seam, and crash-proof where the
    post-commit after-write! hook would leave a stale draft.
  - GET without a stored draft is 404 (the v10 shape; waymark9
    rendered an always-200 empty draft envelope).
  - only actions declaring :edit {:draft …} serve the surface: an
    :edit without a draft policy stores no composition effort, and
    the checks battery already nudges prose edits toward declaring
    one.
  - values persist wire-shaped (like the transition log's inputs);
    per-field revs/authors (waymark9) are unported — live collab
    (waymark10.server.collab, phase 9b) holds its revs room-local.
  - a {:shared true :live true} policy serves the waymark-relay
    websocket at …/draft/collab (phase 9b); every accepted set lands
    through the same draft row this namespace owns."
  (:require [waymark10.schema :as schema]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(defn audience-of
  "Who this action's draft belongs to — the one place the
  shared-vs-private decision is made."
  [defn' principal]
  (if (get-in defn' [:edit :draft :shared])
    "shared"
    (:id principal)))

(defn- draftable
  "The action's normalized definition when it declares a draft policy,
  404 otherwise — a draft route for an undraftable action does not
  exist."
  [rdef action-name]
  (or (when-some [a (get-in rdef [:actions action-name])]
        (when (get-in a [:edit :draft]) (assoc a :name action-name)))
      (throw (p/problem :not-found 404 "Not found"
                        {:detail (str (name (:kind rdef))
                                      " has no draftable action "
                                      (name action-name) ".")}))))

(defn- raw-row
  "The stored (wire-shaped) row — drafts read the document as bytes,
  never as schema types."
  [eng rdef tx id]
  (or (store/load-row (:storage eng) tx (:kind rdef) id {})
      (throw (p/not-found (:kind rdef) id))))

(defn- view [defn' raw draft]
  {:values (:values draft)
   :base-version (:base-version draft)
   :prefill (select-keys (:data raw) (get-in defn' [:edit :prefill]))})

(defn save!
  "Validate and upsert the principal's (or the shared) draft of this
  action; base_version = the row's current version. Returns the GET
  view."
  [eng rdef id action-name body principal]
  (let [defn' (draftable rdef action-name)
        body (or body {})]
    (when-not (map? body)
      (throw (p/schema-invalid action-name
                               {:body ["must be a JSON object of input fields"]})))
    (let [decoded (schema/decode (:input defn') body)]
      (when-some [errors (schema/partial-closed-errors (:input defn') decoded)]
        (throw (p/schema-invalid action-name errors))))
    (let [audience (audience-of defn' principal)]
      (store/with-tx (:storage eng)
        (fn [tx]
          (let [raw (raw-row eng rdef tx id)]
            (store/save-draft! (:storage eng) tx (:kind rdef) id
                               action-name audience body (:version raw))
            (view defn' raw {:values body :base-version (:version raw)})))))))

(defn fetch
  "The stored draft this principal can see, with the prefill; 404 when
  none — a private draft does not exist for anyone else."
  [eng rdef id action-name principal]
  (let [defn' (draftable rdef action-name)
        audience (audience-of defn' principal)]
    (store/with-tx (:storage eng)
      (fn [tx]
        (let [raw (raw-row eng rdef tx id)
              draft (or (store/load-draft (:storage eng) tx (:kind rdef) id
                                          action-name audience)
                        (throw (p/problem :not-found 404 "Not found"
                                          {:detail (str "No draft of "
                                                        (name action-name)
                                                        " for this "
                                                        (name (:kind rdef)) ".")})))]
          (view defn' raw draft))))))

(defn discard!
  "Delete the draft; discarding what does not exist is a no-op (DELETE
  is idempotent), but the resource itself must exist."
  [eng rdef id action-name principal]
  (let [defn' (draftable rdef action-name)
        audience (audience-of defn' principal)]
    (store/with-tx (:storage eng)
      (fn [tx]
        (raw-row eng rdef tx id)
        (store/delete-draft! (:storage eng) tx (:kind rdef) id
                             action-name audience)))
    nil))
