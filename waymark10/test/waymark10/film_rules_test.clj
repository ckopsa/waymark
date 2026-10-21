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
            [waymark10.schema :as schema]
            [waymark10.server.engine :as engine]
            [waymark10.server.film-rules :as film-rules]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

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

(deftest seeds-that-run-at-once-leave-one-row-for-each-name
  (let [names (mapv :name film-rules/seed-rules)
        err (java.io.StringWriter.)]
    (dotimes [_ 3]
      (store/with-tx (:storage *eng*)
        (fn [tx]
          (jdbc/execute! tx (into [(str "DELETE FROM film_rules WHERE data->>'name' IN ("
                                        (str/join ", " (repeat (count names) "?"))
                                        ")")]
                                  names))))
      (let [go (java.util.concurrent.CountDownLatch. 1)
            seeds (binding [*err* err]
                    (mapv (fn [_]
                            (future (.await go)
                                    (film-rules/ensure-seed-rules! *eng*)))
                          (range 4)))]
        (.countDown go)
        (run! deref seeds))
      (doseq [name' names]
        (is (= 1 (count (rows-of {:name name'}))) name')))
    (testing "the create the index refused is not reported as a failed seed"
      (is (= "" (str err))))))

(deftest an-engine-that-is-not-workqueue10-has-the-seed-rules-after-boot
  ;; this engine names no application kind: the seed is the engine's own
  (store/with-tx (:storage *eng*)
    (fn [tx] (jdbc/execute! tx ["DELETE FROM film_rules"])))
  (engine/start-runtime! *eng*)
  (try
    (is (= 15 (count film-rules/seed-rules)))
    (doseq [{:keys [name]} film-rules/seed-rules]
      (testing name
        (is (= 1 (count (rows-of {:name name}))))))
    (finally (engine/stop-runtime! *eng*))))

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

(deftest a-create-does-not-name-who-restated-the-rule
  (let [rdef (get (inv/resources *eng*) :film_rule)]
    (testing "the create model leaves the field out, and the row's schema keeps it"
      (is (not (contains? (set (schema/entry-keys (:create-schema rdef)))
                          :restated_by)))
      (is (contains? (set (schema/entry-keys (:schema rdef))) :restated_by))))
  (testing "a create that names it is refused, and no row carries it"
    (let [d (refusal #(make-rule! {:name "marked-at-birth" :restated_by "colton"}
                                  colton))]
      (is (some? d))
      (is (nil? (:restated_by (:data (rule-named "marked-at-birth"))))))))

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
            :surfaces_changed []}]
   :runs [{:caption "The list" :frame_match {:held 0.97M :mid 0.95M}}
          {:caption "The turn" :frame_match 0.93M}]})

(deftest each-metric-is-read-from-the-take
  (testing "the film's metrics are one number each"
    (is (== 0.95 (film-rules/measure a-take "frame_fill")))
    (is (== 0.6M (film-rules/measure a-take "dead_air_s")))
    (is (== 0 (film-rules/measure a-take "chrome_leaks")))
    (is (== 42 (film-rules/measure a-take "runtime_s")))
    (is (== 1 (film-rules/measure a-take "arc")))
    (is (== 0.93M (film-rules/measure a-take "frame_match_min"))))
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
  (testing "the arc reads only the shots that carry a role, as a rendered week has"
    (let [bare {:caption "A quiet day."}
          [f t p] (:shots a-take)
          week (assoc a-take :shots [bare f bare t bare bare p])]
      (is (== 1 (film-rules/measure week "arc")))
      (is (== 0 (film-rules/measure
                 (assoc week :shots [bare t bare f bare p]) "arc")))))
  (testing "a missing field and an unknown metric read as nil"
    (is (nil? (film-rules/measure (update a-take :film dissoc :frame) "frame_fill")))
    (is (= [nil] (film-rules/measure {:shots [{:hold_s 4 :words 0}]}
                                     "read_time_ratio")))
    (is (nil? (film-rules/measure a-take "loudness")))))

;; ── the judging of a take ───────────────────────────────────────────

