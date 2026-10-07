(ns waymark10.film-rules-test
  "The film rule (demo scorecard): the seed rows load once, a rule
  naming an unknown metric is refused with the vocabulary, a person
  makes a rule and a bare agent does not, the sitter of a domain's
  mayor seat makes one and the sitter of another seat does not, only a
  person retires one, and each metric is read from a take.

  Real Postgres. Needs the test database; WAYMARK10_TEST_DSN overrides
  the DSN."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.film-rules :as film-rules]
            [waymark10.server.grants :as grants]
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
            (doseq [table ["film_rules" "domains" "seats"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
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

(defn- restate! [name' patch principal]
  (let [row (rule-named name')]
    (inv/invoke! *eng* :film_rule (str (:id row)) :restate patch
                 {:principal principal
                  :if-match (inv/etag :film_rule (str (:id row)) (:version row))})))

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

(deftest the-seed-restates-a-row-made-before-the-fields
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (jdbc/execute! tx [(str "DELETE FROM film_rules WHERE data->>'name'"
                              " IN ('type-size', 'focus-share')")])))
  (make-rule! {:name "type-size" :metric "type_px" :op ">=" :threshold 28M
               :scope "shot"}
              film-rules/seed-actor)
  (make-rule! {:name "focus-share" :metric "focus_share" :op ">="
               :threshold 0.25M :scope "shot"}
              film-rules/seed-actor)
  (is (nil? (:output (:data (rule-named "type-size")))))
  (is (nil? (:unless (:data (rule-named "focus-share")))))
  (film-rules/ensure-seed-rules! *eng*)
  (testing "the rows that were there gain the fields"
    (is (= 1 (count (rows-of {:name "type-size"}))))
    (is (= "phone" (name (:output (:data (rule-named "type-size"))))))
    (is (nil? (:unless (:data (rule-named "type-size")))))
    (is (= "zoom" (name (:unless (:data (rule-named "focus-share"))))))
    (is (nil? (:output (:data (rule-named "focus-share"))))))
  (testing "a second boot restates nothing"
    (let [version (:version (rule-named "type-size"))]
      (film-rules/ensure-seed-rules! *eng*)
      (is (= version (:version (rule-named "type-size")))))))

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

;; ── a mayor's sitter ────────────────────────────────────────────────

(defn- row-of [kind id]
  (let [rdef (get (inv/resources *eng*) kind)]
    (some->> (store/with-tx (:storage *eng*)
               (fn [tx]
                 (store/load-row (:storage *eng*) tx kind (str id) {})))
             (inv/decode-row rdef))))

(defn- open-seat! [name']
  (:row (inv/create! *eng* :seat
                     {:name name'
                      :charter "Decide whether a film is good enough to send."
                      :scope [{:kind "model" :actions ["retire"]}]
                      :standing_ttl_seconds 604800
                      :cadence_seconds 3600
                      :budget_usd_per_week 5M
                      :sitting_budget_tokens 60000}
                     {:principal colton})))

(defn- sit!
  "The sitter asks to sit in the seat by name, a person approves, and
  the minted grant cites the seat (seat-ledger-test's bootstrap). The
  sitter accepts the grant when the mint left that to it."
  [seat-name sitter]
  (let [ask (:row (inv/create! *eng* :approval_request
                               {:task "Keep the film rules." :seat seat-name}
                               {:principal sitter}))]
    ;; the mint is a wire-boundary effect: the router runs it after
    ;; every invoke, so a test that approves in-process runs it too
    (grants/approval-effects!
     *eng* (get (inv/resources *eng*) :approval_request) :approve
     (inv/invoke! *eng* :approval_request (:id ask) :approve nil
                  {:principal colton}))
    (let [grant (get-in (row-of :approval_request (:id ask)) [:data :grant_id])]
      (when-not (= "accepted" (state-of (row-of :grant grant)))
        (inv/invoke! *eng* :grant grant :accept {} {:principal sitter}))
      grant)))

(deftest a-mayors-sitter-makes-a-rule-and-another-seats-sitter-does-not
  (let [mayor (open-seat! "film-mayor")
        _ (open-seat! "film-clerk")
        _ (inv/create! *eng* :domain
                       {:name "film-house"
                        :charter "Keep the films worth watching."
                        :budget_usd_per_week 40M
                        :mayor (str (:id mayor))}
                       {:principal colton})
        ;; bare agents: neither acts for a person, so only the seat
        ;; its grant cites can admit it
        mayors (t/principal {:id "film-mayor-sitter" :type :agent
                             :display "The mayor's sitter"})
        clerks (t/principal {:id "film-clerk-sitter" :type :agent
                             :display "The clerk's sitter"})]
    (sit! "film-mayor" mayors)
    (sit! "film-clerk" clerks)
    (testing "a grant citing the domain's mayor seat makes a rule"
      (is (= "active" (state-of (make-rule! {:name "made-by-a-mayor"} mayors))))
      (is (some? (rule-named "made-by-a-mayor"))))
    (testing "a grant citing another seat is refused"
      (let [d (refusal #(make-rule! {:name "made-by-a-clerk"} clerks))]
        (is (some? d))
        (is (str/includes? (str (:text d)) "a-person-or-a-mayor-makes-the-rule"))
        (is (nil? (rule-named "made-by-a-clerk")))))))

;; ── the restate ─────────────────────────────────────────────────────

(defn- forget-type-size! []
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (jdbc/execute! tx [(str "DELETE FROM film_rules WHERE data->>'name'"
                              " = 'type-size'")]))))

(deftest a-persons-restate-survives-the-boot-seed
  (forget-type-size!)
  (film-rules/ensure-seed-rules! *eng*)
  (is (= "phone" (name (:output (:data (rule-named "type-size"))))))
  (try
    (restate! "type-size" {:output nil} colton)
    (testing "the rule holds no output, and says who restated it"
      (is (nil? (:output (:data (rule-named "type-size")))))
      (is (= "colton" (:restated_by (:data (rule-named "type-size"))))))
    (testing "the next boot seed leaves it alone"
      (let [version (:version (rule-named "type-size"))]
        (film-rules/ensure-seed-rules! *eng*)
        (is (nil? (:output (:data (rule-named "type-size")))))
        (is (= version (:version (rule-named "type-size"))))))
    (finally
      ;; the other tests read the seed's own type-size
      (forget-type-size!)
      (film-rules/ensure-seed-rules! *eng*))))

(deftest a-mayors-sitter-restates-a-rule-and-another-seats-sitter-does-not
  (let [mayor (open-seat! "rule-mayor")
        _ (open-seat! "rule-clerk")
        _ (inv/create! *eng* :domain
                       {:name "rule-house"
                        :charter "Keep the film rules where they apply."
                        :budget_usd_per_week 40M
                        :mayor (str (:id mayor))}
                       {:principal colton})
        mayors (t/principal {:id "rule-mayor-sitter" :type :agent
                             :display "The mayor's sitter"})
        clerks (t/principal {:id "rule-clerk-sitter" :type :agent
                             :display "The clerk's sitter"})]
    (sit! "rule-mayor" mayors)
    (sit! "rule-clerk" clerks)
    (make-rule! {:name "to-restate" :metric "type_px" :op ">=" :threshold 28M
                 :scope "shot"}
                colton)
    (testing "a grant citing another seat is refused"
      (let [d (refusal #(restate! "to-restate" {:output "phone"} clerks))]
        (is (some? d))
        (is (str/includes? (str (:text d))
                           "a-person-or-a-mayor-restates-the-rule"))
        (is (nil? (:output (:data (rule-named "to-restate")))))))
    (testing "and so is a bare agent"
      (is (some? (refusal #(restate! "to-restate" {:output "phone"} clerk))))
      (is (nil? (:restated_by (:data (rule-named "to-restate"))))))
    (testing "a grant citing the domain's mayor seat restates the rule"
      (restate! "to-restate" {:output "phone" :unless "zoom"} mayors)
      (let [data (:data (rule-named "to-restate"))]
        (is (= "phone" (name (:output data))))
        (is (= "zoom" (name (:unless data))))
        (is (= "rule-mayor-sitter" (:restated_by data)))))))

;; ── the take ────────────────────────────────────────────────────────

(def ^:private a-take
  "A take with every field § 8d names."
  {:film {:frame {:w 1080 :h 1920}
          :content_box {:x 0 :y 48 :w 1080 :h 1824}
          :dead_air_s 0.6M
          :chrome_leaks 0
          :runtime_s 42}
   :shots [{:role "friction" :goal_state "open"
            :viewport {:w 1000 :h 800}
            :focus_box {:x 100 :y 100 :w 500 :h 400}
            :focus_type_px 30 :focus_contrast 7.2M
            :hold_s 4 :words 6
            :surfaces_changed ["ticket-list"]}
           {:role "turn" :goal_state "open"
            :viewport {:w 1000 :h 800}
            :focus_box {:x 0 :y 0 :w 1000 :h 400}
            :focus_type_px 28 :focus_contrast 4.5M
            :hold_s 3 :words 9
            :surfaces_changed 2}
           {:role "payoff" :goal_state "done"
            :viewport {:w 1000 :h 800}
            :focus_box {:x 0 :y 0 :w 1000 :h 800}
            :focus_type_px 36 :focus_contrast 12M
            :hold_s 5 :words 3
            :surfaces_changed []}]})

(deftest each-metric-is-read-from-the-take
  (testing "the film's metrics are one number each"
    (is (== 0.95 (film-rules/measure a-take "frame_fill")))
    (is (== 0.6M (film-rules/measure a-take "dead_air_s")))
    (is (== 0 (film-rules/measure a-take "chrome_leaks")))
    (is (== 42 (film-rules/measure a-take "runtime_s")))
    (is (== 1 (film-rules/measure a-take "arc"))))
  (testing "a shot's metrics are one number per shot, in order"
    (is (= [0.25 0.5 1.0] (film-rules/measure a-take "focus_share")))
    (is (= [30 28 36] (film-rules/measure a-take "type_px")))
    (is (= [7.2M 4.5M 12M] (film-rules/measure a-take "contrast")))
    (is (= [2.0 1.0 5.0] (film-rules/measure a-take "read_time_ratio")))
    (is (= [1 2 0] (film-rules/measure a-take "surfaces_changed"))))
  (testing "every metric of the vocabulary is read"
    (doseq [m film-rules/metric-names]
      (is (some? (film-rules/measure a-take m)) m)))
  (testing "the arc is 0 when the roles are out of order or the goal is not done"
    (is (== 0 (film-rules/measure (update a-take :shots (comp vec reverse)) "arc")))
    (is (== 0 (film-rules/measure
               (assoc-in a-take [:shots 2 :goal_state] "open") "arc"))))
  (testing "a missing field and an unknown metric read as nil"
    (is (nil? (film-rules/measure (update a-take :film dissoc :frame) "frame_fill")))
    (is (= [nil] (film-rules/measure {:shots [{:hold_s 4 :words 0}]}
                                     "read_time_ratio")))
    (is (nil? (film-rules/measure a-take "loudness")))))
