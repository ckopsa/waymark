(ns waymark10.fingerprint
  "The canonical projection of a resource declaration — the law as the
  reviewer, the diff gate, and the overlays read it.

  The fingerprint is a projection of the same declaration map the
  engine consumes, never a second description. Expression laws are
  stored as wire trees (waymark10.wire), never hashed — the diff pins
  the exact leaf that moved and any stored revision reads back into an
  evaluable form. Only the imperative residue (handlers, code guards)
  is hashed, by canonical printed form — and where there is no form to
  print, by the ADDRESS the declaration carries the code at, never by
  the resident object (waymark-j82; see callable-hash for the trade).
  The fingerprint must be a function of the declaration and of nothing
  else: not of load order, not of a class counter, not of a JVM run.

  Ported semantics (waymark9 core/fingerprint.py):
  - classify-path → :shape / :judgment / :truth / :advertisement,
    innermost owning surface wins; derived explain/vars garnish is
    advertisement; unmatched defaults to :truth (an unclassified
    change is conservatively a change of meaning).
  - classify-diff → :data-law when EVERY changed path is overlayable
    (a derivation's tolerance/expr/edge-where, or a recoverable leaf
    of a top-level expression guard), else :code-or-shape.
  - stale-facts → derived facts whose semantic surface moved."
  (:require [clojure.string :as str]
            [waymark10.expr :as expr]
            [waymark10.measures :as measures]
            [waymark10.money :as money]
            [waymark10.schema :as schema]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

;; ── projection helpers ──────────────────────────────────────────────

(defn- form-tree [form]
  (wire/form->wire (expr/normalize form)))

(def ^:private opaque-address
  "The salt that makes an address-hash unmistakable in a diff: this
  leaf says WHERE opaque code sits, not what it does."
  "waymark10.opaque-code@")

(defn callable-hash
  "Identity for the imperative residue, at the address the declaration
  carries it. Strings pass through (already a hash); a callable
  carrying :waymark10/form — what defhandler, defguard and the
  hand-built sugar forms supply — hashes by that canonical printed
  form, byte-for-byte as it always has.

  A BARE fn has no content to hash, so it hashes by its ADDRESS: the
  kind, action and slot the declaration puts it in. That is the same
  honesty the decision record already keeps about opaque code — the
  fingerprint says where the opacity is, never what it does — and the
  trade is recorded loudly here: swapping one bare fn's body for
  another at the same address is INVISIBLE to the fingerprint. It was
  exactly as invisible before (a printed object identity says nothing
  about a body either); what changes is that it is now STABLE.

  Before waymark-j82 a bare fn hashed by `pr-str` of the object —
  #object[…$fn__12873 0x7698a3d9 …] — a compiler-assigned class
  number and a JVM identity hash, both functions of everything loaded
  before it. Nine workqueue10 kinds therefore hashed differently on
  every boot with no code change at all, and production minted 73 law
  revisions that were almost entirely boot noise. An identity that
  moves without the law moving is not an identity.

  The stopgap posture stands, one step further along: the checks now
  WARN on a bare fn in a fingerprinted slot (checks/[opaque-residue]),
  and a strict mode may later refuse it. The cure is the same one it
  always was — declare it with defguard/defhandler, or mint its form
  by hand the way guards/not-the-field does — which upgrades the leaf
  from an address to a law."
  [address h]
  (cond
    (nil? h) nil
    (string? h) h
    :else (if-some [form (:waymark10/form (meta h))]
            (wire/sha256-hex (pr-str form))
            (wire/sha256-hex (str opaque-address address)))))

(defn- remedy-fp
  "A remedy's stored spelling: a bare token as \"kind.action\", a
  bound remedy as {door, id, input} with each binding printed."
  [r]
  (cond
    (map? r) (cond-> (sorted-map "door" (remedy-fp (:door r)))
               (:id r) (assoc "id" (pr-str (:id r)))
               (:fields r) (assoc "fields" (mapv #(if (keyword? %) (name %) (pr-str %))
                                                 (:fields r)))
               (:input r) (assoc "input" (into (sorted-map)
                                               (map (fn [[k f]] [(name k) (pr-str f)]))
                                               (:input r))))
    (qualified-keyword? r) (str (namespace r) "." (name r))
    :else (name r)))

(defn- guard-fp [address g]
  (if-some [w (:when g)]
    ;; expression guard: the stored tree IS the law; check is nil.
    ;; :errors, the per-field sentences, projects only when declared,
    ;; so a guard without them hashes as it always did
    (cond-> {"name"           (name (:name g))
             "expr"           (form-tree w)
             "vars_exprs"     (into (sorted-map)
                                    (map (fn [[k f]] [(name k) (form-tree f)]))
                                    (:vars g))
             "explain"        (:explain g)
             "remedies"       (mapv remedy-fp (:remedies g []))
             "hide"           (boolean (:hide g))
             "severity"       (name (:severity g :refuse))
             "requires_token" (:requires-token g)
             "check"          nil}
      (seq (:errors g)) (assoc "errors" (into (sorted-map)
                                              (map (fn [[k s]] [(name k) s]))
                                              (:errors g)))
      ;; the unseen-evidence sentences, likewise only when declared
      (seq (:hidden g)) (assoc "hidden" (into (sorted-map)
                                              (map (fn [[k s]] [(name k) s]))
                                              (:hidden g)))
      ;; :pending projects only when it is not the default :allow
      (= :deny (:pending g)) (assoc "pending" "deny"))
    {"name"           (name (:name g))
     "check"          (callable-hash (str address ".check")
                                     (or (:check g) (:accepts g)))
     "explain"        (:explain g)
     "remedies"       (mapv remedy-fp (:remedies g []))
     "hide"           (boolean (:hide g))
     "severity"       (name (:severity g :refuse))
     "requires_token" (:requires-token g)}))

(defn- aggregate-fp
  "The aggregate facet, canonical: the edge by name, where sets as
  sorted vectors — a set has no order, a fingerprint must. :sum
  (batch C) adds :of, the summed target field — edge-identity class,
  like the edge names — and :when-empty, the empty-sum semantics
  (projected only when declared, so every default-spelled sum hashes
  byte-identical to before the option existed)."
  [c]
  (cond-> {}
    (:related c)    (assoc "related" (name (:related c)))
    (:owns c)       (assoc "owns" (name (:owns c)))
    (:of c)         (assoc "of" (name (:of c)))
    (:when-empty c) (assoc "when_empty" (name (:when-empty c)))
    (:where c)      (assoc "where" (into (sorted-map)
                                         (map (fn [[f vs]]
                                                [(name f) (vec (sort-by str vs))]))
                                         (:where c)))))

(defn- derived-fp [address d]
  (cond-> {"over" (mapv name (:over d))}
    (contains? d :expr)      (assoc "expr" (form-tree (:expr d)))
    (contains? d :count)     (assoc "count" (aggregate-fp (:count d)))
    (contains? d :sum)       (assoc "sum" (aggregate-fp (:sum d)))
    (contains? d :fn)        (assoc "fn" (callable-hash (str address ".fn")
                                                        (:fn d)))
    (contains? d :tolerance) (assoc "tolerance" (str (:tolerance d)))
    (:explain d)             (assoc "explain" (:explain d))
    (:vars d)                (assoc "vars" (into (sorted-map)
                                                 (map (fn [[k f]] [(name k) (form-tree f)]))
                                                 (:vars d)))))

(defn- over-fp
  "The kind-level ending declaration (waymark-fp62.4.1.2): the ending
  wall reads :over to shut a finished row's doors, so it is law. Sets
  sort to vectors; `name` spells a state keyword and a field value
  string alike. :field rides only when declared."
  [o]
  (cond-> {"accomplished" (vec (sort (map name (:accomplished o))))
           "let_go"       (vec (sort (map name (:let-go o))))
           "ways_back"    (vec (sort (map name (:ways-back o))))}
    (:field o) (assoc "field" (name (:field o)))))

(defn- recurs-fp
  "The kind's recurrence rules (docs/spec-calendar-time.md §4, §7):
  they write rows, so each is law and a change to any is a revision.
  Expressions ride as trees, sets sort, an undeclared key stays absent."
  [rules]
  (into (sorted-map)
        (map (fn [r]
               [(name (:name r :unnamed))
                (cond-> {"makes" (name (:makes r :unnamed))
                         "holidays" (name (:holidays r :skip))}
                  (some? (:every r)) (assoc "every" (form-tree (:every r)))
                  (seq (:on r))      (assoc "on" (vec (sort (map name (:on r)))))
                  (some? (:from r))  (assoc "from" (form-tree (:from r)))
                  (some? (:until r)) (assoc "until" (form-tree (:until r)))
                  (some? (:times r)) (assoc "times" (:times r))
                  (some? (:while r)) (assoc "while" (vec (sort (map name (:while r)))))
                  (some? (:lead r))  (assoc "lead" (form-tree (:lead r)))
                  (some? (:owner r)) (assoc "owner" (form-tree (:owner r)))
                  (seq (:input r))   (assoc "input" (into (sorted-map)
                                                       (map (fn [[f x]] [(name f) (form-tree x)]))
                                                       (:input r))))]))
        rules))

(defn- on-event-fp
  "The kind's inbound events (docs/spec-integrations.md § 3, § 8): each
  moves a door or makes a row, so each is law. Entries keep their order
  (the first whose match names one row runs); expressions ride as
  trees, an undeclared key stays absent."
  [es]
  (mapv (fn [e]
          (let [forms (fn [m] (into (sorted-map)
                                    (map (fn [[f x]] [(name f) (form-tree x)]))
                                    m))]
            (cond-> {"source" (name (:source e))
                     "type"   (:type e)}
              (some? (:door e))  (assoc "door" (name (:door e)))
              (seq (:match e))   (assoc "match" (forms (:match e)))
              (seq (:input e))   (assoc "input" (forms (:input e)))
              (some? (:retry-unmatched-for e))
              (assoc "retry_unmatched_for" (:retry-unmatched-for e))
              (seq (:tell e))    (assoc "tell" (mapv name (:tell e)))
              (some? (:via e))   (assoc "via" (name (:via e)))
              (some? (:makes e)) (assoc "makes" (name (:makes e))))))
        es))

(defn- safety-fp [s]
  {"idempotent" (boolean (:idempotent s))
   "reversible" (boolean (:reversible s))
   "confirm"    (boolean (:confirm s))})

(defn- storage-fp
  "The storage facet: the kind's table projection canonicalized —
  columns sorted by name with type-as-string + generated flag, index
  names sorted. The SAME projection the DDL renders from
  (store/kind-projection), so a promotion change is law: classify-path
  files storage.* under :shape, the diff is :code-or-shape, and the
  boot promotes totally. Generation expressions are excluded on both
  sides (they derive mechanically from field + type)."
  [rmap]
  (let [{:keys [table columns indexes]} (store/kind-projection rmap)]
    {"table" table
     "columns" (into (sorted-map)
                     (map (fn [{col :name :keys [type generated?]}]
                            [col {"type" type
                                  "generated" (boolean generated?)}]))
                     columns)
     "indexes" (vec (sort (keys indexes)))}))

(defn- unwrap-maybe-form [s]
  (if (and (vector? s) (= :maybe (first s))) (second s) s))

(defn- canonical-default
  "A default value as canonical wire law: decimals cross as the
  {\"dec\" …} node (exact, no floats), keywords as names, dates as
  ISO strings — the same discipline every law tree already keeps."
  [v]
  (cond
    (decimal? v) {"dec" (.toPlainString ^java.math.BigDecimal v)}
    (money/money? v) (money/wire-node v)
    (keyword? v) (name v)
    (instance? java.time.LocalDate v) (str v)
    (instance? java.time.Instant v) (str v)
    (sequential? v) (mapv canonical-default v)
    (map? v) (into (sorted-map)
                   (map (fn [[k x]] [(if (keyword? k) (name k) k)
                                     (canonical-default x)]))
                   v)
    :else v))

(defn- schema-defaults
  "The declared :default values of a schema form, flattened —
  {\"field\" value}, item defaults as \"field.item_field\". Empty map
  when the form declares none (the facet stays absent — hash
  stability for the default-free world)."
  [form]
  (if (nil? form)
    (sorted-map)
    (into (sorted-map)
          (mapcat (fn [[k {:keys [properties schema]}]]
                    (let [s (unwrap-maybe-form schema)
                          item (when (and (vector? s) (= :vector (first s)))
                                 (last s))
                          own (when (contains? properties :default)
                                [[(name k) (canonical-default
                                            (:default properties))]])
                          nested (when (and (vector? item)
                                            (= :map (first item)))
                                   (map (fn [[ik iv]]
                                          [(str (name k) "." ik) iv])
                                        (schema-defaults item)))]
                      (concat own nested))))
          (schema/entry-map form))))

(defn- action-fp [address a]
  (cond-> {"from"    (vec (sort (map name (:from a))))
           "to"      (name (:to a))
           "safety"  (safety-fp (:safety a))
           ;; a guard's address names it, never its position: inserting
           ;; a wall ahead of another must not restate the other's
           ;; identity (the diff paths stay positional, and honest)
           "guards"  (mapv (fn [g]
                             (guard-fp (str address ".guards."
                                            (name (:name g)))
                                       g))
                           (:guards a []))
           "handler" (callable-hash (str address ".handler")
                                    (:handler a))}
    ;; a default changes what a blank write stores — law, non-empty-only
    (seq (schema-defaults (:input a)))
    (assoc "input_defaults" (schema-defaults (:input a)))
    ;; blast radius is law (waymark9 touches=): projected only when
    ;; declared, so every touch-free action hashes byte-identical to
    ;; the pre-touches era
    (seq (:touches a))
    (assoc "touches" (mapv (fn [t]
                             (cond-> {"kind" (name (:kind t))
                                      "action" (name (:action t))}
                               (:may t) (assoc "may" true)))
                           (:touches a)))
    ;; the date a posting entry lands on (ledger step 9a): projected only
    ;; when an entry names :on, so every other door hashes as before
    (some :on (:posts a))
    (assoc "posts_on" (mapv #(some-> (:on %) pr-str) (:posts a)))
    ;; who a door stamps as having acted is law (spec-teams §4),
    ;; projected only when declared; its :not-by is a guard above
    (seq (:stamp a))
    (assoc "stamp" (pr-str (into (sorted-map) (:stamp a))))
    ;; what a door unwinds is law too (§ 5), projected only when declared
    (seq (:unwinds a))
    (assoc "unwinds" (pr-str (:unwinds a)))
    ;; a purge door is legal erasure, law (docs/spec-documents.md § 8):
    ;; projected only when declared, so every other door hashes as before
    (:purge a)
    (assoc "purge" true)
    ;; the call a door makes is law (docs/spec-integrations.md § 8):
    ;; projected only when declared, so every other door hashes as before
    (:effect a)
    (assoc "effect" (pr-str (into (sorted-map)
                                  (map (fn [[k v]] [k (if (map? v) (into (sorted-map) v) v)]))
                                  (:effect a))))))

(defn accounts-fp
  "Accounts (docs/spec-ledger-money.md §3, §10): names, units and bounds
  are law — the bounds will refuse writes — so each entry rides as its
  canonical tree, its keys snake_case; an undeclared bound stays absent."
  [accts]
  (into (sorted-map)
        (map (fn [[n a]]
               [(name n) (into (sorted-map)
                               (map (fn [[k v]] [(str/replace (name k) "-" "_")
                                                 (canonical-default v)]))
                               a)]))
        accts))

(defn- authority-fp
  "The mirror sync-law facet: the document contract, push-on-write,
  and per-field authority (external keys, adopts/frozen windows).
  The sync CADENCES (:ttl-seconds :discover-every) are OPERATIONS,
  deliberately outside the law — a polling tweak must not mint a
  revision. Empty (and omitted) for a pull-only, whole-document,
  window-less mirror, so every such kind's hash stays byte-identical
  to the pre-authority era."
  [rmap]
  (let [spec (:mirror rmap)
        fields (into (sorted-map)
                     (keep (fn [[f {:keys [properties]}]]
                             (let [{:keys [external-key adopts frozen
                                           expect]} properties
                                   m (cond-> {}
                                       external-key
                                       (assoc "external_key" (name external-key))
                                       adopts (assoc "adopts" adopts)
                                       (true? frozen) (assoc "frozen" true)
                                       (string? frozen) (assoc "frozen" frozen)
                                       (keyword? expect)
                                       (assoc "expect" (name expect))
                                       (map? expect)
                                       (assoc "expect" {"churn" (:churn expect)}))]
                               (when (seq m) [(name f) m]))))
                     (schema/entry-map (:schema rmap)))]
    (cond-> {}
      (= :partial (:document spec)) (assoc "document" "partial")
      (:push-on-write spec) (assoc "push_on_write" true)
      ;; the gone-policy is law (what a feed-dropped row MEANS);
      ;; :keep — the default — stays out, hash-identical to before
      (map? (:on-gone spec))
      (assoc "on_gone" {"set" (into (sorted-map)
                                    (map (fn [[f v]] [(name f) v]))
                                    (get-in spec [:on-gone :set]))})
      (seq fields) (assoc "fields" fields))))

(defn- measures-fp
  "The measures facet (docs/spec-measures.md §8): each measure by name,
  every key but :label and :default-target — :among as the collection
  grammar parses it, :where as its canonical tree. An optional key left
  out stays absent."
  [ms]
  (into (sorted-map)
        (map (fn [[n m]]
               [(name n)
                (cond-> {"of" (measures/spelled (:of m))
                         "stat" (measures/spelled (:stat m))
                         "among" (into (sorted-map)
                                       (map (fn [[k vs]] [(name k) vs]))
                                       (measures/parse-among (:among m)))
                         "at" (measures/spelled (:at m))
                         "over" (vec (measures/parse-over (:over m)))
                         "at_least" (:at-least m measures/default-at-least)}
                  (some? (:where m)) (assoc "where" (form-tree (:where m)))
                  (some? (:field m)) (assoc "field" (measures/spelled (:field m)))
                  (some? (:tell m)) (assoc "tell" (measures/spelled (:tell m))))]))
        ms))

(defn fingerprint-of
  "Deterministic, canonically-encodable projection of a resource
  declaration map. Phase-0 facets: machine (states, actions with
  guards/safety/handler) and derived; the migrate phase adds storage
  (the table projection — present when the declaration carries a
  schema, i.e. every normalized kind); later phases add create,
  schema, owns, vocab, query, links — each facet landing with the
  feature that declares it. A mirror kind additionally projects its
  AUTHORITY facet (sync law: document contract, push-on-write,
  external keys, adopts/frozen windows) — never its cadences. A kind
  that declares :over projects its OVER facet (the endings the ending
  wall reads).

  The kind's name roots the ADDRESS every opaque leaf hashes by (see
  callable-hash): `plan_day.machine.actions.assign_meal.guards.meal-
  fits-day.check` is where that opacity sits, and the address is the
  only thing about it the law can honestly state."
  [rmap]
  (let [kind (name (:kind rmap))
        actions (into (sorted-map)
                      (map (fn [[k a]]
                             [(name k)
                              (action-fp
                               (str kind ".machine.actions." (name k)) a)]))
                      (:actions rmap))
        derived (into (sorted-map)
                      (map (fn [[k d]]
                             [(name k)
                              (derived-fp
                               (str kind ".derived." (name k)) d)]))
                      (:derived rmap))]
    (cond-> {"kind" kind
             "machine"
             {"states"   (mapv name (:states rmap))
              "initial"  (name (:initial rmap))
              "terminal" (vec (sort (map name (:terminal rmap))))
              "actions"  actions}
             "derived" derived}
      ;; the facet exists exactly when the declaration names a table —
      ;; :plural is normalized in, so every registered kind carries it
      (and (:schema rmap) (:plural rmap))
      (assoc "storage" (storage-fp rmap))

      ;; the :secret disposition (waymark-kyg) is shape-class law: a field
      ;; whose value never leaves the engine. Removing it flips a field
      ;; concealed→world-readable, so it must mint a revision and show in
      ;; the diff. Non-empty-only, so every secret-free kind hashes
      ;; byte-identical to before the disposition existed
      (and (:schema rmap) (seq (schema/secret-fields (:schema rmap))))
      (assoc "shape" {"secret" (vec (sort (map name (schema/secret-fields
                                                     (:schema rmap)))))})

      ;; the create facet (design §24, anticipated above): declared
      ;; field defaults are law — a default changes what a blank write
      ;; stores. Non-empty-only: the default-free world hashes as ever
      (seq (schema-defaults (or (:create-schema rmap) (:schema rmap))))
      (assoc "create" {"defaults" (schema-defaults
                                   (or (:create-schema rmap) (:schema rmap)))})

      ;; create-door guards are judgment law exactly as an action's are
      ;; (waymark-442.9): widening the create gate must move the hash,
      ;; mint a revision and show in the diff. Non-empty-only, so every
      ;; create-guard-free kind hashes byte-identical to before
      (seq (:create-guards rmap))
      (update "create" assoc "guards"
              (mapv (fn [g]
                      (guard-fp (str kind ".create.guards." (name (:name g)))
                                g))
                    (:create-guards rmap)))

      ;; what a create posts at birth is law as its guards are.
      ;; Non-empty-only, so every kind that posts nothing at birth
      ;; hashes byte-identical to before
      (seq (:create-posts rmap))
      (update "create" assoc "posts" (pr-str (:create-posts rmap)))

      ;; recorded deviations are reviewable law (advertisement-class):
      ;; editing one shows in the diff and mints a revision. Projected
      ;; only when non-empty, so every deviation-free kind's hash is
      ;; byte-identical to the pre-deviations era
      (seq (:deviations rmap))
      (assoc "deviations" (vec (:deviations rmap)))

      (and (:mirror rmap) (seq (authority-fp rmap)))
      (assoc "authority" (authority-fp rmap))

      ;; :recurs writes rows on a rule, so the rule is law. Non-empty-only,
      ;; so every recurrence-free kind hashes byte-identical to before
      (seq (:recurs rmap))
      (assoc "recurs" (recurs-fp (:recurs rmap)))

      ;; :on-event moves doors on an outside event's word (spec-
      ;; integrations § 8), so the mapping is law. Non-empty-only, so
      ;; every event-free kind hashes byte-identical to before
      (seq (:on-event rmap))
      (assoc "on_event" (on-event-fp (:on-event rmap)))

      ;; :accounts (docs/spec-ledger-money.md §10): names, units and
      ;; bounds are law. Non-empty-only, so every account-free kind
      ;; hashes byte-identical to before
      (seq (:accounts rmap))
      (assoc "accounts" (accounts-fp (:accounts rmap)))

      ;; :rate-table (docs/spec-ledger-money.md §8): which rows a (rate …)
      ;; reads is law. Present-only, so every other kind hashes as before
      (some? (:rate-table rmap))
      (assoc "rate_table" (let [{n :name ks :key v :value e :effective} (:rate-table rmap)]
                            {"name" (name n) "key" (mapv name ks)
                             "value" (name v) "effective" (name e)}))

      ;; :conserves (docs/spec-ledger-money.md §5, §10): which accounts
      ;; the doors keep in sum is law. Present-only, so every other kind
      ;; hashes as before
      (some? (:conserves rmap))
      (assoc "conserves" (vec (sort (map name (:conserves rmap)))))

      ;; :measures (docs/spec-measures.md §8): which rows a measure reads
      ;; and how it judges them is law; its label is prose. Present-only,
      ;; so every other kind hashes as before
      (and (map? (:measures rmap)) (seq (:measures rmap)))
      (assoc "measures" (measures-fp (:measures rmap)))

      ;; :reconciles (docs/spec-ledger-money.md §7): the close's period,
      ;; sides and tolerance are law. Present-only, so every other kind
      ;; hashes as before
      (some? (:reconciles rmap))
      (assoc "reconciles" (let [{:keys [period sides tolerance]} (:reconciles rmap)]
                            {"period" (mapv form-tree period)
                             "sides" (into (sorted-map)
                                           (map (fn [[n f]] [(name n) (form-tree f)]))
                                           sides)
                             "tolerance" (form-tree tolerance)}))

      ;; the kind's endings (waymark-fp62.4.1.2): :over decides which
      ;; doors the ending wall shuts, so re-spelling it mints a revision.
      ;; Present whenever declared (even empty — an empty :over still
      ;; replaces :terminal as the ending set), absent otherwise, so
      ;; every :over-free kind hashes byte-identical to before
      (some? (:over rmap))
      (assoc "over" (over-fp (:over rmap)))

      ;; :keep (docs/spec-documents.md §4): how long a kind is kept,
      ;; from when, and which door ends it are law. Declared-only, so
      ;; every kind without it hashes as before
      (some? (:keep rmap))
      (assoc "keep" (pr-str (:keep rmap)))

      ;; :frozen-in (docs/spec-documents.md §6, §8): the states in which
      ;; no door may change the data refuse writes, so they are law.
      ;; Non-empty-only, so every frozen-free kind hashes as before
      (seq (:frozen-in rmap))
      (assoc "frozen_in" (vec (sort (map name (:frozen-in rmap))))))))

(defn fingerprint-hash ^String [fp]
  (wire/digest fp))

;; ── path classification (design §4's four classes) ──────────────────

(def ^:private shape-family #{"storage" "shape" "upcasts"})
(def ^:private judgment-family #{"guards" "unless" "when" "unique" "vocab"
                                 "safety" "tolerance"})
(def ^:private truth-family #{"derived" "machine" "authored" "owns" "compound"
                              "touches" "batch" "bulk" "handler" "renames"
                              "renamed_actions" "renamed_fields" "authority"
                              "create" "over" "recurs" "on_event"
                              "accounts" "frozen_in"})
(def ^:private advertisement-family #{"display" "field_display" "summary"
                                      "label_template" "explain" "links"
                                      "profiles" "query" "data_schema"
                                      "input_schema" "schema" "edit" "place"
                                      "row_affordances" "plural" "nav"
                                      "deviations"})
(def ^:private derived-garnish #{"explain" "vars"})

(defn classify-path
  "Rules are ordered: a guard's explain under machine.actions.*.guards
  is a judgment change, not a machine change — the innermost owning
  surface wins. A derivation's explain/vars are refusal-surface
  garnish: advertisement."
  [^String path]
  (let [segs (str/split path #"\.")]
    (if (and (= (first segs) "derived")
             (some derived-garnish (drop 2 segs)))
      :advertisement
      (or (some (fn [[family cls]]
                  (when (some family segs) cls))
                [[shape-family :shape]
                 [judgment-family :judgment]
                 [truth-family :truth]
                 [advertisement-family :advertisement]])
          :truth))))

;; ── the diff ────────────────────────────────────────────────────────

(defn- flatten-fp [v prefix]
  (cond
    (and (map? v) (seq v))
    (into {}
          (mapcat (fn [[k x]]
                    (flatten-fp x (str prefix
                                       (if (keyword? k) (name k) (str k))
                                       "."))))
          v)

    (and (sequential? v) (seq v))
    (into {}
          (comp (map-indexed (fn [i x] (flatten-fp x (str prefix i "."))))
                cat)
          v)

    :else
    {(if (str/ends-with? prefix ".")
       (subs prefix 0 (dec (count prefix)))
       prefix)
     (if (or (map? v) (sequential? v)) "<empty>" v)}))

(defn diff-fingerprints
  "Path-level diff of two fingerprints, each path tagged with its §4
  class. List paths are positional — a reordered guard list reads as
  changed paths, which is honest: order is evaluation order."
  [old new]
  (let [a (flatten-fp old "")
        b (flatten-fp new "")
        entries (fn [paths]
                  (mapv (fn [p] {:path p :class (classify-path p)})
                        (sort paths)))]
    ;; contains?, not map-as-predicate: a leaf holding nil/false (a
    ;; guard's check, an off safety flag) is still a present path
    {:added   (entries (remove #(contains? a %) (keys b)))
     :removed (entries (remove #(contains? b %) (keys a)))
     :changed (entries (for [p (keys a)
                             :when (and (contains? b p)
                                        (not= (a p) (b p)))]
                         p))}))

;; ── the deploy-mode gate: what the overlays can serve ───────────────
;; data law (waymark8 §2): a derivation's stored parameters or tree.
;; judgment law (waymark9 §2): the recoverable leaves of a top-level
;; expression guard. Everything else is code_or_shape — the resident
;; objects ARE that law, and a hold would be a lie.

;; count.where is the tolerance precedent (phase 6): the filter values
;; are stored parameters an overlay can serve; the edge itself
;; (count.related / count.owns) is :code-or-shape — the maintainer's
;; reverse map is built from resident declarations. sum.where (batch
;; C) inherits the rule verbatim; sum.of is edge identity — the
;; column the SQL reads — and stays :code-or-shape with the edges, as
;; does sum.when_empty — what an empty sum MEANS is the resident
;; read, not a stored parameter, so flipping it promotes totally (and
;; the promote's backfill restamps every landed value).
(def ^:private data-law-path
  #"^derived\.[^.]+\.(?:tolerance$|expr(?:\..+)?$|(?:count|sum)\.where(?:\..+)?$|over\.\d+\.(?:child|related)\.where(?:\..+)?$)")

(def ^:private judgment-law-path
  #"^(?:machine\.actions\.[^.]+|create)\.guards\.\d+\.(?:expr(?:\..+)?$|vars_exprs(?:\..+)?$|explain$|remedies(?:\.\d+)?$|hide$|severity$|requires_token$)")

(defn classify-diff
  ":data-law when every added/removed/changed path is overlayable —
  may be held at proposed / piloted per population; else
  :code-or-shape — promotes totally. An empty diff cannot reach here
  (the hash moved)."
  [diff]
  (let [paths (map :path (mapcat diff [:added :removed :changed]))]
    (if (and (seq paths)
             (every? #(or (re-matches data-law-path %)
                          (re-matches judgment-law-path %))
                     paths))
      :data-law
      :code-or-shape)))

(defn stale-facts
  "Derived facts marked stale by definition: every fact named by an
  added/changed/removed path under derived.<fact>., garnish excluded —
  a materialized value computed under the previous law can disagree
  with the semantic surface; a changed explain cannot."
  [diff]
  (->> (mapcat diff [:added :changed :removed])
       (keep (fn [{:keys [path]}]
               (let [segs (str/split path #"\.")]
                 (when (and (= (first segs) "derived")
                            (>= (count segs) 3)
                            (not (some derived-garnish (drop 2 segs))))
                   (second segs)))))
       distinct
       sort
       vec))
