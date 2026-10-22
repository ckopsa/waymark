(ns waymark10.not-by-test
  "Teams 4 (docs/spec-teams.md §4): a door's :stamp writes who acted
  into a member-ref field, and a later door's :not-by keeps that
  member, and any agent acting for them, from the next step."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.fingerprint :as fp]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.types :as t]))

(def order
  {:kind :nb_order
   :initial :open
   :terminal #{:checked}
   :summary "{data.label} · {state}"
   :schema [:map
            [:label [:string {:max 40}]]
            [:packed_by {:optional true :kind :member} [:maybe :waymark/ref]]]
   :flow [[:open :pack :packed {:stamp {:packed_by '(actor)}
                                :one-way "Packed is packed; the check is the next step."}]
          [:packed :check :checked {:not-by [:packed_by]
                                    :one-way "A checked order is done."}]]})

(defn- wall-of [n]
  (some #(when (= :not-by:packed_by (:name %)) %)
        (get-in n [:actions :check :guards])))

(defn- hash-of [rmap]
  (fp/fingerprint-hash (r/fingerprint (r/normalize-resource rmap))))

(deftest a-stamp-writes-who-acted
  (is (= {:packed_by "ana"} (g/stamped {:packed_by '(actor)} {:id "ana" :type :person})))
  (is (= {:packed_by "bot"}
         (g/stamped {:packed_by '(actor)} {:id "bot" :type :agent :acts-for "ana"})))
  (is (= {:packed_by "ana"}
         (g/stamped {:packed_by '(actor :acts-for)} {:id "bot" :type :agent :acts-for "ana"})))
  (is (= {:pack {:packed_by '(actor)}}
         {:pack (get-in (r/normalize-resource order) [:actions :pack :stamp])})))

(deftest anas-agent-cannot-check-what-ana-packed
  (let [wall (wall-of (r/normalize-resource order))
        row {:id "o1" :data {:label "Mugs" :packed_by "ana"}}
        verdict (fn [p] (first (g/evaluate wall row {} {:principal p})))]
    (is (some? wall) ":not-by derives the wall on the door")
    (testing "Ana cannot check what Ana packed"
      (is (t/deny? (verdict {:id "ana" :type :person}))))
    (testing "nor can an agent acting for Ana"
      (is (t/deny? (verdict {:id "bot" :type :agent :acts-for "ana"}))))
    (testing "someone else can"
      (is (t/allow? (verdict {:id "bo" :type :person})))
      (is (t/allow? (verdict {:id "bot" :type :agent :acts-for "bo"}))))
    (testing "a row naming nobody bars nobody"
      (is (t/allow? (first (g/evaluate wall {:id "o2" :data {:label "Cups"}} {}
                                       {:principal {:id "ana" :type :person}})))))))

(deftest the-wall-and-the-stamp-fingerprint
  (let [bare (assoc order :flow [[:open :pack :packed {:one-way "Packed."}]
                                 [:packed :check :checked {:one-way "Done."}]])
        stamp-only (assoc order :flow [[:open :pack :packed {:stamp {:packed_by '(actor)}
                                                             :one-way "Packed."}]
                                       [:packed :check :checked {:one-way "Done."}]])
        both (assoc order :flow [[:open :pack :packed {:stamp {:packed_by '(actor)}
                                                       :one-way "Packed."}]
                                 [:packed :check :checked {:not-by [:packed_by]
                                                           :one-way "Done."}]])]
    (is (not= (hash-of bare) (hash-of stamp-only)))
    (is (not= (hash-of stamp-only) (hash-of both)))))

(deftest an-unwritten-not-by-field-fails-declaration
  (is (map? (r/resource order)))
  (testing "a :not-by field no door stamps or takes"
    (is (thrown-with-msg?
         Exception #"never names anyone"
         (r/resource (assoc order :flow [[:open :pack :packed {:one-way "Packed."}]
                                         [:packed :check :checked {:not-by [:packed_by]
                                                                   :one-way "Done."}]])))))
  (testing "a :not-by field that is no member ref"
    (is (thrown-with-msg?
         Exception #"must be a \(ref \{:kind :member\}\)"
         (r/resource (assoc order :flow [[:open :pack :packed {:stamp {:packed_by '(actor)}
                                                              :one-way "Packed."}]
                                         [:packed :check :checked {:not-by [:label]
                                                                   :one-way "Done."}]]))))))
