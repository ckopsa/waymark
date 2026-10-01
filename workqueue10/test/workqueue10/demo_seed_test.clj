(ns workqueue10.demo-seed-test
  "The demo seed itself (docs/spec-demo-clones.md § 1, § 5 item 1),
  loaded where its own kinds are: the factory's kinds over the
  in-memory twin. This is what keeps the seed true as the kinds change:
  a step the law now refuses fails here before it fails a clone's boot.
  The boot step that reads the two variables is here too, given their
  values as arguments.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.demo-seed-test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.main :as factory]
            [waymark10.dev :as dev]
            [waymark10.server.invoke :as inv]
            [waymark10.server.seed :as seed]
            [waymark10.server.store :as store]
            [workqueue10.main :as main]))

(defn- moves [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind kind :resource-id (str id)} {}))))

(defn- state-of [row] (keyword (name (:state row))))

(deftest the-demo-seed-loads-and-reaches-every-ticket-state-but-in-review
  (let [eng (dev/scratch! (factory/resources) {:name "demo-test"})
        demo (seed/read-seed "demo")
        result (seed/load! eng demo {})
        tickets (dev/rows eng :ticket)
        by-state (frequencies (map state-of tickets))
        declared (set (:states (get (inv/resources eng) :ticket)))]
    (is (true? (:seeded result)))
    (testing "every state the kind declares but in_review, which only a change's submit reaches"
      (is (contains? declared :in_review))
      (is (= (disj declared :in_review) (set (keys by-state))))
      (is (every? #(<= 2 %) (vals by-state)) (pr-str by-state)))
    (testing "each ticket reached its state by the doors"
      (doseq [t tickets]
        (is (seq (moves eng :ticket (:id t))) (get-in t [:data :title]))))
    (testing "two households, as two groups of members"
      (is (= 2 (count (set (map :household (vals (:cast demo)))))))
      (doseq [m (vals (:cast demo))]
        (is (some? (dev/row eng :member (:id m))) (:id m))))
    (testing "one held call waits on a person's tap"
      (let [held (dev/rows eng :held_call)]
        (is (= [:held] (mapv state-of held)))
        (is (= "plan" (get-in (first held) [:data :caller])))))
    (testing "one open invitation, to the member the person signs in as"
      (let [invited (dev/rows eng :invitation)]
        (is (= [:open] (mapv state-of invited)))
        (is (= "ada" (get-in (first invited) [:data :subject])))))
    (testing "a restarted task does not seed twice"
      (is (false? (:seeded (seed/load! eng demo {}))))
      (is (= (count tickets) (count (dev/rows eng :ticket)))))))

;; ── the boot step ───────────────────────────────────────────────────

(def ^:private seed-on-boot! @#'main/seed-on-boot!)

(def ^:private factory-refusal
  "WAYMARK10_SEED is set and FACTORY10 is not 1: the seed's tickets need the factory kinds, so a demo engine boots with FACTORY10=1.")

(defn- booted
  "What `seed/boot!` was called with while `f` ran: a vector of
  [engine seed-name] pairs, the seed itself never loaded."
  [f]
  (let [calls (atom [])]
    (with-redefs [seed/boot! (fn [eng seed-name]
                               (swap! calls conj [eng seed-name])
                               nil)]
      (f))
    @calls))

(deftest a-seed-without-the-factory-kinds-refuses-the-boot
  (doseq [factory [nil "" "0" "true"]]
    (testing (pr-str factory)
      (let [thrown (atom nil)
            calls (booted #(try (seed-on-boot! ::eng "demo" factory)
                                (catch clojure.lang.ExceptionInfo e
                                  (reset! thrown e))))]
        (is (some? @thrown))
        (is (= factory-refusal (some-> @thrown ex-message)))
        (is (= {:seed "demo"} (some-> @thrown ex-data)))
        (is (= [] calls) "the refusal comes before the seed is read")))))

(deftest no-seed-named-does-nothing
  (doseq [seed [nil ""]
          factory [nil "1"]]
    (testing (pr-str [seed factory])
      (let [answer (atom ::unset)
            calls (booted #(reset! answer (seed-on-boot! ::eng seed factory)))]
        (is (nil? @answer))
        (is (= [] calls))))))

(deftest a-seed-with-the-factory-kinds-boots-it-by-name
  (is (= [[::eng "demo"]]
         (booted #(seed-on-boot! ::eng "demo" "1")))))
