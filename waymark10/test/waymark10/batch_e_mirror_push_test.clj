(ns waymark10.batch-e-mirror-push-test
  "Mirror push/write-back (batch E, waymark9 push_mirror + the
  conflicted/reconcile pair at this scope): a :push-on-write mirror's
  own domain action pushes the exported document after commit; a push
  failure lands as the conflicted state with the LOCAL document
  preserved; resolve_conflict — the one human door on the sync
  machine — re-pulls (remote wins) or re-pushes (local wins). Needs
  the batch-E database: WAYMARK10_TEST_DSN=…waymark10_ext_test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r :refer [defhandler]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(def ana (t/principal {:id "ana" :type :human :display "Ana"}))

;; ── the scriptable remote (adapters stay pure/scriptable) ───────────

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
  (push [_ xid document]
    (swap! state update :pushes (fnil inc 0))
    (when (:push-fail @state)
      (throw (ex-info "external document changed under our push" {})))
    (swap! state assoc-in [:docs xid] document)
    (etag-of document)))

(defn- remote [] (->ScriptedRemote (atom {:docs {} :pushes 0})))

;; ── the push-on-write test kind (mealplan's event is pull-only) ─────

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
     :actions
     {:retitle {:from #{:fresh :stale :unreachable} :to :fresh
                :input [:map [:title [:string {:min 1 :max 120}]]]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "The old title is on the audit trail; retitling again covers regret."}
                :handler retitle-handler
                :display {:label "Retitle"}}}}
    {:adapter adapter :ttl-seconds 3600 :discover-every 3600
     :push-on-write true})))

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

(deftest push-happy-conflict-resolve
  (with-push-engine
    (fn [eng rm]
      (swap! (:state rm) assoc-in [:docs "n1"]
             {:title "Remote title" :body "kept"})
      (is (= 1 (mirror/discover! eng :note)))
      (let [id (:id (row-of eng "n1"))]
        (is (some? id))
        (is (zero? (:pushes @(:state rm)))
            "discovery mints and pulls — creates never push")

        (testing "a domain write pushes after commit and lands fresh"
          (let [{:keys [row]} (inv/invoke! eng :note id :retitle
                                           {:title "Ours v1"}
                                           {:principal ana})]
            (is (= :fresh (:state row)))
            (is (= "Ours v1" (get-in row [:data :title])))
            (is (= 1 (:pushes @(:state rm))))
            (is (= "Ours v1" (get-in @(:state rm) [:docs "n1" :title]))
                "the remote carries our write")
            (is (= "kept" (get-in @(:state rm) [:docs "n1" :body]))
                "the exported document carries every declared field")
            (is (= (etag-of (get-in @(:state rm) [:docs "n1"]))
                   (get-in row [:data :external_etag]))
                "the new etag is stamped — the response tells the post-push truth")
            (is (some? (get-in row [:data :synced_at])))))

        (testing "a failed push is the conflicted state, local document preserved"
          (swap! (:state rm) assoc :push-fail true)
          (let [{:keys [row]} (inv/invoke! eng :note id :retitle
                                           {:title "Ours v2"}
                                           {:principal ana})]
            (is (= :conflicted (:state row)))
            (is (= "Ours v2" (get-in row [:data :title]))
                "the local write stands; the state tells the truth about the gap")
            (is (= "external document changed under our push"
                   (get-in row [:data :conflict_reason])))
            (is (= "Ours v1" (get-in @(:state rm) [:docs "n1" :title]))
                "the remote never saw the failed push")))

        (testing "a conflicted row takes no further local writes"
          (let [e (try (inv/invoke! eng :note id :retitle {:title "Nope"}
                                    {:principal ana})
                       nil
                       (catch Exception e e))]
            (is (= 409 (:status (ex-data e))))))

        (testing "resolve_conflict keep=remote re-pulls — remote wins"
          (swap! (:state rm) #(-> % (assoc :push-fail false)
                                  (assoc-in [:docs "n1" :title] "Theirs")))
          (let [{:keys [row]} (inv/invoke! eng :note id :resolve_conflict
                                           {:keep "remote"}
                                           {:principal ana})]
            (is (= :fresh (:state row)))
            (is (= "Theirs" (get-in row [:data :title])))
            (is (nil? (get-in row [:data :conflict_reason])))
            (is (= (etag-of (get-in @(:state rm) [:docs "n1"]))
                   (get-in row [:data :external_etag])))))

        (testing "resolve_conflict keep=local re-pushes — local wins"
          (swap! (:state rm) assoc :push-fail true)
          (inv/invoke! eng :note id :retitle {:title "Ours v3"}
                       {:principal ana})
          (swap! (:state rm) assoc :push-fail false)
          (let [before (:pushes @(:state rm))
                {:keys [row]} (inv/invoke! eng :note id :resolve_conflict
                                           {:keep "local"}
                                           {:principal ana})]
            (is (= :fresh (:state row)))
            (is (= "Ours v3" (get-in row [:data :title])))
            (is (= "Ours v3" (get-in @(:state rm) [:docs "n1" :title]))
                "the re-push landed our truth")
            (is (= (inc before) (:pushes @(:state rm))))
            (is (nil? (get-in row [:data :conflict_reason])))))

        (testing "sync writes never push (no loop)"
          (let [before (:pushes @(:state rm))
                row (row-of eng "n1")
                rdef (get (inv/resources eng) :note)]
            (mirror/refresh! eng rdef (inv/decode-row rdef row))
            (is (= before (:pushes @(:state rm))))))))))

