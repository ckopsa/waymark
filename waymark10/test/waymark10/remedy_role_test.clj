(ns waymark10.remedy-role-test
  "A refusal whose remedy needs a role the caller lacks names that role
  (ticket 01e3c909): a guard declares the role its code checks as
  `:role`, and remedy-takers/annotate says the door needs it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.members :as members]
            [waymark10.server.remedy-takers :as rt]))

(def ^:private eng
  {:resources {:member {:actions {:assign_roles
                                  {:guards [members/assign-is-recovery-admins]}}}}})

(defn- refused []
  (ex-info "Refused"
           {:waymark10/problem :guard-refused
            :title "Refused"
            :detail "That needs a recovery-admin."
            :remedies [{:door :member/assign_roles}]}))

(deftest the-guard-declares-its-role
  (is (= ["recovery-admin"] (rt/door-roles eng :member "assign_roles"))))

(deftest a-caller-without-the-role-is-told-it
  (let [d (ex-data (rt/annotate eng {:type :person :id "p-1" :roles ["member"]}
                                nil (refused)))]
    (is (= [{:door "member/assign_roles"
             :roles ["recovery-admin"]
             :says (rt/role-sentence :member "assign_roles" ["recovery-admin"])}]
           (:remedy-takers d)))
    (is (str/includes? (:detail d) "needs the `recovery-admin` role"))
    (is (str/starts-with? (:detail d) "That needs a recovery-admin."))))

(deftest a-caller-holding-the-role-is-told-nothing
  (testing "the role held"
    (let [e (refused)]
      (is (identical? e (rt/annotate eng {:type :person :id "p-1"
                                          :roles ["recovery-admin"]}
                                     nil e)))))
  (testing "the engine's own"
    (let [e (refused)]
      (is (identical? e (rt/annotate eng {:type :system} nil e))))))
