(ns waymark10.server.seats.fire-test
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.seats :as seats]
            [waymark10.server.seats.fire :as fire]))

(def ^:private walker {:data {:walk "ticket"}})

(def ^:private ticket-id "bb84d8d9-fa62-4dbb-9c60-be406b49478b")

(deftest a-wake-text-names-the-row-of-the-kind-the-seat-walks
  (is (= ticket-id
         (fire/named-row walker (str "{\"kind\":\"ticket\",\"id\":\"" ticket-id "\"}"))))
  (testing "a change counts for a seat that walks tickets"
    (is (= ticket-id
           (fire/named-row walker (str "{\"kind\":\"change\",\"id\":\"" ticket-id "\"}")))))
  (testing "a row of another kind names nothing"
    (is (nil? (fire/named-row walker (str "{\"kind\":\"seat\",\"id\":\"" ticket-id "\"}"))))))

(deftest prose-names-its-first-whole-id
  (is (= ticket-id (fire/named-row walker (str "Walk " ticket-id ", then stop."))))
  (testing "with no engine to look it up, a short id names nothing"
    (is (nil? (fire/named-row walker "Walk bb84d8d9, then stop."))))
  (testing "a seat that walks nothing, or no text, names nothing"
    (is (nil? (fire/named-row {:data {}} ticket-id)))
    (is (nil? (fire/named-row walker nil)))))

(deftest seats-still-answers-for-every-moved-var
  (is (identical? fire/hold-fire-key! seats/hold-fire-key!))
  (is (identical? fire/fire-key-held? seats/fire-key-held?))
  (is (identical? fire/fire-key-row seats/fire-key-row))
  (is (identical? fire/spend-fire-key! seats/spend-fire-key!))
  (is (identical? fire/queue-fire-key! seats/queue-fire-key!))
  (is (identical? fire/take-fire-key! seats/take-fire-key!))
  (is (identical? fire/revoke-fire-key! seats/revoke-fire-key!))
  (is (identical? fire/keep-fire-key! seats/keep-fire-key!))
  (is (= fire/seats-actor seats/seats-actor))
  (is (= 600 seats/sit-deadline-seconds))
  (is (= 3600 seats/default-idle-seconds)))
