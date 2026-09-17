(ns waymark10.server.definitions
  "The definition resource and the law lifecycle (waymark9
  server/definitions.py, made synchronous): the law is inside the
  envelope. One row per revision per target kind, whose data is the
  canonical fingerprint — the record and the anchor, never the
  source. Git still holds the text; this holds \"which text was live,
  when, and what it meant\".

  The boot IS the revise: boot-revise! fingerprints every resident
  application kind (never :definition itself — the law of the law is
  a named punt, with waymark9's __registry__ row), compares to the
  stored current row, and deploys per the engine's :deploy-mode. And
  because the registry only ever changes HERE, the boot is also where
  the house says what the change cost a seat: `sweep-seats!` runs
  after the fingerprints — see its own section below
  (docs/spec-seat.md § 7).
  :promote (the default) is the single-breath revise: mint N+1
  current, retire N. :propose holds a data-law diff at proposed —
  the boot keeps serving the current law by installing its stored
  fingerprint in the rdef's :judgment-laws slot, so every row stamped
  with it is judged (and rendered) from the store while the resident
  code — which IS the proposed law — waits for promote.

  The lifecycle EFFECTS (waymark9's DefinitionLifecycle) run in the
  engine seam: invoke!'s after-write! hook calls `lifecycle` after a
  committed, non-replayed write, in the same call but after the
  write's transaction — pilot installs the population and restamps
  its where-matches; promote flips the served law, restamps immediate
  kinds, grandfathers-or-supersedes the prior revision; withdraw
  returns piloted rows to the current law; an adopt anywhere runs the
  supersede-when-empty sweep.

  Recorded deviations and named punts (each a sentence, per the
  discipline):
  - Boot writes go through create!/invoke! (the law rides the same
    envelope/log machinery — dogfood); a held revision is born
    :proposed via :on-create, logged as :create (waymark9 spelled it
    `propose`/`revise`; v10 keeps one create action name).
  - Withdraw semantics, honestly: after a withdraw the resident code
    STILL expresses the withdrawn law — there is no code rollback —
    so :judgment-laws KEEPS serving the current law's stored trees
    for every row until a boot with reverted code or a promote
    (waymark9 has the same property, recorded on its withdraw).
  - Lifecycle restamps (pilot where=, promote's immediate adopt,
    withdraw's return) are one bulk UPDATE with no per-row log —
    waymark9 restamped row-by-row; v10 records the deviation and
    keeps the per-row :adopt action for the logged path.
  - supersede-when-empty counts EVERY stamped row, terminal included
    (waymark9 counted non-terminal survivors): a law lives while
    anything cites it; adopting a closed row is the maintenance act
    that retires it.
  - Populations validate against the target kind's filter grammar
    (batch C closes waymark9's check_population punt): pilot's
    where= runs through the collections parser (collections.clj's
    public parse-query) per field, and only plain equality
    parameters pass — range suffixes and multi-values have no
    restamp meaning. The guard needs the engine's registry, which a
    static declaration cannot reach, so boot-revise! appends it to
    the pilot action (engines that never boot the definitions
    lifecycle also never pilot).
  - No unique (target_kind, revision) constraint yet: two racing
    boots could double-mint; single-process deploys until the
    storage grows declared uniqueness.
  - The derived-law overlay is LIVE (batch C): materialize and the
    maintainer resolve specs through :judgment-laws
    (waymark10.derived/specs-under) — the same slot this lifecycle
    installs for judgment — so holds, pilots and grandfathered laws
    are exact for expr facts and aggregate where-filters.
  - Blast radius (batch C, waymark9's measure): the proposed/piloted
    self-loop — spelled as TWO actions, :measure and :measure_pilot,
    because a v10 action declares one :to (recorded deviation) —
    counts, per redefined derived fact, the rows whose value would
    change under the proposed law (both laws evaluated over current
    data; the pilot's population scopes the scan). The report lands
    on data.measure via a maintenance write AFTER the transition
    commits (waymark9 deferred to a job; v10 is synchronous — the
    measure POST's response predates the report, GET re-reads it),
    announced as a derivation observation. Judgment blast radius
    (newly-refused rows) stays punted, named.
  - A promote/pilot/withdraw restamp emits a kind-wide
    derivation-class observation (\"restamp\") and is followed by
    maintainer/backfill! when the diff staled derived facts — the
    phase-6 named seam, wired: every row recomputes under ITS law,
    so adopted rows land the new values and grandfathered survivors
    repair under their birth law.
  - Settle/backfill markers, renames: named punts, waiting on their
    v10 features.
  - The proposal-is-resident guard (waymark9's _resident_only) is
    unported: after a reboot-during-hold the re-adopted proposal is
    promotable by construction (its hash matched the resident code);
    a stale piloted revision is parameter-served but also promotable —
    the residency refusal is a named punt.
  - The SEAT SWEEP judges parked seats and cannot mark one. R-7.1
    names active and parked; `seat`'s `mark_stale` is declared
    `active → active` only (seats.clj records why), so a parked seat
    is judged and the write is a no-op. Nothing is lost: a parked
    seat serves nothing until a person unparks it, and the next boot
    after that unpark marks it.
  - STALE IS ENTRY-GRAINED, not action-grained. A scope entry naming
    one retired action goes into `stale` WHOLE, so the resolve
    subtracts every action it named — including the ones that still
    resolve. Acceptance case 8 asks for exactly that (\"marks the seat
    stale with the entry named. The sitter sees the surviving
    entries\"), and grants' `without-entries` would express the finer
    reading if a later leg wants it; the blunt one is what keeps the
    stale list readable as a list of things to fix.
  - The sweep rides boot-revise!, so the LAW-REFRESH consumer
    (server/coherence) re-runs it on every definition burst as well
    as at boot. That is the right cadence, not an accident: what
    makes a scope entry stale is the registry moving, and refresh! is
    the other place the registry moves. Every step is idempotent, so
    a second pass over an unchanged registry writes nothing."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.fingerprint :as fp]
            [waymark10.guards :as g]
            [waymark10.resource :as r :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.collections :as coll]
            [waymark10.server.events :as events]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.maintainer :as maintainer]
            [waymark10.server.problems :as p]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def deploy
  "The deploy actor: the process identity that revises the law at
  boot and drives the bookkeeping transitions."
  (t/principal {:id "waymark10-deploy" :type :system :display "Deploy"}))

;; ── guards ──────────────────────────────────────────────────────────

(defn- system? [ctx]
  (= :system (get-in ctx [:principal :type])))

(def ^:private deploy-only
  (g/guard {:name :deploy-writes-the-law
            :explain "The law is revised by the deploy at boot, never over the wire."
            :reads [:principal]
            :check (fn [_ _ ctx] (if (system? ctx) (t/allow) (t/deny)))}))

(def ^:private deploy-only-hidden
  (g/guard {:name :deploy-writes-the-law
            :explain "The law is revised by the deploy at boot, never over the wire."
            :reads [:principal]
            :hide true
            :check (fn [_ _ ctx] (if (system? ctx) (t/allow) (t/deny)))}))

(def ^:private population-shape
  (g/guard {:name :population-shape
            :explain "A population is where={…} or after=true — exactly one."
            :needs-input true
            :check (fn [_ inp _]
                     (let [w (:where inp)]
                       (if (not= (boolean (and (map? w) (seq w)))
                                 (boolean (:after inp)))
                         (t/allow)
                         (t/deny))))}))

(def ^:private data-law-only
  (g/expr {:name :data-law-pilots
           :when '(= (data :diff_class) "data_law")
           :explain "Only a data-law diff can pilot per-population — code does not interpret per-row; promote totally instead."}))

(def ^:private data-law-measures
  (g/expr {:name :data-law-measures
           :when '(= (data :diff_class) "data_law")
           :explain "Blast radius is measured for data-law diffs — a code-or-shape diff promotes totally; there are no stored parameters to compare."}))

(def ^:private measurable
  ;; input-free (no judges → :needs-input false), so render's probe
  ;; grades it and the action narrates honestly before anyone POSTs
  (g/guard {:name :redefines-derived-facts
            :explain "This proposal redefines no derived fact; there is no blast radius to measure."
            :check (fn [row _ _]
                     (let [declared (into #{}
                                          (map name)
                                          (keys (get-in row [:data :fingerprint
                                                             :derived])))
                           ;; a genesis definition (never diffed against a
                           ;; prior revision) carries no :diff — nothing
                           ;; redefines a derived fact when nothing changed
                           stale (when-some [diff (get-in row [:data :diff])]
                                   (fp/stale-facts diff))]
                       (if (some declared stale) (t/allow) (t/deny))))}))

;; ── the population grammar (batch C, waymark9's check_population) ──

(defn- population-where-problems
  "Every way one pilot where= map fails the target kind's filter
  grammar, as sentences. A population is an equality map over
  filterable stored fields (or state): the collections parser grades
  names, tokens and value types; range suffixes and multi-values are
  refused here — a restamp is an equality, not a query."
  [trdef where]
  (into []
        (mapcat
         (fn [[f v]]
           (let [pname (name f)]
             (if (or (map? v) (sequential? v) (set? v))
               [(str pname " pins one value — a population is an equality map")]
               (try
                 ;; the grammar only, never the view: a restamp
                 ;; population is exactly what it pins, so the target
                 ;; kind's :default-filters must not widen it
                 (let [{:keys [conds]} (coll/parse-query trdef {pname (str v)}
                                                         {:defaults? false})]
                   (if (= := (:op (first conds)))
                     []
                     [(str pname " does not pin a stored field to one value "
                           "— range and multi-value parameters have no "
                           "restamp meaning")]))
                 (catch Exception e
                   (let [errors (:errors (ex-data e))]
                     (if (seq errors)
                       (mapv (fn [[param msgs]]
                               (str (name param) ": " (str/join "; " msgs)))
                             (sort-by (comp str key) errors))
                       [(str pname ": " (ex-message e))]))))))))
        (sort-by (comp str key) where)))

(defn- population-grammar-guard
  "The one pilot guard a static declaration cannot spell: where=
  validates against the TARGET kind's filter grammar, which lives on
  the engine's registry — so this guard closes over the engine and
  boot-revise! installs it (install-pilot-grammar!)."
  [eng]
  (g/guard
   {:name :population-grammar
    :explain "The population must speak {target_kind}'s filter grammar — {problem}"
    :needs-input true
    :check
    (fn [row inp _ctx]
      (let [where (:where inp)]
        (if-not (and (map? where) (seq where))
          (t/allow)   ; after=true — population-shape grades the pair
          (let [kind (keyword (get-in row [:data :target_kind]))
                trdef (get (inv/resources eng) kind)
                problems (if trdef
                           (population-where-problems trdef where)
                           [(str "kind " (name kind)
                                 " is not registered on this engine")])]
            (if (seq problems)
              (t/deny {:vars {:target_kind (name kind)
                              :problem (str/join "; " problems)}})
              (t/allow))))))}))

(defn- install-pilot-grammar!
  "Append the engine-closed population-grammar guard to the pilot
  action, once (idempotent by guard name)."
  [eng]
  (swap! (:registry eng)
         update-in [:kinds :definition :actions :pilot :guards]
         (fn [gs]
           (if (some #(= :population-grammar (:name %)) gs)
             gs
             (conj (vec gs) (population-grammar-guard eng))))))

;; ── the resource ────────────────────────────────────────────────────

(defhandler record-population [row inp _ctx]
  (assoc-in row [:data :population]
            (if (:after inp) {:after true} {:where (:where inp)})))

(defresource definition
  {:kind :definition
   :plural "definitions"
   :states [:proposed :piloted :current :grandfathered :superseded :withdrawn]
   :initial :current
   :terminal #{:superseded :withdrawn}
   ;; proposed is entered at creation (a held propose-mode boot);
   ;; piloted and withdrawn are reachable only through it
   :allow-dead #{:proposed :piloted :withdrawn}
   :nav :system
   :summary "Law of {data.target_kind} · revision {data.revision} · {state}"
   :schema [:map
            [:target_kind {:x-display
                           {:label "The kind this law governs"
                            :help "One resource kind's own name — task, meal, chore — spelled exactly as its declaration spells it. A definition governs one kind and never two."}}
             [:string {:min 1 :max 64}]]
            [:revision {:x-display {:label "Revision number"}}
             [:int {:min 1}]]
            [:fingerprint_hash {:x-display
                                {:label "Fingerprint hash"
                                 :help "The sha256 of the canonical projection below — the whole reason a revision can be recognised rather than described."}}
             [:string {:min 64 :max 64}]]
            ;; the canonical projection, verbatim — the record IS the law
            ;; waymark-2hd0: the deploy writes every free-form field on
            ;; this kind; the sentences say so where a form would ask
            [:fingerprint {:x-display {:label "The law itself, canonically"
                                       :spelled-by-hand "The canonical projection of the declaration, written by the deploy at boot — a tree only the fingerprinter spells."}}
             :any]
            [:diff {:optional true
                    :x-display {:label "What moved since the last revision"
                                :spelled-by-hand "The canonical diff between two projections, written by the deploy beside the fingerprint."}}
             :any]
            [:diff_class {:x-display
                          {:label "What kind of change this is"
                           :choices
                           {"initial" "The first law this kind ever had — there was nothing to diff it against"
                            "data_law" "Only overlayable law moved — a derivation, a threshold, an edge's where — so it can be piloted and measured before it governs"
                            "code_or_shape" "Code or storage shape moved — this one deploys whole; there is no partial population to pilot it on"}}}
             [:enum "initial" "data_law" "code_or_shape"]]
            [:change_summary {:optional true
                              :x-display
                              {:label "What changed, in a sentence"
                               :help "The line a reviewer reads first — what moved and why, not how. The diff below says how."}}
             [:maybe [:string {:max 120}]]]
            [:population {:optional true
                          :x-display {:label "The rows a pilot governs"
                                      :spelled-by-hand "The pilot's population as the pilot door recorded it — the filter or the forward-only mark, never a form's own words."}}
             :any]
            [:deploy_note {:optional true
                           :x-display
                           {:label "Note for the deploy record"
                            :help "Why this revision was pushed when it was — the ticket, the incident, the person who asked. Read months later by whoever is wondering."}}
             [:maybe [:string {:max 120}]]]
            [:held {:optional true
                    :x-display {:label "Hold as a proposal instead of deploying"}}
             [:maybe :boolean]]
            ;; the blast-radius report (batch C) — written by the
            ;; measure lifecycle as maintenance, never by a handler
            [:measure {:optional true
                       :x-display {:label "Blast-radius report"
                                   :spelled-by-hand "Written by the measure lifecycle as maintenance, never by a hand."}}
             :any]]
   :filterable {:state #{:eq :in}
                :target_kind #{:eq :in}}
   :create-guards [deploy-only]
   ;; a held proposal is born :proposed — the declared create landing
   :on-create (fn [row _ctx]
                (if (get-in row [:data :held])
                  (assoc row :state :proposed)
                  row))
   :actions
   {:pilot {:from #{:proposed} :to :piloted
            :input [:map
                    [:where {:optional true
                             :x-display
                             {:label "Which rows the pilot governs"
                              :help "A filter over the kind's own fields — the slice that lives under the new law while everything else keeps the current one."
                              :spelled-by-hand "Field=value pairs as one map, in the collection query grammar the kind's own filterable fields speak — the same grammar a saved view's where speaks."}}
                     :any]
                    [:after {:optional true
                             :x-display
                             {:label "Or: every row created from now on"
                              :help "Set this instead of a filter to pilot forward only — existing rows keep the current law, new ones are born under this revision."}}
                     [:maybe :boolean]]]
            :record true
            :guards [population-shape data-law-only]
            :safety {:idempotent false :reversible false :confirm true
                     :consequence "The declared population's rows begin living under this revision; the current law keeps governing everything else. The way back is withdraw."}
            :handler record-population
            :display {:label "Pilot" :order 2}}
    ;; four-eyes on the CREATE action (waymark9 barred `propose`; v10
    ;; logs creation as :create, so the actual logged action name
    ;; carries the bar): the deploy actor that proposed cannot promote
    :promote {:from #{:proposed :piloted} :to :current
              :guards [(g/four-eyes :create)]
              :safety {:idempotent true :reversible false :confirm true
                       :consequence "This proposal becomes the served law for every row of its kind; rows adopt or grandfather per the kind's adoption declaration."}
              :display {:label "Promote" :order 1}}
    :withdraw {:from #{:proposed :piloted} :to :withdrawn
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "The proposal closes; the current law continues to govern — served from its stored trees while the withdrawn code stays resident."
                        ;; final on purpose (waymark-9u10): the current law
                        ;; has gone on governing from its stored trees since
                        ;; — reopening would put a closed proposal back over
                        ;; a law that has been served in the meantime
                        :final "The current law has governed from its stored trees since the proposal closed; reopening would set a closed proposal over a law already served. A fresh proposal is the next boot's, never a reopen."}
               :display {:label "Withdraw" :order 3}}
    ;; the proposed/piloted self-loop, spelled twice because a v10
    ;; action declares one :to (recorded deviation; waymark9's single
    ;; `measure` served proposed only)
    :measure {:from #{:proposed} :to :proposed
              :guards [data-law-measures measurable]
              :safety {:idempotent false :reversible true :confirm false}
              :display {:label "Measure blast radius" :order 4
                        :description "Recompute every redefined fact over the live rows under both laws and report the flips; the report lands on data.measure."}}
    :measure_pilot {:from #{:piloted} :to :piloted
                    :guards [data-law-measures measurable]
                    :safety {:idempotent false :reversible true :confirm false}
                    :display {:label "Measure pilot blast radius" :order 4
                              :description "The same meter, scoped to the pilot's declared population."}}
    :grandfather {:from #{:current} :to :grandfathered
                  :guards [deploy-only]
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "Rows still live under this revision: it is law, not history — it supersedes the day its last row adopts."}
                  :display {:label "Grandfather" :order 9}}
    :supersede {:from #{:current :grandfathered} :to :superseded
                :guards [deploy-only-hidden]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "Superseding is the engine's bookkeeping; a rollback is a new revision, never an un-supersede."}
                :display {:label "Supersede" :order 9}}}})

;; ── stored-row plumbing ─────────────────────────────────────────────

(defn- wire-keys
  "Stored fingerprints come back from JSONB with keyword keys (the
  one JSON mapper keywordizes); the fingerprint vocabulary is string
  keys. One normalization at the load boundary."
  [v]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (into {} (map (fn [[k val]] [(if (keyword? k) (name k) k) val])) x)
       x))
   v))

(defn- def-rows
  "Every stored definition row of one target kind, revision-descending,
  fingerprints normalized to the string-keyed vocabulary."
  [eng kind]
  (store/with-tx (:storage eng)
    (fn [tx]
      (->> (store/query-rows (:storage eng) tx :definition
                             {:target_kind (name kind)} {:limit 1000})
           (mapv #(update-in % [:data :fingerprint] wire-keys))
           (sort-by (comp :revision :data) >)))))

(defn- rev-of [row] (get-in row [:data :revision]))
(defn- hash-of [row] (get-in row [:data :fingerprint_hash]))
(defn- fp-of [row] (get-in row [:data :fingerprint]))

(defn- install!
  "Swap new law slots into the kind's rdef; every install resets the
  judgment cache (rebuilt guard vectors key off the slots)."
  [eng kind f]
  (swap! (:registry eng) update-in [:kinds kind]
         (fn [rd] (assoc (f rd) :judgment-cache (atom {})))))

(defn- rdef-now [eng kind] (get (inv/resources eng) kind))

;; ── readers ─────────────────────────────────────────────────────────

(defn current-law
  "The revision number governing the kind, nil pre-law."
  [eng kind]
  (:current-law (rdef-now eng kind)))

(defn proposed-law [eng kind] (:proposed-law (rdef-now eng kind)))
(defn piloted-law [eng kind] (:piloted-law (rdef-now eng kind)))

;; ── the on-demand loader (waymark-442.4) ────────────────────────────

(defn- load-fingerprint
  "Revision R's stored fingerprint, read out of its definition ROW.
  `:law-ids` ({revision → row id}) is the index — written by
  boot-revise! over every stored row of the kind, whatever state, and
  kept current by install-current!/after-pilot! — so the common case
  is one load-row. The scan behind it is the honest fallback for an
  engine whose rdef predates the index (a kind installed by a boot
  that never wrote one); it costs a query and answers rather than
  guessing."
  [eng kind revision]
  (when (rdef-now eng :definition)
    (let [st (:storage eng)
          row (or (when-some [id (get-in (rdef-now eng kind) [:law-ids revision])]
                    (store/with-tx st (fn [tx] (store/load-row st tx :definition
                                                               id {}))))
                  (first (filter #(= revision (rev-of %)) (def-rows eng kind))))
          fp (some-> row fp-of wire-keys)]
      (when (and (map? fp) (seq fp)) fp))))

(defn stored-fingerprint
  "The law of revision R for one kind — the half of time travel that
  `:judgment-laws` deliberately does not hold.

  That slot carries only the revisions that must be SERVED: a
  grandfathered law with rows still stamped to it, or the current law
  under a propose hold. install-current! dissocs the revision it
  promotes and sweep! dissocs a law whose last row left, because a
  law nobody is judged by should not be resolved through on every
  read. An as-of question asks the opposite thing — what did the law
  of THAT day say — so it needs an ARBITRARY revision, and it reads
  the definition row to get one.

  Cached in the registry atom under :law-fingerprints, NOT on the
  rdef, and that placement is the whole subtlety: `install!` rebuilds
  [:kinds kind] wholesale on every law install and hands the kind a
  fresh :judgment-cache, so an rdef-borne cache of historical law
  would be thrown away every time a proposal moved. The registry's
  top level survives, and it is safe to survive: a revision's stored
  fingerprint is written once and never rewritten, so a hit can never
  go stale. Only hits are remembered — a miss stays a miss, because
  the row it is waiting for may be written a second from now.

  An engine built without a registry atom (`inv/engine`, the bare
  constructor a unit test reaches for) still gets an ANSWER; it just
  pays for it every time. Refusing to answer without a cache would
  make the law of the day depend on how the engine was constructed."
  [eng kind revision]
  (when (some? revision)
    (let [reg (:registry eng)]
      (or (get (:judgment-laws (rdef-now eng kind)) revision)
          (when reg (get-in @reg [:law-fingerprints kind revision]))
          (when-some [fp (load-fingerprint eng kind revision)]
            (when reg
              (swap! reg assoc-in [:law-fingerprints kind revision] fp))
            fp)))))

;; ── the deploy story helpers ────────────────────────────────────────

(defn- describe [diff]
  (let [counts (frequencies (map :class (mapcat diff [:added :removed :changed])))]
    (if (empty? counts)
      "revised with no path-level change"
      (let [s (str "changes: "
                   (str/join ", " (map (fn [[c n]] (str n " " (name c)))
                                       (sort-by (comp name key) counts))))]
        (subs s 0 (min 120 (count s)))))))

(defn- mint!
  "One definition row via the ordinary create path — the law lives in
  the same envelope/log machinery as everything else."
  [eng corr body]
  (:row (inv/create! eng :definition
                     (into {} (filter (comp some? val)) body)
                     {:principal deploy :correlation-id corr})))

(defn- withdraw! [eng corr row-id]
  (inv/invoke! eng :definition row-id :withdraw nil
               {:principal deploy :correlation-id corr}))

;; ── the sweep ───────────────────────────────────────────────────────

(defn sweep!
  "Supersede-when-empty (waymark9 design §1/§3): every grandfathered
  revision with zero stamped rows transitions to superseded and drops
  its :judgment-laws entry — laws die when they are empty, and the
  log knows the day."
  ([eng]
   (doseq [kind (sort (keys (dissoc (inv/resources eng) :definition)))]
     (sweep! eng kind)))
  ([eng kind]
   (let [storage (:storage eng)]
     (doseq [row (filter #(= :grandfathered (:state %)) (def-rows eng kind))]
       (let [rev (rev-of row)
             n (store/with-tx storage
                 (fn [tx] (store/law-count storage tx kind rev)))]
         (when (zero? n)
           (inv/invoke! eng :definition (:id row) :supersede nil
                        {:principal deploy})
           (install! eng kind
                     (fn [rd] (update rd :judgment-laws dissoc rev)))))))))

;; ── restamps announce, repairs follow (batch C) ─────────────────────

(defn- restamp!
  "One population restamp plus its kind-wide derivation-class
  observation (\"restamp\"), one transaction — the live-update gap a
  bulk UPDATE with no per-row log used to leave. Returns rows moved."
  [eng kind where to-rev]
  (store/with-tx (:storage eng)
    (fn [tx]
      (let [n (store/restamp-law! (:storage eng) tx kind where to-rev)]
        (when (pos? n)
          (events/record-observation!
           (:storage eng) tx
           {:kind kind :resource-id nil :class "restamp"
            :changed {:law_revision to-rev :rows n}}))
        n))))

(defn- repair-stale!
  "The stale-facts repair (phase 6's named backfill seam, wired):
  when the revision's diff staled derived facts the resident law
  still declares, recompute the kind — each row under ITS law. A
  stale marker naming a since-removed fact drops with a *err* line
  (waymark9's _still_declared)."
  [eng kind diff]
  (let [rdef (rdef-now eng kind)
        stale (fp/stale-facts (or diff {}))
        declared? (fn [f] (contains? (:derived rdef) (keyword f)))
        kept (filterv declared? stale)]
    (doseq [f (remove declared? stale)]
      (binding [*out* *err*]
        (println (str "waymark10 definitions: dropping backfill marker "
                      (name kind) "." f
                      " — the current law no longer declares the fact"))))
    (when (seq kept)
      (maintainer/backfill! eng kind (mapv keyword kept)))))

;; ── the promote effect (shared by boot auto-promote and the human
;;    promote) ────────────────────────────────────────────────────────

(defn- install-current!
  "new-row (a definition row in state :current) becomes the served
  law of its kind: prior current rows retire — an adoption :immediate
  kind's rows restamp first, so the prior supersedes empty; a kind
  with surviving stamped rows grandfathers instead and its stored
  fingerprint joins :judgment-laws — slots flip, the sweep runs."
  [eng kind new-row]
  (let [target-rdef (rdef-now eng kind)
        new-rev (rev-of new-row)
        storage (:storage eng)
        priors (filter #(and (= :current (:state %))
                             (not= (:id %) (:id new-row)))
                       (def-rows eng kind))]
    (doseq [prior priors]
      (let [old-rev (rev-of prior)]
        (when (= :immediate (:adoption target-rdef))
          (restamp! eng kind {:law-revision old-rev} new-rev))
        (let [survivors (store/with-tx storage
                          (fn [tx] (store/law-count storage tx kind old-rev)))
              retire (if (pos? survivors) :grandfather :supersede)]
          (inv/invoke! eng :definition (:id prior) retire nil
                       {:principal deploy})
          (install! eng kind
                    (fn [rd]
                      (update rd :judgment-laws
                              (fn [j] (if (pos? survivors)
                                        (assoc (or j {}) old-rev (fp-of prior))
                                        (dissoc (or j {}) old-rev)))))))))
    (install! eng kind
              (fn [rd]
                (-> rd
                    (assoc :current-law new-rev)
                    (assoc-in [:law-ids new-rev] (:id new-row))
                    ;; the promoted revision is resident now
                    (update :judgment-laws (fnil dissoc {}) new-rev)
                    (assoc :proposed-law nil :piloted-law nil))))
    (sweep! eng kind)))

;; ── the lifecycle effects ───────────────────────────────────────────

(defn- after-pilot! [eng row]
  (let [kind (keyword (get-in row [:data :target_kind]))
        rev (rev-of row)
        pop (get-in row [:data :population])]
    (when (rdef-now eng kind)
      (install! eng kind
                (fn [rd]
                  (-> rd
                      (assoc :piloted-law {:revision rev
                                           :definition-id (:id row)
                                           :population pop})
                      (assoc :proposed-law nil)
                      (assoc-in [:law-ids rev] (:id row)))))
      ;; existing where-matches restamp to the piloted revision —
      ;; judged under it from now on (the resident code IS that law) —
      ;; and their stale facts recompute under it (each row under its
      ;; own law, so the untouched population stays put)
      (when-some [where (:where pop)]
        (restamp! eng kind where rev)
        (repair-stale! eng kind (get-in row [:data :diff]))))))

(defn- after-promote! [eng row]
  (let [kind (keyword (get-in row [:data :target_kind]))]
    (when (rdef-now eng kind)
      (install-current! eng kind
                        (update-in row [:data :fingerprint] wire-keys))
      ;; the promote recomputes stale facts under the new law —
      ;; grandfathered survivors repair under their birth law
      (repair-stale! eng kind (get-in row [:data :diff])))))

(defn- after-withdraw! [eng row]
  (let [kind (keyword (get-in row [:data :target_kind]))
        rev (rev-of row)]
    (when-some [rd (rdef-now eng kind)]
      (install! eng kind
                (fn [rd]
                  (cond-> rd
                    (= rev (get-in rd [:proposed-law :revision]))
                    (assoc :proposed-law nil)
                    (= rev (get-in rd [:piloted-law :revision]))
                    (assoc :piloted-law nil))))
      ;; piloted rows return to the current law; a withdrawn proposal
      ;; moved no row. NOTE the docstring's withdraw semantics: the
      ;; :judgment-laws entry for the current law STAYS — the resident
      ;; code still expresses the withdrawn law.
      (when-some [cur (:current-law rd)]
        (when (pos? (restamp! eng kind {:law-revision rev} cur))
          ;; the returned rows' facts were computed under the pilot;
          ;; the same diff names what to repair
          (repair-stale! eng kind (get-in row [:data :diff])))))))

;; ── the blast-radius effect (batch C) ───────────────────────────────

(defn- after-measure!
  "waymark9's BlastRadiusMeter, run in the lifecycle seam: compare
  every redefined derived fact under the current law's stored specs
  vs this proposal's, over current data (the pilot's population when
  piloted), and land the report on data.measure — a maintenance
  write (no version, no transition), announced as a derivation
  observation so an open definition screen refetches."
  [eng row]
  (let [kind (keyword (get-in row [:data :target_kind]))
        rdef (rdef-now eng kind)]
    (when rdef
      (let [storage (:storage eng)
            current (first (filter #(= :current (:state %))
                                   (def-rows eng kind)))
            report (maintainer/blast-radius
                    eng kind
                    {:facts (fp/stale-facts (get-in row [:data :diff]))
                     :current-fp (some-> current fp-of)
                     :proposed-fp (wire-keys (get-in row [:data :fingerprint]))
                     :population (when (= :piloted (:state row))
                                   (get-in row [:data :population :where]))})
            report (assoc report
                          :at (str ((:now-fn eng)))
                          :from_revision (some-> current rev-of)
                          :to_revision (rev-of row))
            drdef (rdef-now eng :definition)]
        (store/with-tx storage
          (fn [tx]
            (when-some [raw (store/load-row storage tx :definition (:id row)
                                            {:for-update true})]
              (let [data (assoc (:data (inv/decode-row drdef raw))
                                :measure (p/wire-value report))]
                (store/update-data! storage tx :definition (:id row)
                                    (schema/encode (:schema drdef) data)
                                    (:next-flip-at raw))
                (events/record-observation!
                 storage tx {:kind :definition :resource-id (:id row)
                             :class "recompute" :changed ["measure"]})))))))))

(defn lifecycle
  "The engine seam invoke!'s after-write! calls: definition
  transitions carry their effects; an adopt anywhere runs the sweep."
  [eng kind action res]
  (if (= :definition kind)
    (case action
      :pilot (after-pilot! eng (:row res))
      :promote (after-promote! eng (:row res))
      :withdraw (after-withdraw! eng (:row res))
      (:measure :measure_pilot) (after-measure! eng (:row res))
      nil)
    (when (= :adopt action)
      (sweep! eng kind)))
  nil)

;; ── boot ────────────────────────────────────────────────────────────

(defn- revise-kind!
  "Compare one resident kind's fingerprint to its stored law; write
  nothing, or deploy per :deploy-mode, and fill the law slots."
  [eng corr kind]
  (let [rdef (rdef-now eng kind)
        fp' (:fingerprint rdef)
        fph (:fingerprint-hash rdef)
        rows (def-rows eng kind)
        by-state (group-by :state rows)
        current (first (:current by-state))
        proposed (first (:proposed by-state))
        piloted (first (:piloted by-state))
        law-ids (into {} (map (juxt rev-of :id)) rows)
        ;; grandfathered laws always serve their rows from the store
        jlaws (into {} (map (juxt rev-of fp-of)) (:grandfathered by-state))
        next-rev (inc (reduce max 0 (map rev-of rows)))]
    (cond
      ;; (a) the first law: mint revision 1, born current
      (nil? current)
      (let [row (mint! eng corr {:target_kind (name kind)
                                 :revision next-rev
                                 :fingerprint_hash fph
                                 :fingerprint fp'
                                 :diff_class "initial"
                                 :change_summary "the law as first recorded"})]
        (install! eng kind
                  #(merge % {:current-law next-rev
                             :proposed-law nil
                             :piloted-law nil
                             :law-ids (assoc law-ids next-rev (:id row))
                             :judgment-laws jlaws})))

      ;; (b) unchanged: a reboot costs nothing — adopt the stored
      ;; numbers; a lingering proposal whose code is no longer
      ;; resident exits the honest way
      (= fph (hash-of current))
      (do
        (doseq [prop (:proposed by-state)]
          (withdraw! eng corr (:id prop)))
        (install! eng kind
                  #(merge % {:current-law (rev-of current)
                             :proposed-law nil
                             :piloted-law (when piloted
                                            {:revision (rev-of piloted)
                                             :definition-id (:id piloted)
                                             :population (get-in piloted [:data :population])})
                             :law-ids law-ids
                             ;; a pilot whose code is not resident is
                             ;; parameter-served from its stored trees
                             :judgment-laws (cond-> jlaws
                                              piloted (assoc (rev-of piloted)
                                                             (fp-of piloted)))})))

      ;; (b') the resident code IS the held proposal — the hold
      ;; continues across the reboot
      (and proposed (= fph (hash-of proposed)))
      (install! eng kind
                #(merge % {:current-law (rev-of current)
                           :proposed-law {:revision (rev-of proposed)
                                          :definition-id (:id proposed)}
                           :piloted-law nil
                           :law-ids law-ids
                           :judgment-laws (assoc jlaws (rev-of current)
                                                 (fp-of current))}))

      ;; (b'') the resident code IS the piloted revision — the pilot
      ;; continues across the reboot
      (and piloted (= fph (hash-of piloted)))
      (install! eng kind
                #(merge % {:current-law (rev-of current)
                           :proposed-law nil
                           :piloted-law {:revision (rev-of piloted)
                                         :definition-id (:id piloted)
                                         :population (get-in piloted [:data :population])}
                           :law-ids law-ids
                           :judgment-laws (assoc jlaws (rev-of current)
                                                 (fp-of current))}))

      ;; (c) the law moved
      :else
      (let [diff (fp/diff-fingerprints (fp-of current) fp')
            class (fp/classify-diff diff)
            summary' (describe diff)
            hold? (and (= :data-law class) (= :propose (:deploy-mode eng)))]
        (doseq [prop (:proposed by-state)]
          (withdraw! eng corr (:id prop)))
        (if hold?
          ;; propose mode + data-law diff: register and HOLD — the
          ;; overlay serves the current law from its stored trees
          (let [row (mint! eng corr {:target_kind (name kind)
                                     :revision next-rev
                                     :fingerprint_hash fph
                                     :fingerprint fp'
                                     :diff (p/wire-value diff)
                                     :diff_class "data_law"
                                     :change_summary summary'
                                     :held true})]
            (install! eng kind
                      #(merge % {:current-law (rev-of current)
                                 :proposed-law {:revision next-rev
                                                :definition-id (:id row)}
                                 :piloted-law nil
                                 :law-ids (assoc law-ids next-rev (:id row))
                                 :judgment-laws (assoc jlaws (rev-of current)
                                                       (fp-of current))})))
          ;; auto-promote: the single-breath revise (a code-or-shape
          ;; diff in propose mode carries the recorded marker — the
          ;; resident objects ARE that law; holding would be a lie)
          (let [row (mint! eng corr
                           {:target_kind (name kind)
                            :revision next-rev
                            :fingerprint_hash fph
                            :fingerprint fp'
                            :diff (p/wire-value diff)
                            :diff_class (if (= :data-law class)
                                          "data_law" "code_or_shape")
                            :change_summary summary'
                            :deploy_note (when (and (= :propose (:deploy-mode eng))
                                                    (= :code-or-shape class))
                                           "promoted without hold: diff exceeds data-law")})]
            (install! eng kind
                      #(merge % {:law-ids (assoc law-ids next-rev (:id row))
                                 :judgment-laws jlaws
                                 :proposed-law nil
                                 :piloted-law nil}))
            (install-current! eng kind row)
            ;; the boot's promote repairs like the human one: stale
            ;; facts recompute, each row under its own law
            (repair-stale! eng kind diff)))))))


