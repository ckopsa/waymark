(ns waymark10.judgment
  "The judgment as a row (waymark-fp62.11 R-1): a judge is a
  declaration, not a file.

  A judged kind used to write its own verdict doors in code. `ci_run`
  is the whole argument: three classify doors, a stamp door, a
  person's reclassify door and three scenarios — 437 lines to ask one
  question about one kind. A judge on a SECOND kind was a second file
  and a deploy. Everything else in this framework is data; a judgment
  had no business being the exception.

  ── WHAT ONE ROW SAYS ──

  One row declares one judgment. It names the SUBJECT KIND, the QUEUE
  (a filter on that kind — which of its rows this judgment is about),
  the VERDICTS (each a name and one sentence, because a word nobody
  can read back is not a verdict), the REMEDY CEILING, and the
  CONSEQUENCE — a door on the subject that a landed verdict earns.
  `waymark10.verdict` is the row the queue's rows then collect, one
  per subject per judgment.

  ── THE STATES ARE A DEFINITION'S ──

  `draft` is where a judgment is authored and re-authored (`revise`
  restates the whole row, because a judgment read half-changed is a
  judgment nobody can act on). `promote` is the gate: the four
  declaration checks run THERE, so a judgment that cannot project
  never projects. `supersede` retires one in favor of the next — the
  row stays, because the verdicts written under it are still rows
  about rows and a ledger whose judgment vanished is a ledger of
  orphans.

  ── WHY THE CHECKS ARE GUARDS AND NOT A BATTERY ──

  `waymark10.checks` judges DECLARATIONS at import, where a bad one
  refuses the boot. A judgment is authored at RUN time by a person, so
  the same four questions have to be asked at a door and answered in
  the household's own words: this engine serves no such kind; two of
  your verdicts are the same word; that queue filters on a field the
  kind does not filter by; that consequence is no door. Each names
  `revise` as the way back, which is the whole of what the author has
  to do next.

  The registry consult is `saved_view`'s, inherited whole: `(:rdef-of
  ctx)` resolves the kind token against the declarations this engine
  actually serves, and a storage-free probe carrying no consult
  advertises optimistically — the write path always carries it.

  ── MANY JUDGMENTS ON ONE KIND (R-6) ──

  Two rows may name the same subject kind. Each has its own verdicts,
  its own ceiling and its own count, and a subject may carry one
  verdict under each. Nothing here enforces that and nothing needs to:
  the uniqueness is the verdict's, one row over, per (judgment,
  subject).

  ── AND ON THE ENGINE'S OWN ROWS (R-7) ──

  `subject_kind` is a kind TOKEN rather than a ref, the tickler's
  shape and `verdict_reason`'s, so a judgment may name `sitting`,
  `grant` or `seat` as readily as an application's kind. A judge on
  how an agent sat is the same declaration as a judge on a CI run."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def judgment-kind
  "The declaration's kind keyword — the definite marker, never a name
  string."
  :judgment)

(def default-remedy-max
  "How long a remedy may be when the author says nothing. Two hundred
  and forty characters is a sentence and a half: long enough to say
  what to do, short enough that nobody writes a report into it."
  240)

;; ── the field set, as one map ───────────────────────────────────────

(def verdict-entry
  "One verdict the judgment names: the word and the sentence a reader
  gets back. The sentence is required and the reason is the same one
  `verdict_reason`'s labels are required: a token with no words beside
  it is a record only its author can read."
  [:map
   [:name {:x-display
           {:label "The word"
            :help "One snake_case word for this verdict — infra, base_red, this_change. It is what a seat writes and what the ledger counts, so it is short and it does not change once the judgment is promoted."}}
    [:string {:min 1 :max 40}]]
   [:sentence {:x-display
               {:label "What it means"
                :help "One sentence a person reads back months later, in the household's own words. What the word means about the subject — not what to do about it, which is the remedy the seat writes."}}
    [:string {:min 1 :max 240}]]])

(def queue-spelled-by-hand
  "Why the queue is typed rather than offered — `grants`' own sentence
  for its own filter map, said again here because it is the same box
  for the same reason: no form can list another kind's field names."
  "No form can list another kind's field names, so this pair is typed: write the field name, then the value it must equal. The field must be one that kind declares filterable.")

(def ^:private prose
  {:name
   {:x-display
    {:label "Name"
     :help "What this judgment is called, in the words the household would use out loud — \"Why did CI go red\", \"Did the seat do what it was asked\". One name per house; a second judgment on the same kind gets its own."}}
   :subject_kind
   {:x-display
    {:raw true
     :label "What it judges"
     :help "The kind whose rows this judgment is about, by its own token — ci_run, outcome, sitting. Kept as a name rather than a reference, because a judgment names a COLLECTION and a reference names one row."}}
   :queue
   {:x-display
    {:label "Which of them"
     :spelled-by-hand queue-spelled-by-hand
     :help "The slice this judgment is about: field=value pairs in the shape of that kind's own query, equality only. Leave it empty and the judgment is about every row of the kind."}}
   :verdicts
   {:x-display
    {:label "The verdicts"
     :help "The closed list of words a seat may write about a subject here, each with the sentence it means. Between one and twelve: a list a reader cannot hold in their head is a list a model guesses from."}}
   :remedy_max
   {:examples [240]
    :x-display
    {:label "How long a remedy may be"
     :help "The ceiling on the sentence a seat writes beside its verdict, in characters. Two hundred and forty by default — long enough to say what to do, short enough that nobody files a report."}}
   :consequence
   {:x-display
    {:raw true
     :label "What a verdict earns"
     :help "The name of a door on the subject kind that a landed verdict may walk — the action, not its label. Leave it blank and a verdict is a record and nothing more, which is the honest answer for a judgment that only measures."}}
   :notes
   {:x-display
    {:widget "prose"
     :label "Notes"
     :help "Anything the fields could not carry: why this judgment exists, what an earlier one got wrong, where the words came from. Never required."}}})

(defn- entry [k extra form]
  [k (merge (get prose k) extra) form])

;; ── the four walls the promote door keeps ───────────────────────────
;;
;; All four judge the ROW and none judges the input: `promote` takes
;; no body, so a guard here declares `:judges []` and reads the
;; document it is about to project. Each names `revise` as the way
;; back, because that is literally the next thing the author does.

(defn- served-rdef
  "The subject kind's declaration, through the ctx registry consult —
  nil when no consult is in scope (a render probe) or when this engine
  serves no such kind."
  [row ctx]
  (when-some [rdef-of (:rdef-of ctx)]
    (when-some [k (some-> (get-in row [:data :subject_kind]) str str/trim
                          not-empty)]
      (rdef-of k))))

(g/defguard subject-kind-is-served
  {:judges []
   :reads [:storage]
   :vars [:kind]
   :remedies [:judgment/revise]
   :explain "This judgment is about {kind}, and this engine serves no kind by that name. A judgment projects onto a queue of real rows, so a subject nothing answers to is a queue that can never fill and a count that can never move."}
  [row _inp ctx]
  ;; the registry consult, `saved_view/composes-declared-primitives`'s
  ;; posture verbatim: no consult in scope means a storage-free probe,
  ;; which advertises optimistically — the write path always carries it
  (if (nil? (:rdef-of ctx))
    (t/allow)
    (if (some? (served-rdef row ctx))
      (t/allow)
      (t/deny {:vars {:kind (str (get-in row [:data :subject_kind]))}}))))

(g/defguard verdict-names-are-distinct
  {:judges []
   :reads []
   :vars [:word]
   :remedies [:judgment/revise]
   :explain "Two of these verdicts are both called {word}. One word means one thing here, or the ledger's count under this judgment is a count of two different questions added together."}
  [row _inp _ctx]
  ;; the one wall that needs nothing but the row — it reads the
  ;; document and no world at all, which is why it can say its whole
  ;; sentence before the registry is consulted
  (let [words (mapv #(str (:name %)) (get-in row [:data :verdicts]))
        dup (->> words frequencies (keep (fn [[w n]] (when (> (long n) 1) w)))
                 sort first)]
    (if dup
      (t/deny {:vars {:word dup}})
      (t/allow))))

(g/defguard queue-names-filterable-fields
  {:judges []
   :reads [:storage]
   :vars [:field :kind :fields]
   :remedies [:judgment/revise]
   :explain "The queue narrows {kind} by {field}, which that kind does not filter by equality. It filters by {fields}. A queue the collection cannot express is a queue nobody can read back."}
  [row _inp ctx]
  (if-some [rdef (served-rdef row ctx)]
    (let [eq (into (sorted-set)
                   (keep (fn [[f ops]] (when (contains? (set ops) :eq) (name f))))
                   (:filterable rdef))
          bad (->> (keys (get-in row [:data :queue]))
                   (map name) sort
                   (remove eq) first)]
      (if bad
        (t/deny {:vars {:field bad
                        :kind (str (get-in row [:data :subject_kind]))
                        :fields (if (seq eq) (str/join ", " eq) "nothing")}})
        (t/allow)))
    ;; an unserved kind (or no consult) is the wall above's sentence to
    ;; say, and one refusal per author per tap is the courtesy
    (t/allow)))

(g/defguard consequence-is-a-door
  {:judges []
   :reads [:storage]
   :vars [:door :kind :doors]
   :remedies [:judgment/revise]
   :explain "The consequence names {door}, which is no door of {kind}. That kind's doors are {doors}. A consequence that cannot be walked is a promise this judgment could never keep."}
  [row _inp ctx]
  (if-some [rdef (served-rdef row ctx)]
    (if-some [door (some-> (get-in row [:data :consequence]) str str/trim
                           not-empty)]
      (let [doors (into (sorted-set)
                        (map name)
                        (concat (keys (:actions rdef))
                                (:create-action-names rdef)))]
        (if (contains? doors door)
          (t/allow)
          (t/deny {:vars {:door door
                          :kind (str (get-in row [:data :subject_kind]))
                          :doors (str/join ", " doors)}})))
      (t/allow))
    (t/allow)))

;; ── the hands ───────────────────────────────────────────────────────

(def ^:private authored-fields
  "The fields a person writes — the whole of the judgment, which is
  what `revise` restates."
  [:name :subject_kind :queue :verdicts :remedy_max :consequence :notes])

(defhandler restate-the-judgment
  [row inp _ctx]
  ;; WHOLESALE, over exactly the authored fields: a judgment read
  ;; half-changed is a judgment nobody can act on, and an omitted
  ;; optional CLEARS rather than lingering. The transition log keeps
  ;; what stood before, which is where a reviewer reads the drift.
  (update row :data
          (fn [d] (into d (map (fn [k] [k (get inp k)])) authored-fields))))

(defhandler name-the-successor
  [row inp _ctx]
  (assoc-in row [:data :successor] (:successor inp)))

;; ── :judgment — one row, one judge ──────────────────────────────────

(defresource judgment
  {:kind :judgment
   :plural "judgments"
   ;; hand-written kinds inherit no :nav, and :system is the honest one
   ;; here for `verdict_reason`'s reason: a judgment is the law a queue
   ;; is read under, not a thing in the queue, so a :primary spelling
   ;; would card it in do-now beside the work it measures.
   :nav :system
   :states [:draft :promoted :superseded]
   :initial :draft
   :terminal #{:superseded}
   ;; WHAT AN ENDING MEANS HERE (waymark-iqa.24/.25). A superseded
   ;; judgment was LET GO — the house moved to the next one — and
   ;; nothing about this kind is ever accomplished: a judgment does not
   ;; finish, it stops being the one in force. Declared rather than
   ;; left blank, because the machine's default reads every terminal
   ;; state as an accomplishment and would congratulate the house for
   ;; retiring a judge.
   :over {:accomplished #{} :let-go #{:superseded}}
   ;; ONE NAME PER HOUSE. Two judgments may share a subject kind (R-6)
   ;; and must not share a name: the name is what a person says out
   ;; loud when they ask what the count is a count of.
   :unique [[:name]]
   :summary "{data.name} · {data.subject_kind} · {state}"
   :label-template "{data.name}"
   :display {:title "The judgments this house holds"}
   :schema
   [:map
    (entry :name {:filter #{:eq}} [:string {:min 1 :max 120}])
    (entry :subject_kind {:filter #{:eq}} [:string {:min 1 :max 60}])
    (entry :queue {:optional true :default {}}
           [:maybe [:map-of :keyword [:string {:min 1 :max 200}]]])
    (entry :verdicts {} [:vector {:min 1 :max 12} verdict-entry])
    (entry :remedy_max {:default default-remedy-max}
           [:int {:min 40 :max 1000}])
    (entry :consequence {:optional true} [:maybe [:string {:max 60}]])
    (entry :notes {:optional true} [:maybe [:string {:max 1200}]])
    ;; the judgment this one stood down for, written by `supersede`
    ;; and by nothing else: a pointer forward so a reader who lands on
    ;; a retired judge is told where the house went next
    [:successor {:optional true
                 :kind :judgment
                 :x-display
                 {:hidden true
                  :label "Succeeded by"
                  :spelled-by-hand "Refused here: the successor is Supersede's to write."}}
     [:maybe :waymark/ref]]]
   :create-schema
   [:map
    (entry :name {} [:string {:min 1 :max 120}])
    (entry :subject_kind {} [:string {:min 1 :max 60}])
    (entry :queue {:optional true :default {}}
           [:maybe [:map-of :keyword [:string {:min 1 :max 200}]]])
    (entry :verdicts {} [:vector {:min 1 :max 12} verdict-entry])
    (entry :remedy_max {:default default-remedy-max}
           [:int {:min 40 :max 1000}])
    (entry :consequence {:optional true} [:maybe [:string {:max 60}]])
    (entry :notes {:optional true} [:maybe [:string {:max 1200}]])]
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:name :created_at] :default "name"}
   :links [{:rel "verdicts" :kind :verdict
            :href "/api/verdicts?judgment={id}"
            :summary "Every verdict written under this judgment"}
           {:rel "successor" :kind :judgment
            :href "/api/judgments/{data.successor}"
            :summary "The judgment this one stood down for"}]
   :actions
   {:revise
    {:from #{:draft} :to :draft
     ;; THE WHOLE ROW, RESTATED. A judgment is read as one thing — the
     ;; queue and the verdicts and the ceiling answer each other — so
     ;; the door that changes it hands back all of it, `saved_view`'s
     ;; `apply-view` and `ranking_note`'s `restate` exactly.
     :input
     [:map
      (entry :name {} [:string {:min 1 :max 120}])
      (entry :subject_kind {} [:string {:min 1 :max 60}])
      (entry :queue {:optional true :default {}}
             [:maybe [:map-of :keyword [:string {:min 1 :max 200}]]])
      (entry :verdicts {} [:vector {:min 1 :max 12} verdict-entry])
      (entry :remedy_max {:default default-remedy-max}
             [:int {:min 40 :max 1000}])
      (entry :consequence {:optional true} [:maybe [:string {:max 60}]])
      (entry :notes {:optional true} [:maybe [:string {:max 1200}]])]
     :edit {:prefill authored-fields}
     :record true
     :handler restate-the-judgment
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Revise" :order 1
               :description "Change the judgment while it is still a draft — the form opens on what stands, and the log keeps what stood before"}}
    :promote
    {:from #{:draft} :to :promoted
     ;; THE GATE (R-1). The four declaration checks run here, in the
     ;; household's own words, because a judgment is authored at RUN
     ;; time and `waymark10.checks` only ever speaks at import. A
     ;; judgment that fails one does not project — it stays a draft,
     ;; where `revise` is the way on.
     ;;
     ;; The order is the framework's own: the row's own shape first
     ;; (distinct words need no world at all), then the registry, so an
     ;; author whose verdicts collide hears about that before they hear
     ;; anything about the kind they named.
     :guards [verdict-names-are-distinct
              subject-kind-is-served
              queue-names-filterable-fields
              consequence-is-a-door]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "From here the judgment projects: its queue is a queue, its verdicts are the words a seat may write, and the verdicts written under it are rows. Nothing comes back to draft — a judgment that turned out wrong is superseded by the next one, and both stay on the record."}
     :display {:label "Promote" :order 2
               :description "Put this judgment in force — its queue fills and seats may write its verdicts"}}
    :supersede
    {:from #{:promoted} :to :superseded
     :input [:map
             [:successor {:optional true
                          :kind :judgment
                          :x-display
                          {:label "Succeeded by"
                           :help "The judgment the house is moving to, when there is one. Leave it blank to retire this judge with nothing in its place."}}
              [:maybe :waymark/ref]]]
     ;; the edit-shaped heuristic sees `successor` twice and reads a
     ;; rewrite; it is a first value welded onto a blank, which is the
     ;; exact case :edit-shape exists to waive (waymark-01f). A prefill
     ;; here would open the form on a field that is empty by
     ;; construction.
     :waives #{:edit-shape}
     :handler name-the-successor
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The judgment stops being in force and stays on the record with every verdict written under it. There is no way back to draft; a judge the house wants again is a new row."}
     :display {:label "Supersede" :order 3
               :description "Retire this judgment — its verdicts stay rows, and a successor may be named"}}}})
