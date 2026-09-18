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

  THE ENGINE'S OWN READ (bead waymark-fp62.7.16) is the second story
  here, and it is driven the same way: the research handler over a
  ctx that carries a `:power` hook. The hook is the REAL one —
  `gate-proxy/power-of` over an engine whose gate row holds the same
  scriptable Gate the source suite uses (spec-mcp-servers R-13) — so
  each assertion runs the real leash check, the real resolution of
  the tool to the row's powers and the real extraction, and only the
  socket is missing.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.inbox-item-test"
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.machine :as machine]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.render :as render]
            [waymark10.server.store.memory :as memory]
            [waymark10.text :as text]
            [workqueue10.resources.inbox-item :as inbox :refer [inbox-item]]
            [workqueue10.sources.gate-chat :as gc])
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

;; ── the engine's own read (waymark-fp62.7.16) ───────────────────────
;;
;; Acceptance 1 to 4 of the bead's design. The seam under test is the
;; ctx `:power` hook: the research handler asks for ONE message, the
;; leash judges the tool, the extraction makes the words, and the row
;; keeps the first 4,000 characters of them.

(def ^:private the-sitters-leash
  "A visibility that admits `email.read` and nothing else — the shape
  `grants/capability-entry` reads: the token, with no filter on it.
  This is what the sitter's own grant confers at the wire, and the
  hook judges it with `gate-proxy/admitted?` exactly as the power
  door does."
  {:surface {"email.read" {:kind "email.read" :actions #{}}}})

(def ^:private a-leash-without-mail
  "The same shape, naming a power that is not the mail."
  {:surface {"notes.read" {:kind "notes.read" :actions #{}}}})

(defn- gate-with
  "A scriptable Gate that answers `rows` to the message read
  (gate-chat's twin, the source suite's arrangement). → the state."
  [rows]
  (let [state (gc/fake-state)]
    (gc/answer! state inbox/read-tool rows)
    state))

(defn- gate-engine
  "A memory engine whose bridge row (spec-mcp-servers R-13, the row
  named gate) holds the scriptable Gate as its client: the real
  dispatcher, the real row, the real powers, only the socket missing.
  The seed's create discovers the fake's scripted tools onto the row;
  a fake scripted `down` refuses the read itself, which the hook
  answers with nil exactly as a dark Gate did."
  [state]
  (doto (engine/engine {:storage (memory/storage)
                        :resources [caps/capability]
                        :services {:mcp-servers {:gate-rpc (gc/fake-rpc state)}}})
    (gate/ensure-gate-row!)))

(defn- power-ctx
  "The ctx a write carries when the request wears a leash that admits
  the mail: the REAL hook, over the scriptable Gate behind the gate
  row."
  ([state] (power-ctx state the-sitters-leash))
  ([state vis]
   {:principal the-clerk
    :now now
    :power (gate/power-of (gate/rpc-of (gate-engine state)) vis)}))

(def ^:private html-message
  "One HTML message of about 60 KB, with a script block and a style
  block in it — the shape mail actually arrives in."
  {:message_id (:message_id a-message)
   :subject (:subject a-message)
   :body_html (str "<html><head><style>p{color:#333}</style>"
                   "<script>track('open')</script></head><body>"
                   "<p>Jen&nbsp;asks whether Thursday works &amp; "
                   "wants the deck estimate confirmed.</p>"
                   (apply str
                          (repeat 1000
                                  "<div class=\"row\">The stain order waits on the answer.</div>"))
                   "</body></html>")})

(def ^:private plain-body
  "A short plain message: 900 characters, which is under the cap."
  (apply str (repeat 90 "Ten chars.")))

(def ^:private plain-message
  "The same message with both halves, the plain one first — a rig
  that spells its parts says which one to read."
  {:message_id (:message_id a-message)
   :subject (:subject a-message)
   :parts [{:mimeType "text/plain" :body plain-body}
           {:mimeType "text/html" :body "<p>the html twin, which loses</p>"}]})

(defn- researched
  "The research handler over one ctx → the row it wrote."
  [ctx]
  (inbox/write-the-summary (at :queued {})
                           {:summary "Jen wants Thursday confirmed."}
                           ctx))

;; 1 · a 60 KB HTML message becomes 4,000 characters of words

(deftest research-reads-the-message-and-keeps-the-first-part-of-it
  (let [state (gate-with [html-message])
        row (researched (power-ctx state))
        excerpt (get-in row [:data :body_excerpt])]

    (testing "the engine asked Gate for this one message, and said why"
      (let [[call :as calls] (gc/calls state)]
        (is (= 1 (count calls))
            "one fetch for one row — the whole point is that the turns
             after this one do not fetch again")
        (is (= inbox/read-tool (:tool call)))
        (is (= (:message_id a-message)
               (get-in call [:arguments inbox/read-arg])))
        (is (= inbox/read-why (get-in call [:arguments :__why]))
            "`why` is translated to Gate's own spelling on the
             forward, so the household's log says who asked and for
             what")))

    (testing "the row keeps the words, capped, and says what it cut"
      (is (= text/default-max-chars (count excerpt))
          "4,000 characters and not one more")
      (is (pos? (long (get-in row [:data :body_cut])))
          "a 60 KB message does not fit, and the row says so rather
           than reading like the whole of it")
      (is (re-find #"Jen asks whether Thursday works & wants" excerpt)
          "the words the model decides with, with the entities decoded")
      (is (not (re-find #"<div|color:#333|track\(" excerpt))
          "no tags, no style block and no script block — the model
           pays for none of them"))

    (testing "and the summary the door collected is untouched beside it"
      (is (= "Jen wants Thursday confirmed." (get-in row [:data :summary])))
      (is (= :queued (:state row))
          "the machine advances the state, never the handler"))))

;; 2 · a short plain message is kept whole

(deftest a-plain-message-under-the-cap-is-kept-whole
  (let [state (gate-with [plain-message])
        row (researched (power-ctx state))]
    (is (= plain-body (get-in row [:data :body_excerpt]))
        "a text/plain part is preferred over its HTML twin, and a
         message under the cap arrives as it was written")
    (is (= 900 (count (get-in row [:data :body_excerpt]))))
    (is (= 0 (get-in row [:data :body_cut]))
        "0 and not absent: nothing was cut, and the reader is told
         that rather than left to guess")))

;; 3 · the door never refuses for the engine's own fault

(deftest research-commits-when-the-engine-cannot-read-the-message
  (testing "a ctx with no power hook writes no excerpt and nothing else"
    (let [row (researched {:principal the-clerk :now now})]
      (is (nil? (get-in row [:data :body_excerpt])))
      (is (nil? (get-in row [:data :body_cut])))
      (is (= "Jen wants Thursday confirmed." (get-in row [:data :summary]))
          "the verdict still lands: the model can read the message
           with waymark_power, as it did before this door could read")
      (is (= (:subject a-message) (get-in row [:data :subject]))
          "and the headers the source wrote are not touched")))

  (testing "a Gate that is dark writes no excerpt either"
    (let [state (gate-with [html-message])]
      (gc/down! state true)
      (let [row (researched (power-ctx state))]
        (is (nil? (get-in row [:data :body_excerpt])))
        (is (nil? (get-in row [:data :body_cut])))
        (is (= "Jen wants Thursday confirmed."
               (get-in row [:data :summary]))))))

  (testing "a rig that refuses answers a sentence about itself, not a message"
    (let [row (researched
               {:principal the-clerk :now now
                :power (fn [_tool _args]
                         ;; the power door forwards Gate's payload word
                         ;; for word, refusals included
                         {:isError true
                          :content [{:type "text"
                                     :text "emila: no message with that id"}]})})]
      (is (nil? (get-in row [:data :body_excerpt]))
          "a refusal written into the excerpt would make the row read
           as if the message had said it")
      (is (nil? (get-in row [:data :body_cut])))))

  (testing "a leash that does not admit the mail never reaches Gate"
    (let [state (gate-with [html-message])
          row (researched (power-ctx state a-leash-without-mail))]
      (is (nil? (get-in row [:data :body_excerpt]))
          "the hook is the same leash the power door reads: a grant
           that does not name email.read buys no mail here either")
      (is (empty? (gc/calls state))
          "and nothing was asked of Gate at all"))))

;; 4 · the walk reads the excerpt where it reads the row

(deftest the-researched-rows-envelope-carries-the-excerpt
  (let [state (gate-with [plain-message])
        written (researched (power-ctx state))
        row (assoc written :state :researched)
        env (render/envelope inbox-item row {:principal the-clerk :now now})]
    (is (= plain-body (get-in env ["data" "body_excerpt"]))
        "the walk reads the words where it reads the row — no second
         call, and no power in the sitter's hand")
    (is (= 0 (get-in env ["data" "body_cut"])))
    (is (not (re-find #"Ten chars" (str (get env "summary"))))
        "the summary line stays the subject and the sender: a queue
         page that carried 4,000 characters per row would be the bill
         this field exists to cut")))
