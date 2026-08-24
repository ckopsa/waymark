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
  write and no poll. needs_weight is the other queue — priced but
  weightless (latest_price_cents set, package_grams nil), so
  ?needs_weight=true sweeps the products whose spend can't pro-rate
  until a human records the package weight."
  (:require [waymark10.dsl :refer [defaction defderived defresource
                                   defhandler guard]])
  (:import (java.time LocalDate ZoneOffset)))

(def overwrite
  {:idempotent true :reversible false :confirm false})

;; the household prose, factored: the create door and update_details
;; ask for the very same package facts, and one door is not a place
;; the other may drift from (saved_view's move)
(def ingredient-ref-display
  {:label "Pantry ingredient"
   :help "Which pantry concept this package is one store's version of — \"chicken thighs\", not the words on the bag."})

(def product-name-display
  {:label "Product name"
   :help "The package the way the store writes it, size and all — \"Kirkland Organic Chicken Thighs, 2.72 kg\"."})

(def package-grams-display
  {:label "Package weight (g)"
   :help "What the whole package weighs as you carry it out of the store — it's what turns a price into ¢ per 100 g."})

(def yield-display
  {:label "Usable share (%)"
   :help "How much of that weight actually reaches the plate after bones, peel and trim — a rotisserie chicken picks about 51; blank means all of it, and over 100 is for things that grow (dry rice, about 300)."})

(def package-count-display
  {:label "Pieces per package"
   :help "How many separate items are inside — 12 for a dozen eggs; leave it blank when the package is just one thing."})

(def upc-display
  {:label "Barcode number"
   :help "The digits under the barcode — it's how a receipt line finds this exact package again."})

(def url-display
  {:raw true
   :label "Store page"
   :help "Link to this package on the store's own site, if it has one — the scraper reads a price off it when the page will give one up."})

(def sighting-schema
  [:map
   [:seen_on {:x-display {:label "Seen on"
                          :help "The day this price was true — recording the same day twice replaces, so a corrected receipt never doubles up."}}
    :waymark/date]
   [:price_cents {:x-display {:widget "money" :label "Price (one package)"
                              :help "What ONE package cost — never the receipt line's total for several."}}
    [:int {:min 1}]]
   [:source {:x-display {:label "Where the price came from"
                         :choices {"receipt" "A receipt — we actually bought it"
                                   "scrape" "A store page — a price we looked up"}}}
    [:enum "receipt" "scrape"]]
   [:ref {:optional true
          :x-display {:raw true
                      :label "Receipt or link"
                      :help "Where to find this price again — the receipt it came off, or the store page address the scrape read."}}
    [:maybe [:string {:max 280}]]]
   [:quantity {:optional true :default 1
               :x-display {:label "Packages bought"
                           :help "How many of this package the receipt covers — spend context only; the price above stays one package's."}}
    [:maybe [:int {:min 1}]]]
   [:on_sale {:optional true :default false
              :x-display {:label "On sale"
                          :help "Tick when this was a sale price, so a low number doesn't read as the everyday one."}}
    [:maybe :boolean]]])

(def sightings-display
  {:label "Prices seen"
   :help "Every price we've seen this package at, one entry per day — at creation that's just the receipt in your hand; later ones come in through Record price."})

(def notes-display
  {:widget "prose"
   :label "Notes"
   :help "Anything worth remembering about this particular package — where it sits in the store, how the size compares, whether the sale price is the real one."})

(defn reprice-product
  "The price facts from the sightings, restated: sightings sorted by
  seen_on (the upsert key), last_seen_on/latest_price_cents from the
  newest, cents_per_100g = latest × 100 ÷ USABLE grams rounded
  half-up — nil when nothing is unit-priceable (no sightings, or a
  counted good with no package_grams).

  Usable grams = package_grams × yield_percent ÷ 100 (nil yield =
  100: everything you carry out is plate), so the unit price reads ¢
  per 100 PLATE grams — a $4.99 rotisserie is a 1361 g bird but only
  ~51% picks as meat, and the plate pays for the carcass. Usable
  grams are never rounded on their own: the ÷ 100 folds into one
  integer division, so rounding happens ONCE, half-up, at the final
  cents — cents_per_100g = round(price × 100 × 100 ÷ (package_grams
  × yield)). With yield 100 the formula reduces exactly to the old
  price × 100 ÷ package_grams (same fractional part, same half-up
  verdict), so yieldless products price bit-identically to before.

  Verified downstream (why this is the ONE change): recipe grams are
  plate grams (the house rule — grams of what goes in the dish), and
  every consumer prices through cents_per_100g alone —
  meal-line/best-unit-price reads it off best-product's winner,
  price-line multiplies it by plate grams, and grocery-list's
  compile_from_plan does the same for its direct-priced groups — so
  meal_line pricing, substitution pricing, and the grocery compile
  all inherit plate-cost correctness with zero downstream change."
  [data]
  (let [ss (vec (sort-by :seen_on (:sightings data)))
        latest (peek ss)
        grams (:package_grams data)
        yield (long (or (:yield_percent data) 100))
        price (:price_cents latest)]
    (assoc data
           :sightings ss
           :last_seen_on (:seen_on latest)
           :latest_price_cents price
           :cents_per_100g (when (and price grams (pos? (long grams)))
                             (let [denom (* (long grams) yield)]
                               (quot (+ (* (long price) 10000) (quot denom 2))
                                     denom))))))

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

;; the priced-but-weightless gap: a receipt line prices a product the
;; family never weighed, so cents_per_100g stays blank and the spend
;; can't pro-rate. The filter is the queue, not a wall — package_grams
;; STAYS nullable and record_sighting STAYS unblocked, because
;; receipts don't carry weights; ?needs_weight=true is the sweep and
;; update_details is the fix
(defderived needs-weight
  {:over [:latest_price_cents :package_grams]
   :expr '(and (is-set (var :latest_price_cents))
               (not (is-set (var :package_grams))))
   :explain "Priced but weightless — record the package weight and the unit math unlocks."})

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
            (reprice-product   ; package_grams/yield_percent move cents_per_100g
             (merge data (select-keys inp [:name :package_grams :yield_percent
                                           :package_count :upc :url]))))))

