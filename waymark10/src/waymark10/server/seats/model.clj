(ns waymark10.server.seats.model
  "The model: a rung of the ladder a seat is held for, with its prices
  and its chair (docs/spec-seat.md). Its resource, and the handlers of
  the two doors only it has.

  Moved out of `waymark10.server.seats` whole (ticket 10a0be5d, round
  eight). The chair's key and pool-order handlers, the pool orders and
  the standing key's migration are the seat's as well, so they stay
  there and this namespace reads them from there."
  (:require [waymark10.resource :refer [defresource defhandler]]
            ;; the doors and the migration the seat declares too
            [waymark10.server.seats
             :refer [clear-sitter-key hash-standing-key runner-order-choices
                     runner-orders set-runner-order set-sitter-key]]
            [waymark10.server.seats.guards
             :refer [a-person-at-the-chair key-not-written-by-hand one-model-spelling]]
            [waymark10.server.seats.words :as words :refer [price-entry]]))

(set! *warn-on-reflection* true)

(defhandler reprice-model [row inp _ctx]
  (reduce (fn [r f] (assoc-in r [:data f] (get inp f)))
          row
          [:price_input_per_mtok :price_output_per_mtok
           :price_cache_read_per_mtok :price_cache_write_per_mtok]))

(defhandler restate-model [row inp _ctx]
  (reduce (fn [r f] (if (contains? inp f) (assoc-in r [:data f] (get inp f)) r))
          row
          [:name :display :notes]))

