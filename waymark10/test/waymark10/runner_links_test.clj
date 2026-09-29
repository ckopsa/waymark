(ns waymark10.runner-links-test
  "The runner link (runner-pool work, piece 1a): a person makes one,
  its token is in no read and no log, a restate without a token keeps
  the old one, and an agent's hand is refused.

  Real Postgres. Needs the test database; WAYMARK10_TEST_DSN overrides
  the DSN."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.schema :as schema]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.server.runner-links :as rl]
            [waymark10.server.schedules :as sch]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:dynamic *eng* nil)

(def ^:private tables
  ["runner_links" "runner_providers" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*eng* (engine/engine {:storage st :resources []})]
          (rl/ensure-providers! *eng*)
          (f))
        (finally (pg/close! st))))))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))
(def ^:private clerk (t/principal {:id "clerk" :type :agent :display "Clerk"}))

(def ^:private a-url "https://api.example.test/v1/routines/trig_01/fire")
(def ^:private a-token "sk-routine-first-token-0123456789")
(def ^:private a-second-token "sk-routine-second-token-9876543210")

(defn- row-of [id]
  (let [rdef (get (inv/resources *eng*) :runner_link)]
    (some->> (store/with-tx (:storage *eng*)
               (fn [tx]
                 (store/load-row (:storage *eng*) tx :runner_link (str id) {})))
             (inv/decode-row rdef))))

(defn- log-of [id]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (store/transitions (:storage *eng*) tx
                         {:kind :runner_link :resource-id (str id)} {}))))

