(ns waymark10.server.domains
  "The domain (epic aff24e84, piece 1): a named part of the house with
  a charter, a mayor seat, a week's budget and an owner. A seat names
  the domain it is in; a seat that names none reads as being in
  `factory` (`seats/domain-name-of`), and nothing is stored to say so.

  `create` and `restate` are money-shaped, so an agent's call is held
  for the person's tap (waymark10.holds). `retire` is refused while a
  seat still names the domain.

  `ensure-factory!` is the boot seed: the domain `factory`, made once.
  No seat row is rewritten.

  THE BUDGET IS A CEILING OVER THE SEATS (piece 2). The budgets of a
  domain's active seats total at or under the domain's own
  (seats/budget-fits-the-domain on seat create and restate,
  `budget-covers-the-seats` on the restate here). The row shows the
  total and what is left as `allocated_usd_per_week` and
  `headroom_usd_per_week`, worked out at read time. `move_budget` takes
  an amount from one seat and gives it to another in one transaction;
  a person or the sitter of the domain's mayor seat makes it."
  (:require [waymark10.guards :as g]
            [waymark10.holds :as holds]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.invoke :as inv]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── the guards ──────────────────────────────────────────────────────

(g/defguard domain-waits-for-the-tap
  {:reads [:principal :within]
   :hold true
   :vars [:what]
   :explain "Held for the person's tap. A domain is a charter and a week's money, so {what} is the person's to allow. The call is recorded as a held_call, and the person's Allow runs it exactly as written."
   :open "No door clears this one. The call waits as a held_call for the person's tap."}
  [row _inp ctx]
  ;; create and restate. A person and the engine's own seed pass; an
  ;; agent's call waits, and the one agent call this admits is the
  ;; engine's replay of the held call that person allowed.
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :domain (:id row)) (t/allow)
    :else (t/deny {:vars {:what (if (:id row)
                                  "restating one"
                                  "making one")}})))

