(ns waymark10.server.grants
  "Grants: least-privilege agent links (waymark9 server/grants.py,
  scoped to phase 9a's deliverable). A grant is an ordinary resource
  — {audience, scope, expires_at} through offered → accepted →
  revoked/expired — and its enforcement is RENDERING AT THE SOURCE:
  a request that presents X-Waymark-Grant sees only the granted
  surface. Non-granted kinds 404, non-granted actions are absent from
  envelopes (never narrated as unavailable) and 404 when invoked —
  concealment, waymark9's default-deny-is-rendering discipline.

  The visibility is resolved ONCE per request at the router's
  identity boundary (judgment-style: what this request may see is
  fixed before any handler runs) and rides the request as a closure
  map {:kind? :row? :action? :ids-of}. A presented-but-dead grant
  (unknown id, wrong audience, unaccepted, revoked, expired) scopes
  to NOTHING — dead means scoped-to-nothing, never a fall-through to
  the full surface (waymark9's dead_grant law).

  Recorded deviations and named punts (each a sentence):
  - waymark9's negotiation machine (draft→requested⇄granted,
    request_access, approver-edited scope maps, review notes, the
    attenuation ceiling) is unported: the v10 scope is set at offer
    time and the audience accepts or a person revokes — phase 9a's
    states are offered/accepted/revoked/expired.
  - ApprovalRequest (approval-mode actions, the pending→approved→run
    machine) is a named punt with waymark9's approval flow.
  - Field modes (clear/hashed/hidden) and argument modes are unported:
    a granted kind's data renders whole — the v10 grant grades kinds,
    ids and actions only.
  - The token IS the grant id carried in X-Waymark-Grant (waymark9
    minted an opaque wmk_ bearer token); the requesting principal
    must be the grant's audience, so the header is a scope selector,
    not a credential.
  - The scoped agent's own-grant negotiation surface is unported: a
    scoped request sees its own grant only if the grant grants it.
  - Expiry is enforced live (an accepted grant past expires_at scopes
    to nothing); the :expire transition is bookkeeping anyone may run
    once the clock passes — no sweeper drives it (named punt).
  - Idempotency-replay responses are stored bytes rendered without a
    visibility (the phase-3 render-fn seam's recorded punt, extended):
    a scoped replay serves the first execution's unprojected envelope."
  (:require [waymark10.guards :as g]
            [waymark10.resource :refer [defresource]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── guards ──────────────────────────────────────────────────────────

(g/defguard audience-only
  {:reads [:principal]
   :explain "Only the grant's audience may accept it."}
  [row _inp ctx]
  (let [p (:principal ctx)]
    (if (or (= :system (:type p))
            (= (:id p) (get-in row [:data :audience])))
      (t/allow) (t/deny))))

(g/defguard no-self-dealing
  {:reads [:principal]
   :explain "A holder cannot judge its own access; someone else revokes it."}
  [row _inp ctx]
  (let [p (:principal ctx)]
    (if (and (not= :system (:type p))
             (= (:id p) (get-in row [:data :audience])))
      (t/deny) (t/allow))))

(def ^:private past-expiry
  (g/expr {:name :past-expiry
           :when '(and (is-set (data :expires_at))
                       (<= (data :expires_at) (now)))
           :explain "The grant has not reached its expiry."
           :becomes-available-at (fn [row] (get-in row [:data :expires_at]))}))

;; ── the resource ────────────────────────────────────────────────────

(defresource grant
  {:kind :grant
   :plural "grants"
   :states [:offered :accepted :revoked :expired]
   :initial :offered
   :terminal #{:revoked :expired}
   :nav :secondary
   :summary "Grant to {data.audience} · {state}"
   :schema [:map
            [:audience [:string {:min 1 :max 128}]]
            [:scope [:vector
                     [:map
                      [:kind [:string {:min 1 :max 64}]]
                      [:ids {:optional true}
                       [:maybe [:vector [:string {:min 1 :max 64}]]]]
                      [:actions [:vector [:string {:min 1 :max 64}]]]]]]
            [:expires_at {:optional true} [:maybe :waymark/instant]]]
   :filterable {:state #{:eq :in}
                :audience #{:eq}}
   :actions
   {:accept {:from #{:offered} :to :accepted
             :guards [audience-only]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Acceptance turns the offer live for its audience; the way back is revoke."}
             :display {:label "Accept" :style :primary :order 1}}
    :revoke {:from #{:offered :accepted} :to :revoked
             :guards [no-self-dealing]
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "The link goes dead immediately and for good; the audience keeps nothing."}
             :display {:label "Revoke" :style :danger :order 9}}
    :expire {:from #{:offered :accepted} :to :expired
             :guards [past-expiry]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Expiry is the clock's bookkeeping; fresh access is a new grant, never an un-expire."}
             :display {:label "Expire" :order 8}}}})

;; ── scope evaluation ────────────────────────────────────────────────

(defn- active?
  "Does this grant confer anything right now? Accepted and unexpired —
  enforcement reads the clock live, so a stale :expired transition
  never extends access."
  [row now]
  (and (= :accepted (:state row))
       (let [exp (get-in row [:data :expires_at])]
         (or (nil? exp) (neg? (compare now exp))))))

(defn- surface-of
  "The granted surface: kind → {:ids set|nil :actions #{string}}.
  Entries sharing a kind union their actions; an entry without ids is
  kind-level and absorbs any sibling's id narrowing."
  [row]
  (reduce
   (fn [m {:keys [kind ids actions]}]
     (update m kind
             (fn [e]
               {:ids (when-not (or (and e (nil? (:ids e)) )
                                   (nil? ids))
                       (into (or (:ids e) #{}) (map str) ids))
                :actions (into (get e :actions #{}) (map str) actions)})))
   {}
   (get-in row [:data :scope])))

(def dead
  "The scoped-to-nothing surface a dead or unknown grant confers."
  {})

(defn- load-grant [eng grant-id]
  (when-some [rdef (get (inv/resources eng) :grant)]
    (some->> (store/with-tx (:storage eng)
               (fn [tx] (store/load-row (:storage eng) tx :grant
                                        (str grant-id) {})))
             (inv/decode-row rdef))))

(defn visibility
  "The per-request visibility, resolved once: the X-Waymark-Grant
  header names a grant whose audience must be this principal; an
  active grant confers its surface, anything else confers `dead`.
  Returns closures the render/router consult — {:kind? :row? :action?
  :ids-of} — plus :grant-id for narration-free diagnostics."
  [eng grant-id principal]
  (let [row (load-grant eng grant-id)
        surface (if (and row
                         (active? row ((:now-fn eng)))
                         (= (get-in row [:data :audience]) (:id principal)))
                  (surface-of row)
                  dead)]
    {:grant-id (str grant-id)
     :surface surface
     :kind? (fn [kind] (contains? surface (name kind)))
     :row? (fn [kind id]
             (boolean
              (when-some [e (get surface (name kind))]
                (or (nil? (:ids e)) (contains? (:ids e) (str id))))))
     :action? (fn [kind action]
                (contains? (get-in surface [(name kind) :actions] #{})
                           (name action)))
     :ids-of (fn [kind] (some-> (get-in surface [(name kind) :ids])
                                sort vec))}))
