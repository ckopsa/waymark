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
            [waymark10.server.engine :as engine]
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
  {:name "Kathy Peppas" :snippet "Picture" :time "" :hash "749450b3"})

(def ^:private shumways
  {:name (str "Amy Shumway, Calista Shumway, Wellesley Kopsa, "
              "(304) 482-6884")
   :snippet "Celestia: Picture" :time "" :hash "deb10be2"})

(def ^:private walmart
  {:name "41646" :snippet "We're sorry, please return the item"
   :time "" :hash "6d7a4fd2"})

;; the words that must never appear in a stored document, whatever
;; shape the translation grows into
(def ^:private forbidden-fields
  [:last_message_preview :snippet :preview :text :body :unread_count
   :username :time])

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

  (testing "THE CLOCK GAP, verified live: the rig answers an empty
            time for every thread, so a messa row carries none — and
            the gap renders rather than being invented"
    (is (nil? (:last_message_at (messa/thread->doc kathy))))
    (is (nil? (:last_message_at (messa/thread->doc shumways)))))

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
                                 {"tgram" (tgram/fake-source
                                           gate {:birth-fn birth-fn})
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
  (let [resp (*h* {:request-method :get :uri uri
                   :headers {"x-waymark-principal" "colton"}})]
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
  of which order clojure.test ran them in."
  []
  (gc/answer! *gate* tgram/tool [wellesley bros tote-bot])
  (gc/answer! *gate* messa/tool [kathy shumways])
  (mirror/discover! *eng* :thread)
  (mirror/resync! *eng* :thread))

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

    (testing "the phone answers no time, so its rows honestly carry
              none while telegram's do"
      (is (some? (:last_message_at (get rows "Wellesley Kopsa"))))
      (is (nil? (:last_message_at (get rows "Kathy Peppas")))))

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
