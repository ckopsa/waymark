(ns waymark10.mirror-external-refs-test
  "External-keyed refs on mirror kinds: a :waymark/ref entry declaring
  {:kind … :external-key <field>} resolves at every sync write — the
  document's external id becomes the target mirror row's id — and
  discovery heals edges that observed before their target existed
  (resolve-refs!, a maintenance write). Driven by paydesk's assignment
  kind (employee/fund refs over warehouse ids). Needs the test
  database: WAYMARK10_TEST_DSN overrides."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the scriptable remotes (batch-E's ScriptedRemote shape) ─────────

(defn- etag-of [doc] (wire/digest doc))

(defrecord ScriptedRemote [state]
  mirror/MirrorAdapter
  (discover [_] (vec (sort (keys (:docs @state)))))
  (pull [_ xid]
    (if-some [doc (get-in @state [:docs xid])]
      [doc (etag-of doc)]
      (throw (ex-info (str xid " is gone from the remote") {}))))
  (pull-many [_ xids]
    (when (:down @state) (throw (ex-info "remote unreachable" {})))
    (into {}
          (keep (fn [xid]
                  (when-some [doc (get-in @state [:docs xid])]
                    [xid [doc (etag-of doc)]])))
          xids))
  (push [_ _ _] (throw (ex-info "pull-only" {}))))

(defn- remote [] (->ScriptedRemote (atom {:docs {}})))
(defn- seed! [rm xid doc] (swap! (:state rm) assoc-in [:docs xid] doc))

;; ── the kinds: author (target) and book (edge with the keyed ref) ───

(defn- author-kind [adapter]
  (r/resource
   (mirror/declaration
    {:kind :author
     :summary "{data.name}"
     :label-template "{data.name}"
     :schema [:map
              [:name {:optional true} [:maybe [:string {:max 120}]]]
              ;; the self-referential shape (an employee's manager):
              ;; the target kind is this kind
              [:mentor_external_id {:optional true} [:maybe [:string {:max 64}]]]
              [:mentor_id {:optional true :kind :author
                           :external-key :mentor_external_id}
               [:maybe :waymark/ref]]]}
    {:adapter adapter :ttl-seconds 3600 :discover-every 3600})))

(defn- book-kind [adapter]
  (r/resource
   (mirror/declaration
    {:kind :book
     :summary "{data.title}"
     :schema [:map
              [:title {:optional true} [:maybe [:string {:max 120}]]]
              [:author_external_id {:optional true} [:maybe [:string {:max 64}]]]
              [:author_id {:optional true :kind :author
                           :external-key :author_external_id}
               [:maybe :waymark/ref]]
              ;; the :many shape (a team's member/fund arrays)
              [:coauthor_external_ids {:optional true}
               [:maybe [:vector [:string {:max 64}]]]]
              [:coauthor_ids {:optional true :kind :author
                              :external-key :coauthor_external_ids}
               [:maybe [:vector :waymark/ref]]]]}
    {:adapter adapter :ttl-seconds 3600 :discover-every 3600})))

(defn- row-of [eng kind xid]
  (store/with-tx (:storage eng)
    (fn [tx]
      (first (store/query-rows (:storage eng) tx kind
                               {:external_id xid} {:limit 1})))))

;; ── the machine ─────────────────────────────────────────────────────

(deftest observe-resolves-and-discovery-heals
  (let [authors (remote) books (remote)]
    (db/with-test-engine
      [(author-kind authors) (book-kind books)]
      (fn [eng]
        (testing "target first: the edge's observe resolves the ref"
          (seed! authors "a1" {:name "Ursula"})
          (seed! books "b1" {:title "Left Hand" :author_external_id "a1"})
          (mirror/discover! eng :author)
          (mirror/discover! eng :book)
          (let [author-row (row-of eng :author "a1")
                book-row (row-of eng :book "b1")]
            (is (= (str (:id author-row))
                   (get-in book-row [:data :author_id]))
                "the ref holds the target's ROW id, not the external id")))

        (testing "a SELF-referential ref resolves within one discovery
                  pass (mints land before the observes run)"
          (seed! authors "a9" {:name "Mentor"})
          (seed! authors "a10" {:name "Mentee" :mentor_external_id "a9"})
          (mirror/discover! eng :author)
          (is (= (str (:id (row-of eng :author "a9")))
                 (get-in (row-of eng :author "a10") [:data :mentor_id]))))

        (testing "edge first: nil ref, then the target's discovery heals it"
          (seed! books "b2" {:title "Dispossessed" :author_external_id "a2"})
          (mirror/discover! eng :book)
          (let [before (row-of eng :book "b2")]
            (is (nil? (get-in before [:data :author_id]))
                "no target row yet — the honest gap")
            (seed! authors "a2" {:name "Ursula Too"})
            (mirror/discover! eng :author)
            (let [a2 (row-of eng :author "a2")
                  b2 (row-of eng :book "b2")]
              (is (= (str (:id a2)) (get-in b2 [:data :author_id]))
                  "the target's arrival healed the edge (resolve-refs!)")
              (is (= (:version before) (:version b2))
                  "the heal is a maintenance write — version untouched, no transition"))))

        (testing "an unset external id resolves to an unset ref"
          (seed! books "b3" {:title "No Author"})
          (mirror/discover! eng :book)
          (is (nil? (get-in (row-of eng :book "b3") [:data :author_id]))))

        (testing "a :many ref resolves to the resolvable projection"
          (seed! books "b4" {:title "Anthology"
                             :coauthor_external_ids ["a1" "missing" "a2"]})
          (mirror/discover! eng :book)
          (let [a1 (str (:id (row-of eng :author "a1")))
                a2 (str (:id (row-of eng :author "a2")))
                b4 (row-of eng :book "b4")]
            (is (= [a1 a2] (get-in b4 [:data :coauthor_ids]))
                "resolvable ids in array order; the unresolved one is
                 the external array's to tell")))

        (testing "a :many ref GROWS when its missing target arrives"
          (let [before (row-of eng :book "b4")]
            (seed! authors "missing" {:name "Found Author"})
            (mirror/discover! eng :author)
            (let [b4 (row-of eng :book "b4")
                  found (str (:id (row-of eng :author "missing")))]
              (is (= 3 (count (get-in b4 [:data :coauthor_ids]))))
              (is (some #{found} (get-in b4 [:data :coauthor_ids])))
              (is (= (:version before) (:version b4))
                  "still a maintenance write — no transition"))))

        (testing "a later heal pass never clobbers an already-set ref"
          ;; regression: the backfill once wrote `false` over resolved
          ;; refs when a second target discovery re-ran the pass
          (let [b2-before (row-of eng :book "b2")]
            (seed! authors "a3" {:name "Third Author"})
            (mirror/discover! eng :author)
            (is (= (get-in b2-before [:data :author_id])
                   (get-in (row-of eng :book "b2") [:data :author_id]))
                "b2's resolved ref survives the a3-triggered heal")))))))

(deftest resync-heals-the-whole-kind
  (let [authors (remote) books (remote)]
    (db/with-test-engine
      [(author-kind authors) (book-kind books)]
      (fn [eng]
        (seed! authors "r1" {:name "First"})
        (mirror/discover! eng :author)

        (testing "an unchanged world resyncs to zero rewrites"
          (is (= {:checked 1 :rewritten 0 :gone 0}
                 (mirror/resync! eng :author))))

        (testing "a changed document rewrites; unchanged rows untouched"
          (seed! authors "r2" {:name "Second"})
          (mirror/discover! eng :author)
          (seed! authors "r1" {:name "First, renamed"})
          (is (= {:checked 2 :rewritten 1 :gone 0}
                 (mirror/resync! eng :author)))
          (is (= "First, renamed"
                 (get-in (row-of eng :author "r1") [:data :name]))))

        (testing "a conflicted row is a person's decision, not resync's"
          (inv/invoke! eng :author (:id (row-of eng :author "r1"))
                       :mark_conflicted {:reason "test conflict"}
                       {:principal mirror/system-observer})
          (seed! authors "r1" {:name "Remote moved on"})
          (mirror/resync! eng :author)
          (let [r1 (row-of eng :author "r1")]
            (is (= :conflicted (:state r1)))
            (is (= "First, renamed" (get-in r1 [:data :name]))
                "the local document stands until resolve_conflict")))

        (testing "a gone-from-feed id keeps serving its stored truth"
          (swap! (:state authors) update :docs dissoc "r2")
          (let [res (mirror/resync! eng :author)]
            (is (= 1 (:gone res)))
            (is (some? (row-of eng :author "r2")))))

        (testing "an unreachable adapter warns and returns nil"
          (swap! (:state authors) assoc :down true)
          (is (nil? (mirror/resync! eng :author))))))))

(deftest def-site-refusals
  (testing ":external-key naming no declared field refuses"
    (is (thrown-with-msg?
         Exception #"names no declared field"
         (r/resource
          (mirror/declaration
           {:kind :bad :summary "x"
            :schema [:map
                     [:thing_id {:optional true :kind :author
                                 :external-key :nope}
                      [:maybe :waymark/ref]]]}
           {:adapter (remote) :ttl-seconds 60 :discover-every 60})))))
  (testing ":external-key off a non-ref entry refuses"
    (is (thrown-with-msg?
         Exception #"rides a :waymark/ref entry"
         (r/resource
          (mirror/declaration
           {:kind :bad :summary "x"
            :schema [:map
                     [:plain {:optional true :kind :author
                              :external-key :plain}
                      [:maybe [:string {:max 10}]]]]}
           {:adapter (remote) :ttl-seconds 60 :discover-every 60})))))
  (testing ":external-key without :kind refuses"
    (is (thrown-with-msg?
         Exception #"needs :kind"
         (r/resource
          (mirror/declaration
           {:kind :bad :summary "x"
            :schema [:map
                     [:other {:optional true} [:maybe [:string {:max 10}]]]
                     [:thing_id {:optional true :external-key :other}
                      [:maybe :waymark/ref]]]}
           {:adapter (remote) :ttl-seconds 60 :discover-every 60}))))))
