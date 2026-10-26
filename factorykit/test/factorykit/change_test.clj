(ns factorykit.change-test
  "The builder's merge rules (ticket ce3dd7b1), on a small core and a
  small extension. That factory10's own change is the declaration it
  was is factory10's test, `factory10.change-kit-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [factorykit.change :as kit]))

(def ^:private a-core
  {:states [:open :merged]
   :terminal #{:merged}
   :unique [[:change_id]]
   :waits [{:on :checks :in #{:open}}]
   :expected-age {:open 60 :merged 5}
   :fields [[:change_id [:string {:min 1}]]
            [:rounds {:default 0} [:int {:min 0}]]]
   :actions {:merge {:from #{:open} :to :merged}}})

(def ^:private an-extension
  {:kind :change
   :plural "changes"
   :initial :open
   :schema [:map [:colour {:optional true} [:maybe :string]]]
   :actions {:paint {:from #{:open} :to :open}}})

(deftest the-core-and-the-extension-are-one-declaration
  (let [built (kit/build a-core an-extension)]
    (testing "the machine and the index are the core's"
      (is (= [:open :merged] (:states built)))
      (is (= #{:merged} (:terminal built)))
      (is (= [[:change_id]] (:unique built))))
    (testing "the fields and the doors are the core's, then the extension's"
      (is (= [:map
              [:change_id [:string {:min 1}]]
              [:rounds {:default 0} [:int {:min 0}]]
              [:colour {:optional true} [:maybe :string]]]
             (:schema built)))
      (is (= #{:merge :paint} (set (keys (:actions built))))))
    (testing "every other key is the extension's, and the core's :fields is spent"
      (is (= :change (:kind built)))
      (is (= :open (:initial built)))
      (is (not (contains? built :fields))))))

(deftest the-extension-adds-to-the-index
  (is (= [[:change_id] [:colour]]
         (:unique (kit/build a-core (assoc an-extension :unique [[:colour] [:change_id]]))))))

(deftest the-extension-says-another-field-order
  (let [built (kit/build a-core (assoc an-extension :field-order [:colour :change_id]))]
    (is (= [:colour :change_id :rounds] (mapv first (rest (:schema built)))))
    (is (not (contains? built :field-order)))))

(deftest the-law-is-the-cores-with-the-extensions-over-it
  (testing "a silent extension keeps the core's law"
    (let [built (kit/build a-core an-extension)]
      (is (= (:waits a-core) (:waits built)))
      (is (= {:open 60 :merged 5} (:expected-age built)))
      (is (not (contains? built :left-by)))))
  (testing "a map merges, and the extension's entry wins"
    (is (= {:open 30 :merged 5}
           (:expected-age (kit/build a-core (assoc an-extension :expected-age {:open 30}))))))
  (testing "any other value stands in place of the core's"
    (is (= [] (:waits (kit/build a-core (assoc an-extension :waits [])))))))

(deftest the-builder-refuses-what-is-the-cores
  (testing "a core field"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"redefines the core's field"
         (kit/build a-core (assoc an-extension :schema [:map [:rounds :int]])))))
  (testing "a core door"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"redefines the core's door"
         (kit/build a-core (assoc-in an-extension [:actions :merge] {:from #{:open} :to :open})))))
  (testing "the states and the tomb"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"are the core's"
         (kit/build a-core (assoc an-extension :states [:open]))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"are the core's"
         (kit/build a-core (assoc an-extension :terminal #{})))))
  (testing "an order that names no field, and a schema that is no plain map"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #":field-order names"
         (kit/build a-core (assoc an-extension :field-order [:shape]))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"is not a \[:map"
         (kit/build a-core (assoc an-extension :schema [:map {:closed true} [:colour :string]]))))))

(deftest the-extension-hands-a-core-door-its-handler
  (let [finish (fn [row _inp _ctx] row)]
    (testing "a core door with no handler takes the extension's"
      (let [built (kit/build a-core (assoc an-extension :handlers {:merge finish}))]
        (is (= finish (get-in built [:actions :merge :handler])))
        (is (not (contains? built :handlers)))))
    (testing "a door the core does not have, and one with the core's own handler"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"the core has no such door"
           (kit/build a-core (assoc an-extension :handlers {:paint finish}))))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"the core's own handler"
           (kit/build (assoc-in a-core [:actions :merge :handler] finish)
                      (assoc an-extension :handlers {:merge finish})))))))

(deftest the-shipped-core-is-the-six-states-and-the-shared-fields
  (is (= [:open :submitted :failing :stuck :merged :closed] (:states kit/core)))
  (is (= #{:merged} (:terminal kit/core)))
  (is (= [[:change_id] [:pull_request]] (:unique kit/core)))
  (is (= [:change_id :repository :number :title :author :base_branch
          :head_branch :head_sha :draft :mergeable :files_changed
          :lines_added :lines_removed :touched_paths :labels :review_state
          :missing_checks :behind_base :base_compared_at
          :missing_checks_head :worktree_dirty :rounds :stall_reason
          :stalled_at :stalled_by :failing_checks :conflicts :rerun_head
          :rerun_note :landing_error :train_red_head :train_red
          :unadopted_since :adoption_note :url :born_from :pull_request]
         (mapv first (:fields kit/core))))
  (testing "and the source's eight doors (ticket 47e2c880), with the stall's four (ticket eb500c47), the PR-less ending's two (ticket 48f9605b) and the opening's one (ticket 20a962be)"
    (is (= #{:adopt :link :opened :observe :fail
             :recover :merge :close
             :stall :unstick :unstick_submitted :rebranch
             :close_without_pr :reopen}
           (set (keys (:actions kit/core)))))
    (is (= #{:fail :recover :merge :close :stall :unstick :unstick_submitted
             :close_without_pr :reopen}
           (set (remove #(contains? (get-in kit/core [:actions %]) :handler)
                        (keys (:actions kit/core))))))))

(def ^:private not-a-field
  "A door's input that is no field of the row: the stall's sentence,
  which `stalled` writes as `stall_reason`, and the PR-less close's,
  which rides the log."
  {:stall #{:why}
   :close_without_pr #{:why}})

(deftest the-core-alone-declares-every-field-its-doors-write
  ;; ticket 354a7dc3: a factory that composes the core with nothing of
  ;; its own gets no door that writes a field its schema does not have
  (let [built (kit/build kit/core {})
        declared (set (map first (rest (:schema built))))]
    (is (= (set (map first (:fields kit/core))) declared))
    (testing "every door's input field is a declared field"
      (doseq [[door {:keys [input]}] (:actions built)
              field (remove (get not-a-field door #{}) (map first (rest input)))]
        (is (contains? declared field)
            (str (name door) " takes " (name field) ", and the core declares no such field"))))
    (testing "and so is every field a core handler names itself"
      (is (every? declared [:head_sha :unadopted_since :adoption_note
                            :stall_reason :stalled_at :stalled_by])))))

(deftest a-stall-is-written-on-the-row-and-the-way-back-clears-it
  (let [row (kit/stalled {:data {:rounds 2}}
                         {:why "It cannot be built as written."}
                         {:now "2026-10-14T00:00:00Z" :principal {:id "seat-1"}})]
    (is (= {:rounds 2
            :stall_reason "It cannot be built as written."
            :stalled_at "2026-10-14T00:00:00Z"
            :stalled_by "seat-1"}
           (:data row)))
    (is (= {:rounds 2 :stall_reason nil :stalled_at nil :stalled_by nil}
           (:data (kit/unstalled row))))))

(deftest a-factory-that-names-no-stall-handler-still-stalls-with-the-reason
  (let [built (kit/build kit/core an-extension)
        ctx {:now "2026-10-14T00:00:00Z" :principal {:id "seat-1"}}
        stall (get-in built [:actions :stall :handler])
        row (stall {:data {:rounds 2}} {:why "It cannot be built as written."} ctx)]
    (testing "the stall writes the reason, the time and the hand on the row"
      (is (= {:rounds 2
              :stall_reason "It cannot be built as written."
              :stalled_at "2026-10-14T00:00:00Z"
              :stalled_by "seat-1"}
             (:data row))))
    (testing "each way back clears the sentence and starts the rounds again"
      (doseq [door [:unstick :unstick_submitted]]
        (is (= {:rounds 0 :stall_reason nil :stalled_at nil :stalled_by nil}
               (:data ((get-in built [:actions door :handler]) row {} ctx)))
            (str door))))
    (testing "the doors that move the ask alone stay the factory's to give"
      (is (= #{:merge :close :fail :recover}
             (set (remove #(contains? (get-in built [:actions %]) :handler)
                          (keys (:actions kit/core)))))))
    (testing "a handler the extension names stands in place of the kit's"
      (let [own (fn [row _inp _ctx] row)]
        (is (= own (get-in (kit/build kit/core (assoc an-extension :handlers {:stall own}))
                           [:actions :stall :handler])))
        (is (= kit/clear-the-stall
               (get-in (kit/build kit/core (assoc an-extension :handlers {:stall own}))
                       [:actions :unstick :handler])))))))

(deftest a-closed-change-is-no-tomb-in-the-core
  (is (not (contains? (:terminal kit/core) :closed)))
  (is (= #{:closed} (get-in kit/core [:actions :reopen :from])))
  (is (= :closed (get-in kit/core [:actions :close_without_pr :to])))
  (testing "a change with no pull request starts again, and one with a pull request stands"
    (is (= {:rounds 0 :stall_reason nil :stalled_at nil :stalled_by nil}
           (:data (kit/reopened {:data {:rounds 3 :stall_reason "It stopped."}}))))
    (is (= {:number 7 :rounds 3}
           (:data (kit/reopened {:data {:number 7 :rounds 3}}))))))

(deftest the-sweep-closes-through-close-without-pr-and-moves-nothing-else
  (let [walked (atom [])
        told (atom [])
        rows [{:id "ended" :data {}}
              {:id "live" :data {}}
              {:id "a-pull-request" :data {:number 7}}
              {:id "refused" :data {}}
              {:id "long" :data {}}]
        closed (kit/sweep!
                {:rows rows
                 :ended (fn [row]
                          (case (:id row)
                            "live" nil
                            "long" (apply str (repeat 600 "a"))
                            "its ticket ended"))
                 :close! (fn [row door input]
                           (when (= "refused" (:id row))
                             (throw (ex-info "the door refused" {})))
                           (swap! walked conj [(:id row) door input]))
                 :log-fn (fn [& parts] (swap! told conj (apply str parts)))})]
    (is (= 2 closed))
    (is (= ["ended" :close_without_pr {:why "its ticket ended"}] (first @walked)))
    (is (= ["long" :close_without_pr] (take 2 (second @walked))))
    (is (= kit/why-chars (count (:why (last (second @walked))))))
    (is (= ["the change refused was not swept (the door refused)"] @told)
        "a refused close costs that row this sweep and nothing else")))
