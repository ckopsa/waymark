(ns factorykit.repo-policy
  "The repository policy's shared base, and the builder a factory
  composes its own repo_policy kind through (epic 459ed2eb, step 5).

  A FACTORY'S POLICY IS THE BASE AND ITS OWN EXTENSION. The base is
  what every factory's policy has: the two states and no tomb, the
  one-row index, the fields every bench reads, the machine's part of
  the four doors, and the wall that keeps a model alone from stating
  the policy it works under. The extension is a defresource map of
  everything else. `build` answers the map `waymark10.dsl/defresource`
  takes.

  A PERSON STATES ONE PART AND THE ENGINE WRITES THE OTHER. The
  extension names its own stated fields as `:policy` and the engine's
  as `:engine`. The create form, the restate door's input and its
  prefill are the stated fields and nothing else, so the kit writes
  all three from one list: two spellings of one policy would be two
  policies.

  THE CORE DOORS CARRY THE MACHINE ONLY. `restate`, `retire`,
  `restore` and `note_source` are where every factory's policy moves.
  The guards, the handler, the safety and the words are the factory's,
  because the rig a handler tells is the factory's own."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguardfn]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── the one wall ────────────────────────────────────────────────────

(defguardfn a-person-or-their-delegate-states-the-policy
  {:reads [:principal]
   :open "No door here changes this verdict. What submit means is a person's statement about a repository. A person states it in person, or through a delegate that acts for them under a grant the person approved. A model in a seat writes a finding that says which number is wrong and why, and a person taps."
   :explain "The repository policy is the house's own sentence about this repository — the branches, the base, the size ceiling, the rounds and the formatter rule. A model that could restate its own policy could raise its own ceiling. A delegate acting for a person is the person's hand, and the grant it wears is the person's decision."}
  [_row _inp ctx]
  ;; The owner's ruling, 2026-09-19: a person works with a model to
  ;; add a repository, so the wall is against a model ALONE — a seat's
  ;; sitter, an agent that acts for nobody — and not against every
  ;; agent. The shape is mcp_server's `a-person-or-the-engine`: a
  ;; person, the engine's own system actor, or an agent whose
  ;; principal names whom it acts for. The grant is the leash; this
  ;; guard only keeps a seat from restating the policy it works under.
  (let [{:keys [type acts-for]} (:principal ctx)]
    (if (and (= :agent type) (str/blank? (str acts-for)))
      (t/deny)
      (t/allow))))

;; ── the base ────────────────────────────────────────────────────────

(def core
  "What every factory's repo_policy has. `:fields` is the stated fields
  every factory has, in the core's own order. `:offered` is the stated
  fields a factory has only when its extension's `:takes` names them."
  {:states [:active :retired]
   :initial :active
   ;; NEITHER STATE IS A TOMB. A retired policy comes back with one
   ;; tap, because a repository the house stops working is a
   ;; repository it may work again.
   :terminal #{}
   ;; ONE POLICY FOR EACH REPOSITORY, enforced by an index
   :unique [[:repository]]
   :fields
   [[:repository {:x-display
                  {:raw true
                   :label "The repository"
                   :help "The repository as GitHub spells it, as owner/repo. It is also the name the bench rig holds the clone under."}}
     [:string {:min 1 :max 140}]]
    [:branch_pattern {:default "waymark/*"
                      :examples ["waymark/*"]
                      :x-display
                      {:raw true
                       :label "The branch pattern"
                       :help "The shape of a work branch, with one * for the change's own id — waymark/* makes waymark/01HZQ7… . A submit on a branch outside this pattern is refused. The text before the * must not be the name of a branch this repository already has: git holds refs/heads/seat and refs/heads/seat/01HZQ7… never at the same time, so seat/* refuses every worktree in a repository with a branch named seat."}}
     [:string {:min 1 :max 120}]]
    [:base {:default "main"
            :examples ["main"]
            :x-display
            {:raw true
             :label "The base branch"
             :help "The branch the work merges into. The worktree starts from it, and a submit on it is refused whatever the grant says."}}
     [:string {:min 1 :max 120}]]
    ;; OPTIONAL, because a policy stated before it existed is restated
    ;; whole without it, and every reader spells the default itself.
    [:required_checks {:optional true
                       :default []
                       :examples [["gate"]]
                       :x-display
                       {:raw true
                        :label "The checks that must be green"
                        :help "The check names the house waits for before it merges, one for each row. The house merges nothing when this list is empty."}}
     [:vector [:string {:min 1 :max 200}]]]
    [:rounds_per_change {:default 3
                         :examples [3]
                         :x-display
                         {:label "Rounds for one change"
                          :help "How many times a seat may submit one change before the house stops. At the ceiling the change moves to stuck and waits for a person."}}
     [:int {:min 1 :max 20}]]]
   ;; A factory whose forge is not GitHub alone takes these. All are
   ;; OPTIONAL, so a factory that takes one later restates no row.
   :offered
   [[:forge {:optional true
             :examples ["github"]
             :x-display
             {:raw true
              :label "The forge"
              :help "The forge that holds this repository, such as github. Leave it empty for GitHub."}}
     [:maybe [:string {:max 60}]]]
    [:workspace {:optional true
                 :examples ["ckopsa"]
                 :x-display
                 {:raw true
                  :label "The forge's workspace"
                  :help "The workspace, the organisation or the owner this repository lives under on its forge. Leave it empty when the repository's own name says it."}}
     [:maybe [:string {:max 140}]]]
    [:forge_repo {:optional true
                  :examples ["waymark"]
                  :x-display
                  {:raw true
                   :label "The repository on the forge"
                   :help "The name the forge holds this repository under, when it is not the name this policy holds it under."}}
     [:maybe [:string {:max 140}]]]
    [:advisory_checks {:optional true
                       :default []
                       :examples [["lint"]]
                       :x-display
                       {:raw true
                        :label "The checks that only advise"
                        :help "The check names the house reads and reports, one for each row. A red one holds no merge."}}
     [:vector [:string {:min 1 :max 200}]]]]
   ;; THE MACHINE'S PART OF THE FOUR DOORS. The extension's door of the
   ;; same name gives the rest.
   :actions
   {:restate {:from #{:active} :to :active}
    :retire {:from #{:active} :to :retired :undo :restore}
    :restore {:from #{:retired} :to :active :undo :retire}
    :note_source {:from #{:active} :to :active}}})

;; ── the builder ─────────────────────────────────────────────────────

(def ^:private machine
  "The keys of the machine, which are the core's."
  [:states :initial :terminal])

(def ^:private written
  "The keys the builder writes from the fields."
  [:schema :create-schema])

(def ^:private spent
  "The extension's keys the builder reads and no declaration carries."
  [:takes :policy :policy-order :engine])

(def ^:private law
  "The cracks law: what a policy waits on, who moves it at no policy
  door, and how long it may rest. The core states none, so a factory's
  expected ages are its own."
  [:waits :left-by :reached-by :expected-age])

(defn- refuse! [why data]
  (throw (ex-info (str "factorykit.repo-policy/build: " why) data)))

(defn- not-twice!
  "Refuses a second field of a name that is already taken."
  [whose taken own-names]
  (when-some [both (seq (filter (set taken) own-names))]
    (refuse! (str "the extension redefines " whose " field "
                  (pr-str (vec both)) ". A field has one definition.")
             {:redefined (vec both)})))

(defn- in-order
  "The fields in the order the extension said. A field the order does
  not name follows the named ones, in its own place among the rest."
  [fields order]
  (let [by-name (into {} (map (juxt first identity)) fields)
        said (set order)]
    (when-some [unknown (seq (remove by-name order))]
      (refuse! (str ":policy-order names " (pr-str (vec unknown))
                    ", and the policy has no such stated field.")
               {:unknown (vec unknown)}))
    (into (mapv by-name (distinct order)) (remove (comp said first)) fields)))

(defn- with-the-law
  "Each law key: the core's, with the extension's over it. Two maps
  merge and the extension's entries win; any other value of the
  extension's stands in place of the core's."
  [declaration core extension]
  (reduce (fn [declaration k]
            (let [ours (get core k)
                  theirs (get extension k)
                  v (cond
                      (nil? theirs) ours
                      (and (map? ours) (map? theirs)) (merge ours theirs)
                      :else theirs)]
              (cond-> declaration (some? v) (assoc k v))))
          declaration
          law))

(defn statement
  "The fields a person states, as schema entries in the form's order:
  the core's `:fields`, the offered fields the extension's `:takes`
  names, then the extension's own `:policy`. `:policy-order`, a vector
  of field names, says another order."
  [core extension]
  (let [{:keys [takes policy policy-order]} extension
        offered (into {} (map (juxt first identity)) (:offered core))]
    (when-some [unknown (seq (remove offered takes))]
      (refuse! (str ":takes names " (pr-str (vec unknown))
                    ", and the core offers no such field.")
               {:unknown (vec unknown)}))
    (not-twice! "the core's"
                (map first (concat (:fields core) (:offered core)))
                (map first policy))
    (in-order (-> (vec (:fields core))
                  (into (map offered) (distinct takes))
                  (into policy))
              policy-order)))

(defn build
  "The defresource map of a factory's repo_policy: `core` and the
  factory's own `extension`, which is a defresource map without the
  core's part.

  - `:states`, `:initial` and `:terminal` are the core's. An extension
    that names one is refused.
  - `:unique` is the core's, then the extension's.
  - `:policy`, `:takes` and `:policy-order` make the stated fields
    (`statement`). `:engine` is the fields the engine writes.
  - `:schema` is the stated fields, then the engine's. `:create-schema`
    is the stated fields. An extension that names either is refused.
  - The doors are the core's `:actions` and the extension's. A core
    door is the core's machine and the extension's door of that name
    over it. An extension that names a core door's machine is refused.
  - `restate` takes the stated fields as its `:input` and its
    `:edit` prefill. An extension that names either is refused.
  - The cracks law (`:waits`, `:left-by`, `:reached-by`,
    `:expected-age`) is the core's, with the extension's over it.
  - Every other key is the extension's."
  [core extension]
  (let [policy (statement core extension)
        engine (vec (:engine extension))
        whole (into [:map] policy)]
    (when-some [named (seq (filter #(contains? extension %)
                                   (concat machine written)))]
      (refuse! (str "the extension names " (pr-str (vec named))
                    ". The machine is the core's, and the builder writes the schemas from the fields.")
               {:named (vec named)}))
    (not-twice! "a stated" (map first policy) (map first engine))
    (doseq [[door shape] (:actions core)
            :let [kits (cond-> (set (keys shape))
                         (= :restate door) (into [:input :edit]))
                  named (filter kits (keys (get-in extension [:actions door])))]
            :when (seq named)]
      (refuse! (str "the extension's " (name door) " door names "
                    (pr-str (vec named))
                    ", and that part of a core door is the kit's to write.")
               {:door door :named (vec named)}))
    (-> (apply dissoc extension spent)
        (merge (select-keys core machine))
        (assoc :unique (vec (distinct (concat (:unique core) (:unique extension))))
               :schema (into whole engine)
               :create-schema whole
               :actions (cond-> (merge-with merge (:actions core) (:actions extension))
                          (contains? (:actions core) :restate)
                          (update :restate assoc
                                  :input whole
                                  :edit {:prefill (mapv first policy)})))
        (with-the-law core extension))))