;; ── the seat sweep (docs/spec-seat.md § 7, § 12) ────────────────────
;;
;; THE REGISTRY CHANGES ONLY AT BOOT (R-7.1), which is why this rides
;; here and not on a clock: the scope a seat was opened with is
;; validated at its own doors, and the only thing that can rot it
;; afterwards is a push that retires a kind or an action. waymark-enx
;; is the incident — a push retired `value.restate`, the stored scope
;; still named it, and every extend-ask got a 409 while the leash died
;; in silence. So the boot that changes the registry is the boot that
;; says what the change cost.
;;
;; The sweep REPORTS and it repairs almost nothing. It writes `stale`
;; so the entries that stopped resolving are on the row, in discover
;; and in the envelope (R-7.4); it ends the sittings nobody closed
;; (R-7.6); and it logs the schedule copies the read-back found
;; drifting (R-12.3) without touching one of them — a mirror that
;; healed the row from the provider's copy would adopt exactly the
;; drift the spec exists to catch. What it does NOT do is narrow a
;; leash: R-7.3 keeps a stale seat serving the entries that still
;; resolve, and the router's resolve is what drops the rest at request
;; time.

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 seat sweep: " parts))))

(def ^:private sweep-cap
  "The most rows one sweep pass reads per kind. A boot is not the
  place to walk an unbounded table, and a house with more seats than
  this has a bigger problem than a stale scope entry."
  1000)

