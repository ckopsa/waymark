(ns vanilla_plus.kinds.release
  "A release: approved content gathered to go live at one time. The
  world keeper gathers approved pieces, reads the plan wow.plan answers
  for the merged commit that holds every file, applies it on Colton's
  tap, and takes every piece live with that commit in one write.
  Abandoning a release frees its pieces, which stay approved."
  (:require [vanilla_plus.kinds.content :refer [release-hand]]
            [waymark10.dsl :refer [defhandler defresource]]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]))

(defn- live-mode
  "restart when any piece needs one, else reload."
  [ctx ids]
  (if (some #(= "restart" (get-in ((:read ctx) :content %) [:data :live_mode])) ids)
    "restart"
    "reload"))

(defn- with-pieces [row ctx ids]
  (-> row
      (assoc-in [:data :content] (vec ids))
      (assoc-in [:data :live_mode] (live-mode ctx ids))))

(defhandler gather-the-pieces [row inp ctx]
  (let [self (str (:id row))
        had (vec (get-in row [:data :content]))
        drop' (set (map str (:remove inp)))
        add (remove (set had) (distinct (map str (:add inp))))]
    (doseq [id had :when (drop' id)]
      ((:invoke ctx) :content id :leave_release {} {:as release-hand}))
    (doseq [id add]
      ((:invoke ctx) :content id :join_release {:release self} {:as release-hand}))
    (with-pieces row ctx (into (vec (remove drop' had)) add))))

(defhandler drop-the-piece [row inp ctx]
  (let [gone (str (:content_id inp))]
    (with-pieces row ctx (vec (remove #{gone} (get-in row [:data :content]))))))

(defhandler write-the-apply [row _inp _ctx]
  (assoc-in row [:data :applied_at] (str (java.time.Instant/now))))

(defn- each-approved-piece!
  "f on each piece of the release still approved, with the etag of the
  version read: content's go_live is an edit door, so it is fenced."
  [row ctx f]
  (doseq [id (get-in row [:data :content])
          :let [piece ((:read ctx) :content id)]
          :when (= "approved" (some-> piece :state name))]
    (f id (inv/etag :content id (:version piece)))))

(defhandler take-every-piece-live [row _inp ctx]
  ;; one write: every piece reaches live with the release's commit in
  ;; the release's own transaction, or none does; a piece that already
  ;; went live alone is left as it is
  (let [sha (get-in row [:data :sha])]
    (each-approved-piece! row ctx
                          #((:invoke ctx) :content %1 :go_live {:released_sha sha}
                                          {:as release-hand :if-match %2}))
    row))

(defhandler free-the-pieces [row _inp ctx]
  (each-approved-piece! row ctx
                        #((:invoke ctx) :content %1 :leave_release {}
                                        {:as release-hand :if-match %2}))
  row)

(defresource release
  {:kind :release
   :plural "releases"
   :states [:gathering :planned :applied :live :abandoned]
   :initial :gathering
   :terminal #{:live :abandoned}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 120}]]
            [:content {:optional true :engine-written true} [:maybe [:vector :string]]]
            ;; the merged commit in ckopsa/vanilla-plus that holds every file
            [:sha {:optional true} [:maybe [:string {:max 40}]]]
            [:plan_summary {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 4000}]]]
            ;; restart when any piece's live_mode is restart, else reload
            [:live_mode {:optional true :engine-written true} [:maybe [:enum "reload" "restart"]]]
            [:applied_at {:optional true :engine-written true} [:maybe [:string {:max 40}]]]]
   :filterable {:state #{:eq :in}}
   :default-filters {:state "gathering"}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:gathering "A release may gather for two weeks; past that, the world keeper tells Colton."
             :planned "A planned release may wait three days for Colton's tap on its apply."
             :applied "An applied release goes live the same day; past that, the world keeper tells Colton."}
   :expected-age {:gathering 1209600 :planned 259200 :applied 86400}
   :actions
   {:gather  {:from #{:gathering} :to :gathering
              :input [:map
                      [:add {:optional true} [:vector [:string {:min 1 :max 64}]]]
                      [:remove {:optional true} [:vector [:string {:min 1 :max 64}]]]]
              :handler gather-the-pieces
              :touches [{:kind :content :action :join_release :may true}
                        {:kind :content :action :leave_release :may true}]
              :safety {:idempotent true :reversible true :confirm false}
              :decided "The world keeper gathers approved pieces into a release on its own, and takes out any that should wait."}
    :drop_piece {:from #{:gathering} :to :gathering
                 :input [:map [:content_id {:kind :content} :waymark/ref]]
                 :handler drop-the-piece
                 :safety {:idempotent true :reversible false :confirm false
                          :one-way "A piece sent back or shelved has left the release; gather it again once it is approved again."}
                 :decided "Only a piece's send_back and shelve doors walk this, under the engine's hand."}
    :plan    {:from #{:gathering} :to :planned
              :input [:map
                      [:sha [:string {:min 7 :max 40}]]
                      [:plan_summary {:x-display {:widget "prose"}} [:string {:min 1 :max 4000}]]]
              :handler (r/field-writer [:sha :plan_summary])
              :edit {:prefill [:sha :plan_summary]
                     :draft {:shared false :live false}}
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A planned release waits for its apply; abandon it to gather again."}
              :decided "The world keeper plans a release on its own once wow.plan answered for the merged commit that holds every piece's file."}
    :applied {:from #{:planned} :to :applied
              :handler write-the-apply
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "An applied release is on the server; take it live."}
              :decided "The world keeper marks a release applied on its own once wow.apply answered applied; Colton approves that apply itself, as its power asks."}
    :go_live {:from #{:applied} :to :live
              :handler take-every-piece-live
              :touches [{:kind :content :action :go_live :may true}]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A live release is in the world, with every piece in it; a change is a new release."}
              :decided "The world keeper takes a release live on its own once it is applied, and every piece in it goes live with the release's commit."}
    :abandon {:from #{:gathering :planned :applied} :to :abandoned
              :handler free-the-pieces
              :touches [{:kind :content :action :leave_release :may true}]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "An abandoned release is set aside; its pieces stay approved for another."}
              :decided "The world keeper abandons a release on its own when it should not go out as gathered, and says why."}}})
