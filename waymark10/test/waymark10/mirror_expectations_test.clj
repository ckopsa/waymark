(ns waymark10.mirror-expectations-test
  "The :expect dynamics grammar: {:expect :immutable} — a document
  moving (or destroying) a set-once value lands the row CONFLICTED,
  resolve_conflict ratifies; {:expect {:churn n}} — more rows changing
  than the declared percent allows is held like the mass-absence
  census and releases when a pass fits the bound; {:expect :volatile}
  — a pass where the field moves nowhere warns (the feed's pipeline
  may have frozen while still answering). Needs the test database:
  WAYMARK10_TEST_DSN overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the scriptable remote ───────────────────────────────────────────

(defn- etag-of [doc] (wire/digest doc))

(defrecord ScriptedRemote [state]
  mirror/MirrorAdapter
  (discover [_] (vec (sort (keys (:docs @state)))))
  (pull [_ xid]
    (if-some [doc (get-in @state [:docs xid])]
      [doc (etag-of doc)]
      (throw (ex-info (str xid " is gone from the remote") {}))))
  (pull-many [_ xids]
    (into {}
          (keep (fn [xid]
                  (when-some [doc (get-in @state [:docs xid])]
                    [xid [doc (etag-of doc)]])))
          xids))
  (push [_ _ _] (throw (ex-info "pull-only" {}))))

(defn- remote [] (->ScriptedRemote (atom {:docs {}})))
(defn- seed! [rm xid doc] (swap! (:state rm) assoc-in [:docs xid] doc))

;; ── the kind: a metric with declared dynamics ───────────────────────

(defn- metric-kind [adapter]
  (r/resource
   (mirror/declaration
    {:kind :metric :summary "{data.name}"
     :schema [:map
              [:name {:optional true} [:maybe [:string {:max 80}]]]
              [:started {:optional true :expect :immutable}
               [:maybe [:string {:max 40}]]]
              [:price {:optional true :expect :volatile}
               [:maybe [:string {:max 40}]]]
              [:dept {:optional true :expect {:churn 50}}
               [:maybe [:string {:max 80}]]]]}
    {:adapter adapter :ttl-seconds 3600 :discover-every 3600})))

(defn- row-of [eng xid]
  (store/with-tx (:storage eng)
    (fn [tx]
      (first (store/query-rows (:storage eng) tx :metric
                               {:external_id xid} {:limit 1})))))

(defn- field [eng xid f] (get-in (row-of eng xid) [:data f]))

;; ── :immutable ──────────────────────────────────────────────────────

(deftest immutable-violations-conflict-and-ratify
  (let [metrics (remote)]
    (db/with-test-engine
      [(metric-kind metrics)]
      (fn [eng]
        (seed! metrics "m1" {:name "M1" :started "2020-01-01"
                             :price "1" :dept "ops"})
        (seed! metrics "m2" {:name "M2" :price "2" :dept "ops"})
        (mirror/discover! eng :metric)

        (testing "first-set is never a violation"
          (seed! metrics "m2" {:name "M2" :started "2021-01-01"
                               :price "2" :dept "ops"})
          (let [res (mirror/resync! eng :metric)]
            (is (= 0 (:conflicted res)))
            (is (= "2021-01-01" (field eng "m2" :started)))))

        (testing "a moved set-once value lands the row conflicted,
                  the local document standing"
          (seed! metrics "m1" {:name "M1" :started "2020-06-06"
                               :price "1" :dept "ops"})
          (let [res (mirror/resync! eng :metric)]
            (is (= 1 (:conflicted res))))
          (let [m1 (row-of eng "m1")]
            (is (= :conflicted (:state m1)))
            (is (= "2020-01-01" (get-in m1 [:data :started])))
            (is (str/includes? (get-in m1 [:data :conflict_reason])
                               "immutable started moved"))))

        (testing "resolve_conflict keep=remote is the ratification"
          (inv/invoke! eng :metric (:id (row-of eng "m1"))
                       :resolve_conflict {:keep "remote"}
                       {:principal (t/principal
                                    {:id "ana" :type :human :display "Ana"})})
          (let [m1 (row-of eng "m1")]
            (is (= :fresh (:state m1)))
            (is (= "2020-06-06" (get-in m1 [:data :started])))))

        (testing "destroying a set-once value (whole-doc absence) is
                  a violation too — unless the census already defends
                  it (here m2 still carries the key)"
          (seed! metrics "m1" {:name "M1" :price "1" :dept "ops"})
          (let [res (mirror/resync! eng :metric)]
            (is (= 1 (:conflicted res))))
          (is (= :conflicted (:state (row-of eng "m1")))))))))

;; ── {:churn n} ──────────────────────────────────────────────────────

(deftest churn-bounds-hold-and-release
  (let [metrics (remote)]
    (db/with-test-engine
      [(metric-kind metrics)]
      (fn [eng]
        (seed! metrics "c1" {:name "C1" :price "1" :dept "ops"})
        (seed! metrics "c2" {:name "C2" :price "2" :dept "ops"})
        (mirror/discover! eng :metric)

        (testing "churn within the bound applies (1 of 2 = 50%,
                  bound {:churn 50} — not exceeded)"
          (seed! metrics "c1" {:name "C1" :price "1" :dept "fin"})
          (mirror/resync! eng :metric)
          (is (= "fin" (field eng "c1" :dept))))

        (testing "churn OVER the bound holds the field (2 of 2)"
          (seed! metrics "c1" {:name "C1" :price "1" :dept "hr"})
          (seed! metrics "c2" {:name "C2" :price "2" :dept "hr"})
          (mirror/resync! eng :metric)
          (is (= "fin" (field eng "c1" :dept)))
          (is (= "ops" (field eng "c2" :dept))
              "…while other fields keep syncing normally"))

        (testing "a pass back within the bound releases — held rows
                  re-apply even when their documents didn't move again"
          (seed! metrics "c1" {:name "C1" :price "1" :dept "fin"})
          (mirror/resync! eng :metric)
          (is (= "fin" (field eng "c1" :dept)))
          (is (= "hr" (field eng "c2" :dept))
              "c2's held value applied by the release"))))))

