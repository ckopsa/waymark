(ns mealplan10.conformance-test
  "All eleven kinds handed to the waymark10 conformance DRIVER: the
  machine walks itself, and every obligation core owes — plus every
  obligation each enrolled module owes — is proved over the real ring
  handler. Until waymark-db9.5 that was nine deftests written out
  here and re-written in three sibling suites; now the suite is one
  call and the obligations live where their surface does
  (waymark10.test.packs). Two claims stay HERE because they are this
  app's, not the framework's: the bulk report over :meal's own
  accept_many, and the assertion that mealplan10 actually HAS folded
  enums for :core/folded-enums to have proved something about — the
  three sibling suites recorded in a comment that they had none.

  The suite knows Waymark, not meal plans; these registrations are
  the enrollment:

  - state factories for plan and grocery_list (waymark9's
    @state_factory, ported): finalize's require gate needs seven
    mark_eating_out self-loops a shortest-path walk cannot spell.
  - create examples for prep_task (a real plan_id ref) and event
    (external_id identity), an observe_external document example
    (generation would invent non-JSON documents), and an
    assign_off_theme example (its meal_id has no acceptance set to
    guide generation — waymark9 registered the same one).
  - the engine boots with :suppress-mirror-refresh (waymark9's
    _suppress_mirror_refresh): a Mirror breaks the walker's
    reads-are-pure assumption — a GET on a staged stale event would
    heal it to fresh under the assertions. Production reads pull
    through; only this fixture suppresses.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [calendar10.source :as es]
            [mealplan10.main :as main]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.conformance :as conf]
            [waymark10.test.db :as db]
            [waymark10.test.factories :as fac]
            [waymark10.test.suite :as suite]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(def ^:private tables
  ["meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists" "prep_tasks"
   "ingredients" "products" "substitutions" "events"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(declare register-prep-task-example!)

(use-fixtures :once
  (fn [f]
    ;; one JVM runs every suite since the consolidation cleanup
    ;; (waymark-26j), and choreplan10's conformance registers ITS
    ;; prep_task example (the pre-fold mirror's) under the same
    ;; registry key — re-pin the native kind's example before walking
    (register-prep-task-example!)
    (let [st (pg/storage db/dsn)
          feed (es/fake-calendar)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources (main/resources feed)
                                  :suppress-mirror-refresh true})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

(def kinds [:meal :meal_line :rotation :plan :plan_day :grocery_list
            :prep_task :ingredient :product :substitution :event])

;; ── the enrollment ──────────────────────────────────────────────────

(def ^:private plan-start "2026-01-06")   ; a Tuesday, safely past

(defn- mk! [eng kind body]
  (:row (inv/create! eng kind body {:principal (fac/walker-principal)})))

(defn- step!
  ([eng kind id action] (step! eng kind id action nil))
  ([eng kind id action body]
   (inv/invoke! eng kind id action body
                {:principal (fac/walker-principal)
                 :acknowledged #{:calendar-clear}})))

(defn- listed-meal! [eng]
  (let [m (mk! eng :meal {:name "Tacos al pastor" :themes ["mexican"]})]
    (step! eng :meal (:id m) :accept)
    (:id m)))

(fac/example-input! :plan :create
  {:start_date plan-start :weeks 1})

;; its meal_id carries no acceptance set (the off-theme door exists to
;; escape the theme narrowing), so generation cannot guide it —
;; waymark9 registered the identical example, one kind lower now
(fac/example-input! :plan_day :assign_off_theme
  (fn [eng] {:meal_id (listed-meal! eng)}))

;; no :create example: a plan births its WHOLE week (the unique
;; (plan_id, date) index holds every in-range slot), so the walker
;; stages days through the factory below — the same posture as plan
;; and grocery_list, whose creates also happen inside their factories.
(fac/state-factory! :plan_day
  (fn [eng target]
    (let [rot (mk! eng :rotation {})
          _ (step! eng :rotation (:id rot) :activate)
          _ (listed-meal! eng)
          plan (mk! eng :plan {:start_date plan-start :weeks 1
                               :rotation_id (:id rot)})
          [d1 d2] (store/with-tx (:storage eng)
                    (fn [tx] (store/query-rows (:storage eng) tx :plan_day
                                               {:plan_id (:id plan)}
                                               {:limit 3})))]
      (case target
        :undecided (inv/decode-row (get (inv/resources eng) :plan_day) d1)
        ;; assign is a fenced edit now — the factory speaks the etag
        :planned (:row (inv/invoke! eng :plan_day (:id d1) :assign_off_theme
                                    {:meal_id (listed-meal! eng)}
                                    {:principal (fac/walker-principal)
                                     :if-match (inv/etag :plan_day (:id d1)
                                                         (:version d1))}))
        :eating_out (:row (step! eng :plan_day (:id d2) :mark_eating_out
                                 {:where "out"}))))))

(fac/state-factory! :plan
  (fn [eng target]
    (let [rot (mk! eng :rotation {})
          _ (step! eng :rotation (:id rot) :activate)
          _ (listed-meal! eng)
          plan (mk! eng :plan {:start_date plan-start :weeks 1
                               :rotation_id (:id rot)})
          pid (:id plan)]
      (case target
        :draft plan
        :abandoned (:row (step! eng :plan pid :abandon))
        (do (doseq [d (store/with-tx (:storage eng)
                        (fn [tx] (store/query-rows (:storage eng) tx
                                                   :plan_day {:plan_id pid}
                                                   {:limit 20})))]
              (step! eng :plan_day (:id d) :mark_eating_out nil))
            (let [res (step! eng :plan pid :finalize)]
              (case target
                :planned (:row res)
                :active (:row (step! eng :plan pid :begin))
                :done (do (step! eng :plan pid :begin)
                          (:row (step! eng :plan pid :complete))))))))))

(fac/state-factory! :grocery_list
  (fn [eng target]
    (let [plan ((get @fac/state-factories :plan) eng :planned)
          g (mk! eng :grocery_list {:plan_id (:id plan)})
          gid (:id g)
          items [{:name "tortillas"} {:name "pork shoulder 1400g"}]]
      (doseq [item items]
        (step! eng :grocery_list gid :add_item item))
      (case target
        :draft (:row (step! eng :grocery_list gid :add_item {:name "limes"}))
        :ready (:row (step! eng :grocery_list gid :finalize))
        :done (do (step! eng :grocery_list gid :finalize)
                  (step! eng :grocery_list gid :check_item {:name "tortillas"})
                  (step! eng :grocery_list gid :check_item
                         {:name "pork shoulder 1400g"})
                  (:row (step! eng :grocery_list gid :complete)))
        ;; the mistake door (waymark-3by): terminal beside :done
        :discarded (:row (step! eng :grocery_list gid :discard))))))

;; a defn (called at load AND from the fixture) because choreplan10's
;; conformance contests the [:prep_task :create] registry key with the
;; pre-fold mirror's example — last registration wins, so each suite
;; re-pins its own before it walks
(defn- register-prep-task-example! []
  (fac/example-input! :prep_task :create
    (fn [eng]
      (let [plan ((get @fac/state-factories :plan) eng :planned)]
        {:plan_id (:id plan) :date plan-start :meal_name "Tacos al pastor"
         :task_type "prep" :due_at "2026-01-06T12:00:00Z"}))))

(register-prep-task-example!)

(fac/example-input! :event :create
  ;; the calendar's birth door changed with waymark-6k5.2: an event is
  ;; no longer minted by discovery alone, it is SCHEDULED — a
  ;; create-push through the declared :create-schema, whose guards want
  ;; a title and a when. The old example handed the engine an
  ;; :external_id, which the create door now refuses outright
  ;; (bookkeeping is claim_external's to stamp, never the author's).
  (fn [_] {:title "Conformance walk" :all_day true :date "2026-01-06"}))

;; ── the pantry quartet: refs (and the distinct create gate) defeat
;;    generation, so every create is an example; absorb and rematch
;;    mint their own peers ────────────────────────────────────────────

(defn- active-ingredient! [eng nm]
  (let [i (mk! eng :ingredient {:name nm})]
    (step! eng :ingredient (:id i) :accept)
    (:id i)))

(fac/example-input! :ingredient :create
  (fn [_] {:name (str "Chicken thighs " (random-uuid))}))

(fac/example-input! :ingredient :absorb
  (fn [eng] {:duplicate_id (active-ingredient!
                            eng (str "Chicken thigh " (random-uuid)))}))

(fac/example-input! :product :create
  (fn [eng]
    {:ingredient_id (active-ingredient! eng (str "Thighs " (random-uuid)))
     :store "costco"
     :name "Kirkland Organic Chicken Thighs"
     :package_grams 2720
     :sightings [{:seen_on "2026-01-02" :price_cents 1899
                  :source "receipt"}]}))

(fac/example-input! :product :rematch
  (fn [eng] {:ingredient_id (active-ingredient!
                             eng (str "Rematched " (random-uuid)))}))

(fac/example-input! :meal_line :create
  (fn [eng]
    {:meal_id (listed-meal! eng)
     :ingredient_id (active-ingredient! eng (str "Line ing " (random-uuid)))
     :grams 1400}))

(fac/example-input! :substitution :create
  (fn [eng]
    {:from_ingredient_id (active-ingredient!
                          eng (str "Butter " (random-uuid)))
     :to_ingredient_id (active-ingredient!
                        eng (str "Margarine " (random-uuid)))}))

(fac/example-input! :event :observe_external
  {:document {:title "Piano recital" :date "2026-01-08" :kind "note"}
   :etag "conformance-etag-1"})

;; ── the whole suite ─────────────────────────────────────────────────

(deftest conformance
  (let [report (suite/check! {:engine *eng* :handler *h* :kinds kinds})]
    ;; the coverage claim the driver cannot make for an application:
    ;; :core/folded-enums checks every advertised enum member and
    ;; honestly checks NOTHING in an app with no acceptance-set guard
    ;; (choreplan10 and eveningplan10 said exactly that, in a comment).
    ;; mealplan10 has them, so zero here is a hole, not a shrug.
    (is (pos? (suite/coverage report :core/folded-enums))
        "at least one folded enum member was dry-run over the wire")
    ;; the same claim for the decision record: :plan is the one kind in
    ;; this house that declares :retain {:judgment true}, and the walk
    ;; drives it — so zero records read here means the obligation was
    ;; green because it had nothing to look at, which is the failure
    ;; mode a retention feature is most likely to hide behind
    (is (pos? (suite/coverage report :core/decision-record))
        "at least one committed transition's decision record was read")))

;; ── the bulk report: this app's own fan-out door ────────────────────

(deftest bulk-report-shape
  (let [m1 (mk! *eng* :meal {:name "Bulk one" :themes ["asian"]})
        m2 (mk! *eng* :meal {:name "Bulk two" :themes ["asian"]})
        resp (*h* {:request-method :post
                   :uri "/api/meals/-/accept_many"
                   :headers suite/walker-headers
                   :body (wire/write-json {:ids [(:id m1) (:id m2)]})})
        report (wire/read-json (:body resp))
        violations (conf/bulk-report-violations
                    report {:action :accept_many :items 2})]
    (is (= 200 (:status resp)))
    (is (empty? violations) (str/join "\n" violations))
    (is (= 2 (get-in report [:data :succeeded])))))