(defn- refusal [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

(defn- restate! [id body principal]
  (let [row (row-of id)]
    (inv/invoke! *eng* :runner_link id :restate body
                 {:principal principal
                  :if-match (inv/etag :runner_link id (:version row))})))

(defn- make-link! [principal]
  (:row (inv/create! *eng* :runner_link
                     {:provider "claude_routine"
                      :fire_url a-url
                      :fire_token a-token}
                     {:principal principal})))

(defn- make-localfire-link! [principal]
  (:row (inv/create! *eng* :runner_link
                     {:provider "localfire"
                      :fire_url a-url
                      :fire_token a-token}
                     {:principal principal})))

(deftest the-token-is-the-one-secret
  (is (= #{:fire_token} (schema/secret-fields (:schema rl/runner-link)))))

(deftest a-person-makes-a-link-and-its-token-is-never-read-back
  (let [link (make-link! colton)
        id (:id link)]
    (testing "the engine holds the token"
      (is (= "live" (name (:state (row-of id)))))
      (is (= a-token (get-in (row-of id) [:data :fire_token]))))
    (testing "no read carries it"
      (let [rdef (get (inv/resources *eng*) :runner_link)
            env (render/envelope rdef (row-of id)
                                 {:principal colton
                                  :now ((:now-fn *eng*))})]
        (is (not (contains? (get env "data") "fire_token")))
        (is (not (str/includes? (pr-str env) a-token)))))
    (testing "a restate without a token keeps the old one"
      (restate! id {:cap {:runs 5 :window_seconds 18000}} colton)
      (let [row (row-of id)]
        (is (= a-token (get-in row [:data :fire_token])))
        (is (= {:runs 5 :window_seconds 18000} (get-in row [:data :cap])))))
    (testing "a restate with a token replaces it"
      (restate! id {:token a-second-token} colton)
      (is (= a-second-token (get-in (row-of id) [:data :fire_token]))))
    (testing "an agent may not re-token"
      (is (= :a-person-writes-the-token
             (:guard (refusal #(restate! id {:token a-token} clerk)))))
      (is (= a-second-token (get-in (row-of id) [:data :fire_token]))))
    (testing "the log says what was done, without the value"
      (let [log (log-of id)]
        (is (some #(= :restate (:action %)) log))
        (is (not (str/includes? (pr-str log) a-token)))
        (is (not (str/includes? (pr-str log) a-second-token)))))
    (testing "retire and restore"
      (inv/invoke! *eng* :runner_link id :retire nil {:principal colton})
      (is (= "retired" (name (:state (row-of id)))))
      (inv/invoke! *eng* :runner_link id :restore nil {:principal colton})
      (is (= "live" (name (:state (row-of id))))))))

(defn- stub
  "A provider that answers `answer` to every fire."
  [answer]
  (reify sch/Provider
    (fire [_ _link _text] answer)))

(deftest a-fire-writes-what-the-provider-answered
  (testing "started stamps the fire and counts the run in the window"
    (let [id (:id (make-link! colton))]
      (restate! id {:cap {:runs 5 :window_seconds 18000}} colton)
      (is (= {:started "https://run.example.test/1"}
             (rl/fire-link! *eng* (stub {:started "https://run.example.test/1"})
                            (row-of id) "go")))
      (let [row (row-of id)]
        (is (= "live" (name (:state row))))
        (is (some? (get-in row [:data :last_fired_at])))
        (is (some? (get-in row [:data :window_started_at])))
        (is (= 1 (get-in row [:data :runs_in_window]))))
      (rl/fire-link! *eng* (stub {:started nil}) (row-of id) nil)
      (is (= 2 (get-in (row-of id) [:data :runs_in_window])))))
  (testing "throttled records retry_after and keeps the link live"
    (let [id (:id (make-link! colton))]
      (rl/fire-link! *eng* (stub {:throttled "30"}) (row-of id) nil)
      (let [row (row-of id)]
        (is (= "live" (name (:state row))))
        (is (some? (get-in row [:data :retry_after])))
        (is (nil? (get-in row [:data :last_fired_at]))))
      (testing "and the next run that starts clears it"
        (rl/fire-link! *eng* (stub {:started nil}) (row-of id) nil)
        (is (nil? (get-in (row-of id) [:data :retry_after]))))))
  (testing "bad-link marks the link broken"
    (let [id (:id (make-link! colton))]
      (rl/fire-link! *eng* (stub {:bad-link "The Routine refused the token."})
                     (row-of id) nil)
      (is (= "broken" (name (:state (row-of id)))))))
  (testing "no person may write the live state"
    (let [id (:id (make-link! colton))]
      (is (some? (refusal #(inv/invoke! *eng* :runner_link id :break nil
                                        {:principal colton}))))
      (is (= "live" (name (:state (row-of id))))))))

(deftest claude-routine-answers-in-three-words
  (let [fake (sch/fake-fire)
        p (sch/claude-routine fake)
        link {:fire_url a-url :fire_token a-token}]
    (testing "a 2xx is started, with the run's URL"
      (is (= "https://claude.ai/code/session_01fake1"
             (:started (sch/fire p link "go"))))
      (is (= a-token (:token (first (sch/fires fake))))))
    (testing "a 429 is throttled, with the provider's retry-after"
      (sch/answer! fake 429 {:retry-after "30"})
      (is (= "30" (:throttled (sch/fire p link nil)))))
    (testing "a 401 is a bad link, with the provider's sentence"
      (sch/answer! fake 401)
      (is (= "The Routine refused the token." (:bad-link (sch/fire p link nil)))))
    (testing "exactly one of the three words"
      (sch/answer! fake 404)
      (is (= 1 (count (select-keys (sch/fire p link nil)
                                   [:started :throttled :bad-link])))))))

(deftest an-agent-does-not-make-a-link
  (is (= :a-person-makes-the-link
         (:guard (refusal #(make-link! clerk))))))

(defn- counting
  "A provider that answers `answer` to every fire and counts them in `n`."
  [n answer]
  (reify sch/Provider
    (fire [_ _link _text] (swap! n inc) answer)))

(defn- link-id! [] (str (:id (make-link! colton))))

(defn- provider-row
  "The claude_routine provider row, as the store holds it now."
  []
  (let [id (str (:id (rl/provider-row *eng* "claude_routine")))
        rdef (get (inv/resources *eng*) :runner_provider)]
    (some->> (store/with-tx (:storage *eng*)
               (fn [tx]
                 (store/load-row (:storage *eng*) tx :runner_provider id {})))
             (inv/decode-row rdef))))

(defn- set-provider-cap! [cap]
  (let [row (provider-row)
        id (str (:id row))]
    (inv/invoke! *eng* :runner_provider id :restate {:cap cap}
                 {:principal colton
                  :if-match (inv/etag :runner_provider id (:version row))})))

(defn- open-provider-window!
  "The provider's window opened now with no runs and no throttle held,
  written through the engine's own door as a landing fire writes it."
  []
  (inv/invoke! *eng* :runner_provider (str (:id (provider-row))) :fired
               {:window_started_at (str (java.time.Instant/now))
                :runs_in_window 0}
               {:principal sch/system-actor}))

(deftest a-provider-cap-holds-every-pool-of-its-links
  (testing "a cap of 3 across two seats' pools: the 4th fire is held until the window closes"
    (open-provider-window!)
    (set-provider-cap! {:runs 3 :window_seconds 18000})
    (try
      (let [a (link-id!) b (link-id!) c (link-id!) d (link-id!)
            n (atom 0)
            go #(rl/fire-pool! *eng* (constantly (counting n {:started nil})) % nil)
            fired (mapv go [[a b] [c d] [a b]])
            held (go [c d])
            p (provider-row)
            closes (.plusSeconds (java.time.Instant/parse
                                  (str (get-in p [:data :window_started_at])))
                                 18000)]
        (is (every? :runner fired))
        (is (= 3 @n (get-in p [:data :runs_in_window])))
        (is (nil? (:runner held)))
        (is (= closes (:retry-at held))))
      (finally
        (set-provider-cap! nil)
        (open-provider-window!))))
  (testing "a throttle that names the account holds every link of it"
    (try
      (let [a (link-id!) b (link-id!) c (link-id!)
            n (atom 0)
            out (rl/fire-pool! *eng* (constantly (stub {:throttled "30" :scope :account}))
                               [a b] nil)]
        (is (nil? (:runner out)))
        (is (some? (:retry-at out)))
        (is (some? (get-in (provider-row) [:data :retry_after])))
        (is (nil? (get-in (row-of a) [:data :retry_after])))
        (is (nil? (get-in (row-of b) [:data :last_fired_at])))
        (testing "and another seat's pool sends nothing either"
          (is (nil? (:runner (rl/fire-pool! *eng* (constantly (counting n {:started nil}))
                                            [c] nil))))
          (is (zero? @n))))
      (finally (open-provider-window!))))
  (testing "a throttle that names no account holds only its link"
    (let [a (link-id!) b (link-id!)
          providers {a (stub {:throttled "30"}) b (stub {:started nil})}]
      (is (= b (:runner (rl/fire-pool! *eng* #(providers (str (:id %))) [a b] nil))))
      (is (some? (get-in (row-of a) [:data :retry_after])))
      (is (nil? (get-in (provider-row) [:data :retry_after]))))))

(deftest a-pool-skips-a-waiting-link-and-takes-the-least-used
  (testing "the first link throttles, so the wake goes out once, through the second"
    (let [a (link-id!) b (link-id!)
          fires {a (atom 0) b (atom 0)}
          providers {a (counting (fires a) {:throttled "30"})
                     b (counting (fires b) {:started nil})}
          provider-of #(providers (str (:id %)))]
      (is (= b (:runner (rl/fire-pool! *eng* provider-of [a b] "go"))))
      (is (= [1 1] [@(fires a) @(fires b)]))
      (is (some? (get-in (row-of a) [:data :retry_after])))
      (testing "and the next fire skips the waiting link"
        (is (= b (:runner (rl/fire-pool! *eng* provider-of [a b] nil))))
        (is (= 1 @(fires a))))))
  (testing "with every link throttled nothing starts, and the pool names the earlier time"
    (let [a (link-id!) b (link-id!)
          out (rl/fire-pool! *eng*
                             #(stub {:throttled (if (= a (str (:id %))) "30" "90")})
                             [a b] nil)
          later (java.time.Instant/parse
                 (str (get-in (row-of b) [:data :retry_after])))]
      (is (nil? (:runner out)))
      (is (some? (:retry-at out)))
      (is (.isBefore ^java.time.Instant (:retry-at out) later))
      (testing "and a second fire before that time sends nothing"
        (let [n (atom 0)]
          (is (nil? (:runner (rl/fire-pool! *eng* (constantly (counting n {:started nil}))
                                            [a b] nil))))
          (is (zero? @n))))))
  (testing "least-used selection alternates two links over four fires"
    (let [a (link-id!) b (link-id!)]
      (is (= [a b a b]
             (vec (repeatedly 4 #(:runner (rl/fire-pool! *eng* (constantly (stub {:started nil}))
                                                         [a b] nil))))))))
  (testing "a link whose cap is spent is skipped until its window closes"
    (let [a (link-id!) b (link-id!)]
      (restate! a {:cap {:runs 1 :window_seconds 18000}} colton)
      (is (= [a b b]
             (vec (repeatedly 3 #(:runner (rl/fire-pool! *eng* (constantly (stub {:started nil}))
                                                         [a b] nil))))))))
  (testing "a broken or missing link is skipped, and a pool with none to fire names no time"
    (let [a (link-id!)]
      (rl/fire-link! *eng* (stub {:bad-link "no"}) (row-of a) nil)
      (is (= {:retry-at nil}
             (rl/fire-pool! *eng* (constantly (stub {:started nil}))
                            [a "no-such-link"] nil))))))

(deftest a-prefer-pool-sends-only-the-overflow-to-a-capped-cloud-link
  (let [lf (str (:id (make-localfire-link! colton)))
        cloud (link-id!)
        busy (atom false)
        fires {lf (atom 0) cloud (atom 0)}
        provider-of (fn [row]
                      (let [id (str (:id row))]
                        (reify sch/Provider
                          (fire [_ _link _text]
                            (swap! (fires id) inc)
                            (if (and (= id lf) @busy)
                              {:throttled "600"}
                              {:started nil})))))
        fire! #(:runner (rl/fire-pool! *eng* provider-of [lf cloud] nil "prefer"))]
    (restate! cloud {:cap {:runs 1 :window_seconds 18000}} colton)
    (is (= "localfire" (str (get-in (row-of lf) [:data :provider]))))
    (testing "localfire takes every fire it can"
      (is (= [lf lf lf] (vec (repeatedly 3 fire!))))
      (is (zero? @(fires cloud))))
    (testing "when localfire answers throttled, the next fire goes to the cloud link"
      (reset! busy true)
      (is (= cloud (fire!)))
      (is (= [4 1] [@(fires lf) @(fires cloud)])))
    (testing "once the cloud cap is spent, the wake waits for the earlier retry_after"
      (let [out (rl/fire-pool! *eng* provider-of [lf cloud] nil "prefer")
            lf-free (java.time.Instant/parse
                     (str (get-in (row-of lf) [:data :retry_after])))]
        (is (nil? (:runner out)))
        (is (= lf-free (:retry-at out)))
        (is (= [4 1] [@(fires lf) @(fires cloud)]))))))

(deftest a-least-used-pool-keeps-its-rule-when-named
  (let [a (link-id!) b (link-id!)]
    (is (= [a b a b]
           (vec (repeatedly 4 #(:runner (rl/fire-pool! *eng* (constantly (stub {:started nil}))
                                                       [a b] nil "least_used"))))))))