(def ^:private scope-guards
  "The four scope guards (spec-seat.md § 2), in the order a door runs
  them. The SAME vars `seat`'s own create and restate carry — a sweep
  that re-implemented `is this kind real` would be a second definition
  of the law, right on the day it was written and wrong on the next."
  [grants/scope-names-real-kinds
   grants/scope-names-real-actions
   grants/scope-filters-are-filterable
   grants/scope-omits-private-kinds])

(defn- scope-ctx
  "The ctx those four read, built off the LIVE registry: the
  vocabulary hook, the registry consult and the capability lookup,
  spelled exactly as invoke/make-ctx spells them at a door. No
  `:mode`, so every guard runs its real check rather than the probe's
  optimistic answer."
  [eng]
  (let [st (:storage eng)]
    {:services (:services eng)
     :action-names (fn [target-kind]
                     (some-> (get (inv/resources eng) (keyword target-kind))
                             inv/action-names))
     :rdef-of (fn [token]
                (let [rs (inv/resources eng)
                      t (name token)]
                  (or (get rs (keyword t))
                      (some (fn [[_ r]] (when (= t (:plural r)) r)) rs))))
     :find (fn [target-kind where opts]
             (when (contains? (inv/resources eng) target-kind)
               (store/with-tx st
                 (fn [tx]
                   (store/query-rows st tx target-kind (or where {})
                                     (merge {:limit 100} opts))))))}))

