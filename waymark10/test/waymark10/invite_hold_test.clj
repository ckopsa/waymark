(ns waymark10.invite-hold-test
  "HC3 (ticket a052edca): an agent's invite (a member create with
  `invite` true) is held for its person when that person is a manager.
  The person's Allow creates the invited member and its grant AS THE
  PERSON, the engine mints the token, and the sign-in link is answered
  to the person alone: no agent read, held call or log carries it. An
  agent whose person is no manager is refused. Over the ring handler
  on a memory engine."
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

(defn- rows-of [eng kind where]
  (store/with-tx (:storage eng)
    (fn [tx] (store/query-rows (:storage eng) tx kind where {:limit 100}))))

(defn- log-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/transitions (:storage eng) tx
                                {:kind kind :resource-id (str id)} {}))))

(defn- held-row [eng id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx :held_call (str id) {}))))

(defn- leash!
  "The helper's leash, minted by its person and accepted: it may create
  members and read held calls, members and grants. → the headers it
  presents."
  [eng]
  (let [g (:row (inv/create! eng :grant
                             {:audience helper
                              :scope [{:kind "member" :actions ["create"]}
                                      {:kind "held_call" :actions []}
                                      {:kind "grant" :actions []}]}
                             (as "pat")))
        agent (assoc (t/principal {:id helper :type :agent :display helper})
                     :acts-for "pat")]
    (inv/invoke! eng :grant (str (:id g)) :accept {} {:principal agent})
    {"x-waymark-principal" helper
     "x-waymark-actor-type" "agent"
     "x-waymark-acts-for" "pat"
     "x-waymark-grant" (str (:id g))}))

(defn- invite! [h hdrs display]
  (req h :post "/api/members"
       {:headers hdrs
        :body {:display display :actor_type "human" :invite true
               :grant {:scope [{:kind "member" :actions []}]}}}))

(deftest an-agents-invite-is-held-and-the-allow-shows-the-link-to-the-person
  (let [{:keys [eng h]} (world)
        _ (member! eng "pat" nil)
        hdrs (leash! eng)
        asked (invite! h hdrs "Robin")
        hid (:held_call (json asked))]
    (testing "held, not refused, and the held call says who is invited and what they may do"
      (is (= 202 (:status asked)) (pr-str (json asked)))
      (is (some? hid))
      (let [shown (str (get-in (held-row eng hid) [:data :shown]))]
        (is (= "pat" (str (get-in (held-row eng hid) [:data :owner]))))
        (is (str/includes? shown "Invite Robin") shown)
        (is (str/includes? shown "read member") shown))
      (is (empty? (rows-of eng :member {:display "Robin"})) "nothing written yet"))
    (testing "the person's Allow makes the member and its grant as the person, and answers the link"
      (let [allowed (req h :post (str "/api/held_calls/" hid "/-/allow") {:headers person})
            link (:invite_link (json allowed))
            [m & more] (rows-of eng :member {:display "Robin"})
            token (str (get-in m [:data :bind_token]))
            made (first (log-of eng :member (:id m)))
            g (first (rows-of eng :grant {:audience (str (:id m))}))]
        (is (= 200 (:status allowed)) (pr-str (json allowed)))
        (is (some? m))
        (is (empty? more))
        (is (= :invited (:state m)))
        (is (<= 22 (count token)))
        (is (= (str "/auth/login?invite=" token) link))
        (is (= "pat" (str (get-in made [:actor :id]))) (pr-str (:actor made)))
        (is (= helper (str (get-in made [:actor :requested_by]))))
        (is (some? g) "the invitee's grant is made")
        (is (= "pat" (str (get-in (first (log-of eng :grant (:id g))) [:actor :id]))))
        (testing "no agent read, held call or log carries the token"
          (doseq [uri [(str "/api/members/" (:id m))
                       (str "/api/held_calls/" hid)
                       (str "/api/grants/" (:id g))]]
            (is (not (str/includes? (pr-str (json (req h :get uri {:headers hdrs}))) token))
                uri))
          (is (not (str/includes? (pr-str (held-row eng hid)) token)))
          (is (not (str/includes? (pr-str (log-of eng :member (:id m))) token)))
          (is (not (str/includes? (pr-str (log-of eng :grant (:id g))) token)))
          (is (not (str/includes? (pr-str (log-of eng :held_call hid)) token))))))
    (testing "the person's Refuse writes nothing"
      (let [hid (:held_call (json (invite! h hdrs "Sam")))]
        (is (some? hid))
        (req h :post (str "/api/held_calls/" hid "/-/refuse")
             {:headers person :body {:reason "Not this one."}})
        (is (empty? (rows-of eng :member {:display "Sam"})))))))

(deftest an-agent-whose-person-is-no-manager-is-refused
  (let [{:keys [eng h]} (world)
        _ (member! eng "pat" ["contributor"])
        _ (member! eng "olive" nil)
        d (:row (inv/create! eng :domain {:name "factory"
                                          :charter "The engine's own work."
                                          :budget_usd_per_week 40M}
                             (as "olive")))
        hdrs (leash! eng)]
    (inv/invoke! eng :domain (str (:id d)) :restate {:owner "olive"}
                 (assoc (as "olive")
                        :if-match (inv/etag :domain (str (:id d)) (:version d))))
    (let [asked (invite! h hdrs "Robin")]
      (is (= 409 (:status asked)) (pr-str (json asked)))
      (is (nil? (:held_call (json asked))))
      (is (empty? (rows-of eng :member {:display "Robin"}))))))
