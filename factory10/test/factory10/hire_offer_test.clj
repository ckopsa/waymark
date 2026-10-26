(ns factory10.hire-offer-test
  "A hire arrives as one held call (ticket 7116068f, M4b): over a memory
  engine with orgs/example loaded and its found plan live, the engine
  opens a hire plan for a gap; the onboarding seat's go_live on it is
  held with the offer — the gap, the seat, its weekly cost and its doors
  in words; the owner's Allow goes live and the pass opens the seat in
  the org's domain; a refused offer retires the plan quiet for a week,
  and open-hire! for that gap is refused hire_quiet until the clock
  passes it. No database.

  Run: cd factory10 && clojure -M:test --focus factory10.hire-offer-test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.org-live :as org-live]
            [factory10.org-plan-walk :as walk]
            [factory10.resources.org-plan :as org-plan]
            [waymark10.server.domains :as domains]
            [waymark10.server.door-receivers :as doors]
            [waymark10.server.engine :as engine]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.org :as org-boot]
            [waymark10.server.roles :as roles]
            [waymark10.server.seed :as seed-load]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-10-18T08:00:00Z"))

;; example's seed's own owner, so the seat it hires sits inside its scope
(def ^:private the-owner
  (t/principal {:id "owner" :type :human :display "Rosa Delgado"}))

(def ^:private the-seat-agent
  (t/principal {:id "onboarding" :type :agent :acts-for "owner"
                :display "onboarding"}))

(def ^:private the-engine
  (t/principal {:id "factory10-hire" :type :system :display "The engine"}))

(def ^:private gap
  {:kind "request" :state "new" :reason "no_holder"
   :row_ids ["r1" "r2"] :oldest_since "2026-10-11T08:00:00Z"
   :expected_seconds 604800 :key "example/request/new"})

(def ^:private a-hire-seat
  {:name "example-request-1"
   :mode "fired"
   :walk "request"
   :rows_per_firing 1
   :cadence_seconds 3600
   :standing_ttl_seconds 604800
   :budget_usd_per_week 8M
   :sitting_budget_tokens 60000
   :charter "You move each request row, one row a firing. Take accept or decline on it when the row is ready for that door, and say why in one sentence."
   :scope [{:kind "request" :actions ["accept" "decline"]}]})

(defn- org-engine
  "A memory engine as the house boots one, its clock `clock`, with
  example's seed loaded and its found plan live."
  [clock]
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (into (vec (main/resources))
                                             ((requiring-resolve 'example.org/resources)))
                            :now-fn #(deref clock)
                            :suppress-mirror-refresh true})]
    (roles/ensure-factory-roles! eng)
    (domains/ensure-factory! eng)
    (seed-load/load! eng (org-boot/loader-seed
                          (edn/read-string (slurp (io/resource "example/seed.edn"))))
                     {})
    (walk/live! eng (:id (:row (inv/create! eng :org_plan {:name "example"}
                                            {:principal the-owner})))
                the-owner)
    eng))

(defn- load-row [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))

(defn- hire! [eng]
  (org-plan/open-hire! eng {:name "example" :gap gap} {:principal the-engine}))

(defn- simulated-hire!
  "A hire plan for the gap, put up with its seat, filmed and flipped. →
  its id."
  [eng]
  (let [id (str (:id (:row (hire! eng))))
        as {:principal the-owner}]
    (inv/invoke! eng :org_plan id :propose
                 {:hire_seat a-hire-seat :change (walk/audited! eng id {})} as)
    (inv/invoke! eng :org_plan id :hand_in_film
                 {:film_url "https://example.com/hire.mp4"
                  :take_report "{}" :head_sha walk/a-head} as)
    (inv/invoke! eng :org_plan id :attach_film
                 {:film_url "https://example.com/hire.mp4"} walk/the-engine)
    (walk/flipped! eng id walk/a-head)
    id))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest a-hire-plan-goes-up-with-its-seat
  (let [eng (org-engine (atom t0))
        id (str (:id (:row (hire! eng))))]
    (is (= :a-hire-names-its-seat
           (keyword (:guard (refusal #(inv/invoke! eng :org_plan id :propose {}
                                                   {:principal the-owner})))))
        "a hire plan with no hire_seat does not go up")))

