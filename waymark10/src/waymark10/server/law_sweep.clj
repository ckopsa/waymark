(ns waymark10.server.law-sweep
  "The law sweep: judge the corpus before the law ships
  (docs/spec-law-sweep.md, and its 2026-08-23 amendments).

  `:propose` holds a data-law diff at `proposed` and asks a human to
  promote it. Nothing told that human what promoting would DO. This
  namespace answers the one question — **which live rows does this
  law change, and how?** — before the promote, and it is a LOOP, not
  an invention. Everything under it already existed:

  - `server/definitions` boots the hold by installing the CURRENT
    revision's stored fingerprint into the rdef's `:judgment-laws`
    while the RESIDENT code — which IS the proposal — waits.
  - `server/judgment/resolve-action` has served any stored revision's
    guard trees since batch C, positionally and name-checked.
  - `waymark10.scenario/verdict` is the enforcement loop's own
    grading, and became public for exactly this second caller.

  So both sides of the comparison are already in hand, and the naive
  reading of the report keys is backwards: **under current** is the
  OVERLAY (`resolve-action` through `:judgment-laws`), **under
  proposed** is the resident declaration verbatim. The sweep needed no
  new judgment mechanism at all. It needed a caller.

  ── the four classes are two and a half ──

  1. `schema`      — stored `data` the proposed `:schema` will not
                     admit, as malli's own explain.
  2. `availability`— an action available under revision N that refuses
                     under N+1, or the reverse. THE class: it is a
                     GUARD change, invisible to any schema diff, and
                     it is the design doc's own named punt closed
                     (\"judgment blast radius (newly-refused rows)
                     unmeasured\", waymark10-design.md:2161).
  3. `state`       — a row sitting in a state the proposed machine no
                     longer declares. `store/migrate` plans the
                     rename; it does not name the rows. This does.
  4. `derivation`  — ALREADY BUILT, and called rather than rebuilt:
                     `maintainer/blast-radius` is the definition
                     kind's own `:measure` meter, evaluating both
                     laws' specs over current data. The sweep
                     PROJECTS its `{:fact :flips :of :sample}` as
                     class `derivation` and grows no second
                     recomputation. Its grain is the FACT, not the
                     row, and the finding wears that honestly.

  A held diff is `data_law` by construction (`fp/classify-diff`: a
  schema or a state-machine change classifies `code_or_shape` and
  auto-promotes, hold or no hold). So under a hold, classes 1 and 3
  cannot be CONSEQUENCES of promoting — they are PRE-EXISTING drift,
  stored rows the law already does not admit, and the report says so
  in its notes rather than letting a proposer read them as blast
  radius. They are still worth the scan: it is the only place anyone
  asks the question, and the rows are already in hand.

  ── what the report will not pretend ──

  Every honesty clause the spec recorded lands in `:notes`, where a
  reader meets it before the findings:

  - **One principal.** A guard reading `:principal` answers for the
    person who asked. The sweep probes as the requester and names
    them; it does not enumerate the household.
  - **A snapshot, not a proof.** A guard declaring `:reads [:storage]`
    (or a kind, or `:transitions`) probes against TODAY's rows.
  - **Composites advertise nothing.** `{:any [...]}` denies as itself
    (`guards.clj`), so a finding under one names the composite, never
    the arm. The row and the action are still named.
  - **Adoption.** A kind declaring `:adoption :never` grandfathers on
    promote: existing rows keep their law until each adopts, so their
    availability cannot drift and the note says why the page is
    empty.
  - **The cap.** `sweep-cap` rows are scanned and the count of the
    rest is told. Truncation announced beats totality implied; the
    spec's job-past-the-threshold posture stays a named punt.

  The sweep does not promote and never will. Promotion is a human act
  on the definition row; this is evidence, not a gate.

  ── what it is NOT ──

  Not a kind. The spec's original drew `requested → running →
  reported` with a post-commit pass, and the module table has no
  column for a post-commit pass — the engine's `:maintain` and
  `:lifecycle` slots are core literals, and growing a fifth
  contribution is a proposal to change core, reviewed as one
  (`waymark10.modules`, § the closed table). A sweep commits nothing,
  so the three states collapse into one answered document: the
  request IS the GET, and `reported` is the only state it was ever
  going to be read in."
  (:require [clojure.string :as str]
            [waymark10.fingerprint :as fp]
            [waymark10.machine :as machine]
            [waymark10.scenario :as scenario]
            [waymark10.schema :as schema]
            [waymark10.server.collections :as coll]
            [waymark10.server.invoke :as inv]
            [waymark10.server.judgment :as judgment]
            [waymark10.server.maintainer :as maintainer]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.summary :as summary]
            [waymark10.types :as t])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(def sweep-cap
  "Rows scanned in one synchronous sweep. The spec recommends a job
  past an export-cap-ish threshold (`server/jobs` already gives
  deferred execution with leases and progress); v10 scans to the cap
  and REPORTS the remainder instead, which is the household's scale
  answered honestly and the paydesk's answered visibly. A def rather
  than an engine opt, the same call `maintainer/sample-cap` makes."
  500)

(def swept-from
  "The two states a proposal can be swept in. Propose-only is not an
  enrollment verb (`modules.clj` has none and must not grow one) and
  not a deploy-mode read: it is the door's own state constraint, the
  shape `definitions.clj`'s deploy-only guard already has."
  #{:proposed :piloted})

;; ── the probe ctx ───────────────────────────────────────────────────

(defn- actor-of-hook
  "The `unless`/four-eyes hook, over a short transaction of its own —
  `inv/render-hooks` twins :read and :find for the read path and stops
  there, because the envelope probe has no four-eyes question to ask.
  A sweep does: a guard barring whoever created the row is exactly the
  kind of law a proposal moves."
  [eng]
  (fn [row transition]
    (let [st (:storage eng)]
      (store/with-tx st
        (fn [tx]
          (some (fn [rec]
                  (when (= (:action rec) transition)
                    (get-in rec [:actor :id])))
                (store/transitions st tx {:kind (:kind row)
                                          :resource-id (:id row)}
                                   {:newest-first true})))))))

(defn probe-ctx
  "The one ctx BOTH probes share, and that identity is the whole
  method: when the row, the input and the world are held fixed, a
  difference in verdict is attributable to the LAW and to nothing
  else.

  `:probe` with a nil input, which is render's own posture: a guard
  that grades input cannot decide without it, so it pends rather than
  refusing, and the sweep never reports a drift nobody has typed yet.
  The hooks are live (`inv/render-hooks` plus :actor-of) — a sweep is
  a question about real rows, and the storage-free ctx a scenario
  uses would answer it optimistically."
  [eng principal]
  (t/ctx (merge {:principal principal
                 :now ((:now-fn eng))
                 :services (:services eng)
                 :mode :probe
                 :rate nil
                 :actor-of (actor-of-hook eng)}
                (inv/render-hooks eng))))

;; ── the door ────────────────────────────────────────────────────────

(defn sweepable!
  "Everything that must be true before a comparison means anything,
  refused as itself. Returns the target kind's rdef.

  The residency check is the one this namespace had to grow.
  `definitions.clj` records the unported `_resident_only` guard as a
  named punt — harmless there, fatal here: if the resident code no
  longer expresses the proposal, 'under proposed' would be a third
  law nobody proposed, and the report would be confident nonsense."
  [eng row]
  (let [data (:data row)
        kind (keyword (:target_kind data))
        rdef (get (inv/resources eng) kind)]
    (when-not (contains? swept-from (:state row))
      (throw (p/wrong-state :sweep (:state row) swept-from
                            (str "/api/definitions/" (:id row)))))
    (when-not rdef
      (throw (p/problem :wrong-state 409 "Wrong state"
                        {:detail (str "This engine serves no kind "
                                      (:target_kind data)
                                      "; there is no corpus to sweep.")})))
    (when-not (= (:fingerprint_hash data) (:fingerprint-hash rdef))
      (throw (p/problem :wrong-state 409 "Wrong state"
                        {:detail (str "The resident code no longer expresses"
                                      " revision " (:revision data)
                                      " — a sweep compares the stored law to"
                                      " the code in this process, and the code"
                                      " moved. Reboot on the proposal's own"
                                      " commit, or withdraw it.")})))
    rdef))

;; ── the classes ─────────────────────────────────────────────────────

(defn- finding
  "One row-grained finding, wearing the spec's own shape: the row named
  the way every other surface names it (kind, id, and the summary
  template the household wrote), then the class, then the detail that
  belongs to that class alone."
  [rdef row class detail]
  {:kind (name (:kind rdef))
   :id (:id row)
   :summary (summary/render (:summary rdef) (assoc row :kind (:kind rdef)))
   :class class
   :detail detail})

(defn- schema-finding
  "Class 1: stored data the proposed schema will not admit, in malli's
  own humanized words. Never re-worded — the sweep invents no prose."
  [rdef row]
  (when-some [errs (schema/errors (:schema rdef) (:data row))]
    (finding rdef row :schema {:errors (p/wire-value errs)})))

(defn- state-finding
  "Class 3: a row in a state the proposed machine does not declare.
  `store/migrate` plans the UPDATE that rewrites the token and calls
  it destructive; it never says which rows it would rewrite. This
  names them, and it reads the DECLARATION rather than
  `pg/distinct-states`, so the in-memory twin answers it too."
  [rdef row]
  (when-not (contains? (set (:states rdef)) (:state row))
    (finding rdef row :state
             {:state (name (:state row))
              :declared (mapv name (:states rdef))
              ;; :renames keys are spelled either way by authors and
              ;; normalized nowhere; read both rather than miss the
              ;; one clause that says where the row is going
              :renames_to (when-some [t (or (get-in rdef [:renames :states
                                                          (name (:state row))])
                                            (get-in rdef [:renames :states
                                                          (:state row)]))]
                            (name t))})))

(defn- status-of
  "A verdict as the envelope's own three-way partition: concealed,
  refused, or available. A :warning deny is not a refusal (the
  acknowledge protocol owns it) and `verdict` has already collected
  it."
  [v]
  (cond (:hidden v) :hidden (:refused v) :refused :else :available))

(defn- availability-finding
  "Class 2, the reason this namespace exists. Two probes of one row
  with one ctx: the guard vector the row is judged by TODAY (its own
  stamp, resolved through `:judgment-laws`) against the vector it
  would be judged by after the promote. Different verdict, different
  wall, or the same wall with different words — each is a change a
  proposer is entitled to see before they make it."
  [rdef row a current-defn proposed-defn ctx]
  (let [cur (scenario/verdict (:guards current-defn) row nil ctx)
        prop (scenario/verdict (:guards proposed-defn) row nil ctx)
        [sc sp] [(status-of cur) (status-of prop)]]
    (when (or (not= sc sp)
              (not= (:refused cur) (:refused prop))
              (not= (:reason cur) (:reason prop)))
      (finding rdef row :availability
               (cond-> {:action (name (:name a))
                        :under_current (name sc)
                        :under_proposed (name sp)}
                 ;; the household's own sentence, rendered by the same
                 ;; path a refusal takes on the wire — the side that
                 ;; REFUSES speaks, and the new refusal speaks first
                 (or (:refused prop) (:refused cur))
                 (assoc :because (or (:reason prop) (:reason cur))
                        :guard (name (or (:refused prop) (:refused cur))))
                 (seq (:remedies prop))
                 (assoc :remedies (mapv p/wire-value (:remedies prop))))))))

(defn- adopts?
  "Would this row be judged by the resident law the moment the
  proposal promotes? `install-current!` restamps the PRIOR CURRENT
  revision's rows to the new one, and only for a kind declaring
  `:adoption :immediate`; everything else keeps its stamp and its law.
  A row that does not adopt cannot drift, and saying so is a finding's
  absence with a reason behind it."
  [rdef row current-rev]
  (and (= :immediate (:adoption rdef :immediate))
       (= (:law-revision row) current-rev)))

(defn- row-findings
  "Everything one live row has to say about the proposal. The two
  data-shape classes are asked of every row; availability is asked
  only of rows that would actually change law on promote, and only of
  the actions whose guards a stored revision overlays — two cheap
  exits that keep the common case one map lookup, exactly as
  `resolve-action`'s own does."
  [rdef row current-rev ctx]
  (let [proposed? (adopts? rdef row current-rev)]
    (into (into [] (remove nil?) [(schema-finding rdef row)
                                  (state-finding rdef row)])
          (when proposed?
            (for [a (remove :bulk (machine/actions-seq rdef))
                  :when (contains? (:from a) (:state row))
                  :let [current-defn (judgment/resolve-action
                                      rdef a (:law-revision row))]
                  ;; no overlay, no comparison to make: the row is
                  ;; already judged by the resident trees, which is
                  ;; what the proposal is
                  :when (not (identical? current-defn a))
                  :let [f (availability-finding rdef row a current-defn a ctx)]
                  :when f]
              f)))))

;; ── the derivation class, delegated ─────────────────────────────────

(defn- derivation-findings
  "Class 4 by CALL, not by copy: `maintainer/blast-radius` is the
  meter the definition kind's own `:measure` action runs, and its
  report — flips per redefined fact, over a capped sample and an
  uncapped scan — is projected here as findings of class
  `derivation`. Their grain is the FACT rather than the row, which is
  the meter's grain, and the finding says so by carrying the sample
  instead of an id."
  [eng rdef defrow current-fp]
  (let [kind (:kind rdef)
        facts (fp/stale-facts (or (get-in defrow [:data :diff]) {}))
        declared (filterv #(contains? (:derived rdef) (keyword %)) facts)]
    (when (seq declared)
      (let [report (maintainer/blast-radius
                    eng kind
                    {:facts declared
                     :current-fp current-fp
                     :proposed-fp (:fingerprint rdef)
                     :population (when (= :piloted (:state defrow))
                                   (get-in defrow [:data :population :where]))})]
        (into []
              (keep (fn [{:keys [fact flips of sample]}]
                      (when (pos? (long (or flips 0)))
                        {:kind (name kind)
                         :class :derivation
                         :summary (str fact " · " flips " of " of
                                       " rows would change value")
                         :detail {:fact fact :flips flips :of of
                                  :sample (vec sample)}})))
              (:facts report))))))

;; ── the notes ───────────────────────────────────────────────────────

(defn- leaves-of [gd]
  (cons gd (mapcat leaves-of (concat (:all gd) (:any gd)))))

(defn- notes
  "The report's honesty clauses, met before the findings. Each one is
  a punt the spec recorded, said out loud rather than assumed."
  [rdef defrow principal scanned total]
  (let [guards (into [] (comp (remove :bulk)
                              (mapcat :guards)
                              (mapcat leaves-of))
                     (machine/actions-seq rdef))
        beyond (into (sorted-set)
                     (comp (mapcat :reads)
                           (remove scenario/offline-reads)
                           (map str))
                     guards)]
    (into []
          (remove nil?)
          [(str "Probed as " (:id principal)
                " — a guard that reads the principal answers for them alone.")
           (when (seq beyond)
             (str "A snapshot, not a proof: guards on this kind read "
                  (str/join ", " beyond)
                  ", so every verdict here is against today's rows."))
           (when (some :any guards)
             "An {:any …} composite denies as itself, so a finding under one names the composite and not the arm.")
           (when (= "data_law" (get-in defrow [:data :diff_class]))
             (str "The held diff is data-law: the schema and the state machine"
                  " are identical under both laws, so any schema or state"
                  " finding below is pre-existing drift, not a consequence of"
                  " promoting."))
           (when (= :never (:adoption rdef :immediate))
             (str (name (:kind rdef)) " adopts :never — promoting grandfathers"
                  " every existing row under its birth law, so nothing here"
                  " re-judges until each row adopts."))
           (when (< scanned total)
             (str "Scanned " scanned " of " total " rows (the sweep's cap);"
                  " the remainder is unswept."))])))

;; ── the sweep ───────────────────────────────────────────────────────

(defn- enc ^String [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn report
  "One held proposal, swept: the live rows it re-judges differently,
  with the household's own sentence for each.

  `params` is the target kind's collection query grammar VERBATIM
  (`server/collections/parse-query`), so 'sweep only the active
  chores' is a filter and not a flag — including the 422 that names
  every unknown parameter. Pagination is ignored, as it is for the
  worksheet export: a sweep is the whole subset, up to `sweep-cap`."
  [eng defrow principal params]
  (let [rdef (sweepable! eng defrow)
        kind (:kind rdef)
        st (:storage eng)
        current-rev (:current-law rdef)
        current-fp (get (:judgment-laws rdef) current-rev)
        {:keys [conds filters applied]} (coll/parse-query
                                         rdef (dissoc params "page[size]"
                                                      "page[number]"))
        self (str "/api/definitions/" (:id defrow) "/sweep"
                  (when (seq applied)
                    (str "?" (str/join "&" (map (fn [[k v]]
                                                  (str (enc k) "=" (enc v)))
                                                applied)))))
        {:keys [rows total]}
        (store/with-tx st
          (fn [tx]
            {:rows (store/search-rows st tx kind conds {:limit sweep-cap})
             :total (store/count-matching st tx kind conds)}))
        ctx (probe-ctx eng principal)
        findings (into (into []
                             (mapcat #(row-findings rdef (inv/decode-row rdef %)
                                                    current-rev ctx))
                             rows)
                       (derivation-findings eng rdef defrow current-fp))
        totals (merge {:schema 0 :availability 0 :state 0 :derivation 0}
                      (frequencies (map :class findings)))]
    {:waymark "10"
     :kind "law_sweep"
     :self self
     :state "reported"
     :summary (str "Law sweep · " (name kind) " · revision " current-rev
                   " → " (get-in defrow [:data :revision]) " · "
                   (count findings) " finding"
                   (when (not= 1 (count findings)) "s") " over "
                   (count rows) " row" (when (not= 1 (count rows)) "s"))
     :data {:target_kind (name kind)
            :definition (str "/api/definitions/" (:id defrow))
            :from_revision current-rev
            :to_revision (get-in defrow [:data :revision])
            :diff_class (get-in defrow [:data :diff_class])
            :adoption (name (:adoption rdef :immediate))
            :scanned (count rows)
            :of total
            :truncated (< (count rows) total)
            :filters filters
            :notes (notes rdef defrow principal (count rows) total)
            :totals totals
            :findings findings}}))

(defn proposal?
  "Is this decoded definition row one a sweep can answer for? The
  pack asks it to tell a door that SHOULD refuse from one that should
  report, so the obligation and the door read one predicate."
  [row]
  (contains? swept-from (:state row)))
