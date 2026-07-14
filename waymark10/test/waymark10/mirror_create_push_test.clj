(ns waymark10.mirror-create-push-test
  "Create push (the un-punt of \"creates never push\" — paydesk's
  assignment worksheet demanded it): a :create-push mirror may be
  born locally through its declared :create-schema; the post-commit
  pass pushes the exported document as a CREATE, the authority mints
  the external id, and claim_external stamps identity + etag. A
  failed create push is the conflicted state on a row with NO
  external id — resolve keep=local retries the create, keep=remote
  refuses (there is no remote truth to keep). Discovery mints (born
  WITH an external id) never create-push. Needs the test database:
  WAYMARK10_TEST_DSN=…waymark10_test."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r :refer [defhandler]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(def ana (t/principal {:id "ana" :type :human :display "Ana"}))

;; ── the scriptable remote, now a mint too ───────────────────────────

(defn- etag-of [doc] (wire/digest doc))

(defrecord MintingRemote [state]
  mirror/MirrorAdapter
  (discover [_] (vec (sort (keys (:docs @state)))))
  (pull [_ xid]
    (swap! state update :pulls (fnil inc 0))
    (if-some [doc (get-in @state [:docs xid])]
      [doc (etag-of doc)]
      (throw (ex-info (str xid " is gone from the remote") {}))))
  (pull-many [_ xids]
    (into {}
          (keep (fn [xid]
                  (when-some [doc (get-in @state [:docs xid])]
                    [xid [doc (etag-of doc)]])))
          xids))
  (push [_ xid document]
    (swap! state update :pushes (fnil inc 0))
    (when (:push-fail @state)
      (throw (ex-info "external document changed under our push" {})))
    (swap! state assoc-in [:docs xid] document)
    (etag-of document))

  mirror/MirrorCreateAdapter
  (push-create [_ document]
    (swap! state update :create-pushes (fnil inc 0))
    (when (:create-fail @state)
      (throw (ex-info "the authority refused the create" {})))
    (let [xid (str "r-" (:minted (swap! state update :minted (fnil inc 0))))]
      (swap! state assoc-in [:docs xid] document)
      [xid (etag-of document)])))

(defn- remote [] (->MintingRemote (atom {:docs {}})))

;; ── the create-push test kind ───────────────────────────────────────

(defhandler retitle-handler [row inp _ctx]
  (assoc-in row [:data :title] (:title inp)))

