(ns waymark10.film-rules-test
  "The film rule (demo scorecard): the seed rows load once, a rule
  naming an unknown metric is refused with the vocabulary, a person
  makes a rule and a bare agent does not, and only a person retires
  one.

  Real Postgres. Needs the test database; WAYMARK10_TEST_DSN overrides
  the DSN."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.film-rules :as film-rules]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:dynamic *eng* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (jdbc/execute! tx ["DROP TABLE IF EXISTS film_rules CASCADE"])))
        (binding [*eng* (engine/engine {:storage st :resources []})]
          (f))
        (finally (pg/close! st))))))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))
(def ^:private clerk (t/principal {:id "clerk" :type :agent :display "Clerk"}))

(defn- rows-of [where]
  (let [rdef (get (inv/resources *eng*) :film_rule)]
    (mapv #(inv/decode-row rdef %)
          (store/with-tx (:storage *eng*)
            (fn [tx]
              (vec (store/query-rows (:storage *eng*) tx :film_rule where
                                     {:limit 1000})))))))

(defn- rule-named [name'] (first (rows-of {:name name'})))

(defn- refusal
  "The problem a refused call threw, with `:text` holding everything it
  says, or nil when the call went through."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d)
             (assoc d :text (str (ex-message e) " " (pr-str d)))
             (throw e))))))

(defn- rule-body [extra]
  (merge {:name "a-rule"
          :metric "runtime_s"
          :op "<="
          :threshold 90M
          :scope "film"
          :severity "warn"
          :why "A demo that runs long loses its viewer."
          :origin "craft"}
         extra))

(defn- make-rule! [extra principal]
  (:row (inv/create! *eng* :film_rule (rule-body extra) {:principal principal})))