(defn stale-entries
  "The entries of one stored scope that no longer resolve, each beside
  the sentence the guard that refused it would have said —
  [{:entry e :note sentence} …], in scope order.

  Judged ENTRY BY ENTRY on purpose. A door judges the scope whole and
  answers with the first failure, which is the right answer to 'may I
  write this'; the sweep is answering 'which of these still work', and
  R-7.3 turns on the difference — a stale seat must still serve the
  entries that are not stale, so the sweep has to know which ones
  those are. The one law lost by the split is
  `scope-filters-are-filterable`'s two-filtered-entries-per-kind rule,
  which no registry change can newly break."
  [eng scope]
  (let [ctx (scope-ctx eng)]
    (into []
          (keep (fn [entry]
                  (some (fn [guard]
                          (let [[verdict denier] (g/evaluate guard nil
                                                             {:scope [entry]} ctx)]
                            (when (t/deny? verdict)
                              {:entry entry
                               :note (g/render-reason denier verdict nil)})))
                        scope-guards)))
          scope)))

(def ^:private note-cap
  "What `mark_stale`'s `note` holds. R-7.2 wants the guard's own
  sentence and the field is 240 characters wide, so a refusal that
  spells a long kind's whole action vocabulary is cut rather than
  refused — a sweep that could not record WHY would be the silence
  this whole section exists to end."
  240)

