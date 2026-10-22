(ns waymark10.server.scenes
  "The scene and the take (docs/spec-scenes.md): a demo written as data,
  and the record of one performance of it.

  A SCENE IS WRITTEN LOOSE AND JUDGED AT ONE DOOR. `given` and `shots`
  are lists of maps the schema does not look into, so a draft may be
  saved half written. `check` reads every call and every shot against
  the kinds this engine serves and the surfaces its page names
  (`ui-routes/surfaces`), and refuses with the first one that cannot be
  performed: `shot 3: ...`. It runs nothing.

  THE WALK CARRIES THREE THINGS from one shot to the next: the names
  bound so far and the kind each holds, the kind of the row last
  opened, and the door last tapped. A `door:<action>` with no `@row` is
  judged against the row last opened, and `type` against the door last
  tapped. Where a kind is not known, the kind's own law is not judged.

  A SAY MAY HOLD HOLES. A `say` is a string, or a list of strings and
  `{\"$rows\": {kind, since, until, field, terminal, state, count, sum,
  one, many, none}}` and
  `{\"$moves\": {kind, since, until, by, none}}` holes, and
  `{\"$day\": {since, until, domain, none}}`, whose window may instead be
  `window: \"shot\"`: since the take's last `$day` was filled (`take-run`),
  or `window: \"clock\"` with a `day` k: between the clone's (k-1)th and
  kth `clock_shift` (`clock-window`).
  A `$rows` hole's window may instead be `window: \"take\"`: since the
  take began, or `window: \"clock\"` with `days` [from, to]: the clone's
  clock days from to to, the whole played week unless named
  (`clock-span`).
  A `{\"$list\": {items, lead, none}}` joins what its `$rows` holes say.
  `check` judges each hole;
  the performer fills it when the shot plays (`fill-say`), from the rows
  the scene's clone holds in that window (`rows-in-window`), the
  transitions its log holds there (`moves-in-window`), or one sentence
  of an org's day (`day-in-window`), and a hole that finds none says so
  plainly.

  A CARD IS A FULL-SCREEN TEXT. A shot `{card, hold: {ms}}` shows its
  text alone on black for the hold. The text is plain, at most 280
  characters, and may hold holes as a `say` does; `check` asks a hold
  of at least 1500 ms and 60 ms a word (`card-ms`).

  A TAKE IS WRITTEN ONCE, by the runner, and never moves. The engine
  stamps `ok` and `first_failing_shot` from the shots it was given."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.film-rules :as film-rules]
            [waymark10.server.invoke :as inv]
            [waymark10.server.routes.ui :as ui-routes]
            [waymark10.server.store :as store]
            [waymark10.summary :as summary]
            [waymark10.types :as t])
  (:import (java.time Instant LocalDate OffsetDateTime ZoneOffset)))

(set! *warn-on-reflection* true)

(def kind :scene)

(def take-kind :take)

(def verbs
  "The one verb a shot has, in the order the spec lists them."
  ["open" "tap" "press" "focus" "type" "hold" "wait_for" "meanwhile" "call" "day" "event"])

(def ops
  "What an expectation may compare with."
  ["=" "!=" "contains" ">=" "<=" "exists" "absent"])

;; ── reading a scene ─────────────────────────────────────────────────

(defn- plain
  "The scene with every map key a string, whatever hand wrote it."
  [x]
  (walk/postwalk
   (fn [v]
     (if (map? v)
       (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v])) v)
       v))
   x))

(defn- tick [x] (str "`" x "`"))

(defn- fail [& parts] {:problem (apply str parts)})

