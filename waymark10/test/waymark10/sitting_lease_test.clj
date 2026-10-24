(ns waymark10.sitting-lease-test
  "Leases build ticket 3a (docs/spec-leases.md §2, §3): a sitting's
  `expires_at` is set at birth from its seat's `sitting_idle_seconds`,
  renewed at half-life only and after the stamp's own write, refused
  `lease_expired` once past, and read as open by the counts. A report
  renews as a call does, and an ended sitting never renews. The
  backfill gives an open sitting born before the lease its end, once.

  Memory storage; the clock is an atom the tests move."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-10-23T12:00:00Z"))

(def ^:private model-name "lease-economy")

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))
(def ^:private clerk
  (t/principal {:id "lease-clerk" :type :agent :display "Clerk"
                :model model-name}))

(defn- fresh []
  (let [clock (atom t0)]
    [(engine/engine {:storage (memory/storage) :resources []
                     :now-fn (fn [] @clock)})
     clock]))

(defn- later! [clock seconds]
  (swap! clock #(.plusSeconds ^Instant % (long seconds))))

(defn- row-of [eng id]
  (let [rdef (get (inv/resources eng) :sitting)]
    (some->> (store/with-tx (:storage eng)
               (fn [tx] (store/load-row (:storage eng) tx :sitting (str id) {})))
             (inv/decode-row rdef))))

(defn- ends-at [eng id]
  (some-> (get-in (row-of eng id) [:data :expires_at]) str Instant/parse))

(defn- transitions-of [eng id]
  (count (store/with-tx (:storage eng)
           (fn [tx] (store/transitions (:storage eng) tx
                                       {:kind :sitting :resource-id id} {})))))

(defn- refusal [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (pr-str (ex-data e)))))

(defn- sitting!
  "An open sitting of a fresh seat, born at the clock's now. → {:sitting
  :seat :grant}."
  [eng seat-name & [extra]]
  (let [model (:row (inv/create! eng :model
                                 {:name model-name :display model-name
                                  :vendor "anthropic" :tier "economy"
                                  :price_input_per_mtok 1M
                                  :price_output_per_mtok 5M
                                  :price_cache_read_per_mtok 0.1M
                                  :price_cache_write_per_mtok 1.25M}
                                 {:principal colton}))
        seat (:row (inv/create! eng :seat
                                (merge {:name seat-name
                                        :charter "Decide whether a message asks something."
                                        :scope [{:kind "model" :actions ["retire"]}]
                                        :standing_ttl_seconds 604800
                                        :cadence_seconds 3600
                                        :budget_usd_per_week 5M
                                        :sitting_budget_tokens 60000}
                                       extra)
                                {:principal colton}))
        ask (:row (inv/create! eng :approval_request
                               {:task "Walk this seat's rows." :seat seat-name}
                               {:principal clerk}))
        _ (grants/approval-effects!
           eng (get (inv/resources eng) :approval_request) :approve
           (inv/invoke! eng :approval_request (:id ask) :approve nil
                        {:principal colton}))
        grant (get-in (store/with-tx (:storage eng)
                        (fn [tx] (store/load-row (:storage eng) tx :approval_request
                                                 (str (:id ask)) {})))
                      [:data :grant_id])
        row (:row (inv/create! eng :sitting
                               {:seat (:id seat) :model (:id model)
                                :grant (str grant)}
                               {:principal clerk}))]
    {:sitting (:id row) :seat (:id seat) :grant grant}))

(defn- counts [in]
  {:harness_session "s1" :input_tokens in :output_tokens 0
   :cache_read_tokens 0 :cache_write_tokens 0})

(deftest a-sitting-is-born-with-a-lease-of-its-seats-idle-limit
  (let [[eng _] (fresh)
        {:keys [sitting]} (sitting! eng "lease-birth")]
    (is (= (.plusSeconds ^Instant t0 3600) (ends-at eng sitting))
        "started_at plus sitting_idle_seconds, not claim_silence_seconds")))

(deftest a-renewal-happens-at-half-life-only
  (let [[eng clock] (fresh)
        {:keys [sitting]} (sitting! eng "lease-half")
        before (transitions-of eng sitting)]
    (testing "a call with more than half the lease left renews nothing"
      (later! clock 1000)
      (seats/stamp-call! eng sitting)
      (seats/bump-counter! eng sitting :transitions)
      (is (= (.plusSeconds ^Instant t0 3600) (ends-at eng sitting)))
      (is (= before (transitions-of eng sitting))))
    (testing "past the half, the next call renews once, in its own transition"
      (later! clock 900)
      (seats/stamp-call! eng sitting)
      (is (= (.plusSeconds ^Instant t0 (+ 1900 3600)) (ends-at eng sitting)))
      (is (= (inc before) (transitions-of eng sitting))))
    (testing "and the calls after it are back above the half"
      (later! clock 10)
      (seats/bump-counter! eng sitting :refusals)
      (seats/add-served! eng sitting "waymark_get" 10)
      (is (= (.plusSeconds ^Instant t0 (+ 1900 3600)) (ends-at eng sitting)))
      (is (= (inc before) (transitions-of eng sitting))))))

