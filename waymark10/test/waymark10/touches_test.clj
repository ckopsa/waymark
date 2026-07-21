(ns waymark10.touches-test
  "The declared cross-write set (waymark9 touches=, design §24):
  :touches rides normalize-action, fingerprints as truth (non-empty
  only — touch-free actions hash byte-identical to the pre-touches
  era), the assembly refuses advertisements of writes that cannot
  happen and warns on unadvertised cascades, the envelope renders the
  set on the action entry, and the conformance library holds every
  logged run to the promise by correlation id. Suite-local kinds;
  the log obligation runs on real Postgres."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.checks-assembly :as ca]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.conformance :as conf]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

(defn- parent-map [& {:keys [archive-touches poke-touches]}]
  {:kind :tt_parent
   :plural "tt_parents"
   :states [:open :archived]
   :initial :open
   :terminal #{:archived}
   :summary "{data.title} · {state}"
   :schema [:map [:title [:string {:min 1 :max 40}]]]
   :owns [{:kind :tt_child :via :parent_id :on {:archive :file}}]
   :actions {:archive
             (cond-> {:from #{:open} :to :archived
                      :safety {:idempotent true :reversible false
                               :confirm false
                               :one-way "Archived is history."}}
               archive-touches (assoc :touches archive-touches))
             :poke
             (cond-> {:from #{:open} :to :open
                      :safety {:idempotent true :reversible false
                               :confirm false}}
               poke-touches (assoc :touches poke-touches))}})

(def ^:private child
  (r/resource
   {:kind :tt_child
    :plural "tt_children"
    :states [:fresh :filed]
    :initial :fresh
    :terminal #{:filed}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:parent_id {:kind :tt_parent :filter #{:eq}} :waymark/ref]]
    :actions {:file {:from #{:fresh} :to :filed
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Filed is history."}}}}))

(def ^:private parent
  (r/resource
   (parent-map :archive-touches [{:kind :tt_child :action :file :may true}]
               :poke-touches [{:kind :tt_child :action :file}])))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

;; ── 1. the fingerprint facet ────────────────────────────────────────

(deftest touches-fingerprint-as-truth
  (let [fp1 (fp/fingerprint-of parent)]
    (testing "the facet, canonical — :may rides only when true"
      (is (= [{"kind" "tt_child" "action" "file" "may" true}]
             (get-in fp1 ["machine" "actions" "archive" "touches"])))
      (is (= [{"kind" "tt_child" "action" "file"}]
             (get-in fp1 ["machine" "actions" "poke" "touches"]))))
    (testing "a touch-free action carries no facet — hash stability"
      (is (nil? (get-in (fp/fingerprint-of child)
                        ["machine" "actions" "file" "touches"]))))
    (testing "declaring a touch is code-or-shape law"
      (let [bare (fp/fingerprint-of
                  (r/resource (parent-map
                               :poke-touches [{:kind :tt_child :action :file}])))]
        (is (= :code-or-shape
               (fp/classify-diff (fp/diff-fingerprints bare fp1))))))))

;; ── 2. the assembly holds the advertisement to the registry ─────────

(deftest assembly-refuses-lies-and-warns-on-silence
  (testing "touching an unregistered kind refuses"
    (let [bad (r/resource (parent-map
                           :archive-touches [{:kind :tt_ghost :action :file}]))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"touches kind"
           (ca/run-all {:kinds {:tt_parent bad :tt_child child}})))))
  (testing "touching a missing action refuses"
    (let [bad (r/resource (parent-map
                           :archive-touches [{:kind :tt_child :action :shred}]))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"not an action \(or the create door\)"
           (ca/run-all {:kinds {:tt_parent bad :tt_child child}})))))
  (testing "touching the target's CREATE door passes — the ctx :create
            birth is advertisable (chore's queue verb)"
    (let [births (r/resource (parent-map
                              :archive-touches [{:kind :tt_child
                                                 :action :create}]))]
      (is (map? (ca/run-all {:kinds {:tt_parent births
                                     :tt_child child}})))))
  (testing "an unadvertised cascade is a coverage warning"
    (let [silent (r/resource (parent-map))
          {:keys [warnings]} (ca/run-all {:kinds {:tt_parent silent
                                                  :tt_child child}})]
      (is (some #(and (str/includes? % "[touches]")
                      (str/includes? % "archive")) warnings))))
  (testing "the advertised world assembles warning-free on this front"
    (let [{:keys [warnings]} (ca/run-all {:kinds {:tt_parent parent
                                                  :tt_child child}})]
      (is (not-any? #(str/includes? % "[touches]") warnings)))))

;; ── 3. malformed declarations refuse at the def site ────────────────

(deftest normalize-refuses-malformed-touches
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #":kind and :action as keywords"
       (r/resource (parent-map :archive-touches [{:kind "tt_child"
                                                  :action :file}]))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unknown key"
       (r/resource (parent-map :archive-touches [{:kind :tt_child
                                                  :action :file
                                                  :maybe true}])))))

;; ── 4. the log obligation, held by correlation id ───────────────────

(def ^:private tables
  ["tt_parents" "tt_children" "definitions"
   "waymark10_transitions" "waymark10_idempotency"
   "waymark10_observations"])

(deftest touches-violations-read-the-log
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st))))
  (let [st (pg/storage db/dsn)]
    (try
      (let [eng (engine/engine {:storage st :resources [parent child]})
            p (:row (inv/create! eng :tt_parent {:title "p"}
                                 {:principal elena}))]
        (inv/create! eng :tt_child {:title "c1" :parent_id (:id p)}
                     {:principal elena})
        (testing "a declared touch that never fires is the violation"
          (inv/invoke! eng :tt_parent (:id p) :poke nil
                       {:principal elena :correlation-id "poke-1"})
          (let [vs (conf/touches-violations eng)]
            (is (= [{:kind :tt_parent :action :poke
                     :touch {:kind :tt_child :action :file}}]
                   (mapv #(select-keys % [:kind :action :touch]) vs)))))
        (testing "the cascade satisfies archive's :may touch — no new
                  violation, and the :may tolerates zero-child runs"
          (inv/invoke! eng :tt_parent (:id p) :archive nil
                       {:principal elena :correlation-id "arch-1"})
          (is (= [:poke] (mapv :action (conf/touches-violations eng))))))
      (finally (pg/close! st)))))
