(ns waymark10.amendments-test
  "Amendments step 2 (docs/spec-amendment.md §14): the kind, its lane
  and its engine-only walls, in memory."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.amendments :as amendments]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.promises :as promises]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private elena (t/principal {:id "elena" :type :human :display "Elena"}))

(def ^:private planner (t/principal {:id "seat:planner" :type :agent :display "planner"}))

(defn- eng! []
  (engine/engine {:storage (memory/storage)
                  :resources [promises/promise amendments/amendment]}))

(defn- promise! [eng]
  (str (:id (:row (inv/create! eng :promise
                               {:name "merged content is live within 2 h"
                                :about {:kind "amendment" :filter {:state "draft"}}
                                :holds {:query {:kind "amendment" :filter {:id "{id}"} :expect "none"}}
                                :by {:after "restate" :within 7200}
                                :remedy {:kind "amendment" :door "withdraw"}}
                               {:principal elena})))))

(defn- amendment! [eng changes]
  (str (:id (:row (inv/create! eng :amendment
                               {:title "Give the deploy four hours" :changes changes}
                               {:principal elena})))))

(defn- row [eng id]
  (let [st (:storage eng)]
    (store/with-tx st #(store/load-row st % :amendment id {}))))

(defn- state [eng id] (name (:state (row eng id))))

(defn- refusal
  "The refusal of `f`'s call as {:guard :text}, or nil."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         {:guard (some-> (:guard (ex-data e)) name keyword)
          :text (str (ex-message e) " " (pr-str (ex-data e)))})))

(defn- as [eng who id door inp]
  #(inv/invoke! eng :amendment id door inp
                {:principal who :idempotency-key (str (:id who) door (gensym))}))

(deftest a-seat-cannot-replay-or-settle
  (let [eng (eng!)
        pid (promise! eng)
        aid (amendment! eng {:kind "promise" :id pid :door "restate"
                             :patch {:by {:after "restate" :within 14400}}})]
    (is (= "draft" (state eng aid)))
    (is (= "elena" (get-in (row eng aid) [:data :filed_by])))
    (testing "a seat's replay is refused and moves nothing"
      (is (some? (refusal (as eng planner aid :replay nil))))
      (is (= "draft" (state eng aid))))
    (testing "the engine replays: a report over the window that names what it could not run"
      (is (nil? (refusal #(amendments/replay! eng aid))))
      (is (= "replayed" (state eng aid)))
      (is (some #{"refusals"} (map :token (get-in (row eng aid) [:data :replay :blind])))))
    (testing "settle carries the engine's wall, and a seat does not pass it"
      (is (some #{:the-engine-moves-it}
                (map :name (get-in amendments/amendment [:actions :settle :guards]))))
      (let [check (:check amendments/the-engine-moves-it)]
        (is (not (t/allow? (check nil nil {:principal planner}))))
        (is (not (t/allow? (check nil nil {:principal elena}))))
        (is (t/allow? (check nil nil {:principal amendments/engine-actor}))))
      (is (some? (refusal (as eng planner aid :settle nil)))))
    (testing "a restate sends it back to draft and clears the replay"
      (is (nil? (refusal (as eng elena aid :restate {:title "Give the deploy three hours"}))))
      (is (= "draft" (state eng aid)))
      (is (nil? (get-in (row eng aid) [:data :replay]))))))

(deftest a-patch-naming-scope-is-refused-with-the-raw-door-named
  (let [eng (eng!)
        pid (promise! eng)]
    (testing "create"
      (let [r (refusal #(amendment! eng {:kind "seat" :id "any" :door "restate"
                                         :patch {:charter "Plan." :scope []}}))]
        (is (= :an-amendment-stays-in-its-lane (:guard r)))
        (is (str/includes? (:text r) "`scope`"))
        (is (str/includes? (:text r) "seat.restate"))))
    (testing "a promise's debtor and any door but restate are outside it too"
      (is (str/includes? (:text (refusal #(amendment! eng {:kind "promise" :id pid :door "restate"
                                                           :patch {:debtor "planner"}})))
                         "promise.restate"))
      (is (str/includes? (:text (refusal #(amendment! eng {:kind "promise" :id pid :door "retire"
                                                           :patch {:name "gone"}})))
                         "promise.retire")))
    (testing "restate refuses it as create does"
      (let [aid (amendment! eng {:kind "promise" :id pid :door "restate" :patch {:name "live in 4 h"}})
            r (refusal (as eng elena aid :restate {:changes {:kind "seat" :id "any" :door "restate"
                                                             :patch {:scope []}}}))]
        (is (= :an-amendment-stays-in-its-lane (:guard r)))
        (is (str/includes? (:text r) "seat.restate"))
        (is (= "draft" (state eng aid)))))
    (testing "a seat names a promise it answers"
      (is (= :a-seats-amendment-answers-a-promise
             (:guard (refusal #(inv/create! eng :amendment
                                            {:title "Loosen it" :answers []
                                             :changes {:kind "promise" :id pid :door "restate"
                                                       :patch {:name "live in 4 h"}}}
                                            {:principal planner}))))))))

