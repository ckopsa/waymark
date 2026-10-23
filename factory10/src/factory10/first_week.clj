(ns factory10.first-week
  "An org's first week, filmed (ticket 3260d452, M3d of epic cafa6106).

  THE SCENE IS A TEMPLATE. factorykit's `scenes/first-week.edn` is the
  film of an org's simulated first week as data: an opening, one day's
  shots for each of the seven days the world seat plays, and a closing.
  `scene` renders it from an org's seed and the clone it is filmed on.
  The clone's URL and head SHA are inputs: this repo starts no clone.
  It answers a scene's create input beside that clone.

  THE AUDIT RENDERS IT FOR EACH HEAD (ticket 4dbb8243). The org-findings
  job of tests.yml runs `-main` on the org's directory at the pull
  request's head and uploads the text as `first_week_scene.edn`; the
  forge's findings pass writes it onto the plan as `first_week_scene`,
  at that head. The director plays it on the clone it stands up. With
  the scene rides the org's week.edn and its git blob sha (`week-of`,
  ticket 4c70e261): the director plays that script on the clone, and the
  film pass compares its take with the plan's `dry_week` (`take-differs`).

  THE FILM LANDS ON THE PLAN. `landing` reads the film rules' answer
  (film-rules/judge). With no fail rule missed, the film is attached and
  the plan moves to simulated. A red film's missed fail rules become
  findings in the audit's shape (M2b), and `record_film_findings` sends
  the plan back to drafting (ticket d52abbf2). `land!` takes the door,
  as the engine's hand."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [factorykit.conform :as conform]
            [waymark10.server.invoke :as inv]
            [waymark10.server.scenes :as scenes])
  (:import (java.time LocalDate)
           (java.time.format TextStyle)
           (java.util Locale)))

(set! *warn-on-reflection* true)

;; ── the scene ───────────────────────────────────────────────────────

(defn template
  "factorykit's first-week scene template, as data."
  []
  (edn/read-string (slurp (io/resource "scenes/first-week.edn"))))

(defn- fill
  "`form` with each {{param}} in its strings replaced by its value; a
  string that is one {{param}} whole, of a number, becomes that number."
  [form values]
  (walk/postwalk
   (fn [x]
     (if (string? x)
       (or (some (fn [[k v]] (when (and (number? v) (= x (str "{{" (name k) "}}"))) v))
                 values)
           (reduce-kv (fn [s k v] (str/replace s (str "{{" (name k) "}}") (str v)))
                      x values))
       x))
   form))

