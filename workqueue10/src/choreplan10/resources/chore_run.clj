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
  \"Dishes · 2026-07-21 · due\" everywhere without a join.

  THE TRIAGE DECK (:views): the due queue is exactly deck-shaped —
  one state to drain, two snap judgments out of it — so the runs
  collection advertises a :deck view over state=due, Done to the
  right, Skip to the left. A deck gesture must be honestly
  reversible (checks.clj), which is what turned :done/:skipped from
  terminal states into ones with a way back: :reopen and :unskip are
  the declared :undo pairs, and a mis-swipe costs one tap on the
  undo toast instead of a wrong record kept forever."
  (:require [waymark10.dsl :refer [defresource prose ref-to]]))

(defresource chore-run
  {:kind :chore_run
   :states [:due :done :skipped]
   :initial :due
   :summary "{data.chore_name} · {data.due_date} · {state}"
   :filterable {:state #{:eq :in} :chore_id #{:eq} :overdue #{:eq}}
   :sortable {:fields [:due_date] :default "due_date"}

   :fields
   {:at-create  [[:chore_id (ref-to :chore {:label :chore_name
                                            ;; the chore's standing
                                            ;; instructions ride the run
                                            ;; — at a glance, no hop to
                                            ;; the chore
                                            :carry {:notes :chore_notes}})]
                 [:due_date :waymark/date]]

    :while-open [[:assignee [:waymark/vocab {:open true}]]
                 [:notes (prose "How it went")]]
    :open       #{:due}
    ;; engine-maintained: the ranked worklist's one law — hard-due
    ;; first — needs "past due" as an indexed fact on BOTH kinds
    ;; (prep_task already derives its own)
    :facts      [[:overdue :boolean]]}

   ;; due DATE, not instant: overdue starts the morning after, the
   ;; prep_task law's date-typed variant
   :derived
   {:overdue {:over [:due_date :now]
              :expr '(< (var :due_date) (date-of (var :now)))}}

   :actions
   {:complete
    {:from #{:due} :to :done
     :undo :reopen
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Done" :style :primary :order 1}}

    :skip
    {:from #{:due} :to :skipped
     :undo :unskip
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Skip" :order 2}}

    ;; the honest reverses the deck demands: each departs from its
    ;; pair's destination and lands exactly back in :due (the undo
    ;; law verify-undo-pointers holds), so a snap judgment is one
    ;; tap from unmade
    :reopen
    {:from #{:done} :to :due
     :undo :complete
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Reopen" :order 3}}

    :unskip
    {:from #{:skipped} :to :due
     :undo :skip
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Un-skip" :order 4}}}

   ;; the demo deck: triage the due queue — swipe right Done, swipe
   ;; left Skip; the card leads with the chore and when it's due
   :views [{:name :triage :kind :deck
            :where {:state :due}
            :right :complete :left :skip
            :card [:chore_id :due_date :assignee :overdue]
            :display {:label "Triage"}}]})
