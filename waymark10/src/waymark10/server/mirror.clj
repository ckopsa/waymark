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

  Recorded punts, deliberately out of scope until a dogfood demands
  them: the per-kind discovery cursor (a restarted dev server
  re-discovers, idempotently), mirror cursors/webhooks (pushes and
  pulls are per-row, never a change feed), per-field authority
  (AuthoredMeta), pushing a locally-minted row out to the authority
  (creates never push — only declared domain actions do), and
  distinguishing unreachable-on-push from a true etag conflict (at
  this scope every push failure is the conflicted state; the resolve
  decides either way)."
  (:require [waymark10.guards :as g]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Duration Instant)
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
    carries — the batched twin of pull. Throw on unreachable.")
  (push [a external-id document]
    "Write the local document to the external system → the new etag.
    Throw on unreachable or on an external change under our feet —
    at this scope both are one recorded failure (the conflicted
    state; resolve_conflict decides). Pull-only adapters throw
    unconditionally and are never called (no :push-on-write)."))

;; ── the woven declaration ───────────────────────────────────────────

(def sync-states [:fresh :stale :unreachable :conflicted])

(def sync-action-names
  "The engine's doors on the sync machine — the names a push-on-write
  kind's own domain actions may not shadow, and the writes the push
  pass never pushes (pushing a sync write would loop)."
  #{:observe_external :mark_stale :mark_unreachable :mark_conflicted
    :resolve_conflict})

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

(def ^:private bookkeeping-schema
  [[:external_id [:string {:min 1 :max 256}]]
   [:external_etag {:optional true :x-display {:hidden true}}
    [:maybe [:string {:max 256}]]]
   [:synced_at {:optional true} [:maybe :waymark/instant]]
   ;; why the row sits conflicted — data, so the gap renders; cleared
   ;; by the next successful sync write
   [:conflict_reason {:optional true} [:maybe [:string {:max 280}]]]])

(defn- declared-fields [data-schema]
  (remove bookkeeping-fields (schema/entry-keys data-schema)))