(defresource model
  {:kind :model
   :plural "models"
   :purpose words/model
   :states [:active :retired]
   :initial :active
   :terminal #{}                       ; retirement is reversible, deliberately
   :nav :system
   ;; a person retires a model and brings it back; no seat does
   :left-by [{:in #{:active} :by :person :via :retire :to :retired}
             {:in #{:retired} :by :person :via :reactivate :to :active}]
   :summary "{data.name} · {data.tier} · {state}"
   :label-template "{data.display}"
   :schema
   [:map
    [:name {:examples ["claude-sonnet-5"]
            :x-display
            {:raw true
             :label "API identifier"
             :help "The identifier the harness declares and the vendor answers to, spelled exactly. This is what a session's claim is matched against, so a near-miss is a seat that serves nothing."}}
     [:string {:min 1 :max 64}]]
    [:display {:examples ["Sonnet 5"]
               :x-display
               {:label "What a person reads"
                :help "The short name for a card, a ledger line and a ladder step — what you would say out loud."}}
     [:string {:min 1 :max 120}]]
    [:vendor {:examples ["anthropic"]
              :x-display
              {:raw true
               :label "Who serves it"
               :help "The vendor this identifier belongs to, lowercase."}}
     [:string {:min 1 :max 60}]]
    [:tier {:x-display
            {:label "Rung on the ladder"
             :choices {"frontier" "Frontier — the newest and dearest; where a seat opens before its judgment is known"
                       "strong" "Strong — most of the work, most of the time"
                       "economy" "Economy — the floor a seat reaches when the law is spoken at the door"}}}
     [:enum "frontier" "strong" "economy"]]
    [:price_input_per_mtok {:examples [3M]
                            :not-money "a USD rate per million tokens, not an amount; cost-of turns it into money"
                            :x-display
                            {:label "Input, $ per million tokens"
                             :help "What a million input tokens costs. Every price here is read at the moment a sitting closes and written down beside its cost, so a later reprice never moves a closed bill."}}
     price-entry]
    [:price_output_per_mtok {:examples [15M]
                             :not-money "a USD rate per million tokens, not an amount; cost-of turns it into money"
                             :x-display
                             {:label "Output, $ per million tokens"
                              :help "What a million output tokens costs."}}
     price-entry]
    [:price_cache_read_per_mtok {:examples [0.3M]
                                 :not-money "a USD rate per million tokens, not an amount; cost-of turns it into money"
                                 :x-display
                                 {:label "Cache read, $ per million tokens"
                                  :help "What a million cache-read tokens costs — usually the cheapest line, and the reason a charter that fits in the cache is nearly free to carry."}}
     price-entry]
    [:price_cache_write_per_mtok {:examples [3.75M]
                                  :not-money "a USD rate per million tokens, not an amount; cost-of turns it into money"
                                  :x-display
                                  {:label "Cache write, $ per million tokens"
                                   :help "What a million cache-write tokens costs."}}
     price-entry]
    [:notes {:optional true
             :x-display
             {:label "Anything else worth knowing"
              :help "A line for whoever reads the ladder later — a context window, a deprecation date, why this rung exists."}}
     [:maybe [:string {:max 240}]]]
    ;; ── THE CHAIR (waymark-fp62.7.23) ───────────────────────────────
    ;; ONE ROUTINE FOR EACH MODEL, so the credentials that Routine
    ;; needs live on the model. Both fields are the seat's and the
    ;; schedule's own, worn here without a change of meaning: a
    ;; session presenting `sitter_key` is a sitter of any seat this
    ;; model is the chair of (`waymark_sit`, R-4), and a seat with no
    ;; pool of its own fires through the runner links in this model's
    ;; (R-5). A step down the ladder is then one restate of the
    ;; seat's `held_for` — the seat moves to another chair, and
    ;; nobody makes a second Routine.
    ;; THE CHAIR'S KEY IS STANDING TOO (R-22.6), so the row keeps its
    ;; hash alone, as the seat does. `sitter_key` stays declared only
    ;; as the fence `key-not-written-by-hand` judges: this schema is
    ;; the create door, and a create carrying it is refused by name.
    [:sitter_key {:optional true :secret true
                  :x-display
                  {:hidden true
                   :label "Chair key"
                   :spelled-by-hand "Refused here: the key is offer_key's to write."}}
     [:maybe [:string {:min 22 :max 128}]]]
    [:sitter_key_hash {:optional true :secret true
                       :x-display
                       {:hidden true
                        :label "Chair key hash"
                        :spelled-by-hand "Written by offer_key as the key's hash and cleared by revoke_key; the key itself is never kept, never typed into a form, and never rendered back."}}
     [:maybe [:string {:max 128}]]]
    ;; the runner pool (waymark ticket d16b71bf): the order the links
    ;; every seat this model is the chair of fires through are tried
    ;; in, unless its schedule has a pool of its own.
    [:runner_order {:optional true
                    :x-display
                    {:label "Pool order"
                     :help "How a fire picks among the runner links that may fire. Empty is least used."
                     :choices runner-order-choices}}
     [:maybe (into [:enum] runner-orders)]]]
   :filterable {:state #{:eq :in}
                :name #{:eq}
                :tier #{:eq :in}}
   :sortable {:fields [:name] :default "name"}
   ;; ONE ROW PER IDENTIFIER, enforced by an index and not by a
   ;; sentence: a second row for one model would split its prices, and
   ;; a sitting costed against the wrong half would be wrong forever.
   :unique [[:name]]
   ;; the write fence of R-2, and the seat's own note about why a
   ;; fence must have something to name: a guard judges a field of the
   ;; door it stands on. This kind declares no create-schema, so its
   ;; row schema IS that door and the field is already there to be
   ;; refused — which is the whole of what the seat had to declare it
   ;; for.
   :create-guards [one-model-spelling
                   key-not-written-by-hand]
   ;; a raw chair key from before the hash is hashed at boot (R-22.6)
   :data-migrate hash-standing-key
   :actions
   {:retire {:from #{:active} :to :retired
             :safety {:idempotent true :reversible true :confirm true
                      :consequence "Seats may no longer be held for this model, and a restate naming it is refused; sittings already costed against its prices are untouched."}
             :display {:label "Retire" :style :danger :order 9}}
    :reactivate {:from #{:retired} :to :active
                 :safety {:idempotent true :reversible true :confirm false}
                 :display {:label "Reactivate" :order 1}}
    ;; A TRANSITION, so the history of prices is on record (R-9.3): the
    ;; log says what the prices were, when they moved, and by whose
    ;; hand — and a closed sitting keeps the copy it was costed
    ;; against, so the record and the bills never disagree.
    :reprice {:from #{:active} :to :active
              :input [:map
                      [:price_input_per_mtok {:examples [3M]
                                              :x-display {:label "Input, $ per million tokens"
                                                          :help "The new price for a million input tokens."}}
                       price-entry]
                      [:price_output_per_mtok {:examples [15M]
                                               :x-display {:label "Output, $ per million tokens"
                                                           :help "The new price for a million output tokens."}}
                       price-entry]
                      [:price_cache_read_per_mtok {:examples [0.3M]
                                                   :x-display {:label "Cache read, $ per million tokens"
                                                               :help "The new price for a million cache-read tokens."}}
                       price-entry]
                      [:price_cache_write_per_mtok {:examples [3.75M]
                                                    :x-display {:label "Cache write, $ per million tokens"
                                                                :help "The new price for a million cache-write tokens."}}
                       price-entry]]
              :record true
              :edit {:prefill [:price_input_per_mtok :price_output_per_mtok
                               :price_cache_read_per_mtok
                               :price_cache_write_per_mtok]}
              :safety {:idempotent true :reversible true :confirm false}
              :handler reprice-model
              :display {:label "Reprice" :order 2
                        :description "The vendor moved its prices — record the new four; nothing already closed changes"}}
    ;; The Routine behind a row can move to another model; the row
    ;; follows it here and keeps its id, its history and its seats.
    ;; Prices stay with `reprice`. An omitted field keeps its value.
    :restate {:from #{:active} :to :active
              :input [:map
                      [:name {:optional true
                              :examples ["claude-sonnet-5"]
                              :x-display {:raw true
                                          :label "API identifier"
                                          :help "The identifier the Routine's model now answers to, spelled exactly."}}
                       [:string {:min 1 :max 64}]]
                      [:display {:optional true
                                 :examples ["Sonnet 5"]
                                 :x-display {:label "What a person reads"
                                             :help "The short name for a card, a ledger line and a ladder step."}}
                       [:string {:min 1 :max 120}]]
                      [:notes {:optional true
                               :x-display {:label "Anything else worth knowing"
                                           :help "A line for whoever reads the ladder later."}}
                       [:maybe [:string {:max 240}]]]]
              :guards [one-model-spelling]
              :record true
              :edit {:prefill [:name :display :notes]}
              :safety {:idempotent true :reversible true :confirm false}
              :handler restate-model
              :display {:label "Restate" :order 3
                        :description "The Routine now runs another model — rename this row; its seats, sittings and prices stay"}}

    ;; ── the chair's four doors (waymark-fp62.7.23) ──────────────────
    ;; The seat's `offer_key`/`revoke_key` and the schedule's
    ;; `link`/`unlink`, on the row that is the chair. None of the four
    ;; records, and it is the seat's `offer_key` reason verbatim: a
    ;; recorded action persists its RAW inputs into the transition
    ;; log, and two of these inputs ARE credentials (R-12.11). The
    ;; transition row — actor, input digest, summary — is still the
    ;; audit that each was written, by whom, when.
    :offer_key
    {:from #{:active} :to :active
     :input [:map
             [:key {:x-display
                    {:raw true
                     :label "Chair key"
                     :help "The secret this model's Routine hands back to the engine when it sits, minted by YOU — at least 22 characters of real randomness, which is 128 bits a machine made and no hand typed. The engine never generates it and never shows it again. A new offer replaces the old one."}}
              [:string {:min 22 :max 128}]]]
     :guards [a-person-at-the-chair]
     :safety {:idempotent true :reversible true :confirm false}
     :handler set-sitter-key
     :display {:label "Offer key" :order 4
               :description "Hand this model a secret to paste into its Routine — a session presenting it may sit in any seat this model is the chair of"}}

    :revoke_key
    {:from #{:active} :to :active
     :guards [a-person-at-the-chair]
     :safety {:idempotent true :reversible true :confirm false}
     :handler clear-sitter-key
     :display {:label "Revoke key" :order 5
               :description "The key answers for nothing; a session presenting it is told no seat answers, and the seats themselves are untouched"}}

    ;; the pool order (waymark ticket 529deb73): a person's hand only.
    ;; Which links are in the pool is each link's own `pools`, through
    ;; its join_pool and leave_pool; the engine never sets an order.
    :set_runner_order
    {:from #{:active} :to :active
     :input [:map
             [:runner_order {:optional true
                             :x-display
                             {:label "Pool order"
                              :help "How a fire picks among the links that may fire. Empty is least used."
                              :choices runner-order-choices}}
              [:maybe (into [:enum] runner-orders)]]]
     :writes #{:runner_order}
     :record true
     :guards [a-person-at-the-chair]
     :edit {:prefill [:runner_order] :fence false
            :unfenced-reason
            "The order is restated whole; a restate replaces what stands rather than editing it."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The new order replaces the one this model held; another restate puts it back."}
     :handler set-runner-order
     :display {:label "Pool order" :order 8
               :description "How a fire picks among the links in the pool of this model's seats. A runner link joins the pool from its own page, through Add to a pool"}}}
   :deviations
   ["THE CHAIR'S WRITE FENCE IS A GUARD. `sitter_key` is declared on this kind's ONE schema, which is its create door as well — this kind has no create-schema — so a create could carry it. `key-not-written-by-hand` is what refuses it, and its refusal names the door that writes the field instead. The secret stays `{:secret true}`, so the advertised create body drops it, no form asks, and the usability policies skip it; what a caller gains over silent omission is the sentence."
    "R-9.2 calls `name` unique and R-4.7's precedent (roles.clj's `one-spelling`) judges only ACTIVE rows. The two disagree about a retired model, so this kind takes the index's reading: `one_model_spelling` refuses any spelling already on record, active or retired, and its sentence sends the reader to `reactivate`. A second row for one identifier would split its prices, and a closed sitting costed against the wrong half would be wrong forever."]})
