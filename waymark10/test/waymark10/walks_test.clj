(ns waymark10.walks-test
  "The walk and walk_frame kinds (docs/spec-guided-follow.md § 4): a
  recording holds only what its recorder saw, a sealed walk takes no
  frames, and the retention sweep purges the frames and keeps the row.
  Memory storage and the real engine."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.walks :as walks]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private chore
  "A row a frame can be about."
  (r/resource
   {:kind :chore
    :plural "chores"
    :states [:open]
    :initial :open
    :terminal #{:open}
    :summary "{data.title} · {state}"
    :schema [:map [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}}}))

(def ^:private person (t/principal {:id "colton" :display "Colton"}))
(def ^:private other (t/principal {:id "iris" :display "Iris"}))

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage) :resources [chore]}))

(defn- row-of [eng k id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) k)]
    (store/with-tx st
      (fn [tx]
        (some->> (store/load-row st tx k (str id) {})
                 (inv/decode-row rdef))))))

(defn- frames-of [eng walk-id]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (vec (store/query-rows st tx :walk_frame {:walk (str walk-id)}
                               {:limit 100}))))))

(defn- chore! [eng title]
  (:id (:row (inv/create! eng :chore {:title title} {:principal person}))))

(defn- walk!
  ([eng] (walk! eng {}))
  ([eng extra]
   (:row (inv/create! eng :walk
                      (merge {:followed "planner" :title "Filing a ticket"} extra)
                      {:principal person}))))

(defn- sight-of
  "The recorder's visibility: it sees one chore and no whole kind."
  [visible]
  {:row? (fn [_kind id] (= (str id) (str visible)))
   :whole-kind? (fn [_kind] false)})

(defn- refusal [f]
  (try (f) nil
       (catch Exception e (or (inv/problem-reason e) "refused"))))

(deftest a-walk-records-only-what-its-recorder-saw
  (let [eng (fresh-engine)
        seen (chore! eng "Dishes")
        hidden (chore! eng "Laundry")
        w (walk! eng)
        sight (sight-of seen)]
    (is (= "recording" (name (:state w))))
    (is (= "colton" (get-in w [:data :recorder]))
        "the engine stamps the recorder from the principal")
    (is (= 30 (get-in w [:data :retention_days])))
    (testing "a frame about a row the recorder sees is written"
      (is (some? (walks/record-frame! eng (:id w) sight
                                      {:type "move" :body {:self (str "/api/chores/" seen)}}))))
    (testing "a frame about a row the recorder cannot see is not"
      (is (nil? (walks/record-frame! eng (:id w) sight
                                     {:type "move" :body {:self (str "/api/chores/" hidden)}})))
      (is (nil? (walks/record-frame! eng (:id w) sight
                                     {:type "move" :body {:self "/api/chores"}}))
          "nor the collection without whole-kind sight"))
    (testing "headers, grant ids, keys and credentials never reach a frame"
      (let [f (walks/record-frame! eng (:id w) sight
                                   {:type "ui"
                                    :body {:self (str "/api/chores/" seen)
                                           :dialog "rename"
                                           :headers {:authorization "Bearer x"}
                                           :grant_id "g-1"
                                           :ui {:key "k-1" :field "title"}}})]
        (is (= {:self (str "/api/chores/" seen) :dialog "rename" :ui {:field "title"}}
               (get-in f [:data :body])))))
    (testing "a type the stream does not carry is not written"
      (is (nil? (walks/record-frame! eng (:id w) sight {:type "gossip" :body {}}))))
    (is (= 2 (get-in (row-of eng :walk (:id w)) [:data :frame_count])))
    (is (= 2 (count (frames-of eng (:id w)))))))

(deftest a-sealed-walk-takes-no-frames
  (let [eng (fresh-engine)
        w (walk! eng)]
    (is (some? (walks/record-frame! eng (:id w) nil {:type "ui" :body {:dialog "open"}})))
    (testing "only the recorder seals it"
      (is (some? (refusal #(inv/invoke! eng :walk (str (:id w)) :seal {}
                                        {:principal other})))))
    (inv/invoke! eng :walk (str (:id w)) :seal {} {:principal person})
    (let [sealed (row-of eng :walk (:id w))]
      (is (= "sealed" (name (:state sealed))))
      (is (some? (get-in sealed [:data :ended_at]))))
    (is (nil? (walks/record-frame! eng (:id w) nil {:type "ui" :body {:dialog "close"}})))
    (is (= 1 (get-in (row-of eng :walk (:id w)) [:data :frame_count])))
    (is (= 1 (count (frames-of eng (:id w)))))))

(deftest retention-purges-frames-and-keeps-the-row
  (let [eng (fresh-engine)
        short-lived (walk! eng {:retention_days 1})
        long-lived (walk! eng {:retention_days 30})]
    (doseq [w [short-lived long-lived]]
      (walks/record-frame! eng (:id w) nil {:type "move" :body {}})
      (inv/invoke! eng :walk (str (:id w)) :seal {} {:principal person}))
    (testing "inside its days nothing moves"
      (is (= 0 (walks/sweep! eng))))
    (let [later (.plusSeconds (Instant/now) (* 2 86400))
          eng' (assoc eng :now-fn (constantly later))]
      (is (= 1 (walks/sweep! eng')))
      (let [row (row-of eng :walk (:id short-lived))]
        (is (= "purged" (name (:state row))))
        (is (= 1 (get-in row [:data :frame_count])) "the row keeps its counts")
        (is (= "Filing a ticket" (get-in row [:data :title]))))
      (is (empty? (frames-of eng (:id short-lived))))
      (is (= "sealed" (name (:state (row-of eng :walk (:id long-lived))))))
      (is (= 1 (count (frames-of eng (:id long-lived))))))))