(defn- one-sentence [s]
  (let [s (str s)]
    (if (<= (count s) note-cap) s (str (subs s 0 (dec note-cap)) "…"))))

(defn- rows-of [eng kind where]
  (let [st (:storage eng)]
    (if (contains? (inv/resources eng) kind)
      (store/with-tx st
        (fn [tx] (store/query-rows st tx kind where {:limit sweep-cap})))
      [])))

(defn- sweep-scopes!
  "R-7.1/R-7.2: judge every active or parked seat's scope, and write
  the failures into `stale` through the concealed `mark_stale`, with
  the guard's own sentence as the note. `seats/mark-stale!` is
  idempotent by intent — an unchanged list is not written again — so
  a second boot over an unchanged registry writes nothing. → how many
  seats moved."
  [eng]
  (reduce
   (fn [n row]
     (let [found (stale-entries eng (get-in row [:data :scope]))]
       (if (seq found)
         (if (try (seats/mark-stale! eng (:id row)
                                     (mapv :entry found)
                                     (one-sentence (:note (first found))))
                  (catch Exception e
                    (warn! "seat " (:id row) " could not be marked stale: "
                           (ex-message e))
                    false))
           (inc n)
           n)
         ;; nothing failing and nothing recorded: the clean case, and
         ;; the one a boot must not write to. R-7.5 gives the CLEARING
         ;; of `stale` to `restate`, where the person who fixed the
         ;; scope is standing — a sweep that cleared it would be the
         ;; engine saying the scope is good when what changed was the
         ;; registry underneath it.
         n)))
   0
   (concat (rows-of eng :seat {:state :active})
           (rows-of eng :seat {:state :parked}))))

