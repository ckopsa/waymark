(ns workqueue10.resources.tickler
  "The tickler (waymark-iqa.4): the house's someday/maybe list, with a
  spring in it. Twenty-five of the queue's hundred-odd rows are
  `dropped` — work nobody finished and nobody decided about — and a
  dropped pile that only ever grows is a pile the household has
  already stopped reading. A tickler is the note pinned to one of
  those rows: bring this back on a date, and let me answer it in one
  tap when it comes.

  THREE ANSWERS, AND THE MIDDLE ONE IS THE POINT.

  - NOT NOW pushes the next offer out — a week, then three, then two
    months, then half a year. Each 'not now' is a fact the house keeps
    (`offer_count`), so 'I have said not-now to this four times' is
    something the household can READ rather than something one person
    half-remembers. That is the epic's reason for server-side state.
  - LET IT GO retires it for good. The tickler never returns — not
    next week, not next year — and the guilt goes with it. The row
    stands as record; the asking stops.
  - TAKE IT BACK says the opposite: this is live work again. The
    tickler stops asking, and the row itself is one tap away on its
    own screen, where its own doors are.

  WHY A MARKER KIND AND NOT A FIELD ON `task` (docs/spec-feed.md fork
  (b), decided there and inherited here). Two of the five reasons are
  disqualifying rather than merely persuasive:

  1. `task` is `:push-on-write true`. `mirror/push-after-write!`
     exports the whole document and calls the authority on ANY action
     that is not a sync door, and a failed push lands the row
     `mark_conflicted`. So a 'not now' tapped in the feed would be an
     HTTP call to Google Tasks, and Google being down would leave a
     household task CONFLICTED because somebody deferred it.
  2. `mirror/check-domain-actions!` will not let the verdict be
     spelled at all: a mirror's own actions must move between
     #{:fresh :stale :unreachable} — its machine IS the sync machine
     — and a `conflicted` row takes no local writes until a person
     decides. The tickler has to work on rows whose authority is
     sulking, which are precisely the rows most likely to be dropped.

  And one that is about the household rather than the mechanism: the
  tickler IS NOT TASK-ONLY. Abandoned media, a chore run nobody ran,
  a letter left waiting — a field on `task` cannot hold a
  household-wide someday/maybe list. A marker names {kind, id} and
  covers all of them under one law.

  WHAT THE MARKER OWES ITS SUBJECT, AND WHAT IT DOES NOT. It
  denormalizes `what` at birth — the subject's own summary, in the
  household's words — so the card reads even when the row behind it
  is gone. It never writes the subject: no verdict here touches
  another kind's row, which is the whole of reason 1 above. The way
  back to the work is a LINK, and a link is what 'take it back' hands
  you.

  WHEN A TICKLER RETIRES ITSELF. At OFFER TIME, not on a sweeper: the
  feed's `:ticklers` population reads the subject when it is about to
  card the marker, and says nothing if the subject is finished or
  gone (`waymark10.server.feed/set-aside?` is the one spelling). A
  tickler that quietly withdrew itself on a clock would be worse than
  one that stayed on the fridge; a tickler that notices at the moment
  it would have spoken is honest — and it costs no job, no lease and
  no second copy of a derivable fact.

  NO CLOCK IS READ HERE. `next-offer` is a pure function of the
  engine's own `:now` and the count of not-nows already said; the
  schedule is data, one vector, and a scenario or a test can judge it
  without a database and without waiting."
  (:require [waymark10.dsl :refer [defhandler defresource defscenario]])
  (:import (java.time Instant)))

;; ── the backoff, as data ────────────────────────────────────────────

(def backoff-days
  "How far out each 'not now' pushes the next offer: a week, three
  weeks, two months, half a year — then half a year forever.

  The first step is the law's own sentence: a not-now returns LATER,
  NOT TOMORROW. A tickler that came back the next morning would be a
  nag, and a household learns to dismiss a nag without reading it.
  The last step is the floor, and it is deliberately not infinity:
  twice a year the house looks at what it has been carrying, because
  the only honest way to never see something again is to LET IT GO,
  which is a verdict and not a slow fade."
  [7 21 60 180])

(defn days-out
  "The step this many not-nows have earned — 1-based, and the last
  step repeats. Pure, total, and judged by tickler-test."
  ^long [said]
  (long (nth backoff-days
             (min (max 0 (dec (long said))) (dec (count backoff-days))))))

(defn next-offer
  "When a tickler comes back: the engine's own `:now` plus the step.
  A pure function of two values it is HANDED — nothing here reads a
  clock, so the same inputs answer the same instant in a test, in a
  scenario and in the house."
  ^Instant [^Instant now said]
  (.plusSeconds now (* 86400 (days-out said))))

