(ns waymark10.server.decision
  "The decision record: why a transition was ALLOWED.

  Refusals narrate; approvals were silent. A committed transition
  recorded who moved the row, from where to where, and under which
  law revision — and not one word about the judgment that let it
  through. This namespace closes that asymmetry, and closes it
  cheaply, because the answer splits in two and only one half needs
  storing (docs/spec-decision-record.md):

  THE BASIS IS DERIVED. *Which* guards judged is a function of
  (kind, action, law_revision) plus the definition row's stored
  fingerprint — `basis` below is that lookup, resolved through the
  same judgment overlay the invoker enforces with. It costs no
  column, no byte, and no migration, and it is RETROACTIVE: every
  transition ever logged gains an answer the day this ships, which no
  stored record could offer.

  THE EVIDENCE IS WRITTEN, AND DECLARED. What those guards READ is
  gone the instant the transaction commits, so a kind that declares
  :retain {:judgment true} carries it forward in the transitions
  row's `judgment` column. A guard's evidence is exactly its declared
  :vars evaluated over the scope it judged — the same map
  guards/expr-check builds, and the same values the refusal sentence
  would have interpolated had the guard denied. That is the spec's
  second thesis and the whole bound on the bytes:

      the decision record is the refusal sentence
      the guard did not have to give.

  An author who wants a fuller record declares fuller :vars, and gets
  a better refusal sentence for free.

  A CODE GUARD RECORDS NOTHING BUT ITS NAME. Its :check is a closure;
  fingerprint/callable-hash already refuses to store more than its
  hash, and a decision record that guessed at a closure's inputs
  would be the forms-only rule broken from the inside. Same boundary,
  drawn twice, deliberately — `\"opaque\": true`. A composite records
  opaque too, under the generated name its guard vector carries
  (`a&b`): naming the arms would record law the declaration folded on
  purpose.

  WHERE THE BYTES STOP — two locks, and the second is the dangerous
  one:

  - Secret fields are never CAPTURED. The evidence scope is built
    over the secret-concealed row, so a :vars form reading a secret
    field evaluates to nil at write time. Subtraction beats
    projection: cheaper, stricter, and impossible to forget at a
    read site that has not been written yet.
  - Everything else in `read` is a FIELD VALUE. A history read must
    therefore pass the same visibility a row read does, or an
    unprojected judgment object is a disclosure channel with a URL.
    That is why every recorded var carries the document fields its
    form read (`read_fields`), and why `project` exists here, beside
    the write, rather than at whichever route reads it first.

  A note on keys: the record is built with KEYWORD keys, because the
  store's jsonb round-trip keywordizes on the way back and a shape
  that changed across a write would be two shapes to reason about.
  The BYTES are the spec's JSON either way — {:read {:left 3}} writes
  {\"read\":{\"left\":3}} and reads back exactly as it was written."
  (:require [waymark10.expr :as expr]
            [waymark10.schema :as schema]
            [waymark10.server.judgment :as judgment])
  (:import (java.time Instant LocalDate)))

(set! *warn-on-reflection* true)

;; ── the derived half: which guards judged ───────────────────────────

(defn- guard-form
  "How much of a guard is data: :expr when its verdict is a stored
  tree, :composite when it folds arms under a generated name, :code
  otherwise. The one distinction the record is allowed to draw about
  a guard's insides."
  [g]
  (cond (or (:all g) (:any g)) :composite
        (:when g)              :expr
        :else                  :code))

(defn basis
  "WHICH guards judged this transition — DERIVED, never stored.

  (rdef, action, law-revision) → {:action :revision :law :guards},
  where :law is :stored when that revision's fingerprint served the
  trees, :resident when the resident declaration IS that law (a nil
  pre-law stamp, the current revision, a piloted one), and :engine
  for the injected :adopt, which judges nothing.

  Each guard is {:name :severity :reads :judges :form}, positional,
  in the order the invoker evaluated them. nil when the kind declares
  no such action — a renamed action reads through the log's own
  continuity map, which is the caller's business, not this one's.

  Retroactive by construction: nothing here reads a transitions row.
  Hand it a revision from 2026-07-03 and it answers with the law of
  July 3rd, for a transition logged long before this column existed."
  [rdef action revision]
  (let [creates (:create-action-names rdef #{})
        declared (get-in rdef [:actions action])]
    (cond
      ;; the create door: a birth's guards are the kind's
      ;; :create-guards, resident by construction — the judgment
      ;; overlay serves :actions and the create path has never had one
      (and (nil? declared) (contains? creates action))
      {:action action :revision revision :law :resident
       :guards (mapv (fn [g] {:name (:name g) :severity (:severity g :refuse)
                              :reads (vec (:reads g)) :judges (vec (:judges g))
                              :form (guard-form g)})
                     (:create-guards rdef))}

      ;; the engine's own restamp judges nothing, and says so rather
      ;; than answering with an empty guard vector that would read as
      ;; "a law with no guards"
      (and (nil? declared) (= :adopt action))
      {:action action :revision revision :law :engine :guards []}

      (nil? declared) nil

      :else
      (let [defn' (judgment/resolve-action rdef (assoc declared :name action)
                                           revision)]
        {:action action
         :revision revision
         :law (if (and revision (get (:judgment-laws rdef) revision))
                :stored :resident)
         :guards (mapv (fn [g] {:name (:name g)
                                :severity (:severity g :refuse)
                                :reads (vec (:reads g))
                                :judges (vec (:judges g))
                                :form (guard-form g)})
                       (:guards defn'))}))))

;; ── the written half: what the guards read ──────────────────────────

(defn retains?
  "Does this kind retain judgment evidence? :retain is one map shared
  with time travel's tier 3 ({:data true}); either entry alone is a
  partial declaration of the same intent. Default off, grown by
  declaration — write amplification on the one write path is bytes on
  every transition of every kind forever."
  [rdef]
  (boolean (get-in rdef [:retain :judgment])))

(def ^:private value-cap
  "The longest evidence string recorded. :vars bounds the record by
  construction — a guard declares two or three names, not a document
  — but a var bound to a prose field would still put a paragraph in
  the log on every write. Recorded deviation from the spec, which
  bounds by declaration alone; the ellipsis says the value was cut."
  240)

(defn- recordable
  "One evidence value, made storable. The JSONB scalars pass through;
  everything else records as its printed form — a LocalDate as its
  ISO day, an Instant as its ISO moment, a keyword as its name. An
  evidence value must never be the reason a write fails, which is
  also why the caller evaluates each var inside its own try."
  [v]
  (cond
    (nil? v)      nil
    (boolean? v)  v
    (number? v)   v
    (keyword? v)  (name v)
    (string? v)   (if (> (count v) value-cap)
                    (str (subs v 0 value-cap) "…")
                    v)
    (map? v)      (into {} (map (fn [[k x]]
                                  [(if (keyword? k) (name k) (str k))
                                   (recordable x)]))
                        v)
    (or (instance? LocalDate v) (instance? Instant v)) (str v)
    (coll? v)     (mapv recordable v)
    :else         (let [s (str v)]
                    (if (> (count s) value-cap)
                      (str (subs s 0 value-cap) "…")
                      s))))

(defn evidence-scope
  "The scope a guard's :vars evaluate over for the record — the same
  map guards/expr-check judges with, minus every secret field.

  Subtraction at WRITE time is the first lock: a :vars form reading a
  {:secret true} field evaluates over an absent key and records nil,
  so the value is not merely hidden from some future reader, it was
  never captured. The refusal sentence already renders over this same
  concealed row (waymark-kyg), which is why the record and the
  sentence agree word for word."
  [rdef row inp ctx]
  (let [secret (not-empty (schema/secret-fields (:schema rdef)))]
    {:data  (cond-> (:data row) secret (#(apply dissoc % secret)))
     :input (cond-> inp secret (#(apply dissoc % secret)))
     :now   (:now ctx)}))

(defn- read-fields
  "The document fields one :vars form touched, as the projection will
  need them: (data :f) and (input :f) alike, because an input key
  lands in the document and a grant's visibility is a question about
  fields, not about which door a value came through. Conservative on
  purpose — the lock that errs wide withholds a little too much; the
  lock that errs narrow discloses."
  [form]
  (let [{:keys [data inputs]} (expr/info form)]
    (vec (sort (map name (into (set data) inputs))))))

(defn- guard-entry
  "One guard's line in the record: its name, the verdict it returned,
  its declared :reads — and, for an expression guard, its :vars
  evaluated over the scope it judged. Anything whose verdict is not a
  stored tree records `opaque`."
  [g verdict scope]
  (let [base {:name    (name (:name g))
              :verdict verdict
              :reads   (mapv name (:reads g))}]
    (if (and (= :expr (guard-form g)) (seq (:vars g)))
      (let [pairs (into []
                        (keep (fn [[k form]]
                                (try
                                  [k (recordable (expr/evaluate form scope))
                                   (read-fields form)]
                                  (catch Exception _ nil))))
                        (:vars g))]
        (cond-> (assoc base :read (into {} (map (fn [[k v _]] [k v])) pairs))
          (some (comp seq #(nth % 2)) pairs)
          (assoc :read_fields (into {}
                                    (keep (fn [[k _ fs]]
                                            (when (seq fs) [k fs])))
                                    pairs))))
      (assoc base :opaque true))))

(defn entry
  "The record's line for one evaluated guard. `denier` is g/evaluate's
  second value; a warning-severity deny the caller acknowledged
  records as `acknowledged` rather than `allow`, because it is the
  one verdict that was overridden rather than earned. A hard deny
  never reaches here — a refusal does not commit, so it has no
  transition to hang from."
  [g verdict-deny? denier acknowledged scope]
  (guard-entry g
               (if verdict-deny?
                 (if (contains? acknowledged (:name denier))
                   "acknowledged" "warned")
                 "allow")
               scope))

(defn record
  "The `judgment` column's whole value: the revision that judged, the
  guards in the order they judged, and the warning-severity names the
  caller overrode. nil when nothing was retained — a kind with
  retention off writes SQL NULL, never an empty object that would lie
  about coverage. An EMPTY vector is not nothing: a retaining kind
  whose action declares no guards records `\"guards\": []`, which is
  the true sentence, and only a nil basis means \"this kind retains
  nothing\"."
  [revision entries overridden]
  (when (some? entries)
    (cond-> {:revision revision
             :guards (vec entries)}
      (seq overridden) (assoc :acknowledged (mapv name overridden)))))

;; ── the read lock: evidence rides the grant ─────────────────────────

(defn project
  "A stored judgment object, read through a grant's field visibility.
  `visible?` is (fn [field-name-string] → boolean); nil projects
  nothing (the system door, which already sees the row whole).

  An evidence value whose form read a field this visibility conceals
  is WITHHELD — dropped from `read` and named in `withheld` — never
  refused. The var NAMES stay: a guard's :vars are law, they ride the
  definition row's fingerprint, and a reader who may see the guard at
  all may see what the guard was looking for. Only the VALUE is a
  field value, and only the value is projected away.

  This is spec-time-travel's one security clause inherited verbatim,
  and it lives beside the write so the history route inherits it
  rather than reinventing it."
  [judgment visible?]
  (if (or (nil? judgment) (nil? visible?))
    judgment
    (update judgment :guards
            (fn [gs]
              (mapv
               (fn [g]
                 (let [hidden (into #{}
                                    (keep (fn [[var' fs]]
                                            (when-not (every? visible? fs) var')))
                                    (:read_fields g))]
                   (if (empty? hidden)
                     g
                     (-> g
                         (update :read #(apply dissoc % hidden))
                         (update :read_fields #(apply dissoc % hidden))
                         (assoc :withheld (vec (sort-by name hidden)))))))
               (or gs []))))))
