(ns waymark10.occupies
  "`:occupies` (docs/spec-calendar-time.md §5): a row holds a resource
  for a window, and the engine writes the guards that say so. An entry
  {:resource :from :to :in :doors} names the ref to the resource, the
  window's two fields, the states that hold it and the doors that take
  it (`:grain :day` reads the window as whole days). On each door the
  registry appends two trees, named from the resource field:

  - `<resource>-is-free`: one `none` per kind that occupies the same
    resource kind (the row itself is left out by `none`), with a
    `first` naming a free resource in evidence and a remedy on each
    door of the holding row's kind that moves it in a holding state;
  - `<resource>-is-working`: `working?` over the window — with
    `:grain :day`, every day of [from, to] a business day — in the
    resource's own `:calendar` when its kind names one, else the
    default work_calendar.

  On an instant window both name (evidence :slot), a `next-free`
  (build step 9): the next window as long as the refused one, within
  30 business days, in which the resource is working and free — and
  the working guard a remedy that takes the door again at that slot,
  and the holiday the refused day is. On a `:grain :day` window the
  free guard names it alone: the next run of as many whole days, its
  first a business day ({slot.day}, no {slot.time}).

  An entry's `:bumps #{door}` names doors of the kind that move the row
  to the resource's next free window — a rain day. The engine writes
  each one's handler (an authored one is a definition error): the same
  scan, from the business day after the row's own (an instant window
  from its opening hour; a day window as next-free-days steps), the row
  left out as the -is-free guards leave it; and appends
  `<resource>-has-a-free-day`, which refuses when no window turns up in
  30 business days.

  A door whose input does not carry a field reads it from the row,
  (data …). Derived at assembly, where every occupier of a resource
  kind is known; the trees fingerprint as any authored guard. A kind's
  `:explain {guard-name \"sentence\"}` replaces a derived guard's
  sentence."
  (:require [clojure.string :as str]
            [waymark10.declare :as d]
            [waymark10.evidence :as evidence]
            [waymark10.expr :as expr]
            [waymark10.guards :as g]
            [waymark10.schema :as schema]
            [waymark10.types :as t])
  (:import (java.time LocalDate)))

(set! *warn-on-reflection* true)

