(ns factory10.first-week-test
  "An org's first-week scene and where its film lands (ticket 3260d452,
  M3d): the template renders for orgs/example's seed and checks ready
  beside the org's kinds; a green film moves a proposed plan to
  simulated with its film_url; a red one sends it back to drafting with
  its missed fail rules as findings. Memory storage, no network.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.first-week :as first-week]
            [factory10.main :as main]
            [factory10.org-plan-walk :as walk]
            [waymark10.server.engine :as engine]
            [waymark10.server.film-rules :as film-rules]
            [waymark10.server.invoke :as inv]
            [waymark10.server.scenes :as scenes]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private seed
  (edn/read-string (slurp (io/resource "example/seed.edn"))))

(defn- example-dir
  "orgs/example, found from its seed at <org-dir>/resources/example/seed.edn."
  []
  (.getPath (.getParentFile (.getParentFile (.getParentFile
                                             (io/file (io/resource "example/seed.edn")))))))

(def ^:private filmed-on
  {:clone_url "https://clone.example.test/example"
   :head_sha "5e469c6166edf6a3f28ae2e6bdb88b775025f14d"
   :day_one "2026-10-19"})

(defn- eng []
  (engine/engine {:storage (memory/storage)
                  :resources (into (vec (main/resources))
                                   ((requiring-resolve 'example.org/resources)))}))

(def ^:private mayor (t/principal {:id "mayor" :display "Mayor"}))

(def ^:private the-owner
  (t/principal {:id "colton" :type :human :display "colton"}))

(def ^:private the-forge
  (t/principal {:id "factory10-source" :type :system
                :display "factory10-source"}))

(defn- captions
  "Each day shot's say as [its first part, its `$day` hole]."
  [shots]
  (keep (fn [{:keys [say]}]
          (when-some [h (when (sequential? say) (some #(when (map? %) (get % "$day")) say))]
            [(first say) h]))
        shots))

(defn- day-captions
  "What `captions` answers for days named `weekdays`: day k reads day k
  of the clone's clock."
  [weekdays]
  (map-indexed (fn [i weekday]
                 [(str weekday ". ") {:window "clock" :day (inc i) :domain "example"}])
               weekdays))

(defn- counts
  "The `$rows` holes of a shot's say, its `$list`'s too."
  [shot]
  (keep #(when (map? %) (get % "$rows")) (tree-seq coll? seq (:say shot))))

(defn- example-kinds []
  ((requiring-resolve 'example.org/resources)))

(def ^:private three-kind-seed
  (assoc seed :steps [{:as :owner :kind :prospect :ref :p :create {:title "Hedge lead"}}
                      {:as :owner :kind :study :ref :s :create {:title "Yard study"}}
                      {:as :owner :kind :invoice :ref :i :create {:title "Front hedge"}}]))

(def ^:private three-kinds
  "The fixture's declarations, as far as the closing reads them."
  [{:kind :prospect :states [:new :engaged :declined] :terminal #{:engaged :declined}}
   {:kind :study :states [:drafted :delivered :dropped] :terminal #{:delivered :dropped}}
   {:kind :invoice :states [:sent :paid :written_off] :terminal #{:paid :written_off}}])

(defn- spoken
  "The closing's say as the performer speaks it, each kind's count in `n`
  and each summed kind's amounts in `sums`."
  ([closing n] (spoken closing n {}))
  ([closing n sums]
   (scenes/fill-say (:say closing)
                    (fn [{:keys [kind sum]}]
                      (if sum (get sums kind []) (repeat (get n kind 0) "row"))))))

(deftest a-three-kind-seed-renders-its-week
  (let [brief "A six-person landscaping business in Provo. It trims hedges."
        shots (:shots (:scene (first-week/scene three-kind-seed
                                                (assoc filmed-on :brief brief :resources three-kinds))))
        closing (last shots)]
    (testing "the shots open the knocked kind's collection, and tap no nav"
      (is (not-any? #(some-> % :tap :surface (str/starts-with? "nav.")) shots))
      (is (= {:collection "prospects"} (:open (first shots))))
      (is (= 8 (count (filter #(= {:collection "prospects"} (:open %)) shots)))))
    (testing "the opening says the business and its brief's first sentence"
      (is (= "Example Landscaping. A six-person landscaping business in Provo."
             (:say (second shots))))
      (is (= {:ms 3000} (:hold (second shots)))))
    (testing "seven day shots, each its weekday and a `$day` hole of the shot's window"
      (is (= (day-captions ["Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday" "Sunday"])
             (captions shots))))
    (testing "the closing counts each kind's success ends over the week, then the taps"
      (is (= [["prospect" "engaged" "prospect engaged" "prospects engaged"]
              ["study" "delivered" "study delivered" "studies delivered"]
              ["invoice" "paid" "invoice paid" "invoices paid"]
              ["held_call" nil "tap from you" "taps from you"]]
             (map (juxt :kind :state :one :many) (counts closing))))
      (is (every? #(= {:since "2026-10-19" :until "2026-10-26"} (select-keys % [:since :until]))
                  (counts closing)))
      (is (str/ends-with? (last (:say closing)) "Merge to make it real.")))
    (testing "it reads each count by its noun and end, a zero dropping its clause"
      (is (= "That was the week: 3 prospects engaged, 1 study delivered, 1 invoice paid, 2 taps from you. Merge to make it real."
             (spoken closing {"prospect" 3 "study" 1 "invoice" 1 "held_call" 2})))
      (is (= "That was the week: 1 prospect engaged, 2 invoices paid, 1 tap from you. Merge to make it real."
             (spoken closing {"prospect" 1 "invoice" 2 "held_call" 1})))
      (is (= "A quiet week. Merge to make it real."
             (spoken closing {})))
      (is (not-any? :sum (counts closing)) "no kind declares an amount"))
    (testing "a noun's plural"
      (is (= ["prospects" "studies" "days" "work orders" "glass"]
             (map first-week/plural ["prospect" "study" "day" "work order" "glass"]))))
    (testing "the arc film rule still reads friction, turn, payoff in order"
      (is (= ["friction" "turn" "payoff"] (keep :role shots))))
    (testing "a brief's first sentence is cut at 140 characters"
      (is (= 140 (count (first-week/brief-line (apply str (repeat 200 "a")))))))))

(deftest a-kind-with-an-amount-sums-the-week's-money
  (let [kinds (assoc-in three-kinds [2 :schema]
                        [:map [:client :string] [:amount {:optional true} [:maybe [:int {:min 0}]]]])
        closing (last (:shots (:scene (first-week/scene three-kind-seed
                                                        (assoc filmed-on :resources kinds)))))]
    (testing "the closing sums the week's invoices, after the ends and before the taps"
      (is (= [["prospect" "engaged" nil "prospects engaged"]
              ["study" "delivered" nil "studies delivered"]
              ["invoice" nil "amount" "invoiced"]
              ["held_call" nil nil "taps from you"]]
             (map (juxt :kind :state :sum :many) (counts closing)))))
    (testing "it says the money in whole dollars, a zero dropping its clause"
      (is (= "That was the week: 2 prospects engaged, $1,250 invoiced, 1 tap from you. Merge to make it real."
             (spoken closing {"prospect" 2 "held_call" 1} {"invoice" [1200 50.4]})))
      (is (= "A quiet week. Merge to make it real." (spoken closing {} {"invoice" [0]}))))
    (testing "the money field is a number named amount, total or fee"
      (is (nil? (first-week/amount-field {:schema [:map [:total :string]]})))
      (is (= "fee" (first-week/amount-field {:schema [:map [:fee {:optional true} :double]]}))))))

(deftest the-template-renders-for-the-example-org
  (let [{:keys [scene clone]} (first-week/scene seed (assoc filmed-on :resources (example-kinds)))
        says (keep :say (:shots scene))]
    (testing "the clone is the one it was given"
      (is (= {:url (:clone_url filmed-on) :head_sha (:head_sha filmed-on)} clone)))
    (testing "every hole is filled from the seed"
      (is (not (str/includes? (pr-str scene) "{{")))
      (is (= "example" (:seed scene)))
      (is (= ["owner"] (:cast scene)))
      (is (= "The first week of Example Landscaping" (:title scene))))
    (testing "with no brief, the opening is the business alone"
      (is (= "Example Landscaping." (first says))))
    (testing "a caption per day, day 1 on day_one to day 7: its weekday and its `$day`"
      (is (= (day-captions ["Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday" "Sunday"])
             (captions (:shots scene)))))
    (testing "the closing counts each kind's success ends, no loss, then the taps asked"
      (is (= [["request" "accepted" "requests accepted"] ["work_order" "done" "work orders done"]
              ["held_call" nil "taps from you"]]
             (map (juxt :kind :state :many) (counts (last (:shots scene)))))))
    (testing "the arc runs friction, turn, payoff"
      (is (= ["friction" "turn" "payoff"] (keep :role (:shots scene)))))
    (testing "each given binds one row: none binds a waymark_query's collection"
      (is (not-any? #(and (some? (:bind %)) (= "waymark_query" (:tool %)))
                    (:given scene))))
    (testing "about 45 seconds"
      (is (<= 35000 (reduce + (keep #(get-in % [:hold :ms]) (:shots scene))) 45000)))
    (testing "it checks ready beside the org's kinds"
      (let [e (eng)
            opts {:principal mayor}
            id (str (:id (:row (inv/create! e :scene scene opts))))
            done (inv/invoke! e :scene id :check {}
                              (assoc opts :idempotency-key (str (random-uuid))))]
        (is (= "ready" (some-> done :row :state name)) (pr-str done))))))

(def ^:private a-week
  "Seven days of meanwhile calls on the example org: Ana's requests, the
  owner's move on one an earlier day bound, two quiet days."
  [[{:as "ana" :tool "waymark_invoke" :bind "edge"
     :arguments {:kind "request" :action "create"
                 :input {:title "Edge the front walk" :customer "Ana Ruiz"}}}]
   [{:as "ana" :tool "waymark_invoke"
     :arguments {:kind "request" :action "create"
                 :input {:title "The hedge is still not trimmed" :customer "Ana Ruiz"}}}]
   [{:as "owner" :tool "waymark_invoke"
     :arguments {:kind "request" :action "accept" :id "$edge"}}]
   []
   [{:as "ana" :tool "waymark_invoke"
     :arguments {:kind "request" :action "create"
                 :input {:title "Thanks, see you Tuesday" :customer "Ana Ruiz"}}}
    {:as "owner" :tool "waymark_invoke"
     :arguments {:kind "request" :action "decline" :id "$edge"}}]
   []
   [{:as "owner" :tool "waymark_invoke"
     :arguments {:kind "work_order" :action "create"
                 :input {:title "Edge the front walk" :crew "crew"}}}]])

(deftest the-director's-week-plays-before-each-day's-caption
  (let [{:keys [scene]} (first-week/scene seed (assoc filmed-on :resources (example-kinds)
                                                      :week a-week))
        shots (:shots scene)
        caption? #(and (sequential? (:say %)) (some (fn [p] (and (map? p) (get p "$day"))) (:say %)))]
    (testing "each day's calls play as meanwhile shots, in order, right before its caption"
      (is (= (mapcat (fn [calls weekday] (concat calls [weekday]))
                     a-week
                     ["Monday. " "Tuesday. " "Wednesday. " "Thursday. " "Friday. " "Saturday. " "Sunday. "])
             (keep #(cond (:meanwhile %) (:meanwhile %) (caption? %) (first (:say %))) shots)))
      (is (every? (fn [[a b]] (or (not (:meanwhile b)) (:meanwhile a) (:open a)))
                  (partition 2 1 shots))
          "a day's calls follow its open, not the day before's caption"))
    (testing "the calls' cast joins the scene's, the owner first"
      (is (= ["owner" "ana"] (:cast scene))))
    (testing "the arc and the captions stand as without a week"
      (is (= ["friction" "turn" "payoff"] (keep :role shots)))
      (is (= (captions (:shots (:scene (first-week/scene seed (assoc filmed-on :resources (example-kinds))))))
             (captions shots))))
    (testing "an empty week is the scene with none"
      (is (= (first-week/scene seed (assoc filmed-on :resources (example-kinds)))
             (first-week/scene seed (assoc filmed-on :resources (example-kinds)
                                           :week (vec (repeat 7 [])))))))
    (testing "it checks ready, its calls judged by their shape"
      (let [e (eng)
            opts {:principal mayor}
            id (str (:id (:row (inv/create! e :scene scene opts))))
            done (inv/invoke! e :scene id :check {}
                              (assoc opts :idempotency-key (str (random-uuid))))]
        (is (= "ready" (some-> done :row :state name)) (pr-str done))))))

(deftest the-audit-renders-the-example-org's-scene-at-its-head
  (let [text (first-week/org-scene (example-dir) (:head_sha filmed-on)
                                   (:day_one filmed-on))
        {:keys [scene clone day_one]} (edn/read-string text)]
    (testing "it names its day one, the day the clone's seed counts [:days 0] from"
      (is (= (:day_one filmed-on) day_one))
      (is (some #(and (map? %) (= day_one (:since %)))
                (tree-seq coll? seq (:shots scene)))))
    (testing "it is the scene the seed renders, as edn text"
      (is (= (:scene (first-week/scene seed (assoc filmed-on :clone_url ""
                                                   :resources (example-kinds))))
             scene)))
    (testing "at the head it names, on no clone yet"
      (is (= {:url "" :head_sha (:head_sha filmed-on)} clone)))
    (testing "with no hole left and within the plan's field"
      (is (not (str/includes? text "{{")))
      (is (<= (count text) 65536)))))

(deftest a-take-of-the-plan's-scene-is-marked-a-first-week
  (let [text (first-week/org-scene (example-dir) (:head_sha filmed-on)
                                   (:day_one filmed-on))
        tk {:shots [{:caption "Monday. A quiet day."}]}]
    (testing "the scene the audit renders says it is a first week"
      (is (true? (:first-week (edn/read-string text)))))
    (testing "a take of it is marked, its shots as they were"
      (is (= (assoc tk :scene {:first_week true}) (first-week/marked tk text))))
    (testing "a scene rendered before the mark marks its take all the same"
      (is (= {:first_week true}
             (:scene (first-week/marked tk (pr-str (dissoc (edn/read-string text) :first-week)))))))
    (testing "a plan with no scene leaves the take as it is"
      (is (= tk (first-week/marked tk nil)))
      (is (= tk (first-week/marked tk ""))))))

(def ^:private rules
  [{:name "chrome-leaks" :metric "chrome_leaks" :op "=" :threshold 0
    :scope "film" :severity "fail" :origin "craft"
    :why "No /api path, uuid or raw key shows on screen."}
   {:name "dead-air" :metric "dead_air_s" :op "<=" :threshold 0.8
    :scope "film" :severity "warn" :origin "craft"
    :why "Something changes on screen at least every 0.8 seconds."}])

(defn- proposed! [e]
  (let [id (str (:id (:row (inv/create! e :org_plan
                                         {:name "example"
                                          :brief "A six-person landscaping business."}
                                         {:principal the-owner}))))]
    (walk/proposed! e id the-owner)
    (inv/invoke! e :org_plan id :hand_in_film
                 {:film_url "https://films.example.test/example-week.mp4"
                  :take_report "{}" :head_sha (:head_sha filmed-on)}
                 {:principal the-owner})
    id))

(def ^:private film
  {:film_url "https://films.example.test/example-week.mp4"
   :head_sha (:head_sha filmed-on)})

(deftest a-green-film-moves-the-plan-to-simulated
  (let [e (eng)
        id (proposed! e)
        answer (film-rules/judge rules {:film {:chrome_leaks 0 :dead_air_s 2.0}})
        row (:row (first-week/land! e id answer film {:principal the-forge}))]
    (is (= "warn" (:verdict answer)) "a warn rule missed, and no fail rule")
    (is (= "simulated" (some-> row :state name)))
    (is (= (:film_url film) (get-in row [:data :film_url])))))

(deftest a-red-film-returns-the-plan-to-drafting
  (let [e (eng)
        id (proposed! e)
        answer (film-rules/judge rules {:film {:chrome_leaks 2 :dead_air_s 2.0}})
        row (:row (first-week/land! e id answer film {:principal the-forge}))]
    (is (= "red" (:verdict answer)))
    (is (= "drafting" (some-> row :state name)))
    (testing "the missed fail rule is a finding, and the warn rule is not"
      (is (= ["film/chrome-leaks"] (mapv :key (get-in row [:data :findings]))))
      (is (= "error" (get-in row [:data :findings 0 :severity])))
      (is (= (:head_sha film) (get-in row [:data :findings_sha]))))
    (is (nil? (get-in row [:data :film_url])))))

;; ── a take's shots against its scene's (ticket ef24488d)

(deftest a-take's-shots-line-up-with-its-scene's-ids-and-roles
  (let [doc (first-week/scene seed (assoc filmed-on :resources (example-kinds) :week a-week))
        text (pr-str doc)
        shots (:shots (:scene doc))
        filmed #(if (:meanwhile %) {:meanwhile {:as (get-in % [:meanwhile :as])}} {:hold_s 1.0})]
    (testing "each scene shot carries its 1-based position as its id, a meanwhile shot too"
      (is (some :meanwhile shots))
      (is (= (range 1 (inc (count shots))) (map :id shots))))
    (testing "a take naming each shot's id carries its scene shot's role, and drops none"
      (let [tk {:shots (mapv #(assoc (filmed %) :id (:id %)) shots)}
            lined (first-week/line-up tk text)]
        (is (= (map :role shots) (map :role (:shots lined))))
        (is (= ["friction" "turn" "payoff"] (keep :role (:shots lined))))
        (is (= [] (first-week/dropped-roles lined text)))
        (is (= ["take-dropped-role"] (map :key (first-week/dropped-roles tk text))))))
    (testing "a take from before the id lines up by position, its meanwhile shots aside"
      (let [tk {:shots (vec (cons {:meanwhile {:as "ana"}}
                                  (map filmed (remove :meanwhile shots))))}
            lined (first-week/line-up tk text)]
        (is (= (map :id (remove :meanwhile shots)) (map :id (remove :meanwhile (:shots lined)))))
        (is (= (keep :role shots) (keep :role (:shots lined))))
        (is (= [] (first-week/dropped-roles lined text)))))
    (testing "a take whose count is not the scene's is a take-dropped-role warning naming both"
      (let [[w] (first-week/dropped-roles {:shots [{:hold_s 1.0}]} text)]
        (is (= "warning" (:severity w)))
        (is (str/includes? (:sentence w)
                           (str "the scene has " (count (remove :meanwhile shots)) " shots and the take 1")))))
    (testing "a plan with no scene drops nothing"
      (is (= [] (first-week/dropped-roles {:shots [{:hold_s 1.0}]} nil))))))

(deftest a-green-film's-warning-lands-beside-it
  (let [e (eng)
        id (proposed! e)
        answer (film-rules/judge rules {:film {:chrome_leaks 0 :dead_air_s 0.5}})
        warning {:key "take-dropped-role" :severity "warning" :class "film"
                 :subject "film" :sentence "The take does not line up with its scene."}
        row (:row (first-week/land! e id answer (assoc film :warnings [warning])
                                    {:principal the-forge}))]
    (is (= "simulated" (some-> row :state name)))
    (is (= ["take-dropped-role"] (mapv :key (get-in row [:data :findings]))))
    (is (nil? (get-in row [:data :next_step])) "a warning moves no step")))

;; ── a first week the rules cannot pass by not measuring it (ticket 179f7824)

(def ^:private week-rules
  (filterv #(#{"busy-week" "quiet-days"} (:name %)) film-rules/seed-rules))

(defn- week-take
  "A first week's take: shots that run the arc's roles, with a day
  caption for each of `days` between the friction and the payoff."
  [days]
  {:film {:output "phone"}
   :shots (-> [{:role "friction"
                :caption "Example Landscaping: A six-person landscaping business."}]
              (into (map (fn [c] {:role "turn" :caption c}) days))
              (conj {:role "payoff" :goal_state "done" :caption "That was the week."}))})

(def ^:private quiet-week
  (mapv #(str % ". A quiet day.")
        ["Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday" "Sunday"]))

(deftest a-quiet-first-week-returns-the-plan-to-drafting
  (let [e (eng)
        id (proposed! e)
        answer (film-rules/judge week-rules (week-take quiet-week))
        row (:row (first-week/land! e id answer film {:principal the-forge}))]
    (is (= "drafting" (some-> row :state name)))
    (is (= ["film/busy-week" "film/quiet-days"]
           (mapv :key (get-in row [:data :findings]))))
    (is (nil? (get-in row [:data :film_url])))))

(deftest a-first-week-the-film-cannot-measure-returns-the-plan-to-drafting
  (let [e (eng)
        id (proposed! e)
        answer (film-rules/judge week-rules (week-take []))
        row (:row (first-week/land! e id answer film {:principal the-forge}))
        findings (get-in row [:data :findings])]
    (is (= ["busy-week" "quiet-days"] (:unmeasured answer)))
    (is (= "drafting" (some-> row :state name)))
    (is (= ["film/busy-week" "film/quiet-days"] (mapv :key findings)))
    (testing "each finding says what could not be measured, and its remedy names the fields"
      (is (every? #(str/starts-with? (:sentence %) "The film could not measure ") findings))
      (is (str/includes? (:remedy (first findings)) "shot.meanwhile"))
      (is (str/includes? (:remedy (second findings)) "shot.caption")))
    (is (nil? (get-in row [:data :film_url])))))

(deftest a-warn-rule-the-film-cannot-measure-does-not-block-it
  (let [e (eng)
        id (proposed! e)
        answer (film-rules/judge rules {:film {:chrome_leaks 0}})
        row (:row (first-week/land! e id answer film {:principal the-forge}))]
    (is (= ["dead-air"] (:unmeasured answer)))
    (is (= "simulated" (some-> row :state name)))
    (is (= (:film_url film) (get-in row [:data :film_url])))))
