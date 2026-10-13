(ns waymark10.server.cracks
  "GET /api/-/cracks: will anything fall through? (ticket 8b52a6e6,
  part 1 of epic 400609cf.)

  A static read over the declarations and the ACTIVE seat rows. For
  every kind, and every state a row can reach from its initial state,
  it names the ways work stops with nobody told:

  no_exit        no active seat's scope holds an action that leaves
                 the state (terminal states excepted)
  cadence_only   seats can leave it, and none of them has a wake_on
                 entry on a transition into the state: a row waits
                 for the shortest cadence among them
  person_only    no wake_on and no cadence: the only seats that can
                 leave it are interactive, so a row waits for a
                 person to sit
  unwoken        seats can leave it, and nothing brings one of them
                 there: no wake_on entry, no cadence, no person
  remedy_unheld  a guard's remedy names a door no active seat holds

  Only a wake_on entry WAKES (ticket cd402fb6, part 3). A walk says
  which rows a seat is handed, not what brings it, so a walk alone
  counts for nothing here; the wake_on a walk seat is given when it
  wrote none (`seats/effective-wake-on`) is a wake_on and counts.

  It reads what is DECLARED, never the rows of the audited kinds: a
  crack here is a state a row could sit in, not a row that does.
  `audit` is pure over (declarations, seat rows) so a test, and the
  gate a later ticket adds, judge it with no engine at all.

  A filter is read for ONE thing, its `state` entry: an entry that
  names states admits those and no other, and an entry that names
  none admits every state. What a filter says about any other field
  narrows rows, not states, and this audit does not judge rows."
  (:require [clojure.string :as str]
            [waymark10.checks :as checks]
            [waymark10.guards :as g]
            [waymark10.machine :as machine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(def ^:private seat-page
  "The most active seats one audit reads."
  500)

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
  wake_on. Empty is a seat nothing brings to this state. Of the three
  only wake_on is a wake; `state-cracks` grades the other two."
  [seat rdef state scope-filters walk-rdef]
  (let [kind (text (:kind rdef))
        into-state (arrivals rdef state)]
    (cond-> []
      (seats/interactive-seat? seat) (conj "interactive")
      (some? (get-in seat [:data :cadence_seconds])) (conj "cadence")
      (some (fn [e]
              (and (= kind (text (:kind e)))
                   (some into-state (map text (:actions e)))
                   (admits-state? (:filter e) state)
                   (no-narrower? (:filter e) scope-filters)))
            (seats/effective-wake-on seat walk-rdef))
      (conj "wake_on"))))

(defn- state-cracks
  "no_exit, cadence_only, person_only and unwoken, for one kind. A
  state one of its leavers has a wake_on entry for is clean; what is
  left is graded by the best thing that still brings a seat there."
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
                                               (walk-rdef (:seat l)))})
                wakes (into #{} (mapcat :wakes) considered)
                cadences (for [l leavers
                               :let [c (get-in (:seat l) [:data :cadence_seconds])]
                               :when (number? c)]
                           c)
                shortest (when (seq cadences) (apply min cadences))
                wake-it (str "Give one of these seats a wake_on entry on " kind
                             " naming one of: "
                             (str/join ", " (sort (arrivals rdef state)))
                             " (with a filter no narrower than its scope filter).")]
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

                  (contains? wakes "wake_on")
                  nil

                  (contains? wakes "cadence")
                  [(cond-> {:kind kind :state (text state) :crack "cadence_only"
                            :seats_considered (vec considered)
                            :what_would_close_it
                            (str "A row here waits for a cadence"
                                 (when shortest
                                   (str ", the shortest every " shortest " seconds"))
                                 ". " wake-it)}
                     shortest (assoc :shortest_cadence_seconds shortest))]

                  (contains? wakes "interactive")
                  [{:kind kind :state (text state) :crack "person_only"
                    :seats_considered (vec considered)
                    :what_would_close_it
                    (str "A row here waits for a person to sit. Give a seat that"
                         " is not interactive a scope entry on " kind
                         " that holds one of: "
                         (str/join ", " (map (comp text :name) out))
                         ", and a wake_on entry naming one of: "
                         (str/join ", " (sort (arrivals rdef state))) ".")}]

                  :else
                  [{:kind kind :state (text state) :crack "unwoken"
                    :seats_considered (vec considered)
                    :what_would_close_it wake-it}])]
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

(defn audit
  "Every crack the declarations and the active seats leave, in a
  stable order. `rdefs` is every kind the engine serves; `seat-rows`
  is the ACTIVE seats, as stored."
  [rdefs seat-rows]
  (let [by-kind (into {} (map (juxt :kind identity)) rdefs)
        seat-rows (vec seat-rows)]
    (into []
          (mapcat (fn [rdef]
                    (concat (state-cracks rdef seat-rows by-kind)
                            (remedy-cracks rdef seat-rows))))
          (sort-by (comp text :kind) rdefs))))

(defn- active-seats [eng]
  (if (contains? (inv/resources eng) :seat)
    (store/with-tx (:storage eng)
      (fn [tx]
        (store/query-rows (:storage eng) tx :seat {:state :active}
                          {:limit seat-page})))
    []))

(defn report
  "The cracks document. `visible?` says which kinds the caller may
  read about; `kind` narrows the answer to one."
  [eng visible? {:keys [kind]}]
  (let [seat-rows (active-seats eng)
        cracks (into []
                     (filter (fn [c]
                               (and (visible? (keyword (:kind c)))
                                    (or (nil? kind) (= kind (:kind c))))))
                     (audit (vals (inv/resources eng)) seat-rows))]
    {:waymark "cracks"
     :seats (count seat-rows)
     :total (count cracks)
     :by_crack (into (sorted-map) (frequencies (map :crack cracks)))
     :cracks cracks}))
