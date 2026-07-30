(ns waymark10.saved-view
  "Saved views: the :views declaration promoted to a first-class kind
  (views as resources, waymark-rla). A developer declares a view once
  per deploy; a saved_view row is the same shape authored at RUNTIME —
  by a person or an agent — and everything a kind gets for free
  (storage, collection UI, forms, grants, events) comes with it. An
  app opts in by putting `saved-view` in its resources vector, exactly
  like any app kind; no engine magic.

  A saved view composes ONLY declared primitives — the target kind's
  filterable fields, its schema fields, its reversible actions — never
  new logic. The law that holds that line is the SAME battery the
  declaration-time check runs (waymark10.checks/view-problems), called
  here at write time through the ctx :rdef-of registry consult: create
  and revise refuse a view the target's own declaration would refuse.
  The collection surface (waymark10.server.collections) merges ACTIVE
  rows targeting a kind into that kind's envelope `views`
  advertisement, marked {:source \"saved\"} and carrying the row's own
  href, re-validating leniently — a view stranded by a redeploy is
  skipped with a warning there, and stays visible here for its owner
  to fix or retire.

  Field shape (all wire strings — the same tokens the collection
  grammar already speaks):
    :label      the human name the switcher chip wears
    :target     the viewed collection — kind name or plural
    :view_kind  \"deck\" | \"feed\"
    :where      filter params in the collection's own wire grammar
                (\"state=pending&owner=ana\", URL-encoded) — the exact
                string the filter bar already puts in the hash
    :card       the data-field subset a card shows
    :right/:left  deck gestures, naming declared reversible actions
    :description  why this slice exists

  Lifecycle: active → retired, reversible both ways (:undo pairs).
  :clone forks a copy through the ctx :create door — the copy passes
  the same create gate, so a stale view cannot propagate."
  (:require [clojure.string :as str]
            [waymark10.checks :as checks]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.types :as t])
  (:import (java.net URLDecoder)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(def kind
  "The saved_view kind keyword — the definite marker the collection
  surface checks registry membership against (never name-string
  matching)."
  :saved_view)

(def view-fields
  "The authored surface :revise overwrites wholesale — the same fields
  the write gate judges, so what is stored is exactly what was judged."
  [:label :target :view_kind :where :card :right :left :description])

(defn- decode ^String [^String s]
  (URLDecoder/decode s StandardCharsets/UTF_8))

(defn parse-where
  "The stored :where wire string (\"state=pending&owner=ana\",
  URL-encoded) as the {field-keyword wire-string} map the shared view
  validator and the envelope advertisement read. nil when blank; a
  malformed pair (no key) is dropped rather than crashed on — the
  validator judges what remains."
  [s]
  (when-not (str/blank? (str s))
    (not-empty
     (into {}
           (keep (fn [pair]
                   (let [[k v] (str/split pair #"=" 2)]
                     (when-not (str/blank? k)
                       [(keyword (decode k)) (decode (or v ""))]))))
           (str/split (str s) #"&")))))

(defn view-of
  "One saved_view row's data as the declared-view shape
  waymark10.checks/view-problems judges: {:kind :where :card :right
  :left}, keys absent when the row leaves them blank."
  [data]
  (let [blank->nil #(when-not (str/blank? (str %)) %)]
    (cond-> {:kind (keyword (str (:view_kind data)))}
      (parse-where (:where data)) (assoc :where (parse-where (:where data)))
      (seq (:card data)) (assoc :card (mapv keyword (:card data)))
      (blank->nil (:right data)) (assoc :right (keyword (:right data)))
      (blank->nil (:left data)) (assoc :left (keyword (:left data))))))

(defn problems
  "Every violation of one saved view's field set against the live
  registry: rdef-of is the ctx :rdef-of consult (kind token or plural
  → rdef). An unknown target is the one problem that precedes all
  others — there is no law to judge the rest against."
  [rdef-of data]
  (if-some [trdef (when-not (str/blank? (str (:target data)))
                    (rdef-of (str (:target data))))]
    (checks/view-problems trdef (view-of data))
    [(str "target " (pr-str (:target data))
          " names no kind this engine serves")]))

(g/defguard composes-declared-primitives
  {:judges [:target :view_kind :where :card :right :left]
   :reads [:storage]
   :vars [:problems]
   :open "The law is the target kind's own declaration — filterable fields, schema fields, reversible actions; a refusal names each violation."
   :explain "This view does not compose the target's declared primitives: {problems}"}
  [_row inp ctx]
  (if-some [rdef-of (:rdef-of ctx)]
    (if-some [ps (seq (problems rdef-of inp))]
      (t/deny {:vars {:problems (str/join "; " ps)}})
      (t/allow))
    ;; no registry in scope (a render probe): advertise optimistically —
    ;; the write path always carries the consult
    (t/allow)))

(defhandler apply-view
  [row inp _ctx]
  ;; the overwrite is wholesale over view-fields: an omitted optional
  ;; clears, so the stored fields are exactly the set the guard judged
  (update row :data
          (fn [d] (into d (map (fn [k] [k (get inp k)])) view-fields))))

(defhandler clone-view
  [row _inp ctx]
  ((:create ctx) kind
   (-> (select-keys (:data row) view-fields)
       (update :label (fn [l]
                        (let [l' (str l " (copy)")]
                          (subs l' 0 (min (count l') 60)))))))
  row)

(def ^:private view-input
  [:map
   [:label [:string {:min 1 :max 60}]]
   [:target {:x-display {:label "Target collection (kind name)"}}
    [:string {:min 1 :max 60}]]
   [:view_kind {:x-display {:label "View kind"}} [:enum "deck" "feed"]]
   [:where {:optional true
            :x-display {:label "Filter (wire params, e.g. state=pending)"
                        :raw true}}
    [:maybe [:string {:max 500}]]]
   [:card {:optional true
           :x-display {:label "Card fields"}}
    [:maybe [:vector [:string {:min 1 :max 60}]]]]
   [:right {:optional true
            :x-display {:label "Right gesture (deck: action name)"}}
    [:maybe [:string {:min 1 :max 60}]]]
   [:left {:optional true
           :x-display {:label "Left gesture (deck: action name)"}}
    [:maybe [:string {:min 1 :max 60}]]]
   [:description {:optional true :x-display {:widget "prose"}}
    [:maybe [:string {:max 280}]]]])

(defresource saved-view
  {:kind :saved_view
   :plural "saved_views"
   :nav :secondary
   :states [:active :retired]
   :initial :active
   :terminal #{}
   :summary "{data.label} · {data.view_kind} of {data.target} · {state}"
   :label-template "{data.label}"
   :schema [:map
            [:label {:sort :default} [:string {:min 1 :max 60}]]
            [:target {:filter #{:eq}
                      :x-display {:label "Target collection (kind name)"}}
             [:string {:min 1 :max 60}]]
            [:view_kind {:filter #{:eq} :x-display {:label "View kind"}}
             [:enum "deck" "feed"]]
            [:where {:optional true
                     :x-display {:label "Filter (wire params, e.g. state=pending)"
                                 :raw true}}
             [:maybe [:string {:max 500}]]]
            [:card {:optional true :x-display {:label "Card fields"}}
             [:maybe [:vector [:string {:min 1 :max 60}]]]]
            [:right {:optional true
                     :x-display {:label "Right gesture (deck: action name)"}}
             [:maybe [:string {:min 1 :max 60}]]]
            [:left {:optional true
                    :x-display {:label "Left gesture (deck: action name)"}}
             [:maybe [:string {:min 1 :max 60}]]]
            [:description {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 280}]]]]
   :filterable {:state #{:eq :in}}
   :create-guards [composes-declared-primitives]
   :actions
   {:revise {:from #{:active} :to :active
             :input view-input
             :edit {:prefill [:label :target :view_kind :where :card
                              :right :left :description]}
             ;; the overwrite writes the whole authored surface and is
             ;; non-reversible, so the log carries what was written
             :record true
             :guards [composes-declared-primitives]
             :safety {:idempotent true :reversible false :confirm false}
             :handler apply-view
             :display {:label "Revise" :order 1
                       :description "Rewrite this view's whole authored surface — the write gate re-judges it against the target's declaration"}}
    :clone {:from #{:active} :to :active
            :touches [{:kind :saved_view :action :create}]
            :safety {:idempotent false :reversible false :confirm false
                     :one-way "A clone the author thinks better of is one retire away — the original is untouched."}
            :handler clone-view
            :display {:label "Clone" :order 2
                      :description "Fork a copy of this view to revise independently"}}
    :retire {:from #{:active} :to :retired :undo :restore
             :safety {:idempotent true :confirm false}
             :display {:label "Retire" :style :danger :order 8
                       :description "Take this view off its collection's switcher — restore brings it back"}}
    :restore {:from #{:retired} :to :active :undo :retire
              :safety {:idempotent true :confirm false}
              :display {:label "Restore" :order 1}}}})
