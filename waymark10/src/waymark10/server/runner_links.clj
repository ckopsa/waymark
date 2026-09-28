(ns waymark10.server.runner-links
  "The runner link: one provider endpoint a run can be started through,
  and the credential that opens it (runner-pool work, piece 1a).

  A person makes the Routine by hand and pastes three things here:
  the provider, the fire URL and the token. The token is held the way
  the schedule's and the model's `link` hold theirs (R-12.11): a
  `:secret` field, never rendered, never filterable, and never in a
  transition's recorded inputs — so neither `create` nor `restate`
  records. A `restate` without a token keeps the token that stands.

  The live-state fields (`retry_after`, `window_started_at`,
  `runs_in_window`, `last_fired_at`) are the engine's: `fire-link!`
  fires through a provider (schedules/Provider, piece 1b) and writes
  what it answered, through three hidden doors. The link's liveness is
  the row's own state, `live` or `broken`, beside `retired`.

  `ensure-seeded-links!` (piece 1c) is the boot seed: one link for
  each model and each schedule that holds its own fire URL and token,
  recorded by `seeded_from` so a second boot makes none. The source
  rows keep their own links; nothing fires through a seeded link yet.

  `fire-pool!` (d16b71bf) chooses among a pool's links: it skips a
  link that is waiting and takes the least-used of the rest."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.schedules :as sch]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def providers ["claude_routine"])

(defn- a-persons-hand?
  "A person, or a tool that person is signed in to — the rule the
  schedule's and the model's `link` doors keep."
  [ctx]
  (let [{:keys [type acts-for]} (:principal ctx)]
    (or (= :human type)
        (and (= :agent type) (not (str/blank? (str acts-for)))))))

(g/defguard a-person-makes-the-link
  {:reads [:principal]
   :explain "A runner link is a person's to make. A person makes the Routine by hand, and a person — or a tool that person is signed in to — pastes its fire URL and its token here. An agent does not make a link; the engine copies only a link a person already pasted onto a model or a schedule."}
  [_row _inp ctx]
  (if (or (a-persons-hand? ctx)
          (= :system (get-in ctx [:principal :type])))
    (t/allow) (t/deny)))

(g/defguard a-person-writes-the-token
  {:judges [:token]
   :reads [:principal]
   :explain "A new token is a person's to paste. Restate the link without a token, and the one it holds stays."}
  [_row inp ctx]
  (if (or (nil? (:token inp)) (a-persons-hand? ctx))
    (t/allow)
    (t/deny)))

(g/defguard the-engine-writes-the-fire
  {:reads [:principal]
   :hide true
   :explain "A link's live state is the engine's record of what the provider last answered."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type]))
    (t/allow) (t/deny)))

(def ^:private engine-writes
  {:idempotent true :reversible false :confirm false
   :one-way "Bookkeeping the engine writes as it fires through the link; the next fire moves the row again."})

(defhandler stamp-fire
  [row inp _ctx]
  (update row :data
          (fn [data]
            (-> (merge data (select-keys inp [:last_fired_at :window_started_at
                                              :runs_in_window]))
                (dissoc :retry_after)))))

(defhandler hold-throttle
  [row inp _ctx]
  (assoc-in row [:data :retry_after] (:retry_after inp)))

(defhandler restate-link
  [row inp _ctx]
  (update row :data
          (fn [data]
            (cond-> (merge data (select-keys inp [:provider :fire_url :cap]))
              (some? (:token inp)) (assoc :fire_token (:token inp))))))

(def ^:private provider-field
  [:provider {:x-display
              {:label "The provider"
               :help "Whose endpoint the fire URL is. A Claude Routine is the one provider today."
               :choices {"claude_routine" "A Claude Routine, made by hand and fired by URL."}}}
   (into [:enum] providers)])

(def ^:private fire-url-field
  [:fire_url {:x-display
              {:raw true
               :label "The fire URL"
               :help "The one endpoint that starts a run, copied from the Routine's own page. It carries the Routine's id, which is not a secret."}}
   [:string {:min 1 :max 400}]])

