(ns waymark10.listen-test
  "The one listener predicate (docs/spec-events.md §2, E-1.1 to E-1.8):
  every old shape, read into an entry, judged against the same
  transitions; an empty list and an absent field as one meaning; a
  filter read on the row after the commit, moved into and out of; a
  row that is gone; domains, with and without service alerts; the one
  read; and the write-time filter check."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.audience :as audience]
            [waymark10.server.invoke :as inv]
            [waymark10.server.listen :as listen]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as types]))

(def crate
  (r/resource
   {:kind :crate
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:max 80}]]
             [:domain {:optional true} [:maybe [:string {:max 40}]]]
             [:bin {:optional true} [:maybe [:string {:max 40}]]]]
    :filterable {:state #{:eq :in}
                 :domain #{:eq :in}
                 :bin #{:eq :in}}
    :faceted [:domain]
    :absent-as {:domain "factory"}
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A finished crate is history."}}}}))

(def ^:private colton (types/principal {:id "colton" :display "Colton"}))
(def ^:private opts {:principal colton})

(defn- world [] (inv/engine {:storage (memory/storage) :resources [crate]}))

(defn- crate! [eng data]
  (get-in (inv/create! eng :crate data opts) [:row :id]))

(defn- moved
  "A committed transition, as the log hands it to a listener."
  [kind id action from to]
  {:kind kind :resource-id id :action action :from-state from :to-state to
   :actor {:id "colton" :type :person}})

(defn- hears? [eng entries t]
  (boolean (some #(listen/matches? eng % t) entries)))

(deftest every-old-shape-reads-through-one-predicate
  (let [eng (world)
        a (crate! eng {:title "a"})
        _ (inv/invoke! eng :crate a :finish {} opts)
        finish (moved :crate a :finish :open :done)
        create (moved :crate a :create nil :open)
        other (moved :errand (random-uuid) :finish :open :done)
        table
        [["wake_on" [(listen/from-wake-on {:kind "crate" :actions ["finish"]})]
          true false false]
         ["notifier on, empty actions"
          [(listen/from-notifier-on {:kind "crate" :actions []})]
          true true false]
         ["notice rule, blank fields left out"
          [(listen/from-notice-rule {:kind "crate"
                                     :when {:action "" :from_state "open"
                                            :to_state nil}})]
          true false false]
         ["subscription"
          [(listen/from-subscription {:kinds [] :skip_actors ["someone"]
                                      :domains ["factory"]})]
          true true true]
         ["inbox only, one entry for each key"
          (listen/from-inbox-only {:crate ["create"] :errand []})
          false true true]
         ["cue with a filter"
          [(listen/from-cue {:kind "crate" :actions ["finish"]
                             :filter {:state "done"}})]
          true false false]]]
    (doseq [[shape entries on-finish on-create on-other] table]
      (testing shape
        (is (= on-finish (hears? eng entries finish)))
        (is (= on-create (hears? eng entries create)))
        (is (= on-other (hears? eng entries other)))))
    (testing "the readers write the spec's entries"
      (is (= {:kinds ["crate"] :actions ["finish"] :filter [{:state "done"}]}
             (listen/from-cue {:kind "crate" :actions ["finish"]
                               :filter {:state "done"}})))
      (is (= {:kinds ["crate"] :from ["open"]}
             (listen/from-notice-rule {:kind "crate"
                                       :when {:action "" :from_state "open"}})))
      (is (= [{:kinds ["crate"] :actions ["create"]} {:kinds ["errand"]}]
             (listen/from-inbox-only {:crate ["create"] :errand []}))))))

(deftest an-empty-list-and-an-absent-field-are-one-meaning
  (let [eng (world)
        a (crate! eng {:title "a"})
        t (moved :crate a :finish :open :done)]
    (doseq [entry [{} {:kinds [] :actions [] :from [] :to [] :ids []
                       :domains [] :skip_actors [] :filter []}
                   {:kinds nil :actions nil :filter {}}]]
      (is (true? (listen/matches? eng entry t)) (pr-str entry)))
    (testing "a stated field narrows, OR inside the list, AND across"
      (is (true? (listen/matches? eng {:actions ["shelve" "finish"]} t)))
      (is (false? (listen/matches? eng {:actions ["shelve"]} t)))
      (is (true? (listen/matches? eng {:from ["open"] :to ["done"]} t)))
      (is (false? (listen/matches? eng {:from ["open"] :to ["open"]} t)))
      (is (true? (listen/matches? eng {:ids [(str a)]} t)))
      (is (false? (listen/matches? eng {:ids [(str (random-uuid))]} t)))
      (is (false? (listen/matches? eng {:skip_actors ["colton"]} t))))))

(deftest the-filter-reads-the-row-after-the-commit
  (let [eng (world)
        a (crate! eng {:title "a" :bin "north"})
        _ (inv/invoke! eng :crate a :finish {} opts)
        t (moved :crate a :finish :open :done)]
    (testing "a row that moved into the filter matches; one that moved out does not"
      (is (true? (listen/matches? eng {:filter [{:state "done"}]} t)))
      (is (false? (listen/matches? eng {:filter [{:state "open"}]} t))))
    (testing "a comma value on an :in field is any of; filter maps are OR"
      (is (true? (listen/matches? eng {:filter [{:bin "south,north"}]} t)))
      (is (true? (listen/matches? eng {:filter [{:bin "south"} {:state "done"}]} t)))
      (is (false? (listen/matches? eng {:filter [{:bin "south" :state "done"}]} t))))
    (testing "a filter reads the kind's :absent-as, as its query does"
      (is (true? (listen/matches? eng {:filter [{:domain "factory"}]} t)))
      (is (false? (listen/matches? eng {:filter [{:domain "home"}]} t))))
    (testing "a row that is gone matches no filter, and an unfiltered entry still hears it"
      (let [gone (moved :crate (random-uuid) :finish :open :done)]
        (is (false? (listen/matches? eng {:filter [{:state "done"}]} gone)))
        (is (true? (listen/matches? eng {:kinds ["crate"]} gone)))))
    (testing "a pull reader passes the row as it stands now"
      (is (false? (listen/matches? eng {:filter [{:state "done"}]} t
                                   {:state :open :data {:title "a"}})))
      (is (false? (listen/matches? eng {:filter [{:state "done"}]} t nil))))))

(deftest domains-and-service-alerts
  (let [eng (world)
        a (crate! eng {:title "a"})
        b (crate! eng {:title "b" :domain "home"})
        ta (moved :crate a :finish :open :done)
        tb (moved :crate b :finish :open :done)
        stall (moved :crate b :stall :open :open)]
    (testing "an absent domain counts as the declared one"
      (is (true? (listen/matches? eng {:domains ["factory"]} ta)))
      (is (false? (listen/matches? eng {:domains ["factory"]} tb)))
      (is (true? (listen/matches? eng {:domains ["home" "factory"]} tb))))
    (with-redefs [audience/service-alert?
                  (fn [_ domains t]
                    (and (= #{"factory"} domains) (= :stall (:action t))))]
      (testing "service_alerts lets a service seat's alert through its domains"
        (is (true? (listen/matches? eng {:domains ["factory"] :service_alerts true}
                                    stall)))
        (is (false? (listen/matches? eng {:domains ["factory"]} stall)))
        (is (false? (listen/matches? eng {:domains ["factory"] :service_alerts true}
                                     tb)))))))

(deftest the-row-is-read-at-most-once-and-only-when-needed
  (let [eng (world)
        a (crate! eng {:title "a"})
        t (moved :crate a :finish :open :done)
        reads (atom 0)
        with-tx store/with-tx]
    ;; a record's protocol method is called past its var, so the read
    ;; is counted at the transaction it opens
    (with-redefs [store/with-tx (fn [st f] (swap! reads inc) (with-tx st f))]
      (listen/matches? eng {:kinds ["crate"] :actions ["finish"]} t)
      (is (= 0 @reads))
      (listen/matches? eng {:domains ["factory"] :filter [{:state "open"}]} t)
      (is (= 1 @reads))
      (listen/matches? eng {:domains ["factory"] :filter [{:state "open"}]} t
                       {:state :open :data {}})
      (is (= 1 @reads)))))

(deftest the-write-time-filter-check
  (let [eng (world)]
    (is (nil? (listen/filter-problems eng :crate {:bin "north" :state "done"})))
    (is (nil? (listen/filter-problems eng "crate" [])))
    (is (= ["colour"] (keys (listen/filter-problems eng :crate [{:colour "red"}]))))
    (is (contains? (listen/filter-problems eng :nope {:bin "x"}) "kind"))))
