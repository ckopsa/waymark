(ns choreplan10.assignee-test
  "Who owns a chore is a PERSON: :assignee is a ref to :member (the
  members resource every waymark10 engine carries), not a word that
  happens to spell an id. The obligation is that all three surfaces
  read the member and not the token — the row's own field advertises
  its target, the update door offers the same picker, and the
  collection's filter param does too (waymark-5y3: a uuid on the
  details page, in the dropdown, and in the filter chip was one
  missing declaration, three times).

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [choreplan10.main :as main]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

(def ^:private tables
  ["chores" "chore_runs" "prep_tasks" "members"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (binding [*h* (engine/handler
                       (engine/engine {:storage st
                                       :resources (main/check-resources)}))]
          (f))
        (finally (pg/close! st))))))

(defn- req
  ([method uri] (req method uri nil))
  ([method uri body]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path
                   :headers {"x-waymark-principal" "colton"}}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

;; the dev-header principal is auto-provisioned on first sight, and an
;; auto-provisioned member's id IS the principal id (members.clj) — so
;; "colton" names a real row the moment the first request lands
(deftest an-assignee-is-a-member
  (let [chore (json (req :post "/api/chores"
                         {:name "Dishes" :cadence "daily"
                          :assignee "colton"
                          :notes "run the disposal"}))]
    (testing "the ref dereferences: the assignee is a row, not a word"
      (is (= "colton" (get-in chore [:data :assignee])))
      (is (= "colton · Active" (:summary (json (req :get "/api/members/colton"))))
          "…and the row carries the label every surface renders"))

    (testing "the field advertises its target, so the detail cell and
              the update picker both read the member"
      (let [entry (get-in (json (req :get "/api/schemas/chore"))
                          [:properties :assignee])
            input (get-in chore [:actions :update_details
                                 :input :properties :assignee])]
        (is (= {:kind "member"} (:x-ref entry)))
        (is (= {:kind "member"} (:x-ref input))
            "the update door offers the picker, not a text box")
        (is (= {:label "Assigned to" :showcase true} (:x-display entry))
            "…and 'whose chores?' stands above the table, not inside
             the Filters popover")))

    (testing "and so does the filter param — filtering by assignee is
              picking a person"
      (let [props (get-in (json (req :get "/api/chores"))
                          [:actions :query :input :properties])]
        (is (= {:type "string" :format "waymark-ref" :x-ref {:kind "member"}}
               (:assignee props)))
        (is (= 1 (get-in (json (req :get "/api/chores?assignee=colton"))
                         [:data :total])))
        (is (= 0 (get-in (json (req :get "/api/chores?assignee=nobody"))
                         [:data :total])))))))
