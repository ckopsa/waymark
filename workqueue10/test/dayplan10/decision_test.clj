(ns dayplan10.decision-test
  "The decision lived in over the real engine (waymark-i89n.4): what
  the declaration-time world cannot judge — a decision taking its day
  and member from its block, a subject resolving against real rows,
  Go firing the Home Assistant caller exactly once and recording
  started (and recording nothing when the room does not answer),
  change keeping both sentences, the block's skip letting its
  decisions go, a decision with prep reaching the queue as ONE
  task with source day_plan through the dayplan TaskSource — the thaw
  task's road, drunk in-process — and a PASSAGE launch (waymark-35eb)
  judged against the media row it names: the grammar the words must
  read in, the medium that decides which grammar, Go firing nothing,
  Done logging no progress on the film, and the link the feed projects
  off the row (pure — the fake flickr's rows carry a deep link).

  The scenarios on decision.clj already prove the verdicts (an
  unresolvable subject refused naming the address, a launch that does
  not say how, Go refused with no Home Assistant wired) through the
  HTTP door in the conformance suite and at declaration time; this
  file proves the EFFECTS.

  The engine is the WHOLE household registry (workqueue10.main), not
  dayplan10's alone, because the prep mirror needs the task kind and
  the confluence — and the day_plan source drinks this same engine
  through engine-ref, the way production wires it. Home Assistant is a
  recording fn on :services, beside the feature token the guard reads.

  The clock is the engine's :now-fn, pinned to a morning on the plan's
  own day; every instant is spelled through dayplan10.zone so the
  assertions hold in whatever zone the environment names.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides.
  Run: cd workqueue10 && clojure -M:test --focus dayplan10.decision-test"
  (:require [calendar10.source :as gcal]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dayplan10.passage :as passage]
            [dayplan10.resources.decision :as dec]
            [dayplan10.zone :as zone]
            [next.jdbc :as jdbc]
            [waymark10.dev :as dev]
            [waymark10.schema :as schema]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [workqueue10.confluence :as conf]
            [workqueue10.main :as main]
            [workqueue10.sources.dayplan :as dayplan]
            [workqueue10.sources.flickr :as flickr]
            [workqueue10.sources.hub :as hub])
  (:import (java.time LocalDate LocalTime)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ;; the WHOLE folded registry's tables (task_queue_test's rule): this
  ;; engine boots every kind main/resources declares, so a fixture
  ;; that drops less boots into another suite's residue
  ["tasks" "task_lists" "media" "chores" "chore_runs" "days"
   "meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "letters" "ticklers" "insights" "weathers" "permission_slips"
   "selves" "journals" "dwellings" "connections" "capabilities"
   "saved_views" "dashboards" "dashboard_slots"
   "feed_recipes" "recipe_proposals" "feed_views" "feed_view_consents"
   "composition_requests" "outcome_pieces" "outcomes" "values" "people"
   "hypotheses"
   "activities" "evening_plans" "evening_sessions"
   "contexts" "day_plans" "blocks" "spans" "decisions"
   "members" "roles" "grants" "approval_requests"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(def ^:dynamic *eng* nil)

(def ^:private today (LocalDate/parse "2026-01-06"))       ; a Tuesday
(def ^:private yesterday (LocalDate/parse "2026-01-05"))

(defn- at
  "A clock time on a date, in the household zone."
  ([hh mm] (at today hh mm))
  ([date hh mm] (zone/at date (LocalTime/of (int hh) (int mm)))))

(def ^:private clock
  "The engine's clock — eight on the plan's morning, before any window."
  (atom (at 8 0)))

(def ^:private ui-base "https://rod.kopsa.info")

(def ^:private ha-calls
  "Every service Home Assistant was asked to fire: [service data]."
  (atom []))

(def ^:private fake-flickr
  "The shelf, as the media confluence drinks it: canonical docs with
  the deep link flickr stamps, so a passage has a row to be a place in."
  (conf/fake-source))

(defn- fire-home-assistant!
  "The recording twin of sources.homeassistant/call-service!: one
  service answers as the room would when it is down, the rest record."
  [service data]
  (when (= "switch/unreachable" service)
    (throw (ex-info "home assistant answered 502 for switch/unreachable"
                    {:status 502})))
  (swap! ha-calls conj [service data])
  nil)

(def ^:private colton
  (t/principal {:id "colton" :type :human :display "Colton"}))

(defn- create! [kind body]
  (:row (inv/create! *eng* kind body {:principal colton})))

(defn- act! [kind id action body]
  (inv/invoke! *eng* kind id action body {:principal colton}))

(defn- refusal
  "What a refused write says: {:problem … :guard … :detail …}, nil
  when it went through."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           {:problem (:waymark10/problem d)
            :guard (some-> (:guard d) name)
            :detail (:detail d)
            :message (ex-message e)}))))

