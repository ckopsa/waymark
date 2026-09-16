(ns workqueue10.inbox-source-test
  "The inbox header source (docs/spec-seat.md § 13.8, 'The queue fills
  with no tokens'): the translation, the list-unsubscribe filter, the
  dedupe that makes a second pass free — and the line the whole seat
  rests on, that no body ever reaches a document.

  No database and no network: the fake stands behind the GATE CALLER
  (gate-chat's twin, the thread sources' pattern), so every assertion
  runs the real listing read, the real structured/parts fallback, the
  real filter and the real translation, and only the socket is
  missing. The engine is injected as `mint!`, so the four fields are
  checked here rather than at a create door."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [workqueue10.sources.gate-chat :as gc]
            [workqueue10.sources.inbox :as inbox]))

;; ── the listing, in four plausible spellings ────────────────────────
;;
;; emila's listing shape is pinned nowhere in this repo (Gate is
;; external), so these entries deliberately DISAGREE about how to
;; spell an id, a sender and a time — the translation's whole job.

(def ^:private body-text
  "The one string that must never appear in a document, in any coat.")

(def ^:private school
  {:id "m1"
   :subject "Registration closes Friday"
   :from {:name "Ada Park" :email "ada@school.org"}
   :date "2026-09-15T14:02:11Z"
   :body body-text})

(def ^:private newsletter
  {:message_id "m2"
   :subject "This week's deals, just for you"
   :sender "deals@shop.example"
   :received_at "2026-09-15 09:00:00+00:00"
   :headers {:List-Unsubscribe "<mailto:unsubscribe@shop.example>"}
   :snippet body-text})

(def ^:private already-known
  {:id "m3"
   :subject "Re: the fence"
   :from "Kevin Kopsa <kevin@example.com>"
   :date "Tue, 15 Sep 2026 08:02:11 -0600"
   :body body-text})

(def ^:private dentist
  {:uid "m4"
   :title "Appointment reminder"
   :from_address "front.desk@dentist.example"
   :timestamp 1789574531
   :preview body-text})

(def ^:private listing [school newsletter already-known dentist])

(defn- minting-source
  "The real source over a scripted Gate, with a recording mint! and
  m3 already in the house. → [src docs-atom]."
  [& {:keys [rows] :or {rows listing}}]
  (let [state (gc/fake-state)
        docs (atom [])]
    (gc/answer! state inbox/default-tool rows)
    [(inbox/fake-source state
                        {:mint! (fn [doc] (swap! docs conj doc) {:id "row"})
                         :known-ids #{"m3"}
                         :log-fn (constantly nil)
                         :now-fn (fn [] (java.time.Instant/parse
                                         "2026-09-16T12:00:00Z"))})
     docs
     state]))

