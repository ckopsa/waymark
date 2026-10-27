(ns vanilla_plus.kinds.content
  "A piece of content: one new thing in the Classic+ world -- a quest,
  an NPC, an item, a loot change, an event, a tuning pass. It starts as
  an idea, is designed (what it is, where, for which levels, and
  whether the live server can take it with a reload or needs a
  restart), is built as one content file in ckopsa/vanilla-plus, is
  reviewed, and goes live when the world keeper has applied it to the
  running server at the merged commit.

  An approved piece waits for a `release`, which gathers approved
  pieces and takes them live together at one commit; `go_live` still
  takes one piece alone, so an urgent fix ships by itself. `release` is
  written only by the release's own doors, through `join_release` and
  `leave_release` under the engine's hand."
  (:require [waymark10.dsl :refer [defguard defhandler defresource refuse]]
            [waymark10.resource :as r]
            [waymark10.types :as t]))

(def release-hand
  "The engine's own hand a release and its pieces write each other with:
  `release` is engine-written, so only a :system principal sets it."
  (t/principal {:id "vanilla_plus-release" :type :system :display "Release"}))

(defguard in-no-release
  (refuse "A piece joins one release at a time; take it out of its release first.")
  '(not (present? :release)))

(defhandler write-the-approval [row inp _ctx]
  (assoc-in row [:data :approved_why] (:why inp)))

(defhandler join-the-release [row inp _ctx]
  (assoc-in row [:data :release] (:release inp)))

(defhandler leave-the-release [row _inp _ctx]
  (assoc-in row [:data :release] nil))

(defhandler leave-its-release [row _inp ctx]
  ;; a piece sent back or shelved while gathered leaves its release; a
  ;; release already planned refuses that, so the whole door refuses
  (if-some [rid (some-> (get-in row [:data :release]) str not-empty)]
    (do ((:invoke ctx) :release rid :drop_piece {:content_id (str (:id row))}
                       {:as release-hand})
        (assoc-in row [:data :release] nil))
    row))

(def ^:private content-types
  [:enum "quest" "npc" "item" "loot" "event" "tuning" "zone"])

(defresource content
  {:kind :content
   :plural "contents"
   :states [:idea :designed :building :in_review :approved :live :shelved]
   :initial :idea
   :terminal #{:live :shelved}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 120}]]
            [:pitch {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 4000}]]]
            [:content_type {:optional true} [:maybe content-types]]
            [:zone {:optional true} [:maybe [:string {:max 80}]]]
            [:level_range {:optional true} [:maybe [:string {:max 20}]]]
            ;; mirrors the content file's `-- Live:` header line
            [:live_mode {:optional true} [:maybe [:enum "reload" "restart"]]]
            [:build_ticket {:optional true
                            :not-a-ref "a factory ticket's id in the house engine; this org's own engine does not serve ticket"}
             [:maybe [:string {:max 64}]]]
            [:content_file {:optional true} [:maybe [:string {:max 200}]]]
            [:released_sha {:optional true} [:maybe [:string {:max 40}]]]
            [:approved_why {:optional true} [:maybe [:string {:max 200}]]]
            [:release {:optional true :kind :release :engine-written true} [:maybe :waymark/ref]]]
   :filterable {:state #{:eq :in} :content_type #{:eq} :zone #{:eq} :live_mode #{:eq} :release #{:eq}}
   :default-filters {:state "idea"}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:idea "An idea may wait two weeks for a design; past that, the designer tells Colton."
             :designed "A designed piece may wait two weeks to be built; past that, the producer tells Colton."
             :building "A piece may be in the build for a week; past that, the producer tells Colton."
             :in_review "A piece may wait three days in review; past that, the world keeper tells Colton."
             :approved "An approved piece may wait two weeks for its release; past that, the world keeper tells Colton."}
   :expected-age {:idea 1209600 :designed 1209600 :building 604800 :in_review 259200 :approved 1209600}
   :actions
   {:design      {:from #{:idea} :to :designed
                  :input [:map
                          [:pitch {:x-display {:widget "prose"}} [:string {:min 1 :max 4000}]]
                          [:content_type content-types]
                          [:live_mode [:enum "reload" "restart"]]
                          [:zone {:optional true} [:string {:max 80}]]
                          [:level_range {:optional true} [:string {:max 20}]]]
                  :handler (r/field-writer [:pitch :content_type :live_mode :zone :level_range])
                  :edit {:prefill [:pitch :content_type :live_mode :zone :level_range]
                         :draft {:shared false :live false}}
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A designed piece moves on to its build; a new design is a new idea."}
                  :decided "The designer turns an idea into a design on its own: what it is for a player, where, for which levels, and whether it goes live with a reload or a restart."}
    :start_build {:from #{:designed} :to :building
                  :input [:map [:build_ticket [:string {:min 1 :max 64}]]]
                  :handler (r/field-writer [:build_ticket])
                  :edit {:prefill [:build_ticket]}
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A started build is carried by its factory ticket; shelve the piece to stop it."}
                  :decided "The producer starts a build on its own once it has filed the factory ticket that builds the content file."}
    :submit      {:from #{:building} :to :in_review
                  :input [:map [:content_file [:string {:min 1 :max 200}]]]
                  :handler (r/field-writer [:content_file])
                  :edit {:prefill [:content_file]}
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A submitted piece is in review; the world keeper sends it back when it needs more work."}
                  :decided "The producer sends a piece to review on its own once the change that writes its content file is merged with a green tests check."}
    :approve     {:from #{:in_review} :to :approved
                  :input [:map [:why [:string {:min 1 :max 200}]]]
                  :handler write-the-approval
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "An approved piece waits for its release; the world keeper sends it back when it needs more work."}
                  :decided "The world keeper approves a piece on its own when the plan for it matches its design, and says why in one line."}
    :join_release {:from #{:approved} :to :approved
                   :input [:map [:release {:kind :release} :waymark/ref]]
                   :guards [in-no-release]
                   :handler join-the-release
                   :safety {:idempotent true :reversible true :confirm false}
                   :decided "Only a release's gather door walks this, under the engine's hand."}
    :leave_release {:from #{:approved} :to :approved
                    :handler leave-the-release
                    :safety {:idempotent true :reversible true :confirm false}
                    :decided "Only a release's gather and abandon doors walk this, under the engine's hand."}
    :send_back   {:from #{:in_review :approved} :to :building
                  :handler leave-its-release
                  :touches [{:kind :release :action :drop_piece :may true}]
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A piece sent back is in the build again; the producer submits it anew."}
                  :decided "The world keeper sends a piece back to the build on its own when the plan for it does not match its design, and says why."}
    :go_live     {:from #{:in_review :approved} :to :live
                  :input [:map [:released_sha [:string {:min 7 :max 40}]]]
                  :handler (r/field-writer [:released_sha])
                  :edit {:prefill [:released_sha]}
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A live piece is in the world; a change to it is a new piece of content."}
                  :decided "The world keeper marks a piece live on its own after wow.apply answered that the commit holding it is applied; Colton approves that apply itself, as its power asks."}
    :shelve      {:from #{:idea :designed :building :in_review :approved} :to :shelved
                  :handler leave-its-release
                  :touches [{:kind :release :action :drop_piece :may true}]
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A shelved piece is set aside; propose it again as a new idea."}
                  :decided "The seat walking a piece shelves it on its own when it no longer fits the world, and says why."}}})
