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
  "What `captions` answers for days 2026-10-19 on, named `weekdays`."
  [weekdays]
  (map (fn [weekday d]
         [(str weekday ". ")
          {:since (str "2026-10-" d) :until (str "2026-10-" (inc d)) :domain "example"}])
       weekdays (range 19 26)))

(defn- counts
  "The `$rows` holes of a shot's say."
  [shot]
  (keep #(when (map? %) (get % "$rows")) (:say shot)))

(def ^:private three-kind-seed
  (update seed :steps conj {:as :owner :kind :invoice :ref :hedge-bill
                            :create {:title "Front hedge"}}))

(deftest a-three-kind-seed-renders-its-week
  (let [brief "A six-person landscaping business in Provo. It trims hedges."
        shots (:shots (:scene (first-week/scene three-kind-seed (assoc filmed-on :brief brief))))
        closing (last shots)]
    (testing "the opening says the business and its brief's first sentence"
      (is (= "Example Landscaping: A six-person landscaping business in Provo."
             (:say (first shots)))))
    (testing "seven day shots, each its weekday and a `$day` hole of its date"
      (is (= (day-captions ["Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday" "Sunday"])
             (captions shots))))
    (testing "the closing counts each kind's finished rows over the week, then the taps"
      (is (= [["request" true true] ["work_order" true true] ["invoice" true true]
              ["held_call" true nil]]
             (map (juxt :kind :count :terminal) (counts closing))))
      (is (every? #(= {:since "2026-10-19" :until "2026-10-26"} (select-keys % [:since :until]))
                  (counts closing)))
      (is (str/ends-with? (last (:say closing)) "Merge to make it real.")))
    (testing "the arc film rule still reads friction, turn, payoff in order"
      (is (= ["friction" "turn" "payoff"] (keep :role shots))))
    (testing "a brief's first sentence is cut at 140 characters"
      (is (= 140 (count (first-week/brief-line (apply str (repeat 200 "a")))))))))

(deftest the-template-renders-for-the-example-org
  (let [{:keys [scene clone]} (first-week/scene seed filmed-on)
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
    (testing "the closing counts each kind the seed makes, then the taps asked"
      (is (= [["request" true true] ["work_order" true true] ["held_call" true nil]]
             (map (juxt :kind :count :terminal) (counts (last (:shots scene)))))))
    (testing "the arc runs friction, turn, payoff"
      (is (= ["friction" "turn" "payoff"] (keep :role (:shots scene)))))
    (testing "about 45 seconds"
      (is (<= 35000 (reduce + (keep #(get-in % [:hold :ms]) (:shots scene))) 45000)))
    (testing "it checks ready beside the org's kinds"
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
      (is (= (:scene (first-week/scene seed (assoc filmed-on :clone_url ""))) scene)))
    (testing "at the head it names, on no clone yet"
      (is (= {:url "" :head_sha (:head_sha filmed-on)} clone)))
    (testing "with no hole left and within the plan's field"
      (is (not (str/includes? text "{{")))
      (is (<= (count text) 65536)))))

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
