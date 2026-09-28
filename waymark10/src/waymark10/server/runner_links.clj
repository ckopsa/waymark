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
  `runs_in_window`, `last_fired_at`) are declared and left empty: the
  fire interface (1b) writes them. The link's liveness is the row's
  own state, `live` or `broken`, beside `retired`.

  Not here: the fire interface (1b), seeding links from today's
  schedule and model links (1c), and choosing among links."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.types :as t]))

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
   :explain "A runner link is a person's to make. A person makes the Routine by hand, and a person — or a tool that person is signed in to — pastes its fire URL and its token here. An agent does not make a link."}
  [_row _inp ctx]
  (if (a-persons-hand? ctx) (t/allow) (t/deny)))

(g/defguard a-person-writes-the-token
  {:judges [:token]
   :reads [:principal]
   :explain "A new token is a person's to paste. Restate the link without a token, and the one it holds stays."}
  [_row inp ctx]
  (if (or (nil? (:token inp)) (a-persons-hand? ctx))
    (t/allow)
    (t/deny)))

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

(defresource runner-link
  {:kind :runner_link
   :plural "runner_links"
   :states [:live :broken :retired]
   :initial :live
   :terminal #{}                       ; retirement is reversible, deliberately
   ;; :broken is the engine's to write: the fire interface (1b) marks
   ;; a link broken when the provider refuses its token. No door does.
   :allow-dead #{:broken}
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
     [:maybe :waymark/instant]]]
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
    cap-field]
   :filterable {:state #{:eq :in}
                :provider #{:eq :in}}
   :sortable {:fields [:created_at :updated_at] :default "-created_at"}
   :create-guards [a-person-makes-the-link]
   :actions
   {:restate
    {:from #{:live :broken} :to :live
     :input [:map
             [:provider {:optional true
                         :x-display
                         {:label "The provider"
                          :help "Whose endpoint the fire URL is."}}
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
     :display {:label "Restore" :order 2}}}})
