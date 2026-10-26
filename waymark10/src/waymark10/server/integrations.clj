(ns waymark10.server.integrations
  "The integration (docs/spec-integrations.md § 2, build step 1): an
  outside system the engine hears from and calls, held as a row and
  acting as a principal of its own.

  AN INTEGRATION IS A PRINCIPAL. Its id is `integration:<row id>`
  (`principal-id`), and every move it makes is in the log under that
  name, beside every seat's. It never acts under the name of the
  person who approved it, and nobody acts under its name.

  A PERSON WHO DID NOT PROPOSE IT APPROVES IT. A person creates the
  row, born `proposed`; `approve` is a person's tap, and the person
  whose create it was cannot be that person — the held call's
  four-eyes wall, read over `created_by` instead of `caller`. An
  agent that acts for a person proposes one too, HELD for that
  person's tap (waymark10.holds): the Allow runs the create as the
  agent, so `created_by` is the agent and `allowed_by` the person, and
  that person may then approve it — the agent proposed, the person
  decided. An agent acting for nobody, and an integration, are
  refused. The
  approve mints the grant the integration wears, audience
  `integration:<id>`, scope `scope_wanted`, in the approve's own
  transaction, and the engine accepts it there: the approver's tap is
  the consent, as an approved ask's is.

  `scope_wanted` is engine-written from the law (`integration-for`):
  the doors of every `:on-event` that names the integration, and the
  power of every `:effect` whose tool resolves to its `server` row, at
  its birth, at every boot and again at its approve, so a server whose
  powers changed since the birth is read as it stands at the tap; one
  the law names nowhere confers nothing, honestly.

  WHAT IT SENDS IS KEPT ONCE (§ 3, build step 2). `receive!` is the
  judgment behind POST /api/-/events/<name>: the signature checked in
  constant time against the secret row's value (and the one a rotate
  replaced, for 24 hours), the timestamp against `tolerance_s`, then
  one `integration_event` receipt per `(integration, event_id)`. A bad
  signature stores nothing; a duplicate answers the first receipt and
  changes nothing.

  WHAT IT SENDS MOVES A DOOR ONCE (§ 3, build step 3). The applier
  (`apply!`, behind a durable log consumer and a sweep) finds the
  `:on-event` entries for the receipt's source and type and the one row
  their `:match` names, and invokes the door as `integration:<id>`,
  under its grant and `Idempotency-Key: event:<integration>:<event_id>`,
  so a crash between the door and the ending replays and never moves
  the row twice. The receipt lands `applied` with the transition,
  `refused` with the refusal whole, `unmatched`, `ambiguous` or
  `ignored`; `retry` and `dismiss` are a person's or a seat's."
  (:require [clojure.string :as str]
            [waymark10.time :refer [instant-of]]
            [waymark10.checks :as checks]
            [waymark10.expr :as expr]
            [waymark10.guards :as g]
            [waymark10.holds :as holds]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.clock :as clock]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.keys :as keys]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.problems :as p]
            [waymark10.server.secrets :as secrets]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.io InputStream)
           (java.nio.charset StandardCharsets)
           (java.time Duration Instant)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(set! *warn-on-reflection* true)

(def engine-actor
  "The system actor that accepts an approved integration's grant."
  (t/principal {:id "waymark10-integrations" :type :system
                :display "Integrations"}))

(def principal-prefix "integration:")

(defn principal-id
  "The principal id an integration row acts as: `integration:<id>`."
  [row]
  (str principal-prefix (:id row)))

(defn principal
  "The principal an integration row acts as. An :agent, because it is
  neither a person nor the engine's own hand: a grant leashes it."
  [row]
  (t/principal {:id (principal-id row) :type :agent
                :display (str (or (get-in row [:data :name]) (:id row)))}))

(defn integration-principal?
  "Is this principal an integration?"
  [p]
  (str/starts-with? (str (:id p)) principal-prefix))

;; ── guards ──────────────────────────────────────────────────────────

(defn- a-person? [ctx]
  (= :human (get-in ctx [:principal :type])))

(g/defguard a-person-holds-it
  {:reads [:principal]
   :open "No door changes who the caller is: ask a person of the house."
   :explain "An integration is a person's to approve, pause, resume, rotate and retire; no agent and no integration moves one."}
  [_row _inp ctx]
  (if (a-person? ctx) (t/allow) (t/deny)))

(g/defguard a-person-or-their-agent-proposes
  {:hold true
   :reads [:principal :within]
   :open "No door clears this one. An agent's proposal waits as a held_call for its person's tap, and the person's Allow runs it exactly as written."
   :explain "An integration is proposed by a person, or by an agent for its person's tap; the call is held for that person."}
  ;; the create door's wall. A person passes; an agent that acts for a
  ;; person is HELD (the router mints the held call), and the one agent
  ;; call this admits is the engine's replay of the call its person
  ;; allowed. An agent acting for nobody, and an integration, are
  ;; refused outright: there is nobody to hold the call for.
  [_row _inp ctx]
  (let [p (:principal ctx)]
    (cond
      (a-person? ctx) (t/allow)
      (holds/approved-hold? ctx :integration nil) (t/allow)
      (and (= :agent (:type p))
           (not (integration-principal? p))
           (some-> (:acts-for p) str str/trim not-empty))
      (t/deny)
      :else
      (t/deny {:evidence {:refused "An integration is a person's to propose; an agent that acts for no person, and an integration, cannot propose one."}}))))

(def the-creator-does-not-decide
  "The four-eyes wall, the held call's `the-caller-does-not-decide`
  over the field an integration names its proposer in."
  (assoc (g/not-the-field
          :created_by
          {:name :the-creator-does-not-decide
           :explain "The person who proposed this integration cannot be the one who approves it; another person decides."})
         :open (str "The wall is about WHO, not about a field of this "
                    "door: there is nothing to send that opens it. Ask "
                    "another person of the house to approve.")))

