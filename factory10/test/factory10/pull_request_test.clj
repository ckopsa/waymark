(ns factory10.pull-request-test
  "The pull_request Mirror (ticket d2ceedc9), over REAL rows and the
  in-memory GitHub: discovery reads the house's own change rows, pull
  spends the forge's `forge-pull`, and neither writes a change nor moves
  the forge pass's cursor.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.resources.pull-request :as pr]
            [factory10.sources.forge :as forge]
            [factory10.sources.github :as gh]
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

(defn- check-event [object sha]
  (str "{\"action\":\"completed\",\"" object "\":{\"head_sha\":\"" sha "\"}"
       ",\"repository\":{\"full_name\":\"" repo "\"}}"))

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
      (is (= :ambiguous (:state (delivered! eng "d-4" "check_suite" (check-event "check_suite" shared))))))))
