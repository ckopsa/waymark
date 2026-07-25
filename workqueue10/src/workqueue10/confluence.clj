(ns workqueue10.confluence
  "The confluence: N task sources flowing into ONE mirror kind.

  waymark10's Mirror assumes one kind ↔ one adapter (refresh!,
  discover!, push-after-write! all read a single :adapter). The
  confluence satisfies that without touching the framework: it IS one
  MirrorAdapter, and identity is namespaced — external_id is
  \"tag:source-id\" (\"chore:<uuid>\", \"meal:<uuid>\"), an opaque
  string to the engine, a routing fact to us. Every read and write
  splits the prefix and routes to the tagged TaskSource.

  EACH SOURCE SPEAKS CANONICAL. A TaskSource's pull answers the
  queue's own document shape (title / assignee_name / due_at / status
  / detail — status normalized to open|done|dropped), the same
  adapter-owns-the-translation move choreplan10's MealplanFeed makes
  with row->document. The confluence adds :source (the tag) on the
  way through — the routing layer owns the routing fact.

  ONE PUSH-PLAN FOR EVERY SOURCE. Because documents are canonical,
  the push translation is shared: only a local \"done\" has anything
  to say (POST the source's complete); an agreeing authority is
  :noop with its fresh etag — which is what lets prioritize (a
  hub-local write) push harmlessly, the round-trip doubling as a
  freshness check; a source that dropped the task while we completed
  it throws — the conflicted state, resolve_conflict decides. This
  deliberately differs from choreplan's push-plan (which throws on
  any non-done push): the queue HAS local-only writes, so a
  nothing-to-say push must be a lawful :noop, never a conflict.

  PARTIAL TOLERANCE. A down source degrades per-source, never
  feed-wide: discover skips it with a warning (its known rows go
  per-row unreachable through pull, the honest staleness the sync
  machine already renders), and pull-many drops its ids from the
  batch (they fill on first read, or when it returns). Recorded
  cosmetic: a resync! pass counts a down source's rows :gone in its
  stats — stored truth keeps serving, nothing is deleted.

  fake-source is the scriptable in-memory twin (the FakeFeed
  precedent): canonical docs, the SAME push-plan the real boundaries
  run, and an unseeded id reads as an open task the authority simply
  hasn't shown us — so conformance-walked rows push clean."
  (:require [clojure.string :as str]
            [waymark10.server.mirror :as mirror]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

;; ── the source seam ─────────────────────────────────────────────────

(defprotocol TaskSource
  "One authority's slice of the queue, already speaking canonical."
  (source-discover [s]
    "→ seq of source-local ids for the work worth queueing. Throw on
    unreachable.")
  (source-pull [s id]
    "→ [canonical-doc etag]. Throw on unreachable or gone (gone
    carries {:status 404} in ex-data).")
  (source-pull-many [s ids]
    "→ {id [canonical-doc etag]} for the ids the source still
    carries — gone rows drop from the batch. Throw on unreachable.")
  (source-push [s id document]
    "Run the push-plan against the authority's current truth → the
    new etag. Throw on unreachable or a refused move.")
  (source-create [s document]
    "Accept a document the authority has never seen and mint the
    identity → [source-local-id etag]. Throw on unreachable or when
    this authority takes no births from the queue — the failure
    lands conflicted with no external id, and resolve_conflict
    keep=local retries (the framework's create-push law)."))

;; ── the shared translation at the heart of push ─────────────────────

(defn push-plan
  "The pushed canonical document + the source's canonical status →
  the one move to make: :complete (POST the source's own action),
  :noop (nothing to say — a local-only write like prioritize, or an
  authority that already agrees), or a throw (we say done, the
  source dropped it; the conflicted state, resolve_conflict
  decides)."
  [document upstream-status]
  (cond
    (not= "done" (:status document)) :noop
    (= "done" upstream-status) :noop
    (= "open" upstream-status) :complete
    :else
    (throw (ex-info (str "complete does not apply — the source's task is "
                         (pr-str upstream-status)
                         "; resolve the conflict once the queue and the "
                         "authority agree")
                    {:upstream-status upstream-status}))))

;; ── namespaced identity ─────────────────────────────────────────────

(defn xid
  "tag + source-local id → the queue's external_id."
  [tag id]
  (str tag ":" id))

(defn split-xid
  "external_id → [tag source-local-id]. Refuses an unprefixed id —
  every row of this kind is born through the confluence."
  [x]
  (let [[tag id] (str/split (str x) #":" 2)]
    (when (nil? id)
      (throw (ex-info (str "external id " (pr-str (str x))
                           " carries no source tag") {})))
    [tag id]))

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "workqueue10 confluence: " parts))))

(defn- source-for [sources tag]
  (or (get sources tag)
      (throw (ex-info (str "no source registered for tag " (pr-str tag))
                      {:tags (vec (keys sources))}))))

;; ── the composite adapter ───────────────────────────────────────────

(defrecord ConfluenceFeed [sources]
  mirror/MirrorAdapter
  (discover [_]
    (into []
          (mapcat (fn [[tag src]]
                    (try (mapv #(xid tag %) (source-discover src))
                         (catch Exception e
                           ;; a down source discovers nothing THIS
                           ;; pass; its known rows go per-row
                           ;; unreachable through pull — honest
                           ;; partial staleness, never a dead feed
                           (warn! "discover for " tag " failed ("
                                  (ex-message e) "); skipping this pass")
                           nil))))
          sources))
  (pull [_ x]
    (let [[tag id] (split-xid x)
          [doc etag] (source-pull (source-for sources tag) id)]
      [(assoc doc :source tag) etag]))
  (pull-many [_ xids]
    (reduce-kv
     (fn [acc tag pairs]
       (let [src (get sources tag)
             ids (mapv second pairs)
             pulled (when src
                      (try (source-pull-many src ids)
                           (catch Exception e
                             ;; the down source's ids drop from the
                             ;; batch — they fill on first read, or
                             ;; when it returns
                             (warn! "pull-many for " tag " failed ("
                                    (ex-message e) "); its rows keep "
                                    "their stored truth")
                             nil)))]
         (reduce-kv (fn [m id entry]
                      (assoc m (xid tag id)
                             ;; :gone rides through untouched — the
                             ;; source's honest deletion sentinel
                             (if (vector? entry)
                               (let [[doc etag] entry]
                                 [(assoc doc :source tag) etag])
                               entry)))
                    acc pulled)))
     {}
     (group-by first (map split-xid xids))))
  (push [_ x document]
    (let [[tag id] (split-xid x)]
      (source-push (source-for sources tag) id document)))

  mirror/MirrorCreateAdapter
  (push-create [_ document]
    ;; a birth names its authority in :source (the create-schema's
    ;; law); the tagged source mints the identity and the confluence
    ;; namespaces it — the same routing every other verb rides
    (let [tag (:source document)
          _ (when (str/blank? (str tag))
              (throw (ex-info "a captured task names its :source — no authority, no birth" {})))
          [id etag] (source-create (source-for sources tag)
                                   (dissoc document :source))]
      [(xid tag id) etag])))

(defn confluence
  "sources: {tag TaskSource} — e.g. {\"chore\" … \"meal\" …}. The tag
  set is the :source enum's vocabulary; the two are declared together
  in resources/task.clj."
  [sources]
  (->ConfluenceFeed sources))

;; ── the scriptable twin ─────────────────────────────────────────────

(defn- content-etag [doc]
  (wire/sha256-hex (pr-str (into (sorted-map) doc))))

(defrecord FakeSource [state]
  TaskSource
  (source-discover [_]
    (let [{:keys [down discoverable]} @state]
      (when down (throw (ex-info "source unreachable" {})))
      (vec discoverable)))
  (source-pull [_ id]
    (let [{:keys [down removed docs]} @state]
      (when down (throw (ex-info "source unreachable" {})))
      (when (contains? removed id)
        (throw (ex-info (str id " is no longer in the source") {:status 404})))
      (if-some [doc (get docs id)]
        [doc (content-etag doc)]
        (throw (ex-info (str "no task " id) {:status 404})))))
  (source-pull-many [this ids]
    (let [{:keys [down]} @state]
      (when down (throw (ex-info "source unreachable" {})))
      (into {}
            (map (fn [id]
                   (try [(str id) (source-pull this id)]
                        (catch clojure.lang.ExceptionInfo e
                          ;; a removed! (or never-seeded) id is a gone
                          ;; row — the sentinel the real boundaries
                          ;; answer; the fakes hold the same law
                          (if (= 404 (:status (ex-data e)))
                            [(str id) :gone]
                            (throw e))))))
            ids)))
  (source-push [_ id document]
    (let [{:keys [down push-fail docs]} @state]
      (when down (throw (ex-info "source unreachable" {})))
      (when push-fail
        (throw (ex-info (if (string? push-fail)
                          push-fail
                          "the source's task changed under our push")
                        {})))
      ;; the same translation the real boundaries run; an unseeded id
      ;; reads as an open task the authority hasn't shown us yet (the
      ;; FakeFeed auto-vivify spirit — conformance-walked rows push
      ;; clean)
      (case (push-plan document (:status (get docs id) "open"))
        :noop (content-etag (get docs id document))
        :complete
        (let [doc' (assoc (or (get docs id)
                              (dissoc document :source)) :status "done")]
          (swap! state
                 (fn [s]
                   (-> s
                       (assoc-in [:docs id] doc')
                       (update :discoverable
                               #(vec (distinct (conj (vec %) id)))))))
          (content-etag doc')))))
  (source-create [_ document]
    (let [{:keys [down push-fail]} @state]
      (when down (throw (ex-info "source unreachable" {})))
      (when push-fail
        (throw (ex-info (if (string? push-fail)
                          push-fail
                          "the source refused the birth")
                        {})))
      (let [id (str "cap-" (inc (count (:docs @state))))
            doc (-> document (dissoc :source) (assoc :status "open"))]
        (swap! state
               (fn [s]
                 (-> s
                     (assoc-in [:docs id] doc)
                     (update :discoverable
                             #(vec (distinct (conj (vec %) id)))))))
        [id (content-etag doc)]))))

(defn fake-source
  "One authority in memory: scriptable via seed! / remove! / down! /
  fail-pushes!. Seed CANONICAL docs — the confluence stamps :source."
  []
  (->FakeSource (atom {:docs {} :discoverable [] :removed #{}
                       :down false :push-fail false})))

(defn seed!
  "Put a canonical task doc in the fake source (and, unless told
  otherwise, its discovery feed)."
  [fake id doc & [{:keys [discoverable?] :or {discoverable? true}}]]
  (swap! (:state fake)
         (fn [s]
           (cond-> (-> s
                       (assoc-in [:docs id] doc)
                       (update :removed disj id))
             discoverable? (update :discoverable
                                   #(vec (distinct (conj (vec %) id))))))))

(defn remove!
  "Simulate the authority no longer carrying the task: the next pull
  fails like a gone row."
  [fake id]
  (swap! (:state fake)
         (fn [s]
           (-> s
               (update :docs dissoc id)
               (update :discoverable (fn [d] (vec (remove #{id} d))))
               (update :removed conj id)))))

(defn down! [fake down?]
  (swap! (:state fake) assoc :down down?))

(defn fail-pushes!
  "Script the push seam: truthy makes every push throw (pass a string
  for the exact failure sentence); false restores success."
  [fake failing]
  (swap! (:state fake) assoc :push-fail failing))
