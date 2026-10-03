(ns waymark10.server.secrets
  "The secret (ticket 8cd850fa): a credential the owner enters once,
  that no grant ever reads, and that a power call names by reference.

  THE VALUE IS WRITE-ONLY. `value` is a `:secret` field, so no
  projection carries it — no get, query, history, export or event
  frame — and no door records, so it is in no transition's inputs. A
  person writes it at create or through `replace`, and nobody else: a
  seat may create a row WITHOUT a value, as a request the owner fills.

  A CALL NAMES IT BY ITS ID. An argument whose tool input schema says
  `x-secret-ref: true` takes a secret row's id. `resolve-refs!` puts
  the value in its place at the one point the engine hands a call to a
  server (mcp-servers/call!), so the held call's `forward`, the
  caller's own transcript and every log keep the id. Each forward
  stamps `used_at` through the engine's hidden `used` door. What the
  server answers is `scrub`bed of every value it was given, and the
  transcript's second pass learns every value (`values`), so an echo
  is scrubbed too."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def engine-actor
  "The system actor a forward stamps `used_at` as."
  (t/principal {:id "waymark10-secrets" :type :system
                :display "Secrets"}))

;; ── guards ──────────────────────────────────────────────────────────

(defn- a-person? [ctx]
  (= :human (get-in ctx [:principal :type])))

(g/defguard a-person-enters-the-value
  {:judges [:value]
   :reads [:principal]
   :explain "A secret's value is the owner's to enter, by hand. Make the row without a value, and the owner fills it through replace."
   :remedies [:secret/create]}
  [_row inp ctx]
  (if (or (nil? (:value inp)) (a-person? ctx))
    (t/allow)
    (t/deny)))

(g/defguard the-owner-fills-the-value
  {:reads [:principal]
   :open "No door changes who the caller is: ask the owner to enter the value."
   :explain "A secret's value is entered by a person, and no agent sees it or writes it."}
  [_row _inp ctx]
  (if (a-person? ctx) (t/allow) (t/deny)))

(g/defguard the-engine-stamps-the-use
  {:reads [:principal]
   :hide true
   :explain "The engine stamps a secret's use when it forwards a call that names it."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type]))
    (t/allow)
    (t/deny)))

;; ── handlers ────────────────────────────────────────────────────────

(defn- born [row ctx]
  (update row :data assoc
          :created_by (str (get-in ctx [:principal :id]))
          :value_set_at (when (some? (get-in row [:data :value])) (:now ctx))))

(defhandler replace-value [row inp ctx]
  (update row :data
          (fn [data]
            (cond-> (assoc data :value (:value inp) :value_set_at (:now ctx))
              (contains? inp :note) (assoc :note (:note inp))))))

(defhandler stamp-used [row _inp ctx]
  (assoc-in row [:data :used_at] (:now ctx)))

;; ── the kind ────────────────────────────────────────────────────────

(def ^:private name-field
  [:name {:x-display
          {:raw true
           :label "The name"
           :help "What the secret is called where it is used, as TS_OAUTH_SECRET."}}
   [:string {:min 1 :max 120}]])

(def ^:private note-field
  [:note {:optional true
          :x-display
          {:widget "prose"
           :label "What it opens"
           :help "One sentence for the owner: what this credential is for and where to find it."}}
   [:maybe [:string {:max 480}]]])

(def ^:private value-help
  "The credential itself. The engine holds it and never shows it again.")

