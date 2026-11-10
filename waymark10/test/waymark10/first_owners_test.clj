(ns waymark10.first-owners-test
  "WAYMARK10_FIRST_OWNERS (ticket 627ad2bf, spec-first-run R-4.8): an
  IdP-backed engine born empty gets its named first owners, and
  nothing else. No database: dev/scratch! is the world."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.members.first-owners :as first-owners]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(defn- oidc-engine
  "A scratch engine wearing an :oidc and invited-only membership, as an
  IdP-backed house does; ensure! reads only that :oidc is there."
  []
  (assoc (dev/scratch! []) :oidc {:issuer "https://idp.example/realms/pilot"}
         :members :invited-only))

(defn- members-of [eng]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/query-rows st tx :member {} {:limit 100})))))

(defn- refusal-type [eng id]
  (try (members/gate! eng (t/principal {:id id})) nil
       (catch Exception e (str (:type (ex-data e)) " " (ex-message e)))))

(deftest an-empty-oidc-engine-births-its-named-owners
  (let [eng (oidc-engine)
        born (first-owners/ensure! eng " sub-colton , sub-marco,, sub-colton")]
    (testing "two subjects give two active managers"
      (is (= #{"sub-colton" "sub-marco"} (set (map (comp str :id) born))))
      (doseq [m (members-of eng)]
        (is (= :active (:state m)))
        (is (= (str (:id m)) (get-in m [:data :subject])))
        (is (= #{"manager" "recovery-admin"} (set (get-in m [:data :roles]))))))
    (testing "each named owner enters, with the owner's roles"
      (doseq [id ["sub-colton" "sub-marco"]]
        (is (= #{"manager" "recovery-admin"}
               (set (:roles (members/gate! eng (t/principal {:id id}))))))))
    (testing "the birth is logged as the registrar's, from the variable"
      (let [ts (store/with-tx (:storage eng)
                 (fn [tx] (store/transitions (:storage eng) tx
                                             {:kind :member :resource-id "sub-marco"} {})))]
        (is (some #(and (= (:id members/registrar) (get-in % [:actor :id]))
                        (str/includes? (str (get-in % [:actor :why]))
                                       "WAYMARK10_FIRST_OWNERS"))
                  ts))))
    (testing "a third, unnamed subject still gets membership-invited"
      (is (some-> (refusal-type eng "sub-stranger") (str/includes? "invited"))))))

(deftest a-store-with-members-ignores-the-variable
  (let [eng (oidc-engine)]
    (inv/create! eng :member {:display "ada" :actor_type "human" :subject "ada"}
                 {:principal members/registrar :id "ada"})
    (is (nil? (first-owners/ensure! eng "sub-colton")))
    (is (= ["ada"] (map (comp str :id) (members-of eng))))
    (is (some-> (refusal-type eng "sub-colton") (str/includes? "invited")))))

(deftest without-oidc-it-does-nothing
  (let [eng (dev/scratch! [])]
    (is (nil? (first-owners/ensure! eng "sub-colton")))
    (is (empty? (members-of eng)))))

(deftest blank-or-malformed-entries-do-nothing
  (is (= {:subjects [] :malformed 1} (first-owners/subjects "")))
  (is (= {:subjects ["a"] :malformed 2} (first-owners/subjects "a, ,b c")))
  (let [eng (oidc-engine)]
    (is (nil? (first-owners/ensure! eng "  ,\t")))
    (is (nil? (first-owners/ensure! eng nil)))
    (is (empty? (members-of eng)))))
