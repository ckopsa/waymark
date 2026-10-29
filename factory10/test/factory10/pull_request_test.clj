(ns factory10.pull-request-test
  "The pull_request Mirror (ticket d2ceedc9), over REAL rows and the
  in-memory GitHub: discovery reads the house's own change rows, pull
  spends the forge's `forge-pull`, and neither writes a change nor moves
  the forge pass's cursor.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.bench :as bench]
            [factory10.main :as main]
            [factory10.mirror :as house-mirror]
            [factory10.resources.change :as change]
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
            [waymark10.server.waiting-on :as waiting-on]
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
  "The row a seat's sit minted for pull request `number`'s branch, as
  the mirror's link leaves it once GitHub's `opened` event names the
  pull request (plan 85b4462e): the forge pass adopts and observes no
  change. A repository with no cursor yet is given
  one from before the pull requests opened, as a house whose seats
  submit has read its repositories before: a cursor the store seeded
  from the row's own stamp would pass the pull requests by."
  ([eng number] (a-seat-row! eng number (format "%040d" number)))
  ([eng number head-sha]
  (let [store (forge/cursor-store eng)]
    (when-not ((:load store) repo)
      ((:save! store) repo "2026-09-18T11:00:00Z"))
    (inv/create! eng :change {:change_id (str "github:" repo "#" number)
                              :repository repo
                              :number number
                              :url (str "https://github.com/ckopsa/waymark/pull/" number)
                              :title (str "Change " number)
                              :head_branch (str "engine/change-" number)
                              :head_sha head-sha
                              :base_branch "main"
                              :born_from (str "ticket:t" number)}
                 {:principal house-mirror/source-principal}))))

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
    ;; GitHub's `closed` event walks the merge (plan 85b4462e), not the pass
    (let [st (:storage eng)
          id (str (:id (the-change eng 33)))]
      (store/with-tx st
        (fn [tx]
          (let [row (store/load-row st tx :change id {})]
            (store/save-row! st tx :change
                             (assoc row :state :merged
                                        :version (inc (long (:version row))))
                             (:version row))))))
    (is (= :merged (:state (the-change eng 33))) "#33 is merged")
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
      (inv/invoke! eng :change (str (:id (the-change eng 41))) :refresh_head
                   {:mergeable "conflicted"}
                   {:principal (assoc house-mirror/source-principal
                                      :subscription "pull_request")})
      (at-head! "b" "unknown")
      (delivered! eng "h-2" "pull_request" (pr-event "synchronize" 41))
      (doors/drain! eng)
      ;; the seed above is a refresh_head of its own
      (is (= 3 (count (refreshes))))
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

;; ── The change learns its number from the opened event (ticket 20a962be) ──

(defn- opened-event
  "A pull_request event as GitHub sends it, with its head and url."
  [action number branch]
  (str "{\"action\":\"" action "\",\"number\":" number
       ",\"pull_request\":{\"number\":" number
       ",\"html_url\":\"https://github.com/" repo "/pull/" number "\""
       ",\"head\":{\"ref\":\"" branch "\",\"sha\":\"" (format "%040d" number) "\"}}"
       ",\"repository\":{\"full_name\":\"" repo "\"}}"))

(deftest an-opened-event-gives-a-seats-change-its-number
  ;; no forge pass runs in this test at all
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (boot adapter clock)
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy {:repository repo} {:principal a-person})
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        branch "bench/t51"
        cid (str (get-in (inv/create! eng :change {:change_id "ticket:t51"
                                                   :repository repo
                                                   :title "Change 51"
                                                   :head_branch branch
                                                   :base_branch "main"}
                                      {:principal house-mirror/source-principal})
                         [:row :id]))
        the-row #(first (filter (fn [c] (= cid (str (:id c)))) (rows eng :change)))
        openings #(store/with-tx (:storage eng)
                    (fn [tx]
                      (filterv (fn [t] (= "opened" (name (:action t))))
                               (store/transitions (:storage eng) tx
                                                  {:kind :change :resource-id cid} {}))))
        _ (live-github! eng)]
    (gh/seed-pull! state repo (assoc-in (a-pull 51) [:head :ref] branch))
    (pr/connect! adapter eng source)
    (testing "the opened event writes the number, url and change_id through `opened`"
      (let [r (delivered! eng "o-1" "pull_request" (opened-event "opened" 51 branch))
            c (the-row)]
        (is (= :applied (:state r)) (pr-str (:data r)))
        (is (= "change.opened" (get-in r [:data :door])))
        (is (= cid (str (get-in r [:data :target]))))
        (is (= 51 (get-in c [:data :number])))
        (is (= (str "github:" repo "#51") (get-in c [:data :change_id])))
        (is (= (str "https://github.com/" repo "/pull/51") (get-in c [:data :url])))
        (is (= 1 (count (openings))))))
    (testing "the same receipt mints the pull_request row, and its birth links the change"
      (let [row (the-pull-request eng 51)]
        (is (some? row))
        (doors/drain! eng)
        (is (= (str (:id row)) (get-in (the-row) [:data :pull_request])))))
    (testing "a second opened, or a reopened, event does not rewrite the number"
      (doseq [[d action] [["o-2" "opened"] ["o-3" "reopened"]]]
        (let [r (delivered! eng d "pull_request" (opened-event action 51 branch))]
          (is (= "pull_request.mark_stale" (get-in r [:data :door])) action)))
      (is (= 1 (count (openings))))
      (is (= 51 (get-in (the-row) [:data :number]))))
    (testing "an opened event on a branch no change owns moves no change"
      (let [before (change-versions eng)
            r (delivered! eng "o-4" "pull_request" (opened-event "opened" 77 "bench/nobody"))]
        (is (not= :applied (:state r)) (pr-str (:data r)))
        (is (= before (change-versions eng)))))
    (testing "GitHub's merge then moves the change to merged"
      (gh/seed-pull! state repo (merge (assoc-in (a-pull 51) [:head :ref] branch)
                                       {:state "closed"
                                        :merged_at "2026-09-18T16:30:00Z"
                                        :closed_at "2026-09-18T16:30:00Z"}))
      (is (= :applied (:state (delivered! eng "o-5" "pull_request"
                                          (opened-event "closed" 51 branch)))))
      (doors/drain! eng)
      (is (= :merged (:state (the-row)))))))

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
    (a-seat-row! eng 41 head)
    (at! "unknown")
    (pass!)
    (pr/connect! adapter eng source)
    (mirror/discover! eng :pull_request)
    (pass!)
    (is (some? (get-in (the-change eng 41) [:data :pull_request])))
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

