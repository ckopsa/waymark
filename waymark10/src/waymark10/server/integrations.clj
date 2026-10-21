(ns waymark10.server.integrations
  "The integration (docs/spec-integrations.md § 2, build step 1): an
  outside system the engine hears from and calls, held as a row and
  acting as a principal of its own.

  AN INTEGRATION IS A PRINCIPAL. Its id is `integration:<row id>`
  (`principal-id`), and every move it makes is in the log under that
  name, beside every seat's. It never acts under the name of the
  person who approved it, and nobody acts under its name.

  A PERSON WHO DID NOT PROPOSE IT APPROVES IT. A person creates the
  row, born `proposed`; `approve` is a person's tap, and the person
  whose create it was cannot be that person — the held call's
  four-eyes wall, read over `created_by` instead of `caller`. The
  approve mints the grant the integration wears, audience
  `integration:<id>`, scope `scope_wanted`, in the approve's own
  transaction, and the engine accepts it there: the approver's tap is
  the consent, as an approved ask's is.

  `scope_wanted` is engine-written and empty until the law names the
  integration (build step 3 reads `:on-event` and `:effect` into it);
  a grant with neither scope nor seat confers nothing, honestly."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.grants :as grants]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def engine-actor
  "The system actor that accepts an approved integration's grant."
  (t/principal {:id "waymark10-integrations" :type :system
                :display "Integrations"}))

(def principal-prefix "integration:")

(defn principal-id
  "The principal id an integration row acts as: `integration:<id>`."
  [row]
  (str principal-prefix (:id row)))

(defn principal
  "The principal an integration row acts as. An :agent, because it is
  neither a person nor the engine's own hand: a grant leashes it."
  [row]
  (t/principal {:id (principal-id row) :type :agent
                :display (str (or (get-in row [:data :name]) (:id row)))}))

(defn integration-principal?
  "Is this principal an integration?"
  [p]
  (str/starts-with? (str (:id p)) principal-prefix))

;; ── guards ──────────────────────────────────────────────────────────

(defn- a-person? [ctx]
  (= :human (get-in ctx [:principal :type])))

(g/defguard a-person-holds-it
  {:reads [:principal]
   :open "No door changes who the caller is: ask a person of the house."
   :explain "An integration is a person's to propose, approve, pause, rotate and retire; no agent and no integration moves one."}
  [_row _inp ctx]
  (if (a-person? ctx) (t/allow) (t/deny)))

(def the-creator-does-not-decide
  "The four-eyes wall, the held call's `the-caller-does-not-decide`
  over the field an integration names its proposer in."
  (assoc (g/not-the-field
          :created_by
          {:name :the-creator-does-not-decide
           :explain "The person who proposed this integration cannot be the one who approves it; another person decides."})
         :open (str "The wall is about WHO, not about a field of this "
                    "door: there is nothing to send that opens it. Ask "
                    "another person of the house to approve.")))

;; ── handlers ────────────────────────────────────────────────────────

(defn- born [row ctx]
  (update row :data assoc
          :created_by (str (get-in ctx [:principal :id]))))

(defhandler mint-the-grant [row _inp ctx]
  ;; the grant is born beside the verdict, in its transaction: offered
  ;; by the approver to the integration, and accepted by the engine,
  ;; because the approver's tap is the consent
  (let [wanted (not-empty (get-in row [:data :scope_wanted]))
        res ((:create ctx) :grant
             (cond-> {:audience (principal-id row)}
               wanted (assoc :scope (grants/merge-scope wanted))))
        gid (str (get-in res [:row :id]))]
    ((:invoke ctx) :grant gid :accept {} {:as engine-actor})
    (update row :data assoc
            :grant gid
            :approved_by (str (get-in ctx [:principal :id])))))

(defhandler rotate-secret [row inp ctx]
  (update row :data assoc
          :previous_secret (get-in row [:data :secret])
          :secret (:secret inp)
          :rotated_at (:now ctx)))

;; ── the kind ────────────────────────────────────────────────────────

(def ^:private name-field
  [:name {:x-display
          {:raw true
           :label "The name"
           :help "Unique, and the path segment of its receiver: /api/-/events/<name>."}}
   [:string {:min 1 :max 64}]])

(def ^:private system-field
  [:system {:examples ["Stripe, the payment processor"]
            :x-display
            {:label "The outside system"
             :help "One line: which system this is and what it is to the house. The audit reads it."}}
   [:string {:min 1 :max 200}]])

(def ^:private secret-field
  [:secret {:optional true
            :kind :secret
            :x-display
            {:label "Its secret"
             :help "The secret row that holds the signing key. The id, never the value."}}
   [:maybe :waymark/ref]])

(def ^:private signature-field
  [:signature {:optional true
               :x-display
               {:label "How it signs"
                :help "The header, the scheme, the timestamp header and its tolerance in seconds. Left empty: X-Signature, hmac-sha256, no timestamp."}}
   [:maybe
    [:map
     [:header {:optional true} [:maybe [:string {:min 1 :max 120}]]]
     [:scheme {:optional true} [:maybe [:enum "hmac-sha256"]]]
     [:timestamp_header {:optional true} [:maybe [:string {:min 1 :max 120}]]]
     [:tolerance_s {:optional true} [:maybe [:int {:min 0 :max 86400}]]]]]])

