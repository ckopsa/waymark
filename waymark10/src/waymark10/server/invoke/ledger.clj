(ns waymark10.server.invoke.ledger
  "What a write posts (moved out of waymark10.server.invoke, ticket
  2d02c8ab; docs/spec-ledger-money.md): a door's (lines), the entries
  a landing door or a create posts, the row's own balances moved by
  them, and the [period-closed] wall a close row raises over an
  account's period. Each runs inside the write's own transaction."
  (:require [clojure.string :as str]
            [waymark10.expr :as expr]
            [waymark10.money :as money]
            [waymark10.posts :as posts]
            [waymark10.server.invoke.labels :refer [summary-of]]
            [waymark10.server.invoke.rows :refer [decode-row resources]]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(defn stored-lines
  "The line rows one parent holds now, as their data in place order —
  what (lines) reads at a door that does not take `lines` (ledger step
  6b, docs/spec-ledger-money.md §6)."
  [engine tx rdef parent-id]
  (let [{child :kind via :parent} (:lines rdef)
        crdef (get (resources engine) child)]
    (mapv #(:data (if crdef (decode-row crdef %) %))
          (store/search-rows (:storage engine) tx child
                             [{:target :data :field via :op := :value (str parent-id)}]
                             {:order-by :n :all true}))))

(defn door-lines
  "(lines) at a door: the input's when the door takes `lines`, the
  stored set otherwise."
  [engine tx rdef row inp]
  (if (and (map? inp) (contains? inp :lines))
    (vec (:lines inp))
    (stored-lines engine tx rdef (:id row))))

(defn posting-entries
  "The entries a landing door posts (ledger step 4): its :posts over the
  row as it leaves it, then — when it is the :undo of a posting door
  that made the row's last move — that move's postings, reversed. An
  entry that cannot be written refuses the door."
  [engine tx rdef row defn inp ctx materialized]
  (vec
   (concat
    (when-some [ps (seq (:posts defn))]
      (try (posts/entries ps (cond-> {:data (:data materialized) :input inp
                                      :now (:now ctx) :self (str (:id row))
                                      :calendar (:calendar ctx)}
                               (:lines rdef) (assoc :lines (door-lines engine tx rdef row inp))))
           (catch clojure.lang.ExceptionInfo e
             (if (:posts-refused (ex-data e))
               (throw (p/schema-invalid (:name defn) {:posts [(ex-message e)]}))
               (throw e)))))
    (when-some [undoes (not-empty (:reverses-posts defn))]
      (let [[latest] (store/transitions (:storage engine) tx
                                        {:kind (:kind rdef) :resource-id (:id row)}
                                        {:newest-first true :limit 1})]
        (when (and latest (contains? undoes (keyword (name (:action latest)))))
          ((requiring-resolve 'waymark10.server.postings/reversals)
           engine tx (:id latest))))))))

(defn own-balances
  "`data` with this row's own accounts moved by `entries`, so the row a
  posting door saves already holds its balance; `own` is [kind id]."
  [data own entries]
  (reduce (fn [d {:keys [account-kind account-row account-name amount]}]
            (if (= own [(name account-kind) (str account-row)])
              (update d (keyword (name account-name))
                      #(if (some? %) (money/plus % amount) amount))
              d))
          data entries))

(defn house-zone
  "The zone of the calendar that governs the write (ledger step 9a), as
  ctx's :calendar hook answers it — nil when nothing governs."
  [ctx]
  (when-some [calendar-of (:calendar ctx)]
    (:zone (calendar-of))))

(defn as-day
  "A period bound or a posting's date as a LocalDate, or nil."
  [v]
  (cond (instance? java.time.LocalDate v) v
        (string? v) (try (java.time.LocalDate/parse ^String v)
                         (catch java.time.format.DateTimeParseException _ nil))))

(defn close-period
  "A close row's period [start end), read off its kind's :reconciles
  :period over the row's data — nil while either bound is unset."
  [crdef data]
  (let [[s e] (map (fn [f]
                     (let [v (expr/evaluate (expr/normalize f) {:data data})]
                       (when-not (expr/unknown? v) (as-day v))))
                   (get-in crdef [:reconciles :period]))]
    (when (and s e) [s e])))

(defn close-covers?
  "Whether a close kind's :closes names the entry's account (docs/
  spec-ledger-money.md §7): [:house n] that house account, [:all kind n]
  account n on every row of the kind, [kind row n] that row's, its row
  read off the close's data."
  [closes data {:keys [account-kind account-row account-name]}]
  (boolean
   (some (fn [addr]
           (let [[k r n] (if (= 2 (count addr)) [(first addr) nil (second addr)] addr)]
             (and (= (name k) (name account-kind))
                  (= (name n) (name account-name))
                  (cond (nil? r) (nil? account-row)
                        (= :all r) (some? account-row)
                        :else (let [v (if (seq? r) (expr/evaluate (expr/normalize r) {:data data}) r)]
                                (and (some? v) (not (expr/unknown? v))
                                     (= (str v) (str account-row))))))))
         closes)))

(defn closed-period-hit
  "The first closed period one of `entries` lands in, as [sentence
  evidence], or nil: the read behind [period-closed] (see
  refuse-closed-periods!). `lock?` locks each close row before its
  state is read — the write's check; a dry run's rehearsal only reads."
  [engine tx entries ctx lock?]
  (when (seq entries)
    (let [^java.time.ZoneId zone (or (house-zone ctx) java.time.ZoneOffset/UTC)
          today (java.time.LocalDate/ofInstant (java.time.Instant/now) zone)
          found (volatile! nil)]
      (doseq [[ckind crdef] (sort-by key (resources engine))
              :while (nil? @found)
              :let [landed (get-in crdef [:actions :close :to])]
              :when (and landed (seq (:closes crdef)))
              :let [hits (for [raw (store/search-rows (:storage engine) tx ckind [] {:all true})
                               :let [c (decode-row crdef raw)
                                     data (into {} (map (fn [[k v]] [(keyword (name k)) v])) (:data c))
                                     [s e] (close-period crdef data)]
                               :when s
                               en entries
                               :let [on (or (as-day (:effective-on en)) today)]
                               :when (and (not (neg? (compare on s))) (neg? (compare on e))
                                          (close-covers? (:closes crdef) data en))]
                           [(str (:id c)) en on s e])]
              [id hs] (sort-by key (group-by first hits))
              :while (nil? @found)]
        (when-some [locked (store/load-row (:storage engine) tx ckind id {:for-update lock?})]
          (when (= (name landed) (name (:state locked)))
            (let [[_ {:keys [account-kind account-row account-name]} on s e] (first hs)
                  addr (str/join "/" (map name (remove nil? [account-kind account-row account-name])))]
              (vreset! found
                       [(str "The period from " s " until " e " of " addr " is closed by "
                             (name ckind) " " id ": a posting effective " on
                             " cannot land in it. Post it to the open period, effective on or after " e ".")
                        {:close {:kind (name ckind) :id id}
                         :account addr
                         :effective_on (str on)
                         :period {:from (str s) :until (str e)}}])))))
      @found)))

(defn refuse-closed-periods!
  "[period-closed] (docs/spec-ledger-money.md §7, ledger step 9f): no
  entry — a door's :posts or an undo's reversal — lands inside a closed
  period of an account the close covers. A period is closed while a row
  of a :reconciles kind sits in the state its `close` door lands in;
  leaving that state through a reopen door opens it again. Every close
  row whose period holds an entry's date and whose :closes names its
  account is locked, kinds and ids in order, before its state is read,
  so a posting and a close judged at once are decided one after the
  other: the close sums the posting, or the posting finds the period
  closed. An entry with no :effective-on is dated as post! dates it."
  [engine tx rdef row defn entries ctx]
  (when-some [[sentence evidence] (closed-period-hit engine tx entries ctx true)]
    (throw (p/guard-refused
            (:name defn) (:state row) sentence
            {:guard :period-closed
             :remedies []
             :evidence evidence}
            {:kind (:kind rdef) :id (:id row)
             :summary (summary-of rdef row (:now ctx))}))))

(defn closed-period-problem
  "[period-closed] rehearsed (ticket 72899cab): the sentence land! would
  refuse this door with, for its entries over the row as it stands,
  read without the locks — or nil. Entries the rehearsal cannot compute
  (a :posts reading what only the handler writes) are land!'s to judge."
  [engine tx rdef row defn inp ctx]
  (first (closed-period-hit engine tx
                            (try (posting-entries engine tx rdef row defn inp ctx row)
                                 (catch clojure.lang.ExceptionInfo _ nil))
                            ctx false)))

(defn create-entries
  "The entries a create posts (ledger step 4, at birth): its kind's
  :create-posts over the row as it is born — its data, the create's
  input, (lines) as the input carries them. An entry that cannot be
  written refuses the create, and so does [period-closed], before any
  row is inserted."
  [engine tx rdef row inp ctx action]
  (when-some [ps (seq (:create-posts rdef))]
    (let [entries (try (posts/entries ps (cond-> {:data (:data row) :input inp
                                                  :now (:now ctx) :self (str (:id row))
                                                  :calendar (:calendar ctx)}
                                           (:lines rdef) (assoc :lines (vec (:lines inp)))))
                       (catch clojure.lang.ExceptionInfo e
                         (if (:posts-refused (ex-data e))
                           (throw (p/schema-invalid action {:posts [(ex-message e)]}))
                           (throw e))))]
      (refuse-closed-periods! engine tx rdef row {:name action} entries ctx)
      entries)))