(deftest a-policy-with-no-required-check-requires-every-finished-check
  (let [done (fn [n c] {:check_name n :status "completed" :conclusion c})
        d (pr/checks-verdict [] [(done "test10 (shard 3)" "failure")
                                 (done "check-queue" "success")]
                             "clean")]
    (is (= "red" (:checks_verdict d)))
    (is (= ["test10 (shard 3)"] (:failing_checks d)))))

;; ── The change follows its facts (ticket 6a530e5d) ──────────────────────

(deftest a-change-follows-its-pull-requests-facts
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (boot adapter clock)
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy {:repository repo :required_checks ["gate"]}
                       {:principal a-person})
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        rdef (get (inv/resources eng) :pull_request)
        head (fn [n] (format "%040x" n))
        at-head! (fn [n & [pull opts]]
                   (gh/seed-pull! state repo (assoc-in (merge (a-pull 51) pull)
                                                       [:head :sha] (head n))
                                  opts))
        pulled! #(do (mirror/refresh! eng rdef (the-pull-request eng 51)
                                      {:ignore-ttl? true})
                     (doors/drain! eng)
                     (:data (the-pull-request eng 51)))
        follows (fn []
                  (store/with-tx (:storage eng)
                    (fn [tx]
                      (filterv #(= "follow_facts" (name (:action %)))
                               (store/transitions (:storage eng) tx
                                                  {:kind :change
                                                   :resource-id (str (:id (the-change eng 51)))}
                                                  {})))))]
    (a-seat-row! eng 51)
    (at-head! 1)
    (gh/seed-check! state repo (head 1) (a-check 1 (head 1) "completed" "success"))
    ;; the forge pass mints the number and links the row; the change
    ;; stays open, which J7 does not read, so no forge pass writes a fact
    (forge/pass! {:source source :engine eng :log-fn (fn [& _] nil)})
    (pr/connect! adapter eng source)
    (mirror/discover! eng :pull_request)
    (forge/pass! {:source source :engine eng :log-fn (fn [& _] nil)})
    (is (= :open (:state (the-change eng 51))))
    (is (some? (get-in (the-change eng 51) [:data :pull_request])))
    (testing "a head that ran its required check misses none"
      (is (= [] (get-in (the-pull-request eng 51) [:data :missing_checks]))))
    (testing "a head with no run of a required check carries it, and behind_base with a compare-capable forge"
      (at-head! 2)
      (let [d (pulled!)]
        (is (= ["gate"] (:missing_checks d)))
        (is (= (head 2) (:missing_checks_head d)))
        (is (true? (:behind_base d)))
        (is (some? (:base_compared_at d))))
      (testing "and the change follows, with no forge pass"
        (let [c (the-change eng 51)]
          (is (= ["gate"] (get-in c [:data :missing_checks])))
          (is (= (head 2) (get-in c [:data :missing_checks_head])))
          (is (true? (get-in c [:data :behind_base])))
          (is (some? (get-in c [:data :base_compared_at])))
          (is (= 1 (count (follows))))))
      (testing "a second pull of the same facts walks nothing"
        (pulled!)
        (is (= 1 (count (follows))))))
    (testing "a head that holds its base is not behind"
      (gh/seed-ancestor! state repo (head 3) "main")
      (at-head! 3)
      (pulled!)
      (let [c (the-change eng 51)]
        (is (= (head 3) (get-in c [:data :missing_checks_head])))
        (is (false? (get-in c [:data :behind_base])))))
    (testing "a pull request turned draft, with new touched paths, moves the change's"
      (at-head! 3 {:draft true} {:files [{:filename "docs/a.md"}]})
      (pulled!)
      (let [c (the-change eng 51)]
        (is (true? (get-in c [:data :draft])))
        (is (= ["docs/a.md"] (get-in c [:data :touched_paths])))))
    (testing "a pull request with a new title, counts, labels and reviews moves the change's (ticket a9d8aed2)"
      (let [moved {:draft true :title "A retitled pull request" :changed_files 5
                   :additions 90 :deletions 7
                   :labels [{:name "agent"} {:name "docs"}]}
            opts {:files [{:filename "docs/a.md"}] :reviews [{:state "APPROVED"}]}
            before (count (follows))]
        (at-head! 3 moved opts)
        (pulled!)
        (let [c (the-change eng 51)]
          (is (= "A retitled pull request" (get-in c [:data :title])))
          (is (= 5 (get-in c [:data :files_changed])))
          (is (= 90 (get-in c [:data :lines_added])))
          (is (= 7 (get-in c [:data :lines_removed])))
          (is (= ["agent" "docs"] (get-in c [:data :labels])))
          (is (= "approved" (get-in c [:data :review_state])))
          (is (= (inc before) (count (follows)))))
        (testing "and a second pull of the same facts walks nothing"
          (pulled!)
          (is (= (inc before) (count (follows)))))))
    (testing "no other hand follows the facts, not even the forge pass's"
      (doseq [who [house-mirror/source-principal a-person]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (inv/invoke! eng :change (str (:id (the-change eng 51))) :follow_facts
                                  {:draft false} {:principal who}))))
      (is (true? (get-in (the-change eng 51) [:data :draft]))))))

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

