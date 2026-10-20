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
  at that head. The director plays it on the clone it stands up.

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
            [waymark10.server.invoke :as inv])
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
  "`form` with each {{param}} in its strings replaced by its value."
  [form values]
  (walk/postwalk
   (fn [x]
     (if (string? x)
       (reduce-kv (fn [s k v] (str/replace s (str "{{" (name k) "}}") (str v)))
                  x values)
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
  '_' as a space. `resources` are the org's declarations."
  [seed resources n]
  (let [by-kind (into {} (map (juxt #(name (:kind %)) identity)) resources)]
    (->> (the-org-kinds seed)
         (mapcat (fn [k]
                   (when-some [r (get by-kind k)]
                     (let [noun (or (:label r) (words k))
                           ends (into #{} (map name) (:terminal r))]
                       (for [s (map name (:states r))
                             :when (and (ends s) (not (loss-states s)))]
                         {:each_kind k
                          :each_state s
                          :each_one (str noun " " (words s))
                          :each_many (str (plural noun) " " (words s))})))))
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
  owner. With no `week` the scene is the audit's, and none plays."
  [seed {:keys [clone_url head_sha day_one kind brief resources week]}]
  (let [t (template)
        owner (the-owner seed)
        day-one (LocalDate/parse (str day_one))
        days (get-in t [:template :days])
        line (brief-line brief)
        kind (or kind (the-knocked-kind seed))
        values {:seed (:seed seed)
                :org_display (or (:label (first (:domains seed))) (:org seed))
                :owner (:id owner)
                :owner_display (or (:display owner) (:id owner))
                :kind kind
                :kind_plural (the-collection resources kind)
                :clone_url clone_url
                :head_sha head_sha
                :brief_line (or line "")
                :day_one (str day-one)
                :week_end (str (.plusDays day-one (long days)))}
        roles (get-in t [:template :roles])
        day (fn [k]
              (let [date (.plusDays day-one (long (dec k)))
                    shots (fill (:day t)
                                (assoc values :day k
                                       :date (str date)
                                       :weekday (.getDisplayName (.getDayOfWeek date)
                                                                 TextStyle/FULL Locale/ENGLISH)
                                       :next_date (str (.plusDays day-one (long k)))))]
                (cond-> shots
                  (get roles k) (update (dec (count shots)) assoc :role (get roles k))
                  true (with-calls (get (vec week) (dec k))))))
        actors (keep call-as (apply concat week))
        money (the-money seed resources)
        ;; four clauses at most: the ends, the money, then the taps
        per-kind (map #(fill (:closing_kind t) (merge values %))
                      (the-clauses seed resources (- 3 (count money))))
        per-money (map #(fill (:closing_money t) (merge values %)) money)
        closing (walk/postwalk
                 (fn [x]
                   (if (and (vector? x) (not (map-entry? x)))
                     (vec (mapcat #(case % "{{kinds}}" per-kind "{{money}}" per-money [%]) x))
                     x))
                 (fill (:closing t) values))]
    {:scene (assoc (cond-> (fill (:scene t) values)
                     (seq actors) (update :cast #(vec (distinct (concat % actors)))))
                   :shots (vec (concat (fill (if line (:opening t) (:opening_bare t)) values)
                                       (mapcat day (range 1 (inc days)))
                                       closing)))
     :clone (fill (:clone t) values)
     :day_one (str day-one)}))

(defn- read-brief
  "The brief in the org's org.edn at `org-dir`, its :brief; nil when it
  names none."
  [org-dir]
  (let [f (io/file org-dir "org.edn")]
    (when (.isFile f)
      (:brief (edn/read-string (slurp f))))))

(defn- read-seed
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

(defn- read-resources
  "The kinds the org at `org-dir` declares, its `<org>.org/resources`,
  loaded as conform loads them: with the org's src on the classpath."
  [org-dir org]
  (conform/with-org-classpath
    (io/file org-dir)
    #((requiring-resolve (symbol (str org ".org") "resources")))))

(defn org-scene
  "The first-week scene of the org at `org-dir`, at `head-sha`, its day 1
  on `day-one`, as the edn text the org-findings artifact carries, its
  opening on org.edn's :brief when it has one. The audit's run stands up
  no clone, so the clone's url is empty: the director plays the scene on
  the clone it stands up at that head."
  [org-dir head-sha day-one]
  (let [seed (read-seed org-dir)]
    (pr-str (scene seed
                   {:clone_url "" :head_sha head-sha :day_one day-one
                    :brief (read-brief org-dir)
                    :resources (read-resources org-dir (:org seed))}))))

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
  [answer {:keys [film_url head_sha]}]
  (if-some [missed (seq (filter missed? (:rules answer)))]
    {:action :record_film_findings
     :input {:head_sha head_sha :findings (mapv finding-of missed)}}
    {:action :attach_film
     :input {:film_url film_url}}))

(defn land!
  "Lands the film on the plan `plan-id` through the door `landing`
  names. `opts` carry the engine's own principal: record_film_findings
  is its hand alone."
  [eng plan-id answer film opts]
  (let [{:keys [action input]} (landing answer film)]
    (inv/invoke! eng :org_plan plan-id action input opts)))
