(ns waymark10.purpose-for
  "Who each engine kind is FOR (ticket 6c34056f): the `for` and
  `for_note` of its :purpose, kept here and not in the kinds' own
  files, several of which sit at their ns-size ceiling. `for` is who
  starts a row of the kind and who it serves — :person, :agent,
  :either or :engine — and `for_note` the one sentence said when the
  two differ. A kind that declares :for in its own :purpose wins over
  this table (declaration/purpose-wire). Prose, never law. Requires
  nothing, so any namespace may require it.")

(def vocabulary
  "The closed set a :for names."
  #{:person :agent :either :engine})

(def by-kind
  {;; led, asked and waited on
   :quest {:for :person
           :for-note "Started by a person, for a person: a door they see blocked, walked to where they can take it. An agent's equivalent is waymark_pursue, not a quest."}
   :invitation {:for :person
                :for-note "Started by an agent or the quest engine, for a person: one step."}
   :walkthrough {:for :person
                 :for-note "Started by an agent, accepted by a person, for that person: several steps in order."}
   :held_call {:for :agent
               :for-note "Started by an agent's own call and decided by a person: it approves an agent's act, and is not how a person is led."}
   :approval_request {:for :either
                      :for-note "Started by an agent or a person and decided by a person: standing authority, not one act, and not for a seat's grant."}
   :scheduled_action {:for :either
                      :for-note "An agent passes `at` on invoke; a person uses 'Do this later' on the action dialog."}
   ;; the seat machinery
   :seat {:for :agent
          :for-note "An owner declares the office; an agent sits in it."}
   :model {:for :agent
           :for-note "An owner sets the price list; the agents that sit in seats are billed by it."}
   :sitting {:for :engine
             :for-note "The engine opens one when a seat wakes; an agent's session works inside it."}
   :schedule {:for :engine
              :for-note "An owner sets it; the engine reads it to start a seat's sittings."}
   :runner_link {:for :engine
                 :for-note "An owner registers it; the engine fires seats through it."}
   :runner_provider {:for :engine}
   :subscription {:for :engine
                  :for-note "Set by a person or an agent; the engine sends the log or wakes a seat through it."}
   :lease {:for :engine
           :for-note "Written by the engine only."}
   :transcript {:for :agent
                :for-note "Posted by an agent's harness, line by line; a person reads it to audit the sitting."}
   :transcript_entry {:for :agent
                      :for-note "Posted by an agent's harness; never written by hand."}
   :sitting_call {:for :engine
                  :for-note "Written by the engine only."}
   :job {:for :engine
         :for-note "The engine opens one when a bulk call is too large to answer at once."}
   :definition {:for :engine}
   :walk_frame {:for :engine
                :for-note "Written by the engine only."}
   :take {:for :engine}
   :measure_reading {:for :engine}
   :capability {:for :engine}
   ;; the outside world
   :posting {:for :engine
             :for-note "Written by the engine only, in the door's own transaction."}
   :integration_event {:for :engine
                       :for-note "Written by the engine only."}
   :effect_call {:for :engine}
   :integration {:for :engine
                 :for-note "An owner sets it up; the outside system acts through it as a principal of its own."}
   :mcp_server {:for :agent
                :for-note "An owner registers it; an agent reaches its tools through waymark_power."}
   ;; authority
   :member {:for :either
            :for-note "A person or an agent: the roster names both."}
   :grant {:for :either
           :for-note "Worn by a person or an agent; widened when a person approves an approval request."}
   :role {:for :person
          :for-note "An owner registers it; a person or an agent holds it."}
   :team {:for :either}
   :secret {:for :person
            :for-note "A person writes it; the engine substitutes it, and no agent reads it."}
   :domain {:for :person
            :for-note "An owner sets its ceiling over the seats inside it."}
   :amendment_policy {:for :person
                      :for-note "The owner arms it."}
   :notifier {:for :person
              :for-note "A person makes one to be told."}
   :notice_rule {:for :either}
   ;; the house's own records and views
   :promise {:for :either}
   :goal {:for :either}
   :judgment {:for :either}
   :verdict {:for :either}
   :amendment {:for :either}
   :saved_view {:for :either}
   :dashboard {:for :either}
   :dashboard_slot {:for :either}
   :work_calendar {:for :either}
   :attachment {:for :either}
   :scene {:for :either}
   :film_rule {:for :either}
   :walk {:for :either}})

(defn of
  "{:for :for-note} for the kind `k`, or nil when this table says
  nothing of it."
  [k]
  (when k (get by-kind (keyword (name k)))))
