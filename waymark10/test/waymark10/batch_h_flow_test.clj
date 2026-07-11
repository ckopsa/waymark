(ns waymark10.batch-h-flow-test
  "Batch H, deltas 3 and 4 — :flow rows and :undo pointers. Rows of
  [from action to opts?] normalize into today's :actions map; rows
  sharing an action name union their origins, and their :confirm
  sentences land as a per-origin consequence map the render layer
  resolves by the row's CURRENT state. An :undo pointer is verified
  against the graph (the undo departs from this edge's destination
  and lands exactly where it began), stamps :reversible true, and is
  stripped — the invariance proof pins the sugared and split
  spellings byte-identical."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.fingerprint :as fp]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.render :as render]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ready-gate
  (g/expr {:name :ready-gate
           :when '(= (data :ready) true)
           :explain "Not ready yet."
           :severity :refuse}))

;; ── the sugared spelling ────────────────────────────────────────────

(def sugared
  {:kind :parcel
   :initial :draft
   :terminal #{:cancelled}
   :summary "{data.label} · {state}"
   :schema [:map
            [:label [:string {:max 40}]]
            [:ready {:optional true} [:maybe :boolean]]]
   :flow
   [[:draft  :send   :sent      {:requires [ready-gate] :undo :recall}]
    [:sent   :recall :draft     {:undo :send}]
    [:draft  :cancel :cancelled {:confirm "Discards the unsent draft."}]
    [:sent   :cancel :cancelled {:confirm "The recipient may already have it."}]]})

;; ── the split spelling — today's map, byte-for-byte ─────────────────

(def split
  {:kind :parcel
   :states [:draft :sent :cancelled]
   :initial :draft
   :terminal #{:cancelled}
   :summary "{data.label} · {state}"
   :schema [:map
            [:label [:string {:max 40}]]
            [:ready {:optional true} [:maybe :boolean]]]
   :actions
   {:send {:from #{:draft} :to :sent
           :guards [ready-gate]
           :safety {:idempotent true :reversible true :confirm false}}
    :recall {:from #{:sent} :to :draft
             :safety {:idempotent true :reversible true :confirm false}}
    :cancel {:from #{:draft :sent} :to :cancelled
             :safety {:idempotent true :reversible false :confirm true
                      :consequence {:draft "Discards the unsent draft."
                                    :sent "The recipient may already have it."}}}}})

(deftest two-spellings-one-law
  (is (= (r/normalize-resource split) (r/normalize-resource sugared))
      "the flow rows normalize into today's :actions map, states derived in row order")
  (is (= (fp/fingerprint-hash (r/fingerprint (r/normalize-resource split)))
         (fp/fingerprint-hash (r/fingerprint (r/normalize-resource sugared))))))

(deftest flow-derives-the-states-in-row-order
  (is (= [:draft :sent :cancelled]
         (:states (r/normalize-resource sugared)))
      "no :states spelled — the rows and the terminals name them, initial first"))

(deftest the-full-gate-accepts-the-flow-spelling
  (is (map? (r/resource sugared))))

;; ── per-origin consequence, on the wire ─────────────────────────────

(deftest the-consequence-follows-the-origin
  (let [rdef (r/resource sugared)
        envelope (fn [state data]
                   (render/envelope rdef
                                    {:id "p1" :state state :version 1
                                     :data data}
                                    {:now (Instant/parse "2026-07-10T00:00:00Z")}))]
    (testing "from draft, cancelling costs the draft sentence"
      (is (= "Discards the unsent draft."
             (get-in (envelope :draft {:label "x"})
                     ["actions" "cancel" "display" "description"]))))
    (testing "from sent, the same action costs a different sentence"
      (is (= "The recipient may already have it."
             (get-in (envelope :sent {:label "x"})
                     ["actions" "cancel" "display" "description"]))))
    (testing "identical sentences collapse to the plain string spelling"
      (let [n (r/normalize-resource
               (assoc sugared :flow
                      [[:draft :send :sent {:requires [ready-gate] :undo :recall}]
                       [:sent :recall :draft {:undo :send}]
                       [:draft :cancel :cancelled {:confirm "Gone."}]
                       [:sent :cancel :cancelled {:confirm "Gone."}]]))]
        (is (= "Gone." (get-in n [:actions :cancel :safety :consequence])))))))

