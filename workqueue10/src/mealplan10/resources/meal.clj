(ns mealplan10.resources.meal
  "The Meal resource: the meal library, fed entirely by AI suggestions.

  The AI (via the agent client / MCP tool surface) creates meals in
  suggested with a full recipe attached — recipes are never written by
  hand. Humans review suggestions in the generic UI and accept the
  keepers onto the meal list, which is what a plan day can be assigned
  from.

  update_recipe is one :edit declaration — prefill, the If-Match
  fence, and the shared live draft are a single concept instead of
  four flags.

  A meal is tagged with every theme night it can serve (:themes, a
  list — fajitas are mexican AND american); update_themes retags, and
  the themes filter is membership, not equality (the vocab declaration
  carries its own filter and observed facet — no :filterable/:faceted
  entries for it below). Rows from the single-theme era are a declared
  shape: :shape 2 with an upcast folding :theme into a one-tag
  :themes (design §8) — v10's load boundary runs it lazily and stamps
  the shape at the next write.

  The money layer (pantry-prices era): a meal's recipe lines are
  meal_line rows (promoted — the cost question crosses kinds), and
  the meal knows what it POTENTIALLY costs: est_cost_cents /
  priced_ingredients / total_ingredients are engine-maintained
  sums/counts over the owns edge, flipped in the same commit as any
  line write. \"Cheapest bbq night\" is ?themes=bbq&sort=est_cost_cents.
  reprice fans out to every on_recipe line through the owns cascade —
  advertised as :touches, honestly non-idempotent (the outcome
  depends on the price world outside the row).

  update_details is the human fix-it door beside the AI's
  update_recipe: name, servings, and the prep/thaw clocks were frozen
  at create before it existed. prep_minutes and thaw_hours ride BOTH
  editors deliberately — update_recipe carries them because the AI
  states them with the recipe; update_details carries them because a
  phantom thaw is a human's fix, not a re-authoring. The door can
  CLEAR: the input boundary keeps an explicit null distinct from an
  absent key (decode preserves nulls; apply-defaults fills only
  ABSENT keys), so apply-details reads (contains? inp k) — explicit
  null blanks a field, an absent key leaves it, and zeroing a
  phantom thaw is just writing 0. name is optional but never null —
  a meal keeps its name.

  Catalog health: total_ingredients, priced_ingredients, and the
  composed has_recipe are promoted filters — ?total_ingredients=0 /
  has_recipe=false find the empty shells among ~130 meals that
  otherwise look identical to cheap ones. Promotion mints f_*
  generated columns; the migrate planner adds them additively
  (data-safe — Postgres backfills a generated column from the
  document it derives from). And unpriced ≠ free, by law since
  waymark-vpv (2026-07-29, retiring the recorded deviation): the
  :sum grammar grew {:when-empty :absent}, so a meal with no priced
  information reports est_cost_cents nil — unknown, sorting after
  every real price (ASC nulls-last) — and only a genuinely-zero sum
  reads as $0; priced_ingredients=0 beside has_recipe still names
  the unpriced shelf.

  Recorded deviations ride the declaration itself (:deviations, DX
  phase 5) — fingerprint-carried, rendered by waymark10.dev/explain."
  (:require [waymark10.dsl :refer [defaction defderived defresource
                                   defhandler]]))

(defn fold-theme
  "shape 1 → 2: the single-theme era's :theme becomes a one-tag
  :themes list. Idempotent — an already-folded document passes
  through (the maintainer may re-run an upcast)."
  [data]
  (let [theme (:theme data)
        data (dissoc data :theme)]
    (if (and theme (empty? (:themes data)))
      (assoc data :themes [theme])
      data)))

(defhandler apply-recipe [row inp _ctx]
  (cond-> (assoc-in row [:data :recipe] (:recipe inp))
    (some? (:prep_minutes inp))
    (assoc-in [:data :prep_minutes] (:prep_minutes inp))
    (some? (:thaw_hours inp))
    (assoc-in [:data :thaw_hours] (:thaw_hours inp))
    (some? (:leftover_days inp))
    (assoc-in [:data :leftover_days] (:leftover_days inp))))

(defhandler apply-themes [row inp _ctx]
  (assoc-in row [:data :themes] (vec (distinct (:themes inp)))))

