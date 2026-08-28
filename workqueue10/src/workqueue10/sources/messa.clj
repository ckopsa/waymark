(ns workqueue10.sources.messa
  "messa — the phone's text messages (Google Messages), through Gate —
  as the :thread confluence's second authority, and the one that
  proves the protocol is not Telegram's shape wearing a general name.

  THE WIRE is one tool: `messa__threads {limit}`, verified live
  2026-08-28. One object per thread, and only four fields:

      {:name \"Kevin Gallagher\", :snippet \"You: Sounds good\",
       :time \"\", :hash \"d0d1123a\"}

  `snippet` never leaves this namespace — it is the last message,
  which is the one thing this kind exists not to carry.

  THE CLOCK GAP, recorded loudly because it changes what the driver
  can do (docs/spec-threads.md § 'The messa gap'): **`time` is the
  empty string for every thread**, and `messa__read_messages` answers
  `time: null` and `age: null` for every message inside one. So a
  messa thread carries :last_message_at nil, never ranks by recency,
  and can never become an arrival. It is still worth mirroring: the
  thesis is ADDRESSES, and a commitment found in the Kathy Peppas
  thread now has somewhere to point. The moment the rig speaks a
  time this fills with no change here.

  THE GROUP TRICK. Google Messages names a group thread after
  everybody in it — \"Amy Shumway, Calista Shumway, …, Wellesley
  Kopsa, (304) 482-6884\" — so this rig is the one that DOES expose
  participants, as a side effect of how it titles. A comma in the
  name is the group signal and each part is a participant; a name
  with no comma is a direct thread with one. The parts that are not
  names — a phone number, a carrier shortcode — land in
  :participant_names whole and mint nobody (gate-chat/person-name?).

  NO CURSOR and THE BATCH IS THE LISTING, exactly as at tgram: the
  tool takes `limit` and nothing else, there is no per-thread
  metadata route, so pull-many reads the window once and answers
  :gone for every id it no longer carries."
  (:require [clojure.string :as str]
            [workqueue10.confluence :as conf]
            [workqueue10.sources.gate-chat :as gc]))

(set! *warn-on-reflection* true)

(def tool "messa__threads")

(def translation-rev
  "This namespace's translation version, composed into every etag —
  the rig mints none. Bump it when thread->doc changes shape and
  every stored row re-observes on its next pull."
  "m1")

(def title-max
  "A group's title is a ROSTER rather than a headline, so it is
  longer than a chat name has any right to be — cut here, at the
  schema's own bound, rather than refused at the door."
  400)

;; ── the translation ─────────────────────────────────────────────────

(defn participants
  "The rig's title, read as the roster it is: comma-separated when the
  thread has more than one person in it, one name when it does not.
  Blank parts drop; nothing else is judged here — whether a part is a
  PERSON is the birth's question, not the mirror's."
  [nm]
  (into [] (comp (map str/trim) (remove str/blank?))
        (str/split (str nm) #",")))

(defn thread->doc
  "One listing entry → the canonical thread doc. No snippet, no
  time (the rig has none), nothing anybody said."
  [th]
  (let [names (participants (:name th))
        title (str (:name th))]
    {:title (subs title 0 (min (count title) title-max))
     :status "live"
     ;; a comma in the title is Google Messages saying "more than one
     ;; person is here" — the only group signal the rig gives
     :chat_kind (if (> (count names) 1) "group" "direct")
     :participant_names names}))

;; ── the source ──────────────────────────────────────────────────────

(defn mirrorable? [th]
  (and (not (str/blank? (str (:hash th))))
       (not (str/blank? (str (:name th))))))

(defn- listing
  [{:keys [rpc-fn limit]}]
  (into {}
        (comp (filter mirrorable?)
              (map (juxt #(str (:hash %)) identity)))
        (gc/call rpc-fn tool {:limit (or limit gc/default-limit)})))

(defn- doc-for [{:keys [birth-fn]} th]
  (let [doc (thread->doc th)]
    (doseq [nm (:participant_names doc)] (birth-fn nm))
    doc))

(defrecord MessaSource [rpc-fn limit birth-fn]
  conf/ThreadSource
  (thread-discover [this]
    (into [] (map key) (listing this)))

  (thread-pull [this id]
    (if-some [th (get (listing this) (str id))]
      (let [doc (doc-for this th)]
        [doc (gc/content-etag doc translation-rev)])
      (throw (ex-info (str id " is not a thread the phone lists")
                      {:status 404}))))

  (thread-pull-many [this ids]
    (let [threads (listing this)]
      (into {}
            (map (fn [id]
                   [(str id)
                    (if-some [th (get threads (str id))]
                      (let [doc (doc-for this th)]
                        [doc (gc/content-etag doc translation-rev)])
                      :gone)]))
            ids))))

(defn source
  "The real boundary over Gate. config as tgram/source: :rpc-fn,
  :limit, :birth-fn."
  [{:keys [rpc-fn limit birth-fn]}]
  (->MessaSource rpc-fn limit (or birth-fn (constantly nil))))

(defn fake-source
  "The phone in memory: the REAL source over a scriptable Gate, as at
  tgram — the group-title split and the translation both run."
  ([] (fake-source (gc/fake-state)))
  ([state] (fake-source state {}))
  ([state opts] (source (assoc opts :rpc-fn (gc/fake-rpc state)))))
