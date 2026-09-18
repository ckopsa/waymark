(ns workqueue10.resources.inbox-item
  "The inbox item: one message the house has not decided about yet,
  and the three moves that decide it (docs/spec-seat.md § 13.8).

  THE DECISION TREE IS A KIND. A seat walks a tree: for each message,
  research opens it, and then yes states the action item or no
  dismisses it. A tree with branches is a state machine with two doors
  from one state, which is an ordinary declaration and not a
  `:process` — a process has no branches by design. The tree lives
  here, in code, BECAUSE IT IS LAW; the seat that walks it lives in a
  row, because a seat is fluid. Nothing about the shape of the walk is
  in the prompt: at `queued` the envelope offers one door, at
  `researched` it offers two, at a leaf it offers none. A model cannot
  skip research, because the `yes` door is ABSENT until research is
  done — not discouraged, absent — and it cannot make a task except
  through `yes`, which demands the action item in one sentence.

  THE QUEUE IS THE COLLECTION UNDER ITS DEFAULT FILTER. Week one's
  seat rebuilt its worklist from the inbox on every wake, because
  nothing held which messages it had already handled — nine of its
  twenty-three refusals were a second task for a message it had
  already turned into one. `message_id` is the address in the inbox,
  declared `:unique`, so the second minting of a handled message is
  refused by an index rather than by a sentence in a charter; and a
  row at a leaf is simply not in `?state=queued` any more, so it is
  never offered twice. `:default-filters {:state \"queued\"}` is
  outcome's own spelling of the same idea — the collection a walker
  opens IS the work waiting for it.

  WHAT IS STORED, AND WHAT IS NEVER STORED. Headers: the address, the
  subject, who sent it, when it arrived. The source lists headers and
  mints rows with no tokens spent. The verdict writes back the one
  thing worth keeping, which is the summary. The WHOLE BODY is never
  here. That is thread.clj's line about mirroring the conversation
  and never the messages, drawn one domain over, and it is the reason
  this kind can be granted to an economy model without granting it
  the mail.

  THE ENGINE READS THE MESSAGE, NOT THE MODEL (bead
  waymark-fp62.7.16). The clerk's first sitting on the cheaper model
  read one power answer of 179 KB for three messages. That was 80
  percent of everything it read, and each turn after it read the same
  bytes again. So the research door fetches the message itself: the
  handler calls the `email.read` power through the ctx `:power` hook
  — the sitter's own leash, and a call the model does not make — and
  writes `body_excerpt`, up to 4,000 characters of plain text, with
  `body_cut` beside it to say how much the cap removed. One fetch for
  each row replaces a re-read on each turn.

  THE FETCH NEVER REFUSES THE DOOR. A request with no `:power` hook,
  a Gate that is dark, a rig that says no: each one writes no
  excerpt, and the transition commits. Research is a verdict about a
  message, and the engine's own reach is not a reason to refuse it.
  The model can still read the whole message with `waymark_power`,
  which is what it did before this door could read.

  THE TASK IS BORN INSIDE `yes`, UNDER THE OUTER PRINCIPAL. The seat's
  scope does not name `task.create` at all: the queue row is written
  through the cross-write door in the same transaction, by
  `yes->task`, and `:touches` advertises the birth so a reader can see
  it coming and `checks-assembly/check-touches` can verify the pair at
  assembly. `ctx :create` carries the OUTER principal (invoke.clj's
  own finding, and outcome's `materialize` before this), so the task
  lands carrying the sitter's name on its own create transition and is
  judged by task's guards as that hand — including the one that
  refused fourteen times in week one, whose reason string now names
  the fix. Nothing acts as a system actor and nothing is minted after
  the commit.

  `reopen` IS THE PERSON'S DOOR AND NOBODY ELSE'S. A reopen after a
  `no` is a CORRECTION — it is how the house counts what the seat got
  wrong, and the query that reads corrections per transition is what
  decides whether a cheaper model holds. An agent that could reopen
  its own dismissal could answer its own question, and the count would
  be measuring nothing. So `the-correction-is-a-persons` refuses every
  agent hand, with the sentence that says what to do instead. It is
  NOT `unless-granted`: a grant that opened this door would be a grant
  that let the house buy back its own correction count.

  `dismissed` IS NOT DECLARED TERMINAL, and the spec's own declaration
  said it was — see `:deviations`. The framework refuses a door out of
  a tomb (`checks/check-terminal-no-exit`), and it is right to: a
  terminal state is where a row's story ended. This story has one more
  sentence in it, so `:over` carries the ending instead — `action_item`
  is what the house accomplished, `dismissed` is what it let go — and
  the feed reads both as over while the machine keeps the person's
  door open.

  :nav :secondary, for value's reason: an inbox item is not a thing
  the FAMILY does. A `:primary` kind's open rows are claimed by the
  feed's next-actions population, and a queue of unread mail carding
  in do-now would be the machine handing the household the work it
  exists to take off them."
  (:require [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.text :as text]
            [waymark10.types :as t]))

;; ── the two words a verdict writes ──────────────────────────────────
;;
;; An action's input is NOT merged into the document — the handler is
;; the only thing that writes (invoke.clj step 11, task's own
;; `set-priority` one file over). So the two doors that collect a
;; sentence spell the stamp, and nothing else about the row moves:
;; the machine advances the state, never the handler.

;; ── the message the engine reads for the model ──────────────────────

(def read-tool
  "Gate's one-message read, under the `email.read` power (the gate
  row's powers, waymark10.server.mcp-servers). It is named here, as
  the listing tool is named in the source, because emila's wire is
  the one wire in this confluence that is pinned nowhere in this
  repository: a rig that calls its read something else costs this
  field and never the door."
  "emila__read")

(def read-arg
  "Which argument names the message to read. Gate's `emila__read`
  takes `uid`, the IMAP uid, and the source stores that same uid on
  the row under `message_id` (sources/inbox.clj). Read on production
  2026-09-18 off waymark_powers, after a first sitting wrote no
  excerpt because the read was asked for under the row's own word."
  :uid)

(def read-why
  "What Gate's own log records about this read. The household can
  then tell, at Gate, that the ENGINE opened one message for a
  verdict, and that a model did not ask for the mail."
  (str "waymark: reading one message the inbox clerk is about to "
       "decide about"))

(def excerpt-chars
  "How much of one message the row keeps. The default cap
  (waymark10.text): enough for the ask, small enough that each turn
  after pays little to read it again."
  text/default-max-chars)

(defn- read-the-message
  "The message, as the plain words in it → {:text … :cut n}, or nil.

  The `:power` hook is the sitter's own leash (invoke.clj's make-ctx):
  it answers Gate's payload when this request's grant admits
  `email.read`, and nil for everything else — no hook, no grant, a
  dark Gate. Nil here writes nothing, and the door still opens: see
  the header.

  AN `isError` ANSWER IS NOT A MESSAGE. The power door forwards
  Gate's payload word for word, refusals included, so a rig that says
  no answers a sentence about the rig. Writing that sentence into
  `body_excerpt` would make the row read as if the message said it."
  [row ctx]
  (when-some [power (:power ctx)]
    (when-some [id (some-> (get-in row [:data :message_id]) str not-empty)]
      (let [answer (power read-tool {read-arg id :why read-why})
            {:keys [text cut]} (when (and answer (not (:isError answer)))
                                 (text/excerpt answer excerpt-chars))]
        (when (seq text) {:text text :cut cut})))))

