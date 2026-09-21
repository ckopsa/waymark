(ns waymark10.judgment-test
  "The judge declared as a row (waymark-fp62.11 R-1).

  What is worth proving here is the thing a declaration-time battery
  cannot say: a judgment is authored at RUN time by a person, so the
  four checks that decide whether it can project run at a DOOR and
  answer in the household's own words. `waymark10.checks` refuses a
  bad declaration at boot; `promote` refuses a bad judgment at a tap,
  and the caller reads which of the four it was.

  The in-memory twin hosts it, `recipe_proposal_test`'s reason
  exactly: every read here goes through the storage protocol, so the
  twin is the proof that the surface is portable — and the suite pays
  no database for a law that is about declarations."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.store.memory :as memory]
            [waymark10.wire :as wire]))

;; ── a subject kind to judge ─────────────────────────────────────────

(def ticket
  "Something with a filterable field, a door, and nothing else. The
  queue and the consequence are judged against a REAL declaration, so
  the test needs one that is not the framework's own."
  (r/resource
   {:kind :jt_ticket
    :plural "jt_tickets"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema [:map [:title {:filter #{:eq}} [:string {:min 1 :max 60}]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Closing is acknowledged."}
             :display {:label "Close" :order 1
                       :description "Close this ticket"}}}}))

;; ── the wire ────────────────────────────────────────────────────────

(def ^:private as-mom
  {"x-waymark-principal" "mom" "x-waymark-actor-type" "human"})

(defn- call!
  [eng method uri & {:keys [body headers]}]
  (let [resp ((engine/handler eng)
              (cond-> {:request-method method :uri uri
                       :headers (merge {"content-type" "application/json"}
                                       as-mom headers)}
                body (assoc :body (wire/write-json body))))]
    (assoc resp :doc (some-> (:body resp) wire/read-json))))

(defn- boot []
  (engine/engine {:storage (memory/storage) :resources [ticket]}))

(defn- id-of [self] (last (str/split (str self) #"/")))

(defn- guard-of [resp] (some-> (:doc resp) :guard keyword))
(defn- detail-of [resp] (str (some-> (:doc resp) :detail)))

(def ^:private words
  [{:name "infra" :sentence "The runner failed, not the change."}
   {:name "this_change" :sentence "The change itself broke the build."}])

(defn- draft!
  "A judgment row at draft, over whatever the caller varies."
  [eng overrides]
  (call! eng :post "/api/judgments"
         :body (merge {:name "Why did the build go red"
                       :subject_kind "jt_ticket"
                       :queue {:state "open"}
                       :verdicts words}
                      overrides)))

(defn- promote! [eng jid]
  (call! eng :post (str "/api/judgments/" jid "/-/promote")))

(defn- refusal
  "The guard that refused a promote of a judgment spelled this way."
  [overrides]
  (let [eng (boot)
        made (draft! eng overrides)]
    (if (not= 201 (:status made))
      {:status (:status made) :doc (:doc made)}
      (promote! eng (id-of (get-in made [:doc :self]))))))

;; ── 1. the happy road ───────────────────────────────────────────────

(deftest a-judgment-is-born-a-draft-and-promoted
  (let [eng (boot)
        made (draft! eng {:consequence "close" :notes "The first judge."})
        jid (id-of (get-in made [:doc :self]))]
    (is (= 201 (:status made)))
    (is (= "draft" (str (get-in made [:doc :state])))
        "a judgment starts where a definition starts")
    (testing "the defaults the author did not spell"
      (is (= 240 (get-in made [:doc :data :remedy_max]))
          "the remedy ceiling fills itself")
      (is (= {:state "open"} (get-in made [:doc :data :queue]))))
    (testing "promote puts it in force"
      (let [up (promote! eng jid)]
        (is (= 200 (:status up)))
        (is (= "promoted" (str (get-in up [:doc :state]))))))
    (testing "and there is no way back to draft — a judgment that turned
              out wrong is superseded, and both rows stay"
      (is (= 409 (:status (call! eng :post
                                 (str "/api/judgments/" jid "/-/revise")
                                 :body {:name "Second thoughts"
                                        :subject_kind "jt_ticket"
                                        :verdicts words})))
          "revise departs draft and only draft"))))

(deftest supersede-retires-a-judgment-and-may-name-its-heir
  (let [eng (boot)
        first' (id-of (get-in (draft! eng {}) [:doc :self]))
        heir (id-of (get-in (draft! eng {:name "The judge after it"})
                            [:doc :self]))]
    (promote! eng first')
    (let [out (call! eng :post (str "/api/judgments/" first' "/-/supersede")
                     :body {:successor heir})]
      (is (= 200 (:status out)))
      (is (= "superseded" (str (get-in out [:doc :state]))))
      (is (= heir (str (get-in out [:doc :data :successor])))))
    (testing "the row stays: the verdicts written under it are still rows
              about rows, and a ledger whose judgment vanished is a
              ledger of orphans"
      (is (= 200 (:status (call! eng :get (str "/api/judgments/" first'))))))))

;; ── 2. the four walls, each in its own words ────────────────────────

(deftest promote-refuses-a-judgment-that-cannot-project
  (testing "a subject kind this engine does not serve"
    (let [out (refusal {:subject_kind "no_such_kind" :queue {}})]
      (is (= 409 (:status out)))
      (is (= :subject-kind-is-served (guard-of out)))
      (is (str/includes? (detail-of out) "no_such_kind"))
      (is (str/includes? (detail-of out) "a queue that can never fill"))))

  (testing "two verdicts spelled the same way"
    (let [out (refusal {:verdicts [{:name "infra" :sentence "One."}
                                   {:name "infra" :sentence "The other."}]})]
      (is (= 409 (:status out)))
      (is (= :verdict-names-are-distinct (guard-of out)))
      (is (str/includes? (detail-of out) "infra"))))

  (testing "a queue key the subject kind does not filter by equality"
    (let [out (refusal {:queue {:owner "mom"}})]
      (is (= 409 (:status out)))
      (is (= :queue-names-filterable-fields (guard-of out)))
      (is (str/includes? (detail-of out) "owner"))
      (is (str/includes? (detail-of out) "title")
          "and the refusal names the fields that ARE filterable")))

  (testing "a consequence that is no door of the subject kind"
    (let [out (refusal {:consequence "reopen"})]
      (is (= 409 (:status out)))
      (is (= :consequence-is-a-door (guard-of out)))
      (is (str/includes? (detail-of out) "reopen"))
      (is (str/includes? (detail-of out) "close")
          "and the refusal names the doors that kind has")))

  (testing "each of them names revise as the way on"
    (doseq [out [(refusal {:subject_kind "no_such_kind" :queue {}})
                 (refusal {:verdicts [{:name "a" :sentence "One."}
                                      {:name "a" :sentence "Two."}]})
                 (refusal {:queue {:owner "mom"}})
                 (refusal {:consequence "reopen"})]]
      (is (= ["judgment.revise"] (mapv str (:remedies (:doc out))))))))

(deftest a-failed-judgment-stays-a-draft-and-revise-is-the-way-on
  (let [eng (boot)
        made (draft! eng {:consequence "reopen"})
        jid (id-of (get-in made [:doc :self]))]
    (is (= 409 (:status (promote! eng jid))))
    (is (= "draft" (str (get-in (call! eng :get (str "/api/judgments/" jid))
                                [:doc :state])))
        "a judgment that fails a check does not project — and does not move")
    (let [fixed (call! eng :post (str "/api/judgments/" jid "/-/revise")
                       ;; an :edit wears the fence — the etag says which
                       ;; version this rewrite read, and a refused promote
                       ;; moved nothing, so it is still the first
                       :headers {"if-match" (str "W/\"judgment-" jid "-v1\"")}
                       :body {:name "Why did the build go red"
                              :subject_kind "jt_ticket"
                              :queue {:state "open"}
                              :verdicts words
                              :consequence "close"})]
      (is (= 200 (:status fixed)))
      (is (= "close" (str (get-in fixed [:doc :data :consequence])))))
    (is (= 200 (:status (promote! eng jid))))))

(deftest revise-restates-the-whole-row
  (let [eng (boot)
        made (draft! eng {:consequence "close" :notes "The first words."})
        jid (id-of (get-in made [:doc :self]))
        again (call! eng :post (str "/api/judgments/" jid "/-/revise")
                     :headers {"if-match" (str "W/\"judgment-" jid "-v1\"")}
                     :body {:name "Why did the build go red"
                            :subject_kind "jt_ticket"
                            :verdicts [{:name "flake"
                                        :sentence "It passes on a re-run."}]})]
    (is (= 200 (:status again)))
    (is (= ["flake"] (mapv #(str (:name %))
                           (get-in again [:doc :data :verdicts]))))
    (testing "an omitted optional CLEARS — a judgment read half-changed
              is a judgment nobody can act on"
      (is (nil? (get-in again [:doc :data :consequence])))
      (is (nil? (get-in again [:doc :data :notes]))))))

;; ── 3. R-6 and R-7 ──────────────────────────────────────────────────

(deftest two-judgments-may-name-one-subject-kind
  (let [eng (boot)
        a (draft! eng {:name "Why did the build go red"})
        b (draft! eng {:name "Did the ticket say what it needed"
                       :verdicts [{:name "clear" :sentence "It said enough."}
                                  {:name "thin" :sentence "It did not."}]})]
    (is (= 201 (:status a)))
    (is (= 201 (:status b)))
    (is (= 200 (:status (promote! eng (id-of (get-in a [:doc :self]))))))
    (is (= 200 (:status (promote! eng (id-of (get-in b [:doc :self]))))))))

(deftest a-judgment-may-name-the-engines-own-rows
  ;; R-7: `subject_kind` is a kind TOKEN, never a ref, so a judge on
  ;; how an agent SAT is the same declaration as a judge on a build.
  (let [eng (boot)
        made (draft! eng {:name "Did the seat do what it was asked"
                          :subject_kind "sitting"
                          :queue {}
                          :verdicts [{:name "did_the_work"
                                      :sentence "It walked the queue it was woken for."}
                                     {:name "wandered"
                                      :sentence "It spent the wake elsewhere."}]})
        up (promote! eng (id-of (get-in made [:doc :self])))]
    (is (= 201 (:status made)))
    (is (= 200 (:status up))
        "sitting is a kind every waymark engine serves, so a judgment
         about one projects")))
