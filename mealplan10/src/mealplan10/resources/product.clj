(ns mealplan10.resources.product
  "The Product resource: one store's representation of an ingredient
  (\"Kirkland Organic Chicken Thighs, 2.72 kg, Costco\"). It
  accumulates price sightings — receipts and scrapes — and its
  lifecycle guards the MATCH (is this really that ingredient?), never
  the price.

  A sighting's price_cents is what ONE package cost — the per-item
  price, never the receipt line's extended total; quantity is how
  many packages were bought (spend context: actual spend =
  price_cents × quantity) and is NEVER a divisor — that arithmetic
  was tried and reverted (mealplan9 f9f308d). One sighting per day:
  record_sighting upserts on seen_on, so a same-day correction
  replaces instead of duplicating.

  The three price facts (last_seen_on, latest_price_cents,
  cents_per_100g) are handler-maintained plain fields, recomputed by
  reprice-product at every sighting/package write — argmax-over-parts
  is beyond the expression grammar, the same boundary v9 drew with
  its fn= lambdas. price_is_stale IS a law: a clock fact whose
  :flips-at hands the maintainer the exact instant, so the scraper's
  whole work queue is ?state=tracked&price_is_stale=true with no
  write and no poll."
  (:require [waymark10.dsl :refer [defaction defderived defresource
                                   defhandler guard]])
  (:import (java.time LocalDate ZoneOffset)))

(def overwrite
  {:idempotent true :reversible false :confirm false})

(def sighting-schema
  [:map
   [:seen_on :waymark/date]
   [:price_cents {:x-display {:widget "money" :label "Price (one package)"}}
    [:int {:min 1}]]
   [:source [:enum "receipt" "scrape"]]
   [:ref {:optional true :x-display {:raw true}}
    [:maybe [:string {:max 280}]]]
   [:quantity {:optional true :default 1} [:maybe [:int {:min 1}]]]
   [:on_sale {:optional true :default false} [:maybe :boolean]]])

(defn reprice-product
  "The price facts from the sightings, restated: sightings sorted by
  seen_on (the upsert key), last_seen_on/latest_price_cents from the
  newest, cents_per_100g = latest × 100 ÷ package_grams rounded
  half-up — nil when nothing is unit-priceable (no sightings, or a
  counted good with no package_grams)."
  [data]
  (let [ss (vec (sort-by :seen_on (:sightings data)))
        latest (peek ss)
        grams (:package_grams data)
        price (:price_cents latest)]
    (assoc data
           :sightings ss
           :last_seen_on (:seen_on latest)
           :latest_price_cents price
           :cents_per_100g (when (and price grams (pos? (long grams)))
                             (quot (+ (* (long price) 100) (quot (long grams) 2))
                                   (long grams))))))

(defderived price-is-stale
  {:over [:last_seen_on :now]
   :expr '(or (not (is-set (var :last_seen_on)))
              (<= (+ (var :last_seen_on) (days 30)) (date-of (var :now))))
   ;; scheduling advice, never law: the exact flip instant, so the
   ;; maintainer's clock index sweeps this row precisely once
   :flips-at (fn [row]
               (some-> ^LocalDate (get-in row [:data :last_seen_on])
                       (.plusDays 30)
                       (.atStartOfDay ZoneOffset/UTC)
                       .toInstant))
   :explain "Last priced {last} — a receipt line or a scrape refreshes it."
   :vars {:last '(var :last_seen_on)}})

(def sighting-on-record
  (guard {:name :sighting-on-record
          :judges [:seen_on]
          :accepts (fn [row] (mapv :seen_on (get-in row [:data :sightings])))
          :explain "{seen_on} is not a recorded sighting of this product."}))

(defhandler apply-rematch [row inp _ctx]
  ;; the label is the engine's — the handler sets the ref only
  (assoc-in row [:data :ingredient_id] (:ingredient_id inp)))

(defhandler record-sighting [row inp _ctx]
  (update row :data
          (fn [data]
            (reprice-product
             (update data :sightings
                     (fn [ss]
                       (conj (vec (remove #(= (:seen_on %) (:seen_on inp)) ss))
                             inp)))))))

(defhandler remove-sighting [row inp _ctx]
  (update row :data
          (fn [data]
            (reprice-product
             (update data :sightings
                     (fn [ss]
                       (vec (remove #(= (:seen_on %) (:seen_on inp)) ss))))))))

(defhandler apply-product-details [row inp _ctx]
  (update row :data
          (fn [data]
            (reprice-product   ; package_grams moves cents_per_100g
             (merge data (select-keys inp [:name :package_grams :package_count
                                           :upc :url]))))))

(defaction rematch
  {:from #{:suggested :tracked} :to :tracked
   :input [:map [:ingredient_id {:kind :ingredient :pick {:state "active"}}
                 :waymark/ref]]
   ;; a one-field verdict, last-wins: the absorb cascade repoints
   ;; products it never rendered a form for, so the fence would lie.
   ;; From suggested it lands tracked — correcting a match IS
   ;; confirming it.
   :edit {:prefill [:ingredient_id] :fence false
          :unfenced-reason "A rematch is a one-field verdict, last write wins — the absorb cascade repoints products without a rendered form."}
   :safety {:idempotent true :reversible false :confirm false
            :one-way "The match verdict stands until the next rematch."}
   :handler apply-rematch
   :display {:label "Rematch ingredient" :order 3}})

(defaction record-sighting-action
  {:from #{:tracked} :to :tracked
   :input sighting-schema
   :safety overwrite   ; upsert keyed on seen_on — replay-safe by shape
   :handler record-sighting
   :display {:label "Record price" :style :primary :order 2
             :description "One package's price on one day — a same-day re-record replaces"}})

(defaction remove-sighting-action
  {:from #{:tracked} :to :tracked
   :input [:map [:seen_on :waymark/date]]
   :place :sightings
   :guards [sighting-on-record]
   :safety overwrite
   :handler remove-sighting
   :display {:label "Remove sighting" :order 8}})

(defaction update-details
  {:from #{:tracked} :to :tracked
   :input [:map
           [:name {:optional true} [:maybe [:string {:min 1 :max 200}]]]
           [:package_grams {:optional true} [:maybe [:int {:min 1}]]]
           [:package_count {:optional true} [:maybe [:int {:min 1}]]]
           [:upc {:optional true} [:maybe [:string {:max 20}]]]
           [:url {:optional true :x-display {:raw true}}
            [:maybe [:string {:max 280}]]]]
   :edit {:prefill [:name :package_grams :package_count :upc :url]}
   :safety overwrite
   :handler apply-product-details
   :display {:label "Update details" :order 4}})

(defn- product-on-create [row _ctx]
  (update row :data reprice-product))

(defresource product
  {:kind :product
   :states [:suggested :tracked :discontinued]
   :initial :suggested
   :terminal #{:discontinued}
   :summary "{data.name} · {data.store} · {state}"
   :nav :secondary
   :schema [:map
            [:ingredient_id {:kind :ingredient :label :ingredient_name
                             :filter #{:eq} :pick {:state "active"}}
             :waymark/ref]
            [:ingredient_name {:optional true} [:maybe [:string {:max 200}]]]
            [:store {:filter #{:eq :in}} [:string {:min 1 :max 50}]]
            [:name {:sort :default} [:string {:min 1 :max 200}]]
            [:package_grams {:optional true} [:maybe [:int {:min 1}]]]
            [:package_count {:optional true} [:maybe [:int {:min 1}]]]
            [:upc {:optional true :filter #{:eq}} [:maybe [:string {:max 20}]]]
            [:url {:optional true :x-display {:raw true}}
             [:maybe [:string {:max 280}]]]
            [:sightings {:part-scope {:key :seen_on}}
             [:vector sighting-schema]]
            ;; the three handler-maintained price facts (reprice-product
            ;; is their one writer — the fn= boundary, recorded below)
            [:last_seen_on {:optional true :filter #{:eq :range} :sort true}
             [:maybe :waymark/date]]
            [:latest_price_cents {:optional true
                                  :x-display {:widget "money"
                                              :label "Latest price"}}
             [:maybe [:int {:min 1}]]]
            [:cents_per_100g {:optional true :filter #{:eq :range} :sort true
                              :x-display {:label "¢ / 100 g"}}
             [:maybe [:int {:min 0}]]]
            [:price_is_stale {:optional true :derived price-is-stale
                              :filter #{:eq}}
             [:maybe :boolean]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   ;; the author's create door: the birthing observation is at most one
   ;; sighting, and the price facts / engine label are not the client's
   ;; to write
   :create-schema [:map
                   [:ingredient_id {:kind :ingredient :pick {:state "active"}}
                    :waymark/ref]
                   [:store [:string {:min 1 :max 50}]]
                   [:name [:string {:min 1 :max 200}]]
                   [:package_grams {:optional true} [:maybe [:int {:min 1}]]]
                   [:package_count {:optional true} [:maybe [:int {:min 1}]]]
                   [:upc {:optional true} [:maybe [:string {:max 20}]]]
                   [:url {:optional true :x-display {:raw true}}
                    [:maybe [:string {:max 280}]]]
                   [:sightings {:optional true :default []}
                    [:maybe [:vector {:max 1} sighting-schema]]]
                   [:notes {:optional true :x-display {:widget "prose"}}
                    [:maybe [:string {:max 2000}]]]]
   :on-create product-on-create
   :filterable {:state #{:eq :in}}
   :display {:title "{data.name}"}
   :deviations
   ["last_seen_on / latest_price_cents / cents_per_100g are handler-maintained plain fields, not derived laws — argmax-over-parts is beyond the expression grammar, the boundary v9 drew with its fn= lambdas; reprice-product is their one writer."
    "cents_per_100g rounds half-up in exact integer arithmetic; v9's Python round() was banker's — they differ only at an exact half-cent."]
   :links [{:rel :ingredient :kind :ingredient
            :href "/api/ingredients/{data.ingredient_id}"
            :summary "The pantry concept this product is one store's cut of"}]
   :flow
   [[:suggested :confirm_match :tracked
     {:one-way "The match verdict stands until a rematch says otherwise."
      :display {:label "Confirm match" :style :primary :order 1}}]
    [:suggested :dismiss :discontinued
     {:one-way "Not a real product — dismissing is cheap, the AI can suggest it again."
      :display {:label "Not a real product" :order 2}}]
    [:tracked :discontinue :discontinued
     {:confirm "The product leaves the store-trip math; its price history stays readable."
      :display {:label "Discontinue" :style :danger :order 9}}]]
   :actions
   {:rematch rematch
    :record_sighting record-sighting-action
    :remove_sighting remove-sighting-action
    :update_details update-details}})
