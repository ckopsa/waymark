(ns waymark10.discovery-pass-test
  "The discovery pass's mechanics after the starvation lessons
  (found on a prod-scale mirror, 2026-07): the unknown-id diff is
  ONE set-based read and re-runs cost nothing when nothing is new;
  mints ride the bulk birth door, chunk-transacted, and one refusing
  body never undoes its neighbors; kinds walk in declared :priority
  order so the product's core never waits behind a reference table
  for alphabetical reasons; and the daemon's job lease admits one
  working process. Needs the test database; WAYMARK10_TEST_DSN
  overrides."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

;; ── the scriptable remote (mirror-expectations' pattern) ────────────

(defrecord ScriptedRemote [state]
  mirror/MirrorAdapter
  (discover [_] (vec (sort (keys (:docs @state)))))
  (pull [_ xid]
    (if-some [doc (get-in @state [:docs xid])]
      [doc (wire/digest doc)]
      (throw (ex-info (str xid " is gone from the remote") {}))))
  (pull-many [_ xids]
    (into {}
          (keep (fn [xid]
                  (when-some [doc (get-in @state [:docs xid])]
                    [xid [doc (wire/digest doc)]])))
          xids))
  (push [_ _ _] (throw (ex-info "pull-only" {}))))

(defn- remote [] (->ScriptedRemote (atom {:docs {}})))
(defn- seed! [rm xid doc] (swap! (:state rm) assoc-in [:docs xid] doc))

(defn- kind-of
  [kw adapter & [{:keys [priority]}]]
  (r/resource
   (mirror/declaration
    {:kind kw :summary "{data.name}"
     :schema [:map [:name {:optional true} [:maybe [:string {:max 80}]]]]}
    (cond-> {:adapter adapter :ttl-seconds 3600 :discover-every 3600}
      priority (assoc :priority priority)))))

(defn- rows-of [eng kind]
  (store/with-tx (:storage eng)
    (fn [tx] (store/query-rows (:storage eng) tx kind {} {:limit 1000}))))

;; ── the set-based diff ──────────────────────────────────────────────

(deftest discovery-diffs-against-stored-ids-and-refills-nothing
  (let [rm (remote)]
    (db/with-test-engine
      [(kind-of :metric rm)]
      (fn [eng]
        (seed! rm "a" {:name "A"})
        (seed! rm "b" {:name "B"})
        (seed! rm "c" {:name "C"})
        (testing "first pass mints every unknown id and fills it"
          (is (= 3 (mirror/discover! eng :metric)))
          (let [rows (rows-of eng :metric)]
            (is (= 3 (count rows)))
            (is (= #{"A" "B" "C"} (into #{} (map #(get-in % [:data :name])) rows)))))
        (testing "a re-run diffs to nothing — restarts cost no mints"
          (is (= 0 (mirror/discover! eng :metric)))
          (is (= 3 (count (rows-of eng :metric)))))
        (testing "only the genuinely new id mints on the next pass"
          (seed! rm "d" {:name "D"})
          (is (= 1 (mirror/discover! eng :metric)))
          (is (= 4 (count (rows-of eng :metric)))))
        (testing "external-ids reads the whole stored set in one call"
          (is (= #{"a" "b" "c" "d"}
                 (store/with-tx (:storage eng)
                   (fn [tx]
                     (into #{} (store/external-ids (:storage eng) tx :metric)))))))))))

;; ── the bulk birth door ─────────────────────────────────────────────

(deftest a-refusing-mint-never-undoes-its-neighbors
  (let [rm (remote)]
    (db/with-test-engine
      [(kind-of :metric rm)]
      (fn [eng]
        (testing "a chunk with one schema-refused body salvages the rest"
          (is (= 2 (inv/create-mints!
                    eng :metric
                    [{:external_id "ok-1"}
                     {:external_id "bad" :no_such_field "boom"}
                     {:external_id "ok-2"}]
                    {:principal mirror/system-observer})))
          (is (= #{"ok-1" "ok-2"}
                 (store/with-tx (:storage eng)
                   (fn [tx]
                     (into #{} (store/external-ids (:storage eng) tx :metric)))))))))))

;; ── declared priority ───────────────────────────────────────────────

(deftest mirror-kinds-walk-declared-priority-not-the-alphabet
  (let [rm (remote)]
    (db/with-test-engine
      [(kind-of :aardvark rm)                 ; alphabetically first
       (kind-of :zebra rm {:priority 1})]     ; declared to fill first
      (fn [eng]
        (is (= [:zebra :aardvark] (mirror/mirror-kinds eng)))))))

(deftest a-non-int-priority-is-a-definition-error
  (is (thrown-with-msg?
       Exception #":priority is an int"
       (kind-of :metric (remote) {:priority "high"}))))

;; ── the discovery lease ─────────────────────────────────────────────

(deftest the-discovery-lease-admits-one-worker
  (let [rm (remote)]
    (db/with-test-engine
      [(kind-of :metric rm)]
      (fn [eng]
        (let [st (:storage eng)]
          ;; a prior suite run's lease must not leak into this test
          (jdbc/execute! (:ds st) ["DELETE FROM waymark10_job_leases"])
          (seed! rm "a" {:name "A"})
          (testing "a held lease idles the daemon"
            (is (true? (store/with-tx st
                         (fn [tx] (store/claim-job-lease!
                                   st tx "mirror-discovery" "the-peer" 60)))))
            (let [d (mirror/start-discovery! eng)]
              (try
                (Thread/sleep 1500)
                (is (= 0 (count (rows-of eng :metric)))
                    "the peer holds the lease; this daemon must not work")
                (testing "a released lease hands the work over within a beat"
                  (store/with-tx st
                    (fn [tx] (store/release-job-lease!
                              st tx "mirror-discovery" "the-peer")))
                  (let [deadline (+ (System/currentTimeMillis) 15000)]
                    (loop []
                      (when (and (zero? (count (rows-of eng :metric)))
                                 (< (System/currentTimeMillis) deadline))
                        (Thread/sleep 500)
                        (recur))))
                  (is (= 1 (count (rows-of eng :metric)))))
                (finally (mirror/stop-discovery! d)))))
          (testing "stop hands the lease back rather than aging it out"
            (is (true? (store/with-tx st
                         (fn [tx] (store/claim-job-lease!
                                   st tx "mirror-discovery" "next-holder" 60)))))))))))
