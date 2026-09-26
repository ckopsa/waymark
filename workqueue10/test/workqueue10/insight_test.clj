(ns workqueue10.insight-test
  "The insight's door, over the real ring handler: the claims its
  declaration-time scenarios cannot make, because each one is about
  what ANOTHER row already holds or what the engine writes at birth.

  These deftests lived in `workqueue10.insight-rank-test` beside the
  feed's rank on the insights line. The feed was retired 2026-09 and
  the rank with it; the insight stands on its own, and so do these
  three claims about its door — one live finding per offer
  (waymark-1ag), the derived offer address (waymark-42m), and the
  typed evidence fields (waymark-2m2).

  Needs a Postgres database; WAYMARK10_TEST_DSN names it.
  Run: cd workqueue10 && clojure -M:test --focus workqueue10.insight-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.belief :as belief]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]
            [workqueue10.main :as main]))

(def ^:private tables
  ;; THE WHOLE FOLDED REGISTRY'S TABLES: this engine boots every kind
  ;; main/check-resources declares plus what the module table enrols,
  ;; so a fixture that dropped only its own would boot into whatever
  ;; shape another suite left behind.
  ["values" "people" "hypotheses" "inbox_items"
   "tasks" "task_lists" "media" "chores" "chore_runs" "days"
   "meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "contexts" "day_plans" "blocks" "spans" "decisions"
   "letters" "selves" "journals" "insights"
   "saved_views" "dashboards" "dashboard_slots"
   "connections" "capabilities"
   "members" "roles" "grants" "approval_requests"
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
        ;; :probe-reads mirrors production's boot, so the citation
        ;; walls that consult the registry answer honestly in the
        ;; envelope; :suppress-mirror-refresh keeps the reads pure
        (let [eng (engine/engine {:storage st
                                  :resources (main/check-resources)
                                  :probe-reads true
                                  :suppress-mirror-refresh true})]
          (binding [*h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(defn- human [id] {"x-waymark-principal" id "x-waymark-actor-type" "human"})

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

(defn- leash!
  "An agent HOLDING a scope, minted through the real grant door and
  accepted → the headers that present it."
  [id who scope]
  (let [hs {"x-waymark-principal" id "x-waymark-actor-type" "agent"}
        made (req :post "/api/grants" {:audience id :scope scope} (human who))
        gid (id-of made)
        took (invoke! "grants" gid :accept nil hs)]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (assert (= 200 (:status took)) (pr-str (json took)))
    (assoc hs "x-waymark-grant" gid)))

(defn- publish!
  "One finding through its own create door, as the finder: the
  offered row's address is its citation, and the offer is that row's
  own action."
  [finder text kind id action]
  (let [self (str "/api/" kind "/" id)]
    (req :post "/api/insights"
         {:finding text :evidence [self]
          :offer_kind (subs kind 0 (dec (count kind)))
          :offer_id id :offer_action action :offer_href self}
         finder)))

;; ── one live finding per offer (waymark-1ag) ────────────────────────
;;
;; `not-a-twin`'s law one kind over, and only a live engine can judge
;; it: the wall's whole question is what ANOTHER row already offers,
;; and a declaration-time scenario holds one literal input over an
;; empty store. The last three claims are the ones that keep this a
;; law rather than a cap: a different next step on the same row was
;; never the same question, the same next step read off a different
;; row was never the same question either, and a dismissed prior
;; admits a fresh finding.

(deftest a-second-live-finding-on-one-offer-is-refused-and-the-refusal-names-it
  (let [who "colton-1ag"
        finder (leash! "finder-1ag-a" who [{:kind "insight" :actions ["create"]}])
        other (leash! "finder-1ag-b" who [{:kind "insight" :actions ["create"]}])
        tid (id-of (req :post "/api/tasks" {:title "Clear the side gate"}
                        (human who)))
        elsewhere (id-of (req :post "/api/tasks" {:title "Restock the salt"}
                              (human who)))
        act "complete"
        first' (publish! finder "The side gate has been blocked since the delivery"
                         "tasks" tid act)
        fid (id-of first')]
    (is (= 201 (:status first')) (pr-str (json first')))

    (testing "a second finding offering the same action on the same row — from
              ANOTHER author, because the question belongs to the house and not
              to whoever asked it — is refused, naming the live finding"
      (let [r (publish! other "The side gate, still blocked, said differently"
                        "tasks" tid act)
            says (str (json r))]
        (is (= 409 (:status r)) says)
        (is (= "one-live-finding-per-offer" (str (:guard (json r)))) says)
        (is (str/includes? says (str "/api/insights/" fid))
            "the refusal carries the live finding's own address")
        (is (str/includes? says (str "/api/tasks/" tid))
            "…and the address of the row the question is about")
        (is (str/includes? says "one question at a time")
            "and it says out loud that it is not a cap")))

    (testing "the first is untouched — a refusal at the door changes nothing"
      (is (= "published"
             (str (:state (json (req :get (str "/api/insights/" fid) finder)))))))

    (testing "a different next step on the same row is a different question"
      (let [r (publish! finder "The side gate's rank is doing nothing for anyone"
                        "tasks" tid "deprioritize")]
        (is (= 201 (:status r)) (pr-str (json r)))
        (is (= 200 (:status (invoke! "insights" (id-of r) :dismiss nil
                                     (human who)))))))

    (testing "the same next step on a different row likewise"
      (let [r (publish! finder "The salt bin has been empty for two storms"
                        "tasks" elsewhere act)]
        (is (= 201 (:status r)) (pr-str (json r)))
        (is (= 200 (:status (invoke! "insights" (id-of r) :dismiss nil
                                     (human who)))))))

    (testing "and the same next step read off a DIFFERENT row is a different
              question"
      (let [r (req :post "/api/insights"
                   {:finding "The side gate came up again from somewhere else"
                    :evidence [(str "/api/tasks/" elsewhere)]
                    :offer_kind "task" :offer_id tid :offer_action act}
                   finder)]
        (is (= 201 (:status r)) (pr-str (json r)))
        (is (= 200 (:status (invoke! "insights" (id-of r) :dismiss nil
                                     (human who)))))))

    (testing "and a DISMISSED prior blocks nothing: the wall is about the live one"
      (let [no (invoke! "insights" fid :dismiss nil (human who))
            again (publish! finder "The side gate is still blocked, a week on"
                            "tasks" tid act)]
        (is (= 200 (:status no)) (pr-str (json no)))
        (is (= 201 (:status again)) (pr-str (json again)))
        ;; leave the house as found
        (is (= 200 (:status (invoke! "insights" (id-of again) :dismiss nil
                                     (human who)))))))))

(deftest the-offers-address-is-derived-and-still-checked
  ;; waymark-42m: the pair the finder already names IS the address, and
  ;; the engine writes it at birth. What the wall still catches is an
  ;; author naming one row and linking another — and the sentence
  ;; names where the row actually lives.
  (let [who "colton-42m"
        finder (leash! "finder-42m" who [{:kind "insight" :actions ["create"]}])
        tid (id-of (req :post "/api/tasks" {:title "Take the recycling out"}
                        (human who)))
        self (str "/api/tasks/" tid)
        bare (req :post "/api/insights"
                  {:finding "The recycling has waited past two collection days"
                   :evidence [self]
                   :offer_kind "task" :offer_id tid :offer_action "complete"}
                  finder)]
    (testing "no address is asked for — the kind and the id are the address"
      (is (= 201 (:status bare)) (pr-str (json bare)))
      (let [seen (json (req :get (str "/api/insights/" (id-of bare))
                            (human who)))]
        (is (= self (get-in seen [:data :offer_href]))
            "the engine wrote it at birth from the pair the finder named")
        (is (= (str "/#" self) (get-in seen [:links :offer :href]))
            "and the row can reach the offer it carries")))

    (testing "an address the author DOES spell must be the row's own"
      (let [crossed (req :post "/api/insights"
                         {:finding "The recycling has waited past two collection days"
                          :evidence [self]
                          :offer_kind "task" :offer_id tid :offer_action "complete"
                          :offer_href (str "/api/values/" tid)}
                         finder)
            says (str (json crossed))]
        (is (= 409 (:status crossed)) says)
        (is (str/includes? says "is not where that") says)
        (is (str/includes? says self)
            "the refusal names the address the row actually lives at")))

    (testing "a door that takes typing is not an offer, however natural it reads"
      (let [heavy (req :post "/api/insights"
                       {:finding "The recycling sits unranked at the tail of the queue"
                        :evidence [self]
                        :offer_kind "task" :offer_id tid :offer_action "prioritize"}
                       finder)
            says (str (json heavy))]
        (is (= 409 (:status heavy)) says)
        (is (str/includes? says "recall") says)
        (is (str/includes? says "a card offers a decision, never a form") says)))

    ;; leave the house as found
    (is (= 200 (:status (invoke! "insights" (id-of bare) :dismiss nil
                                 (human who)))))))

(deftest typed-evidence-lands-and-untyped-stays-lawful
  ;; waymark-2m2. The four fields — `evidence_type`, `solicited`,
  ;; `cost`, `episode` — are every one of them optional: an untyped
  ;; finding is exactly as lawful as it ever was, and weighs a
  ;; likelihood ratio of 1, which is silence. What IS refused is a
  ;; word the enum does not know, by the SCHEMA at 422.
  (let [who "colton-2m2"
        finder (leash! "finder-2m2" who [{:kind "insight" :actions ["create"]}])
        tid (id-of (req :post "/api/tasks" {:title "Ask about the darkroom"}
                        (human who)))
        self (str "/api/tasks/" tid)
        typed (req :post "/api/insights"
                   {:finding "Iris asked twice how the enlarger works, unprompted"
                    :evidence [self]
                    :offer_kind "task" :offer_id tid :offer_action "complete"
                    :evidence_type "unprompted_mention"
                    :solicited false
                    :cost "none"
                    :episode "thread/7fda11c6 2026-08-24"}
                   finder)]

    (testing "a typed finding lands, and reads back with all four words on it"
      (is (= 201 (:status typed)) (pr-str (json typed)))
      (let [seen (json (req :get (str "/api/insights/" (id-of typed))
                            (human who)))]
        (is (= "unprompted_mention" (str (get-in seen [:data :evidence_type]))))
        (is (false? (get-in seen [:data :solicited]))
            "the boolean survives the round trip as a boolean")
        (is (= "none" (str (get-in seen [:data :cost]))))
        (is (= "thread/7fda11c6 2026-08-24" (str (get-in seen [:data :episode]))))
        (is (= "published" (str (:state seen)))
            "typing a fact is not a second state machine")))

    (testing "a word the enum does not know is refused at the door, by the schema"
      (let [bad (req :post "/api/insights"
                     {:finding "Iris seemed keen about the darkroom"
                      :evidence [self]
                      :offer_kind "task" :offer_id tid :offer_action "complete"
                      :evidence_type "seemed_keen"
                      :episode "thread/7fda11c6 2026-08-24"}
                     finder)
            says (str (json bad))]
        (is (= 422 (:status bad)) says)
        (is (str/includes? says "evidence_type") says)))

    (testing "an untyped finding is exactly as lawful as it ever was"
      ;; a DIFFERENT next step, because `one-live-finding-per-offer`
      ;; would otherwise fold this into the typed one above
      (let [plain (req :post "/api/insights"
                       {:finding "The darkroom task has waited since the spring"
                        :evidence [self]
                        :offer_kind "task" :offer_id tid
                        :offer_action "deprioritize"}
                       finder)]
        (is (= 201 (:status plain)) (pr-str (json plain)))
        (let [seen (json (req :get (str "/api/insights/" (id-of plain))
                              (human who)))]
          (is (nil? (get-in seen [:data :evidence_type]))
              "no word on it, and the house took it anyway — LR 1 is silence")
          (is (nil? (get-in seen [:data :episode]))))
        (is (= 200 (:status (invoke! "insights" (id-of plain) :dismiss nil
                                     (human who)))))))

    (testing "the table those words are priced by is the belief layer's own"
      (let [lr belief/default-evidence-lr]
        (is (= 24 (count lr)))
        (is (= 24 (count belief/evidence-lr-keys)))
        (is (= 20 (long (:costly_action_high lr))))
        (is (= 8 (long (:unprompted_mention lr))))
        (is (= 0.25 (double (:solicited_discount lr))))
        (is (nil? (belief/evidence-lr-problems lr))
            "the defaults pass their own bounds")
        (is (seq (belief/evidence-lr-problems {:log_odds_clamp 0}))
            "and a clamp of 0 does not")))

    ;; leave the house as found
    (is (= 200 (:status (invoke! "insights" (id-of typed) :dismiss nil
                                 (human who)))))))
