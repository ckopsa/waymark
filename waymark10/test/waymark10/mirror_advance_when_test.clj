(ns waymark10.mirror-advance-when-test
  "The ADVANCE DOORS on a moved VALUE (R-C.9, docs/spec-change.md
  §4.2): an :advances entry may give :when, a predicate over the
  stored row's data and the pulled document, beside or instead of
  :field.

  What this suite proves:

  - a changed pull opens exactly the :when doors whose predicate
    holds, after observe_external, and an unchanged pull opens none.
  - a discovery mint opens the :on-mint door alone — the mint rule
    of :advances stands for every other door.
  - the declaration refuses an entry that watches nothing, and a
    :when that is not a function.

  The :field doors (the inbox's observe_mention) keep their own
  suites, unchanged. No database: the in-memory Storage twin hosts
  it."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.wire :as wire]))

;; ── the scriptable forge ────────────────────────────────────────────────

(defrecord PrRig [state]
  mirror/MirrorAdapter
  (discover [_] (vec (sort (keys @state))))
  (pull [_ xid]
    (if-some [doc (get @state xid)]
      [doc (wire/digest doc)]
      (throw (ex-info (str xid " is not a pull request") {:status 404}))))
  (pull-many [_ xids]
    (into {} (map (fn [xid] [xid (if-some [doc (get @state xid)]
                                   [doc (wire/digest doc)]
                                   :gone)]))
          xids))
  (push [_ _ _] (throw (ex-info "pull-only" {}))))

(defn- forge! [rig xid doc] (swap! (:state rig) assoc xid doc))

;; ── the kind ────────────────────────────────────────────────────────────────────

(def ^:private pr-fields
  [:map
   [:title {:optional true
            :x-display {:label "Title"
                        :help "The pull request's title."}}
    [:maybe [:string {:max 120}]]]
   [:head_sha {:optional true
               :x-display {:label "Head"
                           :help "The commit the branch points at."}}
    [:maybe [:string {:max 64}]]]
   [:gh_state {:optional true
               :x-display {:label "Forge state"
                           :help "open, closed or merged, as the forge says."}}
    [:maybe [:string {:max 16}]]]])

(def ^:private doors [:opened :head_moved :merged :closed])

(defn- pr-kind [adapter advances]
  (r/resource
   (mirror/declaration
    {:kind :when_pr
     :plural "when_prs"
     :summary "{data.title}"
     :schema pr-fields
     :filterable {:state #{:eq :in}}}
    {:adapter adapter
     :ttl-seconds 900
     :discover-every 3600
     :advances advances})))

(def ^:private pr-advances
  {:opened {:on-mint true
            :when (fn [was _] (nil? (:gh_state was)))}
   :head_moved {:when (fn [was doc] (not= (:head_sha was) (:head_sha doc)))}
   :merged {:when (fn [was doc] (and (= "merged" (:gh_state doc))
                                     (not= "merged" (:gh_state was))))}
   :closed {:when (fn [was doc] (and (= "closed" (:gh_state doc))
                                     (= "open" (:gh_state was))))}})

(defn- row-of [eng xid]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (first (store/query-rows st tx :when_pr {:external_id xid}
                                 {:limit 1}))))))

(defn- sync-actions
  "The observe and the advance doors on one row, oldest first."
  [eng xid]
  (let [st (:storage eng)]
    (into []
          (comp (map :action)
                (filter (into #{:observe_external} doors)))
          (store/with-tx st
            (fn [tx]
              (store/transitions st tx {:kind :when_pr
                                        :resource-id (str (:id (row-of eng xid)))}
                                 {}))))))

(defn- pull! [eng xid]
  (let [rdef (get (inv/resources eng) :when_pr)]
    (mirror/refresh! eng rdef (inv/decode-row rdef (row-of eng xid))
                     {:ignore-ttl? true})))

;; ── the doors ─────────────────────────────────────────────────────────────────

(deftest a-moved-value-opens-exactly-its-doors
  (let [rig (->PrRig (atom {}))
        pr {:title "Advance on a value" :head_sha "aaa" :gh_state "open"}
        _ (forge! rig "pr-7" pr)
        eng (inv/engine {:storage (memory/storage)
                         :resources [(pr-kind rig pr-advances)]})]
    (mirror/discover! eng :when_pr)

    (testing "a mint opens its :on-mint door alone, after the observe —
              head_moved's predicate held too (nothing was stored), and
              the mint rule kept it shut"
      (is (= [:observe_external :opened] (sync-actions eng "pr-7"))))

    (forge! rig "pr-7" (assoc pr :head_sha "bbb"))
    (pull! eng "pr-7")
    (testing "a moved head opens head_moved and nothing else, after the
              observe"
      (is (= [:observe_external :opened :observe_external :head_moved]
             (sync-actions eng "pr-7"))))

    (testing "an unchanged pull opens nothing"
      (pull! eng "pr-7")
      (is (= [:observe_external :opened :observe_external :head_moved]
             (sync-actions eng "pr-7"))))

    (forge! rig "pr-7" (assoc pr :head_sha "bbb" :gh_state "merged"))
    (pull! eng "pr-7")
    (testing "a merge opens merged alone: closed asks for open → closed"
      (is (= [:observe_external :opened :observe_external :head_moved
              :observe_external :merged]
             (sync-actions eng "pr-7"))))))

(deftest an-on-mint-door-still-asks-its-when
  (let [rig (->PrRig (atom {}))
        eng (inv/engine
             {:storage (memory/storage)
              :resources [(pr-kind rig {:opened {:on-mint true
                                                 :when (constantly false)}})]})]
    (forge! rig "pr-8" {:title "Shut" :head_sha "aaa" :gh_state "open"})
    (mirror/discover! eng :when_pr)
    (testing "an :on-mint door whose predicate does not hold stays shut"
      (is (= [:observe_external] (sync-actions eng "pr-8"))))))

;; ── the declaration ───────────────────────────────────────────────────────

(deftest the-declaration-judges-a-when-entry
  (let [rig (->PrRig (atom {}))]
    (testing "an entry that watches nothing is refused"
      (is (thrown-with-msg? Exception #":field, :when or both"
                            (pr-kind rig {:opened {:on-mint true}}))))
    (testing "a :when that is not a function is refused"
      (is (thrown-with-msg? Exception #":when is a function"
                            (pr-kind rig {:merged {:when :gh_state}}))))
    (testing ":on-mint is a boolean"
      (is (thrown-with-msg? Exception #":on-mint is true or false"
                            (pr-kind rig {:opened {:when (constantly true)
                                                   :on-mint "yes"}}))))))
