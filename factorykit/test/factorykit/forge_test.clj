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

;; ── the change pass (ticket 97bd7937) ───────────────────────────────

(def ^:private door-states
  {:merge :merged :close :closed :reopen :open :fold :closed})

(defn- a-hand
  "A factory's hand over an atom of rows, with no engine behind it. Each
  write is kept in `calls` as [door row-id]."
  [rows calls]
  (let [write! (fn [id f] (get (swap! rows update id f) id))]
    {:forge-id-prefix "bitbucket:"
     :find-change (fn [change-id]
                    (first (filter #(= change-id (get-in % [:data :change_id]))
                                   (vals @rows))))
     :branch-changes (fn [repository head limit]
                       (take limit
                             (filter #(and (= repository (get-in % [:data :repository]))
                                           (= head (get-in % [:data :head_branch])))
                                     (vals @rows))))
     :create! (fn [fields]
                (let [id (str "row-" (inc (count @rows)))]
                  (swap! calls conj [:create id])
                  (write! id (constantly {:id id :state :open :data fields}))))
     :invoke! (fn [id door input]
                (swap! calls conj [door id])
                (write! id #(-> %
                                (update :data merge input)
                                (update :state (fn [s] (get door-states door s))))))}))

(def ^:private a-pull-request
  {:change_id "bitbucket:acme/shop#7" :repository "acme/shop" :number 7
   :title "Add the till" :author "sam" :base_branch "main"
   :head_branch "till" :head_sha "aaa" :draft false
   :url "https://bitbucket.example/acme/shop/pull-requests/7"
   :mergeable "clean" :files_changed 2 :forge_state "open"})

(def ^:private a-house-row
  {:id "row-9" :state :submitted
   :data {:change_id "ticket:abc" :born_from "ticket:abc"
          :repository "acme/shop" :head_branch "till"}})

(defn- pass! [hand changes]
  (kit/change-pass! hand changes {} (fn [& _])))

(deftest a-pull-request-no-seat-asked-for-is-skipped
  ;; R-C.6: a person's pull request is GitHub's work, not the house's
  (let [rows (atom {})
        calls (atom [])]
    (is (= {:skipped 1} (pass! (a-hand rows calls) [a-pull-request])))
    (is (empty? @calls) "no row is minted for it")
    (is (= {} @rows))))

(deftest an-adopted-pull-request-moves-with-the-forge
  (let [rows (atom {"row-9" (assoc a-house-row :state :open)})
        calls (atom [])
        hand (a-hand rows calls)]
    (is (= {:adopted 1} (pass! hand [a-pull-request])))
    (is (= [[:adopt "row-9"] [:observe "row-9"]] @calls))
    (is (= "clean" (get-in @rows ["row-9" :data :mergeable])))
    (is (not (contains? (get-in @rows ["row-9" :data]) :forge_state))
        "the forge's state is no field of the row")
    (is (= {} (pass! hand [a-pull-request])) "nothing new moves nothing")
    (reset! calls [])
    (is (= {:moved 1}
           (pass! hand [(assoc a-pull-request :forge_state "merged"
                               :head_sha "bbb")])))
    (is (= [[:observe "row-9"] [:merge "row-9"]] @calls))
    (is (= :merged (get-in @rows ["row-9" :state])))
    (is (= "bbb" (get-in @rows ["row-9" :data :head_sha])))))

(deftest a-pull-request-on-a-house-row's-branch-is-adopted
  (let [rows (atom {"row-9" a-house-row})
        calls (atom [])]
    (is (= {:adopted 1} (pass! (a-hand rows calls) [a-pull-request])))
    (is (= [[:adopt "row-9"] [:observe "row-9"]] @calls))
    (is (= 1 (count @rows)) "no second row is minted beside it")
    (is (= "bitbucket:acme/shop#7" (get-in @rows ["row-9" :data :change_id])))
    (is (= :submitted (get-in @rows ["row-9" :state])))))