(g/defguard no-seat-names-the-domain
  {:reads [:seat]
   :vars [:detail]
   :explain "This domain is not retired: {detail}."
   :open "Restate each seat that names this domain so it names another, or retire that seat; then retire the domain."}
  [row _inp ctx]
  (if-some [find' (:find ctx)]
    (cond
      ;; a seat with no domain reads as being in factory, so factory
      ;; is named by seats no query can count
      (= seats/default-domain (str (get-in row [:data :name])))
      (t/deny {:vars {:detail (str "every seat that names no domain is in "
                                   seats/default-domain)}})

      (some #(seq (find' :seat {:domain (str (:id row)) :state %} {:limit 1}))
            ["active" "parked"])
      (t/deny {:vars {:detail "a seat still names it"}})

      :else (t/allow))
    (t/allow)))                         ; probe ctx — decline to guess

(g/defguard budget-covers-the-seats
  {:reads [:seat]
   :vars [:total :ceiling]
   :remedies [:seat/restate :seat/park]
   :explain "This domain's active seats are given {total} dollars a week together, and a budget of {ceiling} is under that. Lower a seat's budget or park a seat first, or state a budget of {total} or more."}
  [row inp ctx]
  ;; restate. Only a budget that comes DOWN under the total is refused:
  ;; a domain already under it may still be raised toward it.
  (let [find' (:find ctx)
        asked (:budget_usd_per_week inp)]
    (if (or (nil? find') (nil? asked))
      (t/allow)
      (let [asked (seats/dollars asked)
            total (seats/domain-allocated find' row)]
        (if (and (neg? (compare asked total))
                 (neg? (compare asked (seats/dollars
                                       (get-in row [:data :budget_usd_per_week])))))
          (t/deny {:vars {:total (seats/money-text total)
                          :ceiling (seats/money-text asked)}})
          (t/allow))))))

(g/defguard the-mayors-or-a-persons-move
  {:reads [:principal :now :grant :seat]
   :open "No door changes who the caller is: ask this domain's mayor or a person to make the move."
   :explain "A domain's budget is moved between its seats by a person, or by the sitter of the domain's own mayor seat. Ask this domain's mayor or a person to make the move."}
  [row _inp ctx]
  (let [{:keys [type acts-for]} (:principal ctx)
        cited (delegation/cited-seats ctx)
        mayor (some-> (get-in row [:data :mayor]) str not-empty)]
    (cond
      (and mayor (contains? cited mayor)) (t/allow)
      ;; a sitter of any other seat
      (seq cited) (t/deny)
      ;; a person, a tool a person is signed in to, or the engine
      (or (not= :agent type) (some? (not-empty (str acts-for)))) (t/allow)
      :else (t/deny))))

(g/defguard move-stays-inside-the-domain
  {:reads [:seat :domain]
   :vars [:detail]
   :open "Name two different active seats of this domain, and an amount above zero that the first seat has."
   :explain "This budget is not moved: {detail}."}
  [row inp ctx]
  (if-some [read' (:read ctx)]
    (let [from (read' :seat (str (:from inp)))
          to (read' :seat (str (:to inp)))
          amount (seats/dollars (:amount inp))
          here? (fn [s]
                  (and s
                       (= "active" (name (:state s)))
                       (= (str (:id row))
                          (some-> (seats/domain-row-of ctx (get-in s [:data :domain]))
                                  :id
                                  str))))
          no (fn [detail] (t/deny {:vars {:detail detail}}))]
      (cond
        (not (pos? (compare amount 0M)))
        (no "the amount is not above zero")

        (= (str (:from inp)) (str (:to inp)))
        (no "it takes from and gives to the same seat")

        (not (here? from))
        (no "the seat it takes from is not an active seat of this domain")

        (not (here? to))
        (no "the seat it gives to is not an active seat of this domain")

        (neg? (compare (seats/dollars (get-in from [:data :budget_usd_per_week]))
                       amount))
        (no (str "the seat it takes from has "
                 (seats/money-text (get-in from [:data :budget_usd_per_week]))
                 " dollars a week, and the move asks for "
                 (seats/money-text amount)))

        :else (t/allow)))
    (t/allow)))                         ; probe ctx — decline to guess

;; ── the handlers ────────────────────────────────────────────────────

(defhandler restate-domain
  [row inp _ctx]
  ;; a patch: a field the input leaves out keeps its stored value
  (update row :data merge
          (select-keys inp [:charter :mayor :budget_usd_per_week :owner])))

(defhandler move-budget
  [row inp ctx]
  ;; both seats are written through their own `set_budget` door, inside
  ;; this write's transaction: the total does not change, so a domain
  ;; with no headroom still moves
  (when-some [invoke (:invoke ctx)]
    (let [amount (seats/dollars (:amount inp))
          budget (fn [id]
                   (seats/dollars (get-in ((:read ctx) :seat (str id))
                                          [:data :budget_usd_per_week])))
          from (- (budget (:from inp)) amount)
          to (+ (budget (:to inp)) amount)]
      (invoke :seat (str (:from inp)) :set_budget {:budget_usd_per_week from})
      (invoke :seat (str (:to inp)) :set_budget {:budget_usd_per_week to})))
  row)

;; ── the derived fields ──────────────────────────────────────────────

(defn- allocated-field
  "The domain's :computed `allocated_usd_per_week`: the budgets of its
  active seats, totalled over the find the render ctx lends."
  [row ctx]
  (when-some [find' (:find ctx)]
    (seats/domain-allocated find' row)))

(defn- headroom-field
  "The domain's :computed `headroom_usd_per_week`: its budget less what
  its active seats are given."
  [row ctx]
  (when-some [allocated (allocated-field row ctx)]
    (- (seats/dollars (get-in row [:data :budget_usd_per_week])) allocated)))

;; ── the kind ────────────────────────────────────────────────────────

(def ^:private charter-field
  [:charter {:examples ["The family's house: its reminders, its lights and its music."]
             :x-display
             {:widget "prose"
              :label "Charter"
              :help "What this domain is for, in a few sentences its mayor and its seats can work from."}}
   [:string {:min 1 :max 4000}]])

(def ^:private mayor-field
  [:mayor {:optional true
           :kind :seat
           :x-display
           {:label "Its mayor"
            :help "The seat that runs this domain. Leave it empty for a domain with no mayor yet."}}
   [:maybe :waymark/ref]])

(def ^:private budget-field
  [:budget_usd_per_week {:examples [3600M]
                         :x-display
                         {:label "Fuel for seven days, in dollars"
                          :help "The most this domain's seats may spend together in seven days."}}
   [:decimal {:min 0 :max 100000}]])

(def ^:private owner-field
  [:owner {:optional true
           :kind :member
           :x-display
           {:label "Its owner"
            :help "The member who answers for this domain. Leave it empty and it is whoever approves held calls today."}}
   [:maybe :waymark/ref]])

(defn- optional [[k props schema]]
  [k (assoc props :optional true) schema])

(defresource domain
  {:kind :domain
   :plural "domains"
   :states [:active :retired]
   :initial :active
   :terminal #{:retired}
   :nav :system
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   :computed {:allocated_usd_per_week
              {:schema [:decimal {:min 0 :max 100000000}]
               :x-display
               {:label "Given to its seats, in dollars a week"
                :help "The total of the budgets of this domain's active seats, worked out at read time. A parked or retired seat does not count."}
               ;; it totals the seat rows: with no read lent it is
               ;; absent, never a false zero
               :reads? true
               :fn allocated-field}
              :headroom_usd_per_week
              {:schema [:decimal {:min -100000000 :max 100000000}]
               :x-display
               {:label "Headroom, in dollars a week"
                :help "The domain's budget less what its active seats are given: what its mayor may still hand out with no tap."}
               :reads? true
               :fn headroom-field}}
   :unique [[:name]]
   :schema
   [:map
    [:name {:examples ["factory"]
            :x-display
            {:raw true
             :label "Domain name"
             :help "One short name, spelled once: no other domain may carry it, and no restate changes it."}}
     [:string {:min 1 :max 64}]]
    charter-field
    mayor-field
    budget-field
    owner-field]
   :filterable {:state #{:eq :in}
                :name #{:eq}
                :mayor #{:eq}}
   :sortable {:fields [:name] :default "name"}
   :create-guards [domain-waits-for-the-tap]
   :actions
   {:restate
    {:from #{:active} :to :active
     :input [:map
             (optional charter-field)
             mayor-field
             (optional budget-field)
             owner-field]
     :record true
     ;; the hold LAST, so a held restate is one every other wall passed
     :guards [budget-covers-the-seats domain-waits-for-the-tap]
     :edit {:prefill [:charter :mayor :budget_usd_per_week :owner] :fence true}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The domain holds what this restate says; the values before are not kept."}
     :handler restate-domain
     :display {:label "Restate the domain" :order 1
               :description "Change the charter, the mayor, the week's budget or the owner"}}

    :move_budget
    {:from #{:active} :to :active
     :input [:map
             [:from {:kind :seat
                     :x-display
                     {:label "Take from"
                      :help "The active seat of this domain whose week's budget comes down by the amount."}}
              :waymark/ref]
             [:to {:kind :seat
                   :x-display
                   {:label "Give to"
                    :help "The active seat of this domain whose week's budget goes up by the amount."}}
              :waymark/ref]
             [:amount {:examples [5M]
                       :x-display
                       {:label "Amount, in dollars a week"
                        :help "How much moves. It is above zero and no more than the first seat has."}}
              [:decimal {:min 0 :max 100000}]]]
     :record true
     :guards [the-mayors-or-a-persons-move move-stays-inside-the-domain]
     ;; NOT idempotent: a second move moves the amount again
     :safety {:idempotent false :reversible false :confirm false}
     :handler move-budget
     :display {:label "Move budget" :order 2
               :description "Take an amount from one seat's week and give it to another, in one write"}}

    :retire
    {:from #{:active} :to :retired
     :guards [no-seat-names-the-domain]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "A retired domain does not come back, and its name stays taken."
              ;; the cost is not the row's: the name is spent, and the
              ;; record of what its seats did was written under it
              :final "What this domain's seats did stays recorded under its name, so the name is not given to another and the domain does not open again."}
     :display {:label "Retire" :style :danger :order 9
               :description "End a domain no seat names any more"}}}})

;; ── the boot seed ───────────────────────────────────────────────────

(def seed-actor
  "The actor the boot seed wears."
  (t/principal {:id "waymark10-domains" :type :system
                :display "Domains"}))

(defn- rows-of [eng kind where]
  (if (contains? (inv/resources eng) kind)
    (store/with-tx (:storage eng)
      (fn [tx] (store/query-rows (:storage eng) tx kind where {:limit 100000})))
    []))

(defn domain-named
  "The domain row of that name, or nil."
  [eng name']
  (first (rows-of eng :domain {:name (str name')})))

(defn ensure-factory!
  "The boot seed: domain `factory` when no domain carries that name,
  with the active seat named `mayor` as its mayor when there is one, a
  budget of 3600 and no owner. A second boot makes none."
  [eng]
  (when (and (contains? (inv/resources eng) :domain)
             (nil? (domain-named eng seats/default-domain)))
    (try
      (let [mayor (some->> (rows-of eng :seat {:name "mayor"})
                           (filter #(= "active" (name (:state %))))
                           first :id str)]
        (inv/create! eng :domain
                     (cond-> {:name seats/default-domain
                              :charter "The engine's own work, and every seat that names no other domain."
                              :budget_usd_per_week 3600M}
                       mayor (assoc :mayor mayor))
                     {:principal seed-actor}))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "waymark10 domains: seed of " seats/default-domain
                        " failed — " (ex-message e))))))))
