(ns clinic_rehearsal.kinds.note-addendum
  "A note addendum: the correction to a signed visit note. The signed
  note is never rewritten; the clinician who corrects it writes an
  addendum under it, and the addendum is kept as written."
  (:require [waymark10.dsl :refer [defresource]]))

(defresource note-addendum
  {:kind :note_addendum
   :plural "note_addenda"
   :states [:written]
   :initial :written
   :terminal #{:written}
   :summary "Addendum · {state}"
   :schema [:map
            [:visit_id {:kind :visit} :waymark/ref]
            [:author {:optional true :kind :member :role "clinician"} [:maybe :waymark/ref]]
            [:text {:x-display {:widget "prose"}} [:string {:min 1 :max 4000}]]]
   :filterable {:visit_id #{:eq}}
   :actions {}})
