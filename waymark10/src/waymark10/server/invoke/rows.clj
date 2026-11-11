(ns waymark10.server.invoke.rows
  "The load boundary of invoke! (moved out of waymark10.server.invoke,
  ticket 2d02c8ab): the etag, the actor stamped on a transition, the
  input digest, the kind → rdef lookup, and the upcast-and-decode step
  every stored row passes through. It reads no store and writes none;
  waymark10.server.invoke keeps its public names through aliases."
  (:require [waymark10.schema :as schema]
            [waymark10.server.at-rest :as at-rest]
            [waymark10.server.problems :as p]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(defn etag [kind id version]
  (str "W/\"" (name kind) "-" id "-v" version "\""))

(defn actor-map
  "The actor stamped on a transition: whose hand, under whose leash,
  and — when the session declared one — which model was running.

  The grant rides here (waymark-sfe) because a write made while
  presenting a live grant should read \"declined by <agent> under
  grant-…\"; the model rides here for the same reason (spec-seat.md
  R-9.5): a seat's history must say which model moved the row. Both
  are facts ABOUT the actor, the column is jsonb, so neither needs a
  migration or a field of its own — and both are simply absent when
  there was nothing to say, which is the honest thing to write. The
  model is the session's CLAIM, unverified here as everywhere.

  `allowed_by` is the person whose Allow this write replays (held-
  calls/door-principal sets it from the held call's `decided_by`), so
  the hand stays the author's and the log still says whose yes it was;
  the correction count reads it (store/corrections-by-model).

  `requested_by` is the agent whose held grant create the person's
  Allow ran as the person (held-calls/door-principal, HC1): the hand
  is the person's, and the log still says which agent asked.

  `scheduled` is the scheduled action whose run this write is
  (scheduled/runner-of, docs/spec-scheduled-actions.md R-4.2): the hand
  is the scheduler's, and the log says the clock moved it.

  `acted_by` is the seat that acted as this cast member on a clone
  (router/cast-principal, clone guard 2/3): the hand is the cast
  member's, and the log says whose seat moved it.

  `via` is the integration an outside member's system spoke through
  (integrations/party-of, docs/spec-teams.md §7), with `via_display`
  its name: the hand is the member's, and the log says whose system
  moved it.

  `subscription` is the subscription whose door receiver walked this
  door (door-receivers, docs/spec-change.md R-C.11): the hand is its
  owner's, and the log says which subscription moved it.

  `why` is the hand's own one sentence about this move, given on the
  invoke (router/move-why, ticket 1d8f5f1c): the row's history shows it
  beside the move."
  [principal grant-id]
  (cond-> {:type (name (:type principal))
           :id (:id principal)
           :display (:display principal)}
    grant-id (assoc :grant grant-id)
    (:model principal) (assoc :model (:model principal))
    (:allowed-by principal) (assoc :allowed_by (str (:allowed-by principal)))
    (:requested-by principal) (assoc :requested_by (str (:requested-by principal)))
    (:scheduled principal) (assoc :scheduled (str (:scheduled principal)))
    (:acted-by principal) (assoc :acted_by (str (:acted-by principal)))
    (:via principal) (assoc :via (str (:via principal))
                            :via_display (str (:via-display principal)))
    (:subscription principal) (assoc :subscription (str (:subscription principal)))
    (:why principal) (assoc :why (str (:why principal)))))

(defn body-digest [body]
  ;; exact decimals in a wire body digest as their {"dec" …} nodes
  ;; (batch H — the first :decimal input field met the digest);
  ;; everything else is the same canonical bytes as ever. Keyed once a
  ;; data key is set (ticket dbeb2f25): an unsalted hash of a body that
  ;; carries a sealed credential would let the log confirm a guess
  (at-rest/keyed-digest (wire/digest (wire/dec-nodes (or body {})))))

(defn resources
  "The kind → rdef map this engine serves: the phase-5 registry atom's
  current snapshot when the definitions boot installed one, else the
  static phase-2 map — old engines keep working with law slots absent."
  [engine]
  (if-some [reg (:registry engine)]
    (:kinds @reg)
    (:resources engine)))

(defn rdef-of [engine kind]
  (or (get (resources engine) kind)
      (throw (p/not-found kind "?"))))

(defn action-names
  "Every action-name string a request (or a grant scope entry) may
  name on this kind — declared actions (:flow rows already desugared
  into :actions at declaration time), generated field editors, and
  the create verb: exactly the vocabulary check-action! enforces and
  well-known publishes. A mirror kind adds the manual sync trigger's
  flavors (resync, discover) — kind-level doors like the create verb,
  never row actions — so an ask can name the trigger and a leash be
  granted it (the door consults :action? on exactly these strings)."
  [rdef]
  (into (sorted-set)
        (map name)
        (concat (keys (:actions rdef)) (:create-action-names rdef)
                (map :action (:creates rdef))
                (when (:mirror rdef) [:resync :discover]))))

(defn upcast-row
  "The load boundary's shape step (phase 8): a stored row older than
  the declaration folds forward through :upcasts — one fn per shape,
  raw JSON document in and out — and carries the declared shape, so
  the next write stamps it. Idempotence over already-upcast documents
  is the upcast author's obligation (a maintenance write persists
  upcast data without the stamp)."
  [rdef row]
  (let [stored (:shape row 1)
        declared (:shape rdef 1)]
    (if (< stored declared)
      (-> row
          (update :data (fn [d]
                          (reduce (fn [d s] ((get (:upcasts rdef) s) d))
                                  d
                                  (range stored declared))))
          (assoc :shape declared))
      row)))

(defn decode-row
  "The load boundary owns coercion: stored JSON upcasts to the
  declared shape, then becomes schema types (ISO strings → LocalDate)
  so laws compare real values."
  [rdef row]
  (update (upcast-row rdef row) :data #(schema/decode (:schema rdef) %)))
