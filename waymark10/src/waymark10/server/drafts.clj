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

  THE DOCUMENT SHAPE (batch D — relay/2). The draft's jsonb column
  holds an envelope this namespace owns:

      {\"_doc\": 2, \"values\": {…}, \"revs\": {field: n},
       \"authors\": {field: actor}, \"ops\": {field: [{rev, ops}]}}

  — per-field revisions and authors (waymark9's revs, now ported)
  plus live collab's per-prose-field op logs, all riding the one
  draft row so the relay protocol survives a process boundary. A
  pre-envelope row (a plain values map) reads as a rev-0 document;
  document/envelope are the only readers and writers of the shape,
  and the wire view still answers {values, base_version, prefill}
  (now with revs and authors beside them, additively).

  Recorded choices and punts:
  - consumption rides finish!'s transaction (waymark10.server.invoke):
    the write that lands the effort deletes its draft in the same
    commit — the smallest honest seam, and crash-proof where the
    post-commit after-write! hook would leave a stale draft. The
    deletion takes the op logs and revs with it: an acted draft's
    composition history is consumed, not archived.
  - GET without a stored draft is 404 (the v10 shape; waymark9
    rendered an always-200 empty draft envelope).
  - only actions declaring :edit {:draft …} serve the surface: an
    :edit without a draft policy stores no composition effort, and
    the checks battery already nudges prose edits toward declaring
    one.
  - values persist wire-shaped (like the transition log's inputs).
  - a PUT is a whole-document replace: changed fields' revs bump,
    their authors restamp, and their op logs clear (a set is a
    rebase point) — but a PUT beside a live room broadcasts nothing;
    live clients converge at their next stale/sync (recorded in
    waymark10.server.collab).
  - a {:shared true :live true} policy serves the waymark-relay/2
    websocket at …/draft/collab (waymark10.server.collab); every
    accepted set/edit lands through the same draft row this
    namespace owns."
  (:require [waymark10.schema :as schema]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

;; ── the document shape ──────────────────────────────────────────────

(def doc-tag
  "The stored envelope's :_doc marker. 2 = relay/2: values + revs +
  authors + ops."
  2)

(defn document
  "The stored jsonb → the draft document {:values :revs :authors
  :ops}. A plain pre-envelope map (or nil) reads as a rev-0 document
  over those values — old rows keep working, no migration."
  [stored]
  (if (and (map? stored) (contains? stored :_doc))
    {:values (or (:values stored) {})
     :revs (or (:revs stored) {})
     :authors (or (:authors stored) {})
     :ops (or (:ops stored) {})}
    {:values (or stored {}) :revs {} :authors {} :ops {}}))

(defn envelope
  "The draft document back to its storable jsonb shape."
  [doc]
  {:_doc doc-tag
   :values (or (:values doc) {})
   :revs (or (:revs doc) {})
   :authors (or (:authors doc) {})
   :ops (or (:ops doc) {})})

(defn author-of
  "The actor stamp a field's last writer leaves — shared with the
  collab relay."
  [principal]
  {:id (:id principal)
   :type (name (:type principal :human))
   :display (or (:display principal) (:id principal))})

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

(defn- view [defn' raw draft doc]
  {:values (:values doc)
   :revs (:revs doc)
   :authors (:authors doc)
   :base-version (:base-version draft)
   :prefill (select-keys (:data raw) (get-in defn' [:edit :prefill]))})

(defn save!
  "Validate and upsert the principal's (or the shared) draft of this
  action; base_version = the row's current version. A PUT replaces
  the whole values document: each changed field's rev bumps, its
  author restamps, and its op log clears. Returns the GET view."
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
          (let [raw (raw-row eng rdef tx id)
                old (document (:values (store/load-draft
                                        (:storage eng) tx (:kind rdef) id
                                        action-name audience)))
                changed (filterv #(not= (get (:values old) %) (get body %))
                                 (distinct (concat (keys (:values old))
                                                   (keys body))))
                doc {:values body
                     :revs (reduce (fn [m k] (update m k (fnil inc 0)))
                                   (:revs old) changed)
                     :authors (reduce (fn [m k]
                                        (assoc m k (author-of principal)))
                                      (:authors old) changed)
                     :ops (reduce dissoc (:ops old) changed)}]
            (store/save-draft! (:storage eng) tx (:kind rdef) id
                               action-name audience (envelope doc)
                               (:version raw))
            (view defn' raw {:base-version (:version raw)} doc)))))))

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
          (view defn' raw draft (document (:values draft))))))))

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
