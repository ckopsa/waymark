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
            [waymark10.server.invoke :as inv])
  (:import (java.time LocalDate)))

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

(defn scene
  "The first-week scene of the org whose seed is `seed`, filmed on the
  clone at `clone_url`, made at `head_sha`, its day 1 on `day_one`
  (YYYY-MM-DD): {:scene <a scene's create input>, :clone {:url
  :head_sha}, :day_one}. `kind` names the kind the world knocks on, when
  the seed's first create is not it. `:day_one` is the day the clone is
  stood up with (clone__up's `day_one`), so its seed's [:days n] count
  from the day the captions' holes read (ticket bbf1f253)."
  [seed {:keys [clone_url head_sha day_one kind]}]
  (let [t (template)
        owner (the-owner seed)
        values {:seed (:seed seed)
                :org_display (or (:label (first (:domains seed))) (:org seed))
                :owner (:id owner)
                :owner_display (or (:display owner) (:id owner))
                :kind (or kind (the-knocked-kind seed))
                :clone_url clone_url
                :head_sha head_sha}
        day-one (LocalDate/parse (str day_one))
        roles (get-in t [:template :roles])
        day (fn [k]
              (let [shots (fill (:day t)
                                (assoc values :day k
                                       :date (str (.plusDays day-one (dec k)))
                                       :next_date (str (.plusDays day-one k))))]
                (cond-> shots
                  (get roles k) (update (dec (count shots)) assoc :role (get roles k)))))]
    {:scene (assoc (fill (:scene t) values)
                   :shots (vec (concat (fill (:opening t) values)
                                       (mapcat day (range 1 (inc (get-in t [:template :days]))))
                                       (fill (:closing t) values))))
     :clone (fill (:clone t) values)
     :day_one (str day-one)}))

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

(defn org-scene
  "The first-week scene of the org at `org-dir`, at `head-sha`, its day 1
  on `day-one`, as the edn text the org-findings artifact carries. The
  audit's run stands up no clone, so the clone's url is empty: the
  director plays the scene on the clone it stands up at that head."
  [org-dir head-sha day-one]
  (pr-str (scene (read-seed org-dir)
                 {:clone_url "" :head_sha head-sha :day_one day-one})))

(defn -main
  "Print the first-week scene of the org at `org-dir` at `head-sha`, its
  day 1 on `day-one` (YYYY-MM-DD), else today."
  [org-dir head-sha & [day-one]]
  (print (org-scene org-dir head-sha (or day-one (str (LocalDate/now)))))
  (flush))

;; ── the film's landing ──────────────────────────────────────────────

(defn- clip [s n]
  (let [s (str s)] (if (> (count s) n) (subs s 0 n) s)))

(defn finding-of
  "One missed fail rule of the judge's answer, as a finding in the
  audit's shape."
  [rule]
  {:key (clip (str "film/" (:name rule)) 64)
   :severity "error"
   :class "film"
   :subject (clip (:metric rule) 200)
   :sentence (clip (str "The film missed " (:name rule) ": " (:why rule)) 240)
   :remedy (clip (str "Revise the org so its film holds " (:metric rule) " "
                      (:op rule) " " (:threshold rule) ", and film it again.")
                 240)})

(defn landing
  "Where a film lands on its plan, from the film rules' `answer`: the
  door and its input. No fail rule missed attaches `film_url`; a red
  film records its missed fail rules, at `head_sha`."
  [answer {:keys [film_url head_sha]}]
  (if-some [missed (seq (filter #(and (= "miss" (str (:verdict %)))
                                      (= "fail" (str (:severity %))))
                                (:rules answer)))]
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
