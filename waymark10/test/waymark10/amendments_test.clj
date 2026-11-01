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
    (testing "the engine replays: an empty report that names capped blind"
      (is (nil? (refusal (as eng amendments/engine-actor aid :replay nil))))
      (is (= "replayed" (state eng aid)))
      (is (= ["capped"] (mapv str (get-in (row eng aid) [:data :replay :blind])))))
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