(defhandler write-the-summary [row inp ctx]
  ;; TWO WRITES, AND ONLY ONE OF THEM IS THE MODEL'S. The summary is
  ;; the sentence the door collected; the excerpt is what the engine
  ;; read for itself, so the next turn does not pay for the message
  ;; again.
  (let [row (assoc-in row [:data :summary] (:summary inp))]
    (if-some [found (read-the-message row ctx)]
      (-> row
          (assoc-in [:data :body_excerpt] (:text found))
          (assoc-in [:data :body_cut] (:cut found)))
      row)))

(defhandler write-the-reason [row inp _ctx]
  ;; `reason` is optional: a `no` with nothing to say is a whole
  ;; answer, and writing nil over nil is what that looks like here.
  (assoc-in row [:data :reason] (:reason inp)))

;; ── the birth the tree exists for ───────────────────────────────────

(def capture-source
  "The confluence tag a task born HERE drinks from.

  Every task in this house is born through the confluence, and its
  external identity carries a source tag — `\"chore:…\"`, `\"meal:…\"`,
  `\"todo:…\"` — which is a routing fact to the confluence and an
  opaque string to the engine. `todo` is the house list on the wall
  tablet: the pocket authority that takes births and the one task's
  own create door defaults to, so an inbox triage lands where a
  captured todo lands and not somewhere new. Named here rather than
  spelled inline so the source agent and this handler read one word."
  "todo")

