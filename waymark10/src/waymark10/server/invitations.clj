(ns waymark10.server.invitations
  "The invitation (docs/spec-guided-follow.md § 3): an agent points at
  one field of one action on one row and hands that step to a person.

  THE INVITATION IS A ROW, not a door and not an ephemeral frame: it
  must outlive the registries' TTL (a person may answer tomorrow), it
  is audited like every hand-off, and it is addressed to one person
  who must find it again outside the follow view.

  THE CREATE GUARDS JUDGE THE AUTHOR, NEVER THE SUBJECT. The author's
  own grant must see `self` and admit `action`, and every name in
  `fields` (`field` alone is the spelling for a list of one) and every
  key of `suggest` must be a non-secret argument of that action's
  input (docs/spec-walkthrough.md § 4). Whether the SUBJECT may
  take the door is judged where it always is, at the subject's own
  invoke: an invitation to a door the person lacks meets that door's
  refusal, which is the honest answer. What the ROW can take now is
  judged at create, by the envelope's own availability: a door its
  state does not offer is refused with that door's `unavailable`
  reason, so the author learns it before the person meets a page
  with no button on it.

  ANSWER IS NOT A DOOR anybody taps. A durable log consumer (the
  judgments consumer's shape) hears every committed transition, and
  when its actor is an open invitation's subject and its (self,
  action) match, the engine walks `answer` with its own hand and
  stores the transition's log id in `answered_by`. The person submits
  under their own grant; `suggest` is shown and never submitted by
  the engine. An expiry sweep walks `expire` past `expires_at`. A
  transition older than the invitation does not answer it: without
  that, two neighbouring steps on the same (self, action) could both
  be answered by one replayed transition.

  A WALKTHROUGH'S STEP IS AN INVITATION THE ENGINE MAKES
  (docs/spec-walkthrough.md § 3). `walkthrough`, `step` and `of` are
  the engine's alone to write: a create that names one from any other
  hand is refused. Such a row is stamped with the walkthrough's
  author, and `withdraw` admits the engine on it.

  `self` is the row's path, `/api/<plural>/<id>` — the form intents
  and presence already speak. It names a row of ANY kind, and the ref
  forms on main name one kind each, so it is a path rather than a
  typed ref."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Instant)
           (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 invitations: " parts))))

(def kind :invitation)

(def engine-actor
  "The system actor that answers and expires an invitation."
  (t/principal {:id "waymark10-invitations" :type :system
                :display "Invitations"}))

(def default-ttl-seconds
  "An invitation naming no expiry waits a week."
  604800)

(def ^:private sweep-cap
  "The most open invitations one pass reads."
  500)

;; ── the step an invitation points at ────────────────────────────────

(defn parse-self
  "`/api/<plural>/<id>` → {:plural :id}, nil for anything else."
  [s]
  (let [[_ plural id] (re-matches #"/api/([^/?#]+)/([^/?#]+)"
                                  (str/trim (str s)))]
    (when (and plural id (not= "-" plural))
      {:plural plural :id id})))

(defn- step-of
  "The step an invitation's input names, as far as it resolves:
  {:rdef :id :action :door}, :door nil when the kind has no such
  action; nil when `self` names no row path of a served kind."
  [inp ctx]
  (when-some [rdef-of (:rdef-of ctx)]
    (when-some [{:keys [plural id]} (parse-self (:self inp))]
      (when-some [rdef (rdef-of plural)]
        (let [action (keyword (str/trim (str (:action inp))))]
          {:rdef rdef :id id :action action
           :door (get-in rdef [:actions action])})))))

(defn- secret?
  "A secret-marked argument: the engine's own :secret, :x-secret,
  writeOnly, or a password format."
  [props]
  (boolean
   (or (:secret props) (:x-secret props) (:writeOnly props)
       (get-in props [:json-schema :writeOnly])
       (= "password" (some-> (:format props) name))
       (= "password" (some-> (get-in props [:json-schema :format]) name)))))

(defn- arg-name
  "An argument's name as the author wrote it: a string or a keyword."
  [x]
  (str/trim (if (keyword? x) (name x) (str x))))

(defn- named-fields
  "The arguments an invitation points at, in the author's reading
  order: `fields`, or `field` alone as a list of one. A row born before
  `fields` holds only `field` and reads the same way."
  [{:keys [field fields]}]
  (cond
    (sequential? fields) (mapv arg-name fields)
    (some? fields) [(arg-name fields)]
    (some? field) [(arg-name field)]
    :else []))

(defn- map-form
  "The :map form a field's schema holds, through any :maybe; nil when
  the field is no map."
  [form]
  (cond
    (and (vector? form) (= :map (first form))) form
    (and (vector? form) (= :maybe (first form))) (recur (last form))))

(defn- argument-entries
  "The schema entries `arg` walks: one for an argument, and one more for
  each step of a dotted path into a nested map argument
  (showcase.evidence.film_url), as the form names that input. nil when
  a step names nothing."
  [entries arg]
  (loop [entries entries
         [k & more] (str/split arg #"\." -1)
         walked []]
    (when-some [entry (get entries (keyword k))]
      (if (seq more)
        (recur (some-> (map-form (:schema entry)) schema/entry-map)
               more
               (conj walked entry))
        (conj walked entry)))))

(defn- argument-problem
  "Why `arg` cannot be shown to a person as an argument of `action`; nil
  when it can. `where` says where the author named it."
  [entries action where arg]
  (let [walked (argument-entries entries arg)]
    (cond
      (nil? walked)
      (str "`" arg "`" where " is not an argument of `" (name action) "`.")

      (some #(secret? (:properties %)) walked)
      (str "`" arg "`" where " is a secret argument, and nobody is invited to type a secret."))))

(defn- judges-the-input?
  "Does this denier's verdict turn on what the door is handed: does its
  `:judges` name an argument of the action's input?"
  [rdef action denier]
  (let [args (some-> (get-in rdef [:actions (keyword (name action)) :input])
                     schema/entry-map)]
    (boolean (some #(contains? args (if (sequential? %) (first %) %))
                   (:judges denier)))))

(defn- shut-now
  "Why the row cannot take the door now, in the words its envelope's
  `unavailable` uses; nil when it can. A denier reading the principal
  or the grant refused the AUTHOR's hand, not the row, and the
  subject's hand is judged at the subject's own invoke, so it is no
  reason here. A denier judging an argument of the door's input
  refused an input nobody has typed yet: what the person submits is
  judged at the person's own invoke too, so it is no reason either. A
  denier that judges the row's state alone still refuses."
  [rdef action row ctx]
  (let [{:keys [status reason denier]}
        (render/action-availability rdef action row ctx)]
    (when (and (= :unavailable status)
               (not (some #{:principal :grant} (:reads denier)))
               (not (judges-the-input? rdef action denier)))
      (or reason (str "`" (name action) "` is not open on that row now.")))))

(defn subject-name
  "The subject's display name, as its member row labels it; the raw
  id when no member row carries that id."
  [subject ctx]
  (or (some-> (:read ctx)
              (apply [:member (str subject)])
              (get-in [:data :display]))
      (str subject)))

(defn sight-problem
  "Why the author's own grant cannot point at the step `inp` names; nil
  when it can. What the row can take NOW is not judged here: the
  invitation's create adds that, and a walkthrough's step waits for it
  until the step opens (docs/spec-walkthrough.md § 1)."
  [inp ctx]
  (let [{:keys [rdef id action door] :as step} (step-of inp ctx)
        k (:kind rdef)
        p (:principal ctx)
        gr (:grant ctx)]
    (cond
      (nil? step)
      "`self` must be a row's path, /api/<plural>/<id>, of a kind this engine serves."

      (nil? door)
      (str "`" (name action) "` is not a door of " (name k) ".")

      ;; one sentence for all three, so a refusal never says
      ;; whether a row the author cannot see exists
      (or (and (some? gr)
               (not (and ((:action? gr) k action) ((:row? gr) k id))))
          (and (nil? gr) (= :agent (:type p)))
          (nil? ((:read ctx) k id)))
      "your grant does not see that row or does not admit that door.")))

(defn fields-problem
  "Why the arguments `inp` names cannot be shown to a person; nil when
  they can, and nil when the step does not resolve, because
  `sight-problem` says that."
  [inp ctx]
  (let [{:keys [action door]} (step-of inp ctx)
        entries (some-> (:input door) schema/entry-map)
        names (named-fields inp)
        suggest (:suggest inp)]
    (cond
      (nil? door) nil

      (and (some? (:field inp)) (some? (:fields inp)))
      "name `fields`, or `field` alone for a list of one, and not both."

      ;; the engine's own invitation may point at the door alone: a
      ;; quest's step that takes no argument still has a door to light
      (and (empty? names) (not= :system (:type (:principal ctx))))
      "name at least one argument, in `fields` or in `field`."

      :else
      ;; one bad name refuses the whole create, and the refusal names it
      (or (some #(argument-problem entries action "" %) names)
          (when (map? suggest)
            (some #(argument-problem entries action
                                     ", a key of `suggest`,"
                                     (arg-name %))
                  (keys suggest)))))))

;; ── guards ──────────────────────────────────────────────────────────

(g/defguard the-author-sees-the-step
  {:judges [:self :action]
   :reads [:principal :grant :storage]
   :vars [:problem]
   :open "The author's own grant is the law here; no field of this door widens it. Ask for a grant that sees the row and admits the door, then invite."
   :explain "An invitation points at a step its author can see and take: {problem}"}
  [_row inp ctx]
  (if (nil? (:rdef-of ctx))
    ;; no registry in scope (a render probe): the write path carries it
    (t/allow)
    (if-some [problem (sight-problem inp ctx)]
      (t/deny {:vars {:problem problem}})
      (let [{:keys [rdef id action]} (step-of inp ctx)]
        (if-some [why (some->> ((:read ctx) (:kind rdef) id)
                               (#(shut-now rdef action % ctx)))]
          (t/deny {:vars {:problem (str "the row cannot take `" (name action)
                                        "` now: " why)}})
          (t/allow))))))

(g/defguard the-field-is-an-open-argument
  {:judges [:action :field :fields :suggest]
   :reads [:storage]
   :vars [:problem]
   :open "The fields are the action's own input schema; name arguments of it that are not secret, in `fields` or in `field` alone, and suggest values only for such arguments."
   :explain "An invitation points at arguments of the action a person may be shown: {problem}"}
  [_row inp ctx]
  ;; a step that does not resolve: the-author-sees-the-step says so
  (if-some [problem (fields-problem inp ctx)]
    (t/deny {:vars {:problem problem}})
    (t/allow)))

(g/defguard the-subject-declines
  {:reads [:principal]
   :open "The wall is about who: the person invited declines, and no field of this door makes anyone else that person."
   :explain "Only the person this invitation is addressed to declines it."}
  [row _inp ctx]
  (if (= (str (get-in row [:data :subject])) (str (:id (:principal ctx))))
    (t/allow)
    (t/deny)))

(g/defguard the-author-withdraws
  {:reads [:principal]
   :open "The wall is about who: the author withdraws its own invitation, and no field of this door makes anyone else the author."
   :explain "Only the author of this invitation withdraws it."}
  [row _inp ctx]
  ;; the engine too, on a walkthrough's invitation and on no other: it
  ;; takes the step back when the walkthrough leaves `running`
  (if (or (= (str (get-in row [:data :author])) (str (:id (:principal ctx))))
          (and (some? (get-in row [:data :walkthrough]))
               (= :system (:type (:principal ctx)))))
    (t/allow)
    (t/deny)))

(g/defguard only-the-engine-names-a-walkthrough
  ;; it judges `walkthrough`, the reference; `step` and `of` are the
  ;; engine's count beside it and have no vocabulary a form could offer
  {:judges [:walkthrough]
   :reads [:principal]
   :open "The wall is about who: the engine opens a walkthrough's steps, and no field of this door makes anyone else the engine. Leave `walkthrough`, `step` and `of` out, and the invitation stands alone."
   :explain "Only the engine names a walkthrough, a step or a step count on an invitation; it opens each step when the one before it ends."}
  [_row inp ctx]
  (if (and (some some? ((juxt :walkthrough :step :of) inp))
           (not= :system (:type (:principal ctx))))
    (t/deny)
    (t/allow)))

(g/defguard the-engine-resolves-it
  {:reads [:principal]
   :hide true
   :explain "The engine answers an invitation when the person takes the step, and expires it past its moment; no hand at the wire does."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

;; ── handlers ────────────────────────────────────────────────────────

(defn- born
  "The birth stamps: the author is the principal that created the row,
  never the body, `fields` is what the row stores, with `field` its
  first for a reader that knows only that one, and an invitation naming
  no expiry gets the default. An invitation the engine makes for a
  walkthrough is the one exception to the first: it is stamped with
  the walkthrough's author, so the audit still reads that the agent
  asked, and the author can still read it, withdraw it and find it by
  `author`."
  [row ctx]
  (let [names (named-fields (:data row))
        led (some-> (get-in row [:data :walkthrough]) str not-empty)
        leader (when led
                 (some-> (:read ctx)
                         (apply [:walkthrough led])
                         (get-in [:data :author])
                         str
                         not-empty))]
    (-> (cond-> row
          (seq names) (update :data assoc :fields names :field (first names)))
        (assoc-in [:data :author]
                  (or leader (str (get-in ctx [:principal :id]))))
        (assoc-in [:data :subject_name] (subject-name (get-in row [:data :subject]) ctx))
        (update-in [:data :expires_at]
                   #(or % (.plusSeconds ^Instant (:now ctx)
                                        (long default-ttl-seconds)))))))

(defhandler record-answer [row inp _ctx]
  (assoc-in row [:data :answered_by] (:transition inp)))

;; ── the kind ────────────────────────────────────────────────────────

(def ^:private step-fields
  "What the author writes: the create model, and the row's own entries
  after `author`."
  [[:subject {:x-ref {:principal true}
              :x-display {:raw true
                          :label "Who is invited"
                          :help "The person the step is handed to: their principal id. Only their own transition answers it."}}
    [:string {:min 1 :max 128}]]
   [:self {:x-display {:raw true
                       :label "The row"
                       :help "The row the step acts on, as its path: /api/<plural>/<id>."}}
    [:string {:min 1 :max 300}]]
   [:action {:x-display {:raw true
                         :label "The door"
                         :help "The action on that row the person is invited to take."}}
    [:string {:min 1 :max 60}]]
   [:fields {:optional true
             :x-display {:label "The fields"
                         :help "The arguments of that action the person is pointed at, one to eight, in the order they are read. A secret argument is refused."}}
    [:maybe [:vector {:min 1 :max 8} [:string {:min 1 :max 60}]]]]
   [:field {:optional true
            :x-display {:raw true
                        :label "The field"
                        :help "The one-field spelling of `fields`: name this or `fields`, never both. The engine stamps it with the first of `fields`."}}
    [:maybe [:string {:min 1 :max 60}]]]
   [:note {:examples ["Pick the repository this ticket belongs to."]
           :x-display {:widget "prose"
                       :label "Note"
                       :help "One sentence shown beside the field: pick the repository here."}}
    [:string {:min 1 :max 240}]]
   [:suggest {:optional true
              :x-display {:label "Suggested values"
                          :help "Values shown to the person as suggestions. The engine never submits them; only the person's own submit does."
                          :spelled-by-hand "Its keys are the arguments of the invited action, which differ per action, so no fixed sub-form can offer them."}}
    [:maybe [:map-of :keyword :any]]]
   [:expires_at {:optional true
                 :x-display {:label "Waits until"
                             :help "When the sweep expires this invitation. Left empty, the engine stamps a week out."}}
    [:maybe :waymark/instant]]])

(def ^:private led-fields
  "What the ENGINE writes when a walkthrough opens a step
  (docs/spec-walkthrough.md § 3): which walkthrough, and how far along."
  [[:walkthrough {:optional true
                  :kind :walkthrough
                  :x-display {:label "The walkthrough"
                              :help "The walkthrough this step belongs to, written by the engine. Empty on an invitation that stands alone."}}
    [:maybe :waymark/ref]]
   [:step {:optional true
           :x-display {:label "Step"
                       :help "The number of this step in its walkthrough, counted from one. Written by the engine."}}
    [:maybe [:int {:min 1 :max 20}]]]
   [:of {:optional true
         :x-display {:label "Of"
                     :help "How many steps the walkthrough has. Written by the engine."}}
    [:maybe [:int {:min 1 :max 20}]]]])

(defn- off-the-form
  "The same entries, kept off an author's form: the create model must
  hold them for the engine's own create, and no person fills them in."
  [entries]
  (mapv (fn [[k props schema]]
          [k (assoc-in props [:x-display :hidden] true) schema])
        entries))

(defresource invitation
  {:kind :invitation
   :plural "invitations"
   :nav :secondary
   :states [:open :answered :declined :withdrawn :expired]
   :initial :open
   :terminal #{:answered :declined :withdrawn :expired}
   :summary "{data.note} · {data.subject_name} · {state}"
   :label-template "{data.note}"
   :schema
   (-> [:map
        [:author {:x-ref {:principal true}
                  :x-display {:raw true
                              :label "Who invited"
                              :help "The principal that handed the step over, stamped by the engine at birth."}}
         [:string {:min 1 :max 128}]]]
       (into step-fields)
       (into led-fields)
       (conj [:subject_name {:optional true
                             :x-display {:label "Invited by name"
                                         :help "The invited person's name as their member row said it at birth, stamped by the engine."}}
              [:maybe [:string {:max 128}]]])
       (conj [:answered_by {:optional true
                            :not-a-ref "It holds the log id of the transition that answered, and a log entry is no row of any kind."
                            :x-display {:raw true
                                        :label "Answered by"
                                        :help "The log id of the person's own transition that answered this invitation."}}
              [:maybe [:string {:max 64}]]]))
   ;; the author and the answer are the engine's to write
   :create-schema (-> [:map]
                      (into step-fields)
                      (into (off-the-form led-fields)))
   :filterable {:state #{:eq :in}
                :subject #{:eq}
                :author #{:eq}
                :walkthrough #{:eq}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :default-filters {:state "open"}
   :create-guards [only-the-engine-names-a-walkthrough
                   the-author-sees-the-step
                   the-field-is-an-open-argument]
   :on-create born
   :actions
   {:decline
    {:from #{:open} :to :declined
     :guards [the-subject-declines]
     :safety {:idempotent true :reversible false :confirm false
              :final "The author reads that you declined and may act on it; reopening would make the record lie. Handing the step over again is a new invitation."}
     :display {:label "Decline" :style :danger :order 1
               :description "Say no to this step; the author reads it on the row"}}
    :withdraw
    {:from #{:open} :to :withdrawn
     :guards [the-author-withdraws]
     :safety {:idempotent true :reversible false :confirm false
              :final "The person no longer sees the step and may have moved on; reopening would make the record lie. Handing it over again is a new invitation."}
     :display {:label "Withdraw" :order 2
               :description "Take this step back from the person"}}
    ;; the two endings the ENGINE writes
    :answer
    {:from #{:open} :to :answered
     :input [:map
             [:transition {:x-display {:hidden true}}
              [:string {:min 1 :max 64}]]]
     :guards [the-engine-resolves-it]
     :handler record-answer
     :edit {:fence false
            :unfenced-reason "The engine's own record of the person's transition. No read preceded it to fence against."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The person took the step; their transition is in the log."}
     :display {:label "Answered"}}
    :expire
    {:from #{:open} :to :expired
     :guards [the-engine-resolves-it]
     :safety {:idempotent true :reversible false :confirm false
              :final "The clock ended the wait; reopening would make the record lie. Handing the step over again is a new invitation."}
     :display {:label "Expired"}}}})

;; ── the engine's own hand ───────────────────────────────────────────

(defn- walk!
  "Walk one of the engine's endings, best effort: a door that refuses
  (the row already left `open`) is a warning, never a throw."
  [eng row-id action body]
  (try
    (inv/invoke! eng kind (str row-id) action body {:principal engine-actor})
    (catch Exception e
      (warn! "invitation " row-id " could not be moved by " (name action)
             " (" (ex-message e) ")")
      nil)))

(defn- open-rows [eng]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) kind)]
    (mapv #(inv/decode-row rdef %)
          (store/with-tx st
            (fn [tx]
              (vec (store/query-rows st tx kind {:state :open}
                                     {:limit sweep-cap})))))))

;; ── resolution ──────────────────────────────────────────────────────

(def consumer-name
  "The durable cursor's name in waymark10_cursors (consumer:invitations)."
  :invitations)

(defn instant-of
  "An instant, as a stored string or an Instant already; nil for
  anything else."
  ^Instant [v]
  (cond
    (instance? Instant v) v
    (string? v) (try (Instant/parse ^String v) (catch Exception _ nil))
    :else nil))

(defn- older?
  "Whether transition `t` was committed before invitation `row` was
  born. Such a transition does not answer it."
  [t row]
  (let [at (instant-of (:at t))
        birth (instant-of (:created-at row))]
    (boolean (and at birth (.isBefore at birth)))))

(defn handle-transition!
  "One committed transition: every open invitation whose subject is its
  actor and whose (self, action) it matches is answered, with the
  transition's log id. A transition committed before the invitation
  was born answers nothing. Never throws: a parked cursor would stop
  every later answer."
  [eng t]
  (try
    (let [rs (inv/resources eng)
          rdef (get rs (:kind t))
          actor (some-> (get-in t [:actor :id]) str not-empty)]
      (when (and actor rdef (contains? rs kind) (not= kind (:kind t))
                 (:resource-id t) (:action t))
        (let [self (str "/api/" (:plural rdef) "/" (:resource-id t))
              action (name (:action t))]
          (doseq [row (open-rows eng)
                  :when (and (= self (get-in row [:data :self]))
                             (= action (str/trim (str (get-in row [:data :action]))))
                             (= actor (str (get-in row [:data :subject])))
                             (not (older? t row)))]
            (walk! eng (:id row) :answer {:transition (str (:id t))})))))
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
  "Register the durable log consumer that answers invitations. opts:
  :dispatcher, :poll-ms, :from-origin?."
  ([eng] (start! eng {}))
  ([eng opts]
   (consumers/register-consumer!
    eng consumer-name (consumer-fn eng)
    (select-keys opts [:dispatcher :poll-ms :from-origin?]))))

(defn stop! [consumer]
  (some-> consumer consumers/stop-consumer!))

;; ── expiry ──────────────────────────────────────────────────────────

(defn sweep-expired!
  "One pass: every open invitation past its `expires_at`, expired
  through its own door under the engine's hand. → how many moved."
  [eng]
  (if-not (contains? (inv/resources eng) kind)
    0
    (let [^Instant now ((:now-fn eng))]
      (reduce
       (fn [n row]
         (let [at (instant-of (get-in row [:data :expires_at]))]
           (if (and at (.isBefore at now)
                    (walk! eng (:id row) :expire {}))
             (inc n)
             n)))
       0
       (open-rows eng)))))

(defn start-expiry-sweeper!
  "Every `:interval-ms`, `sweep-expired!`. The first pass is one
  interval after the start. Returns the handle
  `stop-expiry-sweeper!` takes."
  [eng {:keys [interval-ms] :or {interval-ms 300000}}]
  (let [stop (CountDownLatch. 1)
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (when-not (.await stop (long interval-ms)
                                         TimeUnit/MILLISECONDS)
                         (try (sweep-expired! eng)
                              (catch Exception e
                                (warn! "the expiry pass failed ("
                                       (ex-message e) ")")))
                         (recur))))
                   "waymark10-invitation-expiry")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-expiry-sweeper! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)
