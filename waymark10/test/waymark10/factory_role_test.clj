(ns waymark10.factory-role-test
  "The factory roles (ticket 6f917c11): `manager` and `contributor` are
  registered once, and `roles/a-manager` passes every person while the
  factory domain has no owner; with an owner set it passes the owner
  and a member holding `manager` and refuses a contributor, naming the
  role it wants. Only a manager walks `set_factory_role`, and only a
  manager allows the bench's merge held call (ticket d121729e). A
  person's effective scope (ticket 228f59e8) is the whole engine for a
  manager and everything but the `a-manager` doors for anyone else. In
  memory."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :refer [defresource]]
            [waymark10.server.engine :as engine]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.roles :as roles]
            [waymark10.server.runner-links :as rl]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(defn- eng!
  ([] (eng! []))
  ([resources]
   (let [eng (engine/engine {:storage (memory/storage) :resources resources})]
     (roles/ensure-factory-roles! eng)
     eng)))

(defn- as [id] {:principal (t/principal {:id id :display id})})

(defn- delegate
  "An agent acting for `person`, as the identity gate marks one."
  [person]
  {:principal (assoc (t/principal {:id (str person "-claude") :type :agent
                                   :display (str person "'s Claude")})
                     :acts-for person)})

(defn- member! [eng id roles']
  (:row (inv/create! eng :member
                     (cond-> {:display id :actor_type "human"}
                       (seq roles') (assoc :roles roles'))
                     {:principal members/registrar :id id})))

(defn- rows-of [eng kind where]
  (store/with-tx (:storage eng)
    (fn [tx] (store/query-rows (:storage eng) tx kind where {:limit 100}))))

(defn- roles-of [eng id]
  (let [rdef (get (inv/resources eng) :member)]
    (some->> (store/with-tx (:storage eng)
               (fn [tx] (store/load-row (:storage eng) tx :member (str id) {})))
             (inv/decode-row rdef)
             :data :roles vec)))

(defn- refusal [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- set-role! [eng who target role]
  (inv/invoke! eng :member target :set_factory_role {:role role} (as who)))

(defn- factory! [eng]
  (:row (inv/create! eng :domain {:name "factory"
                                  :charter "The engine's own work."
                                  :budget_usd_per_week 40M}
                     (as "olive"))))

(deftest the-two-roles-are-registered-once
  (let [eng (eng!)]
    (roles/ensure-factory-roles! eng)
    (doseq [r roles/factory-roles]
      (is (= 1 (count (rows-of eng :role {:name r}))) r))))

(deftest with-no-owner-every-person-is-a-manager
  (let [eng (eng!)]
    (factory! eng)
    (member! eng "olive" nil)
    (member! eng "cole" ["contributor"])
    (testing "a contributor walks set_factory_role while no owner is set"
      (set-role! eng "cole" "olive" "contributor")
      (is (= ["contributor"] (roles-of eng "olive"))))
    (testing "agents are not judged: they keep their grant rules"
      (is (roles/manager? {:principal (t/principal {:id "clerk" :type :agent})
                           :read (constantly nil) :find (constantly nil)})))
    (testing "a delegate of a contributor passes while no owner is set"
      (set-role! eng "olive" "olive" nil)
      (inv/invoke! eng :member "olive" :set_factory_role {:role "contributor"}
                   (delegate "cole"))
      (is (= ["contributor"] (roles-of eng "olive"))))))

(deftest with-an-owner-only-a-manager-passes
  (let [eng (eng!)]
    (member! eng "olive" nil)
    (member! eng "mara" ["manager"])
    (member! eng "cole" ["contributor"])
    (let [d (factory! eng)]
      (inv/invoke! eng :domain (str (:id d)) :restate {:owner "olive"}
                   (assoc (as "olive")
                          :if-match (inv/etag :domain (str (:id d)) (:version d)))))
    (testing "the owner passes"
      (set-role! eng "olive" "cole" "manager")
      (is (= ["manager"] (roles-of eng "cole"))))
    (testing "a member holding manager passes, and clears a role"
      (set-role! eng "mara" "cole" nil)
      (is (= [] (roles-of eng "cole"))))
    (testing "a contributor is refused, and the refusal names the role"
      (set-role! eng "mara" "cole" "contributor")
      (let [r (refusal #(set-role! eng "cole" "mara" "contributor"))]
        (is (some? r))
        (is (re-find #"`manager` role" (pr-str r)))
        (is (= ["manager"] (roles-of eng "mara")))))
    (testing "a person with no member row and no role is refused"
      (is (some? (refusal #(set-role! eng "stranger" "cole" nil)))))))

;; ── the held merge: only a manager allows it ───────────────────────

(defn- held! [eng tool]
  (str (:id (:row (inv/create! eng :held_call
                               {:tool tool
                                :why "A person decides this call."
                                :caller "merge-asker"
                                :input {:number 7}
                                :forward {:number 7}
                                :shown "the call"}
                               {:principal held/engine-actor})))))

(defn- allow! [eng id who]
  (:state (:row (inv/invoke! eng :held_call id :allow {}
                             {:principal (t/principal {:id who :display who
                                                       :roles #{"approver"}})}))))

(deftest a-delegate-is-judged-as-its-person
  ;; ticket b7959730: an agent that acts for a person walks no manager's
  ;; door its person may not
  (let [eng (eng!)]
    (member! eng "olive" nil)
    (member! eng "mara" ["manager"])
    (member! eng "cole" ["contributor"])
    (let [d (factory! eng)]
      (inv/invoke! eng :domain (str (:id d)) :restate {:owner "olive"}
                   (assoc (as "olive")
                          :if-match (inv/etag :domain (str (:id d)) (:version d)))))
    (testing "a contributor's delegate is refused, and the refusal names the role"
      (let [r (refusal #(inv/invoke! eng :member "mara" :set_factory_role
                                     {:role "contributor"} (delegate "cole")))]
        (is (re-find #"`manager` role" (pr-str r)))
        (is (= ["manager"] (roles-of eng "mara")))))
    (testing "a manager's delegate passes"
      (inv/invoke! eng :member "cole" :set_factory_role {:role nil} (delegate "mara"))
      (is (= [] (roles-of eng "cole"))))
    (testing "the owner's delegate passes"
      (inv/invoke! eng :member "cole" :set_factory_role {:role "contributor"}
                   (delegate "olive"))
      (is (= ["contributor"] (roles-of eng "cole"))))
    (testing "a seat's agent with no acts-for keeps its grant rules"
      (is (roles/manager? (assoc (inv/render-hooks eng)
                                 :principal (t/principal {:id "code-seat" :type :agent})))))
    (testing "a delegate's scope is its person's"
      (is (not (roles/manager? (assoc (inv/render-hooks eng)
                                      :principal (:principal (delegate "cole"))))))
      (is (= (set (roles/effective-scope eng (t/principal {:id "cole" :display "cole"})))
             (set (roles/effective-scope eng (:principal (delegate "cole"))))))
      (is (= (set (roles/effective-scope eng (t/principal {:id "mara" :display "mara"})))
             (set (roles/effective-scope eng (:principal (delegate "mara")))))))))

(deftest only-a-manager-sets-a-provider-cap-or-a-secret
  ;; ticket 3a5b43b3 (R-3.2): what spends money or holds a credential
  (let [eng (eng!)]
    (rl/ensure-providers! eng)
    (member! eng "olive" nil)
    (member! eng "mara" ["manager"])
    (member! eng "cole" ["contributor"])
    (let [d (factory! eng)]
      (inv/invoke! eng :domain (str (:id d)) :restate {:owner "olive"}
                   (assoc (as "olive")
                          :if-match (inv/etag :domain (str (:id d)) (:version d)))))
    (testing "the runner provider's cap"
      (let [cap! (fn [who]
                   (let [row (rl/provider-row eng "claude_routine")
                         id (str (:id row))]
                     (inv/invoke! eng :runner_provider id :restate {:cap nil}
                                  (assoc (as who)
                                         :if-match (inv/etag :runner_provider id
                                                             (:version row))))))]
        (is (re-find #"`manager` role" (pr-str (refusal #(cap! "cole"))))
            "a contributor is refused, and the refusal names the role")
        (is (nil? (refusal #(cap! "mara"))) "a manager passes")))
    (testing "a secret's create"
      (is (re-find #"`manager` role"
                   (pr-str (refusal #(inv/create! eng :secret
                                                  {:name "cole-token" :value "sk-cole"}
                                                  (as "cole"))))))
      (is (empty? (rows-of eng :secret {:name "cole-token"}))))
    (testing "a secret's replace"
      (let [s (:row (inv/create! eng :secret {:name "mara-token" :value "sk-mara"}
                                 (as "mara")))
            replace! (fn [who v]
                       (inv/invoke! eng :secret (str (:id s)) :replace {:value v}
                                    (assoc (as who)
                                           :if-match (inv/etag :secret (str (:id s))
                                                               (:version s)))))]
        (is (some? s) "a manager makes one")
        (is (re-find #"`manager` role" (pr-str (refusal #(replace! "cole" "sk-cole")))))
        (is (nil? (refusal #(replace! "mara" "sk-mara-2"))) "a manager passes")))))

;; ── effective scope ──────────────────────────────────────────────────

(defresource gadget
  {:kind :gadget
   :plural "gadgets"
   :states [:open :shut]
   :initial :open
   :summary "{data.name} · {state}"
   :schema [:map [:name {:x-display {:label "Name"}} [:string {:min 1 :max 120}]]]
   :actions {:shut {:from #{:open} :to :shut
                    :guards [roles/a-manager]
                    :safety {:idempotent true :reversible true :confirm false}
                    :display {:label "Shut"}}
             :reopen {:from #{:shut} :to :open
                      :safety {:idempotent true :reversible true :confirm false}
                      :display {:label "Reopen"}}}})

(defn- whole [eng]
  (into {} (for [[k r] (inv/resources eng)] [(name k) (set (inv/action-names r))])))

(defn- scope-of [eng id]
  (into {} (for [e (roles/effective-scope eng (t/principal {:id id :display id}))]
             [(:kind e) (set (:actions e))])))

(defn- owned-factory! [eng]
  (member! eng "olive" nil)
  (member! eng "mara" ["manager"])
  (member! eng "cole" ["contributor"])
  (let [d (factory! eng)]
    (inv/invoke! eng :domain (str (:id d)) :restate {:owner "olive"}
                 (assoc (as "olive")
                        :if-match (inv/etag :domain (str (:id d)) (:version d))))))

(deftest only-a-manager-allows-a-held-merge
  (let [eng (eng!)]
    (owned-factory! eng)
    (testing "a contributor is refused at Allow, and the refusal names the role"
      (let [id (held! eng "bench__merge")
            r (refusal #(allow! eng id "cole"))]
        (is (some? r))
        (is (re-find #"`manager` role" (pr-str r)))))
    (testing "a manager allows it"
      (is (= :allowed (keyword (allow! eng (held! eng "bench__merge") "mara")))))
    (testing "the owner allows it"
      (is (= :allowed (keyword (allow! eng (held! eng "bench__merge") "olive")))))
    (testing "allow on a held call for another tool is unchanged"
      (is (= :allowed (keyword (allow! eng (held! eng "emila__send") "cole")))))
    (testing "refuse on a held merge is unchanged"
      (let [id (held! eng "bench__merge")]
        (inv/invoke! eng :held_call id :refuse {:reason "Not this one."}
                     {:principal (t/principal {:id "cole" :display "cole"
                                               :roles #{"approver"}})})))))

(deftest with-no-owner-a-contributor-allows-a-held-merge
  (let [eng (eng!)]
    (factory! eng)
    (member! eng "cole" ["contributor"])
    (is (= :allowed (keyword (allow! eng (held! eng "bench__merge") "cole"))))))

(deftest a-managers-scope-is-the-whole-engine
  (let [eng (eng!)]
    (testing "while the factory domain has no owner, every person"
      (is (= (whole eng) (scope-of eng "cole"))))
    (owned-factory! eng)
    (testing "the owner, and a member holding manager"
      (is (= (whole eng) (scope-of eng "olive")))
      (is (= (whole eng) (scope-of eng "mara"))))
    (testing "each entry is a grant scope entry: kind and actions"
      (is (every? #(= #{:kind :actions} (set (keys %)))
                  (roles/effective-scope eng (t/principal {:id "mara" :display "mara"})))))))

(defn- but-manager-doors
  "The whole engine less the doors `a-manager` guards: the factory
  role's, a runner provider's (ticket 3a5b43b3) and a secret's."
  [w]
  (-> w
      (update "member" disj "set_factory_role")
      (update "runner_provider" disj "create" "restate")
      (update "secret" disj "create" "replace")))

(deftest a-contributors-scope-drops-only-the-manager-doors
  (let [eng (eng!)]
    (owned-factory! eng)
    (is (contains? (get (whole eng) "member") "set_factory_role"))
    (is (= (but-manager-doors (whole eng))
           (scope-of eng "cole")))))

(deftest wiring-a-manager-onto-a-door-narrows-a-contributor
  (let [eng (eng! [gadget])]
    (owned-factory! eng)
    (is (contains? (get (scope-of eng "mara") "gadget") "shut"))
    (is (= (-> (whole eng)
               but-manager-doors
               (update "gadget" disj "shut"))
           (scope-of eng "cole")))))