(defhandler yes->task [row inp ctx]
  ;; ONE TASK, THROUGH THE DOOR THE HOUSEHOLD WOULD HAVE TAPPED.
  ;; `ctx :create` carries the outer principal, so this row is the
  ;; sitter's own capture and task's guards judge it as that hand —
  ;; which is the whole of the staleness answer: the world is
  ;; re-judged by the target's own law, in this transaction, and its
  ;; refusal rolls the `yes` back rather than leaving an inbox item
  ;; that reads decided beside a task that never landed.
  ;;
  ;; The storage-free probe carries no `:create` and never runs a
  ;; writing handler (outcome's `ask-to-iterate` records the same
  ;; guard); the arm is guarded by its presence so the same handler is
  ;; safe to call in a test with nothing behind it.
  (let [res (when (:create ctx)
              ((:create ctx) :task
               (cond-> {:title (:action_item inp)
                        :source capture-source}
                 ;; a due the message named, kept as the clock time it
                 ;; was; task's own `one-due` refuses a second
                 ;; spelling, so only ever this one
                 (:due_at inp) (assoc :due_at (:due_at inp)))))]
    (cond-> row
      res (assoc-in [:data :task] (str (get-in res [:row :id]))))))

;; ── the one wall ────────────────────────────────────────────────────

(defguardfn the-correction-is-a-persons
  {:reads [:principal]
   :explain "Reopening a dismissal is the person's correction, and a correction an agent could make on its own is a correction the house cannot count — the reopens after a `no` are exactly how this seat's judgment is measured. If you dismissed this one and now think you were wrong, say so where an agent may: publish a finding that cites this row, and let a person tap."}
  [_row _inp ctx]
  ;; The person-wall's own shape (`guards/unless-granted`'s first
  ;; clause), spelled by hand and NOT made grantable: every hand but an
  ;; agent's passes, including :system — the engine's own actor is not
  ;; the subject of this law — and no scope opens it, because a scope
  ;; that did would let the house buy back the number it is grading
  ;; itself on.
  (if (= :agent (:type (:principal ctx)))
    (t/deny)
    (t/allow)))

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; All three are CHECK-TIER — no `:given` rows, and the only guard in
;; the tree reads `:principal` and nothing else — so `make check-queue`
;; judges them with no database, in the same breath as the usability
;; warnings.

(def ^:private a-dismissed-message
  {:message_id "19b2f0c4d5e6a7b8"
   :subject "Fwd: invoice for the deck stain"
   :sender "receipts@hardware.example"
   :summary "A receipt for a purchase already made. Nothing is asked of Colton."
   :reason "A receipt, not a request."})

(defscenario an-agent-may-not-reopen-its-own-dismissal
  "The reopens after a no are how this seat's judgment is measured, so
   the hand that said no may not take it back. An agent that thinks it
   was wrong publishes a finding and waits for a tap."
  {:kind    :inbox_item
   :attempt :reopen
   :row     {:state :dismissed :data a-dismissed-message}
   :as      {:id "inbox-clerk" :type :agent}
   :expect  {:refused :the-correction-is-a-persons
             :because "person's correction"}})

