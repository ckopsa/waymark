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
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.server.pull-fires :as pf]
            [waymark10.server.runner-links :as rl]
            [waymark10.server.routes.runner-links :as rlr]
            [waymark10.server.schedules :as sch]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.server.wakes :as wakes]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(def ^:dynamic *eng* nil)

(def ^:private tables
  ["runner_links" "runner_providers" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_pull_fires"])

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

(deftest a-delegate-reaches-its-persons-own-link-and-no-other
  ;; ticket 9602aac5: the link's own-surface opts in with :delegates, so
  ;; a tool a person signed in to reaches what that person owns
  (let [dana (t/principal {:id "dana" :display "Dana"})
        tool (assoc (t/principal {:id "tool:dana" :type :agent
                                  :display "Dana's tool"})
                    :acts-for "dana")
        mine (str (:id (make-link! tool)))
        theirs (str (:id (make-link! colton)))
        vis (grants/visibility *eng* nil tool)]
    (testing "the tool's link is stamped as its person's"
      (is (= "dana" (get-in (row-of mine) [:data :owner]))))
    (testing "the delegate sees its person's link, and not another's"
      (is ((:row? vis) :runner_link mine))
      (is (not ((:row? vis) :runner_link theirs)))
      (let [ids (set ((:ids-of vis) :runner_link))]
        (is (contains? ids mine))
        (is (not (contains? ids theirs)))))
    (testing "restate opens on its person's link alone, and lands"
      (is ((:action-on? vis) :runner_link :restate mine))
      (restate! mine {:cap {:runs 3 :window_seconds 3600}} tool)
      (is (= {:runs 3 :window_seconds 3600}
             (get-in (row-of mine) [:data :cap]))))
    (testing "an agent acting for nobody reaches no person's link"
      (let [bare (grants/visibility *eng* nil clerk)]
        (is (not ((:row? bare) :runner_link mine)))))))

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
      (is (= "broken" (name (:state (row-of id)))))
      (testing "and says why"
        (is (= "The Routine refused the token."
               (get-in (row-of id) [:data :note]))))
      (testing "and the next run that starts clears the reason"
        (rl/fire-link! *eng* (stub {:started nil}) (row-of id) nil)
        (is (= "live" (name (:state (row-of id)))))
        (is (nil? (get-in (row-of id) [:data :note]))))))
  (testing "transient leaves the link live and the fire unspent"
    (let [id (:id (make-link! colton))]
      (restate! id {:cap {:runs 5 :window_seconds 18000}} colton)
      (rl/fire-link! *eng* (stub {:transient "The provider answered 503."})
                     (row-of id) nil)
      (let [row (row-of id)]
        (is (= "live" (name (:state row))))
        (is (nil? (get-in row [:data :note])))
        (is (nil? (get-in row [:data :retry_after])))
        (is (nil? (get-in row [:data :last_fired_at])))
        (is (not (pos? (or (get-in row [:data :runs_in_window]) 0)))))))
  (testing "no person may write the live state"
    (let [id (:id (make-link! colton))]
      (is (some? (refusal #(inv/invoke! *eng* :runner_link id :break nil
                                        {:principal colton}))))
      (is (= "live" (name (:state (row-of id))))))))