(deftest a-duplicate-the-forge-minted-is-folded-into-the-house-row
  (let [minted {:id "row-1" :state :open
                :data (select-keys a-pull-request
                                   [:change_id :repository :head_branch])}
        rows (atom {"row-1" minted
                    "row-9" (assoc a-house-row :state :stuck)})
        calls (atom [])]
    (is (= {:folded 1} (pass! (a-hand rows calls) [a-pull-request])))
    (is (= [[:fold "row-1"] [:adopt "row-9"]] @calls))
    (is (= "row-9" (get-in @rows ["row-1" :data :folded_into])))
    (is (= :closed (get-in @rows ["row-1" :state])))))

(deftest a-change-is-linked-once-its-pull-request-row-exists
  ;; ticket 56c97770: the link follows by the next pass at the latest
  (let [rows (atom {"row-9" a-house-row})
        calls (atom [])
        prs (atom {})
        hand (assoc (a-hand rows calls)
                    :find-pull-request (fn [repository number]
                                         (get @prs [repository number])))
        links #(filter (comp #{:link} first) @calls)]
    (is (= {:adopted 1} (pass! hand [a-pull-request])))
    (is (empty? (links)) "no pull_request row yet, so no link")
    (swap! prs assoc ["acme/shop" 7] "pr-7")
    (pass! hand [a-pull-request])
    (is (= [[:link "row-9"]] (links)))
    (is (= "pr-7" (get-in @rows ["row-9" :data :pull_request])))
    (pass! hand [a-pull-request])
    (is (= [[:link "row-9"]] (links)) "a linked change is not linked again")))

(deftest a-refused-link-still-moves-the-row
  (let [rows (atom {"row-9" a-house-row})
        calls (atom [])
        base (a-hand rows calls)
        hand (assoc base
                    :find-pull-request (constantly "pr-7")
                    :invoke! (fn [id door input]
                               (if (= :link door)
                                 (throw (ex-info "held by another change" {}))
                                 ((:invoke! base) id door input))))]
    (is (= {:adopted 1} (pass! hand [a-pull-request])))
    (is (nil? (get-in @rows ["row-9" :data :pull_request])))))

(deftest a-refused-row-is-counted-and-kept-for-the-source
  (let [hand (assoc (a-hand (atom {"row-9" a-house-row}) (atom []))
                    :invoke! (fn [& _] (throw (ex-info "refused by the test" {}))))
        said (atom [])
        refused (volatile! [])]
    (is (= {:refused 1}
           (kit/change-pass! hand [a-pull-request] {}
                             (fn [& parts] (swap! said conj (apply str parts)))
                             refused)))
    (is (= [a-pull-request] @refused))
    (is (= [(str "the pull request bitbucket:acme/shop#7 was refused a row "
                 "(refused by the test)")]
           @said))))

(deftest a-change-its-pull-request-already-moved-is-a-skip
  ;; R-C.7: the pull request's subscription walked `merge` first, so
  ;; the pass's own `merge` refuses `wrong_state`: nothing to do
  (let [rows (atom {"row-9" (assoc-in a-house-row [:data :change_id]
                                      (:change_id a-pull-request))})
        calls (atom [])
        base (a-hand rows calls)
        hand (assoc base
                    :invoke! (fn [id door input]
                               (if (= :merge door)
                                 (throw (ex-info "already merged"
                                                 {:waymark10/problem :wrong-state}))
                                 ((:invoke! base) id door input))))]
    (is (= {} (pass! hand [(assoc a-pull-request :forge_state "merged")])))))

(deftest the-pass-moves-through-the-factory's-own-move
  (let [seen (atom [])
        hand (assoc (a-hand (atom {"row-9" a-house-row}) (atom []))
                    :move! (fn [row _] (swap! seen conj (:id row)) [row false]))]
    (is (= {:adopted 1} (pass! hand [a-pull-request])))
    (is (= ["row-9"] @seen))))

(deftest a-held-conflict-is-not-written-over
  (let [row {:data {:mergeable "conflicted" :head_sha "aaa"}}]
    (is (= {} (kit/changed-facts row {:mergeable "unknown" :head_sha "aaa"})))
    (is (= {:mergeable "unknown" :head_sha "bbb"}
           (kit/changed-facts row {:mergeable "unknown" :head_sha "bbb"})))
    (is (= {:mergeable "clean"}
           (kit/changed-facts row {:mergeable "clean" :head_sha "aaa"})))))

(defn- a-source
  "A forge that answers one poll and keeps its place. Each retry, commit
  and forget is kept in `said`."
  [answer said budget]
  (reify
    kit/ForgeSource
    (forge-poll [_] answer)
    (forge-log-tail [_ _] {:excerpt "" :note ""})
    (forge-label! [_ _ _] nil)
    (forge-calls [_] 1)
    (forge-checks [_ _ _] [])
    (forge-base [_ _ _] nil)
    kit/ForgeRetry
    (forge-retry! [_ docs] (swap! said conj [:retry (count docs)]))
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
        hand (a-hand (atom {}) (atom []))
        census (kit/pass!
                {:source (a-source an-answer said {:remaining 10})
                 :change-pass! (fn [changes c refused]
                                 (kit/change-pass! hand changes c
                                                   (fn [& _]) refused))
                 :log-fn (fn [& _])
                 :before-changes (fn [c _] (swap! steps conj :before) c)
                 :after-changes (fn [c _] (swap! steps conj :after) c)
                 :always (fn [c _ held] (swap! steps conj [:always held]) c)
                 :report (fn [c _] (swap! steps conj [:report (:skipped c)]))})]
    (is (= {:repositories 1 :complete? true :skipped 1 :calls 1
            :remaining 10}
           census))
    (is (= [[:commit ["acme/shop"]]] @said))
    (is (= [:before :after [:always nil] [:report 1]] @steps))))

