(ns waymark10.mcp-schedule-test
  "`at` on waymark_invoke (docs/spec-scheduled-actions.md, child 3,
  R-7.2): the call is stored as a scheduled_action and not made. Memory
  storage, the real routes through the in-process door and a fixed
  clock. The run is child 1c's and is not exercised here."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private not-before-monday
  "A wall its scheduler expects to lift by the time."
  (g/guard {:name :not-before-monday
            :explain "Chores are scrubbed from Monday on."
            :check (fn [_row _inp _ctx] (t/deny))}))

(def ^:private chore
  "The row a scheduled call acts on."
  (r/resource
   {:kind :chore
    :plural "chores"
    :states [:open :done]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 1}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 2}}
     :scrub {:from #{:open} :to :done
             :guards [not-before-monday]
             :safety {:idempotent true :reversible true :confirm false}
             :display {:label "Scrub" :order 3}}}}))

(def ^:private now (Instant/parse "2026-10-01T12:00:00Z"))

(def ^:private person (t/principal {:id "colton" :display "Colton"}))

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [chore]
                  :now-fn (constantly now)}))

(defn- chore! [eng]
  (:id (:row (inv/create! eng :chore {:title "Dishes"} {:principal person}))))

(defn- tool [eng tool-name args]
  (mcp/call-tool eng (mcp/door eng) {:principal person} tool-name args))

(defn- doc [out] (wire/read-json (get-in out [:content 0 :text])))

(defn- chore-state [eng c]
  (:state (doc (tool eng "waymark_get" {:kind "chore" :id c}))))

(defn- scheduled-count
  "How many scheduled actions the person can see."
  [eng]
  (count (get-in (doc (tool eng "waymark_query" {:kind "scheduled_action"}))
                 [:data :items])))

(defn- later
  "`finish` on a chore at 08:30 the next morning in Denver, with `extra`
  laid over the call."
  [eng c extra]
  (tool eng "waymark_invoke"
        (merge {:kind "chore" :id c :action "finish"
                :at "2026-10-02T08:30" :zone "America/Denver"}
               extra)))

(deftest an-invoke-with-at-stores-the-call-and-moves-nothing
  (let [eng (fresh-engine)
        c (chore! eng)
        out (later eng c {})
        d (doc out)]
    (is (not (:isError out)) "an answer, not a refusal")
    (is (true? (:scheduled d)))
    (is (= "open" (chore-state eng c)) "the call was not made")
    (is (= 1 (scheduled-count eng)) "one scheduled row")
    (testing "the answer is the scheduled row"
      (let [row (doc (tool eng "waymark_get" {:kind "scheduled_action"
                                              :id (:scheduled_action d)}))]
        (is (= "scheduled" (:state row)))
        (is (= {:kind "chore" :action "finish" :id (str c)}
               (select-keys (get-in row [:data :target]) [:kind :action :id])))
        (is (= (:summary row) (:summary d)))))
    (testing "run_at is named in the given zone, and the rule is the default"
      (is (= "2026-10-02T08:30:00-06:00" (:run_at d)))
      (is (= "America/Denver" (:zone d)))
      (is (= "state" (:validity d))))
    (testing "the scheduler's fields ride the call"
      (let [d (doc (later eng c {:validity "strict" :grace_seconds 120}))
            row (doc (tool eng "waymark_get" {:kind "scheduled_action"
                                              :id (:scheduled_action d)}))]
        (is (= "strict" (:validity d)))
        (is (= 120 (get-in row [:data :grace_seconds])))))))

(deftest dry-run-with-at-rehearses-the-check-and-writes-nothing
  (let [eng (fresh-engine)
        c (chore! eng)]
    (testing "a call the door would take"
      (let [out (later eng c {:dry_run true})]
        (is (not (:isError out)) "the check's verdict")
        (is (nil? (:scheduled (doc out))) "and no scheduled answer")))
    (testing "a call the door would refuse"
      (let [out (later eng c {:dry_run true :action "scrub"})]
        (is (true? (:isError out)))
        (is (re-find #"Monday" (str (:detail (doc out)))))))
    (is (zero? (scheduled-count eng)) "neither wrote a row")
    (is (= "open" (chore-state eng c)))))

(deftest a-refused-scheduling-check-is-the-doors-own-refusal
  (let [eng (fresh-engine)
        c (chore! eng)
        out (later eng c {:action "scrub"})
        d (doc out)]
    (is (true? (:isError out)))
    (is (= 409 (:status d)))
    (is (re-find #"Chores are scrubbed from Monday on" (str (:detail d)))
        "the guard's sentence, as the door wrote it")
    (is (zero? (scheduled-count eng)) "what would be refused now is refused now")
    (testing "a refusal the scheduler expects to lift is passed over"
      (let [out (later eng c {:action "scrub"
                              :expect_refusals ["not-before-monday"]})]
        (is (not (:isError out)))
        (is (true? (:scheduled (doc out))))
        (is (= 1 (scheduled-count eng)))))))

(deftest bulk-with-at-is-refused
  (let [eng (fresh-engine)
        c (chore! eng)]
    (doseq [[label many] [["ids" {:ids [(str c)]}]
                          ["items" {:items [{:id (str c)}]}]]]
      (testing label
        (let [out (tool eng "waymark_invoke"
                        (merge {:kind "chore" :action "finish"
                                :at "2026-10-02T08:30:00-06:00"}
                               many))
              d (doc out)]
          (is (true? (:isError out)))
          (is (= 422 (:status d)))
          (is (re-find #"scheduled job" (str (:detail d)))))))
    (is (zero? (scheduled-count eng)))
    (is (= "open" (chore-state eng c)))))

(deftest conditions-are-refused-naming-child-5
  (let [eng (fresh-engine)
        c (chore! eng)]
    (doseq [[label extra] [["the rule" {:validity "conditions"}]
                           ["a condition" {:conditions {:state "open"}}]]]
      (testing label
        (let [out (later eng c extra)]
          (is (true? (:isError out)))
          (is (re-find #"child 5" (str (:detail (doc out))))))))
    (is (zero? (scheduled-count eng)))))