(deftest an-owner-marks-their-link-broken
  ;; ticket 44abe17a: localfire marks a link broken when its person
  ;; revokes their token, and the next restate brings it back
  (let [dana (t/principal {:id "dana" :display "Dana"})
        tool (assoc (t/principal {:id "tool:dana" :type :agent
                                  :display "Dana's tool"})
                    :acts-for "dana")
        id (str (:id (make-localfire-link! tool)))
        vis (grants/visibility *eng* nil tool)]
    (testing "the door opens to the owner's delegate"
      (is ((:action-on? vis) :runner_link :mark_broken id)))
    (testing "an agent acting for nobody may not"
      (is (some? (refusal #(inv/invoke! *eng* :runner_link id :mark_broken
                                        {:note "Nope."} {:principal clerk}))))
      (is (= "live" (name (:state (row-of id))))))
    (testing "the delegate marks it broken and says why"
      (inv/invoke! *eng* :runner_link id :mark_broken
                   {:note "The person revoked their token."} {:principal tool})
      (is (= "broken" (name (:state (row-of id)))))
      (is (= "The person revoked their token." (get-in (row-of id) [:data :note]))))
    (testing "a restate brings it back live and clears the note"
      (restate! id {:token a-second-token} dana)
      (is (= "live" (name (:state (row-of id)))))
      (is (nil? (get-in (row-of id) [:data :note]))))))

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
    (testing "a 404 is a bad link"
      (sch/answer! fake 404)
      (is (= "No Routine answers the fire URL." (:bad-link (sch/fire p link nil)))))
    (testing "a 503 is transient, not a bad link"
      (sch/answer! fake 503)
      (let [answer (sch/fire p link nil)]
        (is (contains? answer :transient))
        (is (not (contains? answer :bad-link)))))
    (testing "an unreachable provider is transient"
      (let [down (reify sch/FireAdapter
                   (fire-routine [_ _ _ _]
                     (throw (ex-info "connection refused" {}))))]
        (is (= "connection refused"
               (:transient (sch/fire (sch/claude-routine down) link nil))))))
    (testing "exactly one of the four words"
      (doseq [status [404 503 418]]
        (sch/answer! fake status)
        (is (= 1 (count (select-keys (sch/fire p link nil)
                                     [:started :throttled :transient :bad-link]))))))))

