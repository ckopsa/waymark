(ns factorykit.conform
  "Org conformance (ticket 86f96963, M1c): one suite every org passes,
  generated or hand-written, judging its kinds and seats the way the
  engine would, as forge-conformance judges every forge.
  `(conform org-dir)` answers findings, each
  {:key :severity :class :subject :sentence :remedy}, in a stable order
  and with keys that are the same on every run. An org passes when it
  answers none.

      clojure -M:conform ../orgs/example     ; from factorykit/
      clojure -M:conform ../orgs             ; every org under it

  The classes, in the order they are judged:

  org_name_not_snake_case
            the org's directory name is snake_case, or live-orgs never
            boots it (ticket d4750bf9)
  manifest  org.edn is there, a map, naming the org and its :live
  load      the org's module loads, and `resources` answers its kinds
  seed      the seed reads, names the org, and every seat template it
            names fills with no hole left — its `:rehearsal :seats`
            too, joined as a clone joins them (ticket 290faa7a), each
            of those fitting the seat create door's schema (ticket
            71e711a2)
  resource  every kind passed the defresource gate
  registry  the kinds assemble beside the ones the engine enrols
  crack     cracks/audit over the org's kinds and its seed's seats,
            the rehearsal's among them, pure: declarations and seat
            maps, no store
  sentence  every guard explains itself, and every confirm door says
            its consequence
  boot      a memory engine answers discover, schema, query, get and
            a dry run of each create, for every kind
  scope     every seat's scope, and every cast member's :scope (the
            rehearsal's seats and cast as well), names
            kinds and actions the booted engine serves; a seat whose
            seed entry names an :owner
            names a cast member, and sits inside that member's :scope,
            its ceiling (a member with no :scope holds the whole org)
  decision  the person's business questions (ticket e1ed02c7), pure
            over declarations and the seed: a final door a model seat
            holds and no person does (unheld_final), a state with no
            expected age (no_wait_limit), a money input no guard reads
            (money_unbounded), a :decided sentence that says a door a
            model seat holds waits for a person while no :hold guard
            holds it (decided_says_held), an optional field no door
            asks for that a door's guard reads off the row
            (door_time_input). Each sentence is a question
            to put to the person; each clears in code — a :hold guard,
            an :expected-age, or the person's answer as :decided
  no-outside-world
            the org's rehearsal has an outside world (ticket
            a4149376): a :rehearsal cast member whose :scope creates
            one of the org's kinds, and a world seat under
            :rehearsal :seats; org.edn's :world :none waives it
  rehearsal the week's outside events and effects play on the clone
            (ticket 524685a0): each integration the week posts an
            :event from has a :rehearsal :steps step reading
            [:param :signing_secret_<integration>], and each effect
            door a week call walks has a :rehearsal :effects fake for
            its tool

  Every finding is an error: the CLI exits 1 on any."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [waymark10.check :as check]
            [waymark10.dev :as dev]
            [waymark10.schema :as schema]
            [waymark10.server.cracks :as cracks]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.org :as org-boot]
            [waymark10.server.seats :as seats]
            [waymark10.server.seed :as seed]
            [waymark10.wire :as wire])
  (:import (clojure.lang Compiler DynamicClassLoader)
           (java.io File))
  (:gen-class))

(set! *warn-on-reflection* true)

(defn- text
  "A keyword's whole spelling, namespace and all; anything else as str."
  [x]
  (if (keyword? x) (subs (str x) 1) (str x)))

(defn- finding [class parts subject sentence remedy]
  {:key (str/join "/" (cons (name class) (map text parts)))
   :severity :error
   :class class
   :subject subject
   :sentence sentence
   :remedy remedy})

(defn- root-message [^Throwable e]
  (loop [e e]
    (if-some [c (ex-cause e)] (recur c) (or (ex-message e) (str e)))))

(defn- sentence? [s]
  (and (string? s) (not (str/blank? s))))

;; ── load ────────────────────────────────────────────────────────────

(defn with-org-classpath
  "Call f with the org's src and resources on the classpath, so an org
  directory need not be a dep of whoever runs the suite. The first-week
  scene loads an org's kinds the same way."
  [^File dir f]
  (let [t (Thread/currentThread)
        prev (.getContextClassLoader t)
        cl (DynamicClassLoader. prev)]
    (doseq [p ["src" "resources"]]
      (.addURL cl (.toURL (.toURI (io/file dir p)))))
    (.setContextClassLoader t cl)
    (try
      (with-bindings {Compiler/LOADER cl} (f))
      (finally (.setContextClassLoader t prev)))))

