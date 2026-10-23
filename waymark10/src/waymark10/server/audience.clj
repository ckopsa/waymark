(ns waymark10.server.audience
  "Who a committed transition is for: the actor that made it, the
  domains its row answers to, and whether it is a service seat's
  alert. A subscription (`webhooks`), a seat's inbox (`routes.seats`)
  and a listener entry (`listen`) judge a transition through these one
  readings. They live apart from `webhooks` so that `listen` reads them
  without requiring it, and `webhooks` may require `listen`."
  (:require [clojure.string :as str]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]))

(defn actor-address
  "The address of a transition's actor as the log holds it (`seat:…`
  for a seat), or nil when it holds none."
  [t]
  (let [a (:actor t)]
    (some-> (if (map? a) (:id a) a) str not-empty)))

(defn- actor-addresses
  "The addresses a transition's actor answers to: its id as the log
  holds it, and `type:id` when the id names no kind of its own."
  [t]
  (let [a (:actor t)
        id (actor-address t)
        type (when (map? a) (some-> (:type a) name))]
    (cond-> #{}
      id (conj id)
      (and id type (not (str/includes? id ":"))) (conj (str type ":" id)))))

(defn- stored-row
  "The row of `kind` with this id as storage holds it, or nil when the
  engine serves no such kind or the row is gone."
  [eng kind id]
  (when (and id (get (inv/resources eng) kind))
    (let [st (:storage eng)]
      (store/with-tx st #(store/load-row st % kind (str id) {})))))

(defn- domains-of
  "The NAMES of the domains the transition's row answers to, or nil
  when its kind keeps no domain name. A kind keeps one when its
  declaration says what an absent `domain` counts as; a row that stores
  none, or that is gone, is in that one. A row that stores a
  `requested_by` answers to that domain as well: it asked for the work.

  `load`, when given, answers the row in place of the read from
  storage: a caller that holds the row reads it once (spec-events
  E-1.6)."
  ([eng t] (domains-of eng t nil))
  ([eng t load]
   (let [rdef (get (inv/resources eng) (keyword (:kind t)))]
     (when-some [absent (get (:absent-as rdef) :domain)]
       (let [raw (if load (load) (stored-row eng (:kind rdef) (:resource-id t)))
             asker (some-> (get-in raw [:data :requested_by]) str not-empty)]
         (cond-> #{(or (some-> (get-in raw [:data :domain]) str not-empty)
                       (if (keyword? absent) (name absent) (str absent)))}
           asker (conj asker)))))))

(defn by-actor?
  "Did an actor answering to one of `addresses` (a set) make this
  transition? A subscription's `skip_actors` and a seat's inbox judge
  an actor through this one reading."
  [addresses t]
  (boolean (some addresses (actor-addresses t))))

(defn in-domains?
  "Does the transition's row answer to one of `domains` (a set of
  names)? A kind that keeps no domain name passes. A subscription's
  `domains` and a seat's inbox judge a row through this one reading.
  `load`, when given, is a function of no arguments answering the row
  (nil when it is gone), called only when the kind keeps a domain."
  ([eng domains t] (in-domains? eng domains t nil))
  ([eng domains t load]
   (if-some [ds (domains-of eng t load)]
     (boolean (some domains ds))
     true)))

(defn seat-domain-name
  "The name of the domain this seat row is in (`seats/domain-name-of`),
  read from storage."
  [eng seat]
  (seats/domain-name-of #(stored-row eng %1 %2) seat))

(def ^:private service-alerts
  "The transitions `service_alerts` lets through, kind to actions: a
  seat's `mark_halted`, which writes a halt and a budget wall alike,
  and a change's `stall`."
  {"seat" #{"mark_halted"}
   "change" #{"stall"}})

(defn- alerting-seat
  "The seat row a service alert is about, or nil when the transition is
  not one: the seat itself for a seat's alert, and for any other kind's
  the seat that made the move, read from the actor's `seat:…` address."
  [eng t]
  (let [kind (name (:kind t))]
    (when (contains? (get service-alerts kind) (some-> (:action t) name))
      (stored-row eng :seat
                  (if (= "seat" kind)
                    (:resource-id t)
                    (some members/seat-id (actor-addresses t)))))))

(defn service-alert?
  "Is the transition an alert of a seat that `serves` any and is in one
  of `domains`?"
  [eng domains t]
  (boolean
   (when-some [seat (alerting-seat eng t)]
     (and (= "any" (some-> (get-in seat [:data :serves]) str))
          (contains? domains (seat-domain-name eng seat))))))
