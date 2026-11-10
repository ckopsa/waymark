(ns waymark10.server.discover
  "One kind's entry in the well-known document's `resources` map (GET
  /api/.well-known/waymark, which waymark_discover answers with). Split
  out of waymark10.server.router, which may not grow
  (ci/ns-size-grandfather.edn), when the entry learned what the kind
  is for (ticket a1f19281)."
  (:require [waymark10.checks :as checks]
            [waymark10.declaration :as declaration]
            [waymark10.measures :as measures]
            [waymark10.resource :as res]
            [waymark10.server.invoke :as inv]))

(set! *warn-on-reflection* true)

(defn- scope-action-names
  "Every action-name string a scope entry for this kind may name in
  :actions — declared actions, generated field editors, and the
  create verb — exactly the vocabulary check-action! enforces.
  Exposed on well-known so building a grant/approval_request scope
  needs no source read: an invited agent sees the real strings, not
  a guess at hyphen-vs-underscore or at what a :while-open group
  generated. (Delegates to invoke's action-names — the one accessor
  the ctx :action-names hook and grant-scope validation also read.)"
  [rdef]
  (inv/action-names rdef))

(defn entry
  "The kind `r`'s entry. `served` is the set of kinds this reader may
  hear named: a purpose's neighbour outside it is left out."
  [r served]
  (cond-> {:href (str "/api/" (:plural r))
           :actions (vec (scope-action-names r))
           ;; the two other vocabularies a
           ;; composing kind is judged against
           ;; (waymark-8sg): the view tokens a
           ;; deep link may name, and the field
           ;; names a filter — a saved view's
           ;; :where included — may put on the
           ;; left of an =. They ride HERE
           ;; beside :actions for the reason
           ;; :actions does: a client composing
           ;; an ask should not need a read per
           ;; field to learn what this engine
           ;; would accept
           :views (mapv (comp name :name)
                        (:views r))
           :filters (checks/where-fields r)
           ;; nav tier on the wire at last —
           ;; the generic UI's client-side
           ;; ENGINE_KINDS set retires
           :nav (name (:nav r :primary))}
    (:domain r)
    (assoc :domain (name (:domain r)))
    ;; the entry an ask for this kind
    ;; is offered (docs/spec-teams.md §6)
    (:scope-default r)
    (assoc :scope_default (:scope-default r))
    ;; what the kind is FOR, and the neighbours it is not (ticket
    ;; a1f19281)
    (:purpose r)
    (assoc :purpose (declaration/purpose-wire r served))
    ;; the kind's named measures, by
    ;; the token a goal, a reading and
    ;; a slot name them with
    ;; (docs/spec-measures.md §2)
    (seq (:measures r))
    (assoc :measures (measures/tokens r))
    ;; a decision kind advertises
    ;; itself, and the field its
    ;; requester is stamped into
    (res/decision r)
    (assoc :decision
           {:by (some-> (res/decision r)
                        :by name)})
    ;; each extra create door, and
    ;; who holds it: engine, or
    ;; whoever the grant admits
    (seq (:creates r))
    (assoc :creates
           (mapv (fn [d]
                   {:action (name (:action d))
                    :href (str "/api/" (:plural r)
                               "/-/" (name (:action d)))
                    :by (if (contains? (:by d) :engine)
                          "engine"
                          "grant")})
                 (:creates r)))))
