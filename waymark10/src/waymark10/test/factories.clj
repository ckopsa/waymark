(ns waymark10.test.factories
  "State factories the applications never hand-write: the machine
  walks itself (waymark9 testing/factories.py, design §9). A create
  body comes from the schema, each step's input from the guards' own
  declared acceptance sets, and every dead end is an honest skip that
  names the action and the registration that fixes it — the suite
  derives more and asks for less.

  This is a library namespace: applications depend on it from their
  test scope. It pulls malli.generator, so test.check must be on the
  classpath.

  Inputs cross into invoke! wire-shaped (ISO strings, not LocalDate):
  the body digest admits only canonical JSON values, so synthesis
  encodes through the action's schema before invoking and the load
  boundary decodes back."
  (:require [clojure.test.check.generators :as tcgen]
            [malli.generator :as mg]
            [waymark10.guards :as g]
            [waymark10.machine :as machine]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Instant LocalDate)))

(set! *warn-on-reflection* true)

;; ── generation over the waymark leaf types ──────────────────────────
;; schema.clj declares :pred but no :gen/gen for the :waymark/* types
;; (malli cannot invent a LocalDate from a pred), so the suite grafts
;; generators onto the form at generation time. Dates generate in the
;; past (2000–2024): clock-gated guards ("the plan starts {start}")
;; hold by default, and a test that needs the future overrides. Refs
;; and vocab tokens generate as short readable strings.

(def ^:private type-gens
  {:waymark/date    (tcgen/fmap #(LocalDate/ofEpochDay (long %))
                                (tcgen/choose 10957 20088))
   :waymark/instant (tcgen/fmap #(Instant/ofEpochSecond (long %))
                                (tcgen/choose 946684800 1735689600))
   :waymark/ref     (tcgen/fmap #(str "ref-" %) (tcgen/choose 0 999999))
   :waymark/vocab   (tcgen/elements ["family" "quick" "bbq" "soup" "veggie"])})

(defn- with-gens
  "Rewrite a schema form so every :waymark/* leaf carries :gen/gen.
  Structure-aware, not a blind walk: :map entry keys and property
  maps stay untouched."
  [form]
  (cond
    (keyword? form)
    (if-some [gen (type-gens form)] [form {:gen/gen gen}] form)

    (vector? form)
    (let [[head & more] form
          [props children] (if (map? (first more))
                             [(first more) (rest more)]
                             [nil more])]
      (cond
        (type-gens head)
        (into [head (assoc (or props {}) :gen/gen (type-gens head))] children)

        (= :map head)
        (into (cond-> [:map] props (conj props))
              (map (fn [entry]
                     (if (vector? entry)
                       (let [[k & emore] entry
                             [eprops [child]] (if (map? (first emore))
                                                [(first emore) (rest emore)]
                                                [nil emore])]
                         (if eprops
                           [k eprops (with-gens child)]
                           [k (with-gens child)]))
                       entry)))
              children)

        :else
        (into (cond-> [head] props (conj props))
              (map with-gens)
              children)))

    :else form))

(defn generate
  "One generated value of a schema form, waymark types included.
  Deterministic given :seed; :size stays small — the suite wants
  plausible documents, not adversarial ones."
  [form {:keys [seed size] :or {seed 0 size 6}}]
  (mg/generate (schema/schema (with-gens form)) {:seed seed :size size}))

(defn sample
  "Ten generated values of a schema form (the schema-guard-gap fuzz)."
  [form {:keys [seed size] :or {seed 0 size 8}}]
  (mg/sample (schema/schema (with-gens form)) {:seed seed :size size}))

;; ── the walker principal ────────────────────────────────────────────

(defn walker-principal
  "A fresh system principal per step: the walker is a client, not a
  backdoor — role-gated paths need a registered example or factory."
  []
  (t/principal {:id "walker" :type :system :display "Conformance walker"}))

;; ── per-action input overrides ──────────────────────────────────────

(def examples
  "The example-input! registry: {[kind action] input-or-fn}. :create
  is the pseudo-action for create bodies. An atom in the namespace —
  applications register at test-load time."
  (atom {}))

(def state-factories
  "The state-factory registry (waymark9 testing/factories.py's
  @state_factory, ported with phase 8): {kind (fn [eng target-state]
  → row)}. The override, not the baseline tax — with no registration
  the machine walks itself; register one only when a path needs staged
  context generation cannot supply (mealplan10's covered week: seven
  mark_eating_out self-loops feed finalize's require gate, and a
  shortest-path walk cannot spell a self-loop)."
  (atom {}))

(defn state-factory!
  "Register a per-kind state factory: (fn [eng target-state] → the row
  in that state, or {:skip {:state … :reason …}})."
  [kind f]
  (swap! state-factories assoc kind f)
  f)

(defn example-input!
  "Register an input override for the inputs generation can't satisfy
  — check-style guards with acknowledged open judgment keep these
  rare. input-or-fn is a wire-shaped body map, or (fn [eng] → body)."
  ([kind action input-or-fn]
   (example-input! examples kind action input-or-fn))
  ([registry kind action input-or-fn]
   (swap! registry assoc [kind action] input-or-fn)
   input-or-fn))

(defn example-for
  "The registered override, resolved (fns receive the engine); nil
  when none is registered."
  [eng kind action]
  (when-some [x (get @examples [kind action])]
    (if (fn? x) (x eng) x)))

;; ── honest skips ────────────────────────────────────────────────────

(def ^:private last-skip (atom nil))

(defn skip-reason
  "Why the last synthesize-input returned nil; nil when it simply had
  no input schema to synthesize for."
  []
  @last-skip)

(defn- skip! [reason]
  (reset! last-skip reason)
  nil)

(defn problem-data
  "The ex-data of a waymark problem, nil for anything else."
  [e]
  (let [d (ex-data e)]
    (when (:waymark10/problem d) d)))

;; ── probing (advertisement reads the same guards as enforcement) ────

(defn probe-ctx
  "A probe-mode context for guard evaluation outside an invocation.
  Carries :read/:find backed by their own transactions (phase 8) so
  acceptance sets that read other kinds can synthesize inputs — the
  render probe stays storage-free; this one is the suite's."
  [eng]
  (t/ctx {:principal (walker-principal)
          :now ((:now-fn eng))
          :services (:services eng)
          :mode :probe
          :read (fn [kind id]
                  (when-some [rdef (get (inv/resources eng) kind)]
                    (store/with-tx (:storage eng)
                      (fn [tx]
                        (some->> (store/load-row (:storage eng) tx kind
                                                 (str id) {})
                                 (inv/decode-row rdef))))))
          :find (fn [kind where opts]
                  (when-some [rdef (get (inv/resources eng) kind)]
                    (store/with-tx (:storage eng)
                      (fn [tx]
                        (mapv #(inv/decode-row rdef %)
                              (store/query-rows (:storage eng) tx kind
                                                (or where {})
                                                (merge {:limit 100} opts)))))))}))

(defn probe-denial
  "The first hard (non-warning) probe denial of an action on a row →
  {:guard … :reason … :hide …}; nil when the action advertises
  available."
  [action-def row ctx]
  (some (fn [guard]
          (let [[v d] (g/evaluate guard row nil (assoc ctx :mode :probe))]
            (when (and (t/deny? v) (not= :warning (:severity d)))
              {:guard (:name d)
               :reason (g/render-reason d v row)
               :hide (boolean (:hide d))})))
        (:guards action-def)))

(defn available-actions
  "The actions a probe advertises from the row's state. Bulk actions
  are excluded — a bulk action is not a row walk."
  [rdef row ctx]
  (->> (machine/transitions-from rdef (:state row))
       (remove :bulk)
       (remove #(probe-denial % row ctx))))

;; ── input synthesis: schema sample ∩ declared acceptance sets ───────

(defn- member?
  "Acceptance membership with the string fallback, mirroring the
  enforcement's own comparison."
  [value allowed]
  (boolean (or (some #(= value %) allowed)
               (let [s (str value)]
                 (some #(= s (str %)) allowed)))))

(defn- pick [xs seed]
  (nth xs (mod (long seed) (count xs))))

(defn- fits-singles? [judges tup singles]
  (every? (fn [[f v]]
            (or (not (contains? singles f))
                (member? v (get singles f))))
          (map vector judges tup)))

(defn- op-holds? [op a b]
  (when (and (some? a) (some? b))
    (let [c (try (compare a b) (catch Exception _ nil))]
      (when c
        (case op
          <  (neg? c) <= (not (pos? c))
          >  (pos? c) >= (not (neg? c))
          == (zero? c))))))

(defn- satisfy-op
  "Overlay values satisfying a comparison relation: keep the sample
  when it already holds, swap when the mirror holds, else generate
  fresh pairs (bounded); nil + recorded reason when nothing fits."
  [input-form sample leaf seed who fix]
  (let [[fa fb] (:judges leaf)
        op (:op leaf)
        a (get sample fa)
        b (get sample fb)]
    (cond
      (or (nil? a) (nil? b)) sample   ; required-ness is the schema's job
      (= op '==) (assoc sample fb a)
      (op-holds? op a b) sample
      (op-holds? op b a) (assoc sample fa b, fb a)
      :else
      (let [fa-form (schema/field-schema input-form fa)
            fb-form (schema/field-schema input-form fb)]
        (loop [i 0]
          (if (= i 20)
            (skip! (str who ": no generated pair satisfies (" op " " fa " " fb ")" fix))
            (let [a' (generate fa-form {:seed (+ seed (* 2 i))})
                  b' (generate fb-form {:seed (+ seed (* 2 i) 1)})]
              (cond
                (op-holds? op a' b') (assoc sample fa a', fb b')
                (op-holds? op b' a') (assoc sample fa b', fb a')
                :else (recur (inc i))))))))))

(defn synthesize-input
  "An input map that (a) validates against the action's :input schema
  and (b) satisfies every leaf guard's declared acceptance: single-
  field :accepts members, admissible :relation tuples, comparison
  :op-satisfying values — remaining fields from malli generation.
  Registered examples win outright.

  Returns nil when the action takes no input, or when a declared
  acceptance set is EMPTY on this row — then (skip-reason) names the
  action and the registration that fixes it, and the caller skips
  honestly. The returned map is wire-shaped (schema-encoded)."
  [eng rdef action-def row ctx {:keys [seed] :or {seed 0}}]
  (reset! last-skip nil)
  (if-some [example (example-for eng (:kind rdef) (:name action-def))]
    example
    (when-some [input-form (:input action-def)]
      (let [who (str (name (:kind rdef)) "/" (name (:name action-def)))
            fix (str "; register (example-input! " (:kind rdef) " "
                     (:name action-def) " …) or a factory that satisfies it")
            leaves (mapcat g/iter-leaves (:guards action-def))
            ;; per-field intersection of single-field acceptance sets —
            ;; exactly what render does to the advertised enum
            singles (reduce
                     (fn [m leaf]
                       (if (and (:accepts leaf)
                                (not (:relation leaf))
                                (= 1 (count (:judges leaf))))
                         (let [field (first (:judges leaf))]
                           (if-some [admitted (g/admitted leaf row ctx)]
                             (assoc m field
                                    (if-some [prior (get m field)]
                                      (let [keep (into #{} (map str) admitted)]
                                        (filterv #(contains? keep (str %)) prior))
                                      (vec admitted)))
                             m))
                         m))
                     {} leaves)
            sample (generate input-form {:seed seed})
            sample (reduce-kv
                    (fn [s field admitted]
                      (cond
                        (nil? s) nil
                        (empty? admitted)
                        (skip! (str who ": the intersected acceptance set for "
                                    field " is empty on the walked row" fix))
                        (member? (get s field) admitted) s
                        :else (assoc s field (pick admitted seed))))
                    sample singles)
            ;; relation tuple sets: one admissible tuple, components in
            ;; judges order, preferring a tuple the single-field sets accept
            sample (reduce
                    (fn [s leaf]
                      (cond
                        (nil? s) nil
                        (:op leaf) (satisfy-op input-form s leaf seed who fix)
                        :else
                        (if-some [allowed (g/admitted leaf row ctx)]
                          (if (empty? allowed)
                            (skip! (str who ": relation " (:name leaf)
                                        " admits no tuples on the walked row" fix))
                            (let [ordered (sort-by str (map vec allowed))
                                  chosen (or (first (filter #(fits-singles? (:judges leaf) % singles)
                                                            ordered))
                                             (first ordered))]
                              (merge s (zipmap (:judges leaf) chosen))))
                          s)))
                    sample
                    (filter :relation leaves))]
        (some->> sample (schema/encode input-form))))))

;; ── create ──────────────────────────────────────────────────────────

(defn create-body
  "The body `create-example` would send, without sending it: the
  registered :create example when present, else malli generation over
  :create-schema (or :schema) with derived facts and optional fields
  dropped — derived facts are the engine's to write, optional fields
  the walk's to earn. :overrides merge last (wire-shaped).

  Public because a create body is sometimes wanted as DATA rather
  than as a row: waymark-jfv.4's outcome pieces carry `prepared`, the
  input their target's own create door will take, and a piece staged
  with a body the walk itself would not have sent would be proving
  something narrower than the law."
  [eng kind {:keys [seed overrides] :or {seed 0}}]
  (let [rdef (or (get (inv/resources eng) kind)
                 (throw (ex-info (str "no enrolled kind " kind) {:kind kind})))
        model (or (:create-schema rdef) (:schema rdef))]
    (merge (or (example-for eng kind :create)
               (let [generated (generate model {:seed seed})
                     optional (into #{}
                                    (keep (fn [[k e]] (when (:optional e) k)))
                                    (schema/entry-map model))
                     derived (set (keys (:derived rdef)))]
                 (schema/encode model (apply dissoc generated
                                             (into optional derived)))))
           overrides)))

(defn create-example
  "Invoke create! with a synthesized create body (`create-body`).
  Warning-severity create guards are acknowledged on retry. Returns
  the create! result {:row … :transition …}."
  [eng kind {:keys [seed overrides] :or {seed 0}}]
  (let [body (create-body eng kind {:seed seed :overrides overrides})
        go (fn [ack] (inv/create! eng kind body
                                  {:principal (walker-principal)
                                   :acknowledged ack}))]
    (try (go #{})
         (catch clojure.lang.ExceptionInfo e
           (let [d (ex-data e)]
             (if (= :warning-required (:waymark10/problem d))
               (go (set (get-in d [:acknowledge :names])))
               (throw e)))))))

;; ── one walker invocation ───────────────────────────────────────────

(defn walker-invoke!
  "One walker step: an Idempotency-Key when the action is not
  idempotent, the etag fence when declared, warnings acknowledged on
  retry — the acknowledge protocol is not a wall for an honest
  client. Returns the invoke! result."
  [eng kind row action-def body]
  (let [opts {:principal (walker-principal)
              :idempotency-key (when-not (get-in action-def [:safety :idempotent])
                                 (str (random-uuid)))
              :if-match (when (get-in action-def [:safety :fence])
                          (inv/etag kind (:id row) (:version row)))}
        go (fn [o] (inv/invoke! eng kind (:id row) (:name action-def) body o))]
    (try (go opts)
         (catch clojure.lang.ExceptionInfo e
           (let [d (ex-data e)]
             (if (= :warning-required (:waymark10/problem d))
               (go (assoc opts :acknowledged (set (get-in d [:acknowledge :names]))))
               (throw e)))))))

;; ── the derived state factory ───────────────────────────────────────

(defn walk-to-state
  "Create via the schema, then follow the machine's shortest action
  path to `target`, synthesizing each step's input from acceptance
  sets. Returns the row in the target state, or {:skip {:state …
  :reason …}} — and the reason always NAMES the blocking action and
  the registration that would fix it (waymark9's honest SkipState)."
  [eng kind target {:keys [seed] :or {seed 0}}]
  (let [rdef (get (inv/resources eng) kind)
        skip (fn [reason] {:skip {:state target :reason reason}})]
    (cond
      (nil? rdef) (skip (str "no enrolled kind " kind))
      ;; a registered factory shadows the machine walk outright
      (get @state-factories kind)
      ((get @state-factories kind) eng target)
      (nil? (machine/path-to rdef target))
      (skip (str (name target) " is unreachable from " (name (:initial rdef))
                 " by non-bulk transitions"))
      :else
      (let [created (try (create-example eng kind {:seed seed})
                         (catch clojure.lang.ExceptionInfo e
                           (if-some [d (problem-data e)]
                             (skip (str "the synthesized create for " (name kind)
                                        " was refused (" (:status d) ": " (:detail d)
                                        "); register (example-input! " kind
                                        " :create …) or a factory"))
                             (throw e))))]
        (if (:skip created)
          created
          (loop [row (:row created)
                 path (machine/path-to rdef target)
                 i 0]
            (if-some [step (first path)]
              (let [ctx (probe-ctx eng)
                    body (synthesize-input eng rdef step row ctx {:seed (+ seed i)})]
                (if-some [reason (when (and (nil? body) (:input step))
                                   (skip-reason))]
                  (skip reason)
                  (let [res (try (walker-invoke! eng kind row step body)
                                 (catch clojure.lang.ExceptionInfo e
                                   (if-some [d (problem-data e)]
                                     (skip (str "walking " (name kind) " to "
                                                (name target) ": " (name (:name step))
                                                " refused (" (:status d) ": "
                                                (:detail d) "); register (example-input! "
                                                kind " " (:name step)
                                                " …) or a factory"))
                                     (throw e))))]
                    (if (:skip res)
                      res
                      (recur (:row res) (rest path) (inc i))))))
              row)))))))