(defscenario the-person-reopens-what-the-clerk-dismissed
  "And the door is really there for the person whose inbox it is —
   this is the correction, one tap, no grant and no ceremony."
  {:kind    :inbox_item
   :attempt :reopen
   :row     {:state :dismissed :data a-dismissed-message}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario the-tree-has-no-shortcut-past-research
  "The model cannot say yes to a message it has not opened — not
   because the charter asks it not to, but because the door is not
   there. The machine refuses it with no guard behind the refusal,
   which is the strongest way a tree can be enforced."
  {:kind    :inbox_item
   :attempt :yes
   :row     {:state :queued
             :data {:message_id "19b2f0c4d5e6a7b9"
                    :subject "Deck estimate — can you confirm Thursday?"
                    :sender "jen@contractor.example"}}
   :as      {:id "inbox-clerk" :type :agent}
   :expect  {:refused :out-of-state
             :because "Researched"}})

;; ── :inbox_item — the queue, and the tree over it ───────────────────

(defresource inbox-item
  {:kind :inbox_item
   :plural "inbox_items"
   ;; not a thing the FAMILY does — see the ns docstring
   :nav :secondary
   :states [:queued :researched :action_item :dismissed]
   :initial :queued
   ;; ONE TOMB, NOT TWO — see :deviations
   :terminal #{:action_item}
   ;; where this kind keeps its endings (task's own sentence): the
   ;; machine's states say where a row IS, and these two say what
   ;; happened to it. A dismissal is let go rather than accomplished,
   ;; and a dismissed row is over for every reader that asks — while
   ;; the person's door stays open, which is the whole reason it is
   ;; said here instead of in :terminal.
   ;;
   ;; AND THE DOORS CLOSE WITH IT (waymark-fp62.4.1), except `reopen`.
   ;; The engine shuts every door on a row whose work is over; the
   ;; exception is the door that says the ending was wrong. Nothing is
   ;; spelled here because nothing has to be: these endings are STATES,
   ;; so the machine already shows the way back — `reopen` departs
   ;; `dismissed` and lands in `researched`, which is no ending at all.
   ;; A kind whose endings are WORDS IN THE DATA has no such view and
   ;; names its ways back in `:over :ways-back` (media's `start`).
   :over {:accomplished #{:action_item} :let-go #{:dismissed}}
   :summary "{data.subject} · {data.sender} · {state}"
   ;; the message's own line, not the kind label: a card headed "Inbox
   ;; item" reads like a form label sitting above the thing it labels
   :label-template "{data.subject}"
   :display {:title "{data.subject}"}
   ;; THE QUEUE IS THE COLLECTION UNDER ITS DEFAULT FILTER (outcome's
   ;; spelling): a walker opens /api/inbox_items and gets exactly the
   ;; messages nobody has decided about, oldest first.
   :filterable {:state #{:eq :in} :message_id #{:eq}}
   :default-filters {:state "queued"}
   :sortable {:fields [:received_at] :default "received_at"}
   ;; ONE ROW PER MESSAGE, enforced by an index rather than by a
   ;; sentence in a charter — week one's nine duplicate refusals, as
   ;; law. Uniqueness is enforced on the promoted column, which is why
   ;; :message_id is filterable above.
   :unique [[:message_id]]
   :schema
   [:map
    ;; the address in the inbox, and the queue's identity. The source
    ;; is the only thing that reads it back — a person never types one
    ;; — but it is not hidden, because a row whose identity a reader
    ;; cannot see is a row nobody can reconcile against the mailbox.
    [:message_id {:x-display
                  {:label "The message's own id"
                   :help "The id the mailbox knows this message by — the source reads it back to see whether this one is already in the queue, so it has to be the id the mailbox would answer with, not a subject line."}}
     [:string {:min 1 :max 250}]]
    ;; :raw because it is a LABEL and not prose — long, but one line
    ;; (thread's own title, one domain over): a forwarded chain's
    ;; subject is longer than a subject has any right to be, and a
    ;; mint refused for length would be a message silently never
    ;; queued, which is the failure mode this kind exists to end.
    [:subject {:x-display
               {:raw true
                :label "Subject"
                :help "The subject line, exactly as it arrived — this is what the card reads and what a person recognises the message by."}}
     [:string {:min 1 :max 400}]]
    [:sender {:x-display
              {:label "Who sent it"
               :help "The sender as the mailbox spells them — an address, or a name and an address. Whoever wants something is who this row is about."}}
     [:string {:min 1 :max 240}]]
    [:received_at {:x-display
                   {:label "When it arrived"
                    :help "The mailbox's own timestamp. The queue is walked oldest first, so this is the order the house answers its mail in."}}
     :waymark/instant]
    ;; WRITTEN BY RESEARCH, and the only thing the body leaves behind.
    ;; The message itself is read through a power at research time and
    ;; never stored; this is the sentence that survives it.
    [:summary {:optional true
               :x-display
               {:widget "prose"
                :label "What the message actually says"
                :help "What is in the message and what, if anything, it asks of this house — in the words a person would use out loud. The body is never kept, so this is the whole of what the next reader has."}}
     [:maybe [:string {:max 480}]]]
    ;; WRITTEN BY RESEARCH TOO, and by the ENGINE rather than by the
    ;; model (waymark-fp62.7.16). The handler reads the message
    ;; through the sitter's own `email.read` power and keeps the first
    ;; part of the words. A person never writes it: it is on no door
    ;; and on no input, exactly as the sitting's `served` is.
    [:body_excerpt {:optional true
                    :x-display
                    {:widget "prose"
                     :label "The first part of the message"
                     :help "The plain words of the message, as the engine read them at research time, up to 4,000 characters. The tags, the scripts and the styles are gone. The whole message is not kept; read it with waymark_power when this is not enough."}}
     [:maybe [:string {:max 4000}]]]
    ;; …AND WHAT THE CAP REMOVED, said out loud. A reader who cannot
    ;; tell a whole message from the first page of one will trust the
    ;; page too much.
    [:body_cut {:optional true
                :x-display
                {:label "Characters the cap removed"
                 :help "How many characters of the message the cap left out. It is 0 when the excerpt is the whole message, and empty when the engine read nothing."}}
     [:maybe [:int {:min 0}]]]
    ;; STAMPED BY YES: the task this message became, as a row and not a
    ;; sentence. The ref is what makes "which of these turned into
    ;; work" answerable without reading prose.
    [:task {:optional true :kind :task
            :x-display {:label "The task it became"}}
     [:maybe :waymark/ref]]
    ;; WRITTEN BY NO, and optional on purpose: a dismissal with
    ;; nothing to say is a whole answer, and a door that demanded a
    ;; reason would teach a model to invent one.
    [:reason {:optional true
              :x-display
              {:label "Why it was dismissed"
               :help "One line, if there is one to give — \"a receipt\", \"a newsletter\", \"not for this house\". Left empty it just means no."}}
     [:maybe [:string {:max 240}]]]
    ;; THE FILTER THAT LEFT THE CHARTER (spec-seat.md § 13.8's third
    ;; row): week one's nine corrections were receipts and newsletters
    ;; that named Colton in the body, and the fix is not a sentence a
    ;; model has to remember — it is the SOURCE declining to mint a
    ;; message that carries a list-unsubscribe header. The rule lives
    ;; there and this field does not enforce it; it is here so the
    ;; source can say what it saw when it queues one anyway, and so a
    ;; household reading the queue can tell a bulk message from a real
    ;; one without opening it. Normally unset, and that is the honest
    ;; thing to say about it.
    [:list_unsubscribe {:optional true :filter #{:eq}
                        :x-display
                        {:label "Bulk mail"
                         :help "True when the message carried a list-unsubscribe header — the mark of a newsletter or a receipt. A source that declines to queue bulk mail at all never sends it, and the field stays empty."}}
     [:maybe :boolean]]]
   ;; THE BIRTH DOOR IS THE SOURCE'S (thread's and person's posture, in
   ;; a kind that has no mirror behind it): headers only, and nothing a
   ;; verdict owns. `summary`, `task` and `reason` are absent from the
   ;; create model on purpose — a form that offered them would let a
   ;; row be born already decided, and the whole claim of this kind is
   ;; that the tree was walked.
   :create-schema
   [:map
    [:message_id {:x-display
                  {:label "The message's own id"
                   :help "The id the mailbox knows this message by. It is the queue's identity — mint one row per id and the same message is never queued twice."}}
     [:string {:min 1 :max 250}]]
    [:subject {:x-display
               {:raw true
                :label "Subject"
                :help "The subject line, exactly as it arrived — no cleaning up, no guessing at what it means. Clamp it to 400 characters rather than letting a forwarded chain refuse the mint."}}
     [:string {:min 1 :max 400}]]
    [:sender {:x-display
              {:label "Who sent it"
               :help "The sender as the mailbox spells them — an address, or a name and an address."}}
     [:string {:min 1 :max 240}]]
    [:received_at {:x-display
                   {:label "When it arrived"
                    :help "The mailbox's own timestamp, not the moment the source read it — the queue is walked in the order the house was written to."}}
     :waymark/instant]
    [:list_unsubscribe {:optional true
                        :x-display
                        {:label "Bulk mail"
                         :help "True when the message carried a list-unsubscribe header. Leave it unset when it did not; a source that declines to queue bulk mail at all never sends it."}}
     [:maybe :boolean]]]
   :actions
   {;; ONE DOOR AT `queued`, AND IT IS THE ONE THAT COSTS TOKENS.
    ;; Research is where the body is read — through a power, outside
    ;; this row — and the summary is the receipt for having read it.
    :research
    {:from #{:queued} :to :researched
     :handler write-the-summary
     :input [:map
             [:summary
              {:examples ["Jen at the contractor is asking Colton to confirm the deck estimate for Thursday morning, and says she needs an answer before she orders the stain."]
               :x-display
               {:widget "prose"
                :label "What the message actually says"
                :help "Read the message, then say what is in it and what it asks of this house — in the words you would use out loud. The whole body is never stored: the engine keeps the first part of the message beside your sentence, so say what it MEANS rather than copy it. If nothing is being asked, say that; it is what the no door is for."}}
              [:string {:min 1 :max 480}]]]
     ;; :edit-shape — a first summary onto a blank row is not an edit
     ;; of one. There is no earlier value to prefill from and no
     ;; second research door, so the fence an :edit implies would be an
     ;; etag demanded of a walker for a field nobody has written.
     ;; :large-effort — the draft box that waiver asks for is a
     ;; courtesy to a person who might mis-click a textarea away. This
     ;; door is walked by a sitter in one call, and a draft it can
     ;; neither see nor lose is scaffolding for nobody.
     :waives #{:edit-shape :large-effort}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Nothing is decided here and nothing is sent — this records what the message says, and opens the two doors that answer it. The engine reads the message itself as it goes and keeps the first part of it on the row. There is no way back to unread, which is honest: you have read it."}
     :display {:label "Research" :order 1
               :description "Open the message and say what it asks — the engine keeps the first part of it beside your sentence, and the yes and no doors appear"}}

    ;; THE FIRST OF THE TWO ANSWERS. It is the only way a task is made
    ;; from this queue, and it demands the action item in one sentence
    ;; — which is the point: a seat that could file a task without
    ;; saying what the task IS is a seat that files noise.
    :yes
    {:from #{:researched} :to :action_item
     :handler yes->task
     :input [:map
             [:action_item
              {:x-display
               {:label "The one thing that needs doing"
                :help "One line, the way you would say it out loud — \"confirm Thursday with Jen\", \"send the deposit\". This becomes the task in the family's queue, so it has to make sense to whoever picks it up without the message in front of them."}}
              [:string {:min 1 :max 200}]]
             [:due_at
              {:optional true
               :x-display
               {:label "Due by, if the message named a time"
                :help "Only when the message itself names one. A date that has already gone is refused at the task's own door with the fix in its sentence — omit it, or set today."}}
              [:maybe :waymark/instant]]]
     ;; the honest blast radius: the task is born HERE, through task's
     ;; own create door, under this hand. check-touches verifies the
     ;; pair at assembly and render puts it on the wire, so nobody taps
     ;; this without being able to read what it reaches.
     :touches [{:kind :task :action :create}]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This writes a real task into the family's queue, under your name, and marks the message answered. The way back is the task's own doors — complete it or let the authority drop it; the message itself does not come back to the queue."}
     :display {:label "Yes, action item" :style :primary :order 2
               :description "Something is being asked of this house — say what it is, in one line, and it lands in the queue"}}

    ;; THE SECOND ANSWER, and it is a real one. A seat that could only
    ;; say yes would turn every newsletter into work.
    :no
    {:from #{:researched} :to :dismissed
     :handler write-the-reason
     :input [:map
             [:reason
              {:optional true
               :x-display
               {:label "Why, if it is worth saying"
                :help "One line — \"a receipt\", \"a newsletter\", \"not for this house\". Leave it blank and the answer is just no; nobody is owed an explanation for declining their own mail."}}
              [:maybe [:string {:max 240}]]]]
     ;; :edit-shape, for research's first reason: a first reason onto a
     ;; blank row is not an edit of one.
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The message leaves the queue and stays on record with whatever you said about it. It is not deleted and the mailbox is not touched — if this was the wrong call, the person whose inbox it is can reopen it."}
     :display {:label "No" :order 3
               :description "Nothing is being asked of this house — set it aside, and say why if it is worth saying"}}

    ;; THE PERSON'S OWN DOOR. Every reopen is a correction on the
    ;; record, and the count of them per transition is what decides
    ;; whether this seat's judgment needs a bigger model.
    :reopen
    {:from #{:dismissed} :to :researched
     :guards [the-correction-is-a-persons]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The message comes back to the two answers with its research intact — the summary stands, and the reason you are overruling stands beside it on the record. Nothing about the dismissal is erased; this adds the correction to it."}
     :display {:label "Reopen" :order 4
               :description "It was dismissed and it should not have been — hand it back the yes and no doors"}}}
   :scenarios [an-agent-may-not-reopen-its-own-dismissal
               the-person-reopens-what-the-clerk-dismissed
               the-tree-has-no-shortcut-past-research]
   :deviations
   ["The spec's declaration made `dismissed` terminal AND put a `reopen` door out of it. The framework refuses that pair by name — checks/check-terminal-no-exit: no action departs a tomb, and the one waiver (:allow-undo) is held to the undo shape, which is the same hand within minutes, never a person overruling an agent days later. So `dismissed` is declared in :over :let-go instead: every reader that asks whether this row's work is over gets the same yes it would have got from :terminal, and the person's correction keeps its door."]})
