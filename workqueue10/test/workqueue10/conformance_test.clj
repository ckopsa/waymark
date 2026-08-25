(ns workqueue10.conformance-test
  "The queue's three kinds handed to the waymark10 conformance
  DRIVER: the machine walks itself, and every obligation core owes —
  plus every obligation each enrolled module owes — is proved over
  the real ring handler. Until waymark-db9.5 that was eight deftests
  written out here and re-written in three sibling suites; now the
  suite is one call and the obligations live where their surface
  does (waymark10.test.packs). Mirrors choreplan10.conformance-test's
  shape; the registrations are the only domain-specific part:

  - task is a Mirror, so it registers the same pair choreplan's
    prep_task and paydesk's mirrors do: an external-identity create and a
    wire-shaped observe_external document (generation would invent
    non-JSON). The create's external id carries a SOURCE TAG
    (\"chore:walk-…\") — every row of this kind is born through the
    confluence, and an untagged id would refuse at the routing seam.
  - task_list is the pull-only Mirror beside it (the list a task
    belongs to, as a row): the same pair, no local writes at all.
  - its :complete pushes through main's module fake sources, whose
    push treats a never-seeded doc as an open task — the walker's
    rows push clean (the FakeFeed auto-vivify spirit).
  - no state factories: no action gates a transition behind a guard
    the walk can't satisfy, so the generic shortest-path walk reaches
    every state on its own.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.test :refer [deftest use-fixtures]]
            [next.jdbc :as jdbc]
            [workqueue10.main :as main]
            [waymark10.server.engine :as engine]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.test.factories :as fac]
            [waymark10.test.suite :as suite]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(def ^:private tables
  ;; the WHOLE folded registry's tables (task_queue_test's rule, and
  ;; the reason it exists): this engine boots every kind main/resources
  ;; declares, chore and meal included, so a fixture that drops only
  ;; "tasks" boots into whatever shape another suite left behind —
  ;; a promoted column added to a folded kind refuses at boot
  ["tasks" "task_lists" "media" "chores" "chore_runs" "days"
   "meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "members" "roles" "grants" "approval_requests"
   ;; insights carry a DAILY CAP counted over rows (waymark-iqa.6), so
   ;; findings left behind by the last run are an allowance already
   ;; spent — and two runs later they are also stale cards holding the
   ;; decide section's two insight slots. The obligation mints a fresh
   ;; author every run so it can ask the cap its question either way;
   ;; this is the house not carrying yesterday's findings.
   "insights"
   ;; and the feed module's own pair, for the same two reasons one
   ;; turn on (waymark-4yn, waymark-0k4). A stored feed_recipe left
   ;; ACTIVE is the order every feed obligation above would be read
   ;; in; a recipe_proposal left OFFERED is a decide card holding a
   ;; slot, and its own open cap (three a stager) counts rows the way
   ;; the insight cap does. Both obligations end where they began, so
   ;; this only matters after a run that did not finish — which is
   ;; exactly the run whose residue is hardest to read.
   "feed_recipes" "recipe_proposals"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        ;; :suppress-mirror-refresh — a Mirror breaks the walker's
        ;; reads-are-pure assumption (a GET on a staged stale row
        ;; would heal it to fresh under the assertions); production
        ;; reads pull through, only this fixture suppresses.
        ;; with-push mirrors production wiring (main/start!): the
        ;; task kind's :complete pushes through the fakes
        (let [eng (mirror/with-push
                   (engine/engine {:storage st
                                   :resources (main/check-resources)
                                   :suppress-mirror-refresh true}))]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

(def kinds [:task :task_list :media])

;; ── the enrollment ──────────────────────────────────────────────────

;; a :create-push mirror's create speaks the CREATE-SCHEMA (the birth
;; input is the author's law): the walker's captures push through
;; main's fake todo source, which mints the identity claim_external
;; stamps back — so walked rows are real mirror rows end to end
(fac/example-input! :task :create
  (fn [_] {:title (str "walked capture " (random-uuid))}))

(fac/example-input! :task :observe_external
  {:document {:title "Dishes"
              :source "chore"
              :assignee_name "colton"
              :due_at "2026-01-07T00:00:00Z"
              :status "open"
              :detail "load and run before bed"
              :list_key "todo:todo.woodworking"}
   :etag "conformance-etag-1"})

;; :task_list is the PULL-ONLY half of the pair (no local writes —
;; the queue mirrors the household's lists and never writes them,
;; though the NATIVE birth door stands beside the mirrors since
;; waymark-fnl), so it registers the plain mirror shape: an
;; external-identity create carrying the confluence's source tag
;; (the paired birth law reads it as mirrored), and a wire-shaped
;; document
(fac/example-input! :task_list :create
  (fn [_] {:external_id (str "gtasks:walk-" (random-uuid))}))

(fac/example-input! :task_list :observe_external
  {:document {:title "Woodworking" :source "gtasks"}
   :etag "conformance-etag-1"})

;; :media is the SECOND confluence's kind and registers the same
;; :create-push pair the task does: the walker's captures are hub
;; births (main's noop hub source mints the identity claim_external
;; stamps back — spec-media.md's shape 1), and the observed document
;; is the flickr addendum's verified live shape, wire-shaped
(fac/example-input! :media :create
  (fn [_] {:title (str "walked queue " (random-uuid)) :medium "movie"}))

(fac/example-input! :media :observe_external
  {:document {:title "12 Angry Men"
              :medium "movie"
              :status "active"
              :year 1957
              :progress 0.0137M
              :progress_text "1:19"
              :work_key "movie:12-angry-men-1957"
              :audience_name "Colton"
              :source "flickr"
              :source_ui_href "https://stream.kopsa.info/#/item/51"}
   :etag "conformance-etag-1"})

;; ── the whole suite ─────────────────────────────────────────────────

(deftest conformance
  (suite/check! {:engine *eng* :handler *h* :kinds kinds}))
