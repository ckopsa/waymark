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
  take against the rules and answers each rule's verdict; the router
  serves it at POST /api/film_rules/-/judge, and it writes nothing.

  A person or the sitter of a domain's mayor seat makes a rule, and
  whoever may make one may restate its `output` and `unless`. Only a
  person retires one.

  `ensure-seed-rules!` is the boot seed: the eight rules the scorecard
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
   ["runtime_s" "film.runtime_s"]])

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
;; the fields read once, and `:shots`, one map per shot in order. The
;; field names are § 8d's. A box is {:x :y :w :h}; a frame and a
;; viewport are {:w :h}.

(defn- area
  "The area of a box, a frame or a viewport, or nil."
  [{:keys [w h]}]
  (when (and (number? w) (number? h)) (* w h)))

(defn- over
  "`a` over `b`, or nil when either is missing or `b` is not positive."
  [a b]
  (when (and (number? a) (number? b) (pos? b)) (double (/ a b))))

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
  goal state is done; else 0."
  [shots]
  (if (and (= roles (vec (dedupe (map (comp word :role) shots))))
           (= "done" (word (:goal_state (last shots)))))
    1
    0))

(def ^:private film-readers
  "Metric → how it is read from the whole take."
  {"frame_fill" (fn [{:keys [film]}]
                  (over (area (:content_box film)) (area (:frame film))))
   "dead_air_s" (fn [tk] (get-in tk [:film :dead_air_s]))
   "chrome_leaks" (fn [tk] (get-in tk [:film :chrome_leaks]))
   "arc" (fn [tk] (arc (:shots tk)))
   "runtime_s" (fn [tk] (get-in tk [:film :runtime_s]))})

(def ^:private shot-readers
  "Metric → how it is read from one shot of the take."
  {"focus_share" (fn [shot]
                   (over (area (:focus_box shot)) (area (:viewport shot))))
   "type_px" :focus_type_px
   "contrast" :focus_contrast
   "read_time_ratio" (fn [{:keys [hold_s words]}]
                       (when (number? words) (over hold_s (/ words 3))))
   "surfaces_changed" (comp counted :surfaces_changed)})

(defn measure
  "The value of `metric` read from the take `tk`: one number for a
  metric read from the film, and a vector with one number per shot, in
  order, for a metric read from each shot. nil stands where the take
  lacks the field, and for a metric outside the vocabulary."
  [tk metric]
  (if-some [read (film-readers metric)]
    (read tk)
    (when-some [read (shot-readers metric)]
      (mapv read (:shots tk)))))

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
   :schema
   [:map
    [:name {:examples ["frame-fill"]
            :x-display
            {:raw true
             :label "Rule name"
             :help "One short slug, spelled once: no other rule may carry it."}}
     [:string {:min 1 :max 64}]]
    [:metric {:examples ["frame_fill"]
              :x-display
              {:raw true
               :label "Metric"
               :help "What the scorer measures: frame_fill, focus_share, type_px, contrast, read_time_ratio, dead_air_s, surfaces_changed, chrome_leaks, arc or runtime_s."}}
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
    [:why {:examples ["A film that leaves most of the frame grey reads as broken."]
           :x-display {:label "Why"
                       :help "One sentence: what a film that misses this rule gets wrong."}}
     [:string {:min 1 :max 480}]]
    [:origin {:examples ["craft"]
              :x-display
              {:label "Whose note"
               :help "Who asked for the rule: a person's own words, quoted, or the word craft."}}
     [:string {:min 1 :max 480}]]
    ;; written by `restate`, never by the boot seed's own
    [:restated_by {:optional true
                   :x-ref {:principal true}
                   :x-display
                   {:raw true
                    :label "Who restated it"
                    :help "Who last restated the rule's output or exemption. The boot seed leaves a rule that carries this alone."}}
     [:maybe [:string {:max 128}]]]]
   :filterable {:state #{:eq :in}
                :name #{:eq}
                :metric #{:eq :in}
                :scope #{:eq}
                :severity #{:eq}}
   :sortable {:fields [:name] :default "name"}
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

(def ^:private film-needs
  "Metric → the fields of the take it is read from, each with how to
  tell that the take carries it."
  {"frame_fill" [["film.content_box" #(sized? (get-in % [:film :content_box]))]
                 ["film.frame" #(sized? (get-in % [:film :frame]))]]
   "dead_air_s" [["film.dead_air_s" #(number? (get-in % [:film :dead_air_s]))]]
   "chrome_leaks" [["film.chrome_leaks"
                    #(number? (get-in % [:film :chrome_leaks]))]]
   "arc" [["shots" #(some? (seq (:shots %)))]
          ["shot.role" #(or (empty? (:shots %))
                           (some (comp word :role) (:shots %)))]
          ["shot.goal_state" #(or (empty? (:shots %))
                                 (word (:goal_state (last (:shots %)))))]]
   "runtime_s" [["film.runtime_s" #(number? (get-in % [:film :runtime_s]))]]})

(def ^:private shot-needs
  "Metric → the fields of one shot it is read from, each with how to
  tell that the shot carries it."
  {"focus_share" [["shot.focus_box" #(sized? (:focus_box %))]
                  ["shot.viewport" #(sized? (:viewport %))]]
   "type_px" [["shot.focus_type_px" #(number? (:focus_type_px %))]]
   "contrast" [["shot.focus_contrast" #(number? (:focus_contrast %))]]
   "read_time_ratio" [["shot.hold_s" #(number? (:hold_s %))]
                      ["shot.words" #(number? (:words %))]]
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
     (not (judges? rule film shot)) {:verdict :unscored}
     (exempt? rule shot) {:verdict :pass}
     :else
     (let [missing (lacking (shot-needs (word (:metric rule))) shot)]
       (cond
         (seq missing) {:verdict :unmeasured :missing missing}
         ;; every field is there and still no number: a shot with no
         ;; words has nothing to read
         (not (number? value)) {:verdict :unscored}
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
      :why (:why rule)}
     (cond
       ;; the output filter is the film's, so it is judged before any shot
       (not (judges? (dissoc rule :role) (:film tk) nil)) {:verdict "unscored"}
       (contains? shot-readers metric) (judge-shots rule tk)
       (contains? film-readers metric) (judge-film rule tk)
       :else {:verdict "unmeasured" :missing []}))))

(defn judge
  "The take `tk` scored against `rules` (each a rule's data), in order
  of name. Each rule answers its name, metric, op, threshold, severity
  and why, and a verdict: `pass`, `miss`, `unscored` (a filter leaves
  the rule out) or `unmeasured` (the take lacks a field the metric
  needs; `missing` names it). A scored film rule carries its `value`; a
  rule read from each shot carries `worst`, the index, caption and value
  of its worst shot. The overall verdict is `red` when a fail rule
  misses, else `warn` when any rule misses, else `green`. An unmeasured
  rule is not a pass: `unmeasured` names each one."
  [rules tk]
  (let [tk (if (map? tk) tk {})
        tk (assoc tk :shots (if (sequential? (:shots tk)) (vec (:shots tk)) []))
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
    :why "When the scene declares roles, the shots run friction, turn, payoff, and the last shot's goal is done."
    :origin "craft"}])

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
  mayor restated."
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
          (binding [*out* *err*]
            (println (str "waymark10 film rules: seed of " (:name rule)
                          " failed — " (ex-message e)))))))))
