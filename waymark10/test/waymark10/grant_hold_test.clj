(ns waymark10.grant-hold-test
  "HC1 (ticket b83e690d): an agent's grant create past its leash is
  held for its person when that person is a manager, and the person's
  Allow creates it AS THE PERSON, the log naming the agent as
  `requested_by`. A grant to the agent itself, and an agent whose
  person is no manager, are refused as before. Over the ring handler
  on a memory engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.grants :as grants]
            [waymark10.server.held-calls :as held]
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
  grants, and holds nothing else. → the headers it presents."
  [eng]
  (let [g (:row (inv/create! eng :grant
                             {:audience helper
                              :scope [{:kind "grant" :actions ["create"]}]}
                             (as "pat")))
        agent (assoc (t/principal {:id helper :type :agent :display helper})
                     :acts-for "pat")]
    (inv/invoke! eng :grant (str (:id g)) :accept {} {:principal agent})
    {"x-waymark-principal" helper
     "x-waymark-actor-type" "agent"
     "x-waymark-acts-for" "pat"
     "x-waymark-grant" (str (:id g))}))

(defn- ask! [h as audience]
  (req h :post "/api/grants"
       {:headers as
        :body {:audience audience
               :scope [{:kind "member" :actions []}]}}))

(deftest a-mint-past-the-leash-is-held-and-the-allow-is-the-persons
  (let [{:keys [eng h]} (world)
        _ (member! eng "pat" nil)
        hdrs (leash! eng)]
    (testing "held, not refused, and the held call says who gets what"
      (let [asked (ask! h hdrs "guest-1")
            hid (:held_call (json asked))]
        (is (= 202 (:status asked)) (pr-str (json asked)))
        (is (some? hid))
        (let [row (held-row eng hid)]
          (is (= "pat" (str (get-in row [:data :owner]))))
          (is (str/includes? (str (get-in row [:data :shown])) "Give guest-1: read member")
              (pr-str (get-in row [:data :shown]))))
        (is (empty? (rows-of eng :grant {:audience "guest-1"})) "nothing written yet")
        (testing "the person's Allow creates it as the person"
          (req h :post (str "/api/held_calls/" hid "/-/allow") {:headers person})
          (let [[g & more] (rows-of eng :grant {:audience "guest-1"})
                made (first (log-of eng :grant (:id g)))]
            (is (some? g))
            (is (empty? more))
            (is (= "pat" (str (get-in made [:actor :id]))) (pr-str (:actor made)))
            (is (= helper (str (get-in made [:actor :requested_by]))))))))
    (testing "the person's Refuse writes nothing"
      (let [hid (:held_call (json (ask! h hdrs "guest-2")))]
        (is (some? hid))
        (req h :post (str "/api/held_calls/" hid "/-/refuse")
             {:headers person :body {:reason "Not this one."}})
        (is (empty? (rows-of eng :grant {:audience "guest-2"})))))
    (testing "a grant to the asking agent itself is refused, not held"
      (let [asked (ask! h hdrs helper)]
        (is (= 409 (:status asked)) (pr-str (json asked)))
        (is (nil? (:held_call (json asked))))))))

(deftest an-agent-whose-person-is-no-manager-is-refused-as-before
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
    (let [asked (ask! h hdrs "guest-1")]
      (is (= 409 (:status asked)) (pr-str (json asked)))
      (is (nil? (:held_call (json asked))))
      (is (empty? (rows-of eng :grant {:audience "guest-1"}))))))

(deftest an-allow-the-person-could-not-make-directly-is-refused-at-allow
  (let [{:keys [eng h]} (world)
        _ (member! eng "pat" nil)
        ;; a create the person's own guards refuse: the kind is not real
        row (held/hold-door! eng {:kind :grant :action :create
                                  :body {:audience "guest-3"
                                         :scope [{:kind "no_such_kind" :actions []}]}
                                  :caller helper
                                  :owner "pat"})
        hid (str (:id row))]
    (req h :post (str "/api/held_calls/" hid "/-/allow") {:headers person})
    (let [after (held-row eng hid)]
      (is (= "failed" (some-> (:state after) name)) (pr-str (:data after))))
    (is (empty? (rows-of eng :grant {:audience "guest-3"})))))

(deftest a-held-grant-names-a-power-by-use-not-read
  (let [shown (#'grants/mint-shown
               {:audience "carson"
                :scope [{:kind "bench.edit" :actions []
                         :filter {:path "orgs/tax_desk/**"}}
                        {:kind "org_plan" :actions []
                         :filter {:name "tax_desk"}}]}
               {})]
    (is (str/includes? shown "use bench.edit where path orgs/tax_desk/**") shown)
    (is (str/includes? shown "read org_plan named tax_desk") shown)
    (is (not (str/includes? shown "read bench.edit")) shown)))
