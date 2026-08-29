(ns waymark10.law-scenarios-test
  "The declared policy, proved — and proved to cost nothing.

  Four claims, in the order they matter:

  1. The SENTENCE is the violation. A guard broken in a test-local
     declaration fails its scenario with the household's own words,
     never with `expected :deny, got :allow`. (The app's guards are
     never broken to prove this; the broken copy lives here.)
  2. The GUARD is what :expect names. A guard swapped for a different
     guard with the same words still fails — which is the whole reason
     :expect takes a name and not prose.
  3. The TIER is read off declarations. No :given and offline :reads ⇒
     judged here, in process, with no storage of any kind. Anything
     else waits for the suite, with the reason printed.
  4. A scenario is NEVER law. Adding, editing or deleting one leaves
     every kind's fingerprint hash byte-identical. fingerprint-of is a
     whitelist, so this holds by construction — and it is pinned
     because a test that minted a law revision would be a test that
     triggered a propose-mode hold, and the framework would be at war
     with itself.

  The last deftest pays the other half: one CONFORMANCE-tier scenario
  driven through the real HTTP door by the :core/law-scenarios
  obligation, so the staging, the declared principal's headers and the
  envelope reading are all exercised over a real engine. Needs the
  waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.declare :refer [defscenario]]
            [waymark10.fingerprint :as fp]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.scenario :as scenario]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.test.suite :as suite]
            [waymark10.types :as t]))

;; ── one suite-local law ─────────────────────────────────────────────

;; defguard, not a bare :check — the macro captures the body's printed
;; form, which is what gives the wall an identity the fingerprint can
;; state (waymark-j82). A formless check would hash by its address and
;; earn the [opaque-residue] warning this suite's own coverage test
;; insists nobody here has.
(g/defguard curator-only
  {:explain "Only a curator closes an errand; ask one to."
   :reads [:principal]
   :remedies [:approval_request/create]}
  [_ _ ctx]
  (if (contains? (:roles (:principal ctx)) "curator")
    (t/allow) (t/deny)))

(def ^:private curator-only-broken
  "The same wall with the same words and no law behind it — what a
  careless edit leaves. Everything about it reads right; only the
  verdict is wrong, which is precisely the failure a scenario exists
  to catch."
  (assoc curator-only :check (fn [_ _ _] (t/allow))))

(def ^:private someone-elses-name
  "A different guard with the SAME sentence — the swap :expect's
  guard-naming is built to notice."
  (g/guard {:name :not-the-curator
            :explain "Only a curator closes an errand; ask one to."
            :reads [:principal]
            :check (fn [_ _ _] (t/deny))}))

(defscenario a-neighbour-cannot-close
  "Somebody who is not a curator does not close the family's errand,
   and the refusal names the way to ask."
  {:kind    :errand
   :attempt :close
   :row     {:state :open :data {:title "Take the bins out"}}
   :as      {:id "otto" :type :person}
   :expect  {:refused :curator-only
             :because "Only a curator closes an errand"
             :remedies [:approval_request/create]}})

