(ns waymark10.teams-test
  "Teams 1 (docs/spec-teams.md §2): a team is a row that confers its
  roles on its members while it is active. A retired team confers
  nothing; a duplicate active name and an unregistered role are
  refused; the doors are the house owner's and a `team-admin`'s. In
  memory."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.roles :as roles]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.teams :as teams]
            [waymark10.types :as t]))

(defn- eng! []
  (let [eng (engine/engine {:storage (memory/storage) :resources []})]
    (roles/ensure-factory-roles! eng)
    (teams/ensure-team-roles! eng)
    eng))

(defn- as [id] {:principal (t/principal {:id id :display id})})

(defn- member! [eng id roles']
  (:row (inv/create! eng :member
                     (cond-> {:display id :actor_type "human"}
                       (seq roles') (assoc :roles roles'))
                     {:principal members/registrar :id id})))

(defn- refusal [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- names? [re r] (boolean (re-find re (pr-str r))))

(defn- team! [eng who inp]
  (:row (inv/create! eng :team inp (as who))))

(defn- team-row [eng id]
  (let [rdef (get (inv/resources eng) :team)]
    (some->> (store/with-tx (:storage eng)
               (fn [tx] (store/load-row (:storage eng) tx :team (str id) {})))
             (inv/decode-row rdef))))

(defn- edit! [eng who id inp]
  (let [r (team-row eng id)]
    (inv/invoke! eng :team (str id) :edit inp
                 (assoc (as who)
                        :if-match (inv/etag :team (str id) (:version r))))))

(defn- retire! [eng who id]
  (inv/invoke! eng :team (str id) :retire nil (as who)))

(defn- restore! [eng who id]
  (inv/invoke! eng :team (str id) :restore nil (as who)))

(defn- confers [eng member-id]
  (teams/conferred-roles (inv/render-hooks eng) member-id))

(deftest a-retired-team-confers-nothing
  (let [eng (eng!)]
    (member! eng "luis" nil)
    (let [crew (team! eng "olive" {:name "Crew B" :members ["luis"]
                                   :roles ["contributor"]})]
      (testing "an active team confers its roles on its members"
        (is (= #{"contributor"} (confers eng "luis")))
        (is (= #{} (confers eng "ana"))))
      (testing "leaving the team takes the role away"
        (edit! eng "olive" (:id crew) {:members []})
        (is (= #{} (confers eng "luis")))
        (edit! eng "olive" (:id crew) {:members ["luis"]})
        (is (= "Crew B" (get-in (team-row eng (:id crew)) [:data :name]))))
      (testing "a retired team confers nothing"
        (retire! eng "olive" (:id crew))
        (is (= #{} (confers eng "luis"))))
      (testing "a restored one confers again"
        (restore! eng "olive" (:id crew))
        (is (= #{"contributor"} (confers eng "luis")))))))

(deftest a-duplicate-active-name-refuses
  (let [eng (eng!)
        a (team! eng "olive" {:name "Crew A" :members []})
        b (team! eng "olive" {:name "Crew B" :members []})]
    (testing "a second active team of one name is refused, naming it"
      (let [r (refusal #(team! eng "olive" {:name "Crew A" :members []}))]
        (is (some? r))
        (is (names? #"Crew A" r))))
    (testing "an edit onto a taken name is refused; keeping its own passes"
      (is (some? (refusal #(edit! eng "olive" (:id b) {:name "Crew A"}))))
      (edit! eng "olive" (:id a) {:name "Crew A" :note "Mornings."})
      (is (= "Mornings." (get-in (team-row eng (:id a)) [:data :note]))))
    (testing "a retired team's name is free again, and its restore waits"
      (retire! eng "olive" (:id a))
      (is (some? (:id (team! eng "olive" {:name "Crew A" :members []}))))
      (let [r (refusal #(restore! eng "olive" (:id a)))]
        (is (some? r))
        (is (names? #"Crew A" r))))))

(deftest an-unregistered-role-refuses
  (let [eng (eng!)]
    (testing "a role no row registers is refused, naming it"
      (let [r (refusal #(team! eng "olive" {:name "Therapists" :members []
                                            :roles ["licensed-pt"]}))]
        (is (some? r))
        (is (names? #"licensed-pt" r))))
    (testing "registered, it passes"
      (inv/create! eng :role {:name "licensed-pt"} {:principal roles/seed-actor})
      (let [tm (team! eng "olive" {:name "Therapists" :members []
                                   :roles ["licensed-pt"]})]
        (is (some? (:id tm)))
        (testing "and an edit naming an unregistered one is refused"
          (is (some? (refusal #(edit! eng "olive" (:id tm)
                                      {:roles ["licensed-pt" "massage"]})))))))))

(deftest team-admin-holds-the-doors
  (let [eng (eng!)]
    (member! eng "olive" nil)
    (member! eng "cole" ["contributor"])
    (member! eng "tess" [teams/team-admin])
    (member! eng "dana" nil)
    (inv/create! eng :domain {:name "factory" :charter "Some work."
                              :budget_usd_per_week 40M :owner "olive"}
                 (as "olive"))
    (testing "a member without the role is refused at create, naming it"
      (let [r (refusal #(team! eng "cole" {:name "Crew A" :members []}))]
        (is (some? r))
        (is (names? #"team-admin" r))))
    (let [crew (team! eng "tess" {:name "Crew A" :members ["cole"]})]
      (testing "a team-admin and the owner pass"
        (is (some? (:id crew)))
        (is (some? (:id (team! eng "olive" {:name "Crew B" :members []})))))
      (testing "a member without the role is refused at edit and retire"
        (is (some? (refusal #(edit! eng "cole" (:id crew) {:note "Mine."}))))
        (is (some? (refusal #(retire! eng "cole" (:id crew))))))
      (let [admins (team! eng "tess" {:name "Admins" :members ["dana"]
                                      :roles [teams/team-admin]})]
        (testing "a team that confers team-admin opens the doors"
          (edit! eng "dana" (:id crew) {:note "Mornings."})
          (is (= "Mornings." (get-in (team-row eng (:id crew)) [:data :note]))))
        (testing "and retired, it opens nothing"
          (retire! eng "tess" (:id admins))
          (is (some? (refusal #(edit! eng "dana" (:id crew) {:note "Evenings."})))))))))