(g/defguard speaks-for-an-outside-member
  {:reads [:storage]
   :vars [:problem]
   :explain "{problem}"
   :open "Name an outside member in `on_behalf_of`, or leave it empty for an integration that acts as itself."}
  [row inp ctx]
  (let [mid (some-> (or (:on_behalf_of inp) (get inp "on_behalf_of")
                        (get-in row [:data :on_behalf_of]))
                    str not-empty)
        read' (:read ctx)]
    (if (or (nil? mid) (nil? read'))
      (t/allow)
      (if (= "outside" (str (get-in (read' :member mid) [:data :actor_type])))
        (t/allow)
        (t/deny {:vars {:problem (str "This integration would speak on behalf of member " mid
                                      ", who is no outside member; name an outside member, or none.")}})))))

;; ── handlers ────────────────────────────────────────────────────────

(g/defguard the-engine-asks-again
  {:reads [:principal]
   :hide true
   :explain "The engine sends an integration back to proposed at boot, when the law asks more of it than its grant admits; nobody does by hand."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type]))
    (t/allow)
    (t/deny)))

(defn- born [row ctx]
  ;; the proposer is whoever made the row: on the replay of a held
  ;; create that is the agent, and the person whose Allow ran it is
  ;; kept beside it, so the four-eyes wall reads the agent
  (let [allowed (or (some-> (holds/allowed-hold ctx :integration nil)
                            (get-in [:data :decided_by]) str not-empty)
                    (some-> (get-in ctx [:principal :allowed-by]) str not-empty))]
    (update row :data #(cond-> (assoc % :created_by (str (get-in ctx [:principal :id])))
                         allowed (assoc :allowed_by allowed)))))

(declare law-wants)

(defhandler mint-the-grant [row _inp ctx]
  ;; the grant is born beside the verdict, in its transaction: offered
  ;; by the approver to the integration, and accepted by the engine,
  ;; because the approver's tap is the consent. The scope is read again
  ;; first, from the law and the server rows as they stand now
  (let [row (if-some [rdefs (:rdefs ctx)]
              (update row :data #(law-wants (rdefs) % ctx))
              row)
        wanted (not-empty (get-in row [:data :scope_wanted]))
        res ((:create ctx) :grant
             (cond-> {:audience (principal-id row)}
               wanted (assoc :scope (grants/merge-scope wanted))))
        gid (str (get-in res [:row :id]))]
    ((:invoke ctx) :grant gid :accept {} {:as engine-actor})
    (update row :data #(-> %
                           (dissoc :widened_by)
                           (assoc :grant gid
                                  :approved_by (str (get-in ctx [:principal :id])))))))

(defhandler note-the-widening [row inp _ctx]
  (update row :data assoc :widened_by (:widened_by inp)))

(defhandler rotate-secret [row inp ctx]
  (update row :data assoc
          :previous_secret (get-in row [:data :secret])
          :secret (:secret inp)
          :rotated_at (:now ctx)))

;; ── the kind ────────────────────────────────────────────────────────

(def ^:private name-field
  [:name {:x-display
          {:raw true
           :label "The name"
           :help "Unique, and the path segment of its receiver: /api/-/events/<name>."}}
   [:string {:min 1 :max 64}]])

(def ^:private system-field
  [:system {:examples ["Stripe, the payment processor"]
            :x-display
            {:label "The outside system"
             :help "One line: which system this is and what it is to the house. The audit reads it."}}
   [:string {:min 1 :max 200}]])

(def ^:private secret-field
  [:secret {:optional true
            :kind :secret
            :x-display
            {:label "Its secret"
             :help "The secret row that holds the signing key. The id, never the value."}}
   [:maybe :waymark/ref]])

(def ^:private signature-field
  [:signature {:optional true
               :x-display
               {:label "How it signs"
                :help "The header, the scheme, the value's prefix, the timestamp header and its tolerance in seconds. Left empty: X-Signature, hmac-sha256, no prefix, no timestamp. Scheme none checks nothing, and the audit names it."}}
   [:maybe
    [:map
     [:header {:optional true
               :x-display {:label "The signature header"
                           :help "The header the system's signature arrives in. Left empty: X-Signature."}}
      [:maybe [:string {:min 1 :max 120}]]]
     [:scheme {:optional true
               :x-display {:label "The scheme"
                           :help "How the signature is computed over the body: hmac-sha256, or none for a source that cannot sign, whose events a seat should read through :via :inbox."}}
      [:maybe [:enum "hmac-sha256" "none"]]]
     [:prefix {:optional true
               :examples ["sha256="]
               :x-display {:label "The signature's prefix"
                           :help "What the header's value starts with before the hex, as sha256=. Set: a value without it is refused, and it is stripped before the compare."}}
      [:maybe [:string {:min 1 :max 40}]]]
     [:timestamp_header {:optional true
                         :x-display {:label "The timestamp header"
                                     :help "The header that carries when the system signed. Left empty: no timestamp is checked."}}
      [:maybe [:string {:min 1 :max 120}]]]
     [:tolerance_s {:optional true
                    :x-display {:label "Its tolerance, in seconds"
                                :help "How old a signed timestamp may be before the delivery is refused as a replay."}}
      [:maybe [:int {:min 0 :max 86400}]]]]]])

(defn- path-field [k label help]
  [k {:optional true
      :examples [["id"]]
      :x-display {:label label :help help}}
   [:maybe [:vector {:max 8} [:string {:min 1 :max 120}]]]])

(defn- header-field [k label help]
  [k {:optional true
      :x-display {:label label :help help}}
   [:maybe [:string {:min 1 :max 120}]]])

(def ^:private server-field
  [:server {:optional true
            :kind :mcp_server
            :x-display
            {:label "The server its effects call"
             :help "The MCP server row its effects call. Leave it empty for an integration the house only hears from."}}
   [:maybe :waymark/ref]])

(def ^:private on-behalf-of-field
  [:on_behalf_of {:optional true
                  :kind :member
                  :x-display
                  {:label "The outside party it speaks for"
                   :help "An outside member whose own system this is. What it sends is logged as that member, via this integration. Leave it empty for an integration that acts as itself."}}
   [:maybe :waymark/ref]])

(def ^:private person-fields
  [name-field
   system-field
   secret-field
   signature-field
   (path-field :event_id_path "Where the event's id is"
               "The path to the event's own id in the body, as [\"id\"].")
   (path-field :event_type_path "Where the event's type is"
               "The path to the event's type in the body, as [\"type\"].")
   (header-field :id_header "The header the event's id is in"
                 "Set: the event's id is read from this header, and a delivery without it is refused. Left empty: from the body at event_id_path.")
   (header-field :type_header "The header the event's type is in"
                 "Set: the event's type is read from this header. Left empty: from the body at event_type_path.")
   server-field
   on-behalf-of-field])

(defresource integration
  {:kind :integration
   :plural "integrations"
   :states [:proposed :live :paused :retired]
   :initial :proposed
   :terminal #{:retired}
   :nav :system
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   :schema
   (into
    [:map]
    (concat
     person-fields
     [[:grant {:optional true
               :kind :grant
               :x-display
               {:label "The grant it wears"
                :help "Minted when a person approves it, to integration:<id>. Engine-written."}}
       [:maybe :waymark/ref]]
      [:scope_wanted {:optional true
                      :x-display
                      {:label "What the law asks of it"
                       :help "Every door the law has it run and every power its effects call; the scope its grant is minted with. Engine-written."}}
       [:maybe grants/scope-schema]]
      [:widened_by {:optional true
                    :x-display
                    {:label "What it asks anew"
                     :help "What the law asks of it that the grant it wears does not admit: a deploy widened its scope and sent it back to proposed, until a person approves it again. Engine-written."}}
       [:maybe grants/scope-schema]]
      [:created_by {:optional true
                    :x-ref {:principal true}
                    :x-display
                    {:label "Proposed by"
                     :help "Who made the row, a person or the agent that proposed it; that one cannot approve it. Engine-written."}}
       [:maybe [:string {:max 200}]]]
      [:allowed_by {:optional true
                    :x-ref {:principal true}
                    :x-display
                    {:label "Allowed by"
                     :help "The person whose tap ran an agent's held proposal. Engine-written."}}
       [:maybe [:string {:max 200}]]]
      [:approved_by {:optional true
                     :x-ref {:principal true}
                     :x-display
                     {:label "Approved by"
                      :help "The person whose tap minted its grant. Engine-written."}}
       [:maybe [:string {:max 200}]]]
      [:previous_secret {:optional true
                         :kind :secret
                         :x-display
                         {:label "The secret before"
                          :help "The secret row a rotate replaced. Engine-written."}}
       [:maybe :waymark/ref]]
      [:rotated_at {:optional true
                    :x-display
                    {:label "Rotated"
                     :help "When its secret was last rotated. Engine-written."}}
       [:maybe :waymark/instant]]]))
   :create-schema (into [:map] person-fields)
   :filterable {:state #{:eq :in}
                :name #{:eq}}
   :sortable {:fields [:name] :default "name"}
   :unique [[:name]]
   :no-own-surface "An integration is the house's; only a grant that names the kind shows that one exists."
   :create-guards [speaks-for-an-outside-member a-person-or-their-agent-proposes]
   :on-create born
   :actions
   {:approve
    {:from #{:proposed} :to :live
     :guards [a-person-holds-it the-creator-does-not-decide speaks-for-an-outside-member]
     :touches [{:kind :grant :action :create}
               {:kind :grant :action :accept}]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The approve mints the grant this integration wears; the way back is pause, or retire."}
     :handler mint-the-grant
     :display {:label "Approve" :style :primary :order 1
               :description "Let this integration act, as itself, with the scope the law asks of it"}}
    :pause
    {:from #{:live} :to :paused
     :guards [a-person-holds-it]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Pause" :order 2
               :description "Stop applying what it sends, and stop its calls, until resume"}}
    :resume
    {:from #{:paused} :to :live
     :guards [a-person-holds-it]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Resume" :order 3}}
    :rotate
    {:from #{:live :paused} :to :same
     :input [:map
             [:secret {:kind :secret
                       :x-display
                       {:label "The new secret"
                        :help "The secret row that holds the new signing key. The old one is accepted for 24 hours."}}
              :waymark/ref]]
     :record true
     ;; the new secret is a fresh choice, never the old one restated,
     ;; and any secret row of the house may be the one a person names
     :waives #{:edit-shape :ref-unjudged}
     :guards [a-person-holds-it]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The new secret signs from now on; the old one is accepted for 24 hours and then not again."}
     :handler rotate-secret
     :display {:label "Rotate the secret" :order 4}}
    ;; § 2: a deploy that widens `scope_wanted` asks again; the engine's
    ;; door alone, walked at boot (`rescope!`)
    :rescope
    {:from #{:live :paused} :to :proposed
     :input [:map [:widened_by grants/scope-schema]]
     :waives #{:edit-shape}
     :guards [the-engine-asks-again]
     :handler note-the-widening
     :safety {:idempotent true :reversible false :confirm false
              :one-way "What it sends is kept, not applied, until a person approves it again."}
     :display {:label "Ask again" :order 8}}
    :retire
    {:from #{:proposed :live :paused} :to :retired
     :guards [a-person-holds-it]
     :safety {:idempotent true :reversible false :confirm true
              :consequence "This integration stops for good: nothing it sends is applied and nothing calls it again."}
     :display {:label "Retire" :style :danger :order 9}}}
   :deviations
   ["§ 2 says `approve` mints the grant through the ordinary approval path. It mints it in the approve's own transaction instead, through the ctx create door, and the engine accepts it there: an approval_request would be one more person's tap for the same decision, and its grant lands post-commit at the wire boundary (grants/wire-boundary-effects), which this door does not need."
    "§ 2's `event_id` and `event_type` are spelled `event_id_path` and `event_type_path`: they hold a path into the body, and a field named `event_id` reads as a ref to an `event` row."]})

;; ── the receipt (§ 3, build step 2) ─────────────────────────────────

(g/defguard the-engine-keeps-the-receipt
  {:reads [:principal]
   :hide true
   :explain "The engine keeps a receipt when a signed event reaches its integration's receiver; nobody writes one by hand."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type]))
    (t/allow)
    (t/deny)))

(def body-cap
  "How much of an event's body a receipt keeps: 64 KB."
  65536)

(def ^:private receipt-fields
  [[:integration {:kind :integration
                  :x-display
                  {:label "The integration"
                   :help "The integration whose receiver took the event."}}
    :waymark/ref]
   [:event_id {:x-display
               {:raw true
                :label "The event's id"
                :help "Its own id, read from the body at the integration's event_id_path. One receipt per id."}}
    [:string {:min 1 :max 200}]]
   [:event_type {:optional true
                 :x-display
                 {:raw true
                  :label "The event's type"
                  :help "Read from the body at the integration's event_type_path."}}
    [:maybe [:string {:min 1 :max 200}]]]
   [:body {:optional true
           :x-display
           {:raw true
            :label "The body"
            :help "The body as it arrived, cut at 64 KB."}}
    [:maybe [:string {:max body-cap}]]]
   [:bytes {:x-display
            {:label "Its size, in bytes"
             :help "How many bytes the body was, before any cut."}}
    [:int {:min 0}]]
   [:truncated {:optional true
                :x-display
                {:label "Cut"
                 :help "True when the body was longer than 64 KB and the receipt keeps the first 64 KB of it."}}
    [:maybe :boolean]]
   [:door {:optional true
           :x-display
           {:raw true
            :label "The door"
            :help "The kind and the door the applier ran, as kind.door. Engine-written."}}
    [:maybe [:string {:min 1 :max 200}]]]
   [:target {:optional true
             :x-display
             {:raw true
              :label "The row"
              :help "The id of the one row the event's match named. Engine-written."}}
    [:maybe [:string {:min 1 :max 200}]]]
   [:transition {:optional true
                 :x-display
                 {:raw true
                  :label "The transition"
                  :help "The id of the transition the door wrote. Engine-written."}}
    [:maybe [:string {:min 1 :max 200}]]]
   [:refusal {:optional true
              :x-display
              {:raw true
               :label "The refusal"
               :help "The door's refusal, whole: its reason, guards and remedies, as JSON. Engine-written."}}
    [:maybe [:string {:min 1 :max body-cap}]]]
   [:tell {:optional true
           :x-display
           {:label "Who hears it"
            :help "The seats the entry's :tell names, or the integration's approver, when it was refused. Engine-written."}}
    [:maybe [:vector [:string {:min 1 :max 200}]]]]
   [:reason {:optional true
             :x-display
             {:label "Why it was let go"
              :help "The sentence its dismiss gave."}}
    [:maybe [:string {:min 1 :max 280}]]]])

(defhandler keep-the-ending [row inp _ctx]
  (update row :data merge inp))

(defhandler clear-the-ending [row _inp _ctx]
  (update row :data dissoc :door :target :transition :refusal :tell))

(g/defguard not-the-integration
  {:reads [:principal]
   :open "No door changes who the caller is: ask a person of the house, or a seat."
   :explain "A person or a seat retries or dismisses a receipt; the integration that sent it does not."}
  [_row _inp ctx]
  (if (integration-principal? (:principal ctx)) (t/deny) (t/allow)))

(def ^:private an-ending
  "An applier's door: the engine writes how a receipt ended."
  {:from #{:received}
   :guards [the-engine-keeps-the-receipt]
   :handler keep-the-ending
   :safety {:idempotent true :reversible false :confirm false
            :one-way "The applier writes how the receipt ended; retry is the way back from a refusal."}})

(def ^:private ending-text [:string {:min 1 :max 200}])

(def ^:private after-the-ending #{:refused :unmatched :ambiguous})

(defresource integration-event
  {:kind :integration_event
   :plural "integration_events"
   :states [:received :applied :refused :unmatched :ambiguous :ignored :dismissed]
   :initial :received
   :terminal #{:applied :ignored :dismissed}
   :nav :system
   :summary "{data.event_type} {data.event_id} · {state}"
   :label-template "{data.event_id}"
   :schema (into [:map] receipt-fields)
   :filterable {:state #{:eq :in}
                :integration #{:eq}
                :event_id #{:eq}
                :event_type #{:eq}}
   :sortable {:fields [:event_id] :default "event_id"}
   :unique [[:integration :event_id]]
   :no-own-surface "A receipt is the house's; only a grant that names the kind shows that one exists."
   :create-guards [the-engine-keeps-the-receipt]
   ;; its exits are the applier's alone, and no seat takes them
   :allow-dead #{:received}
   :actions
   {:mark_applied (assoc an-ending :to :applied
                         :waives #{:edit-shape}
                         :input [:map [:door ending-text] [:target ending-text]
                                 [:transition {:optional true} [:maybe ending-text]]])
    :mark_refused (assoc an-ending :to :refused
                         :waives #{:edit-shape}
                         :input [:map [:door ending-text]
                                 [:target {:optional true} [:maybe ending-text]]
                                 [:refusal {:x-display {:raw true}}
                                  [:string {:min 1 :max body-cap}]]
                                 [:tell {:optional true} [:maybe [:vector ending-text]]]])
    :mark_unmatched (assoc an-ending :to :unmatched)
    :mark_ambiguous (assoc an-ending :to :ambiguous)
    :mark_ignored (assoc an-ending :to :ignored)
    :retry
    {:from after-the-ending :to :received
     :guards [not-the-integration]
     :handler clear-the-ending
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The applier applies it again, under the law as it stands now."}
     :display {:label "Retry" :style :primary :order 1
               :description "Apply this event again, under the law as it stands now"}}
    :dismiss
    {:from after-the-ending :to :dismissed
     :input [:map [:reason {:x-display {:label "Why it is let go"
                                        :help "One sentence for the next reader."}}
                   [:string {:min 1 :max 280}]]]
     :waives #{:edit-shape}
     :guards [not-the-integration]
     :handler keep-the-ending
     :safety {:idempotent true :reversible false :confirm false
              :one-way "A dismissed receipt is kept, and never applied."}
     :display {:label "Dismiss" :order 2
               :description "Let this event go, saying why"}}}
   :deviations
   ["§ 3's endings are states, and the doors that land them (mark_applied, mark_refused, mark_unmatched, mark_ambiguous, mark_ignored) are the engine's own, hidden from everyone else; `dismiss` lands a seventh state, `dismissed`."
    "§ 3 finds the `:on-event` entries for the source and type, then the row by `:match`. When several entries name the type, the first in kind order whose match names exactly one row runs; the receipt lands `ambiguous` only when none does and one names more than one row."
    "§ 3's `:tell` wakes the seats it names, or the approver. The refused receipt carries them as `tell`, and the wakes consumer fires each active seat it names, by name or id, whatever its subscriptions; an approver who is a person, not a seat, is not told yet."
    "§ 3's applier is a log consumer, and also a sweep every 30 seconds over the receipts still `received`: the sweep tries a `:retry-unmatched-for` entry again, and applies what a crash or a pause left."
    "§ 3's `received` is the receipt's state; the moment it arrived is the row's own creation time, not a field."
    "§ 3's `:via :inbox` entry makes its row with the entry's `:input`; with none, the event body's top-level fields are the create's input. A refused create is a `refused` receipt with no target."
    "§ 8 puts `[source-unsigned]` in the declaration battery, which reads the law; an integration's signature is a row's data, so `findings` reads the rows and is not yet in that battery."]})

;; ── the outbox (§ 4, build step 5) ──────────────────────────────────

(g/defguard the-engine-keeps-the-call
  {:reads [:principal]
   :hide true
   :explain "The engine keeps an effect call when a door that declares an :effect is walked; nobody writes one by hand."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type]))
    (t/allow)
    (t/deny)))

