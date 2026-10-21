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
  - `<resource>-is-working`: `working?` over the window, in the
    resource's own `:calendar` when its kind names one, else the
    default work_calendar.

  A door whose input does not carry a field reads it from the row,
  (data …). Derived at assembly, where every occupier of a resource
  kind is known; the trees fingerprint as any authored guard. A kind's
  `:explain {guard-name \"sentence\"}` replaces a derived guard's
  sentence."
  (:require [clojure.string :as str]
            [waymark10.declare :as d]
            [waymark10.schema :as schema]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def ^:private entry-keys #{:resource :from :to :in :doors :grain})

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
  (let [{:keys [resource from to in doors grain]} o
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
    {:kind kind :resource resource :target target :from from :to to
     :in in :doors doors :grain (or grain :instant)
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

(defn- free-guard [kinds o door occs]
  (let [[res from-side to-side] (sides kinds o door)
        bs (binders occs)
        tr (get kinds (:target o))
        live (sort (map name (remove (set (:terminal tr)) (:states tr))))
        gname (guard-name (:resource o) "is-free")
        word (resource-word (:resource o))
        sentence (or (get-in kinds [(:kind o) :explain gname])
                     (str/join " " (for [[p b] bs]
                                     (str "That " word " is held from {" b "." (name (:from p))
                                          "} to {" b "." (name (:to p)) "}."))))
        remedies (vec (for [[p b] bs
                            door (sort (:doors p))
                            :when (some (:in p) (get-in p [:froms door]))]
                        {:door (keyword (name (:kind p)) (name door)) :id (list 'evidence (keyword b))}))
        nones (fn [res-side]
                (all-of (for [[p b] bs]
                          (list 'none [b (:kind p) (overlap p (:grain o) res-side from-side to-side)]))))]
    (d/sentence-guard gname (d/refuse sentence) (nones res)
                      (cond-> {:evidence {:free (list 'first
                                                      ['candidate (:target o)
                                                       (if (seq live) {:state (cons 'in live)} {})]
                                                      (nones '(get candidate :id)))}}
                        (seq remedies) (assoc :remedies remedies)))))

(defn- working-guard [kinds o door]
  (let [[res from-side to-side] (sides kinds o door)
        cal (get-in kinds [(:target o) :calendar])
        gname (guard-name (:resource o) "is-working")]
    (d/sentence-guard gname
                      (d/refuse (or (get-in kinds [(:kind o) :explain gname])
                                    (str "That " (resource-word (:resource o)) " does not work then.")))
                      (apply list (cond-> ['working? from-side to-side]
                                    cal (conj (list 'follow (:target o) res cal)))))))

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
            :when (not-any? #(and (= kind (:kind %))
                                  (contains? #{(guard-name (:resource %) "is-free")
                                               (guard-name (:resource %) "is-working")}
                                             gname))
                            entries)]
      (fail kind (str ":explain names " (pr-str gname) ", a guard no :occupies entry of "
                      (name kind) " derives")))
    (reduce (fn [ks o]
              (reduce (fn [ks door]
                        (cond-> (update ks (:kind o) add-guard door
                                        (free-guard kinds o door (get by-target (:target o))))
                          (= :instant (:grain o))
                          (update (:kind o) add-guard door (working-guard kinds o door))))
                      ks
                      (sort (:doors o))))
            kinds
            entries)))
