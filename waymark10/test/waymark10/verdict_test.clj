(ns waymark10.verdict-test
  "A row about a row (waymark-fp62.11 R-2, R-3).

  The kind declares ONE scenario — the one that writes nothing — for
  the reason spelled in `waymark10.verdict`'s own comment: a
  conformance-tier scenario staging `:given` rows leaves fixed-name
  judgments behind in whatever database the shard is holding, and a
  judgment is `:unique` by name. Everything else that kind promises is
  proved here instead, where the fixture owns its world: a seat judges
  once, a second judge is refused, only a person corrects, and the
  correction overrules the first WITHOUT deleting it.

  The in-memory twin hosts it (`recipe_proposal_test`'s reason: every
  read goes through the storage protocol). The one law it cannot show
  is the unique index, which is Postgres's and lives in
  `unique_index_test`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.store.memory :as memory]
            [waymark10.wire :as wire]))

;; ── the world: something to judge ───────────────────────────────────

(def ticket
  (r/resource
   {:kind :vt_ticket
    :plural "vt_tickets"
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

(def ^:private seat-id "seat-ari")

(defn- call!
  [eng method uri & {:keys [body headers]}]
  (let [resp ((engine/handler eng)
              (cond-> {:request-method method :uri uri
                       :headers (merge {"content-type" "application/json"}
                                       as-mom headers)}
                body (assoc :body (wire/write-json body))))]
    (assoc resp :doc (some-> (:body resp) wire/read-json))))

(defn- id-of [self] (last (str/split (str self) #"/")))
(defn- guard-of [resp] (some-> (:doc resp) :guard keyword))
(defn- detail-of [resp] (str (some-> (:doc resp) :detail)))

(defn- leash!
  "One grant over `verdict.judge`, offered to the seat's agent and
  accepted by it → the headers that present it. An UNLEASHED agent is
  already answered 404 by the router's default deny, which proves
  nothing about any wall — so every claim below about what an agent
  may not do is made by an agent holding exactly the leash a household
  would approve for a judging seat."
  [eng audience]
  (let [made (call! eng :post "/api/grants"
                    :body {:audience audience
                           :scope [{:kind "verdict" :actions ["judge"]}]})
        gid (id-of (get-in made [:doc :self]))
        hs {"x-waymark-principal" audience "x-waymark-actor-type" "agent"}]
    (call! eng :post (str "/api/grants/" gid "/-/accept") :headers hs)
    (assoc hs "x-waymark-grant" gid)))

(def ^:private words
  [{:name "infra" :sentence "The runner failed, not the change."}
   {:name "this_change" :sentence "The change itself broke the build."}])

(defn- judgment!
  "A promoted judgment over vt_ticket, named as the caller likes."
  [eng nm & {:keys [promote? verdicts] :or {promote? true verdicts words}}]
  (let [made (call! eng :post "/api/judgments"
                    :body {:name nm
                           :subject_kind "vt_ticket"
                           :queue {:state "open"}
                           :verdicts verdicts})
        jid (id-of (get-in made [:doc :self]))]
    (when promote?
      (call! eng :post (str "/api/judgments/" jid "/-/promote")))
    jid))

(defn- ticket! [eng title]
  (id-of (get-in (call! eng :post "/api/vt_tickets" :body {:title title})
                 [:doc :self])))

(defn- judge!
  [eng hs body]
  (call! eng :post "/api/verdicts" :headers hs :body body))

(defn- world
  "An engine, a promoted judgment, a ticket to judge, and a leashed
  seat to judge it."
  []
  (let [eng (engine/engine {:storage (memory/storage) :resources [ticket]})]
    {:eng eng
     :judgment (judgment! eng "Why did the build go red")
     :ticket (ticket! eng "The nightly build went red")
     :seat (leash! eng seat-id)}))

(defn- verdict-body [{:keys [judgment ticket]} & {:as overrides}]
  (merge {:judgment judgment
          :subject_kind "vt_ticket"
          :subject_id ticket
          :verdict "infra"
          :remedy "Re-run the job on a clean runner."}
         overrides))

;; ── 1. a seat judges, once ──────────────────────────────────────────

(deftest a-seat-judges-through-the-judge-door
  (let [{:keys [eng seat] :as w} (world)
        said (judge! eng seat (verdict-body w))]
    (is (= 201 (:status said)))
    (is (= "said" (str (get-in said [:doc :state]))))
    (testing "the engine stamps who said it — no body names a sayer"
      (is (= seat-id (str (get-in said [:doc :data :said_by])))))
    (testing "and a body that tried to would be a stray key, because
              said_by is not a field this door offers"
      (is (= 422 (:status (judge! eng seat
                                  (verdict-body w :said_by "mom"
                                                :subject_id (ticket! eng "Another")))))))))

(deftest a-second-judge-on-the-same-subject-is-refused
  (let [{:keys [eng seat] :as w} (world)]
    (is (= 201 (:status (judge! eng seat (verdict-body w)))))
    (let [again (judge! eng seat (verdict-body w :verdict "this_change"
                                                :remedy "Fix the change."))]
      (is (= 409 (:status again)))
      (is (= :one-standing-verdict-per-subject (guard-of again)))
      (is (str/includes? (detail-of again) "already been answered")))
    (testing "…and the first answer still stands"
      (let [page (call! eng :get "/api/verdicts?state=said")]
        (is (= 1 (get-in page [:doc :data :total])))))))

;; ── 2. the judgment decides the vocabulary ──────────────────────────

(deftest a-word-the-judgment-does-not-name-is-refused
  (let [{:keys [eng seat] :as w} (world)
        out (judge! eng seat (verdict-body w :verdict "flaky"))]
    (is (= 409 (:status out)))
    (is (= :verdict-is-in-the-vocabulary (guard-of out)))
    (testing "and the refusal carries the whole vocabulary, so nobody
              guesses twice"
      (is (str/includes? (detail-of out) "infra"))
      (is (str/includes? (detail-of out) "this_change")))))

(deftest a-remedy-over-the-ceiling-is-refused
  (let [{:keys [eng seat] :as w} (world)
        out (judge! eng seat (verdict-body w :remedy (apply str (repeat 300 "x"))))]
    (is (= 409 (:status out)))
    (is (= :remedy-within-the-ceiling (guard-of out)))
    (is (str/includes? (detail-of out) "240"))))

(deftest a-verdict-under-a-judgment-not-in-force-is-refused
  (let [eng (engine/engine {:storage (memory/storage) :resources [ticket]})
        w {:eng eng
           :judgment (judgment! eng "Still being written" :promote? false)
           :ticket (ticket! eng "A ticket")
           :seat (leash! eng seat-id)}
        out (judge! eng (:seat w) (verdict-body w))]
    (is (= 409 (:status out)))
    (is (= :judgment-is-promoted (guard-of out)))
    (is (str/includes? (detail-of out) "draft"))
    (is (= ["judgment.promote"] (mapv str (:remedies (:doc out)))))))

(deftest a-verdict-names-the-judgments-own-kind-and-a-row-that-stands
  (let [{:keys [eng seat] :as w} (world)]
    (testing "another kind is another judgment's business"
      (let [out (judge! eng seat (verdict-body w :subject_kind "sitting"))]
        (is (= 409 (:status out)))
        (is (= :subject-kind-matches (guard-of out)))))
    (testing "a row that is not there"
      (let [out (judge! eng seat
                        (verdict-body w :subject_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"))]
        (is (= 409 (:status out)))
        (is (= :subject-is-a-row (guard-of out)))))))

;; ── 3. the correction (R-3) ─────────────────────────────────────────

(deftest a-person-corrects-and-the-first-row-stays
  (let [{:keys [eng seat judgment] :as w} (world)
        said (judge! eng seat (verdict-body w))
        vid (id-of (get-in said [:doc :self]))
        fix (judge! eng nil (verdict-body w :verdict "this_change"
                                          :remedy "The change dropped a migration."
                                          :corrects vid))]
    (is (= 201 (:status fix)))
    (is (= "mom" (str (get-in fix [:doc :data :said_by]))))
    (testing "the first verdict is overruled — by the engine's own hand,
              inside the correction's own transaction"
      (let [first' (call! eng :get (str "/api/verdicts/" vid))]
        (is (= 200 (:status first')))
        (is (= "overruled" (str (get-in first' [:doc :state]))))))
    (testing "and the first ROW stays: a record that could be edited
              away is not a record of what anybody judged"
      (let [page (call! eng :get "/api/verdicts")]
        (is (= 2 (get-in page [:doc :data :total])))))
    (testing "exactly one answer stands"
      (let [page (call! eng :get "/api/verdicts?state=said")]
        (is (= 1 (get-in page [:doc :data :total])))
        (is (= "this_change"
               (str (get-in page [:doc :data :items 0 :data :verdict]))))))
    (testing "the ledger reads back: corrections per seat, per judgment —
              which is the measurement of the seat"
      (let [page (call! eng :get (str "/api/verdicts?judgment=" judgment
                                      "&said_by=" seat-id
                                      "&state=overruled"))]
        (is (= 1 (get-in page [:doc :data :total])))))))

(deftest an-agent-does-not-correct
  (let [{:keys [eng seat] :as w} (world)
        said (judge! eng seat (verdict-body w))
        vid (id-of (get-in said [:doc :self]))
        other (leash! eng "seat-bo")
        out (judge! eng other (verdict-body w :verdict "this_change"
                                            :remedy "I disagree."
                                            :corrects vid))]
    (is (= 409 (:status out)))
    (is (= :a-person-corrects (guard-of out)))
    (is (str/includes? (detail-of out) "a person's answer"))
    (testing "and the first verdict still stands — nothing was written"
      (is (= "said" (str (get-in (call! eng :get (str "/api/verdicts/" vid))
                                 [:doc :state])))))))

(deftest a-correction-cites-the-answer-that-stands
  (let [{:keys [eng seat] :as w} (world)
        elsewhere (ticket! eng "A different ticket")
        said (judge! eng seat (verdict-body w))
        vid (id-of (get-in said [:doc :self]))
        stray (judge! eng seat (verdict-body w :subject_id elsewhere))
        stray-id (id-of (get-in stray [:doc :self]))]
    (is (= 201 (:status stray)))
    (testing "a correction that cited the OTHER row would overrule one
              answer while standing in for another"
      (let [out (judge! eng nil (verdict-body w :verdict "this_change"
                                              :remedy "Not this one."
                                              :corrects stray-id))]
        (is (= 409 (:status out)))
        (is (= :a-person-corrects (guard-of out)))
        (is (str/includes? (detail-of out) "a different row"))))
    (testing "…and the one that cites the answer that stands is taken"
      (is (= 201 (:status (judge! eng nil
                                  (verdict-body w :verdict "this_change"
                                                :remedy "The change dropped a migration."
                                                :corrects vid))))))))

;; ── 4. many judgments on one kind (R-6) ─────────────────────────────

(deftest two-judgments-each-take-one-verdict-per-subject
  (let [eng (engine/engine {:storage (memory/storage) :resources [ticket]})
        red (judgment! eng "Why did the build go red")
        said-enough (judgment! eng "Did the ticket say what it needed"
                               :verdicts [{:name "clear" :sentence "It said enough."}
                                          {:name "thin" :sentence "It did not."}])
        tid (ticket! eng "One ticket, two questions")
        seat (leash! eng seat-id)
        base {:subject_kind "vt_ticket" :subject_id tid}]
    (is (= 201 (:status (judge! eng seat (merge base {:judgment red
                                                      :verdict "infra"
                                                      :remedy "Re-run it."})))))
    (is (= 201 (:status (judge! eng seat (merge base {:judgment said-enough
                                                      :verdict "thin"
                                                      :remedy "Say which service."}))))
        "a subject may carry one verdict under EACH judgment")
    (testing "and still only one under each"
      (let [again (judge! eng seat (merge base {:judgment red
                                                :verdict "this_change"
                                                :remedy "Again."}))]
        (is (= 409 (:status again)))
        (is (= :one-standing-verdict-per-subject (guard-of again)))))
    (testing "the counts are per judgment, and they are a query"
      (is (= 1 (get-in (call! eng :get (str "/api/verdicts?judgment=" red))
                       [:doc :data :total])))
      (is (= 1 (get-in (call! eng :get (str "/api/verdicts?judgment=" said-enough))
                       [:doc :data :total]))))))
