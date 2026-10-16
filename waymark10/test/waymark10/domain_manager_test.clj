(ns waymark10.domain-manager-test
  "Only a manager makes a domain or restates its charter, budget, mayor
  or owner (ticket bf8336c5): `roles/a-manager` on the domain's create
  and restate doors. While the factory domain has no owner every person
  passes, so the first owner is settable; with one set a contributor is
  refused at each door, naming the role, and a manager passes. In
  memory."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.roles :as roles]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(defn- eng! []
  (let [eng (engine/engine {:storage (memory/storage) :resources []})]
    (roles/ensure-factory-roles! eng)
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

(defn- domain-row [eng id]
  (let [rdef (get (inv/resources eng) :domain)]
    (some->> (store/with-tx (:storage eng)
               (fn [tx] (store/load-row (:storage eng) tx :domain (str id) {})))
             (inv/decode-row rdef))))

(defn- create! [eng who name']
  (inv/create! eng :domain {:name name'
                            :charter "Some work."
                            :budget_usd_per_week 40M}
               (as who)))

(defn- restate! [eng who id inp]
  (let [d (domain-row eng id)]
    (inv/invoke! eng :domain (str id) :restate inp
                 (assoc (as who)
                        :if-match (inv/etag :domain (str id) (:version d))))))

(defn- names-the-role? [r]
  (boolean (re-find #"`manager` role" (pr-str r))))

(deftest the-first-owner-is-settable-while-none-is-set
  (let [eng (eng!)]
    (member! eng "olive" nil)
    (member! eng "cole" ["contributor"])
    (testing "a contributor makes the factory domain while no owner is set"
      (let [d (:row (create! eng "cole" "factory"))]
        (is (some? (:id d)))
        (testing "and sets its first owner"
          (restate! eng "cole" (:id d) {:owner "olive"})
          (is (= "olive" (some-> (domain-row eng (:id d)) :data :owner str))))))))

(deftest with-an-owner-only-a-manager-makes-or-restates-a-domain
  (let [eng (eng!)]
    (member! eng "olive" nil)
    (member! eng "mara" ["manager"])
    (member! eng "cole" ["contributor"])
    (let [f (:row (create! eng "olive" "factory"))
          fid (:id f)]
      (restate! eng "olive" fid {:owner "olive"})
      (testing "a contributor is refused at create, naming the role"
        (let [r (refusal #(create! eng "cole" "garden"))]
          (is (some? r))
          (is (names-the-role? r))))
      (testing "a manager makes a domain"
        (is (some? (:id (:row (create! eng "mara" "garden"))))))
      (testing "a contributor is refused at each restate, naming the role"
        (doseq [inp [{:charter "Another charter."}
                     {:budget_usd_per_week 50M}
                     {:mayor nil}
                     {:owner "cole"}]]
          (let [r (refusal #(restate! eng "cole" fid inp))]
            (is (some? r) (pr-str inp))
            (is (names-the-role? r) (pr-str inp))))
        (is (= "olive" (some-> (domain-row eng fid) :data :owner str))))
      (testing "a manager restates the charter, budget and owner"
        (restate! eng "mara" fid {:charter "Another charter."})
        (restate! eng "mara" fid {:budget_usd_per_week 50M})
        (restate! eng "mara" fid {:owner "mara"})
        (let [d (domain-row eng fid)]
          (is (= "Another charter." (get-in d [:data :charter])))
          (is (= 50M (bigdec (get-in d [:data :budget_usd_per_week]))))
          (is (= "mara" (some-> d :data :owner str)))))
      (testing "the owner passes"
        (restate! eng "mara" fid {:owner "olive"})
        (restate! eng "olive" fid {:charter "The owner's charter."})
        (is (= "The owner's charter."
               (get-in (domain-row eng fid) [:data :charter])))))))
