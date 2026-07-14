(ns waymark10.mirror-document-semantics-test
  "The mirror document contract (:whole/:partial), authority windows
  (:adopts/:frozen), and the mass-absence census: absence under
  :whole is unset; absence under :partial is silence; a window keeps
  the feed out before adoption and after the sunset (with pre-sunset
  absence holding); and a field vanishing from the ENTIRE feed is an
  observed deprecation — held loudly, never N silent nil-outs — that
  self-releases when the key returns. Needs the test database:
  WAYMARK10_TEST_DSN overrides."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.test.db :as db]
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

;; ── the kinds: report (:whole, windowed) and journal (:partial) ─────

(def ^:private report-fields
  [:map
   [:name {:optional true} [:maybe [:string {:max 80}]]]
   [:total_aum {:optional true} [:maybe [:string {:max 40}]]]
   ;; already frozen (past date) and frozen-as-of-now: the feed's
   ;; values never land
   [:legacy {:optional true :frozen "2000-01-02"} [:maybe [:string {:max 40}]]]
   [:pinned {:optional true :frozen true} [:maybe [:string {:max 40}]]]
   ;; announced sunset in the far future: syncs now, absence holds
   [:sunset {:optional true :frozen "2999-01-01"} [:maybe [:string {:max 40}]]]
   ;; adoption in the far future (feed ignored) and the far past
   ;; (ordinary field)
   [:upcoming {:optional true :adopts "2999-01-01"} [:maybe [:string {:max 40}]]]
   [:live {:optional true :adopts "2000-01-02"} [:maybe [:string {:max 40}]]]])

(defn- report-kind [adapter]
  (r/resource
   (mirror/declaration
    {:kind :report :summary "{data.name}" :schema report-fields}
    {:adapter adapter :ttl-seconds 3600 :discover-every 3600})))

(defn- journal-kind [adapter]
  (r/resource
   (mirror/declaration
    {:kind :journal :summary "{data.name}"
     :schema [:map
              [:name {:optional true} [:maybe [:string {:max 80}]]]
              [:total_aum {:optional true} [:maybe [:string {:max 40}]]]]}
    {:adapter adapter :ttl-seconds 3600 :discover-every 3600
     :document :partial})))

(defn- row-of [eng kind xid]
  (store/with-tx (:storage eng)
    (fn [tx]
      (first (store/query-rows (:storage eng) tx kind
                               {:external_id xid} {:limit 1})))))

(defn- field [eng kind xid f] (get-in (row-of eng kind xid) [:data f]))

;; ── whole vs partial ────────────────────────────────────────────────

(deftest whole-absence-is-unset-partial-absence-is-silence
  (let [reports (remote) journals (remote)]
    (db/with-test-engine
      [(report-kind reports) (journal-kind journals)]
      (fn [eng]
        (seed! reports "r1" {:name "R1" :total_aum "100"})
        (seed! reports "r2" {:name "R2" :total_aum "200"})
        (seed! journals "j1" {:name "J1" :total_aum "100"})
        (mirror/discover! eng :report)
        (mirror/discover! eng :journal)
        (is (= "100" (field eng :report "r1" :total_aum)))

        (testing ":whole — one row losing the key is unset (r2 keeps
                  it, so no census)"
          (seed! reports "r1" {:name "R1"})
          (mirror/resync! eng :report)
          (is (nil? (field eng :report "r1" :total_aum)))
          (is (= "200" (field eng :report "r2" :total_aum))))

        (testing ":partial — absence is silence, the value stands"
          (seed! journals "j1" {:name "J1 renamed"})
          (mirror/resync! eng :journal)
          (is (= "J1 renamed" (field eng :journal "j1" :name)))
          (is (= "100" (field eng :journal "j1" :total_aum))))))))

;; ── authority windows ───────────────────────────────────────────────

