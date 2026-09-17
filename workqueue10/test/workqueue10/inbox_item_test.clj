(ns workqueue10.inbox-item-test
  "The inbox tree, judged with no database at all (docs/spec-seat.md
  § 13.8).

  What this file owns is the claim the whole kind is FOR: that the
  walk is enforced by the machine rather than by the prompt. Every
  assertion below is about an ENVELOPE — which doors a row actually
  offers the hand in front of it — because that is the only thing a
  seat can see, and a tree the envelope does not enforce is a tree the
  charter is asking for politely.

  It reads the availability through `render/action-availability`,
  which IS the envelope's own actions/unavailable partition asked one
  door at a time (outcome's `the-door-is-open-now` calls the same fn
  for the same reason: a second opinion about whether a button is
  there would be wrong first). Nothing here opens a store — the one
  guard in the tree reads `:principal` and the machine reads `:state`,
  so a storage-free ctx answers honestly.

  The three declared scenarios in inbox_item.clj carry the half a
  scenario can carry — a verdict over a row written down. The BIRTH
  is a story: `yes` writes a task through the cross-write door, and
  the way to judge a handler is to call it, which is what
  `a-yes-births-exactly-one-task` does over a ctx that records instead
  of writing.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.inbox-item-test"
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.machine :as machine]
            [waymark10.server.render :as render]
            [workqueue10.resources.inbox-item :as inbox :refer [inbox-item]])
  (:import (java.time Instant)))

(def ^:private now (Instant/parse "2026-09-16T14:00:00Z"))

(def ^:private a-message
  {:message_id "19b2f0c4d5e6a7b8"
   :subject "Deck estimate — can you confirm Thursday?"
   :sender "jen@contractor.example"
   :received_at (Instant/parse "2026-09-16T11:12:00Z")})

(defn- at
  "One row of this kind, in the state and with the document named."
  [state extra]
  {:kind :inbox_item
   :id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
   :state state
   :data (merge a-message extra)})

