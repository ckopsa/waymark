(ns factory10.pull-request-test
  "The pull_request Mirror (ticket d2ceedc9), over REAL rows and the
  in-memory GitHub: discovery reads the house's own change rows, pull
  spends the forge's `forge-pull`, and neither writes a change nor moves
  the forge pass's cursor.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.mirror :as house-mirror]
            [factory10.resources.pull-request :as pr]
            [factory10.sources.forge :as forge]
            [factory10.sources.github :as gh]
            [waymark10.server.door-receivers :as doors]
            [waymark10.server.engine :as engine]
            [waymark10.server.history :as history]
            [waymark10.server.integrations :as integrations]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.nio.charset StandardCharsets)
           (java.time Instant)))

(def ^:private repo "ckopsa/waymark")

(def ^:private a-person (t/principal {:id "colton" :type :human}))

(defn- a-pull [number]
  {:number number
   :state "open"
   :title (str "Change " number)
   :user {:login "ckopsa"}
   :draft false
   :base {:ref "main"}
   :head {:ref (str "engine/change-" number) :sha (format "%040d" number)}
   :html_url (str "https://github.com/ckopsa/waymark/pull/" number)
   :updated_at "2026-09-18T12:00:00Z"
   :labels [{:name "agent"}]
   :mergeable_state "clean"
   :changed_files 2
   :additions 40
   :deletions 3})

(defn- boot [adapter clock]
  (engine/engine {:storage (memory/storage)
                  :now-fn (fn [] @clock)
                  :resources (conj (filterv #(not= :pull_request (:kind %))
                                            (main/resources))
                                   (pr/pull-request-resource adapter))}))

(defn- rows [eng kind]
  (let [st (:storage eng)
        rd (get (inv/resources eng) kind)]
    (mapv #(inv/decode-row rd %)
          (store/with-tx st (fn [tx] (store/query-rows st tx kind {} {:all true}))))))

