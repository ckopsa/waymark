(ns waymark10.server.roles
  "Roles: policy names are resources (waymark9 server/roles.py). A
  role's MEANING (what holding it opens) lives in guards and grants;
  the registry closes the silent-grant gap the v3 notes recorded — a
  role name nobody spelled the same way twice used to grant nobody,
  silently. Now the name must exist here, active, before a member may
  hold it; who is admin stays auditable.

  Recorded deviations and named punts:
  - One spelling per role stays a create guard over ctx :find. Since
    design §24 a declared :unique DOES reach storage (plan_day's
    demand); adopting it here (and in definitions) is a named
    follow-up — the guard keeps the nicer refusal sentence, the index
    would close the racing-creates window.
  - waymark9 grants consulted _active_role_named on role-held grants;
    v10 grants name a principal audience, not a role — the registry's
    v10 consumer is the member's roles field (members.clj)."
  (:require [waymark10.guards :as g]
            [waymark10.resource :refer [defresource]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn active-role?
  "Is `role-name` a registered, active role on this engine? Reads
  through the ctx :find hook (same transaction as the write); a ctx
  without the hook declines with nil — the render probe stays
  storage-free."
  [ctx role-name]
  (when-some [find' (:find ctx)]
    (boolean (seq (find' :role {:name (str role-name) :state "active"}
                         {:limit 1})))))

(g/defguard one-spelling
  {:judges [:name]
   :reads [:role]
   :explain "A role named {name} already exists — one spelling per role, or the grant surface splits silently."
   :open "The taken spellings are the roles collection, one query away; advertising them all would enumerate the registry into every create form."}
  [_row inp ctx]
  (if (and (some? (:name inp)) (active-role? ctx (:name inp)))
    (t/deny {:vars {:name (:name inp)}})
    (t/allow)))

(defresource role
  {:kind :role
   :plural "roles"
   :states [:active :retired]
   :initial :active
   :terminal #{}                       ; retirement is reversible, deliberately
   :nav :system
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   ;; No :x-options on :name, deliberately, and this is the one place in
   ;; the codebase where the runtime-vocabulary spelling was asked for
   ;; and REFUSED (waymark-7rw). one-spelling reads the role collection,
   ;; so there IS a list the engine could publish here — but it is the
   ;; list of names already TAKEN. A chip row of it would offer exactly
   ;; the tokens the guard is about to refuse, which is a worse form
   ;; than a blank box, not a better one. What the field owes instead
   ;; is the naming convention, said out loud, and something to start
   ;; from; the refusal names the collision when one happens.
   :schema [:map
            [:name {:examples ["dishwasher-emptier"]
                    :x-display {:raw true
                                :label "Role name"
                                :help "The token guards and grants will spell — lowercase, hyphenated, one word for one authority (\"recovery-admin\", \"parent\"). One spelling per role: a name already registered is refused, because two spellings of one role split the grant surface silently."}}
             [:string {:min 1 :max 40}]]
            [:description {:optional true
                           :examples ["Whoever is on for emptying the dishwasher this week."]
                           :x-display {:label "What holding it means"
                                       :help "A sentence for whoever reads the member list later and wonders what this role opens."}}
             [:maybe [:string {:max 240}]]]]
   :filterable {:state #{:eq :in}
                :name #{:eq}}
   :sortable {:fields [:name] :default "name"}
   :create-guards [one-spelling]
   :actions
   {:retire {:from #{:active} :to :retired
             :safety {:idempotent true :reversible true :confirm true
                      :consequence "Members can no longer be assigned this role; existing holders keep it until reassigned."}
             :display {:label "Retire" :style :danger :order 9}}
    :reactivate {:from #{:retired} :to :active
                 :safety {:idempotent true :reversible true :confirm false}
                 :display {:label "Reactivate" :order 1}}}})

;; ── the factory roles: manager, contributor and requester ───────────

(def manager
  "The role `a-manager` passes."
  "manager")

(def contributor
  "The role of a member who works in the factory and does not run it."
  "contributor")

(def requester
  "The role of a person who files tickets and sees only the ones they
  filed (epic 46831b3c, R-3.4): `grants/unscoped-visibility` narrows
  their sight, and the ticket's birth lands their ask in the backlog."
  "requester")

(def factory-roles
  "The three factory roles, each a role row `ensure-factory-roles!`
  registers. A member holds at most one, given by `set_factory_role`."
  [manager contributor requester])

(def ^:private factory-domain
  ;; seats/default-domain, spelled here: seats sits above members, and
  ;; members reads this namespace
  "factory")

(defn manager?
  "Does the ctx's principal pass as a manager? A person passes when the
  factory domain has no owner (the engine as it was before the role),
  when it is that owner, or when its own member row holds `manager` —
  read from the ROW, never the token. An agent that acts for a person
  (a delegate, marked :acts-for) is judged as that person: it may walk
  no manager's door its person may not. Any other agent (a seat
  sitting) and the engine's own actors are not judged here: they keep
  their grant rules. A ctx with no read hooks (the render probe)
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
      (let [owner (some-> (first (find' :domain {:name factory-domain} {:limit 1}))
                          (get-in [:data :owner]) str not-empty)
            own (when pid
                  (or (read' :member pid)
                      (first (find' :member {:subject pid} {:limit 1}))))]
        (boolean
         (or (nil? owner)
             (= owner pid)
             (= owner (some-> (:id own) str))
             (contains? (set (get-in own [:data :roles])) manager)))))))

(g/defguard a-manager
  {:reads [:principal :member :domain]
   :vars [:role]
   :explain "This door is a manager's: it wants a person who holds the `{role}` role, or the factory domain's owner."
   :open "A manager gives the role on the member's `set_factory_role` door; the factory domain's owner is set by restating that domain."}
  [_row _inp ctx]
  (if (manager? ctx)
    (t/allow)
    (t/deny {:vars {:role manager}})))

(defn- manager-guarded?
  "Does this guard vector require `a-manager`? An :all recurses, an
  :any does not: a door a manager OR someone else may walk is not a
  manager's door."
  [guards]
  (boolean (some #(= (:name a-manager) (:name %))
                 (mapcat g/iter-leaves guards))))

(defn- manager-doors
  "The action names of one kind whose guards require `a-manager`,
  its create verbs among them when its create guards do."
  [rdef]
  (into #{}
        (map name)
        (concat (for [[n a] (:actions rdef)
                      :when (manager-guarded? (:guards a))]
                  n)
                (when (manager-guarded? (:create-guards rdef))
                  (:create-action-names rdef)))))

(defn- scope-over
  "One {:kind :actions} entry per [kind rdef] pair, sorted by kind:
  every action when `whole?`, else every action but the manager doors."
  [whole? kinds]
  (->> kinds
       (sort-by (comp name first))
       (mapv (fn [[kind rdef]]
               (let [held (if whole? #{} (manager-doors rdef))]
                 {:kind (name kind)
                  :actions (vec (remove held (inv/action-names rdef)))})))))

(defn effective-scope
  "A person's effective scope, in a grant scope's shape: one entry
  {:kind :actions} for every kind this engine serves. A manager (as
  `a-manager` judges them, so every person while the factory domain
  has no owner) gets every action; anyone else gets every action but
  the doors `a-manager` guards. A delegate's scope is its person's,
  by the same rule. Derived from the declarations, never
  a kept list: wiring `a-manager` onto a door narrows a contributor's
  scope by itself. Dotted powers are not here — a person holds those
  as granted."
  [eng principal]
  (scope-over (manager? (assoc (inv/render-hooks eng) :principal principal))
              (inv/resources eng)))

(defn effective-scope-in
  "`effective-scope` from inside a write: the ctx's own registry
  (:rdefs) and read hooks, so the answer is the write's transaction's.
  nil for a ctx that carries no registry (the render probe)."
  [ctx principal]
  (when-some [rdefs (:rdefs ctx)]
    (scope-over (manager? (assoc ctx :principal principal))
                (map (juxt :kind identity) (rdefs)))))

(def seed-actor
  "The actor the role seed wears."
  (t/principal {:id "waymark10-roles" :type :system
                :display "Roles"}))

(def ^:private factory-role-text
  {manager "Runs the factory: may give members a factory role, and walks the doors only a manager may."
   contributor "Works in the factory and does not run it."
   requester "Files tickets and sees only the tickets they filed."})

(defn ensure-factory-roles!
  "The boot seed: one active `role` row for each of `factory-roles`
  that no role row spells yet. A second boot makes none."
  [eng]
  (when (contains? (inv/resources eng) :role)
    (doseq [role-name factory-roles]
      (try
        (when (empty? (store/with-tx (:storage eng)
                        (fn [tx] (store/query-rows (:storage eng) tx :role
                                                   {:name role-name} {:limit 1}))))
          (inv/create! eng :role {:name role-name
                                  :description (factory-role-text role-name)}
                       {:principal seed-actor}))
        (catch Exception e
          (binding [*out* *err*]
            (println (str "waymark10 roles: seed of " role-name
                          " failed — " (ex-message e)))))))))
