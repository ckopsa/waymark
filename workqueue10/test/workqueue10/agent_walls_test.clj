(ns workqueue10.agent-walls-test
  "A person's lever is approval-required, never person-only
  (docs/spec-seat.md R-14.7, docs/spec-ticket.md D-6).

  This walks every guard every module declares — the household's kinds
  (workqueue10.main/check-resources), the day job's
  (factory10.main/check-resources) and the engine's own (seats,
  sittings, grants, schedules, transcripts, mcp servers, verdicts …,
  through waymark10.modules/enrolled) — and judges each one twice: once as
  a bare agent (no person behind it) and once as a bare person, over
  the same row and input. A guard that refuses the agent and admits the
  person refuses the agent because it is an agent. When the same agent
  still fails as a delegate (`:acts-for` a person) presenting a grant
  that admits every door, no approval a person can give opens the wall:
  it refuses the agent ONLY because it is an agent. Such a wall must
  be a hold (`:hold true`, and `holds/approved-hold?` before it denies),
  so the agent's call waits for the person's tap instead of dying as a
  409 — or it is named in `flat-walls` below, with the reason it stays
  a flat refusal.

  The verdict is the guard's own, never its source text: a guard that
  cannot be judged offline (it reads a store the probe ctx lacks)
  throws, and a throw is no evidence either way.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.agent-walls-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as factory]
            [waymark10.checks :as checks]
            [waymark10.guards :as g]
            [waymark10.holds :as holds]
            [waymark10.machine :as machine]
            [waymark10.modules :as modules]
            [waymark10.types :as t]
            [workqueue10.main :as queue])
  (:import (java.time Instant)))

;; ── the walls that stay flat ───────────────────────────────────────────

(def flat-walls
  "Guards that refuse a bare agent and admit a person, and stay flat
  refusals on purpose. Each entry says why in one sentence. A wall
  still waiting for its conversion to a hold says `pending <ticket>`."
  {:owner-is-self-or-on-behalf
   "It decides whose room a row is born in, and is not a person's lever: an agent's row is born in the agent's own room."
   :a-private-value-is-a-persons-own
   "It decides whose value a row is, and is not a person's lever: the engine stamps the writer as the owner, so an agent's \"mine\" would be about somebody else's life."
   :a-person-corrects
   "A correction overrules a seat's verdict, and the count of corrections is what a seat is measured by: an agent that could correct, even on a person's approval, would be writing its own measurement."})

;; ── the probe ─────────────────────────────────────────────────────────

(defn- ctx-as [principal]
  {:mode :invoke
   :now (Instant/parse "2026-01-01T12:00:00Z")
   :principal principal
   :services {:features []}
   :rate nil})

