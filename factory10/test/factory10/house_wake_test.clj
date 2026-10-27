(ns factory10.house-wake-test
  "The house's merge pass wakes on the pull request's own green and red,
  and on its merge, through a durable log consumer (ticket 1673d79e).

  The pull request moves as it does in a deployment: the in-memory
  GitHub seeds a head and its check, the mirror re-reads the row, and
  the row's `checks_green`, `checks_red` or `merged` lands in the log.
  A REAL dispatcher wakes the consumer to it.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.bench :as bench]
            [factory10.main :as main]
            [factory10.mirror :as house-mirror]
            [factory10.resources.pull-request :as pr]
            [factory10.sources.forge :as forge]
            [factory10.sources.github :as gh]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private repo "ckopsa/waymark")

(def ^:private a-person (t/principal {:id "colton" :type :human}))

(def ^:private house-policy
  {:merge_by "house" :required_checks ["gate"] :merge_method "squash"})

(def ^:private t0 (Instant/parse "2026-10-27T12:00:00Z"))

(defn- at [s] (.plusSeconds ^Instant t0 (long s)))

(defn- await-pred [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 25) (recur))))))

(defn- head [n] (format "%040x" n))

(defn- a-pull [n]
  {:number 31
   :state "open"
   :title "Change 31"
   :user {:login "ckopsa"}
   :draft false
   :base {:ref "main"}
   :head {:ref "engine/change-31" :sha (head n)}
   :html_url (str "https://github.com/" repo "/pull/31")
   :updated_at "2026-09-18T12:00:00Z"
   :labels [{:name "agent"}]
   :mergeable_state "clean"
   :changed_files 2
   :additions 40
   :deletions 3})

(defn- a-check [n status conclusion]
  {:id n :name "gate" :status status :conclusion conclusion :head_sha (head n)
   :html_url (str "https://github.com/" repo "/runs/" n)
   :details_url (str "https://github.com/" repo "/actions/runs/900/job/" n)})

(defn- rows [eng kind]
  (let [st (:storage eng)
        rd (get (inv/resources eng) kind)]
    (mapv #(inv/decode-row rd %)
          (store/with-tx st (fn [tx] (store/query-rows st tx kind {} {:all true}))))))

(defn- the-pull-request [eng]
  (first (filter #(= (pr/external-id repo 31) (get-in % [:data :external_id]))
                 (rows eng :pull_request))))

(defn- world
  "One engine whose repository's policy is `policy-extra`, with pull
  request #31 mirrored and its change adopted, a running dispatcher, and
  the wake consumer registered over a fresh board."
  [policy-extra]
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (engine/engine {:storage (memory/storage)
                            :now-fn (fn [] @clock)
                            :resources (conj (filterv #(not= :pull_request (:kind %))
                                                      (main/resources))
                                             (pr/pull-request-resource adapter))})
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy (merge {:repository repo} policy-extra)
                       {:principal a-person})
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        store (forge/cursor-store eng)]
    (when-not ((:load store) repo)
      ((:save! store) repo "2026-09-18T11:00:00Z"))
    (inv/create! eng :change {:change_id "ticket:t31"
                              :repository repo
                              :title "Change 31"
                              :head_branch "engine/change-31"
                              :base_branch "main"}
                 {:principal house-mirror/source-principal})
    (gh/seed-pull! state repo (a-pull 1))
    (gh/seed-check! state repo (head 1) (a-check 1 "in_progress" nil))
    (forge/pass! {:source source :engine eng :log-fn (fn [& _] nil)})
    (pr/connect! adapter eng source)
    (mirror/discover! eng :pull_request)
    (let [d (events/dispatcher eng {:poll-ms 50})
          board (bench/house-board)]
      {:eng eng :state state :board board :dispatcher d
       :consumer (bench/start-house-wake! eng board {:dispatcher d :poll-ms 50})})))

(defn- stop! [{:keys [consumer dispatcher]}]
  (some-> consumer consumers/stop-consumer!)
  (events/stop! dispatcher))

(defn- checked!
  "Seed head `n` with a finished check of `conclusion` (or the pull
  request's `extra`), and let the mirror re-read the row."
  [{:keys [eng state]} n conclusion & [extra]]
  (gh/seed-pull! state repo (merge (a-pull n) extra))
  (gh/seed-check! state repo (head n) (a-check n "completed" conclusion))
  (:data (mirror/refresh! eng (get (inv/resources eng) :pull_request)
                          (the-pull-request eng) {:ignore-ttl? true})))

(defn- pending? [{:keys [board]}]
  (contains? (:pending @board) repo))

(deftest a-house-repositorys-verdict-wakes-its-merge-pass
  (let [w (world house-policy)
        board (:board w)
        ran (atom [])
        beat! (fn [s] (bench/house-beat! board (at s) false (fn [])
                                         #(swap! ran conj %)))]
    (try
      (testing "checks_red puts the repository on the board, and the next beat works it"
        (let [d (checked! w 2 "failure")]
          (is (= "red" (:checks_verdict d)))
          (is (await-pred #(pending? w) 10000))
          (is (= #{repo} (:woken (beat! 0))))
          (is (= [#{repo}] @ran) "the merge pass ran for that repository")
          (is (false? (bench/nudge-house! board repo
                                          [:checks (str (:change d)) (head 2) :red]))
              "the forge pass's own observation of that red wakes nothing again")))
      (testing "checks_green does the same, and wakes once beside the forge pass"
        (let [d (checked! w 3 "success")
              change-id (str (:change d))]
          (is (= "green" (:checks_verdict d)))
          (is (= change-id (str (:id (first (rows (:eng w) :change)))))
              "the pull request names the change the forge pass names")
          (is (await-pred #(pending? w) 10000))
          (is (contains? (:seen @board) [:checks change-id (head 3) :green]))
          (is (false? (bench/nudge-house! board repo [:checks change-id (head 3) :green]))
              "the same observation from the forge pass wakes nothing")
          (is (= #{repo} (:woken (beat! 3600))))
          (is (= [#{repo} #{repo}] @ran))))
      (testing "a merge wakes the next front"
        (checked! w 3 "success" {:state "closed"
                                 :merged_at "2026-09-18T17:00:00Z"
                                 :updated_at "2026-09-18T17:00:00Z"})
        (is (await-pred #(pending? w) 10000))
        (is (= #{repo} (:woken (beat! 7200)))))
      (finally (stop! w)))))

(deftest a-repository-the-house-does-not-merge-wakes-nothing
  (let [w (world {:required_checks ["gate"]})
        board (:board w)]
    (try
      (is (= "green" (:checks_verdict (checked! w 3 "success"))))
      (finally (stop! w)))
    ;; the consumer is stopped; whatever it had not heard yet, hear now,
    ;; so the board below is the whole log's answer
    (consumers/drain-consumer! (:eng w) bench/house-wake-consumer
                               #(bench/house-wake! (:eng w) board %))
    (is (empty? (:pending @board)))
    (is (empty? (:seen @board)))))

(deftest stopping-the-sweeper-stops-its-consumer
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (main/resources)})
        d (events/dispatcher eng {:poll-ms 50})
        s (bench/start-enrol-sweeper! eng {:every-seconds 3600 :dispatcher d
                                           :poll-ms 50})]
    (try
      (is (some? (:consumer s)))
      (is (= bench/house-wake-consumer (get-in s [:consumer :name])))
      (is (true? @(get-in s [:consumer :running])))
      (bench/stop-enrol-sweeper! s)
      (is (false? @(get-in s [:consumer :running])))
      (is (not (.isAlive ^Thread (get-in s [:consumer :thread]))))
      (finally (events/stop! d)))))
