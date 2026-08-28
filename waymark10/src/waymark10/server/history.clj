(ns waymark10.server.history
  "Time travel, tiers 1 and 2 (docs/spec-time-travel.md and its
  2026-08-23 amendments).

  `waymark10_transitions` has been a complete record of every write
  this engine ever made, and nothing could query it as history. Two
  surfaces close that, and the second is the one with a decision
  behind it.

  TIER 1 — THE LOG, READ. `GET /api/{plural}/{id}/-/history` serves a
  row's transitions as a first-class document rather than a debugging
  query, and `?as-of=` on a row or a collection folds the same index
  ((kind, resource_id, id), which already existed) to the state, the
  law revision, the actor and the summary as of an instant. The
  summary is not reconstructed: `invoke` renders it at write time and
  stores it on the transition, so the sentence a household read that
  day is the sentence this answers with.

  TIER 2 — THE LAW OF THE DAY. Every transition carries `basis` —
  WHICH guards judged it, resolved under the revision stamped on the
  row at the time, through `decision/basis` and the on-demand loader
  in `server/definitions`. It is derived, so it is retroactive: a
  transition logged in July gains its answer the day this ships, and
  no stored record could have offered that. Where the kind declares
  `:retain {:judgment true}` the transition also carries `judgment` —
  what those guards READ — and that half is a lookup, not a
  re-derivation.

  ── WHAT THIS SURFACE REFUSES TO PRETEND ──

  The spec's tier 2 asks for the actions that were *available* on July
  1st. This does not answer that, and the omission is the whole point
  of landing the decision record first: the log records what happened,
  not what the row looked like, and tier 3 (`:retain {:data true}`) is
  the spec's own recorded punt. Re-judging July's law against today's
  document would return a plausible-looking wrong answer, which is
  worse than no answer and much worse than a named gap. So each
  transition says which of the two it is answering with — `evidence`
  is `recorded`, `before_the_record`, or `not_retained` — and each
  basis says how the law reached it — `stored`, `resident`,
  `unrecoverable` or `engine`.

  `unrecoverable` is this namespace's own addition to
  `decision/basis`'s three. Basis answers `:resident` both when the
  resident code IS that law (the pre-law horizon, the current
  revision) and when the revision simply could not be found — one
  word for a true statement and a hopeful one. Here the loader knows
  which happened, so a stamped revision with no surviving definition
  row is named as what it is: the guards below are TODAY's.

  ── AND WHAT IT REFUSES TO SHIP ──

  A history read is a read of FIELD VALUES, so it passes the same
  grant projection a row read does — `decision/project` against the
  visibility's own `:field?` closure. That is spec-time-travel's one
  security clause, and the reason the decision record landed before
  this route rather than after it: a route that shipped an unprojected
  judgment object would be a disclosure channel with a URL.

  A transition's `inputs` column is deliberately NOT served. It is a
  raw input map with no field projection of its own, written whole
  when an action declares `:record true`, and projecting it would be a
  second visibility surface with its own bugs. The recorded evidence
  is the projected surface; the inputs stay where the audit query can
  reach them and the wire cannot."
  (:require [waymark10.server.decision :as decision]
            [waymark10.server.definitions :as definitions]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store])
  (:import (java.time Instant)
           (java.time.format DateTimeParseException)))

(set! *warn-on-reflection* true)

(def history-cap
  "Transitions served in one history page. The same posture the
  sweep's cap takes: scan to the cap and SAY the remainder is there,
  because truncation announced beats totality implied."
  200)

(def fold-cap
  "Transitions folded for one collection as-of. A collection as-of
  cannot be answered from an index of rows — it is a fold from the
  beginning of the kind's log — so this bounds the fold rather than
  the answer, and `complete` below reports honestly whether the bound
  was ever reached."
  2000)

;; ── the instant ─────────────────────────────────────────────────────

(defn as-of-instant
  "The `as-of=` parameter, parsed. An unparseable instant is a 422
  that names the spelling rather than a silent fall back to now — an
  as-of read that quietly answered live would be the one mistake this
  whole surface exists to prevent."
  [params]
  (when-some [s (get params "as-of")]
    (try
      (Instant/parse s)
      (catch DateTimeParseException _
        (throw (p/schema-invalid
                :query
                {"as-of" [(str "must be an ISO-8601 instant, e.g."
                               " 2026-07-01T00:00:00Z")]}))))))

(defn- at-or-before? [^Instant instant t]
  (or (nil? instant)
      (not (.isAfter ^Instant (:at t) instant))))

;; ── the law of the day ──────────────────────────────────────────────

