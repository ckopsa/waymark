(ns waymark10.integrations-test
  "The integration (docs/spec-integrations.md § 2, build step 1): a
  person proposes it, another person approves it, and the approve
  mints the grant it wears as `integration:<id>`. Memory storage, real
  rows through the engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.engine :as engine]
            [waymark10.server.held-calls :as held]
            [waymark10.server.integrations :as integrations]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private clock (Instant/parse "2026-10-21T09:00:00Z"))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(def ^:private mom (t/principal {:id "mom" :display "Mom"}))

(def ^:private clerk
  (t/principal {:id "stripe-clerk" :type :agent :display "Clerk"}))

(def ^:private mayor
  (assoc (t/principal {:id "seat:mayor" :type :agent :display "mayor (seat)"})
         :acts-for "colton"))

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage)
                  :resources []
                  :now-fn (fn [] clock)}))

(defn- row-of [eng kind id]
  (let [rdef (get (inv/resources eng) kind)]
    (inv/decode-row rdef
                    (store/with-tx (:storage eng)
                      (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))))

(defn- refused
  "The problem a thunk throws, or nil when it answered."
  [thunk]
  (try (thunk) nil (catch Exception e e)))

(defn- propose! [eng]
  (str (get-in (inv/create! eng :integration
                            {:name "stripe"
                             :system "Stripe, the payment processor"
                             :event_id_path ["id"]
                             :event_type_path ["type"]}
                            {:principal colton})
               [:row :id])))

(deftest a-person-proposes-it
  (let [eng (fresh-engine)
        id (propose! eng)
        row (row-of eng :integration id)]
    (is (= :proposed (:state row)))
    (is (= "colton" (get-in row [:data :created_by])))
    (is (nil? (get-in row [:data :grant])) "no grant before the approve")
    (testing "and an agent does not"
      (is (some? (refused #(inv/create! eng :integration
                                        {:name "shippo" :system "Shippo, the postage"}
                                        {:principal clerk})))))))

(deftest the-creator-cannot-approve
  (let [eng (fresh-engine)
        id (propose! eng)
        e (refused #(inv/invoke! eng :integration id :approve {}
                                 {:principal colton}))]
    (is (some? e))
    (is (str/includes? (str (ex-message e)) "cannot be the one who approves"))
    (is (= :proposed (:state (row-of eng :integration id))) "and the row did not move")
    (is (nil? (get-in (row-of eng :integration id) [:data :grant])))
    (testing "nor does an agent"
      (is (some? (refused #(inv/invoke! eng :integration id :approve {}
                                        {:principal clerk}))))
      (is (= :proposed (:state (row-of eng :integration id)))))))

(deftest approve-mints-a-grant
  (let [eng (fresh-engine)
        id (propose! eng)
        _ (inv/invoke! eng :integration id :approve {} {:principal mom})
        row (row-of eng :integration id)
        gid (get-in row [:data :grant])
        grant (row-of eng :grant gid)]
    (is (= :live (:state row)))
    (is (= "mom" (get-in row [:data :approved_by])))
    (is (some? gid) "the row names the grant it wears")
    (is (= (str "integration:" id) (get-in grant [:data :audience]))
        "the grant is the integration's own, not the approver's")
    (is (= (integrations/principal-id row) (get-in grant [:data :audience])))
    (is (= :accepted (:state grant)) "and it is live")))

(def ^:private vcs-effects
  {:name "vcs-effects"
   :system "The vcs server, for a release's effects"
   :event_id_path ["id"]
   :event_type_path ["type"]})

(defn- integration-named [eng n]
  (let [rdef (get (inv/resources eng) :integration)]
    (some->> (store/with-tx (:storage eng)
               (fn [tx] (store/query-rows (:storage eng) tx :integration
                                          {:name n} {:limit 10})))
             first
             (inv/decode-row rdef))))

(deftest an-agents-proposal-is-held-for-its-person
  (let [eng (fresh-engine)
        e (refused #(inv/create! eng :integration vcs-effects {:principal mayor}))
        guard (:guard (ex-data e))]
    (testing "an agent acting for a person is held, not refused"
      (is (= :a-person-or-their-agent-proposes (some-> guard name keyword))
          (pr-str (ex-data e)))
      (is (delegation/hold-guard? guard) "so the router mints a held call")
      (is (nil? (get-in (ex-data e) [:evidence :refused])) "and does not let the refusal stand")
      (is (= "colton" (delegation/hold-owner {:principal mayor})))
      (is (nil? (integration-named eng "vcs-effects")) "nothing is made yet"))
    (let [h (held/hold-door! eng {:kind :integration :action :create
                                  :body vcs-effects
                                  :caller "seat:mayor" :owner "colton"
                                  :why "a-person-or-their-agent-proposes"})
          hid (str (:id h))
          held-rdef (get (inv/resources eng) :held_call)]
      (testing "a forged :within, naming a call nobody allowed, is refused"
        (is (some? (refused #(inv/create! eng :integration vcs-effects
                                          {:principal mayor
                                           :within {:kind :held_call :action :allow
                                                    :id hid}}))))
        (is (nil? (integration-named eng "vcs-effects"))))
      (testing "the person's Allow runs it, as the agent's proposal"
        (let [out (inv/invoke! eng :held_call hid :allow {} {:principal colton})]
          (held/after-allow! eng held-rdef :allow out))
        (is (= :done (:state (row-of eng :held_call hid)))
            (pr-str (:data (row-of eng :held_call hid))))
        (let [row (integration-named eng "vcs-effects")]
          (is (= :proposed (:state row)))
          (is (= "seat:mayor" (get-in row [:data :created_by])) "the agent proposed")
          (is (= "colton" (get-in row [:data :allowed_by])) "the person allowed")))
      (testing "and that person may approve it: the agent proposed, the person decides"
        (let [id (str (:id (integration-named eng "vcs-effects")))]
          (inv/invoke! eng :integration id :approve {} {:principal colton})
          (let [row (row-of eng :integration id)]
            (is (= :live (:state row)))
            (is (= "colton" (get-in row [:data :approved_by])))
            (is (some? (get-in row [:data :grant])))))))))

(deftest an-agent-for-nobody-is-refused
  (let [eng (fresh-engine)]
    (doseq [p [clerk
               (t/principal {:id "integration:abc" :type :agent :display "stripe"})
               (assoc (t/principal {:id "integration:abc" :type :agent :display "stripe"})
                      :acts-for "colton")]]
      (let [e (refused #(inv/create! eng :integration vcs-effects {:principal p}))]
        (is (some? e) (str (:id p)))
        (is (some? (get-in (ex-data e) [:evidence :refused]))
            (str (:id p) ": the refusal stands, nothing is held"))))
    (is (nil? (integration-named eng "vcs-effects")))))

(deftest a-direct-create-still-cannot-approve-its-own
  (let [eng (fresh-engine)
        id (str (get-in (inv/create! eng :integration vcs-effects {:principal colton})
                        [:row :id]))]
    (is (nil? (get-in (row-of eng :integration id) [:data :allowed_by])))
    (is (some? (refused #(inv/invoke! eng :integration id :approve {}
                                      {:principal colton}))))
    (is (= :proposed (:state (row-of eng :integration id))))))
