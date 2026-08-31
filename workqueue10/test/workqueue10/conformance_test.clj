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
  (:require [clojure.test :refer [deftest is use-fixtures]]
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
  ["tasks" "task_lists" "media" "threads" "chores" "chore_runs" "days"
   "meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "members" "roles" "grants" "approval_requests"
   ;; insights carried a DAILY CAP counted over rows until
   ;; waymark-1uv.8 took it off (ranked, not capped); what is left of
   ;; the reason is the second half — findings left behind by the last
   ;; run are stale cards contending, by the rank, for the decide
   ;; section's two insight slots. This is the house not carrying
   ;; yesterday's findings.
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
   ;; …and the view door's pair (waymark-8um.1), for a third reason of
   ;; its own: a consent row left RECORDING would have every feed read
   ;; in the run after it writing rows nobody asked for, and the whole
   ;; claim of that obligation is that nothing is written until
   ;; somebody says so.
   "feed_views" "feed_view_consents"
   ;; …and the crown's three (waymark-jfv.4), for the first two
   ;; reasons at once. An outcome left OFFERED with its pieces still
   ;; on offer is a card ABOVE do-now, and the section takes two — so
   ;; a run's own bundle could be crowded off the page by a run that
   ;; did not finish. The value beneath it is what keeps it there, and
   ;; a value is never terminal, so it outlives everything.
   "composition_requests" "outcome_pieces" "outcomes" "values" "people"
   "hypotheses"
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

(def kinds [:task :task_list :media :thread])

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

;; :thread is the THIRD confluence's kind (docs/spec-threads.md) and
;; registers the pull-only pair :task_list does, for the same reason:
;; the queue mirrors the household's conversations and never writes
;; them — no local writes, no push, and under the pull-only shape a
;; birth is the external-identity create carrying the rig's tag. The
;; observed document is the shape verified live at Gate on 2026-08-28,
;; wire-shaped — and it is worth reading for what is NOT in it: no
;; preview, no snippet, no unread count, nothing anybody said.
(fac/example-input! :thread :create
  (fn [_] {:external_id (str "tgram:walk-" (random-uuid))}))

(fac/example-input! :thread :observe_external
  {:document {:title "Wellesley Kopsa"
              :source "tgram"
              :chat_kind "direct"
              :status "live"
              :last_message_at "2026-08-28T03:54:19Z"
              :participant_names ["Wellesley Kopsa"]}
   :etag "conformance-etag-1"})

;; THE ONE NUMBER GENERATION CANNOT GUESS (waymark-bug). A
;; hypothesis's `prior` is bounded 0.02–0.5 by a guard rather than by
;; the schema, because `waymark10.schema`'s `:decimal` derives its own
;; generator from its `:min`/`:max` with `(long …)` — so a band
;; entirely inside 0 and 1 would generate the single value 0 and the
;; walker would refuse every row it wrote. That is exactly the case
;; `example-input!`'s docstring names: a check-style guard with an
;; acknowledged open judgment. The `about` address names this kind's
;; own collection, so the citation wall is answering about something.
(fac/example-input! :hypothesis :create
  (fn [_] {:claim (str "Somebody here means to do the thing "
                       (subs (str (random-uuid)) 0 8))
           :shape "intent"
           :about [(str "/api/hypotheses/walked-" (random-uuid))]
           :prior 0.1M}))

;; ── the whole suite ─────────────────────────────────────────────────

(deftest conformance
  (let [report (suite/check! {:engine *eng* :handler *h* :kinds kinds})]
    ;; …and the crown MEASURED itself (waymark-jfv.4). `:feed/outcomes`
    ;; is the one obligation in this house whose whole claim is that a
    ;; tap WROTE something — a piece materializing a real row under the
    ;; member's own name — and an obligation that ran over zero taps is
    ;; a green run that proved nothing. This engine holds all four
    ;; kinds it needs, so a skip here is a regression rather than a
    ;; posture.
    (is (pos? (suite/coverage report :feed/outcomes))
        "a member's tap made a piece of the week real")
    ;; …and so did the reasons (waymark-jfv.16), for the same reason
    ;; said one turn on: `:feed/verdict-reasons` is the obligation whose
    ;; whole claim is that a SECOND tap wrote something — a decline that
    ;; learned to speak — and a run over zero reasons would be a green
    ;; run over the bead's own sentence. This engine holds every kind it
    ;; needs, so a skip here is a regression rather than a posture.
    (is (pos? (suite/coverage report :feed/verdict-reasons))
        "a declined piece carried a reason, in one more optional tap")
    ;; …and the diagnosis duty (waymark-8um.4), whose whole claim is
    ;; that a WALL fired: a recomposition of a shown-and-declined bundle
    ;; refused by name for want of a diagnosis. A run in which nothing
    ;; was refused is a green run over law 4's own sentence. This
    ;; engine holds every kind it needs, so a skip is a regression.
    (is (pos? (suite/coverage report :feed/diagnosis))
        "a recomposition with no diagnosis was refused by name")))
