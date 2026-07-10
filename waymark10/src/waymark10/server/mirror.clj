(ns waymark10.server.mirror
  "The Mirror (phase 8, ported from waymark9 server/external.py and
  scoped to what mealplan10's event kind exercises): a kind whose rows
  are PULLED from an external authority, never pushed. The machine IS
  the sync machine — fresh / stale / unreachable — and the sync
  bookkeeping (external_id, external_etag, synced_at) is data, so
  staleness renders instead of hiding.

  Declared as {:mirror {:adapter … :ttl-seconds n :discover-every n}}
  via the `resource` helper below, which weaves the sync machine and
  the bookkeeping fields into the application's own declaration.

  The seams, each recorded:
  - PULL-THROUGH ON READ (refresh!): router's GET consults the mirror
    spec — a fresh row inside its TTL serves as stored; past the TTL
    (or off-fresh) the adapter pulls: a changed etag lands as an
    observe_external transition (system actor — audit and SSE carry
    changes we didn't make), an unreachable adapter marks the row
    unreachable ONCE and the stored truth keeps serving with its
    honest synced_at, an unchanged etag writes nothing (an
    \"observed, unchanged\" transition per TTL would be audit noise).
  - DISCOVERY (discover!): one pass asks the adapter's discover for
    the feed's current external ids and mints a row per unknown id —
    ordinary create!, system actor, {:external_id id} only — then
    eagerly fills the new mints through pull-many (one round trip,
    not N first-read pulls). The engine's runtime may run this on the
    declared :discover-every cadence (engine start!).
  - Sync transitions are system-actor only and HIDDEN (a hide guard):
    humans never see the bookkeeping doors.

  Recorded punts, deliberately out of scope until a dogfood demands
  them: push_on_write and the conflicted/reconcile pair (mealplan's
  calendar is read-only), the per-kind discovery cursor (a restarted
  dev server re-discovers, idempotently), and per-field authority
  (AuthoredMeta)."
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
  "The external system's shape, reduced to three reads — a mirror is
  pulled, never pushed."
  (discover [a]
    "→ seq of external ids currently in the feed. Throw on
    unreachable.")
  (pull [a external-id]
    "→ [document etag]; the document is wire-shaped (plain JSON
    values). Throw on unreachable or gone.")
  (pull-many [a external-ids]
    "→ {external-id [document etag]} for the ids the feed still
    carries — the batched twin of pull. Throw on unreachable."))

;; ── the woven declaration ───────────────────────────────────────────

(def sync-states [:fresh :stale :unreachable])

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

(def ^:private bookkeeping-fields #{:external_id :external_etag :synced_at})

(def ^:private bookkeeping-schema
  [[:external_id [:string {:min 1 :max 256}]]
   [:external_etag {:optional true :x-display {:hidden true}}
    [:maybe [:string {:max 256}]]]
   [:synced_at {:optional true} [:maybe :waymark/instant]]])

(defn- observe-handler
  "The one sync write: the external document onto our data (matching
  declared fields only, bookkeeping excluded), etag and synced_at
  stamped. Closes over the woven data schema so the applied document
  decodes to schema types like any other load."
  [data-schema]
  (let [declared (remove bookkeeping-fields (schema/entry-keys data-schema))]
    (with-meta
      (fn [row inp ctx]
        (let [applied (select-keys (:document inp) declared)
              merged (merge (schema/encode data-schema (:data row))
                            applied
                            {:external_etag (:etag inp)
                             :synced_at (str (:now ctx))})]
          (assoc row :data (schema/decode data-schema merged))))
      ;; the canonical identity of the generic sync handler: one form,
      ;; every mirror kind — the imperative residue is this namespace's
      {:waymark10/form '(fn [row inp ctx]
                          (waymark10.server.mirror/apply-external
                           row inp ctx))})))

(def ^:private sync-safety
  {:idempotent true :reversible false :confirm false
   :one-way "Sync bookkeeping; the next successful pull returns the mirror to fresh."})

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
  declaration. The app map must not declare :states/:initial/:actions
  of its own: the machine is the sync machine (waymark9's rule —
  domain state, if any, lives in data)."
  [rmap {:keys [adapter ttl-seconds discover-every] :as spec}]
  (when (nil? adapter)
    (throw (t/definition-error
            (str (some-> (:kind rmap) name) ": a mirror declares its :adapter"))))
  (when (some rmap [:states :initial :actions])
    (throw (t/definition-error
            (str (some-> (:kind rmap) name)
                 ": a mirror's machine IS the sync machine — declare domain "
                 "state in data, not :states/:initial/:actions"))))
  (let [data-schema (into [:map] (concat bookkeeping-schema (rest (:schema rmap))))]
    (-> rmap
        (assoc :schema data-schema
               :states sync-states
               :initial :fresh
               :terminal #{}
               :mirror {:adapter adapter
                        :ttl-seconds (or ttl-seconds 300)
                        :discover-every (or discover-every 300)}
               :actions
               {:observe_external
                {:from #{:fresh :stale :unreachable} :to :fresh
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
                 :display {:label "Mark unreachable"}}})
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
  stands; unchanged → nothing written. Returns the (possibly
  refreshed) decoded row."
  [eng rdef row]
  (let [spec (:mirror rdef)]
    (if (and (= :fresh (:state row))
             (within-ttl? row ((:now-fn eng)) (:ttl-seconds spec)))
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
