(ns factory10.head-moves-test
  "A change's new head takes back the verdicts that judged its old one
  (ticket 35600491), over REAL rows on a memory engine: the verdict
  keeps the head it was said at, and the mirror's observe of another
  head reopens it, so the judging seat's walk holds the change again.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.mirror :as mirror]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.judgments :as judgments]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private repo "ckopsa/waymark")
(def ^:private head-a "2fba27a0c1d2e3f405162738495a6b7c8d9e0f12")
(def ^:private head-b "a667ef3b1c2d3e4f5061728394a5b6c7d8e9f012")

(def ^:private a-person (t/principal {:id "colton" :type :human}))

(defn- a-change!
  "A change the mirror minted, at `head` (nil: a seat-born row that
  no pull request has given a head yet)."
  [eng number head]
  (str (:id (:row (inv/create! eng :change
                               (cond-> {:change_id (str "github:" repo "#" number)
                                        :repository repo
                                        :number number
                                        :title "A change under review"
                                        :head_branch (str "engine/change-" number)
                                        :base_branch "main"}
                                 head (assoc :head_sha head))
                               {:principal mirror/source-principal})))))

(defn- judgment! [eng nm subject-kind words]
  (let [jrow (:row (inv/create! eng :judgment
                                {:name nm :subject_kind subject-kind
                                 :queue {:state "open"}
                                 :verdicts words
                                 :remedy_max 400}
                                {:principal a-person}))]
    (inv/invoke! eng :judgment (str (:id jrow)) :promote {} {:principal a-person})
    (str (:id jrow))))

(defn- world []
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))})]
    (inv/create! eng :repo_policy {:repository repo} {:principal a-person})
    {:eng eng
     :change (a-change! eng 566 head-a)
     :judgment (judgment! eng "qa" "change"
                          [{:name "verified" :sentence "The change does what it says."}
                           {:name "broken" :sentence "The change does not do what it says."}])}))

(defn- verified!
  ([w] (verified! w (:change w)))
  ([{:keys [eng judgment]} change]
   (:row (inv/create! eng :verdict
                      {:judgment judgment :subject_kind "change" :subject_id change
                       :verdict "verified" :remedy "Merge it."}
                      {:principal a-person}))))

(defn- observe!
  ([w input] (observe! w (:change w) input))
  ([{:keys [eng]} change input]
   (inv/invoke! eng :change change :observe input
                {:principal mirror/source-principal})))

(defn- verdicts-on [eng subject-id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/query-rows (:storage eng) tx :verdict
                               {:subject_id (str subject-id)} {:limit 10}))))

(defn- state-of-the-verdict [eng subject-id]
  (some-> (first (verdicts-on eng subject-id)) :state name))

(deftest a-verdict-keeps-the-head-it-was-said-at
  (let [w (world)]
    (is (= head-a (get-in (verified! w) [:data :subject_head])))))

(deftest a-new-head-reopens-the-verdict-on-the-old-one
  (let [{:keys [eng change judgment] :as w} (world)]
    (verified! w)
    (is (contains? (judgments/judged-subjects eng judgment) change)
        "judged, the change is out of the queue")
    (observe! w {:head_sha head-b})
    (let [v (first (verdicts-on eng change))]
      (is (= "overruled" (name (:state v))))
      (is (= "head moved 2fba27a -> a667ef3" (get-in v [:data :reopen_note]))))
    (testing "and the judging seat's walk holds the change again"
      (is (not (contains? (judgments/judged-subjects eng judgment) change))))
    (testing "the next verdict keeps the new head"
      (is (= head-b (get-in (verified! w) [:data :subject_head]))))))

(deftest an-observe-that-keeps-the-head-reopens-nothing
  (let [{:keys [eng change] :as w} (world)]
    (verified! w)
    (observe! w {:head_sha head-a :mergeable "clean"})
    (observe! w {:review_state "approved"})
    (is (= "said" (state-of-the-verdict eng change)))))

(deftest a-verdict-on-another-kind-is-untouched
  (let [{:keys [eng] :as w} (world)
        ticket (str (:id (:row (inv/create! eng :ticket
                                            {:title "A ticket" :type "task" :repo repo}
                                            {:principal a-person}))))
        jid (judgment! eng "engineering" "ticket"
                       [{:name "pr_opened" :sentence "A pull request is open for it."}])
        v (:row (inv/create! eng :verdict
                             {:judgment jid :subject_kind "ticket" :subject_id ticket
                              :verdict "pr_opened" :remedy "https://example.test/pull/566"}
                             {:principal a-person}))]
    (is (some? v))
    (is (nil? (get-in v [:data :subject_head])) "a ticket carries no head")
    (observe! w {:head_sha head-b})
    (is (= "said" (state-of-the-verdict eng ticket)))))

(deftest a-verdict-said-with-no-head-stands
  ;; the shape of every verdict said before heads were kept: no
  ;; subject_head, so no head can be said to have moved under it
  (let [{:keys [eng] :as w} (world)
        headless (a-change! eng 567 nil)
        v (verified! w headless)]
    (is (nil? (get-in v [:data :subject_head])))
    (observe! w headless {:head_sha head-b})
    (is (= "said" (state-of-the-verdict eng headless)))))
