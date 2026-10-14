(ns factorykit.ask-test
  "The ask's core and its builder (ticket 718f997f), on a small
  extension. That factory10's own ticket is the declaration it was is
  factory10's own suite."
  (:require [clojure.test :refer [deftest is testing]]
            [factorykit.ask :as kit]
            [waymark10.types :as t]))

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
      (is (= (conj queue-doors :restate :review :return)
             (set (keys (:actions built)))))
      (is (= :ticket (:kind built)))
      (is (= "tickets" (:plural built))))))

(deftest a-built-ask-has-the-law
  (let [built (kit/build kit/core an-extension)]
    (testing "every state that is not unaged has an age and an exit"
      (doseq [state (:states built)
              :when (not (contains? kit/unaged state))]
        (is (pos-int? (get-in built [:expected-age state])) (str state))
        (is (some #(contains? (:in %) state) (:left-by built)) (str state))))
    (testing "a blocked ask waits on its blockers"
      (is (= [{:on :blocked_by :awaits :ticket :in #{:blocked}
               :outcomes {:done :release :dropped :release}}]
             (:waits built))))
    (testing "the exits walk the link's doors"
      (is (= #{:review :complete :unblock :return_to_draft}
             (set (map :via (:left-by built))))))))

(deftest an-extension-adds-to-the-law-and-overrides-it
  (let [own-wait {:on :merge_after :awaits :ticket :in #{:in_review}
                  :outcomes {:done :release :dropped :release}}
        own-exit {:in #{:blocked} :by :wait :via :unblock :to :draft}
        built (kit/build kit/core
                         (assoc an-extension
                                :link {:merged-door :land}
                                :waits [own-wait]
                                :left-by [own-exit]
                                :expected-age {:open 3600}))]
    (is (= [:blocked_by :merge_after] (mapv :on (:waits built))))
    (is (= own-exit (some #(when (= :unblock (:via %)) %) (:left-by built))))
    (is (= #{:review :land :unblock :return_to_draft}
           (set (map :via (:left-by built)))))
    (is (= {:open 3600 :in_review 21600 :blocked 604800}
           (:expected-age built)))))

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

;; ── the change link (step 3b) ───────────────────────────────────────

(def ^:private named
  {:change-kind :pull
   :review-door :send_out
   :return-door :send_back
   :merged-door :land
   :close-change-door :supersede
   :close-change-input (fn [sentence] {:superseded_by sentence})})

(defn- walls [door-shape]
  (mapv :name (:guards door-shape)))

(deftest the-link-doors-are-the-cores
  (testing "through the defaults"
    (let [built (kit/build kit/core an-extension)]
      (is (= [#{:open} :in_review] ((juxt :from :to) (get-in built [:actions :review]))))
      (is (= [#{:in_review} :open] ((juxt :from :to) (get-in built [:actions :return]))))
      (is (= [:only-its-change-moves-it] (walls (get-in built [:actions :review]))))
      (is (= [:only-its-change-moves-it] (walls (get-in built [:actions :return]))))))
  (testing "through named doors, with the extension's words over them"
    (let [built (kit/build kit/core
                           (-> an-extension
                               (assoc :link named)
                               (assoc-in [:actions :send_out] {:handler :out})))]
      (is (= {:from #{:open} :to :in_review :handler :out}
             (dissoc (get-in built [:actions :send_out]) :guards)))
      (is (= [#{:in_review} :open] ((juxt :from :to) (get-in built [:actions :send_back]))))
      (is (not (contains? (:actions built) :review)))
      (is (not (contains? built :link)) "the link leaves the map")))
  (testing "their machine and their wall are the kit's to write"
    (doseq [door [{:review {:from #{:draft}}}
                  {:return {:guards []}}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the kit's to write"
                            (kit/build kit/core (assoc an-extension :actions door)))))))

(deftest only-its-change-moves-it
  (let [judge (fn [link within]
                ((:check (kit/only-its-change-moves-it link)) nil nil {:within within}))]
    (testing "the default change kind"
      (let [link (kit/link :ticket nil)]
        (is (= (t/allow) (judge link {:kind :change})))
        (is (= (t/deny) (judge link {:kind :ticket})))
        (is (= (t/deny) (judge link nil)) "no hand at the wire")))
    (testing "a named change kind"
      (let [link (kit/link :ticket named)]
        (is (= (t/allow) (judge link {:kind :pull})))
        (is (= (t/deny) (judge link {:kind :change})))))))

(defn- a-hand
  "A ctx over `asks` (id → state) and `changes`, and the calls it took."
  [asks changes]
  (let [calls (atom [])]
    [calls
     {:read (fn [_kind id] (when-some [s (get asks id)] {:id id :state s}))
      :find (fn [_kind q _]
              (filter #(= (:state q) (str (:state %))) changes))
      :invoke (fn [kind id door input & _]
                (swap! calls conj [kind id door input]))}]))

(deftest the-link-moves-the-ask
  (let [change (fn [ask] {:id "c" :data {:born_from (str "ticket:" ask)}})]
    (doseq [[link review return merged] [[(kit/link :ticket nil) :review :return :complete]
                                         [(kit/link :ticket named) :send_out :send_back :land]]]
      (testing (str "through " (name review))
        (let [[calls ctx] (a-hand {"o" :open "r" :in_review "d" :done "b" :blocked} [])]
          (kit/submitted! link (change "o") ctx)
          (kit/submitted! link (change "r") ctx)
          (kit/returned! link (change "r") ctx)
          (kit/returned! link (change "o") ctx)
          (kit/merged! link (change "b") {:close_reason "Merged: x."} ctx)
          (kit/merged! link (change "d") {:close_reason "Merged: x."} ctx)
          (kit/submitted! link {:id "c" :data {:born_from "task:o"}} ctx)
          (is (= [[:ticket "o" review nil]
                  [:ticket "r" return nil]
                  [:ticket "b" merged {:close_reason "Merged: x."}]]
                 @calls)
              "each moment from its own states, and an ended ask or another kind's is left"))))
    (testing "a probe carries no pen, and moves nothing"
      (is (nil? (kit/submitted! (kit/link :ticket nil) (change "o") {}))))))

(deftest an-ending-closes-its-pr-less-changes
  (let [born {:born_from "ticket:t"}
        changes [{:id "open" :state "open" :data born}
                 {:id "stuck" :state "stuck" :data born}
                 {:id "pr" :state "failing" :data (assoc born :number 7)}
                 {:id "merging" :state "open" :data born}
                 {:id "submitted" :state "submitted" :data born}
                 {:id "other" :state "open" :data {:born_from "ticket:u"}}]
        leave? #(= "merging" (:id %))]
    (testing "through the defaults"
      (let [[calls ctx] (a-hand {} changes)]
        (kit/close-its-changes! (kit/link :ticket nil) {:id "t"} "done" leave? ctx)
        (is (= [[:change "open" :close_without_pr {:why "done"}]
                [:change "stuck" :close_without_pr {:why "done"}]]
               @calls))))
    (testing "through named doors"
      (let [[calls ctx] (a-hand {} changes)]
        (kit/close-its-changes! (kit/link :ticket named) {:id "t"} "done" leave? ctx)
        (is (= [[:pull "open" :supersede {:superseded_by "done"}]
                [:pull "stuck" :supersede {:superseded_by "done"}]]
               @calls))))))
