(ns waymark10.solo-test
  "Solo identity mode (ticket 25266c4f): an engine with :solo-owner and
  no :oidc trusts its dev headers for the owner and the owner's
  delegates alone, gives the owner manager and recovery-admin at first
  sight, and binds loopback. No database: dev/scratch! is the world."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.engine :as engine]
            [waymark10.wire :as wire]))

(defn- whoami
  "[status principal] of the discover document, as these headers."
  [h headers]
  (let [resp (h {:request-method :get :uri "/api/.well-known/waymark"
                 :headers headers})]
    [(:status resp) (some-> (:body resp) wire/read-json :principal)]))

(deftest a-solo-engine-trusts-only-its-owner
  (let [h (dev/handler (dev/scratch! [] {:solo-owner "tim"}))]
    (testing "a stranger's header answers 401"
      (is (= 401 (first (whoami h {"x-waymark-principal" "mallory"})))))

    (testing "an agent acting for a stranger answers 401 too"
      (is (= 401 (first (whoami h {"x-waymark-principal" "bot"
                                   "x-waymark-actor-type" "agent"
                                   "x-waymark-acts-for" "mallory"})))))

    (testing "the owner resolves as manager and recovery-admin"
      (let [[status p] (whoami h {"x-waymark-principal" "tim"})]
        (is (= 200 status))
        (is (= "tim" (:id p)))
        (is (= #{"manager" "recovery-admin"} (set (:roles p))))))

    (testing "an agent acting for the owner resolves as its delegate"
      (let [[status p] (whoami h {"x-waymark-principal" "claude:tim"
                                  "x-waymark-actor-type" "agent"
                                  "x-waymark-acts-for" "tim"})]
        (is (= 200 status))
        (is (= "claude:tim" (:id p)))
        (is (= "tim" (:acts_for p)))))

    (testing "a request naming nobody stays anonymous"
      (let [[status p] (whoami h {})]
        (is (= 200 status))
        (is (nil? p))))))

(deftest without-a-solo-owner-the-dev-headers-are-unchanged
  (let [h (dev/handler (dev/scratch! []))
        [status p] (whoami h {"x-waymark-principal" "mallory"})]
    (is (= 200 status))
    (is (= "mallory" (:id p)))
    (is (empty? (:roles p)))))

(deftest a-solo-engine-binds-loopback
  (let [solo (dev/scratch! [] {:solo-owner "tim"})
        plain (dev/scratch! [])]
    (testing "start! with a solo owner and no WAYMARK10_HOST binds 127.0.0.1"
      (is (= "127.0.0.1" (engine/bind-host solo {} {}))))
    (testing "WAYMARK10_HOST names another host"
      (is (= "10.0.0.5" (engine/bind-host solo {} {"WAYMARK10_HOST" "10.0.0.5"}))))
    (testing "without a solo owner http-kit's default stands"
      (is (nil? (engine/bind-host plain {} {}))))))
