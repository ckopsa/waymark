(ns waymark10.server.invoke.reads
  "What a law may read beyond its own row (moved out of
  waymark10.server.invoke, ticket 2d02c8ab): account balances and the
  postings of a period (docs/spec-ledger-money.md), rates in effect,
  related-row conditions, the render probe's read hooks and the
  write's own calendar hook. make-ctx in waymark10.server.invoke lends
  these to every write's ctx; the read path takes `render-hooks`."
  (:require [clojure.string :as str]
            [waymark10.money :as money]
            [waymark10.retention :as retention]
            [waymark10.schema :as schema]
            [waymark10.server.invoke.rows :refer [decode-row resources]]
            [waymark10.server.store :as store]
            [waymark10.server.work-calendar :as work-calendar]))

(set! *warn-on-reflection* true)

(declare balance-over)

(defn balance-in
  "An account's balance as its row carries it (docs/spec-ledger-money.md
  §4): nil — unknown to the law — for an account no kind declares, for a
  house account, whose balance no row carries yet, and for a row that is
  not there. An :on-read account's row carries none, so its balance is
  its postings' sum (docs/spec-spend.md §2). `opts` is load-row's:
  {:for-update true} for the write's locked read."
  [engine tx account-kind id account-name opts]
  (when-some [trdef (get (resources engine) account-kind)]
    (when-some [acct (get-in trdef [:accounts account-name])]
      (if (:on-read acct)
        (balance-over engine tx account-kind id account-name nil nil)
        (when (some? id)
          (when-some [raw (store/load-row (:storage engine) tx account-kind (str id) opts)]
            (let [v (get-in (decode-row trdef raw) [:data account-name])]
              (if (some? v) v (money/zero (:unit acct))))))))))

(defn period-conds
  "The conds of one account's postings effective on or after `from` and
  before `until`: with `id` :all, the account on every row of the kind,
  matched by account_kind and account_name; with `from` and `until`
  nil, whenever effective."
  [account-kind id account-name from until]
  (cond-> (if (= :all id)
             [{:target :data :field :account_kind :op := :value (name account-kind)}
              {:target :data :field :account_name :op := :value (name account-name)}]
             [{:target :data :field :account :op :=
               :value (str/join "/" (map name (remove nil? [account-kind (some-> id str) account-name])))}])
     from (conj {:target :data :field :effective_on :cast "date" :op :>= :value (str from)})
     until (conj {:target :data :field :effective_on :cast "date" :op :< :value (str until)})))

(defn period-postings
  "The posting rows of `period-conds`. `opts` is search-rows' own, so
  it names :limit or :all — and no sum reads these; sum-amounts does."
  [engine tx account-kind id account-name from until opts]
  (store/search-rows (:storage engine) tx :posting
                     (period-conds account-kind id account-name from until)
                     opts))

(defn amount-sums
  "store/sum-amounts' answer as the values a balance in `unit` adds:
  each currency's sum as Money, :number's as the unit's number (a
  :count a long). Folded with money/plus, a second currency or money
  in a numeric account refuses as it always has."
  [unit sums]
  (keep (fn [[k s]]
          (if (= :number k)
            (if (= :count unit) (long s) (bigdec s))
            (money/money (bigdec s) k)))
        sums))

(defn balance-over
  "What an account's postings effective on or after `from` and before
  `until` add (docs/spec-ledger-money.md §7, ledger step 9b), read from
  the posting rows themselves — so a house account, whose balance no row
  carries, has an answer too. A period no posting falls in is zero in
  the account's unit: live, nothing moved. nil — unknown to the law —
  for an account no kind or house declares, a row account with no row
  named, and an engine that serves no posting kind. The store sums
  every posting of the period (store/sum-amounts: one SUM per
  currency, no row limit — search-rows' page of 100 once dropped the
  rest), and the sums are added here. With `id` :all (step 9c) it is the account on every row of the
  kind, the postings matched by account_kind and account_name; with
  `from` and `until` nil, every posting, whenever effective."
  [engine tx account-kind id account-name from until]
  (let [house? (= :house (keyword account-kind))
        unit (if house?
               (get-in engine [:house-accounts (keyword account-name) :unit])
               (get-in (resources engine) [account-kind :accounts account-name :unit]))]
    (when (and unit (get (resources engine) :posting) (or house? (some? id)))
      (reduce money/plus
              (money/zero unit)
              (amount-sums unit
                           (store/sum-amounts (:storage engine) tx :posting :amount
                                              (period-conds account-kind id account-name
                                                            from until)))))))