(defaction rematch
  {:from #{:suggested :tracked} :to :tracked
   :input [:map [:ingredient_id {:kind :ingredient :pick {:state "active"}
                                 :x-display ingredient-ref-display}
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
           [:name {:optional true :x-display product-name-display}
            [:maybe [:string {:min 1 :max 200}]]]
           [:package_grams {:optional true :x-display package-grams-display}
            [:maybe [:int {:min 1}]]]
           [:yield_percent {:optional true :x-display yield-display}
            [:maybe [:int {:min 1 :max 10000}]]]
           [:package_count {:optional true :x-display package-count-display}
            [:maybe [:int {:min 1}]]]
           [:upc {:optional true :x-display upc-display}
            [:maybe [:string {:max 20}]]]
           [:url {:optional true :x-display url-display}
            [:maybe [:string {:max 280}]]]]
   :edit {:prefill [:name :package_grams :yield_percent :package_count
                    :upc :url]}
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
                             :filter #{:eq} :pick {:state "active"}
                             :x-display ingredient-ref-display}
             :waymark/ref]
            [:ingredient_name {:optional true} [:maybe [:string {:max 200}]]]
            [:store {:filter #{:eq :in}
                     :x-display {:label "Store"
                                 :help "Where this exact package is sold — one store per row; the same item at WinCo is its own product."}}
             [:string {:min 1 :max 50}]]
            [:name {:sort :default :x-display product-name-display}
             [:string {:min 1 :max 200}]]
            [:package_grams {:optional true :x-display package-grams-display}
             [:maybe [:int {:min 1}]]]
            ;; what survives to the plate, per 100 g carried out of
            ;; the store: nil = 100 — everything you carry out is
            ;; plate. Below 100 = trim (a whole chicken picks ~51%
            ;; meat); ABOVE 100 = concentrates (dry rice ~300: 300 g
            ;; cooked per 100 g dry; bouillon ~4000). package_grams
            ;; stays the honest carry-out weight; cents_per_100g
            ;; divides by usable grams, so it reads ¢ per 100 PLATE
            ;; grams — recipe grams are plate grams, so every price
            ;; consumer inherits the correction
            [:yield_percent {:optional true :x-display yield-display}
             [:maybe [:int {:min 1 :max 10000}]]]
            [:package_count {:optional true :x-display package-count-display}
             [:maybe [:int {:min 1}]]]
            [:upc {:optional true :filter #{:eq} :x-display upc-display}
             [:maybe [:string {:max 20}]]]
            [:url {:optional true :x-display url-display}
             [:maybe [:string {:max 280}]]]
            [:sightings {:part-scope {:key :seen_on}
                         :x-display sightings-display}
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
            [:needs_weight {:optional true :derived needs-weight
                            :filter #{:eq}}
             [:maybe :boolean]]
            [:notes {:optional true
                     :examples ["The 2.72 kg bag is two dinners; the small one is never worth it."]
                     :x-display notes-display}
             [:maybe [:string {:max 2000}]]]]
   ;; the author's create door: the birthing observation is at most one
   ;; sighting, and the price facts / engine label are not the client's
   ;; to write
   :create-schema [:map
                   [:ingredient_id {:kind :ingredient :pick {:state "active"}
                                    :x-display ingredient-ref-display}
                    :waymark/ref]
                   [:store {:x-display {:label "Store"
                                        :help "Where this exact package is sold — one store per row; the same item at WinCo is its own product."}}
                    [:string {:min 1 :max 50}]]
                   [:name {:x-display product-name-display}
                    [:string {:min 1 :max 200}]]
                   [:package_grams {:optional true
                                    :x-display package-grams-display}
                    [:maybe [:int {:min 1}]]]
                   ;; nil = 100 (all plate); >100 = concentrates —
                   ;; the schema comment above carries the full story
                   [:yield_percent {:optional true :x-display yield-display}
                    [:maybe [:int {:min 1 :max 10000}]]]
                   [:package_count {:optional true
                                    :x-display package-count-display}
                    [:maybe [:int {:min 1}]]]
                   [:upc {:optional true :x-display upc-display}
                    [:maybe [:string {:max 20}]]]
                   [:url {:optional true :x-display url-display}
                    [:maybe [:string {:max 280}]]]
                   [:sightings {:optional true :default []
                                :x-display sightings-display}
                    [:maybe [:vector {:max 1} sighting-schema]]]
                   [:notes {:optional true
                            :examples ["The 2.72 kg bag is two dinners; the small one is never worth it."]
                            :x-display notes-display}
                    [:maybe [:string {:max 2000}]]]]
   :on-create product-on-create
   :filterable {:state #{:eq :in}}
   :display {:title "{data.name}"}
   :deviations
   ["last_seen_on / latest_price_cents / cents_per_100g are handler-maintained plain fields, not derived laws — argmax-over-parts is beyond the expression grammar, the boundary v9 drew with its fn= lambdas; reprice-product is their one writer."
    "yield_percent sits on the recorded-fact side of that fn= boundary: a client-stated input like package_grams, folded into cents_per_100g only by reprice-product — needs_weight still judges package_grams alone, because the carry-out weight stays the recorded fact."
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

;; ── the price desk (waymark-34n): the queues' one screen ────────────

(def price-desk
  "The price-work, composed where a human can act on it. The scraper
  run settled who the stale-price queue really serves: 39 of 40
  retailer pages render their price in JS and don't parse statically
  (236 of the 286 stale products don't even carry a url), so the
  queue's real consumer is a human with a receipt — the scraper stays
  as garnish. An ANCHORLESS surface, because staleness is the
  product's own clock, not any plan's or week's: two collection
  members over tracked products — :stale (price_is_stale, the 30-day
  clock ran out) and :weightless (needs_weight, priced but no
  package_grams, so spend can't pro-rate) — with record_sighting and
  update_details showcased: the receipt in one hand, the fix doors in
  the other. Served read-only at /api/surfaces/price-desk; each panel
  carries its truthful count, because the queue outruns the 200-row
  items page. Losing spellings, recorded: a plan-anchored board (the
  queue has no anchor row), and attention-on-counts (attention
  nominates anchor data fields; anchorless has none — the counts
  themselves are the flag)."
  {:name :price-desk
   :members [{:name :stale :kind :product
              :where {:state #{"tracked"} :price_is_stale #{"true"}}}
             {:name :weightless :kind :product
              :where {:state #{"tracked"} :needs_weight #{"true"}}}]
   :showcase [:record_sighting :update_details]})
