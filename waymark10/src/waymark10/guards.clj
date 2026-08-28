(ns waymark10.guards
  "Guards: one declaration the fingerprint, the reviewer, the render
  probe, and the enforcement all read. A guard is a map; nothing is
  sniffed — call shape and probe behavior come from :reads/:judges
  declarations, never from arity inspection.

  Guard keys:
    :name            keyword
    :explain         reason template (required, non-blank); {var}
                     placeholders resolve from deny :vars, then
                     :vars-fn garnish; missing keys render literally
    :judges          vector of input field keywords this guard grades
    :reads           vector of external deps beyond the document —
                     :now, :principal, :storage, :transitions,
                     :services.features, kind keywords. Empty ⇒ the
                     verdict is a pure fn of (row, input)
    :accepts         (fn [row] | fn [row ctx] — shape by :reads) → the
                     single-field acceptance set, or nil to decline
    :check           (fn [row inp ctx] → verdict) residual logic
    :when            expression form — the verdict as data (g/expr)
    :vars            expr guards: {kw form} garnish trees;
                     code guards: vector of names :vars-fn supplies
    :vars-fn         (fn [row] → map) render-time garnish
    :open            acknowledged sentence — judged field with nothing
                     advertised (the closure-rule escape hatch)
    :hide            conceal the refusal (404, absent from unavailable)
    :remedies        affordance tokens (:kind/action) that would
                     change the verdict
    :becomes-available-at  (fn [row] → Instant/LocalDate)
    :requires-token  capability token (\"role:manager\")
    :needs-input     probe override; defaults to (check ∧ judges)
    :severity        :refuse | :warning (acknowledgable, E1)

  Composites: {:all [g …]} via g/and — first deny wins, judges/reads
  union; {:any [g …]} via g/or — first allow wins, judges absent (an
  OR advertises nothing), atomic for schema purposes."
  (:refer-clojure :exclude [and or require])
  (:require [clojure.string :as str]
            [waymark10.expr :as expr]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── construction ────────────────────────────────────────────────────

(defn guard
  "Validate and default a guard map. Every invariant here is an
  import-time refusal."
  [{:keys [name explain judges reads accepts check when vars vars-fn
           needs-input severity]
    :as g}]
  (clojure.core/when (clojure.core/or (nil? explain) (str/blank? explain))
    (throw (t/definition-error "a guard explains itself: :explain is required")))
  (let [severity (clojure.core/or severity :refuse)]
    (clojure.core/when-not (#{:refuse :warning} severity)
      (throw (t/definition-error (str "guard severity " severity " is not :refuse/:warning"))))
    (clojure.core/when (clojure.core/and accepts (not= 1 (count judges)))
      (throw (t/definition-error
              "accepts= is the single-field acceptance set; a multi-field admission is a relation")))
    (clojure.core/when (clojure.core/and (nil? accepts) (nil? check) (nil? when))
      (throw (t/definition-error
              "a guard must advertise (accepts/when) or enforce (check) — this one does neither")))
    (clojure.core/when (clojure.core/and vars-fn (empty? vars))
      (throw (t/definition-error ":vars-fn must be accompanied by the :vars names it supplies")))
    (clojure.core/when (fn? vars)
      (throw (t/definition-error "a callable belongs in :vars-fn; :vars declares the names")))
    (merge g
           {:name (clojure.core/or name :guard)
            :judges (vec judges)
            :reads (vec reads)
            :severity severity
            ;; a verdict that grades input — code or expression —
            ;; cannot decide without it
            :needs-input (if (some? needs-input)
                           (boolean needs-input)
                           (clojure.core/and (clojure.core/or (some? check) (some? when))
                                             (boolean (seq judges))))})))

(defn leaf? [g] (clojure.core/and (nil? (:all g)) (nil? (:any g))))

(defn iter-leaves
  "The schema-visible leaves: :all recurses; :any is atomic (an OR
  cannot tighten a schema); a leaf is itself."
  [g]
  (cond
    (:all g) (mapcat iter-leaves (:all g))
    (:any g) [g]
    :else [g]))

;; ── composites ──────────────────────────────────────────────────────

(defn- dedup [xs] (vec (distinct xs)))

(defn and
  "All parts must allow; the first deny wins and supplies the reason
  (its own leaf is the denier)."
  [& parts]
  {:all (vec parts)
   :name (keyword (str/join "&" (map (comp clojure.core/name :name) parts)))
   :explain (:explain (first parts))
   :judges (dedup (mapcat :judges parts))
   :reads (dedup (mapcat :reads parts))
   :hide (boolean (some :hide parts))
   :severity :refuse
   :needs-input (boolean (some :needs-input parts))})

(defn or
  "Any part may allow; if none does, the first deny supplies the
  reason. Judges absent — an OR advertises nothing."
  [& parts]
  {:any (vec parts)
   :name (keyword (str/join "|" (map (comp clojure.core/name :name) parts)))
   :explain (:explain (first parts))
   :judges []
   :reads (dedup (mapcat :reads parts))
   :hide (every? :hide parts)
   :severity :refuse
   :needs-input (every? :needs-input parts)})

;; ── evaluation ──────────────────────────────────────────────────────

(defn admitted
  "The acceptance set for a render/enforcement moment: nil when the
  guard declines to constrain. Call shape is declared by :reads,
  never sniffed."
  [g row ctx]
  (clojure.core/when-some [accepts (:accepts g)]
    (clojure.core/when-some [out (if (seq (:reads g))
                                   (accepts row ctx)
                                   (accepts row))]
      (vec out))))

(defn- member?
  "Acceptance membership with the string fallback: ids cross the wire
  as strings."
  [value allowed]
  (boolean
   (clojure.core/or (some #(= value %) allowed)
                    (let [s (str value)]
                      (some #(= s (str %)) allowed)))))

(defn- expr-check
  "The check a :when tree compiles to."
  [g row inp ctx]
  (let [scope {:data (:data row) :input inp :now (:now ctx)}]
    (if (expr/evaluate (:when g) scope)
      (t/allow)
      (t/deny {:vars (not-empty
                      (into {}
                            (keep (fn [[k form]]
                                    (try [k (expr/evaluate form scope)]
                                         (catch Exception _ nil))))
                            (:vars g)))}))))

(declare evaluate)

(defn- evaluate-leaf [g row inp ctx]
  (clojure.core/or
   ;; 1. acceptance enforcement — the advertised set IS the law
   (clojure.core/when (clojure.core/and (some? inp) (:accepts g))
     (let [field (first (:judges g))
           value (get inp field)]
       (clojure.core/when (some? value)
         (clojure.core/when-some [allowed (admitted g row ctx)]
           (clojure.core/when-not (member? value allowed)
             [(t/deny {:vars {field value}}) g])))))
   ;; 2. the residual check, with the probe short-circuit: a check
   ;;    that grades input cannot decide without it
   (cond
     ;; a require gate at CREATE (design §24, the phase-2 promise
     ;; delivered): no row exists, so the bound spec's own law
     ;; computes over the validated input — (var :f) reads the create
     ;; body, exactly what the maintained fact would store. A spec
     ;; beyond the pure grammar (an aggregate) has nothing to compute
     ;; before the row exists and allows, as ever.
     (clojure.core/and (:require g) (nil? row)
                       (get-in g [:require/spec :expr]))
     (if (nil? inp)
       [(t/allow {:pending-input true}) g]
       [(if (expr/evaluate (get-in g [:require/spec :expr])
                           {:vars (assoc inp :now (:now ctx))})
          (t/allow)
          (t/deny))
        g])

     (:when g)
     (if (clojure.core/and (= :probe (:mode ctx)) (nil? inp) (:needs-input g))
       [(t/allow {:pending-input true}) g]
       [(expr-check g row inp ctx) g])

     (:check g)
     (if (clojure.core/and (= :probe (:mode ctx)) (nil? inp) (:needs-input g))
       [(t/allow {:pending-input true}) g]
       [((:check g) row inp ctx) g])

     :else [(t/allow) g])))

(defn- evaluate-relation [g row inp ctx]
  (if (nil? inp)
    [(t/allow {:pending-input true}) g]
    (let [values (mapv #(get inp %) (:judges g))]
      (if (some nil? values)
        [(t/allow) g]   ; required-ness is the schema's job
        (if-some [op (:op g)]
          (let [[a b] values
                c (compare a b)
                ok (case op
                     <  (neg? c) <= (not (pos? c))
                     >  (pos? c) >= (not (neg? c))
                     == (zero? c))]
            (if ok [(t/allow) g]
                [(t/deny {:vars (zipmap (:judges g) values)}) g]))
          (if-some [allowed (admitted g row ctx)]
            (let [match? (fn [tup]
                           (clojure.core/and
                            (= (count tup) (count values))
                            (every? (fn [[v a]]
                                      (clojure.core/or (= v a) (= (str v) (str a))))
                                    (map vector values tup))))]
              (if (some match? allowed)
                [(t/allow) g]
                [(t/deny {:vars (zipmap (:judges g) values)}) g]))
            [(t/allow) g]))))))

(defn evaluate
  "→ [verdict denier]. The denier is the leaf whose explain renders
  the refusal."
  [g row inp ctx]
  (cond
    (:all g)
    (loop [parts (:all g) pending false]
      (if-some [p (first parts)]
        (let [[v d] (evaluate p row inp ctx)]
          (if (t/deny? v)
            [v d]
            (recur (rest parts) (clojure.core/or pending (t/pending-input? v)))))
        [(t/allow {:pending-input pending}) g]))

    (:any g)
    (loop [parts (:any g) first-deny nil]
      (if-some [p (first parts)]
        (let [[v d] (evaluate p row inp ctx)]
          (if (t/allow? v)
            [v g]
            (recur (rest parts) (clojure.core/or first-deny [v d]))))
        first-deny))

    (:relation g) (evaluate-relation g row inp ctx)
    :else (evaluate-leaf g row inp ctx)))

;; ── reasons and structured hope ─────────────────────────────────────

(defn listed
  "A short, ordered rendering of what a refusal found — EVERY
  offender, not the first, because an author fixing them one round
  trip at a time is an author wasting a morning. Sorted so the same
  set of offenders always renders the same sentence, and `pr-str`'d so
  a blank or a stray space is visible rather than swallowed.

  THE ONE COPY (waymark-g4e). `outcome`, `insight` and `ranking_note`
  each carried a private one, identical to the character, and the
  trap in all three was `clojure.core/distinct`: it destructures
  `[f :as xs]`, which calls `nth`, which a `PersistentHashSet` does
  not support. A caller that built its offenders with `(into #{} …)`
  therefore THREW where it meant to refuse, the router turned the
  throw into a 500, and the refusal it was about to name was lost
  (waymark-8gc did exactly this on 2026-08-28). `seq` first is a
  no-op for every vector caller and is what makes a set an ordinary
  argument; nil renders as the empty string rather than throwing,
  because a guard's sentence must never be the thing that fails."
  [xs]
  (str/join ", " (map pr-str (sort (distinct (seq xs))))))

(def ^:private placeholder #"\{([A-Za-z0-9_.-]+)\}")

(defn- format-map
  "str.format_map over a safe dict: missing keys render literally —
  a reason must never crash."
  [template vars]
  (str/replace template placeholder
               (fn [[whole k]]
                 (let [kw (keyword k)]
                   (if (contains? vars kw)
                     (str (get vars kw))
                     (if (contains? vars k)
                       (str (get vars k))
                       whole))))))

(defn render-reason
  "The denier's explain, formatted from deny vars over vars-fn
  garnish (deny vars win). Garnish must never block: vars-fn errors
  are swallowed."
  [g deny row]
  (let [garnish (clojure.core/when (clojure.core/and (:vars-fn g) (some? row))
                  (try ((:vars-fn g) row) (catch Exception _ nil)))]
    (format-map (:explain g) (merge garnish (:vars deny)))))

(defn becomes-available
  "Structured hope: {:at iso} from deny retry-at or the declared
  clock, else {:requires token}, else nil."
  [g deny row]
  (clojure.core/or
   (clojure.core/when-some [at (:retry-at deny)] {:at (str at)})
   (clojure.core/when-some [f (:becomes-available-at g)]
     (clojure.core/when (some? row)
       (try (clojure.core/when-some [at (f row)] {:at (str at)})
            (catch Exception _ nil))))
   (clojure.core/when-some [tok (:requires-token g)] {:requires tok})))

;; ── declared verdicts: g/expr ───────────────────────────────────────

(defn expr
  "The verdict as data: :when is a guard-scope expression form; the
  fingerprint stores the tree, the reviewer diffs the leaf, and (9.0)
  the judgment overlay evaluates any stored revision. :judges derives
  from the tree's (input …) reads, :reads from (now) — declared by
  the law itself."
  [{:keys [when vars judges reads name] :as opts}]
  (clojure.core/when-not (seq? when)
    (throw (t/definition-error "guard/expr takes a :when expression form")))
  (let [w (expr/normalize when)
        ps (concat (expr/problems w) (expr/guard-scope-problems w))
        _ (clojure.core/when (seq ps)
            (throw (t/definition-error (str "guard " name " :when — " (first ps))
                                       {:problems ps})))
        vars (into {}
                   (map (fn [[k form]]
                          (let [f (expr/normalize form)
                                ps (concat (expr/problems f)
                                           (expr/guard-scope-problems f))]
                            (clojure.core/when (seq ps)
                              (throw (t/definition-error
                                      (str "guard " name " :vars " k " — " (first ps))
                                      {:problems ps})))
                            [k f])))
                   vars)
        winfo (expr/info w)]
    (guard (merge (dissoc opts :when :vars)
                  {:when w
                   :vars vars
                   :judges (clojure.core/or judges (vec (sort (:inputs winfo))))
                   :reads (clojure.core/or reads
                                           (if (clojure.core/or (:uses-now winfo)
                                                                (some (comp :uses-now expr/info) (vals vars)))
                                             [:now] []))}))))

(defn relation
  "Multi-field admission: :accepts produces admissible tuples, or :op
  compares exactly two judged fields."
  [{:keys [judges accepts op] :as opts}]
  (clojure.core/when (< (count judges) 2)
    (throw (t/definition-error "a relation judges at least two fields; one field is a plain guard")))
  (clojure.core/when (= (nil? accepts) (nil? op))
    (throw (t/definition-error "a relation takes exactly one of :accepts (tuples) or :op (comparison)")))
  (clojure.core/when op
    (clojure.core/when-not ('#{< <= > >= ==} op)
      (throw (t/definition-error (str "relation op " op " is not one of < <= > >= =="))))
    (clojure.core/when-not (= 2 (count judges))
      (throw (t/definition-error "a comparison relation judges exactly two fields"))))
  (clojure.core/when (clojure.core/or (nil? (:explain opts)) (str/blank? (:explain opts)))
    (throw (t/definition-error "a guard explains itself: :explain is required")))
  (merge opts
         {:relation true
          :name (clojure.core/or (:name opts) :relation)
          :judges (vec judges)
          :reads (vec (:reads opts))
          :severity (clojure.core/or (:severity opts) :refuse)
          :needs-input true}))

;; ── built-ins ───────────────────────────────────────────────────────
;; Every factory here mints its :check's canonical printed form
;; EXPLICITLY, the discipline not-the-field and is-the-field draw at
;; the bottom of this file and waymark-j82 finished. A factory-minted
;; guard is one the app author cannot rewrite: leaving it formless
;; would file its law under its ADDRESS (callable-hash's honest
;; fallback) and warn an author about code that is not theirs. So the
;; form is data here, built to print exactly as the equivalent
;; defguard body would — the argument that distinguishes one call from
;; the next (the role, the field, the flag) rides INSIDE it, so
;; changing it is a change of law and not merely of a guard's name.

(defn role [role-name & [{:keys [explain requires-token hide]}]]
  (guard {:name (keyword (str "role:" (clojure.core/name role-name)))
          :explain (clojure.core/or explain
                                    (str "Requires role '" (clojure.core/name role-name) "'."))
          :reads [:principal]
          :requires-token (clojure.core/or requires-token
                                           (str "role:" (clojure.core/name role-name)))
          :hide (boolean hide)
          :check (with-meta
                   (fn [_ _ ctx]
                     (if (contains? (:roles (:principal ctx)) (clojure.core/name role-name))
                       (t/allow) (t/deny)))
                   {:waymark10/form
                    (list 'fn '[_ _ ctx]
                          (list 'if (list 'contains? '(:roles (:principal ctx))
                                          (clojure.core/name role-name))
                                '(t/allow) '(t/deny)))})}))

(defn owner [& [{:keys [field explain hide] :or {field :customer_id}}]]
  (guard {:name (keyword (str "owner:" (clojure.core/name field)))
          :explain (clojure.core/or explain "Only the owner may do this.")
          :reads [:principal]
          :requires-token (str "owner:" (clojure.core/name field))
          :hide (boolean hide)
          :check (with-meta
                   (fn [row _ ctx]
                     (if (= (str (get-in row [:data field])) (:id (:principal ctx)))
                       (t/allow) (t/deny)))
                   {:waymark10/form
                    (list 'fn '[row _ ctx]
                          (list 'if (list '= (list 'str (list 'get-in 'row [:data field]))
                                          '(:id (:principal ctx)))
                                '(t/allow) '(t/deny)))})}))

(defn feature-flag [flag & [{:keys [explain]}]]
  (guard {:name (keyword (str "feature-flag:" (clojure.core/name flag)))
          :explain (clojure.core/or explain
                                    (str "Feature '" (clojure.core/name flag) "' is not enabled."))
          :reads [:services.features]
          :requires-token (str "feature:" (clojure.core/name flag))
          :check (with-meta
                   (fn [_ _ ctx]
                     (let [fs (set (:features (:services ctx)))]
                       (if (clojure.core/or (contains? fs flag)
                                            (contains? fs (clojure.core/name flag)))
                         (t/allow) (t/deny))))
                   {:waymark10/form
                    (list 'fn '[_ _ ctx]
                          (list 'let ['fs '(set (:features (:services ctx)))]
                                (list 'if (list 'or (list 'contains? 'fs flag)
                                                (list 'contains? 'fs
                                                      (clojure.core/name flag)))
                                      '(t/allow) '(t/deny))))})}))

(defn rate-limit
  "Budget consumed only in :invoke mode — probe and dry-run never
  spend. Deny carries :retry-at. The coordinator is the engine's
  (:rate ctx) hook (phase 2); outside an engine the guard allows."
  [limit per-seconds & [{:keys [explain scope]}]]
  (let [gname (str "rate-limit:" limit "/" per-seconds "s")
        key-scope (clojure.core/or scope gname)]
    (guard {:name (keyword gname)
            :explain (clojure.core/or explain "Rate limit reached; try again shortly.")
            :reads [:now]
            :check (with-meta
                     (fn [_ _ ctx]
                       (if-some [rate (:rate ctx)]
                         (let [k (str key-scope ":" (:id (:principal ctx)))
                               cutoff (.minusSeconds ^java.time.Instant (:now ctx) (long per-seconds))
                               hits ((:window rate) k cutoff)]
                           (if (>= (count hits) limit)
                             (t/deny {:retry-at (.plusSeconds ^java.time.Instant (first hits)
                                                              (long per-seconds))})
                             (do (clojure.core/when (= :invoke (:mode ctx))
                                   ((:hit rate) k (:now ctx)))
                                 (t/allow))))
                         (t/allow)))
                     {:waymark10/form
                      (list 'fn '[_ _ ctx]
                            (list 'if-some ['rate '(:rate ctx)]
                                  (list 'let ['k (list 'str key-scope ":"
                                                       '(:id (:principal ctx)))
                                              'cutoff (list 'minus-seconds '(:now ctx)
                                                            (long per-seconds))
                                              'hits '((:window rate) k cutoff)]
                                        (list 'if (list '>= '(count hits) (long limit))
                                              (list 't/deny
                                                    {:retry-at (list 'plus-seconds '(first hits)
                                                                     (long per-seconds))})
                                              '(do (when (= :invoke (:mode ctx))
                                                     ((:hit rate) k (:now ctx)))
                                                   (t/allow))))
                                  '(t/allow)))})})))

;; ── fact-gated requirement ──────────────────────────────────────────

(defn require
  "Gate on a maintained boolean derivation: the stored fact judges the
  row (no recompute). The declaring resource's normalize step binds
  the fact's spec (:require/spec) so refusals can render the spec's
  explain and vars; the create path (row nil) computes from input in
  phase 2."
  [fact & [{:keys [explain hide remedies severity]}]]
  (guard {:name (keyword (str "require:" (clojure.core/name fact)))
          :require fact
          :own-explain? (some? explain)
          :explain (clojure.core/or explain
                                    (str "Not yet: "
                                         (str/replace (clojure.core/name fact) "_" " ")
                                         " does not hold."))
          :reads [:storage]
          :hide (boolean hide)
          :remedies (vec remedies)
          :severity (clojure.core/or severity :refuse)
          :check (with-meta
                   (fn [row inp _ctx]
                     (cond
                       (nil? row) (t/allow {:pending-input (nil? inp)})
                       (get-in row [:data fact]) (t/allow)
                       :else (t/deny)))
                   {:waymark10/form
                    (list 'fn '[row inp _ctx]
                          (list 'cond
                                '(nil? row) '(t/allow {:pending-input (nil? inp)})
                                (list 'get-in 'row [:data fact]) '(t/allow)
                                :else '(t/deny)))})}))

;; ── history-judged guards ───────────────────────────────────────────

(defn unless
  "Bar the principal a log fact names: unless (actor-of :propose).
  The (:actor-of ctx) engine hook resolves the last actor of the
  named transition (phase 2 wires it; nil actor allows)."
  [transition & [{:keys [explain hide name]}]]
  (clojure.core/when (str/blank? (clojure.core/name transition))
    (throw (t/definition-error "unless names a transition of this resource")))
  (guard {:name (clojure.core/or name (keyword (str "unless:actor-of:"
                                                    (clojure.core/name transition))))
          :unless transition
          :explain (clojure.core/or explain
                                    (str "You performed "
                                         (str/replace (clojure.core/name transition) "_" " ")
                                         " on this resource; a different principal must do this."))
          :reads [:transitions :principal]
          :hide (boolean hide)
          :check (with-meta
                   (fn [row _ ctx]
                     (let [actor (clojure.core/when-some [f (:actor-of ctx)]
                                   (f row transition))]
                       (if (clojure.core/and (some? actor)
                                             (= actor (:id (:principal ctx))))
                         (t/deny)
                         (t/allow))))
                   {:waymark10/form
                    (list 'fn '[row _ ctx]
                          (list 'let ['actor (list 'when-some ['f '(:actor-of ctx)]
                                                   (list 'f 'row transition))]
                                '(if (and (some? actor)
                                          (= actor (:id (:principal ctx))))
                                   (t/deny)
                                   (t/allow))))})}))

(defn four-eyes
  "Whoever performed `of` cannot do this."
  [of & [{:keys [explain hide]}]]
  (unless of {:explain (clojure.core/or explain
                                        (str "Whoever performed "
                                             (str/replace (clojure.core/name of) "_" " ")
                                             " cannot do this; someone else must."))
              :hide hide
              :name (keyword (str "four-eyes:" (clojure.core/name of)))}))

;; ── field-named principals (the decider's walls) ────────────────────
;; four-eyes above is a TRANSITION-HISTORY wall: it asks (:actor-of
;; ctx) who performed some earlier edge. A standalone decision has no
;; such edge — its requester is stamped by :on-create, before any
;; transition exists to be the actor of — so the wall it needs reads
;; the ROW'S OWN FIELD instead. grants.clj drew that line by hand
;; first (someone-else-decides); these two factories are that hand
;; drawing, generalized, so the :decision sugar and the hand-written
;; guard are one law with two spellings.
;;
;; Both mint their :check's canonical form EXPLICITLY — the rule these
;; two set first and waymark-j82 carried to every factory above (role,
;; owner, feature-flag, require, unless, rate-limit). A guard the sugar
;; mints is the one place a formless :check would be intolerable: it
;; would file the law under the address callable-hash falls back to,
;; and warn an author about a body they did not write and cannot fix.
;; So the form is data here, built to print exactly as the equivalent
;; defguard body would — which is also what lets grants.clj's own
;; someone-else-decides become a call to this factory without moving
;; approval_request's fingerprint by one byte.

(defn not-the-field
  "The field four-eyes wall: whoever this row names in `field` cannot
  be the principal acting now. \"Not you\", where you is written down
  in the document rather than remembered from the log."
  [field & [{:keys [explain hide name]}]]
  (guard
   (cond-> {:name (clojure.core/or name
                                   (keyword (str "not-the-" (clojure.core/name field))))
            :reads [:principal]
            :explain (clojure.core/or
                      explain
                      (str "The "
                           (str/replace (clojure.core/name field) "_" " ")
                           " of this row cannot be the one to decide it; "
                           "another principal must."))
            :check (with-meta
                     (fn [row _inp ctx]
                       (if (= (:id (:principal ctx)) (get-in row [:data field]))
                         (t/deny) (t/allow)))
                     {:waymark10/form
                      (list 'fn '[row _inp ctx]
                            (list 'if
                                  (list '= '(:id (:principal ctx))
                                        (list 'get-in 'row [:data field]))
                                  '(t/deny) '(t/allow)))})}
     hide (assoc :hide true))))

(defn is-the-field
  "The named-decider wall: only the principal this row names in
  `field` may act. \"The person this row names decides it\" — the
  shape a household means by a guardian, an assignee, an addressee,
  and the one neither four-eyes nor role can say. A row naming
  nobody names no decider, and the wall refuses rather than opening:
  an unassigned verdict waits for an assignment."
  [field & [{:keys [explain hide name]}]]
  (guard
   (cond-> {:name (clojure.core/or name
                                   (keyword (str "is-the-" (clojure.core/name field))))
            :reads [:principal]
            :explain (clojure.core/or
                      explain
                      (str "Only this row's "
                           (str/replace (clojure.core/name field) "_" " ")
                           " may decide it."))
            :check (with-meta
                     (fn [row _inp ctx]
                       (let [named (get-in row [:data field])]
                         (if (clojure.core/and (some? named)
                                               (= (:id (:principal ctx)) named))
                           (t/allow) (t/deny))))
                     {:waymark10/form
                      (list 'fn '[row _inp ctx]
                            (list 'let ['named (list 'get-in 'row [:data field])]
                                  (list 'if (list 'and '(some? named)
                                                  '(= (:id (:principal ctx)) named))
                                        '(t/allow) '(t/deny))))})}
     hide (assoc :hide true))))

;; ── the grantable person-wall (waymark-sfe) ─────────────────────────
;;
;; THE OWNER'S RULING, 2026-08-28: "The whole reason we have the access
;; controls we have is so that I can ask you to do what I want when I
;; want. It doesn't make sense to disallow it, it just makes sense to
;; permission it."
;;
;; Four kinds had written the same wall by hand — a pure function of
;; the principal's type, refusing EVERY agent outright at the verdict
;; and affirmation doors. Each stood BESIDE the grant system rather
;; than through it, so a person could not delegate "decline these
;; thirty-one with these words" even though the grants machine already
;; expresses exactly that scope. This factory is those four hands,
;; generalized: a person still passes; an agent passes only when the
;; grant it presented ADMITS this action on this kind (and on this
;; row, when the entry carries a :filter).
;;
;; It reuses the projection's own admission — `(:grant ctx)` is the
;; visibility's `{:id :action? :row?}`, minted by `grants/visibility`
;; and threaded through the invoke ctx and the render probe alike — so
;; the wall asks the SAME question the router's concealment asks
;; instead of re-deriving what a scope means. A ctx carrying no grant
;; (an unscoped read, the engine's own actor, a check-tier scenario)
;; refuses every agent, which is the posture the walls had before.
;;
;; FOUR EYES STAYS ABSOLUTE. `:own-field` names the field recording
;; whose hand wrote the row — `written_by`, `composed_by`,
;; `authored_by` — and an agent that finds itself there is refused
;; grant or no grant. It is spelled ON THIS WALL rather than as a
;; separate `not-the-field` beside it because that one refuses EVERY
;; principal, and a person re-affirming a value they wrote themselves
;; is the ordinary path; what the grant opens for an agent, four eyes
;; must close again for the agent alone.

(def ^:private no-grant-sentence
  "The refusal that names the fix. A composer that has just been
  refused is one approval_request away from being allowed, and a
  sentence that did not say the token would be a sentence that sent it
  to read source."
  "An agent decides here only under a grant that admits %s — ask for one (the approval_request door) and a person approves it.")

(defn- own-row-sentence [field]
  (str "And this row names you as its "
       (str/replace (clojure.core/name field) "_" " ")
       ". Whoever wrote a row never answers it — that wall is four eyes, "
       "and no grant opens it. Another hand decides this one."))

(defn unless-granted
  "The person-wall, made grantable: a person (or the engine's own
  system actor) passes; an AGENT passes only under a presented grant
  whose scope admits `kind`.`action` — and, when that scope entry
  carries a `:filter`, only on a row inside it.

  `opts`:
    :explain    the household's own sentence, required — it is what a
                reader hits, and the grant clause is appended to it
    :name       the guard's name (keep the kind's existing one, so
                scenarios and clients keep reading the same word)
    :own-field  the field naming whose hand wrote the row; an agent
                that wrote it is refused grant or no grant
    :open       the acknowledged sentence, when the kind wrote one
    :hide       conceal the refusal, as everywhere

  ONE THING IT IS NOT: the concealment check. At the wire, an action a
  scope does not name has already 404'd (`router/check-action!`) before
  a guard runs, so the refusal below is the one a scoped CROSS-WRITE
  meets, the one a scenario reads at declaration time, and the one an
  engine-internal invoke draws. The wall and the projection agree by
  construction because they consult the same closure."
  [kind action {:keys [explain name own-field open hide]}]
  (clojure.core/when (clojure.core/or (nil? explain) (str/blank? explain))
    (throw (t/definition-error
            "unless-granted carries the kind's own sentence: :explain is required")))
  (let [token (str (clojure.core/name kind) "." (clojure.core/name action))
        no-grant (format no-grant-sentence token)
        own-said (clojure.core/when own-field (own-row-sentence own-field))]
    (guard
     (cond-> {:name (clojure.core/or name
                                     (keyword (str "unless-granted:" token)))
              :reads [:principal :grant]
              :vars [:problem]
              :explain (str explain "\n\n{problem}")
              :check
              (with-meta
                (fn [row _inp ctx]
                  (let [p (:principal ctx)
                        g (:grant ctx)
                        mine (clojure.core/when own-field
                               (get-in row [:data own-field]))]
                    (cond
                      (not= :agent (:type p)) (t/allow)

                      (clojure.core/and (some? mine) (= mine (:id p)))
                      (t/deny {:vars {:problem own-said}})

                      (clojure.core/and (some? g)
                                        ((:action? g) kind action)
                                        (clojure.core/or
                                         (nil? (:id row))
                                         ((:row? g) kind (:id row))))
                      (t/allow)

                      :else (t/deny {:vars {:problem no-grant}}))))
                {:waymark10/form
                 (list 'fn '[row _inp ctx]
                       (list 'let ['p '(:principal ctx)
                                   'g '(:grant ctx)
                                   'mine (clojure.core/when own-field
                                           (list 'get-in 'row
                                                 [:data own-field]))]
                             (list 'cond
                                   '(not= :agent (:type p)) '(t/allow)
                                   '(and (some? mine) (= mine (:id p)))
                                   (list 't/deny {:vars {:problem own-said}})
                                   (list 'and '(some? g)
                                         (list '(:action? g) kind action)
                                         (list 'or '(nil? (:id row))
                                               (list '(:row? g) kind '(:id row))))
                                   '(t/allow)
                                   :else
                                   (list 't/deny {:vars {:problem no-grant}}))))})}
       open (assoc :open open)
       hide (assoc :hide true)))))

;; ── the code-guard macro ────────────────────────────────────────────

(defmacro defguard
  "A residual code guard whose identity is its canonical printed form
  (the fingerprint hashes the form, never file text):

     (defguard meal-is-listed
       {:judges [:meal_id] :reads [:meal]
        :explain \"That meal is not on the family meal list yet.\"}
       [row inp ctx]
       …body…)

  The captured form is gensym-canonicalized (expr/canonical-gensyms)
  before it is stored: the body arrives AFTER READ, so a `#(…)`
  inside it is already `(fn* [p1__10794#] …)` and that counter is
  global to the load. Storing the raw shape made the guard's identity
  a function of everything compiled before it (waymark-j82)."
  [name opts params & body]
  `(def ~name
     (guard (merge ~opts
                   {:name ~(keyword name)
                    :check (with-meta (fn ~params ~@body)
                             {:waymark10/form
                              '~(expr/canonical-gensyms
                                 (list* 'fn params body))})}))))
