(ns waymark10.server.cracks
  "GET /api/-/cracks: will anything fall through? (ticket 8b52a6e6,
  part 1 of epic 400609cf.)

  A static read over the declarations and the ACTIVE seat rows. For
  every kind, and every state a row can reach from its initial state,
  it names the ways work stops with nobody told:

  no_exit        no active seat's scope holds an action that leaves
                 the state (terminal states excepted)
  unwoken        seats can leave it, and nothing brings one of them
                 there: no wake_on entry on a transition into the
                 state, no walk whose filter admits it, no cadence
  remedy_unheld  a guard's remedy names a door no active seat holds
  silent_person_exit
                 every exit from the state is held for a person's tap
                 (a confirm door, or a guard declared `:hold true`,
                 which is how a held call and a delegation ceiling
                 are both spoken), and nothing tells the person a row
                 arrived: no notice rule, no subscription, no cue in
                 the inbox of a seat a person sits in (ticket
                 53e55720, part 4)
  unwalked_value a value live rows carry in a field every walk of the
                 kind filters on, and no active seat's walk filter
                 admits (ticket 53e55720, part 4)

  The first four read what is DECLARED, never the rows of the audited
  kinds: a crack there is a state a row could sit in, not a row that
  does. `unwalked_value` alone reads rows, and only the ones `report`
  hands it. `audit` is pure over (declarations, seat rows, world) so a
  test, and the gate a later ticket adds, judge it with no engine at
  all.

  A filter is read for ONE thing, its `state` entry: an entry that
  names states admits those and no other, and an entry that names
  none admits every state. What a filter says about any other field
  narrows rows, not states, and this audit does not judge rows."
  (:require [clojure.string :as str]
            [waymark10.checks :as checks]
            [waymark10.guards :as g]
            [waymark10.holds :as holds]
            [waymark10.machine :as machine]
            [waymark10.server.held-calls :as held-calls]
            [waymark10.server.invoke :as inv]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(def ^:private seat-page
  "The most active seats one audit reads."
  500)

(def ^:private row-page
  "The most rows of one state of one walked kind an audit reads, the
  newest first."
  2000)

(defn- text [v] (if (keyword? v) (name v) (str v)))

(defn- fval
  "A filter's value for a field, whichever way the row spells the key."
  [fm k]
  (when (map? fm)
    (let [k (keyword (text k))]
      (if (contains? fm k) (get fm k) (get fm (name k))))))

(defn- admits-state?
  "Does this filter let a row in `state` through? Its `state` entry is
  one name, a comma-separated any-of, or a list; no entry admits all."
  [fm state]
  (let [v (fval fm :state)]
    (or (nil? v)
        (contains? (into #{}
                         (map (comp str/trim text))
                         (if (coll? v) v (str/split (str v) #",")))
                   (text state)))))

(defn- fvals
  "The values a filter entry names: one name, a comma-separated any-of,
  or a list."
  [v]
  (into #{}
        (map (comp str/trim text))
        (if (coll? v) v (str/split (str v) #","))))

(defn- seat-name [seat]
  (or (some-> (get-in seat [:data :name]) str not-empty) (str (:id seat))))

(defn- entries-for [seat kind]
  (filterv #(= (text kind) (text (:kind %))) (get-in seat [:data :scope])))

(defn- holding-entries
  "The seat's scope entries that hold `action` on `kind` for a row in
  `state`; a nil state asks about the door alone."
  [seat kind action state]
  (filterv (fn [e]
             (and (some #{(text action)} (map text (:actions e)))
                  (or (nil? state) (admits-state? (:filter e) state))))
           (entries-for seat kind)))

(defn- exits
  "The actions that move a row OUT of `state`: an action that lands
  where it left is a door, not a way out."
  [rdef state]
  (filterv #(and (some? (:to %)) (not= state (:to %)))
           (machine/transitions-from rdef state)))

(defn- arrivals
  "The action names that put a row INTO `state`. `create` is a door
  every kind serves and no kind lists in `:actions`."
  [rdef state]
  (cond-> (into #{}
                (comp (filter #(= state (:to %))) (map (comp text :name)))
                (machine/actions-seq rdef))
    (= state (:initial rdef)) (conj "create")))

(defn- no-narrower?
  "Is the wake filter no narrower than one of the filters the seat
  acts under? Every field it names, that scope filter names the same."
  [wake-filter scope-filters]
  (or (empty? wake-filter)
      (boolean
       (some (fn [sf]
               (every? (fn [[k v]] (= (text v) (some-> (fval sf k) text)))
                       wake-filter))
             scope-filters))))

(defn- wakes-of
  "What brings this seat to a row in `state` of the kind, as the words
  the answer prints: interactive (a person sits in it), cadence,
  wake_on, walk. Empty is a seat nothing wakes for this state."
  [seat rdef state scope-filters walk-rdef]
  (let [kind (text (:kind rdef))
        into-state (arrivals rdef state)
        walk (some-> (get-in seat [:data :walk]) str not-empty)]
    (cond-> []
      (seats/interactive-seat? seat) (conj "interactive")
      (some? (get-in seat [:data :cadence_seconds])) (conj "cadence")
      (some (fn [e]
              (and (= kind (text (:kind e)))
                   (some into-state (map text (:actions e)))
                   (admits-state? (:filter e) state)
                   (no-narrower? (:filter e) scope-filters)))
            (seats/effective-wake-on seat walk-rdef))
      (conj "wake_on")
      (and (= kind walk) (admits-state? (seats/walk-filter seat) state))
      (conj "walk"))))

(defn- state-cracks
  "no_exit and unwoken, for one kind."
  [rdef seat-rows by-kind]
  (let [kind (text (:kind rdef))
        terminal (set (:terminal rdef))
        walk-rdef (fn [seat]
                    (some->> (get-in seat [:data :walk]) str not-empty
                             keyword (get by-kind)))]
    (for [state (sort-by text (machine/reachable-states rdef))
          :when (not (contains? terminal state))
          :let [out (exits rdef state)
                leavers (for [seat seat-rows
                              :let [held (for [a out
                                               :let [es (holding-entries seat kind (:name a) state)]
                                               :when (seq es)]
                                           {:action (text (:name a)) :entries es})]
                              :when (seq held)]
                          {:seat seat
                           :holds (mapv :action held)
                           :filters (into [] (comp (mapcat :entries) (map :filter)) held)})
                considered (for [l leavers]
                             {:seat (seat-name (:seat l))
                              :holds (:holds l)
                              :wakes (wakes-of (:seat l) rdef state (:filters l)
                                               (walk-rdef (:seat l)))})]
          crack (cond
                  (empty? leavers)
                  [{:kind kind :state (text state) :crack "no_exit"
                    :seats_considered (into []
                                            (comp (filter #(seq (entries-for % kind)))
                                                  (map (fn [s] {:seat (seat-name s) :holds []})))
                                            seat-rows)
                    :what_would_close_it
                    (if (seq out)
                      (str "Give an active seat a scope entry on " kind
                           " that holds one of: "
                           (str/join ", " (map (comp text :name) out))
                           ", under a filter that admits the state " (text state) ".")
                      (str "No action of " kind " leaves " (text state)
                           ": declare one, or declare the state terminal."))}]

                  (every? (comp empty? :wakes) considered)
                  [{:kind kind :state (text state) :crack "unwoken"
                    :seats_considered (vec considered)
                    :what_would_close_it
                    (str "Give one of these seats a wake_on entry on " kind
                         " naming one of: "
                         (str/join ", " (sort (arrivals rdef state)))
                         " (with a filter no narrower than its scope filter)"
                         ", a walk over " kind " whose filter admits "
                         (text state) ", or a cadence.")}])]
      crack)))

(defn- remedy-cracks
  "remedy_unheld, for one kind: one crack per state the refusing door
  opens from (none for the create door, which refuses before a row
  exists) and per remedy no active seat holds."
  [rdef seat-rows]
  (let [kind (text (:kind rdef))
        reachable (machine/reachable-states rdef)
        froms (fn [door]
                (if (= :create door)
                  [nil]
                  (sort-by text (filter reachable (get-in rdef [:actions door :from])))))]
    (distinct
     (for [{:keys [door guard]} (checks/guard-sites rdef)
           tok (g/remedy-doors guard)
           :when (qualified-keyword? tok)
           :let [tk (namespace tok)
                 ta (name tok)]
           :when (not-any? #(seq (holding-entries % tk ta nil)) seat-rows)
           state (froms door)]
       {:kind kind :state (some-> state text) :crack "remedy_unheld"
        :door (text door) :remedy (str tk "/" ta)
        :seats_considered (into []
                                (comp (filter #(seq (entries-for % tk)))
                                      (map (fn [s] {:seat (seat-name s) :holds []})))
                                seat-rows)
        :what_would_close_it
        (str "Give an active seat a scope entry on " tk " that holds " ta
             ": a refusal at " kind "/" (text door)
             " names it as the way out, and no seat can take it.")}))))

(defn- held-by
  "Why only a person can take this exit, as the words the answer
  prints: confirm (the door wants its consequence sentence), hold (a
  guard of it is declared `:hold true`). Empty is a door a seat takes
  alone."
  [rdef action]
  (cond-> []
    (get-in action [:safety :confirm]) (conj "confirm")
    (some (fn [{:keys [door guard]}]
            (and (= (:name action) door)
                 (or (true? (:hold guard)) (holds/hold? (:name guard)))))
          (checks/guard-sites rdef))
    (conj "hold")))

(defn- tellers
  "What tells a person that a row of the kind arrived in `state`, as
  the words the answer prints: notice_rule (a rule on a transition
  into the state; a rule with `at` hears no transition), subscription
  (one that names the kind, or names none), inbox_cue (a cue on an
  arriving action, in the inbox of a seat a person sits in). A filter
  on any of them narrows rows, and this does not judge rows."
  [kind state into-state seat-rows {:keys [notice-rules subscriptions]}]
  (let [blank-or (fn [want ok?] (or (str/blank? (str want)) (boolean (ok? (text want)))))]
    (cond-> []
      (some (fn [r]
              (let [d (:data r)
                    w (fval d :when)]
                (and (= kind (some-> (fval d :kind) text))
                     (str/blank? (str (fval (fval d :at) :field)))
                     (blank-or (fval w :action) into-state)
                     (blank-or (fval w :to_state) #{(text state)}))))
            notice-rules)
      (conj "notice_rule")
      (some (fn [s]
              (let [kinds (fval (:data s) :kinds)]
                (or (empty? kinds) (some #{kind} (map text kinds)))))
            subscriptions)
      (conj "subscription")
      (some (fn [seat]
              (and (seats/interactive-seat? seat)
                   (some (fn [c]
                           (and (= kind (some-> (fval c :kind) text))
                                (or (empty? (fval c :actions))
                                    (some into-state (map text (fval c :actions))))))
                         (:cues (seats/inbox-of seat)))))
            seat-rows)
      (conj "inbox_cue"))))

(defn- person-cracks
  "silent_person_exit, for one kind: a state every exit from which
  waits on a person, with nothing that tells the person."
  [rdef seat-rows world]
  (let [kind (text (:kind rdef))
        terminal (set (:terminal rdef))]
    (for [state (sort-by text (machine/reachable-states rdef))
          :when (not (contains? terminal state))
          :let [out (mapv (fn [a] {:action (text (:name a))
                                   :held_by (held-by rdef a)})
                          (exits rdef state))]
          :when (and (seq out) (every? (comp seq :held_by) out))
          :when (empty? (tellers kind state (arrivals rdef state) seat-rows world))]
      {:kind kind :state (text state) :crack "silent_person_exit"
       :exits out
       :what_would_close_it
       (str "Tell the person when a row of " kind " arrives in " (text state)
            ": a notice rule on " kind " whose `when` names to_state " (text state)
            " (or one of: " (str/join ", " (sort (arrivals rdef state)))
            "), a subscription that names " kind
            ", or a cue on one of those actions in the inbox of a seat a person"
            " sits in. Every way out (" (str/join ", " (map :action out))
            ") waits for a person's tap.")})))

(defn- value-cracks
  "unwalked_value, for one kind: `rows` is the rows of it that were
  read. A field counts when EVERY active seat that walks the kind
  filters on it, since a walk with no entry for the field admits each
  of its values. `state` is left to the cracks above."
  [rdef seat-rows rows]
  (let [kind (text (:kind rdef))
        terminal (into #{} (map text) (:terminal rdef))
        walkers (filterv #(= kind (some-> (get-in % [:data :walk]) str)) seat-rows)
        filters (mapv seats/walk-filter walkers)
        fields (into (sorted-set)
                     (comp (mapcat keys) (map text) (remove #{"state"}))
                     filters)
        live (remove #(contains? terminal (text (:state %))) rows)]
    (for [field fields
          :when (every? #(some? (fval % field)) filters)
          :let [admitted (into #{} (mapcat #(fvals (fval % field))) filters)
                carried (for [row live
                              :let [v (fval (:data row) field)]
                              :when (some? v)
                              one (if (coll? v) v [v])
                              :let [s (text one)]
                              :when (and (seq s) (not (contains? admitted s)))]
                          {:value s :state (text (:state row))})]
          [value hits] (sort-by key (group-by :value carried))]
      {:kind kind :state nil :crack "unwalked_value"
       :field field :value value
       :rows (count hits)
       :states (vec (sort (distinct (map :state hits))))
       :seats_considered (mapv (fn [s f]
                                 {:seat (seat-name s)
                                  :admits (vec (sort (fvals (fval f field))))})
                               walkers filters)
       :what_would_close_it
       (str "Give an active seat a walk over " kind " whose filter admits "
            field " " value ", or restate these rows under a " field
            " a seat walks: " (str/join ", " (sort admitted)) ".")})))

(defn audit
  "Every crack the declarations and the active seats leave, in a
  stable order. `rdefs` is every kind the engine serves; `seat-rows`
  is the ACTIVE seats, as stored. `world` is the rest that was read,
  each part optional: `:notice-rules` and `:subscriptions` are the
  ACTIVE rows of those kinds (and the rules the engine declares, in a
  row's shape), `:rows` is {kind [row …]} for the kinds whose values
  are judged."
  ([rdefs seat-rows] (audit rdefs seat-rows nil))
  ([rdefs seat-rows world]
   (let [by-kind (into {} (map (juxt :kind identity)) rdefs)
         seat-rows (vec seat-rows)]
     (into []
           (mapcat (fn [rdef]
                     (concat (state-cracks rdef seat-rows by-kind)
                             (person-cracks rdef seat-rows world)
                             (remedy-cracks rdef seat-rows)
                             (value-cracks rdef seat-rows
                                           (get (:rows world) (:kind rdef))))))
           (sort-by (comp text :kind) rdefs)))))

(defn- active-of [eng kind]
  (if (contains? (inv/resources eng) kind)
    (store/with-tx (:storage eng)
      (fn [tx]
        (store/query-rows (:storage eng) tx kind {:state :active}
                          {:limit seat-page})))
    []))

(defn- told
  "The notice rules that can tell: the active rows, and the rules the
  engine declares itself, in a row's shape."
  [eng]
  (into (vec (active-of eng :notice_rule))
        (for [[k rules] held-calls/engine-rules
              r rules]
          {:data (assoc r :kind (name k))})))

(defn- live-rows
  "{kind [row …]} for every kind an active seat walks under a filter:
  its rows in each state that is not terminal, the newest `row-page`
  of each state."
  [eng seat-rows]
  (let [st (:storage eng)
        resources (inv/resources eng)]
    (into {}
          (for [k (distinct (keep #(when (seats/walk-filter %)
                                     (some-> (get-in % [:data :walk]) str not-empty keyword))
                                  seat-rows))
                :let [rdef (get resources k)]
                :when rdef]
            [k (store/with-tx st
                 (fn [tx]
                   (into []
                         (mapcat #(store/query-rows st tx k {:state %}
                                                    {:limit row-page :newest-first true}))
                         (remove (set (:terminal rdef))
                                 (sort-by text (machine/reachable-states rdef))))))]))))

(defn report
  "The cracks document. `visible?` says which kinds the caller may
  read about; `kind` narrows the answer to one."
  [eng visible? {:keys [kind]}]
  (let [seat-rows (active-of eng :seat)
        cracks (into []
                     (filter (fn [c]
                               (and (visible? (keyword (:kind c)))
                                    (or (nil? kind) (= kind (:kind c))))))
                     (audit (vals (inv/resources eng)) seat-rows
                            {:notice-rules (told eng)
                             :subscriptions (active-of eng :subscription)
                             :rows (live-rows eng seat-rows)}))]
    {:waymark "cracks"
     :seats (count seat-rows)
     :total (count cracks)
     :by_crack (into (sorted-map) (frequencies (map :crack cracks)))
     :cracks cracks}))
