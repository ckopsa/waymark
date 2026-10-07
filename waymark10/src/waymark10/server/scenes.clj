(ns waymark10.server.scenes
  "The scene and the take (docs/spec-scenes.md): a demo written as data,
  and the record of one performance of it.

  A SCENE IS WRITTEN LOOSE AND JUDGED AT ONE DOOR. `given` and `shots`
  are lists of maps the schema does not look into, so a draft may be
  saved half written. `check` reads every call and every shot against
  the kinds this engine serves and the surfaces its page names
  (`ui-routes/surfaces`), and refuses with the first one that cannot be
  performed: `shot 3: ...`. It runs nothing.

  THE WALK CARRIES THREE THINGS from one shot to the next: the names
  bound so far and the kind each holds, the kind of the row last
  opened, and the door last tapped. A `door:<action>` with no `@row` is
  judged against the row last opened, and `type` against the door last
  tapped. Where a kind is not known, the kind's own law is not judged.

  A TAKE IS WRITTEN ONCE, by the runner, and never moves. The engine
  stamps `ok` and `first_failing_shot` from the shots it was given."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.routes.ui :as ui-routes]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def kind :scene)

(def take-kind :take)

(def verbs
  "The one verb a shot has, in the order the spec lists them."
  ["open" "tap" "press" "type" "hold" "wait_for" "meanwhile"])

(def ops
  "What an expectation may compare with."
  ["=" "!=" "contains" ">=" "<=" "exists" "absent"])

;; ── reading a scene ─────────────────────────────────────────────────

(defn- plain
  "The scene with every map key a string, whatever hand wrote it."
  [x]
  (walk/postwalk
   (fn [v]
     (if (map? v)
       (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v])) v)
       v))
   x))

(defn- tick [x] (str "`" x "`"))

(defn- fail [& parts] {:problem (apply str parts)})

