(ns factory10.red-base-test
  "A red base opens one groomed fix ticket, and a green base completes
  it (ticket ade81ae9). Judged over the REAL GitHub source and an
  in-memory GitHub, with the engine over the in-memory store, as
  factory10.github-source-test is.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.bench :as bench]
            [factory10.main :as main]
            [factory10.sources.forge :as forge]
            [factory10.sources.github :as gh]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private repo "ckopsa/waymark-bench")

(def ^:private a-person (t/principal {:id "colton" :display "Colton"}))

(def ^:private head-1 "1111111111111111111111111111111111111111")
(def ^:private head-2 "2222222222222222222222222222222222222222")
(def ^:private head-3 "3333333333333333333333333333333333333333")

(defn- a-check [id sha conclusion]
  {:id id :name "gate" :status "completed" :conclusion conclusion
   :head_sha sha
   :html_url (str "https://github.com/" repo "/runs/" id)
   :details_url (str "https://github.com/" repo "/actions/runs/900/job/" id)})

(defn- world
  "A fake GitHub, the real source over it, and an engine holding one
  active policy for `repo` that requires `gate`."
  []
  (let [state (gh/fake-state)
        eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))})]
    (inv/create! eng :repo_policy {:repository repo :required_checks ["gate"]}
                 {:principal a-person})
    {:state state :engine eng :source (gh/fake-source state {:repos repo})}))

(defn- pass! [{:keys [source engine]}]
  (forge/pass! {:source source :engine engine :log-fn (fn [& _] nil)}))

(defn- rows [eng kind where]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/query-rows st tx kind where
                                                 {:limit 50})))))

(defn- tickets [eng] (rows eng :ticket {:repo repo}))

(defn- policy-of [eng] (first (rows eng :repo_policy {:repository repo})))

(defn- state-of [row] (some-> (:state row) name keyword))

(defn- head-at!
  "The base's head moves to `sha`, and `gate` finishes on it."
  [{:keys [state]} sha id conclusion]
  (gh/seed-branch! state repo "main" sha)
  (gh/seed-check! state repo sha (a-check id sha conclusion))
  (gh/seed-log! state (str id) "compiling\nFAIL in (the-merge-clash)\nexpected 1, got 2"))

(deftest a-base-red-for-two-passes-opens-one-groomed-ticket
  (let [{:keys [engine] :as w} (world)]
    (head-at! w head-1 501 "failure")

    (testing "one red pass opens nothing: a rerun may still turn it"
      (is (= 0 (:base-opened (pass! w))))
      (is (empty? (tickets engine)))
      (is (= "red" (str (get-in (policy-of engine) [:data :base_state]))))
      (is (= head-1 (get-in (policy-of engine) [:data :base_head]))))

    (testing "the second red pass opens one groomed p0 bug"
      (is (= 1 (:base-opened (pass! w))))
      (let [[tk & more] (tickets engine)
            detail (str (get-in tk [:data :detail]))]
        (is (empty? more))
        (is (= :open (state-of tk)) "groomed, so the code seat wakes")
        (is (= 0 (get-in tk [:data :priority])))
        (is (= "bug" (str (get-in tk [:data :type]))))
        (is (= "main is red: gate" (get-in tk [:data :title])))
        (is (str/includes? detail head-1))
        (is (str/includes? detail (str "https://github.com/" repo "/runs/501")))
        (is (str/includes? detail "FAIL in (the-merge-clash)")
            "the failed step's log tail rides in the body")
        (is (= [(str head-1 ": gate")] (get-in tk [:data :red_heads])))
        (is (= (str (:id tk))
               (get-in (policy-of engine) [:data :base_ticket])))))

    (testing "a third red pass on the same head opens nothing more"
      (is (= 0 (:base-opened (pass! w))))
      (is (= 1 (count (tickets engine)))))

    (testing "a new red head is noted on the same ticket, not a second one"
      (head-at! w head-2 502 "failure")
      (let [census (pass! w)]
        (is (= 0 (:base-opened census)))
        (is (= 1 (:base-noted census))))
      (let [[tk & more] (tickets engine)]
        (is (empty? more))
        (is (= [(str head-1 ": gate") (str head-2 ": gate")]
               (get-in tk [:data :red_heads])))))

    (testing "a green head completes it with the engine's own hand"
      (head-at! w head-3 503 "success")
      (is (= 1 (:base-closed (pass! w))))
      (let [tk (first (tickets engine))]
        (is (= :done (state-of tk)))
        (is (= (str "main is green again at " head-3 ".")
               (get-in tk [:data :close_reason]))))
      (is (= "green" (str (get-in (policy-of engine) [:data :base_state])))))

    (testing "a green pass with nothing new writes nothing"
      (let [before (:version (policy-of engine))]
        (is (= 0 (:base-closed (pass! w))))
        (is (= before (:version (policy-of engine))))))))

(deftest a-base-red-once-and-green-next-opens-nothing
  (let [{:keys [engine] :as w} (world)]
    (head-at! w head-1 601 "failure")
    (pass! w)
    (head-at! w head-2 602 "success")
    (let [census (pass! w)]
      (is (= 0 (:base-opened census)))
      (is (= 0 (:base-closed census))))
    (is (empty? (tickets engine)))
    (is (= "green" (str (get-in (policy-of engine) [:data :base_state]))))))

