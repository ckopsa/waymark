(ns factorykit.forge-test
  "The build-state table and the conformance cases (ticket 6b716e98):
  the kit's own judge passes every case, and each JSON copy says what
  its EDN file says."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [factorykit.forge :as kit]
            [jsonista.core :as json]))

(defn- json-of [n]
  (json/read-value (slurp (io/resource n)) json/keyword-keys-object-mapper))

(defn- edn-of [n]
  (edn/read-string (slurp (io/resource n))))

(deftest the-json-copies-say-what-the-edn-files-say
  (is (= (edn-of "build-states.edn") (json-of "build-states.json")))
  (is (= (edn-of "forge-conformance.edn") (json-of "forge-conformance.json"))))

(deftest the-kit-passes-its-own-cases
  (is (seq (:cases kit/conformance)))
  (is (= [] (kit/conformance-failures))))

(deftest a-wrong-judge-fails-and-is-named
  (is (= {:case "a skipped required check reads green"
          :expect {:verdict "green"}
          :actual {:verdict "red" :names ["gate"]}}
         (first (kit/conformance-failures
                 (fn [c]
                   (if (= "a skipped required check reads green" (:name c))
                     {:verdict :red :names ["gate"]}
                     (kit/judge c))))))))

(deftest every-raw-state-has-one-verdict-the-table-names
  (let [verdicts (set (map keyword (:verdicts kit/build-states)))]
    (doseq [[forge by-verdict] (:forges kit/build-states)]
      (is (every? verdicts (keys by-verdict)) (str forge))
      (is (apply distinct? (mapcat val by-verdict))
          (str forge " maps one raw state two ways")))))

(deftest a-state-the-table-does-not-name-is-never-green
  (is (nil? (kit/build-state :github {:status "completed" :conclusion "startup_failure"})))
  (is (nil? (kit/build-state :bitbucket {:state "PAUSED"})))
  (is (nil? (kit/verdict {:forge :bitbucket :required ["build"]}
                         [{:check_name "build" :state "PAUSED"}]))))

;; ── the change rows and the pass ───────────────────────────────────

(def ^:private a-pull-request
  {:change_id "bitbucket:acme/shop#7" :repository "acme/shop" :number 7
   :title "Add the till" :author "sam" :base_branch "main"
   :head_branch "till" :head_sha "aaa" :draft false
   :url "https://bitbucket.example/acme/shop/pull-requests/7"
   :mergeable "clean" :files_changed 2 :forge_state "open"})

(deftest a-held-conflict-is-not-written-over
  (let [row {:data {:mergeable "conflicted" :head_sha "aaa"}}]
    (is (= {} (kit/changed-facts row {:mergeable "unknown" :head_sha "aaa"})))
    (is (= {:mergeable "unknown" :head_sha "bbb"}
           (kit/changed-facts row {:mergeable "unknown" :head_sha "bbb"})))
    (is (= {:mergeable "clean"}
           (kit/changed-facts row {:mergeable "clean" :head_sha "aaa"})))))

(defn- a-source
  "A forge that answers one poll and keeps its place. Each commit and
  forget is kept in `said`."
  [answer said budget]
  (reify
    kit/ForgeSource
    (forge-poll [_] answer)
    (forge-log-tail [_ _] {:excerpt "" :note ""})
    (forge-label! [_ _ _] nil)
    (forge-calls [_] 1)
    (forge-checks [_ _ _] [])
    (forge-base [_ _ _] nil)
    kit/ForgeCommit
    (forge-commit! [_ repositories] (swap! said conj [:commit repositories]))
    (forge-forget! [_] (swap! said conj [:forget]))
    kit/ForgeBudget
    (forge-budget [_] budget)))

(def ^:private an-answer
  {:changes [a-pull-request] :checks [] :repositories ["acme/shop"]
   :complete? true})

(deftest the-kit-polls-a-forge-and-stores-its-place
  (let [said (atom [])
        steps (atom [])
        census (kit/pass!
                {:source (a-source an-answer said {:remaining 10})
                 :log-fn (fn [& _])
                 :before-changes (fn [c _] (swap! steps conj :before) c)
                 :after-changes (fn [c _] (swap! steps conj :after) c)
                 :always (fn [c _ held] (swap! steps conj [:always held]) c)
                 :report (fn [c _] (swap! steps conj [:report (:repositories c)]))})]
    (is (= {:repositories 1 :complete? true :calls 1 :remaining 10}
           census)
        "no row is written for a polled pull request")
    (is (= [[:commit ["acme/shop"]]] @said))
    (is (= [:before :after [:always nil] [:report 1]] @steps))))

(deftest a-spent-limit-holds-the-pass-and-sends-the-source-back
  (let [said (atom [])
        steps (atom [])
        census (kit/pass!
                {:source (a-source an-answer said
                                   {:held-until "2026-10-14T04:00:00Z"})
                 :log-fn (fn [& _])
                 :before-changes (fn [c _] (swap! steps conj :before) c)
                 :always (fn [c _ held] (swap! steps conj [:always held]) c)})]
    (is (= [[:forget]] @said))
    (is (= [[:always "2026-10-14T04:00:00Z"]] @steps))
    (is (= "2026-10-14T04:00:00Z" (:held-until census)))))

(deftest a-throw-sends-the-source-back-after-the-always-step
  (let [said (atom [])
        steps (atom [])]
    (is (thrown? Exception
                 (kit/pass!
                  {:source (a-source an-answer said {})
                   :log-fn (fn [& _])
                   :before-changes (fn [& _] (throw (ex-info "thrown" {})))
                   :always (fn [c _ _] (swap! steps conj :always) c)})))
    (is (= [[:forget]] @said))
    (is (= [:always] @steps))))

(deftest the-state-door-table
  (is (= :merge (kit/state-door :stuck "merged")))
  (is (= :close (kit/state-door :failing "closed")))
  (is (= :reopen (kit/state-door :closed "open")))
  (is (nil? (kit/state-door :open "open")))
  (is (nil? (kit/state-door :merged "closed"))))
