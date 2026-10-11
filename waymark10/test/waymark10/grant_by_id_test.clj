(ns waymark10.grant-by-id-test
  "A grant read by its id for a principal that is not at the wire
  (docs/spec-scheduled-actions.md R-4.2): `grants/grant-by-id` and
  `grants/grant-standing`."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(def ^:private clerk
  (t/principal {:id "mail-clerk" :type :agent :display "Clerk"
                :model "grant-by-id-model"}))

(defn- fresh-engine
  "An engine whose clock is the atom's instant, with the one registry
  row a scope here names."
  [clock]
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources [caps/capability]
                            :now-fn (fn [] @clock)})]
    (inv/create! eng :capability schedules/write-capability {:principal colton})
    eng))

(defn- a-grant!
  "A grant for the clerk, minted by a person → its id."
  [eng body]
  (str (:id (:row (inv/create! eng :grant
                               (merge {:audience (:id clerk)
                                       :scope [{:kind "schedule.write"
                                                :actions []}]}
                                      body)
                               {:principal colton})))))

(deftest a-grant-is-read-by-id-with-no-principal-at-the-wire
  (let [clock (atom (Instant/parse "2026-09-18T09:00:00Z"))
        eng (fresh-engine clock)
        gid (a-grant! eng {:expires_at "2026-09-19T09:00:00Z"})
        standing #(:standing (grants/grant-standing eng gid))]
    (testing "an id nobody holds"
      (is (nil? (grants/grant-by-id eng "no-such-grant")))
      (is (nil? (grants/grant-by-id eng nil)))
      (is (= {:standing :absent :grant nil}
             (grants/grant-standing eng "no-such-grant"))))
    (testing "the row, decoded, as it stands"
      (let [row (grants/grant-by-id eng gid)]
        (is (= :offered (:state row)))
        (is (= (:id clerk) (get-in row [:data :audience])))
        (is (= ["schedule.write"] (mapv :kind (get-in row [:data :scope]))))
        (is (= row (:grant (grants/grant-standing eng gid))))))
    (testing "offered confers nothing yet"
      (is (= :offered (standing))))
    (testing "accepted and unexpired"
      (inv/invoke! eng :grant gid :accept {} {:principal clerk})
      (is (= :active (standing))))
    (testing "past expires_at by the live clock, before any expire is written"
      (reset! clock (Instant/parse "2026-09-19T09:00:00Z"))
      (is (= :accepted (:state (grants/grant-by-id eng gid))))
      (is (= :expired (standing))))
    (testing "revoked"
      (reset! clock (Instant/parse "2026-09-18T10:00:00Z"))
      (is (= :active (standing)))
      (inv/invoke! eng :grant gid :revoke {} {:principal colton})
      (is (= :revoked (standing)))
      (is (= :revoked (:state (grants/grant-by-id eng gid)))))))