(defn law-of
  "The rdef as revision R's law, plus how honestly it got there.

  → [rdef' source], one of three:

  `:stored`   — R's fingerprint was found and overlaid, so
                `judgment/resolve-action` rebuilds R's guard trees
                exactly as the invoker enforced with them.
  `:resident` — the resident code IS that law, and saying so is a
                true sentence rather than a shrug: the nil pre-law
                stamp (which judgment.clj has always served
                resident), the current revision (the boot's residency
                gate mints a new one the moment the code stops
                expressing it), a piloted revision (same gate), and
                an engine with no law slots at all, whose creates
                carry `invoke/create-law-revision`'s phase-2 stub.
  `:unrecoverable` — a stamped revision that is neither, with no
                surviving definition row. A hard DROP severed it, or
                this engine never ran the definitions lifecycle. The
                guards named are TODAY's, and the notes say so.

  The overlay is assoc'd onto a LOCAL copy of the rdef, sharing the
  live `:judgment-cache` atom by identity: rebuilt guard vectors key
  off [revision action], a revision's trees never change, and the
  cache is thrown away by the same `install!` that would have made it
  stale. Historical law costs one DB read, once, per revision."
  [eng rdef revision]
  (if (nil? revision)
    [rdef :resident]
    (if-some [fp (definitions/stored-fingerprint eng (:kind rdef) revision)]
      [(assoc-in rdef [:judgment-laws revision] fp) :stored]
      [rdef (if (and (contains? rdef :current-law)
                     (not= revision (:current-law rdef))
                     (not= revision (:revision (:piloted-law rdef))))
              :unrecoverable
              :resident)])))

;; ── one transition, read ────────────────────────────────────────────

(defn law-resolver
  "`law-of`, memoized for the span of ONE read.

  A page of two hundred transitions usually names two or three
  revisions, and `stored-fingerprint` deliberately does not cache a
  MISS — the definition row it is waiting for may be written a second
  from now. Without this memo a history page over an unrecoverable
  revision would run one query per row for an answer that cannot
  change while the page is being built. The memo lives and dies with
  the request, which is exactly the scope where a miss cannot go
  stale."
  [eng rdef]
  (let [seen (atom {})]
    (fn [revision]
      (or (get @seen revision)
          (let [v (law-of eng rdef revision)]
            (swap! seen assoc revision v)
            v)))))

(defn- basis-of
  "WHY this transition was allowed, under the law that allowed it.
  nil when the current declaration no longer knows the action — a
  renamed or retired verb has no guards to name, and inventing some
  from today's machine would be exactly the wrong answer."
  [law t]
  (let [rev (:law-revision t)
        [law-rdef source] (law rev)]
    (when-some [b (decision/basis law-rdef (:action t) rev)]
      (cond-> b
        (and (= :unrecoverable source) (not= :engine (:law b)))
        (assoc :law :unrecoverable)))))

(defn- evidence-tier
  "Which of the two answers a transition is giving, said out loud.

  `recorded` — the guards' own reading, stored at write time.
  `before_the_record` — the kind retains, but this transition predates
    the retention (or the column): the evidence was never written, and
    an empty object here would lie about coverage.
  `not_retained` — the kind declares no :retain {:judgment true}, so
    what the guards read is gone. The basis still answers WHICH."
  [rdef judgment]
  (cond (some? judgment)       :recorded
        (decision/retains? rdef) :before_the_record
        :else                  :not_retained))

(defn- entry
  "One transition on the wire: what moved, who moved it, under which
  law — then the two halves of why.

  The actor carries `grant` when the write was made while presenting a
  live one (waymark-sfe), and carries no such key otherwise, which is
  the honest thing to say about an unscoped hand. It is what makes a
  delegated verdict readable as one: *declined by `<agent>` under
  `grant-…`*."
  [rdef law t visible?]
  (let [j (decision/project (:judgment t) visible?)
        b (basis-of law t)]
    (cond-> {:at (:at t)
             :action (:action t)
             :from (:from-state t)
             :to (:to-state t)
             :actor (select-keys (:actor t) [:type :id :display :grant])
             :law_revision (:law-revision t)
             :evidence (evidence-tier rdef (:judgment t))}
      (:summary t) (assoc :summary (:summary t))
      (seq (:acknowledged t)) (assoc :acknowledged (vec (:acknowledged t)))
      (:correlation-id t) (assoc :correlation_id (:correlation-id t))
      b (assoc :basis b)
      j (assoc :judgment j))))

(defn visible-fn
  "The field visibility a history read projects through, spelled the
  way `decision/project` wants it — (fn [field-name-string] → bool),
  built from the very closure a row read narrows its data with. nil
  for an unscoped request, which is `project`'s no-op and the system
  door's own posture: it already sees the row whole."
  [rdef visibility]
  (when-some [field? (:field? visibility)]
    (fn [f] (boolean (field? (:kind rdef) f)))))

;; ── the notes ───────────────────────────────────────────────────────

(defn- notes
  "The honesty clauses, met before the evidence. Each one is a punt
  the spec recorded or a limit this engine actually has, said rather
  than assumed."
  [rdef entries scoped? truncated cap]
  (let [tiers (into #{} (map :evidence) entries)
        laws (into #{} (comp (keep :basis) (map :law)) entries)]
    (into []
          (remove nil?)
          [(str "Which guards judged is DERIVED from the action and the law"
                " revision, never stored — so every transition here has a"
                " basis, including those logged before this surface existed.")
           (when (contains? tiers :not_retained)
             (str (name (:kind rdef)) " declares no :retain {:judgment true},"
                  " so what those guards READ was never captured. The law is"
                  " named; the evidence is gone."))
           (when (contains? tiers :before_the_record)
             (str "Some transitions predate this kind's retention: their"
                  " evidence was never written, and `evidence` says so per"
                  " transition rather than answering with an empty object."))
           (when (contains? laws :unrecoverable)
             (str "A law revision stamped here has no surviving definition"
                  " row on this engine, so the guards named for its"
                  " transitions are the RESIDENT code's, not that"
                  " revision's."))
           (when scoped?
             (str "Read through your grant: an evidence value whose form read"
                  " a field you may not see is withheld and named in"
                  " `withheld`, never silently dropped."))
           (str "The log records what happened, not what the row looked like."
                " `data` as of a past instant is not recoverable here —"
                " docs/spec-time-travel.md tier 3 is a recorded punt — and a"
                " transition's stored `inputs` are not served, having no"
                " field projection of their own.")
           (when truncated
             (str "The newest " cap " transitions only (the page's cap);"
                  " older ones are unread. Ask for fewer with `limit` — never"
                  " for more, because a page that grew without bound is how a"
                  " log becomes a denial of service."))])))

;; ── tier 1: the row's history ───────────────────────────────────────

(defn- limit-of [params]
  (if-some [s (get params "limit")]
    (let [n (try (Long/parseLong s)
                 (catch NumberFormatException _
                   (throw (p/schema-invalid
                           :query {"limit" ["must be a positive integer"]}))))]
      (when-not (pos? n)
        (throw (p/schema-invalid :query {"limit" ["must be a positive integer"]})))
      (min n history-cap))
    history-cap))

(defn row-history
  "GET /api/{plural}/{id}/-/history — one row's transitions, newest
  first, each judged by the law that judged it.

  The caller's authorization is the ROW's: the route checks
  concealment before this runs, exactly as a row read does, so a row
  a grant hides has no history either. What survives that check is
  then projected field by field."
  [eng rdef id params visibility]
  (let [st (:storage eng)
        limit (limit-of params)
        visible? (visible-fn rdef visibility)
        where {:kind (:kind rdef) :resource-id (str id)}
        ;; one row past the page is the whole of the remainder
        ;; question: a COUNT over the log to say "of 4000" would be a
        ;; second query on every history read, and asking for limit+1
        ;; answers exactly the sentence the notes need — no more, and
        ;; never the off-by-one that calls a log of exactly `limit`
        ;; transitions truncated
        peek' (store/with-tx st
                (fn [tx]
                  (store/transitions st tx where {:limit (inc limit)
                                                  :newest-first true})))
        truncated (> (count peek') limit)
        rows (vec (take limit peek'))
        law (law-resolver eng rdef)
        entries (mapv #(entry rdef law % visible?) rows)
        self (str "/api/" (:plural rdef) "/" id "/-/history")]
    {:waymark "10"
     :kind "history"
     :self self
     :state "recorded"
     :summary (str "History · " (name (:kind rdef)) " " id " · "
                   (count entries) " transition"
                   (when (not= 1 (count entries)) "s"))
     :data {:of (str "/api/" (:plural rdef) "/" id)
            :kind (name (:kind rdef))
            :id (str id)
            :scanned (count entries)
            :truncated truncated
            :notes (notes rdef entries (some? visible?) truncated limit)
            :transitions entries}}))

;; ── tier 1: the row as of an instant ────────────────────────────────

(defn- fold-entry
  "The last transition at or before the instant — the one that put the
  row where it stood. nil when the row had not been created yet, which
  is an answer and not an absence."
  [rows instant]
  (last (filter #(at-or-before? instant %) rows)))

(defn row-as-of
  "GET /api/{plural}/{id}?as-of=INSTANT — the row's state, its law and
  its summary as they stood.

  NOT an envelope, and that is a recorded deviation from the spec's
  own wording ('answers the envelope with state, law_revision and
  summary as of that instant'). An envelope carries `data`, `actions`,
  `links` and an ETag, and every one of them is a statement about NOW:
  the data is not recoverable at all (tier 3), the actions would be
  today's doors probed against today's document, and a client whose
  first rule is 'follow the envelope's own href' would find live verbs
  hanging off a historical document. So the as-of read answers a
  document that can only be read as history, and the `X-As-Of` header
  the spec asked for rides it."
  [eng rdef id instant visibility]
  (let [st (:storage eng)
        visible? (visible-fn rdef visibility)
        rows (store/with-tx st
               (fn [tx]
                 (store/transitions st tx {:kind (:kind rdef)
                                           :resource-id (str id)}
                                    {:limit fold-cap})))
        t (fold-entry rows instant)
        e (when t (entry rdef (law-resolver eng rdef) t visible?))]
    {:waymark "10"
     :kind "as_of"
     :self (str "/api/" (:plural rdef) "/" id "?as-of=" instant)
     :state "recorded"
     :summary (if t
                (str (name (:kind rdef)) " " id " · " (name (:to-state t))
                     " as of " instant)
                (str (name (:kind rdef)) " " id
                     " did not exist as of " instant))
     :data (cond-> {:of (str "/api/" (:plural rdef) "/" id)
                    :kind (name (:kind rdef))
                    :id (str id)
                    :as_of instant
                    :existed (some? t)
                    :state (some-> t :to-state)
                    :law_revision (:law-revision t)
                    :summary (:summary t)
                    :notes (into (if t
                                   []
                                   [(str "No transition of this row is at or"
                                         " before that instant. Every write"
                                         " this engine makes is logged, so the"
                                         " row had not been created yet — or"
                                         " its log was severed from it by a"
                                         " hard DROP, the spec's other"
                                         " recorded punt.")])
                                 (notes rdef (if e [e] []) (some? visible?)
                                        false (count rows)))}
             e (assoc :put_there_by e))}))

;; ── tier 1: the collection as of an instant ─────────────────────────

(defn collection-as-of
  "GET /api/{plural}?as-of=INSTANT — the rows that existed then, in
  the states they held.

  A fold from the beginning of the kind's log, because there is no
  index of rows-as-they-were to read instead. `complete` is the fold's
  own honesty: false only when the bound was reached BEFORE the
  instant asked about, which is the one case where the answer could be
  missing a row. Reaching the bound after the instant costs nothing —
  the transitions past it could not have changed the answer.

  It takes no filters. The collection grammar queries stored `data`,
  and `data` as of a past instant is exactly what the log does not
  carry; a filter answered against today's rows would name a set
  nobody could describe."
  [eng rdef instant params visibility]
  (let [extra (sort (keys (dissoc params "as-of")))]
    (when (seq extra)
      (throw (p/schema-invalid
              :query
              (into {} (map (fn [k]
                              [k [(str "an as-of collection takes no filters:"
                                       " the log carries state, not data")]]))
                    extra))))
    (let [st (:storage eng)
          row? (:row? visibility)
          rows (store/with-tx st
                 (fn [tx]
                   (store/transitions st tx {:kind (:kind rdef)}
                                      {:limit fold-cap})))
          reached? (= (count rows) fold-cap)
          in-window (filterv #(at-or-before? instant %) rows)
          complete (or (not reached?) (< (count in-window) (count rows)))
          items (into []
                      (comp (map (fn [[rid ts]]
                                   (let [t (last ts)]
                                     {:id rid
                                      :self (str "/api/" (:plural rdef) "/" rid)
                                      :state (:to-state t)
                                      :law_revision (:law-revision t)
                                      :summary (:summary t)
                                      :at (:at t)
                                      :action (:action t)
                                      :actor (select-keys (:actor t)
                                                          [:type :id :display :grant])})))
                            (filter (fn [i] (or (nil? row?)
                                                (row? (:kind rdef) (:id i))))))
                      (sort-by key (group-by :resource-id in-window)))]
      {:waymark "10"
       :kind "as_of_collection"
       :self (str "/api/" (:plural rdef) "?as-of=" instant)
       :state "recorded"
       :summary (str (:plural rdef) " as of " instant " · "
                     (count items) " row"
                     (when (not= 1 (count items)) "s"))
       :data {:of (str "/api/" (:plural rdef))
              :kind (name (:kind rdef))
              :as_of instant
              :folded (count in-window)
              :complete complete
              :notes (into
                      [(str "Folded from the beginning of this kind's log:"
                            " the rows that existed then, in the states they"
                            " held. `data` is not recoverable at a past"
                            " instant — docs/spec-time-travel.md tier 3.")]
                      (remove nil?)
                      [(when-not complete
                         (str "Incomplete: the fold reached its bound of "
                              fold-cap " transitions before " instant
                              ", so a row that moved after that bound may be"
                              " missing or shown in an earlier state."))
                       (when row?
                         (str "Read through your grant: rows outside it are"
                              " absent here exactly as they are absent from"
                              " the live collection."))
                       (str "A retired row is still a row: waymark retires"
                            " rather than deletes, so it appears here in the"
                            " state it held.")])
              :items items}})))
