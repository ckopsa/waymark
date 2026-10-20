(ns factory10.org-hiring-gaps-test
  "An org's rows reach its hiring gaps (ticket 0ae89267): orgs/example's
  request, stamped only with `:domain :example`, is placed in its domain
  with no stored domain, and ages in `new` past a day. Pure; no engine.

  Run: cd factory10 && clojure -M:test --focus factory10.org-hiring-gaps-test"
  (:require [clojure.test :refer [deftest is testing]]
            [example.org :as example]
            [waymark10.resource :as r]
            [waymark10.server.live-cracks :as live])
  (:import (java.time Instant)))

(def ^:private now (Instant/parse "2026-10-20T12:00:00Z"))

(defn- ago [seconds] (.minusSeconds ^Instant now (long seconds)))

(defn- rdef [kind]
  (some #(when (= kind (name (:kind %))) %) (example/resources)))

(defn- new-request [id seconds]
  {:id id :state :new :updated-at (ago seconds) :data {:title "Mow the lawn"}})

(defn- gaps [rows]
  (live/hiring-gaps {:rdefs (example/resources) :seats [] :seat-domains {}
                     :rows {:request rows} :now now}
                    #{"example"}))

(deftest a-request-with-no-stored-domain-is-the-orgs
  (is (= "example" (live/row-domain (rdef "request") (new-request "q1" 10))))
  (testing "and hiring-gaps sees it"
    (let [[g :as all] (gaps [(new-request "q1" 90000)])]
      (is (= 1 (count all)))
      (is (= {:org "example" :kind "request" :state "new" :reason "no_holder"
              :row_ids ["q1"]}
             (select-keys g [:org :kind :state :reason :row_ids]))))))

(deftest a-new-request-ages-past-one-day
  (is (= 86400 (live/expected-age (rdef "request") :new)))
  (is (= ["q1"] (mapv :id (live/aged-rows (example/resources) []
                                          {:request [(new-request "q1" 86500)
                                                     (new-request "q2" 86000)]}
                                          now)))))

(deftest an-org-kinds-unnamed-states-take-the-template-default
  (let [thing {:kind :thing :states [:waiting :over] :terminal #{:over}}
        [stamped] (r/in-domain :example {:default-expected-age 86400} [thing])
        [off] (r/in-domain :example {:default-expected-age nil} [thing])]
    (is (= 86400 (live/expected-age stamped :waiting)))
    (is (nil? (live/expected-age stamped :over)))
    (is (nil? (live/expected-age off :waiting)))
    (is (nil? (live/expected-age (first (r/in-domain :example [thing])) :waiting)))))

(deftest a-stored-only-kind-keeps-todays-placement
  (let [stored-only (assoc (rdef "request") :row-domain :stored-only)]
    (is (nil? (live/row-domain stored-only (new-request "q1" 10))))
    (is (= "beta" (live/row-domain stored-only
                                   (assoc-in (new-request "q1" 10) [:data :domain] "beta"))))
    (is (= [] (live/hiring-gaps {:rdefs [stored-only] :seats [] :seat-domains {}
                                 :rows {:request [(new-request "q1" 90000)]} :now now}
                                #{"example"})))))
