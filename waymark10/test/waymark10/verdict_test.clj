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
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.verdict :as verdict]
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
  ;; a query rides in :query-string, never inside :uri — a router
  ;; handed "/api/verdicts?state=said" as a path answers not-found,
  ;; and every count below would read nil
  [eng method uri & {:keys [body headers]}]
  (let [[path query] (str/split (str uri) #"\?" 2)
        resp ((engine/handler eng)
              (cond-> {:request-method method :uri path
                       :headers (merge {"content-type" "application/json"}
                                       as-mom headers)}
                query (assoc :query-string query)
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
  would approve for a judging seat. `actions` widens it, for the seat
  a household names to reopen."
  [eng audience & {:keys [actions] :or {actions ["judge"]}}]
  (let [made (call! eng :post "/api/grants"
                    :body {:audience audience
                           :scope [{:kind "verdict" :actions actions}]})
        gid (id-of (get-in made [:doc :self]))
        hs {"x-waymark-principal" audience "x-waymark-actor-type" "agent"}]
    (call! eng :post (str "/api/grants/" gid "/-/accept") :headers hs)
    (assoc hs "x-waymark-grant" gid)))

(def ^:private words
  [{:name "infra" :sentence "The runner failed, not the change."}
   {:name "this_change" :sentence "The change itself broke the build."}])

(defn- judgment!
  "A promoted judgment over vt_ticket, named as the caller likes."
  [eng nm & {:keys [promote? verdicts subject-kind queue]
             :or {promote? true verdicts words
                  subject-kind "vt_ticket" queue {:state "open"}}}]
  (let [made (call! eng :post "/api/judgments"
                    :body {:name nm
                           :subject_kind subject-kind
                           :queue queue
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
      (let [page (call! eng :get "/api/verdicts?state=said")
            ;; a collection item is the envelope minus its data, so the
            ;; word is read off the row the item points at
            standing (call! eng :get (str (get-in page [:doc :data :items 0 :self])))]
        (is (= 1 (get-in page [:doc :data :total])))
        (is (= "this_change"
               (str (get-in standing [:doc :data :verdict]))))))
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

(deftest a-comma-list-names-several-judgments-and-several-words
  ;; A steward follows more than one desk, and an engineer wakes on
  ;; more than one word. Under :eq alone the comma list was ONE literal
  ;; value and every one of these counts read 0 — silently, in a queue
  ;; and in a wake_on filter alike.
  (let [eng (engine/engine {:storage (memory/storage) :resources [ticket]})
        red (judgment! eng "Why did the build go red")
        said-enough (judgment! eng "Did the ticket say what it needed"
                               :verdicts [{:name "clear" :sentence "It said enough."}
                                          {:name "thin" :sentence "It did not."}])
        seat (leash! eng seat-id)
        say! (fn [jid word]
               (judge! eng seat {:judgment jid :subject_kind "vt_ticket"
                                 :subject_id (ticket! eng (str jid word))
                                 :verdict word :remedy "Because."}))
        total #(get-in (call! eng :get (str "/api/verdicts?" %)) [:doc :data :total])]
    (say! red "infra")
    (say! red "this_change")
    (say! said-enough "thin")
    (testing "judgment=A,B is either judgment"
      (is (= 3 (total (str "judgment=" red "," said-enough)))))
    (testing "verdict=a,b is either word"
      (is (= 2 (total "verdict=infra,thin"))))
    (testing "and one value still means exactly that one"
      (is (= 2 (total (str "judgment=" red))))
      (is (= 1 (total "verdict=thin"))))))

;; ── 5. the reopen: a story that is not over ─────────────────────────
;;
;; A correction needs a word for the new state, and some judgments
;; have none — every word pull-request follow-up names is final. The
;; reopen takes the standing answer back and writes NOTHING in its
;; place, so the subject is back in the judgment's queue.

(defn- reopen!
  [eng hs vid note]
  (call! eng :post (str "/api/verdicts/" vid "/-/reopen")
         :headers hs :body {:note note}))

(defn- state-of [eng vid]
  (str (get-in (call! eng :get (str "/api/verdicts/" vid)) [:doc :state])))

(def ^:private why "The bench bug that turned this red has been fixed.")

(deftest a-reopen-takes-the-answer-back-and-writes-nothing-in-its-place
  (let [{:keys [eng seat] :as w} (world)
        said (judge! eng seat (verdict-body w))
        vid (id-of (get-in said [:doc :self]))
        out (reopen! eng nil vid why)]
    (is (= 200 (:status out)) (detail-of out))
    (testing "said → overruled, and the row stays"
      (is (= "overruled" (state-of eng vid)))
      (is (= 1 (get-in (call! eng :get "/api/verdicts") [:doc :data :total]))
          "no replacement verdict was written")
      (is (= 0 (get-in (call! eng :get "/api/verdicts?state=said")
                       [:doc :data :total]))
          "and nothing stands"))
    (testing "the record says who reopened it and why — on the row"
      (let [row (call! eng :get (str "/api/verdicts/" vid))]
        (is (= "mom" (str (get-in row [:doc :data :reopened_by]))))
        (is (= why (str (get-in row [:doc :data :reopen_note]))))
        (is (= seat-id (str (get-in row [:doc :data :said_by])))
            "and the sayer is still the sayer")))
    (testing "…and on the transition"
      (let [ts (store/with-tx (:storage eng)
                 (fn [tx] (store/transitions (:storage eng) tx
                                             {:kind :verdict :resource-id vid}
                                             {})))
            t (first (filter #(= :reopen (:action %)) ts))]
        (is (some? t))
        (is (= "mom" (str (get-in t [:actor :id]))))
        (is (= why (str (get-in t [:inputs :note]))))))
    (testing "the subject may be judged again — nothing stands on it"
      (is (= 201 (:status (judge! eng seat (verdict-body w :verdict "this_change"
                                                          :remedy "Fix the change.")))))))
  (testing "the note is required, and one sentence"
    (let [{:keys [eng seat] :as w} (world)
          vid (id-of (get-in (judge! eng seat (verdict-body w)) [:doc :self]))]
      (is (= 422 (:status (call! eng :post (str "/api/verdicts/" vid "/-/reopen")
                                 :body {}))))
      (is (= 422 (:status (reopen! eng nil vid (apply str (repeat 241 "x"))))))
      (is (= "said" (state-of eng vid))))))

(deftest the-reopen-door-carries-the-confirm-sentence
  (let [{:keys [eng seat] :as w} (world)
        vid (id-of (get-in (judge! eng seat (verdict-body w)) [:doc :self]))
        entry (get-in (call! eng :get (str "/api/verdicts/" vid))
                      [:doc :actions :reopen])]
    (is (true? (get-in entry [:safety :confirm])))
    (is (= verdict/reopen-consequence
           (str (get-in entry [:display :description]))))))

(deftest only-the-standing-verdict-reopens
  (let [{:keys [eng seat] :as w} (world)
        vid (id-of (get-in (judge! eng seat (verdict-body w)) [:doc :self]))
        fix (judge! eng nil (verdict-body w :verdict "this_change"
                                          :remedy "The change dropped a migration."
                                          :corrects vid))
        fix-id (id-of (get-in fix [:doc :self]))]
    (is (= 201 (:status fix)))
    (testing "an overruled verdict is refused, and the refusal names the
              verdict that stands"
      (let [out (reopen! eng nil vid why)]
        (is (= 409 (:status out)))
        (is (str/ends-with? (str (get-in out [:doc :type])) "wrong-state"))
        (is (str/includes? (detail-of out) fix-id))
        (is (str/includes? (detail-of out) "this_change"))
        (is (= "overruled" (state-of eng vid)))
        (is (= "said" (state-of eng fix-id)) "the standing one did not move")))
    (testing "a verdict already reopened is refused too, and says nothing
              stands — the subject is already in the queue"
      (is (= 200 (:status (reopen! eng nil fix-id why))))
      (let [out (reopen! eng nil fix-id "Again, with feeling.")]
        (is (= 409 (:status out)))
        (is (str/includes? (detail-of out) "Nothing stands"))))))

(deftest who-may-reopen
  (let [{:keys [eng judgment] :as w} (world)
        author (leash! eng seat-id :actions ["judge" "reopen"])
        other (leash! eng "seat-bo" :actions ["reopen"])
        said (judge! eng author (verdict-body w))
        vid (id-of (get-in said [:doc :self]))]
    (is (= 201 (:status said)))
    (testing "never the seat that said it, even holding verdict.reopen"
      (let [out (reopen! eng author vid why)]
        (is (= 409 (:status out)))
        (is (= :who-may-reopen (guard-of out)))
        (is (str/includes? (detail-of out) "does not reopen its own answer"))
        (is (= "said" (state-of eng vid)))))
    (testing "a seat with only verdict.judge has no reopen door at all"
      (let [judge-only (leash! eng "seat-cy")]
        (is (not= 200 (:status (reopen! eng judge-only vid why))))
        (is (= "said" (state-of eng vid)))))
    (testing "a person who does not own the judgment does not reopen"
      (let [out (reopen! eng {"x-waymark-principal" "dad"
                              "x-waymark-actor-type" "human"}
                         vid why)]
        (is (= 409 (:status out)))
        (is (= :who-may-reopen (guard-of out)))
        (is (str/includes? (detail-of out) "mom"))
        (is (= "said" (state-of eng vid)))))
    (testing "another seat whose scope lists reopen does"
      (let [out (reopen! eng other vid why)]
        (is (= 200 (:status out)) (detail-of out))
        (is (= "seat-bo" (str (get-in out [:doc :data :reopened_by]))))))
    (testing "and the ledger can tell the two apart"
      (is (= 1 (get-in (call! eng :get (str "/api/verdicts?judgment=" judgment
                                            "&reopened_by=seat-bo"))
                       [:doc :data :total]))))))

(deftest the-judgments-owner-reopens
  ;; the person who wrote the judgment, not merely any person
  (let [{:keys [eng seat] :as w} (world)
        vid (id-of (get-in (judge! eng seat (verdict-body w)) [:doc :self]))]
    (is (= 200 (:status (reopen! eng nil vid why))))
    (is (= "overruled" (state-of eng vid)))))

(deftest a-verdict-about-the-reopened-one-stays-on-the-record
  (let [{:keys [eng seat] :as w} (world)
        vid (id-of (get-in (judge! eng seat (verdict-body w)) [:doc :self]))
        ;; a later judgment whose subjects are verdicts themselves (R-7)
        meta-j (judgment! eng "Did the seat read the log"
                          :subject-kind "verdict"
                          :queue {:state "said"}
                          :verdicts [{:name "read" :sentence "It read the log."}
                                     {:name "guessed" :sentence "It did not."}])
        about (judge! eng (leash! eng "seat-dee")
                      {:judgment meta-j
                       :subject_kind "verdict"
                       :subject_id vid
                       :verdict "guessed"
                       :remedy "Read the job log before answering."})
        about-id (id-of (get-in about [:doc :self]))]
    (is (= 201 (:status about)) (detail-of about))
    (is (= 200 (:status (reopen! eng nil vid why))))
    (is (= "overruled" (state-of eng vid)))
    (is (= "said" (state-of eng about-id))
        "the verdict ABOUT the reopened one is a row of its own, and
         only the reopened verdict moves")
    (is (nil? (get-in (call! eng :get (str "/api/verdicts/" about-id))
                      [:doc :data :reopened_by])))))

(deftest a-correction-cannot-cite-a-reopened-verdict
  (let [{:keys [eng seat] :as w} (world)
        vid (id-of (get-in (judge! eng seat (verdict-body w)) [:doc :self]))]
    (is (= 200 (:status (reopen! eng nil vid why))))
    (let [out (judge! eng nil (verdict-body w :verdict "this_change"
                                            :remedy "The change dropped a migration."
                                            :corrects vid))]
      (is (= 409 (:status out)))
      (is (= :a-person-corrects (guard-of out)))
      (is (str/includes? (detail-of out) "was reopened"))
      (is (str/includes? (detail-of out) "nothing to correct")))))
