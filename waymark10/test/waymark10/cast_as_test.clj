(ns waymark10.cast-as-test
  "Clone guard 2/3: on a clone, a seat with acts_as_cast may act as a
  seeded cast member, and the log records both hands. Over the
  in-memory twin and a toy kind; the router's judge is handed the seat
  row a request would carry."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.router :as router]
            [waymark10.server.seed :as seed]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (clojure.lang ExceptionInfo)))

(def ^:private chore
  "The toy kind a customer files."
  (r/resource
   {:kind :chore
    :plural "chores"
    :states [:open :done]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema [:map [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 1}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 2}}}}))

(def ^:private cast-seed
  {:seed "cast"
   :version 1
   :cast {:rosa {:id "rosa" :display "Rosa Delgado" :type :human}
          :lee {:id "lee" :display "Lee Park" :type :human :roles ["manager"]}
          :ann {:id "ann" :display "Ann, a customer" :type :human}
          :parcel {:id "parcel" :display "Parcelco" :type :outside :roles ["carrier"]}}
   :steps [{:as :rosa :kind :chore :create {:title "Trim the front hedge"}}]})

(def ^:private world-seat {:id "world" :data {:acts_as_cast true}})

(def ^:private sitter (t/principal {:id "seat:world" :display "world" :type :agent}))

(defn- engine [clone?]
  (let [eng (dev/scratch! [chore] {:name "demo-cast" :clone? clone? :solo-owner "rosa"})]
    (seed/load! eng cast-seed {})
    eng))

(defn- moves [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind kind :resource-id (str id)} {}))))

(defn- refused
  "The refusal a call threw, or nil when it went through."
  [f]
  (try (f) nil
       (catch ExceptionInfo e e)))

(deftest the-seed-loader-marks-its-cast
  (let [eng (engine true)]
    (is (true? (get-in (dev/row eng :member "ann") [:data :cast])))
    (testing "and a hand may not"
      (is (some? (refused #(inv/create! eng :member
                                        {:display "Mal" :actor_type "human"
                                         :subject "mal" :cast true}
                                        {:principal (t/principal {:id "rosa"})
                                         :id "mal"})))))))

(deftest a-clone-seat-acts-as-a-seeded-cast-member
  (let [eng (engine true)
        p (router/cast-principal eng world-seat sitter "ann")
        row (:row (inv/create! eng :chore {:title "Mow the back lawn"} {:principal p}))
        log (moves eng :chore (:id row))]
    (is (= "ann" (:id p)))
    (testing "the log records both: acted by the seat, as the cast member"
      (is (= "ann" (get-in (first log) [:actor :id])))
      (is (= "seat:world" (get-in (first log) [:actor :acted_by]))))))

(deftest a-clone-seat-acts-as-an-outside-cast-member
  (let [eng (engine true)
        p (router/cast-principal eng world-seat sitter "parcel")
        row (:row (inv/create! eng :chore {:title "Deliver the pavers"} {:principal p}))
        log (moves eng :chore (:id row))]
    (is (= "outside" (get-in (dev/row eng :member "parcel") [:data :actor_type])))
    (is (= "parcel" (:id p)))
    (is (= :human (:type p)) "an outside party acts as the person they are")
    (is (= "parcel" (get-in (first log) [:actor :id])))
    (is (= "seat:world" (get-in (first log) [:actor :acted_by])))))

(deftest a-clone-seat-acts-as-the-seeded-owner
  (let [eng (engine true)
        p (router/cast-principal eng world-seat sitter "rosa")
        row (:row (inv/create! eng :chore {:title "Plan the first week"} {:principal p}))]
    (is (= "rosa" (:id p)))
    (is (some? (:id row)))
    (is (= "rosa" (get-in (first (moves eng :chore (:id row))) [:actor :id])))))

(deftest acting-as-is-refused-unless-all-hold
  (let [eng (engine true)]
    (inv/create! eng :member {:display "Zed" :actor_type "human" :subject "zed"}
                 {:principal members/registrar :id "zed"})
    (testing "not a clone"
      (is (some? (refused #(router/cast-principal (engine false) world-seat sitter "ann")))))
    (testing "a seat without acts_as_cast"
      (is (some? (refused #(router/cast-principal eng {:id "clerk" :data {}} sitter "ann"))))
      (is (some? (refused #(router/cast-principal eng nil sitter "ann")))))
    (testing "the owner, on a live engine"
      (is (some? (refused #(router/cast-principal (engine false) world-seat sitter "rosa")))))
    (testing "a manager"
      (is (some? (refused #(router/cast-principal eng world-seat sitter "lee")))))
    (testing "a member with no cast marker"
      (is (some? (refused #(router/cast-principal eng world-seat sitter "zed")))))
    (testing "a member that does not exist"
      (is (some? (refused #(router/cast-principal eng world-seat sitter "nobody")))))))