(def ^:private ref-pattern #"\$([A-Za-z_][A-Za-z0-9_]*)")

(defn- unbound
  "Why a value cannot stand: the first `$name` in it no call or shot
  before it bound; nil when every one is bound."
  [x bound]
  (when-some [nm (->> (tree-seq coll? seq x)
                      (filter string?)
                      (mapcat #(map second (re-seq ref-pattern %)))
                      (remove #(contains? bound %))
                      first)]
    (str "`$" nm "` is never bound.")))

(defn- row-rdef
  "The definition of the kind a row address names: `$ref` reads its
  binding, and /api/<plural>[/<id>] its plural. nil when it is not
  known."
  [s bound rdef-of]
  (let [s (str/trim (str s))]
    (if-some [[_ nm] (re-matches ref-pattern s)]
      (let [r (get bound nm)] (when (map? r) r))
      (when-some [[_ plural] (re-matches #"/api/([^/?#]+)(?:/[^/?#]+)?" s)]
        (rdef-of plural)))))

(defn- names-of
  "The argument names a schema form takes; none for no form."
  [form]
  (into #{} (map name) (some-> form schema/entry-map keys)))

(defn- takes [rdef action]
  (names-of (get-in rdef [:actions (keyword action) :input])))

;; ── surfaces ────────────────────────────────────────────────────────

(def ^:private surface-exact
  (into #{} (comp (map :name) (remove #(str/includes? % "<"))) ui-routes/surfaces))

(def ^:private surface-stems
  "The registry's names that end in <…>, as the part before it."
  (into [] (keep #(second (re-matches #"(.*)<[^>]+>" (:name %)))) ui-routes/surfaces))

(defn- surface-of
  "A surface name as the registry holds it: {:name} for a whole name,
  {:stem :rest} for a completed stem, nil for neither."
  [nm]
  (if (contains? surface-exact nm)
    {:name nm}
    (some (fn [stem]
            (when (and (str/starts-with? nm stem) (< (count stem) (count nm)))
              {:stem stem :rest (subs nm (count stem))}))
          surface-stems)))

(defn- target
  "A shot's target, `surface` or `surface@row`: {:surface :rdef}, or
  {:problem}. The row is the one named, or the row last opened."
  [s {:keys [bound shown]} rdef-of]
  (let [[nm row] (str/split (str/trim (str s)) #"@" 2)
        sf (surface-of nm)
        rdef (if row (row-rdef row bound rdef-of) shown)
        tail (:rest sf)]
    (cond
      (nil? sf)
      (fail (tick nm) " is not a surface in the registry (GET /api/-/ui/surfaces).")

      (and row (unbound row bound))
      (fail (unbound row bound))

      (and row (str/starts-with? row "/") (nil? rdef))
      (fail (tick row) " names no kind this engine serves.")

      (and (= "nav." (:stem sf)) (nil? (rdef-of tail)))
      (fail (tick tail) " is not a kind this engine serves.")

      (and (contains? #{"door:" "door-shut:"} (:stem sf))
           rdef
           (nil? (get-in rdef [:actions (keyword tail)])))
      (fail (tick tail) " is not a door of " (name (:kind rdef)) ".")

      :else {:surface sf :rdef rdef})))

;; ── calls, expectations and shots ───────────────────────────────────

(defn- call-step
  "One `given` or `meanwhile` call, {as, tool, arguments, bind?}: the
  walk after it, or {:problem}. A waymark_invoke or waymark_pursue call
  is judged as the door it names; one with no `id` whose action the
  kind does not declare is the kind's create."
  [c st people rdef-of]
  (let [args (get c "arguments")
        k (some-> (get args "kind") str)
        rdef (when k (rdef-of k))
        action (some-> (get args "action") str)
        door (when (and rdef action) (get-in rdef [:actions (keyword action)]))
        acts? (contains? #{"waymark_invoke" "waymark_pursue"} (get c "tool"))
        create? (and acts? rdef (nil? door) (nil? (get args "id")))
        names (if create?
                (names-of (or (:create-schema rdef) (:schema rdef)))
                (names-of (:input door)))
        input (get args "input")
        stray (when (and acts? rdef (map? input))
                (first (remove names (keys input))))]
    (cond
      (not (map? c))
      (fail "a call is a map of `as`, `tool` and `arguments`.")

      (not (some #{(str (get c "as"))} people))
      (fail "`as` names " (tick (get c "as")) ", which is not in the cast.")

      (str/blank? (str (get c "tool")))
      (fail "a call names its `tool`.")

      (and k (nil? rdef))
      (fail (tick k) " is not a kind this engine serves.")

      (and acts? (nil? rdef))
      (fail "a " (get c "tool") " call names its `kind`.")

      (and acts? (nil? door) (not create?))
      (fail (tick action) " is not a door of " k ".")

      (some? stray)
      (fail (tick stray) " is not a field " (tick action) " takes.")

      (unbound args (:bound st))
      (fail (unbound args (:bound st)))

      :else
      (cond-> (assoc st :found rdef)
        (some? (get c "bind"))
        (assoc-in [:bound (str (get c "bind"))] (or rdef true))))))

(defn- call-shot-step
  "One `call` shot, {as, kind, action, row?, name?, input?}: the walk
  after it, or {:problem}. It is a week.edn call (docs/orgkit.md § The
  week) the performer makes as the shot plays (ticket 1b89ca49). `row`
  is the `name` an earlier call shot gave the row it made, and with no
  `row` an action the kind does not declare is the kind's create."
  [v st people rdef-of]
  (let [k (some-> (get v "kind") str)
        rdef (when k (rdef-of k))
        action (some-> (get v "action") str)
        row (some-> (get v "row") str)
        door (when (and rdef action) (get-in rdef [:actions (keyword action)]))
        create? (and rdef (nil? door) (nil? row))
        names (if create?
                (names-of (or (:create-schema rdef) (:schema rdef)))
                (names-of (:input door)))
        input (get v "input")
        stray (when (map? input) (first (remove names (keys input))))]
    (cond
      (not (some #{(str (get v "as"))} people))
      (fail "`as` names " (tick (get v "as")) ", which is not in the cast.")

      (nil? k)
      (fail "a `call` names its `kind`.")

      (nil? rdef)
      (fail (tick k) " is not a kind this engine serves.")

      (str/blank? action)
      (fail "a `call` names its `action`.")

      (and (nil? door) (not create?))
      (fail (tick action) " is not a door of " k ".")

      (and row (not (contains? (:named st) row)))
      (fail "`row` names " (tick row) ", which no earlier call shot made.")

      (and (some? input) (not (map? input)))
      (fail "a `call`'s `input` is a map.")

      (some? stray)
      (fail (tick stray) " is not a field " (tick action) " takes.")

      :else
      (cond-> (assoc st :found rdef)
        (some? (get v "name"))
        (update :named (fnil conj #{}) (str (get v "name")))))))

(defn- check-problem
  "Why one expectation cannot be read; nil when it can."
  [where c st rdef-of]
  (cond
    (not (map? c))
    "an expectation is a map of `path`, `op` and `value`."

    (not (some #{(get c "op")} ops))
    (str (tick (get c "op")) " is not an op; the ops are " (str/join " " ops) ".")

    (str/blank? (str (get c "path")))
    "an expectation names its `path`."

    (and (= "envelope" where) (str/blank? (str (get c "self"))))
    "an envelope expectation names its `self`."

    (= "screen" where)
    (or (:problem (target (get c "surface") st rdef-of))
        (unbound (get c "value") (:bound st)))

    :else (unbound [(get c "self") (get c "value")] (:bound st))))

(defn- expect-problem
  "Why an `expect` cannot be read; nil when it can."
  [e st rdef-of]
  (cond
    (not (map? e))
    "`expect` is a map of `envelope` and `screen`."

    (seq (remove #{"envelope" "screen"} (keys e)))
    (str "`expect` holds `envelope` and `screen`, and not "
         (tick (first (remove #{"envelope" "screen"} (keys e)))) ".")

    :else
    (some (fn [where]
            (some #(check-problem where % st rdef-of) (get e where)))
          ["envelope" "screen"])))

(defn- named? [x] (and (string? x) (not (str/blank? x))))

(defn- seat-open-refusal
  "Why a `seat` or `sitting` open cannot be resolved: `{seat <name>}`
  alone, or `{sitting {of <name>, which latest}}` alone. nil when it
  can; whether the seat exists is known only when the shot plays."
  [o]
  (let [h (get o "sitting")
        stray (when (map? h) (remove #{"of" "which"} (keys h)))
        which (get h "which")]
    (cond
      (< 1 (count o))
      "a `seat` or `sitting` open names nothing beside it."

      (contains? o "seat")
      (when-not (named? (get o "seat"))
        "a `seat` open names the seat by its name, a string.")

      (not (map? h))
      "a `sitting` open is a map of `of` and `which`."

      (seq stray)
      (str "a `sitting` open takes of and which, not " (tick (first stray)) ".")

      (not (named? (get h "of")))
      "a `sitting` open names its seat in `of`, by name."

      (not= "latest" (if (keyword? which) (name which) which))
      (str "a `sitting` open's `which` is \"latest\", and this one is " (tick which) "."))))

(declare span-problem)

(defn open-refusal
  "Why a shot's `open` cannot be resolved, as `check` says it of a
  `$day-row` open: one key, a map of `window` (the clock's), `day` and
  `domain`, judged as a `$day` hole of the clock's window is, or of
  `since`, `until` and `domain`, judged as an explicit `$day` is, and beside
  it only `as_of` false, for the row as it stands now; and of a `seat`
  or `sitting` open (`seat-open-refusal`). nil for any other open, which
  POST /api/-/scenes/open passes through as given."
  [open]
  (let [o (plain open)]
    (if (and (map? o) (or (contains? o "seat") (contains? o "sitting")))
      (seat-open-refusal o)
    (when (and (map? o) (contains? o "$day-row"))
      (let [h (get o "$day-row")
            stray (when (map? h) (remove #{"window" "day" "domain"} (keys h)))]
        (cond
          (seq (remove #{"$day-row" "as_of"} (keys o)))
          "a `$day-row` open names nothing beside it but `as_of`."

          (and (contains? o "as_of") (not (false? (get o "as_of"))))
          "a `$day-row` open's `as_of` is false, for the row as it stands now, or left out."

          (not (map? h))
          "a `$day-row` open is a map of `window`, `day` and `domain`, or of `since`, `until` and `domain`."

          (or (contains? h "since") (contains? h "until"))
          (let [stray (remove #{"since" "until" "domain"} (keys h))]
            (cond
              (seq stray)
              (str "a `$day-row` open of `since` and `until` takes domain beside them, not "
                   (tick (first stray)) ".")

              (str/blank? (some-> (get h "domain") str))
              "a `$day-row` open names a `domain`."

              :else (span-problem "$day-row" h)))

          (seq stray)
          (str "a `$day-row` open takes window, day and domain, not " (tick (first stray)) ".")

          (str/blank? (some-> (get h "domain") str))
          "a `$day-row` open names a `domain`."

          (not= "clock" (get h "window"))
          (str "a `$day-row` open's `window` is \"clock\", and this one is "
               (tick (get h "window")) ".")

          (not (and (integer? (get h "day")) (<= 1 (get h "day") 31)))
          (str "a `$day-row` open names its `day`, a whole number from 1 to 31, and this one is "
               (tick (get h "day")) ".")))))))

(def ^:private press-targets
  "What a `press` `target` names: the held band's buttons, as surfaces."
  {"allow" "held.allow" "deny" "held.deny"})

(defn- focus-problem
  "Why a `focus` `target` cannot be framed: `held`, the held band
  (data-focus=\"held\"), or `field:<name>`, the field row carrying
  data-field=\"<name>\". nil when it can."
  [tg]
  (when-not (or (and (= "held" tg) (surface-of "held"))
                (and (string? tg) (str/starts-with? tg "field:")
                     (not (str/blank? (subs tg 6)))))
    (str (tick tg) " is not a focus target; the targets are held and field:<name>.")))

(defn- verb-step
  "One shot's verb: the walk after it, or {:problem}."
  [verb v st people rdef-of]
  (let [bound (:bound st)]
    (case verb
      "open"
      (if (some #(contains? v %) ["$day-row" "seat" "sitting"])
        ;; the row is the clock day's or the seat's, known only when the shot plays
        (if-some [p (open-refusal v)] (fail p) (assoc st :shown nil :door nil))
        (let [self (get v "self")
              coll (get v "collection")
              rdef (if self (row-rdef self bound rdef-of) (some-> coll str rdef-of))]
          (cond
            (not (or self coll))
            (fail "`open` names `self` or `collection`.")

            (and self (unbound self bound))
            (fail (unbound self bound))

            (and (nil? rdef) (or coll (str/starts-with? (str self) "/")))
            (fail (tick (or coll self)) " names no kind this engine serves.")

            :else (assoc st :shown rdef :door nil :found rdef))))

      "focus"
      (if-some [p (focus-problem (get v "target"))] (fail p) st)

      ("tap" "press")
      (if (and (= "press" verb) (contains? v "target"))
        (if (some-> (get press-targets (get v "target")) surface-of)
          st
          (fail (tick (get v "target")) " is not a press target; the targets are allow and deny."))
      (let [tg (target (get v "surface") st rdef-of)]
        (cond
          (:problem tg) tg

          (= "door:" (get-in tg [:surface :stem]))
          (assoc st :door {:rdef (:rdef tg) :action (get-in tg [:surface :rest])})

          ;; the tracker opens whatever door heads the plan
          (= "tracker.go" (get-in tg [:surface :name]))
          (assoc st :door nil)

          :else st)))

      "type"
      (let [tg (target (get v "field") st rdef-of)
            arg (get-in tg [:surface :rest])
            {:keys [rdef action]} (:door st)]
        (cond
          (:problem tg) tg

          (not= "dialog.field:" (get-in tg [:surface :stem]))
          (fail "`type` names its field as `dialog.field:<name>`.")

          (and rdef (not (contains? (takes rdef action)
                                    (first (str/split arg #"\.")))))
          (fail (tick arg) " is not a field " (tick action) " takes.")

          (unbound (get v "value") bound)
          (fail (unbound (get v "value") bound))

          :else st))

      "hold"
      (cond
        (not (pos-int? (get v "ms")))
        (fail "`hold` names `ms`, a whole number above zero.")

        (some? (get v "on"))
        (let [tg (target (get v "on") st rdef-of)] (if (:problem tg) tg st))

        :else st)

      "wait_for"
      (if (pos-int? (get v "within_ms"))
        (if-some [p (expect-problem (get v "expect") st rdef-of)] (fail p) st)
        (fail "`wait_for` names `within_ms`, a whole number above zero."))

      "meanwhile"
      (call-step v st people rdef-of)

      "call"
      (call-shot-step v st people rdef-of)

      ;; an outside event the clone hears (ticket 524685a0): the performer
      ;; signs `body` as the integration `source` signs and posts it, and
      ;; the shot's `bind` names the receipt it keeps
      "event"
      (let [src (get v "source")]
        (cond
          (not (and (or (string? src) (keyword? src)) (not (str/blank? (name src)))))
          (fail "`event` names `source`, the name of the integration that sends it.")

          (not (map? (get v "body")))
          (fail "`event` names `body`, the event's body as a map.")

          (unbound (get v "body") bound)
          (fail (unbound (get v "body") bound))

          :else (assoc st :found (rdef-of "integration_event"))))

      ;; where a day of the clone's clock starts (ticket 0f6632ad): it
      ;; renders nothing, and its number and date increase through the scene
      "day"
      (let [n (get v "n")
            date (try (LocalDate/parse (str (get v "date"))) (catch Exception _ nil))
            prev (:day-mark st)]
        (cond
          (not (pos-int? n))
          (fail "`day` names `n`, a whole number above zero.")

          (nil? date)
          (fail "`day` names `date`, an ISO date (YYYY-MM-DD).")

          (and prev (or (<= (long n) (long (:n prev)))
                        (not (.isAfter ^LocalDate date ^LocalDate (:date prev)))))
          (fail "day " (tick n) " on " (tick date) " does not come after day "
                (tick (:n prev)) " on " (tick (:date prev))
                "; the days and their dates increase through the scene.")

          :else (assoc st :day-mark {:n n :date date}))))))

;; ── holes in a say ──────────────────────────────────────────────────

(def hole-fields
  "The timestamps a `$rows` hole may read its window on."
  ["created_at" "updated_at"])

(def nothing
  "What a hole that finds no rows says, unless it names its own `none`."
  "nothing came in")

(def move-actors
  "Whose transitions a `$moves` hole may read: the org's seats (the
  default), its people, or anyone."
  ["seats" "people" "anyone"])

(def no-moves
  "What a `$moves` hole that finds no transitions says, unless it names
  its own `none`."
  "nothing moved")

(def quiet-day
  "What a `$day` hole says of a day with nothing in it, unless it names
  its own `none`."
  "A quiet day.")

(def ^:private say-shape
  "`say` is a string, or a list of strings, `$rows`, `$moves` or `$day` holes and `$list`s.")

(defn- instant-of
  "An RFC 3339 instant, or a YYYY-MM-DD date read as its first moment in
  UTC; nil when it is neither."
  ^Instant [s]
  (let [s (str s)]
    (or (try (Instant/parse s) (catch Exception _ nil))
        (try (.toInstant (.atStartOfDay (LocalDate/parse s) ZoneOffset/UTC))
             (catch Exception _ nil)))))

(defn- hole-of
  "The `$rows` map a say part holds; nil when the part is no hole."
  [part]
  (when (map? part) (get part "$rows")))

(defn- moves-hole-of
  "The `$moves` map a say part holds; nil when the part is no such hole."
  [part]
  (when (map? part) (get part "$moves")))

(defn- day-hole-of
  "The `$day` map a say part holds; nil when the part is no such hole."
  [part]
  (when (map? part) (get part "$day")))

(defn- list-hole-of
  "The `$list` map a say part holds; nil when the part is no list."
  [part]
  (when (map? part) (get part "$list")))

(defn- span-problem
  "Why a hole named `hole` cannot read [since, until); nil when it can."
  [hole h]
  (let [since (instant-of (get h "since"))
        until (instant-of (get h "until"))]
    (cond
      (nil? since)
      (str "a `" hole "` hole's `since` is a date or an instant, and this one is "
           (tick (get h "since")) ".")

      (nil? until)
      (str "a `" hole "` hole's `until` is a date or an instant, and this one is "
           (tick (get h "until")) ".")

      (not (.isBefore since until))
      (str "a `" hole "` hole's `since` comes before its `until`."))))

(defn- take-window-problem
  "Why a hole named `hole` cannot read the take's window, `window:
  \"take\"` in place of `since` and `until`, or the clock's, `window:
  \"clock\"` with its `days` [from, to]; nil when it can."
  [hole h]
  (let [days (get h "days")]
    (cond
      (not (#{"take" "clock"} (get h "window")))
      (str "a `" hole "` hole's `window` is \"take\" or \"clock\", and this one is "
           (tick (get h "window")) ".")

      (or (contains? h "since") (contains? h "until"))
      (str "a `" hole "` hole with a `window` takes no `since` or `until`.")

      (and (contains? h "days") (not= "clock" (get h "window")))
      (str "a `" hole "` hole's `days` goes with `window: \"clock\"` only.")

      (and (contains? h "days")
           (not (and (sequential? days) (= 2 (count days))
                     (every? #(and (integer? %) (<= 1 % 31)) days)
                     (apply <= days))))
      (str "a `" hole "` hole's `days` is [from, to], two whole numbers from 1 to 31,"
           " the first no later than the second, and this one is " (tick days) "."))))

(defn- window-problem
  "Why one hole named `hole` (`$rows`, `$moves`) cannot read its window:
  its shape, its `kind` and its [since, until). `names` are the keys it
  takes. Nil when it can."
  [hole names h rdef-of]
  (if-not (map? h)
    (str "a `" hole "` hole is a map of `kind`, `since` and `until`.")
    (let [k (some-> (get h "kind") str)
          stray (remove (set names) (keys h))]
      (cond
        (seq stray)
        (str "a `" hole "` hole takes " (str/join ", " (butlast names))
             " and " (last names) ", not " (tick (first stray)) ".")

        (str/blank? k) (str "a `" hole "` hole names a `kind`.")

        (nil? (rdef-of k)) (str (tick k) " is not a kind this engine serves.")

        (contains? h "window") (take-window-problem hole h)

        :else (span-problem hole h)))))

(defn- hole-problem
  "Why one `$rows` hole cannot be filled; nil when it can."
  [h rdef-of]
  (or (window-problem "$rows" ["kind" "since" "until" "window" "days" "field" "terminal" "state" "count"
                               "sum" "one" "many" "none"]
                      h rdef-of)
      (when (and (contains? h "days") (not (contains? h "window")))
        "a `$rows` hole's `days` goes with `window: \"clock\"` only.")
      (when (and (contains? h "field") (not (some #{(get h "field")} hole-fields)))
        (str (tick (get h "field")) " is not a field a hole reads; the fields are "
             (str/join ", " hole-fields) "."))
      (some (fn [k]
              (when (and (contains? h k) (not (boolean? (get h k))))
                (str "a `$rows` hole's `" k "` is true or false.")))
            ["terminal" "count"])
      (when (and (contains? h "sum")
                 (not (and (string? (get h "sum")) (not (str/blank? (get h "sum"))))))
        "a `$rows` hole's `sum` names a field.")
      (when (contains? h "sum")
        (let [s (some-> (rdef-of (str (get h "kind"))) :schema
                        (schema/field-schema (keyword (get h "sum"))))
              head (if (vector? s) (first s) s)]
          (when-not (#{:int :double :float :number :decimal} head)
            (str (tick (get h "sum")) " is not a number field of " (tick (get h "kind")) "."))))
      (when (and (contains? h "sum") (true? (get h "count")))
        "a `$rows` hole counts or sums, not both.")
      (when (and (contains? h "state")
                 (not (some #{(str (get h "state"))}
                            (map name (:states (rdef-of (str (get h "kind"))))))))
        (str (tick (get h "state")) " is not a state of " (tick (get h "kind")) "."))
      (when (not= (contains? h "one") (contains? h "many"))
        "a `$rows` hole names `one` and `many` together.")
      (when (and (contains? h "one") (not (true? (get h "count"))) (not (contains? h "sum")))
        "a `$rows` hole's `one` and `many` word a count or a sum: it names `count` true or a `sum`.")
      (some (fn [k]
              (when (and (contains? h k) (not (string? (get h k))))
                (str "a `$rows` hole's `" k "` is a string.")))
            ["one" "many"])))

(defn- moves-problem
  "Why one `$moves` hole cannot be filled; nil when it can."
  [h rdef-of]
  (or (window-problem "$moves" ["kind" "since" "until" "by" "none"] h rdef-of)
      (when (and (contains? h "by") (not (some #{(get h "by")} move-actors)))
        (str (tick (get h "by")) " is not whose moves a hole reads; it reads "
             (str/join ", " move-actors) "."))))

(defn- day-problem
  "Why one `$day` hole cannot be filled; nil when it can. Its `domain`
  is judged for shape only: a domain no kind names plays a quiet day."
  [h]
  (let [names ["since" "until" "window" "day" "domain" "none"]]
    (if-not (map? h)
      "a `$day` hole is a map of `since`, `until` and `domain`, or of `window` and `domain`, with a `day` when the window is the clock."
      (let [stray (remove (set names) (keys h))]
        (cond
          (seq stray)
          (str "a `$day` hole takes " (str/join ", " (butlast names))
               " and " (last names) ", not " (tick (first stray)) ".")

          (str/blank? (some-> (get h "domain") str))
          "a `$day` hole names a `domain`."

          (contains? h "window")
          (cond
            (not (#{"shot" "clock"} (get h "window")))
            (str "a `$day` hole's `window` is \"shot\" or \"clock\", and this one is "
                 (tick (get h "window")) ".")

            (or (contains? h "since") (contains? h "until"))
            "a `$day` hole with a `window` takes no `since` or `until`."

            (= "shot" (get h "window"))
            (when (contains? h "day")
              "a `$day` hole's `day` goes with `window: \"clock\"` only.")

            (not (and (integer? (get h "day")) (<= 1 (get h "day") 31)))
            (str "a `$day` hole of `window: \"clock\"` names its `day`, a whole number from 1 to 31, and this one is "
                 (tick (get h "day")) "."))

          (contains? h "day")
          "a `$day` hole's `day` goes with `window: \"clock\"` only."

          :else (span-problem "$day" h))))))

(defn- list-problem
  "Why one `$list` part cannot be spoken; nil when it can: its `items`
  are `$rows` holes, each judged, and its `lead` and `none` strings."
  [l rdef-of]
  (let [names ["items" "lead" "none"]
        items (when (map? l) (get l "items"))]
    (cond
      (not (map? l)) "a `$list` is a map of `items`, `lead` and `none`."

      (seq (remove (set names) (keys l)))
      (str "a `$list` takes items, lead and none, not "
           (tick (first (remove (set names) (keys l)))) ".")

      (not (and (sequential? items) (seq items) (every? hole-of items)))
      "a `$list`'s `items` are one or more `$rows` holes."

      :else
      (or (some #(hole-problem (hole-of %) rdef-of) items)
          (some (fn [k]
                  (when (and (contains? l k) (not (string? (get l k))))
                    (str "a `$list`'s `" k "` is a string.")))
                ["lead" "none"])))))

(defn- say-problem
  "Why a shot's `say` cannot be spoken; nil when it can."
  [say rdef-of]
  (cond
    (or (nil? say) (string? say)) nil
    (not (sequential? say)) say-shape
    :else (some (fn [part]
                  (cond
                    (string? part) nil
                    (and (map? part) (contains? part "$rows"))
                    (hole-problem (hole-of part) rdef-of)
                    (and (map? part) (contains? part "$moves"))
                    (moves-problem (moves-hole-of part) rdef-of)
                    (and (map? part) (contains? part "$day"))
                    (day-problem (day-hole-of part))
                    (and (map? part) (contains? part "$list"))
                    (list-problem (list-hole-of part) rdef-of)
                    :else say-shape))
                say)))

(def card-max
  "The most characters a card's text holds."
  280)

(defn- card-text
  "A card's written text: the string, or a list's strings joined."
  [card]
  (if (string? card) card (apply str (filter string? card))))

(defn card-ms
  "The least a card of `text` is held, in ms: 1500, and 60 a word."
  [text]
  (+ 1500 (* 60 (count (re-seq #"\S+" (str text))))))

(defn- card-problem
  "Why a shot's `card` cannot be shown; nil when it can. A card is plain
  text of at most `card-max` characters, or a list of text and holes as
  a `say` is, and its shot holds it: the verb is `hold`, its `ms` at
  least `card-ms`."
  [shot rdef-of]
  (let [card (get shot "card")
        text (when (or (string? card) (sequential? card)) (card-text card))
        ms (get-in shot ["hold" "ms"])]
    (cond
      (nil? text)
      "a `card` is text, or a list of text and holes as a `say` is."

      (and (str/blank? text) (not-any? map? card))
      "a `card` has text, and this one is empty."

      (> (count text) card-max)
      (str "a `card` is at most " card-max " characters, and this one has "
           (count text) ".")

      (say-problem card rdef-of)
      (say-problem card rdef-of)

      (not (map? (get shot "hold")))
      "a `card` is held: its shot's verb is `hold`."

      (and (pos-int? ms) (< ms (card-ms text)))
      (str "a `card` of " (count (re-seq #"\S+" text)) " words is held at least "
           (card-ms text) " ms, and this one is held " ms "."))))

(defn- dollars
  "Whole dollars `n` with a comma between thousands: 1250 as 1,250."
  [n]
  (str/replace (str n) #"\B(?=(\d{3})+(?!\d))" ","))

(defn take-run
  "One take as the performer plays it: an atom holding the instant the
  take began (`began`, now unless given), and the instant the take's
  last `$day` hole of `window: \"shot\"` was filled, before the first
  the instant the take began; with `now-fn`, the clock its windows read
  now on (`:now-fn`), the wall clock without one."
  ([] (take-run (Instant/now)))
  ([^Instant began] (take-run began nil))
  ([^Instant began now-fn]
   (atom (cond-> {:began began :day-filled began}
           now-fn (assoc :now-fn now-fn)))))

(defn fill-say
  "A shot's `say` as the film speaks it. A string stays as written; a
  list is joined, each `$rows` hole replaced by the summary lines of the
  rows `rows-of` answers for it, joined with `; `, or by its `none`
  (`nothing came in` unless it names one) when there are none; with
  `count` true, by how many rows there are instead, and with `terminal`
  true it reads only rows in a terminal state, with `state` only rows in
  that state. A count with `one` and `many` says `<n> <one>` or `<n>
  <many>`; at zero it says its `none`, or without one drops its clause:
  the hole and the string after it. `rows-of` is a fn of
  {:kind :since :until :field :terminal :state :sum} to summary lines; the performer
  passes `rows-in-window` over the scene's clone. With `sum` naming a
  field, `rows-of` answers that field of each row instead, and the hole
  says their total in whole dollars (`$1,250`), worded and dropped as a
  count is. A `$list` says its `lead` and the text of each of its
  `items`, `$rows` holes, that says something, joined with `, `; or its
  `none` (`nothing came in`) when none does. Each `$moves` hole is
  replaced the same way by the lines `moves-of` answers, a fn of
  {:kind :since :until :by} (`moves-in-window`), or by its `none`
  (`nothing moved`). Each `$day` hole is replaced by the sentence
  `day-of` answers, a fn of {:since :until :domain} (`day-in-window`),
  or by its `none` (`A quiet day.`) when it answers nil. A `$day` hole of
  `window: \"shot\"` reads [the instant `run` holds, now), and leaves now
  on `run` for the next one; a `$rows` hole of `window: \"take\"` reads
  [the instant the take began on `run`, now), and one of
  `window: \"clock\"` asks `rows-of` for {:clock-days [from to] :until now}
  in place of `since` and `until`: those days of the clone's clock, the
  whole played week when it names no `days`. `run` is the take's
  `take-run`, a fresh one unless given. A `$day` hole of
  `window: \"clock\"` asks `day-of` for {:clock-day k :until now :domain}:
  day k of the clone's clock."
  ([say rows-of] (fill-say say rows-of (constantly [])))
  ([say rows-of moves-of] (fill-say say rows-of moves-of (constantly nil)))
  ([say rows-of moves-of day-of] (fill-say say rows-of moves-of day-of (take-run)))
  ([say rows-of moves-of day-of run]
   (let [say (plain say)
         ;; now on the run's clock: an engine's own, which stamps its rows
         now! (fn [] ((:now-fn @run (fn [] (Instant/now)))))
         spoken (fn [lines h none]
                  (if (seq lines)
                    (str/join "; " lines)
                    (str (get h "none" none))))
         ;; a `$rows` hole's text; nil when its zero drops its clause
         rows-said (fn [h]
                     (let [sum (some-> (get h "sum") str)
                           window (case (get h "window")
                                    "take" {:since (:began @run) :until (now!)}
                                    "clock" {:clock-days (vec (get h "days" []))
                                             :until (now!)}
                                    {:since (instant-of (get h "since"))
                                     :until (instant-of (get h "until"))})
                           lines (rows-of (cond-> (merge
                                                   {:kind (str (get h "kind"))}
                                                   window
                                                   {:field (get h "field" "created_at")
                                                   :terminal (true? (get h "terminal"))
                                                    :state (some-> (get h "state") str)})
                                            sum (assoc :sum sum)))
                           n (if sum
                               (Math/round (double (reduce + 0 (filter number? lines))))
                               (count lines))
                           said (if sum (str "$" (dollars n)) (str n))]
                       (cond
                         (not (or sum (true? (get h "count")))) (spoken lines h nothing)
                         (not (contains? h "one")) said
                         (pos? n) (str said " " (get h (if (= 1 n) "one" "many")))
                         (contains? h "none") (str (get h "none"))
                         :else nil)))
         said-list (fn [l]
                     (let [said (keep #(some-> (hole-of %) rows-said) (get l "items"))]
                       (if (seq said)
                         (str (get l "lead") (str/join ", " said))
                         (str (get l "none" nothing)))))]
     (if-not (sequential? say)
       (str say)
       (loop [parts (seq say) out []]
         (if-not parts
           (apply str out)
           (let [part (first parts)]
             (if-some [h (or (hole-of part) (list-hole-of part))]
               (if (list-hole-of part)
                 (recur (next parts) (conj out (said-list h)))
                 (if-some [s (rows-said h)]
                   (recur (next parts) (conj out s))
                   ;; a zero drops its clause: the hole and the string after it
                   (recur (cond-> (next parts) (string? (second parts)) next) out)))
               (recur (next parts)
                      (conj out
                            (if-some [h (moves-hole-of part)]
                              (spoken (moves-of {:kind (str (get h "kind"))
                                                 :since (instant-of (get h "since"))
                                                 :until (instant-of (get h "until"))
                                                 :by (get h "by" "seats")})
                                      h no-moves)
                              (if-some [h (day-hole-of part)]
                                (or (day-of (case (get h "window")
                                              "shot"
                                              (let [now (now!)
                                                    [was] (swap-vals! run assoc :day-filled now)]
                                                {:since (:day-filled was) :until now
                                                 :domain (str (get h "domain"))})
                                              "clock"
                                              {:clock-day (get h "day") :until (now!)
                                               :domain (str (get h "domain"))}
                                              {:since (instant-of (get h "since"))
                                               :until (instant-of (get h "until"))
                                               :domain (str (get h "domain"))}))
                                    (str (get h "none" quiet-day)))
                                (str part)))))))))))))

(declare clock-span)

(defn rows-in-window
  "The summary lines of engine `eng`'s rows of `kind` whose `field`
  (created_at, or updated_at) falls in [since, until), oldest first;
  with `terminal`, only those in one of the kind's terminal states, and
  with `state`, only those in that state; with `sum`, that field of
  each instead of its line. With `clock-days` [from to] in place of
  `since`, the window is those days of the engine's demo clock, the
  last ending at `until` (`clock-span`). None for a kind the engine does
  not serve, and none for clock days it has not played."
  [eng {:keys [kind clock-days field terminal state sum] :as w}]
  (let [{:keys [since until]} (if (contains? w :clock-days)
                                (clock-span eng clock-days (:until w))
                                w)
        st (:storage eng)
        rdef (get (inv/resources eng) (keyword kind))
        ended (into #{} (map name) (:terminal rdef))
        ended? (fn [row]
                 (and (or (not terminal) (contains? ended (some-> (:state row) name)))
                      (or (nil? state) (= state (some-> (:state row) name)))))
        said (fn [row]
               (if sum
                 (get-in row [:data (keyword sum)])
                 (summary/line rdef (assoc row :kind (:kind rdef)))))
        at (if (= "updated_at" field) :updated-at :created-at)
        within? (fn [row]
                  (let [t (get row at)]
                    (and (inst? t)
                         (<= (inst-ms since) (inst-ms t))
                         (< (inst-ms t) (inst-ms until)))))]
    (if-not (and rdef since until)
      []
      (store/with-tx st
        (fn [tx]
          (->> (store/query-rows st tx (keyword kind) {} {:all true})
               (filter within?)
               (filter ended?)
               (sort-by #(inst-ms (get % at)))
               (mapv #(said (inv/decode-row rdef %)))))))))

(defn- moved-by?
  "Whether a logged actor is one a `$moves` hole's `by` names: a seat
  (its principal is `seat:<id>`), a person, or anyone."
  [by actor]
  (case by
    "anyone" true
    "people" (= "human" (some-> (:type actor) name))
    (str/starts-with? (str (:id actor)) "seat:")))

(defn- ms-of
  "A logged `at` as epoch millis; nil when it is no time."
  [t]
  (cond
    (inst? t) (inst-ms t)
    (instance? OffsetDateTime t) (.toEpochMilli (.toInstant ^OffsetDateTime t))
    (some? t) (some-> (instant-of t) inst-ms)))

(defn moves-in-window
  "The transitions engine `eng`'s log holds on rows of `kind` in [since,
  until), made by those `by` names (`seats`, the default, `people` or
  `anyone`), oldest first, each as `<summary>: <action>`. None for a
  kind the engine does not serve."
  [eng {:keys [kind since until by]}]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) (keyword kind))
        lo (inst-ms since)
        hi (inst-ms until)]
    (if-not rdef
      []
      (->> (store/with-tx st #(store/transitions st % {:kind (:kind rdef)}
                                                 {:limit 100000}))
           (keep (fn [t]
                   (let [ms (ms-of (:at t))]
                     (when (and ms (<= lo ms) (< ms hi)
                                (moved-by? (or by "seats") (:actor t)))
                       [ms t]))))
           (sort-by first)
           (mapv (fn [[_ t]]
                   (str (or (not-empty (str (:summary t))) (:resource-id t))
                        ": " (name (:action t)))))))))

(defn- first-part
  "A summary line's first part, before its first ` · `."
  [line]
  (first (str/split (str line) #" · " 2)))

(defn- words [s] (str/replace (name s) "_" " "))

(defn- past [verb]
  (if (str/ends-with? verb "e") (str verb "d") (str verb "ed")))

(defn- move-rank
  "Where one logged move of a row of `rdef` stands in a `$day` sentence:
  1 for a door that cannot be undone, 2 for another seat's move, 3 for a
  create; nil for a move the day does not tell."
  [rdef t]
  (cond
    (nil? (:from-state t)) 3
    (false? (get-in rdef [:actions (keyword (name (:action t))) :safety :reversible])) 1
    (moved-by? "seats" (:actor t)) 2))

(defn- move-phrase
  "One logged move, phrased as factory10.law-text phrases its door: the
  row was written, went to its new state, moved on, or was the door's
  verb. The row is named by its summary's first part."
  [t]
  (let [who (first-part (or (not-empty (str (:summary t))) (:resource-id t)))
        from (some-> (:from-state t) name)
        to (some-> (:to-state t) name)]
    (cond
      (nil? from) (str who " was written")
      (nil? to) (str who " moved on")
      (not= from to) (str who " went to " (words to))
      :else (str who " was " (past (words (:action t)))))))

(defn- door-field [door k]
  (some-> (or (get door k) (get door (name k))) str not-empty))

(defn- held-phrase
  "One held call, as the ask it puts to a person: the door it would
  walk and the row it would walk it on, else the tool it would call."
  [st tx rdefs row]
  (let [door (get-in row [:data :door])
        k (door-field door :kind)
        id (door-field door :id)
        action (door-field door :action)
        rdef (some->> k keyword (get rdefs))
        target (when (and rdef id)
                 (some->> (store/load-row st tx (:kind rdef) id {})
                          (inv/decode-row rdef)
                          (#(assoc % :kind (:kind rdef)))
                          (summary/line rdef)
                          first-part))]
    (if (and action target)
      (str "a person was asked to " (words action) " " target)
      (str "a person was asked to allow "
           (first-part (summary/line (get rdefs :held_call)
                                     (assoc row :kind :held_call)))))))

(defn- ask-phrase
  "One approval_request, as the ask it puts to a person: the access its
  task is for, else the row's summary."
  [rdef row]
  (if-some [task (some-> (get-in row [:data :task]) str str/trim
                         (str/replace #"[.]+$" "") not-empty)]
    (str "a person was asked to approve access to " task)
    (str "a person was asked to approve "
         (first-part (summary/line rdef (assoc row :kind :approval_request))))))

(defn- day-entries
  "What the day [since, until) of the org whose kinds name `domain`
  holds, best first, as `told-day` ranks it: each [rank ms phrase row],
  `row` the {:kind :id} the entry is about. A held call is about the row
  its door would walk when this engine serves that kind, else itself.
  `opts` are `told-day`'s."
  [eng {:keys [since until domain]} {:keys [held? asks?] :or {held? true asks? true}}]
  (let [st (:storage eng)
        rdefs (inv/resources eng)
        lo (inst-ms since)
        hi (inst-ms until)
        within (fn [t] (when-some [ms (ms-of t)] (when (and (<= lo ms) (< ms hi)) ms)))
        kinds (filter #(= (str domain) (some-> (:domain %) name)) (vals rdefs))
        held-rdef (when held? (get rdefs :held_call))
        ask-rdef (when asks? (get rdefs :approval_request))]
    (store/with-tx st
      (fn [tx]
        (let [held (when held-rdef
                     (for [raw (store/query-rows st tx :held_call {} {:limit 100000})
                           :let [ms (within (:created-at raw))
                                 row (when ms (inv/decode-row held-rdef raw))]
                           :when (and row (= (str domain) (str (get-in row [:data :domain]))))]
                       (let [door (get-in row [:data :door])
                             k (some->> (door-field door :kind) keyword (get rdefs) :kind)
                             id (door-field door :id)]
                         [0 ms (held-phrase st tx rdefs row)
                          (if (and k id) {:kind k :id id} {:kind :held_call :id (str (:id row))})])))
              asks (when ask-rdef
                     (for [raw (store/query-rows st tx :approval_request {} {:limit 100000})
                           :let [ms (within (:created-at raw))
                                 row (when ms (inv/decode-row ask-rdef raw))]
                           :when (and row (= (str domain)
                                             (str (or (some-> (get-in row [:data :domain]) str not-empty)
                                                      (get-in ask-rdef [:absent-as :domain])))))]
                       [0 ms (ask-phrase ask-rdef row) {:kind :approval_request :id (str (:id row))}]))
              moves (for [rdef kinds
                          t (store/transitions st tx {:kind (:kind rdef)} {:limit 100000})
                          :let [ms (within (:at t))
                                rank (when ms (move-rank rdef t))]
                          :when rank]
                      [rank ms (move-phrase t) {:kind (:kind rdef) :id (str (:resource-id t))}])]
          (vec (sort-by (juxt first second) (concat held asks moves))))))))

(defn- told-day
  "One plain sentence of the day [since, until) of the org whose kinds
  name `domain`, or nil when nothing happened in it. It reads that
  window's transitions on every kind of the domain, the domain's held
  calls and the approval requests born in it (an ask's domain is the
  one born-ask stamps from its requester's seat, else the kind's
  :absent-as), ranks a held call or an ask first, then a door that
  cannot be undone, then another seat's move, then a create (oldest
  first within each), and tells the first two as clauses joined with
  `; ` (`day-entries`). `opts` :held? and :asks? false leave the held
  calls or the asks out, unsaid, for a caller who does not see that
  kind whole."
  [eng w opts]
  (let [said (->> (day-entries eng w opts)
                  (map #(nth % 2))
                  (take 2)
                  (str/join "; "))]
    (when-not (str/blank? said)
      (str (str/upper-case (subs said 0 1)) (subs said 1) "."))))

(defn- clock-bounds
  "The bounds of engine `eng`'s demo clock days in ms, in order: the start
  of its log, each `clock_shift` row, and `until` or the engine's clock,
  whichever is later: its rows are stamped on that clock, which runs
  ahead of the wall by the shifts."
  [eng ^Instant until]
  (let [st (:storage eng)
        shifts (if (contains? (inv/resources eng) :clock_shift)
                 (store/with-tx st
                   (fn [tx]
                     (->> (store/query-rows st tx :clock_shift {} {:limit 100000})
                          (keep #(ms-of (:created-at %)))
                          sort)))
                 [])]
    (-> [0] (into shifts)
        (conj (max (inst-ms until) (inst-ms ((:now-fn eng (fn [] until)))))))))

(defn clock-window
  "Day `k` of engine `eng`'s demo clock as a {:since :until} window: from
  its (k-1)th `clock_shift` row to its kth, day 1 from the start of its
  log and the day after the last shift up to `until`; nil for a later
  day, and for a `k` that is no whole number from 1. Rows, the log and
  the shifts are all stamped on the engine's clock (`store/stamped`),
  which only moves forward, so the shifts bound the days."
  [eng k ^Instant until]
  (let [bounds (clock-bounds eng until)]
    (when (and (integer? k) (<= 1 k (dec (count bounds))))
      {:since (Instant/ofEpochMilli (nth bounds (dec k)))
       :until (Instant/ofEpochMilli (nth bounds k))})))

(defn clock-span
  "Days `from` to `to` of engine `eng`'s demo clock as one {:since :until}
  window: from the start of day `from` (`clock-window`) to the end of day
  `to`, the last day ending at `until`. `from` is day 1 and `to` the last
  day played unless given, and a `to` past it is the last; nil when
  `from` is a day the clock has not played."
  [eng [from to] ^Instant until]
  (let [bounds (clock-bounds eng until)
        played (dec (count bounds))
        from (or from 1)
        to (if (integer? to) (min to played) played)]
    (when (and (integer? from) (<= 1 from to))
      {:since (Instant/ofEpochMilli (nth bounds (dec from)))
       :until (Instant/ofEpochMilli (nth bounds to))})))

(defn day-in-window
  "One plain sentence of the day `w` of the org whose kinds name its
  `domain`, or nil when nothing happened in it (`told-day`). `w` is
  {:since :until :domain}, or {:clock-day k :until :domain}: day k of
  the engine's demo clock, the last day ending at `until`
  (`clock-window`). `opts` are `told-day`'s."
  ([eng w] (day-in-window eng w {}))
  ([eng w opts]
   (if (contains? w :clock-day)
     (when-some [cw (clock-window eng (:clock-day w) (:until w))]
       (told-day eng (merge (dissoc w :clock-day) cw) opts))
     (told-day eng w opts))))

(defn- domain-kinds
  "The kinds engine `eng` serves whose domain is `domain`, by name."
  [eng domain]
  (->> (vals (inv/resources eng))
       (filter #(= (str domain) (some-> (:domain %) name)))
       (sort-by (comp name :kind))))

(defn day-row-open
  "The concrete open of a `$day-row` open: the screen of the top move of
  day k of engine `eng`'s demo clock (`clock-window`, the last day
  ending at `until`), or of [since, until) when no :clock-day is given,
  in the org whose kinds name `domain`, ranked as a
  `$day` ranks it (`day-entries`). The moves of the best rank on the top
  move's kind are one row: that row's page as it stood at the end of
  day k, read from its log, {self /api/<plural>/<id>?as-of=<end>, as_of
  <end>}, or as it stands now, {self /api/<plural>/<id>}, when :as-of?
  is false; several: that kind's collection, unfiltered, for an open
  names no filter. Nothing moved, a day the clock has not played, or a caller who
  does not see the domain whole: the domain's first kind by name the
  caller sees, its collection; nil when it sees none. `opts` are
  `told-day`'s, and :sees?, a fn of a kind keyword, every kind unless
  given."
  [eng {:keys [clock-day since until domain as-of?] :or {as-of? true}}
   {:keys [sees?] :or {sees? (constantly true)} :as opts}]
  (let [rdefs (inv/resources eng)
        plural #(or (:plural (get rdefs %)) (str (name %) "s"))
        kinds (domain-kinds eng domain)
        cw (if clock-day
             (clock-window eng clock-day until)
             (when (and since until) {:since since :until until}))
        entries (when (and cw (every? #(sees? (:kind %)) kinds))
                  (filter #(sees? (:kind (peek %)))
                          (day-entries eng (assoc cw :domain domain) opts)))
        [rank _ _ top] (first entries)
        rows (->> entries
                  (filter #(= rank (first %)))
                  (map peek)
                  (filter #(= (:kind top) (:kind %)))
                  distinct)
        fallback (first (filter #(sees? (:kind %)) kinds))]
    (cond
      (= 1 (count rows))
      (let [self (str "/api/" (plural (:kind top)) "/" (:id top))]
        (if as-of?
          {"self" (str self "?as-of=" (:until cw)) "as_of" (str (:until cw))}
          {"self" self}))
      (seq rows) {"collection" (plural (:kind top))}
      fallback {"collection" (plural (:kind fallback))})))

;; ── the say over the wire (POST /api/-/scenes/say) ───────────────────

(def take-run-ttl-ms
  "How long the engine keeps a take's run after the take began: two
  hours, longer than any film plays."
  (* 2 60 60 1000))

(defonce ^:private take-runs
  ;; take key → {:began Instant :run (take-run)}
  (atom {}))

(defn take-run-of
  "The run of the take keyed `k`: the first call for a key makes one
  (`take-run`, begun `now`), a later one reuses it, and every run begun
  `take-run-ttl-ms` or more before `now` is dropped first. With
  `now-fn`, an engine's clock, the run begins and reads on it; its
  keeping is still judged on `now`, the wall."
  ([k] (take-run-of k (Instant/now)))
  ([k now] (take-run-of k now nil))
  ([k ^Instant now now-fn]
   (let [lo (- (inst-ms now) (long take-run-ttl-ms))]
     (-> (swap! take-runs
                (fn [runs]
                  (let [runs (into {} (filter #(< lo (inst-ms (:began (val %))))) runs)]
                    (cond-> runs
                      (not (contains? runs k))
                      (assoc k {:began now
                                :run (take-run (if now-fn (now-fn) now) now-fn)})))))
         (get-in [k :run])))))

(defn say-refusal
  "Why `say` cannot be filled on engine `eng`, as `check` says it of a
  shot; nil when it can."
  [eng say]
  (let [rdefs (inv/resources eng)]
    (say-problem (plain say) #(get rdefs (keyword %)))))

(defn fill-say-as
  "`fill-say` over engine `eng` for one caller: a `$rows` or `$moves`
  hole reads its kind only when `sees?` (a fn of a kind keyword) says
  the caller sees it whole, and a `$day` hole its domain only when the
  caller sees every kind of it; otherwise the hole finds nothing and
  says its `none`. A `$day` tells held calls only when the caller sees
  held_call whole, and asks only when it sees approval_request whole;
  otherwise those clauses are left out, unsaid. `run` is the take's run
  (`take-run-of`)."
  [eng say run sees?]
  (let [rdefs (inv/resources eng)
        kind-ok? #(boolean (sees? (keyword %)))]
    (fill-say say
              #(if (kind-ok? (:kind %)) (rows-in-window eng %) [])
              #(if (kind-ok? (:kind %)) (moves-in-window eng %) [])
              (fn [w]
                (when (every? sees? (keep #(when (= (:domain w) (some-> (:domain %) name))
                                             (:kind %))
                                          (vals rdefs)))
                  (day-in-window eng w {:held? (boolean (sees? :held_call))
                                        :asks? (boolean (sees? :approval_request))})))
              run)))

(defn seat-open
  "The concrete open of a `seat` or `sitting` open, as the shot plays:
  the page of the seat of that name, an active one first, {self
  /api/seats/<id>}, or of its newest sitting, {self
  /api/sittings/<id>}. {:problem} with one sentence when no seat the
  caller sees (`sees?`) has the name, the caller cannot see sittings,
  or the seat has not sat yet."
  [eng o sees?]
  (let [st (:storage eng)
        rdefs (inv/resources eng)
        plural #(or (:plural (get rdefs %)) (str (name %) "s"))
        sitting? (contains? o "sitting")
        nm (str/trim (str (if sitting? (get-in o ["sitting" "of"]) (get o "seat"))))
        seat (when (sees? :seat)
               (store/with-tx st
                 (fn [tx]
                   (let [rows (store/query-rows st tx :seat {:name nm} {:all true})]
                     (or (first (filter #(= "active" (some-> (:state %) name)) rows))
                         (first rows))))))
        sat (when (and seat sitting? (sees? :sitting))
              (store/with-tx st
                (fn [tx]
                  (first (store/query-rows st tx :sitting {:seat (str (:id seat))}
                                           {:limit 1 :newest-first true})))))]
    (cond
      (nil? seat) {:problem (str "no seat is named " (tick nm) ".")}
      (not sitting?) {"self" (str "/api/" (plural :seat) "/" (:id seat))}
      (not (sees? :sitting))
      {:problem (str "this caller cannot see sittings, so the sitting of "
                     (tick nm) " cannot be opened.")}
      (nil? sat) {:problem (str "the seat " (tick nm) " has not sat yet.")}
      :else {"self" (str "/api/" (plural :sitting) "/" (:id sat))})))

(defn open-as
  "A shot's `open` as the performer takes it, for one caller (POST
  /api/-/scenes/open): a `$day-row` open resolved to its clock day's
  screen, or its [since, until)'s (`day-row-open`, the day ending now; its row as of the day's
  end unless the open says `as_of` false), through `sees?` as
  `fill-say-as` fills a `$day`; a `seat` or `sitting` open to its page
  (`seat-open`); any other open as given. nil when the caller sees no
  kind of the domain."
  [eng open sees?]
  (let [o (plain open)]
    (if-some [h (when (map? o) (get o "$day-row"))]
      (day-row-open eng (merge (if (contains? h "since")
                                 {:since (instant-of (get h "since"))
                                  :until (instant-of (get h "until"))}
                                 {:clock-day (get h "day") :until (Instant/now)})
                               {:domain (str (get h "domain"))
                                :as-of? (not (false? (get o "as_of")))})
                    {:sees? #(boolean (sees? %))
                     :held? (boolean (sees? :held_call))
                     :asks? (boolean (sees? :approval_request))})
      (if (and (map? o) (or (contains? o "seat") (contains? o "sitting")))
        (seat-open eng o #(boolean (sees? %)))
        open))))

(defn- shot-step
  "One shot: the walk after it, or {:problem}."
  [shot st people rdef-of]
  (if-not (map? shot)
    (fail "a shot is a map with one verb.")
    (let [named (filter (set verbs) (keys shot))
          stray (remove (into #{"say" "card" "expect" "bind" "role" "speed" "trim" "id"} verbs)
                        (keys shot))
          verb (first named)]
      (cond
        (seq stray)
        (fail "unknown verb " (tick (first stray)) "; the verbs are "
              (str/join ", " verbs) ".")

        (and (contains? shot "card") (card-problem shot rdef-of))
        (fail (card-problem shot rdef-of))

        (not= 1 (count named))
        (fail "a shot has one verb, and this one has " (count named) ".")

        (and (contains? shot "role")
             (not (some #{(get shot "role")} film-rules/roles)))
        (fail (tick (get shot "role")) " is not a role; the roles are "
              (str/join ", " film-rules/roles) ".")

        (and (contains? shot "speed")
             (not (and (number? (get shot "speed"))
                       (<= 0.25 (get shot "speed") 8))))
        (fail "`speed` is a number from 0.25 to 8, and this one is "
              (tick (get shot "speed")) ".")

        (and (contains? shot "trim") (not (boolean? (get shot "trim"))))
        (fail "`trim` is true or false, and this one is "
              (tick (get shot "trim")) ".")

        (and (true? (get shot "trim")) (not (str/blank? (str (get shot "say")))))
        (fail "a shot with `trim` is cut from the film, so it has no `say`.")

        (say-problem (get shot "say") rdef-of)
        (fail (say-problem (get shot "say") rdef-of))

        :else
        (let [v (get shot verb)
              st' (verb-step verb (if (map? v) v {}) st people rdef-of)
              st' (cond-> st'
                    (and (nil? (:problem st')) (some? (get shot "bind")))
                    (assoc-in [:bound (str (get shot "bind"))]
                              (or (:found st') true)))]
          (if-some [p (when (and (nil? (:problem st')) (contains? shot "expect"))
                        (expect-problem (get shot "expect") st' rdef-of))]
            (fail p)
            st'))))))

(defn- wearing-problem
  "Why a scene's `wearing` cannot stand, as `wearing <key>: <why>`; nil
  when every entry can. A key is a cast id, and a value is a grant id as
  written or the `$bind` of a given that reads kind `grant`. Nothing is
  resolved here: the performer reads the grant after the givens run."
  [scene people]
  (let [reads (into {}
                    (keep (fn [c]
                            (when (and (map? c) (some? (get c "bind")))
                              [(str (get c "bind"))
                               (and (contains? #{"waymark_get" "waymark_query"}
                                               (get c "tool"))
                                    (= "grant" (some-> (get-in c ["arguments" "kind"])
                                                       str)))])))
                    (get scene "given"))]
    (some (fn [[who grant]]
            (let [grant (str grant)
                  nm (when (str/starts-with? grant "$") (subs grant 1))
                  why (cond
                        (not (some #{who} people))
                        (str (tick who) " is not in the cast.")

                        (nil? nm) nil

                        (not (contains? reads nm))
                        (str (tick grant) " names no given.")

                        (not (get reads nm))
                        (str (tick grant) " is bound by a given that does not read kind `grant`."))]
              (when why (str "wearing " (tick who) ": " why))))
          (get scene "wearing"))))

(defn- query-bind-problem
  "Why a given cannot bind what it answers, as `given N: <why>`; nil when
  each can. A bind names one row: the performer reads the answer's
  `self`. A waymark_query answers a collection, which has none, so its
  bind stands only as the grant read a `wearing` names."
  [scene]
  (let [worn (into #{} (map str) (vals (get scene "wearing")))]
    (some (fn [[i c]]
            (when (and (map? c) (= "waymark_query" (get c "tool"))
                       (some? (get c "bind"))
                       (not (contains? worn (str "$" (get c "bind")))))
              (str "given " (inc i) ": a bind names one row, and a waymark_query"
                   " answers a collection, so " (tick (get c "bind"))
                   " would bind nothing; bind a waymark_get, or name it in `wearing`"
                   " as a grant read.")))
          (map-indexed vector (get scene "given")))))

(defn problem-of
  "The first call, `wearing` entry or shot of a scene's data that cannot
  be performed, as `given N: <why>`, `wearing <key>: <why>` or
  `shot N: <why>`; nil when every one can. `rdef-of` is the ctx's
  registry consult."
  [data rdef-of]
  (let [scene (plain data)
        people (mapv str (get scene "cast"))
        run (fn [st label step items]
              (reduce (fn [st [i item]]
                        (let [st' (step item (dissoc st :found) people rdef-of)]
                          (if-some [p (:problem st')]
                            (reduced (fail label " " (inc i) ": " p))
                            st')))
                      st
                      (map-indexed vector items)))
        st (run {:bound {}} "given" call-step (get scene "given"))
        worn (when-not (:problem st)
               (or (query-bind-problem scene) (wearing-problem scene people)))]
    (:problem (cond
                (:problem st) st
                worn (fail worn)
                :else (run st "shot" shot-step (get scene "shots"))))))

;; ── guards and handlers ─────────────────────────────────────────────

(g/defguard every-shot-can-be-performed
  {:reads [:storage]
   :vars [:problem]
   :open "Revise the scene so the call or shot named can be performed, then check it again."
   :explain "A scene is ready when every call and shot names what this house serves: {problem}"}
  [row _inp ctx]
  (if-some [rdef-of (:rdef-of ctx)]
    (if-some [problem (problem-of (:data row) rdef-of)]
      (t/deny {:vars {:problem problem}})
      (t/allow))
    ;; no registry in scope (a render probe): the write path carries it
    (t/allow)))

(g/defguard the-take-is-of-a-version-the-scene-had
  {:judges [:scene :scene_version]
   :reads [:storage]
   :open "Name a scene you can see, and a version from 1 up to the one it holds now."
   :explain "A take is of a scene, at a version that scene has had."}
  [_row inp ctx]
  (if-some [read (:read ctx)]
    (let [scene (some->> (:scene inp) str not-empty (read kind))
          version (get-in scene [:data :version])]
      (if (and scene (int? version) (int? (:scene_version inp))
               (<= 1 (:scene_version inp) version))
        (t/allow)
        (t/deny)))
    (t/allow)))

(defn- born
  "The birth stamps: the author is the principal, and the version is 1."
  [row ctx]
  (update row :data assoc
          :author (str (get-in ctx [:principal :id]))
          :version 1))

(defn- take-born
  "The verdict the engine reads off the shots: the first that failed."
  [row _ctx]
  (let [failing (first (keep #(when-not (:ok %) (:shot %))
                             (get-in row [:data :shots])))]
    (update row :data assoc :first_failing_shot failing :ok (nil? failing))))

;; ── the kinds ───────────────────────────────────────────────────────

(def ^:private offer-fields
  "What the author writes: the create model."
  [[:title {:examples ["Quests, from a dotted Complete"]
            :x-display {:label "Title"
                        :help "One line that names the demo."}}
    [:string {:min 1 :max 120}]]
   [:seed {:x-display {:label "Seed"
                       :help "The demo seed the runner loads before the first call."}}
    [:string {:min 1 :max 80}]]
   [:devices {:x-display {:label "Devices"
                          :help "Each device the scene is performed on; one take per device."}}
    [:vector {:min 1 :max 2} [:enum "phone" "desktop"]]]
   [:cast {:x-display {:label "Cast"
                       :help "Cast ids from the seed. The first is the person filmed."}}
    [:vector {:min 1 :max 12} [:string {:min 1 :max 128}]]]
   [:given {:optional true
            :x-display {:raw true
                        :label "Given"
                        :help "Setup calls made before filming, in order: {as, tool, arguments, bind?}."
                        :spelled-by-hand "Each call's arguments are its tool's own, so no fixed sub-form can offer them."}}
    [:maybe [:vector {:max 40} [:map-of :keyword :any]]]]
   [:wearing {:optional true
              :x-display {:raw true
                          :label "Wearing"
                          :help "The grant each cast member wears when filming starts: a cast id to a grant id, or to the `$bind` of a given that reads the grant."
                          :spelled-by-hand "Each key is a cast id of this scene, so no fixed sub-form can offer them."}}
    [:maybe [:map-of :keyword [:string {:min 1 :max 128}]]]]
   [:shots {:x-display {:raw true
                        :label "Shots"
                        :help "The filmed moments, in order. Each has one verb, and may have `say`, `expect` and `bind`."
                        :spelled-by-hand "Each verb takes its own keys, so no fixed sub-form can offer them."}}
    [:vector {:min 1 :max 200} [:map-of :keyword :any]]]])

(defhandler revise-scene [row inp _ctx]
  (-> row
      (update :data merge (into {} (remove (comp nil? val))
                                (select-keys inp (map first offer-fields))))
      (update-in [:data :version] (fnil inc 1))))

(defresource scene
  {:kind :scene
   :plural "scenes"
   :nav :secondary
   :states [:draft :ready :retired]
   :initial :draft
   :summary "{data.title} · v{data.version} · {state}"
   :label-template "{data.title}"
   :schema
   (-> [:map]
       (into offer-fields)
       (into [[:author {:optional true
                        :x-ref {:principal true}
                        :x-display {:raw true
                                    :label "Who wrote it"
                                    :help "The principal that created the scene, stamped by the engine."}}
               [:maybe [:string {:max 128}]]]
              [:version {:optional true
                         :x-display {:label "Version"
                                     :help "1 at birth, and one more at each revise. A take names the version it performed."}}
               [:maybe [:int {:min 1}]]]]))
   :no-own-surface "A scene is a film's script, read by all who film it; it is not its author's private row."
   :create-schema (into [:map] offer-fields)
   :filterable {:state #{:eq :in}
                :seed #{:eq}
                :author #{:eq}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :default-filters {:state "draft,ready"}
   :on-create born
   :actions
   {:check
    {:from #{:draft} :to :ready
     :guards [every-shot-can-be-performed]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Check" :order 1
               :description "Judge every call and shot against this house; nothing is run"}}
    :revise
    {:from #{:draft :ready} :to :draft
     :input (into [:map]
                  (map (fn [[k props form]]
                         [k (assoc props :optional true) [:maybe form]]))
                  offer-fields)
     :handler revise-scene
     :safety {:idempotent false :reversible false :confirm false
              :one-way "The scene takes the fields given, its version goes up by one, and it must be checked again."}
     :display {:label "Revise" :order 2
               :description "Rewrite any of the scene's fields; it is a draft again"}}
    :retire
    {:from #{:draft :ready} :to :retired
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The scene is no longer performed. Its takes stay."}
     :display {:label "Retire" :order 3
               :description "Stop performing this scene"}}
    :restore
    {:from #{:retired} :to :draft
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Restore" :order 4
               :description "Bring the scene back as a draft; it must be checked again"}}}})

(def ^:private shot-result
  "What the runner saw at one shot."
  [:map
   [:shot {:x-display {:label "Shot" :help "The shot's place in the scene, counted from 1."}}
    [:int {:min 1}]]
   [:ok {:x-display {:label "Passed" :help "The verb was performed and every expectation held."}}
    :boolean]
   [:pressed {:optional true
              :x-display {:label "Pressed" :help "The surface the runner acted on, as it resolved."}}
    [:maybe [:string {:max 300}]]]
   ;; a focus shot's surface, as the performer names it beside pressed
   [:focus {:optional true
            :x-display {:label "Focus" :help "The surface a focus shot drew the eye to, by name."}}
    [:maybe [:string {:max 300}]]]
   [:expects {:optional true
              :x-display {:raw true
                          :label "Expectations"
                          :help "One entry per expectation: what was asked, what was read, and whether it held."
                          :spelled-by-hand "An entry repeats its expectation's own keys beside what was read."}}
    [:maybe [:vector {:max 40} [:map-of :keyword :any]]]]
   ;; a meanwhile call the engine refused: its kind, action and sentence
   [:refused {:optional true
              :x-display {:raw true
                          :label "Refused"
                          :help "The call a meanwhile made that the engine refused, and why."
                          :spelled-by-hand "The entry repeats the refused call's own kind and action beside the engine's sentence."}}
    [:maybe [:map-of {:max 40} :keyword :any]]]
   ;; a call shot's outcome: moved, held or refused, and the engine's sentence
   [:call {:optional true
           :x-display {:label "Call"
                       :help "How the engine answered a call shot: moved, held or refused, with its sentence."}}
    [:maybe [:map
             [:outcome [:enum "moved" "held" "refused"]]
             [:sentence {:optional true} [:maybe [:string {:max 480}]]]]]]
   [:why {:optional true
          :x-display {:label "Why it failed"
                      :help "The runner's sentence for a shot that failed: the verb it could not perform, or the expectation that did not hold."}}
    [:maybe [:string {:max 500}]]]
   [:still {:optional true
            :x-display {:raw true :label "Still" :help "Where the still of this shot is kept."}}
    [:maybe [:string {:max 500}]]]
   ;; the scene shot's own id and role, carried onto the take so the
   ;; film rules (the week arc) can read the roles
   [:id {:optional true
         :x-display {:label "Shot id" :help "The id the scene gave this shot."}}
    [:maybe [:int {:min 1}]]]
   [:role {:optional true
           :x-display {:label "Role" :help "The shot's part in the arc: friction, turn or payoff."}}
    [:maybe (into [:enum] film-rules/roles)]]
   ;; a card shot's text as it was shown, and how long it was held
   [:card {:optional true
           :x-display {:widget "prose" :label "Card" :help "The text a card shot showed, its holes filled."}}
    [:maybe [:string {:max 2000}]]]
   [:hold_ms {:optional true
              :x-display {:label "Held (ms)" :help "How long the card was held, in ms."}}
    [:maybe [:int {:min 0}]]]])

(def ^:private take-fields
  "What the runner writes: the create model."
  [[:scene {:kind :scene
            :x-display {:raw true :label "The scene"}}
    :waymark/ref]
   [:scene_version {:x-display {:label "Scene version"
                                :help "The version of the scene that was performed."}}
    [:int {:min 1}]]
   [:device {:x-display {:label "Device" :help "The device it was performed on."
                         :choices {"phone" "A phone, held upright."
                                   "desktop" "A desktop browser window."}}}
    [:enum "phone" "desktop"]]
   [:commit {:x-display {:raw true :label "Commit" :help "The commit of the house that was filmed."}}
    [:string {:min 1 :max 64}]]
   ;; an org clone's renderer names both halves of what it filmed
   [:engine_commit {:optional true
                    :x-display {:raw true :label "Engine commit" :help "The engine's commit the take was rendered on."}}
    [:maybe [:string {:max 64}]]]
   [:org_commit {:optional true
                 :x-display {:raw true :label "Org commit" :help "The org's commit the take was rendered on, for an org clone."}}
    [:maybe [:string {:max 64}]]]
   [:film {:optional true
           :x-display {:raw true :label "Film" :help "The id of the film this take made. Empty when none was rendered."}}
    [:maybe [:string {:max 200}]]]
   [:shots {:x-display {:label "Shots" :help "One result per shot performed, in order. A take that stopped holds the failing shot last."}}
    [:vector {:max 200} shot-result]]])

(defresource take
  {:kind :take
   :plural "takes"
   :nav :secondary
   :states [:recorded]
   :initial :recorded
   :terminal #{:recorded}
   :summary "{data.device} · v{data.scene_version} · {data.commit}"
   :label-template "{data.device} · v{data.scene_version} · {data.commit}"
   :schema
   (-> [:map]
       (into take-fields)
       (into [[:ok {:optional true
                    :x-display {:label "Every shot passed"
                                :help "Stamped by the engine from the shots."}}
               [:maybe :boolean]]
              [:first_failing_shot {:optional true
                                    :x-display {:label "First failing shot"
                                                :help "The first shot that did not pass, stamped by the engine. Empty when every shot passed."}}
               [:maybe [:int {:min 1}]]]]))
   :create-schema (into [:map] take-fields)
   :create-guards [the-take-is-of-a-version-the-scene-had]
   :on-create take-born
   :filterable {:scene #{:eq}
                :device #{:eq}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :links [{:rel "scene" :kind :scene
            :href "/api/scenes/{data.scene}"
            :summary "The scene this take performed"}]})
