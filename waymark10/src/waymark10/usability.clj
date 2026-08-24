(ns waymark10.usability
  "The usability battery: five declaration-time policies that hold
  every kind to inputs a person can actually answer (waymark-0ee).

  The motivating complaint, recorded so the policies keep their
  reason: creating a saved view offered no hints for the right and
  left gestures or the card fields — a human was asked to RECALL
  tokens the engine knows exhaustively, into a blank text box. The
  fix is not a better form. The form, the MCP tool description and
  the generated docs are all PROJECTIONS of the declaration, so the
  cheapest place to make every client kinder at once is the
  declaration, and the cheapest way to make a declaration kinder is
  to say out loud, once, where it is unkind.

      1 effort honesty          no recall where selection is possible
      2 mandatory display prose labels, hints, and enum prose
      3 composition scaffolding a blank textarea has something in it
      4 gesture duties          a swipe is short, cheap and undoable
      5 card completeness       a row can name itself

  WHY THIS IS NOT waymark10.checks. The fail-fast gate in
  waymark10.checks runs inside `defresource`, at import, and prints
  every warning it finds on *err*. That is right for a battery of a
  dozen opinions and wrong for a battery of a hundred: a boot, a test
  run and a REPL load would each recite the whole fix-list, and a
  warning recited that often is a warning nobody reads. These
  policies run in the CHECK CLI instead (waymark10.check), where an
  author is asking the question on purpose. They are opinions, they
  print with a fix named, and they never change an exit code — the
  one refusal this bead names is a create guard that already exists
  (see `card-fields-guard-note` below).

  Every policy returns strings shaped like the checks battery's:
  \"[policy-name] …\", one sentence, naming the fix."
  (:require [clojure.string :as str]
            [waymark10.demand :as demand]
            [waymark10.guards :as g]
            [waymark10.machine :as machine]
            [waymark10.schema :as schema]))

(set! *warn-on-reflection* true)

;; ── shared introspection ────────────────────────────────────────────

(defn- leaf-guards [a] (mapcat g/iter-leaves (:guards a)))

(defn human-invokable?
  "Can a person meet this door at all? A leaf guard declaring `:hide`
  conceals the action from every envelope — the grant's :extend is
  minted by the approval effect and never rendered — so prose owed to
  a human is not owed there. Everything else is a form somebody may
  one day open."
  [action]
  (not-any? :hide (leaf-guards action)))

(defn- create-door
  "The create door as an action-shaped map, so one set of policies
  reads both surfaces. Its guards are :create-guards, its input the
  :create-schema (the :schema when none is spelled), and it has no
  :edit — which is exactly why composition scaffolding bites hardest
  here: a create form has no document to prefill from."
  [r]
  {:name :create
   :input (or (:create-schema r) (:schema r))
   :guards (:create-guards r)})

(defn- doors
  "Every input surface a caller fills: the create door first, then the
  actions that take input, in name order."
  [r]
  (into [(create-door r)]
        (filter :input)
        (machine/actions-seq r)))

(defn- where-of [door]
  (if (= :create (:name door))
    "the create door"
    (str "action " (name (:name door)))))

(defn- demanding-entries
  "The entries of one door's input that actually ASK the caller for
  something. A :const is pre-bound; a hidden or :secret field never
  renders; a place-scope key is bound by the part the caller is
  already looking at; a derived fact is the engine's own and no
  caller may write it. Everything else is a demand.

  :x-display {:raw true} is deliberately NOT an exemption, though it
  was tried: the long-text check reads :raw as \"a display shape IS
  declared — raw text\", and saved_view's :where wears it for exactly
  that reason while being the most hand-authored field in the
  framework. A marker that means two things cannot carry an
  exemption. Returns [[k entry json-schema-prop] …] in declaration
  order."
  [r door]
  (let [form (:input door)
        props (:properties (schema/json-schema form))
        place-key (get-in r [:part-scopes (:place door) :key])
        derived (set (keys (:derived r)))]
    (into []
          (keep (fn [[k {:keys [properties] :as entry}]]
                  (when-not (or (contains? properties :const)
                                (:secret properties)
                                (get-in properties [:x-display :hidden])
                                (contains? derived k)
                                (= k place-key))
                    [k entry (get props k)])))
          (schema/entry-map form))))

