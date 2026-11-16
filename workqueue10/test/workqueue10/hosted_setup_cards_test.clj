(ns workqueue10.hosted-setup-cards-test
  "A fresh hosted engine's setup cards (ticket 8268d86e, spec-first-run
  R-4.8): WAYMARK10_FIRST_OWNERS's owners each get \"Setting up\", and
  the first of them the `house` plan and \"Next steps\", with no factory
  seed. No database: the in-memory twin.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.hosted-setup-cards-test"
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.domains :as domains]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members.first-owners :as first-owners]
            [waymark10.server.roles :as roles]
            [waymark10.server.runner-links :as runner-links]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.watches :as watches]
            [waymark10.types :as t]
            [workqueue10.main :as main]))

(def ^:private oidc {:app-url "https://work.example/"})

(defn- hosted
  "A fresh factory engine with :oidc, as the factory seed's tests boot
  one, and no seed loaded."
  []
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (main/factory-only-resources)
                            :suppress-mirror-refresh true})]
    (runner-links/ensure-providers! eng)
    (roles/ensure-factory-roles! eng)
    (domains/ensure-factory! eng)
    (assoc eng :oidc oidc :members :invited-only)))

(defn- who [id] (t/principal {:id id :display id :type :human}))

(defn- cards [eng id]
  (filterv #(contains? #{"Setting up" "Next steps"} (get-in % [:watch :name]))
           (:rows (watches/held-read eng nil (who id)))))

(defn- named [eng kind n]
  (filterv #(= n (get-in % [:data :name])) (dev/rows eng kind)))

(defn- house [eng] (first (named eng :org_plan "house")))

(def ^:private connect
  "Add this engine to Claude as a connector: https://work.example/api/-/mcp. The card clears when it has connected once.")

(deftest each-first-owner-gets-setup-cards-and-the-first-the-house
  (let [eng (hosted)
        _ (first-owners/ensure! eng "sub-a,sub-b")
        plan (house eng)]
    (testing "each owner's held read answers Connect your Claude, with the URL and no mayor"
      (doseq [id ["sub-a" "sub-b"]]
        (let [c (first (cards eng id))]
          (is (= ["member" id "Setting up"] ((juxt :kind :id #(get-in % [:watch :name])) c)) (pr-str c))
          (is (= connect (:note c)))
          (is (not (.contains (str (:note c)) "mayor"))))))
    (testing "the plan's owner also sees Say what the house is for; the other does not"
      (is (= [["org_plan" (str (:id plan)) "Next steps"]]
             (mapv (juxt :kind :id #(get-in % [:watch :name])) (rest (cards eng "sub-a")))))
      (is (= 1 (count (cards eng "sub-b")))))
    (testing "one house plan, drafting, with no brief, owned by the first owner, and no onboarding seat"
      (is (= 1 (count (named eng :org_plan "house"))))
      (is (= "drafting" (name (:state plan))))
      (is (empty? (str (get-in plan [:data :brief]))))
      (is (= "sub-a" (str (get-in plan [:data :owner]))))
      (is (empty? (named eng :seat "onboarding-house"))))))

(deftest the-brief-clears-its-card-and-moves-the-plan-to-its-onboarding-seat
  ;; a hosted engine has no bench powers and no model, so the seat
  ;; itself is not seated here: the plan's note says why instead
  (let [eng (hosted)
        _ (first-owners/ensure! eng "sub-a,sub-b")]
    (inv/invoke! eng :org_plan (str (:id (house eng))) :write_brief
                 {:brief "Our household: meals, chores and the week ahead."}
                 {:principal (who "sub-a")})
    (is (= ["member"] (mapv :kind (cards eng "sub-a"))))
    (let [note (get-in (house eng) [:data :onboarding_note])]
      (is (= "onboarding-house" (:subject note)) (pr-str note))
      (is (not (.contains (str (:sentence note)) "Write the brief")) (pr-str note)))))

(deftest a-required-card-stays-and-the-house-card-may-wait
  (let [eng (hosted)
        _ (first-owners/ensure! eng "sub-a")
        watch-id #(str (:id (first (named eng :watch %))))
        mark! (fn [watch action id kind]
                (try (inv/invoke! eng :watch (watch-id watch) action
                                  {:row_id id :row_kind kind} {:principal (who "sub-a")})
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e))))
        plan (str (:id (house eng)))
        shown #(set (map :id (cards eng "sub-a")))]
    (testing "Connect your Claude refuses dismiss"
      (is (some? (mark! "Setting up" :dismiss "sub-a" "member")))
      (is (contains? (shown) "sub-a")))
    (testing "Say what the house is for accepts dismiss and comes back with undismiss"
      (is (nil? (mark! "Next steps" :dismiss plan "org_plan")))
      (is (not (contains? (shown) plan)))
      (is (nil? (mark! "Next steps" :undismiss plan "org_plan")))
      (is (contains? (shown) plan)))))

(deftest a-second-boot-files-nothing-twice
  (let [eng (hosted)
        _ (first-owners/ensure! eng "sub-a,sub-b")
        before [(count (dev/rows eng :watch)) (count (dev/rows eng :org_plan))]]
    (is (nil? (first-owners/ensure! eng "sub-a,sub-b")))
    (is (= before [(count (dev/rows eng :watch)) (count (dev/rows eng :org_plan))]))
    (is (= 1 (count (named eng :watch "Next steps"))))
    (is (= 2 (count (named eng :watch "Setting up"))))))

(deftest a-boot-that-died-before-the-cards-is-resumed
  (let [eng (hosted)]
    (with-redefs [waymark10.server.members.setup-cards/file! (fn [_ _] nil)]
      (first-owners/ensure! eng "sub-a,sub-b"))
    (is (empty? (named eng :watch "Setting up")))
    (first-owners/ensure! eng "sub-a,sub-b")
    (is (= 2 (count (named eng :watch "Setting up"))))
    (is (= 1 (count (named eng :org_plan "house"))))))

(deftest an-engine-that-had-members-is-given-no-cards
  (let [eng (hosted)]
    (first-owners/ensure! eng "sub-x")
    (inv/create! eng :watch {:name "Mine" :cues [{:kind "member" :actions [] :states ["active"]}]}
                 {:principal (who "sub-x")})
    (let [before (count (dev/rows eng :watch))]
      (is (nil? (first-owners/ensure! eng "sub-a,sub-b")))
      (is (= before (count (dev/rows eng :watch))))
      (is (empty? (cards eng "sub-a"))))))

(deftest an-engine-without-org-plan-gets-setting-up-alone
  (let [eng (assoc (dev/scratch! []) :oidc oidc :members :invited-only)]
    (is (not (contains? (inv/resources eng) :org_plan)))
    (is (= 1 (count (first-owners/ensure! eng "sub-a"))))
    (is (= ["Setting up"] (mapv #(get-in % [:watch :name]) (cards eng "sub-a"))))
    (is (empty? (named eng :watch "Next steps")))))
