(ns waymark10.server.cracks
  "GET /api/-/cracks: will anything fall through? (ticket 8b52a6e6,
  part 1 of epic 400609cf; `dead_wait` is part 2, ticket d9edac4e.)

  A static read over the declarations and the ACTIVE seat rows. For
  every kind, and every state a row can reach from its initial state,
  it names the ways work stops with nobody told:

  no_exit        no active seat's scope holds an action that leaves
                 the state (terminal states excepted). A kind's
                 `:left-by` names its indirect exits (ticket 071c563c):
                 a seat that holds the other kind's door named there
                 leaves the state as one that holds a door here does,
                 and a state a source leaves (the mirror, the sweep, a
                 wait's ending, a person's tap, the engine's own
                 actor) needs no seat and is no crack. A state a
                 person leaves is judged by silent_person_exit
  cadence_only   seats can leave it, and none of them has a wake_on
                 entry on a transition into the state: a row waits
                 for the shortest cadence among them
  person_only    no wake_on and no cadence: the only seats that can
                 leave it are interactive, so a row waits for a
                 person to sit
  unwoken        seats can leave it, and nothing brings one of them
                 there: no wake_on entry, no cadence, no person
  remedy_unheld  a guard's remedy names a door no active seat holds
  dead_wait      the kind declares a wait (`:waits`) one of whose
                 outcomes does nothing to the row that waits, and no
                 seat is woken to act on it: none that holds a door on
                 the waiting row has a wake_on entry naming the action
                 that ends the awaited row that way, or a cadence
  silent_person_exit
                 every exit from the state is held for a person's tap
                 (a confirm door, or a guard declared `:hold true`;
                 one declared `:hold :agents` holds only an agent and
                 leaves the door its ordinary holders), which is how a held call and a delegation ceiling
                 are both spoken), and nothing tells the person a row
                 arrived: no notice rule, no subscription, no cue in
                 the inbox of a seat a person sits in (ticket
                 53e55720, part 4)
  unwalked_value a value live rows carry in a field every walk of the
                 kind that hands out their state filters on, and no
                 active seat's walk filter admits (ticket 53e55720,
                 part 4). Each state is judged by its own walks
                 (ticket 5cb126ab): a walk shut to the state says
                 nothing of the rows there. A row a seat is woken for
                 and can move is no crack. A seat a person sits in
                 is woken by what it wrote in wake_on and by nothing
                 else, and its walk hands out no row (ticket
                 8186d41a); where the kind declares another kind's
                 door out of the state, that door is the one that
                 moves the work
  deadline_untold
                 the kind declares an `:expected-age {:until …}` on a
                 state that is no_exit or unwoken here, and nothing
                 tells a person a row arrived there, so the aged
                 ticket its deadline files reaches nobody
                 (docs/spec-calendar-time.md §7)
  measure-tell-unseated
                 a measure the kind declares tells a seat (`:tell`)
                 that is not an active seat, so a miss would tell
                 nobody (docs/spec-measures.md §8). A measure with no
                 :tell is checks' [measure-untold]

  Only a wake_on entry WAKES (ticket cd402fb6, part 3). A walk says
  which rows a seat is handed, not what brings it, so a walk alone
  counts for nothing here; the wake_on a walk seat is given when it
  wrote none (`seats/effective-wake-on`) is a wake_on and counts.

  A kind's `:reached-by` names its indirect arrivals (ticket 4b0460cc):
  another kind's door that brings a row to the state, as the groom of
  a ticket brings its seat to the change born from it. A wake_on entry
  on that door wakes the seat for the state as one on a door here does.

  All but the last read what is DECLARED, never the rows of the
  audited kinds: a crack there is a state a row could sit in, not a
  row that does. `unwalked_value` alone reads rows, and only the ones
  `report` hands it. `audit` is pure over (declarations, seat rows,
  world) so a test, and the gate in factory10's cracks_gate_test,
  judge it with no engine at all.

  A filter is read for ONE thing, its `state` entry: an entry that
  names states admits those and no other, and an entry that names
  none admits every state. What a filter says about any other field
  narrows rows, not states, and this audit does not judge rows.

  ONE EXCEPTION, where rows were read (ticket d71d2805). A wake_on
  entry narrower than the scope the seat acts under wakes it for the
  rows its filter admits and no other, so alone it covers no state.
  Seats that each wake on one repository cover a state TOGETHER: with
  the kind's rows in hand, the state is woken when every live row
  meets a narrowed wake of a seat that can leave it. A row none meets
  keeps the crack, which names what that row carries in
  `unwoken_values`. With no rows read a narrowed wake counts for
  nothing, as before.

  A STATE A ROW RESTS IN is no crack (same ticket): a terminal state,
  and an ending the kind declares in `:over` whose every exit is a way
  back (`machine/ways-back`), which is a correction and nobody's work."
  (:require [clojure.string :as str]
            [waymark10.checks :as checks]
            [waymark10.expr :as expr]
            [waymark10.guards :as g]
            [waymark10.holds :as holds]
            [waymark10.machine :as machine]
            [waymark10.server.at-rest :as at-rest]
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

(defn- resting
  "The states a row RESTS in: the terminal ones, and an ending the kind
  declares (`:over`, spelled in states) whose every exit is a way back
  (`machine/ways-back`: reopen, a person's correction or the forge's).
  No seat is owed a wake to such a state and no person a telling."
  [rdef]
  (let [{:keys [field accomplished let-go]} (machine/over-vocabulary rdef)
        back (machine/ways-back rdef)]
    (into (set (:terminal rdef))
          (when-not field
            (filter (fn [state]
                      (every? #(contains? back (:name %)) (exits rdef state)))
                    (into accomplished let-go))))))

(defn- arrivals
  "The action names that put a row INTO `state`. `create` is a door
  every kind serves and no kind lists in `:actions`."
  [rdef state]
  (cond-> (into #{}
                (comp (filter #(= state (:to %))) (map (comp text :name)))
                (machine/edges-seq rdef))
    (= state (:initial rdef)) (conj "create")))

(defn- indirect-exits
  "The kind's declared ways out of `state` that are no door of its own
  a seat takes (`:left-by`)."
  [rdef state]
  (filterv #(contains? (set (:in %)) state) (:left-by rdef)))

(defn- door-name [{:keys [kind action]}]
  (str (text kind) "/" (text action)))

(defn- no-narrower?
  "Is the wake filter no narrower than one of the filters the seat
  acts under? Every field it names, that scope filter names the same.
  `::any` stands for a door held on ANOTHER kind by a seat that holds
  no door of this kind out of the state: nothing of this kind bounds
  its rows, so any wake filter is whole. A seat that also holds a door
  of this kind gets no `::any` (`state-cracks`), and its wake filter on
  this kind is judged against that door's filters (ticket adcf09e1)."
  [wake-filter scope-filters]
  (or (empty? wake-filter)
      (boolean (some #{::any} scope-filters))
      (boolean
       (some (fn [sf]
               (every? (fn [[k v]] (= (text v) (some-> (fval sf k) text)))
                       wake-filter))
             (remove #{::any} scope-filters)))))

(defn- far-arrivals
  "The doors of OTHER kinds the kind declares bring a row to `state`
  (`:reached-by`), as #{[kind action]}."
  [rdef state]
  (into #{}
        (comp (filter #(contains? (set (:in %)) state))
              (map (fn [{:keys [by]}] [(text (:kind by)) (text (:action by))])))
        (:reached-by rdef)))

(defn- wake-entries
  "The seat's wake_on entries on a transition into `state` of the kind,
  and the ones on another kind's door the kind declares brings a row
  there (`:reached-by`). The filter of such an entry speaks of that
  kind's fields and narrows no row of this one, so it is dropped."
  [seat rdef state walk-rdef]
  (let [kind (text (:kind rdef))
        into-state (arrivals rdef state)
        far (far-arrivals rdef state)]
    (into []
          (keep (fn [e]
                  (let [acts (map text (:actions e))]
                    (cond
                      (and (= kind (text (:kind e)))
                           (some into-state acts)
                           (admits-state? (:filter e) state))
                      e

                      (some #(contains? far [(text (:kind e)) %]) acts)
                      (dissoc e :filter)))))
          (seats/effective-wake-on seat walk-rdef))))

(defn- narrow-wakes
  "The filters of the seat's wake_on entries into `state` that ARE
  narrower than every filter it acts under: each wakes the seat for the
  rows it admits, and for no other."
  [seat rdef state scope-filters walk-rdef]
  (into []
        (comp (map :filter) (remove #(no-narrower? % scope-filters)))
        (wake-entries seat rdef state walk-rdef)))

(defn- named-values
  "A filter's entries on fields other than `state`, as {field [values]}."
  [fm]
  (into (sorted-map)
        (for [[k v] (when (map? fm) fm)
              :when (not= "state" (text k))]
          [(text k) (vec (sort (fvals v)))])))

(defn- row-meets?
  "Does this row pass the filter's entries on fields other than
  `state`? A filter that names none admits every row."
  [fm row]
  (every? (fn [[field said]]
            (let [have (fval (:data row) field)]
              (some (set said) (map text (if (coll? have) have [have])))))
          (named-values fm)))

(defn- unnamed-values
  "What the rows no wake meets carry in the fields the narrowed wakes
  name, and none of them names: [{:field :value :rows}]."
  [narrowed unmet]
  (let [named (apply merge-with into {}
                     (for [l narrowed
                           fm (:narrow l)
                           [field said] (named-values fm)]
                       {field (set said)}))]
    (vec (for [[field said] (sort-by key named)
               [value n] (sort-by key
                                  (frequencies
                                   (for [row unmet
                                         :let [have (fval (:data row) field)]
                                         one (if (coll? have) have [have])
                                         :let [s (text one)]
                                         :when (and (seq s) (not (contains? said s)))]
                                     s)))]
           {:field field :value value :rows n}))))

(defn- wakes-of
  "What brings this seat to a row in `state` of the kind, as the words
  the answer prints: interactive (a person sits in it), cadence,
  wake_on. Empty is a seat nothing brings to this state. Of the three
  only wake_on is a wake; `state-cracks` grades the other two."
  [seat rdef state scope-filters walk-rdef]
  (cond-> []
    (seats/interactive-seat? seat) (conj "interactive")
    (some? (get-in seat [:data :cadence_seconds])) (conj "cadence")
    (some #(no-narrower? (:filter %) scope-filters)
          (wake-entries seat rdef state walk-rdef))
    (conj "wake_on")))

(defn- state-cracks
  "no_exit, cadence_only, person_only and unwoken, for one kind. A
  state one of its leavers has a wake_on entry for is clean; what is
  left is graded by the best thing that still brings a seat there.
  `rows` is the rows of the kind that were read, nil when none were:
  with them, wakes narrower than their seats' scopes are judged
  together, and the state is woken when every live row meets one."
  [rdef seat-rows by-kind rows]
  (let [kind (text (:kind rdef))
        terminal (resting rdef)
        live (when (some? rows)
               (let [rests (into #{} (map text) terminal)]
                 (remove #(contains? rests (text (:state %))) rows)))
        walk-rdef (fn [seat]
                    (some->> (get-in seat [:data :walk]) str not-empty
                             keyword (get by-kind)))]
    (for [state (sort-by text (machine/reachable-states rdef))
          :when (not (contains? terminal state))
          :let [indirect (indirect-exits rdef state)]
          ;; a source leaves it, and no seat is needed
          :when (not-any? (comp keyword? :by) indirect)
          :let [out (exits rdef state)
                far-doors (into [] (comp (map :by) (filter map?) (distinct)) indirect)
                leavers (for [seat seat-rows
                              :let [held (for [a out
                                               :let [es (holding-entries seat kind (:name a) state)]
                                               :when (seq es)]
                                           {:action (text (:name a)) :entries es})
                                    far (filterv #(seq (holding-entries seat (:kind %) (:action %) nil))
                                                 far-doors)]
                              :when (or (seq held) (seq far))
                              :let [filters (cond-> (into [] (comp (mapcat :entries) (map :filter)) held)
                                              (and (seq far) (empty? held)) (conj ::any))]]
                          {:seat seat
                           :holds (into (mapv :action held) (map door-name) far)
                           :filters filters
                           :narrow (narrow-wakes seat rdef state filters (walk-rdef seat))})
                considered (for [l leavers]
                             (cond-> {:seat (seat-name (:seat l))
                                      :holds (:holds l)
                                      :wakes (wakes-of (:seat l) rdef state (:filters l)
                                                       (walk-rdef (:seat l)))}
                               (seq (:narrow l))
                               (assoc :wakes_for (mapv named-values (:narrow l)))))
                ;; the seats woken for SOME rows here, and the live rows
                ;; none of them is both woken for and able to move
                narrowed (filterv (comp seq :narrow) leavers)
                unmet (when (and live (seq narrowed))
                        (remove (fn [row]
                                  (some (fn [l]
                                          (and (some #(row-meets? % row) (:filters l))
                                               (some #(row-meets? % row) (:narrow l))))
                                        narrowed))
                                live))
                missing (unnamed-values narrowed unmet)
                wakes (cond-> (into #{} (mapcat :wakes) considered)
                        (and live (seq narrowed) (empty? unmet)) (conj "wake_on"))
                cadences (for [l leavers
                               :let [c (get-in (:seat l) [:data :cadence_seconds])]
                               :when (number? c)]
                           c)
                shortest (when (seq cadences) (apply min cadences))
                wake-it (str "Give one of these seats a wake_on entry on " kind
                             " naming one of: "
                             (str/join ", " (sort (arrivals rdef state)))
                             " (with a filter no narrower than its scope filter"
                             (when (seq missing)
                               (str ", or one that admits "
                                    (str/join ", " (map #(str (:field %) " " (:value %))
                                                        missing))))
                             ").")]
          crack (cond
                  (empty? leavers)
                  [{:kind kind :state (text state) :crack "no_exit"
                    :seats_considered (into []
                                            (comp (filter #(seq (entries-for % kind)))
                                                  (map (fn [s] {:seat (seat-name s) :holds []})))
                                            seat-rows)
                    :what_would_close_it
                    (str
                     (if (seq out)
                       (str "Give an active seat a scope entry on " kind
                            " that holds one of: "
                            (str/join ", " (map (comp text :name) out))
                            ", under a filter that admits the state " (text state) ".")
                       (str "No action of " kind " leaves " (text state)
                            ": declare one, or declare the state terminal."))
                     (when (seq far-doors)
                       (str " Or give one a scope entry that holds one of: "
                            (str/join ", " (map door-name far-doors))
                            ", which " kind " declares moves its rows out of "
                            (text state) ".")))}]

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
      (cond-> crack (seq missing) (assoc :unwoken_values missing)))))

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
  alone. A guard declared `:hold :agents` holds only an agent's call,
  so the door is read as if it were absent."
  [rdef action]
  (cond-> []
    (get-in action [:safety :confirm]) (conj "confirm")
    (some (fn [{:keys [door guard]}]
            (and (= (:name action) door)
                 (not= :agents (:hold guard))
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

(defn- deadline-cracks
  "deadline_untold, for one kind: an `:expected-age {:until …}` on a
  state whose own crack in `states` (this kind's `state-cracks`) is
  no_exit or unwoken, and that nothing tells a person of (`tellers`).
  Its fields are the ones the :until reads on the row, and each field
  it follows to on another kind as kind.field (`checks/law-follows`)."
  [rdef seat-rows world states]
  (let [kind (text (:kind rdef))
        untold (into #{}
                     (comp (filter #(contains? #{"no_exit" "unwoken"} (:crack %)))
                           (map :state))
                     states)]
    (for [[state age] (sort-by (comp text key) (:expected-age rdef))
          :when (and (map? age) (contains? age :until))
          :when (contains? untold (text state))
          :when (empty? (tellers kind state (arrivals rdef state) seat-rows world))
          :let [own (map name (:data (expr/info (:until age))))
                followed (for [[k f] (checks/law-follows {:expected-age {state age}})]
                           (str (name k) "." (name f)))
                fields (vec (sort (distinct (concat own followed))))
                named (str/join ", " fields)]]
      {:kind kind :state (text state) :crack "deadline_untold"
       :fields fields
       :what_would_close_it
       (str "A row of " kind " in " (text state) " past its deadline (" named
            ") is filed as aged, and no seat is woken to " (text state)
            " to hear it. Wake a seat on that state, or add a notice rule on "
            kind " whose `when` names to_state " (text state) ".")
       :question (str "Who should hear when a " kind " is past " named "?")})))

(defn- measure-cracks
  "measure-tell-unseated, for one kind: a declared measure whose `:tell`
  names no active seat, by its name or its id. The law battery reads
  the :tell and not the seat rows, so this is where it is judged."
  [rdef seat-rows]
  (let [kind (text (:kind rdef))
        seated (into #{} (mapcat (fn [s] [(seat-name s) (str (:id s))])) seat-rows)]
    (for [[mname m] (when (map? (:measures rdef)) (sort-by (comp text key) (:measures rdef)))
          :when (map? m)
          :let [tell (some-> (:tell m) text not-empty)
                token (str kind "." (text mname))]
          :when (and tell (not (contains? seated tell)))]
      {:kind kind :measure (text mname) :crack "measure-tell-unseated"
       :seat tell
       :what_would_close_it
       (str token " tells `" tell "`, which is not an active seat; a miss would tell nobody.")
       :question (str "Who should hear when " (or (:label m) token) " misses its goal?")})))

(defn- person-cracks
  "silent_person_exit, for one kind: a state every exit from which
  waits on a person, with nothing that tells the person. A state the
  kind declares `:left-by :person` is one, whatever its doors say."
  [rdef seat-rows world]
  (let [kind (text (:kind rdef))
        terminal (resting rdef)]
    (for [state (sort-by text (machine/reachable-states rdef))
          :when (not (contains? terminal state))
          :let [out (mapv (fn [a] {:action (text (:name a))
                                   :held_by (held-by rdef a)})
                          (exits rdef state))
                tapped (some #(= :person (:by %)) (indirect-exits rdef state))]
          :when (or tapped (and (seq out) (every? (comp seq :held_by) out)))
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
  read. Each state is judged by the walks that hand its rows out
  (ticket 5cb126ab): a walk its `state` entry shuts to the state says
  nothing of the rows there, so a seat that walks the drafts of every
  repository hides no open row. In a state a field counts when EVERY
  such walk filters on it, since a walk with no entry for the field
  admits each of its values. A row a seat is woken for and can move is
  no crack: the seat holds an exit under a filter the row meets (or a
  door of another kind the kind declares leaves the state), and a
  wake_on entry into the state the row meets too. `state` is left to
  the cracks above.

  Where the kind declares another kind's door out of the state, a walk
  is judged there only when its seat holds that door (ticket
  af58f25f): a seat that walks the open rows of every repository and
  can only drop or block one builds none of them, and hid the row no
  builder is handed. Where no walk's seat holds it, every walk of the
  state is judged, as before.

  A seat a person sits in covers no value by sitting (ticket
  8186d41a): its walk is left out of the walks judged here, and the
  wake_on a walk seat is given when it wrote none is not counted for
  it, since nothing fires a person. What it WROTE in wake_on counts.
  Where the kind declares another kind's door out of the state, only
  a seat that holds that door moves the row: the mayor's ungroom and
  drop leave the state and do not build the work."
  [rdef seat-rows by-kind rows]
  (let [kind (text (:kind rdef))
        terminal (into #{} (map text) (resting rdef))
        walks (into []
                    (comp (filter #(= kind (some-> (get-in % [:data :walk]) str)))
                          (remove seats/interactive-seat?)
                          (map (fn [s] {:seat s :fm (seats/walk-filter s)})))
                    seat-rows)
        walk-rdef (fn [seat]
                    (some->> (get-in seat [:data :walk]) str not-empty
                             keyword (get by-kind)))
        wake-on (fn [seat]
                  (if (seats/interactive-seat? seat)
                    (get-in seat [:data :wake_on])
                    (seats/effective-wake-on seat (walk-rdef seat))))
        far-of (fn [state]
                 (into [] (comp (map :by) (filter map?))
                       (indirect-exits rdef (keyword state))))
        takes? (fn [seat far]
                 (some #(seq (holding-entries seat (:kind %) (:action %) nil)) far))
        woken? (fn [row state]
                 (let [state (keyword state)
                       out (exits rdef state)
                       far (far-of state)
                       into-state (arrivals rdef state)]
                   (some (fn [seat]
                           (and (if (seq far)
                                  (takes? seat far)
                                  (some (fn [a]
                                          (some #(row-meets? (:filter %) row)
                                                (holding-entries seat kind (:name a) state)))
                                        out))
                                (some (fn [e]
                                        (and (= kind (text (:kind e)))
                                             (some into-state (map text (:actions e)))
                                             (admits-state? (:filter e) state)
                                             (row-meets? (:filter e) row)))
                                      (wake-on seat))))
                         seat-rows)))
        live (remove #(contains? terminal (text (:state %))) rows)
        carried (for [[state at] (sort-by key (group-by (comp text :state) live))
                      :let [far (far-of state)
                            open-to (filterv #(admits-state? (:fm %) state) walks)
                            here (or (not-empty (filterv #(takes? (:seat %) far) open-to))
                                     open-to)
                            filters (mapv :fm here)]
                      field (into (sorted-set)
                                  (comp (mapcat keys) (map text) (remove #{"state"}))
                                  filters)
                      :when (every? #(some? (fval % field)) filters)
                      :let [admitted (into #{} (mapcat #(fvals (fval % field))) filters)]
                      row at
                      :let [v (fval (:data row) field)]
                      :when (some? v)
                      one (if (coll? v) v [v])
                      :let [s (text one)]
                      :when (and (seq s)
                                 (not (contains? admitted s))
                                 (not (woken? row state)))]
                  {:field field :value s :state state :walks here :admitted admitted})]
    (for [[[field value] hits] (sort-by key (group-by (juxt :field :value) carried))
          :let [admitted (into (sorted-set) (mapcat :admitted) hits)]]
      {:kind kind :state nil :crack "unwalked_value"
       :field field :value value
       :rows (count hits)
       :states (vec (distinct (map :state hits)))
       :seats_considered (into []
                               (comp (mapcat :walks)
                                     (distinct)
                                     (map (fn [{:keys [seat fm]}]
                                            {:seat (seat-name seat)
                                             :admits (vec (sort (fvals (fval fm field))))})))
                               hits)
       :what_would_close_it
       (str "Give an active seat a walk over " kind " whose filter admits "
            field " " value ", or restate these rows under a " field
            " a seat walks: " (str/join ", " admitted) ".")})))

(defn holds-door?
  "Does the seat's scope hold `action` on `kind` in some state: the
  door alone, whatever row it is asked of."
  [seat kind action]
  (boolean (seq (holding-entries seat kind action nil))))

(defn movers
  "The names of the active seats whose scope holds an action that
  leaves `state` of this kind: who this audit says can move a row that
  sits there. The live audit (ticket 26e51658) names them for a row
  that has stopped."
  [rdef seat-rows state]
  (let [kind (text (:kind rdef))
        state (keyword (text state))
        out (exits rdef state)]
    (into []
          (comp (filter (fn [seat]
                          (some #(seq (holding-entries seat kind (:name %) state))
                                out)))
                (map seat-name))
          seat-rows)))

(defn walked-fields
  "The fields, other than `state`, that an active seat's scope filter on
  this kind names: what decides which seat a row of the kind belongs to."
  [rdef seat-rows]
  (into (sorted-set)
        (for [seat seat-rows
              e (entries-for seat (:kind rdef))
              [field _] (named-values (:filter e))]
          field)))

(defn row-doors
  "For ONE row (ticket 86af9d88): every way out of the state it is in,
  each with the active seats that hold it FOR THIS ROW and what brings
  each of them. nil for a row in a state a row rests in.

  Where the audit reads a filter for its `state` entry alone, this reads
  all of it against the row: a seat holds a door here when a scope entry
  that names the door admits the row. A door of another kind
  (`:left-by`) is held for this row by a seat that holds it and, when
  the seat walks this kind, is handed the row by its walk. An exit a
  source takes (the mirror, a wait's ending) needs no seat and says so.
  A door named as the `:via` of such an exit is walked by that exit and
  is not listed again.

  Each seat says `woken_by`: the doors into this state its wake_on
  names under a filter that admits the row. `held_by` is `seat` when a
  seat a model sits in holds the door, `person` when only interactive
  seats do, `source`, or `nobody`."
  [rdef seat-rows by-kind row]
  (let [kind (text (:kind rdef))
        state (keyword (text (:state row)))
        into-state (arrivals rdef state)
        far-in (far-arrivals rdef state)
        ;; a person's tap is a door of this kind, listed with the seats
        ;; that hold it, and no source
        indirect (filterv #(not= :person (:by %)) (indirect-exits rdef state))
        vias (into #{} (keep #(some-> (:via %) text)) indirect)
        walk-of (fn [seat] (some-> (get-in seat [:data :walk]) str not-empty))
        handed? (fn [seat]
                  (or (not= kind (walk-of seat))
                      (some #(and (admits-state? (:filter %) state)
                                  (row-meets? (:filter %) row))
                            (entries-for seat kind))))
        woken-by (fn [seat]
                   (into []
                         (comp (filter #(row-meets? (:filter %) row))
                               (mapcat (fn [e]
                                         (let [k (text (:kind e))]
                                           (for [a (map text (:actions e))
                                                 :when (if (= kind k)
                                                         (contains? into-state a)
                                                         (contains? far-in [k a]))]
                                             (if (= kind k) a (str k "/" a))))))
                               (distinct))
                         (wake-entries seat rdef state
                                       (some->> (walk-of seat) keyword (get by-kind)))))
        holder (fn [seat]
                 (let [by (woken-by seat)
                       cadence (get-in seat [:data :cadence_seconds])]
                   (cond-> {:seat (seat-name seat)}
                     (seats/interactive-seat? seat) (assoc :person true)
                     (seq by) (assoc :woken_by by)
                     (number? cadence) (assoc :cadence_seconds cadence))))
        door (fn [m held?]
               (let [hs (into [] (comp (filter held?) (map holder)) seat-rows)]
                 (assoc m
                        :seats hs
                        :held_by (cond (empty? hs) "nobody"
                                       (every? :person hs) "person"
                                       :else "seat"))))]
    (when-not (contains? (resting rdef) state)
      (concat
       (for [a (exits rdef state)
             :when (not (contains? vias (text (:name a))))]
         (door {:door (text (:name a)) :to (text (:to a))}
               (fn [seat]
                 (some #(row-meets? (:filter %) row)
                       (holding-entries seat kind (:name a) state)))))
       (for [{:keys [by via to]} indirect]
         (if (map? by)
           (door (cond-> {:door (door-name by)}
                   via (assoc :via (text via))
                   to (assoc :to (text to)))
                 (fn [seat]
                   (and (seq (holding-entries seat (:kind by) (:action by) nil))
                        (handed? seat))))
           (cond-> {:door (text via) :held_by "source" :source (text by)}
             to (assoc :to (text to)))))))))

(defn- wait-doors
  "The doors a hand takes on a row that waits: the ones the wait says
  restate it, or every action open in a state it waits in."
  [rdef wait states]
  (or (seq (map text (:restated-by wait)))
      (distinct
       (for [state states
             a (if (some? state)
                 (machine/transitions-from rdef state)
                 (machine/actions-seq rdef))]
         (text (:name a))))))

(defn- wait-cracks
  "dead_wait, for one kind: one crack per declared wait, per outcome of
  the awaited row that does `:nothing`, and per state the row waits in
  (none when the wait names no state), where no active seat is woken to
  act. A seat acts when it holds one of the wait's doors in a waiting
  state and something brings it: a wake_on entry on the awaited kind
  naming an action that lands in that outcome, or a cadence. A wait
  whose `:waiter` is no row of this kind has no door to hold, and only
  the wake_on entry counts. An awaited thing that is no kind here has
  no action to name, and only a cadence does."
  [rdef seat-rows by-kind]
  (let [kind (text (:kind rdef))]
    (for [wait (:waits rdef)
          :let [awaited (:awaits wait)
                awaited-rdef (get by-kind awaited)
                row-waits? (nil? (:waiter wait))
                states (or (seq (sort-by text (:in wait))) [nil])
                doors (when row-waits? (wait-doors rdef wait states))]
          [outcome does] (sort-by (comp text key) (:outcomes wait))
          :when (= :nothing does)
          :let [told (if awaited-rdef (arrivals awaited-rdef outcome) #{})
                considered
                (for [seat seat-rows
                      :let [holds (filterv (fn [d]
                                             (some #(seq (holding-entries seat kind d %))
                                                   states))
                                           doors)
                            wakes (cond-> []
                                    (and row-waits?
                                         (some? (get-in seat [:data :cadence_seconds])))
                                    (conj "cadence")
                                    (some (fn [e]
                                            (and (some? awaited)
                                                 (= (text awaited) (text (:kind e)))
                                                 (some told (map text (:actions e)))
                                                 (admits-state? (:filter e) outcome)))
                                          (seats/effective-wake-on
                                           seat
                                           (some->> (get-in seat [:data :walk]) str
                                                    not-empty keyword (get by-kind))))
                                    (conj "wake_on"))]
                      :when (if row-waits? (seq holds) (seq wakes))]
                  {:seat (seat-name seat) :holds holds :wakes wakes})]
          :when (every? (comp empty? :wakes) considered)
          state states]
      {:kind kind :state (some-> state text) :crack "dead_wait"
       :on (text (:on wait))
       :awaits (some-> awaited text)
       :outcome (text outcome)
       :seats_considered (vec considered)
       :what_would_close_it
       (str "Declare what " (text outcome) " does to what waits on "
            (text (:on wait)) " of " kind ", or give an active seat"
            (when row-waits?
              (str " that holds one of: " (str/join ", " doors) ","))
            (if (seq told)
              (str " a wake_on entry on " (text awaited) " naming one of: "
                   (str/join ", " (sort told))
                   (when row-waits? ", or a cadence"))
              " a cadence")
            ".")})))

(defn fired-cadences
  "The seats as a cadence reads them (ticket 8950d824): `scheduled` is
  the ids of the seats a schedule that is not ended or broken stands
  for, and a seat outside it keeps no cadence, because nothing fires
  it on one. Nil is a world that read no schedules: every stated
  cadence counts."
  [seat-rows scheduled]
  (if (nil? scheduled)
    (vec seat-rows)
    (mapv #(if (contains? scheduled (str (:id %)))
             %
             (update % :data dissoc :cadence_seconds))
          seat-rows)))

(defn audit
  "Every crack the declarations and the active seats leave, in a
  stable order. `rdefs` is every kind the engine serves; `seat-rows`
  is the ACTIVE seats, as stored. `world` is the rest that was read,
  each part optional: `:notice-rules` and `:subscriptions` are the
  ACTIVE rows of those kinds (and the rules the engine declares, in a
  row's shape), `:rows` is {kind [row …]} for the kinds whose values
  are judged, `:scheduled` the ids of the seats a schedule fires
  (`fired-cadences`)."
  ([rdefs seat-rows] (audit rdefs seat-rows nil))
  ([rdefs seat-rows world]
   (let [by-kind (into {} (map (juxt :kind identity)) rdefs)
         seat-rows (fired-cadences seat-rows (:scheduled world))]
     (into []
           (mapcat (fn [rdef]
                     (let [states (vec (state-cracks rdef seat-rows by-kind
                                                     (get (:rows world) (:kind rdef))))]
                       (concat states
                             (person-cracks rdef seat-rows world)
                             (remedy-cracks rdef seat-rows)
                             (wait-cracks rdef seat-rows by-kind)
                             (value-cracks rdef seat-rows by-kind
                                           (get (:rows world) (:kind rdef)))
                             (deadline-cracks rdef seat-rows world states)
                             (measure-cracks rdef seat-rows)))))
           (sort-by (comp text :kind) rdefs)))))

;; ── what a staffing change opens (ticket 5f3c276c) ──────────────────

(def ^:private depth
  "How far a state has fallen, for the cracks that grade one state. A
  seat change that moves a state up this list opened a crack, and one
  that moves it down closed one."
  {"cadence_only" 1 "person_only" 2 "unwoken" 3 "no_exit" 4})

(defn- crack-key
  "What makes a crack the same one before a change and after it."
  [c]
  (select-keys c [:kind :state :crack :door :remedy :on :outcome :measure]))

(defn added
  "The cracks the seats in `after` leave and the seats in `before` did
  not, in the audit's order and its words. Both are read by `audit`
  over the same declarations and the same world.

  A state that only changes its grade is judged by `depth`: one that
  falls is a crack added, and one that rises is not. A state no seat
  could leave before is at the bottom, and no change adds a crack to
  it."
  ([rdefs before after] (added rdefs before after nil))
  ([rdefs before after world]
   (let [was (audit rdefs before world)
         had (into #{} (map crack-key) was)
         at (juxt :kind :state)
         fallen (reduce (fn [m c]
                          (if-some [d (depth (:crack c))]
                            (update m (at c) (fnil max 0) d)
                            m))
                        {} was)
         bottom (depth "no_exit")]
     (into []
           (remove (fn [c]
                     (let [d (get fallen (at c) 0)]
                       (or (contains? had (crack-key c))
                           (= bottom d)
                           (some-> (depth (:crack c)) (<= d))))))
           (audit rdefs after world)))))

(def ^:private read-with-the-world
  "The cracks `audit` grades by the notice rules, the subscriptions and
  the rows. A seat door's guard reads none of them, so it does not
  judge these."
  #{"silent_person_exit" "unwalked_value" "deadline_untold"})

(defn opened
  "`added`, as a seat door's guard reads it: over the declarations and
  the seat rows alone. With no rows read a narrowed wake covers
  nothing, as `audit` says."
  [rdefs before after]
  (into []
        (remove (comp read-with-the-world :crack))
        (added rdefs before after)))

(seats/staffing-rule! #'opened)

(defn- active-of [eng kind]
  (if (contains? (inv/resources eng) kind)
    (store/with-tx (:storage eng)
      (fn [tx]
        (store/query-rows (:storage eng) tx kind {:state :active}
                          {:limit seat-page})))
    []))

(defn scheduled-in
  "The ids of the seats a schedule that is not ended or broken stands
  for, read in `tx`, or nil when the engine serves no schedule kind."
  [eng tx]
  (when (contains? (inv/resources eng) :schedule)
    (let [st (:storage eng)]
      (into #{}
            (comp (mapcat #(store/query-rows st tx :schedule {:state %}
                                             {:limit seat-page}))
                  (keep #(some-> (get-in % [:data :seat]) str not-empty)))
            [:pending :live :paused]))))

(defn- scheduled-seats
  "`scheduled-in`, in a transaction of its own."
  [eng]
  (when (contains? (inv/resources eng) :schedule)
    (store/with-tx (:storage eng) #(scheduled-in eng %))))

(defn- told
  "The notice rules that can tell: the active rows, and the rules the
  engine declares itself, in a row's shape."
  [eng]
  (into (vec (active-of eng :notice_rule))
        (for [[k rules] held-calls/engine-rules
              r rules]
          {:data (assoc r :kind (name k))})))

(defn- live-rows
  "{kind [row …]} for every kind an active seat walks under a filter,
  and every kind one is woken on under a filter that names a field
  other than `state`: its rows in each state no row rests in, the
  newest `row-page` of each state."
  [eng seat-rows]
  (let [st (:storage eng)
        resources (inv/resources eng)
        walk-of (fn [seat]
                  (some-> (get-in seat [:data :walk]) str not-empty keyword))]
    (into {}
          (for [k (distinct
                   (concat (keep #(when (seats/walk-filter %) (walk-of %)) seat-rows)
                           (for [seat seat-rows
                                 e (seats/effective-wake-on
                                    seat (get resources (walk-of seat)))
                                 :when (seq (named-values (:filter e)))]
                             (keyword (text (:kind e))))))
                :let [rdef (get resources k)]
                :when rdef]
            [k (store/with-tx st
                 (fn [tx]
                   (into []
                         (mapcat #(store/query-rows st tx k {:state %}
                                                    {:limit row-page :newest-first true}))
                         (remove (resting rdef)
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
                             :rows (live-rows eng seat-rows)
                             :scheduled (scheduled-seats eng)}))]
    {:waymark "cracks"
     :seats (count seat-rows)
     :total (count cracks)
     :by_crack (into (sorted-map) (frequencies (map :crack cracks)))
     :cracks cracks
     ;; whether the :at-rest :encrypted fields are sealed (ticket
     ;; dbeb2f25): a note beside the cracks, never one of them
     :at_rest (at-rest/note)}))

(def ^:private export-fields
  "What of a seat `audit` reads, and so all the export prints of one."
  [:name :mode :walk :cadence_seconds :scope :wake_on])

(defn seat-export
  "The seats document (ticket 42259e4b): `seat-rows` narrowed to the
  fields `audit` reads, in the order of their names, a field a seat
  does not carry left out. It is the shape factory10's seat fixture is
  read from, so the gate there can judge the law against the seats the
  house runs with. Pure, as `audit` is."
  [seat-rows]
  {:waymark "cracks_seats"
   :seats (mapv (fn [seat]
                  (into {:name (seat-name seat)}
                        (keep (fn [k]
                                (when-some [v (get-in seat [:data k])]
                                  [k v])))
                        (rest export-fields)))
                (sort-by seat-name seat-rows))})

(defn seats-report
  "GET /api/-/cracks/seats: the ACTIVE seats, as `report` reads them."
  [eng]
  (seat-export (active-of eng :seat)))
