(ns factory10.org-plan-workarounds-test
  "An org_plan's workarounds (ticket 4fdcd08a): the log_workarounds door
  appends and dedupes on (concept, subject), a grant without the door
  is refused it while the onboarding seat's own grant holds it, and the
  collection finds a plan by the concepts its workarounds name.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [waymark10.dev :as dev]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-10-20T08:00:00Z"))

(defn- engine [] (dev/scratch! (main/resources) {:now-fn (constantly t0)}))

(def ^:private the-owner
  (t/principal {:id "colton" :type :human :display "colton"}))

(def ^:private person {"x-waymark-principal" "colton"})

(def ^:private agent-headers
  "The onboarding seat's sitter: an agent acting for its person."
  {"x-waymark-principal" "onboarding"
   "x-waymark-actor-type" "agent"
   "x-waymark-acts-for" "colton"})

(def ^:private a-rule
  {:concept "1" :subject "invoice/send"
   :need "An invoice is sent only once its job is done."
   :instead "A code guard that reads the job, with no declaration."})

(def ^:private a-fee
  {:concept "3" :subject "invoice.late_fee"
   :need "A late fee in dollars and cents."
   :instead "A free-text field."})

(defn- req [h method uri headers body & [query]]
  (h (cond-> {:request-method method :uri uri :headers headers}
       query (assoc :query-string query)
       body (assoc :body (wire/write-json body)))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [x] (last (str/split (str (:self x)) #"/")))
(defn- served? [resp] (contains? #{200 201 202} (:status resp)))

(defn- filed! [eng org]
  (str (:id (:row (inv/create! eng :org_plan {:name org :brief "A small business."}
                               {:principal the-owner})))))

(defn- stored [eng id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx :org_plan id {}))))

(defn- log! [eng id entries]
  (inv/invoke! eng :org_plan id :log_workarounds {:entries entries}
               {:principal the-owner}))

(defn- onboarding-entry
  "The onboarding template's org_plan scope entry, filled for `org`."
  [org]
  (let [t (edn/read-string (slurp (io/resource "seats/onboarding.edn")))]
    (->> (str/replace (pr-str (get-in t [:seat :scope])) "{{org}}" org)
         edn/read-string
         (filter #(= "org_plan" (:kind %)))
         first)))

(defn- granted
  "The agent's headers under an approved grant of the one entry `entry`."
  [h entry]
  (let [asked (req h :post "/api/approval_requests" agent-headers
                   {:task "Walk greenleaf's plan." :scope [entry]})
        _ (assert (= 201 (:status asked)) (pr-str (json asked)))
        approved (req h :post (str "/api/approval_requests/" (id-of (json asked)) "/-/approve")
                      person nil)
        _ (assert (= 200 (:status approved)) (pr-str (json approved)))]
    (assoc agent-headers "x-waymark-grant" (get-in (json approved) [:data :grant_id]))))

(deftest the-door-appends-and-dedupes-on-concept-and-subject
  (let [eng (engine)
        id (filed! eng "greenleaf")]
    (log! eng id [a-rule])
    (log! eng id [(assoc a-rule :need "Said again, in other words.") a-fee a-fee])
    (let [data (:data (stored eng id))]
      (is (= [a-rule a-fee] (:workarounds data))
          "a second entry under a logged concept and subject keeps the first")
      (is (= ["1" "3"] (:workarounds_concept data))))))

(deftest a-grant-without-the-door-is-refused-and-the-onboarding-seat-holds-it
  (let [eng (engine)
        h (dev/handler eng)
        id (filed! eng "greenleaf")
        door #(req h :post (str "/api/org_plans/" id "/-/log_workarounds") %
                   {:entries [a-rule]})]
    (testing "a grant that names the plan's other doors and not this one is refused"
      (let [as (granted h {:kind "org_plan" :actions ["ask"] :filter {:name "greenleaf"}})]
        (is (not (served? (door as))))
        (is (empty? (get-in (stored eng id) [:data :workarounds])))))
    (testing "the onboarding seat's own grant logs it"
      (let [as (granted h (onboarding-entry "greenleaf"))
            resp (door as)]
        (is (served? resp) (pr-str (json resp)))
        (is (= [a-rule] (get-in (stored eng id) [:data :workarounds])))))))

(deftest the-collection-finds-a-plan-by-its-workarounds-concept
  (let [eng (engine)
        h (dev/handler eng)
        greenleaf (filed! eng "greenleaf")
        bluefern (filed! eng "bluefern")
        _ (filed! eng "redoak")
        found #(let [resp (req h :get "/api/org_plans" person nil %)]
                 (assert (= 200 (:status resp)) (pr-str (json resp)))
                 (set (map id-of (get-in (json resp) [:data :items]))))]
    (log! eng greenleaf [a-rule a-fee])
    (log! eng bluefern [(assoc a-fee :subject "quote.deposit")])
    (is (= #{greenleaf} (found "workarounds_concept=1")))
    (is (= #{greenleaf bluefern} (found "workarounds_concept=3")))
    (is (= #{greenleaf bluefern} (found "workarounds_concept=1,3")))
    (is (= #{} (found "workarounds_concept=6")))))