(defn postings-over
  "An account's `limit` postings effective in [from, until) with the
  largest amounts, largest first (docs/spec-ledger-money.md §7, ledger
  step 9e): the evidence a close's refusal shows, each with the kind
  and id of the row whose door posted it. An amount is money, stored as
  an object, so the store orders by its magnitude (search-rows'
  :magnitude) and reads only `limit` rows, not the whole period."
  [engine tx account-kind id account-name from until limit]
  (when (and (get (resources engine) :posting)
             (or (= :house (keyword account-kind)) (some? id)))
    (->> (period-postings engine tx account-kind id account-name from until
                          {:order-by :amount :magnitude true :desc true :limit limit})
         (map #(update (:data %) :amount money/from-wire nil))
         (filter (comp money/money? :amount))
         vec)))

(declare rate-on)

(defn read-hooks
  "The render probe's :read/:find/:sum — make-ctx's enforcement hooks
  twinned for the READ path, each call over a short transaction of
  its own. An engine booted with :probe-reads true hands one instance
  of these to every render ctx (router/render-opts), so a
  cross-resource acceptance set enumerates as the picker enum on the
  envelope (discovery = the advertised set, waymark-1pq) and a
  cross-row code guard tells its real verdict instead of advertising
  optimistically. A per-instance cache (one instance per request)
  keeps the repeated probes — the same plan read by every action's
  gate — to one query each. The invoke-side :render-fn takes none of
  THESE: it runs inside the write's own transaction (engine
  docstring), so it is lent that write's own ctx :read/:find instead
  — its :computed fields read what the write left (ticket 82589f6e) —
  and its doors still advertise optimistically; the follow-up GET
  tells the folded truth."
  [engine]
  (let [st (:storage engine)
        cache (atom {})
        through (fn [k thunk]
                  (if-some [e (find @cache k)]
                    (val e)
                    (let [v (thunk)]
                      (swap! cache assoc k v)
                      v)))]
    {:read (fn [target-kind id]
             (through
              [:read target-kind (str id)]
              #(when-some [trdef (get (resources engine) target-kind)]
                 (some->> (store/with-tx
                           st (fn [tx] (store/load-row st tx target-kind
                                                       (str id) {})))
                          (decode-row trdef)))))
     :find (fn [target-kind where opts]
             (through
              [:find target-kind where opts]
              #(when-some [trdef (get (resources engine) target-kind)]
                 (mapv (partial decode-row trdef)
                       (store/with-tx
                        st (fn [tx] (store/query-rows
                                     st tx target-kind (or where {})
                                     (or opts {}))))))))
     ;; the aggregate twin (waymark-fp62.7.13): the seat's fire door
     ;; judges the week's fuel, and an envelope that could not ask for
     ;; the sum would narrate a wall that has already lifted. Memoed
     ;; like the rest — one page, one seat, one SUM.
     :sum (fn [target-kind field conds]
            (through
             [:sum target-kind field conds]
             #(when (get (resources engine) target-kind)
                (store/with-tx
                 st (fn [tx] (store/sum-matching st tx target-kind
                                                 field conds))))))
     ;; …and its ledger twin (docs/spec-spend.md §5): the seat's week
     ;; is a sum of money postings, grouped by unit, not a numeric field
     :sum-amounts (fn [target-kind field conds]
                    (through
                     [:sum-amounts target-kind field conds]
                     #(when (get (resources engine) target-kind)
                        (store/with-tx
                         st (fn [tx] (store/sum-amounts st tx target-kind
                                                        field conds))))))
     ;; the ledger's read-path twins (docs/spec-ledger-money.md §9): a
     ;; balance as its row carries it, unlocked, and the rate in effect,
     ;; so a door a bound or a rate decides is judged on the probe and in
     ;; the sweep instead of pending
     :balance (fn [account-kind id account-name]
                (through
                 [:balance account-kind (str id) account-name]
                 #(store/with-tx
                   st (fn [tx] (if (= :all id)
                                 (balance-over engine tx account-kind id account-name nil nil)
                                 (balance-in engine tx account-kind id account-name {}))))))
     :balance-over (fn [account-kind id account-name from until]
                     (through
                      [:balance-over account-kind (some-> id str) account-name (str from) (str until)]
                      #(store/with-tx
                        st (fn [tx] (balance-over engine tx account-kind id account-name from until)))))
     ;; a consumer's cursor by name: the seat's `fired_by` reads where
     ;; each subscription that fires it stands (docs/spec-events.md E-4.4)
     :cursor (fn [c]
               (through [:cursor (str c)]
                        #(store/with-tx st (fn [tx] (store/cursor-get st tx (str c))))))
     :postings-over (fn [account-kind id account-name from until limit]
                      (through
                       [:postings-over account-kind (some-> id str) account-name (str from) (str until) limit]
                       #(store/with-tx
                         st (fn [tx] (postings-over engine tx account-kind id account-name
                                                    from until limit)))))
     :rate-of (fn [table key-vals on]
                (through
                 [:rate-of table key-vals (str on)]
                 #(store/with-tx st (fn [tx] (rate-on engine tx table key-vals on)))))
     ;; the log's read-path twin (docs/spec-undo.md). An undo door is
     ;; the one affordance whose availability is a fact about the CLOCK
     ;; and about whose hand is asking, so a card that advertised it
     ;; optimistically would offer a way back that is already gone. The
     ;; memo matters here more than anywhere: a feed page renders many
     ;; rows and each would otherwise cost its own query.
     :last-transition
     (fn [row]
       (through
        [:last-transition (:kind row) (str (:id row))]
        #(first (store/with-tx
                 st (fn [tx] (store/transitions
                              st tx {:kind (:kind row) :resource-id (:id row)}
                              {:newest-first true :limit 1}))))))
     ;; …and the day a row last entered a state, which a :keep that
     ;; counts from {:state s} reads (docs/spec-documents.md §4)
     :entered-at
     (fn [row state]
       (through
        [:entered-at (:kind row) (str (:id row)) (name state)]
        #(retention/entered
          (store/with-tx
           st (fn [tx] (store/transitions
                        st tx {:kind (:kind row) :resource-id (:id row)}
                        {:newest-first true})))
          state)))}))

