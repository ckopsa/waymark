(ns waymark10.scenario
  "Law scenarios: the policy proves itself.

  The conformance packs prove the MACHINERY — that an advertised
  action is an enforced action, that a refusal is shaped like a
  refusal. Nothing proved the POLICY. \"A letter addressed to someone
  else does not open for a curious sibling, and the refusal says
  which wall it hit\" is a sentence the household can check and the
  engine could not. A scenario makes it a declaration: a row written
  down instead of walked to, a principal, an attempted action, an
  expected verdict — data beside the resource, judged by the same
  g/evaluate the render probe and the enforcement loop call.

  ── the sentence IS the violation ──

  A scenario's sentence is required and non-blank, exactly as a
  guard's :explain is, and for the same reason: it is not
  documentation. When the law stops keeping the promise, the failure
  reads in the household's own words — never `expected :deny, got
  :allow`. The diagnosis rides after it, as a clause.

  ── :expect names the GUARD ──

  {:refused :opener-is-recipient} pins WHICH wall, not which prose. A
  scenario that pinned only the sentence would fail on every polish
  and pass on a guard swapped for a different guard with the same
  words. :because is a substring of the RENDERED reason (garnish and
  all) and is optional; :remedies asserts the affordance tokens the
  refusal offers, which is how a scenario says \"and it names the way
  out\". The reserved denier :out-of-state is the one verdict with no
  guard behind it — a row whose state is outside the action's :from.

  ── two tiers, chosen by declaration, never sniffed ──

  A scenario runs in the CHECK tier — in this process, with no
  storage of any kind, in the same breath as `make check-queue` —
  when it declares no :given rows AND every guard on the attempted
  action declares :reads ⊆ offline-reads. Both conditions are read
  off DECLARATIONS, which is guards.clj's own posture ('call shape
  comes from :reads/:judges declarations, never from arity
  inspection'). Everything else is the CONFORMANCE tier: the
  :core/law-scenarios obligation, over the application's real engine,
  through the HTTP door — so what it proves is the refusal a CLIENT
  sees, not the one a guard returned.

  :reads is ASSERTED, not inferred. A code guard that reaches past
  its declared :reads — an in-process atom, a clock read — answers
  the check tier's question with its offline verdict.
  workqueue10's letters-are-paced already handles that by hand
  ((nil? (:read ctx)) ⇒ allow: 'the storage-free probe never spends a
  slot'), which is the pattern, not the exception. A scenario that
  wants the live verdict declares :given and drops a tier.

  ── never fingerprinted ──

  fingerprint-of is a WHITELIST projection, so :scenarios is excluded
  by construction rather than by an exclusion list. That is
  load-bearing and pinned (law_scenarios_test): a test that minted a
  law revision would be a test that triggered a propose-mode hold,
  and the framework would be at war with itself.

  Two callers — waymark10.check (the no-database battery) and
  waymark10.test.packs (the obligation) — and the logic lives in
  neither, the same shape waymark10.test.conformance-as-library
  already has."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.summary :as summary]
            [waymark10.types :as t])
  (:import (java.time Instant)
           (java.time.format DateTimeParseException)))

(set! *warn-on-reflection* true)

;; ── the authored surface, closed ────────────────────────────────────

(def scenario-keys
  "One scenario's whole authored surface — the same closure
  action-keys and link-keys already give theirs, so a typo'd key
  refuses at the def line instead of declaring nothing."
  [:name :sentence :kind :attempt :row :input :as :at :given :expect])

(def expect-keys
  "The verdict half: exactly one of :allowed / :refused, with
  :because and :remedies as optional tightenings of a refusal."
  [:allowed :refused :because :remedies])

(def row-keys [:state :data])
(def given-keys [:kind :state :data])
(def principal-keys [:id :type :roles])

(def offline-reads
  "The external dependencies a STORAGE-FREE judgment can honestly
  supply: the clock the scenario declares (:at), the principal it
  declares (:as), and the feature set — which the check tier serves
  as EMPTY, since a declaration-time world has no service wiring. A
  guard reading anything else (:storage, :transitions, a kind
  keyword) is judged where its reads can be answered: the conformance
  tier."
  #{:now :principal :services.features})

(def out-of-state
  "The reserved denier name: a row whose state is outside the
  action's :from set is refused by the machine itself, with no guard
  behind it (server/problems wrong-state, render's
  out-of-state-entry)."
  :out-of-state)

;; ── construction ────────────────────────────────────────────────────

(defn- err [sname msg]
  (throw (t/definition-error (str "defscenario " (name sname) ": " msg)
                             {:check :scenario :scenario sname})))

(defn- closed! [sname label legal m]
  (when (map? m)
    (when-some [stray (seq (remove (set legal) (keys m)))]
      (err sname (str (pr-str (vec (sort stray))) " " (if (= 1 (count stray)) "is" "are")
                      " not " label " keys; they are " (vec (sort legal)))))))

(defn- instant!
  [sname at]
  (when (some? at)
    (when-not (string? at)
      (err sname ":at is the moment as an ISO instant string"))
    (try (Instant/parse ^String at)
         (catch DateTimeParseException _
           (err sname (str ":at " (pr-str at) " is not an ISO instant"))))))

(defn- principal!
  [sname as]
  (when (some? as)
    (when-not (map? as) (err sname ":as is the principal map {:id … :type … :roles #{…}}"))
    (closed! sname "principal" principal-keys as)
    (when (str/blank? (str (:id as))) (err sname ":as names the principal's :id")))
  ;; roles cross the wire and the role guard as STRINGS (guards/role
  ;; reads (contains? (:roles principal) (name role))), so a scenario
  ;; may spell them either way and lands on one
  (let [as (or as {:id "walker" :type :system})]
    (assoc as
           :id (str (:id as))
           :type (or (:type as) :person)
           :roles (into #{} (map name) (:roles as)))))

(defn- row! [sname label legal row]
  (when (some? row)
    (when-not (map? row) (err sname (str label " is {:state … :data {…}}")))
    (closed! sname label legal row)
    (when-not (keyword? (:state row))
      (err sname (str label " names its :state as a keyword")))
    (when-not (map? (:data row {}))
      (err sname (str label " :data is the row's document as a literal map"))))
  row)

(defn- expect! [sname expect]
  (when-not (map? expect)
    (err sname "a scenario is a verdict: :expect {:allowed true} or {:refused <guard-name>}"))
  (closed! sname "expect" expect-keys expect)
  (let [{:keys [allowed refused because remedies]} expect]
    (when (= (some? allowed) (some? refused))
      (err sname ":expect declares exactly one of :allowed and :refused"))
    (when (and (some? allowed) (not (true? allowed)))
      (err sname ":expect :allowed is true — a false allowance is a refusal, and a refusal names its guard"))
    (when (and (some? refused) (not (keyword? refused)))
      (err sname (str ":expect :refused names the GUARD, not the sentence — a keyword, or "
                      out-of-state " for a row outside the action's :from")))
    (when (and (some? because) (or (not (string? because)) (str/blank? because)))
      (err sname ":expect :because is a substring of the rendered reason"))
    (when (and (some? because) (nil? refused))
      (err sname ":expect :because tightens a refusal; an allowance has no reason to read"))
    (when (and (seq remedies)
               (or (nil? refused) (not (every? keyword? remedies))))
      (err sname ":expect :remedies is a vector of affordance tokens (:kind/action) a refusal offers"))
    expect))

(defn scenario
  "Validate and default one scenario, returning the PLAIN map — the
  very value the inline spelling would put in :scenarios, so the
  def'd and inline spellings are one value (and, since :scenarios is
  outside the fingerprint's whitelist, one hash: none).

  Construction validation only. Whether the kind is served, whether
  the attempt names a real action, whether the expected guard is
  actually on it — those need the assembled declaration, so they are
  the judge's questions, exactly as defaction leaves cross-referencing
  to defresource."
  [sname sentence m]
  (when-not (keyword? sname) (err :scenario "a scenario is named by its def"))
  (when (or (not (string? sentence)) (str/blank? sentence))
    (err sname (str "the sentence comes first, and it IS the violation string — "
                    "(defscenario " (name sname) " \"…\" {…})")))
  (when-not (map? m) (err sname "a scenario is a map of the closed scenario keys"))
  (closed! sname "scenario" scenario-keys m)
  (when-not (keyword? (:kind m)) (err sname ":kind names the resource this scenario judges"))
  (when-not (keyword? (:attempt m))
    (err sname ":attempt names the action, or the create door by its own name"))
  (row! sname ":row" row-keys (:row m))
  (when (some? (:given m))
    (when-not (vector? (:given m))
      (err sname ":given is a vector of rows that must genuinely exist"))
    (doseq [gv (:given m)]
      (when-not (keyword? (:kind gv)) (err sname "each :given row names its :kind"))
      (row! sname ":given row" given-keys gv)))
  (when (and (some? (:input m)) (not (map? (:input m))))
    (err sname ":input is the action's input body as a literal map"))
  (instant! sname (:at m))
  (expect! sname (:expect m))
  (assoc m
         :name sname
         :sentence sentence
         :given (vec (:given m))
         :as (principal! sname (:as m))
         :expect (:expect m)))

;; ── the attempted door ──────────────────────────────────────────────

(defn create-door?
  "Is this attempt the kind's create door? :create-action-names is the
  declaration (normalized to #{:create} when unspoken), never a name
  match."
  [rdef attempt]
  (boolean (contains? (or (:create-action-names rdef) #{:create}) attempt)))

(defn attempted
  "What the scenario attempts, resolved against the declaration:
  {:door :create :guards […]} | {:door :action :action a :guards […]}
  | {:missing \"sentence\"}."
  [rdef s]
  (let [a (:attempt s)]
    (cond
      (nil? rdef)
      {:missing (str "names kind " (:kind s) ", which nothing here declares")}

      (not= (:kind rdef) (:kind s))
      {:missing (str "declares :kind " (:kind s) " on " (:kind rdef)
                     " — a scenario lives on the kind whose action it attempts")}

      (create-door? rdef a)
      {:door :create :guards (vec (:create-guards rdef))}

      (contains? (:actions rdef) a)
      (let [d (assoc (get-in rdef [:actions a]) :name a)]
        {:door :action :action d :guards (vec (:guards d))})

      :else
      {:missing (str "attempts " a ", which " (name (:kind s))
                     " does not declare")})))

(defn- guard-names
  "Every name the denier of this guard tree could wear: the composite
  itself (an :any denies as itself — an OR advertises nothing) and
  every descendant leaf."
  [gd]
  (into #{(:name gd)}
        (mapcat guard-names)
        (concat (:all gd) (:any gd))))

;; ── the tier, read off declarations ─────────────────────────────────

(defn check-tier?
  "Does this scenario keep check.clj's no-storage posture? Two
  declared conditions, both read and neither sniffed: it stages no
  :given rows, and every guard on the attempted action declares
  :reads ⊆ offline-reads."
  [rdef s]
  (let [{:keys [guards missing]} (attempted rdef s)]
    (boolean (and (nil? missing)
                  (empty? (:given s))
                  (every? #(every? offline-reads (:reads %)) guards)))))

(defn deferral-reason
  "Why a scenario waits for the suite, in the author's own terms —
  printed by check so nobody has to guess which half of the tier rule
  they tripped."
  [rdef s]
  (when-not (check-tier? rdef s)
    (let [{:keys [guards missing]} (attempted rdef s)]
      (cond
        missing nil
        (seq (:given s)) (str "stages " (count (:given s)) " given row"
                              (when (not= 1 (count (:given s))) "s"))
        :else
        (let [beyond (into (sorted-set)
                           (comp (mapcat :reads) (remove offline-reads))
                           guards)]
          (str "reads " (str/join ", " (map str beyond))))))))

;; ── the check-tier judge ────────────────────────────────────────────

(defn verdict
  "The enforcement loop's own grading: guards in declaration order, a
  warning collects (it is acknowledgable, not a refusal), the first
  refusing deny is the verdict — the same shape invoke's run-guards
  and create-guard-pass share, so the three judgments cannot drift.

  Public because a scenario is not the only caller that wants \"what
  does THIS guard tree say about THIS row\". The law sweep
  (waymark10.server.law-sweep) hands it a guard vector rebuilt from a
  stored revision and then the resident one, over the same row and
  the same ctx, and reads the difference: one evaluator, two callers,
  and a sweep finding's `because` is a scenario's refusal rendered by
  this very line. The ctx is the caller's to build — storage-free
  here (offline-ctx), hooked and live there."
  [guards row inp ctx]
  (reduce
   (fn [acc gd]
     (let [[v d] (g/evaluate gd row inp ctx)]
       (if-not (t/deny? v)
         acc
         (if (= :warning (:severity d))
           (update acc :warned conj (:name d))
           (reduced (assoc acc
                           :refused (:name d)
                           :reason (g/render-reason d v row)
                           :remedies (vec (:remedies d))
                           :hidden (boolean (:hide d))))))))
   {:warned []}
   guards))

(defn- offline-ctx
  "Everything a check-tier guard is allowed to read, and nothing
  else. :invoke and not :probe because a scenario SUPPLIES its input
  and must not collect the probe's pending-input short-circuit; :rate
  nil because an engine without a rate coordinator is what
  guards/rate-limit already allows for; no :read, :find or :actor-of
  hook, because there is no storage here and a guard that reaches for
  one gets the offline answer it declared it could give."
  [s]
  {:mode :invoke
   :now (some-> ^String (:at s) Instant/parse)
   :principal (:as s)
   :services {:features []}
   :rate nil})

(defn- scenario-row
  "The row as a literal document, wearing the shape storage would
  have given it — a kind, an id, a state, a data map."
  [s]
  (when-some [row (:row s)]
    {:kind (:kind s)
     :id (str "scenario:" (name (:name s)))
     :state (:state row)
     :data (:data row {})}))

(defn- structural
  "What the assembled declaration can answer and the def line could
  not: the door exists, the row suits the door, the expected guard is
  a guard this action actually carries, the clock the law reads is a
  clock the scenario declared. Authoring faults, not broken law —
  they read as themselves rather than as the household's sentence."
  [rdef s]
  (let [{:keys [door guards missing]} (attempted rdef s)
        refused (get-in s [:expect :refused])]
    (or
     (when missing [(str (name (:name s)) " " missing)])
     (not-empty
      (into []
            (comp (remove nil?)
                  (map #(str (name (:name s)) " [" (name (:kind s)) "/"
                             (name (:attempt s)) "] " %)))
            [(when (and (= :create door) (:row s))
               "attempts the create door with a :row — no instance exists yet")
             (when (and (= :action door) (nil? (:row s)))
               (str "attempts " (:attempt s) " with no :row — an action judges an instance"))
             (when (and (= :action door) (:row s)
                        (not (contains? (set (:states rdef)) (get-in s [:row :state]))))
               (str ":row is in state " (get-in s [:row :state])
                    ", which " (name (:kind s)) " does not declare"))
             (when (and refused (not= out-of-state refused)
                        (not (contains? (into #{} (mapcat guard-names) guards) refused)))
               (str ":expect :refused names " refused
                    ", which is not a guard on " (:attempt s)
                    " — the guards there are "
                    (pr-str (vec (sort (map :name guards))))))
             (when (and (= :create door) (= out-of-state refused))
               (str ":expect :refused " out-of-state
                    " — the create door has no state to be out of"))
             (when (and (nil? (:at s))
                        (some #(contains? (set (:reads %)) :now) guards))
               (str "declares no :at, but " (:attempt s)
                    " consults the clock — a scenario over a timed law names its moment"))])))))

(defn- clause
  "What actually happened, when it is not what the scenario said
  would. nil when the law kept its promise."
  [s got]
  (let [{:keys [allowed refused because remedies]} (:expect s)]
    (if-some [u (:unreadable got)]
      ;; the door answered something no verdict can be read off — a
      ;; staging failure, a concealment, an unexpected status. The
      ;; sentence still leads; the clause says what came back
      u
      (if allowed
        (when-some [r (:refused got)]
          (str "the law refused it — " r ": " (:reason got)))
        (cond
          (nil? (:refused got))
          (if (seq (:warned got))
            (str "the law allowed it, warning only ("
                 (str/join ", " (map str (:warned got))) ")")
            "the law allowed it")

          (not= refused (:refused got))
          (str "a different wall: refused by " (:refused got)
               ", not " refused)

          (and because (not (str/includes? (str (:reason got)) because)))
          (str "refused by " refused ", but the reason reads \""
               (:reason got) "\"")

          (and (seq remedies)
               (not (every? (set (:remedies got)) remedies)))
          (str "refused by " refused ", offering "
               (if (seq (:remedies got)) (pr-str (vec (:remedies got))) "no way out")
               " where the scenario expects " (pr-str (vec remedies))))))))

(defn violation
  "One scenario's failure, sentence-first — the household's own words,
  with the diagnosis riding after as a clause. nil when the law kept
  the promise. Public because BOTH tiers narrate the same way: the
  conformance obligation hands it the verdict it read off the wire."
  [s got]
  (when-some [c (clause s got)]
    (str (name (:name s)) " [" (name (:kind s)) "/" (name (:attempt s)) "] "
         (str/replace (str/trim (:sentence s)) #"\s+" " ")
         " — " c)))

(defn judge
  "Judge one CHECK-TIER scenario against its declaration, with no
  storage of any kind → a vector of violation strings (empty when the
  law kept its promise). The tier is the caller's question; this
  judges what it is handed."
  [rdef s]
  (or (structural rdef s)
      (let [{:keys [door action guards]} (attempted rdef s)
            row (scenario-row s)
            ctx (offline-ctx s)]
        (if (and (= :action door)
                 (not (contains? (set (:from action)) (:state row))))
          ;; the machine's own refusal, before any guard is consulted —
          ;; the one verdict with no guard behind it
          (let [states (sort (:from action))]
            (vec (remove nil?
                         [(violation s {:refused out-of-state
                                        :reason (str "Available in state(s) "
                                                     (str/join ", " (map summary/state-label states))
                                                     "; the resource is "
                                                     (summary/state-label (:state row)) ".")
                                        :remedies []})])))
          (vec (remove nil?
                       [(violation s (verdict guards row (:input s) ctx))]))))))

;; ── the report both callers print ───────────────────────────────────

(defn report
  "One kind's scenarios, split by tier and judged where they can be
  judged for free: {:total n :checked n :deferred [{:name … :why …}]
  :violations [\"…\"]}. check.clj prints it; the suite pays the
  deferred half."
  [rdef]
  (let [ss (vec (:scenarios rdef))
        {check-tier true deferred false} (group-by #(check-tier? rdef %) ss)]
    {:total (count ss)
     :checked (count check-tier)
     :deferred (mapv (fn [s] {:name (:name s) :why (deferral-reason rdef s)}) deferred)
     :violations (into [] (mapcat #(judge rdef %)) check-tier)}))

(defn coverage
  "How much of a kind's refusing law any scenario names: [named
  total] over the refusing guard leaves of every action and the
  create door. Counted, never enforced — a usability warning here
  would fire on every action in the tree on the day it landed, and a
  warning nobody can clear is a warning nobody reads."
  [rdef]
  (let [refusing (fn refusing [gd]
                   (into (if (= :warning (:severity gd)) #{} #{(:name gd)})
                         (mapcat refusing)
                         (concat (:all gd) (:any gd))))
        walls (into #{}
                    (comp cat (mapcat refusing) (remove nil?))
                    (cons (:create-guards rdef)
                          (map :guards (vals (:actions rdef)))))
        named (into #{} (keep #(get-in % [:expect :refused])) (:scenarios rdef))]
    [(count (filter named walls)) (count walls)]))
