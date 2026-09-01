(ns workqueue10.undo-test
  "UNDO, over the real ring handler (waymark-qmo6, docs/spec-undo.md).

  The declared scenarios prove the half a conformance tier can prove:
  the walker stages a row as ITSELF and a scenario's principal only
  ever ATTEMPTS, so every scenario about an undo is, by construction,
  somebody reaching for a tap that was not theirs. That is the law's
  most important sentence and it is proved from the wire.

  What only a live engine can answer is here, because every one of
  these claims is a STORY rather than a literal row — one hand
  answering and then taking the answer back, with the house's state
  read afterwards:

  - a person takes a finding and takes the take back: the row is
    `published` again, the version advanced twice, and BOTH taps are
    in the log. Nothing is erased;
  - nobody else can: a second person meets `only-your-own-last-tap`
    by name, and the refusal says whose tap it was;
  - an undo of an undo is refused, because the newest transition on
    the row is then the undo and no door takes that back — the stack
    is one deep per row, across many rows;
  - the walls RE-JUDGE: answering a finding frees its question, so a
    fresh finding on the same offer may lawfully stand by the time the
    hand reaches for undo — and then the undo is refused, naming the
    row now in the way, in that wall's own voice;
  - an author withdraws its own uncontested finding with NO grant at
    all — the own-surface courtesy, and the partial heal of
    waymark-br7v: the author's actions map on its own row was empty,
    because the four-eyes wall correctly refuses it both verdicts;
  - the belief FOLD follows the undo through the `:maintain` seam with
    no new wiring: a dismissed atom leaves its belief and an undone
    dismissal puts it back. That is waymark-2ozr's seam collecting a
    dividend it was not built for, and it is the one claim in this
    file that reaches across two kinds;
  - and un-affirming a hypothesis takes the STAMP back with the state,
    so the summary line and the document never disagree about whose
    the belief is.

  Assertions are order-independent (kaocha randomizes and the deftests
  share one DB): every test names its own principals and its own rows
  and never asserts on collection size.

  Needs a Postgres database; WAYMARK10_TEST_DSN names it.
  Run: cd workqueue10 && clojure -M:test --focus workqueue10.undo-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]
            [workqueue10.main :as main]))

