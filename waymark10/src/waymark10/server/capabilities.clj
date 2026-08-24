(ns waymark10.server.capabilities
  "Capabilities: the vocabulary of grantable things waymark does NOT
  store (waymark-44h — the authorization-authority direction). A
  capability row registers one external power by token —
  telegram.send, gmail.search, telemetry.query — so the ask/grant
  machinery can NAME it: a scope entry whose kind token wears a dot
  is judged against this registry (scope-names-real-kinds) instead
  of the resource vocabulary, and everything downstream of the name
  reuses unchanged — four-eyes approval, the leash, attenuated
  delegation, revocation, the magic links.

  Enforcement is NOT here, deliberately: waymark holds the law about
  access, never the credential. The system fronting the data (Gate,
  a telemetry proxy) asks the introspection door (router.clj
  grant-check) whether the presented grant admits the capability,
  and forwards or refuses with its own hands. Recorded trades, each
  a sentence:
  - enforcement is cooperative — waymark cannot see whether the
    enforcement point honors the leash; own the enforcement point.
  - a capability entry's :filter is a CONSTRAINT the enforcement
    point interprets ({chat \"family\"}); waymark validates its shape
    and carries it, never its meaning.
  - scope :actions on a capability are uninterpreted v1 — the token
    is granted whole; per-verb vocabularies are the registry's to
    grow.
  - downstream usage audit is coarse until the usage-report ping
    exists (waymark-44h.3 carries the punt).

  THE ONE INVERSION (waymark-iqa.23). `feed.preview_as` is a
  capability whose enforcement point is THIS ENGINE: the power being
  granted is waymark's own feed route, so the first trade above —
  enforcement is cooperative, own the enforcement point — is not a
  trade here at all. We own it. The registry still NAMES the power
  (so the ask, the four-eyes approval, the leash, the expiry and the
  revoke door are the ordinary ones, unchanged), and the feed route
  reads the presented grant's surface entry for the token and refuses
  with its own hands. A capability whose :enforced_by names a file in
  this repository is the shape to expect of the others that follow;
  it is not a special case of the machinery, only of who holds the
  data."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── the one capability this engine enforces itself ──────────────────

(def feed-preview-as-token
  "The dotted token the feed route judges (waymark-iqa.23). Named
  here rather than spelled as a literal at the door, because the
  route, the boot seed that creates the row, and the obligation that
  proves the refusal must all say the same eleven characters."
  "feed.preview_as")

(def feed-preview-as
  "The registry ROW for that token, as data — a deployment's boot seed
  ensures it (workqueue10.main/ensure-capabilities!), a test mints it
  through POST /api/capabilities, and neither retypes the sentence a
  human weighs before approving an ask.

  :enforced_by names a file in this repository on purpose. Every other
  capability points at the system standing in front of somebody else's
  data; this one points at us, and that sentence is how a person
  reading the grant form learns that approving it moves real sight
  inside this house rather than authority outside it."
  ;; both sentences are at the schema's ceiling (240 and 120) on
  ;; purpose — this is the one capability whose approval moves sight
  ;; INSIDE the house, and a person weighing it deserves every
  ;; character the form allows
  {:token feed-preview-as-token
   :description
   (str "Read another member's feed exactly as they see it — their "
        "order, their letters and ticklers, their verbs. The grant's "
        "filter names whose feed ({member: <id>}); the document is "
        "stamped with it, and the previewer still acts as "
        "themselves.")
   :enforced_by
   (str "this engine's own feed route — waymark holds the data AND "
        "the law here, unlike every other capability in the registry")})

(g/defguard token-wears-a-dot
  {:judges [:token]
   :explain "A capability token wears a dot (system.power — telegram.send) — the dot is HOW a scope entry is known to name the registry instead of a resource kind."}
  [_row inp _ctx]
  (if (str/includes? (str (:token inp)) ".")
    (t/allow) (t/deny)))

(defresource capability
  {:kind :capability
   :plural "capabilities"
   :states [:active :retired]
   :initial :active
   :terminal #{}
   :nav :system
   :summary "{data.token} · {state}"
   :label-template "{data.token}"
   ;; the vocabulary posture (hospitality audit, agent walk #3): the
   ;; registry is WORDS, not anyone's data. An agent that cannot read
   ;; what powers exist cannot compose its ask — the same argument
   ;; :vocabulary-open? already won — so every named principal reads
   ;; every row here without a grant, and none of them is owned
   :own-surface {:all true}
   ;; no :create-schema, so the schema IS the create form, and every
   ;; entry owes the prose policy 2 asks of a door (waymark-ts2)
   :schema [:map
            [:token {:x-display
                     {:label "The power, as a token"
                      :help "system.power, with the dot — telegram.send, gmail.search. The dot is how a grant knows it is naming a power and not a kind."}}
             [:string {:min 3 :max 80}]]
            [:description {:x-display
                           {:label "What it lets somebody do"
                            :help "One sentence a person can weigh before granting it — what the power reaches, in plain words rather than the API's."}}
             [:string {:min 1 :max 240}]]
            ;; who enforces — a pointer for humans, never a call site
            [:enforced_by {:optional true
                           :x-display
                           {:label "Who actually enforces it"
                            :help "The system standing in front of the data — this engine holds the rule and never the credential, so this names where to look when the rule is not honoured."}}
             [:maybe [:string {:max 120}]]]]
   :filterable {:state #{:eq :in} :token #{:eq}}
   :sortable {:fields [:token] :default "token"}
   :create-guards [token-wears-a-dot]
   :actions
   {:retire {:from #{:active} :to :retired :undo :restore
             :safety {:idempotent true :reversible true :confirm true
                      :consequence "New asks and grants naming this token refuse; grants already standing keep their word until they expire or are revoked."}
             :display {:label "Retire" :style :danger :order 9}}
    :restore {:from #{:retired} :to :active :undo :retire
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Restore" :order 1}}}})
