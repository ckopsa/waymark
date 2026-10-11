(ns waymark10.verdict-head-test
  "A new head reopens the verdicts said at the old one, for ANY subject
  kind (ticket 8ef24689): here a change whose head field is `head`, as
  colton-tools' Bitbucket-fed change keeps it, at 12 characters. The
  observe below is the kind's own and calls the shared
  `verdict/reopen-stale-verdicts!`, as any change kind's observe would.

  The in-memory twin hosts it (`verdict_test`'s reason)."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r :refer [defhandler]]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.verdict :as verdict]))

(def ^:private head-a "2fba27a35bcb")
(def ^:private head-b "a667ef35bcb9")
(def ^:private head-a-in-full "2fba27a35bcb0c1d2e3f405162738495a6b7c8d9")

(def ^:private a-person (t/principal {:id "colton" :type :human}))

(defhandler observe-the-head [row inp ctx]
  (when-some [head (:head inp)]
    (verdict/reopen-stale-verdicts! ctx :vh_change (:id row) head))
  (update row :data merge inp))

(def change
  (r/resource
   {:kind :vh_change
    :plural "vh_changes"
    :states [:open :merged]
    :initial :open
    :terminal #{:merged}
    :allow-dead #{:merged}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:head {:optional true} [:maybe [:string {:max 64}]]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:observe {:from #{:open} :to :open
               :input [:map [:head [:string {:min 1 :max 64}]]]
               :handler observe-the-head
               :safety {:idempotent true :reversible true :confirm false}
               :display {:label "Observe" :order 1
                         :description "The forge says where the change stands"}}}}))

(defn- world []
  (let [eng (engine/engine {:storage (memory/storage) :resources [change]})
        jrow (:row (inv/create! eng :judgment
                                {:name "qa" :subject_kind "vh_change"
                                 :queue {:state "open"}
                                 :verdicts [{:name "verified" :sentence "The change does what it says."}]
                                 :remedy_max 400}
                                {:principal a-person}))
        jid (str (:id jrow))]
    (inv/invoke! eng :judgment jid :promote {} {:principal a-person})
    {:eng eng
     :judgment jid
     :change (str (:id (:row (inv/create! eng :vh_change
                                          {:title "pcore 566" :head head-a}
                                          {:principal a-person}))))}))

(defn- verified! [{:keys [eng judgment change]}]
  (:row (inv/create! eng :verdict
                     {:judgment judgment :subject_kind "vh_change" :subject_id change
                      :verdict "verified" :remedy "Merge it."}
                     {:principal a-person})))

(defn- observe! [{:keys [eng change]} head]
  (inv/invoke! eng :vh_change change :observe {:head head} {:principal a-person}))

(defn- the-verdict [{:keys [eng change]}]
  (store/with-tx (:storage eng)
    (fn [tx] (first (store/query-rows (:storage eng) tx :verdict
                                      {:subject_id (str change)} {:limit 10})))))

(deftest a-verdict-keeps-a-head-field-named-head
  (is (= head-a (get-in (verified! (world)) [:data :subject_head]))))

(deftest a-new-head-reopens-a-verdict-on-any-change-kind
  (let [w (world)]
    (verified! w)
    (observe! w head-b)
    (let [v (the-verdict w)]
      (is (= "overruled" (name (:state v))))
      (is (= "head moved 2fba27a -> a667ef3" (get-in v [:data :reopen_note]))))))

(deftest the-same-commit-at-another-length-reopens-nothing
  (let [w (world)]
    (verified! w)
    (testing "a 40-character sha of the 12-character head is the same commit"
      (observe! w head-a-in-full)
      (is (= "said" (name (:state (the-verdict w))))))
    (testing "and so is the 12-character head again"
      (observe! w head-a)
      (is (= "said" (name (:state (the-verdict w))))))))