(defn- load-org [dir org]
  (let [module (str org ".org")]
    (try
      (let [rs (with-org-classpath
                 dir #((requiring-resolve (symbol module "resources"))))]
        (if (and (sequential? rs) (seq rs))
          {:resources (vec rs)}
          {:findings [(finding :load [org] module
                               (str module "/resources answered no kinds.")
                               "Declare the org's kinds in resources, stamped by in-domain.")]}))
      (catch Throwable e
        {:findings [(finding :load [org] module
                             (str module " did not load: " (root-message e))
                             (str "Make src/" (munge org) "/org.clj load and declare resources, as docs/orgkit.md says."))]}))))

;; ── seed ────────────────────────────────────────────────────────────

(defn- fill-template
  "The template's seat filled as the seed loader fills it, and its
  `:wakes` beside it."
  [{:keys [template params]}]
  (when-some [res (io/resource (str "seats/" template ".edn"))]
    (let [t (edn/read-string (slurp res))
          seat (seed/filled (:seat t) (or params {}) (:template t))
          wakes (some-> (:wakes t) (seed/filled (or params {}) (:template t)))]
      {:seat seat
       :wakes wakes
       :holes (vec (distinct (re-seq #"\{\{[^}]*\}\}" (pr-str [seat wakes]))))})))

(defn- seed-seats
  "The seed's seats as whole seat maps, and what kept any from filling.
  A seat's `:wakes`, its entry's or else its template's, is read as the
  wake_on its door subscriptions carry, as the audit reads a seat."
  [seed]
  (reduce
   (fn [acc [i {:keys [seat template owner wakes] :as entry}]]
     (cond
       seat (update acc :seats conj (cond-> seat
                                       owner (assoc :owner owner)
                                       (seq wakes) (assoc :wake_on wakes)))
       template
       (if-some [{:keys [seat holes] filled :wakes} (fill-template entry)]
         (cond-> (update acc :seats conj (cond-> seat
                                             owner (assoc :owner owner)
                                             (seq (or wakes filled)) (assoc :wake_on (or wakes filled))))
           (seq holes)
           (update :findings conj
                   (finding :seed ["holes" (:name seat)] (str "seat " (:name seat))
                            (str "The " template " template's seat keeps holes its params do not fill: "
                                 (str/join ", " holes) ".")
                            "Give the seed's :params a value for every hole the template names.")))
         (update acc :findings conj
                 (finding :seed ["template" template] (str "seats[" i "]")
                          (str "The seed names the seat template " template ", and factorykit ships none by that name.")
                          "Name a template under factorykit/resources/seats/, or write the whole seat under :seat.")))
       :else
       (update acc :findings conj
               (finding :seed ["seat" i] (str "seats[" i "]")
                        "A seed seat names neither a template nor a whole seat."
                        "Write {:template … :params …} or {:seat {…}}."))))
   {:seats [] :findings []}
   (map-indexed vector (:seats seed))))

(defn- unresolved-ref?
  "A seed's `[:ref …]`, whole or as the text a template fills in: only
  boot resolves it, so the schema cannot judge it here."
  [v]
  (or (and (vector? v) (= :ref (first v)))
      (and (string? v) (str/starts-with? v "[:ref "))))

(defn- seat-schema-findings
  "Each `:rehearsal :seats` entry, filled, judged against the seat
  create door's schema as boot's create judges it — decoded, defaults
  filled — with no database (ticket 71e711a2): an overlong charter or
  instructions is a finding naming the field and its length, not a
  clone that dies at boot."
  [seed]
  (for [seat (:seats (seed-seats {:seats (get-in seed [:rehearsal :seats])}))
        :let [form (:create-schema seats/seat)
              errs (->> (dissoc seat :owner)
                        (schema/decode form)
                        (schema/apply-defaults form)
                        (schema/errors form))]
        :when (map? errs)
        [field msgs] (sort-by (comp text key) errs)
        :when (not (unresolved-ref? (get seat field)))
        :let [said (if (sequential? msgs)
                     (str/join "; " (map #(if (string? %) % (pr-str %)) msgs))
                     (pr-str msgs))]]
    (finding :seed ["schema" (:name seat) field] (str "seat " (:name seat))
             (str "The seat " (:name seat) "'s " (text field)
                  " does not fit the seat create door: " said ".")
             (str "Mend the seed's " (text field) " for this seat, or the template's params that fill it."))))

;; ── resource, registry ──────────────────────────────────────────────

(defn- resource-findings [rs]
  (for [r rs
        :when (not (check/declaration? r))
        :let [k (text (:kind r))]]
    (finding :resource [k] k
             (str "The kind " k " did not pass the defresource gate.")
             "Declare it with defresource, so the gate judges it before the engine does.")))

(defn- registry-findings [org rs]
  (try
    (engine/full-registry rs)
    nil
    (catch Throwable e
      [(finding :registry [org] org
                (str "The kinds do not assemble beside the engine's own: " (root-message e))
                "Rename a kind the engine already enrols, or mend what the registry names.")])))

;; ── crack ───────────────────────────────────────────────────────────

(defn- seat-row [seat]
  {:id (:name seat) :state :active :data seat})

(defn- fired
  "The seed's seats as the audit reads them: each one's wake_on written
  as subscriptions on its fire door, as its birth writes them, and
  handed through `cracks/with-fires`, the only wakes the audit reads."
  [rs seat-maps]
  (let [rows (mapv seat-row seat-maps)]
    (cracks/with-fires (into {} (map (juxt :kind identity)) rs)
                       rows
                       (into []
                             (mapcat (fn [row]
                                       (map #(hash-map :data (seats/wake-subscription row %))
                                            (get-in row [:data :wake_on]))))
                             rows))))

(def ^:private crack-key
  [:kind :state :door :remedy :on :awaits :outcome :field :value])

(defn- crack-findings [rs seats]
  (for [c (cracks/audit rs (fired rs seats))
        :let [subject (str/join "/" (keep #(some-> (get c %) text) [:kind :state]))]]
    (finding :crack (cons (:crack c) (keep #(some-> (get c %) text) crack-key))
             subject
             (str "The cracks audit finds " (:crack c) " at " subject
                  (when-some [d (:door c)] (str ", door " d))
                  (when-some [on (:on c)] (str ", waiting on " on))
                  ".")
             (or (:what_would_close_it c)
                 "Give an active seat a door out of the state and a door subscription that brings it there."))))

;; ── sentence ────────────────────────────────────────────────────────

(defn- sentence-findings [rs]
  (for [r (sort-by (comp text :kind) rs)
        :let [k (text (:kind r))]
        [aname a] (sort-by (comp text key) (:actions r))
        :let [door (str k "." (text aname))]
        f (concat
           (for [g (:guards a)
                 :when (and (map? g) (not (sentence? (:explain g))))]
             (finding :sentence [k aname (:name g)] door
                      (str "The guard " (text (:name g)) " on " door
                           " carries no sentence, so its refusal would say nothing.")
                      "Give the guard its sentence: (refuse \"…\") in defguard, or :explain."))
           (let [c (get-in a [:safety :consequence])]
             (when (and (true? (get-in a [:safety :confirm]))
                        (not (or (sentence? c) (and (map? c) (seq c)))))
               [(finding :sentence [k aname "consequence"] door
                         (str "The confirm door " door " says no consequence to acknowledge.")
                         "Write the door's :confirm sentence: what happens, that cannot be undone.")])))]
    f))

;; ── boot ────────────────────────────────────────────────────────────

(def ^:private walker
  {"x-waymark-principal" "conformance" "x-waymark-actor-type" "system"})

(defn- call [h method uri query body]
  (h (cond-> {:request-method method :uri uri :headers walker}
       query (assoc :query-string query)
       body (assoc :body (wire/write-json body)))))

(defn- ok? [resp] (<= 200 (long (or (:status resp) 0)) 299))
(defn- broke? [resp] (<= 500 (long (or (:status resp) 500))))

(defn- boot-finding [door k resp]
  (finding :boot [door k] k
           (str "A booted memory engine does not answer " door " for " k
                ": status " (:status resp) ".")
           "Boot the org with waymark10.dev/scratch! and read what the engine says."))

(defn- first-creates
  "Each kind's first create body among the seed's steps."
  [seed]
  (reduce (fn [m {:keys [kind create]}]
            (let [kind (keyword (text kind))]
              (cond-> m (and (map? create) (not (contains? m kind))) (assoc kind create))))
          {} (:steps seed)))

(defn- boot
  "Boot the kinds over memory and walk the doors every kind must
  answer. {:engine e :findings […]}; no engine when it did not boot."
  [org rs seed]
  (try
    (let [eng (dev/scratch! rs)
          h (engine/handler eng)
          served (inv/resources eng)
          creates (first-creates seed)
          known (call h :get "/api/.well-known/waymark" nil nil)
          listed (when (ok? known) (:resources (wire/read-json (:body known))))]
      {:engine eng
       :findings
       (into (if (ok? known) [] [(boot-finding "discover" org known)])
             (for [r (sort-by (comp text :kind) rs)
                   :let [kind (:kind r)
                         k (text kind)
                         base (str "/api/" (or (:plural (get served kind)) (:plural r)))
                         body (get creates kind)]
                   f (concat
                      (when (and listed (not (contains? listed kind)))
                        [(boot-finding "discover" k known)])
                      (let [resp (call h :get (str "/api/schemas/" k) nil nil)]
                        (when-not (ok? resp) [(boot-finding "schema" k resp)]))
                      (let [resp (call h :get base nil nil)]
                        (when-not (ok? resp) [(boot-finding "query" k resp)]))
                      (let [resp (call h :post base "dry_run=1" (or body {}))]
                        (when (broke? resp) [(boot-finding "dry_run" k resp)]))
                      (when body
                        (let [made (call h :post base nil body)
                              id (when (ok? made) (:id (wire/read-json (:body made))))
                              resp (when id (call h :get (str base "/" id) nil nil))]
                          (cond
                            (broke? made) [(boot-finding "create" k made)]
                            (and resp (not (ok? resp))) [(boot-finding "get" k resp)]))))]
               f))})
    (catch Throwable e
      {:findings [(finding :boot ["engine" org] org
                           (str "A memory engine does not boot the org: " (root-message e))
                           "Boot the org with waymark10.dev/scratch! and mend what it throws.")]})))

;; ── scope ───────────────────────────────────────────────────────────

(defn- holds? [entries kind action]
  (some #(and (= kind (text (:kind %)))
              (some #{action "*"} (map text (:actions %))))
        entries))

(defn- cast-scope-findings
  "A cast member's :scope is its seats' ceiling: a kind or action it
  misnames passes no seat, so it is judged as the seats' scopes are.
  \"*\" is the ceiling's every-action and is never unknown."
  [vocab seed]
  (for [[m {:keys [scope]}] (sort-by (comp text key) (:cast seed))
        :let [m (text m)]
        {:keys [kind actions]} scope
        :let [k (text kind)
              known (get vocab k)]
        f (if (nil? known)
            [(finding :scope ["unknown-kind" "cast" m k] (str "cast member " m)
                      (str "The cast member " m "'s scope names the kind " k ", which the engine does not serve.")
                      "Name a kind the org declares or the engine enrols.")]
            (for [a (map text actions)
                  :when (and (not= "*" a) (not (contains? known a)))]
              (finding :scope ["unknown-action" "cast" m k a] (str "cast member " m)
                       (str "The cast member " m "'s scope names " k "." a ", which " k " does not declare.")
                       (str "Name one of " k "'s actions: " (str/join ", " known) "."))))]
    f))

(defn- scope-findings [eng seats seed]
  (let [vocab (into {} (map (fn [[k rd]] [(text k) (inv/action-names rd)]))
                    (inv/resources eng))]
    (concat
     (cast-scope-findings vocab seed)
     (for [{seat :name :keys [scope owner]} seats
          :let [member (when owner (get (:cast seed) (keyword (text owner))))
                ceiling (:scope member)]
          f (concat
             (when (and owner (nil? member))
               [(finding :scope ["owner" seat] (str "seat " seat)
                         (str "The seat " seat " names the owner " (text owner) ", who is not in the cast.")
                         "Name a cast member as the seat's :owner.")])
             (for [{:keys [kind actions]} scope
                   :let [k (text kind)
                         known (get vocab k)]
                   f (if (nil? known)
                       [(finding :scope ["unknown-kind" seat k] (str "seat " seat)
                                 (str "The seat " seat "'s scope names the kind " k ", which the engine does not serve.")
                                 "Name a kind the org declares or the engine enrols.")]
                       (for [a (map text actions)
                             f (concat
                                (when-not (contains? known a)
                                  [(finding :scope ["unknown-action" seat k a] (str "seat " seat)
                                            (str "The seat " seat "'s scope names " k "." a ", which " k " does not declare.")
                                            (str "Name one of " k "'s actions: " (str/join ", " known) "."))])
                                (when (and ceiling (not (holds? ceiling k a)))
                                  [(finding :scope ["outside-owner" seat k a] (str "seat " seat)
                                            (str "The seat " seat " holds " k "." a ", which its owner " (text owner) " does not.")
                                            "Narrow the seat's scope to its owner's, or widen the owner's first.")]))]
                         f))]
               f))]
      f))))

;; ── decision ────────────────────────────────────────────────────────

(defn- words [x] (str/replace (text x) "_" " "))

(defn- a-an [s] (str (if (re-find #"^[aeiou]" s) "an " "a ") s))

(defn- either
  "a · a or b · a, b or c, as the law page joins them."
  [xs]
  (let [xs (vec xs)]
    (case (count xs)
      0 ""
      1 (first xs)
      (str (str/join ", " (pop xs)) " or " (peek xs)))))

(defn- guard-of [g] (if (var? g) @g g))

(defn- held? [a] (some #(:hold (guard-of %)) (:guards a)))

(defn- decided? [a] (sentence? (:decided a)))

(def ^:private says-held
  "Words in a :decided sentence that say the door waits for a person."
  #"(?i)\bonly on\b|['’]s tap\b|\bwaits? for\b|\bnever on its own\b")

(defn- narrative?
  "Whether the door says its :decided sentence is narrative and the AI
  may act: `:decided-held {:none \"why\"}`."
  [a]
  (sentence? (get-in a [:decided-held :none])))

(defn- model-holds?
  "Whether a seed seat that is no person's holds kind.action."
  [seats k a]
  (some #(and (not (:person %)) (holds? (:scope %) k a)) seats))

(defn- owner-tap
  "Whose tap a door would wait for: the cast's :owner, else its first
  approver, by display name; a person's when the cast names neither."
  [seed]
  (let [m (or (get (:cast seed) :owner)
              (some (fn [[_ m]] (when (some #{"approver"} (map text (:roles m))) m))
                    (sort-by (comp text key) (:cast seed))))]
    (if-some [d (:display m)] (str d "'s") "a person's")))

(defn- unheld-final-findings [rs seats who]
  (for [r rs
        :let [k (text (:kind r))
              as (sort (for [[aname a] (:actions r)
                             :let [an (text aname)]
                             :when (and (false? (get-in a [:safety :reversible]))
                                        (model-holds? seats k an)
                                        (not (held? a))
                                        (not (decided? a)))]
                         an))]
        :when (seq as)]
    (finding :decision ["unheld_final" k] k
             (str "Should the AI " (either (map words as)) " " (a-an (words k))
                  " on its own, or wait for " who " tap?")
             (str "Hold " (str/join ", " (map #(str k "." %) as))
                  " for a person with a :hold guard, or write the person's answer on each as :decided \"…\"."))))

(defn- decided-says-held-findings
  "A :decided sentence that says a door waits for a person, on a door a
  model seat holds and no :hold guard holds (ticket 7315ae72): the law
  page would say the person taps it while the AI takes it alone."
  [rs seats who]
  (for [r rs
        :let [k (text (:kind r))]
        [aname a] (sort-by (comp text key) (:actions r))
        :let [an (text aname)]
        :when (and (decided? a)
                   (re-find says-held (:decided a))
                   (model-holds? seats k an)
                   (not (held? a))
                   (not (narrative? a)))]
    (finding :decision ["decided_says_held" k an] (str k "." an)
             (str "You said " (words an) " on " (a-an (words k)) " waits for a person, but nothing holds it:"
                  " should the AI do it on its own, or wait for " who " tap?")
             (str "Add a :hold guard for that person, or reword the decision if the AI may act alone"
                  " (:decided-held {:none \"why\"} keeps the sentence as narrative)."))))

(defn- state-decided? [r s]
  (when (map? (:decided r))
    (some (fn [[st d]] (and (= s (text st)) (sentence? d))) (:decided r))))

(defn- no-wait-limit-findings [rs]
  (for [r rs
        :let [k (text (:kind r))
              ends (set (map text (:terminal r)))
              ages (set (map (comp text key) (:expected-age r)))]
        s (map text (:states r))
        :when (not (or (ends s) (ages s) (state-decided? r s)))]
    (finding :decision ["no_wait_limit" k s] (str k "/" s)
             (str "How long may " (a-an (words k)) " stay " (words s) " before someone is told, and who?")
             (str "Declare " k "'s :expected-age for " s ", or write the person's answer as :decided {:" s " \"…\"}."))))

(defn- money-field? [f]
  (or (contains? #{"amount" "fee" "price"} f)
      (str/ends-with? f "_cents")
      (str/ends-with? f "_usd")))

(defn- input-fields
  "The field names a malli :map input declares, nested maps included."
  [schema]
  (->> (tree-seq coll? seq schema)
       (filter #(and (vector? %) (= :map (first %))))
       (mapcat (fn [m] (keep #(when (and (vector? %) (keyword? (first %))) (first %)) (rest m))))
       (map text)
       distinct))

(defn- reads?
  "Whether a guard's law names the field f anywhere."
  [g f]
  (let [g (guard-of g)]
    (when (map? g)
      (some #(and (or (keyword? %) (symbol? %) (string? %)) (= f (name %)))
            (tree-seq coll? seq (dissoc g :name :explain :remedies))))))

(defn- money-unbounded-findings [rs seats]
  (for [r rs
        :let [k (text (:kind r))]
        [aname a] (sort-by (comp text key) (:actions r))
        :let [an (text aname)]
        :when (and (model-holds? seats k an)
                   (not (held? a))
                   (not (decided? a))
                   (some (fn [f] (not-any? #(reads? % f) (:guards a)))
                         (filter money-field? (input-fields (:input a)))))]
    (finding :decision ["money_unbounded" k an] (str k "." an)
             (str "Above what amount should " (words an) " on " (a-an (words k)) " wait for you?")
             (str "Give " k "." an " a guard that reads its amount and holds the door for a person above the line, or write the person's answer as :decided \"…\"."))))

(defn- optional-fields
  "The names of a :map schema's optional fields."
  [schema]
  (when (and (vector? schema) (= :map (first schema)))
    (keep (fn [e] (when (and (vector? e) (keyword? (first e))
                             (map? (second e)) (:optional (second e)))
                    (name (first e))))
          (rest schema))))

(defn- row-reads
  "The fields a guard's law reads off the row, as (present? :k) or
  (data :k)."
  [g]
  (let [g (guard-of g)]
    (when (map? g)
      (->> (tree-seq coll? seq (dissoc g :name :explain :remedies))
           (keep #(when (and (seq? %) (symbol? (first %))
                             (#{"present?" "data"} (name (first %)))
                             (keyword? (second %)))
                    (name (second %))))
           distinct))))

(defn- door-time-input-findings
  "An optional field no door's :input names, so only the generated
  editor writes it, that a door's guard reads off the row: the value
  null on every create and filled by an edit right after, which
  docs/orgkit.md § Door-time inputs calls the smell. The person's
  answer on the door, as :decided, clears it."
  [rs]
  (for [r rs
        :let [k (text (:kind r))
              optional (set (optional-fields (:schema r)))
              asked (set (mapcat (comp input-fields :input val) (:actions r)))]
        [aname a] (sort-by (comp text key) (:actions r))
        :let [an (text aname)]
        :when (not (decided? a))
        f (distinct (mapcat row-reads (:guards a)))
        :when (and (optional f) (not (asked f)))]
    (finding :decision ["door_time_input" k an f] (str k "." an)
             (str "Is " (words f) " something you only know when " (words an) " happens?")
             (str "If so, ask for it on " k "." an ": name it in the door's :input, write it with (r/field-writer [:" f "]) and guard on (input :" f "), as docs/orgkit.md § Door-time inputs shows. If not, make it a required create field, or write the person's answer on the door as :decided \"…\"."))))

(defn- decision-findings
  "The person's business questions (ticket e1ed02c7), pure over the
  declarations and the seed's seats: who may take a final step, how
  long a row may wait, how much money moves without a tap, which
  values only a door knows — in that order, each in the org's kind
  order, after any :decided sentence the law contradicts."
  [rs seats seed]
  (concat (decided-says-held-findings rs seats (owner-tap seed))
          (unheld-final-findings rs seats (owner-tap seed))
          (no-wait-limit-findings rs)
          (money-unbounded-findings rs seats)
          (door-time-input-findings rs)))

;; ── world ───────────────────────────────────────────────────────────

(defn- world-findings
  "The org's outside world, which only its clone loads (ticket
  a4149376): a `:rehearsal` cast member whose :scope creates one of the
  org's kinds, and a world seat under `:rehearsal :seats` — the world
  template, or a whole seat that acts as cast. Without both, nobody
  files, sends or pays anything in the filmed week. `:world :none` in
  the manifest waives it, for an org that only works inside."
  [org rs seed manifest]
  (let [kinds (set (map (comp text :kind) rs))
        {:keys [cast seats]} (:rehearsal seed)
        outside? (some (fn [[_ m]]
                         (some #(and (kinds (text (:kind %)))
                                     (some #{"create" "*"} (map text (:actions %))))
                               (:scope m)))
                       cast)
        world? (some #(or (= "world" (text (:template %)))
                          (true? (get-in % [:seat :acts_as_cast])))
                     seats)]
    (when-not (or (= :none (when (map? manifest) (:world manifest)))
                  (and outside? world?))
      [(finding :no-outside-world [org] org
                "Nobody outside the business starts work in the first week, so the film has nothing to show."
                "Ask: \"Who outside your business starts work for you, and what do they send?\" Write each as a :rehearsal :cast member whose :scope creates what they send, and the world seat, as orgs/example does; or :world :none in org.edn.")])))

;; ── the rehearsal's integrations ────────────────────────────────────

(defn- week-of
  "The org's week.edn as data; nil when it has none or it does not read,
  which the week player reports."
  [^File dir org]
  (let [f (io/file dir "resources" org "week.edn")]
    (when (.isFile f)
      (try (edn/read-string (slurp f)) (catch Throwable _ nil)))))

(defn- rehearsal-findings
  "What the week asks of the rehearsal (ticket 524685a0): each
  integration the week posts an `:event` from has a `:rehearsal :steps`
  step reading `[:param :signing_secret_<integration>]`, so the clone
  holds a secret to sign it with, and each effect door a week call walks
  has a `:rehearsal :effects` fake for its tool, so the clone's wall
  answers it. Pure over the kinds, the seed and the week."
  [org rs seed week]
  (let [calls (mapcat :calls (:days week))
        steps (get-in seed [:rehearsal :steps])
        fakes (set (map text (keys (get-in seed [:rehearsal :effects]))))
        effects (into {} (for [r rs
                               [a d] (:actions r)
                               :let [power (get-in d [:effect :power])]
                               :when (some? power)]
                           [[(text (:kind r)) (name a)] (text power)]))]
    (vec
     (concat
      (for [nm (distinct (keep #(some-> (:event %) name) calls))
            :let [p (keyword (str "signing_secret_" nm))]
            :when (not (some #(= [:param p] %) (tree-seq coll? seq steps)))]
        (finding :rehearsal ["event-secret" org nm] org
                 (str "The week posts a " nm " event, but the rehearsal gives the clone no secret to sign it with; add a secret step reading [:param :signing_secret_" nm "].")
                 (str "Add a :rehearsal :steps step creating the " nm " integration's secret with :value [:param :signing_secret_" nm "], as docs/orgkit.md § Rehearsing an integration shows.")))
      (for [tool (distinct (keep #(get effects [(text (:kind %)) (text (:action %))]) calls))
            :when (not (fakes tool))]
        (finding :rehearsal ["effect-fake" org tool] org
                 (str "The week walks a door whose effect calls " tool ", but the rehearsal has no fake to answer it; add one under :rehearsal :effects.")
                 (str "Add a fake for " tool " under the seed's :rehearsal :effects, as docs/orgkit.md § Rehearsing an integration shows.")))))))

;; ── the suite ───────────────────────────────────────────────────────

(defn- name-findings
  "The org's name, its directory's: snake_case, as live-orgs boots only
  such an org (waymark10.server.org, docs/orgkit.md § Naming rules), so a
  wrong one shows before go_live."
  [org]
  (when-not (re-matches #"[a-z][a-z0-9_]*" org)
    [(finding :org_name_not_snake_case [org] org
              (str "The org's name " org " is not snake_case, so live-orgs skips orgs/" org " and it never boots.")
              "Rename the org's directory, manifest :name and seed :org to lowercase letters, digits and underscores, starting with a letter, as docs/orgkit.md § Naming rules says.")]))

(defn- manifest-findings
  "The org's manifest, org.edn (ticket 29f8e07a): there, readable, a
  map naming the org and saying whether it is live."
  [^File dir org]
  (let [m (try (org-boot/manifest dir) (catch Throwable e e))
        remedy (str "Write org.edn beside deps.edn, as docs/orgkit.md § The manifest says: {:name \""
                    org "\" :live false}.")]
    (cond
      (instance? Throwable m)
      [(finding :manifest ["file" org] org
                (str "orgs/" org "/org.edn does not read: " (root-message m))
                remedy)]
      (nil? m)
      [(finding :manifest ["missing" org] org
                (str "The org has no manifest: orgs/" org "/org.edn is missing.")
                remedy)]
      (not (map? m))
      [(finding :manifest ["file" org] org
                (str "orgs/" org "/org.edn is not a map.")
                remedy)]
      :else
      (cond-> []
        (not= org (:name m))
        (conj (finding :manifest ["name" org] org
                       (str "The manifest names the org " (pr-str (:name m)) ", not " org ".")
                       "Set the manifest's :name to the org's directory name."))
        (not (boolean? (:live m)))
        (conj (finding :manifest ["live" org] org
                       (str "The manifest's :live is " (pr-str (:live m)) ", not true or false.")
                       "Set :live false; going live sets it true."))))))

(defn- judged
  "Every finding but the manifest's that the org at `dir`, named `org`,
  earns."
  [^File dir org]
  (let [seed-file (io/file dir "resources" org "seed.edn")
        seed (try (edn/read-string (slurp seed-file)) (catch Throwable e e))]
    (if (instance? Throwable seed)
      [(finding :seed ["file" org] org
                (str "resources/" org "/seed.edn does not read: " (root-message seed))
                "Write the seed as docs/orgkit.md § The seed says.")]
      (let [{rs :resources :as loaded} (load-org dir org)
            ;; the seed, cast and seats, as a clone joins its :rehearsal
            ;; to them (ticket 290faa7a): judged by seed, crack and scope
            cloned (org-boot/rehearsed seed {:clone? true})
            {:keys [seats] :as seeded} (seed-seats cloned)]
        (if-not rs
          (:findings loaded)
          (let [booted (boot org rs seed)]
            (vec (concat
                  (when (not= org (:org seed))
                    [(finding :seed ["org" org] org
                              (str "The seed names the org " (pr-str (:org seed)) ", not " org ".")
                              "Set the seed's :org to the org's directory name.")])
                  (:findings seeded)
                  (seat-schema-findings seed)
                  (resource-findings rs)
                  (registry-findings org rs)
                  (crack-findings rs seats)
                  (sentence-findings rs)
                  (:findings booted)
                  (when-some [eng (:engine booted)]
                    (scope-findings eng seats cloned))
                  (decision-findings rs (:seats (seed-seats seed)) seed)
                  (world-findings org rs seed
                                  (try (org-boot/manifest dir) (catch Throwable _ nil)))
                  (rehearsal-findings org rs seed (week-of dir org))))))))))

(defn conform
  "Every finding the org at org-dir earns, in a stable order, its
  name's and manifest's first; [] when it conforms. The directory's name
  is the org's name."
  [org-dir]
  (let [dir (.getCanonicalFile (io/file org-dir))
        org (.getName dir)]
    (into (vec (concat (name-findings org) (manifest-findings dir org)))
          (judged dir org))))

(defn- live?
  "Whether the org at `dir` says :live true in its manifest."
  [^File dir]
  (let [m (try (org-boot/manifest dir) (catch Throwable _ nil))]
    (true? (when (map? m) (:live m)))))

(defn fixture?
  "Whether the org at `dir` says :fixture true in its manifest: a
  test-only org, whose week the bench check does not judge (ticket
  911a4c1e). An org that has an org_plan may not say it: `live-report`
  carries it as `fixture_flag`, and the plan's findings pass refuses it
  (factory10.sources.forge/fixture-findings)."
  [^File dir]
  (let [m (try (org-boot/manifest dir) (catch Throwable _ nil))]
    (true? (when (map? m) (:fixture m)))))

(defn- org-dirs
  "An org directory as itself; any other directory as the orgs in it,
  only the live ones when `live-only?` (ticket 7310836f): a drafting
  org's error findings are its onboarding walk's steps, not a red gate."
  [live-only? arg]
  (let [d (io/file arg)]
    (if (.isFile (io/file d "deps.edn"))
      [d]
      (sort-by #(.getName ^File %)
               (filter #(and (.isFile (io/file ^File % "deps.edn"))
                             (or (not live-only?) (live? %)))
                       (.listFiles d))))))

(defn- wire-finding
  "A finding as org_plan's record_findings takes it: strings throughout."
  [f]
  (-> f (update :severity text) (update :class text)))

(defn live-report
  "What org_plan's go_live reads of the org at org-dir (ticket c8e131fb,
  M2e): its manifest's :live as `live_flag`, and its `mayor`, the seed's
  seat named <org>-mayor, as {name budget_usd_per_week}; no mayor when
  the seed seats none; `fixture_flag` true when the manifest says
  :fixture true (ticket 911a4c1e). A boolean and strings, as the
  findings doors take them."
  [org-dir]
  (let [dir (.getCanonicalFile (io/file org-dir))
        org (.getName dir)
        m (try (org-boot/manifest dir) (catch Throwable _ nil))
        seed (try (edn/read-string (slurp (io/file dir "resources" org "seed.edn")))
                  (catch Throwable _ nil))
        mayor (when (map? seed)
                (some #(when (= (str org "-mayor") (str (:name %))) %)
                      (:seats (seed-seats seed))))
        budget (:budget_usd_per_week mayor)]
    (cond-> {:live_flag (true? (when (map? m) (:live m)))}
      (true? (when (map? m) (:fixture m))) (assoc :fixture_flag true)
      mayor (assoc :mayor (cond-> {:name (str (:name mayor))}
                            (some? budget) (assoc :budget_usd_per_week (str budget)))))))

(defn -main
  "Judge each org named, or each under a directory named. Exit 1 on
  any error finding, 2 when no org was found. `--json` prints one JSON
  object instead, each org's name to its findings, for the audit's CI
  job to hand to org_plan's record_findings (ticket 5d62058e), and under
  `_live` each org's `live-report` (ticket c8e131fb). `--live` judges,
  of a directory named, only the orgs whose manifest says :live true;
  an org named itself is judged whatever it says (ticket 7310836f)."
  [& args]
  (let [json? (boolean (some #{"--json"} args))
        live-only? (boolean (some #{"--live"} args))
        dirs (->> (or (seq (remove #{"--json" "--live"} args)) ["."])
                  (mapcat (partial org-dirs live-only?))
                  (map #(.getCanonicalFile ^File %))
                  distinct)
        judged (mapv (fn [^File d] [(.getName (.getCanonicalFile d)) (conform d)])
                     dirs)
        errors (count (filter #(= :error (:severity %)) (mapcat second judged)))]
    (if json?
      (println (wire/write-json
                (assoc (into (sorted-map)
                             (map (fn [[org fs]] [org (mapv wire-finding fs)]))
                             judged)
                       "_live" (into (sorted-map)
                                     (map (fn [^File d]
                                            [(.getName (.getCanonicalFile d)) (live-report d)]))
                                     dirs))))
      (doseq [[org fs] judged]
        (println (str org ": "
                      (if (seq fs) (str (count fs) " finding(s)") "conforms")))
        (doseq [{:keys [severity key sentence remedy]} fs]
          (println (str "  [" (name severity) "] " key " " sentence))
          (println (str "      " remedy)))))
    (shutdown-agents)
    (System/exit (cond (empty? dirs) 2 (pos? (long errors)) 1 :else 0))))