(deftest the-offer-is-one-held-call-and-allow-seats-the-hire
  (let [eng (org-engine (atom t0))
        id (simulated-hire! eng)]
    (testing "the seat's go_live is held, its why the offer"
      (let [d (refusal #(inv/invoke! eng :org_plan id :go_live {}
                                     {:principal the-seat-agent}))
            why (str (:detail d))]
        (is (= :only-a-person-hires (keyword (:guard d))) (pr-str d))
        (is (str/starts-with? why "Request rows wait in new: 2 past their expected age") why)
        (is (str/includes? why "Add example-request-1 at $8/week?") why)
        (is (str/includes? why "It can accept or decline request rows; it cannot") why)
        (is (<= (count why) 1000))
        (is (= "example-request-1 · $8/wk · example/request/new"
               (get-in d [:evidence :shown])))))
    (testing "the owner's Allow goes live, and the pass opens the seat"
      (inv/invoke! eng :org_plan id :go_live {} {:principal the-owner})
      (is (= "going_live" (name (:state (load-row eng :org_plan id)))))
      ;; the hire's pull request merges: the pass opens the seat after it
      (inv/invoke! eng :change (get-in (load-row eng :org_plan id) [:data :change])
                   :merge nil walk/the-engine)
      (is (= 1 (org-live/mark-live-plans! eng)))
      (let [plan (load-row eng :org_plan id)
            seat (load-row eng :seat (get-in plan [:data :hired_seat]))]
        (is (= "live" (name (:state plan))))
        (is (= "example-request-1" (get-in seat [:data :name])))
        (is (= 8M (bigdec (get-in seat [:data :budget_usd_per_week]))))
        (is (= ["accept" "decline"]
               (mapv str (:actions (first (get-in seat [:data :scope]))))))
        (is (= (str (:id (domains/domain-named eng "example")))
               (str (get-in seat [:data :domain]))))))
    (testing "the found plan is untouched"
      (is (= 1 (count (filter #(= "found" (org-plan/purpose-of %))
                              (map #(inv/decode-row (get (inv/resources eng) :org_plan) %)
                                   (store/with-tx (:storage eng)
                                     (fn [tx] (store/query-rows (:storage eng) tx :org_plan
                                                                {:state "live"} {:limit 10})))))))))))

(deftest a-refused-offer-keeps-the-gap-quiet-a-week
  (let [clock (atom t0)
        eng (org-engine clock)
        id (simulated-hire! eng)
        call (held/hold-door! eng {:kind "org_plan" :action "go_live" :id id
                                   :body {} :caller "onboarding" :owner "owner"
                                   :why "The offer."})]
    (inv/invoke! eng :held_call (str (:id call)) :refuse {:reason "Not this week."}
                 {:principal the-owner})
    (testing "the refusal retires the plan with quiet_until a week on"
      (doors/drain! eng)
      (let [plan (load-row eng :org_plan id)]
        (is (= "retired" (name (:state plan))))
        (is (= "The owner refused the hire." (get-in plan [:data :retired_why])))
        (is (= (.plusSeconds t0 604800)
               (Instant/parse (str (get-in plan [:data :quiet_until])))))))
    (testing "open-hire! for the same gap is refused hire_quiet"
      (is (= :hire_quiet (:refused (hire! eng)))))
    (testing "until the clock passes it"
      (reset! clock (.plusSeconds t0 (inc 604800)))
      (is (some? (:row (hire! eng)))))))

(defn- versions
  "Every org_plan, seat, held_call and domain row's version, by id."
  [eng]
  (into {}
        (for [kind [:org_plan :seat :held_call :domain]
              :when (contains? (inv/resources eng) kind)
              row (store/with-tx (:storage eng)
                    (fn [tx] (store/query-rows (:storage eng) tx kind {} {:limit 1000})))]
          [[kind (str (:id row))] (:version row)])))