(def ^:private the-clerk {:id "inbox-clerk" :type :agent :roles #{}})
(def ^:private the-person {:id "colton" :type :person :roles #{}})

(defn- offers
  "The doors this row would advertise to this hand — the envelope's own
  partition, asked one door at a time."
  [row principal]
  (into (sorted-set)
        (keep (fn [a]
                (when (= :available
                         (:status (render/action-availability
                                   inbox-item (:name a) row
                                   {:principal principal :now now
                                    :mode :probe})))
                  (:name a))))
        (machine/actions-seq inbox-item)))

(defn- refusal
  "The sentence and the wall behind one shut door."
  [row principal action]
  (render/action-availability inbox-item action row
                              {:principal principal :now now :mode :probe}))

;; ── the tree, as the envelope enforces it ───────────────────────────

(deftest the-walk-is-the-envelope-and-not-the-prompt
  (testing "at queued there is one door, and it is research"
    (is (= #{:research} (offers (at :queued {}) the-clerk))
        "a queued message offered anything but research would be a
         message a model could answer without opening"))
  (testing "yes is ABSENT until research is done — not discouraged, absent"
    (let [shut (refusal (at :queued {}) the-clerk :yes)]
      (is (= :unavailable (:status shut)))
      (is (nil? (:denier shut))
          "the MACHINE refuses it, with no guard behind the refusal —
           the strongest way a tree can be enforced")))
  (testing "at researched the branch appears, and it is exactly two ways"
    (is (= #{:yes :no}
           (offers (at :researched {:summary "Jen wants Thursday confirmed."})
                   the-clerk))
        "research is not offered twice and reopen is not a door out of
         a row that was never dismissed"))
  (testing "at a leaf the sitter is offered nothing at all"
    (is (empty? (offers (at :action_item {:summary "…" :task "01TASK"})
                        the-clerk))
        "an answered message is never handed back to the walk — which
         is the whole of week one's duplicate-task refusals, as law")
    (is (empty? (offers (at :dismissed {:reason "A receipt."}) the-clerk))
        "and a dismissal is a leaf FOR AN AGENT, whatever the person
         may still do about it"))
  (testing "action_item is a tomb for every hand; dismissed is not"
    (is (empty? (offers (at :action_item {:task "01TASK"}) the-person))
        "a task was born and the way back is that task's own doors")
    (is (= #{:reopen} (offers (at :dismissed {:reason "A receipt."})
                              the-person)))))

;; ── the person's correction ─────────────────────────────────────────

(deftest reopen-is-the-persons-door-and-nobody-elses
  (let [dismissed (at :dismissed {:summary "A receipt, nothing asked."
                                  :reason "A receipt."})]
    (testing "the hand that dismissed it may not take it back"
      (let [shut (refusal dismissed the-clerk :reopen)]
        (is (= :unavailable (:status shut))
            "an agent that could reopen its own dismissal would be
             answering its own question, and the corrections-per-
             transition number would be measuring nothing")
        (is (= :the-correction-is-a-persons (:name (:denier shut)))
            "and it is refused by the WALL, not by the machine — the
             door is in state, so the row must say why")
        (is (re-find #"person's correction" (str (:reason shut)))
            "the refusal says what it is, in the household's words")
        (is (re-find #"publish a finding" (str (:reason shut)))
            "…and what to do instead, which is the repo's own rule
             about a refusal that costs somebody something")))
    (testing "and the person walks it with no grant and no ceremony"
      (is (= :available (:status (refusal dismissed the-person :reopen)))))
    (testing "the engine's own actor is not the subject of this law"
      (is (= :available
             (:status (refusal dismissed {:id "system" :type :system}
                               :reopen)))))))

;; ── the birth ───────────────────────────────────────────────────────

(defn- recording-ctx
  "A ctx that records what the handler births instead of writing it —
  the cross-write door's shape, with an engine's answer behind it."
  [principal]
  (let [births (atom [])]
    [births
     {:principal principal
      :now now
      :create (fn [kind body]
                (swap! births conj [kind body])
                {:row {:id "01TASKID0000000000000000000"}})}]))

(deftest a-yes-births-exactly-one-task-and-stamps-it-on-the-row
  (let [[births ctx] (recording-ctx the-clerk)
        row (inbox/yes->task (at :researched {:summary "Jen wants Thursday."})
                             {:action_item "Confirm Thursday with Jen"}
                             ctx)]
    (testing "one task, through task's own create door"
      (is (= 1 (count @births))
          "a yes that birthed two tasks would be week one's duplicate
           refusal wearing a handler's name")
      (is (= :task (first (first @births)))))
    (testing "the action item IS the task's title — that is what the door demands it for"
      (is (= "Confirm Thursday with Jen" (:title (second (first @births))))))
    (testing "and it carries the confluence's source tag, because every task is born through it"
      (is (= "todo" (:source (second (first @births)))))
      (is (= "todo" inbox/capture-source)
          "the one word the source of the queue and this handler both
           read — an untagged id would refuse at the routing seam"))
    (testing "a message that named no time names no due"
      (is (not (contains? (second (first @births)) :due_at))
          "an absent due is absent, never today: task's own guard
           refuses a date already gone, and inventing one here would
           walk into it"))
    (testing "the task is stamped on the row, so 'which of these became work' is a ref and not prose"
      (is (= "01TASKID0000000000000000000" (get-in row [:data :task]))))
    (testing "and nothing else about the row moved"
      (is (= :researched (:state row))
          "the machine advances the state, never the handler")
      (is (= "Jen wants Thursday." (get-in row [:data :summary]))))))

(deftest a-due-the-message-named-rides-along
  (let [due (Instant/parse "2026-09-17T15:00:00Z")
        [births ctx] (recording-ctx the-clerk)]
    (inbox/yes->task (at :researched {:summary "…"})
                     {:action_item "Confirm Thursday with Jen" :due_at due}
                     ctx)
    (is (= due (:due_at (second (first @births))))
        "the clock time the message named, kept as it was — task widens
         a DAY to its closing midnight, and this is not a day")
    (is (nil? (:due_date (second (first @births))))
        "one due and never two: naming both refuses at task's own door")))

(deftest the-storage-free-probe-never-births-anything
  (let [row (inbox/yes->task (at :researched {})
                             {:action_item "Confirm Thursday with Jen"}
                             {:principal the-clerk :now now})]
    (is (nil? (get-in row [:data :task]))
        "a ctx with no pen stamps no ref — a rehearsal that wrote a
         task would be the one door in the tree where a dry run cost
         the household something")))

;; ── the two words a verdict writes ──────────────────────────────────

(deftest research-and-no-write-exactly-what-they-collected
  (testing "research keeps the summary and nothing else"
    (let [row (inbox/write-the-summary (at :queued {})
                                       {:summary "Jen wants Thursday."}
                                       {:principal the-clerk :now now})]
      (is (= "Jen wants Thursday." (get-in row [:data :summary])))
      (is (= (:message_id a-message) (get-in row [:data :message_id]))
          "the headers are the source's and no door rewrites them")))
  (testing "a no with nothing to say is a whole answer"
    (let [row (inbox/write-the-reason (at :researched {:summary "A receipt."})
                                      {}
                                      {:principal the-clerk :now now})]
      (is (nil? (get-in row [:data :reason]))
          "a door that demanded a reason would teach a model to invent
           one, which is the bug"))))

;; ── the shape the source and the queue both read ────────────────────

(deftest the-declaration-says-what-the-source-and-the-walker-need

  (testing "the machine, whole"
    (is (= [:queued :researched :action_item :dismissed] (:states inbox-item)))
    (is (= :queued (:initial inbox-item)))
    (is (= #{:action_item} (:terminal inbox-item))
        "dismissed is NOT a tomb, because the person's door leaves it —
         the framework refuses a door out of a terminal state, and the
         ending is declared in :over instead (see :deviations)")
    (is (= {:accomplished #{:action_item} :let-go #{:dismissed}}
           (:over inbox-item))
        "both endings are over for every reader that asks; one is what
         the house did and one is what it let go"))

  (testing "the queue IS the collection under its default filter"
    (is (= {:state "queued"} (:default-filters inbox-item)))
    (is (= "received_at" (get-in inbox-item [:sortable :default]))
        "oldest first: the house answers its mail in the order it
         arrived"))

  (testing "one row per message, enforced by an index and not by a charter"
    (is (= [[:message_id]] (:unique inbox-item)))
    (is (contains? (set (keys (:filterable inbox-item))) :message_id)
        "uniqueness is enforced on the promoted column, so the field
         has to be filterable — and the source's 'do we already hold
         this id' read is the same filter"))

  (testing "the birth door takes headers and nothing a verdict owns"
    (let [fields (into #{} (map first) (rest (:create-schema inbox-item)))]
      (is (= #{:message_id :subject :sender :received_at :list_unsubscribe}
             fields))
      (is (not (contains? fields :summary))
          "a row born with a summary is a row that skipped research")
      (is (not (contains? fields :task)))
      (is (not (contains? fields :reason)))))

  (testing "the birth is advertised where a reader meets it"
    (is (= [{:kind :task :action :create}]
           (get-in inbox-item [:actions :yes :touches]))
        ":touches is the blast radius as law — check-touches verifies
         the pair at assembly and render puts it on the wire")
    (is (every? #(get-in inbox-item [:actions % :display :order])
                [:research :yes :no :reopen])
        "four doors, four places in the order a person reads them"))

  (testing "only reopen carries a wall"
    (is (empty? (mapcat #(get-in inbox-item [:actions % :guards])
                        [:research :yes :no]))
        "the walk itself is unwalled: what a seat may reach at all is
         the grant's question, and a second wall here would refuse the
         sitter the scope already admitted")))
