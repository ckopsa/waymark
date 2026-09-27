(ns factory10.ci-run-hold-test
  "The ci_run's reclassify wall as a HOLD (waymark10.holds), end to end
  over the ring handler on a memory engine: an agent's reclassify
  becomes a held call owned by its person, the person's Allow runs it,
  a forged `:within` is refused, and a person's own reclassify runs
  directly. factory10.ticket-hold-test's arrangement, one kind over.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [waymark10.dev :as dev]
            [waymark10.holds :as holds]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-09-27T08:00:00Z"))

(defn- world []
  (let [eng (dev/scratch! (main/resources) {:now-fn (constantly t0)})]
    {:eng eng :h (dev/handler eng)}))

(defn- req
  ([h method uri] (req h method uri {}))
  ([h method uri {:keys [body headers]}]
   (h (cond-> {:request-method method :uri uri :headers (or headers {})}
        body (assoc :body (wire/write-json body))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(def ^:private person {"x-waymark-principal" "colton"})

(def ^:private agent-headers
  "The classifier seat's sitter: an agent acting for its person."
  {"x-waymark-principal" "ci-classifier"
   "x-waymark-actor-type" "agent"
   "x-waymark-acts-for" "colton"})

(def ^:private the-agent
  (assoc (t/principal {:id "ci-classifier" :type :agent
                       :display "ci-classifier"})
         :acts-for "colton"))

(def ^:private the-source
  (t/principal {:id "factory10-source" :type :system
                :display "factory10-source"}))

(def ^:private a-correction
  {:verdict "this_change"
   :remedy "The runner was fine: the test really does fail on this branch, so fix the fixture."})

(defn- get-row [h plural id]
  (json (req h :get (str "/api/" plural "/" id) {:headers person})))

(defn- log-of [eng id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind :ci_run :resource-id (str id)} {}))))

(defn- classified-run!
  "A red run the source minted and the classifier classified. → its id."
  [eng]
  (let [row (:row (inv/create! eng :ci_run
                               {:run_id "github:ckopsa/waymark/check-run/41752098311"
                                :head_sha "1f0c2d3e4a5b60718293a4b5c6d7e8f901234567"
                                :check_name "test10 (shard 3)"
                                :conclusion "failure"}
                               {:principal the-source}))
        id (str (:id row))]
    (inv/invoke! eng :ci_run id :classify_infra
                 {:remedy "Re-run the shard: the dependency cache died."}
                 {:principal the-source})
    id))

(defn- granted!
  "The agent's ask for the reclassify door, approved by its person. →
  the headers it presents."
  [h]
  (let [asked (req h :post "/api/approval_requests"
                   {:headers agent-headers
                    :body {:task "Correct a verdict I got wrong."
                           :scope [{:kind "ci_run" :actions ["reclassify"]}]}})
        _ (assert (= 201 (:status asked)) (pr-str (json asked)))
        approved (req h :post (str "/api/approval_requests/" (id-of asked)
                                   "/-/approve")
                      {:headers person})
        _ (assert (= 200 (:status approved)) (pr-str (json approved)))]
    (assoc agent-headers "x-waymark-grant"
           (get-in (json approved) [:data :grant_id]))))

(deftest the-reclassify-wall-is-a-registered-hold
  (is (holds/hold? :only-a-person-reclassifies)
      "the guard's `:hold true` registered it when the module loaded")
  (is (delegation/hold-guard? "only-a-person-reclassifies")
      "and the router's one question answers it, as a string too"))

(deftest an-agents-reclassify-is-held-for-its-person-and-the-allow-runs-it
  (let [{:keys [h eng]} (world)
        id (classified-run! eng)
        as (granted! h)
        asked (req h :post (str "/api/ci_runs/" id "/-/reclassify")
                   {:headers as :body a-correction})
        held (:held_call (json asked))]
    (testing "the agent's reclassify is not served: it waits on the person"
      (is (= 202 (:status asked)) (pr-str (json asked)))
      (is (true? (:held (json asked))))
      (is (= "classified" (:state (get-row h "ci_runs" id))))
      (let [call (get-row h "held_calls" held)]
        (is (= "colton" (get-in call [:data :owner])) "owned by its person")
        (is (= "ci-classifier" (get-in call [:data :caller])))
        (is (= {:kind "ci_run" :action "reclassify" :id id}
               (select-keys (get-in call [:data :door]) [:kind :action :id])))))
    (testing "a forged :within, naming a call nobody allowed, is refused"
      (let [e (try (inv/invoke! eng :ci_run id :reclassify a-correction
                                {:principal the-agent
                                 :within {:kind :held_call :action :allow
                                          :id held}})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "the door did not open")
        (is (= :only-a-person-reclassifies
               (some-> (:guard (ex-data e)) name keyword))
            (pr-str (ex-data e))))
      (is (= "classified" (:state (get-row h "ci_runs" id)))))
    (testing "the person's Allow runs the reclassify, as the agent's move"
      (let [allowed (req h :post (str "/api/held_calls/" held "/-/allow")
                         {:headers person})]
        (is (= 200 (:status allowed)) (pr-str (json allowed))))
      (let [run (get-row h "ci_runs" id)]
        (is (= "reclassified" (:state run)))
        (is (= "this_change" (get-in run [:data :verdict]))
            "the correction exactly as the agent wrote it"))
      (is (= "done" (:state (get-row h "held_calls" held))))
      (let [last-move (last (log-of eng id))]
        (is (= :reclassify (:action last-move)))
        (is (= "ci-classifier" (get-in last-move [:actor :id])))))))

(deftest a-persons-own-reclassify-runs-directly
  (let [{:keys [h eng]} (world)
        id (classified-run! eng)
        done (req h :post (str "/api/ci_runs/" id "/-/reclassify")
                  {:headers person :body a-correction})]
    (is (= 200 (:status done)) (pr-str (json done)))
    (is (nil? (:held (json done))))
    (is (= "reclassified" (:state (get-row h "ci_runs" id))))
    (is (= "colton" (get-in (last (log-of eng id)) [:actor :id])))))