(defn- sweep-sittings!
  "R-7.6: a sitting left `open` for more than two of its seat's
  cadences is over. It goes through the sitting's own `abandon` door
  under the seats actor, so the ending is in the log like every other
  ending — and with NO tokens, because the absence of a bill is the
  honest record of a session that never reported one. → how many
  ended."
  [eng]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) :sitting)
        ^java.time.Instant now ((:now-fn eng))
        ;; one read per SEAT, not per sitting: a seat waking hourly
        ;; leaves its open sittings behind in a bunch
        seen (volatile! {})
        cadence-of (fn [seat-id]
                     (let [cached (get @seen seat-id ::miss)]
                       (if (not= ::miss cached)
                         cached
                         (let [c (some-> (store/with-tx st
                                           (fn [tx]
                                             (store/load-row st tx :seat
                                                             (str seat-id) {})))
                                         :data :cadence_seconds)]
                           (vswap! seen assoc seat-id c)
                           c))))]
    (if (or (nil? rdef) (not (contains? (inv/resources eng) :seat)))
      0
      (reduce
       (fn [n raw]
         (let [row (inv/decode-row rdef raw)
               cadence (cadence-of (some-> (get-in row [:data :seat]) str))
               started (get-in row [:data :started_at])]
           (if (and cadence started
                    (.isBefore ^java.time.Instant started
                               (.minusSeconds now (* 2 (long cadence)))))
             ;; a door that refuses one sitting must not take the boot
             ;; down with it: the sweep says so and walks on
             (if (try (inv/invoke! eng :sitting (:id row) :abandon nil
                                   {:principal seats/seats-actor})
                      true
                      (catch Exception e
                        (warn! "sitting " (:id row) " could not be abandoned: "
                               (ex-message e))
                        false))
               (inc n)
               n)
             n)))
       0
       (rows-of eng :sitting {:state :open})))))

