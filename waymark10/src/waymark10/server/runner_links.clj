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
  rows keep their own links, and a source row that names no runners
  is given its seeded link as its list (4e42b3d4), so fires go through it.

  `fire-pool!` (d16b71bf) chooses among a pool's links: it skips a
  link that is waiting and takes the least-used of the rest, or, in
  `prefer` order (529deb73), the first of the rest in list order.

  A link and a provider row have an `owner` (b7572409, R-5.1): the
  person who made it, or the one the tool that made it acts for, written
  at birth. The engine's own rows have none: a link with no owner is a
  HOUSE link. A contributor's seat fires only on its owner's links
  (`pool-owner`, R-5.3); a manager's seat, and a seat with no owner,
  fire on every link of the pool.

  `runner-provider` (ec7e7bfb) is the account behind every link of one
  provider: its cap counts every fire through any of them, from any
  seat, and a throttle that names the account holds them all.

  A link's `delivery` (pull-mode epic 54360702, piece 1a) is `push`,
  the default, or `pull`. A pull link has no fire URL: a fire through it
  is held, sealed, in pull_fires.clj until a localfire takes it
  (`take-fire!`) and answers it (`answer-fire!`), and while no localfire
  has polled it within `poll-seconds` it reads waiting
  (`pull-waiting`), so a pool passes it by.

  THE PULLER (LP2). A poll also stamps the row's `puller_seen_at`
  (`seen!`), at most once in `seen-every` seconds, by a maintenance
  write, and the computed `puller` reads it: connected, away or never.
  It is the one source: the pool's `pull-waiting`, a person, a seat's
  waiting sentence (`away-sentence`) and the live cracks pass all read
  it. `poll-seconds` stays above `seen-every` plus the poll's hold, so a
  live puller never reads away."
  (:require [clojure.string :as str]
            [waymark10.time :refer [instant-of]]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.at-rest :as at-rest]
            [waymark10.server.invoke :as inv]
            [waymark10.server.pull-fires :as pf]
            [waymark10.server.roles :as roles]
            [waymark10.server.schedules :as sch]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

;; `localfire` (529deb73) answers the Routine wire, so it fires through
;; the same Provider as `claude_routine`; it is its own value so a count
;; of cloud Routine fires never counts it, and a localfire-only check
;; can tell its links apart.
(def providers ["claude_routine" "localfire"])

(defn- a-persons-hand?
  "A person, or a tool that person is signed in to — the rule the
  schedule's and the model's `link` doors keep."
  [ctx]
  (let [{:keys [type acts-for]} (:principal ctx)]
    (or (= :human type)
        (and (= :agent type) (not (str/blank? (str acts-for)))))))

(g/defguard a-person-makes-the-link
  {:reads [:principal]
   :explain "A runner link is a person's to make. A person makes the Routine by hand, and a person — or a tool that person is signed in to — pastes its fire URL and its token here. An agent does not make a link; the engine copies only a link a person already pasted onto a model or a schedule."
   :open "No door changes who the caller is: ask the person to make the Routine and paste its link here."}
  [_row _inp ctx]
  (if (or (a-persons-hand? ctx)
          (= :system (get-in ctx [:principal :type])))
    (t/allow) (t/deny)))

;; THE SEED'S MARK HAS ONE WRITER, seats.clj's `link-not-written-by-hand`
;; in this kind's shape: `seeded_from` is on the create-schema so the
;; boot seed can record its source, and a planted value would make
;; `ensure-seeded-links!` skip that model or schedule for good.
(g/defguard the-engine-writes-the-seed
  {:judges [:seeded_from]
   :reads [:principal]
   :explain "Where a link was copied from is written by the engine's boot seed alone, never by hand. Make the link without seeded_from."
   :remedies [:runner_link/create]}
  [_row inp ctx]
  (if (or (nil? (:seeded_from inp))
          (= :system (get-in ctx [:principal :type])))
    (t/allow)
    (t/deny)))

(g/defguard a-person-writes-the-token
  {:judges [:token]
   :reads [:principal]
   :explain "A new token is a person's to paste. Restate the link without a token, and the one it holds stays."
   :remedies [:runner_link/restate]}
  [_row inp ctx]
  (if (or (nil? (:token inp)) (a-persons-hand? ctx))
    (t/allow)
    (t/deny)))

(def ^:private push-or-pull
  "A push link names the fire URL the engine posts each fire to. A pull link names none: a localfire on the person's machine asks the engine for its fires. Give a fire URL with push, and none with pull.")

(g/defguard a-pull-link-has-no-url
  {:judges [:fire_url :delivery]
   :explain push-or-pull
   :remedies [:runner_link/create]}
  [_row inp _ctx]
  (if (= (= "pull" (some-> (:delivery inp) name))
         (nil? (some-> (:fire_url inp) str not-empty)))
    (t/allow)
    (t/deny)))

;; the same law on restate, whose input has no :delivery: the row's
;; delivery stands, so a pull link refuses a fire_url, and a push link
;; that names none keeps the one it holds.
(g/defguard a-restated-pull-link-has-no-url
  {:name :a-pull-link-has-no-url
   :judges [:fire_url]
   :explain push-or-pull
   :remedies [:runner_link/restate]}
  [row inp _ctx]
  (if (and (= "pull" (some-> (get-in row [:data :delivery]) name))
           (some? (some-> (:fire_url inp) str not-empty)))
    (t/deny)
    (t/allow)))

(g/defguard the-engine-writes-the-fire
  {:reads [:principal]
   :hide true
   :explain "A link's live state is the engine's record of what the provider last answered."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type]))
    (t/allow) (t/deny)))

