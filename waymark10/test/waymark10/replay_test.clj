(ns waymark10.replay-test
  "History reconstruction (docs/spec-amendment.md §14 step 3): a
  retained kind reads its past field, an unretained one names
  `current_data`, a window older than the log names `before_record`,
  and the cap cuts and says where."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.replay :as replay]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory])
  (:import (java.time Duration Instant)))

(def ^:private until (Instant/parse "2026-11-01T00:00:00Z"))

(defn- days-ago ^Instant [n] (.minus ^Instant until (Duration/ofDays n)))

(def ^:private plan {:kind :plan :plural "plans" :retain {:data true}})
(def ^:private note {:kind :note :plural "notes"})

(defn- fresh [& rdefs]
  (let [st (memory/storage)]
    (doseq [r rdefs] (store/ensure-kind! st r))
    st))

(defn- put-row! [st kind id state data]
  (store/with-tx st #(store/insert-row! st % kind {:id id :state state :data data})))

(defn- log!
  "Append one move and pin its time, as the stores stamp their own."
  [st kind id from to at after]
  (let [rec (store/with-tx st
              #(store/append-transition!
                st % (cond-> {:kind kind :resource-id id :action :move
                              :from-state from :to-state to
                              :actor {:id "p" :type "person"}}
                       after (assoc :after after))))]
    (swap! (:state st) update :transitions
           (fn [log] (mapv #(if (= (:id rec) (:id %)) (assoc % :at at) %) log)))))

(defn- token [h t] (some #(when (= t (:token %)) %) (:blind h)))

(deftest the-window-is-fourteen-days-unless-named
  (is (= (days-ago 14) (:from (replay/window until))))
  (is (= (days-ago 3) (:from (replay/window until 3))))
  (is (thrown? clojure.lang.ExceptionInfo (replay/window until 31)))
  (is (thrown? clojure.lang.ExceptionInfo (replay/window until 0))))

(deftest a-retained-kind-reads-its-past-field
  (let [st (fresh plan)]
    (put-row! st :plan "p1" :done {:title "today's"})
    (log! st :plan "p1" nil :draft (days-ago 20) {:title "born"})
    (log! st :plan "p1" :draft :planned (days-ago 5) {:title "then"})
    (log! st :plan "p1" :planned :done (days-ago 1) {:title "today's"})
    (let [h (replay/history st [plan] (replay/window until))
          row (get-in h [:rows ["plan" "p1"]])]
      (is (= ["then" "today's"] (mapv (comp :title :data) (:timeline row))))
      (is (= "draft" (:opening row)))
      (is (= "planned" (replay/state-at row (days-ago 3))))
      (is (= 2 (:rows_read h)))
      (is (nil? (token h "current_data:plan")))
      (is (nil? (token h "before_record:plan")))
      (is (nil? (token h "capped"))))))

(deftest an-unretained-kind-names-current-data
  (let [st (fresh note)]
    (put-row! st :note "n1" :closed {:title "now"})
    (log! st :note "n1" nil :open (days-ago 20) nil)
    (log! st :note "n1" :open :closed (days-ago 2) nil)
    (let [h (replay/history st [note] (replay/window until))]
      (is (= ["now"] (mapv (comp :title :data)
                           (get-in h [:rows ["note" "n1"] :timeline]))))
      (is (= 1 (:moments (token h "current_data:note")))))))

(deftest a-window-older-than-the-log-names-before-record
  (let [st (fresh note)]
    (put-row! st :note "n1" :open {:title "x"})
    (log! st :note "n1" nil :open (days-ago 4) nil)
    (let [h (replay/history st [note] (replay/window until))]
      (is (= (days-ago 4) (:at (token h "before_record:note")))))))

(deftest the-cap-cuts-and-says-where
  (let [st (fresh plan note)]
    (put-row! st :plan "p1" :s5 {:title "t"})
    (put-row! st :note "n1" :s1 {:title "t"})
    (log! st :note "n1" nil :s1 (days-ago 30) nil)
    (log! st :note "n1" :s1 :s2 (days-ago 2) nil)
    (doseq [i (range 1 6)]
      (log! st :plan "p1" (keyword (str "s" (dec i))) (keyword (str "s" i))
            (days-ago (- 12 i)) {:title (str "v" i)}))
    (testing "three of five plan moves fit; the read stops at the third newest"
      (let [h (replay/history st [plan note] (replay/window until) 3)
            capped (token h "capped")]
        (is (= {:token "capped" :moments 1 :at (days-ago 9) :unread ["note"]} capped))
        (is (= 3 (:rows_read h)))
        (is (= ["v3" "v4" "v5"]
               (mapv (comp :title :data) (get-in h [:rows ["plan" "p1"] :timeline]))))
        (testing "a kind the cap left unread is cut, not reported empty-as-whole"
          (is (nil? (get-in h [:rows ["note" "n1"]]))))))
    (testing "under a cap that holds the window, nothing is cut"
      (let [h (replay/history st [plan note] (replay/window until))]
        (is (nil? (token h "capped")))
        (is (= 5 (count (get-in h [:rows ["plan" "p1"] :timeline]))))
        (is (= 1 (count (get-in h [:rows ["note" "n1"] :timeline]))))))))
