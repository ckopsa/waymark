(ns waymark10.server.mirror
  "The Mirror (phase 8, ported from waymark9 server/external.py and
  scoped to what mealplan10's event kind exercises): a kind whose
  truth lives in an external authority. The machine IS the sync
  machine — fresh / stale / unreachable / conflicted — and the sync
  bookkeeping (external_id, external_etag, synced_at, conflict_reason)
  is data, so staleness renders instead of hiding.

  Declared as {:mirror {:adapter … :ttl-seconds n :discover-every n
  :push-on-write bool}} via the `declaration` helper below, which
  weaves the sync machine and the bookkeeping fields into the
  application's own declaration.

  The seams, each recorded:
  - PULL-THROUGH ON READ (refresh!): router's GET consults the mirror
    spec — a fresh row inside its TTL serves as stored; past the TTL
    (or off-fresh) the adapter pulls: a changed etag lands as an
    observe_external transition (system actor — audit and SSE carry
    changes we didn't make), an unreachable adapter marks the row
    unreachable ONCE and the stored truth keeps serving with its
    honest synced_at, an unchanged etag writes nothing (an
    \"observed, unchanged\" transition per TTL would be audit noise).
    A conflicted row never pulls — leaving conflicted is a person's
    move (resolve_conflict), not the clock's.
  - DISCOVERY (discover!): one pass asks the adapter's discover for
    the feed's current external ids and mints a row per unknown id —
    ordinary create!, system actor, {:external_id id} only — then
    eagerly fills the new mints through pull-many (one round trip,
    not N first-read pulls). The engine's runtime may run this on the
    declared :discover-every cadence (engine start!).
  - PUSH ON WRITE (batch E, waymark9 push_mirror at this scope): a
    kind declaring {:push-on-write true} may also declare its own
    domain actions (moves between sync states — the machine stays the
    sync machine); after such a write COMMITS, the pass pushes the
    exported document through the adapter's push. Success lands as
    observe_external (etag + synced_at stamped); ANY push failure
    lands as mark_conflicted — the local document stands, the state
    tells the truth, and resolve_conflict (a human's action) decides:
    keep=remote re-pulls the authority's truth, keep=local re-pushes
    ours. The pass rides the engine's post-commit :maintain hook —
    enroll with `with-push` (see its docstring for the wiring rule).
  - Sync transitions are system-actor only and HIDDEN (a hide guard);
    resolve_conflict alone is a human door — reconciling is a
    person's decision, never a silent last-writer-wins.
  - THE DOCUMENT CONTRACT (paydesk's reporting scenario demanded it):
    {:document :whole} — the default — reads a pulled document as the
    authority's complete record (absence is unset); :partial reads
    absence as silence (webhook diffs, multi-feed kinds).
    Engine-maintained fields (refs, derived) and entries outside
    their AUTHORITY WINDOW (:adopts/:frozen dates on the entry — law
    content, clock-evaluated, fingerprinted) are excluded from the
    replace. A field absent from the ENTIRE feed while stored rows
    carry values is an OBSERVED DEPRECATION (or a feed bug — same
    defense): the resync census holds it loudly instead of executing
    N silent nil-outs, releases when the key returns, and ratifying a
    real sunset is declaring :frozen.
  - FIELD DYNAMICS (:expect — the reporting scenario's second half):
    :immutable trips to conflicted when a document moves a set-once
    value (the id may now name a different entity — a person
    ratifies); {:churn n} holds a field changing on more rows than
    the declared percent (a reorg and a botched import look identical
    at first sight; widening the bound is the law-shaped
    ratification); :volatile warns when a should-move field moves
    nowhere (the feed froze while still answering). All fingerprinted
    in the authority facet.
  - CREATE PUSH (paydesk's assignment worksheet demanded it — the un-punt
    of \"creates never push\"): a kind declaring {:create-push true}
    (its adapter also a MirrorCreateAdapter) may be born locally — an
    ordinary create! through the declared :create-schema /
    :create-guards / :on-create — and the post-commit pass pushes the
    exported document as a CREATE: the authority mints the external
    id, and claim_external (a sync write) stamps identity + etag onto
    the row. A failed create push lands conflicted with NO external
    id; resolve_conflict keep=local re-pushes the create, keep=remote
    refuses loudly (a locally-minted row has no remote truth to
    keep). Discovery mints — born WITH an external id — never
    create-push, and an unclaimed local birth is invisible to
    pull-through and resync (nothing external names it yet).
  - EXTERNAL-KEYED REFS (paydesk's assignment demanded it): a
    :waymark/ref entry declaring {:kind … :external-key <field>}
    resolves at every sync write — the sibling field's external id
    looks up the target mirror row by external_id and the ROW id
    lands in the ref, so pickers, navigable links, and labels work
    across mirror kinds. Missing target ⇒ nil (renderable gap);
    discovery heals (resolve-refs!, a maintenance write, no
    transition). A push exports the resolved row ids with the rest of
    the document — adapters ignore what the external system doesn't
    own.

  Recorded punts, deliberately out of scope until a dogfood demands
  them: the per-kind discovery cursor (a restarted dev server
  re-discovers, idempotently), mirror cursors/webhooks (pushes and
  pulls are per-row, never a change feed), per-field authority
  (AuthoredMeta), and distinguishing unreachable-on-push from a true
  etag conflict (at this scope every push failure is the conflicted
  state; the resolve decides either way). Pushing a locally-minted
  row out to the authority WAS the fifth punt until paydesk's assignment
  worksheet demanded it — see CREATE PUSH above."
  (:require [clojure.set :as set]
            [waymark10.guards :as g]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Duration Instant LocalDate ZoneOffset)
           (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

;; ── the adapter seam ────────────────────────────────────────────────

(defprotocol MirrorAdapter
  "The external system's shape: three reads, and — for kinds declared
  :push-on-write — one write."
  (discover [a]
    "→ seq of external ids currently in the feed. Throw on
    unreachable.")
  (pull [a external-id]
    "→ [document etag]; the document is wire-shaped (plain JSON
    values). Throw on unreachable or gone.")
  (pull-many [a external-ids]
    "→ {external-id [document etag]} for the ids the feed still
    carries — the batched twin of pull. An adapter that can TELL a
    gone row from a down feed may answer {external-id :gone} for the
    former; plain absence stays ambiguous (stored truth serves
    either way, but only :gone triggers a declared :on-gone policy).
    Throw on unreachable.")
  (push [a external-id document]
    "Write the local document to the external system → the new etag.
    Throw on unreachable or on an external change under our feet —
    at this scope both are one recorded failure (the conflicted
    state; resolve_conflict decides). Pull-only adapters throw
    unconditionally and are never called (no :push-on-write)."))

(defprotocol MirrorCreateAdapter
  "The optional second write, for kinds declared {:create-push true}:
  the external system accepts a document it has never seen and mints
  the identity — the one write push can't express (push is keyed by
  an external id that a locally-minted row doesn't have yet)."
  (push-create [a document]
    "Create the document in the external system → [external-id etag].
    Throw on unreachable or refused; the failure lands as the
    conflicted state on a row that still has no external id, and
    resolve_conflict keep=local retries the create."))

;; ── the woven declaration ───────────────────────────────────────────

(def sync-states [:fresh :stale :unreachable :conflicted])

(def sync-action-names
  "The engine's doors on the sync machine — the names a push-on-write
  kind's own domain actions may not shadow, and the writes the push
  pass never pushes (pushing a sync write would loop)."
  #{:observe_external :observe_gone :claim_external :mark_stale
    :mark_unreachable :mark_conflicted :resolve_conflict})

(def system-observer
  (t/principal {:id "mirror-sync" :type :system :display "Mirror sync"}))

(def ^:private system-only
  (g/guard
   {:name :system-only
    :explain "Sync bookkeeping is the engine's; humans read the mirror."
    :reads [:principal]
    :hide true
    :check (with-meta
             (fn [_ _ ctx]
               (if (= :system (:type (:principal ctx)))
                 (t/allow)
                 (t/deny)))
             {:waymark10/form '(fn [row inp ctx]
                                 (waymark10.server.mirror/system-principal?
                                  ctx))})}))

(def ^:private bookkeeping-fields
  #{:external_id :external_etag :synced_at :conflict_reason})

(defn- bookkeeping-schema
  ;; external_id renders nowhere (machine plumbing — the row's own
  ;; summary and refs say who it is) but stays filterable on the wire.
  ;; A :create-push kind's rows may be BORN without one (the authority
  ;; mints it; claim_external stamps it), so there alone it is
  ;; optional — every other mirror keeps the hard birth invariant.
  [create-push?]
  [(if create-push?
     [:external_id {:optional true :x-display {:hidden true}}
      [:maybe [:string {:min 1 :max 256}]]]
     [:external_id {:x-display {:hidden true}} [:string {:min 1 :max 256}]])
   [:external_etag {:optional true :x-display {:hidden true}}
    [:maybe [:string {:max 256}]]]
   [:synced_at {:optional true} [:maybe :waymark/instant]]
   ;; why the row sits conflicted — data, so the gap renders (prose:
   ;; an adapter's error message is a paragraph, not a label); cleared
   ;; by the next successful sync write
   [:conflict_reason {:optional true :x-display {:widget "prose"}}
    [:maybe [:string {:max 280}]]]])

(defn- declared-fields [data-schema]
  (remove bookkeeping-fields (schema/entry-keys data-schema)))

;; ── external-keyed refs ─────────────────────────────────────────────
;; A mirror's document speaks external ids; waymark refs hold row ids.
;; An entry spelled [:employee_id {:kind :employee :external-key
;; :employee_zenefits_id} :waymark/ref] closes the gap: the sync write
;; resolves the sibling field's external id against the target kind's
;; external_id (every mirror's promoted, :eq-filterable column) and
;; lands the target's ROW id in the ref — so the picker, the navigable
;; link, and the label machinery that already read :kind all work on a
;; mirror kind. No target row yet ⇒ nil, a renderable gap; the target
;; kind's next discovery pass heals it (discover! calls resolve-refs!
;; on every kind pointing at what it just minted).

(defn- schema-head [form]
  (let [f (if (and (vector? form) (= :maybe (first form))) (second form) form)]
    (if (vector? f) (first f) f)))

(defn- ref-shape
  ":one for a :waymark/ref entry, :many for a vector of them (a
  mirror document's id ARRAY — team members, team funds), nil for
  anything else."
  [form]
  (let [f (if (and (vector? form) (= :maybe (first form))) (second form) form)]
    (cond
      (= :waymark/ref (if (vector? f) (first f) f)) :one
      (and (vector? f) (= :vector (first f))
           (= :waymark/ref (schema-head (last f)))) :many)))

(defn external-ref-specs
  "field → {:kind … :external-key … :shape :one|:many} for every
  entry of the woven schema declaring :external-key — the refs the
  sync path resolves."
  [data-schema]
  (into {}
        (keep (fn [[f {:keys [properties schema]}]]
                (when (:external-key properties)
                  [f (assoc (select-keys properties [:kind :external-key])
                            :shape (ref-shape schema))])))
        (schema/entry-map data-schema)))

(defn- check-external-refs!
  "Every :external-key rides a :waymark/ref entry, names a declared
  field, and carries the :kind the id resolves against — refused at
  the def site, not discovered at the first nil ref."
  [kind data-schema]
  (let [entries (schema/entry-map data-schema)
        fields (set (keys entries))]
    (doseq [[f {:keys [properties schema]}] entries
            :let [ek (:external-key properties)]
            :when ek]
      (let [err (fn [msg] (throw (t/definition-error
                                  (str (some-> kind name) "/" (name f) ": " msg))))]
        (when-not (ref-shape schema)
          (err ":external-key rides a :waymark/ref entry (or a vector of them) — this one isn't"))
        (when-not (:kind properties)
          (err ":external-key needs :kind — the target kind the external id resolves against"))
        (when-not (contains? fields ek)
          (err (str ":external-key " ek " names no declared field")))))))

;; ── authority windows and the document contract ─────────────────────
;; The document contract is law: :document :whole (the default) reads
;; a pulled document as the authority's COMPLETE record — a declared
;; field absent from it is unset, not unknown — while :partial reads
;; absence as silence (webhook diffs, multi-feed kinds). Excluded from
;; the whole-document replace: bookkeeping (the engine's),
;; engine-maintained fields (external-keyed refs, derived facts — both
;; recomputed by their own machinery), and entries outside their
;; AUTHORITY WINDOW. [:f {:adopts "2026-09-01"} …] ignores the feed
;; until the date (pre-release noise never lands; the field is local
;; territory); [:f {:frozen "2026-08-01"} …] syncs until the date and
;; stands as history after — and absence BEFORE a declared sunset
;; holds the stored value, because an early yank must not destroy the
;; record; :frozen true freezes as of now. The dates are law CONTENT
;; evaluated against each write's own clock — the clock-flipped-fact
;; discipline; nothing wakes on the boundary, behavior changes at the
;; first write after it. They ride the schema entries, so a date
;; change mints a revision (the fingerprint's authority facet); the
;; sync CADENCES (:ttl-seconds :discover-every) are operations,
;; deliberately outside the law.

(defn- window-instant ^Instant [s]
  (-> (LocalDate/parse ^String s) (.atStartOfDay ZoneOffset/UTC) (.toInstant)))

(defn authority-windows
  "field → {:adopts Instant|nil :frozen Instant|true|nil} for every
  entry declaring an authority boundary."
  [data-schema]
  (into {}
        (keep (fn [[f {:keys [properties]}]]
                (let [{:keys [adopts frozen]} properties]
                  (when (or adopts frozen)
                    [f {:adopts (some-> adopts window-instant)
                        :frozen (cond (true? frozen) true
                                      frozen (window-instant frozen))}]))))
        (schema/entry-map data-schema)))

(defn- iso-date? [s]
  (and (string? s)
       (try (LocalDate/parse ^String s) true
            (catch Exception _ false))))

(defn- check-authority-windows!
  "Window law refuses at the def site: dates are ISO dates, :adopts
  never takes true (adoption without a boundary is just declaring the
  field), and a window opens before it closes."
  [kind data-schema]
  (doseq [[f {:keys [properties]}] (schema/entry-map data-schema)
          :let [{:keys [adopts frozen]} properties
                err (fn [msg]
                      (throw (t/definition-error
                              (str (some-> kind name) "/" (name f) ": " msg))))]]
    (when (and (some? adopts) (not (iso-date? adopts)))
      (err ":adopts takes an ISO date — the day external authority begins"))
    (when (and (some? frozen) (not (true? frozen)) (not (iso-date? frozen)))
      (err ":frozen takes an ISO date (the day authority ends) or true (frozen as of now)"))
    (when (and (iso-date? adopts) (iso-date? frozen)
               (not (.isBefore (LocalDate/parse ^String adopts)
                               (LocalDate/parse ^String frozen))))
      (err (str ":adopts " adopts " does not precede :frozen " frozen
                " — an authority window opens before it closes")))))

;; ── field dynamics: the :expect grammar ─────────────────────────────
;; An entry may declare how its value BEHAVES, and the sync passes
;; measure reality against the model: {:expect :immutable} — set
;; once, never moves; a document moving (or, under :whole,
;; destroying) a stored value is conflict-shaped, row by row — the
;; external id may now name a different logical entity, and
;; resolve_conflict is the ratification door. {:expect :volatile} —
;; expected to move; a whole pass where it moves NOWHERE is the
;; inverse alarm (the feed's pipeline froze while still answering).
;; {:expect {:churn 25}} — at most that percent of rows may change
;; per pass; a breach is held like the mass-absence census (a reorg
;; and a botched import look identical at first sight) and ratifying
;; a legitimate mass change is a law event: widen the bound, promote,
;; revert. All three are law — fingerprinted in the authority facet.

(defn expectations
  "field → :immutable | :volatile | {:churn percent} for every entry
  declaring :expect — the field's declared dynamics."
  [data-schema]
  (into {}
        (keep (fn [[f {:keys [properties]}]]
                (when-some [e (:expect properties)] [f e])))
        (schema/entry-map data-schema)))

(defn- check-expectations! [kind data-schema]
  (doseq [[f e] (expectations data-schema)]
    (when-not (or (contains? #{:immutable :volatile} e)
                  (and (map? e) (= #{:churn} (set (keys e)))
                       (integer? (:churn e)) (<= 1 (:churn e) 100)))
      (throw (t/definition-error
              (str (some-> kind name) "/" (name f)
                   ": :expect is :immutable, :volatile, or {:churn 1..100}"
                   " (the percent of rows that may change per pass), got "
                   (pr-str e)))))))

(defonce ^:private field-census
  ;; adapter-instance → {:absent #{field} :as-of Instant}: the last
  ;; whole-population resync's presence census. One row's absence is
  ;; data (unset); the WHOLE population's absence is the provider
  ;; speaking about the field — an observed deprecation, or a feed
  ;; bug, and at first sight the two are indistinguishable, so both
  ;; get the same defense: sync writes HOLD censused fields instead
  ;; of nil-ing, loudly. The census clears at the next resync that
  ;; sees the key anywhere (and the resync re-observes rows holding
  ;; released values); ratifying a real sunset is declaring :frozen.
  ;; Keyed by adapter instance so engines never share censuses.
  (atom {}))

(defn- all-held
  "Every field the last census pass is defending — mass absence plus
  churn breaches; both hold the same way."
  [adapter]
  (let [{:keys [absent churned]} (get @field-census adapter)]
    (into (or absent #{}) churned)))

(defn- window-open?
  "Is external authority live for this field at now? (engine-owned
  fields are never the feed's to write)"
  [{:keys [engine-fields windows]} f ^Instant now]
  (let [{:keys [adopts frozen]} (get windows f)]
    (and (not (contains? engine-fields f))
         (or (nil? adopts) (not (.isBefore now ^Instant adopts)))
         (not (true? frozen))
         (or (nil? frozen) (.isBefore now ^Instant frozen)))))

(defn- kind-sync-ctx
  "The sync-law view the batch passes need, derived from a NORMALIZED
  rdef (declaration's own sync-ctx closes over the handlers; this one
  serves resync!/refresh!)."
  [rdef]
  (let [ds (:schema rdef)]
    {:declared (vec (declared-fields ds))
     :engine-fields (into (set (keys (external-ref-specs ds)))
                          (keys (:derived rdef)))
     :windows (authority-windows ds)
     :expects (expectations ds)
     :mode (get-in rdef [:mirror :document] :whole)
     :adapter (get-in rdef [:mirror :adapter])}))

(defn- trunc-val [v]
  (let [s (pr-str v)]
    (if (> (count s) 40) (str (subs s 0 37) "…") s)))

(defn- immutable-violation
  "The :immutable tripwire: the first field whose stored, non-nil
  value the document would move (or, under :whole, destroy) → the
  conflict sentence; nil when the document honors every declared
  immutability. First-set (stored nil) is never a violation, and a
  censused/churn-held field is already defended."
  [{:keys [expects mode adapter] :as kctx} row doc ^Instant now]
  (some (fn [[f e]]
          (when (= :immutable e)
            (let [stored (get-in row [:data f])]
              (when (and (some? stored)
                         (window-open? kctx f now)
                         (not (contains? (all-held adapter) f))
                         (if (contains? doc f)
                           (not= (get doc f) stored)
                           (= :whole mode)))
                (let [s (str "immutable " (name f) " moved "
                             (trunc-val stored) " → " (trunc-val (get doc f)))]
                  (if (> (count s) 270) (subs s 0 270) s))))))
        expects))

(defn- apply-document
  "The fields a sync write applies from an external document, under
  the kind's document contract, the authority windows, and the
  census. Under :whole, absence is unset (nil applied); holds are
  expressed by omission — the merge keeps the stored value."
  [{:keys [declared engine-fields windows mode adapter]} doc ^Instant now]
  (reduce
   (fn [m f]
     (let [{:keys [adopts frozen]} (get windows f)]
       (cond
         (contains? engine-fields f) m                        ; refs/derived: their machinery's
         (and adopts (.isBefore now ^Instant adopts)) m       ; not yet adopted
         (true? frozen) m                                     ; frozen as of now
         (and frozen (not (.isBefore now ^Instant frozen))) m ; past the sunset
         ;; a held field neither applies (churn breach: the incoming
         ;; value IS the suspect) nor nils (mass absence)
         (contains? (all-held adapter) f) m
         (contains? doc f) (assoc m f (get doc f))
         (= :partial mode) m                                  ; absence is silence
         frozen m                     ; declared sunset: pre-boundary absence holds
         :else (assoc m f nil))))                             ; :whole — absent is unset
   {} declared))

(defn- resolve-external-refs
  "The encoded document with each external-keyed ref recomputed from
  its sibling external id(s) through the ctx :find hook — one indexed
  lookup per id. A scalar ref lands nil when unset or unmatched (the
  honest gap); a :many ref lands the vector of RESOLVABLE row ids —
  the sibling external array stays the full truth, the ref vector is
  its resolvable projection. A ctx without :find (never the sync
  writes' case) leaves the document untouched."
  [specs merged ctx]
  (if-some [find-rows (:find ctx)]
    (let [lookup (fn [kind xid]
                   (when (and (some? xid) (not= "" (str xid)))
                     (some-> (first (find-rows kind {:external_id (str xid)}
                                               {:limit 1}))
                             :id str)))]
      (reduce-kv
       (fn [m field {:keys [kind external-key shape]}]
         (let [xv (get m external-key)]
           (assoc m field
                  (if (= :many shape)
                    (when (sequential? xv)
                      (into [] (keep #(lookup kind %)) xv))
                    (lookup kind xv)))))
       merged specs))
    merged))

(defn- observe-handler
  "The one sync write: the external document onto our data (matching
  declared fields only, bookkeeping excluded), etag and synced_at
  stamped, any conflict note cleared. Closes over the woven data
  schema so the applied document decodes to schema types like any
  other load."
  [data-schema sync-ctx]
  (let [ref-specs (external-ref-specs data-schema)]
    (with-meta
      (fn [row inp ctx]
        (let [applied (apply-document sync-ctx (:document inp) (:now ctx))
              merged (-> (merge (schema/encode data-schema (:data row))
                                applied
                                {:external_etag (:etag inp)
                                 :synced_at (str (:now ctx))
                                 :conflict_reason nil})
                         (as-> m (resolve-external-refs ref-specs m ctx)))]
          (assoc row :data (schema/decode data-schema merged))))
      ;; the canonical identity of the generic sync handler: one form,
      ;; every mirror kind — the imperative residue is this namespace's
      {:waymark10/form '(fn [row inp ctx]
                          (waymark10.server.mirror/apply-external
                           row inp ctx))})))

(defn- observe-gone-handler
  "The gone-policy's one write: the feed ANSWERED and the row was not
  in it — an observation, not an outage — so the declared :set patch
  lands (wire-shaped values, decoded like any load), freshness is
  stamped, and any conflict note clears. The etag stays: should the
  id ever return, the next pull's differing etag re-observes it."
  [data-schema patch]
  (with-meta
    (fn [row _inp ctx]
      (let [merged (merge (schema/encode data-schema (:data row))
                          patch
                          {:synced_at (str (:now ctx))
                           :conflict_reason nil})]
        (assoc row :data (schema/decode data-schema merged))))
    {:waymark10/form '(fn [row inp ctx]
                        (waymark10.server.mirror/observe-gone
                         row inp ctx))}))

(def ^:private claim-external-handler
  ;; the create push's landing: the authority accepted our document
  ;; and minted the identity — stamp it, with the etag the mint
  ;; answered, so the next pull-through recognizes its own document.
  ;; The local data IS the document; nothing else moves.
  (with-meta
    (fn [row inp ctx]
      (update row :data assoc
              :external_id (:external_id inp)
              :external_etag (:etag inp)
              :synced_at (:now ctx)
              :conflict_reason nil))
    {:waymark10/form '(fn [row inp ctx]
                        (waymark10.server.mirror/claim-external
                         row inp ctx))}))

(def ^:private mark-conflicted-handler
  ;; the local document stands untouched — only the reason lands, so
  ;; the gap renders while resolve_conflict waits for a person
  (with-meta
    (fn [row inp _ctx]
      (assoc-in row [:data :conflict_reason] (:reason inp)))
    {:waymark10/form '(fn [row inp ctx]
                        (waymark10.server.mirror/record-conflict
                         row inp ctx))}))

(defn- resolve-handler
  "The person's door out of conflicted (waymark9 reconcile, at batch
  E's scope): keep=remote pulls the authority's truth and adopts it;
  keep=local pushes ours and adopts the new etag. The adapter call
  runs inside the invoke — the same recorded impurity waymark9's
  reconcile carried; an unreachable adapter fails the invoke loudly
  and the row stays conflicted.

  A row with NO external id is a local birth whose create push
  failed: keep=local re-pushes the CREATE and claims the minted
  identity; keep=remote refuses loudly — there is no remote document
  to keep."
  [adapter data-schema sync-ctx]
  (let [ref-specs (external-ref-specs data-schema)
        declared (declared-fields data-schema)]
    (with-meta
      (fn [row inp ctx]
        (let [xid (get-in row [:data :external_id])
              encoded (schema/encode data-schema (:data row))]
          (cond
            (nil? xid)
            (if (= "remote" (:keep inp))
              (throw (ex-info (str "this row was minted locally and its create "
                                   "push failed — there is no remote document "
                                   "to keep; keep=local re-pushes the create")
                              {}))
              (let [[new-xid etag] (push-create adapter
                                                (select-keys encoded declared))]
                (update row :data assoc
                        :external_id (str new-xid)
                        :external_etag etag
                        :synced_at (:now ctx)
                        :conflict_reason nil)))

            (= "remote" (:keep inp))
            (let [[doc etag] (pull adapter xid)
                  merged (-> (merge encoded
                                    (apply-document sync-ctx doc (:now ctx))
                                    {:external_etag etag
                                     :synced_at (str (:now ctx))
                                     :conflict_reason nil})
                             (as-> m (resolve-external-refs ref-specs m ctx)))]
              (assoc row :data (schema/decode data-schema merged)))

            :else
            (let [etag (push adapter xid (select-keys encoded declared))]
              (update row :data assoc
                      :external_etag etag
                      :synced_at (:now ctx)
                      :conflict_reason nil)))))
      {:waymark10/form '(fn [row inp ctx]
                          (waymark10.server.mirror/resolve-conflict
                           row inp ctx))})))

(def ^:private sync-safety
  {:idempotent true :reversible false :confirm false
   :one-way "Sync bookkeeping; the next successful pull returns the mirror to fresh."})

(defn- check-domain-actions!
  "A push-on-write mirror's own actions are local-write doors on the
  sync machine: names off the engine's sync doors, moves between
  non-conflicted sync states (leaving conflicted is resolve_conflict's
  alone, and a conflicted row takes no local writes until a person
  decides)."
  [kind actions]
  (doseq [[aname a] actions]
    (let [err (fn [msg]
                (throw (t/definition-error
                        (str (some-> kind name) "/" (name aname) ": " msg))))
          from (let [f (:from a)]
                 (cond (set? f) f (sequential? f) (set f) (some? f) #{f}
                       :else #{}))
          writable (disj (set sync-states) :conflicted)]
      (when (contains? sync-action-names aname)
        (err "shadows an engine sync action"))
      (when-not (and (seq from) (every? writable from)
                     (contains? writable (:to a)))
        (err (str "a mirror's machine IS the sync machine — a local "
                  "write moves between " (vec (sort writable))
                  " (domain state lives in data)"))))))

(defn declaration
  "Weave the sync machine into an application declaration map:

     (mirror/declaration
      {:kind :event
       :summary \"{data.title} · {data.date}\"
       :schema [:map [:title …] [:date …] …]   ; domain fields only
       :filterable {…} :sortable {…}}
      {:adapter EVENTS :ttl-seconds 3600 :discover-every 3600})

  Returns the resource MAP (states, bookkeeping fields, sync actions
  and the :mirror spec added) — pass it through r/resource like any
  declaration. The app map must not declare :states/:initial of its
  own: the machine is the sync machine (waymark9's rule — domain
  state, if any, lives in data). A pull-only mirror declares no
  :actions either; a kind declared {:push-on-write true} may add
  domain actions (local writes the post-commit pass pushes — see
  check-domain-actions! for their shape); a kind additionally
  declared {:create-push true} (its adapter a MirrorCreateAdapter)
  may declare :create-schema / :create-guards / :on-create — local
  births the post-commit pass pushes as CREATES, the authority
  minting the identity claim_external stamps back."
  [rmap {:keys [adapter ttl-seconds discover-every push-on-write document
                create-push on-gone resync-every]}]
  (when (nil? adapter)
    (throw (t/definition-error
            (str (some-> (:kind rmap) name) ": a mirror declares its :adapter"))))
  (when (and (some? on-gone) (not= :keep on-gone)
             (not (and (map? on-gone) (= [:set] (vec (keys on-gone)))
                       (map? (:set on-gone)) (seq (:set on-gone)))))
    (throw (t/definition-error
            (str (some-> (:kind rmap) name)
                 ": :on-gone is :keep (the default — a gone row keeps "
                 "serving its stored truth) or {:set {field wire-value}} "
                 "(the patch a feed-answered-but-row-absent observation "
                 "lands), got " (pr-str on-gone)))))
  (when (and (some? resync-every) (not (pos-int? resync-every)))
    (throw (t/definition-error
            (str (some-> (:kind rmap) name)
                 ": :resync-every is a positive number of seconds — the "
                 "whole-kind heal's cadence (omitted, resync runs at boot "
                 "alone), got " (pr-str resync-every)))))
  (when (and create-push (not push-on-write))
    (throw (t/definition-error
            (str (some-> (:kind rmap) name)
                 ": :create-push rides :push-on-write — a kind whose creates "
                 "reach the authority pushes its writes too"))))
  (when (and create-push (not (satisfies? MirrorCreateAdapter adapter)))
    (throw (t/definition-error
            (str (some-> (:kind rmap) name)
                 ": :create-push needs a MirrorCreateAdapter — this adapter "
                 "cannot mint an external row"))))
  (when-not create-push
    (when-some [k (some #(when (contains? rmap %) %)
                        [:create-schema :create-guards :on-create])]
      (throw (t/definition-error
              (str (some-> (:kind rmap) name) ": " k " on a mirror whose rows "
                   "are born from discovery alone — declare {:create-push true} "
                   "(and :push-on-write) to mint rows locally")))))
  (when (and create-push (nil? (:create-schema rmap)))
    (throw (t/definition-error
            (str (some-> (:kind rmap) name)
                 ": :create-push declares its :create-schema — the birth "
                 "input is the author's law, never the woven bookkeeping"))))
  (when (and create-push
             (some bookkeeping-fields (schema/entry-keys (:create-schema rmap))))
    (throw (t/definition-error
            (str (some-> (:kind rmap) name)
                 ": :create-schema never carries sync bookkeeping — the "
                 "authority mints external_id; claim_external stamps it"))))
  (when-not (contains? #{nil :whole :partial} document)
    (throw (t/definition-error
            (str (some-> (:kind rmap) name)
                 ": :document is :whole (the default — a pulled document is "
                 "the authority's complete record) or :partial (absence is "
                 "silence), got " (pr-str document)))))
  (when (some rmap [:states :initial])
    (throw (t/definition-error
            (str (some-> (:kind rmap) name)
                 ": a mirror's machine IS the sync machine — declare domain "
                 "state in data, not :states/:initial"))))
  (when (and (:actions rmap) (not push-on-write))
    (throw (t/definition-error
            (str (some-> (:kind rmap) name)
                 ": a pull-only mirror takes no local writes — declare "
                 ":push-on-write true to add domain actions"))))
  (when push-on-write
    (check-domain-actions! (:kind rmap) (:actions rmap)))
  (let [data-schema (into [:map] (concat (bookkeeping-schema (boolean create-push))
                                         (rest (:schema rmap))))
        ;; refusals precede the window parse — a malformed date gets
        ;; the definition error, never a raw parse exception
        _ (check-external-refs! (:kind rmap) data-schema)
        _ (check-authority-windows! (:kind rmap) data-schema)
        _ (check-expectations! (:kind rmap) data-schema)
        mode (or document :whole)
        gone-patch (when (map? on-gone)
                     (:set on-gone))
        _ (doseq [f (keys gone-patch)]
            (let [err (fn [msg]
                        (throw (t/definition-error
                                (str (some-> (:kind rmap) name) "/" (name f)
                                     ": " msg))))]
              (when (contains? bookkeeping-fields f)
                (err ":on-gone never patches sync bookkeeping — the engine's"))
              (when-not (contains? (set (schema/entry-keys data-schema)) f)
                (err ":on-gone patches no declared field by this name"))
              (when (or (contains? (external-ref-specs data-schema) f)
                        (contains? (:derived rmap) f)
                        (some (fn [[ef {:keys [properties]}]]
                                (and (= ef f) (:derived properties)))
                              (schema/entry-map data-schema)))
                (err ":on-gone never patches an engine-maintained field — its own machinery writes it"))))
        sync-ctx {:declared (vec (declared-fields data-schema))
                  ;; engine-maintained fields: external-keyed refs and
                  ;; derived facts (colocated or top-level) — their own
                  ;; machinery writes them; the document never does
                  :engine-fields
                  (into (set (keys (external-ref-specs data-schema)))
                        (concat (keys (:derived rmap))
                                (keep (fn [[f {:keys [properties]}]]
                                        (when (:derived properties) f))
                                      (schema/entry-map data-schema))))
                  :windows (authority-windows data-schema)
                  :mode mode
                  :adapter adapter}]
    (-> rmap
        (assoc :schema data-schema
               :states sync-states
               :initial :fresh
               :terminal #{}
               :mirror (cond-> {:adapter adapter
                                :ttl-seconds (or ttl-seconds 300)
                                :discover-every (or discover-every 300)
                                :document mode
                                :push-on-write (boolean push-on-write)
                                :create-push (boolean create-push)
                                :on-gone (if gone-patch {:set gone-patch} :keep)}
                         resync-every (assoc :resync-every resync-every))
               :actions
               (merge
                (:actions rmap)
                (when gone-patch
                  {:observe_gone
                   ;; the gone-policy's landing: the feed answered and
                   ;; the row was absent — a deletion observed, never
                   ;; an outage (that is mark_unreachable's)
                   {:from #{:fresh :stale :unreachable} :to :fresh
                    :guards [system-only]
                    :safety {:idempotent true :reversible false :confirm false
                             :one-way "Recording that the feed no longer carries this row loses nothing here — the stored record stands, patched as declared."}
                    :handler (observe-gone-handler data-schema gone-patch)
                    :display {:label "Observed gone from feed"}}})
                (when create-push
                  {:claim_external
                   ;; the create push's landing: identity + etag from
                   ;; the authority's mint, onto the locally-born row
                   {:from (set sync-states) :to :fresh
                    :input [:map
                            [:external_id [:string {:min 1 :max 256}]]
                            [:etag [:string {:max 256}]]]
                    :guards [system-only]
                    :edit {:prefill [:external_id] :fence false
                           :unfenced-reason
                           "A system-only sync write inside the post-commit push pass — no read preceded it to fence against."}
                    :safety {:idempotent true :reversible false :confirm false
                             :one-way "Recording the identity the external system minted loses nothing here."}
                    :handler claim-external-handler
                    :display {:label "Claimed external identity"}}})
                {:observe_external
                 {:from (set sync-states) :to :fresh
                  :input [:map
                          [:document [:map-of :keyword :any]]
                          [:etag [:string {:max 256}]]]
                  :guards [system-only]
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "Recording what the external system already says loses nothing here."}
                  :handler (observe-handler data-schema sync-ctx)
                  :display {:label "Observed external change"}}
                 :mark_stale
                 {:from #{:fresh :stale :unreachable} :to :stale
                  :guards [system-only]
                  :safety sync-safety
                  :display {:label "Mark stale"}}
                 :mark_unreachable
                 {:from #{:fresh :stale :unreachable} :to :unreachable
                  :guards [system-only]
                  :safety sync-safety
                  :display {:label "Mark unreachable"}}
                 :mark_conflicted
                 {:from (set sync-states) :to :conflicted
                  ;; hidden: only the sync machinery fills this form
                  :input [:map [:reason {:x-display {:hidden true}}
                                [:string {:max 280}]]]
                  :guards [system-only]
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "Both truths persist — ours stored here, theirs external — until resolve_conflict decides."}
                  :handler mark-conflicted-handler
                  :display {:label "Conflict detected"}}
                 :resolve_conflict
                 ;; the one human door on the sync machine: a person
                 ;; picks the winner — never a silent last-writer-wins
                 {:from #{:conflicted} :to :fresh
                  :input [:map
                          [:keep {:x-display {:label "Which truth wins"}}
                           [:enum "local" "remote"]]]
                  :safety {:idempotent true :reversible false :confirm true
                           :consequence "The losing version of this document is overwritten, here and externally."}
                  :handler (resolve-handler adapter data-schema sync-ctx)
                  :display {:label "Resolve conflict" :style :primary
                            :order 1}}}))
        ;; discovery's mint check queries the promoted column
        (update :filterable (fn [f] (update (or f {}) :external_id
                                            #(or % #{:eq}))))
        (cond->
          (and create-push (:on-create rmap))
          (assoc :on-create
                 (let [oc (:on-create rmap)]
                   ;; discovery mints ({:external_id id} alone) skip
                   ;; the app's birth hook — it speaks the
                   ;; create-schema's vocabulary, and a mint carries
                   ;; none of it
                   (fn [row ctx]
                     (if (get-in row [:data :external_id])
                       row
                       (oc row ctx)))))))))

;; ── pull-through on read ────────────────────────────────────────────

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 mirror: " parts))))

(defn- within-ttl? [row ^Instant now ttl-seconds]
  (when-some [^Instant synced (get-in row [:data :synced_at])]
    (< (.getSeconds (Duration/between synced now)) (long ttl-seconds))))

(defn refresh!
  "Pull-through on read: a fresh row inside its TTL serves as stored;
  otherwise ask the adapter. changed → observe_external (system
  actor) → fresh; unreachable → mark_unreachable once, stored truth
  stands; unchanged → nothing written; conflicted → never pulled
  (leaving conflicted is a person's move, not the clock's). Returns
  the (possibly refreshed) decoded row."
  [eng rdef row]
  (let [spec (:mirror rdef)]
    (if (or (= :conflicted (:state row))
            ;; a local birth the authority hasn't minted yet: nothing
            ;; external names it, so there is nothing to pull — the
            ;; push pass (or resolve keep=local) owns its claim
            (nil? (get-in row [:data :external_id]))
            (and (= :fresh (:state row))
                 (within-ttl? row ((:now-fn eng)) (:ttl-seconds spec))))
      row
      (let [xid (get-in row [:data :external_id])
            pulled (try (pull (:adapter spec) xid)
                        (catch Exception e
                          ;; a 404 from a feed that ANSWERED is a gone
                          ;; row, not an outage — but only a declared
                          ;; :on-gone policy gives that meaning; :keep
                          ;; preserves the old posture (unreachable
                          ;; once, stored truth serves)
                          (if (and (map? (:on-gone spec))
                                   (= 404 (:status (ex-data e))))
                            ::gone
                            ::unreachable)))]
        (cond
          (= ::unreachable pulled)
          (if (= :unreachable (:state row))
            row
            (:row (inv/invoke! eng (:kind rdef) (:id row) :mark_unreachable
                               nil {:principal system-observer})))

          (= ::gone pulled)
          (let [patch (get-in spec [:on-gone :set])
                encoded (schema/encode (:schema rdef) (:data row))
                changed? (boolean (some (fn [[f v]]
                                          (not= (get encoded f) v))
                                        patch))]
            (if (or changed? (not= :fresh (:state row)))
              (:row (inv/invoke! eng (:kind rdef) (:id row) :observe_gone
                                 nil {:principal system-observer}))
              ;; already patched and fresh: the check is a fact, the
              ;; stamp resets the TTL — audit stays quiet (the
              ;; observed-unchanged discipline)
              (let [now ((:now-fn eng))]
                (store/with-tx (:storage eng)
                  (fn [tx]
                    (store/update-data!
                     (:storage eng) tx (:kind rdef) (:id row)
                     (assoc encoded :synced_at (str now))
                     (:next-flip-at row))))
                (assoc-in row [:data :synced_at] now))))

          :else
          (let [[doc etag] pulled]
            (if (and (= etag (get-in row [:data :external_etag]))
                     (= :fresh (:state row)))
              ;; observed, unchanged: still no transition (that would
              ;; be audit noise) — but the CHECK is a fact, and
              ;; without stamping it the TTL never resets: every read
              ;; past the boundary re-pulls the tunnel forever. A
              ;; maintenance write records freshness.
              (let [now ((:now-fn eng))]
                (store/with-tx (:storage eng)
                  (fn [tx]
                    (store/update-data!
                     (:storage eng) tx (:kind rdef) (:id row)
                     (assoc (schema/encode (:schema rdef) (:data row))
                            :synced_at (str now))
                     (:next-flip-at row))))
                (assoc-in row [:data :synced_at] now))
              ;; the :immutable tripwire runs before the observe: a
              ;; changed document moving a set-once value is
              ;; conflict-shaped, not syncable
              (if-some [reason (immutable-violation (kind-sync-ctx rdef) row doc
                                                    ((:now-fn eng)))]
                (:row (inv/invoke! eng (:kind rdef) (:id row) :mark_conflicted
                                   {:reason reason}
                                   {:principal system-observer}))
                (:row (inv/invoke! eng (:kind rdef) (:id row) :observe_external
                                   {:document doc :etag etag}
                                   {:principal system-observer}))))))))))

;; ── push on write (batch E, waymark9 push_mirror at this scope) ─────

(defn export-document
  "Our row as the external document: the declared domain fields,
  wire-encoded, bookkeeping excluded (waymark9 export_external)."
  [rdef row]
  (select-keys (schema/encode (:schema rdef) (:data row))
               (declared-fields (:schema rdef))))

(defn push-after-write!
  "The write-back pass for one committed, non-replayed write: when the
  kind is a :push-on-write mirror and the action is one of ITS OWN
  domain actions (never the engine's sync doors — that would loop),
  push the exported document. Success lands as observe_external
  (etag + synced_at stamped); any failure lands as mark_conflicted
  with the adapter's own words — the local document stands, and
  resolve_conflict decides. A row already conflicted is left alone
  (waymark9's rule). Returns res with :row refreshed to the post-push
  truth.

  On a :create-push kind the CREATE action pushes too — as a
  push-create, the authority minting the identity claim_external
  stamps back; a failed create push is the conflicted state on a row
  that still has no external id (resolve keep=local retries the
  create). Discovery mints are born WITH an external id and take the
  neither branch — a mint records what the authority already has."
  [eng kind action-name res]
  (let [rdef (get (inv/resources eng) kind)
        spec (:mirror rdef)
        committed? (and spec (:push-on-write spec)
                        (:transition res) (nil? (:replayed? res))
                        (not= :conflicted (:state (:row res))))
        domain? (and committed?
                     (contains? (set (keys (:actions rdef))) action-name)
                     (not (contains? sync-action-names action-name)))
        ;; a birth, or a domain write on a row the authority hasn't
        ;; minted yet (claim raced or failed earlier) — either way the
        ;; authority has never seen this row, so the push is a CREATE
        birth? (and committed? (:create-push spec)
                    (nil? (get-in res [:row :data :external_id]))
                    (or domain?
                        (contains? (set (:create-action-names rdef))
                                   action-name)))
        conflicted (fn [row e]
                     (assoc res :row
                            (:row (inv/invoke!
                                   eng kind (:id row) :mark_conflicted
                                   {:reason (or (ex-message ^Exception e)
                                                "push failed")}
                                   {:principal system-observer}))))]
    (cond
      birth?
      (let [row (:row res)
            doc (export-document rdef row)
            minted (try (push-create (:adapter spec) doc)
                        (catch Exception e e))]
        (if (instance? Exception minted)
          (conflicted row minted)
          (let [[xid etag] minted]
            (assoc res :row
                   (:row (inv/invoke!
                          eng kind (:id row) :claim_external
                          {:external_id (str xid) :etag etag}
                          {:principal system-observer}))))))

      domain?
      (let [row (:row res)
            xid (get-in row [:data :external_id])
            doc (export-document rdef row)
            pushed (try (push (:adapter spec) xid doc)
                        (catch Exception e e))]
        (if (instance? Exception pushed)
          (conflicted row pushed)
          (assoc res :row
                 (:row (inv/invoke!
                        eng kind (:id row) :observe_external
                        {:document doc :etag pushed}
                        {:principal system-observer})))))

      :else res)))

(defn with-push
  "Enroll the push-on-write pass on an engine: weaves push-after-write!
  into the engine's post-commit :maintain hook (after any installed
  maintainer, so the pushed/conflicted :row is what the response
  serves). The engine is a map — build the handler / start! from the
  RETURNED engine. Recorded seam: engine.clj's boot does not auto-wire
  this yet (a named punt), so an embedding that declares one calls
  (mirror/with-push eng) itself — paydesk (assignment, team) and
  choreplan10 (prep_task) both do, from their mains and their
  conformance fixtures alike."
  [eng]
  (let [prior (:maintain eng)]
    (assoc eng :maintain
           (fn [engine kind action-name res]
             (let [res (if prior
                         (or (prior engine kind action-name res) res)
                         res)]
               (push-after-write! engine kind action-name res))))))

;; ── discovery ───────────────────────────────────────────────────────

(defn- row-by-external-id [eng kind xid]
  (store/with-tx (:storage eng)
    (fn [tx]
      (first (store/query-rows (:storage eng) tx kind
                               {:external_id xid} {:limit 1})))))

(def ^:private backfill-limit
  "resolve-refs! and resync! read the kind in ONE bounded fetch
  (query-rows pages by LIMIT only — no offset — so a loop cannot
  advance). Raised from 50k after a prod-scale mirror (paydesk
  support_doc, 100,760 rows) exceeded it and took the silent partial
  heal this bound promised never to allow; a kind at the bound gets
  a loud warning."
  250000)

(defn resolve-refs!
  "The external-ref backfill for one mirror kind: every row whose
  external-keyed ref is still unset while its external id IS set gets
  one resolution attempt, landed as a maintenance write (update-data!
  — no transition; resolving a ref is derivation, not an event, the
  same posture as the derived-fact maintainer). One indexed lookup
  per DISTINCT unresolved external id, not per row. Returns the
  number of rows healed. discover! runs this for every kind pointing
  at what it just minted — a target's arrival heals the edges that
  observed before it existed; a backfill never CLEARS a ref (only the
  sync writes recompute in full)."
  [eng kind]
  (let [rdef (get (inv/resources eng) kind)
        specs (external-ref-specs (:schema rdef))
        st (:storage eng)]
    (if (empty? specs)
      0
      (store/with-tx st
        (fn [tx]
          (let [rows (store/query-rows st tx kind {} {:limit backfill-limit})
                _ (when (= (count rows) backfill-limit)
                    (warn! "resolve-refs! for " (name kind) " hit the "
                           backfill-limit "-row bound — rows beyond it "
                           "keep their unresolved refs until the next pass"))
                ;; the distinct (target-kind, external-id) pairs a
                ;; heal could need across every row and spec — for a
                ;; scalar only when its ref is unset; for a :many
                ;; every array element (the resolved vector may grow)
                wanted (into #{}
                             (for [row rows
                                   [field {target :kind ek :external-key
                                           shape :shape}] specs
                                   xid (if (= :many shape)
                                         (let [xv (get-in row [:data ek])]
                                           (when (sequential? xv) xv))
                                         (when (nil? (get-in row [:data field]))
                                           [(get-in row [:data ek])]))
                                   :when (and (some? xid) (not= "" (str xid)))]
                               [target (str xid)]))
                resolved (into {}
                               (keep (fn [[target xid]]
                                       (when-some [t (first (store/query-rows
                                                             st tx target
                                                             {:external_id xid}
                                                             {:limit 1}))]
                                         [[target xid] (str (:id t))])))
                               wanted)]
            (reduce
             (fn [healed row]
               (let [data (:data row)
                     data' (reduce-kv
                            (fn [d field {target :kind ek :external-key
                                          shape :shape}]
                              (if (= :many shape)
                                ;; grow-only: write the recomputed
                                ;; resolvable projection when it has
                                ;; MORE elements than stored — a heal
                                ;; never shrinks a ref vector (only
                                ;; the sync writes recompute in full)
                                (let [xv (get d ek)
                                      cur (get d field)
                                      new (when (sequential? xv)
                                            (into []
                                                  (keep #(get resolved [target (str %)]))
                                                  xv))]
                                  (if (> (count new) (count cur))
                                    (assoc d field new)
                                    d))
                                (let [xid (get d ek)]
                                  (if (and (nil? (get d field)) (some? xid)
                                           (not= "" (str xid)))
                                    (if-some [rid (get resolved [target (str xid)])]
                                      (assoc d field rid)
                                      d)
                                    d))))
                            data specs)]
                 (if (= data data')
                   healed
                   (do (store/update-data! st tx kind (:id row) data'
                                           (:next-flip-at row))
                       (inc (long healed))))))
             0 rows)))))))

(defn discover!
  "One discovery pass for one mirror kind: mint a row per unknown
  external id ({:external_id id} only — the fields arrive by pull),
  then eagerly fill the new mints through pull-many. Returns the
  number of minted rows; an unreachable adapter mints nothing (the
  next pass retries)."
  [eng kind]
  (let [rdef (get (inv/resources eng) kind)
        spec (:mirror rdef)
        adapter (:adapter spec)
        ids (try (mapv str (discover adapter))
                 (catch Exception e
                   (warn! "discovery for " (name kind) " failed ("
                          (ex-message e) "); retrying next interval")
                   nil))
        new-ids (into []
                      (remove #(some? (row-by-external-id eng kind %)))
                      ids)]
    (doseq [xid new-ids]
      ;; :mint? — the engine's own birth door: full-schema model, no
      ;; app create guards (a mint speaks bookkeeping, not the
      ;; create-schema's vocabulary)
      (inv/create! eng kind {:external_id xid}
                   {:principal system-observer :mint? true}))
    (when (seq new-ids)
      ;; chunked like resync!'s pass: one prod-scale discovery handed
      ;; an adapter 100k ids in one call and its prepared statement
      ;; blew the driver's parameter cap — a batch fill degrades to
      ;; fill-on-first-read, but it shouldn't degrade wholesale
      (let [pulled (try (reduce (fn [m batch]
                                  (merge m (pull-many adapter (vec batch))))
                                {} (partition-all 500 new-ids))
                        (catch Exception e
                          (warn! "batch pull-through for " (name kind)
                                 " failed (" (ex-message e) "); each mint's "
                                 "own first read fills it instead")
                          {}))]
        (doseq [[xid entry] pulled
                ;; a :gone sentinel can't reach a fresh mint (discover
                ;; just named the id) — but the contract allows it, so
                ;; the fill skips rather than destructures
                :when (vector? entry)
                :let [[doc etag] entry
                      row (row-by-external-id eng kind xid)]
                :when row]
          (inv/invoke! eng kind (:id row) :observe_external
                       {:document doc :etag etag}
                       {:principal system-observer}))))
    ;; new targets heal the external-keyed refs pointing at them: a
    ;; row observed before its target existed carries a nil ref until
    ;; either its own next document change or this pass
    (when (seq new-ids)
      (doseq [[k other] (inv/resources eng)
              :when (and (:mirror other)
                         (some #(= kind (:kind %))
                               (vals (external-ref-specs (:schema other)))))]
        (resolve-refs! eng k)))
    (count new-ids)))

(defn discover-all!
  "One discovery pass over every enrolled mirror kind. Returns the
  total minted."
  [eng]
  (reduce (fn [n [kind rdef]]
            (if (:mirror rdef) (+ n (discover! eng kind)) n))
          0
          (inv/resources eng)))

(defn mirror-kinds [eng]
  (into [] (keep (fn [[k rdef]] (when (:mirror rdef) k)))
        (inv/resources eng)))

;; ── resync: the whole-kind heal ─────────────────────────────────────

(defn resync!
  "One full re-pull for one mirror kind: every non-conflicted row's
  external id batch-pulls through the adapter, and any changed etag
  (or off-fresh state) lands as observe_external — the whole-kind
  heal a document-shape change needs. migrate! covers SQL drift; the
  mirrored documents otherwise heal only row-by-row on read past
  TTL, which leaves collection views serving the old shape for
  hours. Conflicted rows stay a person's decision; an id the feed no
  longer carries keeps serving its stored truth (counted :gone).
  Returns {:checked n :rewritten n :gone n}, or nil after warning
  when the adapter is unreachable — resync is a heal, never a gate."
  [eng kind]
  (let [rdef (get (inv/resources eng) kind)
        spec (:mirror rdef)
        adapter (:adapter spec)
        st (:storage eng)
        rows (store/with-tx st
               (fn [tx] (store/query-rows st tx kind {}
                                          {:limit backfill-limit})))
        ;; conflicted stays a person's decision; an unclaimed local
        ;; birth has no external id to re-pull by
        candidates (into [] (remove #(or (= :conflicted (:state %))
                                         (nil? (get-in % [:data :external_id]))))
                         rows)]
    (try
      (let [pulled (reduce
                    (fn [m batch]
                      (merge m (pull-many
                                adapter
                                (mapv #(get-in % [:data :external_id]) batch))))
                    {} (partition-all 500 candidates))
            now ^Instant ((:now-fn eng))
            kctx (kind-sync-ctx rdef)
            ;; the pass statistics: per open field, how many documents
            ;; carry the key, how many rows it would change on, how
            ;; many rows hold a value — the census's, the churn
            ;; bounds', and the stagnation alarm's shared evidence
            pairs (into []
                        (keep (fn [row]
                                (let [e (get pulled
                                             (get-in row [:data :external_id]))]
                                  ;; :gone entries carry no document —
                                  ;; the census and churn statistics
                                  ;; speak only for rows the feed
                                  ;; answered about
                                  (when (vector? e)
                                    [row (first e)]))))
                        candidates)
            npairs (count pairs)
            stats (into {}
                        (map (fn [f]
                               [f {:present (count (filter (fn [[_ d]] (contains? d f)) pairs))
                                   :changed (count (filter (fn [[r d]]
                                                             (not= (get d f)
                                                                   (get-in r [:data f])))
                                                           pairs))
                                   :was-set (count (filter (fn [[r _]]
                                                             (some? (get-in r [:data f])))
                                                           pairs))}]))
                        (filter #(window-open? kctx % now) (:declared kctx)))
            prior (all-held adapter)
            ;; mass absence (whole-document kinds): a field absent
            ;; from EVERY pulled document while stored rows still
            ;; carry values is an observed deprecation (or a feed bug
            ;; — same defense) — held, not nil'd, loudly
            absent (if-not (= :whole (:mode kctx))
                     #{}
                     (into #{}
                           (keep (fn [[f {:keys [present was-set]}]]
                                   (when (and (pos? npairs) (zero? present)
                                              (pos? was-set))
                                     f)))
                           stats))
            ;; churn bounds: more rows changing than the declared
            ;; percent allows is held the same way (a reorg and a
            ;; botched import look identical at first sight);
            ;; ratifying a legitimate mass change is a law event
            churned (into #{}
                          (keep (fn [[f {:keys [changed]}]]
                                  (let [e (get-in kctx [:expects f])]
                                    (when (and (map? e)
                                               (> (* 100 (long changed))
                                                  (* (long (:churn e)) npairs))
                                               (not (contains? absent f)))
                                      f))))
                          stats)
            ;; a field the census just released was held with a
            ;; matching etag — re-apply those rows even though
            ;; nothing else changed, or the hold would ghost forever
            released (set/difference prior (into absent churned))]
        (swap! field-census assoc adapter
               {:absent absent :churned churned :as-of now})
        (doseq [f absent]
          (warn! "census for " (name kind) ": " (name f)
                 " is absent from the entire feed (" (count pulled)
                 " documents) while stored rows carry values — held, not "
                 "nil'd; ratify a real sunset with :frozen, or investigate "
                 "the feed"))
        (doseq [f churned]
          (warn! "churn for " (name kind) ": " (name f) " changed on "
                 (get-in stats [f :changed]) " of " npairs
                 " documents — over the declared {:churn "
                 (get-in kctx [:expects f :churn]) "} bound; held. A "
                 "legitimate mass change ratifies by widening the bound "
                 "(a law change), a feed bug by fixing the feed"))
        (doseq [[f {:keys [changed]}] stats]
          (when (and (= :volatile (get-in kctx [:expects f]))
                     (zero? (long changed)) (>= npairs 2))
            (warn! "stagnation for " (name kind) ": " (name f)
                   " is declared :expect :volatile but moved on none of "
                   npairs " documents — the feed may be stale (or this "
                   "resync ran hot on the heels of the last)")))
        (reduce
         (fn [acc row]
           (let [xid (get-in row [:data :external_id])
                 entry (get pulled xid)]
             (cond
               ;; the adapter SAID gone (the feed answered, the row was
               ;; absent — distinct from mere absence, which stays
               ;; ambiguous): the declared policy lands, quietly when
               ;; already applied
               (= :gone entry)
               (let [patch (when (map? (get-in rdef [:mirror :on-gone]))
                             (get-in rdef [:mirror :on-gone :set]))
                     encoded (schema/encode (:schema rdef) (:data row))
                     changed? (boolean (some (fn [[f v]]
                                               (not= (get encoded f) v))
                                             patch))]
                 (when (and patch (or changed? (not= :fresh (:state row))))
                   (inv/invoke! eng kind (:id row) :observe_gone
                                nil {:principal system-observer}))
                 (-> acc (update :checked inc) (update :gone inc)))

               (vector? entry)
               (let [[doc etag] entry
                     changed? (or (not= etag (get-in row [:data :external_etag]))
                                  (not= :fresh (:state row)))
                     releasable (into []
                                      (filter #(not= (get doc %)
                                                     (get-in row [:data %])))
                                      released)]
                 (cond
                   ;; the :immutable tripwire precedes the observe: a
                   ;; document moving a set-once value is
                   ;; conflict-shaped — the id may now name a
                   ;; different logical entity; resolve_conflict is
                   ;; the ratification door
                   (and changed?
                        (immutable-violation kctx row doc now))
                   (do (inv/invoke! eng kind (:id row) :mark_conflicted
                                    {:reason (immutable-violation kctx row doc now)}
                                    {:principal system-observer})
                       (-> acc (update :checked inc)
                           (update :conflicted inc)))

                   changed?
                   (do (inv/invoke! eng kind (:id row) :observe_external
                                    {:document doc :etag etag}
                                    {:principal system-observer})
                       (-> acc (update :checked inc)
                           (update :rewritten inc)))

                   ;; a released hold with an unchanged etag: the
                   ;; holding observe already RECORDED this document —
                   ;; completing its application (the doc's value, or
                   ;; nil where it lacks the key) is a maintenance
                   ;; write, not a new event (a fresh observe would
                   ;; natural-replay anyway). Derived facts over a
                   ;; released field recompute at the row's next
                   ;; transition (recorded).
                   (seq releasable)
                   (do (store/with-tx st
                         (fn [tx]
                           (store/update-data!
                            st tx kind (:id row)
                            (reduce #(assoc %1 %2 (get doc %2))
                                    (:data row) releasable)
                            (:next-flip-at row))))
                       (-> acc (update :checked inc)
                           (update :rewritten inc)))

                   ;; observed, unchanged: stamp the check (a
                   ;; maintenance write) so the TTL window resets —
                   ;; the boot resync buys the whole kind a fresh hour
                   :else
                   (do (store/with-tx st
                         (fn [tx]
                           (store/update-data!
                            st tx kind (:id row)
                            (assoc (:data row) :synced_at (str now))
                            (:next-flip-at row))))
                       (update acc :checked inc))))

               ;; plain absence from the batch: ambiguous (a gone row
               ;; and a down feed look identical here) — stored truth
               ;; keeps serving, counted, never patched
               :else
               (-> acc (update :checked inc) (update :gone inc)))))
         {:checked 0 :rewritten 0 :gone 0 :conflicted 0}
         candidates))
      (catch Exception e
        (warn! "resync for " (name kind) " failed (" (ex-message e)
               "); stored truth keeps serving")
        nil))))

(defn resync-all!
  "resync! every enrolled mirror kind — the boot heal. The discovery
  daemon runs it once, before its first pass, so a restart
  deterministically re-pulls the world: a document-shape change (or
  an outage a failed discovery would otherwise wait out for a full
  :discover-every interval) heals at the next boot."
  [eng]
  (doseq [kind (mirror-kinds eng)]
    (when-some [{:keys [checked rewritten gone]} (resync! eng kind)]
      (warn! "resync for " (name kind) ": " checked " checked, "
             rewritten " rewritten"
             (when (pos? (long gone)) (str ", " gone " gone-from-feed"))))))

(defn start-discovery!
  "The discovery daemon: one pass per kind on its declared
  :discover-every cadence (checked every 5s against a per-kind
  last-run stamp). Engine start! owns the lifecycle; tests call
  discover! directly."
  [eng]
  (let [stop (CountDownLatch. 1)
        last-run (atom {})
        last-resync (atom {})
        tick (fn []
               (doseq [kind (mirror-kinds eng)
                       :let [every-s (get-in (get (inv/resources eng) kind)
                                             [:mirror :discover-every])
                             now (System/currentTimeMillis)
                             last (get @last-run kind 0)]
                       :when (<= (* 1000 (long every-s)) (- now last))]
                 (swap! last-run assoc kind now)
                 (try (discover! eng kind)
                      (catch Exception e
                        (warn! "discovery pass for " (name kind) " failed: "
                               (ex-message e)))))
               ;; kinds declaring :resync-every also heal on a cadence
               ;; — gone rows and document-shape changes stop waiting
               ;; for the next boot (resync! already never gates)
               (doseq [kind (mirror-kinds eng)
                       :let [every-s (get-in (get (inv/resources eng) kind)
                                             [:mirror :resync-every])
                             now (System/currentTimeMillis)
                             last (get @last-resync kind 0)]
                       :when (and every-s
                                  (<= (* 1000 (long every-s)) (- now last)))]
                 (swap! last-resync assoc kind now)
                 (resync! eng kind)))
        t (Thread. ^Runnable
                   (fn []
                     ;; the boot heal precedes the first discovery:
                     ;; a restart re-pulls what it already holds, then
                     ;; discovers what it doesn't (and counts as every
                     ;; kind's first cadenced resync)
                     (resync-all! eng)
                     (let [now (System/currentTimeMillis)]
                       (doseq [k (mirror-kinds eng)]
                         (swap! last-resync assoc k now)))
                     (tick)
                     (loop []
                       (when-not (.await stop 5000 TimeUnit/MILLISECONDS)
                         (tick)
                         (recur))))
                   "waymark10-mirror-discovery")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-discovery! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)
