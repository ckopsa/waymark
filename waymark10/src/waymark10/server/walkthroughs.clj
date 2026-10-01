(ns waymark10.server.walkthroughs
  "The walkthrough (docs/spec-walkthrough.md § 1 and § 2): the row that
  holds several steps in an order, some the person's and some the
  agent's.

  `open` IS AN OFFER. A walkthrough is born `open` and nothing happens
  until the subject takes `start`: the person agrees to be led before
  the first step opens.

  EVERY STEP IS JUDGED AS AN INVITATION IS JUDGED, under the author's
  own grant and at create: the grant must see the row and admit the
  door, and every name in a person step's `fields` and every key of
  its `suggest` must be a non-secret argument of that door. The
  sentences are the invitation's own. What the row can take NOW is not
  judged here, because step 3's door is often shut until step 2's
  transition commits; it is judged when the step opens.

  THE HANDLERS TOUCH NO OTHER ROW. They move `current`, `waiting_on`,
  `step_opened_at` and `outcomes` on this row. Opening and closing
  invitations is the consumer's work (§ 3).

  ONE CONSUMER, AND ONE RULE. A durable log consumer (`:walkthroughs`)
  hears every committed transition. An ending of the current step's
  invitation, or the author's own transition matching the current
  agent step, walks `step` or `stop` with the engine's hand. Any
  walkthrough transition is RECONCILED, and reconcile is the only
  place an invitation is opened or closed. It reads before it writes
  and every write carries a key made from what was heard, so a
  replayed transition changes nothing. The agent never opens a step
  and never polls for one."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.invitations :as invitations]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def kind :walkthrough)

(def engine-actor
  "The system actor that steps, stops and finishes a walkthrough."
  (t/principal {:id "waymark10-walkthroughs" :type :system
                :display "Walkthroughs"}))

;; ── the steps ───────────────────────────────────────────────────────

(defn- who-of
  "Whose step it is, \"person\" or \"agent\"; nil for no step."
  [step]
  (some-> (:who step) name))

(defn- current-step
  "The step `current` names; nil past the last one."
  [row]
  (let [n (get-in row [:data :current])]
    (when (and (integer? n) (pos? n))
      (nth (vec (get-in row [:data :steps])) (dec n) nil))))

(defn- named? [x]
  (not (str/blank? (str x))))

(defn- step-input
  "The step as the invitation's own judges read one."
  [step]
  (select-keys step [:self :action :fields :suggest]))

(defn- sight-problem
  "Why the author cannot name this step; nil when it can."
  [step ctx]
  (let [person? (= "person" (who-of step))
        self? (named? (:self step))
        action? (named? (:action step))]
    (cond
      (and person? (not (and self? action?)))
      "a person step names `self` and `action`."

      (and action? (not self?))
      "a step that names `action` names `self` too."

      (and self? action?)
      (invitations/sight-problem (step-input step) ctx))))

(defn- argument-problem
  "Why this step's `fields` and `suggest` cannot stand; nil when they
  can. An agent step names neither: the person types nothing on it."
  [step ctx]
  (if (= "person" (who-of step))
    (invitations/fields-problem (step-input step) ctx)
    (when (or (some? (:fields step)) (some? (:suggest step)))
      "an agent step names no `fields` and no `suggest`.")))

(defn- first-problem
  "The first step `judge` refuses, as `step N: <why>`; nil when every
  step passes."
  [judge inp ctx]
  (when (sequential? (:steps inp))
    (first (keep-indexed
            (fn [i step]
              (when (map? step)
                (some->> (judge step ctx) (str "step " (inc i) ": "))))
            (:steps inp)))))

;; ── guards ──────────────────────────────────────────────────────────

(g/defguard the-author-sees-every-step
  {:judges [:steps]
   :reads [:principal :grant :storage]
   :vars [:problem]
   :open "The author's own grant is the law here; no field of this door widens it. Ask for a grant that sees every row and admits every door the steps name, then create."
   :explain "A walkthrough's steps are steps its author can see and take: {problem}"}
  [_row inp ctx]
  (if (nil? (:rdef-of ctx))
    ;; no registry in scope (a render probe): the write path carries it
    (t/allow)
    (if-some [problem (first-problem sight-problem inp ctx)]
      (t/deny {:vars {:problem problem}})
      (t/allow))))

(g/defguard every-field-is-an-open-argument
  {:judges [:steps]
   :reads [:storage]
   :vars [:problem]
   :open "The fields are each action's own input schema; on a person step name arguments of it that are not secret, and suggest values only for such arguments."
   :explain "A person step points at arguments of its action a person may be shown: {problem}"}
  [_row inp ctx]
  (if-some [problem (first-problem argument-problem inp ctx)]
    (t/deny {:vars {:problem problem}})
    (t/allow)))

(defn- is? [row field ctx]
  (= (str (get-in row [:data field])) (str (:id (:principal ctx)))))

(defn- engine? [ctx]
  (= :system (:type (:principal ctx))))

(g/defguard the-subject-starts
  {:reads [:principal]
   :open "The wall is about who: the person led starts, and no field of this door makes anyone else that person."
   :explain "Only the person this walkthrough leads starts it."}
  [row _inp ctx]
  (if (is? row :subject ctx) (t/allow) (t/deny)))

(g/defguard the-walk-is-the-subjects-own-recording
  ;; § 6: a walk is recorded under its recorder's sight, so the person
  ;; led makes it; the engine and the author never do
  {:judges [:walk]
   :reads [:storage]
   :open "Name a walk you made yourself that is still recording and follows this walkthrough's author, or leave `walk` out and nothing is recorded."
   :explain "A walkthrough is recorded into a walk its subject made, that is still recording and follows the author."}
  [row inp ctx]
  (let [id (some-> (:walk inp) str not-empty)
        walk (when id (some-> (:read ctx) (apply [:walk id])))]
    (if (or (nil? id)
            (and walk
                 (= "recording" (some-> (:state walk) name))
                 (= (str (get-in walk [:data :recorder]))
                    (str (get-in row [:data :subject])))
                 (= (str (get-in walk [:data :followed]))
                    (str (get-in row [:data :author])))))
      (t/allow)
      (t/deny))))

(g/defguard either-side-stops
  {:reads [:principal]
   :open "The wall is about who: the person led and the author stop it, and no field of this door makes anyone else one of them."
   :explain "Only the person this walkthrough leads, or its author, stops it."}
  [row _inp ctx]
  (if (or (is? row :subject ctx) (is? row :author ctx) (engine? ctx))
    (t/allow)
    (t/deny)))

(g/defguard the-persons-stop-is-the-persons-to-lift
  {:reads [:principal]
   :open "The wall is about who: the person led resumes, and the author resumes only a stop that was not the person's."
   :explain "The person this walkthrough leads resumes it; its author does too, unless the person was the one who stopped it."}
  [row _inp ctx]
  (if (or (is? row :subject ctx)
          (and (is? row :author ctx)
               (not= (str (get-in row [:data :stopped_by]))
                     (str (get-in row [:data :subject])))))
    (t/allow)
    (t/deny)))

(g/defguard the-author-ends-its-own-step
  {:reads [:principal]
   :open "The wall is about who and when: the author advances, and only while the current step is the agent's. A person step ends by the person's own hand."
   :explain "Only the author advances a walkthrough, and only on an agent step."}
  [row _inp ctx]
  (if (and (is? row :author ctx) (= "agent" (who-of (current-step row))))
    (t/allow)
    (t/deny)))

(g/defguard the-author-withdraws
  {:reads [:principal]
   :open "The wall is about who: the author withdraws its own walkthrough, and no field of this door makes anyone else the author."
   :explain "Only the author of this walkthrough withdraws it."}
  [row _inp ctx]
  (if (is? row :author ctx) (t/allow) (t/deny)))

(g/defguard the-engine-moves-it
  {:reads [:principal]
   :hide true
   :explain "The engine steps a walkthrough when a step ends and finishes it past the last one; no hand at the wire does."}
  [_row _inp ctx]
  (if (engine? ctx) (t/allow) (t/deny)))

;; ── handlers ────────────────────────────────────────────────────────

(defn- now-of ^Instant [ctx]
  (or (:now ctx) (Instant/now)))

(defn- opened
  "The row with its current step opened now; `waiting_on` is nil past
  the last step."
  [row ctx]
  (update row :data assoc
          :step_opened_at (now-of ctx)
          :waiting_on (who-of (current-step row))))

(defn- born
  "The birth stamps: the author is the principal that created the row,
  never the body, and the first step is the current one."
  [row ctx]
  (update row :data assoc
          :author (str (get-in ctx [:principal :id]))
          :subject_name (invitations/subject-name (get-in row [:data :subject]) ctx)
          :current 1
          :outcomes []))

(defhandler open-step [row _inp ctx]
  (opened row ctx))

(defhandler start-step [row inp ctx]
  ;; the recording, when the person asked for one: reconcile seals it
  (cond-> (opened row ctx)
    (some? (:walk inp)) (assoc-in [:data :walk] (str (:walk inp)))))

(defhandler end-step [row inp ctx]
  ;; `advance` carries no input: the author's own word is `done`, and
  ;; no `by`, because no transition was matched
  (let [entry (cond-> {:step (get-in row [:data :current])
                       :outcome (or (some-> (:outcome inp) name) "done")
                       :at (str (now-of ctx))}
                (some? (:by inp)) (assoc :by (str (:by inp))))]
    (-> row
        (update-in [:data :outcomes] #(conj (vec %) entry))
        (update-in [:data :current] inc)
        (opened ctx))))

(defhandler record-stop [row inp ctx]
  (update row :data assoc
          :stopped_by (str (get-in ctx [:principal :id]))
          :stop_reason (:reason inp)))

;; ── the kind ────────────────────────────────────────────────────────

(def ^:private step-schema
  "One step (§ 2). A person step is an invitation's own fields with the
  invitation's own limits, because the engine makes one from them."
  [:map
   [:who {:x-display {:label "Whose step"
                      :help "`person`: the person takes this door themselves. `agent`: the person watches while the author works."}}
    [:enum "person" "agent"]]
   [:self {:optional true
           :x-display {:raw true
                       :label "The row"
                       :help "The row the step acts on, as its path: /api/<plural>/<id>. A person step names it; on an agent step it is where the person's screen goes."}}
    [:maybe [:string {:min 1 :max 300}]]]
   [:action {:optional true
             :x-display {:raw true
                         :label "The door"
                         :help "The action on that row. A person step names it; an agent step that names it ends when the author's own transition matches."}}
    [:maybe [:string {:min 1 :max 60}]]]
   [:fields {:optional true
             :x-display {:label "The fields"
                         :help "On a person step, the arguments of that action the person is pointed at, one to eight, in the order they are read. A secret argument is refused."}}
    [:maybe [:vector {:min 1 :max 8} [:string {:min 1 :max 60}]]]]
   [:note {:examples ["Set the priority and the type, then groom it."]
           :x-display {:widget "prose"
                       :label "Note"
                       :help "One sentence the person reads on this step: what to do, or what the agent is doing."}}
    [:string {:min 1 :max 240}]]
   [:suggest {:optional true
              :x-display {:label "Suggested values"
                          :help "On a person step, values shown to the person as suggestions. The engine never submits them."
                          :spelled-by-hand "Its keys are the arguments of the step's action, which differ per action, so no fixed sub-form can offer them."}}
    [:maybe [:map-of :keyword :any]]]])

(def ^:private outcome-schema
  [:map
   [:step {:x-display {:label "Step" :help "The number of the step that ended."}}
    [:int {:min 1}]]
   [:outcome {:x-display {:label "How it ended"
                          :help "`answered`: the person took the step. `skipped`: the person declined it. `done`: the agent's step ended."}}
    [:enum "answered" "skipped" "done"]]
   [:at {:x-display {:label "When" :help "When the step ended."}}
    [:string {:min 1 :max 64}]]
   [:by {:optional true
         :not-a-ref "It holds the log id of the transition that ended the step, and a log entry is no row of any kind."
         :x-display {:raw true
                     :label "Ended by"
                     :help "The log id of the transition that ended the step. Absent when the author advanced by hand."}}
    [:maybe [:string {:max 64}]]]])

(def ^:private offer-fields
  "What the author writes: the create model."
  [[:subject {:x-ref {:principal true}
              :x-display {:raw true
                          :label "Who is led"
                          :help "The person the steps are handed to: their principal id. Nothing opens until they start it."}}
    [:string {:min 1 :max 128}]]
   [:title {:examples ["Grooming a ticket"]
            :x-display {:label "Title"
                        :help "One line that names the task the steps add up to."}}
    [:string {:min 1 :max 120}]]
   [:steps {:x-display {:label "Steps"
                        :help "One to twenty steps, in the order they are taken. Each is judged under your own grant when you create the walkthrough."}}
    [:vector {:min 1 :max 20} step-schema]]])

(defresource walkthrough
  {:kind :walkthrough
   :plural "walkthroughs"
   :nav :secondary
   :states [:open :running :stopped :finished :withdrawn]
   :initial :open
   :terminal #{:finished :withdrawn}
   :summary "{data.title} · {data.subject_name} · {state}"
   :label-template "{data.title}"
   :schema
   (-> [:map
        [:author {:x-ref {:principal true}
                  :x-display {:raw true
                              :label "Who leads"
                              :help "The principal that wrote the steps, stamped by the engine at birth."}}
         [:string {:min 1 :max 128}]]]
       (into offer-fields)
       (into
        [[:subject_name {:optional true
                         :x-display {:label "Led by name"
                                     :help "The led person's name as their member row said it at birth, stamped by the engine."}}
          [:maybe [:string {:max 128}]]]
         [:current {:optional true
                    :x-display {:label "Current step"
                                :help "The number of the step that is open, counted from one. One past the last step when every step has ended."}}
          [:maybe [:int {:min 1}]]]
         [:waiting_on {:optional true
                       :x-display {:label "Waiting on"
                                   :help "Whose step the current one is: the person's or the agent's. Empty before the start and past the last step."}}
          [:maybe [:enum "person" "agent"]]]
         [:step_opened_at {:optional true
                           :x-display {:label "Step opened"
                                       :help "When the current step last became current, or was resumed."}}
          [:maybe :waymark/instant]]
         [:outcomes {:optional true
                     :x-display {:label "Outcomes"
                                 :help "One entry per ended step, in the order they ended."}}
          [:maybe [:vector outcome-schema]]]
         [:stopped_by {:optional true
                       :x-ref {:principal true}
                       :x-display {:raw true
                                   :label "Stopped by"
                                   :help "The principal that last stopped this walkthrough."}}
          [:maybe [:string {:max 128}]]]
         [:stop_reason {:optional true
                        :x-display {:label "Why it stopped"
                                    :help "One sentence from the hand that last stopped it."}}
          [:maybe [:string {:max 240}]]]
         [:walk {:optional true
                 :kind :walk
                 :x-display {:label "Recording"
                             :help "The walk this walkthrough is recorded into, named by the person at the start. Empty when it is not recorded."}}
          [:maybe :waymark/ref]]]))
   ;; everything but the offer is the engine's to write
   :create-schema (into [:map] offer-fields)
   :filterable {:state #{:eq :in}
                :subject #{:eq}
                :author #{:eq}
                :waiting_on #{:eq}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :default-filters {:state "open,running,stopped"}
   :create-guards [the-author-sees-every-step every-field-is-an-open-argument]
   :on-create born
   :actions
   {:start
    {:from #{:open} :to :running
     :input [:map
             [:walk {:optional true
                     :kind :walk
                     :x-display {:label "Record into"
                                 :help "A walk you started that follows the author. The steps and your answers are recorded into it, and it is sealed when the walkthrough ends. Left empty, nothing is recorded."}}
              [:maybe :waymark/ref]]]
     :guards [the-subject-starts the-walk-is-the-subjects-own-recording]
     :handler start-step
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The first step opens and the author is told. Stop pauses it at any step."}
     :display {:label "Start" :order 1
               :description "Agree to be led; the first step opens"}}
    :stop
    {:from #{:running} :to :stopped
     :input [:map
             [:reason {:optional true
                       :examples ["I need to find the paperwork first."]
                       :x-display {:widget "prose"
                                   :label "Why"
                                   :help "One sentence the other side reads on the row."}}
              [:maybe [:string {:min 1 :max 240}]]]]
     :guards [either-side-stops]
     :handler record-stop
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Stop" :order 2
               :description "Pause at this step; resume picks it up again"}}
    :resume
    {:from #{:stopped} :to :running
     :guards [the-persons-stop-is-the-persons-to-lift]
     :handler open-step
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Resume" :order 3
               :description "Open the current step again"}}
    :advance
    {:from #{:running} :to :running
     :guards [the-author-ends-its-own-step]
     :handler end-step
     :safety {:idempotent false :reversible false :confirm false
              :one-way "The agent's step is recorded done and the next one opens."}
     :display {:label "Advance" :order 4
               :description "End the agent's step and open the next"}}
    :withdraw
    {:from #{:open :running :stopped} :to :withdrawn
     :guards [the-author-withdraws]
     :safety {:idempotent true :reversible false :confirm false
              :final "The person no longer sees the steps and may have moved on; reopening would make the record lie. Leading them again is a new walkthrough."}
     :display {:label "Withdraw" :style :danger :order 5
               :description "Take the whole walkthrough back from the person"}}
    ;; the two moves the ENGINE writes
    :step
    {:from #{:running} :to :running
     :input [:map
             [:outcome {:x-display {:hidden true}}
              [:enum "answered" "skipped" "done"]]
             [:by {:optional true :x-display {:hidden true}}
              [:maybe [:string {:min 1 :max 64}]]]]
     :guards [the-engine-moves-it]
     :handler end-step
     :safety {:idempotent false :reversible false :confirm false
              :one-way "The step ended; the transition that ended it is in the log."}
     :display {:label "Stepped"}}
    :finish
    {:from #{:running} :to :finished
     :guards [the-engine-moves-it]
     :safety {:idempotent true :reversible false :confirm false
              :final "Every step has ended; reopening would make the record lie. Leading the person again is a new walkthrough."}
     :display {:label "Finished"}}}
   :deviations
   ["An agent step that names `fields` or `suggest` is refused at create. § 2 gives an agent step neither, and a stored `suggest` would be values nobody judged."]})

;; ── the engine's own hand ───────────────────────────────────────────

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 walkthroughs: " parts))))

(def ^:private sweep-cap
  "The most rows one pass reads."
  500)

(defn- row-of
  "One row of kind `k`, decoded; nil when it is gone."
  [eng k id]
  (let [st (:storage eng)]
    (when-some [rdef (get (inv/resources eng) k)]
      (some->> (store/with-tx st
                 (fn [tx] (store/load-row st tx k (str id) {})))
               (inv/decode-row rdef)))))

(defn- rows-of [eng k where]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) k)]
    (mapv #(inv/decode-row rdef %)
          (store/with-tx st
            (fn [tx]
              (vec (store/query-rows st tx k where {:limit sweep-cap})))))))

