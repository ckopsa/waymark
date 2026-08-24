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
    exists (waymark-44h.3 carries the punt)."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

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
   :schema [:map
            [:token [:string {:min 3 :max 80}]]
            [:description [:string {:min 1 :max 240}]]
            ;; who enforces — a pointer for humans, never a call site
            [:enforced_by {:optional true} [:maybe [:string {:max 120}]]]]
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
