(ns factory10.requester-test
  "A requester files tickets and sees only their own (epic 46831b3c,
  R-3.4). A ticket a person holding `requester` files lands a draft at
  priority 4 with `filed_by` set and `requested_by` untouched; the
  sight that person arrives with is the ticket kind narrowed to their
  own rows and its create door, with seats, sittings and changes
  absent; and a person with no role still arrives unscoped. In memory.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.roles :as roles]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(defn- person [id] (t/principal {:id id :display id}))

(defn- as [id] {:principal (person id)})

(defn- world []
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))})]
    (roles/ensure-factory-roles! eng)
    (inv/create! eng :repo_policy {:repository "ckopsa/waymark"} (as "olive"))
    eng))

(defn- member! [eng id roles']
  (:row (inv/create! eng :member
                     (cond-> {:display id :actor_type "human"}
                       (seq roles') (assoc :roles roles'))
                     {:principal members/registrar :id id})))

(defn- file! [eng who title]
  (:row (inv/create! eng :ticket {:title title
                                  :type "task"
                                  :priority 1
                                  :repo "ckopsa/waymark"}
                     (as who))))

(deftest a-requesters-ticket-lands-a-draft-at-four
  (let [eng (world)
        _ (member! eng "sam" [roles/requester])
        row (file! eng "sam" "The dishwasher light blinks")]
    (is (= "draft" (name (:state row))))
    (is (= 4 (get-in row [:data :priority])) "whatever they asked")
    (is (= 1 (get-in row [:data :asked_priority])) "the ask is kept")
    (is (= "sam" (get-in row [:data :filed_by])) "the person is stamped")
    (is (nil? (get-in row [:data :requested_by]))
        "requested_by still names a domain, and no domain asked")))

(deftest a-person-with-no-role-files-as-before
  (let [eng (world)
        _ (member! eng "olive" [])
        row (file! eng "olive" "A plain ask")]
    (is (= 1 (get-in row [:data :priority])))
    (is (nil? (get-in row [:data :asked_priority])))
    (is (= "olive" (get-in row [:data :filed_by])))))

(deftest a-requester-sees-only-the-tickets-they-filed
  (let [eng (world)
        _ (member! eng "sam" [roles/requester])
        _ (member! eng "olive" [])
        mine (str (:id (file! eng "sam" "Mine")))
        theirs (str (:id (file! eng "olive" "Not mine")))
        vis (grants/unscoped-visibility eng (person "sam"))]
    (is (some? vis) "a requester is not unscoped")
    (testing "get: their own ticket is seen, another's is absent"
      (is ((:row? vis) :ticket mine))
      (is (not ((:row? vis) :ticket theirs))))
    (testing "query: the listing is narrowed to their filed_by"
      (is ((:kind? vis) :ticket))
      (is (not ((:whole-kind? vis) :ticket)))
      (is (= [{:target :data :field :filed_by :cast "text" :vis? true
               :op := :value "sam"}]
             ((:conds-of vis) :ticket))))
    (testing "the create door is theirs, and no other ticket door"
      (is ((:action? vis) :ticket :create))
      (is (not ((:action? vis) :ticket :groom))))
    (testing "seats, sittings and changes are absent"
      (doseq [k [:seat :change]]
        (is (not ((:kind? vis) k)) (name k)))
      ;; every named principal keeps the own-surface of its own
      ;; sittings; a requester holds none, so the listing is the
      ;; matches-nothing sentinel and no row
      (let [ids ((:ids-of vis) :sitting)]
        (is (and (some? ids) (every? #{"-none-"} ids)) (pr-str ids))))))

(defn- get-as [eng who uri-and-query]
  (let [[uri query] (str/split uri-and-query #"\?" 2)
        resp ((engine/handler eng)
              (cond-> {:request-method :get :uri uri
                       :headers {"x-waymark-principal" who}}
                query (assoc :query-string query)))]
    (assoc resp :json (some-> (:body resp) wire/read-json))))

(deftest a-requester-through-the-router
  (let [eng (world)
        _ (member! eng "sam" [roles/requester])
        _ (member! eng "olive" [])
        mine (str (:id (file! eng "sam" "Mine")))
        theirs (str (:id (file! eng "olive" "Not mine")))]
    ;; both tickets land drafts, and the collection's default filter
    ;; is state=open
    (testing "the ticket collection lists their own ticket and no other"
      (let [b (:json (get-as eng "sam" "/api/tickets?state=draft"))]
        (is (= [mine]
               (mapv #(last (str/split (str (:self %)) #"/"))
                     (get-in b [:data :items]))))))
    (testing "another's ticket is absent, their own is served"
      (is (= 200 (:status (get-as eng "sam" (str "/api/tickets/" mine)))))
      (is (= 404 (:status (get-as eng "sam" (str "/api/tickets/" theirs))))))
    (testing "seats are absent"
      (is (= 404 (:status (get-as eng "sam" "/api/seats")))))
    (testing "the asking door stays: their own-surface is kept"
      (is (= 200 (:status (get-as eng "sam" "/api/approval_requests")))))
    (testing "a person with no role still sees every ticket"
      (is (= 2 (count (get-in (:json (get-as eng "olive" "/api/tickets?state=draft"))
                              [:data :items])))))))

(deftest a-person-with-no-role-sees-what-they-see-today
  (let [eng (world)]
    (member! eng "olive" [])
    (is (nil? (grants/unscoped-visibility eng (person "olive"))))
    (is (nil? (grants/unscoped-visibility eng (person "nobody-yet"))))))

(deftest a-manager-gives-the-requester-role
  (let [eng (world)]
    (member! eng "sam" [])
    (inv/invoke! eng :member "sam" :set_factory_role {:role roles/requester}
                 (as "olive"))
    (is (some? (grants/unscoped-visibility eng (person "sam"))))))