(defn- path-field [k label help]
  [k {:optional true
      :examples [["id"]]
      :x-display {:label label :help help}}
   [:maybe [:vector {:max 8} [:string {:min 1 :max 120}]]]])

(def ^:private server-field
  [:server {:optional true
            :kind :mcp_server
            :x-display
            {:label "The server its effects call"
             :help "The MCP server row its effects call. Leave it empty for an integration the house only hears from."}}
   [:maybe :waymark/ref]])

(def ^:private person-fields
  [name-field
   system-field
   secret-field
   signature-field
   (path-field :event_id_path "Where the event's id is"
               "The path to the event's own id in the body, as [\"id\"].")
   (path-field :event_type_path "Where the event's type is"
               "The path to the event's type in the body, as [\"type\"].")
   server-field])

(defresource integration
  {:kind :integration
   :plural "integrations"
   :states [:proposed :live :paused :retired]
   :initial :proposed
   :terminal #{:retired}
   :nav :system
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   :schema
   (into
    [:map]
    (concat
     person-fields
     [[:grant {:optional true
               :kind :grant
               :x-display
               {:label "The grant it wears"
                :help "Minted when a person approves it, to integration:<id>. Engine-written."}}
       [:maybe :waymark/ref]]
      [:scope_wanted {:optional true
                      :x-display
                      {:label "What the law asks of it"
                       :help "Every door the law has it run and every power its effects call; the scope its grant is minted with. Engine-written."}}
       [:maybe grants/scope-schema]]
      [:created_by {:optional true
                    :x-ref {:principal true}
                    :x-display
                    {:label "Proposed by"
                     :help "The person who made the row, who cannot approve it. Engine-written."}}
       [:maybe [:string {:max 200}]]]
      [:approved_by {:optional true
                     :x-ref {:principal true}
                     :x-display
                     {:label "Approved by"
                      :help "The person whose tap minted its grant. Engine-written."}}
       [:maybe [:string {:max 200}]]]
      [:previous_secret {:optional true
                         :kind :secret
                         :x-display
                         {:label "The secret before"
                          :help "The secret row a rotate replaced. Engine-written."}}
       [:maybe :waymark/ref]]
      [:rotated_at {:optional true
                    :x-display
                    {:label "Rotated"
                     :help "When its secret was last rotated. Engine-written."}}
       [:maybe :waymark/instant]]]))
   :create-schema (into [:map] person-fields)
   :filterable {:state #{:eq :in}
                :name #{:eq}}
   :sortable {:fields [:name] :default "name"}
   :unique [[:name]]
   :no-own-surface "An integration is the house's; only a grant that names the kind shows that one exists."
   :create-guards [a-person-holds-it]
   :on-create born
   :actions
   {:approve
    {:from #{:proposed} :to :live
     :guards [a-person-holds-it the-creator-does-not-decide]
     :touches [{:kind :grant :action :create}
               {:kind :grant :action :accept}]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The approve mints the grant this integration wears; the way back is pause, or retire."}
     :handler mint-the-grant
     :display {:label "Approve" :style :primary :order 1
               :description "Let this integration act, as itself, with the scope the law asks of it"}}
    :pause
    {:from #{:live} :to :paused
     :guards [a-person-holds-it]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Pause" :order 2
               :description "Stop applying what it sends, and stop its calls, until resume"}}
    :resume
    {:from #{:paused} :to :live
     :guards [a-person-holds-it]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Resume" :order 3}}
    :rotate
    {:from #{:live :paused} :to :live
     :input [:map
             [:secret {:kind :secret
                       :x-display
                       {:label "The new secret"
                        :help "The secret row that holds the new signing key. The old one is accepted for 24 hours."}}
              :waymark/ref]]
     :record true
     ;; the new secret is a fresh choice, never the old one restated,
     ;; and any secret row of the house may be the one a person names
     :waives #{:edit-shape :ref-unjudged}
     :guards [a-person-holds-it]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The new secret signs from now on; the old one is accepted for 24 hours and then not again."}
     :handler rotate-secret
     :display {:label "Rotate the secret" :order 4}}
    :retire
    {:from #{:proposed :live :paused} :to :retired
     :guards [a-person-holds-it]
     :safety {:idempotent true :reversible false :confirm true
              :consequence "This integration stops for good: nothing it sends is applied and nothing calls it again."}
     :display {:label "Retire" :style :danger :order 9}}}
   :deviations
   ["§ 2 says `approve` mints the grant through the ordinary approval path. It mints it in the approve's own transaction instead, through the ctx create door, and the engine accepts it there: an approval_request would be one more person's tap for the same decision, and its grant lands post-commit at the wire boundary (grants/wire-boundary-effects), which this door does not need."
    "§ 2's `event_id` and `event_type` are spelled `event_id_path` and `event_type_path`: they hold a path into the body, and a field named `event_id` reads as a ref to an `event` row."
    "`rotate` from `paused` lands `live`: a door names one destination. Pause again after a rotate that should not resume."]})