(def ^:private a-good-take
  "a-take on a phone, with the turn held for its words and changing one
  surface, and two days that tell what happened, one of them asking the
  person: it passes every seed rule but the played week's, which cannot
  measure a take short of a week."
  (-> a-take
      (assoc-in [:film :output] "phone")
      (assoc-in [:shots 0 :caption] "Monday. Acme's study went to review.")
      (update-in [:shots 1] assoc
                 :caption "The turn" :hold_s 5 :surfaces_changed 1)
      (assoc-in [:shots 2 :caption]
                "Tuesday. A person was asked to sign off; Acme's study went to done.")))

(def ^:private grey-corner
  "The phone film whose content sits in the top left of a grey frame."
  (update a-good-take :film assoc
          :frame {:w 1170 :h 2532}
          :content_box {:x 0 :y 0 :w 390 :h 844}))

(def ^:private a-small-shot
  (assoc (first (:shots a-take))
         :caption "Close in" :focus_box {:x 0 :y 0 :w 10 :h 10}))

(defn- judged [tk] (film-rules/judge film-rules/seed-rules tk))

(def ^:private played-names
  "The rules of a played first week (ticket 853b6707)."
  #{"no-bare-list" "busy-week" "week-arc" "asks-the-person"})

(defn- rule-in [answer name']
  (first (filter #(= name' (:name %)) (:rules answer))))

(deftest a-take-is-judged-against-the-seed-rules
  (testing "a good take is green, with one verdict for each rule by name"
    (let [answer (judged a-good-take)]
      (is (= "green" (:verdict answer)))
      ;; a-good-take is a first week short of a played one: the played
      ;; week's rules cannot measure it, and are never unscored on it
      (is (= ["asks-the-person" "busy-week" "no-bare-list" "week-arc"]
             (:unmeasured answer)))
      (is (= (sort (map :name film-rules/seed-rules))
             (map :name (:rules answer))))
      (is (every? #(= (if (played-names (:name %)) "unmeasured" "pass") (:verdict %))
                  (:rules answer)))
      (testing "an unmeasured fail rule counts as a miss, a warn rule as a pass"
        (is (= "miss" (:unmeasured (rule-in answer "busy-week"))))
        (is (= "pass" (:unmeasured (rule-in answer "type-size")))))
      (is (= {:name "frame-fill" :metric "frame_fill" :op ">="
              :threshold 0.95M :severity "fail"}
             (select-keys (rule-in answer "frame-fill")
                          [:name :metric :op :threshold :severity])))
      (is (string? (:why (rule-in answer "frame-fill"))))))
  (testing "the grey-corner phone take is red on frame-fill with about 0.11"
    (let [answer (judged grey-corner)
          fill (rule-in answer "frame-fill")]
      (is (= "red" (:verdict answer)))
      (is (= "miss" (:verdict fill)))
      (is (< 0.11 (:value fill) 0.112))))
  (testing "a desktop take leaves type-size unscored"
    (let [answer (judged (assoc-in a-good-take [:film :output] "desktop"))]
      (is (= "unscored" (:verdict (rule-in answer "type-size"))))
      (is (= "green" (:verdict answer)))))
  (testing "a zoomed shot passes focus-share whatever it measures"
    (let [zoomed (assoc a-small-shot :zoom true)
          share #(rule-in (judged (assoc a-good-take :shots %)) "focus-share")]
      (is (= "pass" (:verdict (share [zoomed]))))
      (is (nil? (:worst (share [zoomed]))))
      (is (= "miss" (:verdict (share [a-small-shot]))))
      (is (= {:index 0 :caption "Close in"}
             (select-keys (:worst (share [a-small-shot])) [:index :caption])))
      (is (< (:value (:worst (share [a-small-shot]))) 0.25))
      (testing "and is not the worst shot of a take that passes"
        (let [mixed (share (assoc (:shots a-good-take) 0 zoomed))]
          (is (= "pass" (:verdict mixed)))
          (is (= 1 (:index (:worst mixed))))))))
  (testing "a take with no dead_air_s leaves dead-air unmeasured, by name"
    (let [answer (judged (update a-good-take :film dissoc :dead_air_s))
          air (rule-in answer "dead-air")]
      (is (= "unmeasured" (:verdict air)))
      (is (= ["film.dead_air_s"] (:missing air)))
      (is (= ["asks-the-person" "busy-week" "dead-air" "no-bare-list" "week-arc"]
             (:unmeasured answer)))))
  (testing "a shot that lacks a field leaves its rule unmeasured"
    (let [answer (judged (update-in a-good-take [:shots 2] dissoc :focus_type_px))]
      (is (= "unmeasured" (:verdict (rule-in answer "type-size"))))
      (is (= ["shot.focus_type_px"] (:missing (rule-in answer "type-size"))))))
  (testing "a take whose visible text holds an /api path is red on chrome-leaks"
    (let [answer (judged (assoc-in a-good-take [:film :chrome_leaks] 1))]
      (is (= "red" (:verdict answer)))
      (is (= "miss" (:verdict (rule-in answer "chrome-leaks"))))
      (is (== 1 (:value (rule-in answer "chrome-leaks"))))))
  (testing "a warn rule that misses names its worst shot, and the take is warn"
    (let [answer (judged (assoc-in a-good-take [:shots 1 :surfaces_changed]
                                   ["ticket-list" "drawer"]))
          surface (rule-in answer "one-surface")]
      (is (= "warn" (:verdict answer)))
      (is (= "miss" (:verdict surface)))
      (is (= {:index 1 :caption "The turn" :value 2} (:worst surface))))))

(def ^:private good-week
  "Seven day captions that tell a first week: no bare list, two quiet
  days, and the person asked once."
  ["Monday. Hedge lead came in, Yard study was drawn, Front hedge was sent."
   "Tuesday. A quiet day."
   "Wednesday. Yard study was written."
   "Thursday. A person was asked to sign off on Yard study."
   "Friday. Front hedge's invoice was sent."
   "Saturday. A quiet day."
   "Sunday. Front hedge's invoice was paid."])

(defn- week-take
  "A take of the first week: an opening, a shot for each of `days` and a
  closing, running the arc's roles."
  ([days] (week-take days true))
  ([days roles?]
   (let [role #(when roles? %)]
     {:film {:output "phone"}
      :shots (-> [{:role (role "friction")
                   :caption "Example Landscaping: A six-person landscaping business."}]
                 (into (map (fn [c] {:role (role "turn") :caption c}) days))
                 (conj {:role (role "payoff") :goal_state "done"
                        :caption "That was the week."}))})))

(def ^:private week-rules
  (filter #(#{"caption-not-list" "quiet-days" "asked-person"} (:name %))
          film-rules/seed-rules))

(deftest the-first-week-is-judged-from-its-day-captions
  (let [judged-week #(film-rules/judge week-rules (week-take %))
        missed #(mapv :name (filter (comp #{"miss"} :verdict) (:rules %)))]
    (testing "a week whose seven days tell what happened passes all three"
      (let [answer (judged-week good-week)]
        (is (= "green" (:verdict answer)))
        (is (= [] (:unmeasured answer)))
        (is (= ["pass" "pass" "pass"] (mapv :verdict (:rules answer))))))
    (testing "a day that is a bare list fails caption-not-list"
      (let [answer (judged-week (assoc good-week 0 "Monday. Hedge lead, Yard study, Front hedge."))]
        (is (= "red" (:verdict answer)))
        (is (= ["caption-not-list"] (missed answer)))
        (is (== 1 (:value (rule-in answer "caption-not-list"))))))
    (testing "a third quiet day fails quiet-days"
      (let [answer (judged-week (assoc good-week 2 "Wednesday. A quiet day."))]
        (is (= "red" (:verdict answer)))
        (is (= ["quiet-days"] (missed answer)))
        (is (== 3 (:value (rule-in answer "quiet-days"))))))
    (testing "a week that never asks the person fails asked-person"
      (let [answer (judged-week (assoc good-week 3 "Thursday. Yard study went to review."))]
        (is (= "red" (:verdict answer)))
        (is (= ["asked-person"] (missed answer)))
        (is (== 0 (:value (rule-in answer "asked-person"))))))
    (testing "a scene whose shots carry no role, as LP7's, is not judged by them"
      (let [answer (film-rules/judge week-rules
                                     (week-take ["Monday. Hedge lead, Yard study, Front hedge."] false))]
        (is (= "green" (:verdict answer)))
        (is (= ["unscored" "unscored" "unscored"] (mapv :verdict (:rules answer))))))
    (testing "a take with the roles and no day caption is unmeasured, not passed"
      (let [answer (film-rules/judge week-rules (week-take []))]
        (is (= ["asked-person" "caption-not-list" "quiet-days"] (:unmeasured answer)))
        (is (= ["shot.caption"] (:missing (rule-in answer "asked-person"))))))))

(def ^:private arc-rules
  (filterv #(= "arc" (:name %)) film-rules/seed-rules))

(deftest a-first-week-is-not-judged-by-the-walk-arc
  (let [no-goal (update a-take :shots (partial mapv #(dissoc % :goal_state)))
        arc-of #(rule-in (film-rules/judge arc-rules %) "arc")]
    (testing "a first week with the roles and no goal_state leaves arc unscored"
      (let [answer (film-rules/judge arc-rules (assoc no-goal :scene {:first_week true}))]
        (is (= "unscored" (:verdict (rule-in answer "arc"))))
        (is (= [] (:unmeasured answer)))
        (is (not= "red" (:verdict answer)))))
    (testing "a walk take with no goal_state is still unmeasured"
      (is (= {:verdict "unmeasured" :missing ["shot.goal_state"]}
             (select-keys (arc-of no-goal) [:verdict :missing]))))
    (testing "a walk take whose goal is open still misses"
      (is (= "miss" (:verdict (arc-of (assoc-in a-take [:shots 2 :goal_state] "open"))))))))

(def ^:private weekdays
  ["Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday" "Sunday"])

(defn- call
  "A meanwhile shot of a take: the call and what it did."
  ([as kind action outcome] (call as kind action outcome nil))
  ([as kind action outcome held-by]
   {:meanwhile (cond-> {:as as :kind kind :action action :outcome outcome}
                 held-by (assoc :held_by held-by))}))

(defn- played-take
  "A first week as the template renders it, knocking on prospects: an
  opening, seven day captions with day 2 the friction and day 5 the
  turn, each after its day's `calls` (day → meanwhile shots), and the
  closing, the payoff."
  [captions calls]
  {:film {:output "phone" :kind "prospect" :cast ["owner" "acme"]}
   :shots (-> [{:caption "Example Landscaping: A six-person landscaping business."}]
              (into (mapcat (fn [day caption]
                              (conj (vec (get calls day))
                                    (cond-> {:caption caption}
                                      (= 2 day) (assoc :role "friction")
                                      (= 5 day) (assoc :role "turn"))))
                            (range 1 8) captions))
              (conj {:role "payoff" :goal_state "done" :caption "That was the week."}))})

(def ^:private told-week
  ["Monday. Hedge lead came in."
   "Tuesday. Acme's payroll records were late."
   "Wednesday. Yard study was drawn."
   "Thursday. Acme was chased for its records."
   "Friday. A person was asked to sign off on Yard study."
   "Saturday. A quiet day."
   "Sunday. Yard study was delivered."])

(def ^:private told-calls
  "A late record on day 2, a chase answered and a tap on day 5, and a
  study the person delivered on day 7."
  {1 [(call "acme" "prospect" "create" "moved")]
   2 [(call "acme" "record" "create" "moved")]
   4 [(call "owner" "record" "chase" "moved" "seat")]
   5 [(call "acme" "record" "submit" "moved")
      (call "owner" "study" "sign_off" "held" "person")]
   7 [(call "owner" "study" "deliver" "moved" "person")]})

(def ^:private played-rules
  (filter (comp played-names :name) film-rules/seed-rules))

(deftest a-played-first-week-is-judged-from-its-captions-and-its-calls
  (let [judged-week #(film-rules/judge played-rules (played-take %1 %2))
        missed #(mapv :name (filter (comp #{"miss"} :verdict) (:rules %)))]
    (testing "a late record, a chase, a tap and a delivered study pass all four"
      (let [answer (judged-week told-week told-calls)]
        (is (= "green" (:verdict answer)))
        (is (= [] (:unmeasured answer)))
        (is (= ["pass" "pass" "pass" "pass"] (mapv :verdict (:rules answer))))
        (is (== 0 (:value (rule-in answer "no-bare-list"))))
        (is (== 6 (:value (rule-in answer "busy-week"))))
        (is (== 3 (:value (rule-in answer "week-arc"))))
        (is (== 2 (:value (rule-in answer "asks-the-person"))))))
    (testing "an all-quiet week misses busy-week and week-arc"
      (let [answer (judged-week (mapv #(str % ". A quiet day.") weekdays)
                                {2 [(call "acme" "record" "create" "refused")]})]
        (is (= "red" (:verdict answer)))
        (is (= ["asks-the-person" "busy-week" "week-arc"] (missed answer)))
        (is (== 0 (:value (rule-in answer "busy-week"))))
        (is (== 0 (:value (rule-in answer "week-arc"))))))
    (testing "a day that is a bare list misses no-bare-list"
      (let [answer (judged-week (assoc told-week 2 "Wednesday. Hedge lead, Yard study, Front hedge.")
                                told-calls)]
        (is (= ["no-bare-list"] (missed answer)))))
    (testing "moves on the knocked kind alone tell no arc"
      (let [knocked (into {} (map (fn [[day calls]]
                                    [day (mapv #(assoc-in % [:meanwhile :kind] "prospect") calls)]))
                          told-calls)
            answer (judged-week told-week knocked)]
        (is (= ["week-arc"] (missed answer)))
        (is (== 0 (:value (rule-in answer "week-arc"))))))
    (testing "a week that never reaches the person misses asks-the-person"
      (let [answer (judged-week told-week (assoc told-calls
                                                 5 [(call "acme" "record" "submit" "moved")]
                                                 7 [(call "owner" "study" "deliver" "moved" "seat")]))]
        (is (= ["asks-the-person"] (missed answer)))))
    (testing "an older render, whose week no meanwhile shot played, misses all four"
      (let [answer (judged-week told-week {})]
        (is (= "red" (:verdict answer)))
        (is (= ["asks-the-person" "busy-week" "no-bare-list" "week-arc"] (missed answer)))
        (is (every? #(= ["shot.meanwhile"] (:missing %)) (:rules answer)))))
    (testing "a scene whose shots carry no role, as LP7's, is not judged by them"
      (let [answer (film-rules/judge played-rules
                                     {:shots [{:caption "Monday. Hedge lead, Yard study, Front hedge."}]})]
        (is (= "green" (:verdict answer)))
        (is (= ["unscored" "unscored" "unscored" "unscored"] (mapv :verdict (:rules answer))))))
    (testing "the seed's arc reads past a meanwhile shot"
      (is (== 1 (film-rules/measure
                 (update a-take :shots #(into [(call "acme" "record" "create" "moved")] %))
                 "arc"))))))

(def ^:private dress-report
  "The renderer's report as the director hands it in (ticket a37c3249),
  under its own names, of a week the clone's clock played before the
  film."
  {:film "first-week.webm" :state "done" :url "https://clone.example/"
   :device "phone" :width 390 :height 844 :image "first-week.png"
   :lead_in_s 1.2 :shortest_shot "s4" :shortest_shot_s 3.1
   :pacing {:summary "even"}
   :scene {:first_week true :clock_days true}
   :shots (mapv (fn [beat]
                  (cond-> {:shot beat :id (str "s" beat) :ok true :pressed []
                           :expects nil :still (str "still-" beat ".png") :why ""}
                    (= 3 beat) (assoc :role "friction")
                    (= 6 beat) (assoc :role "turn")
                    (= 9 beat) (assoc :role "payoff")))
                (range 1 11))
   :stats {:webm_bytes 1200000 :mp4_bytes 900000 :duration_s 48.2 :played_ms 48200
           :content_box {:width 390 :height 820 :x 0 :y 12}
           :phases {} :card_s 2.0 :take "9d6c3b1a"}
   :scorecard {:verdict "red"
               :lines ["miss fail chrome-leaks: 51 against = 0"
                       "miss warn dead-air: 1.3 against <= 0.8"
                       "unmeasured arc: needs shot.goal_state"]}
   :take {:beats 10
          :lines ["1 · Example Landscaping: A six-person landscaping business."
                  "2 · Monday. Hedge lead came in."
                  "3 · Tuesday. Acme Robotics went to qualified."
                  "4 · Wednesday. A quiet day."
                  "5 · Thursday. Acme was chased for its records."
                  "6 · Friday. A person was asked to sign off on Yard study."
                  "7 · Saturday. Yard study was drawn."
                  "8 · Sunday. Yard study was delivered."
                  "9 · That was the week: 1 study delivered. Merge to make it real."
                  "10"]
          :measured "dead air 1.3 s in 1 spans; longest hold 6.3 s on beat 6"}})

(deftest the-renderer's-report-is-read-by-its-own-names
  (let [answer (film-rules/judge film-rules/seed-rules dress-report)]
    (testing "the first week's checks come out measured"
      (doseq [name' ["quiet-days" "busy-week" "chrome-leaks" "frame-fill" "asked-person"
                     "week-arc" "asks-the-person" "no-bare-list" "caption-not-list"]]
        (is (#{"pass" "miss"} (:verdict (rule-in answer name'))) name'))
      (is (not-any? #{"quiet-days" "busy-week" "chrome-leaks" "frame-fill" "asked-person"}
                    (:unmeasured answer))))
    (testing "by the report's names"
      (is (== 51 (:value (rule-in answer "chrome-leaks"))))
      (is (== 1.3 (:value (rule-in answer "dead-air"))))
      (is (< 0.97 (:value (rule-in answer "frame-fill")) 0.98))
      (is (== 1 (:value (rule-in answer "quiet-days"))))
      (is (== 6 (:value (rule-in answer "busy-week"))))
      (is (== 1 (:value (rule-in answer "asked-person"))))
      (is (== 1 (:value (rule-in answer "asks-the-person"))))
      (is (== 3 (:value (rule-in answer "week-arc")))))
    (testing "a field the take carries under its own name is kept"
      (is (== 0 (:value (rule-in (film-rules/judge film-rules/seed-rules
                                                   (assoc dress-report :film {:chrome_leaks 0}))
                                 "chrome-leaks")))))
    (testing "a week the clock did not mark still wants its calls"
      (let [answer (film-rules/judge film-rules/seed-rules
                                     (update dress-report :scene dissoc :clock_days))]
        (is (= ["shot.meanwhile"] (:missing (rule-in answer "busy-week"))))))))

(deftest a-take-is-a-first-week-when-its-scene-says-so
  (let [rules (concat week-rules played-rules)
        quiet (mapv #(str % ". A quiet day.") weekdays)
        week (played-take quiet {2 [(call "acme" "record" "create" "refused")]})
        roleless (update week :shots #(mapv (fn [shot] (dissoc shot :role)) %))
        verdict-of #(:verdict (rule-in %1 %2))]
    (testing "a take of a :first-week scene, no shot carrying a role, is judged by the week's rules"
      (let [answer (film-rules/judge rules (assoc roleless :scene {:first_week true}))]
        (is (= "red" (:verdict answer)))
        (is (= "miss" (verdict-of answer "busy-week")))
        (is (= "miss" (verdict-of answer "quiet-days")))))
    (testing "a take whose shots carry the roles is judged as before, marked or not"
      (let [answer (film-rules/judge rules week)]
        (is (= "miss" (verdict-of answer "busy-week")))
        (is (= "miss" (verdict-of answer "quiet-days")))
        (is (= answer (film-rules/judge rules (assoc week :scene {:first_week true}))))))
    (testing "a take with no mark and no role leaves the week's rules unscored"
      (let [answer (film-rules/judge rules roleless)]
        (is (= "green" (:verdict answer)))
        (is (every? #{"unscored"} (map :verdict (:rules answer))))))
    (testing "a mark that is not true marks nothing"
      (is (every? #{"unscored"}
                  (map :verdict (:rules (film-rules/judge rules (assoc roleless :scene {:first_week "yes"})))))))))

(deftest a-take-is-judged-on-the-frame-under-each-caption
  (let [made (make-rule! {:name "frame-shows-beat" :metric "frame_match_min"
                          :op ">=" :threshold 0.9M :severity "fail"}
                         colton)
        shows #(let [answer (film-rules/judge [(:data made)] %)]
                 [answer (rule-in answer "frame-shows-beat")])]
    (testing "the rule is made"
      (is (some? (rule-named "frame-shows-beat"))))
    (testing "a take whose runs all match passes, with the lowest run named"
      (let [[answer rule] (shows a-good-take)]
        (is (= "green" (:verdict answer)))
        (is (= "pass" (:verdict rule)))
        (is (== 0.93M (:value rule)))
        (is (= {:index 1 :caption "The turn" :value 0.93M} (:worst rule)))))
    (testing "a run whose mid-span frame scores below 0.9 fails the take"
      (let [[answer rule] (shows (assoc-in a-good-take [:runs 0 :frame_match :mid]
                                           0.41M))]
        (is (= "red" (:verdict answer)))
        (is (= "miss" (:verdict rule)))
        (is (= {:index 0 :caption "The list" :value 0.41M} (:worst rule)))))
    (testing "a take rendered before frame_match is unmeasured, not failed"
      (let [[answer rule] (shows (dissoc a-good-take :runs))
            [_ bare] (shows (assoc a-good-take :runs [{:caption "The list"}]))]
        (is (= "green" (:verdict answer)))
        (is (= ["frame-shows-beat"] (:unmeasured answer)))
        (is (= "unmeasured" (:verdict rule)))
        (is (= ["runs"] (:missing rule)))
        (is (= "unmeasured" (:verdict bare)))
        (is (= ["run.frame_match"] (:missing bare)))))))

(deftest a-take-that-carries-decimals-is-judged
  (let [phone {:frame {:w 1170 :h 2532} :output "phone"}
        rule #(rule-in (judged {:film phone :shots [%2]}) %1)
        scored? #(and (contains? #{"pass" "miss"} (:verdict %))
                      (number? (:value (:worst %))))]
    (testing "a hold over words with no exact quotient judges read-time"
      (let [read (rule "read-time" {:caption "A" :zoom false
                                    :hold_s 3.5M :words 7})]
        (is (scored? read))
        (is (< 1.49 (:value (:worst read)) 1.51)))
      (is (scored? (rule "read-time" {:hold_s 3.5M :words 7.0M}))))
    (testing "a decimal focus_box and viewport judge focus-share"
      (let [share (rule "focus-share"
                        {:focus_box {:x 0.5M :y 0.5M :w 333.3M :h 100.1M}
                         :viewport {:w 390.7M :h 844.3M}})]
        (is (scored? share))
        (is (< 0.1 (:value (:worst share)) 0.11))))
    (testing "a decimal content_box and frame judge frame-fill"
      (let [fill (rule-in (judged {:film {:frame {:w 1170.3M :h 2532.7M}
                                          :content_box {:w 390.1M :h 844.9M}}})
                          "frame-fill")]
        (is (= "miss" (:verdict fill)))
        (is (< 0.11 (:value fill) 0.112))))
    (testing "a divisor of zero is unmeasured, by name"
      (let [read (rule "read-time" {:hold_s 3.5M :words 0})
            share (rule "focus-share" {:focus_box {:w 10 :h 10}
                                       :viewport {:w 0 :h 800}})]
        (is (= "unmeasured" (:verdict read)))
        (is (= ["shot.words"] (:missing read)))
        (is (= "unmeasured" (:verdict share)))
        (is (= ["shot.viewport"] (:missing share)))))))

(deftest the-judge-door-answers-and-writes-nothing
  (film-rules/ensure-seed-rules! *eng*)
  (let [h (engine/handler *eng*)
        post! (fn [body]
                (h {:request-method :post
                    :uri "/api/film_rules/-/judge"
                    :headers {"x-waymark-principal" "colton"
                              "content-type" "application/json"}
                    :body (wire/write-json body)}))
        before (rows-of {})
        resp (post! {:take grey-corner})
        answer (wire/read-json (:body resp))]
    (testing "the door scores the take against the active rows"
      (is (= 200 (:status resp)))
      (is (= "red" (:verdict answer)))
      (is (= "miss" (:verdict (rule-in answer "frame-fill"))))
      (is (= "pass" (:verdict (rule-in answer "arc")))))
    (testing "a body with no take is refused"
      (is (= 422 (:status (post! {})))))
    (testing "it writes nothing"
      (is (= before (rows-of {}))))))

(deftest the-collection-names-the-judge-door-and-the-connector-calls-it
  (film-rules/ensure-seed-rules! *eng*)
  (let [h (engine/handler *eng*)
        headers {"x-waymark-principal" "colton"
                 "content-type" "application/json"}
        door (get-in (wire/read-json
                      (:body (h {:request-method :get
                                 :uri "/api/film_rules"
                                 :headers headers})))
                     [:actions :judge])
        route (h {:request-method :post
                  :uri "/api/film_rules/-/judge"
                  :headers headers
                  :body (wire/write-json {:take grey-corner})})
        before (rows-of {})
        out (mcp/call-tool *eng* (mcp/door *eng*) {:principal colton}
                           "waymark_invoke"
                           {:kind "film_rule" :action "judge"
                            :input {:take grey-corner}})]
    (testing "the collection names the door, with the take's input schema"
      (is (= "POST" (:method door)))
      (is (= "/api/film_rules/-/judge" (:href door)))
      (is (true? (get-in door [:safety :safe])))
      (is (some? (get-in door [:input :properties :take :properties :film
                               :properties :content_box])))
      (is (some? (get-in door [:input :properties :take :properties :shots]))))
    (testing "a connector call answers what the route answers"
      (is (not (:isError out)))
      (is (= 200 (:status route)))
      (is (= (wire/read-json (:body route))
             (wire/read-json (get-in out [:content 0 :text])))))
    (testing "it writes nothing"
      (is (= before (rows-of {}))))))

(defn- scoped
  "The grant's own closure shapes: a session that reads `kinds` and is
  granted no action at all."
  [& kinds]
  (let [reads? (fn [k] (contains? (set kinds) (name k)))]
    {:principal clerk
     :visibility {:kind? reads?
                  :row? (fn [k _id] (reads? k))
                  :action? (constantly false)
                  :arg? (constantly true)
                  :field? (constantly true)}}))

(defn- judge-tool [session args]
  (mcp/call-tool *eng* (mcp/door *eng*) session "waymark_invoke"
                 (merge {:kind "film_rule" :action "judge"} args)))

(defn- said [out] (get-in out [:content 0 :text]))

(deftest a-scoped-caller-reaches-the-judge-door-by-reading-the-kind
  (film-rules/ensure-seed-rules! *eng*)
  (let [input {:input {:take grey-corner}}
        whole (judge-tool {:principal colton} input)
        reader (judge-tool (scoped "film_rule") input)
        blind (judge-tool (scoped "seat") input)]
    (testing "a grant that reads the kind and names no action is answered"
      (is (not (:isError reader)))
      (is (= (wire/read-json (said whole)) (wire/read-json (said reader)))))
    (testing "a grant that does not read the kind gets the collection's not-found"
      (is (true? (:isError blind)))
      (is (= 404 (:status (wire/read-json (said blind))))))))

(deftest the-judge-door-is-not-scheduled
  (film-rules/ensure-seed-rules! *eng*)
  (let [later {:at "2027-01-04T08:30:00-06:00" :input {:take grey-corner}}
        out (judge-tool {:principal colton} later)
        rehearsed (judge-tool {:principal colton} (assoc later :dry_run true))]
    (testing "`at` on a safe collection door is refused in its own sentence"
      (doseq [o [out rehearsed]]
        (is (true? (:isError o)))
        (is (str/includes? (said o) "answers now and writes nothing"))
        (is (str/includes? (said o) "cannot be scheduled"))))))

(deftest the-judge-door-holds-a-take-to-its-advertised-schema
  (film-rules/ensure-seed-rules! *eng*)
  (let [h (engine/handler *eng*)
        post! (fn [body]
                (h {:request-method :post
                    :uri "/api/film_rules/-/judge"
                    :headers {"x-waymark-principal" "colton"
                              "content-type" "application/json"}
                    :body (wire/write-json body)}))
        wide (assoc-in grey-corner [:film :frame :w] "wide")
        before (rows-of {})]
    (testing "a field of the wrong type is refused, naming the field"
      (let [resp (post! {:take wide})]
        (is (= 422 (:status resp)))
        (is (str/includes? (str (:body resp)) "frame"))))
    (testing "a field the schema does not name is refused, not left unmeasured"
      (let [resp (post! {:take (assoc grey-corner :flim {})})]
        (is (= 422 (:status resp)))
        (is (str/includes? (str (:body resp)) "flim")))
      (is (= 422 (:status (post! {:take grey-corner :note "beside the take"})))))
    (testing "the connector's call is refused the same way"
      (let [out (judge-tool {:principal colton} {:input {:take wide}})]
        (is (true? (:isError out)))
        (is (str/includes? (said out) "frame"))))
    (testing "a rehearsal is the call: dry_run answers what the call answers"
      (let [plain (judge-tool {:principal colton} {:input {:take grey-corner}})
            dry (judge-tool {:principal colton}
                            {:input {:take grey-corner} :dry_run true})]
        (is (not (:isError dry)))
        (is (= (wire/read-json (said plain)) (wire/read-json (said dry))))))
    (testing "it writes nothing"
      (is (= before (rows-of {}))))))