(defn- refused-hire!
  "A simulated hire plan whose held go_live the owner refused. → its id."
  [eng]
  (let [id (simulated-hire! eng)
        call (held/hold-door! eng {:kind "org_plan" :action "go_live" :id id
                                   :body {} :caller "onboarding" :owner "owner"
                                   :why "The offer."})]
    (inv/invoke! eng :held_call (str (:id call)) :refuse {:reason "Not this week."}
                 {:principal the-owner})
    id))

(deftest a-refused-hire-retires-by-the-subscription
  (let [eng (org-engine (atom t0))
        id (refused-hire! eng)]
    (testing "the subscription retires it, with no tick"
      (doors/drain! eng)
      (is (= "retired" (name (:state (load-row eng :org_plan id))))))
    (testing "a second drain and a tick over the same rows write nothing"
      (let [before (versions eng)]
        (doors/drain! eng)
        (is (= {:marked 0} (org-live/pass! eng)))
        (is (= before (versions eng)))))))

(deftest an-expired-hire-retires-by-the-subscription
  (let [eng (org-engine (atom t0))
        id (simulated-hire! eng)
        call (held/hold-door! eng {:kind "org_plan" :action "go_live" :id id
                                   :body {} :caller "onboarding" :owner "owner"
                                   :why "The offer."})]
    (inv/invoke! eng :held_call (str (:id call)) :expire {} walk/the-engine)
    (doors/drain! eng)
    (let [plan (load-row eng :org_plan id)]
      (is (= "retired" (name (:state plan))))
      (is (= "The offer expired before the owner answered."
             (get-in plan [:data :retired_why]))))))

(deftest a-refused-found-plan-is-left-alone
  (let [eng (org-engine (atom t0))
        id (str (:id (:row (inv/create! eng :org_plan {:name "greenleaf"}
                                        {:principal the-owner}))))
        as {:principal the-owner}]
    (walk/proposed! eng id the-owner)
    (inv/invoke! eng :org_plan id :hand_in_film
                 {:film_url "https://example.com/greenleaf.mp4"
                  :take_report "{}" :head_sha walk/a-head} as)
    (inv/invoke! eng :org_plan id :attach_film
                 {:film_url "https://example.com/greenleaf.mp4"} walk/the-engine)
    (walk/flipped! eng id walk/a-head)
    (is (= "simulated" (name (:state (load-row eng :org_plan id)))))
    (testing "decline_hire refuses a plan that is not a hire"
      (let [d (refusal #(inv/invoke! eng :org_plan id :decline_hire
                                     {:why "refuse"} walk/the-engine))]
        (is (= :only-a-hire-is-declined (keyword (:guard d))) (pr-str d))))
    (testing "a refused go_live on it leaves the plan simulated"
      (let [call (held/hold-door! eng {:kind "org_plan" :action "go_live" :id id
                                       :body {} :caller "onboarding" :owner "owner"
                                       :why "Go live?"})]
        (inv/invoke! eng :held_call (str (:id call)) :refuse {:reason "Not yet."}
                     {:principal the-owner})
        (doors/drain! eng)
        (is (= "simulated" (name (:state (load-row eng :org_plan id)))))))))

(deftest a-tick-with-nothing-to-do-writes-nothing
  (let [eng (org-engine (atom t0))
        _ (simulated-hire! eng)
        before (versions eng)]
    (is (= {:marked 0} (org-live/pass! eng)))
    (is (= before (versions eng)))))

(deftest the-daemon-ticks-the-pass
  (let [eng (org-engine (atom t0))
        id (simulated-hire! eng)
        _ (inv/invoke! eng :org_plan id :go_live {} {:principal the-owner})
        _ (inv/invoke! eng :change (get-in (load-row eng :org_plan id) [:data :change])
                       :merge nil walk/the-engine)
        daemon (org-live/start-passes! eng {:every-seconds 0.05})]
    (try
      (is (loop [n 0]
            (cond
              (= "live" (name (:state (load-row eng :org_plan id)))) true
              (< n 100) (do (Thread/sleep 50) (recur (inc n)))
              :else false))
          "the daemon's tick marks the merged hire live")
      (finally (org-live/stop-passes! daemon)))))