;; ── The pull re-runs a run that died before its tests (ticket 04e40648) ───

(defn- submit-the-change!
  "Put the change at `submitted`, as a seat's submit would."
  [eng number]
  (let [id (str (:id (the-change eng number)))
        st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (let [row (store/load-row st tx :change id {})]
          (store/save-row! st tx :change
                           (-> row
                               (assoc :state :submitted
                                      :version (inc (long (:version row))))
                               (assoc-in [:data :rounds] 1))
                           (:version row)))))))

(deftest a-run-that-died-before-its-tests-is-re-run-once-per-head-by-the-pull
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (boot adapter clock)
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy {:repository repo} {:principal a-person})
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        rdef (get (inv/resources eng) :pull_request)
        head (fn [n] (format "%040x" n))
        at-head! (fn [n] (gh/seed-pull! state repo (assoc-in (a-pull 61) [:head :sha] (head n))))
        died! (fn [n]
                (swap! state
                       (fn [s]
                         (-> s
                             (assoc-in [:repos repo :checks (head n)]
                                       [(a-check n (head n) "completed" "failure")])
                             (assoc-in [:repos repo :runs (head n)]
                                       [{:id (+ 900 n) :workflow_id 11 :head_sha (head n)
                                         :status "completed" :conclusion "failure"}])
                             (assoc-in [:repos repo :jobs (str (+ 900 n))]
                                       [{:id (* 100 n) :run_id (+ 900 n) :name "gate"
                                         :status "completed" :conclusion "failure"
                                         :steps died-in-set-up}])))))
        running! (fn [n]
                   (swap! state assoc-in [:repos repo :checks (head n)]
                          [(a-check n (head n) "in_progress" nil)]))
        pulled! #(do (mirror/refresh! eng rdef (the-pull-request eng 61)
                                      {:ignore-ttl? true})
                     (doors/drain! eng)
                     (:data (the-pull-request eng 61)))
        reran #(mapv :run (gh/reruns state))
        asked (fn [run]
                (count (filter #(re-find (re-pattern (str "/actions/runs/" run "/rerun"))
                                         (str (:path %)))
                               (:requests @state))))
        walks (fn []
                (store/with-tx (:storage eng)
                  (fn [tx]
                    (filterv #(= "rerun_interrupted" (name (:action %)))
                             (store/transitions (:storage eng) tx
                                                {:kind :change
                                                 :resource-id (str (:id (the-change eng 61)))}
                                                {})))))
        change-data #(:data (the-change eng 61))
        pass! #(forge/pass! {:source source :engine eng :log-fn (fn [& _] nil)})]
    (a-seat-row! eng 61)
    (at-head! 1)
    (running! 1)
    (pass!)
    (pr/connect! adapter eng source)
    (mirror/discover! eng :pull_request)
    (pass!)
    (submit-the-change! eng 61)
    (testing "with no forge pass, a head whose only red check died in set-up is re-run once across three pulls"
      (at-head! 2)
      (died! 2)
      (dotimes [_ 3] (pulled!))
      (is (= [902] (reran)))
      (is (= (head 2) (get-in (the-pull-request eng 61) [:data :reran_head])))
      (is (= 1 (count (walks))) "the change's history shows rerun_interrupted")
      (is (= (head 2) (:rerun_head (change-data))))
      (is (nil? (:rerun_note (change-data)))))
    (testing "J6 does not re-run, nor note, a head the pull already re-ran"
      (pass!)
      (is (= [902] (reran)))
      (is (nil? (:rerun_note (change-data)))))
    (testing "the same head interrupted again after its re-run writes rerun_note and re-runs nothing"
      (running! 2)
      (pulled!)
      (died! 2)
      (pulled!)
      (pulled!)
      (is (= [902] (reran)))
      (is (= 2 (count (walks))))
      (is (.contains (str (:rerun_note (change-data))) (subs (head 2) 0 12))))
    (testing "a new head re-runs once"
      (at-head! 3)
      (died! 3)
      (pulled!)
      (pulled!)
      (is (= [902 903] (reran)))
      (is (= 3 (count (walks))))
      (is (= (head 3) (:rerun_head (change-data)))))
    (testing "a source that refuses the re-run is tried on the next pull, then let go"
      (gh/fail-path! state #"rerun-failed-jobs")
      (at-head! 4)
      (died! 4)
      (pulled!)
      (is (= 1 (asked 904)))
      (is (not= (head 4) (get-in (the-pull-request eng 61) [:data :reran_head])))
      (pulled!)
      (is (= pr/rerun-tries (asked 904)))
      (pulled!)
      (pulled!)
      (is (= pr/rerun-tries (asked 904)) "no pull tries past the PR's two")
      (is (= [902 903] (reran)))
      (is (= 3 (count (walks)))))))

