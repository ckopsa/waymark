(ns waymark10.member-hold-test
  "HC2 (ticket bed1f2f6): an agent's member suspend or reinstate is
  held for its person when that person is a manager, and the person's
  Allow runs it AS THE PERSON, the log naming the agent as
  `requested_by`. Suspending the agent's own person, and an agent
  whose person is no manager, are refused. Over the ring handler on a
  memory engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.roles :as roles]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(defn- world []
  (let [eng (dev/scratch! [])]
    (roles/ensure-factory-roles! eng)
    {:eng eng :h (dev/handler eng)}))

(defn- req [h method uri {:keys [body headers]}]
  (h (cond-> {:request-method method :uri uri :headers (or headers {})}
       body (assoc :body (wire/write-json body)))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(def ^:private person {"x-waymark-principal" "pat"})

(def ^:private helper "pat-helper")

(defn- as [id] {:principal (t/principal {:id id :display id})})

(defn- member! [eng id roles']
  (:row (inv/create! eng :member
                     (cond-> {:display id :actor_type "human"}
                       (seq roles') (assoc :roles roles'))
                     {:principal members/registrar :id id})))

(defn- member-state [eng id]
  (store/with-tx (:storage eng)
    (fn [tx] (some-> (store/load-row (:storage eng) tx :member (str id) {})
                     :state name))))

(defn- log-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/transitions (:storage eng) tx
                                {:kind kind :resource-id (str id)} {}))))

(defn- held-row [eng id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx :held_call (str id) {}))))

(defn- leash!
  "The helper's leash, minted by its person and accepted: it may
  suspend and reinstate members. → the headers it presents."
  [eng]
  (let [g (:row (inv/create! eng :grant
                             {:audience helper
                              :scope [{:kind "member" :actions ["suspend" "reinstate"]}]}
                             (as "pat")))
        agent (assoc (t/principal {:id helper :type :agent :display helper})
                     :acts-for "pat")]
    (inv/invoke! eng :grant (str (:id g)) :accept {} {:principal agent})
    {"x-waymark-principal" helper
     "x-waymark-actor-type" "agent"
     "x-waymark-acts-for" "pat"
     "x-waymark-grant" (str (:id g))}))

(defn- ask! [h as id action]
  (req h :post (str "/api/members/" id "/-/" action) {:headers as}))

(defn- by-action [eng id action]
  (some #(when (= action (some-> (:action %) name)) %) (log-of eng :member id)))

(deftest a-suspend-is-held-and-the-allow-is-the-persons
  (let [{:keys [eng h]} (world)
        _ (member! eng "pat" nil)
        _ (member! eng "sam" nil)
        hdrs (leash! eng)]
    (testing "held, not run or refused, and the held call says what changes"
      (let [asked (ask! h hdrs "sam" "suspend")
            hid (:held_call (json asked))]
        (is (= 202 (:status asked)) (pr-str (json asked)))
        (is (some? hid))
        (let [row (held-row eng hid)]
          (is (= "pat" (str (get-in row [:data :owner]))))
          (is (str/includes? (str (get-in row [:data :shown]))
                             "Suspend sam: they can no longer sign in")
              (pr-str (get-in row [:data :shown]))))
        (is (= "active" (member-state eng "sam")) "nothing written yet")
        (testing "the person's Allow suspends as the person"
          (req h :post (str "/api/held_calls/" hid "/-/allow") {:headers person})
          (is (= "suspended" (member-state eng "sam")) (pr-str (:data (held-row eng hid))))
          (let [made (by-action eng "sam" "suspend")]
            (is (= "pat" (str (get-in made [:actor :id]))) (pr-str (:actor made)))
            (is (= helper (str (get-in made [:actor :requested_by]))))))))
    (testing "a reinstate is held the same way, and the Allow reinstates"
      (let [asked (ask! h hdrs "sam" "reinstate")
            hid (:held_call (json asked))]
        (is (= 202 (:status asked)) (pr-str (json asked)))
        (is (str/includes? (str (get-in (held-row eng hid) [:data :shown]))
                           "Reinstate sam: they can sign in again"))
        (is (= "suspended" (member-state eng "sam")))
        (req h :post (str "/api/held_calls/" hid "/-/allow") {:headers person})
        (is (= "active" (member-state eng "sam")))
        (let [made (by-action eng "sam" "reinstate")]
          (is (= "pat" (str (get-in made [:actor :id]))) (pr-str (:actor made)))
          (is (= helper (str (get-in made [:actor :requested_by])))))))
    (testing "the person's Refuse writes nothing"
      (let [hid (:held_call (json (ask! h hdrs "sam" "suspend")))]
        (is (some? hid))
        (req h :post (str "/api/held_calls/" hid "/-/refuse")
             {:headers person :body {:reason "Not this one."}})
        (is (= "active" (member-state eng "sam")))))
    (testing "suspending the agent's own person is refused, not held"
      (let [asked (ask! h hdrs "pat" "suspend")]
        (is (= 409 (:status asked)) (pr-str (json asked)))
        (is (nil? (:held_call (json asked))))
        (is (= "active" (member-state eng "pat")))))))

(deftest an-agent-whose-person-is-no-manager-is-refused
  (let [{:keys [eng h]} (world)
        _ (member! eng "pat" ["contributor"])
        _ (member! eng "olive" nil)
        _ (member! eng "sam" nil)
        d (:row (inv/create! eng :domain {:name "factory"
                                          :charter "The engine's own work."
                                          :budget_usd_per_week 40M}
                             (as "olive")))
        hdrs (leash! eng)]
    (inv/invoke! eng :domain (str (:id d)) :restate {:owner "olive"}
                 (assoc (as "olive")
                        :if-match (inv/etag :domain (str (:id d)) (:version d))))
    (let [asked (ask! h hdrs "sam" "suspend")]
      (is (= 409 (:status asked)) (pr-str (json asked)))
      (is (nil? (:held_call (json asked))))
      (is (= "active" (member-state eng "sam"))))))
