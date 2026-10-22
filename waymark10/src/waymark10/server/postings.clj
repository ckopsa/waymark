(ns waymark10.server.postings
  "Ledger step 3b (docs/spec-ledger-money.md §3-§4): a posting is an
  ordinary row of the engine-served kind `posting`, read-only to every
  principal. It has no edit door and its create door is walled to the
  engine's own hand, so the only way a posting is born is `post!`,
  inside the transaction that commits the posting door's transition
  and its log row (step 4 calls it). A mistake is a reversing posting,
  never an edit.

  The account, the amount, the effective date, the log id and the
  source kind are promoted fields, so a posting filters and sorts like
  any row: the generated columns and their btree indexes come from
  :filterable and :sortable, the way every kind's do.

  The kind is enrolled when some kind declares :accounts
  (waymark10.modules, :ledger). A house that declares only
  :house-accounts does not enrol it yet.

  Ledger step 3c: each row account's balance is a read-only field on
  its row (money/account-field), and `post!` moves it in the same
  transaction as the postings. The posting rows are the truth and the
  field their cache; `recomputed-balance` sums the truth, for step 10's
  [balance-drift] to compare. A house account has no row to carry its
  balance yet."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.money :as money]
            [waymark10.resource :refer [defresource]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Instant LocalDate ZoneId ZoneOffset)))

(set! *warn-on-reflection* true)

(def poster
  "The system actor that writes postings."
  (t/principal {:id "waymark10-ledger" :type :system :display "Ledger"}))

