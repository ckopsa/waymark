(ns mealplan10.resources.ingredient
  "The Ingredient resource: the canonical pantry concept (\"chicken
  thighs\"), store-agnostic — how stores sell it is the product's
  story, what stands in for it is the substitution's.

  The AI (reading receipts and recipes) suggests ingredients; a human
  verdict accepts or declines each, which is what keeps the list
  canonical. Aliases fold in every name it goes by on receipts and
  recipes — a confirmed match teaches the spelling. preferred_stores
  is the family's buying preference, best first; its membership
  filter (?preferred_stores=costco) is the whole-Costco-trip entry
  point, and the pricing law reads its ORDER when several stores
  offer the same ingredient.

  absorb is the dedupe verdict — same concept, different spelling
  (the substitution is the other cut: different concept, acceptable
  stand-in). The survivor takes the duplicate's name and aliases,
  repoints its live products through product.rematch (the handler's
  ctx :invoke door, advertised as :touches), and retires it —
  acknowledging the tracked-products warning, honestly: the stored
  rollup is stale at handler time because its products were just
  repointed in this very transaction.

  The stock half (spec-pantry era 2): three fields and one law.
  shelf_life_days is the human's rough clock — nil means
  shelf-stable, the absence of a clock, not a big number. stocked_on
  is the last date the family knew it on hand; out is the human
  override, beating the clock both ways. The stocked fact falls into
  two regimes: perishables (shelf_life_days set) decay on the clock
  and the purchase stamp resets them — grocery_list.complete invokes
  restock for every checked item carrying this ingredient's ref, no
  human in the loop; staples (nil) are sticky — restocked once ever
  (seeding: one pass over the list, \"which of these do you just
  have?\"), unmarked only by mark_out, because for salt the real
  signal isn't time, it's a human noticing the jar is empty. The two
  verdicts carry no :undo pair — restock stamps a date, so they are
  not exact inverses — and restock is honestly non-idempotent (the
  reprice posture): today lives outside the row, so natural replay
  must never swallow a later restock. restock_many is the seeding
  door — the recorded punt, promoted once it hurt: the same stamp
  fanned over a selection (accept_many's bulk shape), and just as
  honestly non-idempotent, so the whole call demands one
  Idempotency-Key. mark_out is the idempotent
  overwrite it looks like. stocked_on filters on :range as well as
  :eq, so ?stocked_on_lte=<date>&stocked=true is the staple
  re-confirmation queue — \"still have the paprika?\" after N quiet
  months, with restock already the one-tap answer — a query over the
  existing stamp, deliberately not a new clock fact or action. Era 4 adds the second clock:
  opened_shelf_life_days is how long the ingredient keeps once
  opened — the anchor/flex solver's input, never a law here."
  (:require [waymark10.dsl :refer [defaction defderived defguardfn
                                   defresource defhandler expr-guard]]
            [waymark10.types :as t])
  (:import (java.time Instant LocalDate ZoneOffset)))

(def alias-schema
  [:vector {:max 30} [:waymark/vocab {:open true}]])

(def store-schema
  [:vector {:max 10} [:waymark/vocab {:open true}]])

(def overwrite
  {:idempotent true :reversible false :confirm false})

(defderived products-tracked
  {:count {:owns :product :where {:state #{"tracked"}}}})

;; the pantry clock (spec-pantry era 2): the price_is_stale pattern
;; pointed at the pantry. Shelf-stable (nil shelf_life_days) short-
;; circuits the clock clause, so a staple stays stocked until a human
;; says otherwise; :flips-at hands the maintainer the exact expiry
;; instant, so perishables flip with no write and no poll — and a
;; staple (or an out row) hands it nothing, honestly
(defderived stocked
  {:over [:out :stocked_on :shelf_life_days :now]
   :expr '(and (not (var :out))
               (is-set (var :stocked_on))
               (or (not (is-set (var :shelf_life_days)))
                   (< (date-of (var :now))
                      (+ (var :stocked_on) (days (var :shelf_life_days))))))
   :flips-at (fn [row]
               (when-some [life (get-in row [:data :shelf_life_days])]
                 (when-not (true? (get-in row [:data :out]))
                   (some-> ^LocalDate (get-in row [:data :stocked_on])
                           (.plusDays (long life))
                           (.atStartOfDay ZoneOffset/UTC)
                           .toInstant))))
   :explain "Last stocked {on} — a completed shop or a restock refreshes it."
   :vars {:on '(var :stocked_on)}})

;; retire warns (acknowledgeable), never walls: the family may retire
;; an ingredient whose products are history they no longer track
(def no-tracked-products
  (expr-guard {:name :no-tracked-products
               :severity :warning
               :when '(= 0 (data :products_tracked))
               :explain "{n} tracked product(s) still point here — absorb the duplicate or rematch them first."
               :vars {:n '(data :products_tracked)}}))

(defguardfn duplicate-is-absorbable
  {:judges [:duplicate_id]
   :reads [:ingredient]
   :explain "Only another ACTIVE ingredient can be absorbed — and never this one itself."
   :remedies [:ingredient/accept]}
  [row inp ctx]
  (if-some [read (:read ctx)]
    (let [dup-id (:duplicate_id inp)]
      (cond
        (= (str dup-id) (str (:id row))) (t/deny)
        (not= :active (:state (read :ingredient dup-id))) (t/deny)
        :else (t/allow)))
    ;; pure render probe carries no hooks — optimistic, the invoke
    ;; loop re-judges with them
    (t/allow)))

(defhandler apply-aliases [row inp _ctx]
  (assoc-in row [:data :aliases] (vec (distinct (:aliases inp)))))

(defhandler apply-details [row inp _ctx]
  (cond-> row
    (some? (:category inp))
    (assoc-in [:data :category] (:category inp))
    (some? (:unit inp))
    (assoc-in [:data :unit] (:unit inp))
    (some? (:preferred_stores inp))
    (assoc-in [:data :preferred_stores] (vec (distinct (:preferred_stores inp))))
    (some? (:shelf_life_days inp))
    (assoc-in [:data :shelf_life_days] (:shelf_life_days inp))
    (some? (:opened_shelf_life_days inp))
    (assoc-in [:data :opened_shelf_life_days] (:opened_shelf_life_days inp))))

;; the two stock verdicts: today's date from the engine's clock, the
;; way plan-on-create reads it — the handler needs no client input
(defhandler apply-restock [row _inp ctx]
  (update row :data assoc
          :stocked_on (.toLocalDate (.atOffset ^Instant (:now ctx)
                                               ZoneOffset/UTC))
          :out false))

(defhandler apply-mark-out [row _inp _ctx]
  (assoc-in row [:data :out] true))

(defhandler absorb-duplicate [row inp ctx]
  (let [invoke! (:invoke ctx)
        dup-id (:duplicate_id inp)
        dup ((:read ctx) :ingredient dup-id)
        known (into #{(get-in row [:data :name])}
                    (get-in row [:data :aliases]))
        incoming (cons (get-in dup [:data :name])
                       (get-in dup [:data :aliases]))]
    ;; repoint the duplicate's live products; rematch re-parents and
    ;; confirms in one verdict, so both rollups tell the truth in this
    ;; same call. Rematched products leave the query, so the re-query
    ;; loop terminates; discontinued ones keep their historical match.
    (loop []
      (let [page ((:find ctx) :product {:ingredient_id dup-id} {:limit 200})
            due (filterv #(contains? #{:suggested :tracked} (:state %)) page)]
        (when (seq due)
          (doseq [p due]
            (invoke! :product (:id p) :rematch {:ingredient_id (:id row)}))
          (when (= 200 (count page))
            (recur)))))
    (invoke! :ingredient dup-id :retire nil
             {:acknowledged #{:no-tracked-products}})
    (update-in row [:data :aliases]
               (fn [aliases]
                 (vec (take 30 (into (vec aliases)
                                     (comp (distinct) (remove known))
                                     incoming)))))))

(defaction update-aliases
  {:from #{:active} :to :active
   :input [:map [:aliases alias-schema]]
   :edit {:prefill [:aliases]}
   :safety overwrite
   :handler apply-aliases
   :display {:label "Update aliases" :order 3
             :description "Every name this ingredient goes by on receipts and recipes"}})

(defaction update-details
  {:from #{:active} :to :active
   :input [:map
           [:category {:optional true} [:maybe [:string {:max 50}]]]
           [:unit {:optional true} [:maybe [:enum "g" "ml" "each"]]]
           [:preferred_stores {:optional true} [:maybe store-schema]]
           [:shelf_life_days {:optional true} [:maybe [:int {:min 1}]]]
           [:opened_shelf_life_days {:optional true}
            [:maybe [:int {:min 1}]]]]
   :edit {:prefill [:category :unit :preferred_stores :shelf_life_days
                    :opened_shelf_life_days]}
   :safety overwrite
   :handler apply-details
   :display {:label "Update details" :order 4}})

;; no :undo pair: restock stamps a date, so the two verdicts are not
;; exact inverses. And restock is honestly NON-idempotent (the
;; compile_from_plan/reprice posture): "today" lives outside the row,
;; and its input digest is empty — natural replay would swallow a
;; fortnight-later restock as a repeat of the last one, and the
;; purchase stamp could never wind a perishable's clock again.
;; mark_out reads no clock; replayed twice it writes the same truth.
(defaction restock
  {:from #{:active} :to :active
   :safety {:idempotent false :reversible false :confirm false}
   :handler apply-restock
   :display {:label "Restock" :order 6
             :description "On hand as of today — a completed shop stamps this automatically"}})

(defaction mark-out
  {:from #{:active} :to :active
   :safety overwrite
   :handler apply-mark-out
   :display {:label "We're out" :order 7
             :description "The human override — beats the clock both ways until the next restock"}})

(defaction absorb
  {:from #{:active} :to :active
   :input [:map [:duplicate_id {:kind :ingredient :pick {:state "active"}}
                 :waymark/ref]]
   :guards [duplicate-is-absorbable]
   :touches [{:kind :product :action :rematch :may true}
             {:kind :ingredient :action :retire}]
   :safety {:idempotent true :reversible false :confirm true
            :consequence "The duplicate's name and aliases fold into this ingredient, its products repoint here, and it retires."}
   :handler absorb-duplicate
   :display {:label "Absorb duplicate" :order 5}})

(defresource ingredient
  {:kind :ingredient
   :states [:suggested :active :retired]
   :initial :suggested
   :terminal #{:retired}
   :summary "{data.name} · {state}"
   :nav :secondary
   :schema [:map
            [:name {:sort :default} [:string {:min 1 :max 200}]]
            [:aliases {:optional true :default []} [:maybe alias-schema]]
            [:category {:optional true :filter #{:eq :in}}
             [:maybe [:string {:max 50}]]]
            [:unit {:optional true :default "g"}
             [:maybe [:enum "g" "ml" "each"]]]
            [:preferred_stores {:optional true :default []}
             [:maybe store-schema]]
            ;; the stock story (spec-pantry era 2): nil shelf life =
            ;; shelf-stable — the absence of a clock, not a big number;
            ;; stocked_on is restock's stamp, out the human override
            [:shelf_life_days {:optional true} [:maybe [:int {:min 1}]]]
            ;; the opened-residual clock (spec-pantry era 4): how long
            ;; it keeps once opened — nil = opening changes nothing.
            ;; Solver input only; no law here reads it
            [:opened_shelf_life_days {:optional true}
             [:maybe [:int {:min 1}]]]
            ;; promoted (?stocked_on_lte= beside stocked=true) so the
            ;; staple re-confirmation queue is a query, not a new
            ;; clock fact — deliberately the minimal spelling
            [:stocked_on {:optional true :filter #{:eq :range}}
             [:maybe :waymark/date]]
            [:out {:optional true} [:maybe :boolean]]
            [:stocked {:optional true :derived stocked :filter #{:eq}}
             [:maybe :boolean]]
            [:products_tracked {:optional true :derived products-tracked}
             [:maybe :int]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :filterable {:state #{:eq :in}}
   :display {:title "{data.name}"}
   :owns [{:kind :product :via :ingredient_id}
          ;; the substitutions FROM this ingredient — an owns edge so
          ;; the accepted stand-ins embed on this page
          {:kind :substitution :via :from_ingredient_id}]
   :links [{:rel :products :owns :product :embed true
            :badge :products_tracked
            :summary "How stores sell this ingredient"}
           {:rel :substitutions :owns :substitution :embed true
            :where {:state "accepted"}
            :summary "What the family accepts in its place"}]
   :flow
   [[:suggested :accept :active
     {:one-way "Joining the pantry is low-stakes; Retire takes an ingredient out again."
      :display {:label "Accept" :style :primary :order 1}}]
    [:suggested :decline :retired
     {:one-way "Declining a suggestion is cheap — the AI can suggest it again any time."
      :display {:label "No thanks" :order 2}}]]
   :actions
   {:accept_many {:from #{:suggested} :to :active
                  :bulk {:max-items 200 :defer-over 50}
                  :safety {:idempotent true :reversible false :confirm true
                           :consequence "Every selected suggestion joins the pantry."}
                  :display {:label "Accept selected" :style :primary}}
    :update_aliases update-aliases
    :update_details update-details
    :restock restock
    ;; the seeding door: restock, fanned — each selected row runs the
    ;; same apply-restock through the per-item algorithm; max-items
    ;; 500 (the framework caps nothing, defaults 100; the pantry is
    ;; 716 wide), over 50 defers to a job like accept_many
    :restock_many {:from #{:active} :to :active
                   :bulk {:max-items 500 :defer-over 50}
                   :safety {:idempotent false :reversible false :confirm true
                            :consequence "Every selected ingredient is marked on hand as of today."}
                   :handler apply-restock
                   :display {:label "Restock selected"}}
    :mark_out mark-out
    :absorb absorb
    :retire {:from #{:active} :to :retired
             :guards [no-tracked-products]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Retired ingredients stay retired — absorb points a duplicate at its survivor instead."}
             :display {:label "Retire" :style :danger :order 9}}}})
