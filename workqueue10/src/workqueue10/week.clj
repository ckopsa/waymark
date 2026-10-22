(ns workqueue10.week
  "An org's first week as a script, played headlessly (ticket f084a59c,
  docs/orgkit.md § The week): `resources/<org>/week.edn` names each
  day's calls and who makes them, and this plays them in-process and
  reports on each.

    clojure -M -m workqueue10.week <org-dir> [--json]

  THE ORG BOOTS AS AN ORG CLONE. The engine is the one `main` builds for
  WAYMARK10_ORG with FACTORY10_ONLY=1 and WAYMARK10_CLONE=1: the
  factory's kinds and the org's, the demo clock, and the org's seed with
  its rehearsal, over a memory store. The clock starts at 09:00 UTC on
  `:day-one` and moves P1D between days through the `clock_shift` door.

  EACH CALL GOES THROUGH THE WIRE'S DOORS, AS ITS CAST. The ring handler
  answers it, so a refusal is the 409 a client reads and a hold is the
  202 the router mints. `:world` is a rehearsal cast member (the call's
  `:cast`, or the first), `:person` the seed's `:owner`, and `:mayor` an
  agent acting for the owner, wearing a grant the owner mints over the
  doors the script's mayor calls name. A day's `:person-allows` is
  `:today` unless it says `:yesterday` or `:none`: after the day's calls
  the owner allows each call still held, as the director does, since a
  held call expires 24h after it is made. `:yesterday` allows them at
  the start of the next day instead, and each such allow carries the
  warning `[week-allow-late]`: its call had expired by then.

  EACH PARAM IS A STAND-IN. Every `[:param name]` the org's seed names
  is filled with the string `week-<name>` (ticket 8db1541c): the week
  never talks to an outside service, so a real secret has no place in
  it, and it never reads WAYMARK10_SEED_PARAMS, so CI and a bench play
  the same week.

  A REFUSAL IS PART OF THE REPORT. The player throws only for a broken
  script: a row name no earlier call made, a cast it does not know, a
  kind the engine does not serve. Nothing renders and nothing leaves
  the process."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.server.clock-shift :as clock-shift]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.org :as org-boot]
            [waymark10.server.seed :as seed]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire]
            [workqueue10.main :as main])
  (:import (java.io File)
           (java.time Instant LocalDate ZoneOffset)))

(set! *warn-on-reflection* true)