(deftest a-quiet-fired-run-inside-its-idle-limit-stays-live
  (let [[eng clock] (fresh)
        {:keys [sitting seat grant]} (sitting! eng "lease-quiet")]
    (later! clock 600)
    (testing "past claim_silence_seconds (300) the sitting is still open"
      (is (= (str sitting) (str (:id (seats/open-sitting-for-seat eng seat)))))
      (is (= (str sitting) (str (:id (seats/open-sitting-for-grant eng grant)))))
      (is (= 1 (seats/open-sitting-count eng seat)))
      (is (= 1 (seats/busy-sitting-count eng seat))))))

(deftest a-past-due-sitting-is-not-open-and-refuses-renew
  (let [[eng clock] (fresh)
        {:keys [sitting seat grant]} (sitting! eng "lease-lapsed")]
    (later! clock 3601)
    (testing "the counts, the damper's reading and the resit do not see it"
      (is (nil? (seats/open-sitting-for-seat eng seat)))
      (is (nil? (seats/open-sitting-for-grant eng grant)))
      (is (= 0 (seats/open-sitting-count eng seat)))
      (is (= 0 (seats/busy-sitting-count eng seat))))
    (testing "renew is refused lease_expired, and a late call renews nothing"
      (is (re-find #"lease[-_]expired"
                   (str (refusal #(inv/invoke! eng :sitting (str sitting) :renew nil
                                               {:principal seats/seats-actor})))))
      (seats/stamp-call! eng sitting)
      (is (= (.plusSeconds ^Instant t0 3600) (ends-at eng sitting))))))

(deftest a-feed-token-stops-when-its-lease-does
  (let [[eng clock] (fresh)
        {:keys [sitting seat]} (sitting! eng "lease-feed"
                                         {:feed_url "https://inbox.test/feed/lease"
                                          :sitting_idle_seconds 600})
        seat-row (store/with-tx (:storage eng)
                   (fn [tx] (store/load-row (:storage eng) tx :seat (str seat) {})))
        token (:token (seats/issue-feed-token! eng seat-row {:id sitting}))]
    (is (string? token))
    (later! clock 599)
    (is (some? (seats/feed-seat-by-token eng token)) "inside the lease")
    (later! clock 2)
    (is (nil? (seats/feed-seat-by-token eng token))
        "past the lease, before the token's own 35 minutes")))

(deftest a-report-renews-at-half-life-and-an-ended-sitting-never
  (let [[eng clock] (fresh)
        {:keys [sitting]} (sitting! eng "lease-report")]
    (testing "a report past the half renews, after its own write"
      (later! clock 1900)
      (inv/invoke! eng :sitting (str sitting) :report_usage (counts 1000)
                   {:principal clerk})
      (is (= (.plusSeconds ^Instant t0 (+ 1900 3600)) (ends-at eng sitting))))
    (testing "a report on an ended sitting posts and renews nothing"
      (inv/invoke! eng :sitting (str sitting) :close
                   {:input_tokens 2000 :output_tokens 0 :cache_read_tokens 0
                    :cache_write_tokens 0 :turns 1}
                   {:principal clerk})
      (later! clock 2000)
      (inv/invoke! eng :sitting (str sitting) :report_usage (counts 3000)
                   {:principal clerk})
      (is (= (.plusSeconds ^Instant t0 (+ 1900 3600)) (ends-at eng sitting))))))

(deftest the-backfill-gives-an-open-sitting-its-lease-once
  (let [[eng clock] (fresh)
        {:keys [sitting]} (sitting! eng "lease-backfill")
        st (:storage eng)]
    (later! clock 500)
    (store/with-tx st
      (fn [tx]
        (let [row (store/load-row st tx :sitting (str sitting) {:for-update true})]
          (store/update-data! st tx :sitting (str sitting)
                              (-> (:data row)
                                  (dissoc :expires_at)
                                  (assoc :last_call_at (str @clock)))
                              nil))))
    (is (= 1 (seats/backfill-expiries! eng)))
    (is (= (.plusSeconds ^Instant t0 (+ 500 3600)) (ends-at eng sitting))
        "the latest of its stamps plus the seat's idle limit")
    (is (= 0 (seats/backfill-expiries! eng)) "a second pass writes nothing")))
