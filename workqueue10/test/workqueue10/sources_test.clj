(ns workqueue10.sources-test
  "The boundary's pure half: the envelope→canonical translations, the
  shared push-plan table, namespaced identity, and the confluence's
  routing — no database, no HTTP, the fakes where an adapter is
  needed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dayplan10.zone :as zone]
            [workqueue10.confluence :as conf]
            [workqueue10.sources.choreplan :as chores]
            [workqueue10.sources.dayplan :as dayplan]
            [workqueue10.sources.gtasks :as gt]
            [workqueue10.sources.homeassistant :as ha]
            [workqueue10.sources.mealplan :as meals]
            [workqueue10.sources.waymark :as wm]
            [waymark10.server.mirror :as mirror])
  (:import (java.time LocalDate LocalTime)))

;; ── the translations ────────────────────────────────────────────────

(deftest chore-run-translation
  (testing "state → status, per chore_run's three states"
    (is (= "open" (:status (chores/run->task {:state "due" :data {}}))))
    (is (= "done" (:status (chores/run->task {:state "done" :data {}}))))
    (is (= "dropped" (:status (chores/run->task {:state "skipped" :data {}})))))

  (testing "a fourth state fails loudly, never maps silently"
    (is (thrown? IllegalArgumentException
                 (chores/run->task {:state "paused" :data {}}))))

  (testing "due_date widens to the day's CLOSING midnight — overdue
            flips exactly when choreplan's own date-typed law flips"
    (is (= "2026-07-22T00:00:00Z"
           (:due_at (chores/run->task
                     {:state "due" :data {:due_date "2026-07-21"}})))))

  (testing "the run reads standalone: label copy and carried notes"
    (let [t (chores/run->task
             {:state "due"
              :data {:chore_name "Dishes" :assignee "colton"
                     :due_date "2026-07-21"
                     :chore_notes "load and run before bed"}})]
      (is (= "Dishes" (:title t)))
      (is (= "colton" (:assignee_name t))
          "a source speaks NAMES; :assignee is the ref the engine
           resolves from this text")
      (is (= "load and run before bed" (:detail t))))))

(deftest prep-task-translation
  (testing "state → status, per prep_task's four states"
    (is (= "open" (:status (meals/prep->task {:state "pending" :data {}}))))
    (is (= "open" (:status (meals/prep->task {:state "scheduled" :data {}}))))
    (is (= "done" (:status (meals/prep->task {:state "done" :data {}}))))
    (is (= "dropped" (:status (meals/prep->task {:state "cancelled" :data {}})))))

  (testing "title folds task_type + meal_name; due_at rides through"
    (let [t (meals/prep->task
             {:state "pending"
              :data {:task_type "thaw" :meal_name "Traeger brisket"
                     :assignee "housekeeper"
                     :due_at "2026-07-21T16:00:00Z"
                     :notes "move 2500g brisket to the fridge"}})]
      (is (= "thaw: Traeger brisket" (:title t)))
      (is (= "2026-07-21T16:00:00Z" (:due_at t)))
      (is (= "move 2500g brisket to the fridge" (:detail t))))))

(deftest home-assistant-translation
  (testing "status, per HA's two states — deletion is gone, never a status"
    (is (= "open" (:status (ha/item->task {:summary "x" :status "needs_action"}
                                          "America/Denver"))))
    (is (= "done" (:status (ha/item->task {:summary "x" :status "completed"}
                                          "America/Denver")))))

  (testing ":detail is the household's own description and NOTHING
            else — the list used to be string-joined onto its head,
            which made a filterable fact into prose (it is :list_key
            and the :task_list ref now)"
    (is (nil? (:detail (ha/item->task {:summary "x" :status "needs_action"}
                                      "America/Denver")))
        "nothing to say is nil, not an empty string")
    (is (= "glue first"
           (:detail (ha/item->task {:summary "x" :status "needs_action"
                                    :description "glue first"}
                                   "America/Denver"))))
    (is (not (str/includes? (str (:detail (ha/item->task
                                           {:summary "x"
                                            :status "needs_action"
                                            :description "glue first"}
                                           "America/Denver")))
                            "—"))
        "no list prefix survives anywhere in the prose"))

  (testing "the list is still NAMED — it just names a row now"
    (is (= "Woodworking" (ha/list-name {"todo.woodworking" "Woodworking"}
                                       "todo.woodworking")))
    (is (= "My tasks" (ha/list-name {} "todo.my_tasks"))
        "an unconfigured entity derives its own friendly name"))

  (testing "due dates: date-only widens to the closing midnight (the
            chore-source law); datetimes keep their offset; naive
            local times parse in the household's zone"
    (is (= "2026-07-26T00:00:00Z"
           (ha/due->instant "2026-07-25" "America/Denver")))
    (is (= "2026-07-23T01:00:00Z"
           (ha/due->instant "2026-07-22T19:00:00-06:00" "America/Denver")))
    (is (= "2026-07-23T01:00:00Z"
           (ha/due->instant "2026-07-22T19:00:00" "America/Denver")))
    (is (nil? (ha/due->instant nil "America/Denver")))))

