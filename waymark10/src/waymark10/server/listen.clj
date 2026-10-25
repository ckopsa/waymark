(ns waymark10.server.listen
  "One predicate of a listener entry and a committed transition
  (docs/spec-events.md §2, E-1.1 to E-1.8).

  An ENTRY is a map and every field is optional (E-1.2): `kinds`,
  `actions`, `from`, `to`, `ids`, `domains`, `skip_actors`, `filter`
  and `service_alerts`. The stated fields must all hold; inside one
  list any member may. An empty list and an absent field are one
  meaning: the field does not narrow (E-1.3).

  `matches?` reads the moved row at most once, and only when `filter`
  or a domain the kind keeps in the row needs it (E-1.6). It writes
  nothing, and a count, a damper or an SSE class is not one of its
  fields: those belong to the receiver or the reader (E-1.7).

  Each old shape has one reader into an entry here (E-1.8), and the six
  listeners judge through it: wakes, the notifier, the notice rule, the
  subscription, the inbox and the cue."
  (:require [clojure.string :as str]
            [waymark10.server.audience :as audience]
            [waymark10.server.collections :as collections]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]))

(defn- text [x]
  (when (some? x) (if (keyword? x) (name x) (str x))))

(defn- stated
  "The field's list when it narrows, else nil (E-1.3). A lone value
  reads as a list of one."
  [entry field]
  (let [v (get entry field)]
    (cond (nil? v) nil
          (coll? v) (not-empty (mapv text v))
          :else [(text v)])))

(defn- filters
  "The entry's filter maps when they narrow, else nil. One map reads as
  a list of one, and an empty map narrows nothing."
  [f]
  (cond (map? f) (when (seq f) [f])
        (coll? f) (not-empty (filterv seq f))))

(defn- in? [want got]
  (or (nil? want) (boolean (some #(= (text got) %) want))))

(defn- stored-row
  "The moved row as storage holds it now, or nil when it is gone."
  [eng rdef t]
  (let [st (:storage eng)]
    (store/with-tx st #(store/load-row st % (:kind rdef) (str (:resource-id t)) {}))))

(defn matches?
  "Does the listener `entry` hear the committed transition `t` (E-1.1)?

  `filter` is judged by `collections/row-matches?`, which reads it as
  the kind's query does (its :absent-as, every suffix, an array
  field), on the moved row AFTER
  the commit (E-1.4): a row that moved into the filter matches, one
  that moved out does not, a row that is gone matches no filter, and
  the kind's default filters do not apply. A caller that holds the row
  passes it as `row` (nil for a row that is gone): that is how a pull
  reader judges the row as it stands now. Without `row` the row is
  read once, and only when `filter` or a row-held domain needs it.

  `domains` passes as `audience/in-domains?` reads it, or, with
  `service_alerts` true, as a service alert of a seat in those domains
  (`audience/service-alert?`). `skip_actors` drops a move
  `audience/by-actor?` names.

  A nil entry is a reader's entry that hears nothing, and matches no
  transition. The engine is read only when a stated field needs it, so
  a caller judging kind and action alone may pass nil for `eng`."
  ([eng entry t] (matches? eng entry t ::read))
  ([eng entry t row]
   (let [kind (text (:kind t))
         rdef* (delay (get (inv/resources eng) (keyword kind)))
         held (if (= ::read row)
                (delay (when-some [rdef @rdef*] (stored-row eng rdef t)))
                (delay row))
         fs (filters (:filter entry))
         domains (some-> (stated entry :domains) set)
         skip (some-> (stated entry :skip_actors) set)]
     (boolean
      (and (some? entry)
           (in? (stated entry :kinds) kind)
           (in? (stated entry :actions) (:action t))
           (in? (stated entry :from) (:from-state t))
           (in? (stated entry :to) (:to-state t))
           (in? (stated entry :ids) (:resource-id t))
           (or (nil? skip) (not (audience/by-actor? skip t)))
           (or (nil? domains)
               (audience/in-domains? eng domains t #(deref held))
               (and (true? (:service_alerts entry))
                    (audience/service-alert? eng domains t)))
           (or (nil? fs)
               (and @rdef* (some? @held)
                    (collections/row-matches? @rdef* @held fs))))))))

;; ── the write-time check (E-1.5) ────────────────────────────────────

(defn filter-problems
  "Does `kind` declare every field of `filter` (a map, or a list of
  maps) filterable? nil when it does; otherwise the query's own
  refusal, field to sentences, as `collections/parse-query` writes it
  for a list page — so a listener's write door can refuse a filter it
  could never judge, in the words a person already reads (E-1.5). The
  kind's default filters are not read. Its pure half is
  `collections/filter-problems`, which the seat's write doors call,
  because `seats` cannot require this namespace."
  [eng kind filter]
  (if-some [rdef (get (inv/resources eng) (keyword (text kind)))]
    (collections/filter-problems rdef (filters filter))
    {"kind" [(str (pr-str (text kind)) " is not a kind this engine serves")]}))

;; ── the readers of the old shapes (E-1.8) ───────────────────────────

(defn- entry
  "An entry with its blank fields left out: a nil, a blank text and an
  empty list say nothing (E-1.3)."
  [m]
  (into {}
        (remove (fn [[_ v]]
                  (or (nil? v)
                      (and (string? v) (str/blank? v))
                      (and (coll? v) (empty? v)))))
        m))

(defn- one [x]
  (let [s (text x)]
    (when-not (str/blank? s) [s])))

(defn from-wake-on
  "A seat's wake entry `{kind actions filter}`. A count wake's
  `at_least` and `at_most` are the receiver's, not the predicate's
  (E-1.7). An empty `actions` reads as every action, as E-1.3 says,
  on a transition wake and a count wake alike (E-5.2)."
  [{:keys [kind actions filter]}]
  (entry {:kinds (one kind) :actions (mapv text actions)
          :filter (filters filter)}))

(defn from-notifier-on
  "One item of a notifier's `on`: `{kind actions}`."
  [{:keys [kind actions]}]
  (entry {:kinds (one kind) :actions (mapv text actions)}))

(defn from-notice-rule
  "A notice rule's data, `{kind when {action from_state to_state}}`.
  A blank one is left out, so it matches anything, as it does today."
  [{kind :kind w :when}]
  (entry {:kinds (one kind)
          :actions (one (:action w))
          :from (one (:from_state w))
          :to (one (:to_state w))}))

(defn from-subscription
  "A subscription's data: its predicate fields carry the same names."
  [data]
  (entry (select-keys data [:kinds :actions :filter :skip_actors :domains
                            :service_alerts])))

(defn from-inbox-only
  "A seat's `inbox.only`, `{kind [actions]}`: one entry for each key.
  A transition the inbox hears is one any of them matches."
  [only]
  (mapv (fn [[k acts]] (entry {:kinds (one k) :actions (mapv text acts)}))
        only))

(defn from-cue
  "A seat's `inbox.cues` entry `{kind actions filter}`. Its note is the
  reader's, not the predicate's."
  [{:keys [kind actions filter]}]
  (entry {:kinds (one kind) :actions (mapv text actions)
          :filter (filters filter)}))