(def ^:private casts #{:world :mayor :person})

(def ^:private max-days
  "How far a week may run: the demo clock moves at most 14 days."
  15)

(defn- broken
  "A broken script's refusal: the one thing the player throws for."
  [sentence data]
  (ex-info sentence (assoc data ::broken true)))

;; ── the script ────────────────────────────────────────────────────────────────

(defn read-week
  "The week script of the org at `dir`: `resources/<org>/week.edn`."
  [^File dir org]
  (let [f (io/file dir "resources" (str org) "week.edn")]
    (when-not (.isFile f)
      (throw (broken (str "The org `" org "` has no week: " (.getPath f) " is not a file.")
                     {:file (.getPath f)})))
    (edn/read-string (slurp f))))

(defn- day-one
  "09:00 UTC on the week's `:day-one`."
  ^Instant [week]
  (let [d (try (LocalDate/parse (str (:day-one week)))
               (catch Exception _
                 (throw (broken (str "The week's :day-one is `" (:day-one week)
                                     "`, and it is not a date like 2026-10-21.")
                                {}))))]
    (.plusSeconds (.toInstant (.atStartOfDay ^LocalDate d) ZoneOffset/UTC) 32400)))

(defn- as-of [c] (some-> (:as c) name keyword))

(defn- checked!
  "Throw for a script the player cannot play: days that do not climb
  from 1 within the clock's 14, a `:card` that is not text of 1 to 280
  characters, or a call with no known cast, kind or door. `rdefs` is
  the engine's resources."
  [week rdefs]
  (let [days (:days week)
        ns' (map :day days)]
    (when-not (and (seq days) (every? pos-int? ns') (apply < ns') (<= (last ns') max-days))
      (throw (broken (str "The week's :days are numbered " (pr-str (vec ns'))
                          ": each a :day from 1, climbing, and none past " max-days ".")
                     {})))
    (doseq [[whose card] (cons ["The week's" (:card week)]
                               (map (fn [d] [(str "Day " (:day d) "'s") (:card d)]) days))
            :when (some? card)]
      (when-not (and (string? card) (not (str/blank? card)) (<= (count card) 280))
        (throw (broken (str whose " :card is `" (pr-str card)
                            "`, and a card is text of 1 to 280 characters.")
                       {}))))
    (doseq [{:keys [day calls]} days
            {:keys [kind action] :as c} calls]
      (when-not (casts (as-of c))
        (throw (broken (str "Day " day " has a call made as `" (pr-str (:as c))
                            "`, and the week's cast is :world, :mayor and :person.")
                       {:call c})))
      (when-not (get rdefs (keyword (str kind)))
        (throw (broken (str "Day " day " calls `" kind "`, a kind this engine does not serve.")
                       {:call c})))
      (when (str/blank? (str action))
        (throw (broken (str "Day " day " has a `" kind "` call that names no :action.")
                       {:call c}))))))

;; ── the engine ────────────────────────────────────────────────────────────────

(defn stand-in-params
  "The boot's params for the org `org` under `orgs-dir`: each param its
  seed names, filled with the stand-in `week-<name>`."
  [org orgs-dir]
  (into {} (map (fn [k] [k (str "week-" (name k))]))
        (seed/param-names (org-boot/read-seed org orgs-dir))))

(defn boot!
  "An engine for the org `org` under `orgs-dir`, as an org clone boots
  one, over a memory store whose real clock stands at `now`, seeded.
  The boot's lines go to stderr, so the report is stdout's alone."
  [org orgs-dir now]
  (binding [*out* *err*]
    (let [eng (clock-shift/install!
               (engine/engine
                (clock-shift/with-clock
                 {:storage (memory/storage)
                  :resources (main/with-org (main/factory-only-resources) org orgs-dir)
                  :suppress-mirror-refresh true
                  :clone? true
                  :now-fn (constantly now)})))]
      (main/org-on-boot! eng org {:params (stand-in-params org orgs-dir) :orgs-dir orgs-dir})
      eng)))

(defn- row-of [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/load-row st tx kind (str id) {})))))

(defn- call!
  "One request through the ring handler `h`. → {:status :headers :body},
  the body read as JSON."
  [h uri headers body]
  (let [resp (h (cond-> {:request-method :post :uri uri :headers headers}
                  (some? body) (-> (assoc :body (wire/write-json body))
                                   (assoc-in [:headers "content-type"] "application/json"))))
        b (:body resp)
        s (cond (nil? b) nil (string? b) b :else (slurp b))]
    {:status (:status resp)
     :headers (:headers resp)
     :body (when-not (str/blank? s)
             (try (wire/read-json s) (catch Exception _ nil)))}))

;; ── the cast ──────────────────────────────────────────────────────────────────

(defn- cast-of
  "Who the week's cast are on this org's seed: the owner's member id,
  the rehearsal's cast by key, and the mayor agent's id."
  [seed org]
  (let [owner (get-in seed [:cast :owner :id])]
    (when-not (and (string? owner) (seq owner))
      (throw (broken (str "The seed of `" org "` names no :owner with a plain :id, so nobody is the week's person.")
                     {})))
    {:owner owner
     :world (into (sorted-map) (get-in seed [:rehearsal :cast]))
     :mayor (str org "-mayor")}))

(defn- person [id] (t/principal {:id id :display id}))

(defn- mayor-grant!
  "The mayor's leash: minted by the owner over every door the script's
  mayor calls name, and accepted. → its id, or nil for a week the mayor
  makes no call in."
  [eng {:keys [owner mayor]} week]
  (let [doors (reduce (fn [m {:keys [kind action] :as c}]
                        (cond-> m
                          (= :mayor (as-of c))
                          (update (str kind) (fnil conj (sorted-set)) (str action))))
                      (sorted-map)
                      (mapcat :calls (:days week)))]
    (when (seq doors)
      (let [g (:row (inv/create! eng :grant
                                 {:audience mayor
                                  :scope (mapv (fn [[k as]] {:kind k :actions (vec as)}) doors)}
                                 {:principal (person owner)}))]
        (inv/invoke! eng :grant (str (:id g)) :accept {}
                     {:principal (assoc (t/principal {:id mayor :type :agent :display mayor})
                                        :acts-for owner)})
        (str (:id g))))))

(defn- headers-of
  "The dev principal headers the call's cast presents."
  [{:keys [owner world mayor grant]} c]
  (case (as-of c)
    :person {"x-waymark-principal" owner "x-waymark-actor-type" "human"}
    :world (let [k (or (some-> (:cast c) name keyword) (first (keys world)))
                 id (get-in world [k :id])]
             (when-not (and (string? id) (seq id))
               (throw (broken (str "A call is made as :world" (when k (str " `" (name k) "`"))
                                   ", and the seed's rehearsal casts no such member.")
                              {:call c})))
             {"x-waymark-principal" id "x-waymark-actor-type" "human"})
    :mayor (cond-> {"x-waymark-principal" mayor
                    "x-waymark-actor-type" "agent"
                    "x-waymark-acts-for" owner}
             grant (assoc "x-waymark-grant" grant))))

;; ── one call ──────────────────────────────────────────────────────────────────

(defn- id-of [names n day]
  (or (get names (str n))
      (throw (broken (str "Day " day " names the row `" n "`, and no earlier call made it.")
                     {:row n}))))

(defn- resolved
  "The input with each {\"$row\" name} as the id of the row it names."
  [names day v]
  (walk/postwalk
   (fn [x]
     (if (and (map? x) (= 1 (count x)) (contains? x "$row"))
       (id-of names (get x "$row") day)
       x))
   v))

(defn- door
  "Where the call goes: a row's action, or one of the kind's create
  doors when the call names no :row. → {:uri :id}."
  [rdef {:keys [kind action row]} names day]
  (let [plural (:plural rdef)
        creates (map name (:create-action-names rdef))]
    (cond
      (some? row)
      (let [id (id-of names row day)]
        {:uri (str "/api/" plural "/" id "/-/" action) :id id})

      (= (str action) (first creates))
      {:uri (str "/api/" plural)}

      (some #{(str action)} creates)
      {:uri (str "/api/" plural "/-/" action)}

      :else
      (throw (broken (str "Day " day " calls `" kind "` `" action "` with no :row, and `"
                          action "` is not one of its create doors.")
                     {:kind kind :action action})))))

(defn- fenced
  "`headers` with the version a fenced door asks for."
  [eng rdef kind id action headers]
  (if (get-in rdef [:actions (keyword (str action)) :safety :fence])
    (assoc headers "if-match"
           (inv/etag kind id (:version (inv/decode-row rdef (row-of eng kind id)))))
    headers))

(defn- refused [entry {:keys [status body]}]
  (cond-> (assoc entry :outcome "refused" :status status
                 :detail (str (or (:detail body) (:title body) (str "the door answered " status))))
    (:guard body) (assoc :guard (str (:guard body)))))

(defn- played
  "One call, made. → its report entry: `moved`, `held` or `refused`."
  [eng h cast names day {:keys [action] :as c}]
  (let [kind (keyword (str (:kind c)))
        rdef (get (inv/resources eng) kind)
        {:keys [uri id]} (door rdef c names day)
        hs (cond->> (headers-of cast c)
             id (fenced eng rdef kind id action))
        res (call! h uri hs (resolved names day (or (:input c) {})))
        {:keys [status body]} res
        entry (cond-> {:day day :as (name (as-of c)) :kind (str (:kind c)) :action (str action)}
                (:row c) (assoc :row (str (:row c)))
                (:name c) (assoc :name (str (:name c))))]
    (cond
      (and (= 202 status) (:held body))
      (assoc entry :outcome "held" :held_call (str (:held_call body)) :detail (str (:why body)))

      (<= 200 status 299)
      ;; a create names its row in Location, /api/<plural>/<id>
      (cond-> (assoc entry :outcome "moved"
                     :id (str (or (some-> (get (:headers res) "Location") (str/split #"/") last)
                                  id)))
        (:state body) (assoc :state (str (:state body))))

      :else (refused entry res))))

(defn- allows-of
  "A day's `:person-allows`: `:none`, `:yesterday`, or else `:today`."
  [d]
  (let [m (some-> (:person-allows d) name keyword)]
    (if (#{:none :yesterday} m) m :today)))

(defn- allowed
  "The owner's tap on one held call. → its entry: `moved` when the
  replay landed, `refused` with the reason otherwise, and a
  `[week-allow-late]` warning when the call was held on an earlier day,
  so had expired (held calls expire 24h after they are made)."
  [eng h {:keys [owner]} day held]
  (let [hid (:held_call held)
        res (call! h (str "/api/held_calls/" hid "/-/allow")
                   {"x-waymark-principal" owner "x-waymark-actor-type" "human"} nil)
        row (row-of eng :held_call hid)
        state (some-> (:state row) name)
        entry (cond-> {:day day :as "person" :kind "held_call" :action "allow" :held_call hid
                       :allows (select-keys held [:day :as :kind :action :row :name])}
                (:row held) (assoc :row (:row held))
                (< (long (:day held)) (long day))
                (assoc :warning (str "[week-allow-late] day " (:day held) "'s held call is allowed on day "
                                     day ", after it expired: a held call expires 24h after it is made.")))]
    (if (and (<= 200 (long (:status res)) 299) (= "done" state))
      (assoc entry :outcome "moved")
      (refused entry (cond-> res
                       (<= 200 (long (:status res)) 299)
                       (assoc :body {:detail (str "The held call ended " state
                                                  (when-some [r (get-in row [:data :reason])]
                                                    (str ": " r)))}))))))

;; ── the week ──────────────────────────────────────────────────────────────────

(defn- shift! [eng owner days]
  (inv/create! eng :clock_shift {:by (str "P" days "D")} {:principal (person owner)}))

(defn- goal-of
  "The payoff: the rows of the goal's kind in its state."
  [eng {:keys [kind state] :as goal}]
  (when goal
    (let [st (:storage eng)
          rows (store/with-tx st
                 (fn [tx] (vec (store/query-rows st tx (keyword (str kind))
                                                 {:state (str state)} {:limit 1000}))))]
      {:kind (str kind) :state (str state) :count (count rows)
       :rows (mapv #(str (:id %)) rows)})))

(defn play
  "Play the first week of the org at `org-dir`: its own week.edn, or
  `week` when given. → the week report: each call's entry in order,
  `person_asks` (the calls held for the owner), `still_held`, and the
  `goal`'s rows. Throws only for a broken script."
  ([org-dir] (play org-dir nil))
  ([org-dir week]
   (let [dir (.getAbsoluteFile (io/file org-dir))
         org (org-boot/org-name (.getName dir))
         orgs-dir (.getPath (.getParentFile dir))
         week (or week (read-week dir org))
         start (day-one week)
         eng (boot! org orgs-dir start)
         _ (checked! week (inv/resources eng))
         h (engine/handler eng)
         cast (cast-of (org-boot/read-seed org orgs-dir) org)
         cast (assoc cast :grant (mayor-grant! eng cast week))]
     (loop [[d & more] (:days week), prev nil, names {}, calls [], held []]
       (if-not d
         {:org org
          :day_one (str (:day-one week))
          :calls calls
          :person_asks (count (filter #(= "held" (:outcome %)) calls))
          :still_held (count held)
          :warnings (vec (keep :warning calls))
          :goal (goal-of eng (:goal week))}
         (let [day (:day d)
               _ (when prev (shift! eng (:owner cast) (- day prev)))
               mode (allows-of d)
               tap (fn [hs] (mapv #(allowed eng h cast day %) hs))
               morning (if (= :yesterday mode) (tap held) [])
               [names today] (reduce (fn [[names out] c]
                                       (let [e (played eng h cast names day c)]
                                         [(cond-> names
                                            (and (:name c) (= "moved" (:outcome e)))
                                            (assoc (str (:name c)) (:id e)))
                                          (conj out e)]))
                                     [names []]
                                     (:calls d))
               held (into (if (= :yesterday mode) [] held)
                          (filter #(= "held" (:outcome %)))
                          today)
               evening (if (= :today mode) (tap held) [])]
           (recur more day names (-> calls (into morning) (into today) (into evening))
                  (if (= :today mode) [] held))))))))

(defn text
  "The report as lines a person reads."
  [{:keys [org day_one calls person_asks still_held goal]}]
  (str/join
   "\n"
   (concat
    [(str "The first week of " org ", from " day_one ":")]
    (for [{:keys [day as kind action row name outcome guard detail warning]} calls]
      (str "day " day "  " as "  " kind " " action
           (when-some [r (or row name)] (str " " r))
           "  " outcome
           (when (= "refused" outcome)
             (str "  " (when guard (str guard ": ")) detail))
           (when warning (str "  " warning))))
    [(str "person asks: " person_asks ", still held: " still_held)]
    (when goal
      [(str "goal: " (:kind goal) " " (:state goal) ", " (:count goal) " row(s)")]))))

(defn -main
  [& args]
  (let [json? (some #{"--json"} args)
        [dir] (remove #{"--json"} args)]
    (try
      (when-not dir
        (throw (broken "Name the org's directory: clojure -M -m workqueue10.week <org-dir> [--json]" {})))
      (let [report (play dir)]
        (println (if json? (wire/write-json report) (text report)))
        (flush)
        (System/exit 0))
      (catch clojure.lang.ExceptionInfo e
        (binding [*out* *err*]
          (println (str "workqueue10.week: " (ex-message e))))
        (System/exit (if (::broken (ex-data e)) 2 1))))))
