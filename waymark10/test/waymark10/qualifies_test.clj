(ns waymark10.qualifies-test
  "Who may be named (docs/spec-teams.md §3): a job's crew is a list of
  member refs that must hold `landscaper`, and its lead must be on the
  crew's team, read from the proposed crew."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.guards :as g]
            [waymark10.qualifies :as qualifies]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.types :as t]))

(def ^:private safety
  {:idempotent true :reversible false :confirm false :one-way "Assigned is assigned."})

(def ^:private job
  {:kind :job
   :plural "jobs"
   :states [:open :done]
   :initial :open
   :terminal #{:done}
   :summary "{data.site} · {state}"
   :schema [:map
            [:site [:string {:min 1 :max 80}]]
            [:team {:optional true} [:maybe :string]]
            [:hands {:optional true :kind :member :role "landscaper"} [:vector :waymark/ref]]
            [:lead {:optional true :kind :member :in-team '(data :team)} [:maybe :waymark/ref]]]
   :actions {:assign {:from #{:open} :to :open :safety safety
                      :input [:map
                              [:team {:optional true} [:maybe :string]]
                              [:hands {:optional true} [:vector :string]]
                              [:lead {:optional true} [:maybe :string]]]}
             :finish {:from #{:open} :to :done :safety safety}}})

(def ^:private members
  {"ana" {:id "ana" :data {:display "Ana Lopez" :roles ["landscaper"]}}
   "luis" {:id "luis" :data {:display "Luis Ortega" :roles []}}
   "dana" {:id "dana" :data {:display "Dana Ruiz" :roles ["landscaper"]}}
   "sam" {:id "sam" :data {:display "Sam Lee" :roles []}}})

(def ^:private teams
  [{:id "crew-b" :state "active"
    :data {:name "Crew B" :members ["ana" "luis"] :roles ["landscaper"]}}])

(def ^:private ctx
  {:read (fn [kind id] (when (= :member kind) (get members (str id))))
   :find (fn [kind where _]
           (case kind
             :team (filter #(= (:state where) (:state %)) teams)
             :member []
             []))})

(defn- guard-on [w door gname]
  (some #(when (= gname (:name %)) %)
        (if (= :create door)
          (get-in w [:job :create-guards])
          (get-in w [:job :actions door :guards]))))

(defn- judge [gd row inp]
  (first (g/evaluate gd row inp ctx)))

(deftest member-refs-say-who-may-be-named
  (let [w (qualifies/derive-kinds {:job (r/resource job)})
        hands (guard-on w :assign :hands-role)
        lead (guard-on w :assign :lead-in-team)
        row {:id "job-1" :data {:site "The Hendersons' patio" :team "Crew B"}}]
    (testing "the guards are named and ride the doors that carry the field"
      (is (some? hands))
      (is (some? lead))
      (is (some? (guard-on w :create :hands-role)))
      (is (nil? (guard-on w :finish :hands-role)))
      (is (= w (qualifies/derive-kinds w)) "derived again: no second guard"))
    (testing "the remedy is the field's own picker, narrowed: the door again, at the field"
      (is (= [{:door :job/assign :fields [:hands]}] (:remedies hands)))
      (is (= [{:door :job/create :fields [:hands]}]
             (:remedies (guard-on w :create :hands-role)))))
    (testing "a vector ref refuses its one unqualified element by name"
      (let [v (judge hands row {:hands ["ana" "sam" "dana"]})]
        (is (t/deny? v))
        (is (= "Sam Lee does not hold landscaper." (get-in v [:vars :problem])))))
    (testing "a role held through a team qualifies"
      (is (t/allow? (judge hands row {:hands ["ana" "luis" "dana"]}))))
    (testing "the lead must be on the crew, and the refusal names who is"
      (is (t/allow? (judge lead row {:lead "luis"})))
      (let [v (judge lead row {:lead "dana"})]
        (is (t/deny? v))
        (is (= "Dana Ruiz is not on Crew B. Crew B is Ana Lopez and Luis Ortega."
               (get-in v [:vars :problem])))))
    (testing "(data :team) reads the proposed team"
      (let [v (judge lead row {:team "Crew C" :lead "luis"})]
        (is (t/deny? v))
        (is (= "Luis Ortega is not on Crew C. No active team is Crew C."
               (get-in v [:vars :problem])))))
    (testing "a call that does not name the field, or a ctx with no reads, refuses nothing"
      (is (t/allow? (judge hands row {:team "Crew B"})))
      (is (t/allow? (first (g/evaluate hands row {:hands ["sam"]} {})))))
    (testing "the picker's x-ref advertises who qualifies"
      (let [entries (into {} (map (fn [[k e]] [k (:properties e)])) (schema/entry-map (:schema job)))]
        (is (= "landscaper" (:role (schema/ref-props (:hands entries)))))
        (is (= "(data :team)" (:in_team (schema/ref-props (:lead entries)))))))))

(deftest a-qualified-ref-is-a-member-ref
  (is (thrown? Exception
               (qualifies/derive-kinds
                {:job (r/resource (assoc-in job [:schema 4 1 :kind] :crew))}))))
