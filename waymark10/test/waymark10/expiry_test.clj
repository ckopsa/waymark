(ns waymark10.expiry-test
  "One sweep for every kind that declares how it expires
  (waymark10.server.expiry): the held call, the invitation and the
  grant each name their own `expire` door, and a grant past its
  `expires_at` is expired within one sweep."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.expiry :as expiry]
            [waymark10.server.grants :as grants]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invitations :as invitations]
            [waymark10.server.invoke :as inv]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(def ^:private clerk
  (t/principal {:id "expiry-clerk" :type :agent :display "Clerk"
                :model "expiry-model"}))

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

(defn- state-of [eng gid]
  (:state (grants/grant-by-id eng gid)))

(deftest each-kind-names-its-own-expire-door
  (doseq [rdef [held/held-call invitations/invitation grants/grant]]
    (is (= {:field :expires_at :door :expire}
           (select-keys (:expires rdef) [:field :door]))
        (name (:kind rdef))))
  (testing "a held call waiting on its server is expired by its own pass"
    (is (= #{:held :allowed} (set (get-in held/held-call [:expires :in]))))))

(deftest the-sweep-reads-the-door-each-kind-declares
  (let [eng (fresh-engine (atom (Instant/parse "2026-09-18T09:00:00Z")))
        grant (get (expiry/expiring eng) :grant)]
    (is (= :expire (:door grant)))
    (is (= #{:offered :accepted} (set (:states grant)))
        "the states the door leaves, and no terminal one")))

(deftest a-grant-past-its-expiry-is-expired-within-one-sweep
  (let [clock (atom (Instant/parse "2026-09-18T09:00:00Z"))
        eng (fresh-engine clock)
        offered (a-grant! eng {:expires_at "2026-09-18T10:00:00Z"})
        accepted (a-grant! eng {:expires_at "2026-09-18T10:00:00Z"})
        later (a-grant! eng {:expires_at "2026-09-20T09:00:00Z"})]
    (inv/invoke! eng :grant accepted :accept {} {:principal clerk})
    (testing "nothing expires before its moment"
      (is (= 0 (expiry/sweep! eng {:kinds #{:grant}})))
      (is (= :accepted (state-of eng accepted))))
    (testing "past it, one sweep walks both through the grant's own door"
      (reset! clock (Instant/parse "2026-09-18T11:00:00Z"))
      (is (= 2 (expiry/sweep! eng {:kinds #{:grant}})))
      (is (= :expired (state-of eng offered)))
      (is (= :expired (state-of eng accepted)))
      (is (= :offered (state-of eng later))))
    (testing "a second pass writes nothing"
      (is (= 0 (expiry/sweep! eng {:kinds #{:grant}}))))
    (testing "and the check at use still stands"
      (is (= :expired (:standing (grants/grant-standing eng accepted)))))))