(defresource secret
  {:kind :secret
   :plural "secrets"
   :states [:active]
   :initial :active
   :terminal #{}
   :nav :system
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   :schema
   [:map
    name-field
    note-field
    ;; never rendered, never filterable, and never in a transition's
    ;; recorded inputs — which is why no door here records
    [:value {:optional true
             :secret true
             :x-display
             {:hidden true
              :label "The value"
              :spelled-by-hand "Written by the owner at create or through replace; never shown again."}}
     [:maybe [:string {:min 1 :max 4000}]]]
    [:created_by {:optional true
                  :x-ref {:principal true}
                  :x-display
                  {:label "Asked for by"
                   :help "Who made the row. Engine-written."}}
     [:maybe [:string {:max 200}]]]
    ;; not `updated_at`: every kind table carries that column already
    [:value_set_at {:optional true
                    :x-display
                    {:label "Value entered"
                     :help "When the owner last entered the value. Engine-written."}}
     [:maybe :waymark/instant]]
    [:used_at {:optional true
               :x-display
               {:label "Last used"
                :help "When the engine last put the value into a call. Engine-written."}}
     [:maybe :waymark/instant]]]
   :create-schema
   [:map
    name-field
    note-field
    [:value {:optional true
             :secret true
             :x-display
             {:raw true
              :label "The value"
              :help "The credential itself. The engine holds it and never shows it again. Leave it empty to ask the owner for it."}}
     [:maybe [:string {:min 1 :max 4000}]]]]
   :filterable {:state #{:eq :in}
                :name #{:eq}}
   :sortable {:fields [:name] :default "name"}
   :unique [[:name]]
   :create-guards [a-person-enters-the-value]
   :on-create born
   :actions
   {:replace
    {:from #{:active} :to :active
     :input [:map
             [:value {:x-display
                      {:raw true
                       :label "The value"
                       :help value-help}}
              [:string {:min 1 :max 4000}]]
             note-field]
     ;; NOT :record: this input carries the credential, and a recorded
     ;; action persists its raw inputs into the log.
     :guards [the-owner-fills-the-value]
     :edit {:prefill [:note] :fence true}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The secret holds the value this replace enters; the old one is not kept."}
     :handler replace-value
     :display {:label "Enter the value" :style :primary :order 1
               :description "Enter or replace the credential; it is never shown again"}}
    :used
    {:from #{:active} :to :active
     :guards [the-engine-stamps-the-use]
     ;; an in-state door with no input would replay its last outcome
     :replay false
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Bookkeeping the engine writes as it forwards a call; the next forward moves it again."}
     :handler stamp-used
     :display {:label "Used"}}}})

;; ── by reference ────────────────────────────────────────────────────

(defn- rdef-of [eng] (get (inv/resources eng) :secret))

(defn- row-of [eng id]
  (when-some [rd (rdef-of eng)]
    (let [st (:storage eng)]
      (some->> (store/with-tx st
                 (fn [tx] (store/load-row st tx :secret (str id) {})))
               (inv/decode-row rd)))))

(defn values
  "Every value a secret row holds: what the transcript's second pass
  scrubs."
  [eng]
  (if-some [rd (rdef-of eng)]
    (let [st (:storage eng)]
      (into #{}
            (keep #(some-> (get-in (inv/decode-row rd %) [:data :value])
                           str not-empty))
            (store/with-tx st
              (fn [tx] (store/query-rows st tx :secret {} {:limit 1000})))))
    #{}))

(defn ref-fields
  "The argument names a tool's input schema marks `x-secret-ref: true`."
  [input-schema]
  (into #{}
        (keep (fn [[k prop]]
                (when (true? (or (get prop :x-secret-ref)
                                 (get prop "x-secret-ref")))
                  (name k))))
        (or (get input-schema :properties)
            (get input-schema "properties"))))

(defn resolve-refs!
  "→ {:args :values}: `args` with each ref argument's secret row id
  replaced by the value that row holds, and the set of values put in.
  Stamps `used_at` on each row. A ref naming no row, or a row the owner
  has not filled yet, refuses."
  [eng input-schema args]
  (reduce
   (fn [acc field]
     (let [k (some #(when (contains? args %) %) [(keyword field) field])]
       (if (nil? k)
         acc
         (let [id (str (get args k))
               row (row-of eng id)
               v (get-in row [:data :value])]
           (when (nil? row)
             (throw (p/not-found "secret" id)))
           (when (str/blank? (str v))
             (throw (p/problem :secret-empty 409 "Secret has no value"
                               {:detail (str "The secret " (pr-str id)
                                             " holds no value yet; the owner enters it through replace.")})))
           (inv/invoke! eng :secret id :used {} {:principal engine-actor})
           (-> acc
               (assoc-in [:args k] v)
               (update :values conj v))))))
   {:args (or args {}) :values #{}}
   (sort (ref-fields input-schema))))

(defn scrub
  "`x` with every one of `vals` replaced, in every string it holds."
  [x vals]
  (let [vs (remove str/blank? vals)]
    (if (empty? vs)
      x
      (walk/postwalk
       (fn [v]
         (if (string? v)
           (reduce (fn [^String s ^String secret]
                     (str/replace s secret "[redacted:secret]"))
                   v vs)
           v))
       x))))
