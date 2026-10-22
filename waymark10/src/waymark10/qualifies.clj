(ns waymark10.qualifies
  "Who may be named (docs/spec-teams.md §3, decision 2): a member ref
  declares `:role r` — the member named holds r, on their own row or
  through an active team that confers it — and `:in-team <expr>` — the
  member named is on the active team the expression names, read over
  the row with the door's input laid on it, so `(data :crew)` reads the
  proposed crew when the same write changes it. Both may stand
  together, and both hold for every element of a list of refs.

  On every door whose input carries the field (the create door's is the
  kind's create schema) the registry appends `<field>-role` and
  `<field>-in-team`, so a history entry's `basis` names them like any
  other guard. The refusal names the evidence: \"Sam Lee does not hold
  pt.\" / \"Dana Ruiz is not on Crew B. Crew B is Ana Lopez and Luis
  Ortega.\" The check is at the write only: a member who stops
  qualifying leaves the rows that already name them as they are.

  A ctx that cannot read rows (the render probe) refuses nothing, and
  an :in-team expression that answers no team judges nothing."
  (:require [clojure.string :as str]
            [waymark10.expr :as expr]
            [waymark10.guards :as g]
            [waymark10.schema :as schema]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def ^:private team-page
  ;; the most active teams one judgment reads, as guards/team-reads does
  500)

(defn- fail [kind msg]
  (throw (t/definition-error (str (name kind) " [qualifies] " msg) {:check :qualifies})))

(defn guard-name
  "A derived guard's name: :lead_id and \"in-team\" → :lead-id-in-team."
  [field suffix]
  (keyword (str (str/replace (name field) "_" "-") "-" suffix)))

;; ── the reads ──────────────────────────────────────────────────────────────────

(defn- named
  "The member ids `inp` names in `field`: the one, or each of a list;
  nil when the input does not carry it."
  [inp field]
  (when (map? inp)
    (let [v (if (contains? inp field) (get inp field) (get inp (name field)))]
      (cond
        (sequential? v) (into [] (comp (remove nil?) (map str)) v)
        (some? v) [(str v)]))))

(defn- member-row
  "The member row `m` names: by its id, or by the subject it carries."
  [ctx m]
  (or ((:read ctx) :member m)
      (first ((:find ctx) :member {:subject m} {:limit 1}))))

(defn- display
  "A member as a sentence names them: the row's display, else the id."
  [ctx m]
  (or (some-> (member-row ctx (str m)) (get-in [:data :display]) str not-empty)
      (str m)))

(defn- and-list [xs]
  (let [xs (vec xs)]
    (if (< (count xs) 2)
      (str/join xs)
      (str (str/join ", " (pop xs)) " and " (peek xs)))))

(defn role-problem
  "The sentence for the first member `inp` names in `field` who does not
  hold `role`, or nil."
  [field role inp ctx]
  (let [ids (named inp field)]
    (when (and (seq ids) (:read ctx) (:find ctx))
      (let [holds? (:holds-role? (g/team-reads ctx))]
        (some #(when-not (holds? % role)
                 (str (display ctx %) " does not hold " role "."))
              ids)))))

(defn- team-named
  "The active team `t` names, by its id or its name, or nil."
  [ctx t]
  (some #(when (or (= t (str (:id %))) (= t (str (get-in % [:data :name])))) %)
        ((:find ctx) :team {:state "active"} {:limit team-page})))

(defn in-team-problem
  "The sentence for the first member `inp` names in `field` who is not
  on the team `form` names, or nil. `form` is a team's name, or an
  expression read over the row with the input laid on its data."
  [field form row inp ctx]
  (let [ids (named inp field)]
    (when (and (seq ids) (:read ctx) (:find ctx))
      (let [t (if (string? form)
                form
                (expr/evaluate form (g/expr-scope (update row :data merge inp) inp ctx)))]
        (when (and (some? t) (not (expr/unknown? t)) (not (str/blank? (str t))))
          (let [team (team-named ctx (str t))
                tname (or (some-> team (get-in [:data :name]) str not-empty) (str t))
                on (set (map str (get-in team [:data :members])))
                on? (fn [m]
                      (or (contains? on m)
                          (contains? on (some-> (member-row ctx m) :id str))))
                who (fn []
                      (cond
                        (nil? team) (str "No active team is " tname ".")
                        (empty? on) (str tname " has no members.")
                        :else (str tname " is "
                                   (and-list (map #(display ctx %) (get-in team [:data :members])))
                                   ".")))]
            (some #(when-not (and team (on? %))
                     (str (display ctx %) " is not on " tname ". " (who)))
                  ids)))))))

;; ── the guards ──────────────────────────────────────────────────────────────────

(defn- qualify-guard
  "The guard on `door`, a :kind/action token. Its remedy is the field's
  own picker, narrowed: the same door again with another value in the
  field, which the field's x-ref offers only the qualified for."
  [gname field problem law door]
  (g/guard {:name gname
            :explain "{problem}"
            :judges [field]
            :remedies [{:door door :fields [field]}]
            :reads [:member :team]
            :vars [:problem]
            :check (with-meta
                     (fn [row inp ctx]
                       (if-some [p (problem row inp ctx)]
                         (t/deny {:vars {:problem p}})
                         (t/allow)))
                     {:waymark10/form law})}))

(defn- role-guard [{:keys [field role]} door]
  (qualify-guard (guard-name field "role") field
                 (fn [_row inp ctx] (role-problem field role inp ctx))
                 (list 'member-holds (name field) role)
                 door))

(defn- in-team-guard [{:keys [field in-team]} door]
  (qualify-guard (guard-name field "in-team") field
                 (fn [row inp ctx] (in-team-problem field in-team row inp ctx))
                 (list 'member-in-team (name field) in-team)
                 door))

(defn- with-guard
  "`gs` with `g`, once: a registry built again from its own rdefs finds
  the guard already there."
  [gs g]
  (let [gs (vec gs)]
    (if (some #(= (:name g) (:name %)) gs) gs (conj gs g))))

(defn- carries? [form field]
  (boolean (and form (some #{field} (schema/entry-keys form)))))

(defn- guarded
  "`r` with `(mk door)` on every door whose input carries `field`, door
  being that door's :kind/action token (a create's is :kind/create)."
  [r kind field mk]
  (let [door #(keyword (name kind) (name %))]
    (cond-> (reduce-kv (fn [r a ad]
                         (if (carries? (:input ad) field)
                           (update-in r [:actions a :guards] with-guard (mk (door a)))
                           r))
                       r
                       (or (:actions r) {}))
      (seq (:creates r))
      (update :creates (fn [cs] (mapv #(cond-> % (carries? (:input %) field)
                                         (update :guards with-guard (mk (door :create))))
                                      cs)))
      (carries? (or (:create-schema r) (:schema r)) field)
      (update :create-guards with-guard (mk (door :create))))))

(defn derive-kinds
  "`kinds` ({kind rdef}) with the guards of every member ref that says
  who may be named — the registry's step before it fingerprints. Throws
  the [qualifies] definition error for a ref its kind cannot hold."
  [kinds]
  (reduce
   (fn [ks [kind r]]
     (reduce
      (fn [ks {:keys [field role] :as f}]
        (when-not (= :member (keyword (name (:kind f))))
          (fail kind (str (name field) " says who may be named, and names a " (name (:kind f))
                          "; :role and :in-team ride a member ref")))
        (when (and (some? role) (not (and (string? role) (not (str/blank? role)))))
          (fail kind (str (name field) " :role is a role name, not " (pr-str role))))
        (when (contains? f :in-team)
          (let [it (:in-team f)]
            (when-not (or (string? it) (and (seq? it) (empty? (expr/problems it))))
              (fail kind (str (name field) " :in-team is a team's name or an expression naming one, not "
                              (pr-str it))))))
        (cond-> ks
          (some? role) (update kind guarded kind field #(role-guard f %))
          (contains? f :in-team) (update kind guarded kind field #(in-team-guard f %))))
      ks
      (filter #(or (some? (:role %)) (contains? % :in-team))
              (schema/ref-fields (:schema r)))))
   kinds
   (sort-by key kinds)))
