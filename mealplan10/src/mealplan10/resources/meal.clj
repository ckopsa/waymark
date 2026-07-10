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

  Recorded deviations from mealplan9, each a sentence: v10 summary
  templates carry no |join filter, so the summary names the meal and
  its state only; prep_minutes/thaw_hours carry no field defaults
  (malli entries declare none) — the AI writes them with the recipe."
  (:require [waymark10.resource :as r :refer [defresource defhandler]]))

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

(defhandler update-recipe [row inp _ctx]
  (cond-> (assoc-in row [:data :recipe] (:recipe inp))
    (some? (:prep_minutes inp))
    (assoc-in [:data :prep_minutes] (:prep_minutes inp))
    (some? (:thaw_hours inp))
    (assoc-in [:data :thaw_hours] (:thaw_hours inp))))

(defhandler update-themes [row inp _ctx]
  (assoc-in row [:data :themes] (vec (distinct (:themes inp)))))

(def theme-schema
  [:vector {:min 1 :max 10} [:waymark/vocab {:open true}]])

(defresource meal
  {:kind :meal
   :states [:suggested :on_list :retired]
   :initial :suggested
   :terminal #{:retired}
   :shape 2
   :upcasts {1 fold-theme}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 200}]]
            ;; one declaration (design §6): membership filtering and
            ;; observed-value facets derive from the vocab itself
            [:themes theme-schema]
            [:recipe {:optional true
                      :x-display {:label "Recipe" :widget "prose"}}
             [:maybe [:string {:min 1 :max 8000}]]]
            [:prep_minutes {:optional true} [:maybe [:int {:min 0}]]]
            [:thaw_hours {:optional true} [:maybe [:int {:min 0}]]]
            [:servings {:optional true} [:maybe [:int {:min 1}]]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:name] :default "name"}
   :display {:title "{data.name}"}
   :actions
   {:accept {:from #{:suggested} :to :on_list
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Joining the meal list is low-stakes; Retire takes a meal off it again."}
             :display {:label "Add to meal list" :style :primary :order 1}}
    :accept_many {:from #{:suggested} :to :on_list
                  :bulk {:max-items 200 :defer-over 50}
                  :safety {:idempotent true :reversible false :confirm true
                           :consequence "Every selected suggestion joins the family meal list."}
                  :display {:label "Add selected to meal list" :style :primary}}
    :decline {:from #{:suggested} :to :retired
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Declining a suggestion is cheap — the AI can suggest it again any time."}
              :display {:label "No thanks" :order 2}}
    :update_recipe {:from #{:on_list} :to :on_list
                    :input [:map
                            [:recipe {:x-display {:label "Recipe"
                                                  :widget "prose"}}
                             [:string {:min 1 :max 8000}]]
                            [:prep_minutes {:optional true}
                             [:maybe [:int {:min 0}]]]
                            [:thaw_hours {:optional true}
                             [:maybe [:int {:min 0}]]]]
                    ;; one edit concept: prefilled (editing is not
                    ;; re-authoring), fenced (a prefilled form is a
                    ;; snapshot), drafted shared + live (the whole
                    ;; family can polish a recipe together)
                    :edit {:prefill [:recipe :prep_minutes :thaw_hours]
                           :draft {:shared true :live true}}
                    :safety {:idempotent true :reversible false :confirm false}
                    :handler update-recipe
                    :display {:label "Update recipe" :order 2}}
    :update_themes {:from #{:on_list} :to :on_list
                    :input [:map [:themes theme-schema]]
                    :edit {:prefill [:themes]}
                    :safety {:idempotent true :reversible false :confirm false}
                    :handler update-themes
                    :display {:label "Update themes" :order 3
                              :description "Retag the meal with every theme night it can serve"}}
    :retire {:from #{:on_list} :to :retired
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "The meal leaves the family list and can no longer be assigned to plan days."}
             :display {:label "Retire" :style :danger :order 9}}}})