;; ── step 7: replay and adopt ─────────────────────────────────────────

(def ^:private office
  {:name "planner"
   :charter "Plan the week."
   :scope [{:kind "amendment" :actions ["withdraw"]}]
   :held_for []
   :substitute_for []
   :standing_ttl_seconds 604800
   :cadence_seconds 3600
   :budget_usd_per_week 5M
   :sitting_budget_tokens 60000
   :rows_per_firing 20})

(defn- seat-row [eng id]
  (let [st (:storage eng)]
    (store/with-tx st #(store/load-row st % :seat id {}))))

(defn- replayed!
  "A seat, elena's amendment of it by `patch`, replayed. → [seat amendment]."
  [eng patch]
  (let [sid (str (:id (:row (inv/create! eng :seat office {:principal elena}))))
        aid (amendment! eng {:kind "seat" :id sid :door "restate" :patch patch})]
    (amendments/replay! eng aid)
    [sid aid]))

(deftest the-owner-adopts-through-the-seat-restate-keeping-before-and-after
  (let [eng (eng!)
        [sid aid] (replayed! eng {:charter "Plan next week, not this one."})]
    (is (= "replayed" (state eng aid)))
    (is (some #{"model_judgment"} (map :token (get-in (row eng aid) [:data :replay :blind]))))
    (testing "a seat does not adopt"
      (is (some? (refusal (as eng planner aid :adopt nil))))
      (is (= "replayed" (state eng aid))))
    (is (nil? (refusal (as eng elena aid :adopt nil))))
    (is (= "trial" (state eng aid)))
    (is (= "Plan next week, not this one." (get-in (seat-row eng sid) [:data :charter])))
    (is (= {:charter "Plan the week."} (get-in (row eng aid) [:data :before])))
    (is (= {:charter "Plan next week, not this one."} (get-in (row eng aid) [:data :after])))
    (testing "the rest of the office stands as it was"
      (is (= 3600 (get-in (seat-row eng sid) [:data :cadence_seconds])))
      (is (= "withdraw" (get-in (seat-row eng sid) [:data :scope 0 :actions 0]))))))

(deftest a-guard-on-the-seat-restate-refuses-the-adopt-with-its-own-sentence
  (let [eng (eng!)
        [sid aid] (replayed! eng {:walk "promise"})
        r (refusal (as eng elena aid :adopt nil))]
    (is (= :walk-names-a-kind-in-scope (:guard r)))
    (is (= "replayed" (state eng aid)))
    (is (nil? (get-in (seat-row eng sid) [:data :walk])))
    (is (nil? (get-in (row eng aid) [:data :before])))))

(deftest a-stale-target-refuses-the-adopt
  (let [eng (eng!)
        [sid aid] (replayed! eng {:charter "Plan next week."})]
    (inv/invoke! eng :seat sid :restate (-> office (dissoc :name) (assoc :cadence_seconds 7200))
                 {:principal elena :if-match (inv/etag :seat sid (:version (seat-row eng sid)))})
    (let [r (refusal (as eng elena aid :adopt nil))]
      (is (= :the-target-has-not-moved (:guard r)))
      (is (str/includes? (:text r) "cadence_seconds"))
      (is (= "replayed" (state eng aid)))
      (is (= "Plan the week." (get-in (seat-row eng sid) [:data :charter]))))
    (testing "restated and replayed, it reads the row as it stands and lands"
      (is (nil? (refusal (as eng elena aid :restate {:title "Plan next week"}))))
      (amendments/replay! eng aid)
      (is (nil? (refusal (as eng elena aid :adopt nil))))
      (is (= "trial" (state eng aid)))
      (is (= 7200 (get-in (seat-row eng sid) [:data :cadence_seconds]))))))
