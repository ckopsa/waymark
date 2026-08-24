(ns workqueue10.resources.permission-slip
  "The permission slip (waymark-442.6): somebody in the house asks for
  leave, a grown-up answers, and the answer is the row.

  WHY THIS AND NOT A CAPABILITY GRANT. spec-decision-kind records the
  distinction and this kind is the first instance chosen by it: a
  capability grant is a verdict a MACHINE enforces — a dotted token, a
  filter, an expiry, and a call site that consults
  /api/-/grant-check before it acts. A permission slip is a verdict
  the HOUSE reads. Nothing in this engine stops Iris from biking to
  the park; a parent reads the slip and remembers what they said. The
  epic's own example — screen time — turned out to want the grant, and
  is deliberately NOT this kind. \"May I bike to the park with Otto,
  back before dark\" has no enforcement point anywhere and never will:
  the record IS the whole mechanism.

  WHY NOT A STATE ON SOMETHING ELSE. Every other household verdict in
  this app rides a domain row that already existed for another reason
  — a meal is accepted onto the list, a substitution is accepted, a
  plan is finalized, a product's match is confirmed. There is no
  outing kind, no evening-out kind, no errand kind, and inventing one
  to hang a state on would be the smuggle spec-decision-kind names:
  the decision IS the thing here, so it gets a row of its own.

  WHAT IS DECLARED AND WHAT IS PROJECTED. Everything below :decision
  is the whole law: three states, two verdict actions, the walls on
  who may sign, the leash, the pacing, and the asker's sight of their
  own slips. The engine projects the states, the actions, the guards,
  the schema entries it owns, the create model that omits them, the
  queue's filter and sort, and the birth stamps — the same projection
  approval_request rides, whose fingerprint did not move when it
  started riding it.

  THE TWO WALLS ON SIGNING. Four-eyes by FIELD (\"not the asker\"),
  because the asker is stamped at birth and there is no earlier
  transition to be the actor of; and a role (\"a grown-up\"), which is
  the eligibility dimension a household actually means and which no
  guard could say before this bead. A sibling is neither the asker nor
  a parent, and meets the second wall.

  WHAT IS DELIBERATELY ABSENT. No escalation, no delegation, no \"two
  of three parents\" — :decider is a guard, and a guard is a wall, not
  a workflow. good_until is what the house READS (\"back before
  dark\"), not a sweeper: nothing expires a slip on a clock, because
  a slip that quietly withdrew itself would be worse than one that
  stayed on the fridge."
  (:require [waymark10.dsl :refer [defresource defscenario]]))

;; ── the walls, written down as scenarios ────────────────────────────
;; Both walls declare :reads [:principal] and neither needs a :given
;; row, so all three of these are CHECK-TIER: `make check-queue`
;; judges them with no database at all, in the same breath as the
;; usability warnings.

(defscenario nobody-signs-their-own-slip
  "The child who asked cannot be the grown-up who answers — the oldest
   rule in the house, and the one a hurried yes would break first."
  {:kind    :permission_slip
   :attempt :allow
   :row     {:state :offered
             :data {:for_what "bike to the park with Otto"
                    :asked_by "iris"}}
   :as      {:id "iris" :type :person :roles #{"parent"}}
   :expect  {:refused :the-asker-does-not-sign
             :because "cannot be the one to sign it"}})

(defscenario a-grown-up-signs-the-slip
  "A parent who did not ask signs it, and that is the whole
   mechanism: the slip now says yes, and says who said so."
  {:kind    :permission_slip
   :attempt :allow
   :row     {:state :offered
             :data {:for_what "bike to the park with Otto"
                    :asked_by "iris"}}
   :as      {:id "mom" :type :person :roles #{"parent"}}
   :expect  {:allowed true}})

(defscenario a-sibling-cannot-sign
  "Otto is not the asker, which gets him past the first wall, and not
   a grown-up, which does not get him past the second."
  {:kind    :permission_slip
   :attempt :refuse
   :row     {:state :offered
             :data {:for_what "bike to the park with Otto"
                    :asked_by "iris"}}
   :as      {:id "otto" :type :person}
   :expect  {:refused :a-grown-up-signs
             :because "A grown-up signs a permission slip"}})

(defresource permission-slip
  {:kind :permission_slip
   :plural "permission_slips"
   :label-template "{data.for_what}"
   :summary "{data.for_what} · asked by {data.asked_by} · {state}"
   :display {:title "Permission slip"}
   ;; the decision record (waymark-442.5), and the one kind in the
   ;; house with the clearest claim on it: "who was allowed to sign
   ;; this, and what did the wall see" is exactly the question a
   ;; permission slip exists to answer, and the walls' declared vars
   ;; are already the whole record. Declared HERE and not projected by
   ;; :decision, because retention is bytes on every transition of
   ;; this kind forever and that is the app's call, not the sugar's —
   ;; a household's slips are few; another house's verdicts might not
   ;; be. Not law either way: fingerprint-of never names :retain.
   :retain {:judgment true}
   :decision
   {;; the question, in the asker's own words
    :asks    :for_what
    ;; stamped from the principal: an ask that could name someone else
    ;; as its asker is an ask that can frame them
    :by      :asked_by
    :decider {:not  {:field :asked_by
                     :name :the-asker-does-not-sign
                     :explain "The person who asked cannot be the one to sign it; a grown-up who did not ask must."}
              :role {:name "parent"
                     :as :a-grown-up-signs
                     :explain "A grown-up signs a permission slip."}}
    :stamps  {:decided-by :signed_by}
    ;; "back before dark" — twelve hours by default, a week at the
    ;; outside. The cap is what stops an ask for leave that never ends
    ;; from being spelled as one afternoon's
    :expires {:field :good_until :default 43200 :max 604800
              :explain "A slip is for an occasion — at most {max_hours} hours; this one runs to {asked}. Ask for the afternoon you mean."}
    ;; asking is cheap and answering is not: the open cap is the wall
    ;; a household actually feels — answer what is already on the
    ;; fridge before adding to it
    :pacing  {:limit 12 :per :hour :open-cap 4}
    ;; the asker sees their own slips with no grant: an ask you cannot
    ;; read the answer to is not an ask. The verdict doors ride the
    ;; courtesy too, row-gated — a child who tries to sign their own
    ;; meets the wall's honest 409, never a mute 404
    :own-surface true
    :verdicts
    [{:name :allow :to :allowed
      :label "Yes" :style :primary :order 1
      :note :answer
      :safety {:idempotent true :reversible false :confirm false
               :one-way "A signed slip stays on the fridge; changing your mind is a new slip, and the house can see both."}}
     {:name :refuse :to :refused
      :label "Not this time" :order 2
      :note :answer
      :safety {:idempotent true :reversible false :confirm false
               :one-way "A refused slip stays on record with its reason; asking differently is a new slip."}}]}
   :filterable {:signed_by #{:eq}}
   :scenarios [nobody-signs-their-own-slip
               a-grown-up-signs-the-slip
               a-sibling-cannot-sign]})
