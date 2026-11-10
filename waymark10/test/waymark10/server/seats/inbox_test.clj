(ns waymark10.server.seats.inbox-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.seats :as seats]
            [waymark10.server.seats.inbox :as inbox]))

(deftest an-interactive-seat-that-states-no-inbox-hears-the-default
  (is (= inbox/default-inbox (inbox/inbox-of {:data {:mode "interactive"}})))
  (testing "a fired seat has an inbox only when it states one"
    (is (nil? (inbox/inbox-of {:data {}}))))
  (testing "held_call: own hears held_call whatever `only` names"
    (is (= [] (get-in (inbox/inbox-of {:data {:inbox {:only {:ticket []}
                                                       :held_call "own"}}})
                      [:only :held_call])))))

(deftest the-inbox-url-sits-beside-the-origin
  (is (= "https://x.test/api/-/sittings/inbox" (inbox/inbox-url "https://x.test/"))))

(deftest the-place-names-the-key-and-the-seat
  (let [md (inbox/place-claude-md "mayor" "s-1" "k-1")]
    (is (str/includes? md "key:  k-1"))
    (is (str/includes? md "seat: mayor"))))

(deftest a-lease-is-live-while-open-and-ahead
  (let [now (java.time.Instant/parse "2026-11-10T00:00:00Z")]
    (is (inbox/lease-live? {:state :open :data {:expires_at "2026-11-10T01:00:00Z"}} now))
    (is (not (inbox/lease-live? {:state :open :data {:expires_at "2026-11-09T23:00:00Z"}} now)))
    (is (not (inbox/lease-live? {:state :closed :data {}} now)))))

(deftest seats-still-answers-for-every-moved-var
  (is (identical? inbox/interactive-seat? seats/interactive-seat?))
  (is (identical? inbox/lease-live? seats/lease-live?))
  (is (identical? inbox/lease-seconds seats/lease-seconds))
  (is (identical? inbox/inbox-of seats/inbox-of))
  (is (identical? inbox/cue-note seats/cue-note))
  (is (identical? inbox/issue-inbox-key! seats/issue-inbox-key!))
  (is (identical? inbox/inbox-sitting-by-key seats/inbox-sitting-by-key))
  (is (identical? inbox/keep-inbox-lease! seats/keep-inbox-lease!))
  (is (identical? inbox/place-tar seats/place-tar))
  (is (identical? inbox/issue-feed-token! seats/issue-feed-token!))
  (is (identical? inbox/feed-seat-by-token seats/feed-seat-by-token))
  (is (identical? inbox/renew-feed-token! seats/renew-feed-token!))
  (is (= inbox/place-origin seats/place-origin))
  (is (= inbox/inbox-how seats/inbox-how)))
