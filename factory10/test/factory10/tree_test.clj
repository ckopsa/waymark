(ns factory10.tree-test
  "The factory's two kinds, judged with no database at all (bead
  waymark-fp62.6.2, acceptance 1 to 3).

  WHAT THIS FILE OWNS is the claim the ci_run kind is FOR: that the
  walk is enforced by the machine rather than by the prompt. Every
  assertion about the tree is about an ENVELOPE — which doors a row
  actually offers the hand in front of it — because that is the only
  thing a seat can see, and a tree the envelope does not enforce is a
  tree the charter is asking for politely.

  It reads the availability through `render/action-availability`,
  which IS the envelope's own actions/unavailable partition asked one
  door at a time. This is workqueue10.inbox-item-test's arrangement,
  one domain over, and for its reason: nothing here opens a store,
  because the two guards in this module read `:principal` and the
  machine reads `:state`, so a storage-free ctx answers honestly. The
  row ids below are literals for the same reason — no assertion reads
  one, and no store minted one.

  The declaration gate is the last section: the usability battery, the
  remedies census and the declared scenarios, over the very registry
  `make check-factory` assembles. A warning that lands on these two
  kinds fails here, so the gate cannot drift from the file.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.mirror :as mirror]
            [factory10.resources.change :as ch :refer [change]]
            [factory10.resources.ci-run :as ci :refer [ci-run]]
            [factory10.resources.repo-policy :refer [repo-policy]]
            [waymark10.checks :as checks]
            [waymark10.machine :as machine]
            [waymark10.scenario :as scenario]
            [waymark10.schema :as schema]
            [waymark10.server.engine :as engine]
            [waymark10.server.render :as render]
            [waymark10.usability :as usability])
  (:import (java.time Instant)))

(def ^:private now (Instant/parse "2026-09-18T14:00:00Z"))

(def ^:private a-red-run
  {:run_id "github:ckopsa/waymark/check-run/41752098311"
   :head_sha "1f0c2d3e4a5b60718293a4b5c6d7e8f901234567"
   :check_name "test10 (shard 3)"
   :conclusion "failure"
   :started_at (Instant/parse "2026-09-18T13:41:00Z")})

(defn- run-at
  "One ci_run row, in the state and with the document named."
  [state extra]
  {:kind :ci_run
   :id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
   :state state
   :data (merge a-red-run extra)})