;; ── A required check that waits past the limit (ticket a18c3a43) ──────

(defn- edit-row!
  "Write `f` of a row's data straight to the store, as a pass this test
  does not run would have written it."
  [eng kind id f]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (let [row (store/load-row st tx kind (str id) {})]
          (store/save-row! st tx kind
                           (-> row
                               (update :data f)
                               (assoc :version (inc (long (:version row)))))
                           (:version row)))))))

(defn- waits-rig
  "Pull request `number` at 16:00, its policy requiring `required`, its
  head n carrying `(checks-at n sha)`, and its change submitted. The
  forge pass runs only to adopt the change: the waits are the pull's."
  [number required checks-at]
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (boot adapter clock)
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy {:repository repo} {:principal a-person})
        _ (edit-row! eng :repo_policy (:id (first (rows eng :repo_policy)))
                     #(assoc % :required_checks required))
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        rdef (get (inv/resources eng) :pull_request)
        head (fn [n] (format "%040x" n))
        at-head! (fn [n]
                   (gh/seed-pull! state repo (assoc-in (a-pull number) [:head :sha] (head n)))
                   (swap! state assoc-in [:repos repo :checks (head n)]
                          (checks-at n (head n))))
        pass! #(forge/pass! {:source source :engine eng :log-fn (fn [& _] nil)})]
    (a-seat-row! eng number (head 1))
    (at-head! 1)
    (pass!)
    (pr/connect! adapter eng source)
    (mirror/discover! eng :pull_request)
    (pass!)
    (submit-the-change! eng number)
    {:eng eng :clock clock :state state :adapter adapter :head head
     :at-head! at-head!
     :pass! #(do (pass!) (doors/drain! eng))
     :pulled! #(do (mirror/refresh! eng rdef (the-pull-request eng number)
                                    {:ignore-ttl? true})
                   (doors/drain! eng)
                   (:data (the-pull-request eng number)))
     :beat! #(let [b (mirror/advance-beat! eng :pull_request)]
               (doors/drain! eng)
               b)
     :opened (fn [door]
               (count (filter #(= door (name (:action %)))
                              (transitions-of eng (:id (the-pull-request eng number))))))
     :change-state #(name (:state (the-change eng number)))}))

(deftest a-conflicted-head-carries-its-paths-to-the-change
  ;; ticket 3e8a33f2: the rig is asked once per head, and the change's
  ;; `checks_red` writes the paths as `conflicts`
  (let [asked (atom 0)
        paths ["factory10/src/factory10/bench.clj"]]
    (with-redefs [bench/conflict-paths (fn [_ _] (swap! asked inc) paths)]
      (let [{:keys [eng state head pulled! opened change-state]}
            (waits-rig 73 ["gate"] (fn [n sha] [(a-check n sha "in_progress" nil)]))]
        (gh/seed-pull! state repo (assoc-in (merge (a-pull 73) {:mergeable_state "dirty"})
                                            [:head :sha] (head 1)))
        (let [d (pulled!)]
          (is (= "red" (:checks_verdict d)))
          (is (= paths (:conflict_paths d)))
          (is (= (head 1) (:conflict_paths_head d))))
        (pulled!)
        (is (= 1 @asked) "a head is asked once")
        (is (= 1 (opened "checks_red")))
        (is (= "failing" (change-state)))
        (is (= paths (get-in (the-change eng 73) [:data :conflicts])))))))