(defn- note-kind [adapter]
  (r/resource
   (mirror/declaration
    {:kind :note
     :summary "{data.title} · {state}"
     :schema [:map
              [:title {:optional true} [:maybe [:string {:max 120}]]]
              [:body {:optional true} [:maybe [:string {:max 400}]]]]
     :filterable {:state #{:eq}}
     :create-schema [:map [:title [:string {:min 1 :max 120}]]]
     ;; the birth hook fills what the create input leaves unsaid —
     ;; and a discovery mint must skip it (it speaks the
     ;; create-schema's vocabulary; a mint carries none of it)
     :on-create (fn [row _ctx] (assoc-in row [:data :body] "born local"))
     :actions
     {:retitle {:from #{:fresh :stale :unreachable} :to :fresh
                :input [:map [:title [:string {:min 1 :max 120}]]]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "The old title is on the audit trail; retitling again covers regret."}
                :handler retitle-handler
                :display {:label "Retitle"}}}}
    {:adapter adapter :ttl-seconds 3600 :discover-every 3600
     :push-on-write true :create-push true})))

(defn- row-of [eng xid]
  (store/with-tx (:storage eng)
    (fn [tx]
      (first (store/query-rows (:storage eng) tx :note
                               {:external_id xid} {:limit 1})))))

(defn- with-push-engine [f]
  (let [rm (remote)]
    (db/with-test-engine
      [(note-kind rm)]
      (fn [eng] (f (mirror/with-push eng) rm)))))

;; ── the machine ─────────────────────────────────────────────────────

(deftest local-birth-pushes-a-create-and-claims-the-mint
  (with-push-engine
    (fn [eng rm]
      (let [{:keys [row]} (inv/create! eng :note {:title "Local"}
                                       {:principal ana})]
        (is (= :fresh (:state row)))
        (is (= "r-1" (get-in row [:data :external_id]))
            "the authority minted the identity and the claim stamped it")
        (is (= "born local" (get-in row [:data :body]))
            "the birth hook ran for the local create")
        (is (= 1 (:create-pushes @(:state rm))))
        (is (= {:title "Local" :body "born local"}
               (get-in @(:state rm) [:docs "r-1"]))
            "the remote carries the exported document, bookkeeping excluded")
        (is (= (etag-of (get-in @(:state rm) [:docs "r-1"]))
               (get-in row [:data :external_etag]))
            "the mint's etag is stamped — the next pull recognizes its own document")
        (is (some? (get-in row [:data :synced_at])))

        (testing "the claimed row is discovery's old news — no duplicate mint"
          (is (zero? (mirror/discover! eng :note))))

        (testing "a domain write on the claimed row pushes as an UPDATE"
          (let [{:keys [row]} (inv/invoke! eng :note (:id row) :retitle
                                           {:title "Renamed"}
                                           {:principal ana})]
            (is (= :fresh (:state row)))
            (is (= 1 (:pushes @(:state rm))))
            (is (= 1 (:create-pushes @(:state rm)))
                "no second create — the authority already knows this row")
            (is (= "Renamed" (get-in @(:state rm) [:docs "r-1" :title])))))))))

(deftest discovery-mints-never-create-push
  (with-push-engine
    (fn [eng rm]
      (swap! (:state rm) assoc-in [:docs "n1"] {:title "Remote" :body "kept"})
      (is (= 1 (mirror/discover! eng :note)))
      (let [row (row-of eng "n1")]
        (is (some? row))
        (is (nil? (:create-pushes @(:state rm)))
            "a mint records what the authority already has")
        (is (= "kept" (get-in row [:data :body]))
            "the mint pulled the remote document — the birth hook never ran")))))

(deftest failed-create-push-conflicts-and-keep-local-retries
  (with-push-engine
    (fn [eng rm]
      (swap! (:state rm) assoc :create-fail true)
      (let [{:keys [row]} (inv/create! eng :note {:title "Orphan"}
                                       {:principal ana})
            id (:id row)]
        (is (= :conflicted (:state row)))
        (is (nil? (get-in row [:data :external_id]))
            "the authority never minted — the row honestly has no identity")
        (is (= "the authority refused the create"
               (get-in row [:data :conflict_reason])))
        (is (= "Orphan" (get-in row [:data :title]))
            "the local document stands")

        (testing "a conflicted row takes no further local writes"
          (let [e (try (inv/invoke! eng :note id :retitle {:title "Nope"}
                                    {:principal ana})
                       nil
                       (catch Exception e e))]
            (is (= 409 (:status (ex-data e))))))

        (testing "keep=remote refuses — there is no remote truth to keep"
          (is (thrown-with-msg?
               Exception #"no remote document"
               (inv/invoke! eng :note id :resolve_conflict {:keep "remote"}
                            {:principal ana}))))

        (testing "keep=local re-pushes the create and claims the mint"
          (swap! (:state rm) assoc :create-fail false)
          (let [{:keys [row]} (inv/invoke! eng :note id :resolve_conflict
                                           {:keep "local"}
                                           {:principal ana})]
            (is (= :fresh (:state row)))
            (is (= "r-1" (get-in row [:data :external_id])))
            (is (nil? (get-in row [:data :conflict_reason])))
            (is (= "Orphan" (get-in @(:state rm) [:docs "r-1" :title]))
                "the retried create landed our truth")))))))

(deftest unclaimed-rows-are-invisible-to-pulls
  ;; no with-push here: the birth commits without its claim (the
  ;; enrolled-pass-missing posture) — pull-through must not ask the
  ;; adapter about a row nothing external names
  (let [rm (remote)]
    (db/with-test-engine
      [(note-kind rm)]
      (fn [eng]
        (let [{:keys [row]} (inv/create! eng :note {:title "Unclaimed"}
                                         {:principal ana})
              rdef (get (inv/resources eng) :note)]
          (is (nil? (get-in row [:data :external_id])))
          (is (= row (mirror/refresh! eng rdef row))
              "nothing to pull — the row serves as stored")
          (is (nil? (:pulls @(:state rm))))
          (is (= {:checked 0 :rewritten 0 :gone 0 :conflicted 0}
                 (mirror/resync! eng :note))
              "resync has no external id to re-pull by"))))))

;; ── the declaration's own refusals ──────────────────────────────────

(deftest create-push-declaration-refusals
  (let [base {:kind :bad
              :summary "{data.title}"
              :schema [:map [:title {:optional true}
                             [:maybe [:string {:max 120}]]]]}]
    (testing ":create-schema without :create-push"
      (is (thrown-with-msg?
           Exception #"born from discovery alone"
           (mirror/declaration (assoc base :create-schema [:map [:title :string]])
                               {:adapter (remote)}))))
    (testing ":create-push rides :push-on-write"
      (is (thrown-with-msg?
           Exception #"rides :push-on-write"
           (mirror/declaration (assoc base :create-schema [:map [:title :string]])
                               {:adapter (remote) :create-push true}))))
    (testing ":create-push needs a MirrorCreateAdapter"
      (is (thrown-with-msg?
           Exception #"MirrorCreateAdapter"
           (mirror/declaration (assoc base :create-schema [:map [:title :string]])
                               {:adapter (reify mirror/MirrorAdapter
                                           (discover [_] [])
                                           (pull [_ _] nil)
                                           (pull-many [_ _] {})
                                           (push [_ _ _] nil))
                                :push-on-write true :create-push true}))))
    (testing ":create-push declares its :create-schema"
      (is (thrown-with-msg?
           Exception #"declares its :create-schema"
           (mirror/declaration base
                               {:adapter (remote) :push-on-write true
                                :create-push true}))))
    (testing ":create-schema never carries sync bookkeeping"
      (is (thrown-with-msg?
           Exception #"never carries sync bookkeeping"
           (mirror/declaration
            (assoc base :create-schema [:map [:external_id :string]])
            {:adapter (remote) :push-on-write true :create-push true}))))))