(def ^:private the-classifier {:id "ci-classifier" :type :agent :roles #{}})
(def ^:private the-person {:id "colton" :type :person :roles #{}})
(def ^:private the-source {:id "factory10-source" :type :system :roles #{}})

(defn- offers
  "The doors this row would advertise to this hand — the envelope's own
  partition, asked one door at a time."
  [rdef row principal]
  (into (sorted-set)
        (keep (fn [a]
                (when (= :available
                         (:status (render/action-availability
                                   rdef (:name a) row
                                   {:principal principal :now now
                                    :mode :probe})))
                  (:name a))))
        (machine/actions-seq rdef)))

(defn- refusal
  "The sentence and the wall behind one shut door."
  [rdef row principal action]
  (render/action-availability rdef action row
                              {:principal principal :now now :mode :probe}))

;; ── acceptance 2 · the tree, as the envelope enforces it ────────────

(deftest a-red-run-offers-three-doors-and-no-reclassify
  (testing "at red the classifier is offered exactly the three classes"
    (is (= #{:classify_infra :classify_base_red :classify_this_change}
           (offers ci-run (run-at :red {}) the-classifier))
        "a red run offered anything else would be a run a model could
         answer without naming which of the three things went wrong"))
  (testing "reclassify is ABSENT at red — not discouraged, absent"
    (let [shut (refusal ci-run (run-at :red {}) the-person :reclassify)]
      (is (= :unavailable (:status shut)))
      (is (nil? (:denier shut))
          "the MACHINE refuses it, with no guard behind the refusal —
           a run nobody classified has no verdict to correct")))
  (testing "and a person meets the same three doors at red"
    (is (= #{:classify_infra :classify_base_red :classify_this_change}
           (offers ci-run (run-at :red {}) the-person))
        "the walk itself is unwalled: what a seat may reach at all is
         the grant's question, not this kind's")))

(deftest a-classified-run-offers-reclassify-to-a-person-and-nothing-to-a-sitter
  (let [classified (run-at :classified
                           {:verdict "infra"
                            :remedy "Re-run the shard: the cache died."})]
    (testing "the leaf is the person's, and it holds one door"
      (is (= #{:reclassify} (offers ci-run classified the-person))
          "stamp_label is the mirror's and is hidden, so the person
           meets the correction and nothing else"))
    (testing "the hand that classified is offered nothing at all"
      (is (empty? (offers ci-run classified the-classifier))
          "an agent that could correct its own verdict would be
           answering its own question, and the corrections-per-
           transition number would be measuring nothing"))
    (testing "and the refusal says what it is and what to do instead"
      (let [shut (refusal ci-run classified the-classifier :reclassify)]
        (is (= :unavailable (:status shut)))
        (is (= :only-a-person-reclassifies (:name (:denier shut)))
            "refused by the WALL, not by the machine — the door is in
             state, so the row must say why")
        (is (re-find #"person's correction" (str (:reason shut))))
        (is (re-find #"let a person tap" (str (:reason shut))))))
    (testing "the engine's own actor is not the subject of this law"
      (is (= :available
             (:status (refusal ci-run classified the-source :reclassify)))))
    (testing "a reclassified run is a tomb for every hand"
      (is (empty? (offers ci-run (run-at :reclassified {:verdict "this_change"
                                                        :remedy "Fix the fixture."})
                          the-person))
          "the correction is the last word on this run"))))

;; ── acceptance 3 · the door demands the remedy ──────────────────────

(deftest a-classify-with-no-remedy-refuses-and-names-the-field
  (doseq [door [:classify_infra :classify_base_red :classify_this_change]]
    (let [model (get-in ci-run [:actions door :input])
          ;; the very call invoke makes before it runs a door
          ;; (waymark10.server.invoke) — one validator, not a second
          ;; opinion about what the door demands
          errs (schema/closed-errors model {})]
      (testing (str door " refuses an empty input")
        (is (some? errs))
        (is (contains? errs :remedy)
            "the refusal NAMES the field, so a seat reads what is
             missing rather than guessing at the shape"))
      (testing (str door " takes a sentence")
        (is (nil? (schema/closed-errors
                   model
                   {:remedy "Re-run the shard: the cache died and the job never reached a test."}))))
      (testing (str door " refuses an empty sentence")
        (is (contains? (schema/closed-errors model {:remedy ""}) :remedy)
            "a blank box is not a remedy, and a door that took one
             would teach a model to file three words a day"))))
  (testing "the person's correction demands both the verdict and the sentence"
    (let [model (get-in ci-run [:actions :reclassify :input])
          errs (schema/closed-errors model {})]
      (is (contains? errs :verdict))
      (is (contains? errs :remedy))
      (is (contains? (schema/closed-errors model {:verdict "nonsense"
                                                  :remedy "Fix it."})
                     :verdict)
          "the verdict is one of three, and the enum is what says so"))))

;; ── the verdicts a classify writes ──────────────────────────────────

(deftest each-classify-door-writes-its-own-verdict-and-the-sentence
  (doseq [[door verdict handler]
          [[:classify_infra "infra" ci/classify-as-infra]
           [:classify_base_red "base_red" ci/classify-as-base-red]
           [:classify_this_change "this_change" ci/classify-as-this-change]]]
    (let [row (handler (run-at :red {}) {:remedy "Do the thing."}
                       {:principal the-classifier :now now})]
      (is (= verdict (get-in row [:data :verdict]))
          (str door " writes its own verdict and no other"))
      (is (= "Do the thing." (get-in row [:data :remedy])))
      (is (= :red (:state row))
          "the machine advances the state, never the handler")
      (is (nil? (get-in row [:data :pushed_label]))
          "a classify never writes a label — the label is the
           mirror's, pushed to GitHub and stamped back"))))

(deftest the-correction-replaces-the-verdict-and-keeps-the-record
  (let [row (ci/reclassify-the-run
             (run-at :classified {:verdict "infra" :remedy "Re-run it."})
             {:verdict "this_change" :remedy "Fix the fixture's table list."}
             {:principal the-person :now now})]
    (is (= "this_change" (get-in row [:data :verdict])))
    (is (= "Fix the fixture's table list." (get-in row [:data :remedy])))
    (is (= (:run_id a-red-run) (get-in row [:data :run_id]))
        "the mirror's own fields are the source's and no door
         rewrites them")))

;; ── R-5 · the label is the mirror's job, not the model's ────────────

(deftest the-label-is-pushed-by-the-mirror-and-stamped-on-the-row
  (testing "the three verdicts and the three labels are one table"
    (is (= ["ci:infra" "ci:base-red" "ci:this-change"] mirror/labels))
    (is (= "ci:base-red" (mirror/label-for "base_red")))
    (is (nil? (mirror/label-for "something-else"))
        "a label the table does not hold is no label at all"))
  (testing "the classifier never meets the door that records the push"
    (let [classified (run-at :classified {:verdict "infra"
                                          :remedy "Re-run the shard."})]
      (is (= :hidden (:status (refusal ci-run classified the-classifier
                                       :stamp_label)))
          "the seat's scope names the three classify doors, and it
           never holds a GitHub power")
      (is (= :hidden (:status (refusal ci-run classified the-person
                                       :stamp_label))))
      (is (= :available (:status (refusal ci-run classified the-source
                                          :stamp_label)))
          "the source pushes the label and then walks this door, so
           the transition log carries the push")))
  (testing "the stamp writes which label landed and when"
    (let [row (ci/stamp-the-label
               (run-at :classified {:verdict "infra" :remedy "Re-run it."})
               {:label "ci:infra"}
               {:principal the-source :now now})]
      (is (= "ci:infra" (get-in row [:data :pushed_label])))
      (is (= now (get-in row [:data :labelled_at]))
          "an intended label and a pushed one must not read the same
           — the moment is what tells them apart"))))

;; ── the change is the mirror's, and nobody else's ───────────────────

(def ^:private a-pull-request
  {:kind :change
   :id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B1"
   :state :open
   :data {:change_id "github:ckopsa/waymark#31"
          :repository "ckopsa/waymark"
          :number 31
          :title "6.2 The change family"
          :head_sha "1f0c2d3e4a5b60718293a4b5c6d7e8f901234567"}})

(def ^:private bench-doors
  "The doors the BENCH added (waymark-fp62.6.3.2). They are nobody's
  secret: a seat under a grant walks them, and so does a person. The
  mirror's own doors stay hidden from both."
  #{:submit :discard :stall})

(deftest a-change-offers-the-mirrors-doors-to-nobody-and-the-benchs-to-everybody
  (testing "GitHub's own moves are hidden from every hand but the engine's"
    (doseq [door [:observe :merge :close]]
      (is (= :hidden (:status (refusal change a-pull-request the-person door)))
          "a hidden door is absent from the envelope, so nobody spends
           a turn asking about it")
      (is (= :hidden (:status (refusal change a-pull-request
                                       the-classifier door))))))
  (testing "and the bench's doors are there for a person and for a seat"
    (is (= bench-doors (offers change a-pull-request the-person)))
    (is (= bench-doors (offers change a-pull-request the-classifier))
        "the guards that judge a submit read the repository policy, and
         a probe with no read hook advertises optimistically — the door
         itself judges again with a real hook behind it"))
  (testing "the source moves the row, because the source is the mirror"
    (is (= (into bench-doors [:observe :merge :close])
           (offers change a-pull-request the-source)))
    (is (= #{:reopen}
           (offers change (assoc a-pull-request :state :closed) the-source))
        "GitHub reopens a closed pull request, so the row comes back"))
  (testing "a submitted change keeps working, and a stuck one waits"
    (is (= #{:submit :discard_submitted :stall :observe_submitted :merge :close}
           (offers change (assoc a-pull-request :state :submitted) the-source))
        "the checks run, the review lands, and the seat works the next
         round on the same row")
    (is (= #{:unstick}
           (offers change (assoc a-pull-request :state :stuck) the-person))
        "a stuck change is the house asking a person to look at it")
    (is (empty? (offers change (assoc a-pull-request :state :stuck)
                        the-classifier))
        "and the model that stalled it may not put itself back to work"))
  (testing "a merged pull request is where the story ended"
    (is (empty? (offers change (assoc a-pull-request :state :merged)
                        the-source)))))

(deftest the-mirror-writes-what-github-says-and-moves-nothing
  (let [row (ch/observe-the-pull-request
             a-pull-request
             {:head_sha "9a8b7c6d5e4f30291827364554637281900aabbc"
              :review_state "changes_requested"
              :draft nil}
             {:principal the-source :now now})]
    (is (= "9a8b7c6d5e4f30291827364554637281900aabbc"
           (get-in row [:data :head_sha]))
        "a new commit moves the head, and the row follows it")
    (is (= "changes_requested" (get-in row [:data :review_state])))
    (is (= 31 (get-in row [:data :number]))
        "a fact the source did not read is absent, and absent means
         the stored value stands")
    (is (not (contains? (:data row) :draft))
        "a nil is silence, not an erasure — a source that could not
         read a field must not blank it")
    (is (= :open (:state row))
        "the machine advances the state, never the handler")))

;; ── acceptance 1 · the module assembles alone ───────────────────────

(deftest the-module-is-three-kinds-in-one-domain
  (let [rs (main/resources)]
    (is (= [:repo_policy :change :ci_run] (mapv :kind rs))
        "the policy first, because the bench's doors read it; a change
         before a ci_run, because a ci_run points at one")
    (is (every? #(= :factory (:domain %)) rs))
    (testing "and it assembles into a registry with nothing else beside it"
      (let [reg (engine/full-registry rs)]
        (is (contains? (:kinds reg) :change))
        (is (contains? (:kinds reg) :ci_run))
        (is (contains? (:kinds reg) :repo_policy))))))

;; ── the declaration gate, in the suite ──────────────────────────────

(deftest the-usability-battery-holds-nothing-against-these-kinds
  (doseq [r (main/resources)]
    (is (= [] (usability/warnings r))
        (str (name (:kind r))
             " must read as well from the battery as it does from the
              file — a warning here is a form somebody meets"))
    (is (= [] (vec (:waymark10/warnings (meta r))))
        (str (name (:kind r)) " must trip no check-battery warning"))))

(deftest every-guard-that-speaks-says-what-to-do-next
  (let [reg (engine/full-registry (main/resources))
        rdefs (vals (:kinds reg))
        ours #{:change :ci_run :repo_policy}
        cen (checks/census rdefs)]
    (is (empty? (filterv (comp ours :kind) (:dead-ends cen)))
        "a guard that refuses in words and names no way out spends a
         model's fuel on law that was spoken late — :remedies or
         :open, and never a waiver")
    (is (= [] (checks/remedy-token-problems rdefs))
        "a remedy naming a door that would 404 sends the caller
         somewhere worse than nowhere")
    (is (= [] (checks/stale-waiver-problems cen))
        "the waiver list only shrinks, and nothing here adds to it")))

(deftest the-declared-law-still-holds
  (doseq [r (main/resources)]
    (let [{:keys [total checked violations]} (scenario/report r)]
      (is (pos? (long (or total 0)))
          (str (name (:kind r)) " writes its wall down as a scenario"))
      (is (= total checked)
          (str (name (:kind r))
               "'s scenarios are check-tier: every guard reads
                :principal and nothing else, so the gate judges them
                with no database"))
      (is (= [] (vec violations))))))

;; ── the shape the source and the walker both read ───────────────────

(deftest the-declarations-say-what-the-source-needs

  (testing "the ci_run machine, whole"
    (is (= [:red :classified :reclassified] (:states ci-run)))
    (is (= :red (:initial ci-run)))
    (is (= #{:reclassified} (:terminal ci-run)))
    (is (= {:state "red"} (:default-filters ci-run))
        "the queue IS the collection under its default filter")
    (is (= "started_at" (get-in ci-run [:sortable :default]))
        "oldest first: the house answers its red builds in the order
         they broke"))

  (testing "one row per check run, enforced by an index"
    (is (= [[:run_id]] (:unique ci-run)))
    (is (contains? (set (keys (:filterable ci-run))) :run_id)))

  (testing "the birth door carries no verdict"
    (let [fields (into #{} (map first) (rest (:create-schema ci-run)))]
      (is (not (contains? fields :verdict))
          "a row born with a verdict is a row that skipped the tree")
      (is (not (contains? fields :remedy)))
      (is (not (contains? fields :pushed_label)))
      (is (not (contains? fields :labelled_at)))))

  (testing "the change machine carries the bench"
    (is (= [:open :submitted :stuck :merged :closed] (:states change)))
    (is (= #{:merged} (:terminal change))
        "closed is not a tomb: GitHub reopens a closed pull request.
         Neither is stuck: a person puts it back to work")
    (is (= #{:merged} (get-in change [:over :accomplished])))
    (is (= #{:closed} (get-in change [:over :let-go]))
        "stuck is in neither: a change waiting for a person is not a
         change whose work is over")
    (is (= [[:change_id]] (:unique change)))
    (is (= {:state "open,submitted"} (:default-filters change))
        "a change a seat has pushed is still the seat's work"))

  (testing "the repository policy says what submit means"
    (is (= [[:repository]] (:unique repo-policy))
        "one policy for each repository, enforced by an index")
    (is (= [:active :retired] (:states repo-policy)))
    (is (= #{} (:terminal repo-policy)))
    (is (= {:state "active"} (:default-filters repo-policy)))
    (let [fields (into #{} (map first) (rest (:schema repo-policy)))
          form (into #{} (map first) (rest (:create-schema repo-policy)))]
      (is (= #{:repository :clone_url :branch_pattern :base :max_lines
               :opens_pr :auto_merge :rounds_per_change :formatter :deny
               :orientation :enrolled_at :note}
             fields)
          "every number a submit obeys, where the bench clones it from,
           and the engine's own two: when the bench took it and why it
           did not")
      (is (= #{:enrolled_at :note} (into #{} (remove form) fields))
          "…and the engine's two are on no form: a person states the
           policy, and the engine says what the bench did with it")
      (is (contains? (:actions repo-policy) :mark_enrolled)
          "the retry's own hidden door, so a late enrolment is a
           transition and not a silent field write")))

  (testing "the log tail is capped, and the cap is said out loud"
    (is (= 200 ci/log-excerpt-lines))
    (is (= 20000 ci/log-excerpt-chars))
    (is (= 20000 (:max (second (schema/field-schema
                                (:schema ci-run) :log_excerpt))))
        "the field's own ceiling is the one the source reads — two
         numbers that could drift would be one number nobody trusts"))

  (testing "nothing in this module names a seat (R-6)"
    (doseq [r (main/resources)]
      (is (nil? (re-find #":seat\b" (pr-str r)))
          "which seat walks the tree is a row: the seat's walk field
           names ci_run, and no declaration here names a seat"))))
