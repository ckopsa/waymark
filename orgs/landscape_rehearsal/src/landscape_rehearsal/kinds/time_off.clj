(ns landscape_rehearsal.kinds.time-off
  "Time off: a crew away for a window. Once approved it holds the crew
  as a booking does, through the same :occupies, so a booking over it
  is refused and it is not approved over a booking."
  (:require [waymark10.dsl :refer [defresource]]))

(def ^:private safety
  {:idempotent true :reversible false :confirm false
   :one-way "Approved or withdrawn, time off stays so; ask for new time off to change it."})

(defresource time-off
  {:kind :time_off
   :plural "time_offs"
   :states [:requested :approved :withdrawn]
   :initial :requested
   :terminal #{:withdrawn}
   :summary "Time off · {state}"
   :schema [:map
            [:crew_id {:kind :crew} :waymark/ref]
            [:starts_at :waymark/instant]
            [:ends_at :waymark/instant]]
   :filterable {:state #{:eq :in} :crew_id #{:eq} :starts_at #{:eq} :ends_at #{:eq}}
   :occupies [{:resource :crew_id :from :starts_at :to :ends_at
               :in #{:approved} :doors #{:create :approve}}]
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:requested "A time off request waits three days; past that, the mayor tells the owner."
             :approved "Approved time off stands until it is withdrawn; the mayor reviews it every month."}
   :expected-age {:requested 259200 :approved 2592000}
   :actions
   {:approve {:from #{:requested} :to :approved :safety safety
              :decided "The mayor approves time off on its own when no booking holds the crew then."}
    :withdraw {:from #{:requested :approved} :to :withdrawn :safety safety
               :decided "The mayor withdraws time off on its own when the crew says it is not needed."}}})