(defn render-hooks
  "`read-hooks`, and the `:calendar` hook (docs/spec-calendar-time.md
  §3) over their own :read and :find: the render probe and the
  law-sweep name the governing work_calendar from the same memo the
  rest of their reads ride. The hook carries that :read as its metadata,
  so an age's :until can follow a ref (`live-cracks/deadline`)."
  [engine]
  (let [hooks (read-hooks engine)]
    ;; no work_calendar served (switched off by :modules-off): no
    ;; calendar governs, the readers answer unknown, and the boot's
    ;; ensure-default-calendar! seeds none
    (assoc hooks :calendar (when (contains? (resources engine) work-calendar/kind)
                             (some-> (work-calendar/hook (:read hooks) (:find hooks))
                                     (with-meta {:read (:read hooks)}))))))

(defn law-conds
  "An exists/none/count-rows where as the store's conds
  (docs/spec-related-row-law.md §3): :state and :id are the row's own
  columns, any other field a data field cast as the target's schema
  types it — the condition list a :related edge hands ids-matching."
  [trdef conds]
  (let [text #(if (keyword? %) (name %) (str %))
        cast (fn [field]
               (let [s (schema/field-schema (:schema trdef) field)
                     s (if (and (vector? s) (= :maybe (first s))) (second s) s)]
                 (case (if (vector? s) (first s) s)
                   :waymark/date "date"
                   :waymark/instant "timestamptz"
                   :boolean "boolean"
                   :int "bigint"
                   (:double :decimal) "numeric"
                   "text")))]
    (mapv (fn [{:keys [field op value values]}]
            (cond-> (case field
                      :state {:target :state :op op}
                      :id {:target :id :op op}
                      {:target :data :field (name field) :cast (cast field) :op op})
              (= :in op) (assoc :values (mapv text values))
              (not= :in op) (assoc :value (text value))))
          conds)))

(defn rate-on
  "(rate …)'s read (docs/spec-ledger-money.md §8, ledger step 7): the
  :value of the row of the kind declaring `table` whose :key fields
  carry `key-vals` and whose :effective is the latest on or before
  `on`. nil where no row is, where no kind declares the table, or where
  the key names other fields than the table's."
  [engine tx table key-vals on]
  (when-some [trdef (some #(when (= table (get-in % [:rate-table :name])) %)
                          (vals (resources engine)))]
    (let [{ks :key value :value eff :effective} (:rate-table trdef)]
      (when (= (set ks) (set (keys key-vals)))
        (let [conds (law-conds trdef (conj (mapv (fn [k] {:field k :op := :value (get key-vals k)}) ks)
                                           {:field eff :op :<= :value (str on)}))
              [hit] (store/search-rows (:storage engine) tx (:kind trdef) conds
                                       {:order-by eff :desc true :limit 1})]
          (when hit (get-in (decode-row trdef hit) [:data value])))))))

(defn tx-calendar
  "The `:calendar` hook (docs/spec-calendar-time.md §3) over the
  write's own transaction, as make-ctx's :read and :find read it: a
  holiday added in this transaction moves this write's verdict."
  [engine tx]
  (work-calendar/hook
   (fn [target-kind id]
     (when-some [trdef (get (resources engine) target-kind)]
       (some->> (store/load-row (:storage engine) tx target-kind (str id) {})
                (decode-row trdef))))
   (fn [target-kind where opts]
     (when-some [trdef (get (resources engine) target-kind)]
       (mapv #(decode-row trdef %)
             (store/query-rows (:storage engine) tx target-kind
                               (or where {}) (or opts {})))))))
