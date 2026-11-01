(ns waymark10.amendment-proving-test
  "Amendments R-A.12.3 (docs/spec-amendment.md): a deliberately bad
  amendment end to end in a test house, on one clock. A seat keeps
  \"every open task is walked within a day\"; an amendment narrows its
  `walk_filter` to priority 0; the replay names what drops and who holds
  it then; the owner adopts; the trial reads `worse` off the promise's
  own log; watched, nothing reverts; armed, the revert is held for the
  owner's tap, and that tap puts back the exact prior filter once."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.amendment-policy :as policy]
            [waymark10.server.amendment-revert :as revert]
            [waymark10.server.amendment-trial :as trial]
            [waymark10.server.amendments :as amendments]
            [waymark10.server.engine :as engine]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.promises :as promises]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Duration Instant)))

(def ^:private elena (t/principal {:id "elena" :type :human :display "Elena"}))

(def ^:private t0 (Instant/parse "2026-10-01T00:00:00Z"))

(def ^:private task
  (r/resource
   {:kind :task
    :plural "tasks"
    :states [:draft :open :walked]
    :initial :draft
    :terminal #{:walked}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 200}]] [:priority :int]]
    :filterable {:state #{:eq :in} :priority #{:eq :in}}
    :actions {:groom {:from #{:draft} :to :open
                      :safety {:idempotent true :reversible false :confirm false
                               :one-way "Open is open."}}
              :walk {:from #{:open} :to :walked
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "Walked is walked."}}}}))

(def ^:private walked-in-a-day
  {:name "every open task is walked within a day"
   :about {:kind "task" :filter {:state "open"}}
   :holds {:query {:kind "task" :filter {:id "{id}" :state "open"} :expect "none"}}
   :by {:after "groom" :within 86400}
   :remedy {:kind "task" :door "walk"}})

(def ^:private office
  {:held_for []
   :substitute_for []
   :standing_ttl_seconds 604800
   :cadence_seconds 3600
   :budget_usd_per_week 5M
   :sitting_budget_tokens 60000
   :rows_per_firing 20})

(def ^:private walker
  "The seat that keeps the promise: it walks every open task."
  (assoc office :name "walker" :charter "Walk every open task." :walk "task"
         :scope [{:kind "task" :actions ["walk"] :filter {:state "open"}}]))

(def ^:private desk
  "The owner's own seat, a person's: what the walker drops comes here."
  (assoc office :name "desk" :charter "The owner's desk." :mode "interactive"
         :scope [{:kind "task" :actions ["groom" "walk"]}]))

