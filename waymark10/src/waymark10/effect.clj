(ns waymark10.effect
  "The effect door (docs/spec-integrations.md § 4, build step 5): a door
  declares the call it makes, and its transition and one `effect_call`
  row commit in one transaction. The call is made after the commit, by
  the runner (build step 6), never inside it: a wire cannot roll back.

  This is the declaration's half and the pure half of the outbox.
  `weave` judges each `:effect` at the def site, normalizes its forms
  and adds the two hidden doors the engine lands a call through, one
  pair per effect door (`<door>_landed`, `<door>_failed`), because each
  effect door names its own `:lands` and `:fails-to`. `ask` is the call
  a door asks for, over the row as the door leaves it; `says` is the
  probe's sentence. The insert itself is invoke's (`outbox!`), in the
  door's own transaction."
  (:require [clojure.string :as str]
            [waymark10.expr :as expr]
            [waymark10.guards :as g]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def ^:private effect-keys
  #{:power :input :why :idempotency-key :writes :lands :fails-to :compensate})

(def the-engine-lands-it
  "The wall in front of the two hidden doors: only the engine's hand
  lands an effect, and everyone else is told the door is not there."
  (g/guard
   {:name :the-engine-lands-the-effect
    :hide true
    :reads [:principal]
    :explain "The engine lands what an effect's call answered on the row that asked for it; nobody walks this door by hand."
    :check (with-meta
             (fn [_ _ ctx]
               (if (= :system (get-in ctx [:principal :type]))
                 (t/allow)
                 (t/deny)))
             {:waymark10/form '(fn [row inp ctx]
                                 (= :system (:type (:principal ctx))))})}))

(defn landed-door
  "The hidden door that lands effect door `aname`'s answer."
  [aname]
  (keyword (str (name aname) "_landed")))

(defn failed-door
  "The hidden door that lands effect door `aname`'s failure."
  [aname]
  (keyword (str (name aname) "_failed")))

(defn- known [x scope]
  (let [v (expr/evaluate x scope)]
    (when-not (expr/unknown? v) v)))

(defn writes
  "What an answer writes on the row: each `:writes` field, read through
  `(result …)`. A path the answer does not carry writes nothing."
  [eff answer]
  (into {} (keep (fn [[f x]] (when-some [v (known x {:result answer})] [f v])))
        (:writes eff)))

(defn- hidden-doors
  "The two doors the engine lands effect door `aname`'s call through:
  from the state that means asked, to `:lands` (or that same state)
  with the answer's `:writes`, or to `:fails-to` with the sentence."
  [aname asked eff]
  (let [ending [:string {:min 1 :max 200}]
        safety {:idempotent true :reversible false :confirm false
                :one-way "The engine lands what the call answered; the row's own doors are the way on."}]
    {(landed-door aname)
     {:from #{asked} :to (or (:lands eff) asked)
      :engine true
      :guards [the-engine-lands-it]
      :input [:map [:effect_call ending]
              [:answer {:optional true} [:maybe [:map-of :keyword :any]]]]
      :waives #{:edit-shape}
      :handler (with-meta
                 (fn [row inp _ctx]
                   (update row :data merge (writes eff (:answer inp))))
                 {:waymark10/form (list 'effect-landed (:writes eff))})
      :safety safety}
     (failed-door aname)
     {:from #{asked} :to (:fails-to eff)
      :engine true
      :guards [the-engine-lands-it]
      :input [:map [:effect_call ending] [:reason [:string {:min 1 :max 480}]]]
      :waives #{:edit-shape}
      :record true
      :log-says (fn [_row inp] (:reason inp))
      :safety safety}}))

(defn- weave-effects
  "Judge every action's `:effect`, normalize its forms, and add its two
  hidden doors. `[effect-no-key]` and `[effect-no-failure-state]` are
  the battery's to refuse (checks.clj), with their questions; an effect
  with no `:fails-to` opens no failed door. → rmap"
  [rmap]
  (let [states (set (:states rmap))]
    (reduce
     (fn [r [aname a]]
       (let [eff (:effect a)
             bad (fn [s]
                   (throw (t/definition-error
                           (str (some-> (:kind rmap) name) "/" (name aname) " :effect " s))))
             form (fn [f x]
                    (when-some [ps (seq (expr/problems x))]
                      (bad (str f ": " (str/join "; " ps))))
                    (expr/normalize x))
             forms (fn [f m]
                     (when-not (or (nil? m) (and (map? m) (every? keyword? (keys m))))
                       (bad (str f " maps field names to expressions")))
                     (into {} (map (fn [[k x]] [k (form (str f " " k) x)])) m))]
         (when-not (map? eff) (bad "is a map"))
         (when-some [k (first (remove effect-keys (keys eff)))]
           (bad (str "names " k "; an effect takes " (vec (sort effect-keys)))))
         (when-not (and (string? (:power eff)) (not (str/blank? (:power eff))))
           (bad ":power names the tool it calls, as a string"))
         (doseq [k [:fails-to :lands]
                 :let [s (get eff k)]
                 :when (some? s)]
           (when-not (contains? states s)
             (bad (str k " " (pr-str s) " is not a state of this kind"))))
         (when-not (and (keyword? (:to a)) (not= :same (:to a)))
           (bad "stands on a door with no :to, the state that means asked"))
         (when-not (or (nil? (:why eff)) (string? (:why eff)))
           (bad ":why is a sentence"))
         (let [eff (assoc eff
                          :idempotency-key (some->> (:idempotency-key eff) (form ":idempotency-key"))
                          :input (forms ":input" (:input eff))
                          :writes (forms ":writes" (:writes eff)))
               doors (cond-> (hidden-doors aname (:to a) eff)
                       (nil? (:fails-to eff)) (dissoc (failed-door aname)))]
           (doseq [d (keys doors)]
             (when (contains? (:actions rmap) d)
               (bad (str "needs the door " d " for itself, and this kind declares one"))))
           (-> r
               (assoc-in [:actions aname :effect] eff)
               (update :actions merge doors)))))
     rmap
     (filter (comp :effect val) (:actions rmap)))))

(def ^:private compensate-keys #{:power :input :why :fails-to})

(defn undo-door
  "The door a power compensation of effect door `aname` is kept under:
  its effect_call names it, and its failure lands through `<it>_failed`."
  [aname]
  (keyword (str (name aname) "_undo")))

(defn compensation
  "The undo effect door `aname` names (§ 5), as [door eff]: a door of
  this kind with that door's effect, or a power with its input, kept
  under `undo-door`. nil when it names none."
  [rdef aname]
  (let [c (get-in rdef [:actions aname :effect :compensate])]
    (cond (keyword? c) [c (get-in rdef [:actions c :effect])]
          (map? c) [(undo-door aname) c])))

(defn- weave-compensations
  "Judge every `:compensate` (§ 5): an effect door of this kind, or a
  power map {:power :input :fails-to}, whose input forms are normalized
  here. → rmap"
  [rmap]
  (let [states (set (:states rmap))]
    (reduce
     (fn [r [aname a]]
       (let [c (get-in a [:effect :compensate])
             bad (fn [s]
                   (throw (t/definition-error
                           (str (some-> (:kind rmap) name) "/" (name aname)
                                " :effect :compensate " s))))]
         (cond
           (nil? c) r

           (and (keyword? c) (get-in rmap [:actions c :effect])) r

           (map? c)
           (let [door (undo-door aname)]
             (when-some [k (first (remove compensate-keys (keys c)))]
               (bad (str "names " k "; a power compensation takes " (vec (sort compensate-keys)))))
             (when-not (and (string? (:power c)) (not (str/blank? (:power c))))
               (bad ":power names the tool it calls, as a string"))
             (when (nil? (:fails-to c))
               (bad "[effect-no-failure-state] has no :fails-to; name the state a row waits in when the undo fails"))
             (when-not (contains? states (:fails-to c))
               (bad (str ":fails-to " (pr-str (:fails-to c)) " is not a state of this kind")))
             (when-not (or (nil? (:input c))
                           (and (map? (:input c)) (every? keyword? (keys (:input c)))))
               (bad ":input maps field names to expressions"))
             (when-not (or (nil? (:why c)) (string? (:why c)))
               (bad ":why is a sentence"))
             (doseq [d [door (failed-door door)]]
               (when (contains? (:actions rmap) d)
                 (bad (str "needs the door " d " for itself, and this kind declares one"))))
             (assoc-in r [:actions aname :effect :compensate]
                       (assoc c :input
                              (into {} (map (fn [[f x]]
                                              (when-some [ps (seq (expr/problems x))]
                                                (bad (str ":input " f ": " (str/join "; " ps))))
                                              [f (expr/normalize x)]))
                                    (:input c)))))

           :else
           (bad (str "names its undo, an effect door of this kind or a power map"
                     " {:power :input :fails-to}; " (pr-str c) " is not one")))))
     rmap
     (filter (comp :effect val) (:actions rmap)))))

(defn- weave-unwinds
  "Judge every `:unwinds` (§ 5), normalize each `:when`, and open each
  compensation's failed door from the state the unwinding door leaves
  the row in, so a compensation that fails holds the row in its own
  `:fails-to`; a power compensation's failed door is made here. An
  effect door that unwinds does so when it lands: its landed door
  carries the `:unwinds`. → rmap"
  [rmap]
  (reduce
   (fn [r [aname a]]
     (let [bad (fn [s]
                 (throw (t/definition-error
                         (str (some-> (:kind rmap) name) "/" (name aname) " :unwinds " s))))
           us (:unwinds a)
           _ (when-not (and (sequential? us) (seq us) (every? map? us))
               (bad "is a vector of {:effect <door> :when <expression>} maps"))
           us (mapv (fn [u]
                      (when-some [k (first (remove #{:effect :when} (keys u)))]
                        (bad (str "names " k "; an entry takes [:effect :when]")))
                      (let [e (:effect u)]
                        (when-not (get-in rmap [:actions e :effect])
                          (bad (str (pr-str e) " is not an effect door of this kind")))
                        (when-some [ps (seq (some-> (:when u) expr/problems))]
                          (bad (str ":when: " (str/join "; " ps))))
                        (cond-> {:effect e}
                          (some? (:when u)) (assoc :when (expr/normalize (:when u))))))
                    us)
           held (or (get-in a [:effect :lands]) (:to a))
           _ (when (= :same held)
               (bad "lands :same; a door that unwinds lands in one state, where its compensation waits"))]
       (reduce (fn [r {:keys [effect]}]
                 ;; [unwinds-no-compensate] is the battery's to refuse
                 (if-let [[door eff] (compensation r effect)]
                   (let [fd (failed-door door)]
                     (-> r
                         (update-in [:actions fd]
                                    #(or % (assoc (get (hidden-doors door nil eff) fd) :from #{})))
                         (update-in [:actions fd :from] (fnil conj #{}) held)))
                   r))
               (cond-> (assoc-in r [:actions aname :unwinds] us)
                 (:effect a) (assoc-in [:actions (landed-door aname) :unwinds] us))
               us)))
   rmap
   (filter (comp :unwinds val) (:actions rmap))))

(defn weave
  "Judge every action's `:effect`, `:compensate` and `:unwinds` at the
  def site, and add the hidden doors they land through. The findings of
  § 8 — a missing key, failure state or undo — are the battery's
  (checks.clj), which asks each one's question. → rmap"
  [rmap]
  (-> rmap weave-effects weave-compensations weave-unwinds))

(def ^:private undo-prefix "undo:")

(defn undo-key
  "The key a compensation's call is kept under (§ 5): the original's."
  [k]
  (str undo-prefix k))

(defn undo-of
  "The key of the call an undo effect_call compensates, or nil when the
  call undoes nothing."
  [c]
  (let [k (str (get-in c [:data :idempotency_key]))]
    (when (str/starts-with? k undo-prefix) (subs k (count undo-prefix)))))

(defn unwinds?
  "Whether an `:unwinds` entry's `:when` holds over the row as the
  unwinding door leaves it; an entry with none always holds."
  [u row now]
  (or (nil? (:when u))
      (true? (expr/evaluate (:when u) {:data (:data row) :self (some-> (:id row) str) :now now}))))

(defn ask
  "The call a door asks for, over the row as the door leaves it:
  {:power :key :input :why}. A part of the input the row does not carry
  is left out; `:why` fills each {field} from the row's data, as a
  summary does."
  [eff row inp now]
  (let [scope {:data (:data row) :input inp :self (some-> (:id row) str) :now now}]
    (cond-> {:power (:power eff)
             :key (some-> (:idempotency-key eff) (known scope) str)
             :input (into {} (keep (fn [[f x]] (when-some [v (known x scope)] [f v])))
                          (:input eff))}
      (:why eff)
      (assoc :why (str/replace (:why eff) #"\{([A-Za-z0-9_]+)\}"
                               (fn [[_ f]] (str (get (:data row) (keyword f) ""))))))))

(defn says
  "The probe's sentence (§ 7): the call a door would add, or the answer
  it would write again because its key already has a `done` call."
  [{:keys [power key]} reused?]
  (if reused?
    (str "Would call nothing: " power " already answered under key " key
         ", and that answer is written again.")
    (str "Would call " power " once, key " key ".")))
