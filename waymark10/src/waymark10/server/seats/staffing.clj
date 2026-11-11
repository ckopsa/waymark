(ns waymark10.server.seats.staffing
  "The crack a staffing change opens, the walk rows a restate drops,
  and the replay every restate carries (ticket 9b8c96ae, round six:
  moved out of `waymark10.server.seats` unchanged).

  A seat is data, and a restate that narrows a scope or drops a wake
  can leave a state no seat moves. What lives here reads the cracks
  audit's rule (handed over by `waymark10.server.cracks` through
  `staffing-rule!`) over the active seats as they stand and as they
  would stand, the walk replay over the last 14 days, and the log
  under the seat's sittings. It requires nothing of the seats
  namespace: the seat and subscription doors in seats.clj refer the
  guards and helpers below by name. `opens-no-crack` and the two
  dropped-wake guards stay in seats.clj, where factory10's
  cracks_gate_test and webhooks read them."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.grants :as grants]
            [waymark10.server.seats.fire :refer [seats-actor]]
            [waymark10.server.seats.health :refer [delivered?]]
            [waymark10.server.seats.inbox :refer [->instant]]
            [waymark10.server.seats.ledger :refer [restatable]]
            [waymark10.server.seats.wakes
             :refer [door-entries fire-door-of? managed-by-seat? wake-entries
                     wake-subscription wake-token]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── the crack a staffing change opens (ticket 5f3c276c) ─────────────
;;
;; A seat is data: a restate that narrows a scope or drops a wake can
;; leave a state with no seat to move it. The cracks audit
;; (waymark10.server.cracks) is read at the door, over the active seats
;; as they stand and as they would stand. A write that adds a crack
;; stops: an authoring seat's restate as a held call for its person,
;; and a person's create on a warning the person acknowledges by its
;; name. A person's own restate and the park are not judged here: the
;; park is the cheap lever, one tap and no confirm. Retire and merge
;; take an active seat out of the staffing for good, and carry both
;; guards (ticket a77bf705).
;;
;; What wakes a seat is its door subscriptions (E-5.4), so each door
;; judges what it changes, once (ticket 841eb517): the seat door with
;; the seat's subscriptions as its write leaves them, and the
;; subscription doors that can drop a wake (pause, revoke, restate)
;; with that one row as it would stand. The seat's own managed-row
;; writes inside its write are not judged again.

(defonce ^:private staffing-rule (atom nil))

(defn staffing-rule!
  "Cracks names what a staffing change opens: `f` takes the kinds, the
  active seats as they stand and the active seats as they would stand,
  and answers the cracks the change adds. Cracks requires this
  namespace, so the rule is handed over here and not required."
  [f]
  (reset! staffing-rule f))

(def ^:private fires
  "The key the cracks audit reads a seat's wakes from (`cracks/with-fires`).
  Cracks requires this namespace, so the key is spelled out here."
  :waymark10.server.cracks/fires)

(defn- live-sub? [sub]
  (not= "revoked" (some-> (:state sub) wake-token)))

(defn- staffed
  "The seats as the cracks audit reads them (`cracks/with-fires`): each
  that holds a door subscription in `subs` short of revoked carries
  ::fires, the entries of its ACTIVE ones, so a seat whose rows are all
  paused carries none and nothing wakes it. A seat with no such row is
  left to what it wrote. `resources` is the kind map."
  [resources seat-rows subs]
  (mapv (fn [s]
          (let [mine (filterv #(and (live-sub? %) (fire-door-of? s %)) subs)]
            (if (seq mine)
              (assoc s fires (into []
                                   (comp (filter #(= "active" (wake-token (:state %))))
                                         (mapcat #(door-entries resources %)))
                                   mine))
              s)))
        seat-rows))

(defn- subs-as-the-seat-leaves-them
  "The subscriptions after a restate of `row` to `after`: its owner's
  rows as they are, and, for a seat that holds managed rows, those as
  `keep-managed-wakes!` and `subscribe-seat!` write them, one active row
  for each of its `wake-entries`. A seat that holds none is read as it
  stood, by what it wrote, before and after alike."
  [row after subs]
  (let [mine? #(and (live-sub? %) (fire-door-of? row %) (managed-by-seat? %))]
    (if (some mine? subs)
      (into (filterv (complement mine?) subs)
            (map (fn [e] {:state :active
                          :data (assoc (wake-subscription after e) :managed_by "seat")}))
            (wake-entries after))
      subs)))

(defn- as-it-would-stand
  "The active seats after this write, and the subscriptions, read as the
  door that makes it: `door` is the action the invoke stands on (ctx
  :door). A body with no row is a seat born, woken by what it wrote.
  Park and retire take the seat out. Merge takes it out and folds its
  scope into `into`, as `merge-seat` does. Any other door is a restate,
  read as `restate-seat` writes it, its managed rows as the write
  leaves them (`subs-as-the-seat-leaves-them`)."
  [active subs row inp door]
  (let [same? #(= (str (:id %1)) (str %2))
        others (into [] (remove #(same? % (:id row))) active)
        mine (:data row)]
    (cond
      (nil? (:id row)) [(conj others {:state :active :data inp}) subs]
      (#{:park :retire} door) [others subs]
      (= :merge door)
      [(mapv (fn [s]
               (if (same? s (:into inp))
                 (update-in s [:data :scope] grants/merge-scope (:scope mine))
                 s))
             others)
       subs]
      :else (let [after (reduce (fn [r f] (assoc-in r [:data f] (get inp f)))
                                row restatable)]
              [(conj others after) (subs-as-the-seat-leaves-them row after subs)]))))

(defn- door-subscriptions
  "The subscriptions short of revoked, read through the write's ctx."
  [find']
  (into [] (mapcat #(find' :subscription {:state %} {:limit 500})) ["active" "paused"]))

(defn- staffing-added
  "The cracks the staffing rule names between the seats and the
  subscriptions as they stand and `[seats subs]` as they would stand,
  or nil."
  [rule rdefs active subs [active' subs']]
  (let [resources (into {} (map (juxt :kind identity)) rdefs)]
    (seq (rule rdefs
               (staffed resources active subs)
               (staffed resources active' subs')))))

(defn cracks-opened
  "The cracks this write would add, or nil. nil too where nothing can
  be read (the pure render probe carries no registry and no store), and
  for a seat that is not active, whose restate changes no staffing."
  [row inp ctx]
  (let [rule @staffing-rule
        find' (:find ctx)
        rdefs (:rdefs ctx)]
    (when (and rule find' rdefs
               (or (nil? (:id row)) (= "active" (name (:state row)))))
      (let [active (vec (find' :seat {:state "active"} {:limit 10000}))
            subs (door-subscriptions find')]
        (staffing-added rule (rdefs) active subs
                        (as-it-would-stand active subs row inp
                                           (some-> (:door ctx) keyword)))))))

(defn cracks-told
  "The new cracks in the audit's own words, one sentence each."
  [cracks]
  (str/join " " (map (fn [c]
                       (str (:kind c) "/" (or (:state c) (:measure c)) " " (:crack c) ": "
                            (:what_would_close_it c)))
                     cracks)))

(defn- walk-kind [seat]
  (let [w (get-in seat [:data :walk])]
    (when (some? w) (not-empty (if (keyword? w) (name w) (str w))))))

(defn- walk-drops
  "What a restate of `row` to `inp` would drop from the seat's walk over
  the last 14 days of the log (R-A.8.3, by step 4's walk replay):
  {:kind :drops [{:id :at :state :moments}] :capped token-or-nil}, or
  nil where nothing can be read (the render probe carries no registry
  and no log) and for a seat that walks no kind. The replay lives above
  this namespace (replay-walks reads live-cracks, which reads this
  one), so it is resolved at the call."
  [row inp ctx]
  (let [history (:replay-history ctx)
        rdefs (:rdefs ctx)
        now (:now ctx)
        kind (walk-kind row)]
    (when (and history rdefs now kind (:id row))
      (let [by-kind (into {} (map (juxt (comp keyword name :kind) identity)) (rdefs))
            rdef (get by-kind (keyword kind))
            after (reduce (fn [r f] (assoc-in r [:data f] (get inp f))) row restatable)
            judgment (fn [s]
                       (when-some [j (some-> (get-in s [:data :judgment]) str not-empty)]
                         (when-some [jr (some-> (:read ctx) (apply [:judgment j]))]
                           [j jr])))]
        (when rdef
          (let [h (history [rdef] (->instant now))
                r ((requiring-resolve 'waymark10.server.replay-walks/replay)
                   {:rdef rdef :by-kind by-kind :history h :seats []
                    :before row :after after
                    :reads {:judgments (into {} (keep judgment) [row after])}})]
            {:kind kind
             :drops (:drops r)
             :capped (some #(when (= "capped" (:token %)) %) (:blind r))}))))))

(defn- drops-told [drops]
  (str/join ", " (map #(str (:id %) " (" (:state %) " at " (:at %) ")") drops)))

(g/defguard a-restate-says-what-it-drops
  {:severity :warning
   :reads [:principal :now :grant :seat :held_call :judgment :storage :transitions]
   :vars [:count :kind :rows :capped]
   :open "The rows are the walk replay's (docs/spec-amendment.md R-A.8.3), read at the write from the last 14 days of the log. Acknowledge the warning by its name to drop them."
   :explain "This restate would drop {count} {kind} rows the seat's walk handed it in the last 14 days: {rows}.{capped} Keep the walk's filter so they stay in the walk, or acknowledge this warning to drop them on purpose."}
  [row inp ctx]
  ;; the restate door's, beside opens-no-crack: an authoring seat's
  ;; restate is held, and the replay of that held call is the person's yes
  (if (or (delegation/author-seat ctx)
          (delegation/approved-hold? ctx :seat (:id row)))
    (t/allow)
    (let [{:keys [kind drops capped]} (walk-drops row inp ctx)]
      (if (seq drops)
        (t/deny {:vars {:count (count drops) :kind kind :rows (drops-told drops)
                        ;; a cut read still warns, and says where it stopped
                        :capped (if capped
                                  (str " The read was capped at " (:at capped)
                                       "; older moments were not read.")
                                  "")}})
        (t/allow)))))

;; ── every restate carries its replay (ticket 6a100156) ──────────────
;;
;; R-A.8.3: no seat restate lands without a replay. A held restate's
;; call carries one line of findings, so the owner taps with the
;; evidence in view. Every finding here is advisory: only the walk
;; drops above warn, and nothing here refuses.

(def ^:private replay-days
  "The window a restate's replay reads, as an amendment's does (R-A.3.2)."
  14)

(def ^:private replay-sittings-page
  "The most of the seat's sittings a restate's replay reads, newest first."
  200)

(defn- replay-token [v] (if (keyword? v) (name v) (some-> v str not-empty)))

(defn- scope-admits?
  "Does `scope` name `action` on `kind`, or `*` there?"
  [scope kind action]
  (boolean (some (fn [e] (and (= kind (replay-token (:kind e)))
                              (some #{"*" action} (map replay-token (:actions e)))))
                 scope)))

(defn- restate-sittings
  "The seat's sittings that started in the window ending `now`, each as
  {:outcome :moves}: the moves are the log rows made under its grant
  from its start to its end (to now, while it is open). `::unread` when
  the log cannot be read by grant (a store with no row queries)."
  [row ctx now]
  (let [find-rows (:find ctx)
        under (:transitions-under ctx)
        since (.minus ^java.time.Instant now (java.time.Duration/ofDays replay-days))]
    (if-not (and find-rows under (:id row))
      ::unread
      (try
        (into []
              (keep (fn [s]
                      (let [d (:data s)
                            start (->instant (:started_at d))
                            grant (some-> (:grant d) str not-empty)]
                        (when (and start grant (.isAfter ^java.time.Instant start since))
                          {:outcome (replay-token (:outcome d))
                           :moves (under grant start (->instant (:ended_at d)))}))))
              (find-rows :sitting {:seat (str (:id row))}
                         {:limit replay-sittings-page :newest-first true}))
        (catch Exception _ ::unread)))))

(defn would-submit
  "Of `sittings` that closed `cut_short`, how many `delivers` would have
  closed as `submitted` (sitting-health judges a delivery first): [would of]."
  [sittings delivers]
  (let [cut (filter #(= "cut_short" (:outcome %)) sittings)]
    [(count (filter #(delivered? (:moves %) delivers) cut)) (count cut)]))

(defn would-refuse
  "How many moves made under `sittings` the scope `before` admitted and
  `after` would not."
  [sittings before after]
  (count (for [s sittings
               m (:moves s)
               :let [k (replay-token (:kind m)) a (replay-token (:action m))]
               :when (and (scope-admits? before k a) (not (scope-admits? after k a)))]
           m)))

(defn- walk-replay
  "Step 4's walk and holder replay of each kind either side walks,
  beside the other active seats: {:added :dropped :to_person :to_nobody
  :capped}, or nil when neither side walks a kind or nothing can be read."
  [row after ctx now]
  (let [history (:replay-history ctx)
        rdefs (:rdefs ctx)
        find-rows (:find ctx)
        kinds (distinct (keep walk-kind [row after]))]
    (when (and history rdefs (seq kinds))
      (let [by-kind (into {} (map (juxt (comp keyword name :kind) identity)) (rdefs))
            others (if find-rows
                     (into [] (remove #(= (str (:id %)) (str (:id row))))
                           (find-rows :seat {:state "active"} {:limit 500}))
                     [])
            judgment (fn [s]
                       (when-some [j (some-> (get-in s [:data :judgment]) str not-empty)]
                         (when-some [jr (some-> (:read ctx) (apply [:judgment j]))]
                           [j jr])))
            runs (vec (for [k kinds
                            :let [rdef (get by-kind (keyword k))]
                            :when rdef]
                        ((requiring-resolve 'waymark10.server.replay-walks/replay)
                         {:rdef rdef :by-kind by-kind :history (history [rdef] now)
                          :seats others :before row :after after
                          :reads {:judgments (into {} (keep judgment) [row after])}})))
            total (fn [k] (reduce + 0 (map (comp count k) runs)))]
        (when (seq runs)
          {:added (total :adds)
           :dropped (total :drops)
           :to_person (total :to_person)
           :to_nobody (total :to_nobody)
           :capped (some (fn [r] (some #(when (= "capped" (:token %)) %) (:blind r))) runs)})))))

(defn restate-replay-line
  "One line of what a restate of `row` to `inp` would have changed over
  the last 14 days, whatever fields it changes (ticket 6a100156): the
  walk's rows added and dropped, rows moving to a person or to nobody,
  the logged moves a new scope would refuse, the cut-short sittings a
  new `delivers` would close as submitted, and what replay cannot see.
  nil for a create, and where the clock cannot be read. A read that
  fails says nothing rather than refusing: this is advice."
  [row inp ctx]
  (when-some [now (some-> (:now ctx) ->instant)]
    (when (:id row)
      (try
        (let [after (reduce (fn [r f] (assoc-in r [:data f] (get inp f))) row restatable)
              moved? (fn [f] (not= (get-in row [:data f]) (get inp f)))
              walk (walk-replay row after ctx now)
              read (when (or (moved? :scope) (moved? :delivers))
                     (restate-sittings row ctx now))
              unread? (= ::unread read)
              sittings (when-not unread? read)
              [won of] (would-submit sittings (:delivers inp))
              blind (cond-> []
                      (some moved? [:charter :instructions]) (conj "model_judgment")
                      (:capped walk) (conj "capped")
                      unread? (conj "sittings"))
              told (cond-> []
                     walk
                     (conj (str "walk +" (:added walk) " -" (:dropped walk)))
                     (pos? (+ (:to_person walk 0) (:to_nobody walk 0)))
                     (conj (str "to person " (:to_person walk) ", to nobody " (:to_nobody walk)))
                     (moved? :scope)
                     (conj (str "would refuse "
                                (if unread?
                                  "?"
                                  (would-refuse sittings (get-in row [:data :scope]) (:scope inp)))))
                     (moved? :delivers)
                     (conj (str "cut_short→submitted " (if unread? "?" (str won "/" of))))
                     (seq blind)
                     (conj (str "blind " (str/join "," blind))))]
          (str "replay " replay-days "d: "
               (if (seq told) (str/join "; " told) "nothing moves")))
        (catch Exception _ nil)))))

(delegation/restate-replayed! #'restate-replay-line)

(g/defguard a-crack-waits-on-the-person
  {:hold true
   :reads [:principal :now :grant :seat :held_call :within :services]
   :vars [:count :cracks]
   :open "No door clears this one. The call waits as a held_call for the person's tap, and an author that could open a crack alone would leave work with no seat and nobody told."
   :explain "Held for the person's tap. This change to the seats would open cracks ({count}), where work stops with nobody told. {cracks} The call is recorded as a held_call, and the person's Allow runs it exactly as written."}
  [row inp ctx]
  ;; the restate door's, from an authoring seat, and retire's and
  ;; merge's, where `the-persons-lever` holds an author first and this
  ;; names the crack its replay would open. A seat an author creates is
  ;; born parked and changes no staffing, so the create door does not
  ;; carry this guard, and only a person parks.
  (cond
    (nil? (delegation/author-seat ctx)) (t/allow)
    (delegation/approved-hold? ctx :seat (:id row)) (t/allow)
    :else
    (if-some [cracks (cracks-opened row inp ctx)]
      (t/deny {:vars {:count (count cracks) :cracks (cracks-told cracks)}})
      (t/allow))))

;; the same pair at the subscription doors that can drop a wake

(defn- sub-as-it-would-stand
  "The subscription after this write at its own door: pause and revoke
  take it out of the hearing, and a restate states its predicate and
  receiver again, an omitted field keeping its value."
  [row inp door]
  (case door
    :pause (assoc row :state :paused)
    :revoke (assoc row :state :revoked)
    (update row :data merge (select-keys inp [:kinds :actions :filter :receiver]))))

(defn the-seat-writes-it?
  "Is this the seats actor writing inside a seat's write
  (`keep-managed-wakes!`)? The seat door judged the whole write. The
  seats actor outside a seat write is judged as anyone is."
  [ctx]
  (and (= (str (:id seats-actor)) (some-> (get-in ctx [:principal :id]) str))
       (= :seat (some-> (get-in ctx [:within :kind]) keyword))))

(defn wake-drop-opened
  "The cracks this write to an active door subscription would add, or
  nil: the seats as they stand, their subscriptions with this one as it
  would stand."
  [row inp ctx]
  (let [rule @staffing-rule
        find' (:find ctx)
        rdefs (:rdefs ctx)]
    (when (and rule find' rdefs
               (= "active" (some-> (:state row) wake-token))
               (= "door" (some-> (get-in row [:data :receiver :type]) wake-token)))
      (let [active (vec (find' :seat {:state "active"} {:limit 10000}))
            others (filterv #(not= (str (:id row)) (str (:id %)))
                            (door-subscriptions find'))]
        (staffing-added rule (rdefs) active (conj others row)
                        [active (conj others (sub-as-it-would-stand
                                              row inp (some-> (:door ctx) keyword)))])))))
