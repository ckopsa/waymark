(ns mealplan10.price-desk-test
  "The price desk (waymark-34n), end to end over the real ring
  handler: the anchorless surface composing the two price queues with
  the fix actions at hand. The scraper run settled who the queue
  really serves — 39 of 40 retailer pages render their price in JS —
  so the consumer is a human with a receipt, and this suite proves
  the desk tells that human the truth:

  - a tracked product whose 30-day clock ran out sits in :stale, its
    record_sighting and update_details advertised on the item;
  - a freshly priced one does not;
  - a suggested product never does — staleness without a confirmed
    match is not this desk's work;
  - a priced-but-weightless product sits in :weightless;
  - each panel's count is truthful, showcase is the declaration's,
    and there is no anchor to wear — /api/surfaces/price-desk/{id}
    404s while the bare door serves.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [calendar10.source :as es]
            [mealplan10.main :as main]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          feed (es/fake-calendar)
          clock (atom (Instant/parse "2026-07-08T12:00:00Z"))]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources (main/resources feed)
                                  :surfaces main/surfaces
                                  :now-fn (fn [] @clock)})]
          (binding [*h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar (the family-week pattern: ETag along) ─────────────

(def ^:private colton {"x-waymark-principal" "colton"})

(defn- req
  ([method uri] (req method uri nil {}))
  ([method uri body] (req method uri body {}))
  ([method uri body headers]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path
                   :headers (merge colton headers)}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- created! [plural body]
  (let [resp (req :post (str "/api/" plural) body)]
    (is (= 201 (:status resp)) (str plural " create: " (:body resp)))
    (json resp)))

(defn- etag-of [self]
  (get-in (req :get self) [:headers "ETag"]))

(defn- act!
  ([self action] (act! self action nil))
  ([self action body]
   (let [resp (req :post (str self "/-/" (name action)) body
                   {"if-match" (etag-of self)})]
     (is (= 200 (:status resp))
         (str self " " (name action) ": " (:status resp) " " (:body resp)))
     (json resp))))

(defn- id-of [env] (last (str/split (:self env) #"/")))

(defn- active-ingredient! [nm]
  (let [i (created! "ingredients" {:name nm})]
    (act! (:self i) :accept)))

(defn- product!
  "One product, born with at most one sighting; tracked? confirms the
  match (the desk only serves tracked rows)."
  [ing nm {:keys [grams seen-on tracked?] :or {tracked? true}}]
  (let [p (created! "products"
                    (cond-> {:ingredient_id (id-of ing) :store "winco"
                             :name nm}
                      grams (assoc :package_grams grams)
                      seen-on (assoc :sightings
                                     [{:seen_on seen-on :price_cents 499
                                       :source "receipt"}])))]
    (if tracked? (act! (:self p) :confirm_match) p)))

(defn- summaries [items] (set (map :summary items)))

;; ── the desk ────────────────────────────────────────────────────────
;; The clock says 2026-07-08. A 2026-01-02 sighting ran out its 30
;; days long ago; a 2026-07-01 one flips 2026-07-31 — still fresh.

(deftest the-price-desk-serves-the-human-with-the-receipt
  (let [ing (active-ingredient! "Flour (pd)")
        stale (product! ing "Stale flour (pd)"
                        {:grams 1000 :seen-on "2026-01-02"})
        _fresh (product! ing "Fresh cilantro (pd)"
                         {:grams 100 :seen-on "2026-07-01"})
        weightless (product! ing "Weightless cream (pd)"
                             {:seen-on "2026-07-01"})
        _suggested (product! ing "Unvetted salsa (pd)"
                             {:grams 250 :seen-on "2026-01-02"
                              :tracked? false})
        resp (req :get "/api/surfaces/price-desk")
        b (json resp)
        stale-items (get-in b [:members :stale :items])
        weightless-items (get-in b [:members :weightless :items])]
    (is (= 200 (:status resp)) (str (:body resp)))
    (is (= "surface" (:kind b)))
    (is (= "/api/surfaces/price-desk" (:self b)))
    (is (= "price-desk" (:name b)))
    (is (not (contains? b :anchor)) "the queue wears nobody's row")
    (is (= {} (:attention b)) "the counts themselves are the flag")

    (testing "the showcase is the declaration's: receipt in one hand,
              fix doors in the other"
      (is (= ["record_sighting" "update_details"] (:showcase b))))

    (testing "the stale member: the run-out clock in, the fresh row
              and the unconfirmed match out"
      (is (contains? (summaries stale-items)
                     "Stale flour (pd) · winco · Tracked"))
      (is (not-any? #(str/starts-with? % "Fresh cilantro")
                    (summaries stale-items)))
      (is (not-any? #(str/starts-with? % "Unvetted salsa")
                    (summaries stale-items))
          "suggested is the match queue, not the price queue"))

    (testing "the weightless member: priced but no package_grams"
      (is (contains? (summaries weightless-items)
                     "Weightless cream (pd) · winco · Tracked"))
      (is (not-any? #(str/starts-with? % "Stale flour")
                    (summaries weightless-items))))

    (testing "the counts are truthful"
      (is (= 1 (get-in b [:members :stale :count])))
      (is (= 1 (count stale-items)))
      (is (= 1 (get-in b [:members :weightless :count])))
      (is (= 1 (count weightless-items))))

    (testing "each item advertises the fix actions it showcases"
      (let [it (first stale-items)]
        (is (contains? (:actions it) :record_sighting))
        (is (contains? (:actions it) :update_details))
        (is (not (contains? it :data)) "items stay envelope-minus-data")))

    (testing "recording the price is the queue's exit: a fresh
              sighting empties the stale panel"
      (act! (:self stale) :record_sighting
            {:seen_on "2026-07-08" :price_cents 549 :source "receipt"})
      (let [after (json (req :get "/api/surfaces/price-desk"))]
        (is (= 0 (get-in after [:members :stale :count])))
        (is (= [] (get-in after [:members :stale :items])))))

    (testing "recording the weight is the other exit"
      (act! (:self weightless) :update_details {:package_grams 500})
      (let [after (json (req :get "/api/surfaces/price-desk"))]
        (is (= 0 (get-in after [:members :weightless :count])))))

    (testing "the doors: well-known lists the bare href beside the
              anchored week board, and the desk takes no anchor"
      (let [w (json (req :get "/api/.well-known/waymark"))]
        (is (= "/api/surfaces/price-desk"
               (get-in w [:surfaces :price-desk :href])))
        (is (= "/api/surfaces/week-board/{anchor-id}"
               (get-in w [:surfaces :week-board :href]))))
      (is (= 404 (:status (req :get "/api/surfaces/price-desk/some-id")))))))
