(ns waymark10.server.film-rules
  "The film rule (demo scorecard, 2026-10-07): one measured rule of a
  good demo film, as a row. A rule names a metric the scorer knows, an
  operator and a threshold, the part of the film it is judged over, how
  hard a miss is, why the rule exists and whose note it came from. Each
  critique a person makes of a film becomes one row, so an agent can
  meet the scorecard without seeing the film.

  The vocabulary is closed: `metrics` names every metric and the field
  of the take it is read from (docs/spec-agent-demo-walks.md § 8d). A
  rule naming any other metric is refused with the list. `measure` reads
  one metric from a take, by those field names. `judge` scores a whole
  take against the rules and answers each rule's verdict; the kind
  declares it as the collection door `judge`, so the router serves it at
  POST /api/film_rules/-/judge, and it writes nothing.

  A person or the sitter of a domain's mayor seat makes a rule, and
  whoever may make one may restate its `output` and `unless`. Only a
  person retires one.

  `ensure-seed-rules!` is the boot seed: the fifteen rules the scorecard
  starts with, each made once by its name. The engine's own start runs
  it (`engine/start-runtime!`), so every application that enrolls the
  kind has them. A seed row made before `output` and `unless` existed
  gains them there, through `restate`. A restate by anyone else writes
  `restated_by`, and the seed leaves a row that carries it alone."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def kind :film_rule)

;; ── the vocabulary ──────────────────────────────────────────────────

(def metrics
  "Each metric the scorer knows, in the order the spec lists them, with
  the field of the take it is read from."
  [["frame_fill" "film.content_box over film.frame"]
   ["focus_share" "shot.focus_box over shot.viewport"]
   ["type_px" "shot.focus_type_px"]
   ["contrast" "shot.focus_contrast"]
   ["read_time_ratio" "shot.hold_s over shot.words / 3"]
   ["dead_air_s" "film.dead_air_s"]
   ["surfaces_changed" "shot.surfaces_changed"]
   ["chrome_leaks" "film.chrome_leaks"]
   ["arc" "shot.role in order, and the last shot's goal_state"]
   ["runtime_s" "film.runtime_s"]
   ["frame_match_min" "the lowest run.frame_match"]
   ["bare_lists" "the day captions (a shot.caption that opens with a weekday) that list three or more things and say no verb"]
   ["quiet_days" "the day captions that end in 'A quiet day.'"]
   ["asked_person" "the day captions that say 'was asked'"]
   ["week_bare_lists" "the day captions of a first week that are bare lists, as bare_lists counts them"]
   ["busy_days" "the day captions of a first week that do not end in 'A quiet day.'"]
   ["arc_moves" "the arc's roles whose day holds a shot.meanwhile move on a kind other than film.kind"]
   ["person_asks" "the shot.meanwhile calls held for a person, or moved by film.cast's first through a door a person holds"]])

(def metric-names (mapv first metrics))

(def ops [">=" "<=" "="])
(def scopes ["film" "shot" "beat"])
(def roles ["friction" "turn" "payoff"])
(def severities ["fail" "warn"])
(def outputs ["phone" "desktop"])

(def exemptions
  "Each shot property that exempts a shot from a rule, with the field of
  the take it is read from."
  [["zoom" "shot.zoom"]])

(def exemption-names (mapv first exemptions))

(def seed-actor-id
  "The id of the actor the boot seed wears."
  "waymark10-film-rules")

(defn- asked
  "The value a create names for field `k`, or nil."
  [row inp k]
  (some-> (or (get inp k) (get-in row [:data k])) str not-empty))

;; ── the take ────────────────────────────────────────────────────────

;; A take is what the scorer hands over for one film: `:film`, a map of
;; the fields read once, `:shots`, one map per shot in order, and
;; `:runs`, one map per caption run in order. The field names are
;; § 8d's. A box is {:x :y :w :h}; a frame and a viewport are {:w :h}.

(defn- area
  "The area of a box, a frame or a viewport, or nil."
  [{:keys [w h]}]
  (when (and (number? w) (number? h)) (* w h)))

(defn- over
  "`a` over `b`, or nil when either is missing or `b` is not positive.
  The division is in doubles: a take's decimal is read as a BigDecimal,
  and two of them may have no exact quotient."
  [a b]
  (when (and (number? a) (number? b) (pos? b))
    (let [q (/ (double a) (double b))]
      (when (Double/isFinite q) q))))

(defn- word
  "A role, a state or an enum field as a string, or nil: a take and a
  body spell one as a string, and a row gives an enum back as a keyword."
  [v]
  (when (or (string? v) (keyword? v)) (not-empty (name v))))

(defn- counted
  "A count, from a number or from the list of the things counted."
  [v]
  (if (coll? v) (count v) v))

(defn- arc
  "1 when the shot roles run friction, turn, payoff and the last shot's
  goal state is done; else 0. A shot with no role (a camera, a hold) is
  not read, and a meanwhile shot is a call, not a shot the camera holds,
  so the arc does not read it either."
  [shots]
  (let [shots (remove :meanwhile shots)]
    (if (and (= roles (vec (dedupe (keep (comp word :role) shots))))
             (= "done" (word (:goal_state (last shots)))))
      1
      0)))

;; The first week (FS2) draws one shot per day, its caption the weekday
;; and the `$day` phrasing of what happened (scenes): `Monday. Acme's
;; study went to review.`, `A quiet day.` for a day with nothing to
;; tell, `a person was asked to ...` for a held call or an approval.

(def ^:private weekday
  #"^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\b")

(def ^:private day-verb
  "A verb of the `$day` phrasing: a caption that says one tells what
  happened, not only what there was."
  #"(?i)\b(went|was|were|came|chased|signed|sent|asked|paid)\b")

(defn- day-captions
  "The captions of `shots` that open with a weekday name, in order."
  [shots]
  (filterv #(re-find weekday %) (filter string? (map :caption shots))))

(defn- bare-list?
  "True when `caption` lists three or more things, by ', ', and says no
  verb of the `$day` phrasing."
  [caption]
  (and (<= 3 (count (str/split caption #", ")))
       (not (re-find day-verb caption))))

(defn- days-that
  "How many of the day captions of the take `tk` hold `pred`."
  [pred tk]
  (count (filter pred (day-captions (:shots tk)))))

(def ^:private week-metrics
  "The metrics read from the first week's day captions. They judge only
  a take `first-week?` holds."
  #{"bare_lists" "quiet_days" "asked_person"})

(defn- first-week?
  "True when the take `tk` is a first week (ticket 78da0888): its scene
  says so, `:scene {:first_week true}`, or a shot of it carries one of
  the arc's roles."
  [{:keys [scene shots]}]
  (boolean (or (and (map? scene) (true? (:first_week scene)))
               (some (set roles) (map (comp word :role) shots)))))

;; A played first week (ticket fb9a1cff) carries the director's calls
;; as meanwhile shots, each with what it did: `{:meanwhile {:as :kind
;; :action :outcome :held_by}}`. `outcome` is `moved`, `held` (a held
;; call, waiting on a person's tap) or `refused`, and `held_by` is who
;; holds the door the call named. A day caption's day is the calls since
;; the day caption before it. `film.kind` is the kind the world knocks
;; on, the opening's, and `film.cast` the scene's cast, the person first.

(def ^:private week-days
  "The day captions of a whole first week."
  7)

(def ^:private played-metrics
  "The metrics read from a first week's captions and its meanwhile
  calls. They judge a take `first-week?` holds, when a meanwhile shot
  played its week or it has a whole week of day captions."
  #{"week_bare_lists" "busy_days" "arc_moves" "person_asks"})

(defn- week?
  "True when the take `tk` is a first week `played-metrics` judge."
  [{:keys [shots] :as tk}]
  (and (first-week? tk)
       (boolean (or (some :meanwhile shots)
                    (<= week-days (count (day-captions shots)))))))

(defn- clock-played?
  "True when the take `tk` is a week the clone's clock played before the
  film (ticket a37c3249): its scene says so, `:scene {:clock_days true}`,
  and no meanwhile shot is in it. No call of the week is in the take, so
  its day captions tell the moves."
  [{:keys [scene shots]}]
  (boolean (and (map? scene) (true? (:clock_days scene))
                (not-any? :meanwhile shots))))

(defn- day-caption?
  [shot]
  (let [c (:caption shot)]
    (boolean (and (string? c) (re-find weekday c)))))

(defn- role-days
  "Each shot of `shots` that carries a role, as [role calls]: the
  meanwhile calls of its day. A day caption's day is the calls since the
  day caption before it; a shot that is no day caption, as the closing,
  reads the last day before it and the calls since."
  [shots]
  (loop [s (seq shots) pending [] last-day [] out []]
    (if-let [shot (first s)]
      (let [role (word (:role shot))]
        (cond
          (:meanwhile shot) (recur (next s) (conj pending (:meanwhile shot)) last-day out)
          (day-caption? shot) (recur (next s) [] pending
                                     (cond-> out role (conj [role pending])))
          :else (recur (next s) pending last-day
                       (cond-> out role (conj [role (into last-day pending)])))))
      out)))

(def ^:private moved
  "The outcomes of a call that tell a move: a held call is the house
  moving to the person."
  #{"moved" "held"})

(defn- arc-moves
  "How many of the arc's roles have a shot whose day holds a call that
  moved, or was held, on a kind other than the knocked one."
  [{:keys [film shots]}]
  (let [knocked (word (:kind film))
        off? (fn [m] (and (moved (word (:outcome m)))
                          (some? (word (:kind m)))
                          (not= knocked (word (:kind m)))))
        told (set (keep (fn [[role calls]] (when (some off? calls) role))
                        (role-days shots)))]
    (count (filter told roles))))

(defn- person-asks
  "How many meanwhile calls of the take were held for a person, or were
  the person's own move (film.cast's first) through a door a person
  holds."
  [{:keys [film shots]}]
  (let [cast (:cast film)
        person (when (sequential? cast) (word (first cast)))]
    (count (filter (fn [m]
                     (or (= "held" (word (:outcome m)))
                         (and (some? person)
                              (= person (word (:as m)))
                              (= "moved" (word (:outcome m)))
                              (= "person" (word (:held_by m))))))
                   (keep :meanwhile shots)))))

(defn- told-arc
  "How many of the arc's roles have a shot whose day caption tells a
  move, not `A quiet day.`, for a week the clone's clock played: a
  role's day is its own caption when it is a day caption, else the last
  day caption before it, as `role-days` reads it."
  [{:keys [shots]}]
  (let [told (loop [s (seq shots) day nil out #{}]
               (if-let [shot (first s)]
                 (let [day (if (day-caption? shot) (:caption shot) day)
                       role (word (:role shot))]
                   (recur (next s) day
                          (cond-> out
                            (and role day (not (str/ends-with? day "A quiet day.")))
                            (conj role))))
                 out))]
    (count (filter told roles))))

(def ^:private film-readers
  "Metric → how it is read from the whole take."
  {"frame_fill" (fn [{:keys [film]}]
                  (over (area (:content_box film)) (area (:frame film))))
   "dead_air_s" (fn [tk] (get-in tk [:film :dead_air_s]))
   "chrome_leaks" (fn [tk] (get-in tk [:film :chrome_leaks]))
   "arc" (fn [tk] (arc (:shots tk)))
   "runtime_s" (fn [tk] (get-in tk [:film :runtime_s]))
   "bare_lists" (partial days-that bare-list?)
   "quiet_days" (partial days-that #(str/ends-with? % "A quiet day."))
   "asked_person" (partial days-that #(str/includes? % "was asked"))
   "week_bare_lists" (partial days-that bare-list?)
   "busy_days" (partial days-that #(not (str/ends-with? % "A quiet day.")))
   "arc_moves" #(if (clock-played? %) (told-arc %) (arc-moves %))
   "person_asks" #(if (clock-played? %)
                    (days-that (fn [c] (str/includes? c "was asked")) %)
                    (person-asks %))})

(def ^:private shot-readers
  "Metric → how it is read from one shot of the take."
  {"focus_share" (fn [shot]
                   (over (area (:focus_box shot)) (area (:viewport shot))))
   "type_px" :focus_type_px
   "contrast" :focus_contrast
   "read_time_ratio" (fn [{:keys [hold_s words]}]
                       (when (number? words)
                         (over hold_s (/ (double words) 3))))
   "surfaces_changed" (comp counted :surfaces_changed)})

(defn- lowest
  "The lowest of the numbers in `vs`, or nil when it holds none."
  [vs]
  (when-some [ns' (seq (filter number? vs))]
    (apply min ns')))

(defn- frame-match
  "The lowest frame_match score of one run. A run carries one score, or
  the scores by frame (held and mid-span), and each one counts."
  [run]
  (let [v (:frame_match run)]
    (lowest (cond (map? v) (vals v)
                  (coll? v) v
                  :else [v]))))

(def ^:private run-readers
  "Metric → how it is read from one run of the take. The metric is the
  lowest of these over the runs."
  {"frame_match_min" frame-match})

(defn- runs-of
  "The runs of the take `tk`, in order."
  [tk]
  (let [runs (:runs tk)]
    (if (sequential? runs) runs [])))

(defn measure
  "The value of `metric` read from the take `tk`: one number for a
  metric read from the film, a vector with one number per shot, in
  order, for a metric read from each shot, and the lowest number over
  the runs for a metric read from each run. nil stands where the take
  lacks the field, and for a metric outside the vocabulary."
  [tk metric]
  (if-some [read (film-readers metric)]
    (read tk)
    (if-some [read (shot-readers metric)]
      (mapv read (:shots tk))
      (when-some [read (run-readers metric)]
        (lowest (map read (runs-of tk)))))))

;; ── the guards ──────────────────────────────────────────────────────

(g/defguard name-is-a-slug
  {:reads []
   :open "Name the rule in lower-case letters, digits and hyphens, starting with a letter: frame-fill."
   :explain "A rule's name is a slug: lower-case letters, digits and hyphens, starting with a letter."}
  [row inp _ctx]
  (let [n (asked row inp :name)]
    (if (or (nil? n) (re-matches #"[a-z][a-z0-9]*(-[a-z0-9]+)*" n))
      (t/allow)
      (t/deny))))

(g/defguard metric-is-in-the-vocabulary
  {:reads []
   :vars [:metric :vocabulary]
   :open "Name one of the metrics the scorer knows: {vocabulary}."
   :explain "The scorer knows no metric named {metric}. The metrics it knows are: {vocabulary}."}
  [row inp _ctx]
  (let [m (asked row inp :metric)]
    (if (or (nil? m) (some #{m} metric-names))
      (t/allow)
      (t/deny {:vars {:metric m
                      :vocabulary (str/join ", " metric-names)}}))))

(g/defguard role-goes-with-a-shot
  {:reads []
   :open "Leave the role empty, or give the rule the scope shot."
   :explain "Only a shot has a role, so a rule names a role only when its scope is shot."}
  [row inp _ctx]
  (if (and (asked row inp :role)
           (not= "shot" (asked row inp :scope)))
    (t/deny)
    (t/allow)))

(g/defguard unless-goes-with-a-shot
  {:reads []
   :open "Leave the exemption empty, or give the rule the scope shot."
   :explain "An exemption names a property of a shot, so a rule names one only when its scope is shot."}
  [row inp _ctx]
  (if (and (asked row inp :unless)
           (not= "shot" (asked row inp :scope)))
    (t/deny)
    (t/allow)))

(defn- makes?
  "Whether the caller of `ctx` may make a film rule: a person, the boot
  seed, or the sitter of a domain's mayor seat."
  [ctx]
  (let [{:keys [type acts-for]} (:principal ctx)
        find' (:find ctx)
        cited (delegation/cited-seats ctx)]
    (boolean
     (or
      ;; a person, a tool a person is signed in to, or the boot seed
      (not= :agent type) (some? (not-empty (str acts-for)))
      (nil? find')                       ; probe ctx — decline to guess
      (some #(seq (find' :domain {:mayor (str %) :state "active"} {:limit 1}))
            cited)))))

(g/defguard a-person-or-a-mayor-makes-the-rule
  {:reads [:principal :now :grant :domain]
   :open "No door changes who the caller is: ask a person or a domain's mayor to make the rule."
   :explain "A film rule is made by a person, or by the sitter of a domain's mayor seat. Ask a person or a mayor to make the rule."}
  [_row _inp ctx]
  (if (makes? ctx) (t/allow) (t/deny)))

(g/defguard a-person-retires-the-rule
  {:reads [:principal]
   :open "No door changes who the caller is: ask a person to retire the rule."
   :explain "A film rule is a person's taste written down, so only a person retires one — in person, or through a tool the person is signed in to."}
  [_row _inp ctx]
  ;; seats/a-person's rule: not a bare agent, and not the engine either
  (let [{:keys [type acts-for]} (:principal ctx)]
    (if (or (= :human type)
            (and (= :agent type) (not (str/blank? (str acts-for)))))
      (t/allow)
      (t/deny))))

(g/defguard a-person-or-a-mayor-restates-the-rule
  {:reads [:principal :now :grant :domain]
   :open "No door changes who the caller is: ask a person or a domain's mayor to restate the rule."
   :explain "Whoever may make a film rule may restate one: a person, or the sitter of a domain's mayor seat. Ask a person or a mayor to restate the rule."}
  [_row _inp ctx]
  (if (makes? ctx) (t/allow) (t/deny)))

;; ── the handler ─────────────────────────────────────────────────────

(defn- seed?
  "Whether `principal` is the actor the boot seed wears."
  [{:keys [id type]}]
  (and (= :system type) (= seed-actor-id (str id))))

(defhandler restate-rule
  [row inp ctx]
  ;; a patch: a field the input leaves out keeps its stored value
  (let [principal (:principal ctx)]
    (cond-> (update row :data merge (select-keys inp [:output :unless]))
      ;; the seed's own restate leaves no mark, so the mark means
      ;; someone chose these values and the seed leaves them alone
      (not (seed? principal))
      (assoc-in [:data :restated_by] (str (:id principal))))))

;; ── the fields a restate writes ─────────────────────────────────────

(def ^:private output-field
  [:output {:optional true
            :x-display
            {:label "Only films at this output"
             :help "Judge only a film rendered at this output. Left empty, a film at any output is judged."
             :choices {"phone" "A film rendered for a phone."
                       "desktop" "A film rendered for a desktop."}}}
   [:maybe (into [:enum] outputs)]])

(def ^:private unless-field
  [:unless {:optional true
            :x-display
            {:label "Unless the shot"
             :help "With the scope shot: a shot that has this property passes the rule whatever it measures. Left empty, no shot is exempt."
             :choices {"zoom" "The shot asks for a zoom."}}}
   [:maybe (into [:enum] exemption-names)]])

;; ── the kind ────────────────────────────────────────────────────────

(def ^:private made-fields
  "The fields a create names: the kind's schema less what only `restate`
  writes."
  [[:name {:examples ["frame-fill"]
           :x-display
           {:raw true
            :label "Rule name"
            :help "One short slug, spelled once: no other rule may carry it."}}
    [:string {:min 1 :max 64}]]
   [:metric {:examples ["frame_fill"]
             :x-display
             {:raw true
              :label "Metric"
              :help "What the scorer measures: frame_fill, focus_share, type_px, contrast, read_time_ratio, dead_air_s, surfaces_changed, chrome_leaks, arc, runtime_s or frame_match_min."}}
    [:string {:min 1 :max 64}]]
   [:op {:x-display {:label "Compared how"
                     :help "How the measured value is held against the threshold."
                     :choices {">=" "At least the threshold."
                               "<=" "At most the threshold."
                               "=" "Exactly the threshold."}}}
    (into [:enum] ops)]
   [:threshold {:examples [0.95M]
                :x-display
                {:label "Threshold"
                 :help "The number the measured value is held against. For arc it is 1: the roles are in order and the last shot's goal is done."}}
    [:decimal {:min 0 :max 100000}]]
   [:scope {:x-display
            {:label "Judged over"
             :help "The whole film, each shot, or each beat."
             :choices {"film" "The whole film, measured once."
                       "shot" "Each shot on its own."
                       "beat" "Each beat on its own."}}}
    (into [:enum] scopes)]
   [:role {:optional true
           :x-display
           {:label "Only shots of this role"
            :help "With the scope shot: judge only the shots of this role. Left empty, every shot is judged."
            :choices {"friction" "A shot that shows what is hard."
                      "turn" "A shot where the thing changes."
                      "payoff" "A shot that shows it done."}}}
    [:maybe (into [:enum] roles)]]
   output-field
   unless-field
   [:severity {:x-display
               {:label "A miss is"
                :help "fail stops the film from being sent; warn is reported beside it."
                :choices {"fail" "The film is not sent."
                          "warn" "The miss is reported beside the film."}}}
    (into [:enum] severities)]
   [:unmeasured {:optional true
                 :x-display
                 {:label "A take it cannot measure"
                  :help "What the rule counts when the take lacks a field its metric needs. Left empty, a fail rule counts it a miss and a warn rule a pass."
                  :choices {"miss" "The film missed the rule."
                            "pass" "The film passed the rule."}}}
    [:maybe [:enum "miss" "pass"]]]
   [:why {:examples ["A film that leaves most of the frame grey reads as broken."]
          :x-display {:label "Why"
                      :help "One sentence: what a film that misses this rule gets wrong."}}
    [:string {:min 1 :max 480}]]
   [:origin {:examples ["craft"]
             :x-display
             {:label "Whose note"
              :help "Who asked for the rule: a person's own words, quoted, or the word craft."}}
    [:string {:min 1 :max 480}]]])

;; written by `restate`, never by the boot seed's own
(def ^:private restated-by-field
  [:restated_by {:optional true
                 :x-ref {:principal true}
                 :x-display
                 {:raw true
                  :label "Who restated it"
                  :help "Who last restated the rule's output or exemption. The boot seed leaves a rule that carries this alone."}}
   [:maybe [:string {:max 128}]]])

(declare judge-take)

(defresource film-rule
  {:kind :film_rule
   :plural "film_rules"
   :nav :secondary
   :states [:active :retired]
   :initial :active
   :terminal #{}
   :summary "{data.name} · {data.metric} {data.op} {data.threshold} · {data.severity}"
   :label-template "{data.name}"
   :unique [[:name]]
   :schema (conj (into [:map] made-fields) restated-by-field)
   ;; the create model omits `restated_by`: a create that names it is
   ;; refused at the schema, and the create form does not show it
   :no-own-surface "restated_by is written by the restate door, not at birth; a rule is the house's and nobody's own row."
   :create-schema (into [:map] made-fields)
   :filterable {:state #{:eq :in}
                :name #{:eq}
                :metric #{:eq :in}
                :scope #{:eq}
                :severity #{:eq}}
   :sortable {:fields [:name] :default "name"}
   ;; the judging door (spec-agent-demo-walks § 8d): the take in the
   ;; field names `metrics` reads. Every field is optional, because a
   ;; take that lacks one leaves its rule unmeasured and is not refused
   :collection-doors
   {:judge
    {:summary "Score a take against the active rules. It reads the rules and writes nothing."
     :handler (fn [eng body ctx]
                (judge-take eng (:take body) (:row? (:visibility ctx))))
     :input
     [:map
      [:take
       [:map
        [:film {:optional true}
         [:map
          [:content_box {:optional true}
           [:map [:x :double] [:y :double] [:w :double] [:h :double]]]
          [:frame {:optional true} [:map [:w :double] [:h :double]]]
          [:dead_air_s {:optional true} :double]
          [:chrome_leaks {:optional true} :int]
          [:runtime_s {:optional true} :double]
          [:output {:optional true} (into [:enum] outputs)]
          ;; the knocked kind, and the scene's cast, the person first
          [:kind {:optional true} :string]
          [:cast {:optional true} [:vector :string]]]]
        ;; the scene filmed: `first_week` marks a first week's take
        [:scene {:optional true}
         [:map [:first_week {:optional true} :boolean]]]
        [:shots {:optional true}
         [:vector
          [:map
           [:caption {:optional true} :string]
           [:role {:optional true} (into [:enum] roles)]
           [:goal_state {:optional true} :string]
           [:zoom {:optional true} :boolean]
           [:focus_box {:optional true}
            [:map [:x :double] [:y :double] [:w :double] [:h :double]]]
           [:viewport {:optional true} [:map [:w :double] [:h :double]]]
           [:focus_type_px {:optional true} :double]
           [:focus_contrast {:optional true} :double]
           [:hold_s {:optional true} :double]
           [:words {:optional true} :int]
           ;; a count, or the list of names
           [:surfaces_changed {:optional true} :any]
           ;; a call made while the camera stayed, and what it did
           [:meanwhile {:optional true}
            [:map
             [:as {:optional true} :string]
             [:kind {:optional true} :string]
             [:action {:optional true} :string]
             [:outcome {:optional true} [:enum "moved" "held" "refused"]]
             [:held_by {:optional true} :string]]]]]]
        [:runs {:optional true}
         [:vector
          [:map
           [:caption {:optional true} :string]
           ;; one score, or the scores by frame (held, mid)
           [:frame_match {:optional true} :any]]]]]]]}}
   :create-guards [name-is-a-slug
                   metric-is-in-the-vocabulary
                   role-goes-with-a-shot
                   unless-goes-with-a-shot
                   a-person-or-a-mayor-makes-the-rule]
   :actions
   {;; whoever may make a rule may restate it, and the boot seed walks
    ;; this door too: the handler writes `restated_by` for everyone but
    ;; the seed, so the seed can tell a chosen value from a missing one
    :restate
    {:from #{:active} :to :active
     :input [:map output-field unless-field]
     :guards [unless-goes-with-a-shot a-person-or-a-mayor-restates-the-rule]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The rule holds what this restate says; the values before are not kept."}
     :handler restate-rule
     :display {:label "Restate the rule" :order 5
               :description "Change the output the rule is for, or the shots it exempts"}}

    :retire
    {:from #{:active} :to :retired :undo :restore
     :guards [a-person-retires-the-rule]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Retire" :style :danger :order 9
               :description "Stop scoring films against this rule"}}
    ;; retiring writes nothing but the state, so coming back is free:
    ;; the same person who may retire a rule may restore it
    :restore
    {:from #{:retired} :to :active :undo :retire
     :guards [a-person-retires-the-rule]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Restore" :order 1
               :description "Score films against this rule again"}}}})

;; ── the judgment ────────────────────────────────────────────────────

(defn judges?
  "True when `rule` (a rule's data) is scored on this part of a take. A
  rule that names an output is scored only on a film rendered at it, and
  a rule that names a role only on a shot of it. `film` is the take's
  film; `shot` is one of its shots, nil for a rule whose scope is film."
  [rule film shot]
  (let [output (word (:output rule))
        role (word (:role rule))]
    (and (or (nil? output) (= output (word (:output film))))
         (or (nil? role) (= role (word (:role shot)))))))

(defn exempt?
  "True when `shot` has the property `rule` names in `unless`."
  [rule shot]
  (let [u (word (:unless rule))]
    (boolean (and u (true? (get shot (keyword u)))))))

(defn verdict
  "How `value`, the rule's metric measured on this part of a take,
  stands against `rule`: :unscored when the rule is not judged here,
  :pass when the shot is exempt or the value holds, else :miss."
  [rule film shot value]
  (let [threshold (:threshold rule)]
    (cond
      (not (judges? rule film shot)) :unscored
      (exempt? rule shot) :pass
      (case (word (:op rule))
        ">=" (>= value threshold)
        "<=" (<= value threshold)
        "=" (== value threshold)) :pass
      :else :miss)))

;; ── the judging of a take ───────────────────────────────────────────

(defn- sized? [m] (some? (area m)))

(defn- roomy?
  "True when `m` has an area above zero: a metric divides by it."
  [m]
  (boolean (some-> (area m) pos?)))

(def ^:private day-needs
  [["shot.caption" #(some? (seq (day-captions (:shots %))))]])

(defn- clock-captioned?
  "True unless the take `tk` is a week the clock played with no day
  caption: such a week's calls are read from its captions."
  [tk]
  (or (not (clock-played? tk)) (some? (seq (day-captions (:shots tk))))))

(def ^:private film-needs
  "Metric → the fields of the take it is read from, each with how to
  tell that the take carries it."
  {"frame_fill" [["film.content_box" #(sized? (get-in % [:film :content_box]))]
                 ["film.frame" #(roomy? (get-in % [:film :frame]))]]
   "dead_air_s" [["film.dead_air_s" #(number? (get-in % [:film :dead_air_s]))]]
   "chrome_leaks" [["film.chrome_leaks"
                    #(number? (get-in % [:film :chrome_leaks]))]]
   "arc" [["shots" #(some? (seq (:shots %)))]
          ["shot.role" #(or (empty? (:shots %))
                           (some (comp word :role) (:shots %)))]
          ["shot.goal_state" #(or (empty? (:shots %))
                                 (word (:goal_state (last (:shots %)))))]]
   "runtime_s" [["film.runtime_s" #(number? (get-in % [:film :runtime_s]))]]
   "bare_lists" day-needs
   "quiet_days" day-needs
   "asked_person" day-needs
   "week_bare_lists" day-needs
   "busy_days" day-needs
   "arc_moves" [["film.kind" #(or (clock-played? %)
                                  (some? (word (get-in % [:film :kind]))))]
                ["shot.caption" clock-captioned?]]
   "person_asks" [["shot.caption" clock-captioned?]]})

(def ^:private shot-needs
  "Metric → the fields of one shot it is read from, each with how to
  tell that the shot carries it."
  {"focus_share" [["shot.focus_box" #(sized? (:focus_box %))]
                  ["shot.viewport" #(roomy? (:viewport %))]]
   "type_px" [["shot.focus_type_px" #(number? (:focus_type_px %))]]
   "contrast" [["shot.focus_contrast" #(number? (:focus_contrast %))]]
   "read_time_ratio" [["shot.hold_s" #(number? (:hold_s %))]
                      ;; the ratio divides by the words, so none is no measure
                      ["shot.words" #(and (number? (:words %))
                                         (pos? (:words %)))]]
   "surfaces_changed" [["shot.surfaces_changed"
                        #(number? (counted (:surfaces_changed %)))]]})

(defn- lacking
  "The names of the `needs` that `part` of the take does not carry."
  [needs part]
  (vec (keep (fn [[field has?]] (when-not (has? part) field)) needs)))

(defn- badness
  "How far `value` stands on the wrong side of the rule's threshold."
  [rule value]
  (let [threshold (:threshold rule)]
    (case (word (:op rule))
      ">=" (- threshold value)
      "<=" (- value threshold)
      "=" (Math/abs (double (- value threshold))))))

(defn- judge-shot
  "One shot against a rule read from each shot: its index, its caption,
  its verdict, and the value or the fields the shot lacks."
  [rule film index shot value]
  (merge
   {:index index :caption (:caption shot)}
   (cond
     ;; a meanwhile shot is a call: the camera holds no frame of it
     (:meanwhile shot) {:verdict :unscored}
     (not (judges? rule film shot)) {:verdict :unscored}
     (exempt? rule shot) {:verdict :pass}
     :else
     (let [needs (shot-needs (word (:metric rule)))
           missing (lacking needs shot)]
       (cond
         (seq missing) {:verdict :unmeasured :missing missing}
         ;; every field is there and still no number: the shot is not
         ;; measured, as a film is not in judge-film
         (not (number? value)) {:verdict :unmeasured
                                :missing (mapv first needs)}
         :else {:verdict (verdict rule film shot value) :value value})))))

(defn- judge-shots
  "A rule read from each shot: a miss when any shot misses, else
  unmeasured when any shot lacks a field, else a pass when any shot
  passes. `worst` is the shot that misses by the most, or, when none
  misses, the measured shot nearest to a miss."
  [rule {:keys [film shots] :as tk}]
  (if (empty? shots)
    {:verdict "unmeasured" :missing ["shots"]}
    (let [judged (map (fn [index shot value]
                        (judge-shot rule film index shot value))
                      (range) shots (measure tk (word (:metric rule))))
          of (fn [v] (filter #(= v (:verdict %)) judged))
          misses (of :miss)
          passes (of :pass)
          missing (vec (distinct (mapcat :missing judged)))
          measured (filter #(number? (:value %))
                           (if (seq misses) misses passes))
          worst (when (seq measured)
                  (apply max-key #(badness rule (:value %)) measured))]
      (cond-> {:verdict (cond (seq misses) "miss"
                              (seq missing) "unmeasured"
                              (seq passes) "pass"
                              :else "unscored")}
        (seq missing) (assoc :missing missing)
        worst (assoc :worst (select-keys worst [:index :caption :value]))))))

(defn- judge-film
  "A rule read from the film once."
  [rule {:keys [film] :as tk}]
  (let [metric (word (:metric rule))
        needs (film-needs metric)
        missing (lacking needs tk)
        value (measure tk metric)]
    (cond
      (seq missing) {:verdict "unmeasured" :missing missing}
      (not (number? value)) {:verdict "unmeasured" :missing (mapv first needs)}
      :else {:verdict (name (verdict rule film nil value)) :value value})))

(def ^:private run-needs
  "Metric → the field of one run it is read from."
  {"frame_match_min" "run.frame_match"})

(defn- judge-runs
  "A rule read from each run: the lowest value over the runs is held
  against the threshold. A miss when it misses, else unmeasured when
  the take has no run or any run lacks the field, else a pass. `worst`
  is the run that carries the lowest value."
  [rule {:keys [film] :as tk}]
  (let [metric (word (:metric rule))
        read (run-readers metric)
        runs (runs-of tk)
        judged (map-indexed (fn [index run]
                              {:index index
                               :caption (:caption run)
                               :value (read run)})
                            runs)
        measured (filter #(number? (:value %)) judged)
        worst (when (seq measured) (apply min-key :value measured))
        miss? (and worst (= :miss (verdict rule film nil (:value worst))))]
    (cond-> (cond
              miss? {:verdict "miss"}
              (empty? runs) {:verdict "unmeasured" :missing ["runs"]}
              (< (count measured) (count runs))
              {:verdict "unmeasured" :missing [(run-needs metric)]}
              :else {:verdict "pass"})
      worst (assoc :value (:value worst) :worst worst))))

(defn- unmeasured-of
  "What an unmeasured verdict counts as for `rule`: its own `unmeasured`,
  else a miss for a fail rule and a pass for a warn one (ticket 179f7824)."
  [rule]
  (or (word (:unmeasured rule))
      (if (= "fail" (word (:severity rule))) "miss" "pass")))

(defn- judge-rule
  "One rule's entry in the answer: what the rule says, and its verdict."
  [rule tk]
  (let [metric (word (:metric rule))]
    (merge
     {:name (:name rule)
      :metric metric
      :op (word (:op rule))
      :threshold (:threshold rule)
      :severity (word (:severity rule))
      :unmeasured (unmeasured-of rule)
      :why (:why rule)}
     (cond
       ;; the output filter is the film's, so it is judged before any shot
       (not (judges? (dissoc rule :role) (:film tk) nil)) {:verdict "unscored"}
       ;; a first week pins no quest, so it has no goal_state; week-arc
       ;; judges its story (ticket 567b5cf8). Read from the scene's mark,
       ;; not the roles a walk take carries too.
       (and (= "arc" metric) (true? (get-in tk [:scene :first_week])))
       {:verdict "unscored"}
       ;; the week's rules judge the takes their scene or the arc's roles mark
       (and (or (week-metrics metric) (played-metrics metric))
            (not (first-week? tk)))
       {:verdict "unscored"}
       ;; a first week is never unscored by them (ticket 179f7824): a take
       ;; short of a played week is unmeasured, and so missed by default
       ;; a week the clone's clock played before the film carries no call:
       ;; its day captions tell the moves (ticket a37c3249)
       (and (played-metrics metric) (clock-played? tk)) (judge-film rule tk)
       (and (played-metrics metric) (not (week? tk)))
       {:verdict "unmeasured" :missing ["shot.meanwhile"]}
       ;; a week no meanwhile shot played, as an older render's: the rule
       ;; cannot be judged, and that is a finding, not a pass
       (and (played-metrics metric) (not-any? :meanwhile (:shots tk)))
       {:verdict "miss" :missing ["shot.meanwhile"]}
       (contains? shot-readers metric) (judge-shots rule tk)
       (contains? film-readers metric) (judge-film rule tk)
       (contains? run-readers metric) (judge-runs rule tk)
       :else {:verdict "unmeasured" :missing []}))))

;; The director hands in the renderer's report (ticket a37c3249), whose
;; names are its own: the frame is its top-level `width` and `height`,
;; the content box `stats.content_box` {width height x y}, each beat's
;; caption a line `<beat> · <caption>` of `take.lines`, and what the
;; renderer measured a line of `scorecard.lines`, as `miss fail
;; chrome-leaks: 51 against = 0`. `reported` reads them into the take's
;; names; a field the take carries under its own name is kept.

(defn- box
  "A box or a frame as the take spells it, {:w :h}, read from the
  report's {:width :height} as well."
  [b]
  (when (map? b)
    (cond-> b
      (nil? (:w b)) (assoc :w (:width b))
      (nil? (:h b)) (assoc :h (:height b)))))

(defn- scored
  "The number the report's scorecard line for the rule `rule-name`
  gives, or nil."
  [tk rule-name]
  (let [lines (get-in tk [:scorecard :lines])
        re (re-pattern (str "(?:^|\\s)" (java.util.regex.Pattern/quote rule-name)
                            ":\\s*(-?\\d+(?:\\.\\d+)?)(?:\\s|$)"))]
    (when (sequential? lines)
      (some #(when (string? %) (some-> (re-find re %) second bigdec)) lines))))

(defn- beat-captions
  "The report's `take.lines` as beat → caption; a bare beat has none."
  [tk]
  (let [lines (get-in tk [:take :lines])]
    (into {}
          (keep (fn [line]
                  (when (string? line)
                    (let [[beat caption] (str/split line #" · " 2)]
                      (when-not (str/blank? caption)
                        [(str/trim beat) caption])))))
          (when (sequential? lines) lines))))

(defn- captioned
  "`shots`, each with its beat's caption from `captions` where it
  carries none: a shot's beat is its `shot`, else its place counted
  from 1."
  [shots captions]
  (if (empty? captions)
    shots
    (vec (map-indexed (fn [i s]
                        (if-some [c (when (and (map? s) (nil? (:caption s)))
                                      (get captions (str (if (some? (:shot s)) (:shot s) (inc i)))))]
                          (assoc s :caption c)
                          s))
                      shots))))

(defn- reported
  "The take `tk` with what the renderer's report carries under its own
  names read into the take's (ticket a37c3249)."
  [tk]
  (let [stats (if (map? (:stats tk)) (:stats tk) {})
        film (if (map? (:film tk)) (:film tk) {})
        fill (fn [m k v] (if (and (nil? (get m k)) (some? v)) (assoc m k v) m))
        number (fn [& vs] (first (filter number? vs)))]
    (assoc tk
           :film (-> film
                     (fill :frame (when (number? (:width tk))
                                    {:w (:width tk) :h (:height tk)}))
                     (fill :content_box (box (:content_box stats)))
                     (fill :chrome_leaks (number (:chrome_leaks stats)
                                                 (scored tk "chrome-leaks")))
                     (fill :dead_air_s (number (:dead_air_s stats)
                                               (scored tk "dead-air"))))
           :shots (captioned (:shots tk) (beat-captions tk)))))

(defn judge
  "The take `tk` scored against `rules` (each a rule's data), in order
  of name. Each rule answers its name, metric, op, threshold, severity
  and why, and a verdict: `pass`, `miss`, `unscored` (a filter leaves
  the rule out) or `unmeasured` (the take lacks a field the metric
  needs; `missing` names it). A scored film rule carries its `value`; a
  rule read from each shot carries `worst`, the index, caption and value
  of its worst shot, and a rule read from each run carries the lowest
  `value` and `worst`, the run that carries it. The overall verdict is
  `red` when a fail rule
  misses, else `warn` when any rule misses, else `green`. An unmeasured
  rule is not a pass: `unmeasured` names each one, and each rule's own
  `unmeasured` says what it counts as, `miss` or `pass`."
  [rules tk]
  (let [tk (if (map? tk) tk {})
        tk (reported (assoc tk :shots (if (sequential? (:shots tk)) (vec (:shots tk)) [])))
        judged (mapv #(judge-rule % tk) (sort-by (comp str :name) rules))
        missed (filter #(= "miss" (:verdict %)) judged)]
    {:verdict (cond (some #(= "fail" (:severity %)) missed) "red"
                    (seq missed) "warn"
                    :else "green")
     :unmeasured (mapv :name (filter #(= "unmeasured" (:verdict %)) judged))
     :rules judged}))

(defn judge-take
  "The take `tk` scored against every active rule the caller may read.
  `row?` is the caller's visibility over rows, (fn [kind id]), or nil
  for a caller who reads them all. It reads the rules and writes
  nothing."
  [eng tk row?]
  (let [rdef (get (inv/resources eng) kind)
        st (:storage eng)
        rows (store/with-tx st
               (fn [tx] (vec (store/query-rows st tx kind {} {:limit 100000}))))]
    (judge (into []
                 (comp (map #(inv/decode-row rdef %))
                       (filter #(= "active" (word (:state %))))
                       (filter #(or (nil? row?) (row? kind (str (:id %)))))
                       (map :data))
                 rows)
           tk)))

;; ── the boot seed ───────────────────────────────────────────────────

(def seed-actor
  "The actor the boot seed wears."
  (t/principal {:id seed-actor-id :type :system
                :display "Film rules"}))

(def seed-rules
  "The rules the scorecard starts with."
  [{:name "frame-fill" :metric "frame_fill" :op ">=" :threshold 0.95M
    :scope "film" :severity "fail"
    :why "The content fills the frame; a film that leaves most of it empty reads as broken."
    :origin "The owner's note: 'The content is only in the top left portion of the video and the rest is grey'"}
   {:name "chrome-leaks" :metric "chrome_leaks" :op "=" :threshold 0M
    :scope "film" :severity "fail"
    :why "A viewer reads the product's words, not an /api path, a uuid, a raw field key or [epic]."
    :origin "The owner's earlier notes on paths and field keys"}
   {:name "type-size" :metric "type_px" :op ">=" :threshold 28M
    :scope "shot" :output "phone" :severity "warn"
    :why "On phone output the text in focus is large enough to read without pausing."
    :origin "craft"}
   {:name "read-time" :metric "read_time_ratio" :op ">=" :threshold 1.2M
    :scope "shot" :severity "warn"
    :why "A shot holds for longer than its words take to read at three words a second."
    :origin "craft"}
   {:name "dead-air" :metric "dead_air_s" :op "<=" :threshold 0.8M
    :scope "film" :severity "warn"
    :why "Nothing on screen stands still long enough for the viewer to think the film stalled."
    :origin "craft"}
   {:name "one-surface" :metric "surfaces_changed" :op "<=" :threshold 1M
    :scope "shot" :severity "warn"
    :why "One thing changes in a shot besides the pointer, so the viewer knows where to look."
    :origin "craft"}
   {:name "focus-share" :metric "focus_share" :op ">=" :threshold 0.25M
    :scope "shot" :unless "zoom" :severity "warn"
    :why "What the shot is about takes a quarter of the viewport, or the shot asks for a zoom."
    :origin "craft"}
   {:name "arc" :metric "arc" :op "=" :threshold 1M
    :scope "film" :severity "fail"
    :why "When the scene declares roles, the shots run friction, turn, payoff, and the last shot's goal is done. A first week is judged by week-arc instead."
    :origin "craft"}
   {:name "caption-not-list" :metric "bare_lists" :op "=" :threshold 0M
    :scope "film" :severity "fail"
    :why "Each day of the first week says what happened, not a bare list of things."
    :origin "FS4, the mayor's restatement of 2026-10-19"}
   {:name "quiet-days" :metric "quiet_days" :op "<=" :threshold 2M
    :scope "film" :severity "fail"
    :why "At most two days of the first week are quiet: the house moves on most days."
    :origin "FS4, the mayor's restatement of 2026-10-19"}
   {:name "asked-person" :metric "asked_person" :op ">=" :threshold 1M
    :scope "film" :severity "fail"
    :why "On at least one day of the first week the person is asked: the film shows where they come in."
    :origin "FS4, the mayor's restatement of 2026-10-19"}
   {:name "no-bare-list" :metric "week_bare_lists" :op "=" :threshold 0M
    :scope "film" :severity "fail"
    :why "No day of the played first week is a bare list of rows: each is one sentence of what happened."
    :origin "Ticket 853b6707, the mayor's decision of 2026-10-20"}
   {:name "busy-week" :metric "busy_days" :op ">=" :threshold 5M
    :scope "film" :severity "fail"
    :why "At least five of the first week's seven days tell something other than 'A quiet day.'"
    :origin "Ticket 853b6707, the mayor's decision of 2026-10-20"}
   {:name "week-arc" :metric "arc_moves" :op "=" :threshold 3M
    :scope "film" :severity "fail"
    :why "The friction, turn and payoff days each hold a move on a kind other than the one the world knocks on."
    :origin "Ticket 853b6707, the mayor's decision of 2026-10-20"}
   {:name "asks-the-person" :metric "person_asks" :op ">=" :threshold 1M
    :scope "film" :severity "fail"
    :why "The week holds a call held for the person, or the person's own move through a door a person holds."
    :origin "Ticket 853b6707, the mayor's decision of 2026-10-20"}])

(defn- rows-of [eng where]
  (store/with-tx (:storage eng)
    (fn [tx] (store/query-rows (:storage eng) tx kind where {:limit 100000}))))

(defn- lacks
  "The `output` and `unless` the seed names for `rule` and `row`, a
  decoded rule of that name, does not carry. A restate by anyone but
  the seed writes `restated_by`, so a row with no such mark and without
  a field was made before the field existed; a row that carries the
  mark lacks nothing, and a value a row carries is never written over."
  [rule row]
  (if (some-> (get-in row [:data :restated_by]) str not-empty)
    {}
    (into {}
          (filter (fn [[k v]] (and v (nil? (word (get-in row [:data k]))))))
          (select-keys rule [:output :unless]))))

(defn ensure-seed-rules!
  "The boot seed: each of `seed-rules` when no rule carries its name,
  active or retired. A second boot makes none, and a rule a person
  retired is not made again. An active rule that carries a seed's name
  and lacks the `output` or `unless` the seed names is restated to
  carry it; a retired one is left as it is, and so is one a person or a
  mayor restated.

  Every engine runs this at its start, with no election. Two engines
  that start at once on one database may both read no row for a name
  and both create; `name` is unique by an index (`:unique [[:name]]`),
  so the second create is refused there and one row stands. That
  refusal is the other engine's seed, and is not reported."
  [eng]
  (when (contains? (inv/resources eng) kind)
    (doseq [rule seed-rules]
      (try
        (if-let [raw (first (rows-of eng {:name (:name rule)}))]
          (let [row (inv/decode-row (get (inv/resources eng) kind) raw)
                patch (lacks rule row)]
            (when (and (seq patch) (= "active" (name (:state row))))
              (inv/invoke! eng kind (str (:id row)) :restate patch
                           {:principal seed-actor})))
          (inv/create! eng kind rule {:principal seed-actor}))
        (catch Exception e
          (when-not (:waymark10/unique-violation (ex-data e))
            (binding [*out* *err*]
              (println (str "waymark10 film rules: seed of " (:name rule)
                            " failed — " (ex-message e))))))))))
