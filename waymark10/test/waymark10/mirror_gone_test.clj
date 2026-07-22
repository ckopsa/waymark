(ns waymark10.mirror-gone-test
  "The gone-policy (:on-gone): a feed that ANSWERED while a row was
  absent is a deletion observed, never an outage — and what that
  observation MEANS is the kind's declared law. {:set {field value}}
  lands the patch through the woven :observe_gone sync write (state
  → fresh, synced_at stamped, audit-quiet once applied); :keep — the
  default — preserves the old posture exactly (stored truth serves,
  a 404 pull marks unreachable). Plain absence from a pull-many
  batch stays ambiguous: a gone row and a down feed look identical
  there, so nothing is ever patched on absence alone — only the
  adapter's explicit :gone sentinel speaks. Needs the test database;
  WAYMARK10_TEST_DSN overrides."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

;; ── the scriptable remote: it can TELL gone from down ───────────────

(defn- etag-of [doc] (wire/digest doc))

(defrecord GoneAwareRemote [state]
  mirror/MirrorAdapter
  (discover [_] (vec (sort (keys (:docs @state)))))
  (pull [_ xid]
    (if-some [doc (get-in @state [:docs xid])]
      [doc (etag-of doc)]
      (throw (ex-info (str xid " is gone") {:status 404}))))
  (pull-many [_ xids]
    (into {}
          (map (fn [xid]
                 (if-some [doc (get-in @state [:docs xid])]
                   [xid [doc (etag-of doc)]]
                   [xid (if (:ambiguous @state) nil :gone)])))
          (remove #(and (:ambiguous @state)
                        (nil? (get-in @state [:docs %])))
                  xids)))
  (push [_ _ _] (throw (ex-info "pull-only" {}))))

(defn- remote [] (->GoneAwareRemote (atom {:docs {}})))
(defn- seed! [rm xid doc] (swap! (:state rm) assoc-in [:docs xid] doc))
(defn- delete! [rm xid] (swap! (:state rm) update :docs dissoc xid))

;; ── the kinds ───────────────────────────────────────────────────────

(def ^:private fields
  [:map
   [:name {:optional true} [:maybe [:string {:max 80}]]]
   [:phase {:optional true} [:maybe [:enum "open" "gone"]]]])

(defn- dropping-kind [adapter]
  (r/resource
   (mirror/declaration
    {:kind :ticket :summary "{data.name}" :schema fields}
    {:adapter adapter :ttl-seconds 0 :discover-every 3600
     :document :partial
     :on-gone {:set {:phase "gone"}}})))

(defn- keeping-kind [adapter]
  (r/resource
   (mirror/declaration
    {:kind :ticket :summary "{data.name}" :schema fields}
    {:adapter adapter :ttl-seconds 0 :discover-every 3600})))

(defn- row-of [eng xid]
  (store/with-tx (:storage eng)
    (fn [tx]
      (first (store/query-rows (:storage eng) tx :ticket
                               {:external_id xid} {:limit 1})))))

;; ── resync applies the policy ───────────────────────────────────────

(deftest resync-lands-the-declared-patch
  (let [rm (remote)]
    (db/with-test-engine
      [(dropping-kind rm)]
      (fn [eng]
        (seed! rm "t1" {:name "T1" :phase "open"})
        (seed! rm "t2" {:name "T2" :phase "open"})
        (mirror/discover! eng :ticket)

        (testing "the adapter's :gone sentinel lands the patch"
          (delete! rm "t1")
          (is (= {:checked 2 :rewritten 0 :gone 1 :conflicted 0}
                 (mirror/resync! eng :ticket)))
          (let [t1 (row-of eng "t1")]
            (is (= :fresh (:state t1)))
            (is (= "gone" (get-in t1 [:data :phase])))
            (is (= "T1" (get-in t1 [:data :name]))
                "only the declared patch moves — the record stands"))
          (is (= "open" (get-in (row-of eng "t2") [:data :phase]))
              "the surviving row is untouched"))

        (testing "a second pass is audit-quiet: the patch already
                  holds, no new transition"
          (let [v (:version (row-of eng "t1"))]
            (is (= {:checked 2 :rewritten 0 :gone 1 :conflicted 0}
                   (mirror/resync! eng :ticket)))
            (is (= v (:version (row-of eng "t1"))))))

        (testing "plain absence stays ambiguous — counted, never patched"
          (swap! (:state rm) assoc :ambiguous true)
          (delete! rm "t2")
          (mirror/resync! eng :ticket)
          (is (= "open" (get-in (row-of eng "t2") [:data :phase]))
              "a row a down feed might still carry keeps its truth")
          (swap! (:state rm) assoc :ambiguous false))))))