(defn- load-row [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st #(store/load-row st % kind id {}))))

(defn- restates [eng sid]
  (let [st (:storage eng)]
    (count (filter #(and (= sid (str (:resource-id %))) (= "restate" (name (:action %))))
                   (store/with-tx st #(store/transitions st % {:kind :seat}
                                                         {:newest-first true :limit 1000}))))))

(defn- walk-filter [eng sid]
  (revert/walk-filter-of (:data (load-row eng :seat sid))))

(defn- id-of [res] (str (:id (:row res))))

(defn- house!
  "The whole story up to the trial's end, the clock moved by hours:
  one break and keep the week before the adopt, two breaks the week
  after. `armed?` arms `seat.restate` before the adopt."
  [armed?]
  (let [clock (atom t0)
        at! (fn [hours] (reset! clock (.plus t0 (Duration/ofHours hours))))
        eng (engine/engine {:storage (store/stamped (memory/storage) #(deref clock))
                            :now-fn #(deref clock)
                            :resources [task promises/promise amendments/amendment
                                        policy/amendment-policy]})
        as-elena {:principal elena}]
    (when armed?
      (let [pid (id-of (inv/create! eng :amendment_policy {:target "seat.restate"} as-elena))]
        (inv/invoke! eng :amendment_policy pid :arm nil (assoc as-elena :idempotency-key "arm"))))
    (inv/create! eng :seat desk as-elena)
    (let [sid (id-of (inv/create! eng :seat walker as-elena))
          task! (fn [title priority]
                  (let [id (id-of (inv/create! eng :task {:title title :priority priority} as-elena))]
                    (inv/invoke! eng :task id :groom nil as-elena)
                    id))
          low (task! "Water the plants" 0)
          high (task! "Pay the gas bill" 1)
          pid (id-of (inv/create! eng :promise walked-in-a-day as-elena))
          move! (fn [hours door row]
                  (at! hours)
                  (inv/invoke! eng :promise pid door {:row row :at (str @clock)}
                               {:principal promises/engine-actor
                                :idempotency-key (str (name door) row hours)}))
          _ (move! 24 :break high)
          _ (move! 30 :keep high)
          prior (walk-filter eng sid)
          _ (at! (* 7 24))
          aid (id-of (inv/create! eng :amendment
                                  {:title "Walk only the urgent tasks"
                                   :owner "elena"
                                   :answers [{:promise pid}]
                                   :changes {:kind "seat" :id sid :door "restate"
                                             :patch {:walk_filter {:state "open" :priority "0"}}}}
                                  as-elena))
          _ (amendments/replay! eng aid)
          replayed (:data (load-row eng :amendment aid))
          _ (inv/invoke! eng :amendment aid :adopt nil (assoc as-elena :idempotency-key "adopt"))
          adopted (walk-filter eng sid)]
      (move! (* 8 24) :break high)
      (move! (+ (* 8 24) 12) :keep high)
      (move! (* 9 24) :break high)
      (at! (* 15 24))
      (trial/sweep! eng)
      {:eng eng :sid sid :aid aid :low low :high high
       :prior prior :adopted adopted :replay (:replay replayed)})))

(defn- replay-names-the-drop [{:keys [replay low high]}]
  (testing "the replay names the dropped row and the person it moves to"
    (let [dropped (set (map :id (get-in replay [:walk :dropped])))]
      (is (contains? dropped high) (pr-str replay))
      (is (not (contains? dropped low)) (pr-str replay))
      (is (some #{high} (get-in replay [:holders :to_person])) (pr-str replay)))))

(deftest watched-a-worse-narrowing-reverts-nothing
  (let [{:keys [eng sid aid prior adopted] :as h} (house! false)
        a (load-row eng :amendment aid)]
    (replay-names-the-drop h)
    (is (not= prior adopted) "the adopt narrowed the walk")
    (is (= "0" (str (or (get adopted :priority) (get adopted "priority")))))
    (testing "the trial reads worse off the promise's log"
      (is (= "watch" (get-in a [:data :mode])))
      (is (= "settled" (name (:state a))))
      (is (= "worse" (get-in a [:data :verdict])) (pr-str (get-in a [:data :trial])))
      (is (= 1 (get-in a [:data :trial :readings :before :breaks])))
      (is (= 2 (get-in a [:data :trial :readings :after :breaks]))))
    (testing "watched, nothing reverts"
      (is (= adopted (walk-filter eng sid)))
      (is (= 1 (restates eng sid)) "the adopt's restate, and no revert"))))

(deftest armed-a-worse-narrowing-is-reverted-by-the-owners-tap
  (let [{:keys [eng sid aid prior adopted] :as h} (house! true)]
    (replay-names-the-drop h)
    (is (= "revert" (get-in (load-row eng :amendment aid) [:data :mode])))
    (testing "the sweep proposes a held revert and restores nothing on its own"
      (is (= "trial" (name (:state (load-row eng :amendment aid)))))
      (is (= adopted (walk-filter eng sid)))
      (is (= 1 (restates eng sid))))
    (let [hid (str (:id (:held (revert/revert! eng aid {:verdict "worse"}))))]
      (is (not= "" hid))
      (testing "the owner allows it, and the walk is the exact prior filter"
        (let [out (inv/invoke! eng :held_call hid :allow {:choice 0} {:principal elena})]
          (held/forward! eng (:row out)))
        (is (= "reverted" (name (:state (load-row eng :amendment aid)))))
        (is (= prior (walk-filter eng sid)))
        (is (= 2 (restates eng sid)) "one restate by adopt, one by the tap"))
      (testing "a second revert finds nothing to do"
        (try (revert/revert! eng aid {:verdict "worse"}) (catch Exception _ nil))
        (trial/sweep! eng)
        (is (= "reverted" (name (:state (load-row eng :amendment aid)))))
        (is (= prior (walk-filter eng sid)))
        (is (= 2 (restates eng sid)))))))
