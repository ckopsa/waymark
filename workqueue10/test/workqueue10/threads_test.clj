(ns workqueue10.threads-test
  "The :thread mirror, whole: the two rigs' translations (and what
  they REFUSE to carry), the bot filter, the group-title split, the
  full-listing batch that turns absence into the :on-gone
  observation, the roster births a participant name mints — and then
  one end-to-end pass over the ring handler proving the three things
  a unit test cannot: that a stored row carries no body field, that a
  known participant resolves to the person row, and that an unknown
  one lands `observed`.

  The unit half needs no database and no network: the fake stands
  behind the GATE CALLER (flickr's twin, one layer up), so every
  assertion runs the real listing read, the real structured/parts
  fallback, the real filters and the real translation, and only the
  socket is missing. Its rows are the shapes verified live on
  2026-08-28, byte for byte (docs/spec-threads.md § The wire).

  The end-to-end half needs the waymark10_test database;
  WAYMARK10_TEST_DSN overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [calendar10.source :as gcal]
            [next.jdbc :as jdbc]
            [workqueue10.confluence :as conf]
            [workqueue10.main :as main]
            [workqueue10.sources.gate-chat :as gc]
            [workqueue10.sources.hub :as hub]
            [workqueue10.sources.messa :as messa]
            [workqueue10.sources.tgram :as tgram]
            [workqueue10.sources.tgrambot :as tgrambot]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

;; ── the verified live shapes ────────────────────────────────────────

(def ^:private wellesley
  {:id 5061625694 :title "Wellesley Kopsa" :type "user" :username nil
   :last_message_date "2026-08-28 03:54:19+00:00" :unread_count 0
   :last_message_preview "I see"})

(def ^:private bros
  {:id -550048080 :title "Bros. 🧠" :type "group" :username nil
   :last_message_date "2026-08-25 18:47:31+00:00" :unread_count 3
   :last_message_preview "When I saw that he was out of bed and dressed"})

(def ^:private tote-bot
  {:id 8123098776 :title "ToteClaw" :type "user" :username "ToteClawBot"
   :last_message_date "2026-08-04 05:52:51+00:00" :unread_count 0
   :last_message_preview "✅ APPROVED"})

(def ^:private kathy
  {:name "Kathy Peppas" :snippet "Picture" :time "9:05 AM"
   :hash "749450b3" :last_message_at "2026-08-28T09:05:00-06:00"})

(def ^:private shumways
  {:name (str "Amy Shumway, Calista Shumway, Wellesley Kopsa, "
              "(304) 482-6884")
   :snippet "Celestia: Picture" :time "Yesterday" :hash "deb10be2"
   ;; a date-only label: the rig resolves it to midday, because
   ;; "Yesterday" knows the day and not the hour
   :last_message_at "2026-08-27T12:00:00-06:00"})

;; the label the rig could not read: it answers null rather than a
;; guess, and this row is what proves nil still means nil
(def ^:private walmart
  {:name "41646" :snippet "We're sorry, please return the item"
   :time "" :hash "6d7a4fd2" :last_message_at nil})

;; ── the bot rig's shapes (waymark-fp62.18.2) ────────────────────────
;;
;; The house's own bot lists the chats it has heard from, and for each
;; one the time of the last message that named it. The chat_id is
;; Telegram's own, so it is the SAME id the account rig answers for
;; the same chat.

(def ^:private bot-bros
  {:chat_id -550048080 :title "Bros. 🧠" :type "group"
   :last_message_at "2026-08-25T18:47:31+00:00"
   :last_mention_at "2026-08-25T18:40:00+00:00" :mentions 2})

(def ^:private bot-meals
  "A chat the bot hears and the house's account does not list: the
  row the bot alone owns."
  {:chat_id -5091757250 :title "Meal plans" :type "supergroup"
   :last_message_at "2026-09-20T18:02:11+00:00"
   :last_mention_at "2026-09-20T17:58:40+00:00" :mentions 4})

(def ^:private bot-wellesley
  {:chat_id 5061625694 :title "Wellesley Kopsa" :type "private"
   :last_message_at "2026-08-28T03:54:19+00:00"
   :last_mention_at nil :mentions 0})

;; the words that must never appear in a stored document, whatever
;; shape the translation grows into
(def ^:private forbidden-fields
  [:last_message_preview :snippet :preview :text :body :unread_count
   :username :time
   ;; the bot's `mentions` is a COUNT of what its rig has heard since
   ;; it started, so it moves when the rig restarts and says nothing
   ;; the mention's own time does not say better
   :mentions])

(defn- carries-no-body?
  "One canonical document, judged against the kind's whole promise:
  no body-shaped FIELD, and no VALUE equal to anything the rig said."
  [doc said]
  (and (every? #(not (contains? doc %)) forbidden-fields)
       (not-any? (fn [v] (some #(= (str v) (str %)) said)) (vals doc))))

(defn- gate-with [rows]
  (let [state (gc/fake-state)]
    (doseq [[tool rs] rows] (gc/answer! state tool rs))
    state))

;; ── tgram: the translation, and what it refuses ─────────────────────

(deftest tgram-translation
  (testing "a direct chat: the title is the counterpart's name, and it
            is the one participant"
    (let [d (tgram/chat->doc wellesley)]
      (is (= "Wellesley Kopsa" (:title d)))
      (is (= "direct" (:chat_kind d)))
      (is (= ["Wellesley Kopsa"] (:participant_names d)))
      (is (= "live" (:status d)))))

  (testing "the rig's own spelling of a time becomes the canonical
            instant — so a rig that changes its spelling does not
            churn every etag in the kind"
    (is (= "2026-08-28T03:54:19Z"
           (:last_message_at (tgram/chat->doc wellesley))))
    (is (= "2026-08-25T18:47:31Z" (tgram/instant-string
                                   "2026-08-25 18:47:31+00:00")))
    (is (nil? (tgram/instant-string "")))
    (is (nil? (tgram/instant-string "sometime last week"))))

  (testing "a GROUP exposes no members, and this source will not read
            its messages to infer them — the gap renders"
    (let [d (tgram/chat->doc bros)]
      (is (= "group" (:chat_kind d)))
      (is (= [] (:participant_names d)))))

  (testing "NO BODY, NO PREVIEW, NO UNREAD COUNT — the whole promise
            of the kind, judged over every field and every value"
    (is (carries-no-body? (tgram/chat->doc wellesley)
                          ["I see" 0 "user"]))
    (is (carries-no-body? (tgram/chat->doc bros)
                          [(:last_message_preview bros) 3]))))

(deftest tgram-bot-filter
  (testing "a chat whose username ends in bot is a notification
            channel, not a conversation — intent, not inventory"
    (is (tgram/bot? tote-bot))
    (is (not (tgram/bot? wellesley)))
    (is (not (tgram/mirrorable? tote-bot)))
    (is (tgram/mirrorable? wellesley)))

  (testing "and it never reaches discovery"
    (let [state (gate-with {tgram/tool [wellesley bros tote-bot]})
          src (tgram/fake-source state)]
      (is (= #{"5061625694" "-550048080"}
             (set (conf/thread-discover src)))))))

(deftest tgram-feed
  (let [state (gate-with {tgram/tool [wellesley bros]})
        src (tgram/fake-source state)]
    (testing "pull answers the document and a content etag — the rig
              mints no version of its own"
      (let [[doc etag] (conf/thread-pull src "5061625694")]
        (is (= "Wellesley Kopsa" (:title doc)))
        (is (str/ends-with? etag (str "|" tgram/translation-rev)))))

    (testing "an id the listing does not carry refuses 404-shaped —
              the gone signal only a declared :on-gone gives meaning"
      (is (= 404 (:status (ex-data (try (conf/thread-pull src "999")
                                        (catch clojure.lang.ExceptionInfo e
                                          e)))))))

    (testing "the batch IS the listing: absence against it is :gone,
              never an outage"
      (let [batch (conf/thread-pull-many src ["5061625694" "999"])]
        (is (vector? (get batch "5061625694")))
        (is (= :gone (get batch "999")))))

    (testing "a dark Gate throws, so the confluence's partial
              tolerance costs this source's rows a beat and no more"
      (gc/down! state true)
      (is (thrown? Exception (conf/thread-discover src)))
      (gc/down! state false))))

(deftest gate-answer-shapes
  (testing "structuredContent is the rig's structure and the content
            parts are its rendering — the source reads either"
    (let [state (gate-with {tgram/tool [wellesley]})
          src (tgram/fake-source state)]
      (is (= ["5061625694"] (conf/thread-discover src)))
      (gc/parts-only! state)
      (is (= ["5061625694"] (conf/thread-discover src)))))

  (testing "a rig that refuses is unreachable for this pass, and says
            so with its own sentence"
    (is (thrown-with-msg?
         Exception #"the rig refused"
         (gc/rows {:isError true
                   :content [{:type "text" :text "no session"}]})))))

;; ── tgrambot: the second clock, and the row it shares ───────────────

(deftest tgrambot-translation
  (testing "the bot's document carries the two clocks and names its
            own rig — the house was last spoken to, and the chat last
            moved"
    (let [d (tgrambot/chat->doc bot-bros)]
      (is (= "Bros. 🧠" (:title d)))
      (is (= "group" (:chat_kind d)))
      (is (= "live" (:status d)))
      (is (= "tgrambot" (:source d)))
      (is (= "2026-08-25T18:47:31Z" (:last_message_at d)))
      (is (= "2026-08-25T18:40:00Z" (:last_mention_at d)))))

  (testing "Telegram's own words for a two-person chat, from either
            entry point of the rig"
    (is (= "direct" (:chat_kind (tgrambot/chat->doc bot-wellesley))))
    (is (= "direct" (:chat_kind (tgrambot/chat->doc
                                 (assoc bot-wellesley :type "user")))))
    (is (= "group" (:chat_kind (tgrambot/chat->doc bot-meals)))))

  (testing "a chat the bot has heard from and nobody named it in:
            the mention is ABSENT rather than invented"
    (let [d (tgrambot/chat->doc bot-wellesley)]
      (is (not (contains? d :last_mention_at)))))

  (testing "and it names NO participants at all — absent, not empty:
            an empty list is a fact about a group, and this rig has
            no opinion, so the account's names stand where it has
            them"
    (is (not (contains? (tgrambot/chat->doc bot-bros)
                        :participant_names))))

  (testing "no body, no preview, and no mention COUNT"
    (is (carries-no-body? (tgrambot/chat->doc bot-meals) [4 "supergroup"]))))

(deftest tgrambot-feed
  (let [state (gate-with {tgrambot/tool [bot-bros bot-meals]})
        src (tgrambot/fake-source state)]
    (testing "the ids are Telegram's own, so they are the ids the
              account rig answers for the same chats"
      (is (= #{"-550048080" "-5091757250"}
             (set (conf/thread-discover src)))))

    (testing "pull answers the document and a content etag"
      (let [[doc etag] (conf/thread-pull src "-5091757250")]
        (is (= "Meal plans" (:title doc)))
        (is (str/ends-with? etag (str "|" tgrambot/translation-rev)))))

    (testing "the batch IS the listing here too"
      (is (= :gone (get (conf/thread-pull-many src ["999"]) "999"))))

    (testing "a chat with no title is inventory, not a conversation"
      (is (not (tgrambot/mirrorable? (assoc bot-bros :title ""))))
      (is (not (tgrambot/mirrorable? (dissoc bot-bros :chat_id)))))))

;; ── the chorus: two rigs, one conversation ──────────────────────────

(deftest two-rigs-over-one-telegram
  (let [state (gate-with {tgram/tool [wellesley bros]
                          tgrambot/tool [bot-bros bot-meals]})
        pair (conf/chorus [(tgram/fake-source state)
                           (tgrambot/fake-source state)])]

    (testing "the ids are the UNION: a chat one rig hears and the
              other does not is still an address, and a chat both
              list is ONE id"
      (is (= #{"5061625694" "-550048080" "-5091757250"}
             (set (conf/thread-discover pair)))))

    (testing "where both rigs list a chat, the account is the
              authority and the bot ADDS what it alone knows"
      (let [[doc etag] (conf/thread-pull pair "-550048080")]
        (is (= "Bros. 🧠" (:title doc)))
        (is (= "2026-08-25T18:47:31Z" (:last_message_at doc))
            "the account's own time, not the bot's")
        (is (= "2026-08-25T18:40:00Z" (:last_mention_at doc))
            "and the mention only the bot can answer")
        (is (= [] (:participant_names doc))
            "the account's word about who is in the group, kept")
        (is (nil? (:source doc))
            "the document names no rig, so the routing tag is the row's")
        (is (str/includes? etag "+")
            "and either rig moving moves the etag")))

    (testing "a chat only the BOT hears is its own row, and it says so"
      (let [[doc _] (conf/thread-pull pair "-5091757250")]
        (is (= "Meal plans" (:title doc)))
        (is (= "tgrambot" (:source doc)))
        (is (= "2026-09-20T17:58:40Z" (:last_mention_at doc)))))

    (testing "a chat only the ACCOUNT lists keeps its nil mention —
              the tgram source never touches that field"
      (let [[doc _] (conf/thread-pull pair "5061625694")]
        (is (= "Wellesley Kopsa" (:title doc)))
        (is (nil? (:last_mention_at doc)))))

    (testing "an id no rig lists is gone, in both shapes"
      (is (= 404 (:status (ex-data (try (conf/thread-pull pair "999")
                                        (catch clojure.lang.ExceptionInfo e
                                          e))))))
      (is (= :gone (get (conf/thread-pull-many pair ["999"]) "999"))))

    (testing "the batch merges the same way, id by id"
      (let [batch (conf/thread-pull-many
                   pair ["-550048080" "-5091757250" "5061625694"])]
        (is (= "2026-08-25T18:40:00Z"
               (:last_mention_at (first (get batch "-550048080")))))
        (is (nil? (:last_mention_at (first (get batch "5061625694")))))))

    (testing "and a DARK rig costs the tag its pass rather than half a
              document: a mention that nils out while the bot is down
              and moves forward when it returns is a door opening for
              nothing"
      ;; one Gate for each rig here, so exactly ONE of them goes dark
      (let [account-state (gate-with {tgram/tool [bros]})
            bot-state (gate-with {tgrambot/tool [bot-bros]})
            split (conf/chorus [(tgram/fake-source account-state)
                                (tgrambot/fake-source bot-state)])]
        (is (some? (conf/thread-pull split "-550048080")))
        (gc/down! bot-state true)
        (is (thrown? Exception (conf/thread-pull split "-550048080")))
        (is (thrown? Exception (conf/thread-discover split)))
        (is (thrown? Exception (conf/thread-pull-many split
                                                      ["-550048080"])))))))

;; ── the confluence stamps the tag, and the document may name the rig ─

(deftest the-tag-is-the-identity-and-the-rig-may-name-itself
  (let [state (gate-with {tgram/tool [bros]
                          tgrambot/tool [bot-bros bot-meals]})
        feed (conf/thread-confluence
              {"tgram" [(tgram/fake-source state)
                        (tgrambot/fake-source state)]})]
    (testing "one chat is ONE row whichever rig heard it — the bot
              mints no second address"
      (is (= #{"tgram:-550048080" "tgram:-5091757250"}
             (set (mirror/discover feed)))))

    (testing "a chat the account lists drinks from the tag"
      (is (= "tgram" (:source (first (mirror/pull feed
                                                  "tgram:-550048080"))))))

    (testing "a chat only the bot hears says which rig answered"
      (is (= "tgrambot" (:source (first (mirror/pull
                                         feed "tgram:-5091757250"))))))))

;; ── messa: the group trick, and the clock gap ───────────────────────

(deftest messa-translation
  (testing "a name with no comma is a direct thread with one person"
    (let [d (messa/thread->doc kathy)]
      (is (= "Kathy Peppas" (:title d)))
      (is (= "direct" (:chat_kind d)))
      (is (= ["Kathy Peppas"] (:participant_names d)))))

  (testing "a comma in the title is Google Messages saying who is in
            the group — this rig is the one that DOES expose members"
    (let [d (messa/thread->doc shumways)]
      (is (= "group" (:chat_kind d)))
      (is (= ["Amy Shumway" "Calista Shumway" "Wellesley Kopsa"
              "(304) 482-6884"]
             (:participant_names d)))))

  (testing "THE CLOCK GAP, CLOSED: the rig resolves its own relative
            label to an instant, and the doc canonicalizes it to UTC
            — the row ranks by recency at last"
    (is (= "2026-08-28T15:05:00Z"
           (:last_message_at (messa/thread->doc kathy))))
    (is (= "2026-08-27T18:00:00Z"
           (:last_message_at (messa/thread->doc shumways)))))

  (testing "and the gap that remains still RENDERS: a label the rig
            could not read answers null, which the doc carries as an
            absent field rather than as an invented time"
    (is (nil? (:last_message_at (messa/thread->doc walmart))))
    (is (nil? (:last_message_at
               (messa/thread->doc (assoc kathy :last_message_at
                                         "not a clock"))))))

  (testing "the human label itself never lands in the document — it
            is `time`, which is on the forbidden list"
    (is (nil? (:time (messa/thread->doc kathy)))))

  (testing "the snippet is the last message, and it never leaves the
            source"
    (is (carries-no-body? (messa/thread->doc kathy) ["Picture" ""]))
    (is (carries-no-body? (messa/thread->doc shumways)
                          ["Celestia: Picture"]))))

(deftest messa-feed
  (let [state (gate-with {messa/tool [kathy shumways walmart]})
        src (messa/fake-source state)]
    (testing "every listed thread is an address, shortcodes included —
              a row is where a fact POINTS, not a judgment of who sent
              it"
      (is (= #{"749450b3" "deb10be2" "6d7a4fd2"}
             (set (conf/thread-discover src)))))
    (testing "and the batch answers :gone for what the phone dropped"
      (is (= :gone (get (conf/thread-pull-many src ["gone-hash"])
                        "gone-hash"))))))

;; ── who becomes a person ────────────────────────────────────────────

(deftest person-name-rule
  (testing "names mint rows"
    (is (gc/person-name? "Wellesley Kopsa"))
    (is (gc/person-name? "Kathy Peppas"))
    (is (gc/person-name? "Amy O'Brien-Shumway")))

  (testing "and addresses the carrier assigned do not — a roster that
            filled with shortcodes and payroll robots would be worse
            than an empty one"
    (is (not (gc/person-name? "41646")))
    (is (not (gc/person-name? "(304) 482-6884")))
    (is (not (gc/person-name? "(743) 222-5699")))
    (is (not (gc/person-name? "Bros. 🧠")))
    (is (not (gc/person-name? "")))
    (is (not (gc/person-name? nil)))))

(deftest births-are-offered-for-every-participant
  (testing "every participant name is offered to the roster on the
            pass that reads it, so the ref resolves on THIS pass
            rather than one beat later — and the filter above is what
            decides which of them mints anything"
    (let [seen (atom [])
          state (gate-with {messa/tool [shumways]})
          src (messa/fake-source state {:birth-fn #(swap! seen conj %)})]
      (conf/thread-pull src "deb10be2")
      (is (= ["Amy Shumway" "Calista Shumway" "Wellesley Kopsa"
              "(304) 482-6884"]
             @seen))))

  (testing "a birth that fails costs a ref its resolution and never
            the pass"
    (let [state (gate-with {tgram/tool [wellesley]})
          src (tgram/fake-source
               state {:birth-fn (fn [_] (throw (ex-info "roster down" {})))})]
      (is (thrown? Exception (conf/thread-pull src "5061625694"))
          "the stub throws through on purpose — the WIRED birth-fn
           swallows, which is what roster-birth-fn's catch is for"))))

;; ── the confluence's own law ────────────────────────────────────────

(deftest thread-confluence-routing
  (let [state (gate-with {tgram/tool [wellesley] messa/tool [kathy]})
        feed (conf/thread-confluence {"tgram" (tgram/fake-source state)
                                      "messa" (messa/fake-source state)})]
    (testing "identity is namespaced by rig, and :source is stamped by
              the routing layer — a source cannot know which authority
              it is"
      (is (= #{"tgram:5061625694" "messa:749450b3"}
             (set (mirror/discover feed))))
      (is (= "tgram" (:source (first (mirror/pull feed
                                                  "tgram:5061625694")))))
      (is (= "messa" (:source (first (mirror/pull feed
                                                  "messa:749450b3"))))))

    (testing "the queue does not WRITE a conversation — and the one
              door that could reach the push is told why in a sentence
              a person reads"
      (is (thrown-with-msg?
           Exception #"does not write conversations"
           (mirror/push feed "tgram:5061625694" {}))))

    (testing "and it takes no births at all: the thread feed declares
              no create adapter, so refusing is structural rather than
              a runtime throw"
      (is (not (satisfies? mirror/MirrorCreateAdapter feed))))

    (testing "a down rig costs its own rows a pass and nothing else"
      (let [down (gc/fake-state)
            partial-feed (conf/thread-confluence
                          {"tgram" (tgram/fake-source state)
                           "messa" (messa/fake-source down)})]
        (gc/down! down true)
        (is (= ["tgram:5061625694"] (mirror/discover partial-feed)))))))

;; ── the advance beat's own read (waymark-fp62.18.3) ─────────────────
;;
;; The framework beats every :advance-every seconds and asks ONE
;; question: which declared instants moved? Only the rig that owns an
;; instant can answer it, which here is the bot and nobody else.

(deftest the-beat-asks-the-rig-that-owns-the-mention
  (let [state (gate-with {tgram/tool [wellesley bros]
                          tgrambot/tool [bot-bros bot-meals bot-wellesley]})
        bot (tgrambot/fake-source state)
        account (tgram/fake-source state)]

    (testing "the bot answers the mention it alone hears, from the
              SAME listing read its three other verbs make"
      (is (= {"-550048080" {:last_mention_at "2026-08-25T18:40:00Z"}
              "-5091757250" {:last_mention_at "2026-09-20T17:58:40Z"}}
             (conf/thread-advances bot)))
      (is (nil? (get (conf/thread-advances bot) "5061625694"))
          "a chat nobody has named the house in is left OUT, not
           answered nil: the beat asks which instants moved"))

    (testing "the house's own account is not asked at all — it cannot
              answer, so it does not satisfy the protocol"
      (is (not (satisfies? conf/AdvanceSource account))))

    (testing "the chorus answers the union of the rigs that can, and
              the confluence stamps the routing tag — so the bot's
              instant lands against the row the account owns"
      (let [feed (conf/thread-confluence {"tgram" [account bot]
                                          "messa" (messa/fake-source state)})]
        (is (= {"tgram:-550048080" {:last_mention_at "2026-08-25T18:40:00Z"}
                "tgram:-5091757250" {:last_mention_at "2026-09-20T17:58:40Z"}}
               (mirror/advance-listing feed)))))

    (testing "a dark rig costs the beat that pass and nothing else:
              no move is seen, and nothing is written from a listing"
      (let [feed (conf/thread-confluence {"tgram" [account bot]})]
        (gc/down! state true)
        (is (= {} (mirror/advance-listing feed)))
        (gc/down! state false)
        (is (seq (mirror/advance-listing feed))))))

  (testing "the scriptable twin holds the same law: a seeded advance
            is answered, and a down source refuses the verb"
    (let [fake (conf/fake-source)]
      (conf/seed-advance! fake "c-1" {:last_mention_at "2026-09-21T18:26:00Z"})
      (is (= {"c-1" {:last_mention_at "2026-09-21T18:26:00Z"}}
             (conf/thread-advances fake)))
      (conf/down! fake true)
      (is (thrown? Exception (conf/thread-advances fake)))
      (conf/down! fake false)
      (is (= 1 (count (conf/thread-advances fake)))))))

;; ── end to end, over the engine ─────────────────────────────────────

(def ^:private tables
  ["threads" "people" "tasks" "task_lists" "media" "chores" "chore_runs"
   "days" "meals" "meal_lines" "rotations" "plans" "plan_days"
   "grocery_lists" "prep_tasks" "ingredients" "products" "substitutions"
   "events" "members" "roles" "grants" "approval_requests"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)
(def ^:dynamic *gate* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          gate (gate-with {tgram/tool [wellesley bros tote-bot]
                           tgrambot/tool [bot-bros]
                           messa/tool [kathy shumways]})
          engine-ref (atom nil)
          birth-fn (gc/roster-birth-fn {:engine-ref engine-ref})]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (mirror/with-push
                   (engine/engine
                    {:storage st
                     :resources (main/resources
                                 {"chore" (conf/fake-source)
                                  "meal" (conf/fake-source)
                                  "todo" (conf/fake-source)
                                  "gtasks" (conf/fake-source)}
                                 {"hub" (hub/source)}
                                 ;; the tgram TAG holds two rigs, in
                                 ;; authority order: the house's own
                                 ;; account, then the house's bot
                                 {"tgram" [(tgram/fake-source
                                            gate {:birth-fn birth-fn})
                                           (tgrambot/fake-source gate)]
                                  "messa" (messa/fake-source
                                           gate {:birth-fn birth-fn})}
                                 (gcal/fake-calendar)
                                 nil)}))]
          (reset! engine-ref eng)
          (binding [*eng* eng *h* (engine/handler eng) *gate* gate]
            (f)))
        (finally (pg/close! st))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- get! [uri]
  (let [[path query] (str/split uri #"\?" 2)
        resp (*h* (cond-> {:request-method :get :uri path
                           :headers {"x-waymark-principal" "colton"}}
                    query (assoc :query-string query)))]
    (is (= 200 (:status resp)) (str uri ": " (:body resp)))
    (json resp)))

(defn- id-of [self] (last (str/split (str self) #"/")))

(defn- rows-by-title
  "Every thread row's OWN data, keyed by title — a collection answers
  a projection, and only the row's own address answers `data`."
  []
  (into {}
        (map (fn [item]
               (let [d (:data (get! (:self item)))]
                 [(:title d) d])))
        (get-in (get! "/api/threads?page%5Bsize%5D=100") [:data :items])))

(defn- seed-gate!
  "Put the verified listings back, so each scene below is independent
  of which order clojure.test ran them in. The bot hears ONE of the
  chats the account lists, so the row set is the account's and the
  mention rides the row the account already owns."
  []
  (gc/answer! *gate* tgram/tool [wellesley bros tote-bot])
  (gc/answer! *gate* tgrambot/tool [bot-bros])
  (gc/answer! *gate* messa/tool [kathy shumways])
  (mirror/discover! *eng* :thread)
  (mirror/resync! *eng* :thread))

(defn- row-of
  "One thread row, by its external id."
  [xid]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (first (store/query-rows (:storage *eng*) tx :thread
                               {:external_id xid} {:limit 1})))))

(defn- actions-on
  "The actions recorded on one thread row, oldest first."
  [xid]
  (mapv :action
        (store/with-tx (:storage *eng*)
          (fn [tx]
            (store/transitions (:storage *eng*) tx
                               {:kind :thread
                                :resource-id (str (:id (row-of xid)))}
                               {})))))

(deftest the-house-gets-addresses-for-its-conversations
  (seed-gate!)
  (testing "one discovery pass mints a row per conversation — the
            bot's channel excepted"
    (is (zero? (mirror/discover! *eng* :thread))
        "and a second pass mints nothing: identity is the rig's"))

  (let [rows (rows-by-title)]
    (testing "the rows are the conversations, by their household names"
      (is (= #{"Wellesley Kopsa" "Bros. 🧠" "Kathy Peppas"
               (:name shumways)}
             (set (keys rows)))))

    (testing "AND NOT ONE OF THEM CARRIES A WORD ANYBODY SAID — the
              kind's whole promise, proved on the STORED row rather
              than on the translation"
      (doseq [[title row] rows]
        (is (carries-no-body?
             row
             ["I see" (:last_message_preview bros) "Picture"
              "Celestia: Picture" 0 3])
            (str title " carries something it should not"))))

    (testing "a direct chat's external id IS the peer's sender id —
              the directory tgram__get_messages will not answer"
      (is (= "tgram:5061625694"
             (:external_id (get rows "Wellesley Kopsa")))))

    (testing "both rigs speak a clock now, so every stored row carries
              the cursor the driver windows on"
      (is (some? (:last_message_at (get rows "Wellesley Kopsa"))))
      (is (some? (:last_message_at (get rows "Kathy Peppas")))))

    (testing "and a thread is never done: `live` is the only word a
              listed conversation has"
      (is (= #{"live"} (set (map :status (vals rows))))))))

(deftest participants-become-the-roster
  (seed-gate!)
  (let [rows (rows-by-title)
        people (get-in (get! "/api/people?page%5Bsize%5D=100")
                       [:data :items])
        by-name (into {} (map (juxt #(get-in % [:fields :name]) identity))
                      people)]

    (testing "an unknown participant lands OBSERVED — the roster grows
              on its own, and nobody is told who their people are"
      (is (contains? by-name "Wellesley Kopsa"))
      (is (= "observed" (:state (get by-name "Wellesley Kopsa"))))
      (is (= "observed" (:state (get by-name "Kathy Peppas")))))

    (testing "…and only the names that LOOK like names do: a carrier
              shortcode and a bare phone number mint nobody"
      (is (not (contains? by-name "(304) 482-6884")))
      (is (not (contains? by-name "41646"))))

    (testing "the raw names stay whole beside the refs, and the refs
              resolve to the roster rows"
      (let [group (get rows (:name shumways))]
        (is (= 4 (count (:participant_names group)))
            "the rig's own words, including the phone number")
        (is (= 3 (count (:participants group)))
            "and its resolvable projection — the subset the house can name")
        (is (contains? (set (:participants group))
                       (id-of (:self (get by-name "Wellesley Kopsa")))))))

    (testing "a tgram group exposes no members, so it names nobody
              rather than guessing"
      (is (= [] (:participant_names (get rows "Bros. 🧠"))))
      (is (= [] (:participants (get rows "Bros. 🧠")))))))

(deftest a-thread-the-phone-drops-is-let-go
  (seed-gate!)
  (testing "the rig ANSWERED and the thread was absent from its
            listing: the house stopped talking there. The row keeps
            serving — the address an old insight cites is still an
            address — and says `dropped`"
    (gc/answer! *gate* messa/tool [shumways])
    (mirror/resync! *eng* :thread)
    (let [rows (rows-by-title)]
      (is (= "dropped" (:status (get rows "Kathy Peppas"))))
      (is (= "live" (:status (get rows (:name shumways)))))))

  (testing "…and it comes back live when the phone lists it again —
            nothing was deleted"
    (seed-gate!)
    (is (= "live" (:status (get (rows-by-title) "Kathy Peppas"))))))

;; ── the house hears itself named ────────────────────────────────────

(deftest a-mention-of-the-house-opens-a-door-of-its-own
  (seed-gate!)
  (let [xid "tgram:-550048080"
        mentions #(count (filterv #{:observe_mention} (actions-on xid)))
        observes #(count (filterv #{:observe_external} (actions-on xid)))
        before (mentions)
        observed (observes)]

    (testing "the bot's mention lands on the row the ACCOUNT owns:
              one chat is one row, whichever rig heard it"
      (is (some? (row-of xid)))
      (is (= "Bros. 🧠" (get-in (row-of xid) [:data :title])))
      (is (= "2026-08-25T18:40:00Z"
             (str (get-in (row-of xid) [:data :last_mention_at])))))

    (testing "a message nobody addressed to the house moves the etag
              and nothing else: observe_external, and no mention"
      (gc/answer! *gate* tgram/tool
                  [wellesley
                   (assoc bros :last_message_date "2026-08-26 09:00:00+00:00")
                   tote-bot])
      (mirror/resync! *eng* :thread)
      (is (= "2026-08-26T09:00:00Z"
             (str (get-in (row-of xid) [:data :last_message_at]))))
      (is (= before (mentions)) "the mention door stayed shut")
      (is (= (inc observed) (observes))))

    (testing "and a mention moves BOTH doors, once: the etag changed,
              so the document landed, and the house was named, so the
              mention door opened beside it"
      (gc/answer! *gate* tgrambot/tool
                  [(assoc bot-bros :last_mention_at
                          "2026-08-26T09:05:00+00:00"
                          :mentions 3)])
      (mirror/resync! *eng* :thread)
      (is (= (inc before) (mentions)))
      (is (= (+ 2 observed) (observes)))
      (is (= "2026-08-26T09:05:00Z"
             (str (get-in (row-of xid) [:data :last_mention_at])))
          "and the row carries the new time"))

    (testing "a second pass over the same listing opens nothing: the
              door is a MOVE and not a level"
      (mirror/resync! *eng* :thread)
      (is (= (inc before) (mentions))))

    ;; leave the listings as every other scene here expects them
    (seed-gate!)))

;; ── …and the house hears it within the beat, not within the hour ────

(deftest the-beat-brings-a-mention-in-within-seconds
  (seed-gate!)
  (let [xid "tgram:-550048080"
        rdef (get (inv/resources *eng*) :thread)
        mentions #(count (filterv #{:observe_mention} (actions-on xid)))
        before (mentions)]

    (gc/answer! *gate* tgrambot/tool
                [(assoc bot-bros :last_mention_at "2026-09-21T18:26:00Z"
                        :mentions 7)])

    (testing "the row is fresh inside its TTL, so the pull-through
              alone serves the stored truth — which is why the hourly
              heal was the only thing that ever saw a mention"
      (mirror/refresh! *eng* rdef (inv/decode-row rdef (row-of xid)))
      (is (= "2026-08-25T18:40:00Z"
             (str (get-in (row-of xid) [:data :last_mention_at]))))
      (is (= before (mentions))))

    (testing "one beat asks the bot rig which mentions moved — one
              list_chats call, the account rig and the phone unasked —
              and refreshes that row alone"
      (is (= 1 (:moved (mirror/advance-beat! *eng* :thread))))
      (is (= "2026-09-21T18:26:00Z"
             (str (get-in (row-of xid) [:data :last_mention_at]))))
      (is (= (inc before) (mentions))
          "the mention door opened once, and the seat's wake reads it"))

    (testing "a beat over a dark Gate costs that pass and nothing
              else: the confluence's partial tolerance answers an
              empty listing, so no move is seen and no row is touched"
      (gc/down! *gate* true)
      (is (= {:listed 0 :moved 0} (mirror/advance-beat! *eng* :thread)))
      (is (= (inc before) (mentions)))
      (gc/down! *gate* false))

    (testing "and the door is a MOVE: a second beat over the same
              listing opens nothing"
      (is (= 0 (:moved (mirror/advance-beat! *eng* :thread))))
      (is (= (inc before) (mentions))))

    ;; leave the listings as every other scene here expects them
    (seed-gate!)))
