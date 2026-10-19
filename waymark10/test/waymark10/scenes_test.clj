(ns waymark10.scenes-test
  "The scene and the take (docs/spec-scenes.md): a well-formed scene
  checks ready, each refusal of `check` names its call or shot, and a
  take reads back with its shot results. Memory storage and the real
  engine; `check` runs nothing, so no case performs a shot."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.server.scenes :as scenes]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private chore
  "The row the shots act on: one door with two arguments, one without."
  (r/resource
   {:kind :chore
    :plural "chores"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]
     [:room {:optional true :x-display {:label "Room"}}
      [:maybe [:string {:max 40}]]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:rename {:from #{:open} :to :open
              :input [:map
                      [:title {:x-display {:label "Title"}}
                       [:string {:min 1 :max 80}]]
                      [:room {:optional true :x-display {:label "Room"}}
                       [:maybe [:string {:max 40}]]]]
              :handler (fn [row inp _ctx]
                         (update row :data merge (select-keys inp [:title :room])))
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Rename" :order 1}}
     :finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "The chore is done and stays done."}
              :display {:label "Finish" :order 2}}}}))

(def ^:private mayor (t/principal {:id "mayor" :display "Mayor"}))

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage) :resources [chore]}))

(defn- row-of [eng kind id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) kind)]
    (store/with-tx st
      (fn [tx]
        (some->> (store/load-row st tx kind (str id) {})
                 (inv/decode-row rdef))))))

(def ^:private well-formed
  {:title "Tidying, filmed"
   :seed "chores"
   :devices ["phone" "desktop"]
   :cast ["colton" "planner"]
   :given [{:as "planner" :tool "waymark_invoke"
            :arguments {:kind "chore" :action "create" :input {:title "Dishes"}}
            :bind "dishes"}]
   :shots [{:open {:collection "chores"} :say "Every chore of the house."}
           {:open {:self "$dishes"}}
           {:tap {:surface "door:rename@$dishes"}
            :expect {:screen [{:surface "dialog" :path "action" :op "=" :value "rename"}]}}
           {:type {:field "dialog.field:title" :value "Dishes, tonight"}}
           {:press {:surface "dialog.submit"}
            :expect {:envelope [{:self "$dishes" :path "data.title"
                                 :op "=" :value "Dishes, tonight"}]}}
           {:meanwhile {:as "planner" :tool "waymark_invoke"
                        :arguments {:kind "chore" :id "$dishes" :action "finish"}}}
           {:wait_for {:expect {:envelope [{:self "$dishes" :path "state"
                                            :op "=" :value "done"}]}
                       :within_ms 5000}}
           {:hold {:ms 800 :on "row"}}]})

(defn- write! [eng scene]
  (:id (:row (inv/create! eng :scene scene {:principal mayor}))))

(defn- walk!
  ([eng id action] (walk! eng id action {}))
  ([eng id action input]
   (inv/invoke! eng :scene (str id) action input
                {:principal mayor
                 :idempotency-key (str (random-uuid))})))

(defn- refusal
  "The refusal's sentence, or nil when the call went through."
  [f]
  (try (f) nil
       (catch Exception e (or (inv/problem-reason e) "refused"))))