;; The 'not now' stamp: one more not-now on the record, the next offer
;; moved out by the schedule, and who said it. The verdict spells its
;; own :handler rather than taking the sugar's generic stamp, which is
;; exactly what verdict-action leaves room for ("a verdict that spells
;; its own :handler keeps it whole — the sugar never wraps") — and it
;; is the reason .4 landed before .6: nothing had exercised a verdict
;; that RETURNS to the open state, and this is what one looks like.
;; The backoff is called by NAME, so the fingerprint records the call
;; and not the schedule: changing backoff-days changes the house's
;; policy, not the kind's law surface, and tickler-test is what judges
;; it.
(defhandler push-the-offer-out [row _inp ctx]
  (let [said (inc (long (or (get-in row [:data :offer_count]) 0)))]
    (-> row
        (assoc-in [:data :offer_count] said)
        (assoc-in [:data :next_offer_at] (next-offer (:now ctx) said))
        (assoc-in [:data :answered_by] (get-in ctx [:principal :id])))))

;; ── the law, written down as scenarios ──────────────────────────────
;; All three are CHECK-TIER: no :given rows, and the verdict doors
;; carry no guards at all (:decider :anyone), so `make check-queue`
;; judges them with no database in the same breath as the usability
;; warnings.

(def ^:private a-set-aside-row
  {:what "Sand and repaint the porch railing"
   :subject_kind "task"
   :subject_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
   :set_aside_by "colton"})

(defscenario a-let-go-item-never-returns
  "Let go is let go: the house does not ask again — not next week,
   not next year. The machine itself refuses the question, with no
   guard behind it, which is the strongest way a promise can be
   kept."
  {:kind    :tickler
   :attempt :not_now
   :row     {:state :let_go :data a-set-aside-row}
   :as      {:id "colton" :type :person}
   :expect  {:refused :out-of-state
             :because "Offered"}})

(defscenario a-taken-back-item-stops-asking
  "Picked up again is also an answer. The tickler's job is over the
   moment the work is live, and it does not resume."
  {:kind    :tickler
   :attempt :not_now
   :row     {:state :taken :data a-set-aside-row}
   :as      {:id "colton" :type :person}
   :expect  {:refused :out-of-state
             :because "Offered"}})

(defscenario anyone-in-the-house-answers-a-tickler
  "A tickler has no four-eyes wall and should not have one: the
   someday/maybe list belongs to the household, so whoever is holding
   the phone when it surfaces can say 'not now' — including, and
   especially, the person who set it aside."
  {:kind    :tickler
   :attempt :not_now
   :row     {:state :offered :data a-set-aside-row}
   :as      {:id "iris" :type :person}
   :expect  {:allowed true}})

;; ── :tickler — the note on the dropped pile ─────────────────────────

(defresource tickler
  {:kind :tickler
   :plural "ticklers"
   :label-template "{data.what}"
   :summary "{data.what} · set aside by {data.set_aside_by} · {state}"
   :display {:title "Tickler"}
   ;; the way back to the work: the row's own screen, when whoever
   ;; set it aside knew the address. A marker with no href relates to
   ;; nothing and the link simply omits — the framework's own rule,
   ;; and the same posture task's :origin link takes.
   :links [{:rel "subject" :href "/#{data.subject_href}"
            :summary "The row this tickler is about, on its own screen"}]
   :decision
   {;; the ask, in the household's words — denormalized from the
    ;; subject at birth so the card still reads when the row behind
    ;; it is gone. :asks is a required string anyway, so this costs
    ;; nothing extra.
    :asks    {:field :what :max 200
              :x-display
              {:label "What you are putting off"
               :help "The work itself, in the words you would use out loud — this is what you will read when it comes back, and it has to make sense months from now with the row behind it possibly long gone."}}
    ;; stamped from the principal: who set it aside is a household
    ;; fact, not a field a caller may name for somebody else
    :by      :set_aside_by
    ;; NO WALL, AND THAT IS THE LAW. A tickler is the house answering
    ;; its own someday/maybe list; the person who set an item aside is
    ;; exactly the person who should get to say 'not now' again, so a
    ;; four-eyes wall would be backwards and a role wall would be
    ;; invented. :anyone is the sugar's spelling for a wall's absence
    ;; said out loud (waymark-iqa.4 extended :decider for this, and
    ;; moved no existing declaration's hash by one byte).
    :decider :anyone
    :stamps  {:decided-by :answered_by}
    ;; whoever set it aside reads their own ticklers and answers them
    ;; with no grant — a note you cannot answer is not a note. Humans
    ;; are unscoped and see the whole shelf either way; this is the
    ;; courtesy an agent that set something aside needs.
    :own-surface true
    :verdicts
    ;; THE NON-TERMINAL VERDICT. :not_now returns to :offered — the
    ;; first declaration anywhere that needs one. desugar-decision
    ;; permits it ("at least one verdict must leave the open state",
    ;; not all), and the two that DO leave are below.
    [{:name :not_now :to :offered
      :label "Not now" :order 1
      :handler push-the-offer-out
      ;; NOT idempotent, and the honest declaration matters: two taps
      ;; are two not-nows and push the date twice, so the door asks
      ;; for an Idempotency-Key and a double-tap replays instead of
      ;; compounding. The feed's own origin key (feed/origin-key) is
      ;; exactly that key, so a card verb pays nothing for it.
      :safety {:idempotent false :reversible false :confirm false
               :one-way "Each 'not now' pushes the next offer further out — a week, then three, then two months. Nothing is lost and nothing is deleted; the house just stops asking for a while, and remembers that you said so."}}
     {:name :let_it_go :to :let_go
      ;; …and it may say WHY (waymark-jfv.16). `:display` spelled whole
      ;; because a verdict's own display wins whole over the sugar's
      ;; label/order pair — same two facts, plus the one word that
      ;; tells a settled card to offer the four quick reasons.
      ;;
      ;; `:not_now` deliberately does NOT carry it, and the reason is
      ;; the reason kind's own `one-reason-per-verdict`: a not-now is
      ;; said again and again by design, and one row per (subject,
      ;; verdict) could only ever hold the first of them. Letting go is
      ;; said once, which is what makes it the tickler's answerable
      ;; decline. Filed (waymark-jfv.18) rather than smuggled.
      :display {:label "Let it go" :order 2 :reasons true}
      :safety {:idempotent true :reversible false :confirm false
               :one-way "This one is done being carried. It never comes back — the row stays on record, the asking stops, and if you want it again you will have to want it enough to say so."}}
     {:name :take_it_back :to :taken
      :label "Take it back" :order 3
      :safety {:idempotent true :reversible false :confirm false
               :one-way "The tickler steps out of the way — it has nothing left to ask. The work itself is on its own screen, where its own doors are; this only stops the reminders."}}]}
   ;; the marker's own fields. The engine adds what, set_aside_by and
   ;; answered_by beside them; these three are the reference and the
   ;; spring, and none of them is a note a verdict writes.
   :schema [:map
            ;; WHICH ROW, as kind + id rather than as a ref: a ref
            ;; names ONE declared kind and the tickler is deliberately
            ;; household-wide. This pair is what the population reads
            ;; when it asks whether the subject is still set aside.
            [:subject_kind {:filter #{:eq}
                            :x-display {:label "Kind of row"}}
             [:string {:min 1 :max 64}]]
            [:subject_id {:x-display {:label "Row"}}
             [:string {:min 1 :max 64}]]
            ;; the way back for a PERSON — the same fact in the shape
            ;; a link can use. Hidden: the LINK is the affordance and
            ;; a raw href in the fields is noise (task's own posture,
            ;; one field over).
            [:subject_href {:optional true :x-display {:hidden true}}
             [:maybe [:string {:max 500}]]]
            ;; WHEN IT COMES BACK. Unset means now — a tickler set
            ;; aside with no date is on the fridge already. :before is
            ;; the filter the ticklers population's question wants
            ;; ("due by now"), and the one the kind's own collection
            ;; screen offers a person.
            [:next_offer_at {:optional true :filter #{:before} :sort true
                             :x-display {:label "Comes back on"}}
             [:maybe :waymark/instant]]
            ;; HOW MANY TIMES YOU HAVE SAID NOT NOW. The household
            ;; record the epic asked for, and the backoff's only
            ;; input. Written by the verdict, never by a caller.
            [:offer_count {:optional true :filter #{:eq}
                           :x-display {:label "Times you said not now"}}
             [:maybe [:int {:min 0}]]]]
   ;; the birth door, spelled rather than projected, because the
   ;; sugar's create model would offer offer_count — a field the
   ;; verdict owns — to whoever fills the form.
   :create-schema
   [:map
    [:what {:x-display
            {:label "What you are putting off"
             :help "The work itself, in the words you would use out loud — this is what you will read when it comes back, and it has to make sense months from now with the row behind it possibly long gone."}}
     [:string {:min 1 :max 200}]]
    [:subject_kind {:x-display
                    {:label "Kind of row"
                     :help "Which sort of row this is about — task, media, chore_run. The house reads it back to see whether the work is still waiting before it asks you again."}}
     [:string {:min 1 :max 64}]]
    [:subject_id {:x-display
                  {:label "Row"
                   :help "The row's own id, the one in its address bar — the tickler holds a pointer, never a copy, so the work stays where it lives."}}
     [:string {:min 1 :max 64}]]
    [:subject_href {:optional true
                    :x-display
                    {:label "Its address"
                     :help "Where the row lives, as it appears after the site name (/api/tasks/…). Leave it blank and the tickler still works; fill it in and the card can take you straight there."}}
     [:maybe [:string {:max 500}]]]
    [:next_offer_at {:optional true
                     :x-display {:label "Bring it back on"}}
     [:maybe :waymark/instant]]]
   :scenarios [a-let-go-item-never-returns
               a-taken-back-item-stops-asking
               anyone-in-the-house-answers-a-tickler]})