(def ^:private bare-agent (ctx-as {:id "probe-agent" :type :agent :roles #{}}))

(def ^:private bare-persons
  "A scenario's person is `:person`; the wire's is `:human`."
  [(ctx-as {:id "probe-person" :type :person :roles #{}})
   (ctx-as {:id "probe-person" :type :human :roles #{}})])

(def ^:private approved-delegate
  "The agent with every approval a person can give short of a hold: it
  acts for a person, under a grant that admits every door on every row."
  (assoc (ctx-as {:id "probe-agent" :type :agent :roles #{}
                  :acts-for "probe-person"})
         :grant {:id "probe-grant"
                 :action? (constantly true)
                 :row? (constantly true)}))

(defn- verdict
  "The guard's verdict, or ::unjudged when it cannot be judged offline."
  [lg row inp ctx]
  (try (first (g/evaluate lg row inp ctx))
       (catch Throwable _ ::unjudged)))

(defn- walls-the-agent?
  "Does this guard refuse a bare agent where it admits a bare person,
  and go on refusing the agent as an approved delegate, on some row and
  input the probe tries?"
  [lg row]
  (let [refused? (fn [inp ctx]
                   (let [v (verdict lg row inp ctx)]
                     (and (not= ::unjudged v) (t/deny? v))))
        admitted? (fn [inp ctx]
                    (let [v (verdict lg row inp ctx)]
                      (and (not= ::unjudged v) (t/allow? v))))]
    (boolean
     (some (fn [inp]
             (and (refused? inp bare-agent)
                  (some #(admitted? inp %) bare-persons)
                  (refused? inp approved-delegate)))
           [nil {} (zipmap (:judges lg) (repeat true))]))))

(defn- hold? [lg]
  (or (true? (:hold lg)) (holds/hold? (:name lg))))

(defn- probe-row [r door]
  (when-not (= :create door)
    (let [a (some #(when (= door (:name %)) %) (machine/actions-seq r))
          from (:from a)
          state (or (when (coll? from) (first (sort-by str from)))
                    (when (keyword? from) from)
                    (first (:states r)))]
      {:kind (:kind r) :id "probe:agent-walls" :state state :data {}})))

(defn agent-only-walls
  "Every guard site in `resources` that refuses a bare agent only
  because it is an agent, is not a hold, and is not allowlisted:
  [{:kind :door :guard-name} …]."
  [resources allowlist]
  (->> resources
       (mapcat (fn [r]
                 (for [{:keys [door guard]} (checks/guard-sites r)
                       :when (not (hold? guard))
                       :when (not (contains? allowlist (:name guard)))
                       :when (walls-the-agent? guard (probe-row r door))]
                   {:kind (:kind r) :door door :guard-name (:name guard)})))
       distinct
       vec))

(defn- module-resources
  "The modules' kinds, and the engine's own beside them (seats, sittings,
  grants, schedules, transcripts, mcp servers, verdicts …) — the same
  list the engine boots with, read from the module table with no store."
  []
  (->> (let [app (concat (factory/check-resources) (queue/check-resources))]
         (concat app (modules/enrolled (vec app) nil)))
       (reduce (fn [acc r] (if (contains? (:seen acc) (:kind r))
                             acc
                             (-> acc (update :seen conj (:kind r))
                                 (update :rs conj r))))
               {:seen #{} :rs []})
       :rs))

(defn- failure-message [walls]
  (str/join
   "\n"
   (for [{:keys [kind door guard-name]} walls]
     (str "guard " guard-name " (" (name kind) " " (name door) ") refuses an agent"
          " outright where it admits a person. Make it a hold (`:hold true` and"
          " the replay check, holds/approved-hold?), or add it to"
          " workqueue10.agent-walls-test/flat-walls with a reason."))))

;; ── the check ─────────────────────────────────────────────────────────

(deftest no-guard-refuses-an-agent-outright
  (let [walls (agent-only-walls (module-resources) flat-walls)]
    (is (empty? walls) (failure-message walls))))

(deftest every-allowlist-entry-says-why
  (doseq [[guard-name why] flat-walls]
    (is (not (str/blank? why)) (str guard-name " is allowlisted without a reason"))))

;; ── the check, on a fixture ───────────────────────────────────────────

(def ^:private agent-wall
  (g/guard {:name :fixture-refuses-every-agent
            :reads [:principal]
            :explain "A fixture wall: no agent passes."
            :check (fn [_row _inp ctx]
                     (if (= :agent (:type (:principal ctx))) (t/deny) (t/allow)))}))

(def ^:private held-wall
  (g/guard {:name :fixture-holds-every-agent
            :reads [:principal :within]
            :hold true
            :explain "A fixture hold: an agent waits for the person's tap."
            :check (fn [row _inp ctx]
                     (cond (not= :agent (:type (:principal ctx))) (t/allow)
                           (holds/approved-hold? ctx :fixture :poke (:id row)) (t/allow)
                           :else (t/deny)))}))

(def ^:private everyone-wall
  (g/guard {:name :fixture-refuses-everyone
            :reads [:principal]
            :explain "A fixture wall: only the engine's own hand passes."
            :check (fn [_row _inp ctx]
                     (if (= :system (:type (:principal ctx))) (t/allow) (t/deny)))}))

(defn- fixture-kind [guard]
  {:kind :fixture :states [:open :done]
   :actions {:poke {:from #{:open} :to :done :guards [guard]}}})

(deftest the-check-fails-a-guard-that-refuses-an-agent-outright
  (testing "a flat agent wall is named"
    (is (= [{:kind :fixture :door :poke :guard-name :fixture-refuses-every-agent}]
           (agent-only-walls [(fixture-kind agent-wall)] {}))))
  (testing "the same wall, allowlisted, passes"
    (is (empty? (agent-only-walls [(fixture-kind agent-wall)]
                                  {:fixture-refuses-every-agent "a fixture"}))))
  (testing "the same wall as a hold passes"
    (is (empty? (agent-only-walls [(fixture-kind held-wall)] {}))))
  (testing "a wall that refuses the person too is not an agent wall"
    (is (empty? (agent-only-walls [(fixture-kind everyone-wall)] {}))))
  (testing "a grantable wall is approval-required, not person-only"
    (is (empty? (agent-only-walls
                 [(fixture-kind (g/unless-granted :fixture :poke
                                                  {:name :fixture-grantable
                                                   :explain "A fixture: an agent needs a grant."}))]
                 {})))))
