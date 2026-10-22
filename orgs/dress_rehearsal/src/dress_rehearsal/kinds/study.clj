(ns dress_rehearsal.kinds.study
  "A study: one client's R&D credit study for one tax year. Records are
  collected, the AI drafts each project's narrative and the specialist
  confirms it with the client's engineering lead, the specialist
  calculates the credit, the principal signs it off, and it goes to the
  client's CPA, who files it. We never file returns ourselves.

  While a study is collecting, the AI drafts each project's narrative as
  its engineering records arrive. The specialist, not the AI, decides
  when the cost records are complete, and that is what moves the study
  to drafting. A missing document is chased twice; after the second
  chase the principal is told."
  (:require [waymark10.dsl :refer [defguard defguardfn defresource defscenario refuse]]
            [waymark10.holds :as holds]
            [waymark10.types :as t]))

(defguard records-complete
  (refuse "Drafting starts once the cost records are complete; say when the last one came.")
  '(is-set (input :records_complete_on)))

(defguard records-complete-already
  (refuse "The last cost record came on a day that has come, not one still ahead; say when the last one came.")
  '(<= (input :records_complete_on) (today)))

(defguard chased-on-a-day-that-came
  (refuse "A chase is recorded on a day that has come, not one still ahead; say when it went.")
  '(<= (input :last_chased_on) (today)))

(defguard has-preparer
  (refuse "A study goes to review with the name of who prepared it.")
  '(is-set (input :preparer)))

(defguard has-credit
  (refuse "A study goes to review once the credit is calculated.")
  '(is-set (input :credit_usd)))

(defguardfn records-confirmed-by-a-person
  {:reads [:principal :within]
   :hold true
   :explain "The cost records are declared complete only on a person's tap: The Principal's for now, the tax specialist's once one is on file."
   :open "No door clears this one. The call waits as a held call for The Principal's tap."}
  [row _inp ctx]
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :study :records_in (:id row)) (t/allow)
    :else (t/deny)))

(defguardfn principal-signs-off
  {:reads [:principal :within]
   :hold true
   :explain "A study is signed off only on The Principal's tap, because the principal signs off every study, never the one who prepared it."
   :open "No door clears this one. The call waits as a held call for The Principal's tap."}
  [row _inp ctx]
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :study :sign_off (:id row)) (t/allow)
    :else (t/deny)))

(defresource study
  {:kind :study
   :plural "studies"
   :states [:collecting :drafting :review :signed_off :delivered]
   :initial :collecting
   :terminal #{:delivered}
   :summary "{data.client} {data.tax_year} · {state}"
   ;; a film's day reads the row as it stood: its fields, not only its state
   :retain {:data true}
   :schema [:map
            [:client [:string {:min 1 :max 120}]]
            [:tax_year [:int {:min 2000 :max 2100}]]
            [:prospect_id {:optional true :kind :prospect} [:maybe :waymark/ref]]
            [:cpa {:optional true} [:maybe [:string {:max 120}]]]
            [:records_due_on {:optional true} [:maybe [:string {:max 10}]]]
            [:filing_date {:optional true} [:maybe [:string {:max 10}]]]
            [:records_complete_on {:optional true} [:maybe :waymark/date]]
            ;; what records are still missing: one entry per kind of record, with when it was last chased
            [:missing_records {:optional true}
             [:maybe [:vector {:max 4}
                      [:map
                       [:record [:enum "payroll" "contractor_invoices" "cloud_and_supply_costs" "engineering_records"]]
                       [:last_chased_on {:optional true} [:maybe [:string {:max 10}]]]]]]]
            ;; how many times the missing documents were chased, and the last day
            [:chases {:optional true :default 0} [:maybe [:int {:min 0}]]]
            [:last_chased_on {:optional true} [:maybe :waymark/date]]
            [:principal_told_on {:optional true} [:maybe [:string {:max 10}]]]
            [:preparer {:optional true} [:maybe [:string {:max 80}]]]
            [:credit_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:signed_off_by {:optional true :kind :member} [:maybe :waymark/ref]]]
   ;; who signed is stamped by sign_off, never typed at birth
   :create-schema [:map
                   [:client [:string {:min 1 :max 120}]]
                   [:tax_year [:int {:min 2000 :max 2100}]]
                   [:prospect_id {:optional true :kind :prospect} [:maybe :waymark/ref]]
                   [:cpa {:optional true} [:maybe [:string {:max 120}]]]
                   [:records_due_on {:optional true} [:maybe [:string {:max 10}]]]
                   [:filing_date {:optional true} [:maybe [:string {:max 10}]]]
                   [:records_complete_on {:optional true} [:maybe :waymark/date]]
                   [:missing_records {:optional true}
                    [:maybe [:vector {:max 4}
                             [:map
                              [:record [:enum "payroll" "contractor_invoices" "cloud_and_supply_costs" "engineering_records"]]
                              [:last_chased_on {:optional true} [:maybe [:string {:max 10}]]]]]]]
                   [:chases {:optional true :default 0} [:maybe [:int {:min 0}]]]
                   [:last_chased_on {:optional true} [:maybe :waymark/date]]
                   [:principal_told_on {:optional true} [:maybe [:string {:max 10}]]]
                   [:preparer {:optional true} [:maybe [:string {:max 80}]]]
                   [:credit_usd {:optional true} [:maybe [:int {:min 0}]]]]
   :filterable {:state #{:eq :in} :client #{:eq} :tax_year #{:eq} :preparer #{:eq}}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:collecting "A study may stay collecting for 14 days: missing documents are chased twice, then the principal is told, and past 14 days the mayor tells The Principal."
             :drafting "A study may stay drafting for 7 days; past that, the mayor tells the tax specialist."
             :review "A study may stay in review for 3 days; past that, the mayor tells The Principal."
             :signed_off "A signed-off study may wait 2 days to be delivered; past that, the mayor tells The Principal."}
   :actions
   {:chase      {:from #{:collecting} :to :collecting
                 :input [:map
                         [:chases [:int {:min 1}]]
                         [:last_chased_on :waymark/date]
                         [:missing_records {:optional true} [:maybe [:vector {:max 4} [:map [:record [:enum "payroll" "contractor_invoices" "cloud_and_supply_costs" "engineering_records"]] [:last_chased_on {:optional true} [:maybe [:string {:max 10}]]]]]]]
                         [:principal_told_on {:optional true} [:maybe [:string {:max 10}]]]]
                 :guards [chased-on-a-day-that-came]
                 ;; it writes the count it is given, so a retry writes the same
                 :safety {:idempotent true :reversible true :confirm false}
                 :decided "The AI chases missing documents on its own: twice, then the principal is told."}
    :records_in {:from #{:collecting} :to :drafting
                 :input [:map [:records_complete_on :waymark/date]]
                 :guards [records-complete records-complete-already records-confirmed-by-a-person]
                 :display {:label "Records complete"}
                 :safety {:idempotent true :reversible false :confirm false
                          :one-way "Drafting has begun; a late record is added to the draft."}}
    :submit     {:from #{:drafting} :to :review
                 :input [:map
                         [:preparer [:string {:min 1 :max 80}]]
                         [:credit_usd [:int {:min 0}]]]
                 :guards [has-preparer has-credit]
                 :safety {:idempotent true :reversible true :confirm false}}
    :send_back  {:from #{:review} :to :drafting
                 :safety {:idempotent true :reversible true :confirm false}}
    :sign_off   {:from #{:review} :to :signed_off
                 :stamp {:signed_off_by '(actor)}
                 :guards [principal-signs-off]
                 :safety {:idempotent true :reversible false :confirm false
                          :one-way "A signed-off study is final; correct it with a new study."}}
    :deliver    {:from #{:signed_off} :to :delivered
                 :safety {:idempotent true :reversible false :confirm false
                          :one-way "The study and Form 6765 are with the client's CPA."}
                 :decided "The AI delivers a study to the client's CPA on its own once it is signed off; sign-off is the gate."}}})

(defscenario chase-a-collecting-study
  "A collecting study records a chase for its missing documents and stays collecting."
  {:kind :study :attempt :chase
   :row {:state :collecting :data {:client "Birch Instruments" :tax_year 2025 :chases 0}}
   :input {:chases 1 :last_chased_on "2026-10-20"}
   :at "2026-10-21T16:00:00Z"
   :calendar {:zone "America/Denver"}
   :expect {:allowed true}})

(defscenario records-in-with-the-day-the-last-came
  "A collecting study moves to drafting once the day the last cost record came is recorded."
  {:kind :study :attempt :records_in
   :row {:state :collecting :data {:client "Acme Robotics" :tax_year 2025}}
   :input {:records_complete_on "2026-10-20"}
   :at "2026-10-21T16:00:00Z"
   :calendar {:zone "America/Denver"}
   :expect {:allowed true}})

(defscenario records-in-refused-without-the-day-the-last-came
  "A collecting study stays collecting while no day for the last cost record is recorded."
  {:kind :study :attempt :records_in
   :row {:state :collecting :data {:client "Acme Robotics" :tax_year 2025}}
   :input {}
   :at "2026-10-21T16:00:00Z"
   :calendar {:zone "America/Denver"}
   :expect {:refused :records-complete}})

(defscenario principal-allows-sign-off-and-is-stamped
  "The mayor's sign-off waits for The Principal's tap; their Allow signs the study off, and who signed is stamped, not typed."
  {:kind :study :attempt :sign_off
   :row {:state :review :data {:client "Birch Instruments" :tax_year 2025
                               :preparer "The tax specialist" :credit_usd 48000}}
   :input {}
   :at "2026-10-30T16:00:00Z"
   :expect {:allowed true :state :signed_off
            :lands {:signed_off_by :actor}}})
