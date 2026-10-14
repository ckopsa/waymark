(ns factorykit.ask-test
  "The ask's core and its builder (ticket 718f997f), on a small
  extension. That factory10's own ticket is the declaration it was is
  factory10's own suite."
  (:require [clojure.test :refer [deftest is testing]]
            [factorykit.ask :as kit]))

(def ^:private an-extension
  {:kind :ticket
   :plural "tickets"
   :actions {:groom {:guards [:a-wall] :handler :groom-it}
             :restate {:from #{:draft} :to :draft}}})

(def ^:private queue-doors
  #{:groom :ungroom :return_to_draft :prioritize :block :unblock
    :defer :resume :shelve :drop :reopen})

(deftest the-core-is-the-queue
  (is (= [:draft :open :in_review :blocked :deferred :done :dropped]
         (:states kit/core)))
  (is (= :draft (:initial kit/core)))
  (is (= #{} (:terminal kit/core)))
  (is (= queue-doors (set (keys (:actions kit/core))))))

(defn- moves [door]
  (let [shape (get-in kit/core [:actions door])]
    [(:from shape) (:to shape)]))

(deftest the-queue-doors-move-the-ask
  (is (= [#{:draft} :open] (moves :groom)))
  (is (= [#{:open} :draft] (moves :ungroom)))
  (is (= [#{:blocked} :draft] (moves :return_to_draft)))
  (is (= [#{:open} :open] (moves :prioritize)))
  (is (= [#{:draft :open :blocked} :blocked] (moves :block)))
  (is (= [#{:blocked} :open] (moves :unblock)))
  (is (= [#{:open} :deferred] (moves :defer)))
  (is (= [#{:deferred} :open] (moves :resume)))
  (is (= [#{:open :in_review} :draft] (moves :shelve)))
  (is (= [#{:draft :open} :dropped] (moves :drop)))
  (is (= [#{:done :dropped} :draft] (moves :reopen))))

(deftest a-groom-never-lands-in-the-backlog
  (let [[tag [field props schema]] (get-in kit/core [:actions :groom :input])]
    (is (= :map tag))
    (is (= :priority field))
    (is (:optional props))
    (is (= [:maybe [:int {:min 0 :max 3}]] schema))
    (is (= 3 kit/queue-max-priority))))

(deftest the-core-and-the-extension-are-one-declaration
  (let [built (kit/build kit/core an-extension)]
    (testing "the machine is the core's"
      (is (= (:states kit/core) (:states built)))
      (is (= :draft (:initial built)))
      (is (= #{} (:terminal built))))
    (testing "a core door is the core's part with the extension's over it"
      (is (= {:from #{:draft} :to :open
              :input (get-in kit/core [:actions :groom :input])
              :guards [:a-wall] :handler :groom-it}
             (get-in built [:actions :groom]))))
    (testing "a core door the extension is silent on is the core's alone"
      (is (= {:from #{:done :dropped} :to :draft}
             (get-in built [:actions :reopen]))))
    (testing "the extension's own doors and keys stand"
      (is (= {:from #{:draft} :to :draft} (get-in built [:actions :restate])))
      (is (= (conj queue-doors :restate) (set (keys (:actions built)))))
      (is (= :ticket (:kind built)))
      (is (= "tickets" (:plural built))))))

(deftest what-is-the-kits-is-refused-an-extension
  (testing "the machine"
    (doseq [k [:states :initial :terminal]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the extension names"
                            (kit/build kit/core (assoc an-extension k [])))
          (str k))))
  (testing "a core door's machine, and groom's input"
    (doseq [door [{:drop {:to :done}}
                  {:block {:from #{:open}}}
                  {:groom {:input [:map]}}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the kit's to write"
                            (kit/build kit/core (assoc an-extension :actions door)))))))

(deftest an-ending-releases-its-waiters
  (let [rows {"a" {:id "a" :state :done}
              "b" {:id "b" :state :open}
              "c" {:id "c" :state :dropped}}
        waiters [{:id "w1" :version 1 :data {:blocked_by ["a" "b"]}}
                 {:id "w2" :version 1 :data {:blocked_by ["a" "c"]
                                             :blocked_from "draft"}}
                 {:id "w3" :version 1 :data {:blocked_by ["a"]
                                             :blocked_from "open"}}
                 {:id "w4" :version 1 :data {:blocked_by ["b"]}}]
        calls (atom [])
        ctx {:find (fn [kind filter _] (when (and (= :ticket kind)
                                                  (= {:state "blocked"} filter))
                                         waiters))
             :read (fn [_kind id] (get rows id))
             :invoke (fn [kind id door input _fence]
                       (swap! calls conj [kind id door input]))}]
    (kit/release-the-waiters! :ticket {:id "a"} ctx)
    (is (= [[:ticket "w1" :block {:blocked_by ["b"]}]
            [:ticket "w2" :return_to_draft nil]
            [:ticket "w3" :unblock nil]]
           @calls)
        "blockers left restate the list, none left send it back where it was blocked from"))
  (testing "a probe carries no pen, and releases nothing"
    (is (nil? (kit/release-the-waiters! :ticket {:id "a"} {})))))
