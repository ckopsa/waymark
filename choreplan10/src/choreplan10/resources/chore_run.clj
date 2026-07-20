(ns choreplan10.resources.chore-run
  "The ChoreRun resource: one occurrence of a chore — due on a date,
  then done or skipped, one row per ask. It references its chore by
  :chore_id rather than living embedded inside it (the
  eveningplan10 session split: an occurrence has its own lifecycle,
  so it earns its own rows).

  :at-create is just :chore_id + :due_date, the two facts that never
  change; :assignee/:notes live in :while-open because who actually
  does a run (and how it went) is decided as the day arrives, not
  once forever at creation. :filterable/:sortable ride separately
  from :fields because a :fields row is strictly [field (word …)] —
  :filterable is what makes chore's :links.runs href a real filter;
  :sortable is what its embedded grid can offer to sort by.

  Summary leans on :chore_name — the ref's engine-maintained label
  copy (the prep_task meal_name pattern), so a run reads as
  \"Dishes · 2026-07-21 · due\" everywhere without a join."
  (:require [waymark10.dsl :refer [defresource prose ref-to]]))

(defresource chore-run
  {:kind :chore_run
   :states [:due :done :skipped]
   :initial :due
   :terminal #{:done :skipped}
   :summary "{data.chore_name} · {data.due_date} · {state}"
   :filterable {:state #{:eq :in} :chore_id #{:eq}}
   :sortable {:fields [:due_date] :default "due_date"}

   :fields
   {:at-create  [[:chore_id (ref-to :chore {:label :chore_name})]
                 [:due_date :waymark/date]]

    :while-open [[:assignee [:waymark/vocab {:open true}]]
                 [:notes (prose "How it went")]]
    :open       #{:due}}

   :actions
   {:complete
    {:from #{:due} :to :done
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Done is done — the run closes and stays as the record."}
     :display {:label "Done" :style :primary :order 1}}

    :skip
    {:from #{:due} :to :skipped
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Skipping closes the run without doing it; the next run starts fresh."}
     :display {:order 2}}}})