(defn- move! [action name' principal]
  (let [row (rule-named name')]
    (inv/invoke! *eng* :film_rule (str (:id row)) action {}
                 {:principal principal
                  :if-match (inv/etag :film_rule (str (:id row)) (:version row))})))

(defn- retire! [name' principal]
  (move! :retire name' principal))

(defn- state-of [row] (name (:state row)))

(deftest the-seed-rows-load-once
  (film-rules/ensure-seed-rules! *eng*)
  (film-rules/ensure-seed-rules! *eng*)
  (doseq [{:keys [name metric op threshold scope severity]} film-rules/seed-rules]
    (testing name
      (let [rows (rows-of {:name name})
            data (:data (first rows))]
        (is (= 1 (count rows)) "a second boot makes none")
        (is (= "active" (state-of (first rows))))
        (is (= [metric op scope severity]
               (mapv #(clojure.core/name (get data %))
                     [:metric :op :scope :severity])))
        (is (== threshold (:threshold data))))))
  (testing "the owner's two notes are fails, and say whose they are"
    (let [fill (:data (rule-named "frame-fill"))
          leaks (:data (rule-named "chrome-leaks"))]
      (is (== 0.95M (:threshold fill)))
      (is (str/includes? (:origin fill) "top left portion"))
      (is (== 0 (:threshold leaks)))
      (is (= "fail" (clojure.core/name (:severity leaks))))))
  (testing "type-size is for phone output, and a zoom exempts focus-share"
    (is (= "phone" (name (:output (:data (rule-named "type-size"))))))
    (is (= "zoom" (name (:unless (:data (rule-named "focus-share")))))))
  (testing "every seed names a metric of the vocabulary"
    (is (every? (set film-rules/metric-names)
                (map :metric film-rules/seed-rules)))))

(deftest an-unknown-metric-is-refused-with-the-vocabulary
  (let [d (refusal #(make-rule! {:name "loudness-floor" :metric "loudness"}
                                colton))]
    (is (some? d))
    (is (nil? (rule-named "loudness-floor")))
    (doseq [m film-rules/metric-names]
      (is (str/includes? (:text d) m) (str "the refusal names " m)))))

(deftest a-role-needs-the-scope-shot
  (is (some? (refusal #(make-rule! {:name "role-on-a-film" :role "payoff"}
                                   colton))))
  (is (nil? (rule-named "role-on-a-film")))
  (let [made (make-rule! {:name "payoff-holds" :metric "read_time_ratio"
                          :op ">=" :threshold 1.5M
                          :scope "shot" :role "payoff"}
                         colton)]
    (is (= "payoff" (name (get-in made [:data :role]))))))

(deftest an-exemption-needs-the-scope-shot
  (is (some? (refusal #(make-rule! {:name "zoom-on-a-film" :unless "zoom"}
                                   colton))))
  (is (nil? (rule-named "zoom-on-a-film")))
  (let [made (make-rule! {:name "phone-contrast" :metric "contrast"
                          :op ">=" :threshold 4.5M
                          :scope "shot" :output "phone" :unless "zoom"}
                         colton)]
    (is (= "phone" (name (get-in made [:data :output]))))
    (is (= "zoom" (name (get-in made [:data :unless]))))))

(defn- seed-named [name']
  (first (filter #(= name' (:name %)) film-rules/seed-rules)))

(deftest a-phone-rule-is-not-scored-on-a-desktop-take
  (let [rule (seed-named "type-size")]
    (is (= :unscored (film-rules/verdict rule {:output "desktop"} {} 14)))
    (is (= :miss (film-rules/verdict rule {:output "phone"} {} 14)))
    (is (= :pass (film-rules/verdict rule {:output "phone"} {} 28))))
  (testing "a rule that names no output is scored at either"
    (let [rule (seed-named "read-time")]
      (is (= :miss (film-rules/verdict rule {:output "desktop"} {} 1.0)))
      (is (= :miss (film-rules/verdict rule {:output "phone"} {} 1.0)))))
  (testing "a role still narrows the shots"
    (let [rule (assoc (seed-named "type-size") :role "payoff")]
      (is (= :unscored
             (film-rules/verdict rule {:output "phone"} {:role "turn"} 14)))
      (is (= :miss
             (film-rules/verdict rule {:output "phone"} {:role "payoff"} 14))))))

(deftest a-shot-that-asks-for-zoom-passes-focus-share
  (let [rule (seed-named "focus-share")
        film {:output "desktop"}]
    (is (= :pass (film-rules/verdict rule film {:zoom true} 0.1)))
    (is (= :miss (film-rules/verdict rule film {:zoom false} 0.1)))
    (is (= :miss (film-rules/verdict rule film {} 0.1)))
    (is (= :pass (film-rules/verdict rule film {} 0.25)))))

(deftest a-person-makes-a-rule-and-a-bare-agent-does-not
  (is (= "active" (state-of (make-rule! {:name "made-by-a-person"} colton))))
  (is (some? (refusal #(make-rule! {:name "made-by-an-agent"} clerk))))
  (is (nil? (rule-named "made-by-an-agent")))
  (testing "a name is a slug"
    (is (some? (refusal #(make-rule! {:name "Not A Slug"} colton))))))

(deftest retiring-needs-a-person
  (make-rule! {:name "to-retire"} colton)
  (testing "an agent's retire is refused"
    (is (some? (refusal #(retire! "to-retire" clerk))))
    (is (= "active" (state-of (rule-named "to-retire")))))
  (testing "and so is the engine's own"
    (is (some? (refusal #(retire! "to-retire" film-rules/seed-actor))))
    (is (= "active" (state-of (rule-named "to-retire")))))
  (testing "a person's goes through"
    (retire! "to-retire" colton)
    (is (= "retired" (state-of (rule-named "to-retire")))))
  (testing "an agent's restore is refused"
    (is (some? (refusal #(move! :restore "to-retire" clerk))))
    (is (= "retired" (state-of (rule-named "to-retire")))))
  (testing "a person's restore brings the rule back"
    (move! :restore "to-retire" colton)
    (is (= "active" (state-of (rule-named "to-retire"))))))
