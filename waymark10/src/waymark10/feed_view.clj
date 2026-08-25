(ns waymark10.feed-view
  "The view-event door (waymark-8um.1): a member's own screen says which
  cards it showed them, and the feed's GET still writes nothing.

  Two kinds live here because they are one law. `feed_view_consent` is
  the SWITCH — off for everybody until a member turns their own on —
  and `feed_view` is the RECORD, which exists only while that switch
  is on. Splitting them across two namespaces would put the wall and
  the thing it guards in different rooms.

  ── WHY THERE IS A DOOR AT ALL ──

  The original law said the feed read writes nothing, and that half
  stands and is not negotiable: `GET /api/-/feed` is a read, a read
  that wrote would make every refresh a fact about the reader, and a
  cursor page that logged would make the archive a surveillance walk.

  What laws v3 adds is the other half. The contest the epic ratified
  needs to know what was SHOWN — a learner cannot learn about a card
  it never showed, and 'no burial without a diagnosis' cannot say a
  card kept losing without a record of it losing. That record cannot
  come from the GET. So it comes from the SCREEN, through a declared
  door, as an ordinary write with an ordinary audit trail — which is
  the one shape in this engine where a person can read what is being
  kept about them, in the collection, by its own address.

  ── PER MEMBER, BY CHOICE, AND THE SCREEN DOES NOT EVEN ASK ──

  Nothing is recorded until a member creates their own
  `feed_view_consent`. The screen reads `views.recording` off the feed
  document and does not post when it is false; the door refuses anyway
  (`the-member-turned-this-on`), and the belt is not redundant with the
  braces — a client is somebody else's code, and a promise kept only
  in a client is a promise kept only until somebody writes a second
  one.

  ── A PREVIEW LEAVES NOTHING ──

  `feed.preview_as` answers somebody ELSE's feed. Two things keep it
  from writing into their record, and neither is a courtesy:

  1. `member` is ENGINE-STAMPED from the posting principal, and
     supplying somebody else's is refused BY NAME. So there is no
     spelling of this door, by anyone, that files a view under a
     member who did not do the looking. `recipe_proposal`'s
     `proposed_by` is the precedent and the reasoning is the same one:
     a row that could name somebody else as its author is a row that
     can frame them.
  2. The document a previewer reads says `views.recording false`,
     always — `feed/document` computes it as false whenever the read
     is a preview — so the previewer's own screen has nothing to
     beacon about either. Their own feed, read normally, still
     records their own views if they turned it on; the preview simply
     is not one of them.

  ── WHAT IS DERIVED RATHER THAN WRITTEN ──

  Whether an ACTION followed a view is not a field. Every verb fired
  from a card already rides `Idempotency-Key: feed/<day>/<card_id>/
  <nonce>` (`feed/origin-key`), and `feed/origin-of` reads (day,
  card_id) back out of the audit trail. A view carries the same two
  names, spelled by the same source, so 'was this card acted on' is a
  JOIN and never a second write from a client that would have to be
  believed. The ranking formula (waymark-8um.3) is the caller of that
  join; this bead makes it possible and does not compute it.

  ── AND WHAT IS NOT KEPT ──

  Not the section (`card_id` is `section/kind/id` — reading it out of
  the id is not a query, it is a `split`). Not an `at` beside
  `created_at`, which IS the engine's clock and would be a second
  truth if it were copied. Not a dwell time, not an impression count:
  one row per member per card per day, enforced by the door, because
  the exposure is the fact and the number of times a thumb scrolled
  back past it is not."
  (:require [clojure.string :as str]
            [waymark10.declare :refer [defscenario]]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def consent-kind
  "The switch's kind keyword — the definite marker, never a name
  string."
  :feed_view_consent)

(def view-kind
  "The record's kind keyword."
  :feed_view)

;; ── the switch ──────────────────────────────────────────────────────

(g/defguard a-switch-is-your-own
  {:judges [:member]
   :reads [:principal]
   :vars [:named :you]
   :explain "This switch would be {named}'s, and you are {you}. Whether the house keeps a record of what somebody was shown is that person's own answer — nobody turns it on for anybody else, and nobody turns it on for a member who is not in the room."}
  [_row inp ctx]
  (let [named (some-> (:member inp) str str/trim not-empty)
        me (str (:id (:principal ctx)))]
    (if (or (nil? named) (= named me))
      (t/allow)
      (t/deny {:vars {:named named :you me}}))))

(g/defguard the-switch-is-your-own-hand
  {:reads [:principal]
   :vars [:whose :you]
   :open "The switch is its member's own, both ways: only they may stop their own recording, and only they may start it again. There is no administrator's door here and none is wanted."
   :explain "This is {whose}'s switch and you are {you}. A record of what somebody was shown belongs to the person it is about, and so does the decision to stop keeping it."}
  [row _inp ctx]
  (let [whose (str (get-in row [:data :member]))
        me (str (:id (:principal ctx)))]
    (if (= whose me)
      (t/allow)
      (t/deny {:vars {:whose whose :you me}}))))

(defhandler stamp-the-member
  [row ctx]
  ;; the one stamp, and it is not the caller's to give — whoever posts
  ;; is who the row is about (recipe_proposal's `proposed_by`
  ;; precedent). `a-switch-is-your-own` has already refused a body that
  ;; named somebody else; this is what makes a body that named NOBODY
  ;; land on the right person anyway.
  (assoc-in row [:data :member] (:id (:principal ctx))))

;; ── the record ──────────────────────────────────────────────────────

(g/defguard a-view-is-your-own
  {:judges [:member]
   :reads [:principal]
   :vars [:named :you]
   :explain "This would file a view under {named}, and you are {you}. A screen reports what IT showed, and it can only ever have shown its own reader — a preview of somebody else's feed is your read of their page, never their read of it."}
  [_row inp ctx]
  (let [named (some-> (:member inp) str str/trim not-empty)
        me (str (:id (:principal ctx)))]
    (if (or (nil? named) (= named me))
      (t/allow)
      (t/deny {:vars {:named named :you me}}))))

(g/defguard the-member-turned-this-on
  {:reads [:principal :feed_view_consent]
   :vars [:door]
   :open "Nothing is recorded about what a member was shown until that member turns it on, one person at a time. There is no household-wide setting and no default that is not off."
   :explain "This house is not keeping a record of what you were shown, and nothing was written. It is off for everybody until each person turns their own on, at {door} — and the same row is where you turn it off again."}
  [_row _inp ctx]
  (let [find' (:find ctx)]
    ;; the storage-free probe advertises optimistically — saved_view's
    ;; and insight's posture, and the write path always carries the
    ;; consult
    (if (nil? find')
      (t/allow)
      (let [pid (:id (:principal ctx))
            live (find' consent-kind {:member pid :state "recording"}
                        {:limit 1})]
        (if (seq live)
          (t/allow)
          (t/deny {:vars {:door "/api/feed_view_consents"}}))))))

(g/defguard this-card-is-counted-once-a-day
  {:reads [:principal :feed_view]
   :vars [:card :day]
   :open "One row per member, per card, per day. A card scrolled past three times was shown once; an exposure is a fact and an impression count is not."
   :explain "This screen already reported {card} on {day}. Nothing was written and nothing was lost — the exposure is already on the record."}
  [_row inp ctx]
  (let [find' (:find ctx)]
    (if (nil? find')
      (t/allow)
      (let [pid (:id (:principal ctx))
            card (str (:card_id inp))
            day (str (:day inp))
            already (find' view-kind {:member pid :card_id card :day day}
                           {:limit 1})]
        (if (seq already)
          (t/deny {:vars {:card card :day day}})
          (t/allow))))))

(defhandler stamp-the-viewer
  [row ctx]
  (assoc-in row [:data :member] (:id (:principal ctx))))

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; TWO TIERS, read off the declarations rather than chosen. The
;; switch's own doors read nothing but the caller, so both of its walls
;; are judged for free in `check`, beside the usability warnings. The
;; record's create door reads ROWS — the switch, and this member's own
;; day — so its two scenarios defer to the suite, and they are the two
;; that name the walls a household would actually meet.

(defscenario nobody-turns-the-recording-on-for-somebody-else
  "The switch is the first-person one. A body naming another member is
   refused where it is written rather than quietly re-stamped, because
   'I turned it on for you' and 'you turned it on' are different
   sentences and only one of them is consent."
  {:kind    :feed_view_consent
   :attempt :create
   :input   {:member "colton"}
   :as      {:id "iris" :type :person}
   :expect  {:refused :a-switch-is-your-own
             :because "own answer"}})

(defscenario nobody-stops-somebody-elses-recording
  "…and the same sentence in reverse. A record of what somebody was
   shown belongs to the person it is about; there is no administrator's
   hand on this switch, in either direction."
  {:kind    :feed_view_consent
   :attempt :stop
   :row     {:state :recording :data {:member "colton"}}
   :as      {:id "iris" :type :person}
   :expect  {:refused :the-switch-is-your-own-hand
             :because "belongs to the person"}})

(defscenario a-view-is-never-filed-under-somebody-else
  "The preview wall, said as law rather than as a client's manners: a
   previewer reading another member's feed cannot file what they saw
   under that member. The field is engine-stamped anyway — this is the
   refusal that gives the impossibility a name."
  {:kind    :feed_view
   :attempt :create
   :input   {:member "colton"
             :card_id "do_now/task/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
             :population "next_actions"
             :day "2026-08-26"}
   :as      {:id "iris" :type :person}
   :expect  {:refused :a-view-is-your-own
             :because "showed"}})

(defscenario no-record-until-the-member-asks-for-one
  "Off for everybody until each person turns their own on. The refusal
   names the switch, because a door that refuses without saying where
   the key is has told a person only that they failed."
  {:kind    :feed_view
   :attempt :create
   :input   {:card_id "do_now/task/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
             :population "next_actions"
             :day "2026-08-26"}
   :as      {:id "iris" :type :person}
   :expect  {:refused :the-member-turned-this-on
             :because "not keeping a record"}})

;; ── the prose the doors wear ────────────────────────────────────────
;;
;; Spelled once and worn by both the row schema and the create model,
;; the way feed_recipe's and recipe_proposal's own `prose` maps are.

(def ^:private prose
  {:member
   {:x-display
    {:raw true
     :label "Whose"
     :help "The member this is about — written by the engine from whoever posted it, never typed. A row that could name somebody else is a row that can frame them."}}
   :card_id
   {:examples ["do_now/task/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
    :x-display
    {:raw true
     :label "The card"
     :help "The card's own identity within the day — section/kind/id, exactly as the feed document spells it at card_id. It is kept whole because that is the name the audit trail already uses when a verb is fired from a card, and two names for one card would be two answers to one question."}}
   :population
   {:x-display
    {:raw true
     :label "Which line drew it"
     :help "The recipe population this card came out of — next_actions, letters, memories. Kept beside the card because a card id does not carry it and the contest is measured line by line."}}
   :day
   {:x-display
    {:label "The feed's day"
     :help "The day the feed was seeded for, copied from the document's own day. Not the clock: the clock is created_at and the engine writes that. This is the bucket, and it is spelled by the same source that spells it in the audit trail so the two sides of that join line up."}}})

(defn- entry [k extra form]
  [k (merge (get prose k) extra) form])

(defresource feed-view-consent
  {:kind :feed_view_consent
   :plural "feed_view_consents"
   :nav :system
   :states [:recording :stopped]
   :initial :recording
   ;; NOT terminal, either of them: stopping and starting again are the
   ;; same decision said twice, and a household setting that could only
   ;; ever be flipped once would be a trap rather than a switch.
   :terminal #{}
   ;; ONE SWITCH PER MEMBER, enforced by the storage rather than by a
   ;; wall: a second row would let 'off' be half-true, because the door
   ;; below asks whether ANY of this member's rows is recording. The
   ;; unique index is the cheapest honest answer and it costs the
   ;; create door no tier — both of its walls still read only the
   ;; caller, so both are judged with no database at all.
   :unique [[:member]]
   :summary "{data.member} · {state}"
   :label-template "{data.member}"
   :display {:title "Keeping a record of what the feed showed you"}
   :schema
   [:map (entry :member {:optional true :filter #{:eq}}
                [:maybe [:string {:max 128}]])]
   ;; :member is in the CREATE model and then stamped over, and the
   ;; redundancy is deliberate. Left out, a body that named somebody
   ;; else would be refused by the closed schema as a stray key — and
   ;; "unknown field" is not the sentence this law wants to say. In,
   ;; the refusal has a name, a household sentence and a scenario.
   :create-schema
   [:map (entry :member {:optional true} [:maybe [:string {:max 128}]])]
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :create-guards [a-switch-is-your-own]
   :on-create stamp-the-member
   ;; the switch is yours to read and yours to flip, with no grant —
   ;; and there is deliberately no `create` here: an agent that could
   ;; mint its own is harmless, but it would still be a door advertised
   ;; to somebody with no screen.
   :own-surface {:by :member :actions #{:stop :resume}}
   :actions
   {:stop {:from #{:recording} :to :stopped
           :guards [the-switch-is-your-own-hand]
           :safety {:idempotent true :reversible true :confirm false}
           :display {:label "Stop recording" :order 1
                     :description "Nothing further is written about what your screen shows you. What is already written stays — this stops the keeping, it does not erase the record"}}
    :resume {:from #{:stopped} :to :recording
             :guards [the-switch-is-your-own-hand]
             :safety {:idempotent true :reversible true :confirm false}
             :display {:label "Start recording again" :order 2
                       :description "Your screen goes back to telling the house which cards it showed you"}}}
   :scenarios [nobody-turns-the-recording-on-for-somebody-else
               nobody-stops-somebody-elses-recording]})

(defresource feed-view
  {:kind :feed_view
   :plural "feed_views"
   :nav :system
   ;; ONE STATE, and it is both the initial and the terminal one. A
   ;; view is not a thing that happens to a row over time — it happened
   ;; or it did not — so there is no verb here, and the absence is the
   ;; declaration rather than an omission. Nobody edits what they were
   ;; shown.
   :states [:recorded]
   :initial :recorded
   :terminal #{:recorded}
   ;; ONE ROW PER MEMBER, PER CARD, PER DAY — the same rule
   ;; `this-card-is-counted-once-a-day` says in the household's words,
   ;; said again in the storage's, and the pair is belt and braces for
   ;; the ordinary reason: the guard is the sentence a person reads,
   ;; the index is the fact under a race.
   ;;
   ;; THE ORDER OF THE THREE FIELDS IS THE ONLY INDEX THIS DECLARATION
   ;; CAN ASK FOR, so it is spent on the reader that is coming. A
   ;; declared `:unique` group is the one index beyond state, law and
   ;; the sortable clocks that `store/kind-projection` will emit, and
   ;; the ranking formula (waymark-8um.3) aggregates BY CARD over a
   ;; window of days — so `(card_id, day, member)` is the shape its
   ;; reads want. What that costs is the member's own read: a person
   ;; asking for their own view rows walks the table. That is affordable
   ;; on a table this bounded (see the growth math in docs/spec-feed.md)
   ;; and it is the honest trade rather than a hidden one.
   :unique [[:card_id :day :member]]
   :summary "{data.member} was shown {data.card_id} · {data.day}"
   :label-template "{data.card_id}"
   :display {:title "A card this screen showed"}
   :schema
   [:map
    (entry :member {:optional true :filter #{:eq}}
           [:maybe [:string {:max 128}]])
    (entry :card_id {:filter #{:eq}} [:string {:min 3 :max 200}])
    ;; a plain string, not an enum over the population registry, and
    ;; the reason is what this row IS: a record of what a feed
    ;; answered on a day. A record whose schema refused a population
    ;; the engine has since renamed would be a record that could not
    ;; be written, which is the wrong way round.
    (entry :population {:filter #{:eq}} [:string {:min 1 :max 40}])
    (entry :day {:filter #{:eq :range}} :waymark/date)]
   :create-schema
   [:map
    (entry :member {:optional true} [:maybe [:string {:max 128}]])
    (entry :card_id {} [:string {:min 3 :max 200}])
    (entry :population {} [:string {:min 1 :max 40}])
    (entry :day {} :waymark/date)]
   :filterable {:state #{:eq :in}}
   ;; the two names the formula aggregates by (waymark-8um.3 — by card,
   ;; and by population) are promoted columns, so its reads are index
   ;; scans rather than a table walk. :day is promoted too, which is
   ;; what makes both the per-draw window and any later purge a query.
   :sortable {:fields [:created_at :day] :default "-created_at"}
   ;; SHAPE FIRST, WORLD NEXT — insight's ordering, inherited whole. A
   ;; body that names somebody else hears about itself before it hears
   ;; anything about the house, and because the last wall counts ROWS a
   ;; refused create spends nothing.
   :create-guards [a-view-is-your-own
                   the-member-turned-this-on
                   this-card-is-counted-once-a-day]
   :on-create stamp-the-viewer
   ;; READ-ONLY, and the empty :actions is most of the point: your own
   ;; views are yours to read at their own address. A composer that
   ;; needs them for the diagnosis duty (waymark-8um.4) reads them
   ;; through an ordinary grant the household approves by name —
   ;; `{:kind "feed_view" :actions []}` — which is the insight
   ;; precedent, and it confers reading and nothing else because there
   ;; is nothing else to confer.
   :own-surface {:by :member :actions #{}}
   :actions {}
   :scenarios [a-view-is-never-filed-under-somebody-else
               no-record-until-the-member-asks-for-one]})
