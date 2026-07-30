(ns waymark10.dashboard-test
  "User-configurable surfaces (waymark-ggw): the dashboard kind and
  its owned dashboard_slot parts compose ONLY declared primitives —
  existing kinds, their own filter grammar, their declared or saved
  views — enforced at write time by the same ctx :rdef-of consult the
  saved_view gate runs. Store-backed acceptance drives the real ring
  handler: a POSTed dashboard's GET splices the ACTIVE slots at
  links.slots.embedded (the render contract the generic client forks
  on), remove/restore and retire/restore round-trip, clone deep-copies
  the active slots through the same create gate, and a redeploy that
  strands a stored slot never breaks the dashboard's own page — the
  slot still rides the embed for its owner to fix; the CLIENT wears
  the collection's refusal per panel (hand-verified, repo convention)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.dashboard :as dash]
            [waymark10.resource :as r]
            [waymark10.saved-view :as sv]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

;; ── the target kind: saved_view_test's ticket, declared views and
;;    all — the primitives a slot may compose ────────────────────────

(def ticket
  (r/resource
   {:kind :ticket
    :states [:pending :approved :flagged]
    :initial :pending
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 100}]]]
    :filterable {:state #{:eq :in}}
    :flow [[:pending :approve :approved {:undo :unapprove
                                         :display {:label "Approve"}}]
           [:approved :unapprove :pending {:undo :approve}]
           [:pending :flag :flagged {:undo :unflag
                                     :display {:label "Flag"}}]
           [:flagged :unflag :pending {:undo :flag}]]
    :views [{:name :triage :kind :deck :where {:state "pending"}
             :right :approve :left :flag
             :card [:title] :display {:label "Triage"}}]}))

