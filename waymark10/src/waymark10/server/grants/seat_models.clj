(ns waymark10.server.grants.seat-models
  "Which models and connector clients may sit a seat (R-5.7): the
  seat an ask spells by name, the connector token a seat's list may
  carry, and the `model-may-sit` wall. Split out of
  waymark10.server.grants so that namespace keeps room under its
  ns-size entry (ticket 829a08aa); grants re-exports the public names."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.types :as t]))

(defn- nonblank
  "One field as a non-blank string, however the wire spelled it."
  [x]
  (some-> x str str/trim not-empty))

(defn seat-named
  "The ACTIVE seat an ask spells by name, through the ctx :find hook —
  the one lookup the three seat-ask guards share. nil when the ask
  names no seat, when no active seat wears that spelling, or when the
  ctx carries no hooks (the storage-free render probe, which declines
  rather than guesses — the phase-8 discipline)."
  [inp ctx]
  (when-some [nm (nonblank (:seat inp))]
    (when-some [find' (:find ctx)]
      (first (find' :seat {:name nm :state "active"} {:limit 1})))))

(def connector-token-prefix
  "A seat's `held_for` / `substitute_for` entry that names a connector
  client rather than a model: `connector:waymark10-connector-claude`
  admits that client's delegates, `waymark10-connector-claude:<sub>`
  (R-5.7). A delegate declares no model, so the list says who it is."
  "connector:")

(defn connector-token?
  "True when one list entry names a connector client, not a model ref."
  [entry]
  (str/starts-with? (str entry) connector-token-prefix))

(defn connector-admitted?
  "True when `principal-id` is a delegate of a connector client that
  `held` — a seat's list for the ask's kind — names by its token."
  [principal-id held]
  (let [pid (str principal-id)]
    (boolean
     (some (fn [entry]
             (when (connector-token? entry)
               (let [client (subs (str entry) (count connector-token-prefix))]
                 (and (seq client) (str/starts-with? pid (str client ":"))))))
           held))))

(defn- model-name
  "One model ref as the identifier a harness declares, for a sentence
  a reader can act on; the ref itself when the row is gone, and a
  connector token as it is written."
  [ctx id]
  (or (when (connector-token? id) (str id))
      (when-some [read' (:read ctx)]
        (nonblank (get-in (read' :model (str id)) [:data :name])))
      (str id)))

(defn- model-list-of
  "The seat's list for the ask's kind: `substitute_for` for a
  substitute ask, `held_for` for a full one (R-5.7). Empty means any
  model may sit."
  [seat substitute?]
  (into [] (map str) (get-in seat [:data (if substitute?
                                           :substitute_for
                                           :held_for)])))

(g/defguard model-may-sit
  {:judges [:seat]
   :reads [:principal :seat :model]
   :vars [:seat :model :list :which]
   :open "The models a seat is held for are that seat's own row, one GET away — and the list is a handful of refs, not a vocabulary this form could recite."
   :explain "The seat {seat} is held for {which}: {list}. This session declares {model}, which is not one of them — so a grant minted here would scope to nothing at its first request. Ask to sit as a substitute if the seat takes one, or start the session on a model the seat is held for."}
  [_row inp ctx]
  (if-some [seat (seat-named inp ctx)]
    (let [substitute? (true? (:substitute inp))
          wanted (model-list-of seat substitute?)
          claim (nonblank (get-in ctx [:principal :model]))
          mine (when (and claim (:find ctx))
                 (some-> (first ((:find ctx) :model {:name claim} {:limit 1}))
                         :id str))]
      (if (or (empty? wanted)
              (and mine (some #{mine} wanted))
              (connector-admitted? (get-in ctx [:principal :id]) wanted))
        (t/allow)
        (t/deny {:vars {:seat (get-in seat [:data :name])
                        :model (or claim "no model at all")
                        :which (if substitute?
                                 "substitutes"
                                 "its full sitter")
                        ;; the list by the IDENTIFIER a harness declares,
                        ;; never by row id: the refusal has to be
                        ;; actionable at the door the caller can reach,
                        ;; and that door speaks model names
                        :list (str/join ", " (map #(model-name ctx %) wanted))}})))
    (t/allow)))