(g/defguard the-engine-posts-it
  {:reads [:principal]
   :hide true
   :explain "A posting is written by the engine, in the same transaction as the door that posts it; no hand at the wire creates or edits one."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(defn account-address
  "The one string an account is filtered by: `<kind>/<row>/<name>` for a
  row's account, `house/<name>` for a house account (row nil)."
  [account-kind account-row account-name]
  (str/join "/" (map name (remove nil? [account-kind account-row account-name]))))

(defresource posting
  {:kind :posting
   :plural "postings"
   :states [:posted]
   :initial :posted
   :terminal #{:posted}
   :nav :system
   :summary "{data.account} {data.amount.amount} {data.amount.currency} · {data.effective_on}"
   :schema
   [:map
    [:account {:not-a-ref "An account's address: kind, row and account name, not one row's id."
               :x-display {:raw true :label "Account"
                           :help "The account this posting moves: <kind>/<row>/<name>, or house/<name>."}}
     [:string {:min 1 :max 200}]]
    [:account_kind {:not-a-ref "A kind's name, or house."
                    :x-display {:raw true :label "Account's kind"
                                :help "The kind whose row owns the account, or house."}}
     [:string {:min 1 :max 64}]]
    [:account_row {:optional true
                   :not-a-ref "The owning row's id under account_kind, which varies by posting."
                   :x-display {:raw true :label "Account's row"
                               :help "The id of the row that owns the account. Absent for a house account."}}
     [:maybe [:string {:max 80}]]]
    [:account_name {:x-display {:raw true :label "Account name"
                                :help "The account's name as its kind declares it."}}
     [:string {:min 1 :max 64}]]
    [:amount {:x-display {:label "Amount"
                          :help "Signed: what this posting adds to the account, or takes from it."}}
     :waymark/money]
    [:at {:x-display {:label "Committed"
                      :help "The instant the posting's transaction committed."}}
     :waymark/instant]
    [:effective_on {:x-display {:label "Effective on"
                                :help "The date the posting counts on; periods read it."}}
     :waymark/date]
    [:kind {:x-display {:raw true :label "Posted by kind"
                        :help "The kind of the row whose door posted it."}}
     [:string {:min 1 :max 64}]]
    [:source_id {:not-a-ref "The posting row's id under `kind`, which varies by posting."
                 :x-display {:raw true :label "Posted by row"
                             :help "The id of the row whose door posted it."}}
     [:string {:min 1 :max 80}]]
    [:action {:x-display {:raw true :label "Door"
                          :help "The door that posted it."}}
     [:string {:min 1 :max 64}]]
    [:log_id {:not-a-ref "A transition's id in the log, not a row of a kind."
              :x-display {:raw true :label "Log row"
                          :help "The transition this posting committed with; a retried invoke never posts twice."}}
     [:string {:min 1 :max 80}]]
    [:reverses {:optional true
                :not-a-ref "A posting's id; a correction names the posting it reverses."
                :x-display {:raw true :label "Reverses"
                            :help "The posting this one reverses, when it is a correction."}}
     [:maybe [:string {:max 80}]]]]
   :create-guards [the-engine-posts-it]
   :filterable {:account #{:eq :in}
                :amount #{:range}
                :effective_on #{:eq :range}
                :log_id #{:eq}
                :kind #{:eq :in}}
   :sortable {:fields [:effective_on :amount] :default "effective_on"}
   :deviations
   ["§4 names a `posting` table beside the log; a posting is a row of an engine-served kind instead (owner, 2026-10-21), so it filters, sorts and grants like any row."
    "Each posting is inserted by the engine's quiet birth door (`inv/insert-quiet!`) with no transition of its own; the posting door's transition, named by log_id, is the write."]})

(defn- rebalance!
  "Move each row account's balance (step 3c) by its entries, inside
  `tx`: the owning row is locked, its field summed onto and saved a
  version on (inv/update-quiet!). Rows lock in one order, so two posts
  over the same rows wait for each other rather than deadlock. The row
  `settled` names, [kind id], already holds its move: the posting door
  saved it so (step 4)."
  [eng tx entries settled]
  (doseq [[[account-kind account-row] es]
          (sort-by (comp str key) (group-by (juxt :account-kind :account-row) entries))
          :when (and account-row
                     (not= settled [(name account-kind) (str account-row)]))]
    (when-not (inv/update-quiet!
               eng tx account-kind account-row
               (fn [data]
                 (reduce (fn [d {:keys [account-name amount]}]
                           (update d (keyword (name account-name))
                                   #(if (some? %) (money/plus % amount) amount)))
                         data es)))
      (throw (ex-info (str "no " (name account-kind) " " account-row " to post to")
                      {:account-kind account-kind :account-row account-row})))))

(defn recomputed-balance
  "An account's balance summed from its posting rows, nil when it has
  none: the truth its projected field caches (step 3c), and what step
  10's [balance-drift] compares that field with."
  [eng tx account-kind account-row account-name]
  (transduce (map #(money/from-wire (get-in % [:data :amount]) nil))
             (completing (fn [acc a] (if (some? acc) (money/plus acc a) a)))
             nil
             (store/search-rows (:storage eng) tx :posting
                                [{:target :data :field :account :op :=
                                  :value (account-address account-kind account-row account-name)}]
                                {})))

(defn- posted
  "The postings the transition `log-id` wrote, as stored."
  [eng tx log-id]
  (store/search-rows (:storage eng) tx :posting
                     [{:target :data :field :log_id :op := :value (str log-id)}]
                     {}))

(defn reversals
  "The entries that take back what the transition `log-id` posted
  (docs/spec-ledger-money.md §4, `undo`): each of its postings negated,
  :reverses naming it. Empty when it posted nothing. Step 5's bounds
  will judge them like any entry."
  [eng tx log-id]
  (mapv (fn [{:keys [id data]}]
          (let [m (money/from-wire (:amount data) nil)]
            {:account-kind (keyword (:account_kind data))
             :account-row (:account_row data)
             :account-name (keyword (:account_name data))
             ;; a :count or :decimal account's amount is a bare number
             :amount (if (money/money? m)
                       (money/->Money (.negate ^java.math.BigDecimal (:amount m)) (:currency m))
                       (- m))
             :reverses id}))
        (posted eng tx log-id)))

(defn post!
  "Write `entries` as postings inside `tx`, the transaction that commits
  the posting door's transition — so the postings, the row and the log
  row commit together or not at all. Each entry is {:account-kind
  :account-row (nil for a house account) :account-name :amount (a Money,
  signed) :effective-on (a LocalDate; when absent, the commit's date in
  the source's :zone — the governing calendar's, the house's day — or
  UTC when nothing governs) :reverses (optional)}; `source` is {:kind
  :id :action :log-id :at :zone}.
  Each row account's balance moves in the same transaction (step 3c),
  its row locked and saved a version on — so a door that posts to its
  own row saves that row first, then posts, naming it `:settled` when
  the saved row already holds its move (step 4).

  Idempotent by log id: a transition that already posted posts nothing
  again, and answers what it posted.
  → the postings as stored."
  ([eng tx source entries] (post! eng tx source entries {}))
  ([eng tx source entries {:keys [settled]}]
   (let [{:keys [kind id action log-id at zone]} source
         ^Instant at (or at (Instant/now))
         ^ZoneId zone (or zone ZoneOffset/UTC)
         today (LocalDate/ofInstant at zone)]
     (if-some [done (not-empty (posted eng tx log-id))]
       (vec done)
       (do
         (rebalance! eng tx entries settled)
         (mapv (fn [{:keys [account-kind account-row account-name amount
                            effective-on reverses]}]
                 (inv/insert-quiet!
                  eng tx :posting
                  (cond-> {:account (account-address account-kind account-row account-name)
                           :account_kind (name account-kind)
                           :account_name (name account-name)
                           :amount amount
                           :at at
                           :effective_on (or effective-on today)
                           :kind (name kind)
                           :source_id (str id)
                           :action (name action)
                           :log_id (str log-id)}
                    account-row (assoc :account_row (str account-row))
                    reverses (assoc :reverses (str reverses)))
                  {:principal poster}))
               entries))))))