;; ── the rows must agree where the engine has one slot ───────────────

(defn- flow-error [flow re]
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo re
       (r/normalize-resource (assoc sugared :flow flow)))))

(deftest rows-sharing-an-action-agree
  (testing "one destination"
    (flow-error [[:draft :send :sent {:undo :recall}]
                 [:sent :recall :draft {:undo :send}]
                 [:sent :send :cancelled {:one-way "No."}]]
                #"one action, one destination"))
  (testing "per-origin :requires is a recorded demand, not a silent merge"
    (flow-error [[:draft :send :sent {:undo :recall}]
                 [:sent :recall :draft {:undo :send}]
                 [:draft :cancel :cancelled {:confirm "Gone."}]
                 [:sent :cancel :cancelled {:confirm "Gone."
                                            :requires [ready-gate]}]]
                #"disagree on \[:requires\].*recorded demand"))
  (testing "a confirmed action confirms from every origin"
    (flow-error [[:draft :send :sent {:undo :recall}]
                 [:sent :recall :draft {:undo :send}]
                 [:draft :cancel :cancelled {:confirm "Gone."}]
                 [:sent :cancel :cancelled {:one-way "Silent."}]]
                #"disagree on \[:one-way\]|confirms from some origins"))
  (testing "a row with no safety story is refused at the gate"
    (flow-error [[:draft :send :sent {}]
                 [:sent :recall :draft {:undo :send}]
                 [:draft :cancel :cancelled {:confirm "Gone."}]
                 [:sent :cancel :cancelled {:confirm "Gone."}]]
                #"declares no safety story"))
  (testing "unknown opts are refused, never silently dropped"
    (flow-error [[:draft :send :sent {:undo :recall :gaurds [ready-gate]}]
                 [:sent :recall :draft {:undo :send}]
                 [:draft :cancel :cancelled {:confirm "Gone."}]
                 [:sent :cancel :cancelled {:confirm "Gone."}]]
                #"unknown opt")))

;; ── the undo pointer is verified, then stripped ─────────────────────

(deftest undo-pointers-verify-against-the-graph
  (testing "a verified pointer stamps :reversible true and leaves no residue"
    (let [n (r/normalize-resource sugared)]
      (is (true? (get-in n [:actions :send :safety :reversible])))
      (is (not (contains? (get-in n [:actions :send]) :undo)))))
  (testing "a pointer to a missing action refuses"
    (flow-error [[:draft :send :sent {:undo :unsend}]
                 [:sent :recall :draft {:undo :send}]
                 [:draft :cancel :cancelled {:confirm "Gone."}]
                 [:sent :cancel :cancelled {:confirm "Gone."}]]
                #"not an action of\s+this kind"))
  (testing "a lying pointer — the named action does not depart from the destination"
    (flow-error [[:draft :send :sent {:undo :cancel}]
                 [:sent :recall :draft {:undo :send}]
                 [:draft :cancel :cancelled {:confirm "Gone."}]]
                #"does not depart from|not this\s+edge's reverse|must return\s+exactly"))
  (testing "a lying pointer — the named action lands somewhere else"
    (flow-error [[:draft :send :sent {:undo :sideways}]
                 [:sent :sideways :limbo {:one-way "A detour is not a reverse."}]
                 [:limbo :onward :draft {:one-way "Back around."}]
                 [:sent :recall :draft {:undo :send}]
                 [:draft :cancel :cancelled {:confirm "Gone."}]
                 [:sent :cancel :cancelled {:confirm "Gone."}]
                 [:limbo :cancel :cancelled {:confirm "Gone."}]]
                #"must return\s+exactly where it began"))
  (testing "bare :reversible true stays legal — no pointer demanded"
    (is (map? (r/normalize-resource split)))))

(deftest undo-works-on-inline-actions-too
  (let [inline (-> split
                   (assoc-in [:actions :send :safety]
                             {:idempotent true :confirm false})
                   (assoc-in [:actions :send :undo] :recall))]
    (is (= (r/normalize-resource split) (r/normalize-resource inline))
        ":undo on a plain :actions entry stamps the same :reversible true")))
