(ns waymark10.server.film-rules
  "The film rule (demo scorecard, 2026-10-07): one measured rule of a
  good demo film, as a row. A rule names a metric the scorer knows, an
  operator and a threshold, the part of the film it is judged over, how
  hard a miss is, why the rule exists and whose note it came from. Each
  critique a person makes of a film becomes one row, so an agent can
  meet the scorecard without seeing the film.

  The vocabulary is closed: `metrics` names every metric and the field
  of the take it is read from (docs/spec-agent-demo-walks.md § 8d). A
  rule naming any other metric is refused with the list.

  A person or the sitter of a domain's mayor seat makes a rule. Only a
  person retires one.

  `ensure-seed-rules!` is the boot seed: the eight rules the scorecard
  starts with, each made once by its name. The engine's own start runs
  it (`engine/start-runtime!`), so every application that enrolls the
  kind has them."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource]]
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

(defn- asked
  "The value a create names for field `k`, or nil."
  [row inp k]
  (some-> (or (get inp k) (get-in row [:data k])) str not-empty))

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

(g/defguard a-person-or-a-mayor-makes-the-rule
  {:reads [:principal :now :grant :domain]
   :open "No door changes who the caller is: ask a person or a domain's mayor to make the rule."
   :explain "A film rule is made by a person, or by the sitter of a domain's mayor seat. Ask a person or a mayor to make the rule."}
  [_row _inp ctx]
  (let [{:keys [type acts-for]} (:principal ctx)
        find' (:find ctx)
        cited (delegation/cited-seats ctx)]
    (cond
      ;; a person, a tool a person is signed in to, or the boot seed
      (or (not= :agent type) (some? (not-empty (str acts-for)))) (t/allow)
      (nil? find') (t/allow)             ; probe ctx — decline to guess
      (some #(seq (find' :domain {:mayor (str %) :state "active"} {:limit 1}))
            cited)
      (t/allow)
      :else (t/deny))))

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
     [:string {:min 1 :max 480}]]]
   :filterable {:state #{:eq :in}
                :name #{:eq}
                :metric #{:eq :in}
                :scope #{:eq}
                :severity #{:eq}}
   :sortable {:fields [:name] :default "name"}
   :create-guards [name-is-a-slug
                   metric-is-in-the-vocabulary
                   role-goes-with-a-shot
                   a-person-or-a-mayor-makes-the-rule]
   :actions
   {:retire
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

;; ── the boot seed ───────────────────────────────────────────────────

(def seed-actor
  "The actor the boot seed wears."
  (t/principal {:id "waymark10-film-rules" :type :system
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
    :scope "shot" :severity "warn"
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
    :scope "shot" :severity "warn"
    :why "What the shot is about takes a quarter of the viewport, or the shot asks for a zoom."
    :origin "craft"}
   {:name "arc" :metric "arc" :op "=" :threshold 1M
    :scope "film" :severity "fail"
    :why "When the scene declares roles, the shots run friction, turn, payoff, and the last shot's goal is done."
    :origin "craft"}])

(defn- rows-of [eng where]
  (store/with-tx (:storage eng)
    (fn [tx] (store/query-rows (:storage eng) tx kind where {:limit 100000}))))

(defn ensure-seed-rules!
  "The boot seed: each of `seed-rules` when no rule carries its name,
  active or retired. A second boot makes none, and a rule a person
  retired is not made again."
  [eng]
  (when (contains? (inv/resources eng) kind)
    (doseq [rule seed-rules]
      (try
        (when (empty? (rows-of eng {:name (:name rule)}))
          (inv/create! eng kind rule {:principal seed-actor}))
        (catch Exception e
          (binding [*out* *err*]
            (println (str "waymark10 film rules: seed of " (:name rule)
                          " failed — " (ex-message e)))))))))