(defn- row [kind id] (dev/row *eng* kind id))

(def ^:private member-seq (atom 0))
(defn- fresh-member [] (str "member-" (swap! member-seq inc)))

(defn- plan! [date]
  (create! :day_plan {:date (str date) :member (fresh-member)}))

(defn- block-named [plan-id nm]
  (->> (dev/rows *eng* :block)
       (some #(when (and (= plan-id (get-in % [:data :plan_id]))
                         (= nm (get-in % [:data :context_name])))
                %))))

(defn- workday-block!
  "A plan for today and its Workday block."
  []
  (let [plan (plan! today)]
    [plan (block-named (:id plan) "Workday")]))

(defn- decide!
  "A decision into a block, the ordinary way."
  [block body]
  (create! :decision (merge {:block_id (:id block) :kind "work" :order 1} body)))

(defn- prep-tasks []
  (->> (dev/rows *eng* :task)
       (filter #(= "day_plan" (get-in % [:data :source])))))

(defn- task-for [decision-id]
  (some #(when (= (str "day_plan:" decision-id) (get-in % [:data :external_id])) %)
        (prep-tasks)))

(defn- media-row
  "The media row the flickr fake's work landed as."
  [work-key]
  (some #(when (= (conf/xid "flickr" work-key) (get-in % [:data :external_id])) %)
        (dev/rows *eng* :media)))

(def ^:private stream "https://stream.kopsa.info")

;; the household's engine, whole: the day plan's source drinks it
;; through engine-ref, and Home Assistant is the recording fn beside
;; the feature token
(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          engine-ref (atom nil)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (mirror/with-push
                   (engine/engine
                    {:storage st
                     :resources (main/resources
                                 {"chore" (conf/fake-source)
                                  "meal" (conf/fake-source)
                                  "todo" (conf/fake-source)
                                  "gtasks" (conf/fake-source)
                                  "day_plan" (dayplan/engine-source
                                              {:engine-ref engine-ref
                                               :ui-base ui-base
                                               :principal "workqueue10"})}
                                 {"flickr" fake-flickr "hub" (hub/source)}
                                 (gcal/fake-calendar))
                     :now-fn (fn [] @clock)
                     :services {:features ["home_assistant"]
                                :home-assistant fire-home-assistant!}}))]
          (reset! engine-ref eng)
          (binding [*eng* eng]
            (create! :context {:name "Workday" :default_shapes ["workday"]
                               :default_spans [{:from "09:00" :to "12:00"}
                                               {:from "13:00" :to "17:00"}]
                               :default_order 1})
            (f)))
        (finally (pg/close! st))))))

;; ── § 1 birth ───────────────────────────────────────────────────────

