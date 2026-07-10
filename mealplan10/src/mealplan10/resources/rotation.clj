(ns mealplan10.resources.rotation
  "The Sunday rotation: the dynamic list of themes Sunday cycles
  through.

  Several rotations can exist (seasonal lists, experiments); activate
  stamps activated_at, and new plans draw from the most recently
  activated active rotation — an action's effect belongs to this
  resource alone, so activating one never mutates its siblings. New
  plans auto-select that rotation and pre-theme their Sundays from it,
  starting at data.position (advance moves it after a theme gets
  used). A plan's set_sunday_theme guard reads this resource, so a
  Sunday theme not in the rotation is refused with a remedy pointing
  at add_theme.

  not_last_theme is a pure acceptance-set declaration — the set of
  removable themes is both the rendered enum and the enforcement;
  there is no separate check body to drift.

  Batch-G style note: this kind declares no field-scoped law (no
  sort, no derived, no part scope; only :state filtering, which is
  not a schema entry), so nothing colocates — the style rewrite here
  is just the two repeated safety values named. The law is unchanged;
  mealplan10.style-invariance-test pins the fingerprint hash.

  Recorded deviation: v10 declares no field defaults, so the name and
  the starter themes land in :on-create when the create body leaves
  them blank."
  (:require [waymark10.guards :as g]
            [waymark10.resource :as r :refer [defresource defhandler]]))

(def default-themes
  ["breakfast for dinner" "indian" "greek" "soup night"])

;; removable = what's on the rotation, unless that would empty it.
;; One declaration: the rendered enum, the enforcement, and the
;; per-part availability all come from this set.
(def not-last-theme
  (g/guard {:name :not-last-theme
            :judges [:theme]
            :accepts (fn [row]
                       (let [themes (get-in row [:data :themes])]
                         (if (< 1 (count themes)) (vec themes) [])))
            :explain "'{theme}' cannot be removed; the rotation must keep at least one theme."}))

(defhandler activate-rotation [row _inp ctx]
  (assoc-in row [:data :activated_at] (:now ctx)))

(defhandler add-theme [row inp _ctx]
  (update-in row [:data :themes]
             (fn [themes]
               (if (some #{(:theme inp)} themes)
                 themes
                 (conj (vec themes) (:theme inp))))))

(defhandler remove-theme [row inp _ctx]
  ;; removing an absent theme is a no-op, so retries stay replay-safe
  (update row :data
          (fn [d]
            (if (some #{(:theme inp)} (:themes d))
              (let [themes (vec (remove #{(:theme inp)} (:themes d)))]
                (assoc d :themes themes
                       :position (mod (:position d) (count themes))))
              d))))

(defhandler advance-rotation [row _inp _ctx]
  (update row :data
          (fn [d]
            (assoc d :position (mod (inc (:position d))
                                    (count (:themes d)))))))

(defn- rotation-on-create
  "Blank create fields land their mealplan9 model defaults here."
  [row _ctx]
  (update row :data
          (fn [d]
            (-> d
                (update :name #(or % "Sunday rotation"))
                (update :themes #(if (seq %) (vec %) default-themes))
                (assoc :position 0)))))

(def theme-input
  [:map [:theme [:string {:min 1 :max 50}]]])

;; named safety values: each spelled once, in full, and cited by name
;; — never inferred
(def routine
  ;; honestly reversible: activate/deactivate are each other's way back
  {:idempotent true :reversible true :confirm false})
(def overwrite
  ;; an idempotent list edit — nothing to confirm, no strict reverse
  {:idempotent true :reversible false :confirm false})

(defresource rotation
  {:kind :rotation
   :states [:inactive :active]
   :initial :inactive
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 100}]]
            [:themes [:vector {:min 1} [:string {:min 1 :max 50}]]]
            [:position [:int {:min 0}]]
            [:activated_at {:optional true} [:maybe :waymark/instant]]]
   ;; the create form: name it and list themes; the pointer starts at
   ;; 0 and only ever moves via advance
   :create-schema [:map
                   [:name {:optional true} [:maybe [:string {:min 1 :max 100}]]]
                   [:themes {:optional true}
                    [:maybe [:vector {:min 1} [:string {:min 1 :max 50}]]]]]
   :on-create rotation-on-create
   :filterable {:state #{:eq :in}}
   :display {:title "{data.name}"}
   :actions
   {:activate {:from #{:inactive} :to :active
               :safety routine
               :handler activate-rotation
               :display {:label "Make active" :style :primary :order 1
                         :description "New plans draw Sunday themes from the most recently activated rotation"}}
    :deactivate {:from #{:active} :to :inactive
                 :safety routine
                 :display {:label "Deactivate" :order 4}}
    :add_theme {:from #{:active} :to :active
                :input theme-input
                :safety overwrite
                :handler add-theme
                :display {:label "Add theme" :style :primary :order 1}}
    :remove_theme {:from #{:active} :to :active
                   :input theme-input
                   :guards [not-last-theme]
                   :safety overwrite
                   :handler remove-theme
                   :display {:label "Remove theme" :order 2}}
    :advance {:from #{:active} :to :active
              :safety {:idempotent false :reversible false :confirm false}
              :handler advance-rotation
              :display {:label "Next theme" :order 3}}}})