(def ^:private entry-keys #{:resource :from :to :in :doors :grain :bumps})

(defn- fail [kind msg]
  (throw (t/definition-error (str (name kind) " [occupies] " msg) {:check :occupies})))

(defn resource-word
  "The resource field as a word: :crew_id → \"crew\"."
  [field]
  (str/replace (str/replace (name field) #"_id$" "") "_" " "))

(defn guard-name
  "A derived guard's name: :crew_id and \"is-free\" → :crew-is-free."
  [field suffix]
  (keyword (str (str/replace (resource-word field) " " "-") "-" suffix)))

(defn- ref-kinds [form]
  (into {} (keep (fn [{:keys [field kind listed]}] (when-not listed [field kind])))
        (when form (schema/ref-fields form))))

(defn- create-door [r door]
  (some #(when (= door (:action %)) %) (:creates r)))

(defn- door-fields
  "The fields a door's input carries; the create door's is the kind's
  create schema."
  [r door]
  (let [form (cond
               (contains? (:actions r) door) (get-in r [:actions door :input])
               (create-door r door) (:input (create-door r door))
               :else (or (:create-schema r) (:schema r)))]
    (set (when form (schema/entry-keys form)))))

(defn- entry
  "One :occupies entry of `kind`, judged against its declaration."
  [kind r o]
  (when-not (map? o)
    (fail kind (str "an :occupies entry is {:resource :from :to :in :doors}; " (pr-str o) " is not")))
  (let [{:keys [resource from to in doors grain bumps]} o
        fields (set (schema/entry-keys (:schema r)))
        target (get (ref-kinds (:schema r)) resource)
        in (when (coll? in) (set in))
        doors (when (coll? doors) (set doors))
        door? (into (set (keys (:actions r)))
                    (concat (or (:create-action-names r) #{:create}) (map :action (:creates r))))]
    (when-some [extra (seq (remove entry-keys (keys o)))]
      (fail kind (str "an :occupies entry declares " (vec extra) ", keys it does not know")))
    (when-not target
      (fail kind (str ":resource " (pr-str resource) " is not a ref field naming its :kind")))
    (doseq [f [from to]]
      (when-not (contains? fields f)
        (fail kind (str "the window's " (pr-str f) " is not a field of " (name kind)))))
    (when-not (and (seq in) (every? (set (:states r)) in))
      (fail kind (str ":in names the states that hold " (resource-word resource) "; "
                      (pr-str (:in o)) " are not states of " (name kind))))
    (when-not (and (seq doors) (every? door? doors))
      (fail kind (str ":doors names the doors that take " (resource-word resource) "; "
                      (pr-str (:doors o)) " are not doors of " (name kind))))
    (when-not (contains? #{nil :instant :day} grain)
      (fail kind (str ":grain is :instant or :day, not " (pr-str grain))))
    (when-not (or (nil? bumps) (and (coll? bumps) (seq bumps) (every? keyword? bumps)))
      (fail kind (str ":bumps names the doors that move " (resource-word resource)
                      " to its next free window; " (pr-str bumps) " is not a set of doors")))
    (doseq [b (sort bumps)
            :let [h (get-in r [:actions b :handler])]
            :when (and (some? h) (not (::bump (meta h))))]
      (fail kind (str (pr-str b) " is a :bumps door, and declares a :handler; the engine writes a bump's")))
    {:kind kind :resource resource :target target :from from :to to
     :in in :doors doors :grain (or grain :instant) :bumps (set bumps)
     :froms (into {} (map (fn [[a ad]] [a (:from ad)])) (:actions r))}))

(defn- binders
  "Each occupier of one resource kind with its binder: the kind's
  initial letter, numbered when two share one."
  [occs]
  (second
   (reduce (fn [[seen out] o]
             (let [base (subs (name (:kind o)) 0 1)
                   n (get seen base 0)]
               [(assoc seen base (inc n))
                (conj out [o (symbol (if (zero? n) base (str base (inc n))))])]))
           [{} []]
           occs)))

(defn- all-of [xs]
  (if (next xs) (cons 'and xs) (first xs)))

(defn- overlap
  "The where that finds occupier `p` holding resource `res` in the
  window [from-side, to-side)."
  [p grain res from-side to-side]
  (let [[before after] (if (= :day grain) '[<= >=] '[< >])]
    {(:resource p) (list '= res)
     (:from p) (list before to-side)
     (:to p) (list after from-side)
     :state (cons 'in (sort (map name (:in p))))}))

(defn- sides
  "The resource, from and to of `o` as door `door` reads them: its
  input where it carries the field, the row where it does not."
  [kinds o door]
  (let [fields (door-fields (get kinds (:kind o)) door)]
    (mapv #(list (if (contains? fields %) 'input 'data) %)
          [(:resource o) (:from o) (:to o)])))

(defn- nones-of
  "The tree that finds resource `res-side` held by none of `occs` in
  the window [from-side, to-side)."
  [o occs res-side from-side to-side]
  (all-of (for [[p b] (binders occs)]
            (list 'none [b (:kind p) (overlap p (:grain o) res-side from-side to-side)]))))

(defn- working-form
  "(working? from to), in the resource's own :calendar when its kind
  names one."
  [kinds o res from-side to-side]
  (let [cal (get-in kinds [(:target o) :calendar])]
    (apply list (cond-> ['working? from-side to-side]
                  cal (conj (list 'follow (:target o) res cal))))))

(defn- next-free
  "(evidence :slot), canonical: the next window as long as the refused
  one in which the resource is working and free, its end as (evidence
  :slot_ends) — the pred the guards' own trees over the moved window.
  A :grain :day window steps whole business days, so its pred is the
  nones alone."
  [kinds o door occs]
  (let [[res from-side to-side] (sides kinds o door)
        cal (get-in kinds [(:target o) :calendar])
        day? (= :day (:grain o))
        nones (nones-of o occs res from-side to-side)]
    {:next-free (cond-> {:from from-side :to to-side :resource res :of (:target o)
                         :calendar (when cal (list 'follow (:target o) res cal))}
                  day? (assoc :grain :day))
     :pred (if day? nones (list 'and nones (working-form kinds o res from-side to-side)))}))

(defn- slot-remedies
  "The refused door again at the slot: the resource as the call gave
  it, the window moved — on a door of the row whose input carries the
  window, not its create."
  [kinds o door]
  (let [fields (door-fields (get kinds (:kind o)) door)]
    (when (and (contains? (:froms o) door) (contains? fields (:from o)))
      [{:door (keyword (name (:kind o)) (name door))
        :input (into {} (filter (comp fields key))
                     {(:resource o) (list 'input (:resource o))
                      (:from o) '(evidence :slot)
                      (:to o) '(evidence :slot_ends)})}])))

(defn- free-guard [kinds o door occs]
  (let [[res from-side to-side] (sides kinds o door)
        bs (binders occs)
        tr (get kinds (:target o))
        live (sort (map name (remove (set (:terminal tr)) (:states tr))))
        gname (guard-name (:resource o) "is-free")
        word (resource-word (:resource o))
        day? (= :day (:grain o))
        sentence (or (get-in kinds [(:kind o) :explain gname])
                     (str/join " " (concat
                                    (for [[p b] bs]
                                      (str "That " word " is held from {" b "." (name (:from p))
                                           "} to {" b "." (name (:to p)) "}."))
                                    [(str "That " word " is next free {slot.day}"
                                          (when-not day? " at {slot.time}") ".")])))
        remedies (vec (for [[p b] bs
                            door (sort (:doors p))
                            :when (some (:in p) (get-in p [:froms door]))]
                        {:door (keyword (name (:kind p)) (name door)) :id (list 'evidence (keyword b))}))
        nones #(nones-of o occs % from-side to-side)]
    (d/sentence-guard gname (d/refuse sentence) (nones res)
                      (cond-> {:evidence {:free (list 'first
                                                      ['candidate (:target o)
                                                       (if (seq live) {:state (cons 'in live)} {})]
                                                      (nones '(get candidate :id)))
                                          :slot (next-free kinds o door occs)}}
                        (seq remedies) (assoc :remedies remedies)))))

(defn- working-guard [kinds o door occs]
  (let [[res from-side to-side] (sides kinds o door)
        gname (guard-name (:resource o) "is-working")
        word (resource-word (:resource o))
        slot? (= :instant (:grain o))
        remedies (when slot? (slot-remedies kinds o door))]
    (d/sentence-guard gname
                      (d/refuse (or (get-in kinds [(:kind o) :explain gname])
                                    (if slot?
                                      (str "{holiday.day} is {holiday.name}. That " word " does not work then. "
                                           "That " word " is next free {slot.day} at {slot.time}.")
                                      (str "That " word " does not work then."))))
                      (working-form kinds o res from-side to-side)
                      (cond-> {}
                        slot? (assoc :evidence {:slot (next-free kinds o door occs)})
                        (seq remedies) (assoc :remedies remedies)))))

(def ^:private next-day
  "The day after the window's own: where a bump's instant scan starts."
  (fn [^LocalDate d] (.plusDays d 1)))

(defn- bump-scan
  "[next-free pred first-day-of]: the scan a :bumps door runs, its pred
  canonical — an instant window from the business day after its own,
  a :grain :day one as next-free-days steps."
  [kinds o door occs]
  (let [{nf :next-free pred :pred} (next-free kinds o door occs)]
    [nf (expr/normalize pred) (when-not (= :day (:grain o)) next-day)]))

(defn- bump-handler
  "The handler the engine writes on a :bumps door: the row's window
  moved to the resource's next free one (guards/free-window), the row
  left out as the -is-free guards leave it. No window: the row as it
  was — <resource>-has-a-free-day refused that call."
  [kinds o door occs]
  (let [[nf pred first-day-of] (bump-scan kinds o door occs)]
    (with-meta
      (fn [row inp ctx]
        (let [{s :from e :to} (g/free-window nf pred row inp ctx first-day-of)]
          (if (some? s)
            (update row :data assoc (:from o) (str s) (:to o) (str e))
            row)))
      {::bump true
       :waymark10/form (list 'bump nf pred)})))

(defn- free-day-guard
  "<resource>-has-a-free-day on a :bumps door: refuses when the scan its
  handler runs finds no window in 30 business days. A ctx that cannot
  read rows, or a scope with no calendar to ask, does not refuse."
  [kinds o door occs]
  (let [[nf pred first-day-of] (bump-scan kinds o door occs)
        gname (guard-name (:resource o) "has-a-free-day")
        word (resource-word (:resource o))]
    (g/guard {:name gname
              :explain (or (get-in kinds [(:kind o) :explain gname])
                           (str "That " word " has no free "
                                (if (= :day (:grain o)) "day" "window")
                                " in the next 30 business days."))
              :reads (vec (sort (distinct (map :kind occs))))
              :check (with-meta
                       (fn [row inp ctx]
                         (let [ctx (if (:find ctx) ctx (merge ctx (:evidence-reads ctx)))
                               found (when (:rows-matching ctx)
                                       (g/free-window nf pred row inp ctx first-day-of))]
                           (if (and (some? found) (nil? (:from found))) (t/deny) (t/allow))))
                       {:waymark10/form (list 'has-a-free-day nf pred 30)})})))

(defn- add-guard
  "`g` on door `door` of `r`, once: a registry built again from its own
  rdefs finds the guard already there."
  [r door g]
  (let [conj-new (fn [gs]
                   (let [gs (vec gs)]
                     (if (some #(= (:name g) (:name %)) gs) gs (conj gs g))))]
    (cond
      (contains? (:actions r) door) (update-in r [:actions door :guards] conj-new)
      (create-door r door) (update r :creates (fn [cs] (mapv #(cond-> % (= door (:action %))
                                                                (update :guards conj-new))
                                                             cs)))
      :else (update r :create-guards conj-new))))

(defn derive-kinds
  "`kinds` ({kind rdef}) with every :occupies door's derived guards
  appended — the registry's step before it fingerprints. Throws the
  [occupies] definition error for an entry its kind cannot hold."
  [kinds]
  (let [entries (vec (for [[kind r] (sort-by key kinds)
                           :let [os (:occupies r)]
                           :when (some? os)
                           o (if (sequential? os) os (fail kind ":occupies is a vector of entries"))]
                       (entry kind r o)))
        by-target (group-by :target entries)]
    (doseq [{:keys [kind target]} entries
            :let [tr (or (get kinds target)
                         (fail kind (str "occupies " (name target) ", a kind this registry does not hold")))
                  cal (:calendar tr)]
            :when (and cal (not (contains? (set (schema/entry-keys (:schema tr))) cal)))]
      (fail target (str ":calendar names " (pr-str cal) ", a field " (name target) " does not have")))
    (doseq [[kind r] (sort-by key kinds)
            gname (sort (keys (:explain r)))
            :when (and (not (evidence/derives? r gname))
                       (not-any? #(and (= kind (:kind %))
                                  (contains? (cond-> #{(guard-name (:resource %) "is-free")
                                                       (guard-name (:resource %) "is-working")}
                                               (seq (:bumps %))
                                               (conj (guard-name (:resource %) "has-a-free-day")))
                                             gname))
                            entries))]
      (fail kind (str ":explain names " (pr-str gname) ", a guard no :occupies or :evidence entry of "
                      (name kind) " derives")))
    (reduce (fn [ks o]
              (let [occs (get by-target (:target o))
                    ks (reduce (fn [ks door]
                                 (-> ks
                                     (update (:kind o) add-guard door (free-guard kinds o door occs))
                                     (update (:kind o) add-guard door (working-guard kinds o door occs))))
                               ks
                               (sort (:doors o)))]
                ;; a bump the kind has no door for is [bumps-not-a-door]'s;
                ;; a handler already there is this derivation's own
                (reduce (fn [ks door]
                          (if (contains? (get-in ks [(:kind o) :actions]) door)
                            (-> ks
                                (update-in [(:kind o) :actions door :handler]
                                           #(or % (bump-handler kinds o door occs)))
                                (update (:kind o) add-guard door (free-day-guard kinds o door occs)))
                            ks))
                        ks
                        (sort (:bumps o)))))
            kinds
            entries)))