(def ticket-v2
  "The redeploy: the declared views are gone — what a stored slot
  deep-linking :triage must survive being served against."
  (r/resource
   {:kind :ticket
    :states [:pending :approved :flagged]
    :initial :pending
    :terminal #{}
    :allow-dead #{:flagged}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 100}]]]
    :filterable {:state #{:eq :in}}
    :flow [[:pending :approve :approved {:undo :unapprove}]
           [:approved :unapprove :pending {:undo :approve}]]}))

;; ── the write-time law, unit ────────────────────────────────────────

(def ^:private rdef-of-stub
  (fn [t] (when (contains? #{"ticket" "tickets"} (str t)) ticket)))

(def ^:private read-stub
  ;; one active saved view "77" targeting ticket, one retired "88",
  ;; one "99" aimed elsewhere
  (fn [k id]
    (when (= k sv/kind)
      (case (str id)
        "77" {:state :active :data {:target "ticket"}}
        "88" {:state :retired :data {:target "ticket"}}
        "99" {:state :active :data {:target "unicorn"}}
        nil))))

(deftest slot-problems-accepts-composed-primitives
  (is (= [] (dash/slot-problems rdef-of-stub read-stub
                                {:target "ticket" :where "state=pending"})))
  (is (= [] (dash/slot-problems rdef-of-stub read-stub
                                {:target "tickets"}))
      "the plural names the target too")
  (is (= [] (dash/slot-problems rdef-of-stub read-stub
                                {:target "ticket" :view "triage"}))
      "a declared view token resolves")
  (is (= [] (dash/slot-problems rdef-of-stub read-stub
                                {:target "ticket" :view "sv-77"}))
      "an active saved view targeting the same kind resolves"))

(deftest slot-problems-names-every-violation
  (testing "an unknown target precedes all others"
    (is (some #(str/includes? % "names no kind this engine serves")
              (dash/slot-problems rdef-of-stub read-stub
                                  {:target "unicorn" :where "state=pending"}))))
  (testing "a where the target's filter grammar does not serve"
    (is (some #(str/includes? % ":where names")
              (dash/slot-problems rdef-of-stub read-stub
                                  {:target "ticket" :where "title=x"}))))
  (testing "a where value that is not a state"
    (is (some #(str/includes? % "is not a state")
              (dash/slot-problems rdef-of-stub read-stub
                                  {:target "ticket" :where "state=bogus"}))))
  (testing "a view token the target does not declare"
    (is (some #(str/includes? % "is not a declared view")
              (dash/slot-problems rdef-of-stub read-stub
                                  {:target "ticket" :view "sideboard"}))))
  (testing "an sv- name with no row behind it"
    (is (some #(str/includes? % "names no saved view")
              (dash/slot-problems rdef-of-stub read-stub
                                  {:target "ticket" :view "sv-1234"}))))
  (testing "an sv- name whose row is retired"
    (is (some #(str/includes? % "not active")
              (dash/slot-problems rdef-of-stub read-stub
                                  {:target "ticket" :view "sv-88"}))))
  (testing "an sv- name aimed at another kind"
    (is (some #(str/includes? % "targets")
              (dash/slot-problems rdef-of-stub read-stub
                                  {:target "ticket" :view "sv-99"})))))

;; ── the store-backed acceptance: the real handler ───────────────────

(def ^:dynamic *h* nil)
(def ^:dynamic *st* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["tickets" "saved_views" "dashboards"
                           "dashboard_slots" "definitions"
                           "waymark10_transitions" "waymark10_idempotency"
                           "waymark10_drafts"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*h* (engine/handler
                       (engine/engine
                        {:storage st
                         :resources (into [ticket sv/saved-view]
                                          dash/resources)}))
                  *st* st]
          (f))
        (finally (pg/close! st))))))

(defn- req
  ([method uri] (req method uri nil))
  ([method uri body] (req method uri body *h*))
  ([method uri body h] (req method uri body h nil))
  ([method uri body h headers]
   ((or h *h*)
    (cond-> {:request-method method
             :uri uri
             :headers (merge {"x-waymark-principal" "colton"} headers)}
      body (assoc :body (wire/write-json body))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))
(defn- embedded-slots [resp] (get-in (json resp) [:links :slots :embedded]))

(deftest the-dashboard-envelope-carries-its-render-contract
  (let [_ (req :post "/api/tickets" {:title "Fix the door"})
        _ (req :post "/api/tickets" {:title "Oil the hinge"})
        made (req :post "/api/dashboards" {:label "Morning desk"
                                           :description "What I check first"})
        did (id-of made)
        _ (is (= 201 (:status made)) (:body made))
        s1 (req :post "/api/dashboard_slots"
                {:dashboard_id did :label "Pending tickets"
                 :target "ticket" :where "state=pending" :seat 1})
        s2 (req :post "/api/dashboard_slots"
                {:dashboard_id did :label "Triage deck"
                 :target "tickets" :where "state=pending"
                 :view "triage" :seat 2})]
    (is (= 201 (:status s1)) (:body s1))
    (is (= 201 (:status s2)) (:body s2))

    (testing "the GET splices the active slots — the client's contract"
      (let [resp (req :get (str "/api/dashboards/" did))
            body (json resp)
            slots (get-in body [:links :slots :embedded])]
        (is (= 200 (:status resp)))
        (is (= "dashboard" (:kind body)) "the dispatch key")
        (is (= 2 (count slots)))
        (is (every? #(= "active" (:state %)) slots))
        (let [f (:fields (first (filter #(= 1 (get-in % [:fields :seat]))
                                        slots)))]
          (is (= "Pending tickets" (:label f)))
          (is (= "ticket" (:target f)))
          (is (= "state=pending" (:where f)))
          (is (not (contains? f :dashboard_id))
              "the owns join key is locked into the link, not repeated"))
        (is (= "triage"
               (get-in (first (filter #(= 2 (get-in % [:fields :seat]))
                                      slots))
                       [:fields :view]))
            "the deep-linked view rides the slot's fields")))

    (testing "a removed slot leaves the embed; restore returns it"
      (let [sid (id-of s1)]
        (is (= 200 (:status (req :post (str "/api/dashboard_slots/" sid
                                            "/-/remove")))))
        (is (= ["Triage deck"]
               (mapv #(get-in % [:fields :label])
                     (embedded-slots (req :get (str "/api/dashboards/" did))))))
        (is (= 200 (:status (req :post (str "/api/dashboard_slots/" sid
                                            "/-/restore")))))
        (is (= 2 (count (embedded-slots
                         (req :get (str "/api/dashboards/" did))))))))

    (testing "revise rewrites the slot's whole surface through the gate"
      (let [sid (id-of s2)
            resp (req :post (str "/api/dashboard_slots/" sid "/-/revise")
                      {:label "Approved lately" :target "ticket"
                       :where "state=approved" :seat 2}
                      *h*
                      {"if-match" (str "W/\"dashboard_slot-" sid "-v1\"")})]
        (is (= 200 (:status resp)) (:body resp))
        (let [f (->> (embedded-slots (req :get (str "/api/dashboards/" did)))
                     (filter #(str/ends-with? (:self %) sid))
                     first :fields)]
          (is (= "state=approved" (:where f)))
          (is (nil? (:view f)) "the omitted optional cleared — wholesale"))))

    (testing "retire takes the dashboard off active; restore returns it"
      (is (= 200 (:status (req :post (str "/api/dashboards/" did
                                          "/-/retire")))))
      (is (= "retired" (:state (json (req :get (str "/api/dashboards/" did))))))
      (is (= 2 (count (embedded-slots (req :get (str "/api/dashboards/" did)))))
          "the slots stay with the shelved dashboard")
      (is (= 200 (:status (req :post (str "/api/dashboards/" did
                                          "/-/restore")))))
      (is (= "active" (:state (json (req :get (str "/api/dashboards/" did)))))))

    (testing "clone deep-copies the active slots through the create gate"
      (let [resp (req :post (str "/api/dashboards/" did "/-/clone")
                      nil *h* {"idempotency-key" "dash-clone-once"})
            _ (is (= 200 (:status resp)) (:body resp))
            col (json (req :get "/api/dashboards"))
            copy (->> (get-in col [:data :items])
                      (filter #(str/includes? (str (:summary %)) "(copy)"))
                      first)
            _ (is (some? copy) "the copy lists beside its original")
            copy-id (last (str/split (:self copy) #"/"))
            slots (embedded-slots (req :get (:self copy)))]
        (is (not= did copy-id))
        (is (= 2 (count slots)) "both active slots copied")
        (is (= #{"Approved lately" "Pending tickets"}
               (into #{} (map #(get-in % [:fields :label])) slots)))
        (is (= ["state=pending" "state=approved"]
               (mapv #(get-in % [:fields :where])
                     (sort-by #(get-in % [:fields :seat]) slots)))
            "the copied slots keep their stored surfaces")
        ;; tidy for the neighbors
        (is (= 200 (:status (req :post (str "/api/dashboards/" copy-id
                                            "/-/retire")))))))))

(deftest the-write-gate-refuses-what-composition-forbids
  (let [did (id-of (req :post "/api/dashboards" {:label "Refusals"}))
        refuse (fn [body]
                 (let [resp (req :post "/api/dashboard_slots" body)
                       p (json resp)]
                   (is (= 409 (:status resp)) (:body resp))
                   (:detail p)))]
    (testing "a target this engine does not serve"
      (is (str/includes?
           (refuse {:dashboard_id did :label "Nowhere" :target "unicorn"})
           "names no kind this engine serves")))
    (testing "a where the target's filter grammar does not serve"
      (is (str/includes?
           (refuse {:dashboard_id did :label "Unfilterable"
                    :target "ticket" :where "title=x"})
           ":where names")))
    (testing "a where value that is not a state"
      (is (str/includes?
           (refuse {:dashboard_id did :label "Ghost state"
                    :target "ticket" :where "state=bogus"})
           "is not a state")))
    (testing "a view token the target does not declare"
      (is (str/includes?
           (refuse {:dashboard_id did :label "Ghost view"
                    :target "ticket" :view "sideboard"})
           "is not a declared view")))
    (testing "a dangling sv- reference"
      (is (str/includes?
           (refuse {:dashboard_id did :label "Dangling"
                    :target "ticket"
                    :view "sv-00000000-0000-0000-0000-000000000000"})
           "names no saved view")))
    (testing "an sv- reference whose saved view is retired"
      (let [made (req :post "/api/saved_views"
                      {:label "Shortlived" :target "ticket"
                       :view_kind "feed" :where "state=pending"})
            svid (id-of made)
            _ (is (= 201 (:status made)) (:body made))
            _ (is (= 200 (:status (req :post (str "/api/saved_views/" svid
                                                  "/-/retire")))))]
        (is (str/includes?
             (refuse {:dashboard_id did :label "Stale ref"
                      :target "ticket" :view (str "sv-" svid)})
             "not active"))))
    (testing "an active sv- reference targeting the same kind passes"
      (let [made (req :post "/api/saved_views"
                      {:label "Pending feed" :target "tickets"
                       :view_kind "feed" :where "state=pending"})
            svid (id-of made)
            _ (is (= 201 (:status made)) (:body made))
            resp (req :post "/api/dashboard_slots"
                      {:dashboard_id did :label "Saved feed"
                       :target "ticket" :view (str "sv-" svid)})]
        (is (= 201 (:status resp)) (:body resp))))
    ;; tidy
    (req :post (str "/api/dashboards/" did "/-/retire"))))

(deftest a-redeploy-strands-the-slot-not-the-page
  ;; authored under ticket v1, where :triage is a declared view
  (let [did (id-of (req :post "/api/dashboards" {:label "Survives"}))
        made (req :post "/api/dashboard_slots"
                  {:dashboard_id did :label "Triage" :target "ticket"
                   :where "state=pending" :view "triage"})
        sid (id-of made)
        _ (is (= 201 (:status made)) (:body made))
        ;; the redeploy: same storage, the declared views gone
        h2 (engine/handler
            (engine/engine {:storage *st*
                            :resources (into [ticket-v2 sv/saved-view]
                                             dash/resources)}))
        resp (req :get (str "/api/dashboards/" did) nil h2)]
    (testing "the dashboard page survives, the stranded slot still riding"
      (is (= 200 (:status resp)))
      (is (some #(str/ends-with? (:self %) sid) (embedded-slots resp))
          "the slot stays embedded — the CLIENT wears the refusal per
          panel; the owner fixes or removes it from the slot's own
          screen"))
    (testing "the slot's own screen still serves under the new law"
      (is (= 200 (:status (req :get (str "/api/dashboard_slots/" sid)
                               nil h2)))))
    ;; tidy for the neighbors
    (req :post (str "/api/dashboard_slots/" sid "/-/remove"))
    (req :post (str "/api/dashboards/" did "/-/retire"))))