;; ── :volatile ───────────────────────────────────────────────────────

(deftest volatile-stagnation-warns
  (let [metrics (remote)]
    (db/with-test-engine
      [(metric-kind metrics)]
      (fn [eng]
        (seed! metrics "v1" {:name "V1" :price "10" :dept "ops"})
        (seed! metrics "v2" {:name "V2" :price "20" :dept "ops"})
        (mirror/discover! eng :metric)

        (testing "a pass where the volatile field moves nowhere warns"
          (let [sw (java.io.StringWriter.)]
            (binding [*err* sw]
              (mirror/resync! eng :metric))
            (is (str/includes? (str sw) "stagnation for metric"))))

        (testing "a pass where it moves anywhere stays quiet"
          (seed! metrics "v1" {:name "V1" :price "11" :dept "ops"})
          (let [sw (java.io.StringWriter.)]
            (binding [*err* sw]
              (mirror/resync! eng :metric))
            (is (not (str/includes? (str sw) "stagnation")))))))))

;; ── refusals and the fingerprint ────────────────────────────────────

(deftest expect-refusals-and-facet
  (testing "malformed expectations refuse at the def site"
    (doseq [bad [:sometimes {:churn 0} {:churn 101} {:churn "x"}
                 {:churn 10 :extra 1}]]
      (is (thrown-with-msg?
           Exception #":expect is :immutable, :volatile, or \{:churn 1\.\.100\}"
           (r/resource
            (mirror/declaration
             {:kind :bad :summary "x"
              :schema [:map [:f {:optional true :expect bad}
                             [:maybe [:string {:max 10}]]]]}
             {:adapter (remote) :ttl-seconds 60 :discover-every 60})))
          (pr-str bad))))

  (testing "expectations fingerprint in the authority facet"
    (let [fp (fp/fingerprint-of (metric-kind (remote)))]
      (is (= "immutable" (get-in fp ["authority" "fields" "started" "expect"])))
      (is (= "volatile" (get-in fp ["authority" "fields" "price" "expect"])))
      (is (= {"churn" 50} (get-in fp ["authority" "fields" "dept" "expect"]))))))
