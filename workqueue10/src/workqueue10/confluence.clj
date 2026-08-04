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

  A SECOND CONFLUENCE, over the same sources, carries the LISTS. Two
  of the authorities the queue drinks from keep their work in named
  lists — google's task lists, home assistant's todo entities — and a
  list is a row, not a prefix: it has a title a person reads, an
  identity a task can point at, and an authority that owns it. The
  list feed is its own MirrorAdapter (list-confluence) over its own
  protocol (TaskListSource), for one reason: waymark10's Mirror binds
  one kind to one adapter, and :task_list is a kind. It is NOT a
  second method on TaskSource, because a source that has no lists —
  a chore run, a prep task, a captured todo in the generic fake —
  should not have to answer a question its authority never asks.
  list-sources reads which of the confluence's sources speak lists
  from the protocols they satisfy, so adding the third one is
  declaring it, not editing a map.

  fake-source is the scriptable in-memory twin (the FakeFeed
  precedent): canonical docs, the SAME push-plan the real boundaries
  run, and an unseeded id reads as an open task the authority simply
  hasn't shown us — so conformance-walked rows push clean. It speaks
  lists too (seed-list!), so the queue's end-to-end story can watch a
  task's list ref resolve without standing up a real authority."
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

(defprotocol TaskListSource
  "The lists ONE authority keeps, already speaking canonical — the
  optional second half of a source, for the authorities that group
  their work by name. A source implements this or it doesn't; the
  ones that don't are simply absent from the list feed (list-sources
  reads the protocol, not a configuration), which is the whole reason
  these three verbs are not on TaskSource.

  A list document is small on purpose: a :title, and whatever hrefs
  point back at the authority. The routing tag arrives the way it
  does for tasks — stamped by the confluence, never by the source."
  (list-discover [s]
    "→ seq of source-local list ids the queue should mirror. Throw on
    unreachable.")
  (list-pull [s id]
    "→ [canonical-list-doc etag]. Throw on unreachable or gone (gone
    carries {:status 404} in ex-data).")
  (list-pull-many [s ids]
    "→ {id [canonical-list-doc etag]} for the lists the authority
    still keeps; a list it can prove is gone answers :gone. Throw on
    unreachable."))

(defn list-sources
  "The confluence's sources, narrowed to the ones that keep lists —
  the map list-confluence rides. Reading the protocol rather than a
  hand-kept tag set means a source grows a list feed by implementing
  one thing, and the wiring in main follows for free."
  [sources]
  (into {} (filter (fn [[_ s]] (satisfies? TaskListSource s))) sources))

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

;; ── the composite adapters ──────────────────────────────────────────
;;
;; Two feeds, one routing law. The fan-out below is shared because the
;; partial-tolerance posture is: a source that throws costs its own
;; rows a pass and nothing else, on either feed.

(defn- tell
  "One per-tag pass outcome to the wired :report-fn (the breaker
  panel's feed, waymark-kyg.1). nil report-fn says nothing; a
  reporter that throws is warned and dropped — health reporting must
  never cost the pass it reports on."
  [report-fn tag ok? error]
  (when report-fn
    (try (report-fn {:tag tag :ok? ok? :error error})
         (catch Exception e
           (warn! "pass reporter for " tag " failed (" (ex-message e) ")")))))

(defn- fan-discover
  "Every tagged source's ids, namespaced. A source that throws
  discovers nothing THIS pass; its known rows go per-row unreachable
  through pull — honest partial staleness, never a dead feed."
  [sources report-fn what discover-one]
  (into []
        (mapcat (fn [[tag src]]
                  (try (let [ids (mapv #(xid tag %) (discover-one src))]
                         (tell report-fn tag true nil)
                         ids)
                       (catch Exception e
                         (warn! what " discover for " tag " failed ("
                                (ex-message e) "); skipping this pass")
                         (tell report-fn tag false (ex-message e))
                         nil))))
        sources))

(defn- fan-pull-many
  "The batched read, split by tag and re-prefixed on the way back. A
  down source's ids drop from the batch (they fill on first read, or
  when it returns); a :gone sentinel rides through untouched — the
  source's honest deletion signal, which only a declared :on-gone
  policy gives meaning to."
  [sources report-fn what pull-many-one stamp xids]
  (reduce-kv
   (fn [acc tag pairs]
     (let [src (get sources tag)
           ids (mapv second pairs)
           pulled (when src
                    (try (let [m (pull-many-one src ids)]
                           (tell report-fn tag true nil)
                           m)
                         (catch Exception e
                           (warn! what " pull-many for " tag " failed ("
                                  (ex-message e) "); its rows keep "
                                  "their stored truth")
                           (tell report-fn tag false (ex-message e))
                           nil)))]
       (reduce-kv (fn [m id entry]
                    (assoc m (xid tag id)
                           (if (vector? entry)
                             (let [[doc etag] entry] [(stamp tag doc) etag])
                             entry)))
                  acc pulled)))
   {}
   (group-by first (map split-xid xids))))

(defn- stamp-task
  "The two routing facts a source cannot know about itself: which
  authority this row drinks from, and — when the source keeps lists —
  the confluence's spelling of the list the task lives in.

  A source names its list the way its own authority does (\"MTIzNA\",
  \"todo.woodworking\"); the SAME tag that namespaces the row's
  identity namespaces the key, so a task's :list_key is exactly the
  matching :task_list row's external_id and the ref resolves on the
  default match. One concept, one spelling, both feeds."
  [tag doc]
  (cond-> (assoc doc :source tag)
    (not (str/blank? (str (:list_key doc))))
    (assoc :list_key (xid tag (:list_key doc)))))

(defn- stamp-list [tag doc] (assoc doc :source tag))

(defn- unstamp-list-key
  "stamp-task, run backwards, for the one direction a document travels
  OUT: a birth names the list it should land in the way the queue
  spells it (\"gtasks:MTIzNA\" — the :task_list row's own external
  id), and the source that will honour it only knows its authority's
  half. A key tagged for ANOTHER authority refuses here rather than
  being handed over: the create door's guard says the same thing in a
  sentence a person reads, and this is the routing seam holding the
  line for anything that reaches it another way."
  [tag doc]
  (if (str/blank? (str (:list_key doc)))
    doc
    (let [[key-tag id] (split-xid (:list_key doc))]
      (when-not (= key-tag tag)
        (throw (ex-info (str "a " tag " capture cannot land in list "
                             (pr-str (str (:list_key doc))) " — that list is "
                             key-tag "'s")
                        {})))
      (assoc doc :list_key id))))

(defrecord ConfluenceFeed [sources report-fn]
  mirror/MirrorAdapter
  (discover [_] (fan-discover sources report-fn "task" source-discover))
  (pull [_ x]
    (let [[tag id] (split-xid x)
          [doc etag] (source-pull (source-for sources tag) id)]
      [(stamp-task tag doc) etag]))
  (pull-many [_ xids]
    (fan-pull-many sources report-fn "task" source-pull-many stamp-task
                   xids))
  (push [_ x document]
    (let [[tag id] (split-xid x)]
      (source-push (source-for sources tag) id document)))

  mirror/MirrorCreateAdapter
  (push-create [_ document]
    ;; a birth names its authority in :source (the create-schema's
    ;; law); the tagged source mints the identity and the confluence
    ;; namespaces it — the same routing every other verb rides. The
    ;; list a birth names travels the same way in reverse: the queue's
    ;; spelling goes in, the authority's own comes back out.
    (let [tag (:source document)
          _ (when (str/blank? (str tag))
              (throw (ex-info "a captured task names its :source — no authority, no birth" {})))
          [id etag] (source-create (source-for sources tag)
                                   (unstamp-list-key
                                    tag (dissoc document :source)))]
      [(xid tag id) etag])))

(defn confluence
  "sources: {tag TaskSource} — e.g. {\"chore\" … \"meal\" …}. The tag
  set is the :source enum's vocabulary; the two are declared together
  in resources/task.clj. report-fn (optional) hears each fan pass's
  per-tag outcome — the breaker panel's feed."
  ([sources] (confluence sources nil))
  ([sources report-fn] (->ConfluenceFeed sources report-fn)))

(defrecord ListConfluence [sources report-fn]
  mirror/MirrorAdapter
  (discover [_] (fan-discover sources report-fn "task_list" list-discover))
  (pull [_ x]
    (let [[tag id] (split-xid x)
          [doc etag] (list-pull (source-for sources tag) id)]
      [(stamp-list tag doc) etag]))
  (pull-many [_ xids]
    (fan-pull-many sources report-fn "task_list" list-pull-many stamp-list
                   xids))
  (push [_ x _document]
    ;; :task_list is pull-only and this throw is unreachable through
    ;; the sync machine (the framework never pushes a kind that does
    ;; not declare :push-on-write). It is a sentence rather than a
    ;; stub because the one door that COULD reach it — a person
    ;; resolving a conflict keep=local — deserves to be told why it
    ;; cannot: the queue mirrors the household's lists, it does not
    ;; rename them.
    (throw (ex-info (str "the queue does not write task lists — " (str x)
                         " is mirrored from its authority, and renaming "
                         "or deleting it happens there")
                    {}))))

(defn list-confluence
  "sources: {tag TaskListSource} — the list-keeping subset of the
  confluence's own sources (list-sources narrows it). One adapter for
  the :task_list kind, routing on exactly the same tags the task feed
  routes on, so \"gtasks:MTIzNA\" names a list and
  \"gtasks:MTIzNA/t-1\" names a task in it. report-fn as on
  confluence — both feeds speak the same tag to the same breaker."
  ([sources] (list-confluence sources nil))
  ([sources report-fn] (->ListConfluence sources report-fn)))

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
        [id (content-etag doc)])))

  TaskListSource
  ;; the fake keeps lists too, so the queue's story can watch a task's
  ;; :task_list ref resolve without a real authority. Seeded lists are
  ;; the whole truth here: an unseeded id is gone, and a down source
  ;; refuses every verb exactly as it does on the task feed.
  (list-discover [_]
    (let [{:keys [down lists]} @state]
      (when down (throw (ex-info "source unreachable" {})))
      (vec (sort (keys lists)))))
  (list-pull [_ id]
    (let [{:keys [down lists]} @state]
      (when down (throw (ex-info "source unreachable" {})))
      (if-some [doc (get lists id)]
        [doc (content-etag doc)]
        (throw (ex-info (str "no list " id) {:status 404})))))
  (list-pull-many [_ ids]
    (let [{:keys [down lists]} @state]
      (when down (throw (ex-info "source unreachable" {})))
      (into {}
            (map (fn [id]
                   [(str id) (if-some [doc (get lists (str id))]
                               [doc (content-etag doc)]
                               :gone)]))
            ids))))

(defn fake-source
  "One authority in memory: scriptable via seed! / seed-list! /
  remove! / down! / fail-pushes!. Seed CANONICAL docs — the
  confluence stamps :source (and namespaces a task's :list_key)."
  []
  (->FakeSource (atom {:docs {} :discoverable [] :removed #{} :lists {}
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

(defn seed-list!
  "Put a canonical LIST doc in the fake source's list feed, under the
  source-local id a seeded task's :list_key names — the pair the
  :task_list ref is made of."
  [fake id doc]
  (swap! (:state fake) assoc-in [:lists (str id)] doc))

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