(defscenario a-curator-closes
  "A curator closes it, which is the whole point of holding the role."
  {:kind    :errand
   :attempt :close
   :row     {:state :open :data {:title "Take the bins out"}}
   :as      {:id "mom" :type :person :roles #{:curator}}
   :expect  {:allowed true}})

(defscenario a-closed-errand-does-not-close-twice
  "A closed errand stays closed — the machine refuses the second
   knock, with no guard behind it."
  {:kind    :errand
   :attempt :close
   :row     {:state :closed :data {:title "Take the bins out"}}
   :as      {:id "mom" :type :person :roles #{:curator}}
   :expect  {:refused :out-of-state}})

(defscenario through-the-door-a-neighbour-is-refused
  "The refusal a CLIENT sees is the same refusal the guard returned,
   over rows that genuinely exist."
  {:kind    :errand
   :attempt :close
   :given   [{:kind :errand :state :open :data {:title "The one already on the list"}}]
   :row     {:state :open :data {:title "Take the bins out"}}
   :as      {:id "otto" :type :person}
   :expect  {:refused :curator-only
             :because "Only a curator closes an errand"
             :remedies [:approval_request/create]}})

(def ^:private errand-map
  {:kind :errand
   :plural "errands"
   :states [:open :closed]
   :initial :open
   :terminal #{:closed}
   :summary "{data.title} · {state}"
   :schema [:map [:title [:string {:min 1 :max 80}]]]
   :actions
   {:close {:from #{:open} :to :closed
            :guards [curator-only]
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A closed errand stays closed."}}}
   :scenarios [a-neighbour-cannot-close
               a-curator-closes
               a-closed-errand-does-not-close-twice
               through-the-door-a-neighbour-is-refused]})

(def ^:private errand (r/resource errand-map))

(defn- with-guard
  "The same declaration behind a different wall — how every negative
  case here is built, so the app's own guards are never touched."
  [gd]
  (r/resource (assoc-in errand-map [:actions :close :guards] [gd])))

;; ── 1. the sentence is the violation ────────────────────────────────

(deftest a-broken-guard-fails-in-the-households-own-words
  (let [broken (with-guard curator-only-broken)
        {:keys [violations checked]} (scenario/report broken)]
    (is (= 3 checked) "three of the four are judged with no storage at all")
    (is (= 1 (count violations)) "only the wall that broke reports")
    (let [v (first violations)]
      (testing "the scenario's own sentence leads"
        (is (str/includes?
             v "Somebody who is not a curator does not close the family's errand")))
      (testing "and the diagnosis rides after it"
        (is (str/includes? v "the law allowed it")))
      (testing "never the shape of the assertion"
        (is (not (str/includes? v "expected")))))))

;; ── 2. :expect names the guard, not the prose ───────────────────────

(deftest a-swapped-guard-with-the-same-words-still-fails
  (let [swapped (with-guard someone-elses-name)
        vs (:violations (scenario/report swapped))]
    (is (some #(str/includes? % ":expect :refused names :curator-only") vs)
        "the structural fault reads as itself: that guard is not on this action")
    (is (some #(str/includes? % "a-curator-closes") vs)
        "and the wall that now refuses everyone breaks the allowance too")))

(deftest a-polished-sentence-is-caught-by-because
  (let [reworded (with-guard (assoc curator-only
                                    :explain "Curators close errands."))
        vs (:violations (scenario/report reworded))]
    (is (= 1 (count vs)))
    (is (str/includes? (first vs) "the reason reads \"Curators close errands.\""))))

(deftest a-refusal-that-stops-naming-the-way-out-fails
  (let [mute (with-guard (assoc curator-only :remedies []))
        vs (:violations (scenario/report mute))]
    (is (= 1 (count vs)))
    (is (str/includes? (first vs) "no way out"))))

;; ── the green run ───────────────────────────────────────────────────

(deftest the-law-as-declared-keeps-every-promise
  (is (= [] (:violations (scenario/report errand)))))

(deftest core-kinds-keep-their-own-scenarios
  (testing "every scenario the assembled registry declares, judged for free"
    (let [reg (:kinds (engine/full-registry [errand]))
          reports (for [[_ rdef] reg
                        :when (seq (:scenarios rdef))]
                    (scenario/report rdef))]
      (is (some #(pos? (:total %)) reports)
          "core's own approval_request declares scenarios")
      (is (= [] (into [] (mapcat :violations) reports))))))

;; ── 3. the tier, read off declarations ──────────────────────────────

(deftest the-tier-is-declared-never-sniffed
  (testing "no :given, offline :reads — judged with no storage"
    (is (scenario/check-tier? errand a-neighbour-cannot-close))
    (is (nil? (scenario/deferral-reason errand a-neighbour-cannot-close))))
  (testing ":given rows drop it to the suite, and say so"
    (is (not (scenario/check-tier? errand through-the-door-a-neighbour-is-refused)))
    (is (= "stages 1 given row"
           (scenario/deferral-reason errand through-the-door-a-neighbour-is-refused))))
  (testing "a law that reaches past the offline set drops too, naming what it reads"
    (let [storage-bound (with-guard (assoc curator-only :reads [:principal :storage]))]
      (is (not (scenario/check-tier? storage-bound a-neighbour-cannot-close)))
      (is (= "reads :storage"
             (scenario/deferral-reason storage-bound a-neighbour-cannot-close)))))
  (testing "the report prints the split"
    (let [{:keys [total checked deferred]} (scenario/report errand)]
      (is (= 4 total))
      (is (= 3 checked))
      (is (= [{:name :through-the-door-a-neighbour-is-refused
               :why "stages 1 given row"}]
             deferred)))))

(deftest coverage-is-counted-never-enforced
  (let [[named walls] (scenario/coverage errand)]
    (is (= 1 walls))
    (is (= 1 named))
    (is (empty? (:waymark10/warnings (meta errand)))
        "a wall nobody names is never a warning — a warning nobody can clear is a warning nobody reads")))

;; ── 4. a scenario is never law ──────────────────────────────────────

(defn- hash-of [rmap]
  (fp/fingerprint-hash (r/fingerprint (r/normalize-resource rmap))))

(deftest editing-a-scenario-mints-no-law-revision
  (let [bare (dissoc errand-map :scenarios)
        edited (assoc errand-map :scenarios
                      [(assoc-in a-neighbour-cannot-close [:expect :because] "Only a curator")])
        deleted (assoc errand-map :scenarios [])]
    (testing "the whole point: :scenarios is outside fingerprint-of's whitelist"
      (is (= (hash-of bare) (hash-of errand-map) (hash-of edited) (hash-of deleted))))
    (testing "and the projection itself never names them"
      (is (not (contains? (fp/fingerprint-of (r/normalize-resource errand-map))
                          "scenarios"))))))

;; ── construction refuses at the def line ────────────────────────────

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest a-malformed-scenario-refuses-where-it-is-written
  (testing "the sentence is required, exactly as a guard's explain is"
    (is (str/includes? (refusal #(scenario/scenario :s "  " {:kind :errand}))
                       "the sentence comes first")))
  (testing "the key set is closed"
    (is (str/includes? (refusal #(scenario/scenario :s "A sentence."
                                                    {:kind :errand :attempt :close
                                                     :expect {:allowed true}
                                                     :gvien []}))
                       "not scenario keys")))
  (testing "a scenario is a verdict, and exactly one"
    (is (str/includes? (refusal #(scenario/scenario :s "A sentence."
                                                    {:kind :errand :attempt :close
                                                     :expect {:allowed true
                                                              :refused :curator-only}}))
                       "exactly one of :allowed and :refused")))
  (testing ":refused names the guard, not the sentence"
    (is (str/includes? (refusal #(scenario/scenario :s "A sentence."
                                                    {:kind :errand :attempt :close
                                                     :expect {:refused "Only a curator…"}}))
                       "names the GUARD"))))

(deftest an-unknown-declaration-key-still-refuses-every-boot
  (is (str/includes? (refusal #(r/resource (assoc errand-map :scenraios [])))
                     "declaration")
      ":scenarios joining top-level-keys did not open the closed set"))

;; ── the structural faults the def line could not see ────────────────

(deftest the-assembled-declaration-answers-what-the-def-line-could-not
  (testing "an attempt the kind does not declare"
    (let [s (scenario/scenario :ghost "A sentence."
                               {:kind :errand :attempt :abandon
                                :row {:state :open :data {}}
                                :expect {:allowed true}})]
      (is (str/includes? (first (scenario/judge errand s))
                         "attempts :abandon, which errand does not declare"))))
  (testing "a row in a state the kind does not declare"
    (let [s (scenario/scenario :nowhere "A sentence."
                               {:kind :errand :attempt :close
                                :row {:state :vanished :data {}}
                                :expect {:allowed true}})]
      (is (str/includes? (first (scenario/judge errand s))
                         "which errand does not declare"))))
  (testing "a timed law with no moment named"
    (let [timed (with-guard (assoc curator-only :reads [:principal :now]))
          s (scenario/scenario :untimed "A sentence."
                               {:kind :errand :attempt :close
                                :row {:state :open :data {}}
                                :as {:id "otto" :type :person}
                                :expect {:allowed true}})]
      (is (str/includes? (first (scenario/judge timed s))
                         "consults the clock")))))

;; ── the conformance tier, through the real door ─────────────────────

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(def ^:private tables
  ["errands" "members" "roles" "grants" "approval_requests" "definitions"
   "verdict_reasons"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "events"])

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (engine/engine {:storage st :resources [errand]})]
          (binding [*eng* eng *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

(deftest the-deferred-half-is-paid-by-the-suite
  (let [report (suite/run (suite/context {:engine *eng* :handler *h*
                                          :kinds [:errand]}))
        mine (first (filter #(= :core/law-scenarios (:name %)) report))]
    (is (some? mine) "core's pack owes the obligation")
    (is (= [] (:violations mine)))
    (is (= 9 (:covered mine))
        (str "exactly the scenarios the check tier could not judge, and no"
             " more: ranking_note's one (waymark-1uv.6 — a person refused"
             " the birth door, deferred by the chain rule exactly as"
             " verdict_reason's is), errand's one, plus the two recipe_proposal staging"
             " scenarios the :feed module enrols into every engine"
             " (waymark-0k4 — their create door carries a wall that reads"
             " the house's own recipe rows), plus the two feed_view ones"
             " (waymark-8um.1 — the same shape, one door over: its create"
             " reads the member's switch and this member's own day), plus"
             " verdict_reason's TWO (waymark-jfv.16 and waymark-hcr — and"
             " the deferral is the CHAIN's rather than either wall's:"
             " nobody-explains-somebody-elses-no reads only the caller and"
             " a-claim-is-not-answered-with-an-offers-word reads only the"
             " body, but their door's last guard counts rows, and a create"
             " scenario is judged against the whole chain), plus remark's one (waymark-vf8 — the chain"
             " rule again: nobody-speaks-in-somebody-elses-voice reads only"
             " the caller, and words-do-not-answer now stands beside it on"
             " that door reading the SUBJECT's row and its kind's own"
             " :answered-at-a-door). The other three of errand's are not"
             " re-run here"))))