(deftest a-refused-row-is-offered-again
  (let [said (atom [])
        hand (assoc (a-hand (atom {"row-9" a-house-row}) (atom []))
                    :invoke! (fn [& _] (throw (ex-info "refused by the test" {}))))]
    (is (= 1 (:refused (kit/pass! {:source (a-source an-answer said {})
                                   :change-pass!
                                   (fn [changes c refused]
                                     (kit/change-pass! hand changes c
                                                       (fn [& _]) refused))
                                   :log-fn (fn [& _])}))))
    (is (= [[:retry 1] [:commit ["acme/shop"]]] @said))))

(deftest a-spent-limit-holds-the-pass-and-sends-the-source-back
  (let [rows (atom {})
        said (atom [])
        steps (atom [])
        census (kit/pass!
                {:source (a-source an-answer said
                                   {:held-until "2026-10-14T04:00:00Z"})
                 :hand (a-hand rows (atom []))
                 :log-fn (fn [& _])
                 :before-changes (fn [c _] (swap! steps conj :before) c)
                 :always (fn [c _ held] (swap! steps conj [:always held]) c)})]
    (is (= {} @rows) "no row is written while the limit is spent")
    (is (= [[:forget]] @said))
    (is (= [[:always "2026-10-14T04:00:00Z"]] @steps))
    (is (= "2026-10-14T04:00:00Z" (:held-until census)))))

(deftest a-throw-sends-the-source-back-after-the-always-step
  (let [said (atom [])
        steps (atom [])]
    (is (thrown? Exception
                 (kit/pass!
                  {:source (a-source an-answer said {})
                   :hand (a-hand (atom {}) (atom []))
                   :log-fn (fn [& _])
                   :change-pass! (fn [& _] (throw (ex-info "thrown" {})))
                   :always (fn [c _ _] (swap! steps conj :always) c)})))
    (is (= [[:forget]] @said))
    (is (= [:always] @steps))))

(deftest the-state-door-table
  (is (= :merge (kit/state-door :stuck "merged")))
  (is (= :close (kit/state-door :failing "closed")))
  (is (= :reopen (kit/state-door :closed "open")))
  (is (nil? (kit/state-door :open "open")))
  (is (nil? (kit/state-door :merged "closed"))))