;; HA has no fake transport (its reads are one POST per service call,
;; and the queue's story exercises the routing through the generic
;; fake source), so the two translations that USED to be one string
;; join are asserted directly — decorate and list-doc are public here
;; for exactly that reason, where google's equivalents stay private
;; behind a fake that runs the real source end to end.
(deftest home-assistant-stamps-the-list-it-came-from
  (let [src {:api-base "http://ha.lan:8123" :ui-base "https://ha.kopsa.info"
             :names {"todo.woodworking" "Woodworking"}}]
    (testing "a task's :list_key is its own entity, source-local — the
              confluence namespaces it into the list row's external id"
      (let [doc (ha/decorate src "todo.woodworking"
                             (ha/item->task {:summary "Sharpen chisels"
                                             :status "needs_action"
                                             :description "glue first"}
                                            "America/Denver"))]
        (is (= "todo.woodworking" (:list_key doc)))
        (is (= "glue first" (:detail doc))
            "…and the list is no longer smuggled into the prose")))

    (testing "the list row: the friendly name titles it, and both hrefs
              are the ones a task from it already carried — HA anchors
              on the list either way"
      (let [doc (ha/list-doc src "todo.woodworking")]
        (is (= "Woodworking" (:title doc)))
        (is (= "http://ha.lan:8123/api/states/todo.woodworking"
               (:source_href doc)))
        (is (= "https://ha.kopsa.info/todo?entity_id=todo.woodworking"
               (:source_ui_href doc)))))))

(deftest home-assistant-lists-are-the-configured-entities
  (let [src (ha/http-source
             {:url "http://ha.lan:8123/" :ui-url "https://ha.kopsa.info"
              :token "t" :lists "todo.woodworking, todo.shopping_list"
              :names {"todo.woodworking" "Woodworking"}})]
    (testing "list discovery reads the CONFIGURATION, not the wire —
              there is no round trip here, so there is nothing to fail
              (the recorded punt: HA's own friendly_name lives at
              /api/states and would cost one)"
      (is (= ["todo.woodworking" "todo.shopping_list"]
             (conf/list-discover src))))

    (testing "an unconfigured entity derives its own friendly name"
      (is (= "Shopping list"
             (:title (first (conf/list-pull src "todo.shopping_list"))))))

    (testing "the batch agrees with the singular read, etag and all"
      (is (= (conf/list-pull src "todo.woodworking")
             (get (conf/list-pull-many src ["todo.woodworking"])
                  "todo.woodworking"))))))