(g/defguard a-person-breaks-the-link
  {:reads [:principal]
   :explain "A person marks their own link broken — themselves, or a tool they are signed in to — when they know it can no longer run, as when they revoked its token. The engine marks a link broken through its own door when the provider refuses a fire."
   :open "No door changes who the caller is: ask the person whose link this is."}
  [_row _inp ctx]
  (if (or (a-persons-hand? ctx)
          (= :system (get-in ctx [:principal :type])))
    (t/allow) (t/deny)))

;; THE POOLS ARE THE ENGINE'S TO WRITE (S1 of docs/spec-seat.md §22): a
;; link's `pools` says which schedules and models name it in their
;; `runners`, restated by `schedules/sync-pools!` after every write to
;; one. A person says which pools a link is in through `join_pool` and
;; `leave_pool` (S2a), never through `set_pools`.
(g/defguard the-engine-keeps-the-pools
  {:reads [:principal]
   :hide true
   :explain "Which pools name a link is the engine's record of the runner lists that name it; add the link to a pool, or take it out, with join_pool or leave_pool instead."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type]))
    (t/allow) (t/deny)))

;; THE PERSON'S POOL DOORS (S2a of docs/spec-seat.md §22): a person, or
;; a tool they are signed in to, as on the link's other doors. A seat
;; does not move a link between pools.
(g/defguard a-person-pools-the-link
  {:reads [:principal]
   :explain "Which pools a link is in is a person's to say — themselves, or a tool they are signed in to. A seat does not move a link into or out of a pool."
   :open "No door changes who the caller is: ask the person whose link this is."}
  [_row _inp ctx]
  (if (a-persons-hand? ctx) (t/allow) (t/deny)))