(def ^:private tables
  ;; the whole folded registry's tables — outcome_test's rule and its
  ;; reason: this engine boots every kind main/check-resources declares
  ;; plus what the module table enrols, so a fixture dropping only its
  ;; own would boot into whatever shape another suite left behind.
  ["composition_requests" "outcome_pieces" "outcomes" "values" "people"
   "hypotheses"
   "tasks" "task_lists" "media" "chores" "chore_runs" "days"
   "meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "activities" "evening_plans" "evening_sessions"
   "letters" "weathers" "selves" "journals" "ticklers" "insights"
   "permission_slips" "saved_views" "dashboards" "dashboard_slots"
   "connections" "capabilities"
   "members" "roles" "grants" "approval_requests"
   "feed_recipes" "recipe_proposals" "feed_views" "feed_view_consents"
   "verdict_reasons"
   "attachments" "subscriptions" "jobs"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors" "waymark10_job_leases"])

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
        ;; :probe-reads mirrors production's boot — which matters more
        ;; here than anywhere, because an undo door's availability is a
        ;; fact about the clock and about whose hand is asking, and the
        ;; render probe reads the same `:last-transition` hook the
        ;; enforcement does (advertisement equals enforcement)
        (let [eng (engine/engine {:storage st
                                  :resources (main/check-resources)
                                  :probe-reads true
                                  :suppress-mirror-refresh true})]
          (binding [*h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar (insight_rank_test's idiom) ───────────────────────

(defn- human [id] {"x-waymark-principal" id "x-waymark-actor-type" "human"})
(defn- agent' [id] {"x-waymark-principal" id "x-waymark-actor-type" "agent"})

(defn- req
  ([method uri headers] (req method uri nil headers))
  ([method uri body headers]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path :headers (or headers {})}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp)
                           (#(if (string? %) % (slurp %)))
                           wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(defn- invoke! [plural id action body headers]
  (req :post (str "/api/" plural "/" id "/-/" (name action))
       (or body {}) headers))

(defn- row [plural id headers] (json (req :get (str "/api/" plural "/" id) headers)))

(defn- state-of [plural id headers] (str (:state (row plural id headers))))

(defn- leash!
  "An agent HOLDING a scope, minted through the real grant door and
  accepted → the headers that present it."
  [id who scope]
  (let [hs (agent' id)
        made (req :post "/api/grants" {:audience id :scope scope} (human who))
        gid (id-of made)
        took (invoke! "grants" gid :accept nil hs)]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (assert (= 200 (:status took)) (pr-str (json took)))
    (assoc hs "x-waymark-grant" gid)))

(defn- publish!
  "One finding through its own create door: the offered row's address
  is its citation, and the offer is that row's own action. `extra`
  carries the evidence typing when a test wants the finding to be an
  ATOM of a belief."
  [finder text plural id action & [extra]]
  (let [self (str "/api/" plural "/" id)]
    (req :post "/api/insights"
         (merge {:finding text :evidence [self]
                 :offer_kind (subs plural 0 (dec (count plural)))
                 :offer_id id :offer_action action}
                extra)
         finder)))

(defn- task!
  "A row to point findings at — the lightest one this household has."
  [who title]
  (id-of (req :post "/api/tasks" {:title title} (human who))))

(defn- refused
  "The guard a 409 names, as a keyword, or nil for anything else. The
  wire spells it under :guard; a wrong-state refusal names none."
  [resp]
  (some-> (json resp) :guard keyword))

(defn- detail [resp] (str (:detail (json resp))))

;; ── the stack, one row deep ─────────────────────────────────────────

(deftest a-person-takes-a-finding-and-takes-the-take-back
  (let [who "colton-undo-1"
        other "iris-undo-1"
        finder (leash! "finder-undo-1" who [{:kind "insight" :actions ["create"]}])
        tid (task! who "Call the roofer")
        made (publish! finder "The roof call has sat three weeks" "tasks" tid
                       "complete")
        iid (id-of made)]
    (is (= 201 (:status made)) (pr-str (json made)))

    (testing "the tap lands and the row is answered"
      (let [took (invoke! "insights" iid :take nil (human who))]
        (is (= 200 (:status took)) (pr-str (json took)))
        (is (= "taken" (state-of "insights" iid (human who))))))

    (testing "the answered card advertises its own way back, to the hand
              that answered — advertisement equals enforcement, and the
              probe reads the same log the door does"
      (is (contains? (:actions (row "insights" iid (human who))) :undo)))

    (testing "…and not to anybody else's, which is four eyes in the
              other direction: a second person reversing an answer
              would be the house un-answering itself"
      (let [nope (invoke! "insights" iid :undo nil (human other))]
        (is (= 409 (:status nope)) (pr-str (json nope)))
        (is (= :only-your-own-last-tap (refused nope)))
        (is (str/includes? (detail nope) "not yours"))
        (is (str/includes? (detail nope) who)
            "the refusal names whose tap it actually was")
        (is (= "taken" (state-of "insights" iid (human who)))
            "a refused undo changes nothing")))

    (testing "the hand that tapped takes it back, and the finding is
              asking its question again"
      (let [back (invoke! "insights" iid :undo nil (human who))]
        (is (= 200 (:status back)) (pr-str (json back)))
        (is (= "published" (state-of "insights" iid (human who))))))

    (testing "NOTHING IS ERASED — the version advanced through the
              undo rather than rolling back, and both taps are in the
              row's own history"
      (let [doc (row "insights" iid (human who))
            hist (json (req :get (str "/api/insights/" iid "/-/history")
                            (human who)))
            ;; the log reads newest-first, which is the order a person
            ;; asking "what just happened here" wants
            actions (mapv (comp str :action) (:transitions (:data hist)))]
        (is (<= 3 (long (or (:version (:meta doc)) 0)))
            "create, take, undo — three writes, three versions")
        (is (= ["undo" "take" "create"] actions))))

    (testing "and there is no redo: the newest transition is now the
              undo, and no door takes an undo back — the stack is one
              deep per row, and a person manages it across rows"
      (let [again (invoke! "insights" iid :undo nil (human who))]
        (is (= 409 (:status again)) (pr-str (json again)))
        (is (= :out-of-state (or (refused again) :out-of-state)))))))

;; ── the walls re-judge ──────────────────────────────────────────────

(deftest an-undo-refused-names-the-row-now-in-the-way
  (let [who "colton-undo-2"
        finder (leash! "finder-undo-2" who [{:kind "insight" :actions ["create"]}])
        tid (task! who "Book the chimney sweep")
        first' (publish! finder "The sweep has not been booked since spring"
                         "tasks" tid "complete")
        iid (id-of first')]
    (is (= 201 (:status first')) (pr-str (json first')))

    (testing "while it stands, the house refuses a second finding
              asking the same question off the same reading"
      (let [twin (publish! finder "Still no sweep booked" "tasks" tid
                           "complete")]
        (is (= 409 (:status twin)))
        (is (= :one-live-finding-per-offer (refused twin)))))

    (testing "ANSWERING IS WHAT FREES THE QUESTION — so once the first
              is dismissed the same offer is open again"
      (is (= 200 (:status (invoke! "insights" iid :dismiss nil (human who)))))
      (is (= "dismissed" (state-of "insights" iid (human who))))
      (let [fresh (publish! finder "Still no sweep booked" "tasks" tid
                            "complete")]
        (is (= 201 (:status fresh)) (pr-str (json fresh)))

        (testing "…and now the undo cannot put the old one back,
                  because that would leave the house asking twice —
                  the wall re-judges at the door, and the refusal names
                  the standing finding in that wall's own voice"
          (let [back (invoke! "insights" iid :undo nil (human who))]
            (is (= 409 (:status back)) (pr-str (json back)))
            (is (= :the-question-is-open-again (refused back)))
            (is (str/includes? (detail back) (id-of fresh))
                "the refusal names the row now standing in the way")
            (is (= "dismissed" (state-of "insights" iid (human who)))
                "and the answered finding stays exactly as it is")))))))

;; ── the author's own withdrawal (waymark-br7v, partially healed) ────

(deftest an-author-takes-back-what-nobody-has-answered
  (let [who "colton-undo-3"
        ;; NO GRANT AT ALL. The own-surface courtesy is what makes a
        ;; compiler's work possible, and this is the door it was
        ;; missing: before waymark-qmo6 an author looking at its own
        ;; finding saw an EMPTY actions map, because the four-eyes wall
        ;; correctly refuses it both verdicts and there was nothing
        ;; else on the row to do.
        author (agent' "compiler-undo-3")
        tid (task! who "Order the stove part")
        made (publish! author "The stove part was never ordered" "tasks" tid
                       "complete")
        iid (id-of made)]
    (is (= 201 (:status made)) (pr-str (json made)))

    (testing "the author's own row offers it exactly one door, and it
              is the one that takes its own work back"
      (let [doc (row "insights" iid author)]
        (is (contains? (:actions doc) :withdraw))
        (is (not (contains? (:actions doc) :take))
            "the finder still does not decide")
        (is (not (contains? (:actions doc) :dismiss)))))

    (testing "a second agent cannot withdraw somebody else's finding —
              the own-surface courtesy is about YOUR rows"
      (let [nope (invoke! "insights" iid :withdraw nil (agent' "other-undo-3"))]
        (is (contains? #{404 409} (:status nope)) (pr-str (json nope)))
        (is (= "published" (state-of "insights" iid author)))))

    (testing "the author withdraws it, and `withdrawn` is NOT the
              household's no — nobody ever answered this one"
      (let [gone (invoke! "insights" iid :withdraw nil author)]
        (is (= 200 (:status gone)) (pr-str (json gone)))
        (is (= "withdrawn" (state-of "insights" iid author)))))

    (testing "and the question it was asking is free again, which a
              dismissal would also have done — but a withdrawal never
              weighs on the rank as a household verdict"
      (let [fresh (publish! author "The stove part was never ordered"
                            "tasks" tid "complete")]
        (is (= 201 (:status fresh)) (pr-str (json fresh)))))

    (testing "a tombstone is a tomb: no door out"
      (let [back (invoke! "insights" iid :undo nil author)]
        (is (contains? #{404 409} (:status back)))
        (is (= "withdrawn" (state-of "insights" iid author)))))))

;; ── the fold follows the undo (waymark-2ozr's seam, unedited) ───────

(deftest the-belief-follows-the-finding-back
  (let [who "colton-undo-4"
        reader (leash! "reader-undo-4" who
                       [{:kind "hypothesis" :actions ["create"]}
                        {:kind "insight" :actions ["create"]}])
        tid (task! who "Sand the workbench")
        subject (str "/api/tasks/" tid)
        belief (req :post "/api/hypotheses"
                    {:claim "This house finishes what it starts in the shop"
                     :shape "pattern"
                     :about [subject]
                     :prior 0.1M}
                    reader)
        hid (id-of belief)
        atoms-of (fn [] (long (or (:atom_count (:data (row "hypotheses" hid
                                                           (human who))))
                                  0)))]
    (is (= 201 (:status belief)) (pr-str (json belief)))
    (is (= 0 (atoms-of)) "nothing has fed it yet")

    (let [made (publish! reader "Jack spent Saturday on the workbench"
                         "tasks" tid "complete"
                         {:evidence_type "costly_action" :cost "high"
                          :episode "thread/undo-4 2026-08-31"})
          iid (id-of made)]
      (is (= 201 (:status made)) (pr-str (json made)))

      (testing "the atom's own write refolds the belief it feeds — the
                `:maintain` seam, unchanged by this bead"
        (is (= 1 (atoms-of))))

      (testing "the house saying no takes the atom back OUT of the fold"
        (is (= 200 (:status (invoke! "insights" iid :dismiss nil (human who)))))
        (is (= 0 (atoms-of))))

      (testing "AND THE UNDO PUTS IT BACK, with no new wiring at all:
                the seam keys on the KIND, not the action, so `undo` is
                a committed write on an insight exactly as `dismiss` is"
        (is (= 200 (:status (invoke! "insights" iid :undo nil (human who)))))
        (is (= "published" (state-of "insights" iid (human who))))
        (is (= 1 (atoms-of)))))))

;; ── un-affirming takes the stamp back with the state ────────────────

(deftest un-affirming-a-belief-unclaims-it
  (let [who "colton-undo-5"
        other "iris-undo-5"
        reader (leash! "reader-undo-5" who
                       [{:kind "hypothesis" :actions ["create"]}])
        pid (task! who "Plan the darkroom weekend")
        belief (req :post "/api/hypotheses"
                    {:claim "Iris wants a darkroom more than she says"
                     :shape "interest"
                     :about [(str "/api/tasks/" pid)]
                     :prior 0.1M}
                    reader)
        hid (id-of belief)]
    (is (= 201 (:status belief)) (pr-str (json belief)))

    (testing "a person's tap makes the belief the house's, and stamps
              their name and the day on it"
      (is (= 200 (:status (invoke! "hypotheses" hid :still_stands nil
                                   (human who)))))
      (let [d (:data (row "hypotheses" hid (human who)))]
        (is (= "affirmed" (state-of "hypotheses" hid (human who))))
        (is (= who (str (:affirmed_by d))))))

    (testing "the observer cannot take the household's answer back —
              four eyes hold in this direction too, and no grant opens
              the `:own-field` arm"
      (let [nope (invoke! "hypotheses" hid :undo nil reader)]
        (is (= 409 (:status nope)) (pr-str (json nope)))
        (is (= :the-answer-is-a-persons (refused nope)))))

    (testing "…and neither can a second person"
      (let [nope (invoke! "hypotheses" hid :undo nil (human other))]
        (is (= 409 (:status nope)) (pr-str (json nope)))
        (is (= :only-your-own-last-tap (refused nope)))))

    (testing "the hand that answered takes it back, and THE STAMP GOES
              WITH THE STATE — a row reading `observed` while still
              carrying \"affirmed by colton\" would have the summary
              line and the document disagreeing about the one thing
              this kind exists to keep straight"
      (is (= 200 (:status (invoke! "hypotheses" hid :undo nil (human who)))))
      (let [doc (row "hypotheses" hid (human who))]
        (is (= "observed" (str (:state doc))))
        (is (str/blank? (str (:affirmed_by (:data doc)))))
        (is (str/blank? (str (:affirmed_at (:data doc)))))))

    (testing "and the log keeps every word of it"
      (let [hist (json (req :get (str "/api/hypotheses/" hid "/-/history")
                            (human who)))]
        (is (= ["undo" "still_stands" "create"]
               (mapv (comp str :action)
                     (:transitions (:data hist)))))))))
