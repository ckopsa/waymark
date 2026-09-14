(ns waymark10.process
  "The :process key — a workflow as a resource (docs/spec-process.md).

  A process touches several kinds in one cohesive story: close the
  plan, compile its grocery list, open the next plan. Before this key
  every such story was hand-written inside one kind's handler
  (grocery_list's compile_from_plan, plan's on-create births) or as a
  bespoke staging kind (outcome's make_it_so), and nothing projected
  from a declaration: the engine could not say what the steps were,
  where a row stood in them, or what reversed step two.

  The claim is the :decision key's claim — IT IS NOT A NEW MECHANISM.
  :process desugars into ordinary :states, :actions, handlers, schema
  entries and :touches, ahead of :decision and :flow, before the check
  battery and before the fingerprint. Every step is one write through
  the handler's cross-write doors (ctx :invoke / ctx :create), which
  already run the target's full per-item algorithm under the outer
  principal in the outer transaction. The router, the render probe,
  the conformance driver, MCP — nothing downstream learns a new noun.

  ── the two modes ──

  :atomic — one door, `run`, fires every step inside ONE transaction.
  A refusal anywhere rolls the whole tap back, so no step is ever
  half-landed and no compensation exists to spell. This is
  outcome.make_it_so's rule, generalized.

  :durable — each step is its own door and its own logged transition,
  so a person may tap between steps and a step may reach an external
  system. Here compensation is real: `roll_back` walks the completed
  steps backward through each step's declared :undo door, in one
  transaction. Every step a roll-back could reach must therefore
  spell an :undo, and the one that cannot be reversed is the :pivot —
  after it, roll_back is not offered. That rule is checked at the
  declaration site, not discovered in production.

  ── what is derived, never typed ──

  :touches for every projected door comes from the steps, so the
  blast-radius advertisement cannot lie by omission; the handler's
  stateable identity (:waymark10/form) is built from the step DATA,
  so the fingerprint is a function of the steps and moves exactly
  when a step does; and the cross-kind facts (the target door exists,
  takes the input the step gives it, is not fenced or bulk, the undo
  departs from where the do landed) are checked by the assembly
  battery (checks_assembly/check-process), where every kind is known.

  ── what is deliberately not attempted ──

  No branches, loops or conditions: a step names a declared door and
  binds fields to its input with the expression vocabulary's `data`
  and `now` reads, nothing else. A process that needs a branch is two
  processes, or a guard on the target door. No log-consumer
  choreography: the process row is the orchestrator, and the owns
  cascade stays the only other cross-kind mechanism."
  (:require [clojure.string :as str]
            [waymark10.expr :as expr]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── the closed key sets ─────────────────────────────────────────────

(def process-keys
  "The :process map's whole authored surface."
  #{:binds :steps :mode})

(def step-keys
  "One step's whole authored surface."
  #{:name :do :binds :undo :pivot :display})

(def modes #{:atomic :durable})

(def reserved-action-names
  "The doors the sugar itself projects — a step may not wear one."
  #{:run :roll_back :abandon :create})

(def ^:private snake #"[a-z][a-z0-9_]*")

(defn- sugar-err [kind msg]
  (throw (t/definition-error
          (str (some-> kind name) " :process — " msg))))

;; ── the step grammar ────────────────────────────────────────────────

(defn- check-input-form!
  "A step's input map binds target fields to values the process row
  can supply: scalar literals, or law forms over the expression
  vocabulary reading only `(data :field)` of THIS row and `(now)` —
  never `input` (a step door takes none), `var` or `it` (no scope
  binds them here)."
  [kind sname input]
  (when (some? input)
    (when-not (map? input)
      (sugar-err kind (str "step " (name sname) " :do input is a map")))
    (doseq [[k v] input]
      (when-not (keyword? k)
        (sugar-err kind (str "step " (name sname) " input key " (pr-str k)
                             " is not a keyword")))
      (when (or (seq? v) (symbol? v))
        (when-some [ps (seq (expr/problems v))]
          (sugar-err kind (str "step " (name sname) " input " k ": "
                               (str/join "; " ps))))
        (let [ops (into #{} (comp (filter seq?) (map first))
                        (tree-seq seq? rest v))]
          (when-some [bad (seq (filter #{'input 'var 'it} ops))]
            (sugar-err kind (str "step " (name sname) " input " k " reads "
                                 (vec bad) " — a step reads only (data …) "
                                 "of its own row and (now)"))))))))

(defn- parse-do
  "[kind id-field action input?] — invoke the target row the named
  field points at; [kind :create input?] — birth a target row."
  [kind sname form]
  (when-not (and (vector? form) (<= 2 (count form) 4)
                 (keyword? (first form)) (keyword? (second form)))
    (sugar-err kind (str "step " (name sname) " :do is [kind id-field action "
                         "input?] or [kind :create input?]")))
  (let [[tk f & more] form]
    (if (= :create f)
      (let [[input & extra] more]
        (when (seq extra)
          (sugar-err kind (str "step " (name sname) " :do [kind :create input?]"
                               " takes at most one input map")))
        (check-input-form! kind sname input)
        (cond-> {:kind tk :door :create}
          (some? input) (assoc :input input)))
      (let [[a input & extra] more]
        (when-not (keyword? a)
          (sugar-err kind (str "step " (name sname) " :do names the action "
                               "to invoke as a keyword")))
        (when (seq extra)
          (sugar-err kind (str "step " (name sname) " :do takes at most one "
                               "input map")))
        (check-input-form! kind sname input)
        (cond-> {:kind tk :door :invoke :id-field f :action a}
          (some? input) (assoc :input input))))))

(defn- parse-undo
  "[kind id-field action] — the door that reverses this step. It takes
  no input: a compensation is a plain reverse, never a re-ask."
  [kind sname form binds]
  (when-not (and (vector? form) (= 3 (count form)) (every? keyword? form))
    (sugar-err kind (str "step " (name sname) " :undo is [kind id-field action]")))
  (let [[tk f a] form]
    (when (= :create f)
      (sugar-err kind (str "step " (name sname) " :undo cannot name the create "
                           "door — a birth is reversed through the born row's "
                           "own door, by the field the step :binds")))
    {:kind tk :id-field f :action a
     ;; a birth's undo naturally points at the field the step bound
     :own-birth (boolean (and binds (= f binds)))}))

(defn- parse-step [kind mode i step]
  (when-not (map? step)
    (sugar-err kind (str "step " i " is a map")))
  (when-some [unknown (seq (sort (remove step-keys (keys step))))]
    (sugar-err kind (str "step " i " has unknown key(s) " (vec unknown)
                         "; a step speaks " (vec (sort step-keys)))))
  (let [sname (:name step)]
    (when-not (and (keyword? sname) (re-matches snake (name sname)))
      (sugar-err kind (str "step " i " :name is a snake_case keyword")))
    (when (contains? reserved-action-names sname)
      (sugar-err kind (str "step " (name sname) " wears a door the sugar "
                           "projects itself — " (vec (sort reserved-action-names)))))
    (when-not (contains? step :do)
      (sugar-err kind (str "step " (name sname) " declares :do")))
    (let [binds (:binds step)
          _ (when (and (some? binds)
                       (not (and (keyword? binds) (re-matches snake (name binds)))))
              (sugar-err kind (str "step " (name sname) " :binds is a snake_case "
                                   "field keyword")))
          do* (parse-do kind sname (:do step))
          _ (when (and binds (not= :create (:door do*)))
              (sugar-err kind (str "step " (name sname) " :binds a field but "
                                   "invokes an existing row — only a :create "
                                   "step births an id to bind")))
          undo (when (contains? step :undo)
                 (parse-undo kind sname (:undo step) binds))
          pivot (boolean (:pivot step))]
      (when (and pivot (= :atomic mode))
        (sugar-err kind (str "step " (name sname) " declares :pivot, but an "
                             ":atomic process has no partial completion to "
                             "pivot from — one transaction lands all or none")))
      (when (and undo (= :atomic mode))
        (sugar-err kind (str "step " (name sname) " declares :undo, but an "
                             ":atomic process never compensates — a refusal "
                             "rolls the one transaction back")))
      (when (and (= :create (:door do*)) undo (not binds))
        (sugar-err kind (str "step " (name sname) " births a row and declares "
                             "an :undo, so it must :bind the born id for the "
                             "undo to find")))
      (cond-> {:name sname :do do*}
        binds (assoc :binds binds)
        undo (assoc :undo undo)
        pivot (assoc :pivot true)
        (:display step) (assoc :display (:display step))))))

;; ── the projected machine ───────────────────────────────────────────

(defn landing-state
  "The state a step's completion lands in: <name>_done for every step
  but the last, which lands in :done."
  [steps i]
  (if (= i (dec (count steps)))
    :done
    (keyword (str (name (:name (nth steps i))) "_done"))))

(defn- origin-state [steps i]
  (if (zero? i) :staged (landing-state steps (dec i))))

(defn- pivot-index
  "The index of the :pivot step, or the step count when none — roll
  back is offered from the landing of every step BEFORE it."
  [steps]
  (or (some (fn [[i s]] (when (:pivot s) i)) (map-indexed vector steps))
      (count steps)))

(defn- reversible-indices
  "Steps whose landing state offers roll_back: complete, before the
  pivot, and not the last (a finished process is history)."
  [steps]
  (let [p (pivot-index steps)]
    (filter #(and (< % p) (< % (dec (count steps))))
            (range (count steps)))))

(defn- humanise [k] (str/capitalize (str/replace (name k) "_" " ")))

;; ── the handlers ────────────────────────────────────────────────────

(defn- wire-scalar
  "A handler holds DECODED values (a LocalDate, an Instant); the
  invoke door carries what the wire would have — strings for the
  temporal types, everything else as it is."
  [v]
  (if (instance? java.time.temporal.Temporal v) (str v) v))

(defn- input-of
  "The step's input map evaluated over the row: literals pass, law
  forms read (data …) of this row and (now)."
  [input row ctx]
  (when (some? input)
    (let [scope {:data (:data row) :now (:now ctx)}]
      (into {}
            (map (fn [[k v]]
                   [k (wire-scalar
                       (if (or (seq? v) (symbol? v))
                         (expr/evaluate (expr/normalize v) scope)
                         v))]))
            input))))

(defn run-step
  "One step, through the cross-write door the row's ctx carries. A
  refusal anywhere in the inner write throws the inner problem, which
  refuses THIS door with the target's own sentence — the process does
  not predict another kind's guards, it runs them."
  [row ctx step]
  (let [{:keys [kind door id-field action input]} (:do step)
        inp (input-of input row ctx)
        res (if (= :create door)
              ((:create ctx) kind inp)
              ((:invoke ctx) kind
               (or (get-in row [:data id-field]) "unset")
               action inp))]
    (if-some [b (:binds step)]
      (assoc-in row [:data b] (:id (:row res)))
      row)))

(defn run-steps
  "The :atomic door: every step, in order, one transaction."
  [row ctx steps]
  (reduce (fn [r s] (run-step r ctx s)) row steps))

(defn roll-back
  "The :durable compensation: the completed steps, newest first, each
  through its :undo door. Which steps completed is the row's own
  state — the landing state names the last step done."
  [row ctx steps]
  (let [landings (map-indexed (fn [i _] (landing-state steps i)) steps)
        done (count (take-while #(not= % (:state row)) landings))]
    (doseq [s (reverse (take (inc done) steps))
            :let [{:keys [kind id-field action]} (:undo s)]]
      ((:invoke ctx) kind
       (or (get-in row [:data id-field]) "unset")
       action nil))
    row))

(defn- stateable
  "A handler whose identity is the step DATA it closes over — the
  fingerprint reads the printed form, so it moves exactly when a step
  does and never with the load."
  [f form]
  (with-meta f {:waymark10/form form}))

(defn- step-handler [step]
  (stateable (fn [row _inp ctx] (run-step row ctx step))
             (list 'waymark10.process/run-step 'row 'ctx step)))

(defn- run-handler [steps]
  (stateable (fn [row _inp ctx] (run-steps row ctx steps))
             (list 'waymark10.process/run-steps 'row 'ctx steps)))

(defn- roll-back-handler [steps]
  (stateable (fn [row _inp ctx] (roll-back row ctx steps))
             (list 'waymark10.process/roll-back 'row 'ctx steps)))

;; ── touches, derived ────────────────────────────────────────────────

(defn- touch-of [{:keys [kind door action]}]
  {:kind kind :action (if (= :create door) :create action)})

(defn step-touches [step] [(touch-of (:do step))])

(defn- undo-touches
  "roll_back reaches a different set of doors from each origin, so
  every one is :may — a conditional write, exactly what :may spells."
  [steps]
  (vec (distinct (for [s steps :when (:undo s)]
                   (assoc (touch-of (:undo s)) :may true)))))

;; ── the projection ──────────────────────────────────────────────────

(defn- door-label [{:keys [kind door action]}]
  (str (name kind) "." (if (= :create door) "create" (name action))))

(defn- step-safety
  "A step door leaves its state, so the machine's silent-one-way rule
  (checks/check-one-way) asks it to say what it is. It is not
  :reversible in the check's sense — roll_back lands in :rolled_back,
  never back at the origin — so the door acknowledges itself
  :one-way, and the sentence says whether roll_back can still undo
  its effect."
  [steps i]
  (let [s (nth steps i)
        undoable (some #{i} (reversible-indices steps))]
    {:idempotent true :reversible false :confirm false
     :one-way (str "Step " (name (:name s)) " runs " (door-label (:do s))
                   (if undoable
                     "; roll_back reverses every step taken so far."
                     " and cannot be reversed from this process."))}))

(defn- step-action [steps i]
  (let [s (nth steps i)]
    {:from #{(origin-state steps i)}
     :to (landing-state steps i)
     :safety (step-safety steps i)
     :handler (step-handler s)
     :touches (step-touches s)
     :display (merge {:label (humanise (:name s)) :order (inc i)}
                     (:display s))}))

(defn- abandon-action [n]
  {:from #{:staged} :to :abandoned
   :safety {:idempotent true :reversible false :confirm false
            :one-way "An abandoned process never ran; stage another to try again."}
   :display {:label "Abandon" :order (+ n 2)}})

(defn- durable-actions [steps]
  (let [n (count steps)
        rev (reversible-indices steps)
        base (into {}
                   (map (fn [i] [(:name (nth steps i)) (step-action steps i)]))
                   (range n))
        base (assoc base :abandon (abandon-action n))]
    (if (seq rev)
      (assoc base :roll_back
             {:from (into #{} (map #(landing-state steps %)) rev)
              :to :rolled_back
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A rolled-back process reversed every step it had taken."}
              :handler (roll-back-handler steps)
              :touches (undo-touches steps)
              :display {:label "Roll back" :order (inc n)}})
      base)))

(defn- atomic-actions [steps]
  {:run {:from #{:staged} :to :done
         :safety {:idempotent true :reversible false :confirm false
                  :one-way (str "Runs " (str/join ", " (map (comp door-label :do) steps))
                                " in one transaction — all land, or none does.")}
         :handler (run-handler steps)
         :touches (vec (distinct (mapcat step-touches steps)))
         :display {:label "Run" :order 1}}
   :abandon (abandon-action 1)})

(defn- durable-states [steps]
  (let [n (count steps)]
    (-> [:staged]
        (into (map #(landing-state steps %)) (range (dec n)))
        (conj :done)
        (cond-> (seq (reversible-indices steps)) (conj :rolled_back))
        (conj :abandoned))))

(defn- map-form-keys [form]
  (if (and (vector? form) (= :map (first form)))
    (into #{} (comp (filter vector?) (map first)) (rest form))
    #{}))

(defn- add-entries [form entries]
  (let [have (map-form-keys form)]
    (into (or form [:map])
          (remove #(contains? have (first %)))
          entries)))

(defn- keep-entries [form drop-fields]
  (into [:map]
        (remove #(and (vector? %) (contains? drop-fields (first %))))
        (rest form)))

(defn- check-undo-coverage!
  "Every step a roll-back could reach spells its :undo: the steps
  before the pivot, except the last (a finished process is history,
  and roll_back is not offered from :done)."
  [kind steps]
  (doseq [i (reversible-indices steps)
          j (range (inc i))
          :let [s (nth steps j)]
          :when (not (:undo s))]
    (sugar-err kind (str "step " (name (:name s)) " has no :undo, but roll_back "
                         "is offered after step " (name (:name (nth steps i)))
                         " and would have to reverse it — declare the undo "
                         "door, or mark the first irreversible step :pivot"))))

(defn desugar
  "The :process key → ordinary states, actions, handlers, schema
  entries and touches. Runs FIRST in normalize-resource's thread.

  | declared        | projected                                          |
  |-----------------|----------------------------------------------------|
  | :steps (durable)| :staged → <step>_done … → :done, one door per step, |
  |                 | roll_back from the reversible landings, abandon    |
  | :steps (atomic) | :staged → :done through one `run` door, abandon    |
  | :binds          | required :waymark/ref entries, the create input    |
  | step :binds     | optional :waymark/ref entries the steps stamp      |
  | every door      | :touches from the steps, the handler's form from   |
  |                 | the step data                                      |
  | always          | :filterable over state, newest-first sort, summary |

  The machine IS the steps, so :states/:initial/:terminal are refused
  beside the key; :decision is refused too (one machine per kind).
  Schema entries fill blanks (a hand-spelled field wins over its
  generated twin); an action also named in :actions is the
  one-home-per-action refusal. The normalized :process map stays on
  the declaration for the assembly battery and for advertisement —
  fingerprint-of names no facet for it, so it hashes through the
  doors it projected and nowhere else."
  [rmap]
  (let [p (:process rmap)
        kind (:kind rmap)]
    (if (nil? p)
      rmap
      (do
        (when-not (map? p)
          (sugar-err kind "is a map"))
        (when-some [unknown (seq (sort (remove process-keys (keys p))))]
          (sugar-err kind (str "unknown key(s) " (vec unknown) "; a process speaks "
                               (vec (sort process-keys)))))
        (when (:decision rmap)
          (sugar-err kind "and :decision on one kind — one machine per kind"))
        (doseq [k [:states :initial :terminal]]
          (when (contains? rmap k)
            (sugar-err kind (str "declares " k " — the steps are the machine"))))
        (let [mode (:mode p :atomic)
              _ (when-not (contains? modes mode)
                  (sugar-err kind (str ":mode is " (vec (sort modes)))))
              binds (:binds p {})
              _ (when-not (and (map? binds)
                               (every? keyword? (keys binds))
                               (every? keyword? (vals binds)))
                  (sugar-err kind ":binds is {field-keyword target-kind-keyword}"))
              raw-steps (:steps p)
              _ (when-not (and (vector? raw-steps) (seq raw-steps))
                  (sugar-err kind ":steps is a non-empty vector of step maps"))
              steps (vec (map-indexed #(parse-step kind mode %1 %2) raw-steps))
              names (map :name steps)
              _ (when-some [dup (seq (for [[n c] (frequencies names) :when (< 1 c)] n))]
                  (sugar-err kind (str "step names repeat: " (vec (sort dup)))))
              _ (when (< 1 (count (filter :pivot steps)))
                  (sugar-err kind "at most one step is the :pivot"))
              _ (when (= :durable mode) (check-undo-coverage! kind steps))
              bound (into #{} (keep :binds) steps)
              _ (doseq [b bound]
                  (when (contains? binds b)
                    (sugar-err kind (str "field " b " is both a create :binds "
                                         "and a step's :binds — one home per field"))))
              _ (doseq [s steps
                        :let [{:keys [door id-field]} (:do s)]
                        :when (and (= :invoke door)
                                   (not (contains? binds id-field))
                                   (not (contains? bound id-field))
                                   (not (contains? (map-form-keys (:schema rmap))
                                                   id-field)))]
                  (sugar-err kind (str "step " (name (:name s)) " invokes the row at "
                                       id-field ", which no :binds, step :binds or "
                                       ":schema entry supplies")))
              _ (doseq [s steps
                        :let [f (:id-field (:undo s))]
                        :when (and f
                                   (not (contains? binds f))
                                   (not (contains? bound f))
                                   (not (contains? (map-form-keys (:schema rmap)) f)))]
                  (sugar-err kind (str "step " (name (:name s)) " :undo reads "
                                       f ", which no :binds, step :binds or "
                                       ":schema entry supplies")))
              actions (if (= :atomic mode)
                        (atomic-actions steps)
                        (durable-actions steps))
              _ (doseq [aname (keys actions)]
                  (when (contains? (:actions rmap) aname)
                    (sugar-err kind (str (name aname) " is also declared in "
                                         ":actions — one home per action"))))
              step-kind-of (into {} (keep (fn [s] (when-some [b (:binds s)]
                                                    [b (:kind (:do s))])))
                                 steps)
              entries (-> []
                          (into (map (fn [[f k]] [f {:kind k} :waymark/ref]))
                                (sort-by key binds))
                          (into (map (fn [[f k]]
                                       [f {:optional true :kind k}
                                        [:maybe :waymark/ref]]))
                                (sort-by key step-kind-of)))
              schema (add-entries (:schema rmap) entries)
              states (if (= :atomic mode)
                       [:staged :done :abandoned]
                       (durable-states steps))]
          (-> rmap
              (assoc :process {:mode mode :binds binds :steps steps})
              (assoc :schema schema)
              (update :create-schema #(or % (keep-entries schema bound)))
              (assoc :states states
                     :initial :staged
                     :terminal (into #{} (filter #{:done :rolled_back :abandoned})
                                     states))
              (update :actions #(merge actions (or % {})))
              (update :filterable #(merge {:state #{:eq :in}} %))
              (update :sortable #(or % {:fields [:created_at]
                                        :default "-created_at"}))
              (update :summary #(or % (str (humanise kind) " · {state}")))))))))