(deftest an-agent-does-not-make-a-link
  (is (= :a-person-makes-the-link
         (:guard (refusal #(make-link! clerk))))))

(deftest only-the-engine-writes-seeded-from
  (let [body {:provider "claude_routine"
              :fire_url a-url
              :fire_token a-token
              :seeded_from "model:planted"}]
    (testing "a person's create with seeded_from is refused"
      (is (= :the-engine-writes-the-seed
             (:guard (refusal #(inv/create! *eng* :runner_link body
                                            {:principal colton}))))))
    (testing "the seed's create passes"
      (let [seed (t/principal {:id "waymark10-runner-links" :type :system
                               :display "Runner links"})
            row (:row (inv/create! *eng* :runner_link body {:principal seed}))]
        (is (= "model:planted" (get-in (row-of (:id row)) [:data :seeded_from])))))))

(deftest only-the-engine-writes-pools
  ;; S1 of docs/spec-seat.md §22: `set_pools` is the engine's own hand
  (let [id (str (:id (make-link! colton)))
        pools [{:kind "model" :id "m-1" :rank 0}
               {:kind "schedule" :id "s-1" :rank 2}]]
    (testing "a person's set_pools is refused"
      (is (some? (refusal #(inv/invoke! *eng* :runner_link id :set_pools
                                        {:pools pools}
                                        {:principal colton}))))
      (is (nil? (get-in (row-of id) [:data :pools]))))
    (testing "the engine's set_pools writes the list and keeps the state"
      (inv/invoke! *eng* :runner_link id :set_pools {:pools pools}
                   {:principal sch/system-actor})
      (let [row (row-of id)]
        (is (= :live (:state row)))
        (is (= pools (get-in row [:data :pools])))))))

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
  (testing "a transient refusal tries the next link, and a pool it leaves unstarted says so"
    (let [a (link-id!) b (link-id!)
          fires {a (atom 0) b (atom 0)}
          providers {a (counting (fires a) {:transient "The provider answered 503."})
                     b (counting (fires b) {:started nil})}
          provider-of #(providers (str (:id %)))]
      (is (= b (:runner (rl/fire-pool! *eng* provider-of [a b] "go" "prefer"))))
      (is (= [1 1] [@(fires a) @(fires b)]))
      (is (= "live" (name (:state (row-of a))))))
    (let [a (link-id!)
          out (rl/fire-pool! *eng* (constantly (stub {:transient "The provider answered 503."}))
                             [a] nil)]
      (is (nil? (:runner out)))
      (is (= "The provider answered 503." (:transient out)))
      (is (= "live" (name (:state (row-of a)))))))
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

;; ── a pull link (pull-mode epic 54360702, piece 1a) ─────────────────

(defn- pull-link-id! []
  (str (:id (:row (inv/create! *eng* :runner_link
                               {:provider "localfire"
                                :delivery "pull"
                                :fire_token a-token}
                               {:principal colton})))))

(def ^:private a-fire-text "Seat: s\nKey: one-time-key-0123456789")

(deftest a-pull-link-has-no-url-and-a-push-link-needs-one
  (is (= :a-pull-link-has-no-url
         (:guard (refusal #(inv/create! *eng* :runner_link
                                        {:provider "localfire" :delivery "pull"
                                         :fire_url a-url :fire_token a-token}
                                        {:principal colton})))))
  (is (= :a-pull-link-has-no-url
         (:guard (refusal #(inv/create! *eng* :runner_link
                                        {:provider "claude_routine" :fire_token a-token}
                                        {:principal colton})))))
  (is (rl/pull? (row-of (pull-link-id!)))))

(deftest restate-keeps-delivery-and-judges-the-url-as-create-does
  (testing "restate's input has no delivery"
    (is (not (contains? (set (map name (keys (:properties (schema/json-schema
                                                           (get-in rl/runner-link [:actions :restate :input]))))))
                        "delivery"))))
  (testing "a pull link's restate refuses a fire_url with create's sentence"
    (let [id (pull-link-id!)]
      (is (= :a-pull-link-has-no-url
             (:guard (refusal #(restate! id {:fire_url a-url} colton)))))
      (is (= (:explain rl/a-pull-link-has-no-url)
             (:explain rl/a-restated-pull-link-has-no-url)))
      (is (rl/pull? (row-of id)))))
  (testing "a push link's restate may change its fire_url"
    (let [id (str (:id (make-link! colton)))
          other "https://api.example.test/v1/routines/trig_02/fire"]
      (restate! id {:fire_url other} colton)
      (is (= other (str (get-in (row-of id) [:data :fire_url])))))))

(deftest a-pull-fire-is-held-sealed-not-posted
  (let [id (pull-link-id!)
        n (atom 0)
        answer (rl/fire-link! *eng* (counting n {:started nil}) (row-of id)
                              a-fire-text {:kind "schedule" :id "s-held"})]
    (is (string? (:queued answer)))
    (is (zero? @n) "nothing was posted")
    (let [[f :as held] (pf/pending (:storage *eng*) id)]
      (is (= 1 (count held)))
      (is (= ["schedule" "s-held"] [(:for_kind f) (:for_id f)]))
      (is (str/starts-with? (:text f) "enc:"))
      (is (not (str/includes? (:text f) "one-time-key"))))
    (testing "it survives a restart: a new engine over a new storage takes it"
      (let [st (pg/storage db/dsn)]
        (try
          (let [eng2 (engine/engine {:storage st :resources []})
                f (rl/take-fire! eng2 id)]
            (is (= (:queued answer) (:id f)))
            (is (= a-fire-text (:text f))))
          (finally (pg/close! st)))))))

(deftest take-leases-the-oldest-and-hands-a-lapsed-lease-out-again
  (let [id (pull-link-id!)
        st (:storage *eng*)
        t0 (.minusSeconds (java.time.Instant/now) 3600)
        q! #(pf/enqueue! st {:link-id id :text %1 :now %2})]
    (q! "first" t0)
    (q! "second" (.plusSeconds t0 1))
    (is (= "first" (:text (pf/take! st id (.plusSeconds t0 10)))))
    (is (= "second" (:text (pf/take! st id (.plusSeconds t0 11)))))
    (is (nil? (pf/take! st id (.plusSeconds t0 12))) "both are leased")
    (is (= "first" (:text (pf/take! st id (.plusSeconds t0 (+ 10 pf/lease-seconds)))))
        "a lease run out is handed out again")))

(deftest an-answer-lands-on-the-link-and-on-its-schedule
  (let [id (pull-link-id!)
        landed (atom nil)
        {:keys [queued]} (rl/fire-link! *eng* nil (row-of id) a-fire-text
                                        {:kind "schedule" :id "s-answer"})]
    (with-redefs [sch/land-pull-answer! (fn [_eng sid answer _at runner]
                                          (reset! landed [sid answer runner]))]
      (is (= queued (:id (rl/answer-fire! *eng* queued {:started "https://run/1"})))))
    (is (= ["s-answer" {:started "https://run/1"} id] @landed))
    (is (some? (get-in (row-of id) [:data :last_fired_at])) "the link stamped the run")
    (is (empty? (pf/pending (:storage *eng*) id)) "the fire left the queue")
    (is (nil? (rl/answer-fire! *eng* queued {:started nil})) "a second answer finds none")))

(deftest a-fire-a-day-old-is-dropped-as-missed
  (let [id (pull-link-id!)
        missed (atom [])]
    (pf/enqueue! (:storage *eng*) {:link-id id :for-kind "schedule" :for-id "s-missed"
                                   :text "late"
                                   :now (.minusSeconds (java.time.Instant/now) 90000)})
    (with-redefs [sch/miss! (fn [_eng sid _sentence] (swap! missed conj sid))]
      (is (nil? (rl/take-fire! *eng* id))))
    (is (= ["s-missed"] @missed))))

(deftest a-pull-link-no-localfire-polled-reads-waiting
  (let [pull (pull-link-id!)
        cloud (link-id!)
        n (atom 0)
        now (java.time.Instant/now)]
    (is (= "no localfire has polled this link" (rl/waiting-reason *eng* (row-of pull) now)))
    (is (nil? (rl/waiting-reason *eng* (row-of cloud) now)) "a push link is untouched")
    (testing "a pool [pull, cloud] with prefer falls to the cloud link"
      (is (= cloud (:runner (rl/fire-pool! *eng* (constantly (counting n {:started nil}))
                                           [pull cloud] nil "prefer"))))
      (is (= 1 @n))
      (is (empty? (pf/pending (:storage *eng*) pull))))
    (testing "once a localfire polls, the pull link holds the fire"
      (rl/seen! *eng* pull now)
      (is (nil? (rl/waiting-reason *eng* (row-of pull) now)))
      (let [out (rl/fire-pool! *eng* (constantly (counting n {:started nil}))
                               [pull cloud] "go" "prefer")]
        (is (= pull (:runner out)))
        (is (string? (get-in out [:answer :queued])))
        (is (= 1 @n) "nothing was posted")))))

(deftest a-pool-reads-a-pull-link-waiting-from-its-puller-seen-at
  (let [cloud (link-id!)
        n (atom 0)
        fire (fn [pull]
               (:runner (rl/fire-pool! *eng* (constantly (counting n {:started nil}))
                                       [pull cloud] "go" "prefer")))]
    (testing "a link polled 40s ago is not skipped"
      (let [pull (pull-link-id!)]
        (rl/seen! *eng* pull (.minusSeconds (java.time.Instant/now) 40))
        (is (= pull (fire pull)))))
    (testing "a link polled 70s ago is skipped"
      (let [pull (pull-link-id!)]
        (rl/seen! *eng* pull (.minusSeconds (java.time.Instant/now) 70))
        (is (= cloud (fire pull)))
        (is (empty? (pf/pending (:storage *eng*) pull)))))))

(deftest the-poll-side-table-is-dropped
  (let [st (:storage *eng*)
        exists? #(some? (first (vals (store/with-tx st
                                       (fn [tx]
                                         (jdbc/execute-one!
                                          tx ["SELECT to_regclass('waymark10_pull_polls') AS t"]))))))]
    (store/with-tx st
      (fn [tx]
        (jdbc/execute! tx ["CREATE TABLE IF NOT EXISTS waymark10_pull_polls (
                              link_id text PRIMARY KEY, polled_at timestamptz NOT NULL)"])))
    (is (exists?))
    (swap! @#'pf/ensured disj st)
    (pf/pending st "no-such-link")
    (is (not (exists?)) "the first use after it drops the table")))

(deftest a-least-used-pool-keeps-its-rule-when-named
  (let [a (link-id!) b (link-id!)]
    (is (= [a b a b]
           (vec (repeatedly 4 #(:runner (rl/fire-pool! *eng* (constantly (stub {:started nil}))
                                                       [a b] nil "least_used"))))))))

;; ── a pull link's two doors (pull-mode epic 54360702, piece 1b) ─────

(defn- door [method]
  (let [[[_ get-door] [_ post-door]] (rlr/static *eng*)]
    (case method :get (:get get-door) :post (:post post-door))))

(defn- poll [id token]
  ((door :get) {:request-method :get
                :path-params {:id id}
                :headers (cond-> {} token (assoc "waymark-runner-token" token))}))

(defn- answer [id fire token body]
  ((door :post) {:request-method :post
                 :path-params {:id id :fire fire}
                 :headers (cond-> {} token (assoc "waymark-runner-token" token))
                 :body (wire/write-json body)}))

(deftest a-poll-with-the-token-gets-the-fire-and-is-recorded
  (let [id (pull-link-id!)
        {:keys [queued]} (rl/fire-link! *eng* nil (row-of id) a-fire-text
                                        {:kind "schedule" :id "s-poll"})
        out (binding [rlr/*wait-ms* 0] (poll id a-token))]
    (is (= 200 (:status out)))
    (is (= {:id queued :text a-fire-text} (wire/read-json (:body out))))
    (is (some? (get-in (row-of id) [:data :puller_seen_at])) "the poll was recorded")
    (is (nil? (rl/waiting-reason *eng* (row-of id) (java.time.Instant/now))))))

(deftest a-wrong-or-missing-token-is-401-and-a-push-link-404
  (let [id (pull-link-id!)]
    (binding [rlr/*wait-ms* 0]
      (is (= 401 (:status (refusal #(poll id nil)))))
      (is (= 401 (:status (refusal #(poll id a-second-token)))))
      (is (= 401 (:status (refusal #(poll "no-such-link" a-token)))))
      (is (= 401 (:status (refusal #(answer id "f" nil {:status 429})))))
      (is (= 404 (:status (refusal #(poll (link-id!) a-token))))))
    (is (nil? (get-in (row-of id) [:data :puller_seen_at])) "a refused poll is not recorded")))

(deftest an-empty-queue-answers-204-after-the-wait
  (let [id (pull-link-id!)
        t0 (System/currentTimeMillis)
        out (binding [rlr/*wait-ms* 120 rlr/*step-ms* 20] (poll id a-token))]
    (is (= 204 (:status out)))
    (is (<= 120 (- (System/currentTimeMillis) t0)) "it waited")))

(deftest an-answer-lands-on-the-schedule-row
  (let [id (pull-link-id!)
        landed (atom [])
        fire! #(:queued (rl/fire-link! *eng* nil (row-of id) a-fire-text
                                       {:kind "schedule" :id %}))
        started (fire! "s-run")
        throttled (fire! "s-wait")]
    (with-redefs [sch/land-pull-answer! (fn [_eng sid ans _at runner]
                                          (swap! landed conj [sid ans runner]))]
      (is (= 200 (:status (answer id started a-token
                                  {:claude_code_session_id "session_01"
                                   :claude_code_session_url "https://run/1"}))))
      (is (= 200 (:status (answer id throttled a-token {:status 429 :retry_after 30}))))
      (is (= 404 (:status (refusal #(answer id started a-token {:status 429}))))
          "an answered fire is gone")
      (is (= 422 (:status (refusal #(answer id "x" a-token {:what 1}))))))
    (is (= [["s-run" {:started "https://run/1" :session-id "session_01"} id]
            ["s-wait" {:throttled "30" :status 429} id]]
           @landed))
    (is (= {:transient "The Routine is paused at the provider." :status 400}
           (rlr/answer-of {:status 400})))
    (is (= {:bad-link "The Routine refused the token." :status 401}
           (rlr/answer-of {:status 401})))))

(deftest a-not-started-answer-closes-the-fire-and-pauses-nothing
  (let [id (pull-link-id!)
        {:keys [queued]} (rl/fire-link! *eng* nil (row-of id) a-fire-text
                                        {:kind "schedule" :id "s-unknown"})
        reason "localfire stopped before it knew whether the run started, and does not start a fire twice."
        landed (atom [])
        written (atom [])]
    (testing "it is read with or without a status"
      (is (= {:not-started reason} (rlr/answer-of {:started false :reason reason})))
      (is (= {:not-started reason}
             (rlr/answer-of {:status 400 :started false :reason reason}))))
    (testing "with no reason, a blank one or one too long, it is refused 422"
      (is (= 422 (:status (refusal #(answer id queued a-token {:started false})))))
      (is (= 422 (:status (refusal #(answer id queued a-token
                                            {:status 400 :started false :reason "  "})))))
      (is (= 422 (:status (refusal #(answer id queued a-token
                                            {:started false
                                             :reason (apply str (repeat 501 "x"))}))))))
    (with-redefs [sch/land-pull-answer! (fn [_eng sid ans _at _runner]
                                          (swap! landed conj [sid ans]))
                  wakes/record-not-started! (fn [_eng held why]
                                              (swap! written conj [held why])
                                              true)]
      (is (= 200 (:status (answer id queued a-token {:started false :reason reason})))))
    (is (empty? (pf/pending (:storage *eng*) id)) "the fire is closed")
    (is (= [["s-unknown" {:not-started reason}]] @landed))
    (is (= [[(seats/fire-key-of-text a-fire-text) reason]] @written)
        "the sitting the key would have opened is written down")
    (is (nil? (get-in (row-of id) [:data :retry_after])) "the link is not throttled")
    (is (nil? (get-in (row-of id) [:data :note])) "nor broken")
    (testing "the sitting reads the localfire's sentence"
      (is (= (str "localfire stopped before it knew whether the run started: " reason)
             (wakes/not-started-note reason))))
    (testing "the Routine is neither paused nor throttled"
      (let [acts (atom [])]
        (with-redefs [sch/try-act! (fn [& args] (swap! acts conj (nth args 2)))
                      sch/act! (fn [& args] (swap! acts conj (nth args 2)))]
          (sch/land-answer! *eng* {:id "s-unknown" :state :live :data {}}
                            {:not-started reason} (java.time.Instant/now)))
        (is (empty? @acts))))))

(deftest no-token-or-fire-text-reaches-a-log
  (let [id (pull-link-id!)
        {:keys [queued]} (rl/fire-link! *eng* nil (row-of id) a-fire-text
                                        {:kind "schedule" :id "s-quiet"})
        err (java.io.StringWriter.)
        out (with-out-str
              (binding [*err* err rlr/*wait-ms* 0]
                (with-redefs [sch/land-pull-answer! (fn [& _] nil)]
                  (poll id a-token)
                  (refusal #(poll id a-second-token))
                  (answer id queued a-token {:status 429}))))
        said (str out err)]
    (doseq [secret [a-token a-second-token "one-time-key"]]
      (is (not (str/includes? said secret))))))

;; ── the puller (LP2) ─────────────────────────────────────────────────

(deftest a-pull-link-says-when-its-puller-was-last-seen
  (let [id (pull-link-id!)
        t0 (java.time.Instant/parse "2026-10-18T12:00:00Z")
        seen #(some-> (get-in (row-of id) [:data :puller_seen_at]) str)]
    (testing "a pull link never polled reads never, with no time"
      (is (nil? (seen)))
      (is (= "never" (rl/puller-of (row-of id) t0))))
    (testing "after a poll it reads connected, with the poll's time"
      (let [version (:version (row-of id))]
        (is (true? (rl/seen! *eng* id t0)))
        (is (= (str t0) (seen)))
        (is (= "connected" (rl/puller-of (row-of id) t0)))
        (is (= version (:version (row-of id))) "a maintenance write: the version stands")))
    (testing "a second poll 5s later writes nothing"
      (is (nil? (rl/seen! *eng* id (.plusSeconds t0 5))))
      (is (= (str t0) (seen))))
    (testing "61s after the last poll it reads away"
      (is (= "away" (rl/puller-of (row-of id) (.plusSeconds t0 61)))))
    (testing "a push link has no puller"
      (is (nil? (rl/puller-of (row-of (link-id!)) t0))))))

(deftest a-poll-stamps-the-puller-once-in-thirty-seconds
  (let [id (pull-link-id!)]
    (binding [rlr/*wait-ms* 0]
      (poll id a-token)
      (let [first-seen (get-in (row-of id) [:data :puller_seen_at])]
        (is (some? first-seen) "the poll stamped the row")
        (is (= 204 (:status (poll id a-token))) "the token still opens the link")
        (is (= first-seen (get-in (row-of id) [:data :puller_seen_at]))
            "a second poll within thirty seconds leaves it")))))

(deftest a-pool-of-away-pull-links-reads-waiting-not-broken
  (let [t0 (java.time.Instant/parse "2026-10-18T12:00:00Z")
        hour-ago (.minusSeconds t0 3600)
        away {:id "aaaaaaaa-1" :state :live
              :data {:provider "localfire" :delivery "pull" :puller_seen_at (str hour-ago)}}
        never {:id "bbbbbbbb-2" :state :live :data {:provider "localfire" :delivery "pull"}}
        here (assoc-in away [:data :puller_seen_at] (str t0))
        push {:id "cccccccc-3" :state :live :data {:provider "claude_routine"}}]
    (is (= (str "waiting for localfire link aaaaaaaa to connect (last seen " hour-ago ")")
           (rl/away-sentence [away] t0)))
    (is (= "waiting for localfire link bbbbbbbb to connect (never connected)"
           (rl/away-sentence [never away] t0)))
    (is (nil? (rl/away-sentence [away here] t0)) "one connected link fires")
    (is (nil? (rl/away-sentence [away push] t0)) "a push link fires")
    (is (nil? (rl/away-sentence [(assoc away :state :retired)] t0)) "no live link is no wait")))

(deftest a-contributors-pool-note-names-only-their-links
  (let [t0 (java.time.Instant/parse "2026-10-18T12:00:00Z")
        hour-ago (.minusSeconds t0 3600)
        others {:id "bbbbbbbb-2" :state :live
                :data {:provider "localfire" :delivery "pull" :owner "erin"}}
        theirs {:id "aaaaaaaa-1" :state :live
                :data {:provider "localfire" :delivery "pull" :owner "dana"
                       :puller_seen_at (str hour-ago)}}
        ids ["bbbbbbbb-2" "aaaaaaaa-1"]]
    (with-redefs [rl/links-by-id (constantly {"aaaaaaaa-1" theirs "bbbbbbbb-2" others})]
      (is (= "waiting for localfire link bbbbbbbb to connect (never connected)"
             (rl/pool-away *eng* ids t0))
          "a manager's seat names the pool's first link")
      (is (= (str "waiting for localfire link aaaaaaaa to connect (last seen " hour-ago ")")
             (rl/pool-away *eng* ids t0 "dana"))
          "a contributor's seat names only that contributor's link")
      (is (nil? (rl/pool-away *eng* ["bbbbbbbb-2"] t0 "dana"))
          "no link of theirs is no wait to name"))))

(deftest a-seat-on-an-away-link-reads-the-wait
  (let [t0 (java.time.Instant/parse "2026-10-18T12:00:00Z")
        link {:id "aaaaaaaa-1" :state :live :data {:provider "localfire" :delivery "pull"}}
        seat {:id "s1" :state :active :data {:name "s"}}
        read' (fn [kind id] (when (and (= :runner_link kind) (= "aaaaaaaa-1" (str id))) link))
        find-with (fn [sched] (fn [kind _ _] (when (and sched (= :schedule kind)) [sched])))]
    (is (= "waiting for localfire link aaaaaaaa to connect (never connected)"
           (seats/pull-wait read' (find-with {:data {:runners ["aaaaaaaa-1"]}}) seat t0)))
    (is (nil? (seats/pull-wait read' (find-with {:data {:fire_url a-url}}) seat t0))
        "a seat with a link of its own fires through it")
    (is (nil? (seats/pull-wait read' (find-with nil) seat t0)) "no schedule, no pool")))
