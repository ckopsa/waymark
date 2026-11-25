(ns waymark10.least-approval-power-test
  "ONE TOOL, TWO POWERS (ticket 4322a705). The bench maps its `merge`
  tool under `bench.merge` (approval person) and
  `bench.merge_low_stakes` (approval none). A grant that holds the
  first for one repository and the second for another runs a merge on
  the second at once, holds one on the first for a person, and refuses
  one on a third — the door judges every entry the grant admits for
  the call's arguments and picks the least approval.

  Memory storage, a held clock and an in-process fake rig whose log is
  the wire."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private clock (Instant/parse "2026-11-25T09:00:00Z"))

(def ^:private repo-a "ckopsa/waymark-bench")
(def ^:private repo-b "ckopsa/wow-addons")
(def ^:private repo-c "ckopsa/elsewhere")

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(def ^:private clerk
  (t/principal {:id "merge-clerk" :type :agent :display "Merge clerk"
                :model "least-approval-model"}))

(def ^:private rig-tools
  [{:name "merge" :description "Merge one pull request."
    :inputSchema {:type "object"
                  :properties {:repo {:type "string"}
                               :number {:type "integer"}}}}])

(def ^:private merge-powers
  [{:power "bench.merge" :tools ["merge"] :approval "person"
    :constraints ["repo"]}
   {:power "bench.merge_low_stakes" :tools ["merge"] :approval "none"
    :constraints ["repo"]}])

(defn- fake-rig [log]
  (fn [method params]
    (case method
      "tools/list" {:tools rig-tools}
      "tools/call"
      (do (swap! log conj {:tool (str "bench__" (:name params))
                           :arguments (:arguments params)})
          {:isError false
           :content [{:type "text"
                      :text (wire/write-json {:ok (str (:name params))})}]})
      (throw (ex-info (str "the fake rig speaks no " method) {})))))

(defn- world
  "The bench row over the fake rig, and the clerk wearing `bench.merge`
  on repo A and `bench.merge_low_stakes` on repo B."
  []
  (let [log (atom [])
        rig (fake-rig log)
        eng (engine/engine
             {:storage (memory/storage)
              :resources [caps/capability]
              :now-fn (fn [] clock)
              :services {:mcp-servers
                         {:client-fn (fn [row]
                                       (when (= "bench"
                                                (str (get-in row [:data :name])))
                                         rig))}}})
        _ (inv/create! eng :mcp_server
                       {:name "bench"
                        :transport "stdio"
                        :command "python3"
                        :args ["-m" "bench" "--stdio"]
                        :powers merge-powers
                        :note "The bench, beside the engine."}
                       {:principal colton})
        g (:row (inv/create! eng :grant
                             {:audience (:id clerk)
                              :scope [{:kind "bench.merge" :actions []
                                       :filter {:repo repo-a}}
                                      {:kind "bench.merge_low_stakes" :actions []
                                       :filter {:repo repo-b}}]}
                             {:principal colton}))]
    (inv/invoke! eng :grant (:id g) :accept {} {:principal clerk})
    {:eng eng
     :log log
     :vis (grants/visibility eng (str (:id g)) clerk)}))

(defn- merge!
  "One merge through the power door, as the clerk. → {:answer} or
  {:refused <problem data>}."
  [{:keys [eng vis]} repo]
  (try {:answer (gate/invoke-for eng vis "bench__merge"
                                 {:repo repo :number 3
                                  :why "Land the green change."}
                                 {:caller (:id clerk) :principal clerk})}
       (catch clojure.lang.ExceptionInfo e {:refused (ex-data e)})))

(deftest a-merge-runs-under-the-least-approval-the-grant-admits
  (testing "repo B: bench.merge_low_stakes admits it, so it runs at once"
    (let [w (world)
          r (merge! w repo-b)]
      (is (nil? (:refused r)) (pr-str r))
      (is (not (str/includes? (pr-str (:answer r)) "held_call")) (pr-str r))
      (is (= ["bench__merge"] (mapv :tool @(:log w)))
          "the call reached the rig, with no held call")))
  (testing "repo A: only bench.merge admits it, so a person is asked"
    (let [w (world)
          r (merge! w repo-a)]
      (is (nil? (:refused r)) (pr-str r))
      (is (str/includes? (pr-str (:answer r)) "held") (pr-str r))
      (is (= [] @(:log w)) "the held call never touched the rig")))
  (testing "repo C: neither admits it, so it is refused"
    (let [w (world)
          r (merge! w repo-c)]
      (is (or (some? (:refused r)) (true? (:isError (:answer r)))) (pr-str r))
      (is (= [] @(:log w)) "nothing reached the rig"))))

(deftest a-tool-two-powers-map-names-both-entries
  (let [{:keys [eng]} (world)
        row (servers/row-by-name eng "bench")]
    (is (= ["bench.merge" "bench.merge_low_stakes"]
           (mapv :power (servers/entries-for row "merge"))))
    (is (= "bench.merge" (:token (servers/resolve-tool eng "bench__merge")))
        "with no grant to judge, the first entry stands as before")))