(defn- report-drift!
  "R-12.3: the sweep REPORTS the drift the read-back found in a
  provider's copy of a schedule; it does not repair one. One line per
  row, and the row keeps saying it until an adapter's next read-back
  finds the copy honest again. → how many were reported."
  [eng]
  (let [st (:storage eng)]
    (if-not (contains? (inv/resources eng) :schedule)
      0
      (reduce (fn [n row]
                (warn! "schedule " (:id row) " for seat "
                       (get-in row [:data :seat]) " drifted: "
                       (get-in row [:data :drift]))
                (inc n))
              0
              (store/with-tx st
                (fn [tx]
                  (store/search-rows
                   st tx :schedule
                   [{:target :data :field :drift :op :set? :value true}]
                   {:limit sweep-cap})))))))

(defn sweep-seats!
  "The boot's seat pass (§ 7, R-12.3), run after the kind fingerprints
  because it judges scopes against the registry those fingerprints
  just settled. → {:stale n :abandoned n :drifting n}.

  Every step is guarded against a kind this engine does not serve: an
  engine assembled without the seats module sweeps nothing and says
  nothing, which is what a module you left out should cost."
  [eng]
  {:stale (sweep-scopes! eng)
   :abandoned (sweep-sittings! eng)
   :drifting (report-drift! eng)})

(defn boot-revise!
  "Fingerprint every resident application kind, revise where the hash
  moved, fill the law slots. One correlation id spans the deploy.
  Also installs the engine-closed pilot guards (population grammar —
  batch C), and — AFTER the fingerprints, because it judges scopes
  against the registry they settle — runs the seat sweep
  (spec-seat.md R-7.1)."
  [eng]
  (install-pilot-grammar! eng)
  (let [corr (str (random-uuid))]
    (doseq [kind (sort (keys (dissoc (inv/resources eng) :definition)))]
      (revise-kind! eng corr kind)))
  (sweep-seats! eng))