(defn- walk!
  "Walk one of the engine's doors on a walkthrough, best effort: a door
  that refuses (the row already left `running`) is a warning, never a
  throw. `heard` names what the engine heard that made it walk, and it
  makes the key: `step` is not idempotent, and the same transition
  heard twice ends one step."
  [eng id action body heard]
  (try
    (inv/invoke! eng kind (str id) action body
                 {:principal engine-actor
                  :idempotency-key (str "wt-" (name action) ":" id ":" heard)})
    (catch Exception e
      (warn! "walkthrough " id " could not be moved by " (name action)
             " (" (ex-message e) ")")
      nil)))

(defn- older?
  "Whether transition `t` was committed before the row's current step
  opened. Such a transition ends nothing: it is the invitation's own
  rule, read against `step_opened_at`."
  [t row]
  (let [at (invitations/instant-of (:at t))
        opened (invitations/instant-of (get-in row [:data :step_opened_at]))]
    (boolean (and at opened (.isBefore at opened)))))

(defn- opening
  "The current step's opening as a short token: the step's number and
  `step_opened_at` to the nanosecond. A resume stamps a new
  `step_opened_at`, so it is a new opening."
  [row]
  (let [at (invitations/instant-of (get-in row [:data :step_opened_at]))]
    (str (get-in row [:data :current]) "@"
         (when at
           (Long/toString (+ (* (.getEpochSecond at) 1000000000)
                             (.getNano at))
                          36)))))