(deftest a-red-gate-carries-the-log-of-the-suite-that-failed
  ;; ticket 9a14577e: `gate` only says `quick=failure`; the `quick`
  ;; job's own log is what names the red step
  (let [{:keys [state engine] :as w} (world)]
    (gh/seed-branch! state repo "main" head-1)
    (gh/seed-check! state repo head-1 (a-check 701 head-1 "failure"))
    (gh/seed-log! state "701" "quick=failure")
    (gh/seed-check! state repo head-1
                    (assoc (a-check 702 head-1 "failure") :name "quick"))
    (gh/seed-log! state "702" "Run make check-queue\nFAIL in (calendar10-clash)")
    (gh/seed-check! state repo head-1
                    (assoc (a-check 703 head-1 "success") :name "slow"))
    (gh/seed-log! state "703" "all green here")
    (pass! w)
    (is (= 1 (:base-opened (pass! w))))
    (let [tk (first (tickets engine))
          detail (str (get-in tk [:data :detail]))]
      (is (= "main is red: gate" (get-in tk [:data :title])))
      (is (str/includes? detail "quick=failure") "the gate's log still rides")
      (is (str/includes? detail "- quick"))
      (is (str/includes? detail "FAIL in (calendar10-clash)")
          "the failed suite's own log rides in the body")
      (is (not (str/includes? detail "all green here"))
          "a suite that passed is not quoted"))))

(deftest a-red-after-green-names-the-head-that-turned-it
  (let [{:keys [engine] :as w} (world)]
    (head-at! w head-1 701 "success")
    (pass! w)
    (head-at! w head-2 702 "failure")
    (pass! w)
    (is (= head-2 (get-in (policy-of engine) [:data :base_red_from])))
    (is (= 1 (:base-opened (pass! w))))
    (is (str/includes? (str (get-in (first (tickets engine)) [:data :detail]))
                       (str "first red head after a green one was `" head-2)))))

(deftest a-private-base-is-read-through-actions
  (let [{:keys [state engine] :as w} (world)]
    (gh/checks-answer! state repo 403)
    (gh/seed-branch! state repo "main" head-1)
    (gh/seed-run! state repo head-1 {:id 900})
    (gh/seed-job! state repo 900
                  {:id 801 :name "gate" :status "completed"
                   :conclusion "failure" :head_sha head-1
                   :html_url (str "https://github.com/" repo
                                  "/actions/runs/900/job/801")})
    (gh/seed-log! state "801" "FAIL in the private base")
    (pass! w)
    (is (= 1 (:base-opened (pass! w))))
    (is (str/includes? (str (get-in (first (tickets engine)) [:data :detail]))
                       "FAIL in the private base"))))

(deftest a-base-with-no-head-writes-nothing
  (let [{:keys [engine] :as w} (world)
        before (:version (policy-of engine))]
    (pass! w)
    (is (= before (:version (policy-of engine)))
        "a branch the forge will not read costs this pass, and no row")))

(deftest the-base-doors-are-the-engines-hand-alone
  (let [{:keys [engine]} (world)
        tk (:row (inv/create! engine :ticket {:title "A ticket" :repo repo}
                              {:principal a-person}))
        id (str (:id tk))]
    (inv/invoke! engine :ticket id :groom {} {:principal a-person})
    (is (thrown? Exception
                 (inv/invoke! engine :ticket id :mend {:close_reason "No."}
                              {:principal a-person})))
    (is (thrown? Exception
                 (inv/invoke! engine :ticket id :note_red {:red_head "x: gate"}
                              {:principal a-person})))
    (is (thrown? Exception
                 (inv/invoke! engine :repo_policy (str (:id (policy-of engine)))
                              :note_base {:verdict "red"}
                              {:principal a-person})))))

(deftest a-ticket-door-that-throws-does-not-cost-the-base-write
  (let [{:keys [engine] :as w} (world)
        invoke! inv/invoke!]
    (head-at! w head-1 801 "failure")
    (pass! w)
    (head-at! w head-2 802 "failure")
    (with-redefs-fn
      {#'inv/invoke! (fn [eng kind & more]
                       (if (= :ticket kind)
                         (throw (ex-info "the ticket door is shut" {}))
                         (apply invoke! eng kind more)))}
      (fn []
        (is (= 0 (:base-opened (pass! w)))
            "the ticket door refused, so nothing was opened")))
    (let [policy (policy-of engine)]
      (is (= "red" (str (get-in policy [:data :base_state]))))
      (is (= head-2 (get-in policy [:data :base_head]))
          "the base WAS read on this pass, so the base was written")
      (is (str/blank? (str (get-in policy [:data :base_ticket])))
          "and no ticket is claimed that the door never opened"))))

