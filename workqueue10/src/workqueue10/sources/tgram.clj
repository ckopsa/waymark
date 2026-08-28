(ns workqueue10.sources.tgram
  "tgram — the household's Telegram, through Gate — as the :thread
  confluence's first authority. The flickr shape with a simpler wire
  (docs/spec-threads.md).

  THE WIRE is one tool: `tgram__list_chats {limit}`, verified live
  2026-08-28. One object per conversation:

      {:id 5061625694, :title \"Wellesley Kopsa\", :type \"user\",
       :username nil, :last_message_date \"2026-08-28 03:54:19+00:00\",
       :unread_count 0, :last_message_preview \"I see\"}

  THREE OF THOSE SEVEN FIELDS NEVER LEAVE THIS NAMESPACE.
  `last_message_preview` is a hundred characters of the last message —
  a body in a shorter coat. `unread_count` is a fact about the
  owner's PHONE rather than the conversation: it moves when he opens
  the app and nobody said anything, so mirroring it would churn every
  row on every pass, make \"this thread moved\" ambiguous, and publish
  to every grant-holder how much the house has left unanswered.
  `username` is read and dropped — it is the bot filter's evidence,
  not a fact worth keeping.

  NO CURSOR, and the absence is the design (spec fork (e)): the tool
  takes `limit` and nothing else. What flickr's cursor buys — a small
  delta read — the listing gives for free at forty rows, so the
  LISTING IS THE WINDOW, deliberately wider than one pass needs, and
  a thread that moved cannot hide behind it. The cursor that matters
  is on the ROW: :last_message_at is what the driver windows on and
  what makes a thread an arrival.

  THE BATCH IS THE LISTING. There is no per-chat metadata route, so
  pull-many reads the whole listing once and answers :gone for every
  id it no longer carries — flickr's shape exactly, and the
  observation :on-gone reads. :resync-every rides it.

  BOTS DO NOT MIRROR. A chat whose username ends in \"bot\" is a
  notification channel (ToteClawBot, tote_work_bot at verification),
  not a household conversation. flickr's mirrored-kinds line with a
  different noun: intent, not inventory. The driver used to exclude
  them by hand; the rule belongs where the row is minted.

  PARTICIPANTS. A `user` chat is direct, and its title IS the
  counterpart's name — verified: \"Wellesley Kopsa\", \"Carson
  Kopsa\", \"Kevin Kopsa\". A `group` chat exposes NO members, and
  this source will not read its messages to infer them, so a group
  mirrors with an empty participant list and the gap renders. An
  unknown direct name is born observed through the birth-fn.

  AND THE DIRECTORY THIS BUYS: a direct chat's external id IS the
  peer's telegram sender id (5061625694 is the Wellesley chat and the
  Wellesley sender both). tgram__get_messages answers sender ids and
  no names at all, so the mirrored row set is the only place that
  question is answerable — and no sender id is stored, and no name is
  learned that the rig did not already hand us as a chat title."
  (:require [clojure.string :as str]
            [workqueue10.confluence :as conf]
            [workqueue10.sources.gate-chat :as gc])
  (:import (java.time Instant OffsetDateTime)))

(set! *warn-on-reflection* true)

(def tool "tgram__list_chats")

(def translation-rev
  "This namespace's translation version, composed into every etag.
  Telegram mints no version of its own, so the etag is a content
  hash — which sees the authority moving and can never see US moving;
  bump this whenever chat->doc's output changes shape and every
  stored row re-observes on its next pull."
  "t1")

;; ── the translation ─────────────────────────────────────────────────

