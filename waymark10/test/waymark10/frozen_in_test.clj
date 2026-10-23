(ns waymark10.frozen-in-test
  "docs/spec-documents.md §6, build step 3: :frozen-in. An :edit door
  from a frozen state is a declaration error ([frozen-edited]); a
  handler that changes a frozen row's data is refused, naming the :owns
  child's create as the remedy with the ref filled; transitions still
  run; and an attachment a frozen row references is not deleted.
  Storage is the in-memory twin."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.checks :as checks]
            [waymark10.resource :as r]
            [waymark10.server.attachments :as attachments]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (clojure.lang ExceptionInfo)))

(def ^:private ana (t/principal {:id "ana" :type :human :display "Ana"}))

(def ^:private quiet
  {:idempotent true :reversible false :confirm false
   :one-way "The prior value is on the audit trail."})

(r/defhandler fr-note-reword [row inp _ctx]
  (assoc-in row [:data :body] (:body inp)))

(r/defhandler fr-note-sneak [row _inp _ctx]
  (assoc-in row [:data :body] "Rewritten after signing."))

(def ^:private reword-door
  {:input [:map [:body [:string {:min 1 :max 400}]]]
   :edit {:prefill [:body] :fence false
          :unfenced-reason "A test door; nobody else writes the note."}
   :safety quiet
   :handler fr-note-reword})

(defn- note-decl [extra-actions]
  {:kind :fr_note
   :states [:draft :signed :archived]
   :initial :draft
   :terminal #{:archived}
   :summary "{data.body} · {state}"
   :frozen-in [:signed]
   :schema [:map
            [:body [:string {:min 1 :max 400}]]
            [:consent {:kind :attachment} :waymark/ref]]
   :owns [{:kind :fr_note_addendum :via :note_id}]
   :actions
   (merge
    {:reword (assoc reword-door :from #{:draft} :to :draft)
     :sign {:from #{:draft} :to :signed :safety quiet}
     :sneak {:from #{:signed} :to :signed :safety quiet
             :handler fr-note-sneak}
     :archive {:from #{:signed} :to :archived :safety quiet}}
    extra-actions)})

(def fr-note (r/resource (note-decl {})))

(def fr-note-addendum
  (r/resource
   {:kind :fr_note_addendum
    :states [:filed :withdrawn]
    :initial :filed
    :terminal #{:withdrawn}
    :summary "{data.text} · {state}"
    :schema [:map
             [:note_id {:kind :fr_note} :waymark/ref]
             [:text [:string {:min 1 :max 400}]]]
    :filterable {:note_id #{:eq}}
    :actions {:withdraw {:from #{:filed} :to :withdrawn :safety quiet}}}))

(def fr-packet
  (r/resource
   {:kind :fr_packet
    :states [:draft :signed :archived]
    :initial :draft
    :terminal #{:archived}
    :summary "{data.title} · {state}"
    :frozen-in [:signed]
    :schema [:map
             [:title [:string {:min 1 :max 400}]]
             [:consents {:kind :attachment} [:vector :waymark/ref]]]
    :actions {:sign {:from #{:draft} :to :signed :safety quiet}
              :archive {:from #{:signed} :to :archived :safety quiet}}}))

(defn- engine []
  (inv/engine {:storage (memory/storage)
               :resources [attachments/attachment fr-note fr-note-addendum
                           fr-packet]}))

(defn- row-of [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/load-row st tx kind (str id) {})))))

(defn- refusal [f]
  (try (f) nil (catch ExceptionInfo e (ex-data e))))

(defn- signed-note! [eng]
  (let [file (:row (inv/create! eng :attachment
                                {:name "consent.pdf" :media_type "application/pdf"}
                                {:principal ana}))
        note (:row (inv/create! eng :fr_note
                                {:body "Seen today." :consent (str (:id file))}
                                {:principal ana}))]
    (inv/invoke! eng :fr_note (:id note) :sign {} {:principal ana})
    {:file file :note note}))

(deftest an-edit-door-from-a-frozen-state-is-a-declaration-error
  (let [e (try (checks/run-all
                (r/resource (note-decl {:amend (assoc reword-door
                                                      :from #{:signed}
                                                      :to :signed)})))
               nil
               (catch ExceptionInfo e e))]
    (is (= :frozen-edited (:check (ex-data e))))
    (is (str/includes? (str (ex-message e)) "[frozen-edited] edit door amend")))
  (testing "the same kind, its edit door only in draft, passes"
    (is (map? (checks/run-all fr-note)))))

(deftest a-handler-cannot-change-a-frozen-row
  (let [eng (engine)
        {:keys [note]} (signed-note! eng)
        d (refusal #(inv/invoke! eng :fr_note (:id note) :sneak {} {:principal ana}))]
    (is (= :frozen (:waymark10/problem d)))
    (is (= 409 (:status d)))
    (is (= "A signed fr note is not changed. Add an addendum instead." (:detail d)))
    (testing "the remedy is the owned child's create, the ref filled"
      (is (= ["fr_note_addendum.create"] (:remedies d)))
      (is (= [{:door "fr_note_addendum.create"
               :input {:note_id (str (:id note))}}]
             (:resolved-remedies d))))
    (testing "the data stood still"
      (is (= "Seen today."
             (get-in (inv/decode-row fr-note (row-of eng :fr_note (:id note)))
                     [:data :body]))))
    (testing "transitions still run"
      (inv/invoke! eng :fr_note (:id note) :archive {} {:principal ana})
      (is (= "archived" (name (:state (row-of eng :fr_note (:id note)))))))))

(deftest a-frozen-rows-attachment-is-not-deleted
  (let [eng (engine)
        {:keys [file note]} (signed-note! eng)
        d (refusal #(inv/invoke! eng :attachment (:id file) :delete {}
                                 {:principal ana}))]
    (is (= :guard-refused (:waymark10/problem d)))
    (is (str/includes? (str (:detail d)) "signed fr note"))
    (is (not= "deleted" (name (:state (row-of eng :attachment (:id file))))))
    (testing "once the row has left its frozen state, the file may go"
      (inv/invoke! eng :fr_note (:id note) :archive {} {:principal ana})
      (inv/invoke! eng :attachment (:id file) :delete {} {:principal ana})
      (is (= "deleted" (name (:state (row-of eng :attachment (:id file)))))))))

(deftest a-frozen-rows-listed-attachment-is-not-deleted
  (let [eng (engine)
        file! #(:row (inv/create! eng :attachment
                                  {:name % :media_type "application/pdf"}
                                  {:principal ana}))
        a (file! "first.pdf")
        b (file! "second.pdf")
        packet (:row (inv/create! eng :fr_packet
                                  {:title "Intake"
                                   :consents [(str (:id a)) (str (:id b))]}
                                  {:principal ana}))
        _ (inv/invoke! eng :fr_packet (:id packet) :sign {} {:principal ana})
        d (refusal #(inv/invoke! eng :attachment (:id b) :delete {}
                                 {:principal ana}))]
    (is (= :guard-refused (:waymark10/problem d)))
    (is (str/includes? (str (:detail d)) "signed fr packet"))
    (is (not= "deleted" (name (:state (row-of eng :attachment (:id b))))))
    (testing "once the row has left its frozen state, the file may go"
      (inv/invoke! eng :fr_packet (:id packet) :archive {} {:principal ana})
      (inv/invoke! eng :attachment (:id b) :delete {} {:principal ana})
      (is (= "deleted" (name (:state (row-of eng :attachment (:id b)))))))))
