(ns waymark10.server.held-calls
  "The held call (docs/spec-mcp-servers.md R-14, bead
  waymark-fp62.10.2): a tool call that waits on a person, where the
  ROW IS BOTH THE NOTICE AND THE RECORD.

  THE DECISION. A `powers` entry on an `mcp_server` row says
  `approval`: `none`, `why` or `person` (mcp-servers/approval-of).
  `none` and `why` are the door this engine always had. `person` is
  this kind: the power door does not forward the call at all. It mints
  one `held_call` row and answers the caller at once with
  {held true, held_call <id>, note \"waiting on a person's tap\"}.
  That answer is an ANSWER, not a refusal (R-2): it counts as one
  served answer of its own size on the sitting, and as no refusal.

  THE WALLS ARE A DECISION KIND'S, REUSED BY NAME (R-3). Nobody
  allows their own call. `caller` is stamped at birth, and
  `the-caller-does-not-decide` is guards/not-the-field over it. The
  second wall is a ROLE, `approver`, which is guards/role. Two
  doors stand behind them: `allow` and `refuse {reason}`.

  WHY THE FORWARD IS NOT IN THE ALLOW HANDLER. A handler cannot
  choose its door's destination (`invoke/finish!` writes `(:to
  defn)`), and an allow that reaches a server has two honest endings:
  the answer, and the wire failure. It also must not hold a row FOR
  UPDATE across a call that may take the client's whole timeout. So
  the person's tap lands `allowed`, which says that a person said yes
  and the engine has the call. Then `after-allow!`, at the wire
  boundary beside grants/approval-effects!, forwards once and walks
  `land` (to `done`, with the capped answer on the row) or `fail` (to
  `failed`, with the reason).
  That is one state more than the bead's R-1 list, and it is the one
  place this build deviates; the deviation is on the kind.

  THE FORWARD IS THE POWER DOOR'S OWN. `forward` is written at birth:
  the arguments gate-proxy/invoke-for had already prepared. The
  filter's `allow` globs are added, and the `why` is translated or
  removed for the row's server. So the allow forwards EXACTLY what would have gone
  out at call time, and no grant is re-read at a moment the call was
  not judged in.

  THE ENGINE'S OWN HAND NEVER HOLDS (R-10). `mcp-servers/call!`,
  `rpc-of` and gate-proxy/power-of reach a server without passing
  `invoke-for`, so a source, the `:power` hook and the bench helpers
  are not callers and never mint a row here.

  NOTHING RUNS LATE (R-7). `expires_at` is stamped at birth, 24 hours
  by default, and `sweep-expired!` walks the `expire` door over every
  held row past it. The sweep also fails a row that a stopped engine
  left `allowed` past its expiry, because a call nobody finished must
  not look like one somebody is about to."
  (:require [clojure.string :as str]
            [waymark10.declare :refer [defscenario]]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.nio.charset StandardCharsets)
           (java.time Instant)
           (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

;; ── the engine's own hand ───────────────────────────────────────────

(def engine-actor
  "The system actor that mints a held call, lands its answer, records
  its wire failure and expires it. A person's hand writes `allow` and
  `refuse`, and nothing else on this kind."
  (t/principal {:id "waymark10-held-calls" :type :system
                :display "Held calls"}))

(def approver-role
  "The role a person must hold to answer a held call.

  SPELLED HERE, ONCE. The bead names it \"the approver role of the
  engine's approval_request door\"; that door carries the four-eyes
  wall and no role today, so there was no word to borrow and this
  kind writes the word the design asks for. A deployment mints one
  `role` row named `approver` and gives it to the people who may tap.
  A house with no such row has nobody who can answer, which is a
  configuration a person fixes in one create, not a law this kind
  bends."
  "approver")

(def default-ttl-seconds
  "How long a held call waits before the sweep expires it: 24 hours
  (R-7). A call nobody answered in a day is a call whose moment has
  passed, and running it late would surprise everybody."
  86400)

(def answer-cap-bytes
  "The ceiling on the answer a held call keeps, in UTF-8 bytes (R-1).
  The row is a record of what a person allowed, not a mailbox: a
  larger answer is cut here and the bytes that went are counted in
  `answer_dropped`, so a reader adding the two reads what the server
  said."
  16384)

(def ^:private sweep-cap
  "How many rows one expiry pass reads. The pass runs on a cadence, so
  an overflow is picked up by the next one."
  500)

(def ^:private shown-value-chars
  "How much of one shown value the person's line carries. A held
  call's summary is one line, and the whole budget for a summary is
  140 characters."
  40)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 held-calls: " parts))))

;; ── the text the row carries ────────────────────────────────────────

(defn capped
  "`s`, cut to `limit` UTF-8 bytes → {:text :dropped}. `dropped` is
  how many bytes went.

  The cut is by BYTES and not by characters, because the ceiling is
  about storage. A cut that lands inside a multi-byte character
  leaves that one character unreadable, which is the honest cost of a
  byte ceiling and is why `dropped` says how much is missing."
  [s limit]
  (let [b (.getBytes (str s) StandardCharsets/UTF_8)
        n (alength b)]
    (if (<= n (long limit))
      {:text (str s) :dropped 0}
      {:text (String. b 0 (int limit) StandardCharsets/UTF_8)
       :dropped (- n (long limit))})))

(defn- short-value
  "One shown value, as a person reads it on the line."
  [v]
  (let [s (str/trim (str/replace (str v) #"\s+" " "))]
    (if (< shown-value-chars (count s))
      (str (subs s 0 shown-value-chars) "…")
      s)))

(defn shown-text
  "The person's own line about this call (R-8): the input fields the
  entry marked as `shown`, each as `field=value`, joined.

  An entry that marks none leaves the line to the WHY, because the
  row is the notice and a notice that says only which tool would run
  tells a person nothing about what it would do."
  [entry input why]
  (let [fields (servers/shown-fields entry)
        pairs (into []
                    (keep (fn [f]
                            (when-some [v (get input (keyword f))]
                              (str f "=" (short-value v)))))
                    fields)]
    (if (seq pairs)
      (str/join " · " pairs)
      (short-value why))))

;; ── the answer the caller reads at once (R-2) ───────────────────────

(def held-note
  "The sentence the caller reads instead of the tool's answer."
  "waiting on a person's tap")

(defn held-answer
  "The CallToolResult a held call answers with. It is NOT an error
  result: nothing was refused, and a caller that read this as a
  refusal would learn the wrong lesson and stop asking."
  [row-id]
  (let [body {:held true :held_call (str row-id) :note held-note}]
    {:content [{:type "text" :text (wire/write-json body)}]
     :structuredContent body
     :isError false}))

;; ── guards ──────────────────────────────────────────────────────────

(g/defguard the-power-door-mints-it
  {:reads [:principal]
   :hide true
   :explain "The power door mints a held call when a powers entry says approval person; no hand at the wire writes one."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(g/defguard the-engine-finishes-it
  {:reads [:principal]
   :hide true
   :explain "The engine lands the answer of a call a person allowed, and records the wire failure and the expiry; no hand at the wire does."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(def the-caller-does-not-decide
  "The first wall, a decision kind's own: whoever made the call
  cannot be the one who answers it. `caller` is stamped at birth by
  the power door, so there is no earlier transition to be the actor
  of and four-eyes by ACTOR could say nothing here."
  (assoc (g/not-the-field
          :caller
          {:name :the-caller-does-not-decide
           :explain "The caller of this tool call cannot be the one who allows or refuses it; another person decides."})
         :open (str "The wall is about WHO, not about a field of this "
                    "door: there is nothing to send that opens it. Ask "
                    "somebody who holds the approver role to answer.")))

(defn- the-owner?
  "Is this principal the PERSON a door call was held for? The person
  themselves, or a tool that person is signed in to — and never a
  seat's sitter, whose id is `seat:<the seat>` (seats/sitter-id) and
  who acts for the same person: the author does not approve itself."
  [p owner]
  (and (some? owner)
       (not (str/starts-with? (str (:id p)) "seat:"))
       (or (and (= :human (:type p)) (= owner (str (:id p))))
           (and (= :agent (:type p)) (= owner (str (:acts-for p)))))))

(g/defguard an-approver-decides
  {:reads [:principal]
   :open "The role is a `role` row and a member's own list, and the person a seat call waits on is the row's own owner; no field of this door confers either."
   :explain "A person who holds the approver role answers a held tool call, and a held seat call is answered by the person it was held for. Somebody else's call is waiting, and the tap is theirs to make."}
  [row _inp ctx]
  ;; The second wall, a decision kind's own, grown by one reading
  ;; (server/delegation). A call held at a SEAT OR JUDGMENT DOOR names
  ;; the person its author acts for, and that person alone answers it:
  ;; the delegation is theirs, so the approver role is not enough. A
  ;; held TOOL call is the role's, as it always was.
  (let [p (:principal ctx)
        owner (some-> (get-in row [:data :owner]) str not-empty)]
    (if (if (some? (get-in row [:data :door]))
          (the-owner? p owner)
          (contains? (:roles p) approver-role))
      (t/allow)
      (t/deny))))

(def ^:private decider-walls
  "The two walls, apart rather than folded. The order is the refusal
  order: `not you` is the sentence the caller most needs, and the
  role is the sentence everybody else needs."
  [the-caller-does-not-decide an-approver-decides])

;; ── handlers ────────────────────────────────────────────────────────

(defn- stamp-decider [row ctx]
  (update row :data assoc
          :decided_by (get-in ctx [:principal :id])
          :decided_at (:now ctx)))

(defhandler record-allow [row _inp ctx]
  (stamp-decider row ctx))

(defhandler record-refusal [row inp ctx]
  (assoc-in (stamp-decider row ctx) [:data :reason] (:reason inp)))

(defhandler record-answer [row inp _ctx]
  (update row :data assoc
          :answer (:answer inp)
          :answer_dropped (long (or (:dropped inp) 0))))

(defhandler record-failure [row inp _ctx]
  (assoc-in row [:data :reason] (:reason inp)))

(defn- born
  "The birth stamps: the leash the caller never chose. `expires_at`
  is written AT CREATE, so the person who reads the row reads the
  moment it will actually stop waiting."
  [row ctx]
  (update-in row [:data :expires_at]
             #(or % (.plusSeconds ^Instant (:now ctx)
                                  (long default-ttl-seconds)))))

;; ── scenarios ───────────────────────────────────────────────────────
;;
;; All four are CHECK-TIER: each wall declares :reads [:principal] and
;; neither needs a :given row, so `check` judges them with no database
;; at all.

(def ^:private a-held-send
  {:tool "emila__send"
   :caller "mail-clerk"
   :why "The household asked for the reply to go out today."
   :input {:to "otto@example.test" :text "On my way."}
   :forward {:to "otto@example.test" :text "On my way."}
   :shown "to=otto@example.test · text=On my way."})

(defscenario the-caller-does-not-allow-its-own-call
  "The agent that made the call cannot be the person who allows it.
   It is the wall the decision kinds wrote first, one kind over."
  {:kind    :held_call
   :attempt :allow
   :row     {:state :held :data a-held-send}
   :as      {:id "mail-clerk" :type :agent :roles #{"approver"}}
   :expect  {:refused :the-caller-does-not-decide
             :because "cannot be the one who allows"}})

(defscenario an-approver-allows-the-call
  "A person who holds the role, and did not make the call, taps
   allow; the engine then forwards it once."
  {:kind    :held_call
   :attempt :allow
   :row     {:state :held :data a-held-send}
   :as      {:id "colton" :type :human :roles #{"approver"}}
   :expect  {:allowed true}})

(defscenario a-person-without-the-role-does-not-decide
  "Not the caller, which gets past the first wall, and no approver
   role, which does not get past the second."
  {:kind    :held_call
   :attempt :refuse
   :row     {:state :held :data a-held-send}
   :input   {:reason "not this one"}
   :as      {:id "iris" :type :human}
   :expect  {:refused :an-approver-decides
             :because "approver role"}})

(defscenario an-approver-refuses-with-a-reason
  "A no is a record too, and it carries the sentence the caller
   reads on the row."
  {:kind    :held_call
   :attempt :refuse
   :row     {:state :held :data a-held-send}
   :input   {:reason "Otto already knows."}
   :as      {:id "colton" :type :human :roles #{"approver"}}
   :expect  {:allowed true}})

;; ── the kind ────────────────────────────────────────────────────────

(def ^:private reason-input
  [:map
   [:reason {:x-display
             {:label "Why not"
              :help "One sentence the caller reads on the row. A refusal with no words is a wall the caller cannot learn from."}}
    [:string {:min 1 :max 240}]]])

(defresource held-call
  {:kind :held_call
   :plural "held_calls"
   :nav :system
   ;; A PERSON SAID YES AND THE ENGINE HAS THE CALL is a state of its
   ;; own (`allowed`), and the deviation is recorded below.
   :states [:held :allowed :done :refused :failed :expired]
   :initial :held
   :terminal #{:done :refused :failed :expired}
   :summary "{data.tool} · {data.caller} · {data.shown} · {state}"
   :label-template "{data.tool}"
   ;; THE CALLER READS ITS OWN ROW WITH NO GRANT (R-6). An agent that
   ;; was told its call is waiting must be able to read the answer,
   ;; and an ask you cannot read the answer to is not an ask. It
   ;; opens no DOOR: the two verdicts are a person's, and the caller
   ;; meets the wall's honest 409 rather than a mute 404 when it
   ;; tries.
   :own-surface {:by :caller :actions #{}}
   :schema
   [:map
    [:server {:optional true
              :kind :mcp_server
              :x-display {:label "The server"
                          :help "The mcp_server row whose client would make this call."}}
     [:maybe :waymark/ref]]
    [:tool {:x-display {:raw true
                        :label "The tool"
                        :help "The prefixed tool name the caller asked for, as the power door resolved it."}}
     [:string {:min 1 :max 120}]]
    [:input {:optional true
             :x-display {:label "The call"
                         :help "The call's arguments, as the caller gave them. This is what a person is deciding about."}}
     [:maybe [:map-of :keyword :any]]]
    ;; WHAT THE SERVER WOULD RECEIVE, which is not always what the
    ;; caller typed: the power door adds the filter's `allow` globs
    ;; and translates or removes the `why` for this row's server. It
    ;; is written at birth so the allow forwards exactly what the
    ;; call-time judgment prepared, and it is hidden because nobody
    ;; edits it.
    [:forward {:optional true
               :x-display {:hidden true :label "What the server receives"}}
     [:maybe [:map-of :keyword :any]]]
    [:why {:x-display
           {:label "Why"
            :help "The caller's one sentence of reason. An approval that held a call and had nothing to show would be a notice with no words on it."}}
     [:string {:min 1 :max 240}]]
    [:caller {:x-display {:raw true
                          :label "Who called"
                          :help "The principal whose call this is, stamped by the power door. It is the field the first wall reads."}}
     [:string {:min 1 :max 128}]]
    [:sitting {:optional true
               :kind :sitting
               :x-display {:raw true
                           :label "The sitting"
                           :help "The open sitting the call was made in, when the session sits in a seat. A person's own hand carries none."}}
     [:maybe :waymark/ref]]
    [:shown {:optional true
             :x-display {:label "What it would do"
                         :help "The fields the powers entry marks as shown, on one line. It is the person's whole reading of the call."}}
     [:maybe [:string {:max 140}]]]
    ;; A SEAT OR JUDGMENT DOOR THAT WAITS ON A PERSON
    ;; (server/delegation). When this is present the call is not a
    ;; tool call: it is a write a delegating seat's sitter asked for,
    ;; and the allow replays it at this door, as that sitter, with
    ;; `forward` as its body. `author` is the seat it was written for.
    [:door {:optional true
            :x-display {:label "The door"
                        :help "The kind, the action and the row a held seat call would write, and the seat that asked."}}
     [:maybe [:map
              [:kind {:x-display {:raw true :label "Kind"}} [:string {:min 1 :max 64}]]
              [:action {:x-display {:raw true :label "Action"}} [:string {:min 1 :max 64}]]
              [:id {:optional true :x-display {:raw true :label "Row"}}
               [:maybe [:string {:min 1 :max 128}]]]
              [:author {:optional true :x-display {:raw true :label "Asked by the seat"}}
               [:maybe [:string {:min 1 :max 128}]]]
              ;; the version the author read, for a fenced door: the
              ;; replay presents it, so a row that moved between the ask
              ;; and the tap refuses rather than being written over
              [:if_match {:optional true :x-display {:raw true :label "Read at"}}
               [:maybe [:string {:min 1 :max 256}]]]]]]
    [:owner {:optional true
             :x-display {:raw true
                         :label "Waits on"
                         :help "The person a held seat call waits on: the one its author acts for. Only they answer it."}}
     [:maybe [:string {:min 1 :max 128}]]]
    [:expires_at {:optional true
                  :x-display {:label "Waits until"
                              :help "When the sweep expires this call. Stamped at birth, 24 hours out, so the person reads the moment it will actually stop waiting."}}
     [:maybe :waymark/instant]]
    [:answer {:optional true
              :x-display {:widget "prose"
                          :label "What the server answered"
                          :help "The tool's answer after the allow, cut to 16 KB. The caller reads it here."}}
     [:maybe [:string {:max 16384}]]]
    [:answer_dropped {:optional true
                      :x-display {:label "Answer bytes dropped"
                                  :help "How many bytes of the answer the ceiling cut. Add it to the answer to see what the server said."}}
     [:maybe [:int {:min 0}]]]
    [:reason {:optional true
              :x-display {:label "Why not"
                          :help "The person's sentence on a refusal, or the engine's on a wire failure."}}
     [:maybe [:string {:max 240}]]]
    [:decided_by {:optional true
                  :x-display {:raw true :label "Who decided"}}
     [:maybe [:string {:max 128}]]]
    [:decided_at {:optional true :x-display {:label "When"}}
     [:maybe :waymark/instant]]]
   ;; THE CREATE MODEL IS THE BIRTH AND NOTHING ELSE. What a verdict
   ;; and the engine's own endings write is not the power door's to
   ;; supply, so the model omits the five of them. It is the posture
   ;; the decision sugar takes one kind over, spelled by hand because
   ;; this kind's machine is not a decision's.
   :create-schema
   [:map
    [:server {:optional true :kind :mcp_server
              :x-display {:label "The server"
                          :help "The mcp_server row whose client would make this call."}}
     [:maybe :waymark/ref]]
    [:tool {:x-display {:raw true :label "The tool"
                        :help "The prefixed tool name the caller asked for, as the power door resolved it."}}
     [:string {:min 1 :max 120}]]
    [:input {:optional true
             :x-display {:label "The call"
                         :help "The call's arguments, as the caller gave them."}}
     [:maybe [:map-of :keyword :any]]]
    [:forward {:optional true
               :x-display {:hidden true :label "What the server receives"}}
     [:maybe [:map-of :keyword :any]]]
    [:why {:x-display {:label "Why"
                       :help "The caller's one sentence of reason, which the person who taps reads."}}
     [:string {:min 1 :max 240}]]
    [:caller {:x-display {:raw true :label "Who called"
                          :help "The principal whose call this is."}}
     [:string {:min 1 :max 128}]]
    [:sitting {:optional true :kind :sitting
               :x-display {:raw true :label "The sitting"
                           :help "The open sitting the call was made in, when the session sits in a seat."}}
     [:maybe :waymark/ref]]
    [:shown {:optional true
             :x-display {:label "What it would do"
                         :help "The fields the powers entry marks as shown, on one line."}}
     [:maybe [:string {:max 140}]]]
    ;; A SEAT OR JUDGMENT DOOR THAT WAITS ON A PERSON
    ;; (server/delegation). When this is present the call is not a
    ;; tool call: it is a write a delegating seat's sitter asked for,
    ;; and the allow replays it at this door, as that sitter, with
    ;; `forward` as its body. `author` is the seat it was written for.
    [:door {:optional true
            :x-display {:label "The door"
                        :help "The kind, the action and the row a held seat call would write, and the seat that asked."}}
     [:maybe [:map
              [:kind {:x-display {:raw true :label "Kind"}} [:string {:min 1 :max 64}]]
              [:action {:x-display {:raw true :label "Action"}} [:string {:min 1 :max 64}]]
              [:id {:optional true :x-display {:raw true :label "Row"}}
               [:maybe [:string {:min 1 :max 128}]]]
              [:author {:optional true :x-display {:raw true :label "Asked by the seat"}}
               [:maybe [:string {:min 1 :max 128}]]]
              ;; the version the author read, for a fenced door: the
              ;; replay presents it, so a row that moved between the ask
              ;; and the tap refuses rather than being written over
              [:if_match {:optional true :x-display {:raw true :label "Read at"}}
               [:maybe [:string {:min 1 :max 256}]]]]]]
    [:owner {:optional true
             :x-display {:raw true
                         :label "Waits on"
                         :help "The person a held seat call waits on: the one its author acts for. Only they answer it."}}
     [:maybe [:string {:min 1 :max 128}]]]
    [:expires_at {:optional true
                  :x-display {:label "Waits until"
                              :help "When the sweep expires this call. The engine stamps it at birth, 24 hours out."}}
     [:maybe :waymark/instant]]]
   ;; `caller` IS FILTERABLE BECAUSE A SEAT'S WAKE NEEDS IT (R-6): a
   ;; `wake_on` entry {held_call [allow refuse] filter {caller …}}
   ;; wakes the seat when the person decides its OWN call and not
   ;; somebody else's, and `wakes/moved-under?` compiles that filter
   ;; through the kind's own query grammar. `server` is deliberately
   ;; absent: it is a `:maybe` ref, so it promotes no column for a
   ;; filter to walk, and a filter that cannot be answered is worse
   ;; than one nobody offered.
   :filterable {:state #{:eq :in}
                :caller #{:eq}
                :tool #{:eq}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :default-filters {:state "held"}
   :create-guards [the-power-door-mints-it]
   :on-create born
   :actions
   {:allow
    {:from #{:held} :to :allowed
     :guards decider-walls
     ;; no :record here: the door takes no input, so there is nothing
     ;; to retain. The transition itself is the record of the tap, with
     ;; the actor and the instant on it, and `refuse` records because
     ;; its reason is the thing the caller reads back.
     :handler record-allow
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The engine forwards the call once and keeps what the server answered. A held call is answered one time; a second allow is refused because the row is no longer held."}
     :display {:label "Allow" :style :primary :order 1
               :description "Let this call go out, and keep what the server answers on the row"}}
    :refuse
    {:from #{:held} :to :refused
     :input reason-input
     :guards decider-walls
     :record true
     :handler record-refusal
     :edit {:prefill [:reason] :fence false
            :unfenced-reason "A refusal's reason is written once with the verdict; a held call has nothing here to clobber."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The call never goes out, and the reason stays on the row for the caller to read. Asking differently is a new call."}
     :display {:label "Refuse" :style :danger :order 2
               :description "Stop this call, and say why in one sentence the caller reads"}}
    ;; the two endings the ENGINE writes after a person's yes
    :land
    {:from #{:allowed} :to :done
     :input [:map
             [:answer {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 16384}]]]
             [:dropped {:optional true :x-display {:hidden true}}
              [:maybe [:int {:min 0}]]]]
     :guards [the-engine-finishes-it]
     :handler record-answer
     :edit {:fence false
            :unfenced-reason "The engine's own landing of what the server answered. No read preceded it to fence against."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The call has gone out and the answer is on the row."}
     :display {:label "Answered"}}
    :fail
    {:from #{:allowed} :to :failed
     :input [:map
             [:reason {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 240}]]]]
     :guards [the-engine-finishes-it]
     :handler record-failure
     :edit {:fence false
            :unfenced-reason "The engine's own record of a call that did not reach its server. No read preceded it to fence against."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The call did not reach its server. Making it again is a new call."}
     :display {:label "Failed"}}
    :expire
    {:from #{:held :allowed} :to :expired
     :guards [the-engine-finishes-it]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Nothing runs late: an expired call never goes out, and making it again is a new call."}
     :display {:label "Expired"}}}
   :scenarios [the-caller-does-not-allow-its-own-call
               an-approver-allows-the-call
               a-person-without-the-role-does-not-decide
               an-approver-refuses-with-a-reason]
   :deviations
   ["R-1 lists five states and this kind has six. `allowed` stands between the person's tap and the ending, because a handler cannot choose its door's destination and an allow that reaches a server has two honest endings: the answer, and the wire failure. It also keeps a call that may take the client's whole timeout OUT of the transaction that holds the row. The person's tap lands `allowed`; `after-allow!` forwards at the wire boundary and walks `land` or `fail`."
    "R-3 names \"the approver role of the engine's approval_request door\". That door carries the four-eyes wall and no role, so there was no word to borrow: this kind spells `approver` itself (`approver-role`) and a deployment mints the `role` row."
    "R-1 names `input` as the call's arguments. The row carries a second, hidden map, `forward`: what the server would actually receive, prepared by the power door at call time (the filter's `allow` globs, the `why` translated or removed). Without it the allow would have to re-judge a grant at a moment the call was never judged in."
    "R-7 names the expiry of a HELD call. The sweep also expires a row a stopped engine left `allowed` past its moment, because a call nobody finished must not look like one somebody is about to."]})

;; ── the mint (R-2) ──────────────────────────────────────────────────

(defn hold!
  "Mint the held call the power door refuses to forward, and answer
  the caller. → {:row :answer}.

  Every field is the door's own knowledge at call time: the row that
  would have served the call, the tool as the door resolved it, the
  arguments as the caller gave them, the arguments the server would
  have received, the why, the caller and the sitting it sat in.

  It is the ENGINE's write (`the-power-door-mints-it`), so the
  transition names the engine and the `caller` field names the
  caller. A hand at the wire cannot reach this door at all."
  [eng {:keys [server tool input forward why caller sitting entry]}]
  (let [why (:text (capped why 240))
        row (:row (inv/create!
                   eng :held_call
                   (cond-> {:tool (str tool)
                            :why why
                            :caller (str caller)
                            :input (or input {})
                            :forward (or forward {})
                            :shown (:text (capped (shown-text entry input why)
                                                  140))}
                     (some-> server str not-empty) (assoc :server (str server))
                     (some-> sitting str not-empty) (assoc :sitting (str sitting)))
                   {:principal engine-actor}))]
    {:row row :answer (held-answer (:id row))}))

(defn hold-door!
  "Mint the held call a delegating seat's write became
  (server/delegation): the router refused to serve it because one of
  `delegation/hold-guards` said the person must tap first, and this is
  the row that waits for that tap. → the row.

  `forward` is the body exactly as the author sent it, and `door`
  names where the allow replays it. The engine writes the row
  (`the-power-door-mints-it`), `caller` names the author's sitter and
  `owner` the one person who may answer it."
  [eng {:keys [kind action id body caller owner author why if-match]}]
  (let [kind (name kind)
        action (name action)
        what (or (some-> (:name body) str not-empty) (some-> id str))
        shown (str action " " kind (when what (str " " what)))]
    (:row (inv/create!
           eng :held_call
           (cond-> {:tool (str kind "." action)
                    :why (:text (capped (or (some-> why str not-empty)
                                            "Held for the person's tap.")
                                        240))
                    :caller (str caller)
                    :input (or body {})
                    :forward (or body {})
                    :shown (:text (capped shown 140))
                    :door (cond-> {:kind kind :action action}
                            (some-> id str not-empty) (assoc :id (str id))
                            (some-> author str not-empty) (assoc :author (str author))
                            (some-> if-match str not-empty) (assoc :if_match (str if-match)))}
             (some-> owner str not-empty) (assoc :owner (str owner)))
           {:principal engine-actor}))))

;; ── the forward, at the wire boundary (R-4) ─────────────────────────

(defn- finish!
  "Walk one of the engine's own endings on a held call, best effort:
  a door that refuses must not take the caller's request down with
  it, exactly as `approval-effects!` does not."
  [eng row-id action body]
  (try
    (inv/invoke! eng :held_call (str row-id) action body
                 {:principal engine-actor})
    (catch Exception e
      (warn! "held call " row-id " could not be moved by " (name action)
             " (" (ex-message e) ")")
      nil)))

(defn- door-principal
  "The author's sitter, as the replay wears it: the seat's own member
  id, an agent, acting for the person the row was held for. It is the
  hand that asked, so the write lands in history as the author's; the
  person's yes is on this row, as `decided_by`."
  [row]
  (let [caller (str (get-in row [:data :caller]))]
    (cond-> (t/principal {:id caller :type :agent :display caller})
      (some-> (get-in row [:data :owner]) str not-empty)
      (assoc :acts-for (str (get-in row [:data :owner]))))))

(defn- forward-door!
  "Replay one ALLOWED held seat call at its door, once (server/
  delegation). The write runs as the author, under `:within` naming
  this row, which is the one thing the delegation guards read as the
  person's yes. Keyed by this row, so a replayed forward answers the
  first one's result. A refusal lands `failed` with the door's own
  sentence: the world may have moved between the ask and the tap."
  [eng row]
  (let [{:keys [kind action id if_match]} (get-in row [:data :door])
        kind (keyword (str kind))
        action (keyword (str action))
        id (some-> id str not-empty)
        body (or (get-in row [:data :forward]) {})
        opts {:principal (door-principal row)
              :within {:kind :held_call :action :allow :id (str (:id row))}
              :idempotency-key (str "held_call:" (:id row))
              :if-match (some-> if_match str not-empty)}]
    (try
      (let [res (if id
                  (inv/invoke! eng kind id action body opts)
                  (inv/create! eng kind body opts))
            written (:row res)]
        (finish! eng (:id row) :land
                 {:answer (wire/write-json
                           (cond-> {:kind (name kind) :action (name action)}
                             written (assoc :id (str (:id written))
                                            :state (some-> (:state written) name))))
                  :dropped 0})
        :done)
      (catch Exception e
        (let [{:keys [text]} (capped (str (or (:detail (ex-data e))
                                              (ex-message e)))
                                     240)]
          (finish! eng (:id row) :fail {:reason text}))
        :failed))))

(declare forward-tool!)

(defn forward!
  "Forward one ALLOWED held call, once, and land its ending. → the
  row's new state, or nil when there was nothing to forward.

  The forward is `mcp-servers/call!` on the tool the row names with
  the arguments the row carries, which is what `invoke-for` would
  have sent at call time. A server that answers lands `done` with the
  answer cut to `answer-cap-bytes`; a failure of any kind lands
  `failed` with its sentence. Nothing here throws: the person's tap
  already committed, and a wire failure is a fact about the call, not
  about the tap."
  [eng row]
  (when (= :allowed (:state row))
    (if (some? (get-in row [:data :door]))
      (forward-door! eng row)
      (forward-tool! eng row))))

(defn- forward-tool!
  "The tool call's forward, as it always was: `mcp-servers/call!` on
  the tool the row names, with the arguments the row carries."
  [eng row]
  (let [tool (str (get-in row [:data :tool]))
        args (or (get-in row [:data :forward]) {})]
    (try
      (let [payload (servers/call! eng tool args)
            {:keys [text dropped]} (capped (wire/write-json payload)
                                           answer-cap-bytes)]
        (finish! eng (:id row) :land {:answer text :dropped dropped})
        :done)
      (catch Exception e
        (let [{:keys [text]} (capped (str (ex-message e)) 240)]
          (finish! eng (:id row) :fail {:reason text}))
        :failed))))

(defn after-allow!
  "THE WIRE-BOUNDARY EFFECT of a person's allow (R-4), called by the
  router after every committed invoke, beside
  `grants/approval-effects!`.

  An `allow` on a `held_call` moves the row to `allowed` and nothing
  else; the forward runs OUT HERE, under the engine's own hand, and
  walks `land` or `fail`. Every other write passes through
  untouched, and the caller's own result is what comes back either
  way. A person who taps allow is told that their tap landed, and
  the row is where the server's answer appears.

  A REPLAY FORWARDS NOTHING. `invoke!` answers a repeated tap with
  the first one's outcome and says so in `:replayed?`; a forward
  behind that answer would be a second call on the outside for one
  person's one yes, which is the whole thing this kind exists to
  prevent."
  [eng rdef action out]
  (when (and (= :held_call (:kind rdef))
             (= :allow action)
             (map? out)
             (:transition out)
             (nil? (:replayed? out))
             (= :allowed (:state (:row out))))
    (try
      (forward! eng (:row out))
      (catch Exception e
        (warn! "the forward of held call " (:id (:row out)) " failed ("
               (ex-message e) ")"))))
  out)

;; ── the expiry sweep (R-7) ──────────────────────────────────────────

(defn- instant-of
  "An instant, however the row spells it: a stored string, or an
  Instant already. Unparsable is nil, which reads as never."
  [v]
  (cond
    (instance? Instant v) v
    (some-> v str not-empty) (try (Instant/parse (str v))
                                  (catch Exception _ nil))
    :else nil))

(defn sweep-expired!
  "One pass: every held call past its `expires_at`, expired through
  its own door under the engine's hand. → how many moved.

  A row the engine cannot date is left alone: a call it cannot expire
  is a call it must not expire by guessing. A row a stopped engine
  left `allowed` past its moment expires too. The kind's deviations
  say so."
  [eng]
  (if-not (contains? (inv/resources eng) :held_call)
    0
    (let [st (:storage eng)
          rdef (get (inv/resources eng) :held_call)
          ^Instant now ((:now-fn eng))
          rows (store/with-tx st
                 (fn [tx]
                   (into (vec (store/query-rows st tx :held_call
                                                {:state :held}
                                                {:limit sweep-cap}))
                         (store/query-rows st tx :held_call
                                           {:state :allowed}
                                           {:limit sweep-cap}))))]
      (reduce
       (fn [n raw]
         (let [row (inv/decode-row rdef raw)
               ^Instant at (instant-of (get-in row [:data :expires_at]))]
           (if (and at (.isBefore at now)
                    (finish! eng (:id row) :expire {}))
             (inc n)
             n)))
       0
       rows))))

(defn start-expiry-sweeper!
  "The cadence of R-7: every `:interval-ms`, `sweep-expired!`. The
  first pass is one interval after the start, so a boot writes
  nothing. Returns the handle `stop-expiry-sweeper!` takes."
  [eng {:keys [interval-ms] :or {interval-ms 300000}}]
  (let [stop (CountDownLatch. 1)
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (when-not (.await stop (long interval-ms)
                                         TimeUnit/MILLISECONDS)
                         (try (sweep-expired! eng)
                              (catch Exception e
                                (warn! "the expiry pass failed ("
                                       (ex-message e) ")")))
                         (recur))))
                   "waymark10-held-call-expiry")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-expiry-sweeper! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)
