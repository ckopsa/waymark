(ns waymark10.server.teams
  "Teams (docs/spec-teams.md §2, decision 1): a crew, or \"the licensed
  therapists\", is a row and not an enum. A team names its members and
  the role names it CONFERS: a member holds role r while on an active
  team whose `roles` has r, and leaving the team takes it away in the
  same write. A retired team confers nothing and admits nothing; none
  is ever deleted, so the rows and the log that name one still read.

  Membership is data, not law: adding or removing a member is an
  `edit`, logged like any other, and takes effect at the next judgment.

  The doors are held by the house's owner — the factory domain's, read
  the way `roles/manager?` reads it, so every person while it has none
  — and by a member holding the registered role `team-admin`, on their
  own row or conferred by an active team. `ensure-team-roles!` is the
  boot seed that registers it."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.roles :as roles]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def team-admin
  "The registered role whose holder walks every team door."
  "team-admin")

(def ^:private owner-domain
  ;; roles' factory-domain, spelled here: it is private there
  "factory")

(def ^:private team-page
  ;; the most active teams one judgment reads; a house has a handful
  500)

;; ── what a team confers ──────────────────────────────────────────────

(defn conferred-roles
  "The role names the active teams naming `member-id` confer, as a set.
  A retired team confers nothing. Reads through the ctx :find hook
  (same transaction as the write); empty for a ctx without it (the
  render probe)."
  [ctx member-id]
  (let [mid (some-> member-id str not-empty)
        find' (:find ctx)]
    (into (sorted-set)
          (comp (filter (fn [row]
                          (some #(= mid (str %)) (get-in row [:data :members]))))
                (mapcat #(get-in % [:data :roles])))
          (when (and mid find')
            (find' :team {:state "active"} {:limit team-page})))))

(defn holds-role?
  "Does the member row `own` hold `role-name` — on its own row, or
  conferred by an active team that names it?"
  [ctx own role-name]
  (boolean
   (and own
        (or (contains? (set (get-in own [:data :roles])) role-name)
            (contains? (conferred-roles ctx (:id own)) role-name)))))

(defn team-admin?
  "Does the ctx's principal walk the team doors? A person passes when
  the factory domain has no owner, when it is that owner, or when its
  member row holds `team-admin` (`holds-role?`). A delegate is judged
  as its person; any other agent and the engine's own actors keep
  their grant rules, as in `roles/manager?`. A ctx with no read hooks
  passes."
  [ctx]
  (let [p (:principal ctx)
        delegate (when (= :agent (:type p))
                   (some-> (:acts-for p) str not-empty))
        pid (or delegate (some-> (:id p) str not-empty))
        read' (:read ctx)
        find' (:find ctx)]
    (cond
      (and (contains? #{:agent :system} (:type p)) (nil? delegate)) true
      (or (nil? read') (nil? find')) true
      :else
      (let [owner (some-> (first (find' :domain {:name owner-domain} {:limit 1}))
                          (get-in [:data :owner]) str not-empty)
            own (when pid
                  (or (read' :member pid)
                      (first (find' :member {:subject pid} {:limit 1}))))]
        (boolean
         (or (nil? owner)
             (= owner pid)
             (= owner (some-> (:id own) str))
             (holds-role? ctx own team-admin)))))))

;; ── the guards ──────────────────────────────────────────────────────

(g/defguard a-team-admin
  {:reads [:principal :member :domain :team]
   :vars [:role]
   :explain "This door is a team admin's: it wants a person who holds the `{role}` role, or the house's owner."
   :open "The role is given on the member's row, or by an active team that confers it; the house's owner is the factory domain's."}
  [_row _inp ctx]
  (if (team-admin? ctx)
    (t/allow)
    (t/deny {:vars {:role team-admin}})))

(defn- name-taken?
  "Does an active team other than `own-id` carry `team-name`?"
  [ctx team-name own-id]
  (when-some [find' (:find ctx)]
    (boolean (some #(not= (str own-id) (str (:id %)))
                   (find' :team {:name (str team-name) :state "active"}
                          {:limit 2})))))

(g/defguard one-active-team-name
  {:judges [:name]
   :reads [:team]
   :explain "An active team named {name} already exists — one name per active team, or one crew reads as two."
   :open "The active teams are the teams collection, one query away; a retired team's name is free again."}
  [row inp ctx]
  (if (and (some? (:name inp)) (name-taken? ctx (:name inp) (:id row)))
    (t/deny {:vars {:name (:name inp)}})
    (t/allow)))

(g/defguard team-name-free-to-restore
  {:reads [:team]
   :vars [:name]
   :explain "An active team named {name} already exists — retire or rename it before this one comes back."
   :open "The active teams are the teams collection, one query away."}
  [row _inp ctx]
  (let [n (get-in row [:data :name])]
    (if (and (some? n) (name-taken? ctx n (:id row)))
      (t/deny {:vars {:name n}})
      (t/allow))))

(g/defguard team-roles-registered
  {:judges [:roles]
   :reads [:role]
   :explain "No active role named {roles} — register the role first; a team conferring it would confer nothing."
   :open "The active roles are the roles collection, one query away; the registry judges each name at submit."}
  [_row inp ctx]
  (if-some [unknown (when (:find ctx)
                      (seq (sort (remove #(roles/active-role? ctx %)
                                         (:roles inp)))))]
    (t/deny {:vars {:roles (str/join ", " unknown)}})
    (t/allow)))

;; ── the resource ────────────────────────────────────────────────────

(def ^:private name-field
  [:name {:examples ["Crew B"]
          :x-display {:label "Team name"
                      :help "What the house calls this crew or group. No two active teams carry one name; a retired team's name is free again."}}
   [:string {:min 1 :max 60}]])

(def ^:private members-field
  [:members {:default []
             :kind :member
             :x-display {:label "Members"
                         :help "The members on this team. It may be empty; adding or removing one is an edit, logged like any other."}}
   [:vector :waymark/ref]])

(def ^:private roles-field
  [:roles {:optional true
           :examples [["pt"]]
           :x-display {:raw true
                       :label "Roles it confers"
                       :help "Role names from the roles registry, exactly as spelled there: a member holds each while on this team and the team is active. A name no active role carries is refused."}}
   [:vector [:string {:min 1 :max 40}]]])

(def ^:private note-field
  [:note {:optional true
          :x-display {:label "Note"
                      :help "A sentence for whoever reads the team list later: what this team is for."}}
   [:maybe [:string {:max 240}]]])

(defn- optional
  "An entry an edit may leave out: optional, and with no default that
  would overwrite the stored value."
  [[k props s]]
  [k (-> props (assoc :optional true) (dissoc :default)) s])

(defhandler edit-team
  [row inp _ctx]
  ;; a patch: a field the input leaves out keeps its stored value
  (update row :data merge (select-keys inp [:name :members :roles :note])))

(defresource team
  {:kind :team
   :plural "teams"
   :states [:active :retired]
   :initial :active
   :terminal #{}                       ; never deleted; retire is reversible
   :nav :system
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   :schema [:map name-field members-field roles-field note-field]
   :filterable {:state #{:eq :in}
                :name #{:eq}}
   :sortable {:fields [:name] :default "name"}
   :create-guards [a-team-admin one-active-team-name team-roles-registered]
   :actions
   {:edit {:from #{:active} :to :active
           :input [:map
                   (optional name-field)
                   (optional members-field)
                   roles-field
                   note-field]
           :record true
           :guards [a-team-admin one-active-team-name team-roles-registered]
           :edit {:prefill [:name :members :roles :note] :fence true}
           :safety {:idempotent true :reversible true :confirm false}
           :handler edit-team
           :display {:label "Edit" :style :primary :order 1
                     :description "Change the name, the members, the roles it confers or the note"}}
    :retire {:from #{:active} :to :retired
             :guards [a-team-admin]
             :safety {:idempotent true :reversible true :confirm true
                      :consequence "The team confers nothing and admits nothing until it is restored; its members keep the roles their own rows hold."}
             :display {:label "Retire" :style :danger :order 9}}
    :restore {:from #{:retired} :to :active
              :guards [a-team-admin team-name-free-to-restore]
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Restore" :order 2}}}})

;; ── the boot seed ───────────────────────────────────────────────────

(defn ensure-team-roles!
  "The boot seed: an active `team-admin` role row when no role row
  spells it yet (roles/ensure-factory-roles!'s shape). A second boot
  makes none."
  [eng]
  (when (contains? (inv/resources eng) :role)
    (try
      (when (empty? (store/with-tx (:storage eng)
                      (fn [tx] (store/query-rows (:storage eng) tx :role
                                                 {:name team-admin} {:limit 1}))))
        (inv/create! eng :role {:name team-admin
                                :description "Makes, edits, retires and restores the house's teams."}
                     {:principal roles/seed-actor}))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "waymark10 teams: seed of " team-admin
                        " failed — " (ex-message e))))))))
