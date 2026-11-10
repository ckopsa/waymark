(ns waymark10.server.seats.keys-test
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.seats :as seats]
            [waymark10.server.seats.keys :as seat-keys])
  (:import (java.time Instant)))

(deftest a-fire-text-names-its-seat-and-the-hash-of-its-key
  (let [text "Do the work.\nSeat: 02eee915 (code-seat).\nKey: oY-_lWij\n"]
    (is (= {:seat "02eee915" :hash (seat-keys/key-hash "oY-_lWij")}
           (seat-keys/fire-key-of-text text)))
    (testing "a text with no key names nothing"
      (is (nil? (seat-keys/fire-key-of-text "Seat: 02eee915 (code-seat).")))
      (is (nil? (seat-keys/fire-key-of-text nil))))))

(deftest a-blank-key-hashes-to-nothing-and-matches-nothing
  (is (nil? (seat-keys/key-hash nil)))
  (is (not (seat-keys/key-matches? {:data {:sitter_key_hash nil}} nil)))
  (is (seat-keys/key-matches? {:data {:sitter_key_hash (seat-keys/key-hash "k")}} "k")))

(deftest a-live-entry-answers-until-its-moment
  (let [now (Instant/parse "2026-11-10T05:00:00Z")
        row {:data {:fire_keys [{:hash (seat-keys/key-hash "old")
                                 :expires_at "2026-11-10T04:00:00Z"}
                                {:hash (seat-keys/key-hash "new")
                                 :expires_at "2026-11-10T06:00:00Z"}
                                {:hash (seat-keys/key-hash "undated")}]}}]
    (is (= 1 (count (seat-keys/live-keys row now))))
    (is (some? (seat-keys/fire-key-entry row "new" now)))
    (is (nil? (seat-keys/fire-key-entry row "old" now)))
    (is (nil? (seat-keys/fire-key-entry row "undated" now)))))

(deftest the-seats-aliases-still-resolve
  (is (= (seat-keys/key-hash "k") (seats/key-hash "k")))
  (is (= (seat-keys/fire-key-of-text "Seat: s x\nKey: k")
         (seats/fire-key-of-text "Seat: s x\nKey: k"))))
