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
            [clojure.walk :as walk]
            [waymark10.declare :refer [defscenario]]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.grants :as grants]
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
  "The birth stamps: the leash the caller never chose, and the person
  the call waits on. `expires_at` is written AT CREATE, so the person
  who reads the row reads the moment it will actually stop waiting.
  `waits_on` is the member the `owner` names, or the one the `caller`
  is or acts for (grants/waits-on), so a notice_rule can address it.
  It is read from the member rows, never from the body."
  [row ctx]
  (let [who (or (some-> (get-in row [:data :owner]) str not-empty)
                (get-in row [:data :caller]))
        m (grants/waits-on ctx who)]
    (-> row
        (update-in [:data :expires_at]
                   #(or % (.plusSeconds ^Instant (:now ctx)
                                        (long default-ttl-seconds))))
        (update :data #(if m (assoc % :waits_on m) (dissoc % :waits_on))))))

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
               [:maybe [:string {:min 1 :max 256}]]]
              ;; what the edit is ABOUT, beside the version: one digest
              ;; per prefill field of the door, read at the hold. A row
              ;; that moved in none of them is replayed at its new etag
              [:prefill_digests {:optional true :x-display {:hidden true}}
               [:maybe [:map-of :keyword :string]]]]]]
    [:owner {:optional true
             :x-display {:raw true
                         :label "Waits on"
                         :help "The person a held seat call waits on: the one its author acts for. Only they answer it."}}
     [:maybe [:string {:min 1 :max 128}]]]
    ;; the MEMBER the call waits on (docs/spec-addressed-notice.md),
    ;; beside `owner`'s principal string: `born` stamps it, so a
    ;; notice_rule can address {field waits_on}
    [:waits_on {:optional true :kind :member
                :x-display {:label "Tells"
                            :help "The member this call waits on: the person the calling seat or delegate acts for, or the caller themselves. The engine stamps it at birth, and a notice rule tells them."}}
     [:maybe :waymark/ref]]
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
               [:maybe [:string {:min 1 :max 256}]]]
              [:prefill_digests {:optional true :x-display {:hidden true}}
               [:maybe [:map-of :keyword :string]]]]]]
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

(defn- digest-value
  "A stored value as canonical bytes will take it: keys as names, and
  any scalar the canonical writer refuses (a float, a decimal, an
  instant) as its string. Both digests of one field read the same
  store, so the spelling only has to be stable, not pretty."
  [v]
  (cond
    (or (nil? v) (boolean? v) (string? v) (int? v) (keyword? v)) v
    (map? v) (into {} (map (fn [[k x]] [(if (keyword? k) (name k) (str k))
                                        (digest-value x)]))
                   v)
    (sequential? v) (mapv digest-value v)
    (set? v) (mapv digest-value (sort-by str v))
    :else (str v)))

(defn- prefill-now
  "The row as it stands, read for its fence: its version and one
  SHA-256 per named field, over that field's canonical JSON. → nil
  when there is no such row."
  [eng kind id fields]
  (let [st (:storage eng)]
    (when-some [raw (store/with-tx st
                      (fn [tx] (store/load-row st tx (keyword kind) (str id) {})))]
      {:version (:version raw)
       :digests (into {}
                      (map (fn [f]
                             (let [f (keyword f)]
                               [f (wire/digest
                                   [(digest-value (get (:data raw) f))])])))
                      fields)})))

(defn- prefill-of
  "The fields a door's edit is about: its `:edit :prefill` list, or nil
  for a door that declares none."
  [eng kind action]
  (seq (get-in (inv/resources eng)
               [(keyword kind) :actions (keyword action) :edit :prefill])))

(defn hold-door!
  "Mint the held call a delegating seat's write became
  (server/delegation): the router refused to serve it because one of
  `delegation/hold-guards` said the person must tap first, and this is
  the row that waits for that tap. → the row.

  `forward` is the body exactly as the author sent it, and `door`
  names where the allow replays it. The engine writes the row
  (`the-power-door-mints-it`), `caller` names the author's sitter and
  `owner` the one person who may answer it.

  A FENCED door keeps what its edit is about beside `if_match`: for a
  door whose edit declares a prefill list, one digest per field as the
  row stands now. The allow's replay reads them again when the version
  moved, so a transition that touched none of them (a seat's `fire`)
  does not spend the person's tap."
  [eng {:keys [kind action id body caller owner author why if-match]}]
  (let [kind (name kind)
        action (name action)
        what (or (some-> (:name body) str not-empty) (some-> id str))
        shown (str action " " kind (when what (str " " what)))
        digests (when (and (some-> if-match str not-empty)
                           (some-> id str not-empty))
                  (when-some [fields (prefill-of eng kind action)]
                    (not-empty (:digests (prefill-now eng kind id fields)))))]
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
                            (some-> if-match str not-empty) (assoc :if_match (str if-match))
                            digests (assoc :prefill_digests digests))}
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
  person's yes is on this row, as `decided_by`, and rides the write as
  `:allowed-by`, which the transition's actor keeps as `allowed_by`
  (invoke/actor-map). The correction count reads that mark: a write a
  person allowed is that person's word, never the seat's own."
  [row]
  (let [caller (str (get-in row [:data :caller]))]
    (cond-> (t/principal {:id caller :type :agent :display caller})
      (some-> (get-in row [:data :owner]) str not-empty)
      (assoc :acts-for (str (get-in row [:data :owner])))
      (some-> (get-in row [:data :decided_by]) str not-empty)
      (assoc :allowed-by (str (get-in row [:data :decided_by]))))))

