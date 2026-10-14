(ns factorykit.repo-policy-contract
  "THE REPOSITORY POLICY CONTRACT (ticket 50fd9aba), as
  `factorykit.change-contract` is the change's. `factorykit.repo-policy`
  builds a factory's repo_policy kind from the base; this suite says
  what that kind must then do in the factory's own engine, and a
  factory runs it against its own policy.

  `(failures new-hand)` runs every clause and answers the ones the
  policy broke, each as `{:clause :says}`; an empty answer conforms.
  `new-hand` is a function of no argument that answers a hand on a
  fresh engine, so each clause starts from nothing. The suite requires
  no engine and no factory namespace.

  | key | what it is |
  |---|---|
  | `:declaration` | the repo_policy kind the engine serves |
  | `:fixed` | the stated fields the extension names `:fixed`, which the builder spends and the declaration does not carry |
  | `:mint!` | `(fn [hand fields])` → the row the create door minted; `hand` is `:person` or `:model` (a model acting for nobody), and a refusal throws |
  | `:row` | `(fn [id])` → the row as it stands |
  | `:walk!` | `(fn [hand id door input])` → the row after the door, by the same hands; a refusal throws |"
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def states
  "Every factory's policy has these two states, and no tomb."
  #{:active :retired})

(defn- word [x] (some-> x name keyword))

(defn- state-of [row] (word (:state row)))

(defn- refused?
  "True when `f` throws: the door refused the call."
  [f]
  (try (f) false (catch Exception _ true)))

(defn- fresh-repository []
  (str "contract/repo-" (subs (str (random-uuid)) 0 8)))

(defn- keys-of
  "The field names of a `[:map …]` schema, in its order."
  [schema]
  (when (vector? schema)
    (into [] (keep #(when (vector? %) (first %))) (rest schema))))

(defn- restatement
  "The policy `row` states, as restate's input takes it."
  [declaration row]
  (into {}
        (keep (fn [k] (when-some [v (get-in row [:data k])] [k v])))
        (keys-of (get-in declaration [:actions :restate :input]))))

;; ── the clauses ─────────────────────────────────────────────────────

(defn- the-machine [{:keys [declaration]}]
  (let [ours (set (map word (:states declaration)))
        tomb (set (map word (:terminal declaration)))]
    (cond
      (not= states ours)
      (str "its states are " (pr-str (sort ours)) "; the base's are "
           (pr-str (sort states)))
      (seq tomb)
      (str "its tomb is " (pr-str (sort tomb)) ", and the base has none")
      (not= :active (word (:initial declaration)))
      (str "it starts in " (some-> (:initial declaration) name)
           ", and the base starts in active"))))

(defn- a-model-alone-states-nothing [{:keys [declaration mint! walk! row]}]
  (if (not (refused? #(mint! :model {:repository (fresh-repository)})))
    "a model acting for nobody created a policy"
    (let [id (:id (mint! :person {:repository (fresh-repository)}))
          stood (get-in (row id) [:data :rounds_per_change])
          again (assoc (restatement declaration (row id))
                       :rounds_per_change (inc (long (or stood 3))))]
      (cond
        (not (refused? #(walk! :model id :restate again)))
        "a model acting for nobody restated the policy"
        (not= stood (get-in (row id) [:data :rounds_per_change]))
        "the refused restate changed the policy"
        (refused? #(walk! :person id :restate again))
        "a person was refused restate of the policy as it stands"
        (not= (:rounds_per_change again)
              (get-in (row id) [:data :rounds_per_change]))
        "the person's restate did not write what it stated"
        (not= :active (state-of (row id)))
        "the restate moved the policy out of active"))))

(defn- fixed-is-stated-once [{:keys [declaration fixed]}]
  (let [fixed (set (map word fixed))
        stated (keys-of (:create-schema declaration))
        restated (keys-of (get-in declaration [:actions :restate :input]))
        prefill (get-in declaration [:actions :restate :edit :prefill])
        named #(seq (filter fixed (map word %)))]
    (cond
      (seq (remove (set stated) fixed))
      (str ":fixed names " (pr-str (sort (remove (set stated) fixed)))
           ", which the create form does not state")
      (named restated)
      (str "restate's input names the fixed " (str/join ", " (map name (named restated))))
      (named prefill)
      (str "restate's prefill names the fixed " (str/join ", " (map name (named prefill))))
      (not= (set (remove fixed stated)) (set restated))
      "restate's input is not the create form's fields less the fixed ones"
      (not= (set restated) (set (map word prefill)))
      "restate's prefill is not its input's fields")))

(def clauses
  "The contract, clause by clause: each judge takes a hand and answers
  nil when the policy keeps the clause, or a sentence when it does not."
  [[:the-machine the-machine]
   [:a-model-alone-states-nothing a-model-alone-states-nothing]
   [:fixed-is-stated-once fixed-is-stated-once]])

(defn failures
  "Every clause, each on a fresh hand from `new-hand`. → the clauses the
  policy broke, `{:clause :says}`; `[]` conforms. A judge that throws
  is a broken clause, and says what it threw."
  [new-hand]
  (into []
        (keep (fn [[clause judge]]
                (when-some [says (try (judge (new-hand))
                                      (catch Exception e
                                        (str "it threw: " (ex-message e))))]
                  {:clause clause :says says})))
        clauses))