(deftest authority-windows-gate-the-feed
  (let [reports (remote)]
    (db/with-test-engine
      [(report-kind reports)]
      (fn [eng]
        (seed! reports "w1" {:name "W1"
                             :legacy "from-feed" :pinned "from-feed"
                             :sunset "S1" :upcoming "from-feed"
                             :live "L1"})
        (seed! reports "w2" {:name "W2" :sunset "S2" :live "L2"})
        (mirror/discover! eng :report)

        (testing "past-frozen and frozen-true never adopt the feed"
          (is (nil? (field eng :report "w1" :legacy)))
          (is (nil? (field eng :report "w1" :pinned))))

        (testing "a future sunset syncs like any field"
          (is (= "S1" (field eng :report "w1" :sunset))))

        (testing "a future adoption ignores the feed; a past one syncs"
          (is (nil? (field eng :report "w1" :upcoming)))
          (is (= "L1" (field eng :report "w1" :live))))

        (testing "absence BEFORE a declared sunset holds (w2 still
                  carries the key, so this is the window rule, not
                  the census)"
          (seed! reports "w1" {:name "W1 changed"})
          (mirror/resync! eng :report)
          (is (= "W1 changed" (field eng :report "w1" :name)))
          (is (= "S1" (field eng :report "w1" :sunset))
              "the record survives the early yank")
          (is (nil? (field eng :report "w1" :live))
              "…while an unwindowed absent field is honestly unset"))))))

;; ── the mass-absence census ─────────────────────────────────────────

(deftest mass-absence-holds-and-releases
  (let [reports (remote)]
    (db/with-test-engine
      [(report-kind reports)]
      (fn [eng]
        (seed! reports "c1" {:name "C1" :total_aum "111"})
        (seed! reports "c2" {:name "C2" :total_aum "222"})
        (mirror/discover! eng :report)

        (testing "the key vanishing from EVERY document holds values"
          (seed! reports "c1" {:name "C1"})
          (seed! reports "c2" {:name "C2"})
          (is (= {:checked 2 :rewritten 2 :gone 0}
                 (mirror/resync! eng :report))
              "both rows re-observed (etags changed), values held")
          (is (= "111" (field eng :report "c1" :total_aum)))
          (is (= "222" (field eng :report "c2" :total_aum))))

        (testing "the key returning anywhere releases the hold — and
                  the release re-observes held rows, so ghosts clear"
          (seed! reports "c2" {:name "C2" :total_aum "222b"})
          (is (= {:checked 2 :rewritten 2 :gone 0}
                 (mirror/resync! eng :report))
              "c2 for the change, c1 for the release")
          (is (= "222b" (field eng :report "c2" :total_aum)))
          (is (nil? (field eng :report "c1" :total_aum))
              "c1's absence is one row's absence again: unset"))))))

;; ── def-site refusals and the fingerprint facet ─────────────────────

(defn- report-kind-bad [props]
  (r/resource
   (mirror/declaration
    {:kind :bad :summary "x"
     :schema [:map [:f (merge {:optional true} props)
                    [:maybe [:string {:max 10}]]]]}
    {:adapter (remote) :ttl-seconds 60 :discover-every 60})))

(deftest window-refusals-and-authority-facet
  (testing "malformed dates and inverted windows refuse"
    (is (thrown-with-msg?
         Exception #"ISO date"
         (report-kind-bad {:frozen "next tuesday"})))
    (is (thrown-with-msg?
         Exception #"ISO date"
         (report-kind-bad {:adopts true})))
    (is (thrown-with-msg?
         Exception #"opens before it closes"
         (report-kind-bad {:adopts "2026-09-01" :frozen "2026-08-01"}))))

  (testing "the authority facet fingerprints windows, never cadences"
    (let [fp (fp/fingerprint-of (report-kind (remote)))]
      (is (= {"frozen" "2999-01-01"} (get-in fp ["authority" "fields" "sunset"])))
      (is (= {"adopts" "2999-01-01"} (get-in fp ["authority" "fields" "upcoming"])))
      (is (nil? (get-in fp ["authority" "ttl-seconds"]))))
    (let [plain (r/resource
                 (mirror/declaration
                  {:kind :plain :summary "{data.name}"
                   :schema [:map [:name {:optional true}
                                  [:maybe [:string {:max 80}]]]]}
                  {:adapter (remote) :ttl-seconds 60 :discover-every 60}))]
      (is (nil? (get (fp/fingerprint-of plain) "authority"))
          "a window-less pull-only whole mirror carries no facet — hash
           byte-identical to the pre-authority era"))))
