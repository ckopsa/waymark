(ns waymark10.posts
  "Ledger step 4 (docs/spec-ledger-money.md §4): the `:posts` a door
  declares, read at the def site and evaluated as the door lands.

  An entry is {:account [kind row name] :amount form :sign :+|:-}, with
  an optional :when form that makes it conditional; [:house name]
  addresses a house account. The row part of an address and the amount
  are expressions over the door's own scope — (self), (data …),
  (input …). `{:move form :from [..] :to [..]}` normalizes to the two
  entries it means, so the two spellings are one law: what leaves one
  account arrives in another.

  `:each coll` repeats an entry once per element of coll, usually
  (lines), each read there as (line …) (ledger step 6b). `:on` arrives
  with the governing calendar (step 9); until then it is refused by
  name."
  (:require [clojure.string :as str]
            [waymark10.expr :as expr]
            [waymark10.money :as money]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def entry-keys #{:account :amount :sign :when :each})

(def move-keys #{:move :from :to :when :each})

(defn- account-problem
  "What is wrong with an entry's :account, or nil; `depth` is 1 inside
  an :each entry, whose row part may read (line …)."
  [acct depth]
  (cond
    (and (vector? acct) (= 2 (count acct)) (= :house (first acct)) (keyword? (second acct)))
    nil
    (not (and (vector? acct) (= 3 (count acct))
              (keyword? (first acct)) (keyword? (nth acct 2))))
    (str ":account is [kind row name] — a kind, an expression for its row such as"
         " (self) or (data :product), and an account name — or [:house name]; got "
         (pr-str acct))
    (seq (expr/problems (second acct) depth))
    (str ":account row " (first (expr/problems (second acct) depth)))))

(defn- form-problem [k e depth]
  (cond
    (not (contains? e k)) (str "entry names its " k)
    (seq (expr/problems (get e k) depth)) (str k " " (first (expr/problems (get e k) depth)))))

(defn normalize
  "An action's :posts → its canonical entries, each {:account :amount
  :sign} plus :when when it is conditional; a :move becomes its two
  entries, :from's :- then :to's :+. Idempotent. A malformed entry is a
  definition error naming the door."
  [kind aname posts]
  (let [bad #(throw (t/definition-error
                     (str (name kind) "/" (name aname) " :posts: " %)
                     {:check :posts}))]
    (when-not (sequential? posts)
      (bad "is a vector of entries"))
    (vec
     (mapcat
      (fn [e]
        (when-not (map? e) (bad (str "an entry is a map; got " (pr-str e))))
        (when (contains? e :on)
          (bad ":on dates an entry in the governing calendar and arrives with periods (§14, step 9)"))
        (let [move? (contains? e :move)
              allowed (if move? move-keys entry-keys)
              ;; an :each entry's forms stand inside its repetition,
              ;; where (line …) is bound
              depth (if (contains? e :each) 1 0)]
          (when-some [extra (seq (remove allowed (keys e)))]
            (bad (str "an entry names " (vec (sort extra)) "; it takes " (vec (sort allowed)))))
          (some-> (form-problem (if move? :move :amount) e depth) bad)
          (when (contains? e :when) (some-> (form-problem :when e depth) bad))
          (when (contains? e :each) (some-> (form-problem :each e 0) bad))
          (let [conditional (cond-> {}
                              (contains? e :when) (assoc :when (expr/normalize (:when e)))
                              (contains? e :each) (assoc :each (expr/normalize (:each e))))
                entry (fn [acct amount sign]
                        (some-> (account-problem acct depth) bad)
                        (merge {:account (if (= 3 (count acct))
                                           (update acct 1 expr/normalize)
                                           acct)
                                :amount (expr/normalize amount)
                                :sign sign}
                               conditional))]
            (if move?
              [(entry (:from e) (:move e) :-) (entry (:to e) (:move e) :+)]
              (do (when-not (contains? #{:+ :-} (:sign e))
                    (bad (str ":sign is :+ or :-; got " (pr-str (:sign e)))))
                  [(entry (:account e) (:amount e) (:sign e))])))))
      posts))))

(defn- negative? [a]
  (if (money/money? a)
    (neg? (.signum ^java.math.BigDecimal (:amount a)))
    (neg? a)))

(defn negate [a]
  (if (money/money? a)
    (money/->Money (.negate ^java.math.BigDecimal (:amount a)) (:currency a))
    (- a)))

(defn entries
  "The postings canonical `posts` write over `scope` — {:data (the row as
  the door leaves it) :input :now :self :lines} — as postings/post! takes
  them: {:account-kind :account-row :account-name :amount}, the amount
  signed. An entry with :each writes once per element of its
  collection, in order, each element read as (line …); an :each that is
  unknown or not a list refuses. An entry whose :when is false writes
  nothing. An amount that is
  unknown, not a number or Money, or negative — :sign says the
  direction, and a sign hidden in data is how a refund becomes a charge
  — refuses, as does a :when that is unknown or a row part that names
  no row: ex-info with :posts-refused and the sentence."
  [posts scope]
  (let [refuse #(throw (ex-info % {:posts-refused true}))]
    (vec
     (for [{:keys [account amount sign] :as e} posts
           :let [[k r n] (if (= 2 (count account))
                           [(first account) nil (second account)]
                           account)]
           scope (if (contains? e :each)
                   (let [xs (expr/evaluate (:each e) scope)]
                     (cond
                       (expr/unknown? xs)
                       (refuse (str "the lines the entry to " (name n) " posts per are unknown"))
                       (and (some? xs) (not (sequential? xs)))
                       (refuse (str "the entry to " (name n) " posts per element of a list; its :each is not one"))
                       :else (map #(update scope :its conj %) xs)))
                   [scope])
           :let [holds (if (contains? e :when) (expr/evaluate (:when e) scope) true)]
           :when (do (when (expr/unknown? holds)
                       (refuse (str "whether the entry to " (name n) " posts is unknown")))
                     (true? holds))]
       (let [row (when r (expr/evaluate r scope))
             a (expr/evaluate amount scope)]
         (when (and r (or (nil? row) (expr/unknown? row) (str/blank? (str row))))
           (refuse (str "the entry to " (name n) " names no " (name k) " row")))
         (cond
           (or (nil? a) (expr/unknown? a))
           (refuse (str "the amount for " (name n) " is unknown"))
           (not (or (money/money? a) (number? a)))
           (refuse (str "the amount for " (name n) " is not a number or money"))
           (negative? a)
           (refuse (str "the amount for " (name n) " is negative; the entry's :sign says the direction")))
         {:account-kind k
          :account-row (when r (str row))
          :account-name n
          :amount (if (= :- sign) (negate a) a)})))))
