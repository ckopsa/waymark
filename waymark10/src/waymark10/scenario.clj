(ns waymark10.scenario
  "Law scenarios: the policy proves itself.

  The conformance packs prove the MACHINERY — that an advertised
  action is an enforced action, that a refusal is shaped like a
  refusal. Nothing proved the POLICY. \"A letter addressed to someone
  else does not open for a curious sibling, and the refusal says
  which wall it hit\" is a sentence the household can check and the
  engine could not. A scenario makes it a declaration: a row written
  down instead of walked to, a principal, an attempted action, an
  expected verdict — data beside the resource, judged by the same
  g/evaluate the render probe and the enforcement loop call.

  ── the sentence IS the violation ──

  A scenario's sentence is required and non-blank, exactly as a
  guard's :explain is, and for the same reason: it is not
  documentation. When the law stops keeping the promise, the failure
  reads in the household's own words — never `expected :deny, got
  :allow`. The diagnosis rides after it, as a clause.

  ── :expect names the GUARD ──

  {:refused :opener-is-recipient} pins WHICH wall, not which prose. A
  scenario that pinned only the sentence would fail on every polish
  and pass on a guard swapped for a different guard with the same
  words. :because is a substring of the RENDERED reason (garnish and
  all) and is optional; :remedies asserts the affordance tokens the
  refusal offers, which is how a scenario says \"and it names the way
  out\". The reserved denier :out-of-state is the one verdict with no
  guard behind it — a row whose state is outside the action's :from.

  ── two tiers, chosen by declaration, never sniffed ──

  A scenario runs in the CHECK tier — in this process, with no
  storage of any kind, in the same breath as `make check-queue` —
  when every guard on the attempted action declares :reads ⊆
  offline-reads or is DECLARATIVE — its :reads derived from a :when
  tree whose follow, exists and did the scenario answers from its own
  :given rows and :history (docs/spec-related-row-law.md §5), or is a
  code guard that declared `:check-tier :given` over kind reads — AND it
  stages :given rows only for such a guard to read. Both conditions are read
  off DECLARATIONS, which is guards.clj's own posture ('call shape
  comes from :reads/:judges declarations, never from arity
  inspection'). Everything else is the CONFORMANCE tier: the
  :core/law-scenarios obligation, over the application's real engine,
  through the HTTP door — so what it proves is the refusal a CLIENT
  sees, not the one a guard returned.

  :reads is ASSERTED, not inferred. A code guard that reaches past
  its declared :reads — an in-process atom, a clock read — answers
  the check tier's question with its offline verdict.
  workqueue10's letters-are-paced already handles that by hand
  ((nil? (:read ctx)) ⇒ allow: 'the storage-free probe never spends a
  slot'), which is the pattern, not the exception. A scenario that
  wants the live verdict declares :given and drops a tier.

  ── never fingerprinted ──

  fingerprint-of is a WHITELIST projection, so :scenarios is excluded
  by construction rather than by an exclusion list. That is
  load-bearing and pinned (law_scenarios_test): a test that minted a
  law revision would be a test that triggered a propose-mode hold,
  and the framework would be at war with itself.

  Two callers — waymark10.check (the no-database battery) and
  waymark10.test.packs (the obligation) — and the logic lives in
  neither, the same shape waymark10.test.conformance-as-library
  already has."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.effect :as effect]
            [waymark10.expr :as expr]
            [waymark10.guards :as g]
            [waymark10.money :as money]
            [waymark10.posts :as posts]
            [waymark10.recurs :as recurs]
            [waymark10.schema :as schema]
            [waymark10.server.work-calendar :as work-calendar]
            [waymark10.summary :as summary]
            [waymark10.types :as t])
  (:import (java.time Instant LocalDate)
           (java.time.format DateTimeParseException)))

(set! *warn-on-reflection* true)

;; ── the authored surface, closed ────────────────────────────────────

(def scenario-keys
  "One scenario's whole authored surface — the same closure
  action-keys and link-keys already give theirs, so a typo'd key
  refuses at the def line instead of declaring nothing."
  [:name :sentence :kind :attempt :recurs :row :input :as :at :calendar :events :effects :given :history :history-mode
   :postings :rates :expect])

(def expect-keys
  "The verdict half: exactly one of :allowed / :refused, with
  :because and :remedies as optional tightenings of a refusal, and
  :balances the accounts the walk leaves as it says
  (docs/spec-ledger-money.md §9)."
  [:allowed :refused :because :remedies :balances])

(def row-keys [:state :data])
;; :reuse adopts the row already on record under the kind's :unique
;; group instead of creating one — opt-in, for a fixture meant to be shared
(def given-keys [:kind :state :data :handle :reuse])
;; one entry of the log the scenario's row is to have, oldest first:
;; who did which door with what body, and for an acted-as move the seat
;; behind them
(def history-keys [:action :as :input :acted-by])
;; :leashed (waymark-zs9) is the conformance tier's one opt-out: an
;; :agent is attempted holding a grant over exactly the door it knocks
;; on, unless the scenario says `:leashed false` — which is how "an
;; unleashed agent sees nothing" stays a sentence a scenario can say.
(def principal-keys [:id :type :roles :leashed])
;; the house's calendar a scenario declares (docs/spec-calendar-time.md
;; §6): the same values a work_calendar row keeps
(def calendar-keys [:zone :workdays :hours :holidays])
;; one outside event a scenario replays (docs/spec-integrations.md §6):
;; the integration that sends it, its type — or the one its body carries
;; — and the body as recorded
(def event-keys [:source :type :body])
;; what a fake answers for one power: the call's answer, or the sentence
;; it failed with; the keyword :unknown is a fake too
(def fake-keys [:answer :fails])

(def offline-reads
  "The external dependencies a STORAGE-FREE judgment can honestly
  supply: the clock the scenario declares (:at), the principal it
  declares (:as), and the feature set — which the check tier serves
  as EMPTY, since a declaration-time world has no service wiring. A
  guard reading anything else (:storage, :transitions, a kind
  keyword) is judged where its reads can be answered: the conformance
  tier.

  `:within` (waymark-jfv.20) is on the offline side: a scenario is a
  client's knock, so the check tier answers it as nil — which is the
  same answer the wire gives, and exactly the case a door walled on it
  exists to refuse.

  `:grant` (waymark-sfe) joins it for the same reason and with the
  same answer: a scenario knocks presenting NO grant, so a grantable
  wall (`guards/unless-granted`) refuses every agent here — the
  ungranted half of its law, judged with no database at all. What a
  scenario cannot say is the other half; \"admitted under a scope
  naming this action\" needs a minted grant and a real request, so it
  is proved in the suites, over the wire, where a grant exists to be
  presented.

  `:facts` (waymark-442.7) is what `guards/require` declares: its check
  consults the maintained fact on the row it is handed, and a scenario
  states that row whole in `:row`, so the verdict needs no storage.

  `:calendar` (docs/spec-calendar-time.md §6) is the house's calendar,
  which the scenario declares beside its moment, so `(today)`,
  `business-days` and `working?` are judged here and not deferred."
  #{:now :principal :services.features :within :grant :facts :calendar})

(def out-of-state
  "The reserved denier name: a row whose state is outside the
  action's :from set is refused by the machine itself, with no guard
  behind it (server/problems wrong-state, render's
  out-of-state-entry)."
  :out-of-state)

;; ── construction ────────────────────────────────────────────────────

(defn- err [sname msg]
  (throw (t/definition-error (str "defscenario " (name sname) ": " msg)
                             {:check :scenario :scenario sname})))

(defn- closed! [sname label legal m]
  (when (map? m)
    (when-some [stray (seq (remove (set legal) (keys m)))]
      (err sname (str (pr-str (vec (sort stray))) " " (if (= 1 (count stray)) "is" "are")
                      " not " label " keys; they are " (vec (sort legal)))))))

(defn- instant!
  [sname at]
  (when (some? at)
    (when-not (string? at)
      (err sname ":at is the moment as an ISO instant string"))
    (try (Instant/parse ^String at)
         (catch DateTimeParseException _
           (err sname (str ":at " (pr-str at) " is not an ISO instant"))))))

(defn- calendar-answer
  "What the :calendar hook answers for the scenario's declared
  calendar — the very shape a work_calendar row answers — or nil when
  its zone names none."
  [cal]
  (work-calendar/answer
   {:id "scenario"
    :data {:zone (:zone cal)
           :workdays (map name (:workdays cal))
           :hours (:hours cal)
           :holidays (map #(if (map? %) % {:on %}) (:holidays cal))}}))

(defn- calendar!
  [sname cal]
  (when (some? cal)
    (when-not (map? cal)
      (err sname ":calendar is the house's calendar {:zone … :workdays … :hours … :holidays …}"))
    (closed! sname "calendar" calendar-keys cal)
    (when (nil? (calendar-answer cal))
      (err sname (str ":calendar :zone " (pr-str (:zone cal)) " names no time zone")))
    (when-not (and (coll? (:workdays cal ())) (every? #(or (string? %) (keyword? %)) (:workdays cal)))
      (err sname ":calendar :workdays is a set of weekday tokens, #{:mon :tue …}"))
    (when-some [bad (seq (remove (set work-calendar/weekdays) (map name (:workdays cal))))]
      (err sname (str ":calendar :workdays names " (pr-str (first bad))
                      ", which is not one of " work-calendar/weekdays)))
    (when (and (some? (:hours cal)) (nil? (:hours (calendar-answer cal))))
      (err sname ":calendar :hours is {:from \"HH:MM\" :to \"HH:MM\"}"))
    (when-not (coll? (:holidays cal ()))
      (err sname ":calendar :holidays is a vector of dates, \"2027-05-31\" or {:on … :name …}"))
    (doseq [h (:holidays cal)]
      (let [on (if (map? h) (:on h) h)]
        (when-not (and (string? on)
                       (try (LocalDate/parse ^String on) (catch DateTimeParseException _ nil)))
          (err sname (str ":calendar :holidays names " (pr-str h) ", which is not a date")))))))

(defn- principal!
  [sname as]
  (when (some? as)
    (when-not (map? as) (err sname ":as is the principal map {:id … :type … :roles #{…}}"))
    (closed! sname "principal" principal-keys as)
    (when (str/blank? (str (:id as))) (err sname ":as names the principal's :id"))
    (when (and (contains? as :leashed) (not (boolean? (:leashed as))))
      (err sname ":as :leashed is true or false")))
  ;; roles cross the wire and the role guard as STRINGS (guards/role
  ;; reads (contains? (:roles principal) (name role))), so a scenario
  ;; may spell them either way and lands on one
  (let [as (or as {:id "walker" :type :system})]
    (assoc as
           :id (str (:id as))
           :type (or (:type as) :person)
           :roles (into #{} (map name) (:roles as)))))

(defn- event-type
  "An event's type: its own :type, or the one its body carries."
  [e]
  (or (:type e) (get-in e [:body "type"]) (get-in e [:body :type])))

(defn- events!
  [sname evs]
  (when (some? evs)
    (when-not (vector? evs)
      (err sname ":events is a vector of {:source … :type … :body {…}}, replayed in order"))
    (doseq [e evs]
      (when-not (map? e) (err sname "each :events entry is {:source … :type … :body {…}}"))
      (closed! sname "event" event-keys e)
      (let [src (:source e)]
        (when-not (and (or (keyword? src) (string? src)) (not (str/blank? (name src))))
          (err sname "each :events entry names its :source, the integration that sends it")))
      (when-not (map? (:body e {}))
        (err sname "an :events entry's :body is the event's body as a literal map"))
      (let [ty (event-type e)]
        (when-not (and (string? ty) (not (str/blank? ty)))
          (err sname "each :events entry names its :type, or its :body carries one under \"type\""))))))

(defn- effects!
  [sname fx]
  (when (some? fx)
    (when-not (map? fx)
      (err sname (str ":effects maps each power to its fake: {\"tool\" {:answer {…}}},"
                      " {\"tool\" {:fails \"…\"}} or {\"tool\" :unknown}")))
    (doseq [[power fake] fx]
      (when-not (and (string? power) (not (str/blank? power)))
        (err sname ":effects names each power as a string, as an :effect's :power does"))
      (when-not (= :unknown fake)
        (when-not (map? fake)
          (err sname (str ":effects " power " is {:answer {…}}, {:fails \"…\"} or :unknown")))
        (closed! sname "fake" fake-keys fake)
        (when-not (= 1 (count (select-keys fake fake-keys)))
          (err sname (str ":effects " power " is one of {:answer {…}}, {:fails \"…\"} and :unknown")))
        (when (and (contains? fake :answer) (not (map? (:answer fake))))
          (err sname (str ":effects " power " :answer is the call's answer as a literal map")))
        (when (and (contains? fake :fails)
                   (or (not (string? (:fails fake))) (str/blank? (:fails fake))))
          (err sname (str ":effects " power " :fails is the sentence the call failed with")))))))

(def handle-pattern
  "How a scenario names a row it STAGED (waymark-fp62.4.1, the blocker
  waymark-79f recorded). A `:given` row may carry a `:handle`, and any
  string in the scenario may then hold `{given/<handle>}` — the
  conformance runner replaces it with the id the walker minted.

  The sigil is deliberate. A scenario's `:input` is a literal wire
  body and its strings are prose, addresses and tokens; a bare
  `{name}` would make every brace in a sentence a possible typo. Only
  `{given/…}` is a reference, and one that names no `:handle` refuses
  at the def line.

  This is what lets a door that RESOLVES what a body cites be proved
  by a declared scenario at all: before it, `:given` rows were minted
  under fresh ids and no scenario could cite a row that would exist,
  so every citation had to be an invented address the door was
  obliged to accept."
  #"\{given/([A-Za-z0-9_-]+)\}")

(defn handle-refs
  "Every `{given/<handle>}` handle named anywhere inside `x`, as a set
  of strings."
  [x]
  (let [found (volatile! #{})]
    (walk/postwalk
     (fn [v]
       (when (string? v)
         (doseq [[_ h] (re-seq handle-pattern v)]
           (vswap! found conj h)))
       v)
     x)
    @found))

(defn fill-handles
  "`x` with every `{given/<handle>}` replaced by the staged id `ids`
  holds for it. A handle with no id is left exactly as written, so the
  runner's own report shows the unfilled reference rather than an
  address ending in the word nil."
  [x ids]
  (walk/postwalk
   (fn [v]
     (if (string? v)
       (str/replace v handle-pattern
                    (fn [[whole h]] (str (get ids h whole))))
       v))
   x))

(def run-sigil
  "A token minted once per suite run, written into a :given row's
  strings where a fixed value would collide with the last run's row: a
  kind whose :unique group names the field takes one row per value,
  and one engine may run the suite twice (modules_test does). The
  conformance tier replaces it with `run-token`'s answer, the check tier
  with `check-run`."
  "{{run}}")

(def check-run
  "What `{{run}}` reads as in the check tier, which stages nothing."
  "check")

(defn run-token
  "A fresh `{{run}}`: a lowercase letter, then six hex digits, so it
  sits inside a name any spelling rule of letters, digits and
  underscores admits."
  []
  (str "r" (subs (str (random-uuid)) 0 6)))

(defn fill-run
  "`s` with every `{{run}}` in its :given data, :row, :input and
  :history replaced by `token`."
  [s token]
  (let [f #(walk/postwalk (fn [v] (if (string? v) (str/replace v run-sigil token) v)) %)]
    (cond-> s
      (:given s) (update :given (fn [gs] (mapv #(cond-> % (:data %) (update :data f)) gs)))
      (:row s) (update :row f)
      (:input s) (update :input f)
      (:history s) (update :history f))))

(defn- row! [sname label legal row]
  (when (some? row)
    (when-not (map? row) (err sname (str label " is {:state … :data {…}}")))
    (closed! sname label legal row)
    (when-not (keyword? (:state row))
      (err sname (str label " names its :state as a keyword")))
    (when-not (map? (:data row {}))
      (err sname (str label " :data is the row's document as a literal map"))))
  row)

(defn- expect! [sname expect]
  (when-not (map? expect)
    (err sname "a scenario is a verdict: :expect {:allowed true} or {:refused <guard-name>}"))
  (closed! sname "expect" expect-keys expect)
  (let [{:keys [allowed refused because remedies]} expect]
    (when (= (some? allowed) (some? refused))
      (err sname ":expect declares exactly one of :allowed and :refused"))
    (when (and (some? allowed) (not (true? allowed)))
      (err sname ":expect :allowed is true — a false allowance is a refusal, and a refusal names its guard"))
    (when (and (some? refused) (not (keyword? refused)))
      (err sname (str ":expect :refused names the GUARD, not the sentence — a keyword, or "
                      out-of-state " for a row outside the action's :from")))
    (when (and (some? because) (or (not (string? because)) (str/blank? because)))
      (err sname ":expect :because is a substring of the rendered reason"))
    (when (and (some? because) (nil? refused))
      (err sname ":expect :because tightens a refusal; an allowance has no reason to read"))
    (when (and (seq remedies)
               (or (nil? refused) (not (every? keyword? remedies))))
      (err sname ":expect :remedies is a vector of affordance tokens (:kind/action) a refusal offers"))
    expect))

;; a recurrence scenario's verdict (docs/spec-calendar-time.md §6): the
;; occurrence list's first and last dates, its length, or the whole list
(def occurrences-keys [:first :last :count :dates])

(defn- iso-date? [v]
  (and (string? v)
       (some? (try (LocalDate/parse ^String v) (catch DateTimeParseException _ nil)))))

(defn- occurrences-expect! [sname expect]
  (when-not (and (map? expect) (= [:occurrences] (vec (keys expect))) (map? (:occurrences expect)))
    (err sname "a recurrence scenario expects its list: :expect {:occurrences {:first … :last … :count …}}"))
  (let [o (:occurrences expect)]
    (closed! sname "occurrences" occurrences-keys o)
    (when (empty? o)
      (err sname ":expect :occurrences names at least one of :first, :last, :count and :dates"))
    (doseq [k [:first :last]
            :when (and (contains? o k) (not (iso-date? (get o k))))]
      (err sname (str ":occurrences " k " is a date, \"2027-04-06\"")))
    (when (and (contains? o :count) (not (nat-int? (:count o))))
      (err sname ":occurrences :count is how many dates the list holds"))
    (when (and (contains? o :dates) (not (and (vector? (:dates o)) (every? iso-date? (:dates o)))))
      (err sname ":occurrences :dates is the whole list, a vector of dates"))
    expect))

;; ── the ledger a scenario stands on (docs/spec-ledger-money.md §9) ──

;; one row of a rate table a scenario states: the key it answers, the
;; date it takes effect and the rate
(def rate-keys [:key :on :value])

(defn- amount-of
  "A scenario's written amount → the value a balance holds: a whole
  number, a decimal (0.5M), or money written \"18.00 USD\". nil for
  anything else — a float is not an amount."
  [v]
  (cond
    (and (number? v) (not (float? v))) v
    (string? v) (let [[a cur & more] (str/split (str/trim v) #"\s+")]
                  (when (and cur (empty? more)) (money/money a cur)))))

(defn- unit-of [v] (if (money/money? v) (:currency v) :number))

(defn- account-address?
  "[kind \"row-id\" name], or [:house name]: an account as a scenario
  names it."
  [a]
  (and (vector? a)
       (or (and (= 3 (count a)) (keyword? (first a)) (string? (second a))
                (not (str/blank? (second a))) (keyword? (nth a 2)))
           (and (= 2 (count a)) (= :house (first a)) (keyword? (second a))))))

(defn- ledger!
  "The scenario's :postings, :rates and :expect :balances: each posting
  [account amount effective_on] a written amount on a date, one unit to
  an account; each rate row {:key {…} :on date :value v}; each expected
  balance an account the postings state, so no balance is read from
  nothing."
  [sname m]
  (when-some [ps (:postings m)]
    (when-not (vector? ps)
      (err sname ":postings is a vector of [account amount effective_on], seeded before the walk"))
    (doseq [p ps]
      (when-not (and (vector? p) (= 3 (count p)))
        (err sname (str "each :postings entry is [account amount effective_on]; got " (pr-str p))))
      (let [[a amt on] p]
        (when-not (account-address? a)
          (err sname (str ":postings names an account as [kind \"row-id\" name] or [:house name]; got "
                          (pr-str a))))
        (when (nil? (amount-of amt))
          (err sname (str ":postings amount " (pr-str amt)
                          " is a whole number, a decimal (0.5M) or money written \"18.00 USD\"")))
        (when-not (iso-date? on)
          (err sname (str ":postings effective_on " (pr-str on) " is a date, \"2026-10-01\"")))))
    (doseq [[a xs] (group-by first ps)
            :when (< 1 (count (into #{} (map (comp unit-of amount-of second)) xs)))]
      (err sname (str ":postings to " (pr-str a) " mix units; an account is of one unit"))))
  (when-some [rates (:rates m)]
    (when-not (and (map? rates) (every? keyword? (keys rates)))
      (err sname ":rates maps each rate table's name to its rows, {:sales_tax [{:key {…} :on \"2026-01-01\" :value …}]}"))
    (doseq [[table rows] rates
            :let [where (str ":rates " (name table))]]
      (when-not (vector? rows)
        (err sname (str where " is a vector of {:key {…} :on \"date\" :value …}")))
      (doseq [r rows]
        (when-not (map? r) (err sname (str where " row is a map; got " (pr-str r))))
        (closed! sname "rate row" rate-keys r)
        (when-not (and (map? (:key r)) (seq (:key r)) (every? keyword? (keys (:key r))))
          (err sname (str where " :key maps each key field to its value, {:state \"UT\"}")))
        (when-not (iso-date? (:on r))
          (err sname (str where " :on is the date the rate takes effect, \"2026-01-01\"")))
        (when-not (contains? r :value)
          (err sname (str where " row names its :value"))))))
  (when-some [want (get-in m [:expect :balances])]
    (when-not (and (map? want) (seq want))
      (err sname ":expect :balances maps accounts to the balance each holds after the walk"))
    (let [stated (into #{} (map first) (:postings m))]
      (doseq [[a v] want]
        (when-not (account-address? a)
          (err sname (str ":expect :balances names an account as [kind \"row-id\" name] or [:house name]; got "
                          (pr-str a))))
        (when-not (contains? stated a)
          (err sname (str ":expect :balances names " (pr-str a) ", which :postings does not state"
                          " — a balance is expected from one the scenario stands on")))
        (when (nil? (amount-of v))
          (err sname (str ":expect :balances " (pr-str a)
                          " is a whole number, a decimal or money written \"0.00 USD\"")))))))

(defn scenario
  "Validate and default one scenario, returning the PLAIN map — the
  very value the inline spelling would put in :scenarios, so the
  def'd and inline spellings are one value (and, since :scenarios is
  outside the fingerprint's whitelist, one hash: none).

  Construction validation only. Whether the kind is served, whether
  the attempt names a real action, whether the expected guard is
  actually on it — those need the assembled declaration, so they are
  the judge's questions, exactly as defaction leaves cross-referencing
  to defresource."
  [sname sentence m]
  (when-not (keyword? sname) (err :scenario "a scenario is named by its def"))
  (when (or (not (string? sentence)) (str/blank? sentence))
    (err sname (str "the sentence comes first, and it IS the violation string — "
                    "(defscenario " (name sname) " \"…\" {…})")))
  (when-not (map? m) (err sname "a scenario is a map of the closed scenario keys"))
  (closed! sname "scenario" scenario-keys m)
  (when-not (keyword? (:kind m)) (err sname ":kind names the resource this scenario judges"))
  (if (some? (:recurs m))
    (do (when-not (keyword? (:recurs m))
          (err sname ":recurs names the rule whose dates this scenario lists, by its :name"))
        (when (some? (:attempt m))
          (err sname "a recurrence scenario lists a rule's dates and attempts no door — drop :attempt")))
    (when-not (keyword? (:attempt m))
      (err sname ":attempt names the action, or the create door by its own name")))
  (row! sname ":row" row-keys (:row m))
  (when (some? (:given m))
    (when-not (vector? (:given m))
      (err sname ":given is a vector of rows that must genuinely exist"))
    (doseq [gv (:given m)]
      (when-not (keyword? (:kind gv)) (err sname "each :given row names its :kind"))
      (when (and (some? (:handle gv)) (not (keyword? (:handle gv))))
        (err sname "a :given row's :handle is a keyword"))
      (when (and (some? (:reuse gv)) (not (boolean? (:reuse gv))))
        (err sname "a :given row's :reuse is true or false"))
      (row! sname ":given row" given-keys gv))
    ;; the handles, in staging order: a row may name only what is
    ;; already standing when it is staged, and two rows may not answer
    ;; to one name
    (reduce
     (fn [seen gv]
       (when-some [bad (seq (sort (remove seen (handle-refs (:data gv)))))]
         (err sname (str "a :given row names {given/" (first bad)
                         "}, which is not staged before it")))
       (let [h (some-> (:handle gv) name)]
         (when (and h (contains? seen h))
           (err sname (str "two :given rows answer to the handle " h)))
         (cond-> seen h (conj h))))
     #{} (:given m)))
  ;; every handle the scenario NAMES is a handle it staged
  (let [staged (into #{} (keep #(some-> (:handle %) name)) (:given m))
        named (-> (handle-refs (:input m))
                  (into (handle-refs (get-in m [:row :data])))
                  (into (handle-refs (:postings m)))
                  (into (handle-refs (get-in m [:expect :balances])))
                  (into (mapcat #(handle-refs (:input %))) (filter map? (:history m))))]
    (when-some [bad (seq (sort (remove staged named)))]
      (err sname (str "names {given/" (first bad) "}, which no :given row"
                      " declares as its :handle"))))
  (when (some? (:history m))
    (when-not (vector? (:history m))
      (err sname ":history is a vector of {:action … :as …}, the row's log oldest first"))
    (doseq [h (:history m)]
      (when-not (map? h) (err sname "each :history entry is {:action … :as \"principal-id\"}"))
      (closed! sname "history" history-keys h)
      (when-not (keyword? (:action h))
        (err sname "each :history entry names its :action as a keyword"))
      (when (str/blank? (str (:as h)))
        (err sname "each :history entry names who acted, :as \"principal-id\""))
      (when (and (some? (:input h)) (not (map? (:input h))))
        (err sname "a :history entry's :input is its door's input body as a literal map"))))
  ;; how the conformance tier meets :history: :stage (the default) walks
  ;; it through the real doors, :refuse declines to judge the scenario
  (when (and (some? (:history-mode m)) (not (#{:stage :refuse} (:history-mode m))))
    (err sname ":history-mode is :stage (the default) or :refuse"))
  (when (and (some? (:input m)) (not (map? (:input m))))
    (err sname ":input is the action's input body as a literal map"))
  (instant! sname (:at m))
  (calendar! sname (:calendar m))
  (events! sname (:events m))
  (effects! sname (:effects m))
  (ledger! sname m)
  (if (some? (:recurs m))
    (occurrences-expect! sname (:expect m))
    (expect! sname (:expect m)))
  (cond-> (assoc m
                 :name sname
                 :sentence sentence
                 :given (vec (:given m))
                 :as (principal! sname (:as m))
                 :expect (:expect m))
    ;; only when spoken, so a scenario without one normalizes as before
    (some? (:history m)) (assoc :history (vec (:history m)))))

;; ── the attempted door ──────────────────────────────────────────────

(defn create-door?
  "Is this attempt the kind's create door? :create-action-names is the
  declaration (normalized to #{:create} when unspoken), never a name
  match."
  [rdef attempt]
  (boolean (contains? (or (:create-action-names rdef) #{:create}) attempt)))

(defn extra-create
  "The extra create door (:creates) this attempt names, or nil: a door
  the kind declares beside its ordinary create, judged by its own
  :guards over its own :input."
  [rdef attempt]
  (some #(when (= attempt (:action %)) %) (:creates rdef)))

(defn attempted
  "What the scenario attempts, resolved against the declaration:
  {:door :create :guards […]} | {:door :action :action a :guards […]}
  | {:missing \"sentence\"}. A :creates door is a create, carrying its
  own :input beside its guards."
  [rdef s]
  (let [a (:attempt s)]
    (cond
      (nil? rdef)
      {:missing (str "names kind " (:kind s) ", which nothing here declares")}

      (not= (:kind rdef) (:kind s))
      {:missing (str "declares :kind " (:kind s) " on " (:kind rdef)
                     " — a scenario lives on the kind whose action it attempts")}

      (create-door? rdef a)
      {:door :create :guards (vec (:create-guards rdef))}

      (some? (extra-create rdef a))
      (let [c (extra-create rdef a)]
        {:door :create :guards (vec (:guards c)) :input (:input c)})

      (contains? (:actions rdef) a)
      (let [d (assoc (get-in rdef [:actions a]) :name a)]
        {:door :action :action d :guards (vec (:guards d))})

      :else
      {:missing (str "attempts " a ", which " (name (:kind s))
                     " does not declare")})))

(defn- guard-names
  "Every name the denier of this guard tree could wear: the composite
  itself (an :any denies as itself — an OR advertises nothing) and
  every descendant leaf."
  [gd]
  (into #{(:name gd)}
        (mapcat guard-names)
        (concat (:all gd) (:any gd))))

;; ── the tier, read off declarations ─────────────────────────────────

(defn- declarative?
  "A guard whose verdict is a :when tree and whose :reads that tree
  derived: every read it makes is a follow, an exists or a did the
  scenario itself can answer. A hand-written :reads is the author's
  word over the tree, and stays as asserted."
  [gd]
  (boolean (and (:when gd) (not (:reads-written gd)))))

(defn- given-served?
  "A code guard that declared `:check-tier :given`: its kind reads are
  safe to answer from the scenario's :given rows, as a declarative
  leaf's are. guards/guard admits the key only where every read is a
  kind, so the default — a code guard's kind reads defer its door —
  holds for every guard that says nothing."
  [gd]
  (= :given (:check-tier gd)))

(defn- unserved-reads
  "What this guard tree reads that the check tier cannot answer: a
  declarative leaf nothing, any other leaf its :reads beyond
  offline-reads, a composite what its parts leave."
  [gd]
  (cond
    (declarative? gd) []
    (given-served? gd) []
    (or (seq (:all gd)) (seq (:any gd))) (mapcat unserved-reads (concat (:all gd) (:any gd)))
    :else (remove offline-reads (:reads gd))))

(defn- reads-related?
  "Does some declarative leaf of this tree read another row or the
  log — the reads the scenario's :given and :history are there to
  serve?"
  [gd]
  (boolean
   (or (and (or (declarative? gd) (given-served? gd))
            (some (complement offline-reads) (:reads gd)))
       (some reads-related? (concat (:all gd) (:any gd))))))

(defn- entries-for
  "The :on-event entries of this kind that one scenario event runs: its
  source and type, and a door to run — a :via :inbox entry makes a row
  of another kind, which no check-tier walk follows."
  [rdef e]
  (filter #(and (= (name (:source %)) (name (:source e)))
                (= (:type %) (event-type e))
                (not= :inbox (:via %)))
          (:on-event rdef)))

(defn- event-guards
  "The guards of every door the scenario's :events can run: judged
  offline as the attempt's are, so they choose the tier with it."
  [rdef s]
  (into [] (comp (mapcat #(entries-for rdef %))
                 (mapcat #(get-in rdef [:actions (:door %) :guards])))
        (:events s)))

(defn check-tier?
  "Does this scenario keep check.clj's no-storage posture? Two
  declared conditions, both read and neither sniffed: every guard on
  the attempted action declares :reads ⊆ offline-reads or is
  declarative, and it stages :given rows only when such a guard reads
  related rows — a :given row nothing declarative reads is a row to
  stage for real."
  [rdef s]
  ;; a recurrence scenario lists a pure function's dates: no door, no
  ;; reads, always here (docs/spec-calendar-time.md §6)
  (or (some? (:recurs s))
      (let [{:keys [guards missing]} (attempted rdef s)
            guards (into (vec guards) (event-guards rdef s))]
        (boolean (and (nil? missing)
                      (empty? (mapcat unserved-reads guards))
                      (or (empty? (:given s)) (some reads-related? guards)))))))

(defn deferral-reason
  "Why a scenario waits for the suite, in the author's own terms —
  printed by check so nobody has to guess which half of the tier rule
  they tripped."
  [rdef s]
  (when-not (check-tier? rdef s)
    (let [{:keys [guards missing]} (attempted rdef s)
          guards (into (vec guards) (event-guards rdef s))]
      (cond
        missing nil
        (and (seq (:given s)) (not-any? reads-related? guards))
        (str "stages " (count (:given s)) " given row"
             (when (not= 1 (count (:given s))) "s"))
        :else
        (let [beyond (into (sorted-set) (mapcat unserved-reads) guards)]
          (str "reads " (str/join ", " (map str beyond))))))))

;; ── the check-tier judge ────────────────────────────────────────────

(defn verdict
  "The enforcement loop's own grading: guards in declaration order, a
  warning collects (it is acknowledgable, not a refusal), the first
  refusing deny is the verdict — the same shape invoke's run-guards
  and create-guard-pass share, so the three judgments cannot drift.

  Public because a scenario is not the only caller that wants \"what
  does THIS guard tree say about THIS row\". The law sweep
  (waymark10.server.law-sweep) hands it a guard vector rebuilt from a
  stored revision and then the resident one, over the same row and
  the same ctx, and reads the difference: one evaluator, two callers,
  and a sweep finding's `because` is a scenario's refusal rendered by
  this very line. The ctx is the caller's to build — storage-free
  here (offline-ctx), hooked and live there."
  [guards row inp ctx]
  (reduce
   (fn [acc gd]
     (let [[v d] (g/evaluate gd row inp ctx)]
       (if-not (t/deny? v)
         acc
         (if (= :warning (:severity d))
           (update acc :warned conj (:name d))
           (reduced (assoc acc
                           :refused (:name d)
                           :reason (g/render-reason d v row)
                           :remedies (g/remedy-doors d)
                           :hidden (boolean (:hide d))))))))
   {:warned []}
   guards))

(defn- given-id
  "The id the check tier gives the n-th :given row: its handle's, so
  `{given/<handle>}` names it, or its place for a row with none."
  [n gv]
  (if-some [h (:handle gv)]
    (str "given:" (name h))
    (str "given:#" n)))

(defn- given-ids
  "handle → id over the :given rows, as fill-handles takes it."
  [s]
  (into {}
        (keep-indexed (fn [n gv] (when-some [h (:handle gv)] [(name h) (given-id n gv)])))
        (:given s)))

(defn- given-rows
  "The :given rows as storage would hand them back — kind, id, state,
  data — under the ids given-id mints, their own handles filled."
  [s]
  (let [ids (given-ids s)]
    (into []
          (map-indexed (fn [n gv]
                         {:kind (:kind gv)
                          :id (given-id n gv)
                          :state (:state gv)
                          :data (fill-handles (:data gv {}) ids)}))
          (:given s))))

(defn- holds?
  "One where condition over one in-memory row, as the store asks it:
  :state and :id are the row's own, any other field a data field; a
  missing value matches nothing, numbers order as numbers and the
  rest as text."
  [row {:keys [field op value values]}]
  (let [text #(if (keyword? %) (name %) (str %))
        v (case field
            :state (:state row)
            :id (:id row)
            (get-in row [:data field]))]
    (and (some? v)
         (case op
           :in (contains? (into #{} (map text) values) (text v))
           :not-in (not (contains? (into #{} (map text) values) (text v)))
           (let [c (if (and (number? v) (number? value))
                     (compare (double v) (double value))
                     (compare (text v) (text value)))]
             (case op
               := (zero? c)
               :not= (not (zero? c))
               :< (neg? c)
               :<= (not (pos? c))
               :> (pos? c)
               :>= (not (neg? c))
               false))))))

(defn- scenario-reads
  "The read hooks served from the scenario itself (docs/spec-related-
  row-law.md §5): :read, :find, :rows-matching and :count-matching
  over its :given rows, :actor-of over its :history — the world is
  exactly what the scenario declares, so no :given is no rows and no
  :history an empty log."
  [s]
  (let [rows (given-rows s)
        of-kind (fn [kind] (filter #(= (keyword kind) (keyword (:kind %))) rows))
        matching (fn [kind conds] (filter (fn [r] (every? #(holds? r %) conds)) (of-kind kind)))
        log (reverse (:history s))]
    {:read (fn [kind id] (some #(when (= (str id) (:id %)) %) (of-kind kind)))
     :find (fn [kind where & [opts]]
             (cond->> (filter (fn [r] (every? (fn [[f v]] (holds? r {:field f :op := :value v})) where))
                              (of-kind kind))
               (:limit opts) (take (:limit opts))
               true vec))
     :rows-matching (fn [kind conds limit]
                      (cond->> (map :id (matching kind conds))
                        limit (take limit)
                        true vec))
     :count-matching (fn [kind conds] (count (matching kind conds)))
     :actor-of (fn [_row door & every]
                 (apply g/actors-of
                        (into []
                              (comp (filter #(= (name door) (name (:action %))))
                                    (map (fn [h]
                                           {:action door
                                            :actor (cond-> {:id (str (:as h))}
                                                     (:acted-by h) (assoc :acted_by (str (:acted-by h))))})))
                              log)
                        door every))}))

(defn- scenario-ledger
  "The ledger hooks served from the scenario itself (docs/spec-ledger-
  money.md §9): :balance sums its :postings to one account, and
  :balance-over those effective in [from, until) (step 9b); :rate-of
  answers its :rates — the row whose :key is the one asked, latest on
  or before the date. An account the postings never name, a period
  none of them falls in, and a rate no row answers are nil, unknown to
  the law: never zero."
  [s]
  (let [text #(if (keyword? %) (name %) (str %))
        key-of (fn [m] (into {} (map (fn [[k v]] [(keyword k) (text v)])) m))
        by-account (group-by first (:postings s))
        of (fn [kind id account]
             (get by-account (if (some? id) [kind (str id) account] [kind account])))
        summed #(when (seq %) (reduce money/plus (map (comp amount-of second) %)))]
    (cond-> {}
      (some? (:postings s))
      (assoc :balance (fn [kind id account] (summed (of kind id account)))
             :balance-over (fn [kind id account ^LocalDate from ^LocalDate until]
                             (summed (filter (fn [[_ _ on]]
                                               (let [d (LocalDate/parse ^String on)]
                                                 (and (not (.isBefore d from)) (.isBefore d until))))
                                             (of kind id account)))))
      (some? (:rates s))
      (assoc :rate-of (fn [table key-vals ^LocalDate on]
                        (let [want (key-of key-vals)]
                          (->> (get (:rates s) (keyword table))
                               (filter #(and (= want (key-of (:key %)))
                                             (not (.isAfter (LocalDate/parse ^String (:on %)) on))))
                               (sort-by :on)
                               last
                               :value)))))))

(defn- offline-ctx
  "Everything a check-tier guard is allowed to read, and nothing
  else. :invoke and not :probe because a scenario SUPPLIES its input
  and must not collect the probe's pending-input short-circuit; :rate
  nil because an engine without a rate coordinator is what
  guards/rate-limit already allows for. The read hooks are lent only
  when `related?` — a declarative or :check-tier :given guard on the
  door reads other rows or the log — and then from the scenario
  (scenario-reads); otherwise
  there are none, and a code guard that reaches for one gets the
  offline answer it declared it could give.

  The :calendar hook is lent when the scenario declares a :calendar,
  and answers that one calendar for every ref: a scenario names one
  house. Without one there is no hook, and the engine makes up no
  plausible calendar — structural has already refused the law that
  reads it.

  The :effects the scenario declares ride along as the fakes the walk
  answers each effect call from (docs/spec-integrations.md §6), and its
  :events are replayed through this same ctx, each door judged as the
  integration that sent it. No guard reads the fakes.

  Its :postings and :rates answer (balance …) and (rate …)
  (scenario-ledger), whatever else the door reads."
  [s related?]
  (cond-> {:mode :invoke
           :now (some-> ^String (:at s) Instant/parse)
           :principal (:as s)
           :services {:features []}
           :rate nil}
    (:calendar s) (assoc :calendar (let [c (calendar-answer (:calendar s))]
                                     (fn ([] c) ([_ref] c))))
    (some? (:effects s)) (assoc :effects (:effects s))
    related? (merge (scenario-reads s))
    true (merge (scenario-ledger s))))

(defn- reads-calendar?
  "Does this guard tree read the house's calendar?"
  [gd]
  (boolean (or (contains? (set (:reads gd)) :calendar)
               (some reads-calendar? (concat (:all gd) (:any gd))))))

(defn- ledger-reads
  "What this guard tree's :when trees read of the ledger, as expr/info
  says it: {:balances #{kinds} :rates #{tables}}."
  [gd]
  (reduce #(merge-with into %1 (ledger-reads %2))
          (select-keys (some-> (:when gd) expr/info) [:balances :rates])
          (concat (:all gd) (:any gd))))

(defn- scenario-row
  "The row as a literal document, wearing the shape storage would
  have given it — a kind, an id, a state, a data map."
  [s]
  (when-some [row (:row s)]
    {:kind (:kind s)
     :id (str "scenario:" (name (:name s)))
     :state (:state row)
     :data (:data row {})}))

(defn- structural
  "What the assembled declaration can answer and the def line could
  not: the door exists, the row suits the door, the expected guard is
  a guard this action actually carries, the clock the law reads is a
  clock the scenario declared. Authoring faults, not broken law —
  they read as themselves rather than as the household's sentence."
  [rdef s]
  (let [{:keys [door guards missing]} (attempted rdef s)
        refused (get-in s [:expect :refused])]
    (or
     (when missing [(str (name (:name s)) " " missing)])
     (not-empty
      (into []
            (comp (remove nil?)
                  (map #(str (name (:name s)) " [" (name (:kind s)) "/"
                             (name (:attempt s)) "] " %)))
            [(when (and (= :create door) (:row s))
               "attempts the create door with a :row — no instance exists yet")
             (when (and (= :action door) (nil? (:row s)))
               (str "attempts " (:attempt s) " with no :row — an action judges an instance"))
             (when (and (= :action door) (:row s)
                        (not (contains? (set (:states rdef)) (get-in s [:row :state]))))
               (str ":row is in state " (get-in s [:row :state])
                    ", which " (name (:kind s)) " does not declare"))
             (when (and refused (not= out-of-state refused)
                        (not (contains? (into #{} (mapcat guard-names) guards) refused)))
               (str ":expect :refused names " refused
                    ", which is not a guard on " (:attempt s)
                    " — the guards there are "
                    (pr-str (vec (sort (map :name guards))))))
             (when (and (= :create door) (= out-of-state refused))
               (str ":expect :refused " out-of-state
                    " — the create door has no state to be out of"))
             (when (and (nil? (:at s))
                        (some #(contains? (set (:reads %)) :now) guards))
               (str "declares no :at, but " (:attempt s)
                    " consults the clock — a scenario over a timed law names its moment"))
             (when (and (nil? (:calendar s))
                        (some reads-calendar? guards))
               (str "declares no :calendar, but " (:attempt s)
                    " reads the house's calendar — a scenario over calendar law names its calendar"))
             ;; a law that reads the ledger is judged over the ledger the
             ;; scenario states, never over a zero it made up (§9)
             (when (and (nil? (:postings s))
                        (some (comp seq :balances ledger-reads) guards))
               (str "states no :postings, but " (:attempt s)
                    " reads a balance — a scenario over ledger law states the postings it stands on"))
             (when (and (nil? (:rates s))
                        (some (comp seq :rates ledger-reads) guards))
               (str "states no :rates, but " (:attempt s)
                    " reads a rate table — a scenario over rate law states the rates in effect"))
             (when (and (= :create door) (seq (get-in s [:expect :balances])))
               ":expect :balances judges an action's postings; a create is judged by its verdict")])))))

(defn- clause
  "What actually happened, when it is not what the scenario said
  would. nil when the law kept its promise."
  [s got]
  (let [{:keys [allowed refused because remedies]} (:expect s)]
    (if-some [u (:unreadable got)]
      ;; the door answered something no verdict can be read off — a
      ;; staging failure, a concealment, an unexpected status. The
      ;; sentence still leads; the clause says what came back
      u
      (if allowed
        (when-some [r (:refused got)]
          (str "the law refused it — " r ": " (:reason got)))
        (cond
          (nil? (:refused got))
          (if (seq (:warned got))
            (str "the law allowed it, warning only ("
                 (str/join ", " (map str (:warned got))) ")")
            "the law allowed it")

          (not= refused (:refused got))
          (str "a different wall: refused by " (:refused got)
               ", not " refused)

          (and because (not (str/includes? (str (:reason got)) because)))
          (str "refused by " refused ", but the reason reads \""
               (:reason got) "\"")

          (and (seq remedies)
               (not (every? (set (:remedies got)) remedies)))
          (str "refused by " refused ", offering "
               (if (seq (:remedies got)) (pr-str (vec (:remedies got))) "no way out")
               " where the scenario expects " (pr-str (vec remedies))))))))

(defn violation
  "One scenario's failure, sentence-first — the household's own words,
  with the diagnosis riding after as a clause. nil when the law kept
  the promise. Public because BOTH tiers narrate the same way: the
  conformance obligation hands it the verdict it read off the wire."
  [s got]
  (when-some [c (clause s got)]
    (str (name (:name s)) " [" (name (:kind s)) "/" (name (:attempt s)) "] "
         (str/replace (str/trim (:sentence s)) #"\s+" " ")
         " — " c)))

;; ── the walk: events replayed, effects answered by their fakes ──────

(defn- known [x scope]
  (let [v (expr/evaluate x scope)]
    (when-not (expr/unknown? v) v)))

(defn- call
  "One effect call the walk makes for door `aname`, over its row as the
  door left it, answered by the scenario's fake for its power — and the
  row landed as the runner lands it: `done` writes the answer and moves
  to `:lands`, `failed` holds the row in `:fails-to`, `unknown` leaves
  it asked. A compensation's answer writes nothing. No fake for the
  power is a fault, not a default. → the walk"
  [w ctx aname eff inp {:keys [key undo]}]
  (let [row (:row w)
        asked (effect/ask eff row inp (:now ctx))
        fake (get (:effects ctx) (:power eff))
        ending (cond (= :unknown fake) :unknown
                     (contains? fake :answer) :done
                     (contains? fake :fails) :failed)]
    (if (nil? ending)
      (update w :faults conj
              (str "reaches " (name aname) ", which calls " (:power eff)
                   ", and :effects names no fake for it — a scenario over effect law"
                   " answers each call, as one over a timed law names its moment"))
      (-> w
          (update :calls conj (cond-> {:door (name aname) :power (:power eff)
                                       :key (or key (:key asked)) :input (:input asked)
                                       :ending ending}
                                (= :failed ending) (assoc :reason (:fails fake))))
          (assoc :row (case ending
                        :done (if undo
                                row
                                (-> row
                                    (update :data merge (effect/writes eff (:answer fake)))
                                    (assoc :state (or (:lands eff) (:state row)))))
                        :failed (assoc row :state (:fails-to eff))
                        :unknown row))))))

(defn- unwind
  "The compensations door `a` asks for (§ 5), as invoke's unwind! keeps
  them: for each :unwinds entry whose :when holds over the row as the
  door left it, each `done` call of its effect door, newest first, undone
  once under its undo: key. → the walk"
  [w rdef ctx a]
  (let [row (:row w)
        undone (distinct
                (for [u (:unwinds a)
                      :when (effect/unwinds? u row (:now ctx))
                      c (:calls w)
                      :when (and (= :done (:ending c)) (= (name (:effect u)) (:door c)))]
                  c))]
    (reduce (fn [w c]
              (let [[door eff] (effect/compensation rdef (keyword (:door c)))
                    k (effect/undo-key (:key c))]
                (if (some #(= k (:key %)) (:calls w))
                  w
                  (call w ctx door eff {} {:key k :undo true}))))
            w (reverse undone))))

(defn- after
  "The row as door `aname` leaves it once allowed, as invoke's finish!
  leaves it: its handler run, its :to, its effect's call answered, and
  the compensations it unwinds — an effect door's when its call lands.
  → the walk"
  [w rdef ctx aname inp]
  (let [a (get-in rdef [:actions aname])
        row (:row w)
        handled (if-some [h (:handler a)] (h row inp ctx) row)
        w (assoc w :row (assoc handled :state (or (:to a) (:state row))))]
    (if-some [eff (:effect a)]
      (let [n (count (:calls w))
            w (call w ctx aname eff inp {})]
        (cond-> w
          (and (:unwinds a) (< n (count (:calls w))) (= :done (:ending (peek (:calls w)))))
          (unwind rdef ctx a)))
      (cond-> w (:unwinds a) (unwind rdef ctx a)))))

(defn- matches?
  "Does an entry's :match name this row? A value the event does not
  carry matches nothing, and no row is no match."
  [row entry scope]
  (and (some? row)
       (every? (fn [[f x]]
                 (let [v (known x scope)]
                   (and (some? v) (holds? row {:field f :op := :value v}))))
               (:match entry))))

(defn- replay
  "The scenario's :events replayed in order over its row (§ 6): each runs
  the pipeline from step 2 — the scenario is the source, so there is no
  signature — and its door is judged as the integration that sent it.
  An event that does not move the row is a fault that says how its
  receipt would end. → the walk"
  [w rdef ctx events]
  (reduce
   (fn [w e]
     (let [scope {:event (:body e {}) :now (:now ctx)}
           label (str (name (:source e)) " " (event-type e))
           es (entries-for rdef e)
           hit (first (filter #(matches? (:row w) % scope) es))]
       (cond
         (empty? es)
         (reduced (update w :faults conj (str "replays " label ", which no :on-event of "
                                              (name (:kind rdef)) " names — the receipt lands ignored")))
         (nil? hit)
         (reduced (update w :faults conj (str "replays " label ", which matches no row — the receipt lands unmatched")))
         :else
         (let [door (:door hit)
               a (get-in rdef [:actions door])
               row (:row w)
               inp (into {} (keep (fn [[f x]] (when-some [v (known x scope)] [f v]))) (:input hit))
               ectx (assoc ctx :principal {:id (str "integration:" (name (:source e)))
                                           :type :agent :roles #{}})]
           (if-not (contains? (set (:from a)) (:state row))
             (reduced (update w :faults conj (str "replays " label ", and " (name door)
                                                  " is not open in state " (name (:state row))
                                                  " — the receipt lands refused")))
             (let [v (verdict (:guards a) row inp ectx)]
               (if-some [r (:refused v)]
                 (reduced (update w :faults conj (str "replays " label ", and " (name door)
                                                      " refused it — " r ": " (:reason v)
                                                      " — the receipt lands refused")))
                 (let [w (after w rdef ectx door inp)]
                   (cond-> w (seq (:faults w)) reduced)))))))))
   w events))

(defn- prepared
  "The scenario as the check tier judges it: its run token and its
  handles filled, a birth's input its declared defaults."
  [rdef s]
  (let [s (fill-run s check-run)
        {:keys [door] door-input :input} (attempted rdef s)
        ;; a {given/<handle>} names the id the check tier gave it
        ids (given-ids s)
        s (cond-> s
            (seq ids) (-> (update :row fill-handles ids)
                          (update :input fill-handles ids)
                          (cond-> (:postings s) (update :postings fill-handles ids)
                                  (get-in s [:expect :balances])
                                  (update-in [:expect :balances] fill-handles ids))))]
    ;; a birth's guards read the input the door hands them: its
    ;; declared defaults already filled
    (cond-> s
      (= :create door)
      (update :input #(schema/apply-defaults
                       (or door-input (:create-schema rdef) (:schema rdef))
                       (or % {}))))))

(defn- walk
  "One prepared door scenario walked at the check tier: its :events
  replayed over its row, then its attempt judged on the row they left
  and, allowed, walked on as invoke walks it, so the effect calls it
  makes are answered too. → {:row :calls :faults :got}"
  [rdef s]
  (let [{:keys [door action guards]} (attempted rdef s)
        ctx (offline-ctx s (some reads-related? (into (vec guards) (event-guards rdef s))))
        w (replay {:row (scenario-row s) :calls [] :faults []} rdef ctx (:events s))
        row (:row w)]
    (cond
      (seq (:faults w)) w

      (and (= :action door)
           (not (contains? (set (:from action)) (:state row))))
      ;; the machine's own refusal, before any guard is consulted — the
      ;; one verdict with no guard behind it
      (let [states (sort (:from action))]
        (assoc w :got {:refused out-of-state
                       :reason (str "Available in state(s) "
                                    (str/join ", " (map summary/state-label states))
                                    "; the resource is "
                                    (summary/state-label (:state row)) ".")
                       :remedies []}))

      :else
      (let [got (verdict guards row (:input s) ctx)]
        (cond-> (assoc w :got got)
          (and (= :action door) (nil? (:refused got))
               (or (:effect action) (:unwinds action)))
          (after rdef ctx (:name action) (:input s)))))))

(defn effect-calls
  "The effect calls one check-tier door scenario makes, in order — its
  events' doors' and its attempt's, each answered by its fake:
  [{:door :power :key :input :ending}], a failed one with its :reason.
  What a test asserts \"one label, then one void\" over. nil for a
  scenario with authoring faults."
  [rdef s]
  (when-not (or (some? (:recurs s)) (structural rdef s))
    (:calls (walk rdef (prepared rdef s)))))

(defn- balances-clause
  "Where the balances the scenario expects differ from the ones its walk
  leaves — what its :postings stated, plus what the attempted door posts
  once allowed — or nil when each holds what was said."
  [rdef s {:keys [row got]}]
  (when-some [want (not-empty (get-in s [:expect :balances]))]
    (let [{:keys [door action]} (attempted rdef s)
          seeded (:balance (scenario-ledger s))
          shown #(if (money/money? %) (str (money/amount-text %) " " (:currency %)) (str %))
          posted (try (when (and (= :action door) (nil? (:refused got)))
                        (posts/entries (:posts action)
                                       {:data (:data row) :input (:input s)
                                        :now (some-> ^String (:at s) Instant/parse)
                                        :self (:id row)}))
                      (catch clojure.lang.ExceptionInfo e
                        (if (:posts-refused (ex-data e)) (ex-message e) (throw e))))]
      (if (string? posted)
        (str "its door's postings cannot be read: " posted)
        (let [off (for [[a v] (sort-by (comp pr-str key) want)
                        :let [[k r n] (if (= 3 (count a)) a [(first a) nil (second a)])
                              moved (keep #(when (and (= k (:account-kind %)) (= r (:account-row %))
                                                      (= n (:account-name %)))
                                             (:amount %))
                                          posted)
                              landed (reduce money/plus (seeded k r n) moved)
                              expected (amount-of v)]
                        :when (not (if (money/money? landed)
                                     (and (money/money? expected)
                                          (= (:currency landed) (:currency expected))
                                          (zero? (compare (:amount landed) (:amount expected))))
                                     (and (number? expected) (== landed expected))))]
                    (str (pr-str a) " holds " (shown landed) ", not " (shown expected)))]
          (when (seq off)
            (str "the balances answer " (str/join "; " off))))))))

(defn- judge-door
  "One door scenario judged: its authoring faults, else its verdict."
  [rdef s]
  (or (structural rdef s)
      (let [s (prepared rdef s)
            {:keys [faults got] :as w} (walk rdef s)]
        (if (seq faults)
          (mapv #(str (name (:name s)) " [" (name (:kind s)) "/" (name (:attempt s)) "] " %) faults)
          (vec (remove nil? [(violation s got)
                             (some->> (balances-clause rdef s w) (hash-map :unreadable) (violation s))]))))))

(defn- judge-recurrence
  "One recurrence scenario judged: the rule's occurrences for its :row
  under its :calendar, against :expect :occurrences — sentence first,
  the dates that differ after."
  [rdef s]
  (let [head (str (name (:name s)) " [" (name (:kind s)) "/recurs " (name (:recurs s)) "] ")
        rule (some #(when (= (:recurs s) (:name %)) %) (:recurs rdef))]
    (if (nil? rule)
      [(str head ":recurs names " (:recurs s) ", which is no rule of " (name (:kind s))
            " — its rules are " (pr-str (vec (sort (map :name (:recurs rdef))))))]
      (let [got (recurs/occurrences rule (get-in s [:row :data] {})
                                    (some-> (:calendar s) calendar-answer))
            seen {:first (some-> (first got) str)
                  :last (some-> (peek got) str)
                  :count (count got)
                  :dates (mapv str got)}
            want (get-in s [:expect :occurrences])
            off (for [k occurrences-keys
                      :when (and (contains? want k) (not= (get want k) (get seen k)))]
                  (str (name k) " " (pr-str (get seen k)) ", not " (pr-str (get want k))))]
        (if (empty? off)
          []
          [(str head (str/replace (str/trim (:sentence s)) #"\s+" " ")
                " — the occurrences answer " (str/join "; " off))])))))

(defn judge
  "Judge one CHECK-TIER scenario against its declaration, with no
  storage of any kind → a vector of violation strings (empty when the
  law kept its promise). The tier is the caller's question; this
  judges what it is handed."
  [rdef s]
  (if (some? (:recurs s))
    (judge-recurrence rdef s)
    (judge-door rdef s)))

;; ── the report both callers print ───────────────────────────────────

(defn report
  "One kind's scenarios, split by tier and judged where they can be
  judged for free: {:total n :checked n :deferred [{:name … :why …}]
  :violations [\"…\"]}. check.clj prints it; the suite pays the
  deferred half."
  [rdef]
  (let [ss (vec (:scenarios rdef))
        {check-tier true deferred false} (group-by #(check-tier? rdef %) ss)]
    {:total (count ss)
     :checked (count check-tier)
     :deferred (mapv (fn [s] {:name (:name s) :why (deferral-reason rdef s)}) deferred)
     :violations (into [] (mapcat #(judge rdef %)) check-tier)}))

(defn coverage
  "How much of a kind's refusing law any scenario names: [named
  total] over the refusing guard leaves of every action, the create
  door and each extra create door under :creates. Counted, never enforced — a usability warning here
  would fire on every action in the tree on the day it landed, and a
  warning nobody can clear is a warning nobody reads."
  [rdef]
  (let [refusing (fn refusing [gd]
                   (into (if (= :warning (:severity gd)) #{} #{(:name gd)})
                         (mapcat refusing)
                         (concat (:all gd) (:any gd))))
        walls (into #{}
                    (comp cat (mapcat refusing) (remove nil?))
                    (cons (:create-guards rdef)
                          (concat (map :guards (:creates rdef))
                                  (map :guards (vals (:actions rdef))))))
        named (into #{} (keep #(get-in % [:expect :refused])) (:scenarios rdef))]
    [(count (filter named walls)) (count walls)]))