(deftest one-row-per-new-message
  (testing "N listed, one broadcast, one already queued → N-2 minted"
    (let [[src docs] (minting-source)
          report (inbox/pass! src)]
      (is (= 4 (:listed report)))
      (is (= 2 (:minted report)))
      (is (= 1 (:unsubscribed report))
          "the list-unsubscribe header keeps a newsletter out of the queue")
      (is (= 1 (:known report))
          "a message id the house already holds is never minted twice")
      (is (= 2 (count @docs)))
      (is (= ["m1" "m4"] (mapv :message_id @docs)))))

  (testing "a document is the four fields and nothing else"
    (let [[src docs] (minting-source)]
      (inbox/pass! src)
      (doseq [doc @docs]
        (is (= #{:message_id :subject :sender :received_at} (set (keys doc)))
            "headers only — a fifth field is a body in a shorter coat"))))

  (testing "the body never appears in any document, in any coat"
    (let [[src docs] (minting-source)]
      (inbox/pass! src)
      (is (not (str/includes? (pr-str @docs) body-text)))))

  (testing "the translation reads each rig spelling it plausibly could"
    (let [[src docs] (minting-source)
          _ (inbox/pass! src)
          by-id (into {} (map (juxt :message_id identity)) @docs)]
      (is (= {:message_id "m1"
              :subject "Registration closes Friday"
              :sender "Ada Park <ada@school.org>"
              :received_at "2026-09-15T14:02:11Z"}
             (get by-id "m1")))
      (is (= {:message_id "m4"
              :subject "Appointment reminder"
              :sender "front.desk@dentist.example"
              :received_at "2026-09-16T16:02:11Z"}
             (get by-id "m4"))
          "an epoch-second stamp and a :uid/:title spelling still land")))

  (testing "a second pass over the same listing mints nothing"
    (let [[src docs] (minting-source)
          _ (inbox/pass! src)
          second-pass (inbox/pass! src)]
      (is (= 0 (:minted second-pass)))
      (is (= 3 (:known second-pass))
          "the two it minted, and the one it was told about")
      (is (= 2 (count @docs)) "mint! was called twice, in all"))))

(deftest what-never-mints
  (testing "a message with no id anywhere is counted, not minted"
    (let [[src docs] (minting-source :rows [{:subject "Anonymous"
                                             :from "nobody@example.com"}])
          report (inbox/pass! src)]
      (is (= 1 (:unreadable report)))
      (is (= 0 (:minted report)))
      (is (empty? @docs))))

  (testing "list-unsubscribe is read wherever the rig puts it"
    (is (inbox/list-unsubscribe?
         {:headers {:List-Unsubscribe "<mailto:x@y>"}}))
    (is (inbox/list-unsubscribe? {:list_unsubscribe "<https://y/u>"}))
    (is (inbox/list-unsubscribe? {:list_unsubscribe true}))
    (is (inbox/list-unsubscribe?
         {:headers [{:name "List-Unsubscribe" :value "<mailto:x@y>"}]}))
    (is (inbox/list-unsubscribe?
         {:headers ["List-Unsubscribe: <mailto:x@y>"]}))
    (is (not (inbox/list-unsubscribe? {:headers {:Subject "hello"}})))
    (is (not (inbox/list-unsubscribe? {:list_unsubscribe false}))
        "a header the rig mentioned and did not carry is no header")
    (is (not (inbox/list-unsubscribe? {:subject "unsubscribe me"}))
        "the word in a subject is not the header"))

  (testing "a row the engine refuses is counted and the pass goes on"
    (let [state (gc/fake-state)
          minted (atom [])
          _ (gc/answer! state inbox/default-tool listing)
          src (inbox/fake-source
               state {:mint! (fn [doc]
                               (if (= "m1" (:message_id doc))
                                 (throw (ex-info "refused" {}))
                                 (swap! minted conj doc)))
                      :log-fn (constantly nil)})
          report (inbox/pass! src)]
      (is (= 1 (:refused report)))
      (is (= 2 (:minted report)) "m3 and m4 — the refusal stopped nothing")
      (is (= 0 (:minted (inbox/pass! src)))
          "and the refused message is offered again next pass")
      (is (= 1 (:refused (inbox/pass! src)))))))

(deftest the-wire
  (testing "one call a pass, carrying the window and Gate's own why"
    (let [[src _ state] (minting-source)]
      (inbox/pass! src)
      (let [[call :as calls] (gc/calls state)]
        (is (= 1 (count calls)) "one Gate tool call per pass")
        (is (= inbox/default-tool (:tool call)))
        (is (= inbox/default-limit (get-in call [:arguments :limit])))
        (is (str/includes? (str (get-in call [:arguments :__why])) "never a body")))))

  (testing "a rig that answers without structuredContent still reads"
    (let [[src docs state] (minting-source)]
      (gc/parts-only! state)
      (inbox/pass! src)
      (is (= 2 (count @docs)))))

  (testing "a pass that cannot reach Gate throws, and mints nothing"
    (let [[src docs state] (minting-source)]
      (gc/down! state true)
      (is (thrown? Exception (inbox/pass! src)))
      (is (empty? @docs))
      (gc/down! state false)
      (is (= 2 (:minted (inbox/pass! src)))
          "and the next beat fills the queue"))))
