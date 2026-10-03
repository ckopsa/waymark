(ns waymark10.server.domains
  "The domain (epic aff24e84, piece 1): a named part of the house with
  a charter, a mayor seat, a week's budget and an owner. A seat names
  the domain it is in; a seat that names none reads as being in
  `factory` (`seats/domain-name-of`), and nothing is stored to say so.

  `create` and `restate` are money-shaped, so an agent's call is held
  for the person's tap (waymark10.holds). `retire` is refused while a
  seat still names the domain.

  `ensure-factory!` is the boot seed: the domain `factory`, made once.
  No seat row is rewritten."
  (:require [waymark10.guards :as g]
            [waymark10.holds :as holds]
            [waymark10.resource :refer [defresource defhandler]]
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

;; ── the handler ─────────────────────────────────────────────────────

(defhandler restate-domain
  [row inp _ctx]
  ;; a patch: a field the input leaves out keeps its stored value
  (update row :data merge
          (select-keys inp [:charter :mayor :budget_usd_per_week :owner])))

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
     :guards [domain-waits-for-the-tap]
     :edit {:prefill [:charter :mayor :budget_usd_per_week :owner] :fence true}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The domain holds what this restate says; the values before are not kept."}
     :handler restate-domain
     :display {:label "Restate the domain" :order 1
               :description "Change the charter, the mayor, the week's budget or the owner"}}

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