;; ── pull-through applies the policy ─────────────────────────────────

(deftest refresh-lands-the-declared-patch
  (let [rm (remote)]
    (db/with-test-engine
      [(dropping-kind rm)]
      (fn [eng]
        (seed! rm "t3" {:name "T3" :phase "open"})
        (mirror/discover! eng :ticket)
        (delete! rm "t3")
        (let [rdef (get (inv/resources eng) :ticket)
              row (mirror/refresh! eng rdef
                                   (inv/decode-row rdef (row-of eng "t3")))]
          (is (= :fresh (:state row)))
          (is (= "gone" (get-in row [:data :phase]))
              "a 404 from an answering feed is a deletion observed"))))))

;; ── :keep preserves the old posture exactly ─────────────────────────

(deftest keep-is-the-old-posture
  (let [rm (remote)]
    (db/with-test-engine
      [(keeping-kind rm)]
      (fn [eng]
        (seed! rm "t4" {:name "T4" :phase "open"})
        (mirror/discover! eng :ticket)
        (delete! rm "t4")
        (let [rdef (get (inv/resources eng) :ticket)
              row (mirror/refresh! eng rdef
                                   (inv/decode-row rdef (row-of eng "t4")))]
          (is (= :unreachable (:state row))
              "without a declared policy a 404 stays the old unreachable")
          (is (= "open" (get-in row [:data :phase]))))
        (mirror/resync! eng :ticket)
        (is (= "open" (get-in (row-of eng "t4") [:data :phase]))
            "resync counts the :gone sentinel but patches nothing")))))

;; ── def-site refusals and the authority facet ───────────────────────

(deftest gone-policy-refusals-and-facet
  (testing "malformed policies refuse at the def site"
    (doseq [[bad msg] {{:on-gone :delete} #":on-gone is :keep"
                       {:on-gone {:set {}}} #":on-gone is :keep"
                       {:on-gone {:set {:nope "x"}}} #"no declared field"
                       {:on-gone {:set {:external_id "x"}}} #"sync bookkeeping"
                       {:resync-every 0} #"positive number of seconds"
                       {:resync-every "1h"} #"positive number of seconds"}]
      (is (thrown-with-msg?
           Exception msg
           (r/resource
            (mirror/declaration
             {:kind :bad :summary "x" :schema fields}
             (merge {:adapter (remote) :ttl-seconds 60 :discover-every 60}
                    bad))))
          (pr-str bad))))

  (testing ":on-gone never patches an engine-maintained field"
    (is (thrown-with-msg?
         Exception #"engine-maintained"
         (r/resource
          (mirror/declaration
           {:kind :bad :summary "x"
            :schema [:map
                     [:due_at {:optional true} [:maybe :waymark/instant]]
                     [:late {:optional true
                             :derived {:over [:due_at :now]
                                       :expr '(< (var :due_at) (var :now))}}
                      [:maybe :boolean]]]}
           {:adapter (remote) :ttl-seconds 60 :discover-every 60
            :on-gone {:set {:late true}}})))))

  (testing "the policy is law (authority facet); :keep and the resync
            cadence stay out"
    (let [fp' (fp/fingerprint-of (dropping-kind (remote)))]
      (is (= {"set" {"phase" "gone"}} (get-in fp' ["authority" "on_gone"])))
      (is (nil? (get-in fp' ["authority" "resync_every"]))))
    (is (nil? (get-in (fp/fingerprint-of (keeping-kind (remote)))
                      ["authority" "on_gone"])))))