(def ^:private ref-pattern #"\$([A-Za-z_][A-Za-z0-9_]*)")

(defn- unbound
  "Why a value cannot stand: the first `$name` in it no call or shot
  before it bound; nil when every one is bound."
  [x bound]
  (when-some [nm (->> (tree-seq coll? seq x)
                      (filter string?)
                      (mapcat #(map second (re-seq ref-pattern %)))
                      (remove #(contains? bound %))
                      first)]
    (str "`$" nm "` is never bound.")))

(defn- row-rdef
  "The definition of the kind a row address names: `$ref` reads its
  binding, and /api/<plural>[/<id>] its plural. nil when it is not
  known."
  [s bound rdef-of]
  (let [s (str/trim (str s))]
    (if-some [[_ nm] (re-matches ref-pattern s)]
      (let [r (get bound nm)] (when (map? r) r))
      (when-some [[_ plural] (re-matches #"/api/([^/?#]+)(?:/[^/?#]+)?" s)]
        (rdef-of plural)))))

(defn- names-of
  "The argument names a schema form takes; none for no form."
  [form]
  (into #{} (map name) (some-> form schema/entry-map keys)))

(defn- takes [rdef action]
  (names-of (get-in rdef [:actions (keyword action) :input])))

;; ── surfaces ────────────────────────────────────────────────────────

(def ^:private surface-exact
  (into #{} (comp (map :name) (remove #(str/includes? % "<"))) ui-routes/surfaces))

(def ^:private surface-stems
  "The registry's names that end in <…>, as the part before it."
  (into [] (keep #(second (re-matches #"(.*)<[^>]+>" (:name %)))) ui-routes/surfaces))

(defn- surface-of
  "A surface name as the registry holds it: {:name} for a whole name,
  {:stem :rest} for a completed stem, nil for neither."
  [nm]
  (if (contains? surface-exact nm)
    {:name nm}
    (some (fn [stem]
            (when (and (str/starts-with? nm stem) (< (count stem) (count nm)))
              {:stem stem :rest (subs nm (count stem))}))
          surface-stems)))

(defn- target
  "A shot's target, `surface` or `surface@row`: {:surface :rdef}, or
  {:problem}. The row is the one named, or the row last opened."
  [s {:keys [bound shown]} rdef-of]
  (let [[nm row] (str/split (str/trim (str s)) #"@" 2)
        sf (surface-of nm)
        rdef (if row (row-rdef row bound rdef-of) shown)
        tail (:rest sf)]
    (cond
      (nil? sf)
      (fail (tick nm) " is not a surface in the registry (GET /api/-/ui/surfaces).")

      (and row (unbound row bound))
      (fail (unbound row bound))

      (and row (str/starts-with? row "/") (nil? rdef))
      (fail (tick row) " names no kind this engine serves.")

      (and (= "nav." (:stem sf)) (nil? (rdef-of tail)))
      (fail (tick tail) " is not a kind this engine serves.")

      (and (contains? #{"door:" "door-shut:"} (:stem sf))
           rdef
           (nil? (get-in rdef [:actions (keyword tail)])))
      (fail (tick tail) " is not a door of " (name (:kind rdef)) ".")

      :else {:surface sf :rdef rdef})))

;; ── calls, expectations and shots ───────────────────────────────────

(defn- call-step
  "One `given` or `meanwhile` call, {as, tool, arguments, bind?}: the
  walk after it, or {:problem}. A waymark_invoke or waymark_pursue call
  is judged as the door it names; one with no `id` whose action the
  kind does not declare is the kind's create."
  [c st people rdef-of]
  (let [args (get c "arguments")
        k (some-> (get args "kind") str)
        rdef (when k (rdef-of k))
        action (some-> (get args "action") str)
        door (when (and rdef action) (get-in rdef [:actions (keyword action)]))
        acts? (contains? #{"waymark_invoke" "waymark_pursue"} (get c "tool"))
        create? (and acts? rdef (nil? door) (nil? (get args "id")))
        names (if create?
                (names-of (or (:create-schema rdef) (:schema rdef)))
                (names-of (:input door)))
        input (get args "input")
        stray (when (and acts? rdef (map? input))
                (first (remove names (keys input))))]
    (cond
      (not (map? c))
      (fail "a call is a map of `as`, `tool` and `arguments`.")

      (not (some #{(str (get c "as"))} people))
      (fail "`as` names " (tick (get c "as")) ", which is not in the cast.")

      (str/blank? (str (get c "tool")))
      (fail "a call names its `tool`.")

      (and k (nil? rdef))
      (fail (tick k) " is not a kind this engine serves.")

      (and acts? (nil? rdef))
      (fail "a " (get c "tool") " call names its `kind`.")

      (and acts? (nil? door) (not create?))
      (fail (tick action) " is not a door of " k ".")

      (some? stray)
      (fail (tick stray) " is not a field " (tick action) " takes.")

      (unbound args (:bound st))
      (fail (unbound args (:bound st)))

      :else
      (cond-> (assoc st :found rdef)
        (some? (get c "bind"))
        (assoc-in [:bound (str (get c "bind"))] (or rdef true))))))

(defn- check-problem
  "Why one expectation cannot be read; nil when it can."
  [where c st rdef-of]
  (cond
    (not (map? c))
    "an expectation is a map of `path`, `op` and `value`."

    (not (some #{(get c "op")} ops))
    (str (tick (get c "op")) " is not an op; the ops are " (str/join " " ops) ".")

    (str/blank? (str (get c "path")))
    "an expectation names its `path`."

    (and (= "envelope" where) (str/blank? (str (get c "self"))))
    "an envelope expectation names its `self`."

    (= "screen" where)
    (or (:problem (target (get c "surface") st rdef-of))
        (unbound (get c "value") (:bound st)))

    :else (unbound [(get c "self") (get c "value")] (:bound st))))

(defn- expect-problem
  "Why an `expect` cannot be read; nil when it can."
  [e st rdef-of]
  (cond
    (not (map? e))
    "`expect` is a map of `envelope` and `screen`."

    (seq (remove #{"envelope" "screen"} (keys e)))
    (str "`expect` holds `envelope` and `screen`, and not "
         (tick (first (remove #{"envelope" "screen"} (keys e)))) ".")

    :else
    (some (fn [where]
            (some #(check-problem where % st rdef-of) (get e where)))
          ["envelope" "screen"])))

(defn- verb-step
  "One shot's verb: the walk after it, or {:problem}."
  [verb v st people rdef-of]
  (let [bound (:bound st)]
    (case verb
      "open"
      (let [self (get v "self")
            coll (get v "collection")
            rdef (if self (row-rdef self bound rdef-of) (some-> coll str rdef-of))]
        (cond
          (not (or self coll))
          (fail "`open` names `self` or `collection`.")

          (and self (unbound self bound))
          (fail (unbound self bound))

          (and (nil? rdef) (or coll (str/starts-with? (str self) "/")))
          (fail (tick (or coll self)) " names no kind this engine serves.")

          :else (assoc st :shown rdef :door nil :found rdef)))

      ("tap" "press")
      (let [tg (target (get v "surface") st rdef-of)]
        (cond
          (:problem tg) tg

          (= "door:" (get-in tg [:surface :stem]))
          (assoc st :door {:rdef (:rdef tg) :action (get-in tg [:surface :rest])})

          ;; the tracker opens whatever door heads the plan
          (= "tracker.go" (get-in tg [:surface :name]))
          (assoc st :door nil)

          :else st))

      "type"
      (let [tg (target (get v "field") st rdef-of)
            arg (get-in tg [:surface :rest])
            {:keys [rdef action]} (:door st)]
        (cond
          (:problem tg) tg

          (not= "dialog.field:" (get-in tg [:surface :stem]))
          (fail "`type` names its field as `dialog.field:<name>`.")

          (and rdef (not (contains? (takes rdef action)
                                    (first (str/split arg #"\.")))))
          (fail (tick arg) " is not a field " (tick action) " takes.")

          (unbound (get v "value") bound)
          (fail (unbound (get v "value") bound))

          :else st))

      "hold"
      (cond
        (not (pos-int? (get v "ms")))
        (fail "`hold` names `ms`, a whole number above zero.")

        (some? (get v "on"))
        (let [tg (target (get v "on") st rdef-of)] (if (:problem tg) tg st))

        :else st)

      "wait_for"
      (if (pos-int? (get v "within_ms"))
        (if-some [p (expect-problem (get v "expect") st rdef-of)] (fail p) st)
        (fail "`wait_for` names `within_ms`, a whole number above zero."))

      "meanwhile"
      (call-step v st people rdef-of))))

(defn- shot-step
  "One shot: the walk after it, or {:problem}."
  [shot st people rdef-of]
  (if-not (map? shot)
    (fail "a shot is a map with one verb.")
    (let [named (filter (set verbs) (keys shot))
          stray (remove (into #{"say" "expect" "bind"} verbs) (keys shot))
          verb (first named)]
      (cond
        (seq stray)
        (fail "unknown verb " (tick (first stray)) "; the verbs are "
              (str/join ", " verbs) ".")

        (not= 1 (count named))
        (fail "a shot has one verb, and this one has " (count named) ".")

        :else
        (let [v (get shot verb)
              st' (verb-step verb (if (map? v) v {}) st people rdef-of)
              st' (cond-> st'
                    (and (nil? (:problem st')) (some? (get shot "bind")))
                    (assoc-in [:bound (str (get shot "bind"))]
                              (or (:found st') true)))]
          (if-some [p (when (and (nil? (:problem st')) (contains? shot "expect"))
                        (expect-problem (get shot "expect") st' rdef-of))]
            (fail p)
            st'))))))

(defn problem-of
  "The first call or shot of a scene's data that cannot be performed,
  as `given N: <why>` or `shot N: <why>`; nil when every one can.
  `rdef-of` is the ctx's registry consult."
  [data rdef-of]
  (let [scene (plain data)
        people (mapv str (get scene "cast"))
        run (fn [st label step items]
              (reduce (fn [st [i item]]
                        (let [st' (step item (dissoc st :found) people rdef-of)]
                          (if-some [p (:problem st')]
                            (reduced (fail label " " (inc i) ": " p))
                            st')))
                      st
                      (map-indexed vector items)))
        st (run {:bound {}} "given" call-step (get scene "given"))]
    (:problem (if (:problem st) st (run st "shot" shot-step (get scene "shots"))))))

;; ── guards and handlers ─────────────────────────────────────────────

(g/defguard every-shot-can-be-performed
  {:reads [:storage]
   :vars [:problem]
   :open "Revise the scene so the call or shot named can be performed, then check it again."
   :explain "A scene is ready when every call and shot names what this house serves: {problem}"}
  [row _inp ctx]
  (if-some [rdef-of (:rdef-of ctx)]
    (if-some [problem (problem-of (:data row) rdef-of)]
      (t/deny {:vars {:problem problem}})
      (t/allow))
    ;; no registry in scope (a render probe): the write path carries it
    (t/allow)))

(g/defguard the-take-is-of-a-version-the-scene-had
  {:judges [:scene :scene_version]
   :reads [:storage]
   :open "Name a scene you can see, and a version from 1 up to the one it holds now."
   :explain "A take is of a scene, at a version that scene has had."}
  [_row inp ctx]
  (if-some [read (:read ctx)]
    (let [scene (some->> (:scene inp) str not-empty (read kind))
          version (get-in scene [:data :version])]
      (if (and scene (int? version) (int? (:scene_version inp))
               (<= 1 (:scene_version inp) version))
        (t/allow)
        (t/deny)))
    (t/allow)))

(defn- born
  "The birth stamps: the author is the principal, and the version is 1."
  [row ctx]
  (update row :data assoc
          :author (str (get-in ctx [:principal :id]))
          :version 1))

(defn- take-born
  "The verdict the engine reads off the shots: the first that failed."
  [row _ctx]
  (let [failing (first (keep #(when-not (:ok %) (:shot %))
                             (get-in row [:data :shots])))]
    (update row :data assoc :first_failing_shot failing :ok (nil? failing))))

;; ── the kinds ───────────────────────────────────────────────────────

(def ^:private offer-fields
  "What the author writes: the create model."
  [[:title {:examples ["Quests, from a dotted Complete"]
            :x-display {:label "Title"
                        :help "One line that names the demo."}}
    [:string {:min 1 :max 120}]]
   [:seed {:x-display {:label "Seed"
                       :help "The demo seed the runner loads before the first call."}}
    [:string {:min 1 :max 80}]]
   [:devices {:x-display {:label "Devices"
                          :help "Each device the scene is performed on; one take per device."}}
    [:vector {:min 1 :max 2} [:enum "phone" "desktop"]]]
   [:cast {:x-display {:label "Cast"
                       :help "Cast ids from the seed. The first is the person filmed."}}
    [:vector {:min 1 :max 12} [:string {:min 1 :max 128}]]]
   [:given {:optional true
            :x-display {:raw true
                        :label "Given"
                        :help "Setup calls made before filming, in order: {as, tool, arguments, bind?}."
                        :spelled-by-hand "Each call's arguments are its tool's own, so no fixed sub-form can offer them."}}
    [:maybe [:vector {:max 40} [:map-of :keyword :any]]]]
   [:shots {:x-display {:raw true
                        :label "Shots"
                        :help "The filmed moments, in order. Each has one verb, and may have `say`, `expect` and `bind`."
                        :spelled-by-hand "Each verb takes its own keys, so no fixed sub-form can offer them."}}
    [:vector {:min 1 :max 200} [:map-of :keyword :any]]]])

(defhandler revise-scene [row inp _ctx]
  (-> row
      (update :data merge (into {} (remove (comp nil? val))
                                (select-keys inp (map first offer-fields))))
      (update-in [:data :version] (fnil inc 1))))

(defresource scene
  {:kind :scene
   :plural "scenes"
   :nav :secondary
   :states [:draft :ready :retired]
   :initial :draft
   :summary "{data.title} · v{data.version} · {state}"
   :label-template "{data.title}"
   :schema
   (-> [:map]
       (into offer-fields)
       (into [[:author {:optional true
                        :x-ref {:principal true}
                        :x-display {:raw true
                                    :label "Who wrote it"
                                    :help "The principal that created the scene, stamped by the engine."}}
               [:maybe [:string {:max 128}]]]
              [:version {:optional true
                         :x-display {:label "Version"
                                     :help "1 at birth, and one more at each revise. A take names the version it performed."}}
               [:maybe [:int {:min 1}]]]]))
   :create-schema (into [:map] offer-fields)
   :filterable {:state #{:eq :in}
                :seed #{:eq}
                :author #{:eq}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :default-filters {:state "draft,ready"}
   :on-create born
   :actions
   {:check
    {:from #{:draft} :to :ready
     :guards [every-shot-can-be-performed]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Check" :order 1
               :description "Judge every call and shot against this house; nothing is run"}}
    :revise
    {:from #{:draft :ready} :to :draft
     :input (into [:map]
                  (map (fn [[k props form]]
                         [k (assoc props :optional true) [:maybe form]]))
                  offer-fields)
     :handler revise-scene
     :safety {:idempotent false :reversible false :confirm false
              :one-way "The scene takes the fields given, its version goes up by one, and it must be checked again."}
     :display {:label "Revise" :order 2
               :description "Rewrite any of the scene's fields; it is a draft again"}}
    :retire
    {:from #{:draft :ready} :to :retired
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The scene is no longer performed. Its takes stay."}
     :display {:label "Retire" :order 3
               :description "Stop performing this scene"}}
    :restore
    {:from #{:retired} :to :draft
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Restore" :order 4
               :description "Bring the scene back as a draft; it must be checked again"}}}})

(def ^:private shot-result
  "What the runner saw at one shot."
  [:map
   [:shot {:x-display {:label "Shot" :help "The shot's place in the scene, counted from 1."}}
    [:int {:min 1}]]
   [:ok {:x-display {:label "Passed" :help "The verb was performed and every expectation held."}}
    :boolean]
   [:pressed {:optional true
              :x-display {:label "Pressed" :help "The surface the runner acted on, as it resolved."}}
    [:maybe [:string {:max 300}]]]
   [:expects {:optional true
              :x-display {:raw true
                          :label "Expectations"
                          :help "One entry per expectation: what was asked, what was read, and whether it held."
                          :spelled-by-hand "An entry repeats its expectation's own keys beside what was read."}}
    [:maybe [:vector {:max 40} [:map-of :keyword :any]]]]
   [:still {:optional true
            :x-display {:raw true :label "Still" :help "Where the still of this shot is kept."}}
    [:maybe [:string {:max 500}]]]])

(def ^:private take-fields
  "What the runner writes: the create model."
  [[:scene {:kind :scene
            :x-display {:raw true :label "The scene"}}
    :waymark/ref]
   [:scene_version {:x-display {:label "Scene version"
                                :help "The version of the scene that was performed."}}
    [:int {:min 1}]]
   [:device {:x-display {:label "Device" :help "The device it was performed on."
                         :choices {"phone" "A phone, held upright."
                                   "desktop" "A desktop browser window."}}}
    [:enum "phone" "desktop"]]
   [:commit {:x-display {:raw true :label "Commit" :help "The commit of the house that was filmed."}}
    [:string {:min 1 :max 64}]]
   [:film {:optional true
           :x-display {:raw true :label "Film" :help "The id of the film this take made. Empty when none was rendered."}}
    [:maybe [:string {:max 200}]]]
   [:shots {:x-display {:label "Shots" :help "One result per shot performed, in order. A take that stopped holds the failing shot last."}}
    [:vector {:max 200} shot-result]]])

(defresource take
  {:kind :take
   :plural "takes"
   :nav :secondary
   :states [:recorded]
   :initial :recorded
   :terminal #{:recorded}
   :summary "{data.device} · v{data.scene_version} · {data.commit}"
   :label-template "{data.device} · v{data.scene_version} · {data.commit}"
   :schema
   (-> [:map]
       (into take-fields)
       (into [[:ok {:optional true
                    :x-display {:label "Every shot passed"
                                :help "Stamped by the engine from the shots."}}
               [:maybe :boolean]]
              [:first_failing_shot {:optional true
                                    :x-display {:label "First failing shot"
                                                :help "The first shot that did not pass, stamped by the engine. Empty when every shot passed."}}
               [:maybe [:int {:min 1}]]]]))
   :create-schema (into [:map] take-fields)
   :create-guards [the-take-is-of-a-version-the-scene-had]
   :on-create take-born
   :filterable {:scene #{:eq}
                :device #{:eq}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :links [{:rel "scene" :kind :scene
            :href "/api/scenes/{data.scene}"
            :summary "The scene this take performed"}]})