(deftest decision-prep-translation
  (let [env {:self "/api/decisions/d-bag"
             :state "planned"
             :data {:kind "prepare" :text "Bag by the door"
                    :prep "Pack the bag" :date "2026-01-06"
                    :block_name "Workday · 2026-01-06"
                    :member_handle "colton"}}]
    (testing "the prep sentence IS the title; the decision it readies is
              the detail, so the task reads standalone in a mixed queue"
      (let [t (dayplan/decision->task env)]
        (is (= "Pack the bag" (:title t)))
        (is (= "For: Bag by the door — Workday · 2026-01-06" (:detail t)))
        (is (= "colton" (:assignee_name t))
            "the plan's member by handle — the ref's :carry on the decision")))

    (testing "due the evening before the block's date: six o'clock in the
              household zone, a real instant"
      (is (= (str (zone/at (LocalDate/parse "2026-01-05") (LocalTime/of 18 0)))
             (:due_at (dayplan/decision->task env))))
      (is (nil? (:due_at (dayplan/decision->task (assoc-in env [:data :date] nil))))
          "no date, no due — the belt under the block guard"))

    (testing "state → status: planned and started stand as open work,
              done is done"
      (is (= "open" (:status (dayplan/decision->task env))))
      (is (= "open" (:status (dayplan/decision->task (assoc env :state "started")))))
      (is (= "done" (:status (dayplan/decision->task (assoc env :state "done"))))))

    (testing "a decision skipped or changed reads as GONE — the 404
              sentinel the confluence turns into :gone, never a status"
      (doseq [state ["skipped" "changed"]]
        (let [e (try (dayplan/decision->task (assoc env :state state)) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) (str state " throws"))
          (is (= 404 (:status (ex-data e))))
          (is (re-find #"nothing to do now" (ex-message e))))))))

(deftest origin-hrefs
  (testing "the source client stamps the way back: the API row and
            the engine's ui.html anchored on it (the URL hash IS the
            resource href)"
    (let [t (wm/with-origin "https://rod.kopsa.info"
              "/api/chore_runs/abc-123"
              {:title "Dishes" :status "open"})]
      (is (= "https://rod.kopsa.info/api/chore_runs/abc-123"
             (:source_href t)))
      (is (= "https://rod.kopsa.info/#/api/chore_runs/abc-123"
             (:source_ui_href t)))
      (is (= "Dishes" (:title t)) "the translation rides through untouched"))))

;; ── the shared push-plan ────────────────────────────────────────────

(deftest push-plan-table
  (testing "a local-only write (prioritize) has nothing to say"
    (is (= :noop (conf/push-plan {:status "open" :priority 1} "open")))
    (is (= :noop (conf/push-plan {:status "open" :priority 1} "done"))))

  (testing "done travels once; an agreeing authority is idempotence"
    (is (= :complete (conf/push-plan {:status "done"} "open")))
    (is (= :noop (conf/push-plan {:status "done"} "done"))))

  (testing "done against a dropped task is the conflicted state"
    (is (thrown-with-msg? Exception #"complete does not apply"
                          (conf/push-plan {:status "done"} "dropped")))))

;; ── namespaced identity ─────────────────────────────────────────────

(deftest xid-round-trip
  (is (= "chore:cr-1" (conf/xid "chore" "cr-1")))
  (is (= ["chore" "cr-1"] (conf/split-xid "chore:cr-1")))
  (testing "a source-local id containing colons survives the round trip"
    (is (= ["meal" "a:b:c"] (conf/split-xid (conf/xid "meal" "a:b:c")))))
  (testing "an unprefixed id refuses — every row is born through the
            confluence"
    (is (thrown-with-msg? Exception #"carries no source tag"
                          (conf/split-xid "bare-uuid")))))

;; ── the confluence's routing ────────────────────────────────────────

(deftest confluence-routing
  (let [chore-fake (conf/fake-source)
        meal-fake (conf/fake-source)
        feed (conf/confluence {"chore" chore-fake "meal" meal-fake})]
    (conf/seed! chore-fake "cr-1" {:title "Dishes" :status "open"})
    (conf/seed! meal-fake "pt-1" {:title "thaw: Brisket" :status "open"})

    (testing "discover namespaces every source's ids"
      (is (= #{"chore:cr-1" "meal:pt-1"} (set (mirror/discover feed)))))

    (testing "pull routes by prefix and stamps :source on the way through"
      (let [[doc _etag] (mirror/pull feed "chore:cr-1")]
        (is (= "chore" (:source doc)))
        (is (= "Dishes" (:title doc)))))

    (testing "pull-many fans out per source and re-prefixes the keys"
      (let [pulled (mirror/pull-many feed ["chore:cr-1" "meal:pt-1"])]
        (is (= #{"chore:cr-1" "meal:pt-1"} (set (keys pulled))))
        (is (= "meal" (:source (first (get pulled "meal:pt-1")))))))

    (testing "a down source degrades per-source: discover skips it,
              pull-many drops its ids, the other source flows"
      (conf/down! chore-fake true)
      (is (= ["meal:pt-1"] (vec (mirror/discover feed))))
      (is (= #{"meal:pt-1"}
             (set (keys (mirror/pull-many feed ["chore:cr-1" "meal:pt-1"])))))
      (testing "…while a singular pull throws — per-row unreachable is
                the sync machine's honest rendering"
        (is (thrown? Exception (mirror/pull feed "chore:cr-1"))))
      (conf/down! chore-fake false))

    (testing "push routes done to the tagged authority"
      (mirror/push feed "chore:cr-1" {:status "done" :title "Dishes"})
      (is (= "done" (get-in @(:state chore-fake) [:docs "cr-1" :status])))
      (is (= "open" (get-in @(:state meal-fake) [:docs "pt-1" :status]))
          "the other source never heard about it"))

    (testing "an unregistered tag refuses loudly"
      (is (thrown-with-msg? Exception #"no source registered"
                            (mirror/pull feed "laundry:x-1"))))))

(deftest a-birth-routes-on-its-source-and-carries-the-list-back-down
  (let [todos (conf/fake-source)
        gtasks (gt/fake-source {:capture "L-inbox"})
        feed (conf/confluence {"todo" todos "gtasks" gtasks})]
    (gt/list! gtasks "L-errands" "Errands")

    (testing "the tag names the authority, and the minted identity
              comes back namespaced by the same tag it routed on"
      (let [[xid _] (mirror/push-create feed {:source "gtasks"
                                              :title "Buy sandpaper"
                                              :list_key "gtasks:L-errands"})]
        (is (= ["gtasks" "L-errands"]
               [(first (conf/split-xid xid))
                (first (gt/split-id (second (conf/split-xid xid))))])
            "the queue's spelling of the list went in; the authority's
             own came out, and the row's external id carries both")))

    (testing "a birth naming no authority refuses before any source
              hears about it"
      (is (thrown-with-msg? Exception #"names its :source"
                            (mirror/push-create feed {:title "Nowhere"}))))

    (testing "a list belonging to ANOTHER authority refuses at the
              routing seam too — the create door's guard says it in a
              sentence, this holds the line for anything that reaches
              here another way"
      (is (thrown-with-msg? Exception #"cannot land in list"
                            (mirror/push-create feed
                                                {:source "gtasks"
                                                 :title "Buy sandpaper"
                                                 :list_key "todo:todo.inbox"}))))

    (testing "a source with no list concept is handed no list key"
      (let [[xid _] (mirror/push-create feed {:source "todo"
                                              :title "Oil the door hinge"})]
        (is (= "todo" (first (conf/split-xid xid))))))))

;; ── the list feed ───────────────────────────────────────────────────

(deftest a-task-carries-its-list-namespaced
  (testing "a source names its list the way its own authority does;
            the confluence namespaces it with the SAME tag it
            namespaces the row with, so :list_key IS the matching
            :task_list row's external id — which is why the ref needs
            no :match"
    (let [todos (conf/fake-source)
          feed (conf/confluence {"todo" todos})]
      (conf/seed! todos "todo.woodworking/uid-1"
                  {:title "Sharpen chisels" :status "open"
                   :list_key "todo.woodworking"})
      (let [[doc _] (mirror/pull feed "todo:todo.woodworking/uid-1")]
        (is (= "todo:todo.woodworking" (:list_key doc))))
      (let [pulled (mirror/pull-many feed ["todo:todo.woodworking/uid-1"])]
        (is (= "todo:todo.woodworking"
               (:list_key (first (get pulled "todo:todo.woodworking/uid-1"))))
            "the batch stamps it the same way the singular read does"))))

  (testing "a source with no list concept leaves it unset — the gap
            renders, and nothing invents a prefix"
    (let [chores (conf/fake-source)
          feed (conf/confluence {"chore" chores})]
      (conf/seed! chores "cr-1" {:title "Dishes" :status "open"})
      (is (nil? (:list_key (first (mirror/pull feed "chore:cr-1"))))))))

(deftest the-list-confluence-routes-the-same-tags
  (let [todos (conf/fake-source)
        gtasks (conf/fake-source)
        chores (conf/fake-source)
        srcs {"todo" todos "gtasks" gtasks "chore" chores}
        feed (conf/list-confluence (conf/list-sources srcs))]
    (conf/seed-list! todos "todo.woodworking" {:title "Woodworking"})
    (conf/seed-list! gtasks "MTIzNA" {:title "Errands"})

    (testing "discover namespaces every list-keeping source's ids"
      (is (= #{"todo:todo.woodworking" "gtasks:MTIzNA"}
             (set (mirror/discover feed)))))

    (testing "pull routes by prefix and stamps :source, exactly as the
              task feed does"
      (let [[doc _etag] (mirror/pull feed "todo:todo.woodworking")]
        (is (= "todo" (:source doc)))
        (is (= "Woodworking" (:title doc)))))

    (testing "pull-many fans out and answers :gone for a list the
              authority no longer keeps"
      (let [pulled (mirror/pull-many feed ["todo:todo.woodworking"
                                           "gtasks:MTIzNA"
                                           "gtasks:vanished"])]
        (is (= "Errands" (:title (first (get pulled "gtasks:MTIzNA")))))
        (is (= :gone (get pulled "gtasks:vanished")))))

    (testing "a down source degrades per-source here too"
      (conf/down! gtasks true)
      (is (= ["todo:todo.woodworking"] (vec (mirror/discover feed))))
      (conf/down! gtasks false))

    (testing "the queue does not WRITE lists — the one door that could
              reach the push says why"
      (is (thrown-with-msg? Exception #"does not write task lists"
                            (mirror/push feed "todo:todo.woodworking"
                                         {:title "Renamed"}))))))

(deftest list-sources-reads-the-protocol-not-a-configuration
  (testing "a source joins the list feed by implementing TaskListSource
            — the wiring in main follows for free"
    (let [speaks (conf/fake-source)
          silent (reify conf/TaskSource
                   (source-discover [_] [])
                   (source-pull [_ _] nil)
                   (source-pull-many [_ _] {})
                   (source-push [_ _ _] nil)
                   (source-create [_ _] nil))]
      (is (= #{"todo"} (set (keys (conf/list-sources {"todo" speaks
                                                      "chore" silent}))))))))