(deftest a-quiet-base-is-stamped-each-pass
  (let [{:keys [engine] :as w} (world)]
    (head-at! w head-1 901 "success")
    (pass! w)
    (let [before (policy-of engine)
          checked (str (get-in before [:data :base_checked_at]))]
      (is (not (str/blank? checked)))
      (Thread/sleep 20)
      (pass! w)
      (let [after (policy-of engine)]
        (is (not= checked (str (get-in after [:data :base_checked_at])))
            "a base that moved nothing is still stamped as read")
        (is (= (:version before) (:version after))
            "with a maintenance write, not a transition")))))

(deftest a-base-the-pass-cannot-read-is-noted-on-the-policy
  (let [{:keys [engine] :as w} (world)]
    (testing "a refused base read writes source_note"
      (pass! w)
      (is (str/starts-with? (str (get-in (policy-of engine)
                                         [:data :source_note]))
                            "The base `main` was not read (")))

    (testing "a second refusal of the same kind writes nothing again"
      (let [before (get-in (policy-of engine) [:data :source_note])]
        (Thread/sleep 20)
        (pass! w)
        (is (= before (get-in (policy-of engine) [:data :source_note])))))

    (testing "a good read clears it"
      (head-at! w head-1 902 "success")
      (pass! w)
      (is (str/blank? (str (get-in (policy-of engine) [:data :source_note]))))
      (is (= head-1 (get-in (policy-of engine) [:data :base_head]))))))

(deftest the-base-pass-runs-when-a-pass-before-it-throws
  (let [{:keys [engine] :as w} (world)]
    (head-at! w head-1 903 "failure")
    (with-redefs-fn
      {#'forge/label-pass! (fn [& _] (throw (ex-info "the label pass broke" {})))}
      (fn []
        (is (thrown? Exception (pass! w)) "the throw is not swallowed")))
    (is (= head-1 (get-in (policy-of engine) [:data :base_head]))
        "but the base was read and written first")))

;; ── the groom floor (ticket eb515931) ───────────────────────────────
;;
;; A queue under its policy's floor files one draft ticket for mayor
;; and stamps the policy. The floor pass is called directly: it reads
;; the engine's rows and no forge.

(defn- floor-world
  "An engine holding one active policy for `repo` at this floor, `open`
  groomed tickets and two draft bugs."
  [floor open]
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))})
        ticket! (fn [title type]
                  (str (:id (:row (inv/create! eng :ticket
                                               {:title title :type type
                                                :repo repo}
                                               {:principal a-person})))))]
    (inv/create! eng :repo_policy {:repository repo :required_checks ["gate"]
                                   :groom_floor floor}
                 {:principal a-person})
    (dotimes [i open]
      (inv/invoke! eng :ticket (ticket! (str "Open ask " i) "task") :groom {}
                   {:principal a-person}))
    (ticket! "A draft bug" "bug")
    (ticket! "Another draft bug" "bug")
    eng))

(defn- floor-tickets [eng]
  (filterv #(str/starts-with? (str (get-in % [:data :title]))
                              "Groom the next batch for ")
           (tickets eng)))

(defn- floor-pass! [eng]
  (:floor-filed (forge/floor-pass! eng {} (fn [& _] nil)) 0))

(defn- floor-noted! [eng ^Instant at]
  (bench/mark-row! eng :repo_policy (str (:id (policy-of eng)))
                   {:floor_noted_at at} #{}))

(deftest a-queue-under-its-floor-files-one-draft-ticket
  (let [eng (floor-world 4 3)]
    (is (= 1 (floor-pass! eng)))
    (let [[ticket & more] (floor-tickets eng)
          policy (policy-of eng)]
      (is (nil? more))
      (is (= :draft (state-of ticket)))
      (is (= "Groom the next batch for ckopsa/waymark-bench: 3 open, floor 4"
             (get-in ticket [:data :title])))
      (is (= 1 (get-in ticket [:data :priority])))
      (is (str/includes? (str (get-in ticket [:data :detail]))
                         "2 draft bugs, tasks and chores"))
      (is (some? (get-in policy [:data :floor_noted_at])))
      (is (= 3 (get-in policy [:data :floor_count]))))
    (testing "a pass past the window, while the floor ticket is still draft, files none"
      (floor-noted! eng (.minusSeconds (Instant/now) 7200))
      (is (zero? (floor-pass! eng)))
      (is (= 1 (count (floor-tickets eng)))))))

(deftest the-floor-files-none-inside-its-settle-window
  (let [eng (floor-world 4 3)]
    (floor-noted! eng (Instant/now))
    (is (zero? (floor-pass! eng)))
    (is (empty? (floor-tickets eng)))
    (testing "and files once the window has passed"
      (floor-noted! eng (.minusSeconds (Instant/now) 7200))
      (is (= 1 (floor-pass! eng)))
      (is (= 1 (count (floor-tickets eng)))))))

(deftest a-queue-at-its-floor-files-none
  (let [eng (floor-world 4 4)]
    (is (zero? (floor-pass! eng)))
    (is (empty? (floor-tickets eng)))))

(deftest a-floor-of-zero-never-files
  (let [eng (floor-world 0 0)]
    (is (zero? (floor-pass! eng)))
    (is (empty? (floor-tickets eng)))
    (is (nil? (get-in (policy-of eng) [:data :floor_noted_at])))))
