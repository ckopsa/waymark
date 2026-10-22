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
  bounds write no guard.

  A close kind's :reconciles (§7, step 9e) writes the guard of its
  `close` door: its sides agree within the tolerance over the row's
  period. The refusal names both sides and the gap, and carries as
  evidence each side's five largest postings in the period, each naming
  the row whose door posted it."
  (:require [clojure.string :as str]
            [waymark10.expr :as expr]
            [waymark10.guards :as g]
            [waymark10.money :as money]
            [waymark10.resource :as r])
  (:import (java.time LocalDate)))

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
                                       ;; an :each entry's address reads its
                                       ;; line, which no door-level guard binds
                                       (filter #(and (= 3 (count (:account %)))
                                                     (not (contains? % :each)))
                                               (:posts a))))
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

;; ── :reconciles: the close door (step 9e) ─────────────────────────────

(def ^:private evidence-size
  "How many postings a side a close's refusal shows."
  5)

(defn- row-scope
  "The scope a period bound or an address's row part reads: the row's data."
  [row]
  {:data (into {} (map (fn [[k v]] [(keyword (name k)) v])) (:data row))})

(defn- day
  "Form `f` over `scope` as a date, or nil when it is none."
  [f scope]
  (let [v (expr/evaluate (expr/normalize f) scope)]
    (cond (instance? LocalDate v) v
          (string? v) (try (LocalDate/parse ^String v) (catch Exception _ nil)))))

(defn- account-of
  "A (balance …) address as the ledger hooks take it — [kind id name],
  id :all across the kind and nil for a house account — or nil when its
  row part names no row."
  [a scope]
  (cond (= 2 (count a)) [(first a) nil (second a)]
        (= :all (first a)) [(second a) :all (nth a 2)]
        :else (let [id (expr/evaluate (expr/normalize (second a)) scope)]
                (when (and (some? id) (not (expr/unknown? id)) (not= "" (str id)))
                  [(first a) (str id) (nth a 2)]))))

(defn- largest
  "Side `form`'s postings over the row's period with the largest
  amounts, largest first, at most evidence-size: what a close's refusal
  shows, each naming the row whose door posted it. nil with no
  :postings-over to ask (the probe) or no period set."
  [form [start end] row ctx]
  (let [scope (row-scope row)
        from (day start scope)
        until (day end scope)
        hook (:postings-over ctx)]
    (when (and hook from until)
      (->> (for [[k id n] (distinct (keep #(account-of (second %) scope) (r/balance-forms form)))
                 p (hook k id n from until evidence-size)]
             p)
           (sort-by (comp money/magnitude :amount) #(compare %2 %1))
           (take evidence-size)
           (mapv (fn [{:keys [account amount effective_on kind source_id]}]
                   (cond-> {:account account
                            :amount (if (money/money? amount) (money/->wire amount) amount)
                            :effective_on (str effective_on)}
                     source_id (assoc :source {:kind kind :id source_id}))))))))

(defn- close-guard
  "`:reconciles`: the close lands only when its sides agree within the
  tolerance — (<= (abs gap) tolerance), every balance over the row's
  period. With no ledger to ask, as on the probe, it is unknown, so the
  door is pending rather than available."
  [kind {:keys [period sides tolerance]}]
  (let [[[a fa] [b fb]] (seq sides)
        over #(r/over-period % period)
        gap (list '- (over fa) (over fb))
        unit (when (and (seq? tolerance) (= 'money (first tolerance)))
               [:money (nth tolerance 2)])]
    (g/expr {:name (keyword (str (name kind) ".gap-within-tolerance"))
             :severity :refuse
             :explain (str (str/capitalize (words a)) " is {" (name a) "} and " (words b)
                           " is {" (name b) "}: {gap} apart, and at most {tolerance} is allowed.")
             :when (list '<= (list 'abs gap) tolerance)
             :vars {a (shown unit (over fa))
                    b (shown unit (over fb))
                    :gap (shown unit (list 'abs gap))
                    :tolerance (shown unit tolerance)}
             :evidence {a #(largest fa period %1 %2)
                        b #(largest fb period %1 %2)}})))

(defn- with-guard
  "Guard `gd` appended to door `door` of `kind`, unless one by its name
  is already there."
  [ks kind door gd]
  (update-in ks [kind :actions door :guards]
             (fn [gs]
               (let [gs (vec gs)]
                 (if (some #(= (:name gd) (:name %)) gs) gs (conj gs gd))))))

(defn derive-kinds
  "`kinds` ({kind rdef}) with every posting door's bound guards
  appended, and every close kind's `close` door its reconciliation
  guard — the registry's step before it fingerprints. A guard already
  there by name stays as it is, so deriving twice is deriving once."
  [kinds]
  (let [ks (reduce (fn [ks [kind door a]]
                     (reduce #(with-guard %1 kind door %2) ks (door-guards kinds kind a)))
                   kinds
                   (for [[kind r] (sort-by key kinds)
                         [door a] (sort-by key (:actions r))
                         :when (seq (:posts a))]
                     [kind door a]))]
    (reduce (fn [ks [kind r]] (with-guard ks kind :close (close-guard kind (:reconciles r))))
            ks
            (for [[kind r] (sort-by key kinds)
                  :when (and (:reconciles r) (get-in r [:actions :close]))]
              [kind r]))))