(defn- forward-door!
  "Replay one ALLOWED held seat call at its door, once (server/
  delegation). The write runs as the author, under `:within` naming
  this row, which is the one thing the delegation guards read as the
  person's yes. Keyed by this row, so a replayed forward answers the
  first one's result. A refusal lands `failed` with the door's own
  sentence: the world may have moved between the ask and the tap.

  THE FENCE IS ABOUT THE EDIT. When the version moved and the row
  keeps `prefill_digests`, the row is read again: if none of those
  fields moved, the replay runs once more at the row's current etag;
  if some did, it fails naming them. A door with no digests keeps the
  strict version fence."
  [eng row]
  (let [{:keys [kind action id if_match prefill_digests]} (get-in row [:data :door])
        kind (keyword (str kind))
        action (keyword (str action))
        id (some-> id str not-empty)
        body (or (get-in row [:data :forward]) {})
        opts {:principal (door-principal row)
              :within {:kind :held_call :action :allow :id (str (:id row))}
              :idempotency-key (str "held_call:" (:id row))
              :if-match (some-> if_match str not-empty)}
        attempt (fn [opts]
                  (if id
                    (inv/invoke! eng kind id action body opts)
                    (inv/create! eng kind body opts)))
        refence (fn [e]
                  (let [now (prefill-now eng kind id (keys prefill_digests))
                        moved (sort (keep (fn [[f d]]
                                            (when (not= d (get-in now [:digests (keyword f)]))
                                              (name f)))
                                          prefill_digests))]
                    (cond
                      (nil? now) (throw e)
                      (seq moved)
                      (let [s (str "The row changed in " (str/join ", " moved)
                                   " since the author read it.")]
                        (throw (ex-info s {:detail s :moved (vec moved)})))
                      :else (inv/etag kind id (:version now)))))]
    (try
      (let [res (try
                  (attempt opts)
                  (catch clojure.lang.ExceptionInfo e
                    (if (and id (seq prefill_digests)
                             (= :version-conflict (:waymark10/problem (ex-data e))))
                      (attempt (assoc opts :if-match (refence e)))
                      (throw e))))
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

;; ── the notifier (bead waymark-fp62.10.3) ───────────────────────────
;;
;; The engine tells a person that something waits, through an
;; mcp_server power it holds itself. A `notifier` row names a server, a
;; tool on it, an input template and the transitions it hears (`on`). A
;; durable log consumer (`server/consumers`: a named cursor,
;; at-least-once, park on throw, seeded at registration so history is
;; not replayed) renders the template from the moved row and calls the
;; tool AS THE ENGINE, through `mcp-servers/call!`, which never passes
;; the power door's `invoke-for`: the notice's own send does not hold
;; (R-5, this file's R-10). The text always carries the link to the
;; row; the decision is a door on the row, never a reply in the chat
;; (R-3). A failed send is counted on the notifier row and never stops
;; the transition (R-2). The counts are written in place, not through
;; a door, so a notifier never tells about its own tally. Nothing here
;; re-throws: a throwing consumer parks its cursor for every notice.
;; It lives beside the held call, the first thing it tells.

(def notifier-consumer
  "The durable cursor's name in waymark10_cursors (consumer:notifier)."
  :notifier)

(g/defguard a-person-tells
  {:reads [:principal]
   :open "No door opens a notifier to an agent on its own: the person who is told creates and restates it, in person or through a tool the person is signed in to."
   :explain "A notifier is a person's row: a person creates it, restates it, pauses, resumes and retires it. An agent does not choose what a person hears."}
  [_row _inp ctx]
  (let [{:keys [type acts-for]} (:principal ctx)]
    (if (or (= :human type)
            (= :system type)
            (and (= :agent type) (not (str/blank? (str acts-for)))))
      (t/allow)
      (t/deny))))

(def ^:private notifier-on-entry
  [:map
   [:kind {:x-display {:label "Kind"
                       :help "The kind whose transitions this entry hears, e.g. held_call or seat."}}
    [:string {:min 1 :max 64}]]
   [:actions {:optional true
              :x-display {:label "Actions"
                          :help "The actions it hears on that kind, e.g. create or mark_halted. Empty or omitted hears every action."}}
    [:maybe [:vector [:string {:min 1 :max 64}]]]]])

(def ^:private notifier-person-fields
  [[:name {:x-display {:label "Name"
                       :help "What a person calls this notifier."}}
    [:string {:min 1 :max 120}]]
   [:server {:kind :mcp_server
             :x-display {:label "The server"
                         :help "The mcp_server row whose client makes the send."}}
    :waymark/ref]
   [:tool {:x-display {:label "Tool"
                       :help "The tool the engine calls, prefixed with the server's name (tgrambot__send_message) or bare (send_message)."}}
    [:string {:min 1 :max 200}]]
   [:input_template {:x-display {:label "Input"
                                 :help "The tool's arguments. A string value may carry {kind}, {id}, {action}, {summary}, {caller}, {why}, {link} and {expires_at}; the engine fills them from the moved row. The text always carries the link."
                                 :spelled-by-hand "The tool's own arguments as JSON, e.g. {\"chat_id\": \"42\", \"text\": \"{summary} {link}\"}"}}
    [:map-of :keyword :any]]
   [:on {:x-display {:label "Hears"
                     :help "The transitions that send a notice: {kind, actions}."}}
    [:vector notifier-on-entry]]
   [:audience {:x-display {:label "Who is told"
                           :help "The member's principal id. A transition this member made sends nothing."}}
    [:string {:min 1 :max 200}]]
   [:link_base {:optional true
                :x-display {:label "Address"
                            :help "The instance's address, e.g. https://work.example.org. The link is this address and the row's path; without it the link is the path alone."}}
    [:maybe [:string {:max 500}]]]])

(def ^:private notifier-engine-fields
  [[:sent {:optional true :x-display {:label "Sent" :raw true}}
    [:maybe :int]]
   [:failed {:optional true :x-display {:label "Failed" :raw true}}
    [:maybe :int]]
   [:last_error {:optional true :x-display {:label "Last error" :raw true}}
    [:maybe [:string {:max 500}]]]])

(def ^:private notifier-restate-input
  (into [:map] (map (fn [[k props schema]]
                      [k (assoc props :optional true)
                       (if (and (vector? schema) (= :maybe (first schema)))
                         schema
                         [:maybe schema])])
                    notifier-person-fields)))

(defhandler restate-notifier [row inp _ctx]
  (update row :data merge
          (into {} (remove (comp nil? val))
                (select-keys inp (map first notifier-person-fields)))))

(defresource notifier
  {:kind :notifier
   :plural "notifiers"
   :nav :system
   :states [:active :paused :retired]
   :initial :active
   :terminal #{:retired}
   :summary "{data.name} · {data.tool} · {state}"
   :label-template "{data.name}"
   :schema (into [:map] (concat notifier-person-fields notifier-engine-fields))
   :create-schema (into [:map] notifier-person-fields)
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :create-guards [a-person-tells]
   :actions
   {:restate
    {:from #{:active :paused} :to :active
     :input notifier-restate-input
     :guards [a-person-tells]
     :edit {:prefill [:name :server :tool :input_template :on :audience :link_base]}
     :safety {:idempotent true :reversible true :confirm false}
     :handler restate-notifier
     :display {:label "Restate" :style :primary :order 1
               :description "State the server, the tool, the template, what it hears or who is told again"}}
    :pause
    {:from #{:active} :to :paused
     :guards [a-person-tells]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Pause" :order 2
               :description "Send nothing until a person resumes it; what moves meanwhile is not told later"}}
    :resume
    {:from #{:paused} :to :active
     :guards [a-person-tells]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Resume" :style :primary :order 1
               :description "Send again, from the next transition on"}}
    :retire
    {:from #{:active :paused} :to :retired
     :guards [a-person-tells]
     :safety {:idempotent true :reversible false :confirm true
              :consequence "This notifier sends nothing again; a person creates a new one to be told again."}
     :display {:label "Retire" :style :danger :order 9}}}
   :deviations
   ["R-1 names `audience` a member ref. It is the member's principal id, the grant's own spelling of an audience, because R-4 compares it with the transition's actor and the actor is a principal id."
    "R-3 names the link to the row. This engine has no absolute address of its own, so the row carries `link_base`, and the link is that address and the row's API path."
    "R-2 counts sent and failed on the row. The counts are written in place, not through a door: a door per send would be a transition per notice, and a notifier on its own kind would tell about its own tally."]})

(defn- serves-kind? [eng kind] (contains? (inv/resources eng) kind))

(defn- stored-row [eng kind id]
  (when (and id (serves-kind? eng kind))
    (store/with-tx (:storage eng)
      (fn [tx] (store/load-row (:storage eng) tx kind (str id) {})))))

(defn- actor-id [actor]
  (str (if (map? actor) (:id actor) actor)))

(defn notice-link
  "The address of the moved row: the notifier's `link_base` and the
  row's API path."
  [eng notifier-row t]
  (let [plural (:plural (get (inv/resources eng) (:kind t)))
        base (str/replace (str (get-in notifier-row [:data :link_base])) #"/+$" "")]
    (str base "/api/" plural "/" (:resource-id t))))

(defn ui-link
  "The UI's page for one row, `<public origin>/#/api/<plural>/<id>`
  (the hash route the approve handoff already hands a person), from
  the engine's configured public origin, [:services :transcripts
  :public-origin]. nil when none is configured or the kind is not
  served, and the notice falls back to `notice-link`."
  [eng kind id]
  (let [origin (some-> (get-in eng [:services :transcripts :public-origin])
                       str (str/replace #"/+$" "") not-empty)
        plural (:plural (get (inv/resources eng) (keyword (name kind))))]
    (when (and origin plural (some? id))
      (str origin "/#/api/" plural "/" id))))

(defn- fill-notice [s values]
  (str/replace (str s) #"\{(kind|id|action|summary|caller|why|link|expires_at)\}"
               (fn [[_ k]] (str (get values (keyword k) "")))))

(defn render-notice
  "The notifier's input template, filled from the transition and the
  moved row. R-3: when no string value carries the link, the link is
  appended to `text`."
  [template values]
  (let [out (walk/postwalk #(if (string? %) (fill-notice % values) %) (or template {}))
        link (str (:link values))
        carries? (some #(and (string? %) (str/includes? % link)) (vals out))]
    (if carries?
      out
      (update out :text #(str/trim (str % "\n" link))))))

(defn- notice-values [eng notifier-row t]
  (let [row (stored-row eng (:kind t) (:resource-id t))
        data (:data row)]
    {:kind (name (:kind t))
     :id (str (:resource-id t))
     :action (some-> (:action t) name)
     :summary (or (:summary t) (:summary row) "")
     :caller (or (some-> (:caller data) str not-empty) (actor-id (:actor t)))
     :why (or (:why data) "")
     :expires_at (or (:expires_at data) "")
     :link (or (ui-link eng (:kind t) (:resource-id t))
               (notice-link eng notifier-row t))}))

(defn- active-rows [eng kind]
  (if-some [rd (get (inv/resources eng) kind)]
    (let [st (:storage eng)]
      (->> (store/with-tx st
             (fn [tx] (store/query-rows st tx kind {} {:limit 1000})))
           (map #(inv/decode-row rd %))
           (filter #(= :active (:state %)))
           vec))
    []))

(defn- active-notifiers [eng] (active-rows eng :notifier))

(defn hears?
  "Does this notifier's `on` name the transition?"
  [notifier-row t]
  (boolean
   (some (fn [{:keys [kind actions]}]
           (and (= (str kind) (name (:kind t)))
                (or (empty? actions)
                    (some #(= (str %) (some-> (:action t) name)) actions))))
         (get-in notifier-row [:data :on]))))

(defn- notifier-tool [eng notifier-row]
  (let [tool (str (get-in notifier-row [:data :tool]))]
    (if (str/includes? tool "__")
      tool
      (str (get-in (stored-row eng :mcp_server (get-in notifier-row [:data :server]))
                   [:data :name])
           "__" tool))))

(defn- tally-row!
  "Count one outcome on a notifier or a notice rule row, in place."
  [eng kind id outcome error]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (when-some [row (store/load-row st tx kind (str id)
                                        {:for-update true})]
          (store/update-data!
           st tx kind (str id)
           (cond-> (update (:data row) outcome (fnil inc 0))
             error (assoc :last_error (let [s (str error)]
                                        (subs s 0 (min 500 (count s))))))
           (:next-flip-at row)))))))

(defn- tally!
  "Count one send on the notifier row, in place."
  [eng notifier-id outcome error]
  (tally-row! eng :notifier notifier-id outcome error))

(defn- notify!
  "One notice: the rendered input, through the server's tool, as the
  engine. → :sent or :failed; never throws."
  [eng notifier-row t]
  (try
    (let [args (render-notice (get-in notifier-row [:data :input_template])
                              (notice-values eng notifier-row t))
          answer (servers/call! eng (notifier-tool eng notifier-row) args)]
      (if (:isError answer)
        (let [msg (or (some-> answer :content first :text) "the tool answered an error")]
          (tally! eng (:id notifier-row) :failed msg)
          :failed)
        (do (tally! eng (:id notifier-row) :sent nil)
            :sent)))
    (catch Exception e
      (warn! "notifier " (get-in notifier-row [:data :name]) " could not send for "
             "transition " (:id t) " — " (ex-message e))
      (try (tally! eng (:id notifier-row) :failed (or (ex-message e) (str e)))
           (catch Exception _ nil))
      :failed)))

(defn notice-transition!
  "One transition → one notice per active notifier that hears it, or
  nothing. A transition whose actor is the notifier's audience sends
  nothing (R-4). Never throws."
  [eng t]
  (try
    (doseq [n (active-notifiers eng)
            :when (hears? n t)
            :when (not= (actor-id (:actor t))
                        (str (get-in n [:data :audience])))]
      (notify! eng n t))
    (catch Exception e
      (warn! "transition " (:id t) " could not be noticed — " (ex-message e))
      nil))
  nil)

;; ── the addressed notice (docs/spec-addressed-notice.md) ───────────
;;
;; A notifier tells the one person its own row names. A `notice_rule`
;; tells whoever a ref field on the MOVED row names: the rule says who
;; (`address.field`, a ref to member on the kind it hears), the member
;; says how (`member.notify`: the notifier that carries the send and
;; the tool's own input for that person, e.g. a chat_id). The rule's
;; own `notifier` renders the text and gives the link. It rides the
;; notifier's consumer, so its delivery is the notifier's: one cursor,
;; at-least-once, never throws. An empty ref, a member with no
;; `notify` and a failed send are counted on the rule, in place; the
;; actor who caused the transition is told nothing.
;;
;; The address may walk one more hop: `{field: "plan_id", then:
;; "member"}` reads the moved row's `plan_id`, loads the row it names
;; and reads that row's `member`. The wall judges every hop; the send
;; resolves the chain at send time, and an empty hop is unaddressed.

(defn- ref-named
  "The ref field of this resource named `field`, or nil."
  [rd field]
  (some #(when (= (str field) (name (:field %))) %)
        (schema/ref-fields (:schema rd))))

(defn- address-path
  "The field names an address walks, in order: `field`, then `then`."
  [address]
  (into [] (keep #(some-> (get address %) str not-empty)) [:field :then]))

(g/defguard address-names-a-member
  {:reads [:services]
   :vars [:kind :field :why]
   :open "The kinds and their ref fields are each kind's published schema, one GET away; enumerating them into this form would duplicate it."
   :explain "A notice rule addresses the member a ref field on the kind it hears names, directly or through one more ref; {kind}.{field} {why}."}
  [row inp ctx]
  (if-some [rdef-of (:rdef-of ctx)]
    (let [kind (str (or (:kind inp) (get-in row [:data :kind])))
          hops (address-path (or (:address inp) (get-in row [:data :address])))
          field (str/join "." hops)
          why (loop [k kind
                     [h & more] hops
                     first? true]
                (let [rd (when-not (str/blank? k) (rdef-of k))
                      ref (when rd (ref-named rd h))
                      at (fn [s] (if first? s (str "reaches " k ", whose " h " " s)))]
                  (cond
                    (nil? rd) (if first?
                                "names no kind this engine serves"
                                (str "reaches " k ", a kind this engine does not serve"))
                    (nil? ref) (at "is not a ref field of that kind")
                    (:listed ref) (at "is a list of refs, and a rule addresses one member")
                    (seq more) (recur (name (:kind ref)) more false)
                    (not= :member (keyword (:kind ref)))
                    (at (str "names a " (name (:kind ref)) ", not a member")))))]
      (if why
        (t/deny {:vars {:kind kind :field field :why why}})
        (t/allow)))
    (t/allow)))

(g/defguard at-names-a-datetime
  {:reads [:services]
   :vars [:kind :field :why]
   :open "The kinds and their fields are each kind's published schema, one GET away; enumerating them into this form would duplicate it."
   :explain "A notice rule's `at` names a datetime field of the kind it hears; {kind}.{field} {why}."}
  [row inp ctx]
  (let [kind (str (or (:kind inp) (get-in row [:data :kind])))
        field (some-> (or (:at inp) (get-in row [:data :at])) :field str not-empty)
        rd (when (and field (not (str/blank? kind)))
             (some-> (:rdef-of ctx) (#(% kind))))]
    ;; an unknown kind is address-names-a-member's sentence, not this one's
    (if (and rd field)
      (let [s (schema/field-schema (:schema rd) (keyword field))
            head (if (vector? s) (first s) s)]
        (cond
          (nil? s) (t/deny {:vars {:kind kind :field field
                                   :why "is not a field of that kind"}})
          (not= :waymark/instant head)
          (t/deny {:vars {:kind kind :field field
                          :why "is not a datetime, so it would never tell"}})
          :else (t/allow)))
      (t/allow))))

(def ^:private notice-rule-when
  [:map
   [:action {:optional true
             :x-display {:label "Action"
                         :help "The action that moved the row, e.g. queue. Empty matches every action."}}
    [:maybe [:string {:min 1 :max 64}]]]
   [:from_state {:optional true
                 :x-display {:label "From state"
                             :help "The state the row left. Empty matches every state."}}
    [:maybe [:string {:min 1 :max 64}]]]
   [:to_state {:optional true
               :x-display {:label "To state"
                           :help "The state the row entered, e.g. active. Empty matches every state."}}
    [:maybe [:string {:min 1 :max 64}]]]])

(def ^:private notice-rule-person-fields
  [[:name {:x-display {:label "Name"
                       :help "What a person calls this rule."}}
    [:string {:min 1 :max 120}]]
   [:kind {:x-display {:label "Kind"
                       :help "The kind whose transitions this rule hears, e.g. chore."}}
    [:string {:min 1 :max 64}]]
   [:when {:optional true
           :x-display {:label "When"
                       :help "The transition that sends a notice: equality on action, from_state and to_state. Omitted hears every transition of the kind."}}
    [:maybe notice-rule-when]]
   [:at {:optional true
         :x-display {:label "At"
                     :help "A datetime field on the row, e.g. {\"field\": \"starts_at\"}. When set, the rule hears no transition: it tells once when that instant arrives, while the row stands in the `when` to_state."}}
    [:maybe
     [:map
      [:field {:x-display {:label "Field"
                           :help "A datetime field of the kind."}}
       [:string {:min 1 :max 64}]]]]]
   [:address {:x-display {:label "Who is told"
                          :help "The ref field on the moved row that names the member, e.g. {\"field\": \"assignee\"}, or a ref and the field on its row that does, e.g. {\"field\": \"plan_id\", \"then\": \"member\"}."}}
    [:map
     [:field {:x-display {:label "Field"
                          :help "A ref field of the kind whose target is member, or, with then, whose target carries the member."}}
      [:string {:min 1 :max 64}]]
     [:then {:optional true
             :x-display {:label "Then"
                         :help "A ref field, on the row field names, whose target is member. Empty: field itself names the member."}}
      [:maybe [:string {:min 1 :max 64}]]]]]
   [:notifier {:kind :notifier
               :x-display {:label "Notifier"
                           :help "The notifier whose input template and address make the text and the link. The member's own notifier carries the send when the member names one."}}
    :waymark/ref]])

(def ^:private notice-rule-engine-fields
  [[:sent {:optional true :x-display {:label "Sent" :raw true}}
    [:maybe :int]]
   [:failed {:optional true :x-display {:label "Failed" :raw true}}
    [:maybe :int]]
   [:skipped {:optional true :x-display {:label "Skipped: no way to reach them" :raw true}}
    [:maybe :int]]
   [:unaddressed {:optional true :x-display {:label "Unaddressed" :raw true}}
    [:maybe :int]]
   [:last_error {:optional true :x-display {:label "Last error" :raw true}}
    [:maybe [:string {:max 500}]]]
   [:told {:optional true :x-display {:label "Told at their instants" :raw true}}
    [:maybe [:vector :string]]]])

(def ^:private notice-rule-restate-input
  (into [:map] (map (fn [[k props s]]
                      [k (assoc props :optional true)
                       (if (and (vector? s) (= :maybe (first s)))
                         s
                         [:maybe s])])
                    notice-rule-person-fields)))

(defhandler restate-notice-rule [row inp _ctx]
  (update row :data merge
          (into {} (remove (comp nil? val))
                (select-keys inp (map first notice-rule-person-fields)))))

(defresource notice-rule
  {:kind :notice_rule
   :plural "notice_rules"
   :nav :system
   :states [:active :paused :retired]
   :initial :active
   :terminal #{:retired}
   :summary "{data.name} · {data.kind} · {state}"
   :label-template "{data.name}"
   :schema (into [:map] (concat notice-rule-person-fields notice-rule-engine-fields))
   :create-schema (into [:map] notice-rule-person-fields)
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :create-guards [a-person-tells address-names-a-member at-names-a-datetime]
   :actions
   {:restate
    {:from #{:active :paused} :to :active
     :input notice-rule-restate-input
     :guards [a-person-tells address-names-a-member at-names-a-datetime]
     :edit {:prefill [:name :kind :when :at :address :notifier]}
     :safety {:idempotent true :reversible true :confirm false}
     :handler restate-notice-rule
     :display {:label "Restate" :style :primary :order 1
               :description "State the kind, the transition, the field that names who is told or the notifier again"}}
    :pause
    {:from #{:active} :to :paused
     :guards [a-person-tells]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Pause" :order 2
               :description "Tell nobody until a person resumes it; what moves meanwhile is not told later"}}
    :resume
    {:from #{:paused} :to :active
     :guards [a-person-tells]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Resume" :style :primary :order 1
               :description "Tell again, from the next transition on"}}
    :retire
    {:from #{:active :paused} :to :retired
     :guards [a-person-tells]
     :safety {:idempotent true :reversible false :confirm true
              :consequence "This rule tells nobody again; a person creates a new one to tell again."}
     :display {:label "Retire" :style :danger :order 9}}}
   :deviations
   ["R-1 says a rule naming a non-ref or non-member field fails assembly. A rule is a row a person writes, not a declaration, so the check is the create and restate wall `address-names-a-member`, which refuses with the sentence."
    "R-2 names `member.notify.notifier` a ref. It is the notifier row's id as a string: a ref inside a nested map is not a ref the framework resolves, so an id that names no notifier falls back to the rule's own notifier at send time."]})

(defn- decoded-row [eng kind id]
  (when-some [rd (get (inv/resources eng) kind)]
    (some->> (stored-row eng kind id) (inv/decode-row rd))))

(defn- addressee-of
  "The member id an address path names from the moved row, read hop by
  hop at send time; nil where a hop is empty or names no row."
  [eng kind row hops]
  (loop [kind kind, row row, [h & more] hops]
    (let [v (some-> (get-in row [:data (keyword h)]) str not-empty)]
      (if (or (nil? v) (empty? more))
        v
        (let [next-kind (some-> (get (inv/resources eng) kind)
                                (ref-named h) :kind keyword)]
          (recur next-kind (when next-kind (decoded-row eng next-kind v)) more))))))

(defn rule-matches?
  "Does this notice rule's kind and `when` name the transition?
  Equality only; an empty entry matches anything."
  [rule t]
  (let [{:keys [action from_state to_state]} (get-in rule [:data :when])
        same? (fn [want got]
                (or (str/blank? (str want))
                    (= (str want) (some-> got name))))]
    (and (= (str (get-in rule [:data :kind])) (name (:kind t)))
         (same? action (:action t))
         (same? from_state (:from-state t))
         (same? to_state (:to-state t)))))

(defn- address!
  "One addressed notice: the member the rule's field names, reached the
  way the member's `notify` says, with the rule's notifier's text.
  → [outcome error], the outcome :sent, :failed, :skipped, :unaddressed
  or :self. Counts nothing; never throws."
  [eng rule t]
  (let [count! (fn [outcome error] [outcome error])]
    (try
      (let [row (decoded-row eng (:kind t) (:resource-id t))
            addressee (addressee-of eng (keyword (name (:kind t))) row
                                    (address-path (get-in rule [:data :address])))
            member (when addressee (decoded-row eng :member addressee))
            notify (get-in member [:data :notify])
            texter (decoded-row eng :notifier (get-in rule [:data :notifier]))
            carrier (or (some->> (:notifier notify) str not-empty
                                 (decoded-row eng :notifier))
                        texter)
            ;; a held call's birth is the engine's hand: the person
            ;; who caused it is the row's `caller`
            causers (cond-> #{(actor-id (:actor t))}
                      (some-> (get-in row [:data :caller]) str not-empty)
                      (conj (str (get-in row [:data :caller]))))
            subject (some-> (get-in member [:data :subject]) str)]
        (cond
          (nil? addressee) (count! :unaddressed nil)
          (or (contains? causers addressee)
              (contains? causers subject)) [:self nil]
          (or (nil? member) (empty? notify)) (count! :skipped nil)
          (nil? texter) (count! :failed "the rule's notifier is gone")
          :else
          (let [args (merge (render-notice (get-in texter [:data :input_template])
                                           (notice-values eng texter t))
                            (:input notify))
                answer (servers/call! eng (notifier-tool eng carrier) args)]
            (if (:isError answer)
              (count! :failed (or (some-> answer :content first :text)
                                  "the tool answered an error"))
              (count! :sent nil)))))
      (catch Exception e
        (warn! "notice rule " (get-in rule [:data :name]) " could not tell for "
               "transition " (:id t) " — " (ex-message e))
        (count! :failed (or (ex-message e) (str e)))))))

(defn- tell!
  "One addressed notice, counted on the rule in place.
  → :sent, :failed, :skipped, :unaddressed or :self; never throws."
  [eng rule t]
  (let [[outcome error] (address! eng rule t)]
    (if (= :self outcome)
      :self
      (try (tally-row! eng :notice_rule (:id rule) outcome error)
           outcome
           (catch Exception _ :failed)))))

(defn- at-field [rule]
  (some-> (get-in rule [:data :at :field]) str not-empty keyword))

(defn notice-rules-transition!
  "One transition → one addressed notice per active notice rule that
  matches it. A rule with `at` hears no transition; the sweep below
  tells it. Never throws."
  [eng t]
  (try
    (doseq [r (active-rows eng :notice_rule)
            :when (nil? (at-field r))
            :when (rule-matches? r t)]
      (tell! eng r t))
    (catch Exception e
      (warn! "transition " (:id t) " could not be addressed — " (ex-message e))
      nil))
  nil)

;; ── the timed notice ────────────────────────────────────────────────
;;
;; A rule with `at {field}` tells once when the instant its row's field
;; names arrives, while the row stands in the rule's `when.to_state`.
;; The maintainer's clock sweep runs the pass (late by up to one sweep
;; interval). Each mark, keyed (rule, row, instant), is claimed under
;; the rule row's lock in one short transaction before its send, and
;; the send and its count follow outside that lock, so a slow tool call
;; never holds the rule's tallies and a restart between sweeps neither
;; loses a notice nor doubles one (a crash mid-send loses at most the
;; claims in flight, never tells twice). Editing the field
;; makes a new instant, and a new instant tells; a row that leaves the
;; state before its instant never matches.

(def ^:private told-cap
  "How many marks one rule keeps for rows no longer due, the oldest
  dropped first. A mark that still names a due row is always kept, so
  a due set past this cap is never told twice."
  1000)

(defn- told-mark [row-id at]
  (str row-id " " at))

(defn- due-rows
  "[row instant] for each row of the rule's kind in its `when.to_state`
  whose `at` instant is at or before now — filtered on that field and
  read a page of sweep-cap at a time, so no due row waits on the cap."
  [eng rule ^Instant now]
  (let [kind (keyword (str (get-in rule [:data :kind])))
        field (at-field rule)
        state (some-> (get-in rule [:data :when :to_state]) str not-empty)]
    (if-some [rd (get (inv/resources eng) kind)]
      (let [st (:storage eng)
            conds (cond-> [{:target :data :field field :cast "timestamptz"
                            :op :<= :value (str now)}]
                    state (conj {:target :state :op := :value state}))
            page (fn [offset]
                   (store/with-tx st
                     (fn [tx] (store/search-rows st tx kind conds
                                                 {:limit sweep-cap :offset offset}))))]
        (->> (loop [offset 0 acc []]
               (let [rows (page offset)
                     acc (into acc rows)]
                 (if (< (count rows) sweep-cap)
                   acc
                   (recur (+ offset sweep-cap) acc))))
             (map #(inv/decode-row rd %))
             (keep (fn [row]
                     (when-some [^Instant at (instant-of (get-in row [:data field]))]
                       (when (and (or (nil? state) (= state (name (:state row))))
                                  (not (.isAfter at now)))
                         [row at]))))
             vec))
      [])))

(defn- kept-marks
  "The marks a rule keeps: every one that names a row still due, and
  the last told-cap of the rest."
  [marks due]
  (let [live (set (map (fn [[row at]] (told-mark (:id row) at)) due))
        gone (remove live marks)]
    (into (vec (take-last told-cap gone)) (filter live marks))))

(defn- tell-at!
  "One rule's pass: each due row not yet told for its instant is claimed
  — its mark lands under the rule's lock, and the lock lets go — then
  told outside it; the counts land after in a second short lock.
  → how many were told."
  [eng rule now]
  (let [due (due-rows eng rule now)
        st (:storage eng)
        id (str (:id rule))
        kind (keyword (str (get-in rule [:data :kind])))]
    (if (empty? due)
      0
      (let [claimed
            (store/with-tx st
              (fn [tx]
                (when-some [raw (store/load-row st tx :notice_rule id {:for-update true})]
                  (let [told (set (get-in raw [:data :told]))
                        fresh (vec (remove (fn [[row at]] (told (told-mark (:id row) at))) due))]
                    (when (seq fresh)
                      (store/update-data!
                       st tx :notice_rule id
                       (update (:data raw) :told
                               #(kept-marks (into (vec %) (map (fn [[row at]] (told-mark (:id row) at)))
                                                  fresh)
                                            due))
                       (:next-flip-at raw)))
                    fresh))))
            outcomes (mapv (fn [[row at]]
                             (address! eng rule {:id (told-mark (:id row) at)
                                                 :kind kind
                                                 :resource-id (:id row)
                                                 :actor engine-actor}))
                           claimed)
            tally (fn [data [outcome error]]
                    (cond-> data
                      (not= :self outcome) (update outcome (fnil inc 0))
                      error (assoc :last_error (let [s (str error)]
                                                 (subs s 0 (min 500 (count s)))))))]
        (when (seq outcomes)
          (store/with-tx st
            (fn [tx]
              (when-some [raw (store/load-row st tx :notice_rule id {:for-update true})]
                (store/update-data! st tx :notice_rule id
                                    (reduce tally (:data raw) outcomes)
                                    (:next-flip-at raw))))))
        (count claimed)))))

(defn sweep-notice-instants!
  "One pass of the timed notice: every active rule with `at`, told for
  the rows whose instant has come. The maintainer's clock sweep runs
  it; tests call it directly. → how many were told; never throws."
  [eng]
  (try
    (let [now ((:now-fn eng))]
      (reduce (fn [n rule]
                (+ n (try (tell-at! eng rule now)
                          (catch Exception e
                            (warn! "notice rule " (get-in rule [:data :name])
                                   " could not tell at its instant — " (ex-message e))
                            0))))
              0
              (filter at-field (active-rows eng :notice_rule))))
    (catch Exception e
      (warn! "the timed notice sweep failed — " (ex-message e))
      0)))

(defn notifier-consumer-fn
  "The consumer's function of one transition: the notifiers, then the
  notice rules. Public because a test drains it directly
  (`consumers/drain-consumer!`)."
  [eng]
  (fn [t]
    (notice-transition! eng t)
    (notice-rules-transition! eng t)))

(defn notifying?
  "Does this engine serve the notifier at all?"
  [eng]
  (serves-kind? eng :notifier))

(defn start-notifiers!
  "Register the durable log consumer that sends the notices. Returns
  the handle `stop-notifiers!` takes. opts: :dispatcher, :poll-ms,
  :from-origin?."
  ([eng] (start-notifiers! eng {}))
  ([eng opts]
   (consumers/register-consumer!
    eng notifier-consumer (notifier-consumer-fn eng)
    (select-keys opts [:dispatcher :poll-ms :from-origin?]))))

(defn stop-notifiers! [consumer]
  (some-> consumer consumers/stop-consumer!)
  nil)