(defn- the-change [eng number]
  (first (filter #(= number (get-in % [:data :number])) (rows eng :change))))

(defn- a-seat-row!
  "The row a seat's sit minted for pull request `number`'s branch, so
  the forge pass adopts it: it mints no change for a pull request no
  seat asked for (R-C.6). A repository with no cursor yet is given
  one from before the pull requests opened, as a house whose seats
  submit has read its repositories before: a cursor the store seeded
  from the row's own stamp would pass the pull requests by."
  [eng number]
  (let [store (forge/cursor-store eng)]
    (when-not ((:load store) repo)
      ((:save! store) repo "2026-09-18T11:00:00Z"))
    (inv/create! eng :change {:change_id (str "ticket:t" number)
                              :repository repo
                              :title (str "Change " number)
                              :head_branch (str "engine/change-" number)
                              :base_branch "main"}
                 {:principal house-mirror/source-principal})))

(defn- the-pull-request [eng number]
  (first (filter #(= (pr/external-id repo number) (get-in % [:data :external_id]))
                 (rows eng :pull_request))))

(defn- change-versions [eng]
  (into {} (map (juxt :id :version)) (rows eng :change)))

(defn- transitions-of [eng id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/transitions (:storage eng) tx
                                {:kind :pull_request :resource-id id} {}))))

(deftest discovery-reads-the-house-and-pull-reads-the-forge
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (boot adapter clock)
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy {:repository repo} {:principal a-person})
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        pass! #(forge/pass! {:source source :engine eng :log-fn (fn [& _] nil)})
        cursor #((:load (forge/cursor-store eng)) repo)]
    (doseq [n [31 32 33]] (a-seat-row! eng n))
    (doseq [n [31 32 33]] (gh/seed-pull! state repo (a-pull n)))
    (pass!)
    (gh/seed-pull! state repo (merge (a-pull 33)
                                     {:state "closed"
                                      :merged_at "2026-09-18T15:00:00Z"
                                      :updated_at "2026-09-18T15:00:00Z"}))
    (pass!)
    (is (= :merged (:state (the-change eng 33))) "the forge pass merged #33")
    (testing "an adapter connected to nothing discovers nothing"
      (is (zero? (mirror/discover! eng :pull_request))))
    (pr/connect! adapter eng source)
    (let [before-cursor (cursor)
          before-changes (change-versions eng)
          before-calls (forge/forge-calls source)
          minted (mirror/discover! eng :pull_request)]
      (testing "the pulls are the mirror's calls, not the forge pass's"
        (is (= before-calls (forge/forge-calls source)))
        (is (pos? (long (pr/mirror-calls adapter)))))
      (testing "two open changes mint two rows, and the merged one is not discovered"
        (is (= 2 minted))
        (is (= #{"ckopsa/waymark#31" "ckopsa/waymark#32"}
               (set (map #(get-in % [:data :external_id]) (rows eng :pull_request))))))
      (testing "pull fills them, fresh"
        (let [row (the-pull-request eng 31)]
          (is (= :fresh (:state row)))
          (is (= "Change 31" (get-in row [:data :title])))
          (is (= 31 (get-in row [:data :number])))
          (is (= "open" (get-in row [:data :gh_state])))
          (is (= "main" (get-in row [:data :base_branch])))
          (is (= "clean" (get-in row [:data :mergeable])))
          (is (= 40 (get-in row [:data :lines_added])))))
      (testing "a second pass mints nothing"
        (is (zero? (mirror/discover! eng :pull_request))))
      (testing "no change row is written, and the forge pass's cursor does not move"
        (is (= before-changes (change-versions eng)))
        (is (= before-cursor (cursor))))
      (testing "a forge that does not answer marks rows unreachable once; they keep serving"
        (reset! clock (Instant/parse "2026-09-18T18:00:00Z"))
        (gh/down! state true)
        (let [rdef (get (inv/resources eng) :pull_request)
              id (:id (the-pull-request eng 31))
              served (mirror/refresh! eng rdef (the-pull-request eng 31))
              n (count (transitions-of eng id))]
          (is (= :unreachable (:state served)))
          (is (= "Change 31" (get-in served [:data :title])))
          (mirror/refresh! eng rdef (the-pull-request eng 31))
          (is (= n (count (transitions-of eng id)))
              "the second failing read writes nothing"))))))

(deftest every-change-with-a-number-is-linked-to-its-pull-request
  ;; ticket 56c97770 (spec-change §3, R-C.4): the forge pass links each
  ;; change once its pull_request row exists, and links it once
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (boot adapter clock)
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy {:repository repo} {:principal a-person})
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        pass! #(forge/pass! {:source source :engine eng :log-fn (fn [& _] nil)})
        links-of (fn [id]
                   (store/with-tx (:storage eng)
                     (fn [tx]
                       (filterv #(= "link" (name (:action %)))
                                (store/transitions (:storage eng) tx
                                                   {:kind :change :resource-id (str id)}
                                                   {})))))]
    (doseq [n [31 32]] (a-seat-row! eng n))
    (doseq [n [31 32]] (gh/seed-pull! state repo (a-pull n)))
    (pass!)
    (testing "a change whose pull request has no row yet stays unlinked"
      (is (nil? (get-in (the-change eng 31) [:data :pull_request]))))
    (pr/connect! adapter eng source)
    (mirror/discover! eng :pull_request)
    (pass!)
    (testing "the next pass links each change to its own row, once"
      (doseq [n [31 32]]
        (let [c (the-change eng n)]
          (is (= (str (:id (the-pull-request eng n)))
                 (get-in c [:data :pull_request])))
          (is (= 1 (count (links-of (:id c))))))))
    (testing "a linked change is not linked again"
      (pass!)
      (is (= 1 (count (links-of (:id (the-change eng 31)))))))))

(deftest the-endings-ride-the-document
  (testing "pull->doc carries merged_at and closed_at as metadata, beside the change document"
    (let [doc (gh/pull->doc repo (merge (a-pull 7)
                                        {:state "closed"
                                         :merged_at "2026-09-18T15:00:00Z"
                                         :closed_at "2026-09-18T15:00:00Z"})
                            nil)]
      (is (= "merged" (:forge_state doc)))
      (is (not (contains? doc :merged_at)) "the change document is unchanged")
      (is (= "2026-09-18T15:00:00Z" (:merged_at (meta doc))))
      (is (= "2026-09-18T15:00:00Z" (:closed_at (meta doc)))))))

;; ── GitHub's webhooks (ticket 73fd079f) ─────────────────────────────

(def ^:private signing-key "github-webhook-test-key")

(def ^:private another-person (t/principal {:id "mom" :type :human}))

(defn- live-github!
  "The `github` integration, proposed by one person and approved by
  another."
  [eng]
  (let [sec (str (get-in (inv/create! eng :secret {:name "GITHUB_WEBHOOK" :value signing-key}
                                      {:principal a-person})
                         [:row :id]))
        id (str (get-in (inv/create! eng :integration (assoc pr/github-integration :secret sec)
                                     {:principal a-person})
                        [:row :id]))]
    (inv/invoke! eng :integration id :approve {} {:principal another-person})
    id))

(defn- delivered!
  "Deliver a body signed as GitHub signs it, apply its receipt, and
  answer the receipt."
  [eng delivery event body]
  (let [bs (.getBytes ^String body StandardCharsets/UTF_8)
        rid (:receipt (integrations/receive!
                       eng "github"
                       {"x-hub-signature-256" (str "sha256=" (integrations/sign signing-key bs))
                        "x-github-delivery" delivery
                        "x-github-event" event}
                       bs))]
    (integrations/apply! eng rid)
    (first (filter #(= (str rid) (str (:id %))) (rows eng :integration_event)))))

(defn- pr-event [action number]
  (str "{\"action\":\"" action "\",\"number\":" number
       ",\"pull_request\":{\"number\":" number "}"
       ",\"repository\":{\"full_name\":\"" repo "\"}}"))

(defn- train-pr-event [action number]
  (str "{\"action\":\"" action "\",\"number\":" number
       ",\"pull_request\":{\"number\":" number
       ",\"head\":{\"ref\":\"train/" repo "/" number "\"}}"
       ",\"repository\":{\"full_name\":\"" repo "\"}}"))

(defn- check-event [object sha]
  (str "{\"action\":\"completed\",\"" object "\":{\"head_sha\":\"" sha "\"}"
       ",\"repository\":{\"full_name\":\"" repo "\"}}"))

(defn- pr-check-event
  "A completed check as GitHub sends it: its head, the pull requests it
  names and the branch it ran on, where each object keeps it."
  [object sha branch numbers]
  (let [prs (apply str (interpose "," (map #(str "{\"number\":" % "}") numbers)))
        on (if (= "check_run" object)
             (str ",\"check_suite\":{\"head_branch\":\"" branch "\"}")
             (str ",\"head_branch\":\"" branch "\""))]
    (str "{\"action\":\"completed\",\"" object "\":{\"head_sha\":\"" sha "\""
         ",\"pull_requests\":[" prs "]" on "}"
         ",\"repository\":{\"full_name\":\"" repo "\"}}")))

(deftest github-events-refresh-and-mint-pull-requests
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (boot adapter clock)
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy {:repository repo} {:principal a-person})
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        pass! #(forge/pass! {:source source :engine eng :log-fn (fn [& _] nil)})
        shared (format "%040d" 99)
        sharing (fn [n] (assoc-in (a-pull n) [:head :sha] shared))
        iid (live-github! eng)]
    (doseq [n [31 32]] (a-seat-row! eng n))
    (doseq [n [31 32]] (gh/seed-pull! state repo (sharing n)))
    (pass!)
    (pr/connect! adapter eng source)
    (mirror/discover! eng :pull_request)
    (testing "a synchronize refreshes its one row inside the TTL, walked by the integration"
      (gh/seed-pull! state repo (assoc (sharing 31) :title "Change 31, renamed"))
      (let [r (delivered! eng "d-1" "pull_request" (pr-event "synchronize" 31))
            row (the-pull-request eng 31)
            h (history/row-history eng (get (inv/resources eng) :pull_request) (str (:id row)) {} nil)
            marked (first (filter #(= "mark_stale" (name (:action %))) (get-in h [:data :transitions])))]
        (is (= :applied (:state r)) (pr-str (:data r)))
        (is (= "pull_request.mark_stale" (get-in r [:data :door])))
        (is (= (str (:id row)) (str (get-in r [:data :target]))))
        (is (= :fresh (:state row)))
        (is (= "Change 31, renamed" (get-in row [:data :title])))
        (is (= (str "integration:" iid) (str (get-in marked [:actor :id]))))))
    (testing "an opened pull request with a change and no row mints it, filled"
      (a-seat-row! eng 34)
      (gh/seed-pull! state repo (a-pull 34))
      (pass!)
      (is (some? (the-change eng 34)))
      (is (nil? (the-pull-request eng 34)))
      (let [r (delivered! eng "d-2" "pull_request" (pr-event "opened" 34))
            row (the-pull-request eng 34)]
        (is (= :applied (:state r)) (pr-str (:data r)))
        (is (= :fresh (:state row)))
        (is (= "Change 34" (get-in row [:data :title])))))
    (testing "a completed check on a head two pull requests share is ambiguous, kept"
      (is (= :ambiguous (:state (delivered! eng "d-3" "check_run" (check-event "check_run" shared)))))
      (is (= :ambiguous (:state (delivered! eng "d-4" "check_suite" (check-event "check_suite" shared))))))
    (testing "a completed check on a head the row has not pulled yet reaches it by number"
      (let [moved (format "%040d" 3131)]
        (gh/seed-pull! state repo (assoc-in (a-pull 31) [:head :sha] moved))
        (is (= shared (get-in (the-pull-request eng 31) [:data :head_sha])))
        (let [r (delivered! eng "d-5" "check_run"
                            (pr-check-event "check_run" moved "engine/change-31" [31]))
              row (the-pull-request eng 31)]
          (is (= :applied (:state r)) (pr-str (:data r)))
          (is (= "pull_request.mark_stale" (get-in r [:data :door])))
          (is (= (str (:id row)) (str (get-in r [:data :target]))))
          (is (= moved (get-in row [:data :head_sha])) "the pull reads the current head"))))
    (testing "a check on main, or on a merge train's branch, is ignored"
      (doseq [[d object sha branch numbers]
              [["d-6" "check_run" (format "%040d" 7) "main" []]
               ["d-7" "check_suite" (format "%040d" 7) "main" []]
               ["d-8" "check_run" (format "%040d" 8) "train/ckopsa/waymark/2250" [2250]]
               ["d-9" "check_suite" (format "%040d" 8) "train/ckopsa/waymark/2250" [2250]]]]
        (is (= :ignored (:state (delivered! eng d object (pr-check-event object sha branch numbers))))
            (str object " on " branch))))
    (testing "a merge train's own pull request, opened or closed, is ignored"
      (doseq [[d action] [["d-10" "opened"] ["d-11" "closed"]]]
        (is (= :ignored (:state (delivered! eng d "pull_request"
                                            (train-pr-event action 2250))))
            action)))))

;; ── The change follows its head (ticket 423164bf, R-C.5) ─────────────

(deftest a-change-follows-its-pull-requests-head
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (boot adapter clock)
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy {:repository repo} {:principal a-person})
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        pass! #(forge/pass! {:source source :engine eng :log-fn (fn [& _] nil)})
        head (fn [c] (apply str (repeat 40 c)))
        at-head! (fn [c mergeable]
                   (gh/seed-pull! state repo (-> (a-pull 41)
                                                 (assoc-in [:head :sha] (head c))
                                                 (assoc :mergeable_state mergeable))))
        refreshes (fn []
                    (store/with-tx (:storage eng)
                      (fn [tx]
                        (filterv #(= "refresh_head" (name (:action %)))
                                 (store/transitions (:storage eng) tx
                                                    {:kind :change
                                                     :resource-id (str (:id (the-change eng 41)))}
                                                    {})))))
        _ (live-github! eng)]
    (a-seat-row! eng 41)
    (gh/seed-pull! state repo (a-pull 41))
    (pass!)
    (pr/connect! adapter eng source)
    (mirror/discover! eng :pull_request)
    (pass!)
    (is (some? (get-in (the-change eng 41) [:data :pull_request])))
    (testing "a webhook head move lands on the change within the event, with no forge pass"
      (at-head! "b" "clean")
      (is (= :applied (:state (delivered! eng "h-1" "pull_request" (pr-event "synchronize" 41)))))
      (doors/drain! eng)
      (let [c (the-change eng 41)]
        (is (= (head "b") (get-in c [:data :head_sha])))
        (is (= "clean" (get-in c [:data :mergeable])))
        (is (= 1 (count (refreshes))))))
    (testing "a stored conflict the forge's read of the same head does not lift stands"
      (inv/invoke! eng :change (str (:id (the-change eng 41))) :observe
                   {:mergeable "conflicted"} {:principal house-mirror/source-principal})
      (at-head! "b" "unknown")
      (delivered! eng "h-2" "pull_request" (pr-event "synchronize" 41))
      (doors/drain! eng)
      (is (= 2 (count (refreshes))))
      (is (= "conflicted" (get-in (the-change eng 41) [:data :mergeable]))))
    (testing "a new head lifts it"
      (at-head! "c" "unknown")
      (delivered! eng "h-3" "pull_request" (pr-event "synchronize" 41))
      (doors/drain! eng)
      (let [c (the-change eng 41)]
        (is (= (head "c") (get-in c [:data :head_sha])))
        (is (= "unknown" (get-in c [:data :mergeable])))))
    (testing "no other hand refreshes the head, not even the forge pass's"
      (doseq [who [house-mirror/source-principal a-person]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (inv/invoke! eng :change (str (:id (the-change eng 41))) :refresh_head
                                  {:head_sha (head "d")} {:principal who}))))
      (is (= (head "c") (get-in (the-change eng 41) [:data :head_sha]))))))

(deftest an-unknown-change-is-lifted-by-its-pull-requests-re-read
  ;; ticket 79b4b61a: GitHub computes mergeability after the push and
  ;; does not move `updated_at`; the Mirror's re-read of the pull
  ;; request carries the answer to the change, with no forge pass
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (boot adapter clock)
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy {:repository repo} {:principal a-person})
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        pass! #(forge/pass! {:source source :engine eng :log-fn (fn [& _] nil)})
        rdef (get (inv/resources eng) :pull_request)
        head (apply str (repeat 40 "a"))
        at! (fn [mergeable]
              (gh/seed-pull! state repo (-> (a-pull 41)
                                            (assoc-in [:head :sha] head)
                                            (assoc :mergeable_state mergeable))))
        _ (live-github! eng)]
    (a-seat-row! eng 41)
    (at! "unknown")
    (pass!)
    (pr/connect! adapter eng source)
    (mirror/discover! eng :pull_request)
    (pass!)
    (is (some? (get-in (the-change eng 41) [:data :pull_request])))
    (is (= "unknown" (get-in (the-change eng 41) [:data :mergeable])))
    (at! "clean")
    (mirror/refresh! eng rdef (the-pull-request eng 41) {:ignore-ttl? true})
    (doors/drain! eng)
    (let [c (the-change eng 41)]
      (is (= head (get-in c [:data :head_sha])) "the head did not move")
      (is (= "clean" (get-in c [:data :mergeable]))
          "the pull request's re-read lifts `unknown` through refresh_head"))))

;; ── The checks' verdict (ticket 04b740e6) ─────────────────────────────

(defn- a-check [id sha status conclusion]
  {:id id :name "gate" :status status :conclusion conclusion :head_sha sha
   :html_url (str "https://github.com/" repo "/runs/" id)
   :details_url (str "https://github.com/" repo "/actions/runs/900/job/" id)})

(deftest a-pull-request-carries-its-checks-verdict
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (boot adapter clock)
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy {:repository repo} {:principal a-person})
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        rdef (get (inv/resources eng) :pull_request)
        head (fn [n] (format "%040x" n))
        at-head! (fn [n & [pull]]
                   (gh/seed-pull! state repo (assoc-in (merge (a-pull 31) pull)
                                                       [:head :sha] (head n))))
        pulled! #(:data (mirror/refresh! eng rdef (the-pull-request eng 31)
                                         {:ignore-ttl? true}))
        opened (fn [door]
                 (count (filter #(= door (name (:action %)))
                                (transitions-of eng (:id (the-pull-request eng 31))))))]
    (a-seat-row! eng 31)
    (at-head! 1)
    (gh/seed-check! state repo (head 1) (a-check 1 (head 1) "in_progress" nil))
    (forge/pass! {:source source :engine eng :log-fn (fn [& _] nil)})
    (pr/connect! adapter eng source)
    (mirror/discover! eng :pull_request)
    (testing "a running check is pending"
      (is (= "pending" (get-in (the-pull-request eng 31) [:data :checks_verdict]))))
    (testing "a required check finished red writes red with its names, and opens checks_red once"
      (at-head! 2)
      (gh/seed-check! state repo (head 2) (a-check 2 (head 2) "completed" "failure"))
      (let [d (pulled!)]
        (is (= "red" (:checks_verdict d)))
        (is (= ["gate"] (:failing_checks d)))
        (is (= (head 2) (:checks_head d))))
      (is (= 1 (opened "checks_red")))
      (pulled!)
      (is (= 1 (opened "checks_red")) "a second pull of the same head opens nothing"))
    (testing "an interrupted run is pending, and opens nothing"
      (at-head! 3)
      (gh/seed-check! state repo (head 3) (a-check 3 (head 3) "completed" "cancelled"))
      (is (= "pending" (:checks_verdict (pulled!))))
      (is (= 1 (opened "checks_red")))
      (is (zero? (opened "checks_green"))))
    (testing "a green head opens checks_green"
      (at-head! 4)
      (gh/seed-check! state repo (head 4) (a-check 4 (head 4) "completed" "success"))
      (is (= "green" (:checks_verdict (pulled!))))
      (is (= 1 (opened "checks_green"))))
    (testing "a conflicted pull request is red as merge-conflict"
      (at-head! 4 {:mergeable_state "dirty"})
      (let [d (pulled!)]
        (is (= "red" (:checks_verdict d)))
        (is (= ["merge-conflict"] (:failing_checks d))))
      (is (= 2 (opened "checks_red"))))))