(defn- listing [ks] (str "[" (str/join " " (map (comp str keyword name) ks)) "]"))

;; ── 1 · effort honesty ──────────────────────────────────────────────

(defn effort-honesty
  "No recall where selection is possible.

  The check's proxy for \"legal values enumerable from the
  declaration\" is the closure rule's own escape hatch. A guard that
  judges an input field must tell the client what the field wants —
  an acceptance set, a relation, a schema constraint — or acknowledge
  the gap with `:open` (waymark10.checks/check-closure, which is a
  definition ERROR without one). Every `:open` in this codebase says
  the same thing in different words: the legal tokens are the
  registry's, one GET away, and enumerating them into the form would
  duplicate the registry. That acknowledgment is exactly the
  admission this policy re-raises as an opinion — the engine knows
  the answers exhaustively and the human is typing them from memory.

  So: a guard-judged field whose demand class is recall or
  composition, on a door whose guard escaped closure with `:open`,
  warns. A field that already carries an enum, a const or an x-ref
  (demand class selection) is silent, which is the whole point — the
  fix clears the warning."
  [r]
  (let [seen (volatile! #{})]
    (into []
          (mapcat
           (fn [door]
             (when (human-invokable? door)
               (let [props (:properties (schema/json-schema (:input door)))
                     entries (schema/entry-map (:input door))]
                 (for [lg (leaf-guards door)
                       :when (and (:open lg) (seq (:judges lg)))
                       f (:judges lg)
                       :when (contains? entries f)
                       :let [cls (demand/field-class f (get props f) #{})
                             token [(:name door) f]]
                       :when (and (#{"recall" "composition"} cls)
                                  (not (contains? @seen token)))]
                   (do (vswap! seen conj token)
                       (str "[effort-honesty] " (where-of door) " field " f
                            " is free text, but guard " (name (:name lg))
                            " judges it against tokens the engine enumerates"
                            " exhaustively (its :open acknowledges the gap) —"
                            " every client renders a blank box where a picker"
                            " belongs; give " f " an :enum, a :kind ref, or a"
                            " vocabulary the schema can publish")))))))
          (doors r))))

;; ── 2 · mandatory display prose ─────────────────────────────────────

(def ^:private typed-demands #{"recall" "composition"})

(defn- blank-box?
  "Would this property render as an empty box with nothing in it? A
  boolean, a number, an instant or a date arrives at a real control —
  a checkbox, a spinner, a calendar — and a label is enough to answer
  it. A bare string, array or object arrives as a rectangle, and a
  rectangle is where the hint sentence earns its keep."
  [prop]
  ;; an optional entry publishes as anyOf/oneOf with a null branch —
  ;; the widget question is about the branch that carries a value
  (let [leaf (or (first (remove #(= "null" (:type %))
                                (concat (:anyOf prop) (:oneOf prop))))
                 prop)
        t (:type leaf)]
    (and (not (contains? leaf :format))
         (not (contains? #{"boolean" "integer" "number"} t)))))

(defn display-prose
  "Labels, hint sentences, and prose for enum values.

  At most TWO sentences per door — the prose one and the enum one —
  because a fix-list reads as a to-do per FORM and a per-field
  spelling of the same two facts is a wall nobody works through:

  - fields with no `:x-display :label`. The generic client titles the
    field name when nothing else is offered (`prep_minutes` → \"Prep
    Minutes\"), which is a courtesy and not a label; and an MCP tool
    description shows the agent the bare token either way.
  - fields whose demand is recall or composition and carry no
    `:x-display :help` sentence. A selection is self-explaining once
    labeled; a free box is a memory test until somebody says what
    belongs in it. `:help` is the key the generic form already
    renders beneath the field.
  - enum fields whose values carry no prose. `:x-display {:choices
    {\"local\" \"…\"}}` is the spelling; without it a select offers
    wire tokens and the human guesses.

  The create door counts as a human-invokable door, deliberately —
  the complaint that filed this bead was about a CREATE form."
  [r]
  (into []
        (mapcat
         (fn [door]
           (when (human-invokable? door)
             (let [es (demanding-entries r door)
                   unlabelled (for [[k {:keys [properties]} _] es
                                    :when (nil? (get-in properties
                                                        [:x-display :label]))]
                                k)
                   unhinted (for [[k {:keys [properties]} prop] es
                                  :when (and (nil? (get-in properties
                                                           [:x-display :help]))
                                             (typed-demands
                                              (demand/field-class k prop #{}))
                                             (blank-box? prop))]
                              k)
                   tokenised (for [[k {:keys [properties]} prop] es
                                   :when (and (seq (:enum prop))
                                              (empty? (get-in properties
                                                              [:x-display
                                                               :choices])))]
                               k)]
               (cond-> []
                 (or (seq unlabelled) (seq unhinted))
                 (conj (str "[display-prose] " (where-of door) " renders"
                            " without prose"
                            (when (seq unlabelled)
                              (str " — no :x-display :label on "
                                   (listing unlabelled)))
                            (when (seq unhinted)
                              (str (if (seq unlabelled) ", and" " —")
                                   " no :help sentence on the typed demand(s) "
                                   (listing unhinted)))
                            ": a field with neither is a bare wire token in"
                            " the form and an unexplained argument in the MCP"
                            " tool; give each an :x-display {:label … :help …}"))
                 (seq tokenised)
                 (conj (str "[display-prose] " (where-of door) " offers the"
                            " enum(s) " (listing tokenised) " as bare wire"
                            " tokens — declare :x-display {:choices {\"token\""
                            " \"the sentence a person reads\"}} so every"
                            " client shows prose, not spelling")))))))
        (doors r)))

;; ── 3 · composition scaffolding ─────────────────────────────────────

(defn- scaffolded?
  "Does this door hand the caller something to start from? Three
  spellings, and the bead names all three: a declared `:default`
  (the form prefills it), an `:examples` value (standard JSON Schema,
  read by agents and offered as the field's placeholder), or a
  template — which on an ACTION means `:edit` prefilling the very
  field that demands composition, the document's current prose as the
  starting text. The create door has no document, so only the first
  two can save it, which is why this policy bites there."
  [door composing]
  (let [entries (schema/entry-map (:input door))
        prefilled (set (get-in door [:edit :prefill]))]
    (or (some (fn [k] (let [p (:properties (get entries k))]
                        (or (contains? p :default) (contains? p :examples))))
              composing)
        (boolean (seq (filter prefilled composing))))))

(defn composition-scaffolding
  "An action or create door whose demand class is composition must
  hand the caller something to start from. A composition input with
  nothing is a blank textarea in every client — the highest-effort
  demand the vocabulary has, made from a standing start."
  [r]
  (into []
        (keep
         (fn [door]
           (when (human-invokable? door)
             (let [es (demanding-entries r door)
                   composing (for [[k _ prop] es
                                   :when (= "composition"
                                            (demand/field-class k prop #{}))]
                               k)]
               (when (and (seq composing) (not (scaffolded? door composing)))
                 (str "[composition-scaffolding] " (where-of door)
                      " demands composition in " (listing composing)
                      " with nothing to start from — a blank textarea in"
                      " every client; declare a :default, an :examples"
                      " value, or an :edit template that prefills it"))))))
        (doors r)))

;; ── 4 · gesture duties ──────────────────────────────────────────────

(def ^:private gesture-label-budget
  "A deck chip sits under a thumb, beside its twin. Two words."
  16)

(defn gesture-duties
  "An action bound to a deck gesture owes three things the same swipe
  cannot ask twice: a SHORT label (the chip is a thumb wide), a
  demand of at most selection (a swipe collects a decision, never a
  form), and a way back — reversible, idempotent, or confirm-gated.

  Reversibility is already law here, not opinion:
  waymark10.checks/deck-gesture-problems refuses a declared view
  whose gesture binds a non-reversible action. This policy states the
  duty in full anyway, because the same three questions are asked of
  a saved_view row at WRITE time by the same shared battery, and a
  duty split across two documents is a duty nobody can read."
  [r]
  (into []
        (comp
         (filter #(= :deck (:kind %)))
         (mapcat
          (fn [v]
            (mapcat
             (fn [side]
               (let [aname (get v side)
                     a (get (:actions r) aname)]
                 (when a
                   (let [label (get-in a [:display :label])
                         effort (if (:input a)
                                  (demand/effort a
                                                 (schema/json-schema (:input a))
                                                 (get-in r [:part-scopes
                                                            (:place a) :key]))
                                  "assent")
                         safety (:safety a)
                         lead (str "[gesture-duties] view " (:name v)
                                   " binds " side " to " (name aname) ", ")]
                     (cond-> []
                       (or (str/blank? (str label))
                           (< gesture-label-budget (count (str label))))
                       (conj (str lead "whose chip reads "
                                  (pr-str (str label)) " — a gesture sits"
                                  " under a thumb beside its twin; give it a"
                                  " :display :label of a word or two"))

                       (demand/heavier? effort "selection")
                       (conj (str lead "whose demand is " effort
                                  " — a swipe collects a decision, never a"
                                  " form; bind a gesture to an action of"
                                  " effort selection or less, and leave the"
                                  " typing to the row's own screen"))

                       (not (or (:reversible safety) (:idempotent safety)
                                (:confirm safety)))
                       (conj (str lead "which is neither reversible nor"
                                  " idempotent nor confirm-gated — a swipe is"
                                  " a snap judgment and owes a way back;"
                                  " declare :undo, :idempotent, or :confirm")))))))
             [:right :left]))))
        (:views r)))

;; ── 5 · card completeness ───────────────────────────────────────────

(def card-fields-guard-note
  "The bead's one REFUSAL, and the reconciliation it wanted recorded:
  a saved_view's chosen :card fields validated against the target
  kind's schema, refusing by name. That guard already exists and
  already refuses — waymark10.saved-view/composes-declared-primitives
  runs waymark10.checks/view-problems at create AND revise, whose
  :card branch answers \":card names [:nope], not data field(s) of
  the schema\" and, for a prose field with no teaser flag, names that
  too. Nothing was added here; a test now pins it
  (waymark10.usability-test), because an unwitnessed law is a law
  waiting to be deleted."
  :waymark10.saved-view/composes-declared-primitives)

(defn card-completeness
  "A nav-visible kind must be able to name one of its rows.

  :summary is already mandatory — waymark10.checks/check-summary-template
  refuses a declaration without one — so what is left is the LABEL:
  the short name a ref picker, a card and a link badge show. The
  engine defaults it to \"{data.name}\" when the schema declares a
  :name field (waymark10.server.invoke/label-of); a nav-visible kind
  with neither a :label-template nor a :name field labels its rows
  with a raw id everywhere it is referenced."
  [r]
  (if (and (contains? #{:primary :secondary} (:nav r))
           (nil? (:label-template r))
           (not (contains? (set (schema/entry-keys (:schema r))) :name)))
    [(str "[card-completeness] a :" (name (:nav r)) "-nav kind with no"
          " :label-template and no :name field to default from — every ref"
          " picker, card and link badge falls back to the raw id; declare"
          " :label-template")]
    []))

;; ── the battery ─────────────────────────────────────────────────────

(def policies
  "The five, in the bead's order — a vector so the report reads the
  same way twice and a sixth policy arrives visibly."
  [#'effort-honesty #'display-prose #'composition-scaffolding
   #'gesture-duties #'card-completeness])

(defn warnings
  "Every usability opinion this battery holds about one normalized
  declaration, in policy order. Opinions only: nothing here throws,
  and the check CLI's exit code does not read them."
  [r]
  (into [] (mapcat (fn [p] (p r))) policies))