(def answer-cap
  "How much of an answer an effect_call keeps: 16 KB, as a held call's."
  16384)

(def ^:private call-text [:string {:min 1 :max 200}])

(def ^:private call-fields
  [[:power {:x-display {:raw true :label "The power"
                        :help "The tool the call names, as server__tool."}}
    call-text]
   [:idempotency_key {:x-display {:raw true :label "Its key"
                                  :help "What makes it once: one call per power and key."}}
    call-text]
   [:target_kind {:x-display {:raw true :label "The kind"
                              :help "The kind of the row whose door asked for the call."}}
    call-text]
   [:target {:x-display {:raw true :label "The row"
                         :help "The id of the row whose door asked for the call."}}
    call-text]
   [:door {:x-display {:raw true :label "The door"
                       :help "The door that asked for the call."}}
    call-text]
   [:input {:optional true
            :x-display {:raw true :label "What it sends"
                        :help "The call's input as JSON, read from the row as the door left it."}}
    [:maybe [:string {:max answer-cap}]]]
   [:why {:optional true
          :x-display {:label "Why"
                      :help "The sentence a person who approves a held call reads."}}
    [:maybe [:string {:min 1 :max 480}]]]
   [:on_behalf_of {:x-display {:raw true :label "On behalf of"
                               :help "Who walked the door that asked for the call."}}
    call-text]
   [:answer {:optional true
             :x-display {:raw true :label "The answer"
                         :help "What the call answered, as JSON, cut at 16 KB. Engine-written."}}
    [:maybe [:string {:max answer-cap}]]]
   [:reason {:optional true
             :x-display {:label "Why it failed"
                         :help "The server's sentence when it refused the call. Engine-written."}}
    [:maybe [:string {:min 1 :max 480}]]]
   [:held_call {:optional true
                :x-display {:raw true :label "The held call"
                            :help "The held_call a person decides when the power waits on a person's tap. Engine-written."}}
    [:maybe call-text]]
   [:tries {:optional true
            :x-display {:label "Tries"
                        :help "How many times the runner sent it. Written before each try. Engine-written."}}
    [:maybe [:int {:min 0 :max 9}]]]])

