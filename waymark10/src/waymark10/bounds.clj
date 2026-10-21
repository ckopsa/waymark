(ns waymark10.bounds
  "Ledger step 5 (docs/spec-ledger-money.md §5): the guards an
  account's bounds write. `:never-below b` gives every door whose
  :posts can take from the account a guard that its balance after the
  door stays at or above b; `:never-above` — a number, or a sibling
  account on the same row — gives every door that can add to it a
  guard that the balance stays at or below. A door's entries to one
  account add up first, so two lines of one product are judged as one.
  Each guard reads (balance …), which the write answers under the
  row's lock, so two doors judged at once for the last mug are judged
  one after the other, and the refusal says the account and the gap.

  The guards are trees the registry writes before it fingerprints, as
  :occupies writes its own, so an author's hand-written twin hashes the
  same. Entries under a :when add up only with the others under that
  same :when. A house account carries no balance row yet, so its
  bounds write no guard."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]))

(set! *warn-on-reflection* true)

(defn- words [k] (str/replace (name k) "_" " "))

(defn- total
  "The sum of the forms `xs`, as one form."
  [xs]
  (if (= 1 (count xs)) (first xs) (cons '+ xs)))

(defn- shown
  "A form as a sentence's hole reads it: money as its amount and code."
  [unit form]
  (if (vector? unit)
    (list 'str (list 'amount form) " " (list 'currency form))
    form))

(defn- literal
  "Bound b as a law literal of the account's unit."
  [unit b]
  (if (vector? unit) (list 'money (str b) (second unit)) b))

(defn- signed [es sign] (map :amount (filter #(= sign (:sign %)) es)))

(defn- after
  "The account's balance once the entries `es` land: `now`, plus what
  they add, less what they take."
  [now es]
  (let [adds (signed es :+)
        takes (signed es :-)
        up (if (seq adds) (total (cons now adds)) now)]
    (if (seq takes) (list '- up (total takes)) up)))

(defn- under
  "The law, held only where the entries' :when holds."
  [w law]
  (if (some? w) (list 'or (list 'not w) law) law))

(defn- below-guard
  "`:never-below`: the balance after the door stays at or above it."
  [gname kind [k row n] acct w es]
  (let [unit (:unit acct)
        b (:never-below acct)
        now (list 'balance [k row n])
        bound (if (keyword? b) (list 'balance [k row b]) (literal unit b))
        none? (and (number? b) (zero? b))]
    (g/expr {:name gname
             :severity :refuse
             :explain (if none?
                        (str "Only {balance} " (words n) " on that " (words k)
                             "; this " (words kind) " takes {takes}.")
                        (str "That " (words k) "'s " (words n) " never goes below {bound}: "
                             "it holds {balance}, and this " (words kind) " takes {takes}."))
             :when (under w (list '<= bound (after now es)))
             :vars (cond-> {:balance (shown unit now)
                            :takes (shown unit (total (signed es :-)))}
                     (not none?) (assoc :bound (shown unit bound)))})))

(defn- above-guard
  "`:never-above`: the balance after the door stays at or below it."
  [gname [k row n] acct w es]
  (let [unit (:unit acct)
        c (:never-above acct)
        now (list 'balance [k row n])
        cap (if (keyword? c) (list 'balance [k row c]) (literal unit c))]
    (g/expr {:name gname
             :severity :refuse
             :explain (if (keyword? c)
                        (str "{cap} was " (words c) "; {balance} is already " (words n)
                             ", so at most {room} more.")
                        (str (str/capitalize (words n)) " is at most {cap}; {balance} is already "
                             (words n) ", so at most {room} more."))
             :when (under w (list '<= (after now es) cap))
             :vars {:cap (shown unit cap)
                    :balance (shown unit now)
                    :room (shown unit (list '- cap now))}})))

(defn- door-guards
  "The guards door `a` of `kind` gains from the bounds its :posts could
  break: one per account and :when its entries move, named for the
  account, a second address of one account numbered on."
  [kinds kind a]
  (let [specs (for [[[[k _ n :as address] w] es]
                    (sort-by (comp pr-str key)
                             (group-by (juxt :account :when)
                                       (filter #(= 3 (count (:account %))) (:posts a))))
                    :let [acct (get-in kinds [k :accounts n])
                          signs (set (map :sign es))
                          base (str (name k) "." (name n))]
                    spec [(when (and (contains? acct :never-below) (contains? signs :-))
                            [(str base "-never-below") #(below-guard % kind address acct w es)])
                          (when (and (contains? acct :never-above) (contains? signs :+))
                            [(str base "-never-above") #(above-guard % address acct w es)])]
                    :when spec]
                spec)]
    (first (reduce (fn [[out seen] [base mk]]
                     (let [i (get seen base 0)]
                       [(conj out (mk (keyword (if (zero? i) base (str base "-" (inc i))))))
                        (assoc seen base (inc i))]))
                   [[] {}]
                   specs))))

(defn derive-kinds
  "`kinds` ({kind rdef}) with every posting door's bound guards
  appended — the registry's step before it fingerprints. A guard
  already there by name stays as it is, so deriving twice is deriving
  once."
  [kinds]
  (reduce (fn [ks [kind door a]]
            (reduce (fn [ks gd]
                      (update-in ks [kind :actions door :guards]
                                 (fn [gs]
                                   (let [gs (vec gs)]
                                     (if (some #(= (:name gd) (:name %)) gs) gs (conj gs gd))))))
                    ks
                    (door-guards kinds kind a)))
          kinds
          (for [[kind r] (sort-by key kinds)
                [door a] (sort-by key (:actions r))
                :when (seq (:posts a))]
            [kind door a])))