(defn- gone-pool
  "The first of these {kind id} pools that is not there, or has ended
  or retired, spelled `kind id`; nil when every one is open, or when the
  ctx has no read hook."
  [ctx pools]
  (when-some [read' (:read ctx)]
    (some (fn [{:keys [kind id]}]
            (let [row (read' (keyword (name kind)) (str id))]
              (when (or (nil? row)
                        (contains? #{"ended" "retired"} (some-> (:state row) name)))
                (str (name kind) " " id))))
          pools)))

(def ^:private open-pool
  "A link joins the pool of a schedule or a model that is still running: {pool} is not there, or has ended or retired.")

(g/defguard the-pool-is-open
  {:judges [:kind :id]
   :reads [:schedule :model]
   :vars [:pool]
   :explain open-pool
   :open "The schedules and the models are each one query away."}
  [_row inp ctx]
  (if-some [gone (gone-pool ctx [inp])]
    (t/deny {:vars {:pool gone}})
    (t/allow)))

;; the same law on create, whose input names its pools as a list
(g/defguard the-pools-are-open
  {:name :the-pool-is-open
   :judges [:pools]
   :reads [:schedule :model]
   :vars [:pool]
   :explain open-pool
   :open "The schedules and the models are each one query away."}
  [_row inp ctx]
  (if-some [gone (gone-pool ctx (:pools inp))]
    (t/deny {:vars {:pool gone}})
    (t/allow)))

(def ^:private at-the-end
  "The rank a pool entry gets when its door names none: past every place
  a list has, so the link goes last. The engine's next restate of the
  pools writes its true place."
  1000)

(defn- names-pool?
  "Does a `pools` entry name the schedule or model `kind` `id`?"
  [kind id]
  (fn [e]
    (and (= (name kind) (some-> (:kind e) name))
         (= (str id) (some-> (:id e) str)))))

(defn- put-in-pool
  "`pools` with the link in the pool `e` names, at `e`'s rank or last;
  an entry it had in that pool is replaced."
  [pools {:keys [kind id rank]}]
  (conj (vec (remove (names-pool? kind id) pools))
        {:kind (name kind) :id (str id)
         :rank (if (some? rank) (long rank) at-the-end)}))

(defhandler join-pool
  [row inp _ctx]
  (let [pools (vec (get-in row [:data :pools]))]
    ;; no rank, and already in that pool: the link keeps its place
    (if (and (nil? (:rank inp)) (some (names-pool? (:kind inp) (:id inp)) pools))
      row
      (assoc-in row [:data :pools] (put-in-pool pools inp)))))

(defhandler leave-pool
  [row inp _ctx]
  (assoc-in row [:data :pools]
            (vec (remove (names-pool? (:kind inp) (:id inp))
                         (get-in row [:data :pools])))))

(def ^:private pool-kind-field
  [:kind {:x-display
          {:label "Which pool"
           :help "A schedule's pool fires that one seat; a model's pool fires every seat it is the chair of."
           :choices {"schedule" "A schedule: one seat's own pool."
                     "model" "A model: the pool of every seat it chairs."}}}
   [:enum "schedule" "model"]])

(def ^:private pool-id-field
  [:id {:x-ref {:kind-from :kind}
        :x-display
        {:label "Its id"
         :help "The id of the schedule or the model whose pool this is."}}
   [:string {:min 1 :max 200}]])

(def ^:private pool-rank-field
  [:rank {:optional true
          :x-display
          {:label "Its place"
           :help "Where in the pool's list the link goes, from 0. A link at that place or later moves down one. Empty puts it last, or keeps the place it has."}}
   [:maybe [:int {:min 0 :max 1000}]]])

(def ^:private create-pools-field
  [:pools {:optional true
           :x-display
           {:label "Put it in these pools"
            :help "The schedules and models whose pool this link joins as it is made, each at its place or last. Leave it empty to add it to a pool later."}}
   [:maybe [:vector {:max 20} [:map pool-kind-field pool-id-field pool-rank-field]]]])

(def ^:private pool-entry
  [:map
   [:kind [:enum "schedule" "model"]]
   [:id {:x-ref {:kind-from :kind}} [:string {:min 1 :max 200}]]
   [:rank [:int {:min 0 :max 1000}]]])

(def ^:private pools-field
  [:pools {:optional true
           :x-display
           {:hidden true
            :label "Named by"
            :help "The schedules and models whose runner list names this link, each with its place in that list. Engine-written after every write to one."}}
   [:maybe [:vector pool-entry]]])

(defhandler write-pools
  [row inp _ctx]
  (assoc-in row [:data :pools] (vec (:pools inp))))

(def ^:private engine-writes
  {:idempotent true :reversible false :confirm false
   :one-way "Bookkeeping the engine writes as it fires through the link; the next fire moves the row again."})

(defhandler stamp-fire
  [row inp _ctx]
  (update row :data
          (fn [data]
            (-> (merge data (select-keys inp [:last_fired_at :window_started_at
                                              :runs_in_window]))
                (dissoc :retry_after :note)))))

(defhandler record-break
  [row inp _ctx]
  (assoc-in row [:data :note] (:note inp)))

(defhandler hold-throttle
  [row inp _ctx]
  (assoc-in row [:data :retry_after] (:retry_after inp)))

(defhandler restate-link
  [row inp _ctx]
  (update row :data
          (fn [data]
            (cond-> (dissoc (merge data (select-keys inp [:provider :fire_url :cap]))
                            :note)
              (some? (:token inp)) (assoc :fire_token (:token inp))))))

(def ^:private provider-field
  [:provider {:x-display
              {:label "The provider"
               :help "Whose endpoint the fire URL is."
               :choices {"claude_routine" "A Claude Routine, made by hand and fired by URL."
                         "localfire" "A localfire server on a machine you own. It answers the Routine's wire, and its fires are counted apart from the cloud Routines'."}}}
   (into [:enum] providers)])

(def ^:private fire-url-field
  [:fire_url {:optional true
              :x-display
              {:raw true
               :label "The fire URL"
               :help "The one endpoint that starts a run, copied from the Routine's own page. It carries the Routine's id, which is not a secret. A pull link has none."}}
   [:string {:min 1 :max 400}]])

(def ^:private delivery-field
  [:delivery {:optional true
              :x-display
              {:label "How fires reach it"

               :help "push, the default: the engine posts each fire to the fire URL. pull: a localfire asks the engine for its fires over its own outbound connection, and the engine holds them until it does."
               :choices {"push" "The engine posts each fire to the fire URL."
                         "pull" "A localfire asks for its fires; the link has no fire URL."}}}
   [:maybe [:enum "push" "pull"]]])

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

(def ^:private owner-field
  [:owner {:optional true
           :x-ref {:principal true}
           :x-display
           {:raw true
            :label "Whose it is"
            :spelled-by-hand "The person who made this, or the one the tool that made it acts for, written at birth; never typed. Empty for the engine's own, a house link."}}
   [:maybe [:string {:max 128}]]])

(defn- maker
  "The person whose hand makes a row: a person, or the one a tool acts
  for. nil for the engine and for any other agent."
  [ctx]
  (let [{:keys [type id acts-for]} (:principal ctx)]
    (cond
      (= :human type) (some-> id str not-empty)
      (= :agent type) (some-> acts-for str not-empty))))

(defn- owned-at-birth
  "The on-create of a link and a provider row: its maker is its owner."
  [row ctx]
  (if-some [person (maker ctx)]
    (assoc-in row [:data :owner] person)
    row))

(defn- born-with-pools
  "The on-create of a link: `owned-at-birth`, and each pool its create
  named spelled as a `pools` entry, at its place or last (S2a)."
  [row ctx]
  (cond-> (owned-at-birth row ctx)
    (some? (get-in row [:data :pools]))
    (update-in [:data :pools] #(reduce put-in-pool [] %))))

(def ^:private seeded-from-field
  [:seeded_from {:optional true
                 :x-display
                 {:hidden true
                  :label "Copied from"
                  :help "The model or schedule whose own link the engine copied this one from at boot, as kind:id. Empty for a link a person made here."}}
   [:maybe [:string {:min 1 :max 200}]]])

(declare puller-of)

(defn- puller-field
  "The link's :computed `puller`: `puller-of` at the render's clock."
  [row ctx]
  (puller-of row (:now ctx)))

(defresource runner-link
  {:kind :runner_link
   :plural "runner_links"
   :states [:live :broken :retired]
   :initial :live
   :terminal #{}                       ; retirement is reversible, deliberately
   ;; :broken is written two ways: `fire-link!` marks a link broken
   ;; through the hidden `break` door when the provider answers that
   ;; the link is bad, and the link's owner walks `mark_broken` when
   ;; they know it is (ticket 44abe17a: a revoked token).
   :nav :system
   :summary "{data.provider} · {state}"
   :label-template "{data.provider} link"
   :computed {:puller
              {:schema :string
               :x-display
               {:label "Its localfire"
                :help "connected when a localfire asked for this pull link's fires within a minute, away when longer ago, never when none has asked. Empty on a push link. Worked out at read time from Localfire last seen."}
               :fn puller-field}}
   :schema
   [:map
    provider-field
    fire-url-field
    delivery-field
    ;; THE TOKEN IS HELD AS THE SCHEDULE'S IS (R-12.11): never
    ;; rendered, never filterable, and never in a transition's recorded
    ;; inputs — which is why no door here records.
    [:fire_token {:secret true :at-rest :encrypted
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
    [:puller_seen_at {:optional true
                      :x-display
                      {:label "Localfire last seen"
                       :help "When a localfire last asked for this pull link's fires, to within thirty seconds: a poll writes it only when it is older than that. Empty until one asks, and on a push link. Engine-written."}}
     [:maybe :waymark/instant]]
    [:note {:optional true
            :x-display
            {:widget "prose"
             :label "Why the link broke"
             :help "The provider's own sentence about why it refused the last fire, or the owner's when they marked it broken. A person restates the link to bring it back. Cleared by the next run that starts, or by a restate."}}
     [:maybe [:string {:max 280}]]]
    seeded-from-field
    pools-field
    owner-field]
   :create-schema
   [:map
    provider-field
    fire-url-field
    delivery-field
    [:fire_token {:secret true
                  :x-display
                  {:raw true
                   :label "The token"
                   :help "The credential that opens that one Routine. The engine holds it and never shows it again."}}
     [:string {:min 16 :max 400}]]
    cap-field
    seeded-from-field
    create-pools-field]
   :filterable {:state #{:eq :in}
                :provider #{:eq :in}
                :delivery #{:eq}
                :seeded_from #{:eq}
                :puller_seen_at #{:after :before :range}}
   :sortable {:fields [:created_at :updated_at] :default "-created_at"}
   :create-guards [a-person-makes-the-link the-engine-writes-the-seed
                   a-pull-link-has-no-url the-pools-are-open]
   :on-create born-with-pools
   ;; the person who made a link reaches it with no grant naming the
   ;; kind, and so does the tool they signed in to: owned-at-birth
   ;; stamps the person a tool acts for, so the delegate opts in.
   ;; The token stays write-only; a manager's grant still sees all
   :own-surface {:by :owner :delegates true
                 :actions #{:restate :mark_broken :join_pool :leave_pool}}
   :actions
   {:restate
    {:from #{:live :broken} :to :live
     :input [:map
             [:provider {:optional true
                         :x-display
                         {:label "The provider"
                          :help "Whose endpoint the fire URL is."
                          :choices {"claude_routine" "A Claude Routine, made by hand and fired by URL."
                                    "localfire" "A localfire server on a machine you own. It answers the Routine's wire, and its fires are counted apart from the cloud Routines'."}}}
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
     :guards [a-person-writes-the-token a-restated-pull-link-has-no-url]
     :edit {:prefill [:provider :fire_url :cap] :fence true}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The link holds what this restate says; a new token replaces the old one, which is not kept."}
     :handler restate-link
     :display {:label "Restate the link" :order 1
               :description "Change the fire URL or the cap, or paste a new token. Push or pull stays as the link was made: to change it, retire this link and make a new one"}}

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

    ;; the owner's own break (ticket 44abe17a): they know the link can
    ;; no longer run, as when they revoked its token, and say why. A
    ;; restate brings it back live, as after the engine's `break`.
    :mark_broken
    {:from #{:live} :to :broken
     :input [:map
             [:note {:x-display
                     {:widget "prose"
                      :label "Why it is broken"
                      :help "One sentence for the next reader, such as: the person revoked their token."}}
              [:string {:min 1 :max 280}]]]
     :record true
     :guards [a-person-breaks-the-link]
     :edit {:prefill [:note] :fence false
            :unfenced-reason
            "The owner says the link can no longer run; nothing it read since makes that less true."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The link fires nothing until a person restates it."}
     :handler record-break
     :display {:label "Mark broken" :style :danger :order 8
               :description "Nothing fires through this link until it is restated"}}

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
     :input [:map
             [:note {:x-display {:hidden true}} [:string {:min 1 :max 280}]]]
     :record true
     :guards [the-engine-writes-the-fire]
     :edit {:prefill [:note] :fence false
            :unfenced-reason
            "The provider's own sentence, written the moment it refused the fire; no read preceded it to fence against."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The link fires nothing until a person restates it."}
     :handler record-break
     :display {:label "Link refused" :style :danger}}

    ;; a person puts the link in a pool, or takes it out, from the link
    ;; itself (S2a of docs/spec-seat.md §22). The schedule's or the
    ;; model's own `runners` follow: the schedules consumer restates
    ;; that list, as the engine, once this commits.
    :join_pool
    {:from #{:live :broken} :to :same
     :input [:map pool-kind-field pool-id-field pool-rank-field]
     :writes #{:pools}
     :record true
     :guards [a-person-pools-the-link the-pool-is-open]
     :safety {:idempotent true :reversible true :confirm false}
     :handler join-pool
     :display {:label "Add to a pool" :order 3
               :description "Put this link in a schedule's or a model's pool, at a place in its list or last"}}

    :leave_pool
    {:from #{:live :broken} :to :same
     :input [:map pool-kind-field pool-id-field]
     :writes #{:pools}
     :record true
     :guards [a-person-pools-the-link the-pool-is-open]
     :safety {:idempotent true :reversible true :confirm false}
     :handler leave-pool
     :display {:label "Take out of a pool" :order 4
               :description "Take this link out of a schedule's or a model's pool; the rest of its list closes up"}}

    ;; the pools that name this link (S1 of docs/spec-seat.md §22):
    ;; hidden, the engine's own hand, and in every state a link may be
    ;; in, since a broken or retired link is still named by its lists.
    :set_pools
    {:from #{:live :broken :retired} :to :same
     :input [:map
             [:pools {:x-display {:hidden true}}
              [:vector {:max 10000} pool-entry]]]
     :writes #{:pools}
     :record true
     :guards [the-engine-keeps-the-pools]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Bookkeeping the engine writes after a runner list changes; the next change restates it."}
     :handler write-pools
     :display {:hidden true :label "Pools restated"}}}})

;; ── the provider (ticket ec7e7bfb) ──────────────────────────────────
;; One row per provider: the account behind all of its links. Every
;; fire through any link of it, from any seat, counts toward its
;; window, and a throttle that names the account holds every link.
;; The per-Routine cap stays on each link.

(g/defguard the-engine-seeds-the-provider
  {:reads [:principal]
   :hide true
   :explain "A provider row is the engine's: it seeds one for each provider it knows."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type]))
    (t/allow) (t/deny)))

(g/defguard a-person-sets-the-cap
  {:reads [:principal]
   :explain "An account's cap is a person's to set, from what the provider's own terms allow."
   :open "No door changes who the caller is: ask the person to set the account's cap."}
  [_row _inp ctx]
  (if (a-persons-hand? ctx) (t/allow) (t/deny)))

(defhandler stamp-provider-fire
  [row inp _ctx]
  (update row :data
          (fn [data]
            (-> (merge data (select-keys inp [:window_started_at :runs_in_window]))
                (dissoc :retry_after)))))

(defhandler restate-provider
  [row inp _ctx]
  (update row :data merge (select-keys inp [:cap])))

(defresource runner-provider
  {:kind :runner_provider
   :plural "runner_providers"
   :states [:live]
   :initial :live
   :terminal #{}
   :nav :system
   :summary "{data.provider} · {state}"
   :label-template "{data.provider} account"
   :schema
   [:map
    provider-field
    cap-field
    [:retry_after {:optional true
                   :x-display
                   {:label "Free again at"
                    :help "The provider throttled the whole account and named this time; nothing fires through any of its links before it. Engine-written."}}
     [:maybe :waymark/instant]]
    [:window_started_at {:optional true
                         :x-display
                         {:label "This window began"
                          :help "When the account cap's current window opened. Engine-written."}}
     [:maybe :waymark/instant]]
    [:runs_in_window {:optional true
                      :x-display
                      {:label "Runs this window"
                       :help "How many runs every link of this provider, from every seat, has started in the cap's current window. Engine-written."}}
     [:maybe [:int {:min 0}]]]
    owner-field]
   :create-schema
   [:map
    provider-field
    cap-field]
   :filterable {:state #{:eq :in}
                :provider #{:eq :in}}
   :sortable {:fields [:created_at :updated_at] :default "-created_at"}
   ;; the account spends money: only a manager changes it (R-3.2)
   :create-guards [the-engine-seeds-the-provider roles/a-manager]
   :on-create owned-at-birth
   ;; the account's owner, and the tool they signed in to, see it; its
   ;; restate stays a manager's, so the own-surface confers no door
   :own-surface {:by :owner :delegates true}
   :actions
   {:restate
    {:from #{:live} :to :live
     :input [:map cap-field]
     :record true
     :guards [a-person-sets-the-cap roles/a-manager]
     :edit {:prefill [:cap] :fence true}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The account holds the cap this restate says; the one before is not kept."}
     :handler restate-provider
     :display {:label "Set the cap" :order 1
               :description "At most this many runs across every link of this provider, in each window"}}

    ;; a run started through some link of this provider: its window
    ;; counts one more, whichever seat or pool the fire came from.
    :fired
    {:from #{:live} :to :live
     :input [:map
             [:window_started_at {:x-display {:hidden true}} :waymark/instant]
             [:runs_in_window {:x-display {:hidden true}} [:int {:min 0}]]]
     :record true
     :guards [the-engine-writes-the-fire]
     :edit {:prefill [:window_started_at] :fence false
            :unfenced-reason
            "Stamped by the fire the moment the provider answered; no read preceded it to fence against."}
     :safety engine-writes
     :handler stamp-provider-fire
     :display {:label "Run started"}}

    ;; the provider answered that the account, not one link, has no
    ;; free run: nothing fires through any of its links before it.
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
     :display {:label "Account throttled"}}}})

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
  The source rows keep their own links untouched. A source row whose
  `runners` is empty — an active model, a schedule that has not
  ended — is given that link as its list and stays in the state it is
  in (`sch/seed-states`); a list a person set is never overwritten."
  [eng]
  (when (contains? (inv/resources eng) :runner_link)
    (doseq [kind [:model :schedule]
            r (rows-of eng kind {})
            :let [url (some-> (get-in r [:data :fire_url]) str not-empty)
                  token (some-> (get-in r [:data :fire_token]) str not-empty)
                  from (str (name kind) ":" (:id r))]
            :when (and url token)]
      (try
        (when (empty? (rows-of eng :runner_link {:seeded_from from}))
          (inv/create! eng :runner_link
                       {:provider "claude_routine"
                        :fire_url url
                        :fire_token token
                        :seeded_from from}
                       {:principal seed-actor}))
        (when-let [link (first (rows-of eng :runner_link {:seeded_from from}))]
          (when-let [door (when (nil? (sch/runners-of-row r))
                            (let [state (keyword (name (:state r)))]
                              (if (= kind :model)
                                (when (= :active state) :seed_runners)
                                (when (contains? sch/seed-states state)
                                  :seed_runners))))]
            (inv/invoke! eng kind (str (:id r)) door
                         {:runners [(str (:id link))]}
                         {:principal seed-actor})))
        (catch Exception e
          (binding [*out* *err*]
            (println (str "waymark10 runner links: seed from " from
                          " failed — " (ex-message e)))))))))

(defn provider-row
  "The runner_provider row of `provider` (a provider name), or nil."
  [eng provider]
  (when (contains? (inv/resources eng) :runner_provider)
    (first (rows-of eng :runner_provider {:provider (str provider)}))))

(defn ensure-providers!
  "The boot seed: one runner_provider row for each provider, with no
  cap, so a second boot makes none. A person sets the cap by restate."
  [eng]
  (when (contains? (inv/resources eng) :runner_provider)
    (doseq [p providers
            :when (nil? (provider-row eng p))]
      (try
        (inv/create! eng :runner_provider {:provider p} {:principal seed-actor})
        (catch Exception e
          (binding [*out* *err*]
            (println (str "waymark10 runner links: seed of provider " p
                          " failed — " (ex-message e)))))))))

;; ── firing through a link (piece 1b) ────────────────────────────────

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

(defn- act!
  ([eng row action body] (act! eng :runner_link row action body))
  ([eng kind row action body]
   (try
     (:row (inv/invoke! eng kind (str (:id row)) action body
                        {:principal sch/system-actor}))
     (catch Exception e
       ;; the row may have moved under the fire (retired meanwhile);
       ;; the provider's answer still stands and is returned
       (binding [*out* *err*]
         (println (str "waymark10 runner links: " (name kind) " " (:id row)
                       " could not record " action " — " (ex-message e))))
       nil))))

(defn account-throttle?
  "Whether a throttle answer names the account rather than the link: a
  `:scope` of account, or a body that says account. An answer that says
  neither is the link's, as before."
  [answer]
  (boolean (or (= "account" (some-> (:scope answer) name))
               (some->> (:body answer) str (re-find #"(?i)\baccount\b")))))

;; ── a pull link (pull-mode epic 54360702, piece 1a) ─────────────────

(def poll-seconds
  "How recently a localfire must have polled a pull link for it to fire."
  60)

(defn- now-of ^Instant [eng]
  (or (instant-of ((:now-fn eng))) (Instant/now)))

(defn pull?
  "Is this link one a localfire polls, rather than one the engine posts to?"
  [row]
  (= "pull" (some-> (get-in row [:data :delivery]) name)))

(defn- rearm!
  "Arm again the wake a dropped fire spent, as a missed fire's expiry
  arms one (`wakes/missed-expired!`): `wake_pending` on the schedule row,
  through its `mark_wake` door (leases 5b)."
  [eng schedule-id]
  (sch/mark-wake! eng schedule-id {:wake_pending true}))

(defn drop-missed!
  "Drop every held fire a day old, and say on each one's schedule that
  it was missed. A fire whose key no sit spent has its key revoked and
  its wake armed again (ticket a25950b4): the missed-fire sweep passes a
  fire nobody took by, so this is where it is written down. The revoke
  ends the sitting the fire opened (ticket 9f108b88), and the sitting
  sweep runs this first, so that sitting is not expired as missed."
  [eng]
  (doseq [f (pf/sweep! (:storage eng) (.minusSeconds (now-of eng) pf/keep-seconds))
          :when (= "schedule" (:for_kind f))]
    (let [revoked? (seats/revoke-fire-key!
                    eng (seats/fire-key-of-text (at-rest/open (:text f))))]
      (sch/miss! eng (:for_id f)
                 "A fire waited a day on its pull runner link and no localfire took it; it was dropped as missed.")
      (when revoked?
        (rearm! eng (:for_id f))))))

(defn- queue-fire!
  "A fire through a pull link: held, sealed, for a localfire to take.
  `for` is {:kind :id}, the row it is for, or nil. → {:queued id}.

  ONE WAITING FIRE PER ROW PER LINK (ticket a25950b4). When the link
  already holds a fire for `for` that no localfire took, no second one
  is queued: the new fire's key is revoked, and the answer is
  {:queued id :waiting true}, naming the fire that waits. A laptop that
  comes back runs the seat once. Else the fire's key is marked as a
  pull fire's (`seats/queue-fire-key!`), to answer for the keep."
  [eng link-row text for]
  (drop-missed! eng)
  (let [st (:storage eng)
        link-id (str (:id link-row))
        held (seats/fire-key-of-text text)
        waiting (when for
                  (first (filter #(and (nil? (:handed_out_at %))
                                       (= (some-> (:kind for) name) (:for_kind %))
                                       (= (some-> (:id for) str) (:for_id %)))
                                 (pf/pending st link-id))))]
    (if waiting
      (do (seats/revoke-fire-key! eng held)
          {:queued (:id waiting) :waiting true})
      (let [id (pf/enqueue! st {:link-id link-id
                                :for-kind (:kind for)
                                :for-id (:id for)
                                :text text
                                :now (now-of eng)})]
        (seats/queue-fire-key! eng held id
                               (.plusSeconds (now-of eng) pf/keep-seconds))
        {:queued id}))))

(declare land-on-link!)

(defn fire-link!
  "Fire one run through `link-row` by `provider` (a schedules/Provider),
  with `text`, and write what it answered onto the link: `started`
  stamps `last_fired_at` and counts the run in the window, `throttled`
  records `retry_after`, `transient` writes nothing (the link stays
  live and the fire is not spent), and `bad-link` marks the link
  broken. Answers the provider's answer.

  A started run also counts in the window of the link's provider row,
  and a throttle that names the account (`account-throttle?`) holds the
  provider row instead of the link.

  A pull link (`pull?`) posts nothing: the fire is held for a localfire
  (`queue-fire!`) and the answer is {:queued id}; what the localfire
  answers lands later, through `answer-fire!`. `for` names the row the
  fire is for, {:kind :id}."
  ([eng provider link-row text] (fire-link! eng provider link-row text nil))
  ([eng provider link-row text for]
   (if (pull? link-row)
     (queue-fire! eng link-row text for)
     (let [answer (sch/fire provider (:data link-row) text)]
       (land-on-link! eng link-row answer)
       answer))))

(defn- land-on-link!
  "Write on `link-row`, and on its provider row, what one fire
  answered, as `fire-link!` documents."
  [eng link-row answer]
  (let [at (now-of eng)
        account (delay (provider-row eng (get-in link-row [:data :provider])))]
    (cond
      (contains? answer :started)
      (do (act! eng link-row :fired
                (merge {:last_fired_at (str at)}
                       (window-after (:data link-row) at)))
          (when-some [p @account]
            (act! eng :runner_provider p :fired (window-after (:data p) at))))

      (contains? answer :throttled)
      (let [until {:retry_after (str (sch/retry-instant at (:throttled answer)
                                                        (:body answer)))}]
        (if-some [p (when (account-throttle? answer) @account)]
          (act! eng :runner_provider p :throttle until)
          (act! eng link-row :throttle until)))

      ;; a 5xx, an unreachable provider, another 4xx: not the link's fault
      (contains? answer :transient)
      nil

      ;; the localfire cannot say whether the run started: not the
      ;; link's fault either, and no throttle (ticket 734b3ecd)
      (contains? answer :not-started)
      nil

      :else
      (let [reason (or (not-empty (str (:bad-link answer)))
                       "The provider refused the link.")]
        (act! eng link-row :break
              {:note (subs reason 0 (min 280 (count reason)))})))))

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
  `at`. `order` \"least_used\" (the default) puts the least used first,
  ties in list order; \"prefer\" (529deb73) keeps list order, so the
  first link that may fire takes every fire it can."
  [rows ^Instant at & [order]]
  (let [free (->> rows
                  (map-indexed vector)
                  (filter (fn [[_ r]] (and (live? r) (nil? (waiting-until r at))))))]
    (->> (if (= "prefer" order)
           free
           (sort-by (fn [[i r]] [(used (:data r) at) i]) free))
         (map second))))

(defn- provider-waiting
  "The instant after `at` before which no link of `link-row`'s provider
  fires — the account's `retry_after`, or the close of its spent
  window — or nil when the account lets it fire."
  [eng link-row ^Instant at]
  (some-> (provider-row eng (get-in link-row [:data :provider]))
          (waiting-until at)))

(defn account-hold
  "The instant before which no fire of `provider` (a provider name) goes
  out — its row's `retry_after`, or the close of its spent window — or
  nil when the account lets it fire. For a schedule's fire through its
  own single link, which is no runner link (5c499772)."
  [eng provider]
  (some-> (provider-row eng provider)
          (waiting-until (or (instant-of ((:now-fn eng))) (Instant/now)))))

(defn count-account!
  "Write on `provider`'s row what one fire through a schedule's own
  single link answered (5c499772): a started run counts in its window,
  and a throttle that names the account holds it — as `fire-link!` does
  for a runner link."
  [eng provider answer]
  (when-some [p (provider-row eng provider)]
    (let [at (or (instant-of ((:now-fn eng))) (Instant/now))]
      (cond
        (contains? answer :started)
        (act! eng :runner_provider p :fired (window-after (:data p) at))

        (and (contains? answer :throttled) (account-throttle? answer))
        (act! eng :runner_provider p :throttle
              {:retry_after (str (sch/retry-instant at (:throttled answer)
                                                    (:body answer)))})))))

(defn- seen-at ^Instant [row]
  (instant-of (get-in row [:data :puller_seen_at])))

(defn pull-waiting
  "For a pull link no localfire has polled within `poll-seconds` of
  `at`, as its `puller_seen_at` says: the instant to look again. nil
  when one has, and for a push link."
  [_eng row ^Instant at]
  (when (pull? row)
    (let [polled (seen-at row)]
      (when (or (nil? polled)
                (.isBefore polled (.minusSeconds at poll-seconds)))
        (.plusSeconds at poll-seconds)))))

(defn waiting-reason
  "Why `row` reads waiting at `at` beyond its own retry and cap: for a
  pull link no localfire has polled, the sentence that says so; else nil."
  [eng row ^Instant at]
  (when (pull-waiting eng row at)
    "no localfire has polled this link"))

(defn- links-by-id [eng]
  (into {} (map (juxt (comp str :id) identity)) (rows-of eng :runner_link {})))

;; ── the puller (LP2) ──────────────────────────────────────────────────

(def seen-every
  "How stale `puller_seen_at` grows before a poll writes it again: a
  link takes at most one row write in this many seconds, not one a poll."
  30)

(defn puller-of
  "Whether a localfire is on pull link `row` at `at`: \"connected\" when
  one was seen within `poll-seconds`, \"away\" when longer ago, \"never\"
  when none was. nil for a push link."
  [row at]
  (when (pull? row)
    (let [^Instant at (or (instant-of at) (Instant/now))]
      (if-some [seen (seen-at row)]
        (if (.isBefore seen (.minusSeconds at poll-seconds)) "away" "connected")
        "never"))))

(defn link-name
  "How a sentence names a link: its provider and the head of its id."
  [row]
  (let [id (str (:id row))]
    (str (or (some-> (get-in row [:data :provider]) name not-empty) "runner")
         " link " (subs id 0 (min 8 (count id))))))

(defn away-sentence
  "Why a seat whose pool is `links` waits rather than fails: when every
  live link of it is a pull link whose localfire is not connected at
  `at`, the sentence naming the first; else nil."
  [links at]
  (let [live (filter live? links)]
    (when (and (seq live)
               (every? #(contains? #{"away" "never"} (puller-of % at)) live))
      (let [r (first live)]
        (str "waiting for " (link-name r) " to connect "
             (if-some [seen (seen-at r)]
               (str "(last seen " seen ")")
               "(never connected)"))))))

(defn- owner-of [row]
  (some-> (get-in row [:data :owner]) str not-empty))

(defn pool-away
  "`away-sentence` over the links of the pool `ids` — with `owner`
  (`pool-owner`), only that person's, as `fire-pool!` tries them."
  [eng ids at & [owner]]
  (away-sentence (cond->> (keep (links-by-id eng) (map str ids))
                   owner (filter #(= owner (owner-of %))))
                 at))

(defn seen!
  "Stamp `puller_seen_at` on pull link `id` at `now` — only when it is
  unset or more than `seen-every` seconds old, so a link polled every
  few seconds is written at most once in that span. A maintenance
  write, as `seats/hold-fire-key!` is: no version, no transition, the
  row read FOR UPDATE so a fire's own write is not lost. → true when it
  wrote."
  [eng id now]
  (let [^Instant now (or (instant-of now) (Instant/now))
        st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (when-some [row (store/load-row st tx :runner_link (str id) {:for-update true})]
          (let [seen (seen-at row)]
            (when (or (nil? seen) (.isBefore seen (.minusSeconds now (long seen-every))))
              (store/maintain! :stamp st tx :runner_link (str id)
                                  (assoc (:data row) :puller_seen_at (str now))
                                  (:next-flip-at row))
              true)))))))

(defn pool-owner
  "The person whose links alone a seat's fire may go out on (b7572409,
  R-5.3): the seat's `owner` when that person is no manager, as
  `roles/manager?` judges them; else nil. A manager's seat, and a seat
  with no owner, fire on every link of the pool, house or owned."
  [eng seat-row]
  (when-some [owner (owner-of seat-row)]
    (when-not (roles/manager? (assoc (inv/render-hooks eng)
                                     :principal {:type :human :id owner}))
      owner)))

(defn- skipped
  "Why a fire for `owner`'s seat went out on none of the links `ids`,
  one clause a link: a house link, another person's link, or its own
  link broken, retired, missing or waiting."
  [eng ids owner]
  (let [fresh (links-by-id eng)]
    (str/join "; "
              (for [id (map str ids)
                    :let [r (get fresh id)]]
                (str "link " id ": "
                     (cond
                       (nil? r) "missing"
                       (nil? (owner-of r)) "a house link"
                       (not= owner (owner-of r)) "another person's link"
                       (not (live? r)) (name (:state r))
                       :else "waiting"))))))

(defn fire-pool!
  "Fire one run through the pool `ids`: each link that may fire, in
  `pool-order` under `order` (\"least_used\" or \"prefer\"), until one
  starts a run. A throttle or a refusal marks only that link
  (`fire-link!`) and the next is tried. `provider-of` answers a link
  row's Provider. A link whose provider row is waiting (its account cap
  spent, or throttled) is skipped, checked afresh before each fire,
  since any seat's fire counts toward it.

  Answers {:runner id :answer answer} for the link a run started
  through; else {:retry-at instant}, the earliest instant a live link
  of the pool is free again — nil when none will be (every link
  broken, retired or missing) — with `:transient`, the provider's
  sentence, when a link answered a transient refusal.

  A pull link that is waiting (`pull-waiting`) is skipped as any
  waiting link is; one a localfire polls holds the fire, answering
  {:queued id}, which counts as the run the pool went out on. `for`
  ({:kind :id}) names the row the fire is for.

  `owner` (`pool-owner`) is the contributor whose seat fires: only
  that person's links are tried, and a fire that goes out on none
  answers `:skipped`, the sentence that names each link and why."
  [eng provider-of ids text & [order for owner]]
  (let [at (or (instant-of ((:now-fn eng))) (Instant/now))
        pool (cond->> (keep (links-by-id eng) (map str ids))
               owner (filter #(= owner (owner-of %))))
        transient (volatile! nil)]
    (or (some (fn [row]
                (when (and (nil? (provider-waiting eng row at))
                           (nil? (pull-waiting eng row at)))
                  (let [answer (fire-link! eng (provider-of row) row text for)]
                    (when (contains? answer :transient)
                      (vreset! transient
                               (or (not-empty (str (:transient answer)))
                                   "The provider could not start a run.")))
                    (when (or (contains? answer :started)
                              (contains? answer :queued))
                      {:runner (str (:id row)) :answer answer}))))
              (pool-order pool at order))
        (let [fresh (links-by-id eng)]
          (cond->
           {:retry-at (->> pool
                          (keep #(get fresh (str (:id %))))
                          (filter live?)
                          (keep (fn [r]
                                  (some->> [(waiting-until r at)
                                            (provider-waiting eng r at)
                                            (pull-waiting eng r at)]
                                           (remove nil?)
                                           seq sort last)))
                          sort
                          first)}
            @transient (assoc :transient @transient)
            owner (assoc :skipped (skipped eng ids owner)))))))

;; ── the two doors piece 1b's routes stand on ─────────────────────────

(defn take-fire!
  "The oldest fire pull link `link-id` holds that nobody holds now —
  never handed out, or its lease (pf/lease-seconds) run out — leased to
  the caller: {:id :text :for_kind :for_id :created_at ...}, the text
  opened. nil when none waits. Fires a day old are dropped first, as
  missed. The take is stamped on the fire's key
  (`seats/take-fire-key!`), so the missed-fire sweep counts from it."
  [eng link-id]
  (drop-missed! eng)
  (when-some [f (pf/take! (:storage eng) (str link-id) (now-of eng))]
    (seats/take-fire-key! eng (seats/fire-key-of-text (:text f)) (now-of eng))
    f))

(defn answer-fire!
  "Land what a localfire answered (the Provider's four words) for the
  fire `fire-id` it took. The fire leaves the queue; the link and its
  provider row record the answer as `fire-link!` records a push answer,
  and the schedule it was for lands it as `sch/fire!` lands one. A
  not-started answer also writes down the sitting the fire's key would
  have opened, when no run spent it (`wakes/record-not-started!`). → the
  fire, or nil when no such fire waits."
  [eng fire-id answer]
  (when-some [f (pf/finish! (:storage eng) (str fire-id))]
    (when-some [link (get (links-by-id eng) (:link_id f))]
      (land-on-link! eng link answer))
    (when (contains? answer :not-started)
      ((requiring-resolve 'waymark10.server.wakes/record-not-started!)
       eng (seats/fire-key-of-text (at-rest/open (:text f)))
       (:not-started answer)))
    (when (= "schedule" (:for_kind f))
      (sch/land-pull-answer! eng (:for_id f) answer (now-of eng) (:link_id f)))
    f))
