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
    (testing "a caption per day, day 1 on day_one to day 7, with holes for the day's rows"
      (let [days (filter #(and (sequential? %) (str/starts-with? (first %) "Day ")) says)
            holes (fn [say] (keep #(get % "$rows") say))]
        (is (= 7 (count days)))
        (is (str/starts-with? (first (first days)) "Day 1, 2026-10-19"))
        (is (str/starts-with? (first (last days)) "Day 7, 2026-10-25"))
        (is (every? #(str/includes? (apply str (filter string? %)) "Rosa Delgado") days))
        (testing "each hole reads its own day"
          (is (every? #(= {:since "2026-10-19" :until "2026-10-20"}
                          (select-keys % [:since :until]))
                      (holes (first days))))
          (is (every? #(= "2026-10-26" (:until %)) (holes (last days)))))
        (testing "what came in, what the house did, and what it asked"
          (is (= #{"approval_request" "held_call" (:kind (first (holes (first days))))}
                 (set (map :kind (holes (first days)))))))))
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