(def ^:private a-call-ending
  "A runner's door: the engine writes how a call ended."
  {:from #{:pending}
   :guards [the-engine-keeps-the-call]
   :handler keep-the-ending
   :waives #{:edit-shape}
   :safety {:idempotent true :reversible false :confirm false
            :one-way "A call that ended stays ended; the row's own doors are the way on."}})

(defresource effect-call
  {:kind :effect_call
   :plural "effect_calls"
   :states [:pending :held :done :failed :unknown]
   :initial :pending
   :terminal #{:done :failed :unknown}
   :nav :system
   :summary "{data.power} {data.idempotency_key} · {state}"
   :label-template "{data.idempotency_key}"
   :schema (into [:map] call-fields)
   :filterable {:state #{:eq :in}
                :power #{:eq}
                :idempotency_key #{:eq}
                :target #{:eq}
                :held_call #{:eq}}
   :sortable {:fields [:idempotency_key] :default "idempotency_key"}
   :unique [[:power :idempotency_key]]
   :no-own-surface "An effect call is the house's; only a grant that names the kind shows that one exists."
   :create-guards [the-engine-keeps-the-call]
   ;; its exits are the runner's alone, and no seat takes them
   :allow-dead #{:pending :held}
   :actions
   {:mark_done (assoc a-call-ending :to :done
                      :from #{:pending :held}
                      :input [:map [:answer {:x-display {:raw true}}
                                    [:string {:max answer-cap}]]])
    :mark_failed (assoc a-call-ending :to :failed
                        :from #{:pending :held}
                        :input [:map [:reason [:string {:min 1 :max 480}]]])
    :mark_unknown (assoc a-call-ending :to :unknown
                         :input [:map [:reason [:string {:min 1 :max 480}]]])
    :mark_held (assoc a-call-ending :to :held
                      :input [:map [:held_call call-text]])
    :note_try (assoc a-call-ending :to :pending
                     :input [:map [:tries [:int {:min 1 :max 9}]]])}
   :deviations
   ["§ 4's runner endings (`mark_done`, `mark_failed`, `mark_unknown`, `mark_held`) and `note_try` are the engine's own, hidden from everyone else. `note_try` writes each try on the call before the wire, so a call a crash left `pending` after a try ends `unknown` and is not sent again, unless its power entry says `idempotent: true`."
    "§ 4's three tries of an idempotent call run in one pass, a second and then two seconds apart; a call still unanswered after the third is `unknown`."
    "§ 4 names no integration on the call: the runner calls as the live integration whose `server` holds the power's row. With one that is not live yet the call waits `pending`; with none it is `failed`, unsent."
    "§ 4's refusal is a refusal the power door or the server gives before or instead of an answer: a problem under 500, or an `isError` answer. A 5xx or a dropped wire is the unknown ending."
    "§ 4 names two hidden doors, `effect_landed` and `effect_failed`; each effect door has its own pair, `<door>_landed` and `<door>_failed`, because each names its own `:lands` and `:fails-to`."
    "§ 4 says what a `done` key does when a door asks again; a `failed` key lands its failure again through the door's failed door in the same way, and a `pending` one adds nothing and lands on its own."
    "§ 4's `:why` fills each {field} from the row's data, as a summary does."]})

;; ── the receiver (§ 3, build step 2) ────────────────────────────────

(def default-signature
  "How an integration signs when its `signature` leaves a part empty."
  {:header "X-Signature" :scheme "hmac-sha256" :tolerance_s 300})

(def ^:private rotation-grace (Duration/ofHours 24))

(defn sign
  "hex hmac-sha256(key, body): the signature a source sends."
  ^String [^String k ^bytes body]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. (.getBytes k StandardCharsets/UTF_8)
                                     "HmacSHA256")))]
    (str/join (map #(format "%02x" %) (.doFinal mac body)))))

(defn- signed-by?
  "Does `sent` sign `body` under `k`? Compared in constant time."
  [^String sent ^String k ^bytes body]
  (keys/equal? (sign k body) sent))

(defn- refuse [type status title detail]
  (p/problem type status title {:detail detail}))

(defn- rows-of [eng kind filt]
  (let [rd (get (inv/resources eng) kind)
        st (:storage eng)]
    (mapv #(inv/decode-row rd %)
          (store/with-tx st
            (fn [tx] (store/query-rows st tx kind filt {:limit 2}))))))

(defn- keys-of
  "The signing keys an integration's events may carry now: its secret's
  value, and for 24 hours after a rotate the one it replaced."
  [eng row ^Instant now]
  (let [d (:data row)
        value (fn [id] (some->> id (secrets/value-of eng) :value))
        rotated (instant-of (:rotated_at d))]
    (filterv some?
             (cond-> [(value (:secret d))]
               (and rotated (.isBefore now (.plus rotated rotation-grace)))
               (conj (value (:previous_secret d)))))))

(defn- fresh?
  "With a timestamp header, is the stamp it carries (epoch seconds)
  within the tolerance of now? Without one, nothing is checked."
  [headers sig ^Instant now]
  (if-some [h (some-> (:timestamp_header sig) str/lower-case)]
    (if-some [ts (try (some-> (get headers h) str/trim Long/parseLong)
                      (catch NumberFormatException _ nil))]
      (<= (Math/abs (- (.getEpochSecond now) (long ts)))
          (long (:tolerance_s sig)))
      false)
    true))

(defn- bytes-of ^bytes [b]
  (cond (nil? b) (byte-array 0)
        (bytes? b) b
        (string? b) (.getBytes ^String b StandardCharsets/UTF_8)
        :else (.readAllBytes ^InputStream b)))

(defn- at-path [body path]
  (reduce (fn [m k] (when (map? m) (or (get m (keyword k)) (get m k))))
          body path))

(defn receive!
  "POST /api/-/events/<nm>, judged. → {:status 202 :receipt id
  :duplicate false} when a receipt is kept, {:status 200 :receipt id
  :duplicate true} when one already was; otherwise throws the problem
  the route answers, having stored nothing: 404 no such integration,
  503 no key to check with, 401 a bad signature or a stale stamp, 503
  `paused`, 410 `retired`, 422 a body with no event id. The signature
  is judged before the state, so an unsigned caller learns no more
  than that the name exists."
  [eng nm headers body]
  (let [now ((:now-fn eng))
        row (or (first (rows-of eng :integration {:name (str nm)}))
                (throw (refuse :not-found 404 "Not found"
                               "No integration of that name.")))
        d (:data row)
        sig (merge default-signature
                   (into {} (remove (comp nil? val)) (:signature d)))
        ;; § 3's option kept: a source that cannot sign says so, and
        ;; only then is the signature step skipped
        unsigned (= "none" (:scheme sig))
        ks (when-not unsigned (keys-of eng row now))
        _ (when (and (not unsigned) (empty? ks))
            (throw (refuse :no-signing-key 503 "No signing key"
                           "The integration's secret holds no value to check a signature against; the owner enters one.")))
        bs (bytes-of body)
        raw (some-> (get headers (str/lower-case (:header sig))) str/trim)
        ;; a declared prefix must be there; without one, a sha256= is
        ;; still forgiven, as before
        sent (some-> (if-some [px (not-empty (:prefix sig))]
                       (when (and raw (str/starts-with? raw px))
                         (subs raw (count px)))
                       (some-> raw (str/replace #"^sha256=" "")))
                     str/lower-case not-empty)
        header-of (fn [h] (some-> (get headers (str/lower-case h)) str/trim not-empty))]
    (when-not (or unsigned
                  (and sent (some #(signed-by? sent % bs) ks) (fresh? headers sig now)))
      (throw (refuse :bad-signature 401 "Unauthorized"
                     "The signature does not match the body, or its timestamp is outside the tolerance.")))
    (case (:state row)
      :paused (throw (refuse :integration-paused 503 "Paused"
                             "This integration is paused; send it again later."))
      :retired (throw (refuse :integration-retired 410 "Retired"
                              "This integration is retired; nothing it sends is kept."))
      nil)
    (let [text (String. bs StandardCharsets/UTF_8)
          parsed (try (wire/read-json text) (catch Exception _ nil))
          id-header (not-empty (:id_header d))
          type-header (not-empty (:type_header d))
          event-id (if id-header
                     (header-of id-header)
                     (some-> (at-path parsed (or (not-empty (:event_id_path d)) ["id"]))
                             str not-empty))
          _ (when-not event-id
              (throw (refuse :no-event-id 422 "No event id"
                             (if id-header
                               "The delivery carries no event id in the integration's id_header."
                               "The body carries no event id at the integration's event_id_path."))))
          event-type (if type-header
                       (header-of type-header)
                       (some-> (at-path parsed (or (not-empty (:event_type_path d)) ["type"]))
                               str not-empty))
          iid (str (:id row))
          kept (fn [] (when-some [r (first (rows-of eng :integration_event
                                                    {:integration iid :event_id event-id}))]
                        {:status 200 :receipt (str (:id r)) :duplicate true}))]
      (or (kept)
          (try
            {:status 202 :duplicate false
             :receipt (str (get-in (inv/create!
                                    eng :integration_event
                                    (cond-> {:integration iid
                                             :event_id event-id
                                             :body (subs text 0 (min (count text) body-cap))
                                             :bytes (alength bs)
                                             :truncated (> (alength bs) body-cap)}
                                      event-type (assoc :event_type event-type))
                                    {:principal engine-actor})
                                   [:row :id]))}
            (catch Exception e
              ;; two deliveries of one event at once: the index keeps
              ;; one, and the other answers it
              (or (and (:waymark10/unique-violation (ex-data e)) (kept))
                  (throw e))))))))

;; ── the law it is held to (§ 2, build step 3) ─────────────────────────────

(defn- inbox? [e] (= :inbox (:via e)))

(defn- create-verb
  "The made kind's create door: `create` when it has one, else the first
  it names."
  [rd]
  (let [names (:create-action-names rd #{:create})]
    (if (contains? names :create) :create (first (sort names)))))

(defn- effect-powers
  "The tokens of the powers the law's `:effect` doors call whose tool
  resolves, among the server rows `servers`, to the row `sid`."
  [rdefs servers sid]
  (when-some [sid (some-> sid str not-empty)]
    (into (sorted-set)
          (for [rd rdefs
                [_ a] (:actions rd)
                :let [power (get-in a [:effect :power])]
                :when power
                :let [hit (servers/resolve-among servers power)]
                :when (and (:token hit)
                           (= sid (str (get-in hit [:row :id]))))]
            (:token hit)))))

(defn wanted
  "`scope_wanted` read from the law: for each kind whose `:on-event`
  names the integration `nm` as its source, the doors those entries
  run, and for each kind a `:via :inbox` entry `:makes`, its create;
  then, given the server rows and the integration's `server` id `sid`,
  the token of each `:effect` power whose tool resolves to that row.
  nil when the law names it nowhere."
  ([rdefs nm] (wanted rdefs nm nil nil))
  ([rdefs nm servers sid]
  (let [by-kind (into {} (map (juxt :kind identity)) rdefs)
        doors (reduce
               (fn [m [k door]] (update m k (fnil conj (sorted-set)) door))
               {}
               (for [rd rdefs
                     e (:on-event rd)
                     :when (= (name (:source e)) (str nm))]
                 (if (inbox? e)
                   [(:makes e) (name (create-verb (get by-kind (:makes e))))]
                   [(:kind rd) (name (:door e))])))]
    (not-empty
     (into (vec (for [[k ds] (sort-by (comp str key) doors)]
                  {:kind (name k) :actions (vec ds)}))
           (for [token (effect-powers rdefs servers sid)]
             {:kind token :actions []}))))))

(defn- power-entry? [e] (str/includes? (str (:kind e)) "."))

(defn- law-wants
  "`data` with its `scope_wanted` written from the law. With a write's
  `ctx` the server rows are read and the effect powers resolved; a
  boot reads no rows, so the powers the last birth or approve resolved
  stand until the next approve resolves them again."
  [rdefs data ctx]
  (let [w (if-some [find (:find ctx)]
            (wanted rdefs (:name data) (find :mcp_server {} {:all true}) (:server data))
            (not-empty (into (vec (wanted rdefs (:name data)))
                             (filter power-entry?)
                             (:scope_wanted data))))]
    (if w
      (assoc data :scope_wanted w)
      (dissoc data :scope_wanted))))

(defn integration-for
  "The integration kind an application's law enrols: a row's birth, and
  every boot after (`:data-migrate`), write its `scope_wanted` from the
  `:on-event` entries that name it and the `:effect` powers its server
  holds; the approve writes it again (`mint-the-grant`)."
  [rdefs]
  (assoc integration
         :on-create (fn [row ctx] (update (born row ctx) :data #(law-wants rdefs % ctx)))
         :data-migrate #(law-wants rdefs % nil)))

;; ── the applier (§ 3, build step 3) ─────────────────────────────────

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 integrations: " parts))))

(defn- load-one [eng kind id]
  (let [st (:storage eng)]
    (some->> (store/with-tx st #(store/load-row st % kind (str id) {}))
             (inv/decode-row (get (inv/resources eng) kind)))))

(defn- entries-for
  "The `:on-event` entries of the law that name this source and type,
  each with the kind it is declared on, in kind order."
  [eng source type]
  (for [[k rd] (sort-by (comp str key) (inv/resources eng))
        e (:on-event rd)
        :when (and (= (name (:source e)) (str source)) (= (:type e) type))]
    (assoc e :kind k)))

(defn- known [x scope]
  (let [v (expr/evaluate x scope)]
    (when-not (expr/unknown? v) v)))

(defn- candidates
  "The rows an entry's `:match` names: at most two, enough to tell one
  from many. A value the event does not carry matches nothing. A field
  the entry names `:absent` is one the row must not carry yet. Only a
  row in a state the entry's door opens from counts: a row the door
  cannot move (a change closed before it got a number, on the live
  one's branch) makes no match ambiguous (ticket 7a529550)."
  [eng {:keys [kind match absent door]} scope]
  (let [filt (into {} (map (fn [[f x]] [f (known x scope)])) match)
        filt (into filt (map (fn [f] [f {:absent true}])) absent)
        from (seq (sort (get-in (inv/resources eng) [kind :actions door :from])))]
    (cond
      (some nil? (vals filt)) []
      (nil? from) (rows-of eng kind filt)
      :else (vec (take 2 (mapcat #(rows-of eng kind (assoc filt :state %)) from))))))

(defn- refusal-json
  "The refusal a door threw, whole: its problem document, as JSON."
  [e]
  (let [doc (into {} (remove (comp qualified-keyword? key)) (ex-data e))]
    (try (wire/write-json doc)
         (catch Exception _
           (wire/write-json (select-keys doc [:type :status :title :detail]))))))

(defn- land!
  "Write how a receipt ended, as the engine. A receipt another pass
  landed first refuses the door, and that is no news."
  [eng rid door inp]
  (try (inv/invoke! eng :integration_event (str rid) door inp {:principal engine-actor})
       (catch clojure.lang.ExceptionInfo e
         (warn! "receipt " rid " did not take " (name door) " (" (ex-message e) ")"))))

(defn- party-of
  "Who an event's door is logged as (docs/spec-teams.md §7, build step
  8). The integration itself, `it`, unless the row names `on_behalf_of`:
  then that outside member, marked :via the integration, so `(actor)`,
  `:stamps` and `:not-by` read the party and the log still says whose
  system spoke — \"report_lost by Parcelco (via Shippo)\". The grant
  judged stays the integration's. An :agent, as a link's arrival is:
  no person's wall opens to a system. nil when the member it names is
  no outside member, or is suspended."
  [eng irow it]
  (if-some [mid (some-> (get-in irow [:data :on_behalf_of]) str not-empty)]
    (let [st (:storage eng)
          m (some->> (store/with-tx st #(store/load-row st % :member mid {}))
                     (inv/decode-row (get (inv/resources eng) :member)))]
      (when (and m
                 (= "outside" (str (get-in m [:data :actor_type])))
                 (not= "suspended" (name (:state m))))
        (assoc (t/principal
                {:id (let [s (str (get-in m [:data :subject]))]
                       (if (str/blank? s) (str (:id m)) s))
                 :type :agent
                 :display (or (not-empty (str (get-in m [:data :display]))) (str (:id m)))
                 :roles (set (map str (get-in m [:data :roles])))})
               :via (:id it)
               :via-display (:display it))))
    it))

(defn- pull-now!
  "An event marked a Mirror row stale: its adapter reads the truth now,
  not at the next read (docs/spec-change.md §4.1, R-C.8). The pull is
  the Mirror's own, written as the sync; one that fails leaves the row
  stale or unreachable, and the next read or resync pulls it again."
  [eng kind id]
  (let [rdef (get (inv/resources eng) kind)]
    (when (:mirror rdef)
      (try (some->> (load-one eng kind id) (mirror/refresh! eng rdef))
           (catch Exception e
             (warn! (name kind) " " id " was marked stale but not pulled ("
                    (ex-message e) ")"))))))

(defn- invoke-entry!
  "Run the entry's door on the one row it matched, as the integration,
  under its grant and the `event:` key, and land what it answered. A
  `:via :inbox` entry runs its made kind's create instead, with no row
  matched. A refusal is kept whole; a fault that is no refusal is
  thrown, and the receipt stays `received` for the next pass."
  [eng irow r e target scope]
  (let [made (when (inbox? e) (:makes e))
        kind (or made (:kind e))
        door (if made (create-verb (get (inv/resources eng) made)) (:door e))
        id (some-> (:id target) str)
        label (str (name kind) "." (name door))
        told (vec (or (seq (map name (:tell e)))
                      (some-> (get-in irow [:data :approved_by]) str vector)))
        refused (fn [doc]
                  (land! eng (:id r) :mark_refused
                         (cond-> {:door label :refusal doc}
                           id (assoc :target id)
                           (seq told) (assoc :tell told))))
        who (principal irow)
        actor (party-of eng irow who)
        vis (when-some [gid (some-> (get-in irow [:data :grant]) str not-empty)]
              (when (= :active (:standing (grants/grant-standing eng gid)))
                (grants/visibility eng gid who)))
        inp (if (and made (nil? (:input e)))
              (into {} (keep (fn [[k v]] (when (some? v) [(keyword (name k)) v])))
                    (:event scope))
              (into {} (keep (fn [[f x]] (when-some [v (known x scope)] [f v])))
                    (:input e)))
        opts (fn [g] {:principal actor
                      :grant g
                      :idempotency-key (str "event:" (:id irow) ":"
                                            (get-in r [:data :event_id]))})]
    (cond
      (not (and vis ((:action? vis) kind door) (or made ((:row? vis) kind id))))
      (refused (refusal-json
                (refuse :not-granted 403 "Not granted"
                        (str "The grant this integration wears does not admit " label
                             "; approve it again so it wears what the law asks of it."))))

      (nil? actor)
      (refused (refusal-json
                (refuse :not-an-outside-party 403 "Not an outside party"
                        (str "This integration acts on behalf of member "
                             (get-in irow [:data :on_behalf_of])
                             ", who is no outside member able to act; name an outside member, or none."))))

      :else
      (try
        (let [res (if made
                    (inv/create! eng kind inp (assoc (opts (:grant vis)) :action door))
                    (inv/invoke! eng kind id door inp (opts (:grant vis))))
              tid (or (get-in res [:transition :id])
                      (get-in res [:response :transition :id]))
              id (or id (some-> (or (get-in res [:row :id])
                                    (get-in res [:response :row :id]))
                                str))]
          (land! eng (:id r) :mark_applied
                 (cond-> {:door label :target id} tid (assoc :transition (str tid))))
          (when (and (not made) (= :mark_stale door))
            (pull-now! eng kind id)))
        (catch clojure.lang.ExceptionInfo ex
          (if (:waymark10/problem (ex-data ex))
            (refused (refusal-json ex))
            (throw ex)))))))

(defn- waiting?
  "Does an entry that matched nothing still wait, inside its
  `:retry-unmatched-for`, for the row to exist?"
  [r found ^Instant now]
  (when-some [born (instant-of (:created-at r))]
    (some (fn [{s :retry-unmatched-for}]
            (and s (.isBefore now (.plusSeconds born (long s)))))
          found)))

(defn- discovered
  "`found` again after discovery, when no entry named a row and an entry
  declaring `:discover true` named none: each such entry's Mirror kind
  runs `discover!` once, so an event about a row the Mirror has not
  minted yet mints and fills it, and every entry is matched again
  (docs/spec-change.md §4.1). Else `found` as it is."
  [eng found look]
  (let [kinds (distinct (keep #(when (and (:discover %) (empty? (:rows %))) (:kind %))
                              found))]
    (if (and (seq kinds) (not-any? #(or (inbox? %) (seq (:rows %))) found))
      (do (doseq [k kinds] (mirror/discover! eng k))
          (look))
      found)))

(defn- discover-behind!
  "After an entry's door ran, each Mirror kind whose `:discover true`
  entry for the same event matched nothing runs discovery once: the
  door may have given the house the identity that Mirror lists, as a
  change learning its pull request's number does (ticket 20a962be), so
  the same receipt mints that row. A discovery that fails is left to
  the Mirror's own next one, and never costs the receipt."
  [eng hit found]
  (doseq [k (distinct (keep #(when (and (:discover %) (empty? (:rows %))
                                        (not= (:kind %) (:kind hit)))
                               (:kind %))
                            found))]
    (try (mirror/discover! eng k)
         (catch Exception e
           (warn! "discovery of " (name k) " behind a receipt failed (" (ex-message e) ")")))))

(defn- ignored?
  "Does an entry that matched nothing say, from the event alone, that no
  row of its kind could own it: its `:ignore-when` true?"
  [e scope]
  (when-some [x (:ignore-when e)]
    (true? (known x scope))))

(defn apply!
  "Apply one receipt that is still `received`, of a `live` integration:
  run the door its entry names on the one row the match names, or land
  `ignored` (no entry names the type, or one's `:ignore-when` holds),
  `ambiguous` (more than one row) or `unmatched` (none, once no
  `:retry-unmatched-for` waits). An entry
  with `:discover true` that matched nothing runs its Mirror's discovery
  first. Any other receipt is left as it is, so a replay moves nothing."
  [eng rid]
  (when-some [r (load-one eng :integration_event rid)]
    (let [irow (load-one eng :integration (get-in r [:data :integration]))]
      (when (and (= :received (:state r)) (= :live (:state irow)))
        (let [body (try (wire/read-json (str (get-in r [:data :body])))
                        (catch Exception _ nil))
              scope {:event body :now ((:now-fn eng))}
              es (entries-for eng (get-in irow [:data :name]) (get-in r [:data :event_type]))
              look (fn [] (mapv #(if (inbox? %) % (assoc % :rows (candidates eng % scope))) es))
              found (discovered eng (look) look)
              hit (first (filter #(or (inbox? %) (= 1 (count (:rows %)))) found))]
          (cond
            (empty? es) (land! eng rid :mark_ignored {})
            hit (let [res (invoke-entry! eng irow r hit (first (:rows hit)) scope)]
                  (discover-behind! eng hit found)
                  res)
            (some #(< 1 (count (:rows %))) found) (land! eng rid :mark_ambiguous {})
            (some #(ignored? % scope) found) (land! eng rid :mark_ignored {})
            (waiting? r found (:now scope)) nil
            :else (land! eng rid :mark_unmatched {})))))))

;; ── a deploy that widens the scope asks again (§ 2) ─────────────────

(defn widened
  "What an integration row's `scope_wanted` asks that the grant it wears
  does not admit, door by door, as scope entries; nil when its grant
  admits it all, or it wears no active grant. A power entry is not
  judged: a boot never widens one (`law-wants`)."
  [eng irow]
  (when-some [gid (some-> (get-in irow [:data :grant]) str not-empty)]
    (when (= :active (:standing (grants/grant-standing eng gid)))
      (let [admits? (:action? (grants/visibility eng gid (principal irow)))]
        (not-empty
         (vec (for [{k :kind ds :actions :as e} (get-in irow [:data :scope_wanted])
                    :when (not (power-entry? e))
                    :let [missing (remove #(admits? (keyword (name k)) (keyword (name %))) ds)]
                    :when (seq missing)]
                {:kind (name k) :actions (mapv name missing)})))))))

(defn rescope!
  "§ 2: a deploy that widens `scope_wanted` moves the row to `proposed`
  again. At boot, once `:data-migrate` wrote each row's `scope_wanted`
  from the law, every live or paused integration whose grant does not
  admit it all walks `rescope`, naming what is new in `widened_by`. The
  receiver keeps what it sends and `apply!` leaves it `received` until
  a person's `approve` mints the wider grant; the sweep then applies
  it. A narrowing, and a boot that changed nothing, move nothing.
  → the ids it moved."
  [eng]
  (let [st (:storage eng)
        rd (get (inv/resources eng) :integration)
        raws (store/with-tx st (fn [tx] (store/query-rows st tx :integration {} {:limit 1000})))]
    (vec (for [raw raws
               :let [irow (inv/decode-row rd raw)]
               :when (#{:live :paused} (:state irow))
               :let [w (widened eng irow)]
               :when (and w
                          (try (inv/invoke! eng :integration (str (:id irow)) :rescope
                                            {:widened_by w} {:principal engine-actor})
                               true
                               (catch clojure.lang.ExceptionInfo e
                                 (warn! "integration " (:id irow) " did not take rescope ("
                                        (ex-message e) ")")
                                 false)))]
           (str (:id irow))))))

;; ── the audit (§ 8, build step 4) ───────────────────────────────────

(defn findings
  "What § 8's audit says of these integration rows: `[source-unsigned]`,
  a warning, for each that signs with scheme none, naming the doors its
  events run without a seat reading them first. Pure."
  [rdefs irows]
  (vec (for [r (sort-by #(str (get-in % [:data :name])) irows)
             :let [nm (str (get-in r [:data :name]))]
             :when (= "none" (get-in r [:data :signature :scheme]))
             :let [doors (for [rd (sort-by (comp str :kind) rdefs)
                               e (:on-event rd)
                               :when (and (= (name (:source e)) nm) (not (inbox? e)))]
                           (str (name (:kind rd)) "." (name (:door e))))]]
         (checks/integration-finding
          :source-unsigned
          (str "integration " nm " signs with scheme none, so its events are not checked"
               (when (seq doors)
                 (str ", and " (str/join ", " doors) " run on them unread")))
          {}))))

(defn handle-transition!
  "One committed transition: a receipt's birth, or its `retry`, applies
  it. Never throws: a parked cursor would stop every later receipt."
  [eng t]
  (try
    (when (and (= :integration_event (some-> (:kind t) keyword))
               (contains? #{"create" "retry"} (some-> (:action t) name)))
      (apply! eng (:resource-id t)))
    (catch Exception e
      (warn! "transition " (:id t) " could not be applied — " (ex-message e))))
  nil)

(defn sweep!
  "Apply every receipt still `received`: one a `:retry-unmatched-for`
  keeps waiting, one a crash or a pause left behind."
  [eng]
  (let [st (:storage eng)]
    (doseq [raw (store/with-tx st #(store/query-rows st % :integration_event
                                                     {:state :received} {:limit 200}))]
      (try (apply! eng (:id raw))
           (catch Exception e
             (warn! "receipt " (:id raw) " could not be applied — " (ex-message e)))))))

(def ignored-kept
  "How long an `ignored` receipt is kept before the purge (§ 3)."
  (Duration/ofDays 30))

(defn purge-ignored!
  "Delete every `ignored` receipt that landed more than `ignored-kept`
  before `now` — a maintenance delete with no transition, as a
  transcript's lines go; the landing transition stays in the log as
  the record of it. → how many."
  ([eng] (purge-ignored! eng (Instant/now)))
  ([eng ^Instant now]
   (let [st (:storage eng)
         cutoff (.minus now ^Duration ignored-kept)]
     (loop [n 0]
       (let [ids (store/with-tx st
                   (fn [tx]
                     (->> (store/query-rows st tx :integration_event
                                            {:state :ignored}
                                            {:order-by :updated_at :limit 500})
                          (take-while #(some-> ^Instant (:updated-at %) (.isBefore cutoff)))
                          (mapv #(str (:id %))))))]
         (if (empty? ids)
           n
           (recur (+ n (long (store/with-tx st
                               (fn [tx] (store/delete-rows! st tx :integration_event ids))))))))))))

(defn start-applier!
  "The applier: a durable log consumer (consumer:integrations) that
  applies each receipt as it is kept or retried, and a sweep every
  `:sweep-ms` that also purges the `ignored` receipts past
  `ignored-kept`. The module elects it, so one process per database
  purges. It first sends back to `proposed` each integration the boot's
  law widened (`rescope!`). opts: :dispatcher, :poll-ms, :sweep-ms."
  [eng {:keys [sweep-ms] :or {sweep-ms 30000} :as opts}]
  (try (rescope! eng)
       (catch Exception e
         (warn! "the rescope failed (" (ex-message e) ")")))
  (let [stop (clock/every! {:name "waymark10-integrations-sweep"
                            :period-ms sweep-ms
                            :f (fn []
                                 ;; a failed sweep still lets the purge run
                                 (try (sweep! eng)
                                      (catch Exception e
                                        (warn! "the sweep failed (" (ex-message e) ")")))
                                 (purge-ignored! eng))})]
    {:consumer (consumers/register-consumer!
                eng :integrations (fn [tr] (handle-transition! eng tr))
                (select-keys opts [:dispatcher :poll-ms]))
     :stop stop}))

(defn stop-applier! [{:keys [consumer stop]}]
  (when stop (stop))
  (some-> consumer consumers/stop-consumer!)
  nil)
