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
  a grant with neither scope nor seat confers nothing, honestly.

  WHAT IT SENDS IS KEPT ONCE (§ 3, build step 2). `receive!` is the
  judgment behind POST /api/-/events/<name>: the signature checked in
  constant time against the secret row's value (and the one a rotate
  replaced, for 24 hours), the timestamp against `tolerance_s`, then
  one `integration_event` receipt per `(integration, event_id)`. A bad
  signature stores nothing; a duplicate answers the first receipt and
  changes nothing. Applying a receipt is build step 3."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.secrets :as secrets]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.io InputStream)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Duration Instant)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

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
     [:header {:optional true
               :x-display {:label "The signature header"
                           :help "The header the system's signature arrives in. Left empty: X-Signature."}}
      [:maybe [:string {:min 1 :max 120}]]]
     [:scheme {:optional true
               :x-display {:label "The scheme"
                           :help "How the signature is computed over the body. Only hmac-sha256 for now."}}
      [:maybe [:enum "hmac-sha256"]]]
     [:timestamp_header {:optional true
                         :x-display {:label "The timestamp header"
                                     :help "The header that carries when the system signed. Left empty: no timestamp is checked."}}
      [:maybe [:string {:min 1 :max 120}]]]
     [:tolerance_s {:optional true
                    :x-display {:label "Its tolerance, in seconds"
                                :help "How old a signed timestamp may be before the delivery is refused as a replay."}}
      [:maybe [:int {:min 0 :max 86400}]]]]]])

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

;; ── the receipt (§ 3, build step 2) ─────────────────────────────────

(g/defguard the-engine-keeps-the-receipt
  {:reads [:principal]
   :hide true
   :explain "The engine keeps a receipt when a signed event reaches its integration's receiver; nobody writes one by hand."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type]))
    (t/allow)
    (t/deny)))

(def body-cap
  "How much of an event's body a receipt keeps: 64 KB."
  65536)

(def ^:private receipt-fields
  [[:integration {:kind :integration
                  :x-display
                  {:label "The integration"
                   :help "The integration whose receiver took the event."}}
    :waymark/ref]
   [:event_id {:x-display
               {:raw true
                :label "The event's id"
                :help "Its own id, read from the body at the integration's event_id_path. One receipt per id."}}
    [:string {:min 1 :max 200}]]
   [:event_type {:optional true
                 :x-display
                 {:raw true
                  :label "The event's type"
                  :help "Read from the body at the integration's event_type_path."}}
    [:maybe [:string {:min 1 :max 200}]]]
   [:body {:optional true
           :x-display
           {:raw true
            :label "The body"
            :help "The body as it arrived, cut at 64 KB."}}
    [:maybe [:string {:max body-cap}]]]
   [:bytes {:x-display
            {:label "Its size, in bytes"
             :help "How many bytes the body was, before any cut."}}
    [:int {:min 0}]]
   [:truncated {:optional true
                :x-display
                {:label "Cut"
                 :help "True when the body was longer than 64 KB and the receipt keeps the first 64 KB of it."}}
    [:maybe :boolean]]])