(defn- sentence
  "A refusal as one `stop_reason`: at most the 240 characters the field
  holds, and `fallback` for a refusal that said nothing."
  [s fallback]
  (let [s (str/trim (str s))]
    (cond
      (str/blank? s) fallback
      (> (count s) 240) (str (subs s 0 239) "…")
      :else s)))

;; ── reconcile ───────────────────────────────────────────────────────

(defn- open-invitations
  "The open invitations the engine made for this walkthrough."
  [eng id]
  (rows-of eng invitations/kind {:state :open :walkthrough (str id)}))

(defn- invitation-of
  "The invitation a person step becomes: the step's own fields,
  unchanged, addressed to the walkthrough's subject and numbered."
  [row n step]
  (cond-> {:subject (get-in row [:data :subject])
           :self (:self step)
           :action (:action step)
           :fields (vec (:fields step))
           :note (:note step)
           :walkthrough (str (:id row))
           :step n
           :of (count (get-in row [:data :steps]))}
    (map? (:suggest step)) (assoc :suggest (:suggest step))))

(defn- open-step!
  "Create the current person step's invitation with the engine's hand.
  The key is the walkthrough's id and the opening, so a replay opens no
  second invitation and a resume opens a fresh one. The create meets
  the invitation's own guards: when the row cannot take the door now,
  or the row is gone, the walkthrough is stopped with the refusal's
  sentence, and the author reads why."
  [eng row n step]
  (let [id (str (:id row))
        heard (opening row)]
    (try
      (inv/create! eng invitations/kind (invitation-of row n step)
                   {:principal engine-actor
                    :idempotency-key (str "wt-open:" id ":" heard)})
      (catch Exception e
        (if (some? (ex-data e))
          (walk! eng id :stop
                 {:reason (sentence (inv/problem-reason e)
                                    (str "Step " n " could not open."))}
                 heard)
          ;; no refusal of the engine's: a fault, and the next
          ;; walkthrough transition reconciles again
          (warn! "walkthrough " id " could not open step " n
                 " (" (ex-message e) ")"))
        nil))))

