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
            [waymark10.server.measure :as measure]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the target kind: saved_view_test's ticket, declared views and
;;    all — the primitives a slot may compose ────────────────────────

(def ticket
  (r/resource
   {:kind :ticket
    :states [:pending :approved :flagged]
    :initial :pending
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 100}]]
             [:opened_at {:optional true} [:maybe :waymark/instant]]
             [:closed_at {:optional true} [:maybe :waymark/instant]]
             [:points {:optional true} [:maybe :int]]]
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
    :schema [:map [:title [:string {:min 1 :max 100}]]
             [:opened_at {:optional true} [:maybe :waymark/instant]]
             [:closed_at {:optional true} [:maybe :waymark/instant]]
             [:points {:optional true} [:maybe :int]]]
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

(deftest slot-problems-judges-a-measure
  (let [ok {:stat "count" :at "opened_at" :window_seconds 86400 :buckets 24}
        probs (fn [m] (dash/slot-problems rdef-of-stub read-stub
                                          {:target "ticket" :measure m}))
        names? (fn [s m] (some #(str/includes? % s) (probs m)))]
    (is (= [] (probs ok)))
    (is (= [] (probs (assoc ok :stat "sum" :field "points"))))
    (is (= [] (probs (assoc ok :stat "median"
                            :field "duration(opened_at, closed_at)"))))
    (testing "at must be a time field of the target"
      (is (names? "is not a time field" (assoc ok :at "title")))
      (is (names? "is not a time field" (assoc ok :at "nowhere"))))
    (testing "a summed field must be a number field"
      (is (names? "is not a number field"
                  (assoc ok :stat "sum" :field "title"))))
    (testing "a duration reads two time fields"
      (is (names? "not both time fields"
                  (assoc ok :stat "median" :field "duration(opened_at,points)"))))
    (testing "a field that is neither a name nor a duration"
      (is (names? "is neither a field name"
                  (assoc ok :stat "sum" :field "points + 1"))))
    (testing "every stat but count needs a field"
      (is (names? "needs a field" (assoc ok :stat "p90"))))
    (testing "the window and bucket ceilings"
      (is (names? "window_seconds"
                  (assoc ok :window_seconds (inc dash/max-window-seconds))))
      (is (names? "buckets" (assoc ok :buckets (inc dash/max-buckets)))))
    (testing "at may name transition:<action>, which counts and reads nothing"
      (is (= [] (probs (assoc ok :at "transition:flag"))))
      (is (names? "names no action" (assoc ok :at "transition:merge")))
      (is (names? "its stat must be count"
                  (assoc ok :at "transition:flag" :stat "sum" :field "points")))
      (is (names? "reads no field"
                  (assoc ok :at "transition:flag" :field "points"))))))

;; ── the store-backed acceptance: the real handler ───────────────────

(def ^:dynamic *h* nil)
(def ^:dynamic *st* nil)
(def ^:dynamic *eng* nil)

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
        (let [eng (engine/engine
                   {:storage st
                    :resources (into [ticket sv/saved-view]
                                     dash/resources)})]
          (binding [*h* (engine/handler eng)
                    *eng* eng
                    *st* st]
            (f)))
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

(deftest a-measure-reads-a-number-over-its-window
  (let [now (Instant/now)
        ago (fn [secs] (str (.minusSeconds now (long secs))))
        mk (fn [body]
             (let [resp (req :post "/api/tickets" body)]
               (is (= 201 (:status resp)) (:body resp))
               (id-of resp)))
        ;; three pending tickets this day: 1200, 3600 and 5400 seconds
        ;; open; one flagged; one pending the day before. A sits off a
        ;; bucket edge: the route reads the live clock, a moment later
        a (mk {:title "A" :opened_at (ago 3000) :closed_at (ago 1800) :points 3})
        b (mk {:title "B" :opened_at (ago 7200) :closed_at (ago 3600) :points 5})
        _ (mk {:title "C" :opened_at (ago 10800) :closed_at (ago 5400) :points 7})
        d (mk {:title "D" :opened_at (ago 14400) :points 100})
        _ (mk {:title "E" :opened_at (ago (* 30 3600)) :points 11})
        _ (is (= 200 (:status (req :post (str "/api/tickets/" d "/-/flag")))))
        did (id-of (req :post "/api/dashboards" {:label "Numbers"}))
        day {:at "opened_at" :window_seconds 86400 :buckets 24}
        slot (fn [label where m]
               (let [resp (req :post "/api/dashboard_slots"
                               (cond-> {:dashboard_id did :label label
                                        :target "ticket" :measure m}
                                 where (assoc :where where)))]
                 (is (= 201 (:status resp)) (:body resp))
                 (id-of resp)))
        measured (fn [sid]
                   (let [resp (req :get (str "/api/dashboard_slots/" sid
                                             "/-/measure"))]
                     (is (= 200 (:status resp)) (:body resp))
                     (json resp)))]
    (testing "count over the window, and the window before it"
      (let [m (measured (slot "Opened" "state=pending"
                              (assoc day :stat "count")))]
        (is (= 3 (:value m)))
        (is (= 1 (:previous m)))
        (is (= 24 (count (:buckets m))))
        (is (= 3 (reduce + (map :value (:buckets m))))
            "every row of the window lands in one bucket")
        (is (= 1 (:value (peek (:buckets m))))
            "the newest bucket holds the hour-old ticket")))
    (testing "where narrows first"
      (is (= 4 (:value (measured (slot "All opened" nil
                                       (assoc day :stat "count"))))))
      (is (= 3 (:value (measured (slot "Pending opened" "state=pending"
                                       (assoc day :stat "count")))))))
    (testing "sum of a number field"
      (let [m (measured (slot "Points" "state=pending"
                              (assoc day :stat "sum" :field "points")))]
        (is (== 15 (:value m)))
        (is (== 11 (:previous m)))))
    (testing "median of a duration between two time fields"
      (let [m (measured (slot "Time open" "state=pending"
                              (assoc day :stat "median"
                                     :field "duration(opened_at,closed_at)")))]
        (is (== 3600 (:value m)))
        (is (nil? (:previous m)) "the day before has no closed ticket")))
    (testing "a row the grant hides is not counted"
      (let [sid (slot "Seen" "state=pending" (assoc day :stat "count"))
            row (store/with-tx *st*
                  #(store/load-row *st* % :dashboard_slot sid {}))
            vis {:kind? (constantly true)
                 :row? (constantly true)
                 :ids-of (fn [k] (when (= :ticket k) #{a b}))
                 :conds-of (constantly nil)
                 :field? (constantly true)
                 :hashed? (constantly false)}]
        (is (= 2 (:value (measure/report (assoc *eng* :now-fn (constantly now))
                                         row vis))))))
    (testing "a bad field or bound is refused at save"
      (let [refused (fn [m]
                      (let [resp (req :post "/api/dashboard_slots"
                                      {:dashboard_id did :label "Bad"
                                       :target "ticket" :measure m})]
                        (is (= 409 (:status resp)) (:body resp))
                        (:detail (json resp))))]
        (is (str/includes? (refused (assoc day :stat "count" :at "title"))
                           "is not a time field"))
        (is (str/includes? (refused (assoc day :stat "sum" :field "title"))
                           "is not a number field"))
        (is (str/includes? (refused (assoc day :stat "count"
                                           :window_seconds (* 31 86400)))
                           "window_seconds"))
        (is (str/includes? (refused (assoc day :stat "count" :buckets 61))
                           "buckets"))))
    (testing "a list panel has no measure to read"
      (let [resp (req :post "/api/dashboard_slots"
                      {:dashboard_id did :label "List" :target "ticket"})]
        (is (= 404 (:status (req :get (str "/api/dashboard_slots/"
                                           (id-of resp) "/-/measure")))))))
    ;; tidy for the neighbors
    (req :post (str "/api/dashboards/" did "/-/retire"))))

(deftest a-measure-counts-transitions-from-the-log
  (let [mk (fn [title]
             (let [resp (req :post "/api/tickets" {:title title})]
               (is (= 201 (:status resp)) (:body resp))
               (id-of resp)))
        act! (fn [id action]
               (is (= 200 (:status (req :post (str "/api/tickets/" id
                                                   "/-/" action))))))
        did (id-of (req :post "/api/dashboards" {:label "Transitions"}))
        day {:stat "count" :at "transition:flag"
             :window_seconds 86400 :buckets 24}
        slot (fn [label where]
               (let [resp (req :post "/api/dashboard_slots"
                               (cond-> {:dashboard_id did :label label
                                        :target "ticket" :measure day}
                                 where (assoc :where where)))]
                 (is (= 201 (:status resp)) (:body resp))
                 (id-of resp)))
        measured (fn [sid]
                   (let [resp (req :get (str "/api/dashboard_slots/" sid
                                             "/-/measure"))]
                     (is (= 200 (:status resp)) (:body resp))
                     (json resp)))
        ;; the log is the fixture's, shared with the neighbors: the HTTP
        ;; read is judged by what THIS test adds to it, the exact counts
        ;; through a grant that sees only this test's rows
        seen (fn [sid ids]
               (let [row (store/with-tx *st*
                           #(store/load-row *st* % :dashboard_slot sid {}))]
                 (:value (measure/report
                          *eng* row
                          {:kind? (constantly true)
                           :row? (constantly true)
                           :ids-of (fn [k] (when (= :ticket k) (set ids)))
                           :conds-of (constantly nil)
                           :field? (constantly true)
                           :hashed? (constantly false)}))))
        flags (slot "Flags" nil)
        was (measured flags)
        a (mk "TA") b (mk "TB") c (mk "TC") d (mk "TD")
        _ (doseq [t [a b c]] (act! t "flag"))
        _ (act! c "unflag")
        _ (act! d "approve")
        now (measured flags)]
    (testing "flags per hour over a day"
      (is (= 24 (count (:buckets now))))
      (is (= 3 (- (:value now) (:value was))))
      (is (= 3 (- (:value (peek (:buckets now)))
                  (:value (peek (:buckets was)))))
          "this test's flags all land in the newest hour")
      (is (= 3 (seen flags [a b c d])) "unflag and approve are other actions"))
    (testing "a transition on a hidden row is not counted"
      (is (= 2 (seen flags [a b]))))
    (testing "where narrows first"
      (is (= 2 (seen (slot "Flagged flags" "state=flagged") [a b c d])))
      (is (= 1 (seen (slot "Pending flags" "state=pending") [a b c d]))))
    (req :post (str "/api/dashboards/" did "/-/retire"))))