(deftest a-decision-takes-its-day-and-member-from-its-block
  (let [[plan block] (workday-block!)
        d (decide! block {:kind "pick" :text "Tonight's film" :order 3})]
    (is (= :planned (:state d)))
    (is (= today (get-in d [:data :date])) "the date is the block's")
    (is (= (get-in plan [:data :member]) (get-in d [:data :member]))
        "the member is the plan's")
    (is (= "Workday · 2026-01-06" (get-in d [:data :block_name]))
        "the block's label rides the ref")
    (is (false? (get-in d [:data :has_prep])) "no prep sentence, no prep fact")
    (testing "a decision needs a block that stands"
      (let [r (refusal #(create! :decision {:block_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9F9"
                                            :kind "work" :text "Nowhere" :order 1}))]
        (is (= "on-a-planned-block" (:guard r)))
        (is (re-find #"add the block to the day first" (str (:detail r))))))
    (testing "…and one that is still planned"
      (act! :block (:id block) :skip nil)
      (let [r (refusal #(decide! block {:text "Into a skipped block"}))]
        (is (= "on-a-planned-block" (:guard r)))
        (is (re-find #"Workday block is skipped" (str (:detail r))))))))

(deftest a-subject-is-a-row-that-stands
  (let [[plan block] (workday-block!)]
    (testing "an address naming a row this house serves is admitted"
      (is (nil? (refusal #(decide! block {:text "Replan the day"
                                          :subject (str "/api/day_plans/" (:id plan))})))))
    (testing "an address whose row does not exist is refused naming it"
      (let [addr "/api/day_plans/01HZQ7Y7F2R3W4V5X6Y7Z8A9F8"
            r (refusal #(decide! block {:text "A day nobody planned" :subject addr}))]
        (is (= "subject-resolves" (:guard r)))
        (is (re-find (re-pattern (java.util.regex.Pattern/quote addr)) (str (:detail r)))
            "the refusal names the address")))
    (testing "a collection this house does not serve is refused the same way"
      (is (= "subject-resolves"
             (:guard (refusal #(decide! block {:text "Elsewhere"
                                               :subject "/api/nothings/01HZQ7Y7F2R3W4V5X6Y7Z8A9F7"}))))))
    (testing "a query string is not an address"
      (is (= "subject-resolves"
             (:guard (refusal #(decide! block {:text "A search"
                                               :subject "/api/tasks?status=open"}))))))))

;; ── § 2 go ──────────────────────────────────────────────────────────

(deftest go-fires-the-service-exactly-once-and-records-started
  (reset! ha-calls [])
  (let [[_ block] (workday-block!)
        d (decide! block {:text "Porch lights on"
                          :launch {:type "service" :service "light/turn_on"
                                   :data {:entity_id "light.porch"}}})]
    (act! :decision (:id d) :start nil)
    (is (= :started (:state (row :decision (:id d)))) "the record says you went")
    (is (= [["light/turn_on" {:entity_id "light.porch"}]] @ha-calls)
        "…and the room heard it exactly once")
    (testing "a second Go re-fires nothing — the verdict was given"
      (refusal #(act! :decision (:id d) :start nil))
      (is (= 1 (count @ha-calls))))
    (testing "a link fires nothing here — the card opens it"
      (let [d2 (decide! block {:kind "pick" :text "Tonight's film" :order 2
                               :launch {:type "href" :href "https://letterboxd.com/"}})]
        (act! :decision (:id d2) :start nil)
        (is (= :started (:state (row :decision (:id d2)))))
        (is (= 1 (count @ha-calls)))))
    (testing "a room that does not answer refuses the start, and nothing is recorded"
      (let [d3 (decide! block {:text "The unreachable switch" :order 3
                               :launch {:type "service" :service "switch/unreachable"}})
            r (refusal #(act! :decision (:id d3) :start nil))]
        (is (some? r) "the start did not go through")
        (is (re-find #"502" (str (:message r) (:detail r))))
        (is (= :planned (:state (row :decision (:id d3))))
            "the record does not say 'went' while the room stayed dark")))))

(deftest service-data-is-an-entity-and-settings-rows-not-json
  ;; waymark-ylat: the owner met a JSON box under Launch → Service data;
  ;; the form now asks for the entity and settings rows, and the call
  ;; Home Assistant hears is the same map it always took
  (reset! ha-calls [])
  (let [[_ block] (workday-block!)
        d (decide! block {:text "Porch lights, dim and red"
                          :launch {:type "service" :service "light/turn_on"
                                   :data {:entity_id "light.porch"
                                          :settings [{:name "brightness_pct" :value "40"}
                                                     {:name "color_name" :value " red "}
                                                     {:name "flash" :value "true"}]}}})]
    (act! :decision (:id d) :start nil)
    (is (= [["light/turn_on" {:entity_id "light.porch" :brightness_pct 40
                              :color_name "red" :flash true}]]
           @ha-calls)
        "a number is a number, true is true, a word is a word"))
  (testing "the shape-1 free-form map folds into the same rows, once"
    (let [old {:launch {:type "service" :service "light/turn_on"
                        :data {:entity_id "light.porch" :brightness_pct 40 :color_name "red"}}}
          folded (dec/fold-launch-data old)]
      (is (= {:entity_id "light.porch"
              :settings [{:name "brightness_pct" :value "40"}
                         {:name "color_name" :value "red"}]}
             (get-in folded [:launch :data])))
      (is (= folded (dec/fold-launch-data folded)) "idempotent")
      (is (= {:entity_id "light.porch" :brightness_pct 40 :color_name "red"}
             (dec/service-data (get-in folded [:launch :data])))
          "and the call it fires is the one the old map fired")
      (let [bare (assoc-in old [:launch :data] {:entity_id "light.porch"})]
        (is (= bare (dec/fold-launch-data bare))
            "nothing but an entity has nothing to fold"))
      (is (= {} (dec/service-data nil)) "a launch with no data sends {}"))))

(deftest not-yet-takes-a-go-back-and-the-launch-stays-in-the-record
  ;; waymark-4an5: from started the only doors were Done, Skip and
  ;; Change, and un-going was two taps. Not yet is the one — and it
  ;; unfires nothing, which is exactly why it costs nothing.
  (reset! ha-calls [])
  (let [[_ block] (workday-block!)
        d (decide! block {:text "Porch lights on"
                          :launch {:type "service" :service "light/turn_on"
                                   :data {:entity_id "light.porch"}}})]
    (act! :decision (:id d) :start nil)
    (is (= 1 (count @ha-calls)))
    (act! :decision (:id d) :unstart nil)
    (is (= :planned (:state (row :decision (:id d)))) "the record reads planned again")
    (is (= 1 (count @ha-calls)) "un-starting unfires nothing — the log keeps the launch")
    (testing "and Go is a fresh verdict — the room hears it again"
      (act! :decision (:id d) :start nil)
      (is (= :started (:state (row :decision (:id d)))))
      (is (= 2 (count @ha-calls))))
    (testing "Not yet is a door out of started and nowhere else"
      (let [d2 (decide! block {:text "The gutters" :order 2})]
        (is (= :wrong-state (:problem (refusal #(act! :decision (:id d2) :unstart nil)))))))))

;; ── § 3 the other doors ─────────────────────────────────────────────

(deftest change-records-what-it-became
  (let [[_ block] (workday-block!)
        d (decide! block {:kind "agenda" :text "The deck estimate"})]
    (act! :decision (:id d) :start nil)
    (act! :decision (:id d) :change {:changed_to "The fence quote instead"})
    (let [d' (row :decision (:id d))]
      (is (= :changed (:state d')))
      (is (= "The fence quote instead" (get-in d' [:data :changed_to])))
      (is (= "The deck estimate" (get-in d' [:data :text]))
          "the decision said this, the day said that, and both are kept"))
    (testing "changed is a tomb"
      (is (= :wrong-state (:problem (refusal #(act! :decision (:id d) :start nil))))))
    (testing "done is reached from planned as well as started, and reopen
              leads back out (waymark-9u10: finishing cost nothing the
              declaration can see, so done is no tomb)"
      (let [d2 (decide! block {:text "The porch railing" :order 2})]
        (act! :decision (:id d2) :finish nil)
        (is (= :done (:state (row :decision (:id d2)))))
        (is (some? (refusal #(act! :decision (:id d2) :start nil)))
            "Go is not a door out of done — Reopen is")
        (act! :decision (:id d2) :reopen nil)
        (is (= :planned (:state (row :decision (:id d2))))
            "reopened, the verdict is a fresh Go")))
    (testing "skip is undone by reopen, from planned or started"
      (let [d3 (decide! block {:text "The gutters" :order 3})]
        (act! :decision (:id d3) :start nil)
        (act! :decision (:id d3) :skip nil)
        (is (= :skipped (:state (row :decision (:id d3)))))
        (act! :decision (:id d3) :reopen nil)
        (is (= :planned (:state (row :decision (:id d3)))))))))

(deftest skipping-the-block-lets-its-decisions-go
  (let [[_ block] (workday-block!)
        planned (decide! block {:text "The porch railing" :order 1})
        started (decide! block {:text "The deck estimate" :order 2})
        done (decide! block {:text "The gutters" :order 3})]
    (act! :decision (:id started) :start nil)
    (act! :decision (:id done) :finish nil)
    (act! :block (:id block) :skip nil)
    (is (= :skipped (:state (row :decision (:id planned)))))
    (is (= :skipped (:state (row :decision (:id started)))))
    (is (= :done (:state (row :decision (:id done))))
        "a finished decision is the record, not something to let go")))

;; ── § 4 prep is a task ──────────────────────────────────────────────

(deftest a-decision-with-prep-is-one-task-in-the-queue
  (let [[_ block] (workday-block!)
        bag (decide! block {:kind "prepare" :text "Bag by the door"
                            :prep "Pack the bag" :order 1})
        _ (decide! block {:text "The porch railing" :order 2})]
    (is (true? (get-in bag [:data :has_prep])) "the discovery fact reads the prep")
    (testing "one discovery pass mints exactly one task, for the decision with prep"
      (is (= 1 (mirror/discover! *eng* :task)))
      (let [t (task-for (:id bag))]
        (is (some? t) "identity is the decision's id under the day_plan tag")
        (is (= "day_plan" (get-in t [:data :source])))
        (is (= "Pack the bag" (get-in t [:data :title])) "titled by the prep sentence")
        (is (= "open" (get-in t [:data :status])))
        (is (= (at yesterday 18 0) (get-in t [:data :due_at]))
            "due the evening before the block's date, six in the household zone")
        (is (= (str ui-base "/api/decisions/" (:id bag)) (get-in t [:data :source_href]))
            "source_href leads back to the decision")
        (is (= "For: Bag by the door — Workday · 2026-01-06" (get-in t [:data :detail])))))
    (testing "a started decision's prep still stands in the queue"
      (let [keys' (decide! block {:kind "prepare" :text "Keys on the hook"
                                  :prep "Find the spare keys" :order 3})]
        (act! :decision (:id keys') :start nil)
        (is (= 1 (mirror/discover! *eng* :task)))
        (is (= "open" (get-in (task-for (:id keys')) [:data :status])))))
    (testing "skipping the decision reads as gone — the queue drops the task"
      (act! :decision (:id bag) :skip nil)
      (mirror/resync! *eng* :task)
      (is (= "dropped" (get-in (task-for (:id bag)) [:data :status]))))
    (testing "a decision skipped before discovery is never minted"
      (let [gone (decide! block {:kind "prepare" :text "Never mind"
                                 :prep "Nothing" :order 4})]
        (act! :decision (:id gone) :skip nil)
        (is (zero? (mirror/discover! *eng* :task)))
        (is (nil? (task-for (:id gone))))))))

;; ── § 5 a passage of what the house owns (waymark-35eb) ─────────────

(deftest a-place-is-spelled-the-way-its-medium-counts
  ;; dayplan10.passage, pure: the four spellings, their order, and the
  ;; medium each belongs to
  (testing "a time, with or without the episode in front"
    (is (= {:grammar :time :seconds 4740} (passage/parse "1:19:00")))
    (is (= {:grammar :time :seconds 270} (passage/parse "4:30")))
    (is (= {:grammar :time :seconds 720
            :episode {:season 2 :episode 5 :text "S02E05"}}
           (passage/parse "S02E05 0:12:00")))
    (is (= "S02E05" (get-in (passage/parse "s2e5 12:00") [:episode :text]))
        "the episode is read loosely and spelled flickr's way")
    (is (nil? (passage/parse "1:75")) "seconds run to fifty-nine"))
  (testing "a chapter, a page, a percent"
    (is (= {:grammar :chapter :n 7} (passage/parse "ch. 7")))
    (is (= {:grammar :chapter :n 7} (passage/parse "ch 7")))
    (is (= {:grammar :chapter :n 7} (passage/parse "chapter 7")))
    (is (= {:grammar :page :n 213} (passage/parse "p. 213")))
    (is (= {:grammar :page :n 213} (passage/parse "page 213")))
    (is (= {:grammar :percent :n 0.34M} (passage/parse "34%")))
    (is (= {:grammar :percent :n 0.34M} (passage/parse "pct 0.34")))
    (is (nil? (passage/parse "101%")) "a percent stops at the whole"))
  (testing "words in no grammar read as nothing"
    (is (nil? (passage/parse "the jury scene")))
    (is (nil? (passage/parse "")))
    (is (nil? (passage/parse nil))))
  (testing "the start comes first, within one grammar"
    (is (passage/precedes? (passage/parse "1:19:00") (passage/parse "1:24:30")))
    (is (not (passage/precedes? (passage/parse "1:24:30") (passage/parse "1:19:00"))))
    (is (not (passage/precedes? (passage/parse "1:19:00") (passage/parse "1:19:00")))
        "the same place is no passage")
    (is (passage/precedes? (passage/parse "S02E05 0:50:00") (passage/parse "S02E07 0:01:00"))
        "a later episode is later, whatever the clock says")
    (is (passage/precedes? (passage/parse "ch. 7") (passage/parse "ch. 9")))
    (is (passage/precedes? (passage/parse "34%") (passage/parse "pct 0.5")))
    (is (not (passage/precedes? (passage/parse "ch. 7") (passage/parse "p. 213")))
        "a chapter and a page have no order between them")
    (is (not (passage/same-grammar? (passage/parse "ch. 7") (passage/parse "1:19:00")))))
  (testing "the medium decides the grammar"
    (is (nil? (passage/misfit (passage/parse "1:19:00") "movie")))
    (is (nil? (passage/misfit (passage/parse "4:12:00") "audiobook")))
    (is (nil? (passage/misfit (passage/parse "41:10") "album"))
        "an album counts in time, like a film")
    (is (nil? (passage/misfit (passage/parse "S02E05 0:12:00") "show")))
    (is (nil? (passage/misfit (passage/parse "ch. 7") "book")))
    (is (nil? (passage/misfit (passage/parse "34%") "comic")))
    (is (re-find #"a movie's place is a time" (passage/misfit (passage/parse "ch. 7") "movie")))
    (is (re-find #"a book's place is a chapter" (passage/misfit (passage/parse "1:19:00") "book")))
    (is (re-find #"names no episode" (passage/misfit (passage/parse "0:12:00") "show")))
    (is (re-find #"a movie has none" (passage/misfit (passage/parse "S02E05 0:12:00") "movie")))
    (is (re-find #"an album has none" (passage/misfit (passage/parse "S02E05 0:12:00") "album")))
    (is (re-find #"an album's place is a time" (passage/misfit (passage/parse "ch. 7") "album")))
    (is (nil? (passage/misfit (passage/parse "1:19:00") nil))
        "a row whose medium was never said has no opinion")))

(deftest the-passage-link-is-a-projection-in-flickrs-grammar
  ;; sources.flickr/passage-link, pure: the row's own deep link with
  ;; the place appended — stored nowhere, computed off the row
  (let [film (str stream "/#/item/51")
        show (str stream "/#/show/The%20Wire")]
    (testing "a film: seconds in, seconds out"
      (is (= (str film "?t=4740&end=5070")
             (flickr/passage-link film "movie" "1:19:00" "1:24:30")))
      (is (= (str film "?t=4740") (flickr/passage-link film "movie" "1:19:00" nil))
          "no end, no end")
      (is (= (str film "?t=15120&end=16200")
             (flickr/passage-link film "audiobook" "4:12:00" "4:30:00")))
      (is (= (str film "?t=161&end=330")
             (flickr/passage-link film "album" "2:41" "5:30"))
          "an album is time too — a track's stretch, seconds in and out"))
    (testing "a show: the episode, then the time inside it"
      (is (= (str show "?ep=S02E05&t=720&end=2700")
             (flickr/passage-link show "show" "S02E05 0:12:00" "S02E05 0:45:00")))
      (is (= (str show "?ep=S02E05&t=720&end=2700&until=S02E07")
             (flickr/passage-link show "show" "S02E05 0:12:00" "S02E07 0:45:00"))
          "…and until, when the end is in a later episode")
      (is (nil? (flickr/passage-link show "show" "0:12:00" nil))
          "a show's place names its episode"))
    (testing "a book: locators"
      (is (= (str film "?from=ch:7&to=ch:9") (flickr/passage-link film "book" "ch. 7" "ch. 9")))
      (is (= (str film "?from=pg:213&to=pg:240") (flickr/passage-link film "book" "p. 213" "page 240")))
      (is (= (str film "?from=pct:0.34") (flickr/passage-link film "comic" "34%" nil)))
      (is (= (str film "?from=pct:0.5&to=pct:0.75") (flickr/passage-link film "book" "pct 0.5" "75%"))))
    (testing "nothing to project from is nothing"
      (is (nil? (flickr/passage-link nil "movie" "1:19:00" nil)) "a row with no deep link")
      (is (nil? (flickr/passage-link film nil "1:19:00" nil)) "a medium never said")
      (is (nil? (flickr/passage-link film "book" "1:19:00" nil)) "words in the wrong grammar")
      (is (nil? (flickr/passage-link film "book" "ch. 7" "p. 213")) "two grammars"))))

(deftest a-passage-is-a-place-in-a-media-row
  (reset! ha-calls [])
  (conf/seed! fake-flickr "movie:12-angry-men-1957"
              {:title "12 Angry Men" :medium "movie" :status "active"
               :progress_text "1:19" :progress 0.0137M
               :source_ui_href (str stream "/#/item/51")})
  (conf/seed! fake-flickr "book:the-jury"
              {:title "The Jury" :medium "book" :status "queued"})
  (conf/seed! fake-flickr "show:the-wire"
              {:title "The Wire" :medium "show" :status "active"
               :source_ui_href (str stream "/#/show/The%20Wire")})
  (mirror/discover! *eng* :media)
  (let [[plan block] (workday-block!)
        film (media-row "movie:12-angry-men-1957")
        book (media-row "book:the-jury")
        wire (media-row "show:the-wire")
        at (fn [r] (str "/api/media/" (:id r)))
        passage (fn [subject from to]
                  {:kind "pick" :text "A scene, for the talk" :subject subject
                   :launch (cond-> {:type "passage" :from from} to (assoc :to to))})]
    (is (some? film) "the shelf landed")
    (testing "the words alone: an end before its start, or in no grammar"
      (let [r (refusal #(decide! block (passage (at film) "1:24:30" "1:19:00")))]
        (is (= "launch-says-how" (:guard r)))
        (is (re-find #"ends \(1:19:00\) before it starts \(1:24:30\)" (str (:detail r)))))
      (let [r (refusal #(decide! block (passage (at film) "the jury scene" nil)))]
        (is (= "launch-says-how" (:guard r)))
        (is (re-find #"reads in no grammar" (str (:detail r)))))
      (is (= "launch-says-how"
             (:guard (refusal #(decide! block (passage (at film) "ch. 7" "1:24:30")))))
          "a chapter to a time counts two ways")
      (is (= "launch-says-how"
             (:guard (refusal #(decide! block {:text "Nowhere in particular"
                                               :launch {:type "passage"}}))))
          "a passage with no start"))
    (testing "a passage is a place in a MEDIA row"
      (let [r (refusal #(decide! block {:text "A scene of nothing"
                                        :launch {:type "passage" :from "1:19:00"}}))]
        (is (= "a-passage-reads-as-a-place" (:guard r)))
        (is (re-find #"no subject" (str (:detail r)))))
      (let [r (refusal #(decide! block (passage (str "/api/day_plans/" (:id plan)) "1:19:00" nil)))]
        (is (= "a-passage-reads-as-a-place" (:guard r)))
        (is (re-find #"is not a media row" (str (:detail r)))))
      (is (= "subject-resolves"
             (:guard (refusal #(decide! block (passage "/api/media/01HZQ7Y7F2R3W4V5X6Y7Z8A9F6" "1:19:00" nil)))))
          "a media row that does not stand is the address wall's refusal, first"))
    (testing "the medium decides the grammar, and the refusal names it"
      (let [r (refusal #(decide! block (passage (at book) "1:19:00" nil)))]
        (is (= "a-passage-reads-as-a-place" (:guard r)))
        (is (re-find #"The Jury is a book" (str (:detail r))))
        (is (re-find #"a chapter \(ch\. 7\), a page \(p\. 213\) or a percent \(34%\)" (str (:detail r)))))
      (let [r (refusal #(decide! block (passage (at film) "ch. 7" "ch. 9")))]
        (is (= "a-passage-reads-as-a-place" (:guard r)))
        (is (re-find #"12 Angry Men is a movie, and the start 'ch\. 7' reads as a chapter" (str (:detail r)))))
      (let [r (refusal #(decide! block (passage (at wire) "0:12:00" "0:45:00")))]
        (is (= "a-passage-reads-as-a-place" (:guard r)))
        (is (re-find #"names no episode" (str (:detail r))))))
    (testing "a well-spelled passage is admitted; Go fires nothing; Done logs no progress"
      (let [d (decide! block (passage (at film) "1:19:00" "1:24:30"))]
        (is (= :planned (:state d)))
        (is (= {:type "passage" :from "1:19:00" :to "1:24:30"}
               (select-keys (get-in d [:data :launch]) [:type :from :to]))
            "the words are kept exactly as typed — the link is nobody's field")
        (is (not (contains? (get-in d [:data :launch]) :href))
            "…and no href is stored: the passage's link is the feed's projection")
        (act! :decision (:id d) :start nil)
        (is (= :started (:state (row :decision (:id d)))) "the record says you went")
        (is (empty? @ha-calls) "…and the room heard nothing — the card carries a passage")
        (act! :decision (:id d) :finish nil)
        (is (= :done (:state (row :decision (:id d)))))
        (let [film' (row :media (:id film))]
          (is (= (select-keys (:data film) [:status :progress :progress_text])
                 (select-keys (:data film') [:status :progress :progress_text]))
              "finishing logs no progress on the film — a scene watched for a talk is not where you are in it"))
        (is (nil? (refusal #(decide! block (assoc (passage (at wire) "S02E05 0:12:00" "S02E07 0:45:00") :order 2))))
            "a show's passage names its episodes")
        (is (nil? (refusal #(decide! block (assoc (passage (at book) "ch. 7" nil) :order 3))))
            "a book's passage with no end opens at the chapter and goes on")))))

;; ── the chapter picker (waymark-z8u4) ───────────────────────────────

(defn- non-null
  "The non-null branch of a :maybe's projection — X | null arrives as
  oneOf/anyOf, and the form judges by the branch that is not null."
  [prop]
  (or (some #(when (not= "null" (:type %)) %)
            (concat (:oneOf prop) (:anyOf prop)))
      prop))

(deftest from-and-to-are-offered-off-the-subjects-own-places
  ;; the create door's launch sub-form carries the :places recipe on
  ;; from and to, its hole renamed to the subject field ONE LEVEL UP
  ;; — the form resolves {subject} at the top of the form and reads
  ;; the media row's /-/places document, where main/places answers the
  ;; row's chapters or episodes. Pure: the published schema alone.
  (let [js (schema/json-schema (:create-schema dec/decision))
        launch (non-null (get-in js [:properties :launch]))
        recipe {:from "places" :of "subject"
                :href "{subject}/-/places" :at ["places"]
                :note "the places inside the row named in subject — its chapters, episodes or sections, spelled the way this field reads a place"}]
    (is (= recipe (get-in launch [:properties :from :x-options])))
    (is (= recipe (get-in launch [:properties :to :x-options])))
    (is (= {:type "passage"} (get-in launch [:properties :from :x-display :when]))
        "…and each still shows only under the passage choice")
    (is (nil? (get-in js [:properties :subject :x-options]))
        "the subject itself is typed or linked, not picked from a shelf")))
