(ns workqueue10.owner-mayor-test
  "The owner's Claude is the mayor from its first call (ticket a4f42818):
  on a seeded house, the owner's delegate through the house's Claude
  connector client wears the seeded `mayor` seat's grant with no key,
  no ask and no approval. Another person's delegate, and the owner's
  through another client, wear none.
  No database: the in-memory twin.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.owner-mayor-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.domains :as domains]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.roles :as roles]
            [waymark10.server.runner-links :as runner-links]
            [waymark10.server.seed :as seed]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire]
            [workqueue10.main :as main]))

(def ^:private params
  {:owner "tim"
   :repo "tim/widgets"
   ;; nothing listens here: the bench row is born dark
   :bench_url "http://127.0.0.1:9/mcp"
   :model "claude-opus-5"
   :pull_token "a-pull-token-0123456789"})

(def ^:private claude "house-connector-claude")
(def ^:private other "house-connector-other")

(defn- seeded
  "A factory-only engine, booted as the seed test boots one, with two
  delegate clients configured, the factory seed loaded on it."
  []
  (let [eng (-> (engine/engine {:storage (memory/storage)
                                :resources (main/factory-only-resources)
                                :suppress-mirror-refresh true})
                (assoc-in [:oidc :delegate-clients] {claude "Claude" other "Other"}))]
    (runner-links/ensure-providers! eng)
    (roles/ensure-factory-roles! eng)
    (domains/ensure-factory! eng)
    (is (true? (:seeded (seed/load! eng (seed/read-seed "factory") {:params params}))))
    eng))

(defn- named [eng kind n]
  (first (filter #(= n (get-in % [:data :name])) (dev/rows eng kind))))

(defn- delegate [client person]
  (assoc (t/principal {:id (oidc/delegate-id client person) :type :agent
                       :display (str "Claude for " person)})
         :acts-for person))

(defn- call
  "One tool call by `who`, its visibility resolved as the identity
  boundary resolves it for each request."
  [eng who tool args]
  (let [sid (mcp/open-session! eng)
        r (mcp/call-tool eng (mcp/door eng)
                         {:principal who :mcp-session-id sid
                          :visibility (grants/unscoped-visibility eng who)}
                         tool args)
        text (str (get-in r [:content 0 :text]))]
    {:ok (not (:isError r)) :text text}))

(defn- grants-of [eng who]
  (filterv #(= (str (:id who)) (str (get-in % [:data :audience]))) (dev/rows eng :grant)))

(deftest the-owners-claude-is-the-mayor-from-its-first-call
  (let [eng (seeded)
        mayor (named eng :seat "mayor")
        house (str (:id (named eng :org_plan "house")))
        who (delegate claude "tim")]
    (testing "discover names the mayor seat"
      (let [{:keys [ok text]} (call eng who "waymark_discover" {})
            doc (wire/read-json text)]
        (is ok text)
        (is (str/includes? (pr-str (get-in doc [:doors :ask :seat])) "mayor") text)))
    (testing "the grant it wears cites the mayor seat, minted once"
      (call eng who "waymark_discover" {})
      (let [gs (grants-of eng who)]
        (is (= 1 (count gs)) (pr-str gs))
        (is (= (str (:id mayor)) (str (get-in (first gs) [:data :seat]))))
        (is (= "accepted" (name (:state (first gs)))))
        (is (some #{(str "connector:" claude)}
                  (map str (get-in (named eng :seat "mayor") [:data :held_for]))))))
    (testing "the org_plan query lists house"
      (let [{:keys [ok text]} (call eng who "waymark_query" {:kind "org_plan"})]
        (is ok text)
        (is (str/includes? text house) text)))
    (testing "write_brief succeeds"
      (let [brief "Our household: meals, chores and the week ahead."
            {:keys [ok text]} (call eng who "waymark_invoke"
                                    {:kind "org_plan" :id house :action "write_brief"
                                     :input {:brief brief}})]
        (is ok text)
        (is (= brief (str (get-in (named eng :org_plan "house") [:data :brief]))) text)))))

(deftest nobody-elses-claude-is-the-mayor
  (let [eng (seeded)]
    (doseq [[why who] [["another person's delegate" (delegate claude "sam")]
                       ["the owner's delegate through another client" (delegate other "tim")]]]
      (testing why
        (let [{:keys [ok text]} (call eng who "waymark_discover" {})]
          (is ok text)
          (is (nil? (get-in (wire/read-json text) [:doors :ask :anchor :grant_id])) text))
        (is (empty? (grants-of eng who)))
        (is (not (:ok (call eng who "waymark_query" {:kind "org_plan"}))))))))

(deftest a-revoked-link-is-not-minted-again
  (let [eng (seeded)
        who (delegate claude "tim")]
    (call eng who "waymark_discover" {})
    (let [g (first (grants-of eng who))]
      (is (some? g))
      (inv/invoke! eng :grant (str (:id g)) :revoke {} {:principal grants/approvals-actor}))
    (call eng who "waymark_discover" {})
    (is (= ["revoked"] (mapv #(name (:state %)) (grants-of eng who))))))