(defn- observe-handler
  "The one sync write: the external document onto our data (matching
  declared fields only, bookkeeping excluded), etag and synced_at
  stamped, any conflict note cleared. Closes over the woven data
  schema so the applied document decodes to schema types like any
  other load."
  [data-schema]
  (let [declared (declared-fields data-schema)]
    (with-meta
      (fn [row inp ctx]
        (let [applied (select-keys (:document inp) declared)
              merged (merge (schema/encode data-schema (:data row))
                            applied
                            {:external_etag (:etag inp)
                             :synced_at (str (:now ctx))
                             :conflict_reason nil})]
          (assoc row :data (schema/decode data-schema merged))))
      ;; the canonical identity of the generic sync handler: one form,
      ;; every mirror kind — the imperative residue is this namespace's
      {:waymark10/form '(fn [row inp ctx]
                          (waymark10.server.mirror/apply-external
                           row inp ctx))})))

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
  and the row stays conflicted."
  [adapter data-schema]
  (let [declared (declared-fields data-schema)]
    (with-meta
      (fn [row inp ctx]
        (let [xid (get-in row [:data :external_id])
              encoded (schema/encode data-schema (:data row))]
          (if (= "remote" (:keep inp))
            (let [[doc etag] (pull adapter xid)
                  merged (merge encoded
                                (select-keys doc declared)
                                {:external_etag etag
                                 :synced_at (str (:now ctx))
                                 :conflict_reason nil})]
              (assoc row :data (schema/decode data-schema merged)))
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
  check-domain-actions! for their shape)."
  [rmap {:keys [adapter ttl-seconds discover-every push-on-write]}]
  (when (nil? adapter)
    (throw (t/definition-error
            (str (some-> (:kind rmap) name) ": a mirror declares its :adapter"))))
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
  (let [data-schema (into [:map] (concat bookkeeping-schema (rest (:schema rmap))))]
    (-> rmap
        (assoc :schema data-schema
               :states sync-states
               :initial :fresh
               :terminal #{}
               :mirror {:adapter adapter
                        :ttl-seconds (or ttl-seconds 300)
                        :discover-every (or discover-every 300)
                        :push-on-write (boolean push-on-write)}
               :actions
               (merge
                (:actions rmap)
                {:observe_external
                 {:from (set sync-states) :to :fresh
                  :input [:map
                          [:document [:map-of :keyword :any]]
                          [:etag [:string {:max 256}]]]
                  :guards [system-only]
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "Recording what the external system already says loses nothing here."}
                  :handler (observe-handler data-schema)
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
                  :input [:map [:reason [:string {:max 280}]]]
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
                  :handler (resolve-handler adapter data-schema)
                  :display {:label "Resolve conflict" :style :primary
                            :order 1}}}))
        ;; discovery's mint check queries the promoted column
        (update :filterable (fn [f] (update (or f {}) :external_id
                                            #(or % #{:eq})))))))

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
            (and (= :fresh (:state row))
                 (within-ttl? row ((:now-fn eng)) (:ttl-seconds spec))))
      row
      (let [xid (get-in row [:data :external_id])
            pulled (try (pull (:adapter spec) xid)
                        (catch Exception _ ::unreachable))]
        (if (= ::unreachable pulled)
          (if (= :unreachable (:state row))
            row
            (:row (inv/invoke! eng (:kind rdef) (:id row) :mark_unreachable
                               nil {:principal system-observer})))
          (let [[doc etag] pulled]
            (if (and (= etag (get-in row [:data :external_etag]))
                     (= :fresh (:state row)))
              row
              (:row (inv/invoke! eng (:kind rdef) (:id row) :observe_external
                                 {:document doc :etag etag}
                                 {:principal system-observer})))))))))

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
  domain actions (never the engine's sync doors — that would loop —
  and never a create: locally-minted rows reaching the authority is a
  recorded punt), push the exported document. Success lands as
  observe_external (etag + synced_at stamped); any failure lands as
  mark_conflicted with the adapter's own words — the local document
  stands, and resolve_conflict decides. A row already conflicted is
  left alone (waymark9's rule). Returns res with :row refreshed to
  the post-push truth."
  [eng kind action-name res]
  (let [rdef (get (inv/resources eng) kind)
        spec (:mirror rdef)
        domain? (and spec (:push-on-write spec)
                     (contains? (set (keys (:actions rdef))) action-name)
                     (not (contains? sync-action-names action-name)))]
    (if-not (and domain? (:transition res) (nil? (:replayed? res)))
      res
      (let [row (:row res)]
        (if (= :conflicted (:state row))
          res
          (let [xid (get-in row [:data :external_id])
                doc (export-document rdef row)
                pushed (try (push (:adapter spec) xid doc)
                            (catch Exception e e))]
            (if (instance? Exception pushed)
              (assoc res :row
                     (:row (inv/invoke!
                            eng kind (:id row) :mark_conflicted
                            {:reason (or (ex-message ^Exception pushed)
                                         "push failed")}
                            {:principal system-observer})))
              (assoc res :row
                     (:row (inv/invoke!
                            eng kind (:id row) :observe_external
                            {:document doc :etag pushed}
                            {:principal system-observer}))))))))))

(defn with-push
  "Enroll the push-on-write pass on an engine: weaves push-after-write!
  into the engine's post-commit :maintain hook (after any installed
  maintainer, so the pushed/conflicted :row is what the response
  serves). The engine is a map — build the handler / start! from the
  RETURNED engine. Recorded seam: engine.clj's boot does not auto-wire
  this yet (a named punt — no enrolled app serves a push-on-write
  mirror; mealplan's calendar is pull-only), so an embedding that
  declares one calls (mirror/with-push eng) itself."
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
      (inv/create! eng kind {:external_id xid} {:principal system-observer}))
    (when (seq new-ids)
      (let [pulled (try (pull-many adapter new-ids)
                        (catch Exception e
                          (warn! "batch pull-through for " (name kind)
                                 " failed (" (ex-message e) "); each mint's "
                                 "own first read fills it instead")
                          {}))]
        (doseq [[xid [doc etag]] pulled
                :let [row (row-by-external-id eng kind xid)]
                :when row]
          (inv/invoke! eng kind (:id row) :observe_external
                       {:document doc :etag etag}
                       {:principal system-observer}))))
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

(defn start-discovery!
  "The discovery daemon: one pass per kind on its declared
  :discover-every cadence (checked every 5s against a per-kind
  last-run stamp). Engine start! owns the lifecycle; tests call
  discover! directly."
  [eng]
  (let [stop (CountDownLatch. 1)
        last-run (atom {})
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
                               (ex-message e))))))
        t (Thread. ^Runnable
                   (fn []
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
