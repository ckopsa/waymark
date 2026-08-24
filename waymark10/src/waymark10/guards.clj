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

(defn role [role-name & [{:keys [explain requires-token hide]}]]
  (guard {:name (keyword (str "role:" (clojure.core/name role-name)))
          :explain (clojure.core/or explain
                                    (str "Requires role '" (clojure.core/name role-name) "'."))
          :reads [:principal]
          :requires-token (clojure.core/or requires-token
                                           (str "role:" (clojure.core/name role-name)))
          :hide (boolean hide)
          :check (fn [_ _ ctx]
                   (if (contains? (:roles (:principal ctx)) (clojure.core/name role-name))
                     (t/allow) (t/deny)))}))

(defn owner [& [{:keys [field explain hide] :or {field :customer_id}}]]
  (guard {:name (keyword (str "owner:" (clojure.core/name field)))
          :explain (clojure.core/or explain "Only the owner may do this.")
          :reads [:principal]
          :requires-token (str "owner:" (clojure.core/name field))
          :hide (boolean hide)
          :check (fn [row _ ctx]
                   (if (= (str (get-in row [:data field])) (:id (:principal ctx)))
                     (t/allow) (t/deny)))}))

(defn feature-flag [flag & [{:keys [explain]}]]
  (guard {:name (keyword (str "feature-flag:" (clojure.core/name flag)))
          :explain (clojure.core/or explain
                                    (str "Feature '" (clojure.core/name flag) "' is not enabled."))
          :reads [:services.features]
          :requires-token (str "feature:" (clojure.core/name flag))
          :check (fn [_ _ ctx]
                   (let [fs (set (:features (:services ctx)))]
                     (if (clojure.core/or (contains? fs flag)
                                          (contains? fs (clojure.core/name flag)))
                       (t/allow) (t/deny))))}))

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
            :check (fn [_ _ ctx]
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
                       (t/allow)))})))

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
          :check (fn [row inp _ctx]
                   (cond
                     (nil? row) (t/allow {:pending-input (nil? inp)})
                     (get-in row [:data fact]) (t/allow)
                     :else (t/deny)))}))

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
          :check (fn [row _ ctx]
                   (let [actor (clojure.core/when-some [f (:actor-of ctx)]
                                 (f row transition))]
                     (if (clojure.core/and (some? actor)
                                           (= actor (:id (:principal ctx))))
                       (t/deny)
                       (t/allow))))}))

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
;; Both mint their :check's canonical form EXPLICITLY. Every other
;; factory here (role, owner, feature-flag, unless) hands the
;; fingerprint a bare fn, which hashes by printed object identity and
;; therefore moves every JVM run — the recorded stopgap in
;; callable-hash. A guard the sugar mints is the one place that
;; stopgap would be intolerable: a declaration key whose hash drifts
;; is a declaration key that mints a revision for nothing. So the form
;; is data here, built to print exactly as the equivalent defguard
;; body would — which is also what lets grants.clj's own
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

;; ── the code-guard macro ────────────────────────────────────────────

(defmacro defguard
  "A residual code guard whose identity is its canonical printed form
  (the fingerprint hashes the form, never file text):

     (defguard meal-is-listed
       {:judges [:meal_id] :reads [:meal]
        :explain \"That meal is not on the family meal list yet.\"}
       [row inp ctx]
       …body…)"
  [name opts params & body]
  `(def ~name
     (guard (merge ~opts
                   {:name ~(keyword name)
                    :check (with-meta (fn ~params ~@body)
                             {:waymark10/form '~(list* 'fn params body)})}))))
