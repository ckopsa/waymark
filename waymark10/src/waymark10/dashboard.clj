(ns waymark10.dashboard
  "Dashboards: user-configurable surfaces (waymark-ggw). A developer
  declares a surface once per deploy (waymark10.server.surface); a
  dashboard is the anchorless surface's user-authored sibling —
  composed at RUNTIME from a dashboard row and its OWNED dashboard_slot
  parts, each slot a collection query the client renders as one live
  panel. Two framework kinds an app opts into together, exactly like
  saved_view (views as resources, waymark-rla): put BOTH `dashboard`
  and `dashboard-slot` (the `resources` vector below) in the app's
  resources vector and storage, forms, grants, events, and the owns
  machinery come free; no engine magic.

  A dashboard composes ONLY declared primitives — existing kinds,
  their own filter grammar, their declared or saved views — never a
  query/layout DSL. Each slot carries:
    :dashboard_id  the owning dashboard (the owns :via ref)
    :label         the panel's human name
    :target        the watched collection — kind name or plural,
                   exactly saved_view's spelling
    :where         filter params in the collection's own wire grammar
                   (\"state=pending&owner=ana\", URL-encoded) — the
                   string the filter bar already puts in the hash
    :view          optional deep link: a view token the target kind
                   DECLARES, or \"sv-<id>\" — the wire name the
                   collection envelope mints for an active saved_view
                   row targeting the same kind
    :seat          optional ordering int; the client sorts by it

  :target, :where and :view are judged against a vocabulary only the
  RUNNING engine enumerates — the kinds it serves, one kind's filter
  grammar, its declared views — so each carries an `:x-options` recipe
  (waymark-8sg) telling any client where to fetch the options in one
  hop. Advertisement, not law: the guard below is unchanged, and the
  widget offers rather than cages precisely because a legal
  \"sv-<id>\" is minted per row and no source can list it.

  The law that holds the composition line is the write-time guard on
  slot create/revise, the same style as saved_view's
  composes-declared-primitives: the ctx :rdef-of registry consult
  resolves :target to a real kind, the shared
  waymark10.checks/view-where-problems battery judges :where against
  that kind's OWN filter grammar, and a :view reference must resolve —
  a declared view token, or a saved_view row that is active and aims
  at the same kind (read through ctx :read, the write's own
  transaction).

  Rendering degrades LENIENTLY, saved-view-entries' tradition: the
  guard gates writes, but a redeploy can strand a stored slot (its
  field unfiltered, its view retired). The dashboard envelope still
  serves — the slots ride as the embedded :slots link, ordinary
  envelope-summaries — and the CLIENT renders a stranded slot as a
  problem panel wearing the collection's own refusal, never a broken
  page; its owner revises or removes it from the slot's own screen.

  Lifecycle: dashboard active → retired, reversible both ways (:undo
  pairs); slots active → removed, likewise. :clone forks the dashboard
  AND deep-copies its active slots through the ctx :create door — each
  copy passes the same create gate, so a stale slot cannot propagate."
  (:require [clojure.string :as str]
            [waymark10.checks :as checks]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.saved-view :as sv]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def kind
  "The dashboard kind keyword — the definite marker (and the client's
  dispatch key: an envelope whose kind is \"dashboard\" with an
  embedded :slots link renders as a dashboard, not a kv table)."
  :dashboard)

(def slot-kind
  "The dashboard_slot part kind keyword."
  :dashboard_slot)

(def dashboard-fields
  "The authored surface :revise overwrites wholesale — the same fields
  the write gate judges, so what is stored is exactly what was judged."
  [:label :description])

(def slot-fields
  "The slot surface :revise overwrites wholesale. :dashboard_id stays
  out — a slot never moves between dashboards; clone copies instead."
  [:label :target :where :view :seat])

;; ── the write-time law (the guard both slot gates run) ─────────────