(defhandler apply-details [row inp _ctx]
  ;; explicit null CLEARS, an absent key leaves — the decode boundary
  ;; keeps the difference, so (contains? inp k) is the honest
  ;; conditional; name never clears (the schema holds it required)
  (cond-> row
    (some? (:name inp))
    (assoc-in [:data :name] (:name inp))
    (contains? inp :servings)
    (assoc-in [:data :servings] (:servings inp))
    (contains? inp :prep_minutes)
    (assoc-in [:data :prep_minutes] (:prep_minutes inp))
    (contains? inp :thaw_hours)
    (assoc-in [:data :thaw_hours] (:thaw_hours inp))))

(def theme-schema
  [:vector {:min 1 :max 10} [:waymark/vocab {:open true}]])

;; a named safety value: an idempotent in-place overwrite — nothing to
;; confirm, and "reverse" is just writing the field again
(def overwrite
  {:idempotent true :reversible false :confirm false})

;; ── the cost rollups (pantry-prices era) ────────────────────────────

(defderived meal-est-cost
  ;; :when-empty :absent — an unpriced meal is unknown, never free:
  ;; zero on_recipe lines, or lines none of which carry an estimate,
  ;; land nil (waymark-vpv; the boot's promote backfill restamped the
  ;; old 0s)
  {:sum {:owns :meal_line :where {:state #{"on_recipe"}}
         :of :est_cost_cents :when-empty :absent}})

(defderived priced-ingredients
  {:count {:owns :meal_line :where {:state #{"on_recipe"}
                                    :priced #{true}}}})

(defderived total-ingredients
  {:count {:owns :meal_line :where {:state #{"on_recipe"}}}})

(defderived has-recipe
  ;; composed over the count fact (plan's has_conflicts shape): the
  ;; actionable catalog facet — ?has_recipe=false is the empty shell
  {:over [:total_ingredients]
   :expr '(< 0 (var :total_ingredients))})

;; ── the on-list editors, def'd ──────────────────────────────────────

(defaction update-recipe
  {:from #{:on_list} :to :on_list
   :input [:map
           [:recipe {:x-display {:label "Recipe"
                                 :widget "prose"}}
            [:string {:min 1 :max 8000}]]
           [:prep_minutes {:optional true}
            [:maybe [:int {:min 0}]]]
           [:thaw_hours {:optional true}
            [:maybe [:int {:min 0}]]]
           [:leftover_days {:optional true}
            [:maybe [:int {:min 0}]]]]
   ;; one edit concept: prefilled (editing is not re-authoring), fenced
   ;; (a prefilled form is a snapshot), drafted shared + live (the
   ;; whole family can polish a recipe together)
   :edit {:prefill [:recipe :prep_minutes :thaw_hours :leftover_days]
          :draft {:shared true :live true}}
   :safety overwrite
   :handler apply-recipe
   :display {:label "Update recipe" :order 2}})

(defaction update-themes
  {:from #{:on_list} :to :on_list
   :input [:map [:themes theme-schema]]
   :edit {:prefill [:themes]}
   :safety overwrite
   :handler apply-themes
   :display {:label "Update themes" :order 3
             :description "Retag the meal with every theme night it can serve"}})

(defaction update-details
  {:from #{:on_list} :to :on_list
   :input [:map
           [:name {:optional true} [:string {:min 1 :max 200}]]
           [:servings {:optional true} [:maybe [:int {:min 1}]]]
           [:prep_minutes {:optional true} [:maybe [:int {:min 0}]]]
           [:thaw_hours {:optional true} [:maybe [:int {:min 0}]]]]
   :edit {:prefill [:name :servings :prep_minutes :thaw_hours]}
   :safety overwrite
   :handler apply-details
   :display {:label "Update details" :order 5
             :description "Rename the meal or fix its servings and prep/thaw clocks"}})

(defresource meal
  {:kind :meal
   :states [:suggested :on_list :retired]
   :initial :suggested
   :terminal #{:retired}
   :shape 2
   :upcasts {1 fold-theme}
   :summary "{data.name} · {state}"
   ;; the nav carries the DECISIONS (plan, grocery list); everything
   ;; else lives behind the ⋯ menu — v9's final cut, carried
   :nav :secondary
   :schema [:map
            [:name {:sort :default} [:string {:min 1 :max 200}]]
            ;; one declaration (design §6): membership filtering and
            ;; observed-value facets derive from the vocab itself
            [:themes theme-schema]
            [:recipe {:optional true
                      :x-display {:label "Recipe" :widget "prose"}}
             [:maybe [:string {:min 1 :max 8000}]]]
            [:prep_minutes {:optional true} [:maybe [:int {:min 0}]]]
            [:thaw_hours {:optional true} [:maybe [:int {:min 0}]]]
            [:leftover_days {:optional true} [:maybe [:int {:min 0}]]]
            [:servings {:optional true} [:maybe [:int {:min 1}]]]
            [:est_cost_cents {:optional true :derived meal-est-cost
                              :sort true
                              :x-display {:widget "money"
                                          :label "Est. cost"}}
             [:maybe :int]]
            [:priced_ingredients {:optional true :derived priced-ingredients
                                  :filter #{:eq :range}}
             [:maybe :int]]
            [:total_ingredients {:optional true :derived total-ingredients
                                 :filter #{:eq :range}}
             [:maybe :int]]
            [:has_recipe {:optional true :derived has-recipe
                          :filter #{:eq}}
             [:maybe :boolean]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :filterable {:state #{:eq :in}}
   ;; the review feed: AI suggestions read one full screen at a time —
   ;; the same rows ?state=suggested serves, in the same order, with
   ;; each meal's own accept/decline buttons riding its envelope. A
   ;; :feed is presentation, never law (waymark-h50).
   :views [{:name :review :kind :feed :where {:state "suggested"}
            :card [:themes :servings :prep_minutes :est_cost_cents]
            :display {:label "Review feed"}}]
   :display {:title "{data.name}"}
   ;; the recipe lines: owned rows, the reprice cascade, the embedded
   ;; on_recipe view with the honest count badge
   :owns [{:kind :meal_line :via :meal_id :on {:reprice :reprice}}]
   :links [{:rel :ingredients :owns :meal_line :embed true
            :badge :total_ingredients
            :where {:state "on_recipe"}
            :summary "The recipe's ingredient lines and what they cost"}]
   :deviations
   ["accept_many stays a declared action — bulk has no flow-row spelling."
    "The on-list editors stay def'd actions — :fields would mint one all-optional writer, but apply-recipe writes conditionally and apply-themes dedupes: a different law under different names."
    "No :undo pointers — nothing here is declared reversible, and nothing walks retired back."
    "v10 summary templates carry no |join filter — the summary names the meal and its state only."
    "prep_minutes and thaw_hours carry no field defaults — the AI writes them with the recipe."
    "leftover_days is declared but unconsumed — the cooked-leftover clock waits for leftover-night planning."]
   ;; the lifecycle doors as flow rows, each wearing its safety story;
   ;; :states stays spelled because the rows are not the whole machine
   ;; (the bulk accept and the editors live in :actions below)
   :flow
   [[:suggested :accept  :on_list
     {:one-way "Joining the meal list is low-stakes; Retire takes a meal off it again."
      :display {:label "Add to meal list" :style :primary :order 1}}]
    [:suggested :decline :retired
     {:one-way "Declining a suggestion is cheap — the AI can suggest it again any time."
      :display {:label "No thanks" :order 2}}]
    [:on_list   :retire  :retired
     {:confirm "The meal leaves the family list and can no longer be assigned to plan days."
      :display {:label "Retire" :style :danger :order 9}}]]
   :actions
   {:accept_many {:from #{:suggested} :to :on_list
                  :bulk {:max-items 200 :defer-over 50}
                  :safety {:idempotent true :reversible false :confirm true
                           :consequence "Every selected suggestion joins the family meal list."}
                  :display {:label "Add selected to meal list" :style :primary}}
    :update_recipe update-recipe
    :update_themes update-themes
    :update_details update-details
    :reprice {:from #{:on_list} :to :on_list
              ;; the cascade does the fan-out; no handler. Honestly
              ;; non-idempotent: the price world moves outside this row
              :touches [{:kind :meal_line :action :reprice :may true}]
              :safety {:idempotent false :reversible false :confirm false}
              :display {:label "Reprice" :order 4
                        :description "Refresh every line's estimate from today's price world"}}}})