(deftest a-queued-check-past-the-limit-turns-the-pull-request-red-on-the-beat
  (let [{:keys [eng clock state adapter head pulled! beat! opened change-state]}
        (waits-rig 71 ["gate"]
                   (fn [n sha] [(assoc (a-check n sha "queued" nil)
                                       :started_at "2026-09-18T15:50:00Z")]))
        xid (pr/external-id repo 71)
        requests #(count (:requests @state))]
    (testing "a queued check inside the limit is pending, and says when its wait falls due"
      (let [d (pulled!)]
        (is (= "pending" (:checks_verdict d)))
        (is (= "2026-09-18T16:21:00Z" (:wait_due_at d)))
        (is (nil? (:waited_at d))))
      (is (= {} (mirror/advance-listing adapter)) "nothing is due yet"))
    (testing "the listing answers the open, due row from storage alone"
      (reset! clock (Instant/parse "2026-09-18T16:25:00Z"))
      (let [before (requests)]
        (is (= {xid {:waited_at "2026-09-18T16:21:00Z"}} (mirror/advance-listing adapter)))
        (is (= before (requests)) "the listing makes no forge call")))
    (testing "the beat re-pulls the due row: it reads red with J6's finding, and the change walks checks_red"
      (is (= {:listed 1 :moved 1} (beat!)))
      (let [d (:data (the-pull-request eng 71))]
        (is (= "red" (:checks_verdict d)))
        (is (= ["check gate has been queued 35 minutes; no runner took it (check its runs-on)"]
               (:failing_checks d)))
        (is (= "2026-09-18T16:21:00Z" (:waited_at d))))
      (is (= 1 (opened "checks_red")))
      (is (= "failing" (change-state))))
    (testing "a second beat pulls nothing"
      (let [before (requests)]
        (is (= {:listed 1 :moved 0} (beat!)))
        (is (= before (requests)))))
    (testing "a runner takes the check: checks_started recovers the change"
      (swap! state assoc-in [:repos repo :checks (head 1)]
             [(a-check 1 (head 1) "in_progress" nil)])
      (is (= "pending" (:checks_verdict (pulled!))))
      (is (= 1 (opened "checks_started")))
      (is (= "submitted" (change-state)))
      (is (= {} (mirror/advance-listing adapter)) "a running check waits on nothing"))))

(deftest a-missing-check-past-the-limit-turns-the-pull-request-red-from-the-head-first-seen
  (let [{:keys [eng clock head at-head! pulled! opened change-state]}
        (waits-rig 72 ["gate" "lint"]
                   (fn [n sha] [(a-check n sha "in_progress" nil)]))
        policy-id (:id (first (rows eng :repo_policy)))
        change-id (:id (the-change eng 72))]
    (testing "the first pull of a head stamps head_seen_at, and the missing check falls due from it"
      (let [d (pulled!)]
        (is (= "pending" (:checks_verdict d)))
        (is (= "2026-09-18T16:00:00Z" (:head_seen_at d)))
        (is (= "2026-09-18T16:31:00Z" (:wait_due_at d)))))
    (testing "a new head resets head_seen_at, and the same head keeps it"
      (reset! clock (Instant/parse "2026-09-18T16:20:00Z"))
      (at-head! 2)
      (let [d (pulled!)]
        (is (= "2026-09-18T16:20:00Z" (:head_seen_at d)))
        (is (= "2026-09-18T16:51:00Z" (:wait_due_at d))))
      (reset! clock (Instant/parse "2026-09-18T16:30:00Z"))
      (is (= "2026-09-18T16:20:00Z" (:head_seen_at (pulled!)))))
    (testing "a submitted change the house brings forward does not wait on its missing check"
      (reset! clock (Instant/parse "2026-09-18T16:55:00Z"))
      (submit-the-change! eng 72)
      (edit-row! eng :repo_policy policy-id #(assoc % :merge_by "house"))
      (edit-row! eng :change change-id
                 #(assoc % :head_sha (head 2) :missing_checks ["lint"]
                         :missing_checks_head (head 2) :behind_base true))
      (let [d (pulled!)]
        (is (= "pending" (:checks_verdict d)))
        (is (nil? (:wait_due_at d))))
      (is (zero? (opened "checks_red"))))
    (testing "past the limit it reads red, naming the check, and the change walks checks_red"
      (edit-row! eng :change change-id #(assoc % :behind_base false))
      (let [d (pulled!)]
        (is (= "red" (:checks_verdict d)))
        (is (= ["check lint has no run after 35 minutes; no workflow started it (check its triggers)"]
               (:failing_checks d))))
      (is (= 1 (opened "checks_red")))
      (is (= "failing" (change-state))))))

;; ── behind_base is compared when the head or the base moves (ticket
;; 2edb2167) ─────────────────────────────────────────────────────────────

(deftest the-pull-compares-its-base-only-when-its-head-or-its-base-moved
  (let [{:keys [eng state head at-head! pulled!]}
        (waits-rig 73 ["gate"]
                   (fn [n sha] (if (= 3 n) [] [(a-check n sha "completed" "success")])))
        policy-id (:id (first (rows eng :repo_policy)))
        base! (fn [sha] (edit-row! eng :repo_policy policy-id #(assoc % :base_head sha)))
        compares #(count (filter (fn [r] (re-find #"/compare/" (str (:path r))))
                                 (gh/requests state)))
        compared! (fn [] (let [before (compares) d (pulled!)]
                           [(- (compares) before) d]))]
    (testing "a green open head is compared, and the compare records its head and base"
      (gh/seed-ancestor! state repo (head 2) "main")
      (base! "b1")
      (at-head! 2)
      (let [[n d] (compared!)]
        (is (= "green" (:checks_verdict d)))
        (is (= 1 n))
        (is (false? (:behind_base d)))
        (is (= (head 2) (:base_compared_head d)))
        (is (= "b1" (:base_compared_base d)))
        (is (some? (:base_compared_at d)))))
    (testing "a second pull at the same head and base makes no compare call"
      (let [[n d] (compared!)]
        (is (= 0 n))
        (is (false? (:behind_base d)))))
    (testing "a moved policy base_head makes one compare"
      (base! "b2")
      (let [[n d] (compared!)]
        (is (= 1 n))
        (is (= "b2" (:base_compared_base d)))))
    (testing "a missing check on a head behind its base writes behind_base and the compared head and base"
      (at-head! 3)
      (let [[n d] (compared!)]
        (is (= 1 n))
        (is (= ["gate"] (:missing_checks d)))
        (is (true? (:behind_base d)))
        (is (= (head 3) (:base_compared_head d)))
        (is (= "b2" (:base_compared_base d))))
      (is (true? (get-in (the-change eng 73) [:data :behind_base]))))
    (testing "a true answer is not compared again at the same head"
      (base! "b3")
      (let [[n d] (compared!)]
        (is (= 0 n))
        (is (true? (:behind_base d)))
        (is (= "b2" (:base_compared_base d)))))))

(deftest a-moved-base-refreshes-the-open-pull-requests-not-known-to-be-behind
  (let [{:keys [eng state head at-head! pulled!]}
        (waits-rig 74 ["gate"]
                   (fn [n sha] [(a-check n sha "completed" "success")]))
        policy-id (:id (first (rows eng :repo_policy)))
        pr-id (:id (the-pull-request eng 74))
        compares #(count (filter (fn [r] (re-find #"/compare/" (str (:path r))))
                                 (gh/requests state)))
        moved! (fn [] (let [before (compares)
                            n (pr/base-moved! eng repo)]
                        (doors/drain! eng)
                        [n (- (compares) before)]))]
    (gh/seed-ancestor! state repo (head 2) "main")
    (edit-row! eng :repo_policy policy-id #(assoc % :base_head "b1"))
    (at-head! 2)
    (is (false? (:behind_base (pulled!))))
    (is (false? (get-in (the-change eng 74) [:data :behind_base])))
    (testing "another repository's base refreshes nothing"
      (is (= 0 (pr/base-moved! eng "ckopsa/elsewhere"))))
    (testing "a pending head with no missing check is not refreshed"
      (edit-row! eng :pull_request pr-id #(assoc % :checks_verdict "pending"))
      (is (= [0 0] (moved!))))
    (testing "a head known to be behind is not refreshed"
      (edit-row! eng :pull_request pr-id #(assoc % :checks_verdict "green" :behind_base true))
      (is (= [0 0] (moved!))))
    (testing "an open green head not behind is pulled now, and the change's behind_base follows"
      (edit-row! eng :pull_request pr-id #(assoc % :behind_base false))
      (swap! state update-in [:repos repo :ancestors (head 2)] disj "main")
      (edit-row! eng :repo_policy policy-id #(assoc % :base_head "b2"))
      (is (= [1 1] (moved!)))
      (let [d (:data (the-pull-request eng 74))]
        (is (true? (:behind_base d)))
        (is (= "b2" (:base_compared_base d))))
      (is (true? (get-in (the-change eng 74) [:data :behind_base]))))))

;; ---- A head moved with no event (ticket e5952682) ----

(deftest a-head-moved-with-no-event-reaches-the-change-within-one-forge-pass
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (boot adapter clock)
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy {:repository repo} {:principal a-person})
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        head (fn [n] (format "%040x" n))
        at-head! (fn [n] (gh/seed-pull! state repo (assoc-in (a-pull 71) [:head :sha] (head n))))
        checks! (fn [n status conclusion]
                  (swap! state assoc-in [:repos repo :checks (head n)]
                         [(a-check n (head n) status conclusion)]))
        walked (fn [action]
                 (store/with-tx (:storage eng)
                   (fn [tx]
                     (filterv #(= action (name (:action %)))
                              (store/transitions (:storage eng) tx
                                                 {:kind :change
                                                  :resource-id (str (:id (the-change eng 71)))}
                                                 {})))))
        pass! #(forge/pass! {:source source :engine eng :log-fn (fn [& _] nil)})]
    (a-seat-row! eng 71 (head 1))
    (at-head! 1)
    (checks! 1 "in_progress" nil)
    (pass!)
    (pr/connect! adapter eng source)
    (mirror/discover! eng :pull_request)
    (pass!)
    (submit-the-change! eng 71)
    (is (some? (get-in (the-change eng 71) [:data :pull_request])))
    (is (= (head 1) (get-in (the-change eng 71) [:data :head_sha])))
    (testing "GitHub moves the head and sends no event, and the old head goes red"
      (at-head! 2)
      (checks! 1 "completed" "failure")
      (checks! 2 "in_progress" nil)
      (let [before (count (walked "refresh_head"))]
        (pass!)
        (doors/drain! eng)
        (let [c (the-change eng 71)]
          (is (= (head 2) (get-in c [:data :head_sha])) "one pass carries the new head")
          (is (< before (count (walked "refresh_head"))) "by refresh_head")
          (is (= :submitted (:state c)) "the stale head's red never lands")
          (is (empty? (walked "checks_red"))))))))

;; ---- GitHub's ending drops the held head (ticket 0f0a17c1) ----

(def ^:private held-fields
  [:held_head :held_since])

(defn- ended-while-held
  "A submitted change linked to pull request 81, held as the forge pass
  holds a head, then ended by GitHub as `ending` (merged_at and
  closed_at on the pull): answers the change row after the closed
  event's receipt is applied and drained. No forge pass runs."
  [ending]
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (boot adapter clock)
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy {:repository repo} {:principal a-person})
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        branch "bench/t81"
        _ (inv/create! eng :change {:change_id "ticket:t81"
                                    :repository repo
                                    :title "Change 81"
                                    :head_branch branch
                                    :base_branch "main"}
                       {:principal house-mirror/source-principal})
        _ (live-github! eng)
        pull (assoc-in (a-pull 81) [:head :ref] branch)]
    (gh/seed-pull! state repo pull)
    (pr/connect! adapter eng source)
    (is (= :applied (:state (delivered! eng "h-1" "pull_request"
                                        (opened-event "opened" 81 branch)))))
    (doors/drain! eng)
    (is (some? (get-in (the-change eng 81) [:data :pull_request])))
    (submit-the-change! eng 81)
    (edit-row! eng :change (:id (the-change eng 81))
               #(assoc % :held_head (get-in pull [:head :sha])
                       :held_since "2026-09-18T15:00:00Z"))
    (is (= :submitted (:state (the-change eng 81))))
    (is (every? #(some? (get-in (the-change eng 81) [:data %])) held-fields))
    (gh/seed-pull! state repo (merge pull {:state "closed"} ending))
    (is (= :applied (:state (delivered! eng "h-2" "pull_request"
                                        (opened-event "closed" 81 branch)))))
    (doors/drain! eng)
    (the-change eng 81)))

(deftest githubs-merge-ends-a-held-change-and-drops-the-wait
  (let [c (ended-while-held {:merged_at "2026-09-18T16:30:00Z"
                             :closed_at "2026-09-18T16:30:00Z"})]
    (is (= :merged (:state c)))
    (doseq [k held-fields]
      (is (nil? (get-in c [:data k])) (name k)))))

(deftest githubs-close-ends-a-held-change-and-drops-the-wait
  (let [c (ended-while-held {:merged_at nil
                             :closed_at "2026-09-18T16:30:00Z"})]
    (is (= :closed (:state c)))
    (doseq [k held-fields]
      (is (nil? (get-in c [:data k])) (name k)))))

;; ---- The change's green head and wait follow its pull request (ticket ef7d1a4e) ----

(deftest a-change-takes-its-green-head-and-wait-from-its-pull-request
  ;; no forge pass runs in this test at all
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (boot adapter clock)
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy {:repository repo} {:principal a-person})
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        rdef (get (inv/resources eng) :pull_request)
        branch "bench/t82"
        _ (inv/create! eng :change {:change_id "ticket:t82"
                                    :repository repo
                                    :title "Change 82"
                                    :head_branch branch
                                    :base_branch "main"}
                       {:principal house-mirror/source-principal})
        _ (live-github! eng)
        pull (assoc-in (a-pull 82) [:head :ref] branch)
        head (get-in pull [:head :sha])]
    (gh/seed-pull! state repo pull)
    (pr/connect! adapter eng source)
    (is (= :applied (:state (delivered! eng "h-1" "pull_request"
                                        (opened-event "opened" 82 branch)))))
    (doors/drain! eng)
    (is (some? (get-in (the-change eng 82) [:data :pull_request])))
    (is (nil? (get-in (the-change eng 82) [:data :green_head])))
    (gh/seed-check! state repo head (a-check 1 head "completed" "success"))
    (let [d (:data (mirror/refresh! eng rdef (the-pull-request eng 82)
                                    {:ignore-ttl? true}))]
      (doors/drain! eng)
      (is (= "green" (:checks_verdict d)))
      (let [c (:data (the-change eng 82))]
        (is (= head (:green_head c)) "the green head arrives by refresh_head")
        (is (some? (:head_seen_at c)))
        (is (= (:head_seen_at d) (:head_seen_at c))
            "the wait counts from the pull request's first pull of the head")))))

