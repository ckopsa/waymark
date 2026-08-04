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

  NO CREDENTIAL rests here yet: that is slice 3's argument to have
  (the framework has no conceal-from-everyone disposition today, and
  a token in a row everyone can GET is worse than a token in env)."
  (:require [waymark10.dsl :refer [defguardfn defhandler defresource]]
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
            [:consecutive_failures {:optional true} [:maybe :int]]]
   :filterable {:state #{:eq :in} :tag #{:eq} :provider #{:eq}
                :mode #{:eq}}
   :sortable {:fields [:tag] :default "tag"}
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
     :display {:label "Back live"}}}})

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

(defn- persist! [eng row data]
  (let [rdef (get (inv/resources eng) :connection)]
    (store/with-tx (:storage eng)
      (fn [tx]
        (store/update-data! (:storage eng) tx :connection (:id row)
                            (schema/encode (:schema rdef) data) nil)))))

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
        (do (persist! eng row (assoc data
                                     :last_answered now
                                     :last_error nil
                                     :failed_since nil
                                     :consecutive_failures 0))
            (when (= :dark (:state row))
              (flip! eng row :mark_live nil)))
        (let [n (inc (long (or (:consecutive_failures data) 0)))]
          (persist! eng row (assoc data
                                   :last_error (brief error)
                                   :failed_since (or (:failed_since data) now)
                                   :consecutive_failures n))
          (when (and (= :live (:state row)) (<= dark-after n))
            (flip! eng row :mark_dark {:error (brief error)})))))))

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