(defn- withdraw!
  "Take one open invitation back with the engine's hand, best effort."
  [eng invitation-id]
  (try
    (inv/invoke! eng invitations/kind (str invitation-id) :withdraw {}
                 {:principal engine-actor})
    (catch Exception e
      (warn! "invitation " invitation-id " could not be withdrawn ("
             (ex-message e) ")")
      nil)))

(defn- seal-walk!
  "Seal the walk this walkthrough was recorded into with the engine's
  hand, best effort (§ 6). It reads first: a walk the person sealed
  themselves, or one the sweep purged, is left as it is."
  [eng row]
  (when-some [id (some-> (get-in row [:data :walk]) str not-empty)]
    (when (= :recording (:state (row-of eng :walk id)))
      (try
        (inv/invoke! eng :walk id :seal {} {:principal engine-actor})
        (catch Exception e
          (warn! "walk " id " could not be sealed (" (ex-message e) ")")
          nil)))))

(defn reconcile!
  "Make the world match the walkthrough row. `running` and past the
  last step: walk `finish`. `running` on a person step with no open
  invitation of this walkthrough for that step: create one. Not
  `running`: withdraw every open invitation of this walkthrough, and
  when it is `finished` or `withdrawn`, seal the walk it was recorded
  into. It reads the row as it is now, not as the transition left it."
  [eng id]
  (when-some [row (row-of eng kind id)]
    (let [n (get-in row [:data :current])
          step (current-step row)
          open (open-invitations eng id)]
      (if (= :running (:state row))
        (cond
          (and (integer? n) (> n (count (get-in row [:data :steps]))))
          (walk! eng id :finish {} "end")

          (and (= "person" (who-of step))
               (not-any? #(= n (get-in % [:data :step])) open))
          (open-step! eng row n step))
        (do
          (doseq [i open]
            (withdraw! eng (:id i)))
          ;; a stop does not seal: a resumed walkthrough keeps recording
          (when (contains? #{:finished :withdrawn} (:state row))
            (seal-walk! eng row))))))
  nil)

;; ── what the consumer hears ─────────────────────────────────────────

(defn- invitation-ended!
  "An ending of the current step's invitation: `answer` and `decline`
  step the walkthrough, `expire` and the author's own `withdraw` stop
  it. The engine's own withdraw stops nothing, and an invitation of a
  step that is no longer current, or of an earlier opening of it, is
  passed over."
  [eng t]
  (when-some [i (row-of eng invitations/kind (:resource-id t))]
    (let [id (some-> (get-in i [:data :walkthrough]) str not-empty)
          n (get-in i [:data :step])
          row (some->> id (row-of eng kind))]
      (when (and row
                 (= :running (:state row))
                 (= n (get-in row [:data :current]))
                 (not (older? t row)))
        (case (keyword (:action t))
          :answer
          (walk! eng id :step
                 (cond-> {:outcome "answered"}
                   (some? (get-in i [:data :answered_by]))
                   (assoc :by (str (get-in i [:data :answered_by]))))
                 (:id t))

          :decline
          (walk! eng id :step {:outcome "skipped" :by (str (:id t))} (:id t))

          :expire
          (walk! eng id :stop
                 {:reason (str "Step " n " waited past its time.")}
                 (:id t))

          :withdraw
          (when (= (some-> (get-in t [:actor :id]) str)
                   (str (get-in row [:data :author])))
            (walk! eng id :stop
                   {:reason (str "The author took step " n " back.")}
                   (:id t)))

          nil)))))

(defn- agent-step-ended!
  "The author's own transition matching the current agent step's
  (self, action), committed after the step opened: the step is done,
  and `by` is the transition's log id."
  [eng t rdef]
  (let [actor (some-> (get-in t [:actor :id]) str not-empty)
        system? (= "system" (some-> (get-in t [:actor :type]) name))]
    (when (and actor (not system?))
      (let [self (str "/api/" (:plural rdef) "/" (:resource-id t))
            action (name (:action t))]
        (doseq [row (rows-of eng kind {:state :running
                                       :author actor
                                       :waiting_on "agent"})
                :let [step (current-step row)]
                :when (and (= self (str/trim (str (:self step))))
                           (= action (str/trim (str (:action step))))
                           (not (older? t row)))]
          (walk! eng (:id row) :step
                 {:outcome "done" :by (str (:id t))}
                 (:id t)))))))

(def consumer-name
  "The durable cursor's name in waymark10_cursors (consumer:walkthroughs)."
  :walkthroughs)

(defn handle-transition!
  "One committed transition. A walkthrough's own is reconciled. An
  invitation's ending moves the walkthrough it belongs to. Any other
  ends the agent step it matches. Never throws: a parked cursor would
  stop every later step from opening."
  [eng t]
  (try
    (let [rs (inv/resources eng)
          k (some-> (:kind t) keyword)
          rdef (get rs k)]
      (when (and rdef (contains? rs kind) (:resource-id t) (:action t))
        (cond
          (= kind k)
          (reconcile! eng (:resource-id t))

          (= invitations/kind k)
          (do (invitation-ended! eng t)
              (agent-step-ended! eng t rdef))

          :else
          (agent-step-ended! eng t rdef))))
    (catch Exception e
      (warn! "transition " (:id t) " could not be handled — " (ex-message e))
      nil))
  nil)

(defn consumer-fn
  "The consumer's function of one transition. Public because a test
  drains it directly (`consumers/drain-consumer!`)."
  [eng]
  (fn [t] (handle-transition! eng t)))

(defn start!
  "Register the durable log consumer that opens each step. opts:
  :dispatcher, :poll-ms, :from-origin?."
  ([eng] (start! eng {}))
  ([eng opts]
   (consumers/register-consumer!
    eng consumer-name (consumer-fn eng)
    (select-keys opts [:dispatcher :poll-ms :from-origin?]))))

(defn stop! [consumer]
  (some-> consumer consumers/stop-consumer!))
