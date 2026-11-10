(ns waymark10.every-kind-test
  "One scope entry reads every kind a grant may name (ticket 6aad331d):
  `{kind: \"*\", actions: []}` is read on every grantable kind, read off
  the law at judgment, never a private kind, and never a door; a seat
  restate that adds a copy of an entry it holds is warned.

  No database: the expansion, the guards and the words are judged as
  functions."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.grants :as grants]
            [waymark10.server.seats.owners :as owners]
            [waymark10.types :as t]))

(def ^:private rdefs
  [{:kind :ticket :domain :work}
   {:kind :meal :domain :home}
   {:kind :journal :domain :home :own-surface {:grantable false}}])

(def ^:private every [{:kind "*" :actions []}])

(defn- kinds [scope] (set (map :kind scope)))

(deftest a-star-reads-every-grantable-kind
  (is (= #{"ticket" "meal"} (kinds (grants/expand-every-kind rdefs every))))
  (testing "a kind declared after the grant is read, with no restate"
    (is (contains? (kinds (grants/expand-every-kind
                           (conj rdefs {:kind :invoice :domain :work}) every))
                   "invoice")))
  (testing "a private kind is never read"
    (is (not (contains? (kinds (grants/expand-every-kind rdefs every)) "journal"))))
  (testing "a domain narrows it to that domain's kinds"
    (is (= #{"ticket"}
           (kinds (grants/expand-every-kind
                   rdefs [{:kind "*" :actions [] :in_domain "work"}])))))
  (testing "the other entries stay as they were"
    (is (some #{{:kind "meal" :actions ["plan"]}}
              (grants/expand-every-kind rdefs (conj every {:kind "meal" :actions ["plan"]}))))))

(deftest a-star-opens-no-door
  (let [surface (#'grants/surface-of
                 {:data {:scope (grants/expand-every-kind rdefs every)}})]
    (is (= #{} (get-in surface ["ticket" :actions])))
    (is (= #{} (get-in surface ["meal" :actions])))))

(deftest the-guard-admits-the-read-only-star
  (let [check (:check grants/scope-names-real-kinds)
        ctx {:action-names (fn [k] (when (= "ticket" (str k)) #{"close"}))}]
    (is (t/allow? (check nil {:scope every} ctx)))
    (is (t/allow? (check nil {:scope [{:kind "*" :actions [] :in_domain "work"}]} ctx)))
    (testing "a star that names a door, rows or a filter is refused"
      (is (not (t/allow? (check nil {:scope [{:kind "*" :actions ["close"]}]} ctx))))
      (is (not (t/allow? (check nil {:scope [{:kind "*" :actions [] :ids ["a"]}]} ctx))))
      (is (not (t/allow? (check nil {:scope [{:kind "*" :actions []
                                                 :filter {:state "open"}}]} ctx)))))
    (testing "an unknown kind is still refused"
      (is (not (t/allow? (check nil {:scope [{:kind "unicorn" :actions []}]} ctx)))))))

(deftest the-held-call-says-it-in-words
  (is (= "read every kind" (#'grants/entry-said {:kind "*" :actions []})))
  (is (= "read every kind of work"
         (#'grants/entry-said {:kind "*" :actions [] :in_domain "work"}))))

(deftest a-restate-that-adds-a-copy-is-warned
  (let [check (:check owners/a-restate-adds-no-copy)
        e {:kind "transcript" :actions []}
        f {:kind "ticket" :actions ["close"]}]
    (is (not (t/allow? (check {:data {:scope [e f]}} {:scope [e f e]} {}))))
    (is (t/allow? (check {:data {:scope [e]}} {:scope [e f]} {})))
    (testing "a copy the scope already held is not this restate's"
      (is (t/allow? (check {:data {:scope [e e]}} {:scope [e e f]} {}))))))
