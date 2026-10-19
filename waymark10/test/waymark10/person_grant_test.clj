(ns waymark10.person-grant-test
  "A person who signs in wears their standing grant (ticket 152ff81d):
  with no X-Waymark-Grant header, `grants/unscoped-visibility` hands a
  human the grant that stands for them, exactly as it hands a delegate
  its worn one. A person with no grant is unscoped as before, and a
  person holding `approver` or `recovery-admin` is never narrowed this
  way. In memory."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :refer [defresource]]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private close
  {:from #{:open} :to :closed
   :safety {:idempotent true :reversible false :confirm false
            :one-way "A closed row keeps its history."}})

;; the household's org_plan, as small as the rule needs: a name to
;; scope a grant by
(defresource org-plan
  {:kind :org_plan
   :states [:open :closed]
   :initial :open
   :terminal #{:closed}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:min 1 :max 120}]]]
   :filterable {:state #{:eq :in} :name #{:eq}}
   :actions {:close close}})

(defresource other-kind
  {:kind :other_kind
   :states [:open :closed]
   :initial :open
   :terminal #{:closed}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:min 1 :max 120}]]]
   :filterable {:state #{:eq :in}}
   :actions {:close close}})

(defn- eng! []
  (engine/engine {:storage (memory/storage) :resources [org-plan other-kind]}))

(defn- member! [eng id roles']
  (:row (inv/create! eng :member
                     (cond-> {:display id :actor_type "human"}
                       (seq roles') (assoc :roles roles'))
                     {:principal members/registrar :id id})))

(defn- plan! [eng id nm]
  (:row (inv/create! eng :org_plan {:name nm}
                     {:principal members/registrar :id id})))

(defn- grant! [eng id audience]
  (inv/create! eng :grant
               {:audience audience
                :scope [{:kind "org_plan" :actions []
                         :filter {:name "X"}}]}
               {:principal grants/approvals-actor :id id :mint? true}))

(defn- person [id] (t/principal {:id id :display id}))

(deftest a-person-with-a-standing-grant-wears-it
  (let [eng (eng!)]
    (member! eng "brother" nil)
    (plan! eng "plan-x" "X")
    (plan! eng "plan-y" "Y")
    (inv/create! eng :other_kind {:name "X"}
                 {:principal members/registrar :id "other-1"})
    (grant! eng "grant-brother" "brother")
    (let [vis (grants/unscoped-visibility eng (person "brother"))]
      (testing "an offered grant is accepted on first arrival and worn"
        (is (some? vis) "a person with a grant is not unscoped")
        (is (= :accepted (:state (grants/standing-grant-for eng "brother")))))
      (testing "they see only the org_plan rows named X"
        (is ((:kind? vis) :org_plan))
        (is ((:row? vis) :org_plan "plan-x"))
        (is (not ((:row? vis) :org_plan "plan-y"))))
      (testing "and no other kind"
        (is (not ((:row? vis) :other_kind "other-1")))))))

(deftest a-person-with-no-grant-is-unscoped-as-today
  (let [eng (eng!)]
    (member! eng "sister" nil)
    (is (nil? (grants/unscoped-visibility eng (person "sister"))))))

(deftest an-agent-member-is-not-narrowed-as-a-person
  (let [eng (eng!)]
    (inv/create! eng :member {:display "juniper" :actor_type "agent"}
                 {:principal members/registrar :id "juniper"})
    (grant! eng "grant-juniper" "juniper")
    (is (nil? (grants/unscoped-visibility eng (person "juniper")))
        "an agent's member row named with no actor type keeps today's sight")))

(deftest an-approver-is-never-narrowed-by-a-grant
  (doseq [role ["approver" "recovery-admin"]]
    (testing role
      (let [eng (eng!)]
        (inv/create! eng :role {:name role}
                     {:principal members/registrar :id (str "role-" role)})
        (member! eng "owner" [role])
        (grant! eng "grant-owner" "owner")
        (is (nil? (grants/unscoped-visibility eng (person "owner")))
            "the owner keeps unscoped sight without the header")))))
