(ns workqueue10.connections
  "The breaker panel (waymark-kyg.1): one row per external authority
  the queue drinks from, its health kept honest by the passes that
  already know it. Born from guest walk #5 — the gtasks token died on
  a Sunday night and the house could neither say so nor show where:
  rows went unreachable one at a time, and the only witness was a
  stderr line nobody reads.

  THE ROW is native and boot-seeded (ensure-connections!, the
  capability registry's idempotent shape): tag is the routing tag the
  confluence already speaks (\"gtasks\", \"todo\", \"calendar\"),
  provider names the credential family a later reconsent door
  (waymark-kyg.2) will hang off, and mode says out loud whether the
  wired source is real or the offline fake — a fallback that looks
  like nothing at all is the failure mode this kind exists to end.

  HEALTH arrives two ways, both funneling into report!: the
  confluence's per-tag fan outcomes (its :report-fn seam), and the
  engine's kind-level :report-pass hook mapped through tag-by-kind
  for adapters that are not confluences (the calendar's). A pass that
  answered writes last_answered and clears the failure run — a
  maintenance write, jobs/persist-data!'s discipline, because a
  5-second beat must not mint transitions. A pass that failed records
  the error and counts; the STATE flips only on the run's edges —
  dark after dark-after consecutive failures (one transient blip is
  not an outage), live on the first answer — so the transitions a
  person reads are the outage's own shape: audited, SSE-visible,
  subscribable.

  THE CREDENTIAL landed with the reconsent door (waymark-kyg.2):
  :refresh_token, declared :secret — the conceal-from-everyone
  disposition — written only by the door's system-only receive_token
  transitions and read only by the mint-time token-fn
  (google-refresh-token-fn). A refresh token spends ONLY at the OAuth
  client that minted it, so the deployment's mint client pair
  (WORKQUEUE10_GTASKS_CLIENT_ID / CALENDAR10_GOOGLE_CLIENT_ID) must
  be the same Web-application client the reconsent door consents
  through."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguardfn defhandler defresource]]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def dark-after
  "Consecutive failed passes before a live connection flips dark."
  2)

(def runner
  (t/principal {:id "workqueue10-connections" :type :system
                :display "Connection health"}))

(defguardfn health-is-the-panels
  {:reads [:principal]
   :hide true
   :explain "Connection rows and their health are the panel's own record; people read the breaker, the reconsent door is how they act on it."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(defhandler mark-dark-handler [row inp ctx]
  (update row :data assoc
          :last_error (:error inp)
          :failed_since (or (get-in row [:data :failed_since]) (:now ctx))))

(defhandler mark-live-handler [row _inp _ctx]
  (update row :data assoc
          :last_error nil
          :failed_since nil
          :consecutive_failures 0))

(defhandler receive-token-handler [row inp ctx]
  (update row :data assoc
          :refresh_token (:refresh_token inp)
          :reconsented_by (:reconsented_by inp)
          :reconsented_at (:now ctx)))

;; the revoke lever (waymark-kyg.2, finding #7 review): a hostile or
;; wrong reconsented token was only removable by a DB edit; this makes
;; a bad reconsent reversible from the app. Authority is the SYSTEM's
;; own (a health writer clearing a token it proved dead) OR the
;; recovery-admin — the same principal the door's own auth admits.
(defguardfn revoke-is-recovery-admins
  {:reads [:principal]
   :explain "Clearing a stored credential is the recovery-admin's lever (and the engine's own); the breaker is read by all, wiped by few."}
  [_row _inp ctx]
  (let [p (:principal ctx)]
    (if (or (= :system (:type p))
            (contains? (set (:roles p)) "recovery-admin"))
      (t/allow)
      (t/deny))))

(defhandler revoke-token-handler [row _inp _ctx]
  (update row :data assoc
          :refresh_token nil
          :reconsented_by nil
          :reconsented_at nil))

(def ^:private revoke-token-safety
  {:idempotent true :reversible false :confirm true
   :consequence "The stored refresh token is wiped; the source falls back to the env token if one is set, else reads dark until a fresh reconsent — a deliberate revoke, not an undo."})

(def ^:private receive-token-input
  [:map
   ;; a credential input: tell the UI never to render it in the clear
   [:refresh_token {:x-display {:hidden true}} [:string {:min 1 :max 512}]]
   [:reconsented_by {:optional true} [:maybe [:string {:max 200}]]]])

(def ^:private receive-token-edit
  {:fence false
   :unfenced-reason "A system-only credential landing from the reconsent door — no read preceded it to fence against."})

(def ^:private receive-token-safety
  {:idempotent true :reversible false :confirm false
   :one-way "Google already superseded the old token; there is nothing to put back."})

(defresource connection
  {:kind :connection
   :plural "connections"
   :states [:live :dark]
   :initial :live
   :terminal #{}
   :nav :system
   :summary "{data.tag} · {state}"
   :label-template "{data.tag}"
   :schema [:map
            [:tag [:string {:min 1 :max 40}]]
            ;; the credential family this connection spends —
            ;; "google" ties gtasks and calendar to the one refresh
            ;; token they share; the reconsent door keys off it
            [:provider {:optional true} [:maybe [:string {:max 40}]]]
            [:mode [:enum "real" "fake"]]
            [:last_answered {:optional true} [:maybe :waymark/instant]]
            [:last_error {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 500}]]]
            [:failed_since {:optional true} [:maybe :waymark/instant]]
            [:consecutive_failures {:optional true} [:maybe :int]]
            ;; the reconsent door's landing (waymark-kyg.2): :secret
            ;; is the conceal-from-everyone disposition — the token
            ;; is spent by the mint-time token-fn, never rendered
            [:refresh_token {:optional true :secret true}
             [:maybe [:string {:max 512}]]]
            [:reconsented_by {:optional true} [:maybe [:string {:max 200}]]]
            [:reconsented_at {:optional true} [:maybe :waymark/instant]]]
   :filterable {:state #{:eq :in} :tag #{:eq} :provider #{:eq}
                :mode #{:eq}}
   :sortable {:fields [:tag] :default "tag"}
   ;; the affordance: a provider-bearing row advertises its own
   ;; reconsent door (provider-less rows drop the link — the
   ;; placeholder has nothing to fill). :external — the browser walks
   ;; through it, not the in-app router.
   :links [{:rel "reconsent"
            :href "/auth/{data.provider}/reconsent?connection={id}"
            :summary "One consent click mints this credential afresh"
            :external true}]
   :create-guards [health-is-the-panels]
   :actions
   {:mark_dark
    {:from #{:live} :to :dark
     :input [:map [:error {:optional true :x-display {:raw true}}
                   [:maybe [:string {:max 500}]]]]
     :guards [health-is-the-panels]
     :edit {:fence false
            :unfenced-reason "A system-only health flip inside the discovery beat — no read preceded it to fence against."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The passes keep reporting; the first answer flips it back."}
     :handler mark-dark-handler
     :display {:label "Went dark"}}
    :mark_live
    {:from #{:dark} :to :live
     :guards [health-is-the-panels]
     :edit {:fence false
            :unfenced-reason "A system-only health flip inside the discovery beat — no read preceded it to fence against."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Darkness returning is its own transition, not an undo."}
     :handler mark-live-handler
     :display {:label "Back live"}}
    ;; the reconsent door's landing, STATE-PRESERVING by construction:
    ;; an action's :to is one state, so a same-state act from two
    ;; states is spelled as one self-loop per state (the editor
    ;; sugar's own _in_<state> convention) — a dark row stays dark
    ;; until the next pass answers honestly; the door never
    ;; force-flips the breaker it exists to fix
    :receive_token_in_live
    {:from #{:live} :to :live
     :input receive-token-input
     :guards [health-is-the-panels]
     :edit receive-token-edit
     :safety receive-token-safety
     :handler receive-token-handler
     :display {:label "Token received"}}
    :receive_token_in_dark
    {:from #{:dark} :to :dark
     :input receive-token-input
     :guards [health-is-the-panels]
     :edit receive-token-edit
     :safety receive-token-safety
     :handler receive-token-handler
     :display {:label "Token received"}}
    ;; the revoke lever, spelled per-state like receive_token above (an
    ;; action's :to is one state, so a same-state clear is a self-loop)
    :revoke_token_in_live
    {:from #{:live} :to :live
     :guards [revoke-is-recovery-admins]
     :safety revoke-token-safety
     :handler revoke-token-handler
     :display {:label "Revoke stored token" :style :danger}}
    :revoke_token_in_dark
    {:from #{:dark} :to :dark
     :guards [revoke-is-recovery-admins]
     :safety revoke-token-safety
     :handler revoke-token-handler
     :display {:label "Revoke stored token" :style :danger}}}})

;; ── the reporter ────────────────────────────────────────────────────

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "workqueue10 connections: " parts))))

(defn- row-by-tag [eng tag]
  (let [st (:storage eng)
        raw (store/with-tx st
              (fn [tx]
                (first (store/query-rows st tx :connection
                                         {:tag (str tag)} {:limit 1}))))]
    (when raw
      (inv/decode-row (get (inv/resources eng) :connection) raw))))

(defn- persist!
  "The maintenance write, guarded against a concurrent reconsent
  (waymark-kyg.2): update-data! rewrites the WHOLE document, so a
  health pass whose snapshot predates a receive_token write would
  clobber :refresh_token back to absent — and passes fire hardest
  while the row is dark, exactly when someone reconsents. So the
  health keys merge onto a FRESH read taken FOR UPDATE inside this
  same write tx; nothing is carried from report!'s stale snapshot but
  the health keys this reporter owns."
  [eng row health]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) :connection)]
    (store/with-tx st
      (fn [tx]
        (when-some [fresh (store/load-row st tx :connection (:id row)
                                          {:for-update true})]
          (let [current (:data (inv/decode-row rdef fresh))]
            (store/update-data! st tx :connection (:id row)
                                (schema/encode (:schema rdef)
                                               (merge current health))
                                nil)))))))

(defn- flip! [eng row action input]
  (try
    (inv/invoke! eng :connection (:id row) action input
                 {:principal runner})
    (catch Exception e
      ;; two passes racing the same edge: the loser's wrong-state 409
      ;; is the machine agreeing with the winner
      (warn! "health flip " (name action) " for "
             (get-in row [:data :tag]) " did not land ("
             (ex-message e) ")"))))

(defn- brief [error]
  (let [s (str error)]
    (if (< 500 (count s)) (subs s 0 500) s)))

(defn report!
  "One pass outcome for one tag. Counters are maintenance writes on
  every report; the state flips only on the run's edges."
  [eng tag ok? error]
  (when-some [row (row-by-tag eng tag)]
    (let [now ((:now-fn eng))
          data (:data row)]
      (if ok?
        (do (persist! eng row {:last_answered now
                               :last_error nil
                               :failed_since nil
                               :consecutive_failures 0})
            (when (= :dark (:state row))
              (flip! eng row :mark_live nil)))
        (let [n (inc (long (or (:consecutive_failures data) 0)))]
          (persist! eng row {:last_error (brief error)
                             :failed_since (or (:failed_since data) now)
                             :consecutive_failures n})
          (when (and (= :live (:state row)) (<= dark-after n))
            (flip! eng row :mark_dark {:error (brief error)})))))))

;; ── the reconsent door's landing (waymark-kyg.2) ────────────────────

(defn connection-by-id
  "The decoded connection row, or nil."
  [eng id]
  (let [st (:storage eng)
        raw (store/with-tx st
              (fn [tx] (store/load-row st tx :connection id nil)))]
    (some->> raw
             (inv/decode-row (get (inv/resources eng) :connection)))))

(defn receive-token!
  "The door's write: the fresh refresh token onto the row via the
  audited receive_token transition, invoked as the runner — the row's
  own state picks the self-loop spelling, so the state never moves.
  Returns the invoked row, nil when the connection is gone."
  [eng id {:keys [refresh-token reconsented-by]}]
  (when-some [row (connection-by-id eng id)]
    (inv/invoke! eng :connection id
                 (if (= :dark (:state row))
                   :receive_token_in_dark
                   :receive_token_in_live)
                 {:refresh_token refresh-token
                  :reconsented_by reconsented-by}
                 {:principal runner})))

(defn stored-refresh-token
  "The freshest reconsented refresh token any provider=google row
  holds — gtasks and the calendar share the one credential family, so
  both drink from whichever row the household reconsented last. nil
  when no row carries one."
  [eng]
  (let [rdef (get (inv/resources eng) :connection)
        st (:storage eng)
        raws (store/with-tx st
               (fn [tx]
                 (store/query-rows st tx :connection
                                   {:provider "google"} {})))]
    (->> raws
         (map #(inv/decode-row rdef %))
         (keep (fn [row]
                 (let [t (get-in row [:data :refresh_token])]
                   (when-not (str/blank? (str t))
                     [(get-in row [:data :reconsented_at]) t]))))
         (sort-by first #(compare %2 %1))
         first
         second)))

(defn google-refresh-token-fn
  "The row-first credential read for calendar10.oauth's
  :refresh-token-fn seam: at mint time the stored (reconsented) token
  wins and the env value backstops it. eng-ref derefs late — the
  token-fn is built before the engine boots (main.clj's engine-ref
  discipline). A refresh token spends only at the client that minted
  it: the mint client pair must be the reconsent door's own client.

  The row read is caught (waymark-kyg.2, finding #6 review): a DB
  hiccup or one malformed row must NOT take down a source whose env
  token was valid — mint time falls through to env on any failure,
  warned once so a persistent fault is visible without flooding the
  log every refresh-skew window."
  [eng-ref env-token]
  (let [warned (atom false)]
    (fn []
      (or (try
            (when-some [eng @eng-ref] (stored-refresh-token eng))
            (catch Exception e
              (when (compare-and-set! warned false true)
                (warn! "row-first refresh-token read failed; falling back to "
                       "the env token (" (ex-message e) ")"))
              nil))
          env-token))))

(defn fan-reporter
  "The confluence's :report-fn — per-tag outcomes from its fan
  passes. eng-ref derefs late: the confluence is built before the
  engine boots (main.clj's engine-ref discipline)."
  [eng-ref]
  (fn [{:keys [tag ok? error]}]
    (when-some [eng @eng-ref]
      (report! eng tag ok? error))))

(defn pass-reporter
  "The engine's :report-pass hook — kind-level outcomes from mirror
  passes, mapped to the tag whose adapter serves that kind. Kinds the
  map does not name (the confluence-fed ones, already reported
  per-tag) say nothing here."
  [eng-ref tag-by-kind]
  (fn [{:keys [kind ok? error]}]
    (when-some [tag (get tag-by-kind kind)]
      (when-some [eng @eng-ref]
        (report! eng tag ok? error)))))

(defn ensure-connections!
  "The boot seed, the capability registry's idempotent shape: one row
  per wired authority, created only when absent, never overwritten —
  health and state are the passes' to keep from here on.
  descriptors: {tag {:provider … :mode \"real\"|\"fake\"}}."
  [eng descriptors]
  (let [boot (t/principal {:id "workqueue10-boot" :type :system
                           :display "Boot seed"})]
    (doseq [[tag {:keys [provider mode]}] descriptors]
      (when-not (row-by-tag eng tag)
        (try
          (inv/create! eng :connection
                       (cond-> {:tag (str tag) :mode (or mode "real")}
                         provider (assoc :provider provider))
                       {:principal boot})
          (catch Exception e
            (warn! "seed for " tag " failed (" (ex-message e) ")")))))))