(defn- view-ref-problems
  "One slot's :view reference judged against the resolved target rdef:
  a declared view token of the target kind, or \"sv-<id>\" naming a
  saved_view row that is ACTIVE and aims at the same kind — the exact
  wire name the collection envelope's saved-view merge mints, so the
  deep link lands on a switcher chip that exists. read' is the ctx
  :read hook (nil on a render probe: the sv- resolution is skipped —
  the write path always carries it). Returns problem strings."
  [rdef-of read' trdef view]
  (let [v (str view)]
    (when-not (str/blank? v)
      (if (str/starts-with? v "sv-")
        (when read'
          (let [row (read' sv/kind (subs v 3))]
            (cond
              (nil? row)
              [(str "view " (pr-str v)
                    " names no saved view this engine serves")]

              (not= "active" (name (:state row)))
              [(str "view " (pr-str v)
                    " names a saved view that is not active")]

              (not= (:kind trdef)
                    (:kind (rdef-of (str (get-in row [:data :target])))))
              [(str "view " (pr-str v) " targets "
                    (pr-str (get-in row [:data :target])) ", not "
                    (name (:kind trdef)))])))
        (when-not (some #(= (keyword v) (:name %)) (:views trdef))
          [(str "view " (pr-str v) " is not a declared view of "
                (name (:kind trdef)))])))))

(defn slot-problems
  "Every violation of one slot's field set against the live registry:
  rdef-of is the ctx :rdef-of consult, read' the ctx :read hook. An
  unknown target precedes all others — there is no law to judge the
  rest against. The :where battery is the SAME one saved_view and the
  declaration-time [views] check run (checks/view-where-problems)."
  [rdef-of read' data]
  (if-some [trdef (when-not (str/blank? (str (:target data)))
                    (rdef-of (str (:target data))))]
    (-> []
        (into (when-some [w (sv/parse-where (:where data))]
                (checks/view-where-problems trdef w)))
        (into (view-ref-problems rdef-of read' trdef (:view data))))
    [(str "target " (pr-str (:target data))
          " names no kind this engine serves")]))

(g/defguard slot-composes-declared-primitives
  {:judges [:target :where :view]
   :reads [:storage]
   :vars [:problems]
   :open "The law is the target kind's own declaration — a known kind, its filterable fields, its declared or saved views; a refusal names each violation."
   :explain "This slot does not compose declared primitives: {problems}"}
  [_row inp ctx]
  (if-some [rdef-of (:rdef-of ctx)]
    (if-some [ps (seq (slot-problems rdef-of (:read ctx) inp))]
      (t/deny {:vars {:problems (str/join "; " ps)}})
      (t/allow))
    ;; no registry in scope (a render probe): advertise optimistically —
    ;; the write path always carries the consult
    (t/allow)))

;; ── handlers ────────────────────────────────────────────────────────

(defhandler apply-dashboard
  [row inp _ctx]
  ;; the overwrite is wholesale over dashboard-fields: an omitted
  ;; optional clears, so what is stored is exactly what was judged
  (update row :data
          (fn [d] (into d (map (fn [k] [k (get inp k)])) dashboard-fields))))

(defhandler apply-slot
  [row inp _ctx]
  (update row :data
          (fn [d] (into d (map (fn [k] [k (get inp k)])) slot-fields))))

(defn- copy-label [l]
  (let [l' (str l " (copy)")]
    (subs l' 0 (min (count l') 60))))

(defhandler clone-dashboard
  [row _inp ctx]
  ;; the deep copy: the dashboard forks through the ctx :create door,
  ;; then each ACTIVE slot re-passes ITS create gate pointed at the
  ;; copy — a slot gone stale since it was written cannot propagate
  (let [res ((:create ctx) kind
             (-> (select-keys (:data row) dashboard-fields)
                 (update :label copy-label)))
        new-id (get-in res [:row :id])]
    (when new-id
      (doseq [s (sort-by (fn [s] [(or (get-in s [:data :seat]) Long/MAX_VALUE)
                                  (str (get-in s [:data :label]))])
                         ((:find ctx) slot-kind
                          {:dashboard_id (:id row) :state :active}
                          {:limit 200}))]
        ((:create ctx) slot-kind
         (-> (select-keys (:data s) slot-fields)
             (assoc :dashboard_id new-id)))))
    row))

;; ── the kinds ───────────────────────────────────────────────────────

(def dashboard-description-example
  "The blank box's starting point (waymark-0ee's composition policy):
  offered as a placeholder, never applied as a value."
  "The Sunday page — what is unclaimed, what is overdue, what is thawing.")

(def ^:private dashboard-prose
  "The household's own words for the two authored fields, spelled once
  and worn by both doors: the create form (this kind declares no
  :create-schema, so its data schema IS the create form) and :revise."
  {:label {:x-display {:label "Name"
                       :help "What this page is called in the nav and on its own header."}}
   :description {:examples [dashboard-description-example]
                 :x-display {:widget "prose"
                             :label "What this page is for"
                             :help "A sentence for whoever opens it later — which corner of the week it watches."}}})

(def ^:private dashboard-input
  [:map
   [:label (:label dashboard-prose) [:string {:min 1 :max 60}]]
   [:description (assoc (:description dashboard-prose) :optional true)
    [:maybe [:string {:max 280}]]]])

(defresource dashboard
  {:kind :dashboard
   :plural "dashboards"
   :nav :secondary
   :states [:active :retired]
   :initial :active
   :terminal #{}
   :summary "{data.label} · dashboard · {state}"
   :label-template "{data.label}"
   :schema [:map
            [:label (assoc (:label dashboard-prose) :sort :default)
             [:string {:min 1 :max 60}]]
            [:description (assoc (:description dashboard-prose) :optional true)
             [:maybe [:string {:max 280}]]]]
   :filterable {:state #{:eq :in}}
   :owns [{:kind :dashboard_slot :via :dashboard_id}]
   ;; the embed IS the render contract: GET /api/dashboards/{id}
   ;; splices the ACTIVE slots at links.slots.embedded, each an
   ;; envelope-summary whose :fields carry label/target/where/view/seat
   :links [{:rel :slots :owns :dashboard_slot :embed {:limit 50}
            :where {:state "active"}}]
   :actions
   {:revise {:from #{:active} :to :active
             :input dashboard-input
             :edit {:prefill [:label :description]}
             :record true
             :safety {:idempotent true :reversible false :confirm false}
             :handler apply-dashboard
             :display {:label "Revise" :order 1
                       :description "Rewrite this dashboard's label and description — the slots are their own rows"}}
    :clone {:from #{:active} :to :active
            :touches [{:kind :dashboard :action :create}
                      {:kind :dashboard_slot :action :create :may true}]
            :safety {:idempotent false :reversible false :confirm false
                     :one-way "A clone the author thinks better of is one retire away — the original and its slots are untouched."}
            :handler clone-dashboard
            :display {:label "Clone" :order 2
                      :description "Fork a copy of this dashboard, deep-copying its active slots through the same create gate"}}
    :retire {:from #{:active} :to :retired :undo :restore
             :safety {:idempotent true :confirm false}
             :display {:label "Retire" :style :danger :order 8
                       :description "Shelve this dashboard — restore brings it back, slots intact"}}
    :restore {:from #{:retired} :to :active :undo :retire
              :safety {:idempotent true :confirm false}
              :display {:label "Restore" :order 1}}}})

(def ^:private slot-prose
  "One panel's authored surface in the household's own words, spelled
  ONCE and worn by all three of its forms — the row schema, the create
  door's narrower ask, and :revise. Three copies of the same sentence
  is three places for it to drift.

  :target, :where and :view are the fields
  `slot-composes-declared-primitives` judges against a vocabulary only
  the running engine enumerates, so each carries its `:x-options`
  recipe (waymark-8sg) beside its prose: the picker is advertised, the
  guard still decides."
  {:dashboard_id {:x-display {:label "Dashboard"
                              :help "The page this panel sits on. A slot never moves; clone it instead."}}
   :label {:x-display {:label "Panel name"
                       :help "The heading over this panel — a few words read at a glance."}}
   :target {:x-options {:from :kinds}
            :x-display {:label "Watched collection"
                        :help "The collection this panel shows — one of the kinds this engine serves."}}
   :where {:x-options {:from :filters :of :target :composes :query}
           :x-display {:label "Filter"
                       :help "The slice, in the collection's own filter grammar — state=pending&owner=ana. Every name left of an = is a filterable field of the target."
                       :raw true}}
   :view {:x-options {:from :views :of :target}
          :x-display {:label "View"
                      :help "Optional deep link: a view the target kind declares, or sv-<id> naming an active saved view aimed at the same kind."}}
   :seat {:x-display {:label "Order"
                      :help "Where the panel sits on the page; lower numbers come first."}}})

(defn- slot-entry
  "One [:key props schema] entry of a slot form: the shared prose,
  plus whatever this surface adds of its own (an optional marker, a
  sort key, a filter grammar, a ref's target kind)."
  [k extra form]
  [k (merge (get slot-prose k) extra) form])

(def ^:private slot-input
  [:map
   (slot-entry :label {} [:string {:min 1 :max 60}])
   (slot-entry :target {} [:string {:min 1 :max 60}])
   (slot-entry :where {:optional true} [:maybe [:string {:max 500}]])
   (slot-entry :view {:optional true} [:maybe [:string {:max 80}]])
   (slot-entry :seat {:optional true} [:maybe [:int {:min 0}]])])

(defresource dashboard-slot
  {:kind :dashboard_slot
   :plural "dashboard_slots"
   :nav :secondary
   :states [:active :removed]
   :initial :active
   :terminal #{}
   :summary "{data.label} · {data.target} · {state}"
   ;; a panel names itself by its own heading — without this every ref
   ;; picker, card and link badge falls back to the raw id
   :label-template "{data.label}"
   :schema [:map
            (slot-entry :dashboard_id {:kind :dashboard :label :dashboard_label
                                       :filter #{:eq} :pick {:state "active"}}
                        :waymark/ref)
            [:dashboard_label {:optional true} [:maybe [:string {:max 200}]]]
            (slot-entry :label {:sort :default} [:string {:min 1 :max 60}])
            (slot-entry :target {:filter #{:eq}} [:string {:min 1 :max 60}])
            (slot-entry :where {:optional true} [:maybe [:string {:max 500}]])
            (slot-entry :view {:optional true} [:maybe [:string {:max 80}]])
            (slot-entry :seat {:optional true} [:maybe [:int {:min 0}]])]
   ;; the client states the ask; the dashboard label is the engine's
   ;; ref-label pass
   :create-schema [:map
                   (slot-entry :dashboard_id {:kind :dashboard
                                              :pick {:state "active"}}
                               :waymark/ref)
                   (slot-entry :label {} [:string {:min 1 :max 60}])
                   (slot-entry :target {} [:string {:min 1 :max 60}])
                   (slot-entry :where {:optional true} [:maybe [:string {:max 500}]])
                   (slot-entry :view {:optional true} [:maybe [:string {:max 80}]])
                   (slot-entry :seat {:optional true} [:maybe [:int {:min 0}]])]
   :filterable {:state #{:eq :in}}
   :create-guards [slot-composes-declared-primitives]
   :actions
   {:revise {:from #{:active} :to :active
             :input slot-input
             :edit {:prefill [:label :target :where :view :seat]}
             :record true
             :guards [slot-composes-declared-primitives]
             :safety {:idempotent true :reversible false :confirm false}
             :handler apply-slot
             :display {:label "Revise" :order 1
                       :description "Rewrite this slot's whole authored surface — the write gate re-judges it against the target's declaration"}}
    :remove {:from #{:active} :to :removed :undo :restore
             :safety {:idempotent true :confirm false}
             :display {:label "Remove" :style :danger :order 8
                       :description "Take this panel off its dashboard — restore brings it back"}}
    :restore {:from #{:removed} :to :active :undo :remove
              :safety {:idempotent true :confirm false}
              :display {:label "Restore" :order 1}}}})

(def resources
  "Both kinds, in registration order — the parent's :owns edge needs
  the child on the same engine, so an app opts into the pair."
  [dashboard dashboard-slot])