(defresource integration-event
  {:kind :integration_event
   :plural "integration_events"
   :states [:received]
   :initial :received
   :terminal #{}
   :nav :system
   :summary "{data.event_type} {data.event_id} · {state}"
   :label-template "{data.event_id}"
   :schema (into [:map] receipt-fields)
   :filterable {:state #{:eq :in}
                :integration #{:eq}
                :event_id #{:eq}
                :event_type #{:eq}}
   :sortable {:fields [:event_id] :default "event_id"}
   :unique [[:integration :event_id]]
   :no-own-surface "A receipt is the house's; only a grant that names the kind shows that one exists."
   :create-guards [the-engine-keeps-the-receipt]
   ;; the endings and their doors are the applier's, build step 3
   :allow-dead #{:received}
   :actions {}
   :deviations
   ["§ 3 lists the endings a receipt moves to (applied, refused, unmatched, ambiguous, ignored) and its retry and dismiss doors; they come with the applier, build step 3. Until then every receipt stays `received`."
    "§ 3's `received` is the receipt's state; the moment it arrived is the row's own creation time, not a field."]})

;; ── the receiver (§ 3, build step 2) ────────────────────────────────

(def default-signature
  "How an integration signs when its `signature` leaves a part empty."
  {:header "X-Signature" :scheme "hmac-sha256" :tolerance_s 300})

(def ^:private rotation-grace (Duration/ofHours 24))

(defn sign
  "hex hmac-sha256(key, body): the signature a source sends."
  ^String [^String k ^bytes body]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. (.getBytes k StandardCharsets/UTF_8)
                                     "HmacSHA256")))]
    (str/join (map #(format "%02x" %) (.doFinal mac body)))))

(defn- signed-by?
  "Does `sent` sign `body` under `k`? Compared in constant time."
  [^String sent ^String k ^bytes body]
  (MessageDigest/isEqual (.getBytes (sign k body) StandardCharsets/UTF_8)
                         (.getBytes sent StandardCharsets/UTF_8)))

(defn- refuse [type status title detail]
  (p/problem type status title {:detail detail}))

(defn- rows-of [eng kind filt]
  (let [rd (get (inv/resources eng) kind)
        st (:storage eng)]
    (mapv #(inv/decode-row rd %)
          (store/with-tx st
            (fn [tx] (store/query-rows st tx kind filt {:limit 2}))))))

(defn- instant-of ^Instant [x]
  (cond (instance? Instant x) x
        (string? x) (try (Instant/parse x) (catch Exception _ nil))))

(defn- keys-of
  "The signing keys an integration's events may carry now: its secret's
  value, and for 24 hours after a rotate the one it replaced."
  [eng row ^Instant now]
  (let [d (:data row)
        value (fn [id] (some->> id (secrets/value-of eng) :value))
        rotated (instant-of (:rotated_at d))]
    (filterv some?
             (cond-> [(value (:secret d))]
               (and rotated (.isBefore now (.plus rotated rotation-grace)))
               (conj (value (:previous_secret d)))))))

(defn- fresh?
  "With a timestamp header, is the stamp it carries (epoch seconds)
  within the tolerance of now? Without one, nothing is checked."
  [headers sig ^Instant now]
  (if-some [h (some-> (:timestamp_header sig) str/lower-case)]
    (if-some [ts (try (some-> (get headers h) str/trim Long/parseLong)
                      (catch NumberFormatException _ nil))]
      (<= (Math/abs (- (.getEpochSecond now) (long ts)))
          (long (:tolerance_s sig)))
      false)
    true))

(defn- bytes-of ^bytes [b]
  (cond (nil? b) (byte-array 0)
        (bytes? b) b
        (string? b) (.getBytes ^String b StandardCharsets/UTF_8)
        :else (.readAllBytes ^InputStream b)))

(defn- at-path [body path]
  (reduce (fn [m k] (when (map? m) (or (get m (keyword k)) (get m k))))
          body path))

(defn receive!
  "POST /api/-/events/<nm>, judged. → {:status 202 :receipt id
  :duplicate false} when a receipt is kept, {:status 200 :receipt id
  :duplicate true} when one already was; otherwise throws the problem
  the route answers, having stored nothing: 404 no such integration,
  503 no key to check with, 401 a bad signature or a stale stamp, 503
  `paused`, 410 `retired`, 422 a body with no event id. The signature
  is judged before the state, so an unsigned caller learns no more
  than that the name exists."
  [eng nm headers body]
  (let [now ((:now-fn eng))
        row (or (first (rows-of eng :integration {:name (str nm)}))
                (throw (refuse :not-found 404 "Not found"
                               "No integration of that name.")))
        d (:data row)
        sig (merge default-signature
                   (into {} (remove (comp nil? val)) (:signature d)))
        ks (keys-of eng row now)
        _ (when (empty? ks)
            (throw (refuse :no-signing-key 503 "No signing key"
                           "The integration's secret holds no value to check a signature against; the owner enters one.")))
        bs (bytes-of body)
        sent (some-> (get headers (str/lower-case (:header sig)))
                     str/trim (str/replace #"^sha256=" "") str/lower-case
                     not-empty)]
    (when-not (and sent (some #(signed-by? sent % bs) ks) (fresh? headers sig now))
      (throw (refuse :bad-signature 401 "Unauthorized"
                     "The signature does not match the body, or its timestamp is outside the tolerance.")))
    (case (:state row)
      :paused (throw (refuse :integration-paused 503 "Paused"
                             "This integration is paused; send it again later."))
      :retired (throw (refuse :integration-retired 410 "Retired"
                              "This integration is retired; nothing it sends is kept."))
      nil)
    (let [text (String. bs StandardCharsets/UTF_8)
          parsed (try (wire/read-json text) (catch Exception _ nil))
          event-id (some-> (at-path parsed (or (not-empty (:event_id_path d)) ["id"]))
                           str not-empty)
          _ (when-not event-id
              (throw (refuse :no-event-id 422 "No event id"
                             "The body carries no event id at the integration's event_id_path.")))
          event-type (some-> (at-path parsed (or (not-empty (:event_type_path d)) ["type"]))
                             str not-empty)
          iid (str (:id row))
          kept (fn [] (when-some [r (first (rows-of eng :integration_event
                                                    {:integration iid :event_id event-id}))]
                        {:status 200 :receipt (str (:id r)) :duplicate true}))]
      (or (kept)
          (try
            {:status 202 :duplicate false
             :receipt (str (get-in (inv/create!
                                    eng :integration_event
                                    (cond-> {:integration iid
                                             :event_id event-id
                                             :body (subs text 0 (min (count text) body-cap))
                                             :bytes (alength bs)
                                             :truncated (> (alength bs) body-cap)}
                                      event-type (assoc :event_type event-type))
                                    {:principal engine-actor})
                                   [:row :id]))}
            (catch Exception e
              ;; two deliveries of one event at once: the index keeps
              ;; one, and the other answers it
              (or (and (:waymark10/unique-violation (ex-data e)) (kept))
                  (throw e))))))))
