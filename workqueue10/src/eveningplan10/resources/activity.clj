(ns eveningplan10.resources.activity
  "The Activity resource: a thing worth doing some evening — pinned to
  how much physical/mental energy it takes and where it happens, so
  picking among a desired activity and its backups is a fast filter,
  not a re-think every time.

  :label-template spells itself out because this kind's name field is
  :title, not :name — the engine's default ref-label template
  (\"{data.name}\") wouldn't find it, and every ref to an activity
  (evening_session's desired/backup slots) would maintain a nil
  label."
  (:require [waymark10.dsl :refer [defresource one-of defhandler]]))

(defhandler apply-duration [row inp _ctx]
  (assoc-in row [:data :duration_minutes] (:duration_minutes inp)))

(defresource activity
  {:kind :activity
   :states [:active :archived]
   :initial :active
   :summary "{data.title}"
   :label-template "{data.title}"
   ;; default pluralization is bare kind+"s" ("activitys") — spelled
   ;; here so the collection URL and table name read right
   :plural "activities"
   :schema [:map
            [:title [:string {:min 1 :max 120}]]
            [:physical_energy (one-of :high :low)]
            [:mental_energy (one-of :high :low)]
            [:location (one-of :workshop :desk :anywhere)]
            [:duration_minutes {:optional true} [:int {:min 1 :max 180}]]
            ;; (prose …)'s :x-display metadata only auto-hoists inside
            ;; :fields rows; in a plain :schema entry, spell it
            [:adaptation_notes {:optional true
                                :x-display {:widget "prose"
                                            :label "What to bring if constrained"}}
             [:maybe [:string {:min 1 :max 8000}]]]]
   :actions
   {:archive {:from #{:active} :to :archived
              :undo :restore
              :safety {:idempotent true :reversible false :confirm true :consequence "You might fart"}}
    :set_duration {:from #{:active} :to :active
                   :input [:map [:duration_minutes [:int {:min 1 :max 180}]]]
                   :edit {:prefill [:duration_minutes]}
                   :safety {:idempotent true :reversible false :confirm false}
                   :handler apply-duration
                   :display {:label "Set duration"}}
    :restore {:from #{:archived} :to :active
          :safety {:idempotent true :reversible true :confirm false}
          :display {:label "Restore"}}
    }})