(defn- why-not
  "Why `check` refuses the well-formed scene with shot `n` replaced."
  [eng n shot]
  (let [id (write! eng (assoc-in well-formed [:shots (dec n)] shot))
        why (refusal #(walk! eng id :check))]
    (is (= "draft" (name (:state (row-of eng :scene id)))) "a refused scene stays a draft")
    (str why)))

(deftest a-well-formed-scene-checks-ready
  (let [eng (fresh-engine)
        id (write! eng well-formed)
        born (row-of eng :scene id)]
    (is (= "draft" (name (:state born))))
    (is (= "mayor" (get-in born [:data :author])))
    (is (= 1 (get-in born [:data :version])))
    (is (nil? (refusal #(walk! eng id :check))))
    (is (= "ready" (name (:state (row-of eng :scene id)))))))

(deftest each-refusal-names-its-shot
  (let [eng (fresh-engine)]
    (testing "an unknown verb"
      (let [why (why-not eng 2 {:swipe {:surface "row"}})]
        (is (str/includes? why "shot 2"))
        (is (str/includes? why "swipe"))))
    (testing "a surface not in the registry"
      (let [why (why-not eng 3 {:tap {:surface "sidebar.toggle"}})]
        (is (str/includes? why "shot 3"))
        (is (str/includes? why "sidebar.toggle"))))
    (testing "an action the kind does not declare"
      (let [why (why-not eng 3 {:tap {:surface "door:archive@$dishes"}})]
        (is (str/includes? why "shot 3"))
        (is (str/includes? why "`archive` is not a door of chore"))))
    (testing "a $ref never bound"
      (let [why (why-not eng 2 {:open {:self "$laundry"}})]
        (is (str/includes? why "shot 2"))
        (is (str/includes? why "`$laundry` is never bound"))))
    (testing "a field a door does not take"
      (let [why (why-not eng 4 {:type {:field "dialog.field:colour" :value "red"}})]
        (is (str/includes? why "shot 4"))
        (is (str/includes? why "`colour` is not a field `rename` takes"))))
    (testing "an agent lane is judged as its door is"
      (let [why (why-not eng 6 {:meanwhile {:as "planner" :tool "waymark_invoke"
                                            :arguments {:kind "chore" :id "$dishes"
                                                        :action "finish"
                                                        :input {:room "Kitchen"}}}})]
        (is (str/includes? why "shot 6"))
        (is (str/includes? why "`room` is not a field `finish` takes"))))
    (testing "an expectation's op"
      (let [why (why-not eng 8 {:hold {:ms 800}
                                :expect {:screen [{:surface "row" :path "text"
                                                   :op "~=" :value "Dishes"}]}})]
        (is (str/includes? why "shot 8"))
        (is (str/includes? why "is not an op"))))))

(deftest a-shot-may-name-its-role
  (let [eng (fresh-engine)
        id (write! eng (assoc-in well-formed [:shots 2 :role] "turn"))]
    (is (nil? (refusal #(walk! eng id :check))))
    (is (= "ready" (name (:state (row-of eng :scene id)))))
    (is (str/includes?
         (why-not eng 3 {:tap {:surface "door:rename@$dishes"} :role "climax"})
         "shot 3: `climax` is not a role; the roles are friction, turn, payoff."))))

(deftest a-shot-may-name-its-speed-or-its-trim
  (let [eng (fresh-engine)
        end (dec (count (:shots well-formed)))
        id (write! eng (update-in well-formed [:shots end] assoc :speed 4 :trim true))]
    (is (nil? (refusal #(walk! eng id :check))))
    (is (= "ready" (name (:state (row-of eng :scene id)))))
    (is (str/includes?
         (why-not eng 3 {:tap {:surface "door:rename@$dishes"} :speed 20})
         "shot 3: `speed` is a number from 0.25 to 8, and this one is `20`."))
    (is (str/includes?
         (why-not eng 3 {:tap {:surface "door:rename@$dishes"} :trim "yes"})
         "shot 3: `trim` is true or false, and this one is `yes`."))
    (is (str/includes?
         (why-not eng 3 {:tap {:surface "door:rename@$dishes"} :trim true
                         :say "Rename it."})
         "shot 3: a shot with `trim` is cut from the film, so it has no `say`."))))

(deftest a-given-call-is-judged-and-named
  (let [eng (fresh-engine)
        stranger (write! eng (assoc-in well-formed [:given 0 :as] "nobody"))
        field (write! eng (assoc-in well-formed [:given 0 :arguments :input]
                                    {:title "Dishes" :colour "red"}))]
    (is (str/includes? (str (refusal #(walk! eng stranger :check))) "given 1"))
    (is (str/includes? (str (refusal #(walk! eng field :check)))
                       "given 1: `colour` is not a field `create` takes"))))

(def ^:private grant-read
  "A given that reads a grant row and binds it."
  {:as "planner" :tool "waymark_get"
   :arguments {:kind "grant" :id "the-seed-grant"}
   :bind "pass"})

(defn- worn
  "The well-formed scene with a given that reads a grant, and `wearing`."
  [wearing]
  (-> well-formed
      (update :given conj grant-read)
      (assoc :wearing wearing)))

(defn- named [m]
  (into {} (map (fn [[k v]] [(name k) v])) m))

(deftest a-scene-may-say-which-grant-a-cast-member-wears
  (let [eng (fresh-engine)
        ready? (fn [scene]
                 (let [id (write! eng scene)]
                   (and (nil? (refusal #(walk! eng id :check)))
                        (= "ready" (name (:state (row-of eng :scene id)))))))
        why (fn [scene]
              (let [id (write! eng scene)
                    why (refusal #(walk! eng id :check))]
                (is (= "draft" (name (:state (row-of eng :scene id))))
                    "a refused scene stays a draft")
                (str why)))]
    (testing "the bind of a given that reads a grant"
      (is (ready? (worn {:colton "$pass"})))
      (is (ready? (-> (worn {:colton "$pass"})
                      (assoc-in [:given 1 :tool] "waymark_query")
                      (assoc-in [:given 1 :arguments] {:kind "grant"})))))
    (testing "a grant id passes as written"
      (is (ready? (assoc well-formed :wearing {:colton "400e8eff-7f8f"}))))
    (testing "a key that is not in the cast"
      (is (str/includes? (why (worn {:nobody "$pass"}))
                         "wearing `nobody`: `nobody` is not in the cast.")))
    (testing "a bind that names no given"
      (is (str/includes? (why (worn {:colton "$lost"}))
                         "wearing `colton`: `$lost` names no given.")))
    (testing "a bind whose given does not read a grant"
      (is (str/includes?
           (why (worn {:colton "$dishes"}))
           "wearing `colton`: `$dishes` is bound by a given that does not read kind `grant`."))
      (is (str/includes?
           (why (assoc-in (worn {:colton "$pass"}) [:given 1 :arguments :kind] "chore"))
           "wearing `colton`: `$pass` is bound by a given that does not read kind `grant`.")))))

(deftest the-envelope-carries-wearing-as-written
  (let [eng (fresh-engine)
        id (write! eng (worn {:colton "$pass"}))
        _ (walk! eng id :check)
        rdef (get (inv/resources eng) :scene)
        env (render/envelope rdef (row-of eng :scene id) {:now ((:now-fn eng))})]
    (is (= {"colton" "$pass"} (named (get-in env ["data" "wearing"])))
        "the bind is not resolved: the performer does that")))

(deftest revise-makes-a-draft-of-the-next-version
  (let [eng (fresh-engine)
        id (write! eng well-formed)]
    (walk! eng id :check)
    (walk! eng id :revise {:title "Tidying, retaken"})
    (let [row (row-of eng :scene id)]
      (is (= "draft" (name (:state row))))
      (is (= 2 (get-in row [:data :version])))
      (is (= "Tidying, retaken" (get-in row [:data :title])))
      (is (= 8 (count (get-in row [:data :shots]))) "a field not named is kept"))
    (walk! eng id :check)
    (walk! eng id :retire)
    (is (= "retired" (name (:state (row-of eng :scene id)))))))

(deftest a-take-reads-back-with-its-shot-results
  (let [eng (fresh-engine)
        id (write! eng well-formed)
        _ (walk! eng id :check)
        take {:scene (str id)
              :scene_version 1
              :device "phone"
              :commit "9c4248d"
              :film "film-1"
              :shots [{:shot 1 :ok true :pressed "nav.chore" :still "stills/1.png"}
                      {:shot 2 :ok true :still "stills/2.png"}
                      {:shot 3 :ok false :pressed "door:rename"
                       :expects [{:surface "dialog" :path "action" :op "="
                                  :value "rename" :was nil :ok false}]
                       :why "door:rename could not be pressed: Timeout 30000ms exceeded."
                       :still "stills/3.png"}]}
        made (:id (:row (inv/create! eng :take take {:principal mayor})))
        row (row-of eng :take made)]
    (is (= "recorded" (name (:state row))))
    (is (= 1 (get-in row [:data :scene_version])))
    (is (= "phone" (get-in row [:data :device])))
    (is (= [true true false] (mapv :ok (get-in row [:data :shots]))))
    (is (= "stills/3.png" (:still (last (get-in row [:data :shots])))))
    (is (= "door:rename could not be pressed: Timeout 30000ms exceeded."
           (:why (last (get-in row [:data :shots])))))
    (testing "a why over 500 characters is refused"
      (is (some? (refusal #(inv/create! eng :take
                                        (assoc-in take [:shots 2 :why]
                                                  (apply str (repeat 501 "x")))
                                        {:principal mayor})))))
    (is (= 3 (get-in row [:data :first_failing_shot])))
    (is (false? (get-in row [:data :ok])))
    (testing "a take of a version the scene never had is refused"
      (is (some? (refusal #(inv/create! eng :take (assoc take :scene_version 2)
                                        {:principal mayor})))))))

(defn- hole [since until]
  {"$rows" {:kind "chore" :since (str since) :until (str until)}})

(deftest a-say-hole-is-filled-from-the-rows-in-its-window
  (let [eng (fresh-engine)
        _ (inv/create! eng :chore {:title "Sweep the porch"} {:principal mayor})
        _ (inv/create! eng :chore {:title "Wash the van"} {:principal mayor})
        now (java.time.Instant/now)
        rows-of #(scenes/rows-in-window eng %)]
    (testing "two rows in the window: both summaries"
      (let [said (scenes/fill-say ["In came: " (hole (.minusSeconds now 3600)
                                                     (.plusSeconds now 3600)) "."]
                                  rows-of)]
        (is (str/starts-with? said "In came: "))
        (is (str/includes? said "Sweep the porch"))
        (is (str/includes? said "Wash the van"))
        (is (not (str/includes? said "$rows")))))
    (testing "none in the window: the plain sentence"
      (is (= "In came: nothing came in."
             (scenes/fill-say ["In came: " (hole "2026-01-01" "2026-01-02") "."]
                              rows-of))))
    (testing "a counting hole says how many, of the terminal ones when it asks"
      (let [counted (assoc-in (hole (.minusSeconds now 3600) (.plusSeconds now 3600))
                              ["$rows" :count] true)]
        (is (= "2 in." (scenes/fill-say [counted " in."] rows-of)))
        (is (= "0 in." (scenes/fill-say [(assoc-in counted ["$rows" :terminal] true) " in."]
                                        rows-of)))))
    (testing "a string say is spoken as written"
      (is (= "Every chore." (scenes/fill-say "Every chore." rows-of))))))

(deftest a-moves-hole-is-filled-from-the-log-in-its-window
  (let [eng (fresh-engine)
        seat (t/principal {:id "seat:s1" :type :agent :display "s1"})
        _ (inv/create! eng :chore {:title "Sweep the porch"} {:principal seat})
        _ (inv/create! eng :chore {:title "Wash the van"} {:principal mayor})
        now (java.time.Instant/now)
        moves (fn [by since until]
                {"$moves" {:kind "chore" :since (str since) :until (str until) :by by}})
        said (fn [hole] (scenes/fill-say ["Did: " hole "."] (constantly [])
                                         #(scenes/moves-in-window eng %)))
        before (.minusSeconds now 3600)
        after (.plusSeconds now 3600)]
    (testing "by seats: the seat's move only, as summary and action"
      (let [s (said (moves "seats" before after))]
        (is (str/includes? s "Sweep the porch") s)
        (is (str/includes? s ": create") s)
        (is (not (str/includes? s "Wash the van")) s)))
    (testing "by people: the person's move only"
      (let [s (said (moves "people" before after))]
        (is (str/includes? s "Wash the van") s)
        (is (not (str/includes? s "Sweep the porch")) s)))
    (testing "none in the window: the plain sentence"
      (is (= "Did: nothing moved." (said (moves "anyone" "2026-01-01" "2026-01-02")))))))

(defn- org-kind
  "One of the fixture org's three kinds, all of domain `acme`: a row is
  sent to review, and finished through a door that cannot be undone."
  [k]
  (r/resource
   {:kind k
    :plural (str (name k) "s")
    :domain :acme
    :states [:open :review :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]
     [:domain {:optional true :x-display {:label "Domain"}
               :not-a-ref "The name of a domain: a word, not a row id."}
      [:maybe [:string {:max 40}]]]]
    :actions
    {:send {:from #{:open} :to :review
            :safety {:idempotent true :reversible true :confirm false}
            :display {:label "Send" :order 1}}
     :recall {:from #{:review} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Recall" :order 3}}
     :finish {:from #{:open :review} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "It is done and stays done."}
              :display {:label "Finish" :order 2}}}}))

(deftest a-day-hole-says-one-day-of-an-org-in-one-sentence
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (mapv org-kind [:study :invoice :note])})
        seat (t/principal {:id "seat:s1" :type :agent :display "s1"})
        now (java.time.Instant/now)
        day {"$day" {:since (str (.minusSeconds now 3600))
                     :until (str (.plusSeconds now 3600)) :domain "acme"}}
        said (fn [hole] (scenes/fill-say [hole] (constantly []) (constantly [])
                                         #(scenes/day-in-window eng %)))]
    (testing "an empty day: a quiet day"
      (is (= "A quiet day." (said day))))
    (let [study (:row (inv/create! eng :study {:title "Acme's study" :domain "acme"}
                                   {:principal mayor}))
          _ (inv/create! eng :invoice {:title "Acme's invoice" :domain "acme"}
                         {:principal mayor})]
      (testing "a day of creates only: it names them"
        (let [s (said day)]
          (is (str/includes? s "Acme's study was written") s)
          (is (str/includes? s "Acme's invoice was written") s)
          (is (str/ends-with? s ".") s)))
      (inv/invoke! eng :study (str (:id study)) :send {} {:principal seat})
      (inv/invoke! eng :study (str (:id study)) :finish {} {:principal seat})
      (inv/create! eng :held_call {:tool "gate__send" :why "Sign it off."
                                   :caller "seat:s1" :shown "the study"
                                   :door {:kind "study" :id (str (:id study))
                                          :action "finish" :author "seat:s1"}}
                   {:principal held/engine-actor})
      (testing "a held call and two moves: the held call first, two clauses at most"
        (is (= "A person was asked to finish Acme's study; Acme's study went to done."
               (said day))))
      (testing "another domain's day is quiet, or says its none"
        (is (= "A quiet day." (said (assoc-in day ["$day" :domain] "elsewhere"))))
        (is (= "Nothing." (said (update day "$day" assoc :domain "elsewhere"
                                        :none "Nothing.")))))
      (inv/create! eng :approval_request
                   {:task "read the pantry."
                    :scope [{:kind "study" :actions ["finish"]}]}
                   {:principal (t/principal {:id "agent-ari" :type :agent
                                             :display "agent-ari"})})
      (testing "an ask from no seat's domain is the factory's, told first"
        (let [s (said (assoc-in day ["$day" :domain] "factory"))]
          (is (str/starts-with? s "A person was asked to approve access to read the pantry") s))
        (is (= "A person was asked to finish Acme's study; Acme's study went to done."
               (said day)))))))

(deftest a-say-hole-checks
  (let [eng (fresh-engine)]
    (testing "a say with a hole checks ready"
      (let [id (write! eng (assoc-in well-formed [:shots 0 :say]
                                     ["Every chore: " (hole "2026-10-19" "2026-10-20")]))]
        (is (nil? (refusal #(walk! eng id :check))))))
    (testing "a hole on a kind not served"
      (is (str/includes? (why-not eng 1 {:open {:collection "chores"}
                                         :say [{"$rows" {:kind "meal" :since "2026-10-19"
                                                         :until "2026-10-20"}}]})
                         "shot 1: `meal` is not a kind this engine serves.")))
    (testing "a window out of order"
      (is (str/includes? (why-not eng 1 {:open {:collection "chores"}
                                         :say [(hole "2026-10-20" "2026-10-19")]})
                         "shot 1: a `$rows` hole's `since` comes before its `until`.")))
    (testing "a `$moves` hole checks, and its `by` is judged"
      (let [id (write! eng (assoc-in well-formed [:shots 0 :say]
                                     ["Did: " {"$moves" {:kind "chore" :since "2026-10-19"
                                                         :until "2026-10-20"}}]))]
        (is (nil? (refusal #(walk! eng id :check)))))
      (is (str/includes? (why-not eng 1 {:open {:collection "chores"}
                                         :say [{"$moves" {:kind "chore" :since "2026-10-19"
                                                          :until "2026-10-20" :by "bots"}}]})
                         "shot 1: `bots` is not whose moves a hole reads")))
    (testing "a say that is neither a string nor a list"
      (is (str/includes? (why-not eng 1 {:open {:collection "chores"} :say {:x 1}})
                         "shot 1: `say` is a string")))))
