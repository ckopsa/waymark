(ns vanilla_plus.kinds.ask
  "An ask: one line the owner said in game -- a whisper to the mayor or
  `.mayor <text>` -- for the org's seat to act on. The wow server
  (ckopsa/vanilla-plus) catches it, keeps only the owner's own
  characters, and posts it signed to the `wow` integration; the engine
  trusts a correctly signed event as the owner's ask and holds no
  account logic of its own. Each event becomes one row here through the
  `:via :inbox` entry below, created as the integration.

  The seat that wakes on it answers with the line it whispered back, or
  ignores it and says why. An ask never answers or allows a held_call:
  a power the seat uses for it waits for the owner's tap as that power
  says, and nothing said in game stands in for that tap."
  (:require [waymark10.dsl :refer [defhandler defresource]]
            [waymark10.resource :as r]))

(defhandler write-the-reply [row inp _ctx]
  (-> row
      (assoc-in [:data :reply] (:reply inp))
      (assoc-in [:data :replied_at] (str (java.time.Instant/now)))))

(defhandler write-why-ignored [row inp _ctx]
  (assoc-in row [:data :ignored_why] (:why inp)))

(defresource ask
  {:kind :ask
   :plural "asks"
   :states [:received :answered :ignored]
   :initial :received
   :terminal #{:answered :ignored}
   :summary "{data.character}: {data.text} · {state}"
   :schema [:map
            ;; one chat line: the client sends at most 255 characters
            [:text [:string {:min 1 :max 255}]]
            [:character {:optional true} [:maybe [:string {:max 40}]]]
            [:account_id {:optional true} [:maybe [:string {:max 40}]]]
            [:zone {:optional true} [:maybe [:string {:max 80}]]]
            [:asked_at {:optional true} [:maybe [:string {:max 40}]]]
            [:reply {:optional true} [:maybe [:string {:max 255}]]]
            [:replied_at {:optional true :engine-written true} [:maybe [:string {:max 40}]]]
            [:ignored_why {:optional true} [:maybe [:string {:max 200}]]]]
   :filterable {:state #{:eq :in} :character #{:eq}}
   :default-filters {:state "received"}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:received "An ask may wait a day for its answer; past that, the mayor tells Colton."}
   :expected-age {:received 86400}
   :actions
   {:answer {:from #{:received} :to :answered
             :input [:map [:reply [:string {:min 1 :max 255}]]]
             :handler write-the-reply
             :edit {:prefill [:reply]}
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "An answered ask stays answered; a new line in game is a new ask."}
             :decided "The seat woken by an ask answers it on its own once it has whispered back, and writes the line it whispered."}
    :ignore {:from #{:received} :to :ignored
             :input [:map [:why [:string {:min 1 :max 200}]]]
             :handler write-why-ignored
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "An ignored ask stays ignored; ask again in game."}
             :decided "The seat woken by an ask ignores it on its own when it is not something to act on, and says why in one line."}}
   ;; the wow server posts each ask signed; it becomes a row, no door runs
   :on-event [{:source :wow
               :type "mayor.ask"
               :via :inbox
               :makes :ask
               :input {:text '(event "text")
                       :character '(event "character")
                       :account_id '(event "account_id")
                       :zone '(event "zone")
                       :asked_at '(event "asked_at")}}]})