(defn- the-owner
  "The cast member filmed: the seed's approver, or its first."
  [seed]
  (let [cast (vals (:cast seed))]
    (or (first (filter #(some #{"approver"} (:roles %)) cast))
        (first cast))))

(defn- the-knocked-kind
  "The kind the outside world knocks on: the one the seed's first create
  makes."
  [seed]
  (some-> (first (filter :create (:steps seed))) :kind name))

(defn- the-called-kind
  "The kind the week script's first call names, its days in order."
  [week-script]
  (some->> (:days week-script) (sort-by :day) (mapcat :calls) (keep :kind) first name))

(defn- the-org-kinds
  "The kinds the seed's steps make rows of, in the order they first name
  them."
  [seed]
  (distinct (keep #(some-> (:kind %) name) (:steps seed))))

(defn- the-collection
  "`kind`'s collection among `resources`: its declared :plural, else its
  name and an s, as the registry makes it."
  [resources kind]
  (or (some #(when (= kind (name (:kind %))) (:plural %)) resources)
      (str kind "s")))

(def loss-states
  "The terminal states the closing leaves out: the ends that are losses."
  #{"declined" "written_off" "cancelled" "dropped"})

(defn- words [k] (str/replace (name k) "_" " "))

(defn plural
  "`noun`'s plural: as written when it ends in s, -ies for a y after a
  consonant, else -s."
  [noun]
  (cond (str/ends-with? noun "s") noun
        (re-find #"[^aeiou]y$" noun) (str (subs noun 0 (dec (count noun))) "ies")
        :else (str noun "s")))

(defn- the-clauses
  "The closing's clauses (ticket 3173de32): for each kind the seed's
  steps make, in that order, each of its terminal states that is no
  loss, in the kind's state order, as the values of one :closing_kind;
  at most `n`. A kind's noun is its declared :label, else its name with
  '_' as a space. `resources` are the org's declarations. The week's
  `goal`, {:kind :state}, when given, is the first clause (ticket
  6c3d93db): the payoff counts what the week was played for."
  [seed resources n goal]
  (let [by-kind (into {} (map (juxt #(name (:kind %)) identity)) resources)
        clause (fn [k s]
                 (let [noun (or (:label (get by-kind k)) (words k))]
                   {:each_kind k
                    :each_state s
                    :each_one (str noun " " (words s))
                    :each_many (str (plural noun) " " (words s))}))]
    (->> (the-org-kinds seed)
         (mapcat (fn [k]
                   (when-some [r (get by-kind k)]
                     (let [ends (into #{} (map name) (:terminal r))]
                       (for [s (map name (:states r))
                             :when (and (ends s) (not (loss-states s)))]
                         (clause k s))))))
         (concat (when (and (:kind goal) (:state goal))
                   [(clause (name (:kind goal)) (name (:state goal)))]))
         (distinct)
         (take n))))

(def amount-names
  "The names a kind's money field goes by, when it is a number."
  #{"amount" "total" "fee"})

(def ^:private number-types
  #{:int :double :float :long :number 'int? 'number? 'double? 'float? 'pos-int? 'nat-int?})

(defn- number-type?
  "Whether the malli type `t` is a number, through a :maybe."
  [t]
  (let [t (if (vector? t) t [t])]
    (if (= :maybe (first t))
      (number-type? (last t))
      (contains? number-types (first t)))))

(defn amount-field
  "`resource`'s money field, read from its :schema: the first number
  field named amount, total or fee; nil when it has none."
  [resource]
  (let [schema (:schema resource)]
    (when (and (vector? schema) (= :map (first schema)))
      (some (fn [entry]
              (when (and (vector? entry)
                         (amount-names (name (first entry)))
                         (number-type? (last entry)))
                (name (first entry))))
            (rest schema)))))

(defn- the-money
  "The closing's money clause (ticket 8a21a8b9): the first kind the
  seed's steps make that declares an amount field, as the values of one
  :closing_money; none when no kind does."
  [seed resources]
  (let [by-kind (into {} (map (juxt #(name (:kind %)) identity)) resources)]
    (->> (the-org-kinds seed)
         (keep (fn [k]
                 (when-some [f (some-> (get by-kind k) amount-field)]
                   {:money_kind k :money_field f})))
         (take 1))))

(defn brief-line
  "The first sentence of `brief`, cut at 140 characters; nil when there
  is no brief."
  [brief]
  (let [s (str/trim (str brief))]
    (when-not (str/blank? s)
      (let [sentence (or (re-find #"(?s)^.*?[.!?](?=\s|$)" s) s)]
        (if (> (count sentence) 140) (subs sentence 0 140) sentence)))))

(defn- call-as
  "The cast member a meanwhile call acts as."
  [call]
  (some-> (or (:as call) (get call "as")) str))

(defn- with-calls
  "One day's `shots` with its meanwhile `calls` spliced in right before
  its caption, the day's last shot."
  [shots calls]
  (let [shots (vec shots)]
    (if (seq calls)
      (vec (concat (pop shots) (map (fn [c] {:meanwhile c}) calls) [(peek shots)]))
      shots)))

(defn- card-shot
  "A full-screen card of `text`, held as long as the scene check asks."
  [text]
  {:card text :hold {:ms (scenes/card-ms text)}})

;; ── the week, played as the camera rolls (ticket 1b89ca49) ──────────

(defn- cast-of [c] (some-> (:as c) name keyword))

(defn- row-name
  "The name the scene knows the week's row `nm` by: `nm` with each
  character a `$ref` cannot spell made `_`."
  [nm]
  (str/replace (str nm) #"[^A-Za-z0-9_]" "_"))

(defn- row-bind
  "The name the scene binds the week's row `nm` to, as a `$ref` spells it."
  [nm]
  (str "row_" (row-name nm)))

(defn- pressed-names
  "Each row of `week-script` a person's create makes by their own press,
  its week.edn name to the one name its bind carries (ticket 0b5333a5)."
  [week-script]
  (into {} (for [{:keys [calls]} (:days week-script)
                 c calls
                 :when (and (not (contains? c :event)) (= :person (cast-of c))
                            (nil? (:row c)) (some? (:name c)))]
             [(str (:name c)) (row-name (:name c))])))

(defn- renamed
  "Call `c` with each reference to a pressed row, its `:row` and each
  `{\"$row\" name}` in its input, renamed by `names` (`pressed-names`), so
  the scene names that row as its press bound it. A call-made row's
  references stay as they are."
  [c names]
  (let [rename #(get names (str %) %)]
    (cond-> c
      (contains? names (some-> (:row c) str)) (update :row rename)
      (some? (:input c))
      (update :input (fn [input]
                       (walk/postwalk
                        #(if (and (map? %) (= 1 (count %)) (contains? % "$row"))
                           {"$row" (rename (get % "$row"))}
                           %)
                        input))))))

(defn- shown?
  "Whether the film shows call `c`, `last?` when it is its day's last:
  day 1 shows every call; a later day the ones marked `:show true`, or
  with no mark, a person's call and the day's last."
  [c day-one? last?]
  (cond day-one? true
        (contains? c :show) (true? (:show c))
        :else (or (= :person (cast-of c)) last?)))

(defn- typed
  "The `type` shots that fill a door's form with `input`, a nested map's
  keys dotted into the field each names."
  ([input] (typed input nil))
  ([input prefix]
   (mapcat (fn [[k v]]
             (let [f (str prefix (name k))]
               (if (map? v)
                 (typed v (str f "."))
                 [{:type {:field (str "dialog.field:" f)
                          :value (cond (string? v) v
                                       (keyword? v) (name v)
                                       (or (number? v) (boolean? v)) (str v)
                                       :else (pr-str v))}}])))
           input)))

(defn- the-mayor-seat
  "The name of the seed's mayor seat, as factorykit's org-mayor template
  names it: `<org>-mayor`. nil when the seed seats no mayor."
  [seed]
  (some #(when (= "org-mayor" (:template %))
           (some-> (get-in % [:params :org]) (str "-mayor")))
        (:seats seed)))

(defn- call-shots
  "The shots that play week.edn call `c`. A person's call on a row is
  the owner's tap: open the row, frame its held band and each field the
  call's `:focus` names, press Allow. A person's create is their own
  press on screen (ticket ff1a2c0f): open the collection, press the
  create's button, type the call's input, press submit, binding the row
  a named one makes. Any other is a `call` shot the
  performer makes as it plays, binding the row a named create makes,
  then the open of the row it moved; the first shown call of a cast
  opens its queue first, and the mayor's first on day 1 (`mayor-seat`
  given) its seat page and its latest sitting before, and that sitting
  again after, so its new move shows (ticket 4a984e27). A call not
  shown is still played, its shots trimmed."
  [c first? mayor-seat resources show?]
  (let [as (cast-of c)
        row (or (:row c) (:name c))
        ref (some->> row row-bind (str "$"))
        shots (cond
                (and (= :person as) (:row c))
                (concat [{:open {:self ref}} {:focus {:target "held"}}]
                        (map (fn [f] {:focus {:target (str "field:" (name f))}}) (:focus c))
                        [{:press {:target "allow"}}])

                (= :person as)
                (concat [{:open {:collection (the-collection resources (name (:kind c)))}}
                         {:press {:surface (str "door:" (name (or (:action c) "create")))}}]
                        (typed (:input c))
                        [(cond-> {:press {:surface "dialog.submit"}}
                           (:name c) (assoc :bind (row-bind (:name c))))]
                        (when (and show? ref) [{:open {:self ref}}]))

                :else
                (let [at-work? (and show? first? (= :mayor as) mayor-seat)
                      sitting {:open {:sitting {:of mayor-seat :which "latest"}}}]
                (concat (when (and show? first?)
                          [{:open {:collection (the-collection resources (name (:kind c)))}}])
                        (when at-work? [{:open {:seat mayor-seat}} sitting])
                        [(cond-> {:call (cond-> {:as (name as) :kind (name (:kind c))
                                                 :action (name (or (:action c) "create"))}
                                          (:row c) (assoc :row (str (:row c)))
                                          (:name c) (assoc :name (str (:name c)))
                                          (some? (:input c)) (assoc :input (:input c))
                                          ;; the AI's one sentence, the invoke's why
                                          ;; (ticket 0ee7ab2d)
                                          (:why c) (assoc :why (str (:why c))))}
                           (:name c) (assoc :bind (row-bind (:name c))))]
                        (when at-work? [sitting])
                        (when (and show? ref) [{:open {:self ref}}]))))]
    (if show? (vec shots) (mapv #(assoc % :trim true) shots))))

(defn- event-shots
  "The shot that plays week.edn event `c` (ticket 524685a0): an `event`
  shot the performer signs as the integration and posts as it plays,
  binding the receipt a named one keeps. Trimmed when not shown."
  [c show?]
  [(cond-> {:event {:source (name (:event c)) :body (or (:body c) {})}}
     (:name c) (assoc :bind (row-bind (:name c)))
     (not show?) (assoc :trim true))])

(defn- week-shots
  "Each day of `week-script` that has calls, to the shots that play
  them, its days in order: day 1 in full, a later day the calls it shows
  (`shown?`)."
  [week-script seed resources]
  (let [mayor-seat (the-mayor-seat seed)
        names (pressed-names week-script)
        play-day (fn [[acc seen] {:keys [day calls]}]
                   (let [n (count calls)
                         [shots seen]
                         (reduce (fn [[shots seen] [i c]]
                                   (let [show? (shown? c (= 1 day) (= i (dec n)))
                                         as (cast-of c)]
                                     [(into shots (if (contains? c :event)
                                                    (event-shots c show?)
                                                    (call-shots (renamed c names) (not (seen as))
                                                                (when (= 1 day) mayor-seat)
                                                                resources show?)))
                                      (cond-> seen (and show? (not (contains? c :event))) (conj as))]))
                                 [[] seen]
                                 (map-indexed vector calls))]
                     [(assoc acc day shots) seen]))]
    (first (reduce play-day [{} #{}]
                   (sort-by :day (filter (comp seq :calls) (:days week-script)))))))

(defn scene
  "The first-week scene of the org whose seed is `seed`, filmed on the
  clone at `clone_url`, made at `head_sha`, its day 1 on `day_one`
  (YYYY-MM-DD): {:scene <a scene's create input>, :clone {:url
  :head_sha}, :day_one}. `kind` names the kind the world knocks on, when
  the seed's first create is not it. `:day_one` is the day the clone is
  stood up with (clone__up's `day_one`), so its seed's [:days n] count
  from the day the captions' holes read (ticket bbf1f253). `brief` is
  the plan's brief: the opening says its first sentence beside the org's
  name, or the name alone without one. `resources` are the org's kind
  declarations: the closing counts each kind's success ends by them, and
  sums the money of the first kind with an amount field.
  `week`, when given, is the week the director writes (ticket fb9a1cff):
  a vector of 7 vectors of meanwhile calls, {as, tool, arguments,
  bind?}, day k's calls played as `meanwhile` shots right before day
  k's caption, and each one's `as` joining the scene's cast after the
  owner. With no `week` the scene is the audit's, and none plays.
  `week_script`, when given, is the org's week.edn as data: its `:card`
  opens the scene, and each day's `:card` comes before that day's shots.
  Its `:day-one` is the scene's day 1, its `:days` the days filmed, one
  day's shots per entry at its `:day` (a day with no entry, a weekend,
  has none), its `:goal` the closing's first count, and its first call's
  kind the collection the opening opens (ticket 6c3d93db). With none of
  these the opening opens the seed's first kind, never an empty name.
  Each shot carries `:id`, its 1-based position in the rendered scene
  (ticket ef24488d), for the take shot that films it to name.
  A day of `week_script` with `:calls` is played as the camera rolls
  (ticket 1b89ca49): its card, the shots `week-shots` makes of its
  calls, then its caption, and each call shot's cast joins the scene's.
  With `week_script`, each day opens on a `:day` shot, {:n <its :day>
  :date YYYY-MM-DD}, before its card (ticket 0f6632ad): where the
  performer shifts the clone's clock to, a weekend skipping two."
  [seed {:keys [clone_url head_sha day_one kind brief resources week week_script]}]
  (let [t (template)
        owner (the-owner seed)
        day-one (LocalDate/parse (str (or (:day-one week_script) day_one)))
        day-nums (or (seq (sort (distinct (filter pos-int? (map :day (:days week_script))))))
                     (range 1 (inc (get-in t [:template :days]))))
        line (brief-line brief)
        kind (or kind (the-called-kind week_script) (the-knocked-kind seed)
                 (first (the-org-kinds seed)) (some-> (first resources) :kind name))
        values {:seed (:seed seed)
                :org_display (or (:label (first (:domains seed))) (:org seed))
                :owner (:id owner)
                :owner_display (or (:display owner) (:id owner))
                :kind kind
                :kind_plural (the-collection resources kind)
                :clone_url clone_url
                :head_sha head_sha
                :brief_line (or line "")
                :day_one (str day-one)}
        roles (get-in t [:template :roles])
        played (week-shots week_script seed resources)
        day-cards (into {} (keep (fn [d] (when (string? (:card d)) [(:day d) (:card d)])))
                        (:days week_script))
        day (fn [k]
              (let [date (.plusDays day-one (long (dec k)))
                    shots (fill (:day t)
                                (assoc values :day k
                                       :date (str date)
                                       :weekday (.getDisplayName (.getDayOfWeek date)
                                                                 TextStyle/FULL Locale/ENGLISH)
                                       :next_date (str (.plusDays day-one (long k)))))]
                (cond->> (cond-> shots
                           (get roles k) (update (dec (count shots)) assoc :role (get roles k))
                           ;; the week's calls, then the day's caption
                           (get played k) (->> peek (conj (get played k)))
                           true (with-calls (get (vec week) (dec k))))
                  (get day-cards k) (concat [(card-shot (get day-cards k))])
                  week_script (concat [{:day {:n k :date (str date)}}]))))
        actors (concat (keep call-as (apply concat week))
                       (keep #(get-in % [:call :as]) (apply concat (vals played))))
        money (the-money seed resources)
        ;; four clauses at most: the ends, the money, then the taps
        per-kind (map #(fill (:closing_kind t) (merge values %))
                      (the-clauses seed resources (- 3 (count money)) (:goal week_script)))
        per-money (map #(fill (:closing_money t) (merge values %)) money)
        closing (walk/postwalk
                 (fn [x]
                   (if (and (vector? x) (not (map-entry? x)))
                     (vec (mapcat #(case % "{{kinds}}" per-kind "{{money}}" per-money [%]) x))
                     x))
                 (fill (:closing t) values))]
    {:scene (assoc (cond-> (fill (:scene t) values)
                     (seq actors) (update :cast #(vec (distinct (concat % actors)))))
                   :shots (vec (map-indexed
                                (fn [i shot] (assoc shot :id (inc i)))
                                (concat (when (string? (:card week_script))
                                          [(card-shot (:card week_script))])
                                        (fill (if line (:opening t) (:opening_bare t)) values)
                                        (mapcat day day-nums)
                                        closing))))
     :clone (fill (:clone t) values)
     :day_one (str day-one)
     :first-week (true? (get-in t [:template :first-week]))}))

(defn- read-scene
  "The plan's scene `scene-text`, edn text, as data; nil when it is
  blank or does not read."
  [scene-text]
  (when (and (string? scene-text) (not (str/blank? scene-text)))
    (try (edn/read-string scene-text) (catch Exception _ nil))))

(defn marked
  "The take `tk` marked `:scene {:first_week true}` when it is a take of
  the plan's first-week scene `scene-text`, edn text (ticket 78da0888):
  the scene says `:first-week true`, as every one `scene` renders does,
  or it is a scene rendered before the mark, which the plan's takes are
  filmed of all the same. `tk` as given when the plan carries no scene."
  [tk scene-text]
  (let [doc (read-scene scene-text)]
    (if (and (map? tk) (map? doc)
             (or (true? (:first-week doc)) (map? (:scene doc))))
      (assoc tk :scene (assoc (if (map? (:scene tk)) (:scene tk) {}) :first_week true))
      tk)))

(defn- clock-days?
  "True when the scene `doc` tells its days by a `$day` hole over the
  clone's clock window, as the template's day shot does."
  [doc]
  (boolean (some (fn [x]
                   (when (map? x)
                     (let [h (get x "$day")
                           w (when (map? h) (or (get h :window) (get h "window")))]
                       (= "clock" (when (or (string? w) (keyword? w)) (name w))))))
                 (tree-seq coll? seq doc))))

(defn clocked
  "The take `tk` marked `:scene {:clock_days true}` when the plan's scene
  `scene-text`, edn text, tells its days by the clone's clock (ticket
  a37c3249): the week may be played before the film, and the film rules
  then read its moves from the day captions. `tk` as given otherwise."
  [tk scene-text]
  (if (and (map? tk) (clock-days? (read-scene scene-text)))
    (assoc tk :scene (assoc (if (map? (:scene tk)) (:scene tk) {}) :clock_days true))
    tk))

(defn- scene-shots
  "The shots of the plan's scene `scene-text`, edn text; nil when it
  carries none."
  [scene-text]
  (let [shots (get-in (read-scene scene-text) [:scene :shots])]
    (when (sequential? shots) (vec shots))))

(defn- framed
  "The positions in `shots` of the shots that are not meanwhile shots."
  [shots]
  (keep-indexed (fn [i s] (when-not (and (map? s) (:meanwhile s)) i)) shots))

(defn- ids? [taken]
  (boolean (some #(and (map? %) (some? (:id %))) taken)))

(defn- matches
  "Each take shot's position in `taken` beside the position in `shots`
  of the scene shot it films (ticket ef24488d): by the `:id` both carry,
  or, for a take from before the id, by position once the meanwhile
  shots are dropped from both."
  [shots taken]
  (if (ids? taken)
    (let [at (into {} (keep-indexed (fn [j s] (when (and (map? s) (some? (:id s)))
                                                [(str (:id s)) j])))
                   shots)]
      (keep-indexed (fn [i t] (when-some [j (when (map? t) (get at (str (:id t))))]
                                [i j]))
                    taken))
    (map vector (framed taken) (framed shots))))

(def ^:private copied
  "What line-up copies from a scene shot onto the take shot that films
  it: its id and role, and what the live week's film rules read (ticket
  4b7af799), its day, card and hold, the page it opens, its press, its
  call and its event."
  [:id :role :day :card :hold :open :press :call :event])

(defn line-up
  "The take `tk` with each scene shot's `copied` keys copied onto
  the take shot that films it, where that shot carries none (ticket
  ef24488d), the shots matched as `matches` does. `tk` as given when the
  plan carries no scene."
  [tk scene-text]
  (let [shots (scene-shots scene-text)]
    (if (and (map? tk) (sequential? (:shots tk)) (seq shots))
      (let [taken (vec (:shots tk))
            copy (fn [t s]
                   (reduce (fn [t k]
                             (if (and (nil? (get t k)) (some? (get s k))) (assoc t k (get s k)) t))
                           t copied))]
        (assoc tk :shots
               (reduce (fn [v [i j]]
                         (let [t (nth v i) s (nth shots j)]
                           (if (and (map? t) (map? s)) (assoc v i (copy t s)) v)))
                       taken
                       (matches shots taken))))
      tk)))

(defn dropped-roles
  "The `take-dropped-role` warning of the take `tk` against the plan's
  scene `scene-text` (ticket ef24488d), in the audit's shape: each scene
  shot with a role whose take shot carries none, and, for a take matched
  by position, a take whose shots and the scene's do not number the
  same, meanwhile shots dropped from both. [] when none was dropped."
  [tk scene-text]
  (let [shots (scene-shots scene-text)
        taken (when (map? tk) (:shots tk))]
    (if-not (and (seq shots) (sequential? taken))
      []
      (let [taken (vec taken)
            filmed (into {} (map (fn [[i j]] [j (nth taken i)])) (matches shots taken))
            lost (for [[j s] (map-indexed vector shots)
                       :when (and (map? s) (not (:meanwhile s)) (some? (:role s))
                                  (nil? (:role (let [t (get filmed j)] (when (map? t) t)))))]
                   (let [r (:role s)]
                     (str (if (keyword? r) (name r) r) " (shot " (or (:id s) (inc j)) ")")))
            counts (when-not (ids? taken)
                     (let [a (count (framed shots)) b (count (framed taken))]
                       (when (not= a b)
                         (str "the scene has " a " shots and the take " b
                              ", meanwhile shots aside"))))
            said (remove nil? [(when (seq lost)
                                 (str "the take dropped the role of " (str/join ", " lost)))
                               counts])]
        (if (empty? said)
          []
          [{:key "take-dropped-role"
            :severity "warning"
            :class "film"
            :subject "film"
            :sentence (let [s (str "The take does not line up with its scene: "
                                   (str/join "; " said) ".")]
                        (subs s 0 (min 240 (count s))))
            :remedy "Film it again with each scene shot's id and role on the take shot that films it."}])))))

(defn- read-brief
  "The brief in the org's org.edn at `org-dir`, its :brief; nil when it
  names none."
  [org-dir]
  (let [f (io/file org-dir "org.edn")]
    (when (.isFile f)
      (:brief (edn/read-string (slurp f))))))

(defn read-seed
  "The seed of the org at `org-dir`: <org-dir>/resources/<org>/seed.edn."
  [org-dir]
  (let [f (->> (.listFiles (io/file org-dir "resources"))
               (sort)
               (map #(io/file % "seed.edn"))
               (filter #(.isFile ^java.io.File %))
               first)]
    (when-not f
      (throw (ex-info (str "no seed under " org-dir "/resources/<org>/seed.edn")
                      {:org-dir (str org-dir)})))
    (edn/read-string (slurp f))))

(defn read-resources
  "The kinds the org at `org-dir` declares, its `<org>.org/resources`,
  loaded as conform loads them: with the org's src on the classpath."
  [org-dir org]
  (conform/with-org-classpath
    (io/file org-dir)
    #((requiring-resolve (symbol (str org ".org") "resources")))))

(defn- blob-sha
  "The git blob sha of `bs`, as `git hash-object` names the file."
  [^bytes bs]
  (let [md (java.security.MessageDigest/getInstance "SHA-1")]
    (.update md (.getBytes (str "blob " (alength bs) "\u0000") "UTF-8"))
    (.update md bs)
    (apply str (map #(format "%02x" (bit-and (long %) 0xff)) (.digest md)))))

(defn week-of
  "The week script of the org at `org-dir` (ticket 4c70e261): {:week_script
  its resources/<org>/week.edn as data, :week_sha that file's git blob
  sha}, the script the director plays and its take report names. nil
  when the org has no week.edn."
  [org-dir]
  (when-some [^java.io.File f (->> (.listFiles (io/file org-dir "resources"))
                                   (sort)
                                   (map #(io/file % "week.edn"))
                                   (filter #(.isFile ^java.io.File %))
                                   first)]
    (let [bs (java.nio.file.Files/readAllBytes (.toPath f))]
      {:week_script (edn/read-string (String. bs "UTF-8"))
       :week_sha (blob-sha bs)})))

(defn org-scene
  "The first-week scene of the org at `org-dir`, at `head-sha`, its day 1
  on `day-one`, as the edn text the org-findings artifact carries, its
  opening on org.edn's :brief when it has one. The audit's run stands up
  no clone, so the clone's url is empty: the director plays the scene on
  the clone it stands up at that head. An org with a week.edn carries
  its script and sha beside the scene (`week-of`), and the scene shows
  the script's cards."
  [org-dir head-sha day-one]
  (let [seed (read-seed org-dir)
        week (week-of org-dir)]
    (pr-str (merge (scene seed
                          {:clone_url "" :head_sha head-sha :day_one day-one
                           :brief (read-brief org-dir)
                           :resources (read-resources org-dir (:org seed))
                           :week_script (:week_script week)})
                   week))))

(defn -main
  "Print the first-week scene of the org at `org-dir` at `head-sha`, its
  day 1 on `day-one` (YYYY-MM-DD), else today."
  [org-dir head-sha & [day-one]]
  (print (org-scene org-dir head-sha (or day-one (str (LocalDate/now)))))
  (flush))

;; ── the film's landing ──────────────────────────────────────────────

(defn- clip [s n]
  (let [s (str s)] (if (> (count s) n) (subs s 0 n) s)))

(defn- word [v]
  (when (or (string? v) (keyword? v)) (name v)))

(defn- numbered
  "Each call of `calls` in order as [[its day, cast, kind and action, how
  many such calls came before it] the call]."
  [calls]
  (first (reduce (fn [[out seen] c]
                   (let [k [(str (:day c)) (str (:as c)) (str (:kind c)) (str (:action c))]
                         n (get seen k 0)]
                     [(conj out [[k n] c]) (assoc seen k (inc n))]))
                 [[] {}] calls)))

(defn take-differs
  "The `take-differs` warning of the take `tk` against the plan's
  `dry_week` (ticket 4c70e261): each call the audit's dry week moved and
  the take's `week` refused on the clone, the two paired call for call
  by day, cast, kind and action. [] when the take played no week, the
  plan has no dry week, or none differs."
  [tk dry-week]
  (let [played (when (map? tk) (get-in (walk/keywordize-keys tk) [:week :calls]))]
    (if-not (and (sequential? played) (sequential? dry-week))
      []
      (let [taken (into {} (numbered played))
            differs (keep (fn [[k c]]
                            (let [t (get taken k)]
                              (when (and (= "moved" (str (:outcome c)))
                                         (= "refused" (str (:outcome t))))
                                (str "day " (:day c) ": " (:kind c) " " (:action c)
                                     (when (seq (str (:guard t))) (str " by " (:guard t)))))))
                          (numbered (walk/keywordize-keys dry-week)))]
        (if (empty? differs)
          []
          [{:key "take-differs"
            :severity "warning"
            :class "film"
            :subject "week"
            :sentence (clip (str "The clone refused what the dry week moved: "
                                 (str/join "; " differs) ".")
                            240)
            :remedy "Find why the clone refused what the audit's dry week moved, and film the week again."}])))))

(defn take-stopped
  "The `take_stopped` warning of the take `tk` (ticket d49008cc): the
  first of its shots with `ok: false`, named with its why. A take that
  stopped on a shot is the performer's failure, not the org's, so it is
  voided and filmed again, not judged. nil when every shot passed."
  [tk]
  (let [shots (when (map? tk) (:shots (walk/keywordize-keys tk)))]
    (when (sequential? shots)
      (when-some [[i s] (first (keep-indexed (fn [i s] (when (and (map? s) (false? (:ok s))) [i s]))
                                             shots))]
        (let [why (str/trim (str (:why s)))]
          {:key "take_stopped"
           :severity "warning"
           :class "film"
           :subject "film"
           :sentence (clip (str "The take stopped at shot " (or (:id s) (inc i))
                                " of " (count shots)
                                (if (str/blank? why) "" (str ": " why))
                                ", so it was voided, not judged.")
                           240)
           :remedy "Fix what stopped the shot, and let the director film the plan again."})))))

(defn- missed?
  "True when `rule` of the judge's answer keeps the film from landing: a
  fail rule it missed, or one it could not measure, unless the rule
  counts that as a pass (ticket 179f7824)."
  [rule]
  (and (= "fail" (word (:severity rule)))
       (case (word (:verdict rule))
         "miss" true
         "unmeasured" (not= "pass" (word (:unmeasured rule)))
         false)))

(defn- lower-first [s]
  (let [s (str s)]
    (if (seq s) (str (str/lower-case (subs s 0 1)) (subs s 1)) s)))

(defn finding-of
  "One missed fail rule of the judge's answer, as a finding in the
  audit's shape. A rule the film could not measure names the fields the
  take lacks."
  [rule]
  (let [metric (:metric rule)
        fields (str/join ", " (map str (:missing rule)))
        fields (if (str/blank? fields) (str "the fields " metric " needs") fields)]
    {:key (clip (str "film/" (:name rule)) 64)
     :severity "error"
     :class "film"
     :subject (clip metric 200)
     :sentence (clip (if (= "unmeasured" (word (:verdict rule)))
                       (str "The film could not measure " metric " (missing " fields
                            "), so it cannot show " (lower-first (:why rule)))
                       (str "The film missed " (:name rule) ": " (:why rule)))
                     240)
     :remedy (clip (if (= "unmeasured" (word (:verdict rule)))
                     (str "Film it again with " fields " in its take, so " metric
                          " can be measured.")
                     (str "Revise the org so its film holds " metric " "
                          (:op rule) " " (:threshold rule) ", and film it again."))
                   240)}))

(defn landing
  "Where a film lands on its plan, from the film rules' `answer`: the
  door and its input. No fail rule missed attaches `film_url`; a red
  film records its missed fail rules, at `head_sha`. A fail rule the
  film could not measure is missed, unless its `unmeasured` is `pass`."
  [answer {:keys [film_url head_sha warnings]}]
  (if-some [missed (seq (filter missed? (:rules answer)))]
    {:action :record_film_findings
     :input {:head_sha head_sha :findings (into (mapv finding-of missed) warnings)}}
    {:action :attach_film
     :input (cond-> {:film_url film_url}
              (seq warnings) (assoc :findings (vec warnings)))}))

(defn land!
  "Lands the film on the plan `plan-id` through the door `landing`
  names. `opts` carry the engine's own principal: record_film_findings
  is its hand alone."
  [eng plan-id answer film opts]
  (let [{:keys [action input]} (landing answer film)]
    (inv/invoke! eng :org_plan plan-id action input opts)))