;; ---- A head with no verdict waits on its checks (ticket 6a5c9f8a) ----

(deftest a-change-waits-on-its-checks-from-its-pull-request
  ;; no forge pass runs in this test at all
  (let [clock (atom (Instant/parse "2026-09-18T16:00:00Z"))
        adapter (pr/forge-mirror)
        eng (boot adapter clock)
        state (gh/fake-state)
        _ (inv/create! eng :repo_policy {:repository repo} {:principal a-person})
        source (gh/fake-source state {:store (forge/cursor-store eng)})
        rdef (get (inv/resources eng) :pull_request)
        branch "bench/t83"
        _ (inv/create! eng :change {:change_id "ticket:t83"
                                    :repository repo
                                    :title "Change 83"
                                    :head_branch branch
                                    :base_branch "main"}
                       {:principal house-mirror/source-principal})
        _ (live-github! eng)
        pull (assoc-in (a-pull 83) [:head :ref] branch)
        head (get-in pull [:head :sha])
        pulled! #(:data (mirror/refresh! eng rdef (the-pull-request eng 83)
                                         {:ignore-ttl? true}))]
    (gh/seed-pull! state repo pull)
    (pr/connect! adapter eng source)
    (is (= :applied (:state (delivered! eng "h-1" "pull_request"
                                        (opened-event "opened" 83 branch)))))
    (doors/drain! eng)
    (testing "a queued required check is pending, and the change waits on it"
      (gh/seed-check! state repo head (a-check 1 head "queued" nil))
      (let [d (pulled!)]
        (doors/drain! eng)
        (is (= "pending" (:checks_verdict d)))
        (is (= ["gate"] (:pending_checks d)))
        (let [c (:data (the-change eng 83))]
          (is (= ["gate"] (:pending_checks c)) "through refresh_head")
          (is (= head (:held_head c)))
          (is (some? (:held_since c)))
          (is (= (:head_seen_at d) (:held_since c))
              "the wait counts from the pull request's first pull of the head")
          (is (nil? (:green_head c))))))
    (testing "a green verdict at that head stamps green_head and ends the wait"
      (swap! state assoc-in [:repos repo :checks head]
             [(a-check 2 head "completed" "success")])
      (let [d (pulled!)]
        (doors/drain! eng)
        (is (= "green" (:checks_verdict d)))
        (let [c (:data (the-change eng 83))]
          (is (= head (:green_head c)))
          (is (nil? (:held_head c)))
          (is (nil? (:held_since c)))
          (is (nil? (:pending_checks c))))))))