(deftest conflicted-rows-never-pull-through
  (with-push-engine
    (fn [eng rm]
      (swap! (:state rm) assoc-in [:docs "n2"] {:title "Remote" :body nil})
      (mirror/discover! eng :note)
      (let [id (:id (row-of eng "n2"))]
        (swap! (:state rm) assoc :push-fail true)
        (inv/invoke! eng :note id :retitle {:title "Ours"} {:principal ana})
        (let [pulls-sensitive (get-in @(:state rm) [:docs "n2" :title])
              rdef (get (inv/resources eng) :note)
              row (inv/decode-row rdef (row-of eng "n2"))
              same (mirror/refresh! eng rdef row)]
          (is (= "Remote" pulls-sensitive))
          (is (= :conflicted (:state same))
              "leaving conflicted is a person's move, not the clock's")
          (is (= "Ours" (get-in same [:data :title]))))))))

;; ── the declaration's own refusals ──────────────────────────────────

(deftest declaration-refusals
  (let [base {:kind :bad
              :summary "{data.title}"
              :schema [:map [:title {:optional true}
                             [:maybe [:string {:max 120}]]]]}
        act {:from #{:fresh} :to :fresh
             :safety {:idempotent true :reversible true :confirm false}}]
    (testing "a pull-only mirror takes no local writes"
      (is (thrown-with-msg?
           Exception #"pull-only mirror takes no local writes"
           (mirror/declaration (assoc base :actions {:edit act})
                               {:adapter :a}))))
    (testing "a domain action may not shadow a sync door"
      (is (thrown-with-msg?
           Exception #"shadows an engine sync action"
           (mirror/declaration
            (assoc base :actions {:observe_external act})
            {:adapter :a :push-on-write true}))))
    (testing "a local write moves between non-conflicted sync states"
      (is (thrown-with-msg?
           Exception #"machine IS the sync machine"
           (mirror/declaration
            (assoc base :actions {:edit (assoc act :to :conflicted)})
            {:adapter :a :push-on-write true})))
      (is (thrown-with-msg?
           Exception #"machine IS the sync machine"
           (mirror/declaration
            (assoc base :actions {:edit (assoc act :from #{:draft})})
            {:adapter :a :push-on-write true}))))
    (testing "states/initial stay the engine's"
      (is (thrown-with-msg?
           Exception #"sync machine"
           (mirror/declaration (assoc base :states [:a :b])
                               {:adapter :a}))))))
