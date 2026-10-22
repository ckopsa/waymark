(ns waymark10.notice-law-test
  "A law scenario's :rules and :expect :notices / :told
  (docs/spec-measures.md §6): a staged notice_rule tells once when its
  fact turns true over the walk, and nobody when the fact stays false or
  was true already. In memory, no storage."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.scenario :as scenario]))

(def ^:private reminder
  (r/resource
   {:kind :nl_reminder
    :plural "nl_reminders"
    :states [:waiting :done]
    :initial :waiting
    :terminal #{:done}
    :summary "{data.note} · {state}"
    :schema [:map
             [:note [:string {:min 1 :max 60}]]
             [:owner {:optional true} [:maybe :string]]
             [:due_on :waymark/date]
             [:overdue {:optional true} [:maybe :boolean]]]
    :derived {:overdue {:over [:due_on :now]
                        :expr '(<= (var :due_on) (date-of (var :now)))}}
    :actions {:finish {:from #{:waiting} :to :done
                       :safety {:idempotent true :reversible false
                                :confirm false
                                :one-way "Done is history."}}}}))

(def ^:private rule
  {:name "tell the owner it is overdue"
   :kind "nl_reminder"
   :when {:fact "overdue" :becomes true}
   :address {:field "owner"}
   :notifier "notifier-house-chat"})

(def ^:private sentence
  "The owner hears once when a reminder falls overdue.")

(defn- finishing
  "A reminder finished at noon on 2026-10-07, its overdue fact stored as
  `stored`, due on `due-on`."
  [due-on stored expect]
  {:kind :nl_reminder :attempt :finish
   :at "2026-10-07T12:00:00Z"
   :calendar {:zone "UTC" :workdays #{:mon :tue :wed :thu :fri}}
   :row {:state :waiting :data {:note "bins" :owner "member-jack"
                                :due_on due-on :overdue stored}}
   :rules [rule]
   :expect (merge {:allowed true} expect)})

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest a-fact-turning-true-raises-one-notice
  (testing "a fact that stays false raises no notice"
    (let [s (scenario/scenario :not-yet sentence (finishing "2026-10-09" false {:notices 0}))]
      (is (scenario/check-tier? reminder s) "a notice scenario is judged here")
      (is (= [] (scenario/judge reminder s)))))
  (testing "a fact that turns true raises exactly one, to the member its address names"
    (is (= [] (scenario/judge reminder (scenario/scenario
                                        :overdue sentence
                                        (finishing "2026-10-06" false
                                                   {:notices 1 :told ["member-jack"]}))))))
  (testing "a fact already true tells nobody again"
    (is (= [] (scenario/judge reminder (scenario/scenario
                                        :still sentence
                                        (finishing "2026-10-06" true {:notices 0}))))))
  (testing "a rule that hears the fact becoming false is not told by it turning true"
    (is (= [] (scenario/judge reminder (scenario/scenario
                                        :falling sentence
                                        (assoc (finishing "2026-10-06" false {:notices 0})
                                               :rules [(assoc rule :when {:fact "overdue" :becomes false})]))))))
  (testing "a wrong count fails in the household's words, naming both"
    (let [[v :as vs] (scenario/judge reminder (scenario/scenario
                                               :overdue sentence
                                               (finishing "2026-10-09" false {:notices 1})))]
      (is (= 1 (count vs)))
      (is (str/includes? v sentence))
      (is (str/includes? v "0 notices, not 1"))))
  (testing "a wrong addressee fails, naming whom it told"
    (is (str/includes? (first (scenario/judge reminder (scenario/scenario
                                                        :overdue sentence
                                                        (finishing "2026-10-06" false
                                                                   {:told ["member-elena"]}))))
                       "told [\"member-jack\"], not [\"member-elena\"]"))))

(deftest a-rule-the-kind-cannot-hear-is-an-authoring-fault
  (is (str/includes? (first (scenario/judge reminder (scenario/scenario
                                                      :overdue sentence
                                                      (assoc (finishing "2026-10-06" false {:notices 1})
                                                             :rules [(assoc rule :when {:fact "late"})]))))
                     "does not derive"))
  (is (str/includes? (first (scenario/judge reminder (scenario/scenario
                                                      :overdue sentence
                                                      (assoc (finishing "2026-10-06" false {:notices 1})
                                                             :rules [(assoc rule :address {:field "boss"})]))))
                     "does not declare")))

(deftest a-notice-without-a-clock-or-a-rule-is-refused
  (let [m (finishing "2026-10-06" false {:notices 1})]
    (is (str/includes? (refusal #(scenario/scenario :overdue sentence (dissoc m :at)))
                       "say :at"))
    (is (str/includes? (refusal #(scenario/scenario :overdue sentence (dissoc m :rules)))
                       "stage one"))
    (is (str/includes? (refusal #(scenario/scenario :overdue sentence
                                                    (assoc m :rules [(dissoc rule :notifier)])))
                       ":notifier"))))
