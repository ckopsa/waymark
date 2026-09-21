(ns workqueue10.sources.tgrambot
  "tgrambot — the house's own Telegram BOT, through Gate — as the
  :thread confluence's third authority, and the first one that hears
  the house being spoken TO.

  WHY A SECOND TELEGRAM. The tgram rig reads the family chat as a
  person's own account (sources/tgram.clj). It can tell the house
  that somebody said something; it cannot tell the house that
  somebody said something to IT, because the house has no voice in
  that rig. The bot rig (waymark-fp62.18.1) is the second entry point
  of the same rig over the Telegram Bot API: it long-polls its own
  updates, and it knows which messages named it by @username, replied
  to it, or gave it a command.

  THE WIRE is one tool: `tgrambot__list_chats {limit}`. One object
  per conversation the bot has heard from, newest first:

      {:chat_id -5091757250, :title \"Meal plans\", :type \"group\",
       :last_message_at \"2026-09-20T18:02:11+00:00\",
       :last_mention_at \"2026-09-20T17:58:40+00:00\", :mentions 4}

  `mentions` is read and dropped: it is a COUNT of what the rig has
  heard since it started, so it moves when the rig restarts and says
  nothing a clock does not say better. What the row keeps is the
  mention's TIME, which is what the driver compares.

  ONE CHAT IS ONE ROW. The external id is `tgram:<chat_id>` — the
  tgram source's own spelling, not a second one — so the family chat
  is one address whichever rig heard it, and an insight that cites
  the chat cites the same row next year. The confluence holds both
  rigs under the one tag (confluence/chorus), and the identity is the
  tag's.

  WHO OWNS WHICH FIELD, decided and recorded here. THE RIG LISTED
  FIRST IS THE AUTHORITY, and main lists the house's account first:

  - `participant_names` (and the refs under them) are the ACCOUNT's.
    It names the counterpart of a direct chat; the bot's listing
    names nobody, and a bot that guessed would overwrite a fact with
    a gap.
  - `last_mention_at` is THE BOT'S, always and alone. No other rig
    answers it, so nothing of the account's can win over it, and the
    two other sources leave the field nil (the tgram source never
    touches it).
  - `title`, `chat_kind` and `last_message_at` are the account's for
    a chat it lists, and the bot's for a chat only the bot hears —
    the bot is in a group the house's account left, or the account
    rig is not wired at all.
  - `:source` is the account's for a chat it lists and `tgrambot` for
    a chat only the bot hears, by the same rule: the first rig that
    answers names the row.

  NO CURSOR, and THE BATCH IS THE LISTING, exactly as at the other
  two rigs: the tool takes `limit` and nothing else, there is no
  per-chat route, so pull-many reads the window once and answers
  :gone for every id it no longer carries.

  THE ADVANCE BEAT reads that same window (waymark-fp62.18.3). This
  rig is an AdvanceSource, which is what lets the framework ask every
  twenty seconds which chats the house was named in, instead of
  waiting for the hourly heal to re-pull every conversation from
  every rig. The beat costs ONE `list_chats` call; the account rig
  and the phone are never asked, because neither can answer.

  NO BODIES. The bot hears whole messages and this source reads none
  of them. `tgrambot__get_messages` and `tgrambot__send_message` are
  a seat's leashed powers at Gate's own door, never a sync pass's."
  (:require [clojure.string :as str]
            [workqueue10.confluence :as conf]
            [workqueue10.sources.gate-chat :as gc]
            [workqueue10.sources.tgram :as tgram]))

(set! *warn-on-reflection* true)

(def tool "tgrambot__list_chats")

(def source-tag
  "What this rig calls itself in a document it wrote. The routing tag
  is `tgram` (one chat is one row), so a document that names nobody
  is the account's and this is how a chat only the bot hears says
  whose it is."
  "tgrambot")

(def translation-rev
  "This namespace's translation version, composed into every etag —
  the rig mints none. Bump it when chat->doc changes shape and every
  stored row re-observes on its next pull."
  "b1")

;; ── the translation ─────────────────────────────────────────────────

(def instant-string
  "The bot rig's clock, read by the account rig's own reader: both are
  entry points of one rig, and they spell a time the same way. A
  value no clock can read answers nil rather than a guess."
  tgram/instant-string)

(defn direct?
  "Telegram's own words for a two-person chat. The Bot API says
  `private`; the account rig says `user`, and this reads both, so one
  rig changing its noun does not change the row's kind."
  [chat]
  (contains? #{"private" "user"} (str/lower-case (str (:type chat)))))

(defn mirrorable?
  "A chat becomes a row when it has an id and a title. The bot hears
  from nobody else, so there is no bot filter here: a rig that
  answers for itself would be answering about the house."
  [chat]
  (and (not (str/blank? (str (:chat_id chat))))
       (not (str/blank? (str (:title chat))))))

(defn chat->doc
  "One listing entry → the canonical thread doc. No message, no
  preview, no mention COUNT — the time of the last word and the time
  of the last word to the house, and nothing anybody said.

  `participant_names` is deliberately ABSENT rather than empty: an
  empty vector is a fact (the group exposes nobody) and this rig has
  no opinion at all, so the account's names stand where it has them."
  [chat]
  (cond-> {:title (str (:title chat))
           :status "live"
           :source source-tag
           :chat_kind (if (direct? chat) "direct" "group")}
    (instant-string (:last_message_at chat))
    (assoc :last_message_at (instant-string (:last_message_at chat)))

    (instant-string (:last_mention_at chat))
    (assoc :last_mention_at (instant-string (:last_mention_at chat)))))

;; ── the source ──────────────────────────────────────────────────────

(defn- listing
  "The whole window, keyed by chat id — the full list absence is
  judged against."
  [{:keys [rpc-fn limit]}]
  (into {}
        (comp (filter mirrorable?)
              (map (juxt #(str (:chat_id %)) identity)))
        (gc/call rpc-fn tool {:limit (or limit gc/default-limit)})))

(defrecord TgrambotSource [rpc-fn limit]
  conf/ThreadSource
  (thread-discover [this]
    (into [] (map key) (listing this)))

  (thread-pull [this id]
    (if-some [chat (get (listing this) (str id))]
      (let [doc (chat->doc chat)]
        [doc (gc/content-etag doc translation-rev)])
      (throw (ex-info (str id " is not a chat the bot has heard from")
                      {:status 404}))))

  (thread-pull-many [this ids]
    ;; the batch IS the listing, as at both other rigs: one read,
    ;; absence answered :gone. Under the chorus a :gone here is not
    ;; the row's ending — the account rig may still list the chat
    (let [chats (listing this)]
      (into {}
            (map (fn [id]
                   [(str id)
                    (if-some [chat (get chats (str id))]
                      (let [doc (chat->doc chat)]
                        [doc (gc/content-etag doc translation-rev)])
                      :gone)]))
            ids)))

  conf/AdvanceSource
  ;; THE ADVANCE BEAT'S ANSWER, and this rig is the only one that can
  ;; give it: `last_mention_at` is the bot's, always and alone. It is
  ;; the SAME listing read the three verbs above make — one
  ;; `list_chats` call, no per-chat route, no message — so a beat
  ;; every twenty seconds costs the house one call and the other two
  ;; rigs nothing at all.
  ;;
  ;; A chat with no mention is LEFT OUT rather than answered nil: the
  ;; beat asks which instants moved, and a chat nobody has ever named
  ;; the house in has no such instant to speak about.
  (thread-advances [this]
    (into {}
          (keep (fn [[id chat]]
                  (when-some [m (instant-string (:last_mention_at chat))]
                    [id {:last_mention_at m}])))
          (listing this))))

(defn source
  "The real boundary over Gate.
  config: :rpc-fn (the shared Gate caller — the tool name resolves by
  its prefix to the `tgrambot` mcp_server row), :limit (how wide the
  window is). NO :birth-fn: this rig names no participants, so it
  mints nobody."
  [{:keys [rpc-fn limit]}]
  (->TgrambotSource rpc-fn limit))

(defn fake-source
  "The bot in memory: the REAL source over a scriptable Gate, as at
  tgram — the listing read, the kind filter and the translation all
  run, and only the socket is missing."
  ([] (fake-source (gc/fake-state)))
  ([state] (fake-source state {}))
  ([state opts] (source (assoc opts :rpc-fn (gc/fake-rpc state)))))
