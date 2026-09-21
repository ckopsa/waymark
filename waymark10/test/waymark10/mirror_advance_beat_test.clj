(ns waymark10.mirror-advance-beat-test
  "The ADVANCE BEAT (waymark-fp62.18.3): the cadence that makes an
  advance door quick.

  A door that opens only when the mirror LOOKS is as slow as the
  looking, and the looking was the whole-kind heal (:resync-every, an
  hour for the queue's conversations) or a read past the TTL. A
  mention of the house asks for an answer at once, so a kind that
  declares :advances may declare :advance-every N seconds: on that
  cadence the elected discovery daemon asks the adapter for the
  ADVANCE LISTING alone — external id → {field instant} — and
  refreshes only the rows whose instant moved forward.

  What this suite proves:

  - one beat over a listing where ONE chat's mention moved refreshes
    that row alone: its instant lands, its advance door is recorded
    once, and the chat that did not move is not touched at all (no
    transition, not even the freshness stamp a pull would leave).
  - the beat refreshes INSIDE the TTL. The same row, read through the
    ordinary pull-through, serves its stored truth — which is the
    pull-through's own rule, and the reason the beat needed a way to
    ask past it.
  - a listing that throws costs the beat that pass and nothing else:
    every row stands exactly as it was, and the next beat asks again.
  - the door is a MOVE and not a level: a second beat over the same
    listing opens nothing.
  - an adapter that cannot answer the listing is SKIPPED — `beats?`
    says so once, at boot, and the beat itself writes nothing.
  - the declaration refuses :advance-every without :advances, and
    refuses a cadence no clock can read.

  No database: the in-memory Storage twin hosts it."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.wire :as wire]))

;; ── the scriptable rig ──────────────────────────────────────────────
;;
;; It answers the whole document AND the advance listing, which is the
;; pair the beat is made of: the listing says where to look and the
;; pull says what is true.

(defrecord ChatRig [state]
  mirror/MirrorAdapter
  (discover [_] (vec (sort (keys (:docs @state)))))
  (pull [_ xid]
    (if-some [doc (get-in @state [:docs xid])]
      [doc (wire/digest doc)]
      (throw (ex-info (str xid " is not a chat this rig lists")
                      {:status 404}))))
  (pull-many [_ xids]
    (into {}
          (map (fn [xid]
                 [xid (if-some [doc (get-in @state [:docs xid])]
                        [doc (wire/digest doc)]
                        :gone)]))
          xids))
  (push [_ _ _]
    (throw (ex-info "the house does not write a conversation" {})))

  mirror/MirrorAdvanceAdapter
  (advance-listing [_]
    (when (:listing-down @state)
      (throw (ex-info "the bot rig is not answering" {})))
    (into {}
          (keep (fn [[xid doc]]
                  (when-some [m (:last_mention_at doc)]
                    [xid {:last_mention_at m}])))
          (:docs @state))))

;; the rig that hears conversations and cannot answer which of them
;; named the house — the wiring the beat skips
(defrecord DeafRig [state]
  mirror/MirrorAdapter
  (discover [_] (vec (sort (keys (:docs @state)))))
  (pull [_ xid]
    (if-some [doc (get-in @state [:docs xid])]
      [doc (wire/digest doc)]
      (throw (ex-info (str xid " is gone") {:status 404}))))
  (pull-many [_ xids]
    (into {} (map (fn [xid] [xid (if-some [doc (get-in @state [:docs xid])]
                                   [doc (wire/digest doc)]
                                   :gone)]))
          xids))
  (push [_ _ _] (throw (ex-info "pull-only" {}))))

(defn- rig [] (->ChatRig (atom {:docs {} :listing-down false})))

(defn- say!
  "Put a chat's whole document in the rig."
  [r xid doc]
  (swap! (:state r) assoc-in [:docs xid] doc))

(defn- listing-down! [r down?]
  (swap! (:state r) assoc :listing-down down?))

;; ── the kind ────────────────────────────────────────────────────────

(def ^:private chat-fields
  [:map
   [:title {:optional true
            :examples ["Meal plans"]
            :x-display {:label "What the conversation is called"
                        :help "The name the rig shows for this chat."}}
    [:maybe [:string {:max 80}]]]
   [:last_message_at {:optional true
                      :x-display
                      {:label "When something was last said"
                       :help "The rig's own time for the last message here."}}
    [:maybe :waymark/instant]]
   [:last_mention_at {:optional true
                      :x-display
                      {:label "When the house was last spoken to"
                       :help "The time of the last message that named the house."}}
    [:maybe :waymark/instant]]])

(defn- chat-kind
  ([adapter] (chat-kind adapter {:advance-every 20}))
  ([adapter extra]
   (r/resource
    (mirror/declaration
     {:kind :beat_chat
      :plural "beat_chats"
      :summary "{data.title}"
      :schema chat-fields
      :filterable {:state #{:eq :in}}}
     (merge {:adapter adapter
             :ttl-seconds 900
             :discover-every 3600
             :advances {:observe_mention
                        {:field :last_mention_at
                         :label "Observed a mention of the house"
                         :help "The rig heard a message that named the house."}}}
            extra)))))

;; ── the world ───────────────────────────────────────────────────────

(defn- boot [adapter]
  (inv/engine {:storage (memory/storage)
               :resources [(chat-kind adapter)]}))

(defn- row-of [eng xid]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (first (store/query-rows st tx :beat_chat {:external_id xid}
                                 {:limit 1}))))))

(defn- actions-on [eng xid]
  (let [st (:storage eng)]
    (mapv :action
          (store/with-tx st
            (fn [tx]
              (store/transitions st tx {:kind :beat_chat
                                        :resource-id (str (:id (row-of eng xid)))}
                                 {}))))))

(defn- counted [eng xid action]
  (count (filterv #{action} (actions-on eng xid))))

(defn- mention-of [eng xid]
  (str (get-in (row-of eng xid) [:data :last_mention_at])))

(def ^:private bros
  {:title "Bros." :last_message_at "2026-09-21T18:02:11Z"
   :last_mention_at "2026-09-21T17:58:40Z"})

(def ^:private meals
  {:title "Meal plans" :last_message_at "2026-09-21T17:40:00Z"
   :last_mention_at "2026-09-21T17:10:00Z"})

(defn- world
  "A rig with two chats, both mirrored and both filled — the state a
  beat meets on a running house."
  []
  (let [r (rig)]
    (say! r "c-bros" bros)
    (say! r "c-meals" meals)
    (let [eng (boot r)]
      (mirror/discover! eng :beat_chat)
      [eng r])))

;; ── one beat, one moved row ─────────────────────────────────────────

(deftest a-beat-refreshes-only-what-moved
  (let [[eng r] (world)
        before-untouched (actions-on eng "c-bros")]

    (testing "a birth opens no door: the rows carry the rig's mentions
              and nobody was woken by the back catalogue"
      (is (= "2026-09-21T17:10:00Z" (mention-of eng "c-meals")))
      (is (zero? (counted eng "c-meals" :observe_mention))))

    (say! r "c-meals" (assoc meals
                             :last_message_at "2026-09-21T18:26:00Z"
                             :last_mention_at "2026-09-21T18:26:00Z"))

    (testing "the pull-through's own rule is untouched: the row is
              fresh inside its TTL, so a read serves the stored truth
              and the mention sits unseen"
      (let [rdef (get (inv/resources eng) :beat_chat)
            row (inv/decode-row rdef (row-of eng "c-meals"))]
        (is (= "2026-09-21T17:10:00Z"
               (str (get-in (mirror/refresh! eng rdef row)
                            [:data :last_mention_at]))))
        (is (= "2026-09-21T17:10:00Z" (mention-of eng "c-meals")))))

    (testing "one beat: the listing named two chats and ONE of them
              moved"
      (is (= {:listed 2 :moved 1} (mirror/advance-beat! eng :beat_chat))))

    (testing "the moved row carries the new instant, and its advance
              door is recorded once beside the observe"
      (is (= "2026-09-21T18:26:00Z" (mention-of eng "c-meals")))
      (is (= 1 (counted eng "c-meals" :observe_mention)))
      (is (= 2 (counted eng "c-meals" :observe_external))
          "the birth's fill, and the beat's own"))

    (testing "and the chat that did not move is not touched at all —
              not even the freshness stamp a pull would leave"
      (is (= before-untouched (actions-on eng "c-bros")))
      (is (= "2026-09-21T17:58:40Z" (mention-of eng "c-bros"))))

    (testing "the door is a MOVE and not a level: a second beat over
              the same listing opens nothing"
      (is (= {:listed 2 :moved 0} (mirror/advance-beat! eng :beat_chat)))
      (is (= 1 (counted eng "c-meals" :observe_mention))))))

;; ── a rig that will not answer ──────────────────────────────────────

(deftest a-listing-that-throws-costs-one-pass
  (let [[eng r] (world)
        before (actions-on eng "c-meals")]
    (say! r "c-meals" (assoc meals :last_mention_at "2026-09-21T18:26:00Z"))
    (listing-down! r true)

    (testing "the beat says nothing happened, and every row stands
              exactly as it was"
      (is (nil? (mirror/advance-beat! eng :beat_chat)))
      (is (= before (actions-on eng "c-meals")))
      (is (= "2026-09-21T17:10:00Z" (mention-of eng "c-meals"))))

    (testing "…and the next beat asks again — the move is still in the
              listing when the rig comes back"
      (listing-down! r false)
      (is (= {:listed 2 :moved 1} (mirror/advance-beat! eng :beat_chat)))
      (is (= "2026-09-21T18:26:00Z" (mention-of eng "c-meals")))
      (is (= 1 (counted eng "c-meals" :observe_mention))))))

;; ── an adapter that cannot answer the listing ───────────────────────

(deftest an-adapter-without-the-verb-is-skipped

  (testing "a kind whose adapter answers the listing beats"
    (is (mirror/beats? (chat-kind (rig)))))

  (testing "a kind that declared no cadence does not beat, however
            able its adapter is"
    (is (not (mirror/beats? (chat-kind (rig) {})))))

  (let [deaf (->DeafRig (atom {:docs {"c-meals" meals}}))
        eng (inv/engine {:storage (memory/storage)
                         :resources [(chat-kind deaf)]})]
    (mirror/discover! eng :beat_chat)
    (testing "a rig that cannot say which chats named the house is
              skipped — the daemon names it once at boot, and the beat
              itself writes nothing"
      (is (not (mirror/beats? (chat-kind deaf))))
      (is (nil? (mirror/advance-beat! eng :beat_chat)))
      (is (= 1 (counted eng "c-meals" :observe_external)))
      (is (zero? (counted eng "c-meals" :observe_mention))))))

;; ── the declaration's own refusals ──────────────────────────────────

(deftest the-cadence-refuses-what-it-cannot-serve
  (testing "a cadence no clock can read refuses at the def site"
    (doseq [bad [0 -20 "20s" 20.5]]
      (is (thrown-with-msg?
           Exception #"positive number of seconds"
           (chat-kind (rig) {:advance-every bad}))
          (pr-str bad))))

  (testing "and a beat on a kind with NO advance door refuses, naming
            the remedy: the beat asks which instants moved, and this
            kind declares no instant to ask about"
    (is (thrown-with-msg?
         Exception #":advance-every rides :advances"
         (r/resource
          (mirror/declaration
           {:kind :beat_chat :plural "beat_chats" :summary "{data.title}"
            :schema chat-fields}
           {:adapter (rig) :ttl-seconds 900 :discover-every 3600
            :advance-every 20}))))))
