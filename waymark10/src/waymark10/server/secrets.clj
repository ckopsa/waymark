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
  is scrubbed too. Scrubbing reads every spelling of a value (`forms`),
  a forward's error is scrubbed as its answer is (`scrub-error`), and
  an argument no secret can fill refuses by its name and type before
  any value is read (`shape-problem`, ticket 7daae831)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.roles :as roles]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

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
          :examples ["The Tailscale OAuth client secret the tofu workflow reads; it is under Settings, OAuth clients."]
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
             :at-rest :encrypted
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
   :no-own-surface "A secret is a value of the house; only a grant that names the kind shows that one exists."
   ;; a credential is the house's: only a manager makes or fills one
   ;; (R-3.2), and a person still enters the value
   :create-guards [a-person-enters-the-value roles/a-manager]
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
     :guards [the-owner-fills-the-value roles/a-manager]
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

(declare forms)

(defn values
  "Every value a secret row holds, in each of its `forms`: what the
  transcript's second pass scrubs."
  [eng]
  (if-some [rd (rdef-of eng)]
    (let [st (:storage eng)]
      (into #{}
            (comp (keep #(some-> (get-in (inv/decode-row rd %) [:data :value])
                                 str not-empty))
                  (mapcat forms))
            (store/with-tx st
              (fn [tx] (store/query-rows st tx :secret {} {:limit 1000})))))
    #{}))

(defn value-of
  "→ nil when `id` names no secret row; otherwise {:value v}, where v is
  nil while the owner has not filled the row. Reads only, with no
  `used_at` stamp: the webhook deliverer asks at every delivery, and a
  stamp is a transition the deliverer would then deliver."
  [eng id]
  (when-some [row (row-of eng id)]
    {:value (some-> (get-in row [:data :value]) str not-empty)}))

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

(def ref-form
  "What an `x-secret-ref` argument takes, in the words a caller reads."
  "the id of a secret row (waymark_query kind secret), bare: no prefix, and not its name")

(defn describe-refs
  "`input-schema` with the accepted form said in the description of
  each `x-secret-ref` property, so a caller reads it where it reads the
  tool and does not guess."
  [input-schema]
  (let [pk (some #(when (contains? input-schema %) %) [:properties "properties"])
        dk (if (string? pk) "description" :description)
        form (str "Takes " ref-form ". The engine puts the secret's value in its place.")]
    (reduce
     (fn [schema field]
       (let [k (some #(when (contains? (get schema pk) %) %)
                     [(keyword field) field])]
         (update-in schema [pk k dk]
                    #(if (str/blank? (str %)) form (str % " " form)))))
     input-schema
     (sort (ref-fields input-schema)))))

(defn- no-such [field id]
  (str "No secret " (pr-str id) ". The argument `" field
       "` takes " ref-form "."))

(defn- no-such-secret [field id]
  (p/problem :not-found 404 "Not found" {:detail (no-such field id)}))

(defn ref-problem
  "The sentence for the first ref argument of `args` that names no
  secret row, saying the form a ref takes, or nil. `known?` answers
  whether an id names a row: the reader is the caller's, so a guard
  judges inside its own transaction."
  [input-schema args known?]
  (some (fn [field]
          (when-some [k (some #(when (contains? args %) %) [(keyword field) field])]
            (let [id (str (get args k))]
              (when-not (known? id)
                (no-such field id)))))
        (sort (ref-fields input-schema))))

(defn- prop-of [input-schema field]
  (let [props (or (get input-schema :properties)
                  (get input-schema "properties"))]
    (or (get props (keyword field)) (get props field))))

(defn- declared-types
  "The JSON types a property declares, as a set of strings, or nil."
  [prop]
  (let [ty (or (get prop :type) (get prop "type"))]
    (cond (nil? ty) nil
          (sequential? ty) (into #{} (map name) ty)
          :else #{(name ty)})))

(defn shape-problem
  "[field sentence] for the first ref argument of `args` no secret can
  fill, or nil. A ref is a row id, a string, and the value the engine
  puts in its place is a string: an argument the caller sent as
  anything else, or a property declaring a type a string is not,
  refuses here, BEFORE any value is read — so the refusal names the
  argument and the type and can quote no value (ticket 7daae831)."
  [input-schema args]
  (some (fn [field]
          (when-some [k (some #(when (contains? args %) %) [(keyword field) field])]
            (let [types (declared-types (prop-of input-schema field))]
              (cond
                (not (string? (get args k)))
                [field (str "The argument `" field "` takes " ref-form
                            ", a string.")]

                (and types (not (contains? types "string")))
                [field (str "The argument `" field "` is declared "
                            (str/join " or " (sort types))
                            ", but a secret's value is a string: no secret fills it.")]))))
        (sort (ref-fields input-schema))))

(defn- check-shape! [input-schema args]
  (when-some [[field sentence] (shape-problem input-schema args)]
    (throw (p/schema-invalid :call {(keyword field) [sentence]}))))

(defn check-refs!
  "Refuses when a ref argument of `args` has a shape no secret fills
  (`shape-problem`) or names no secret row, saying the form a ref
  takes. Reads only: it is what a call is judged by BEFORE it is held,
  so a person's tap is never spent on a reference that could not
  resolve. A row the owner has not filled yet passes; the owner may
  fill it before the tap."
  [eng input-schema args]
  (check-shape! input-schema args)
  (when-some [problem (ref-problem input-schema args #(some? (row-of eng %)))]
    (throw (p/problem :not-found 404 "Not found" {:detail problem}))))

(defn resolve-refs!
  "→ {:args :values}: `args` with each ref argument's secret row id
  replaced by the value that row holds, and the set of values put in.
  Stamps `used_at` on each row. An argument of the wrong shape
  (`shape-problem`), a ref naming no row, or a row the owner has not
  filled yet, refuses."
  [eng input-schema args]
  (check-shape! input-schema args)
  (reduce
   (fn [acc field]
     (let [k (some #(when (contains? args %) %) [(keyword field) field])]
       (if (nil? k)
         acc
         (let [id (str (get args k))
               row (row-of eng id)
               v (get-in row [:data :value])]
           (when (nil? row)
             (throw (no-such-secret field id)))
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

(def ^:private leaf-min
  "The shortest string inside a JSON value that is scrubbed on its own:
  a shorter one is too common a word to hide everywhere."
  4)

(defn- json-strings
  "Every string `s` holds when it is the JSON text of an object or an
  array; otherwise nil."
  [^String s]
  (when (re-find #"^\s*[\{\[]" s)
    (try
      (let [acc (volatile! [])]
        (walk/postwalk #(do (when (string? %) (vswap! acc conj %)) %)
                       (wire/read-json s))
        @acc)
      (catch Exception _ nil))))

(defn- json-escaped
  "`s` as it sits inside a JSON string: quotes and backslashes escaped."
  [^String s]
  (let [j (wire/write-json s)]
    (subs j 1 (dec (count j)))))

(defn forms
  "Every spelling of the value `v` an answer or an error may carry: the
  value, the value escaped inside a JSON string, and, when the value is
  JSON text, each string it holds, alone and escaped. A JSON
  {username, password} quoted back inside a server's JSON error never
  matched the raw value (ticket 7daae831)."
  [v]
  (let [s (str v)]
    (if (str/blank? s)
      #{}
      (into #{}
            (comp (mapcat (juxt identity json-escaped))
                  (remove str/blank?))
            (cons s (filter #(>= (count %) leaf-min) (json-strings s)))))))

(defn scrub
  "`x` with every one of `vals`, in each of its `forms`, replaced in
  every string it holds — the longest spelling first, so a whole value
  is redacted before any string inside it."
  [x vals]
  (let [vs (sort-by (comp - count) (into #{} (mapcat forms) vals))]
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

(defn scrub-error
  "`e` with every one of `vals` scrubbed from its message and its data:
  what a forward rethrows, so a server's refusal that quotes an
  argument back carries no value. A problem keeps its data, scrubbed,
  and loses its cause, whose message may quote the value too; any other
  failure is rethrown as it is unless its message holds a value, and
  then becomes an ex-info with the scrubbed message."
  [^Throwable e vals]
  (let [msg (str (ex-message e))
        msg' (scrub msg vals)]
    (cond
      (instance? clojure.lang.ExceptionInfo e)
      (let [data (ex-data e)
            data' (scrub data vals)]
        (if (and (= msg msg') (= data data'))
          e
          (ex-info msg' data')))

      (not= msg msg') (ex-info msg' {})
      :else e)))
