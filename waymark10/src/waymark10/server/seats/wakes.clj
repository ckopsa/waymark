(ns waymark10.server.seats.wakes
  "A seat's wake and its inbox as subscriptions, carved out of
  `waymark10.server.seats` (ticket bbf88202, carve 4): the filter its
  walk runs under, the computed default wake, the door subscriptions on
  its fire door that carry it (docs/spec-events.md E-5.4), and the
  managed pull subscriptions its inbox is heard through (E-5.5).

  seats.clj keeps a one-line def for each var another namespace or its
  own resource calls, so every `seats/` alias still resolves. This file
  requires nothing of seats: seats requires it."
  (:require [waymark10.machine :as machine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.seats.fire :as fire]
            [waymark10.server.seats.inbox :refer [inbox-of interactive-seat?
                                                  own-held-calls?]]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(def ^:private seats-actor fire/seats-actor)

;; ── the filter a walk runs under (bead waymark-fp62.12) ─────────────

(defn walk-entries
  "Every scope entry that names this kind, in the order they were
  written."
  [scope walk]
  (when walk
    (filterv #(= walk (str (:kind %))) scope)))

(defn walk-entry
  "The one entry a walk runs under, or nil. A lone entry is it. Several
  entries that ALL filter the kind read it as the union of their
  filters, and the walk is the part the seat can act on: the one entry
  among them that names a door (ticket d4211f9a — the others are sight
  alone, and a walk over rows with no door could never empty). Any
  other shape is no entry, as before."
  [entries]
  (if (next entries)
    (when (every? #(seq (:filter %)) entries)
      (let [doors (filter #(seq (:actions %)) entries)]
        (when-not (next doors) (first doors))))
    (first entries)))

(defn entry-filter
  "The filter those entries put on the walk, or nil.

  ONE entry, or none: openness absorbs (grants/surface-of), so a
  second entry naming the kind with no filter widens the leash back
  to the whole collection — and a walk narrower than the sight it
  runs under would break the promise this bead is about. Two entries
  therefore walk the kind unfiltered, exactly as the grant reads
  them."
  [entries]
  (not-empty (:filter (walk-entry entries))))

(defn walk-filter
  "The filter a seat's walk runs under (R-1), or nil when the walk
  runs under the kind's own default filter as it always has.

  Read from the stored row, because the sit reads it (mcp/walk-of)
  and the wake reads it (`effective-wake-on`). The guards below ask
  the same question of the input at the door."
  [seat-row]
  (let [walk (some-> (get-in seat-row [:data :walk]) str not-empty)]
    (entry-filter (walk-entries (get-in seat-row [:data :scope]) walk))))

;; ── the computed default wake (R-12.22) ─────────────────────────────

(def walk-create-action
  "The action a walk seat's default wake entry names: a row
  arriving in the queue is the work this seat exists to do."
  "create")

(def judgment-reopen-kind
  "The kind whose `reopen` puts a subject back in a judgment's queue."
  "verdict")

(def judgment-reopen-action
  "The door on it: a verdict taken back with nothing in its place."
  "reopen")

(defn effective-wake-on
  "What actually wakes this seat (R-12.22), as `wake-entry-schema`
  entries.

  A seat's `wake_on` field is retired (`:renames {:fields …}`) and is
  not read. A seat that walks a queue behaves as ONE entry — the walk's kind with
  the action `create` — and that default is computed HERE, at read
  time, because the spec says the engine writes nothing for it: a
  default in the row would be a value nobody chose, and a restate of
  `walk` would leave it naming the queue the seat no longer walks. A
  seat with neither wakes on its cadence and a person's fire alone,
  which is the empty vector.

  THE DEFAULT FOLLOWS THE FILTER (bead waymark-fp62.12, R-4). When
  the walk's scope entry carries a filter (`walk-filter`), a row
  ARRIVES in the queue two ways: somebody creates it there, and
  somebody moves it there. The computed entry is therefore the walk's
  kind, EVERY action of it, under that filter — the wake consumer
  judges the row that moved against the entry's filter after the
  transition committed (`wakes/moved-under?`), so a row a person
  finalizes into the walk wakes the seat and a row that leaves it does
  not. Every action INCLUDES the birth door: `create` is a door every
  kind serves and no kind lists in `:actions`, and dropping it would
  take away the one wake the unfiltered default already gives.
  `walk-rdef` is the walked kind's own declaration, which is where
  those action names live; called without it this answers the
  `create` default it always has.

  The count wake (R-12.24) asks nothing of the default: the computed
  entry carries no `at_least`, so it stays the transition wake it has
  always been — a walk seat wakes on the row that arrived, and a seat
  that wants a batch says how big a batch is. The settle
  (waymark-fp62.17) asks nothing of it either: the computed entry
  carries no `settle_seconds`, so the default wakes on the row that
  arrived and not after the arrivals stop. A seat that wants the
  trailing edge writes the entry and says how long the quiet is.

  A SEAT THAT SAYS A JUDGMENT HAS A THIRD WAY IN. Its queue is the
  judgment's subjects minus the ones with a standing verdict
  (`judgments/walk-conds`), so a subject re-enters it when its verdict
  is REOPENED — a transition on kind verdict, not on the walked kind.
  The computed default therefore carries a second entry, `verdict`
  `reopen` under this seat's own judgment, and a reopen wakes the
  seat exactly as a new subject does."
  ([seat-row] (effective-wake-on seat-row nil))
  ([seat-row walk-rdef]
   (let [walk (some-> (get-in seat-row [:data :walk]) str not-empty)
         judgment (some-> (get-in seat-row [:data :judgment]) str not-empty)
         fm (walk-filter seat-row)
         reopened (when judgment
                    [{:kind judgment-reopen-kind
                      :actions [judgment-reopen-action]
                      :filter {:judgment judgment}}])]
     (cond
       (and walk fm walk-rdef)
       (into [{:kind walk
               :actions (into [walk-create-action]
                              (map (comp name :name))
                              (machine/actions-seq walk-rdef))
               :filter fm}]
             reopened)
       walk (into [{:kind walk :actions [walk-create-action]}] reopened)
       :else []))))

;; ── the wake as subscriptions (docs/spec-events.md E-5.4) ───────────

(defn managed-wakes
  "The entries a walk seat's MANAGED subscriptions carry (E-5.4): the
  computed default of `effective-wake-on`. A filtered walk's every action
  is spelled as no actions at all, which since E-5.2 means every
  action, so the walked kind's declaration is not needed here. Empty
  for a seat that walks nothing."
  [seat-row]
  (let [fm (walk-filter seat-row)
        [walked & reopened] (effective-wake-on seat-row nil)]
    (cond-> []
      walked (conj (if fm {:kind (:kind walked) :actions [] :filter fm} walked))
      true (into reopened))))

(defn wake-subscription
  "One wake entry as the body of a subscription on the seat's fire
  door (E-5.4), its owner's own (`managed_by` owner, which a managed
  row's caller replaces): kind and actions are its predicate, a
  transition entry's filter its filter, and the settle, its cap and
  the count the receiver's, a count entry's filter its `count_filter`."
  [seat-row {:keys [kind actions filter at_least at_most] :as entry}]
  (let [counts? (or (some? at_least) (some? at_most))]
    {:kinds [(name kind)]
     :actions (when (seq actions) (mapv name actions))
     :filter (when (and (seq filter) (not counts?)) filter)
     :receiver (cond-> (merge {:type "door" :kind "seat"
                               :id (str (:id seat-row)) :action "fire"}
                              (select-keys entry [:at_least :at_most
                                                  :settle_seconds
                                                  :max_wait_seconds]))
                 (and (seq filter) counts?) (assoc :count_filter filter))
     :owner (some-> (get-in seat-row [:data :owner]) str not-empty)
     :managed_by "owner"}))

(defn wake-entries
  "The entries a seat's managed subscriptions carry (E-5.4): a walk
  seat's computed default (`managed-wakes`). What a seat was born with
  is written once, at its create (`missing-wake-subscriptions`), and
  those rows are its owner's."
  [seat-row]
  (vec (managed-wakes seat-row)))

(defn wake-token [v] (if (keyword? v) (name v) (str v)))

(defn door-entries
  "One door subscription as the wake entries it hears (E-4.1): one for
  each kind it hears, with its actions (none is every action of the
  kind and `create`, as E-5.2 reads it), its filter (a count
  receiver's `count_filter`) and its count. `resources` is the kind
  map. The cracks audit and `wakes-of` both read a subscription so."
  [resources sub]
  (let [{:keys [kinds actions filter receiver]} (:data sub)
        {:keys [at_least at_most count_filter]} receiver]
    (for [k kinds
          :let [rdef (get resources (keyword (wake-token k)))
                fm (or (not-empty filter) (not-empty count_filter))]]
      (cond-> {:kind (wake-token k)
               :actions (if (seq actions)
                          (mapv wake-token actions)
                          (into ["create"] (map (comp wake-token :name))
                                (some-> rdef machine/actions-seq)))}
        fm (assoc :filter fm)
        (some? at_least) (assoc :at_least at_least)
        (some? at_most) (assoc :at_most at_most)))))

(defn fire-door-of?
  "Is `sub` a door subscription on this seat's fire door?"
  [seat-row sub]
  (and (= "door" (some-> (get-in sub [:data :receiver :type]) wake-token))
       (= (str (:id seat-row)) (str (get-in sub [:data :receiver :id])))))

(defn door-subscribed?
  "Does this seat hold a door subscription on its fire door short of
  revoked? Such a seat is woken by the door path alone (E-3.9): the
  `:wakes` consumer does not match it. `subs` are the subscriptions as
  they stand."
  [seat-row subs]
  (boolean (some #(and (fire-door-of? seat-row %)
                       (not= "revoked" (some-> (:state %) wake-token)))
                 subs)))

(defn wakes-of
  "What wakes this seat (E-5.4), as `wake-entry-schema` entries: the
  door subscriptions on its fire door, each read as `door-entries`
  reads it with its receiver's settle and cap. Only an ACTIVE row
  wakes it, so an entry whose row its owner paused does not. A seat
  that holds no door subscription short of revoked wakes by a walk
  seat's computed default (`effective-wake-on`), which its managed rows carry once they are written. `subs` are
  the subscriptions as they stand, `resources` the kind map and
  `walk-rdef` the walked kind's declaration."
  [resources seat-row walk-rdef subs]
  (let [mine (filterv #(and (fire-door-of? seat-row %)
                            (not= "revoked" (some-> (:state %) wake-token)))
                      subs)]
    (if (empty? mine)
      (effective-wake-on seat-row walk-rdef)
      (into []
            (comp (filter #(= "active" (some-> (:state %) wake-token)))
                  (mapcat (fn [sub]
                            (let [settle (into {} (filter (comp some? val))
                                               (select-keys (get-in sub [:data :receiver])
                                                            [:settle_seconds :max_wait_seconds]))]
                              (map #(merge % settle) (door-entries resources sub))))))
            mine))))

(defn managed-by-seat? [sub]
  (= "seat" (some-> (get-in sub [:data :managed_by]) name)))

(defn missing-wake-subscriptions
  "The subscription bodies a seat is still owed (E-5.4), given `subs`,
  the subscriptions as they stand. A seat's retired wake field is not
  read, at its birth or since: no birth carries one. A walk seat is owed
  one managed row for each entry of its computed default past the
  managed rows on its fire door, active or paused. None for an
  interactive seat, and none for a seat that holds door subscriptions
  but no managed one: those rows already hear for it, and a second set
  would wake it twice."
  [seat-row subs]
  (let [mine (filter #(and (= "door" (some-> (get-in % [:data :receiver :type]) name))
                           (= (str (:id seat-row))
                              (str (get-in % [:data :receiver :id]))))
                     subs)
        held (count (filter managed-by-seat? mine))]
    (cond
      (interactive-seat? seat-row) []
      (and (seq mine) (zero? held)) []
      :else (mapv #(assoc (wake-subscription seat-row %) :managed_by "seat")
                  (drop held (managed-wakes seat-row))))))

(defn keep-managed-wakes!
  "The seat's managed subscriptions brought in step with its
  `wake-entries` (E-5.4): each managed row on its fire door is restated
  to its entry (resumed first when paused), and a row left with no
  entry is paused. `invoke'` and `find'` are a ctx's doors. The entries
  past its rows are not created here (definitions/subscribe-seat!). The
  rows a seat's birth wrote are its owner's, and not kept here."
  [invoke' find' row]
  (let [after (wake-entries row)
        mine (into []
                   (comp (mapcat #(find' :subscription {:state %} {:limit 500}))
                         (filter #(and (managed-by-seat? %)
                                       (= (str (:id row))
                                          (str (get-in % [:data :receiver :id]))))))
                   [:active :paused])
        ;; → the row as written, whose version the next write fences on
        write! (fn [sub action body]
                 (let [id (str (:id sub))]
                   (or (:row (invoke' :subscription id action body
                                      {:as seats-actor
                                       :if-match (inv/etag :subscription id
                                                           (:version sub))}))
                       sub)))]
    (doseq [[sub e] (map vector mine after)
            :let [sub (if (= "paused" (name (:state sub)))
                        (write! sub :resume nil)
                        sub)]]
      (write! sub :restate (dissoc (wake-subscription row e) :owner :managed_by)))
    (doseq [sub (drop (count after) mine)
            :when (= "active" (name (:state sub)))]
      (write! sub :pause nil))
    nil))

(defn restate-managed-wakes!
  "The seat's write door keeps its managed subscriptions (E-5.4): when
  its `wake-entries` move, each managed row is restated to its new
  entry (resumed first when paused), and a row left with no entry is
  paused. Inside the seat's write and as the seats actor, so a refusal
  rolls the restate back; a seat with no managed row is not written.
  The entries past its rows are created after the write commits
  (definitions/subscribe-seat!). A rehearsal's ctx holds no pen and
  writes nothing."
  [ctx was row]
  (let [invoke' (:invoke ctx)
        find' (:find ctx)]
    (when (and invoke' find' (not= (wake-entries was) (wake-entries row)))
      (keep-managed-wakes! invoke' find' row))
    nil))

(defn- heard-alike
  "What a door subscription's body hears, as one value two that hear
  alike share: its kinds and actions, its filter and its count."
  [{:keys [kinds actions filter receiver]}]
  (let [plain (fn plain [x]
                (cond (map? x) (into {} (map (fn [[k v]] [(wake-token k) (plain v)])) x)
                      (coll? x) (mapv plain x)
                      (keyword? x) (name x)
                      :else x))]
    [(set (map wake-token kinds)) (set (map wake-token actions))
     (not-empty (plain filter)) (not-empty (plain (:count_filter receiver)))
     (some-> (:at_least receiver) long) (some-> (:at_most receiver) long)]))

(declare wake-seat-through!)

(defn wake-seat!
  "The seat's wakes written as door subscriptions on its fire door, its
  owner's own (E-5.4): one for each of `entries`, wake entries, that no
  active or paused row on that door already hears, created through the
  subscription kind's door as the seats actor. Each managed row on that
  door, the computed default a walk seat's birth wrote
  (`missing-wake-subscriptions`), is then released to its owner, and
  paused unless it hears one of the entries, so the seat wakes by what
  was written, and once. A seat that already holds a row on that door
  its seat does not keep is left as it is: those rows are its owner's
  wakes, born with it or restated since, and a second set would wake it
  twice; so a second call writes nothing. An interactive seat is owed
  no wake row, and none is written. The seed's `:wakes` step calls it,
  and the wakes are what the seed wrote, so the pause acknowledges
  `a-dropped-wake-opens-no-crack`: a crack they leave is the seed's.
  `wake-seat-through!` is the same, through a write's own doors.
  → the rows it wrote."
  [eng seat-row entries]
  (let [st (:storage eng)
        as (fn [opts] (merge {:principal seats-actor} (dissoc opts :as)))]
    (wake-seat-through!
     (fn [kind body opts] (inv/create! eng kind body (as opts)))
     (fn [kind id action body opts] (inv/invoke! eng kind id action body (as opts)))
     (fn [kind where opts]
       (store/with-tx st (fn [tx] (store/query-rows st tx kind where opts))))
     seat-row entries)))

(defn wake-seat-through!
  "`wake-seat!` through `create'`, `invoke'` and `find'`, in the shape of
  a ctx's `:create`, `:invoke` and `:find`, each write as the seats
  actor (`:as`): a seat born inside another kind's write (the org plan's
  onboarding seat) is woken in that write's transaction, by the same
  rule the seed's `:wakes` step is. → the rows it wrote."
  [create' invoke' find' seat-row entries]
  (let [mine #(into []
                    (comp (mapcat (fn [s] (find' :subscription {:state s} {:all true})))
                          (filter (partial fire-door-of? seat-row)))
                    [:active :paused])]
   (if (or (interactive-seat? seat-row) (empty? entries)
           (some (complement managed-by-seat?) (mine)))
    []
    (let [as {:as seats-actor}
          wanted (into #{} (map #(heard-alike (wake-subscription seat-row %))) entries)
          heard (into #{} (map (comp heard-alike :data)) (mine))
          made (into []
                     (keep (fn [e]
                             (let [body (wake-subscription seat-row e)]
                               (when-not (contains? heard (heard-alike body))
                                 (:row (create' :subscription body as))))))
                     entries)]
      (doseq [sub (mine)
              :when (managed-by-seat? sub)
              :let [id (str (:id sub))]]
        (invoke' :subscription id :release_to_owner {:managed_by "owner"} as)
        (when (and (= "active" (wake-token (:state sub)))
                   (not (contains? wanted (heard-alike (:data sub)))))
          (invoke' :subscription id :pause nil
                   (assoc as :acknowledged #{:a-dropped-wake-opens-no-crack}))))
      made))))

;; ── the inbox as subscriptions (docs/spec-events.md E-5.5) ──────────

(defn pull-of-seat?
  "Is `sub` a pull receiver on this seat's inbox (E-3.9)?"
  [seat-row sub]
  (and (= "pull" (some-> (get-in sub [:data :receiver :type]) name))
       (= (str (:id seat-row)) (str (get-in sub [:data :receiver :id])))))

(defn inbox-subscriptions
  "The bodies of a seat's MANAGED pull subscriptions (E-5.5): one for
  each kind its `inbox-of` names in `only`, since an entry ANDs its
  fields and `only` ORs its kinds (E-1.2). Each hears its kind's
  actions as `only` gives them (none is every action, E-1.3), leaves
  out the seat's own moves, and carries the seat's cues of its kind in
  their list order: a cue only notes a transition the inbox heard, and
  that transition has one kind. Owned by the seat's owner, as E-5.4's
  are. Empty for a seat with no inbox.

  An inbox that says `held_call: own` narrows its held_call row by a
  `filter` on `caller`, the seat's sitter (`sitter-id`), which the
  one matcher judges (docs/spec-events.md E-1.4); that row keeps the
  seat's own moves, since the seat filed those calls
  (`hears-own-calls?`)."
  [seat-row]
  (let [{:keys [only cues] :as inbox} (inbox-of seat-row)
        id (str (:id seat-row))
        own-calls? (own-held-calls? inbox)
        owner (some-> (get-in seat-row [:data :owner]) str not-empty)]
    (into []
          (map (fn [[k acts]]
                 (let [k (name k)
                       mine (filterv #(= k (some-> (:kind %) name)) cues)]
                   (cond-> {:kinds [k]
                            :actions (when (seq acts) (mapv name acts))
                            :skip_actors [(str "seat:" id)]
                            :receiver (cond-> {:type "pull" :kind "seat" :id id}
                                        (seq mine) (assoc :cues mine))
                            :owner owner
                            :managed_by "seat"}
                     (and own-calls? (= "held_call" k))
                     (-> (assoc :filter {:caller (str "seat:" id)})
                         (dissoc :skip_actors))))))
          (sort-by (comp name key) only))))

(defn unheard-cues
  "The cues of a seat's inbox whose kind no `only` kind names. No pull
  subscription carries them: they could never fire (E-5.5)."
  [seat-row]
  (let [{:keys [only cues]} (inbox-of seat-row)
        heard (into #{} (map (comp name key)) only)]
    (filterv #(not (contains? heard (some-> (:kind %) name))) cues)))

(defn- held-kind [sub]
  (some-> (first (get-in sub [:data :kinds])) name))

(defn hears-own-calls?
  "Is `sub` this seat's pull of the held calls it filed itself, its
  inbox's `held_call: own`: a held_call pull whose filter names the
  seat's sitter as `caller`? The inbox door keeps the seat's own moves
  on it, since a proposal the seat files is born by its own hand."
  [seat-row sub]
  (and (pull-of-seat? seat-row sub)
       (= "held_call" (held-kind sub))
       (= (str "seat:" (:id seat-row))
          (some-> (get-in sub [:data :filter :caller]) str))))

(defn missing-inbox-subscriptions
  "The pull subscription bodies a seat is still owed (E-5.5), given
  `subs`, the subscriptions as they stand: those of its
  `inbox-subscriptions` whose kind no managed pull row of the seat,
  active or paused, already hears. A directly written pull row is
  neither counted nor touched."
  [seat-row subs]
  (let [held (into #{}
                   (comp (filter #(and (managed-by-seat? %) (pull-of-seat? seat-row %)))
                         (keep held-kind))
                   subs)]
    (filterv #(not (contains? held (first (:kinds %))))
             (inbox-subscriptions seat-row))))

(defn restate-managed-inbox!
  "The seat's write door keeps its managed pull subscriptions (E-5.5)
  as `restate-managed-wakes!` keeps its door ones: when its
  `inbox-subscriptions` move, each managed pull row whose kind is still
  heard is restated to its new body (resumed first when paused), and a
  row whose kind left `only` is paused. The kinds newly heard are
  created after the write commits (definitions/subscribe-seat!)."
  [ctx was row]
  (let [after (inbox-subscriptions row)
        invoke' (:invoke ctx)
        find' (:find ctx)]
    (when (and invoke' find' (not= (inbox-subscriptions was) after))
      (let [by-kind (into {} (map (juxt (comp first :kinds) identity)) after)
            mine (into []
                       (comp (mapcat #(find' :subscription {:state %} {:limit 500}))
                             (filter #(and (managed-by-seat? %) (pull-of-seat? row %))))
                       [:active :paused])
            write! (fn [sub action body]
                     (let [id (str (:id sub))]
                       (or (:row (invoke' :subscription id action body
                                          {:as seats-actor
                                           :if-match (inv/etag :subscription id
                                                               (:version sub))}))
                           sub)))]
        ;; one row a kind: a second managed row of a kind is paused
        (reduce (fn [seen sub]
                  (let [k (held-kind sub)
                        body (when-not (contains? seen k) (get by-kind k))]
                    (cond
                      body (let [sub (if (= "paused" (name (:state sub)))
                                       (write! sub :resume nil)
                                       sub)]
                             (write! sub :restate (dissoc body :owner :managed_by)))
                      (= "active" (name (:state sub))) (write! sub :pause nil))
                    (conj seen k)))
                #{} mine)))
    nil))