(defn instant-string
  "Telegram's \"2026-08-26 17:03:21+00:00\" → the canonical RFC 3339
  instant the schema decodes, or nil when the rig says nothing a
  clock can read. Canonical rather than passed through, so a rig that
  changes its spelling does not churn every etag in the kind."
  [s]
  (let [s (str/trim (str s))]
    (when-not (str/blank? s)
      (let [iso (str/replace s #"^(\d{4}-\d{2}-\d{2}) " "$1T")]
        (try (str (Instant/parse iso))
             (catch Exception _
               (try (str (.toInstant (OffsetDateTime/parse iso)))
                    (catch Exception _ nil))))))))

(defn bot?
  "A notification channel, not a conversation."
  [chat]
  (let [u (str/lower-case (str (:username chat)))]
    (and (not (str/blank? u)) (str/ends-with? u "bot"))))

(defn mirrorable?
  "A chat becomes a row when it is a real conversation with a title.
  Everything else — a bot's channel, a nameless entry — is inventory."
  [chat]
  (and (not (str/blank? (str (:title chat))))
       (not (bot? chat))))

(defn chat->doc
  "One listing entry → the canonical thread doc. Note what is NOT
  here: no preview, no unread count, no username, nothing anybody
  said. A direct chat names its one participant; a group names none,
  because the listing exposes none."
  [chat]
  (let [direct? (= "user" (str (:type chat)))]
    (cond-> {:title (str (:title chat))
             :status "live"
             :chat_kind (if direct? "direct" "group")
             :participant_names (if direct? [(str (:title chat))] [])}
      (instant-string (:last_message_date chat))
      (assoc :last_message_at (instant-string (:last_message_date chat))))))

;; ── the source ──────────────────────────────────────────────────────

(defn- listing
  "The whole window, keyed by chat id — the full list absence is
  judged against."
  [{:keys [rpc-fn limit]}]
  (into {}
        (comp (filter mirrorable?)
              (map (juxt #(str (:id %)) identity)))
        (gc/call rpc-fn tool {:limit (or limit gc/default-limit)})))

(defn- doc-for [{:keys [birth-fn]} chat]
  (let [doc (chat->doc chat)]
    ;; the roster grows on its own: a direct chat's counterpart the
    ;; house has never written down is born observed, so the ref
    ;; resolves on THIS pass rather than one beat later. Best-effort —
    ;; a birth that fails costs a ref its resolution, never the pass.
    (doseq [nm (:participant_names doc)] (birth-fn nm))
    doc))

(defrecord TgramSource [rpc-fn limit birth-fn]
  conf/ThreadSource
  (thread-discover [this]
    (into [] (map key) (listing this)))

  (thread-pull [this id]
    (if-some [chat (get (listing this) (str id))]
      (let [doc (doc-for this chat)]
        [doc (gc/content-etag doc translation-rev)])
      (throw (ex-info (str id " is not a chat telegram lists")
                      {:status 404}))))

  (thread-pull-many [this ids]
    ;; no per-chat route exists, so the batch IS the listing — one
    ;; read, absence answered :gone (the rig spoke for its whole
    ;; window; a missing id is an observation, never an outage)
    (let [chats (listing this)]
      (into {}
            (map (fn [id]
                   [(str id)
                    (if-some [chat (get chats (str id))]
                      (let [doc (doc-for this chat)]
                        [doc (gc/content-etag doc translation-rev)])
                      :gone)]))
            ids))))

(defn source
  "The real boundary over Gate.
  config: :rpc-fn (the shared Gate caller — gate-chat/rpc builds it
  once), :limit (how wide the window is), :birth-fn (a participant
  name → the roster row, minting one observed when absent;
  gate-chat/roster-birth-fn is the wired spelling)."
  [{:keys [rpc-fn limit birth-fn]}]
  (->TgramSource rpc-fn limit (or birth-fn (constantly nil))))

(defn fake-source
  "Telegram in memory: the REAL source over a scriptable Gate, so the
  listing read, the structured/parts fallback, the bot filter and the
  translation all run and only the socket is missing. Script the
  state with gate-chat/answer! (rig-shaped chats, never canonical
  documents) and gate-chat/down!."
  ([] (fake-source (gc/fake-state)))
  ([state] (fake-source state {}))
  ([state opts] (source (assoc opts :rpc-fn (gc/fake-rpc state)))))