;; ── A run that died before its tests (ticket 0fac9efc) ───────────────────

(def ^:private died-in-set-up
  [{:name "Set up job" :conclusion "success"}
   {:name "Run actions/cache@v4" :conclusion "failure"}])

(def ^:private died-in-a-test
  [{:name "Set up job" :conclusion "success"}
   {:name "Run make test10" :conclusion "failure"}])

(deftest a-check-run-that-died-before-its-tests-is-pending
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (boot adapter clock)
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy {:repository repo} {:principal a-person})
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        rdef (get (inv/resources eng) :pull_request)
        head (fn [n] (format "%040x" n))
        at-head! (fn [n] (gh/seed-pull! state repo (assoc-in (a-pull 31) [:head :sha] (head n))))
        red-check! (fn [n id check-name]
                     (gh/seed-check! state repo (head n)
                                     (assoc (a-check id (head n) "completed" "failure")
                                            :name check-name)))
        run! (fn [n jobs]
               (gh/seed-run! state repo (head n)
                             {:id (+ 900 n) :workflow_id 11 :head_sha (head n)
                              :status "completed" :conclusion "failure"})
               (doseq [[i job] (map-indexed vector jobs)]
                 (gh/seed-job! state repo (+ 900 n)
                               (assoc job :id (+ (* 100 n) i) :run_id (+ 900 n)
                                      :status "completed"))))
        pulled! #(:data (mirror/refresh! eng rdef (the-pull-request eng 31)
                                         {:ignore-ttl? true}))
        opened (fn [door]
                 (count (filter #(= door (name (:action %)))
                                (transitions-of eng (:id (the-pull-request eng 31))))))]
    (a-seat-row! eng 31)
    (at-head! 1)
    (gh/seed-check! state repo (head 1) (a-check 1 (head 1) "in_progress" nil))
    (forge/pass! {:source source :engine eng :log-fn (fn [& _] nil)})
    (pr/connect! adapter eng source)
    (mirror/discover! eng :pull_request)
    (testing "a required check that failed in its set-up step is pending, with its run"
      (at-head! 2)
      (red-check! 2 2 "gate")
      (run! 2 [{:name "gate" :conclusion "failure" :steps died-in-set-up}])
      (let [d (pulled!)]
        (is (= "pending" (:checks_verdict d)))
        (is (nil? (:failing_checks d)))
        (is (= ["902"] (:interrupted_runs d))))
      (is (zero? (opened "checks_red")) "a runner's death wakes no seat"))
    (testing "the same check failing in a test step is red"
      (at-head! 3)
      (red-check! 3 3 "gate")
      (run! 3 [{:name "gate" :conclusion "failure" :steps died-in-a-test}])
      (let [d (pulled!)]
        (is (= "red" (:checks_verdict d)))
        (is (= ["gate"] (:failing_checks d)))
        (is (nil? (:interrupted_runs d))))
      (is (= 1 (opened "checks_red"))))
    (testing "a fail-fast matrix whose sibling a red job cancelled is red"
      (at-head! 4)
      (red-check! 4 4 "test10 (shard 1)")
      (gh/seed-check! state repo (head 4)
                      (assoc (a-check 5 (head 4) "completed" "cancelled")
                             :name "test10 (shard 2)"))
      (run! 4 [{:name "test10 (shard 1)" :conclusion "failure" :steps died-in-a-test}
               {:name "test10 (shard 2)" :conclusion "cancelled" :steps []}])
      (let [d (pulled!)]
        (is (= "red" (:checks_verdict d)))
        (is (= ["test10 (shard 1)"] (:failing_checks d)))
        (is (nil? (:interrupted_runs d)))))))