(def ^:private cap-field
  [:cap {:optional true
         :x-display
         {:label "How often it may fire"
          :help "At most this many runs in each window of this many seconds. Leave it empty for no cap."}}
   [:maybe [:map
            [:runs {:x-display {:label "Runs"
                                :help "The most runs in one window."}}
             [:int {:min 1 :max 100000}]]
            [:window_seconds {:x-display {:label "Window, in seconds"
                                          :help "How long one window lasts."}}
             [:int {:min 1 :max 31622400}]]]]])

(def ^:private seeded-from-field
  [:seeded_from {:optional true
                 :x-display
                 {:hidden true
                  :label "Copied from"
                  :help "The model or schedule whose own link the engine copied this one from at boot, as kind:id. Empty for a link a person made here."}}
   [:maybe [:string {:min 1 :max 200}]]])

(defresource runner-link
  {:kind :runner_link
   :plural "runner_links"
   :states [:live :broken :retired]
   :initial :live
   :terminal #{}                       ; retirement is reversible, deliberately
   ;; :broken is the engine's to write: `fire-link!` marks a link
   ;; broken through the hidden `break` door when the provider answers
   ;; that the link is bad.
   :nav :system
   :summary "{data.provider} · {state}"
   :label-template "{data.provider} link"
   :schema
   [:map
    provider-field
    fire-url-field
    ;; THE TOKEN IS HELD AS THE SCHEDULE'S IS (R-12.11): never
    ;; rendered, never filterable, and never in a transition's recorded
    ;; inputs — which is why no door here records.
    [:fire_token {:secret true
                  :x-display
                  {:hidden true
                   :label "The token"
                   :spelled-by-hand "Written when the link is made and when a restate carries a new one; never shown again."}}
     [:string {:min 16 :max 400}]]
    cap-field
    [:retry_after {:optional true
                   :x-display
                   {:label "Free again at"
                    :help "The provider throttled the last fire and named this time; nothing fires through this link before it. Engine-written."}}
     [:maybe :waymark/instant]]
    [:window_started_at {:optional true
                         :x-display
                         {:label "This window began"
                          :help "When the cap's current window opened. Engine-written."}}
     [:maybe :waymark/instant]]
    [:runs_in_window {:optional true
                      :x-display
                      {:label "Runs this window"
                       :help "How many runs this link has started in the cap's current window. Engine-written."}}
     [:maybe [:int {:min 0}]]]
    [:last_fired_at {:optional true
                     :x-display
                     {:label "Last fired"
                      :help "When the engine last started a run through this link. Engine-written."}}
     [:maybe :waymark/instant]]
    seeded-from-field]
   :create-schema
   [:map
    provider-field
    fire-url-field
    [:fire_token {:secret true
                  :x-display
                  {:raw true
                   :label "The token"
                   :help "The credential that opens that one Routine. The engine holds it and never shows it again."}}
     [:string {:min 16 :max 400}]]
    cap-field
    seeded-from-field]
   :filterable {:state #{:eq :in}
                :provider #{:eq :in}
                :seeded_from #{:eq}}
   :sortable {:fields [:created_at :updated_at] :default "-created_at"}
   :create-guards [a-person-makes-the-link]
   :actions
   {:restate
    {:from #{:live :broken} :to :live
     :input [:map
             [:provider {:optional true
                         :x-display
                         {:label "The provider"
                          :help "Whose endpoint the fire URL is."
                          :choices {"claude_routine" "A Claude Routine, made by hand and fired by URL."}}}
              (into [:enum] providers)]
             [:fire_url {:optional true
                         :x-display
                         {:raw true
                          :label "The fire URL"
                          :help "The endpoint that starts a run, copied from the Routine's own page."}}
              [:string {:min 1 :max 400}]]
             cap-field
             [:token {:optional true
                      :x-display
                      {:raw true
                       :label "A new token"
                       :help "Paste one only to replace the token the link holds. Leave it empty and the old one stays."}}
              [:maybe [:string {:min 16 :max 400}]]]]
     ;; NOT :record: this input may carry the credential, and a
     ;; recorded action persists its raw inputs into the log.
     :guards [a-person-writes-the-token]
     :edit {:prefill [:provider :fire_url :cap] :fence true}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The link holds what this restate says; a new token replaces the old one, which is not kept."}
     :handler restate-link
     :display {:label "Restate the link" :order 1
               :description "Change the fire URL or the cap, or paste a new token"}}

    :retire
    {:from #{:live :broken} :to :retired
     ;; Not :reversible: restore brings a link back :live, never
     ;; :broken, so a broken link's retirement has no exact undo.
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Restore brings the link back live; a link retired while broken does not return broken."}
     :display {:label "Retire" :style :danger :order 9
               :description "Nothing fires through this link until it is restored"}}

    :restore
    {:from #{:retired} :to :live
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Restore" :order 2}}

    ;; the fire's landing (1b). Hidden and engine-written: a run
    ;; started, so the link is good and its window counts one more.
    :fired
    {:from #{:live :broken} :to :live
     :input [:map
             [:last_fired_at {:x-display {:hidden true}} :waymark/instant]
             [:window_started_at {:x-display {:hidden true}} :waymark/instant]
             [:runs_in_window {:x-display {:hidden true}} [:int {:min 0}]]]
     :record true
     :guards [the-engine-writes-the-fire]
     :edit {:prefill [:last_fired_at] :fence false
            :unfenced-reason
            "Stamped by the fire the moment the provider answered; no read preceded it to fence against."}
     :safety engine-writes
     :handler stamp-fire
     :display {:label "Run started"}}

    ;; the provider has no free run and named a time: the link stays
    ;; live, and nothing fires through it before `retry_after`.
    :throttle
    {:from #{:live} :to :live
     :input [:map
             [:retry_after {:x-display {:hidden true}} :waymark/instant]]
     :record true
     :guards [the-engine-writes-the-fire]
     :edit {:prefill [:retry_after] :fence false
            :unfenced-reason
            "Written by the fire the moment the provider throttled it; no read preceded it to fence against."}
     :safety engine-writes
     :handler hold-throttle
     :display {:label "Provider throttled"}}

    ;; the provider answered that the link itself is bad. A restate
    ;; with a good URL or token brings it back live.
    :break
    {:from #{:live} :to :broken
     :guards [the-engine-writes-the-fire]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The link fires nothing until a person restates it."}
     :display {:label "Link refused" :style :danger}}}})

;; ── the boot seed (piece 1c) ────────────────────────────────────────

(def seed-actor
  "The actor the boot seed wears."
  (t/principal {:id "waymark10-runner-links" :type :system
                :display "Runner links"}))

(defn- rows-of [eng kind where]
  (if (contains? (inv/resources eng) kind)
    (store/with-tx (:storage eng)
      (fn [tx] (store/query-rows (:storage eng) tx kind where {:limit 100000})))
    []))

(defn ensure-seeded-links!
  "The boot seed: for each model and each schedule that holds its own
  fire URL and token, one runner link with provider claude_routine,
  that URL and a copy of the token, recorded by `seeded_from`
  (`model:<id>` or `schedule:<id>`) so running it again makes none.
  The source rows keep their own links untouched."
  [eng]
  (when (contains? (inv/resources eng) :runner_link)
    (doseq [kind [:model :schedule]
            r (rows-of eng kind {})
            :let [url (some-> (get-in r [:data :fire_url]) str not-empty)
                  token (some-> (get-in r [:data :fire_token]) str not-empty)
                  from (str (name kind) ":" (:id r))]
            :when (and url token
                       (empty? (rows-of eng :runner_link {:seeded_from from})))]
      (try
        (inv/create! eng :runner_link
                     {:provider "claude_routine"
                      :fire_url url
                      :fire_token token
                      :seeded_from from}
                     {:principal seed-actor})
        (catch Exception e
          (binding [*out* *err*]
            (println (str "waymark10 runner links: seed from " from
                          " failed — " (ex-message e)))))))))

;; ── firing through a link (piece 1b) ────────────────────────────────

(defn- instant-of
  "An instant, however the row spells it — a stored string or an
  Instant. Unparsable is nil."
  ^Instant [v]
  (cond
    (instance? Instant v) v
    (some-> v str not-empty) (try (Instant/parse (str v))
                                  (catch Exception _ nil))))

(defn window-after
  "The cap's window once one more run starts at `at`: the open window
  counts one more, and a closed one (or none yet) opens at `at` with
  one. A link with no cap keeps one window, opened at its first fire."
  [data ^Instant at]
  (let [started (instant-of (:window_started_at data))
        secs (some-> (get-in data [:cap :window_seconds]) long)
        open? (and started
                   (or (nil? secs)
                       (.isBefore at (.plusSeconds started secs))))]
    (if open?
      {:window_started_at (str started)
       :runs_in_window (inc (long (or (:runs_in_window data) 0)))}
      {:window_started_at (str at) :runs_in_window 1})))

(defn- act! [eng row action body]
  (try
    (:row (inv/invoke! eng :runner_link (str (:id row)) action body
                       {:principal sch/system-actor}))
    (catch Exception e
      ;; the row may have moved under the fire (retired meanwhile);
      ;; the provider's answer still stands and is returned
      (binding [*out* *err*]
        (println (str "waymark10 runner links: link " (:id row)
                      " could not record " action " — " (ex-message e))))
      nil)))

(defn fire-link!
  "Fire one run through `link-row` by `provider` (a schedules/Provider),
  with `text`, and write what it answered onto the link: `started`
  stamps `last_fired_at` and counts the run in the window, `throttled`
  records `retry_after`, and `bad-link` marks the link broken. Answers
  the provider's answer."
  [eng provider link-row text]
  (let [answer (sch/fire provider (:data link-row) text)
        at (or (instant-of ((:now-fn eng))) (Instant/now))]
    (cond
      (contains? answer :started)
      (act! eng link-row :fired
            (merge {:last_fired_at (str at)}
                   (window-after (:data link-row) at)))

      (contains? answer :throttled)
      (act! eng link-row :throttle
            {:retry_after (str (sch/retry-instant at (:throttled answer)
                                                  (:body answer)))})

      :else
      (act! eng link-row :break nil))
    answer))

;; ── the pool (waymark ticket d16b71bf) ─────────────────────────────
;; A model or a schedule names `runners`, an ordered list of link ids.
;; A fire skips a link that is not live, whose `retry_after` is still
;; ahead, or whose cap is spent in its open window, and takes the one
;; with the fewest runs in its window; list order breaks a tie.

(defn- live? [row]
  (= "live" (some-> (:state row) name)))

(defn- used
  "The runs this link has started in its window as it stands at `at`."
  [data ^Instant at]
  (dec (long (:runs_in_window (window-after data at)))))

(defn waiting-until
  "The instant after `at` before which this link fires nothing — its
  `retry_after`, or the close of a window whose cap is spent — or nil
  when it may fire now."
  [row ^Instant at]
  (let [data (:data row)
        runs (some-> (get-in data [:cap :runs]) long)
        secs (some-> (get-in data [:cap :window_seconds]) long)
        started (instant-of (:window_started_at data))
        closes (when (and runs secs started (>= (used data at) (long runs)))
                 (.plusSeconds ^Instant started (long secs)))]
    (->> [(instant-of (:retry_after data)) closes]
         (filter #(and % (.isAfter ^Instant % at)))
         sort
         last)))

(defn pool-order
  "The links of `rows`, given in the pool's order, that may fire at
  `at`: least used first, ties in list order."
  [rows ^Instant at]
  (->> rows
       (map-indexed vector)
       (filter (fn [[_ r]] (and (live? r) (nil? (waiting-until r at)))))
       (sort-by (fn [[i r]] [(used (:data r) at) i]))
       (map second)))

(defn- links-by-id [eng]
  (into {} (map (juxt (comp str :id) identity)) (rows-of eng :runner_link {})))

(defn fire-pool!
  "Fire one run through the pool `ids`: each link that may fire, in
  `pool-order`, until one starts a run. A throttle or a refusal marks
  only that link (`fire-link!`) and the next is tried. `provider-of`
  answers a link row's Provider.

  Answers {:runner id :answer answer} for the link a run started
  through; else {:retry-at instant}, the earliest instant a live link
  of the pool is free again — nil when none will be (every link
  broken, retired or missing)."
  [eng provider-of ids text]
  (let [at (or (instant-of ((:now-fn eng))) (Instant/now))
        pool (keep (links-by-id eng) (map str ids))]
    (or (some (fn [row]
                (let [answer (fire-link! eng (provider-of row) row text)]
                  (when (contains? answer :started)
                    {:runner (str (:id row)) :answer answer})))
              (pool-order pool at))
        (let [fresh (links-by-id eng)]
          {:retry-at (->> pool
                          (keep #(get fresh (str (:id %))))
                          (filter live?)
                          (keep #(waiting-until % at))
                          sort
                          first)}))))