(def ^:private change-kind
  (some #(when (= "change" (name (:kind %))) %) (main/resources)))

(defn- waiting-of [data]
  (let [row {:id "c-1" :kind :change :state :submitted :data data}
        by-kind (into {} (map (juxt :kind identity)) (main/resources))]
    (waiting-on/block change-kind [] by-kind row
                      {:now (Instant/now)
                       :data (assoc data :checks_wait (change/checks-wait row nil))})))

(deftest a-change-waiting-on-its-checks-reads-so-and-is-not-held
  (let [head "2fba27a0c1d2e3f405162738495a6b7c8d9e0f12"]
    (testing "a head with a running check names it"
      (let [w (waiting-of {:head_sha head :held_head head
                           :held_since "2026-09-18T16:00:00Z"
                           :pending_checks ["gate"]})]
        (is (str/includes? (:verdict w) "waits on checks: gate"))
        (is (nil? (:held_by w)) "a wait on checks is not a hold")))
    (testing "a head whose checks have not started says so"
      (let [w (waiting-of {:head_sha head :held_head head
                           :held_since "2026-09-18T16:00:00Z"
                           :pending_checks []})]
        (is (str/includes? (:verdict w) "waits on checks to start"))
        (is (nil? (:held_by w)))))
    (testing "a head with its verdict waits on no check"
      (let [w (waiting-of {:head_sha head :green_head head})]
        (is (not (str/includes? (:verdict w) "waits on checks")))))))
