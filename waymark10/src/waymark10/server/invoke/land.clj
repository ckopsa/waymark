(ns waymark10.server.invoke.land
  "Steps 11–15 of the transition, the write itself (moved out of
  waymark10.server.invoke, ticket bab6336f): `finish!` runs the
  handler and `land!` materializes, advances, logs and saves the row it
  answered; around them the outbox and the undo an effect door adds in
  its own transaction (docs/spec-integrations.md §§ 4–5), the rows a
  door's :makes opens, the leases a finished row lets go, a door's
  :writes wall and its chosen landing. `invoke/invoke-in-tx!` judges
  the door and calls `finish!`."
  (:require [clojure.string :as str]
            [waymark10.derived :as derived]
            [waymark10.effect :as effect]
            [waymark10.guards :as g]
            [waymark10.machine :as machine]
            [waymark10.makes :as makes]
            [waymark10.money :as money]
            [waymark10.server.decision :as decision]
            [waymark10.server.drafts :as drafts]
            [waymark10.server.invoke.births
             :refer [insert-quiet! sans-lines with-totals write-lines!]]
            [waymark10.server.invoke.labels
             :refer [encode-row labels-and-groups live-unique-clash!
                     log-summarizer refused-documents summary-of]]
            [waymark10.server.invoke.ledger
             :refer [house-zone own-balances posting-entries
                     refuse-closed-periods!]]
            [waymark10.server.invoke.purge :refer [purge! purge-says]]
            [waymark10.server.invoke.rows :refer [actor-map decode-row resources]]
            ;; a row whose work is over finishes the leases on it
            ;; (`finish-leases!`, docs/spec-leases.md §1)
            [waymark10.server.leases :as leases]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(defn- land!
  "Steps 12–15: tamper refusal, materialize, advance, append, save,
  idempotency store — of the row the handler answered."
  [engine tx rdef row defn inp ctx handled
   {:keys [digest overridden basis idempotency-key principal correlation-id
           record-key?]
    :or {record-key? true}}]
  (let [now (:now ctx)
        _ (when-some [facts (seq (derived/tampered rdef row handled now (:calendar ctx)))]
            (throw (p/derived-tampered (:name defn) (vec facts))))
        ;; an account's balance (ledger step 3c) moves only by
        ;; postings: a handler that writes one is refused, whoever
        ;; holds the door
        _ (when-some [moved (money/balances-moved (:accounts rdef) (:data row)
                                                  (:data handled))]
            (throw (p/schema-invalid (:name defn) moved)))
        ;; a row standing in a :frozen-in state keeps its data, whoever
        ;; holds the door (docs/spec-documents.md §6): a handler that
        ;; writes one is refused, and the correction is an :owns child.
        ;; Transitions still run; only a purge (§5) overrides the freeze
        _ (when (and (contains? (set (:frozen-in rdef)) (:state row))
                     (not (:purge defn))
                     (not= (:data row) (:data handled)))
            (throw (p/frozen-row (:name defn) (:kind rdef) (:id row) (:state row)
                                 (:owns rdef))))
        ;; a purge (docs/spec-documents.md §5): the personal fields
        ;; cleared and their files let go of, in this transaction
        [handled purged] (if (:purge defn)
                           (purge! engine tx rdef row handled ctx)
                           [handled nil])
        ;; a document field takes only the media types it accepts
        _ (when-some [bad (refused-documents engine tx rdef (:data handled)
                                             (:data row))]
            (throw (p/schema-invalid (:name defn) bad)))
        ;; ref labels + one-of clears (phase 8): engine passes, never
        ;; handler work — the handler sets the ref, the engine writes
        ;; the label and clears the other arm
        handled (update handled :data
                        #(labels-and-groups engine tx rdef % (:data row)))
        ;; a fact that reads (state) reads the state the door enters
        materialized (derived/materialize rdef (assoc handled :state (:to defn)) now (:calendar ctx))
        ;; the door's postings (ledger step 4): this row's own accounts
        ;; move here, before it is saved; post! moves the others and
        ;; writes every posting, in this transaction, under the log row
        entries (posting-entries engine tx rdef row defn inp ctx materialized)
        _ (refuse-closed-periods! engine tx rdef row defn entries ctx)
        own [(name (:kind rdef)) (str (:id row))]
        advanced (-> materialized
                     (update :data own-balances own entries)
                     (assoc :state (:to defn))
                     (update :version inc))
        advanced (assoc advanced :summary (summary-of rdef advanced (:now ctx)))
        _ (live-unique-clash! engine tx rdef advanced)
        record (store/append-transition!
                (:storage engine) tx
                {:kind (:kind rdef)
                 :resource-id (:id row)
                 :action (:name defn)
                 :from-state (:state row)
                 :to-state (:to defn)
                 ;; whose hand, under whose leash, on which model
                 ;; (actor-map: waymark-sfe, spec-seat.md R-9.5)
                 :actor (cond-> (actor-map principal (:id (:grant ctx)))
                          (:via-alias defn)
                          (assoc :via_alias (name (:via-alias defn))))
                 :law-revision (:law-revision row)
                 :input-digest digest
                 :inputs (when (:record defn) inp)
                 :acknowledged (not-empty (vec overridden))
                 ;; the decision record: the basis was built at
                 ;; judgment time and threaded here — finish! receives
                 ;; ctx, not guard-ctx, so nothing is re-evaluated and
                 ;; the record says what the guards actually saw
                 :judgment (decision/record (:law-revision row) basis
                                            overridden)
                 ;; tier 3: the document as this write leaves it
                 :after (decision/after-record rdef (:data advanced))
                 :correlation-id correlation-id
                 :idempotency-key idempotency-key
                 :summary (cond-> (or (when-some [says (:log-says defn)]
                                        (some-> (says advanced inp) str not-empty))
                                      (:summary advanced))
                            purged (str (purge-says purged)))})
        ;; …and the row's whole log with it, this line included (§5,
        ;; 4b): each value of a cleared field reads "‹purged›" in old
        ;; inputs, retained judgments and retained documents
        _ (when purged
            (store/redact-log! (:storage engine) tx (:kind rdef) (:id row)
                               (:cleared purged)
                               (log-summarizer rdef (:now ctx))))
        saved (store/save-row! (:storage engine) tx (:kind rdef)
                               (encode-row rdef (dissoc advanced :summary))
                               (:version row))]
    (when (seq entries)
      ((requiring-resolve 'waymark10.server.postings/post!)
       engine tx {:kind (:kind rdef) :id (:id row) :action (:name defn)
                  ;; the engine's clock, not the JVM's: the wall's window
                  ;; reads effective_at against that same clock
                  :log-id (:id record) :zone (house-zone ctx) :at (:now ctx)}
       entries {:settled own}))
    ;; the effort landed; its draft has served its purpose — consumed
    ;; in the same commit as the write it composed (phase 7)
    (when (get-in defn [:edit :draft])
      (store/delete-draft! (:storage engine) tx (:kind rdef) (:id row)
                           (:name defn) (drafts/audience-of defn principal)))
    ;; the record is the CALL's: a fan-out item (bulk!/batch!/the job
    ;; worker) rides its call's key with :record-key? false — stamped
    ;; on the transition above, so the log says which call it came
    ;; from, and never stored here, so the whole-call report stays
    ;; the one record under that key (waymark-pywy.5)
    (when (and idempotency-key record-key?)
      (store/idempotency-store!
       (:storage engine) tx idempotency-key (:kind rdef) (:name defn) digest
       200 (if-some [render-fn (:render-fn engine)]
             ;; the render seam (phase 3): replay serves the same
             ;; envelope bytes the first execution answered with
             (render-fn rdef (decode-row rdef saved)
                        {:read (:read ctx) :find (:find ctx)})
             (wire/write-json {:id (:id saved) :state (name (:state advanced))
                               :version (:version advanced)
                               :summary (:summary advanced)}))
       "application/waymark+json"))
    ;; writes the handler made through ctx :invoke ride out with the
    ;; result — after-write! owes each its post-commit pass
    ;; …and so do the follow-ups it queued through ctx :follow-up, which
    ;; after-write! makes once this transaction has committed
    (let [inner (some-> (:inner-sink ctx) deref not-empty)
          follow (some-> (:follow-sink ctx) deref not-empty)]
      (cond-> {:row (decode-row rdef saved) :transition record}
        inner (assoc :inner-writes inner)
        follow (assoc :follow-ups follow)))))

;; ── the outbox (docs/spec-integrations.md § 4, build step 5) ─────────

(def effects-actor
  "The system actor that lands an effect's answer on the row that asked
  for it, through the door's hidden `<door>_landed` and `<door>_failed`."
  (t/principal {:id "waymark10-effects" :type :system :display "Effects"}))

(defn- effect-ask
  "The call an effect door asks for, over `row` (effect/ask), with the
  `effect_call` already kept under its power and key as :found, or nil.
  A key that reads nothing on this row refuses the door: without one
  the call cannot be made once."
  [engine tx rdef row defn inp now]
  (let [call (effect/ask (:effect defn) row inp now)
        crd (or (get (resources engine) :effect_call)
                (throw (t/definition-error
                        (str (name (:kind rdef)) "/" (name (:name defn))
                             " declares an :effect, and this engine enrolls no effect_call kind"))))]
    (when (str/blank? (:key call))
      (throw (p/problem :effect-no-key 409 "No idempotency key"
                        {:detail (str (name (:name defn))
                                      "'s :idempotency-key reads nothing on this row, so its call cannot be made once.")})))
    (assoc call :found
           (some->> (store/query-rows (:storage engine) tx :effect_call
                                      {:power (:power call) :idempotency_key (:key call)}
                                      {:limit 1})
                    first
                    (decode-row crd)))))

(defn effect-probe
  "What an effect door's rehearsal says of its call (§ 7): the power,
  the key, and that it would call once — or write a `done` answer
  again. It calls nothing."
  [engine tx rdef row defn inp now]
  (let [{:keys [found] :as call} (effect-ask engine tx rdef row defn inp now)]
    {:power (:power call)
     :key (:key call)
     :says (effect/says call (= :done (:state found)))}))

(defn- outbox!
  "An effect door's outbox, in the door's own transaction (§ 4): one
  `pending` effect_call for the call it asks for, unless its power and
  key already have one. `effect_call` is unique on (power, key). A
  `done` call is reused: nothing is called, and its answer is written
  again through `<door>_landed` once this transaction commits; a
  `failed` one lands its failure again the same way, and a pending one
  lands on its own."
  [engine tx rdef row defn inp ctx {:keys [principal correlation-id]}]
  (let [{:keys [found] :as call} (effect-ask engine tx rdef row defn inp (:now ctx))
        aname (:name defn)
        again (fn [door input]
                (swap! (:follow-sink ctx) conj
                       {:kind (:kind rdef) :id (str (:id row)) :action door
                        :input (assoc input :effect_call (str (:id found)))
                        :opts {:principal effects-actor
                               :correlation-id correlation-id}}))]
    (cond
      (nil? found)
      (insert-quiet! engine tx :effect_call
                     (cond-> {:power (:power call)
                              :idempotency_key (:key call)
                              :target_kind (name (:kind rdef))
                              :target (str (:id row))
                              :door (name aname)
                              :input (wire/write-json (:input call))
                              :on_behalf_of (str (or (:id principal) "anonymous"))}
                       (:why call) (assoc :why (:why call)))
                     {:principal principal})

      (= :done (:state found))
      (again (effect/landed-door aname)
             (let [a (some-> (get-in found [:data :answer]) wire/read-json)]
               (if (map? a) {:answer a} {})))

      (= :failed (:state found))
      (again (effect/failed-door aname)
             {:reason (or (get-in found [:data :reason]) "The call failed.")}))))

(defn- unwind!
  "The compensations a door that `:unwinds` adds in its own transaction
  (§ 5). For each named effect whose call on this row is `done` and
  whose `:when` holds over the row as the door leaves it: one `pending`
  effect_call for its `:compensate` call, a door's or a power's, keyed `undo:<the
  original key>`, newest effect first, and none a second time. A
  compensation that fails lands that door's own `:fails-to`; the door
  that unwound is not rolled back."
  [engine tx rdef row defn ctx {:keys [principal]}]
  (when-some [crd (get (resources engine) :effect_call)]
    (let [st (:storage engine)
          now (:now ctx)
          done (mapv #(decode-row crd %)
                     (store/query-rows st tx :effect_call
                                       {:target (str (:id row)) :state :done} {:limit 500}))
          undone (distinct
                  (for [u (:unwinds defn)
                        :when (effect/unwinds? u row now)
                        c done
                        :when (and (= (name (:kind rdef)) (str (get-in c [:data :target_kind])))
                                   (= (name (:effect u)) (str (get-in c [:data :door]))))]
                    c))]
      (doseq [c (sort-by :created-at #(compare %2 %1) undone)
              :let [[door eff] (effect/compensation
                                rdef (keyword (str (get-in c [:data :door]))))
                    call (effect/ask eff row {} now)
                    k (effect/undo-key (get-in c [:data :idempotency_key]))]
              :when (empty? (store/query-rows st tx :effect_call
                                              {:power (:power call) :idempotency_key k}
                                              {:limit 1}))]
        (insert-quiet! engine tx :effect_call
                       (cond-> {:power (:power call)
                                :idempotency_key k
                                :target_kind (name (:kind rdef))
                                :target (str (:id row))
                                :door (name door)
                                :input (wire/write-json (:input call))
                                :on_behalf_of (str (or (:id principal) "anonymous"))}
                         (:why call) (assoc :why (:why call)))
                       {:principal principal})))))

(defn- make-rows!
  "The rows a landing door's :makes opens (ticket 442ca14b): one create
  per entry through ctx :create — this transaction and the full create
  algorithm, so the made kind's guards judge it and a refusal refuses
  the move. Each input reads `row` as the door left it."
  [engine rdef row defn inp ctx]
  (when-some [create (:create ctx)]
    (doseq [m (:makes defn)]
      (create (:kind m)
              (makes/input-of m (:kind rdef) (get (resources engine) (:kind m))
                              {:data (:data row) :input inp :self (str (:id row))
                               :state (:state row) :now (:now ctx)
                               :read (:read ctx) :calendar (:calendar ctx)})))))

(defn- refuse-stray-writes!
  "[writes] (ticket 7e60b100): a door that declares :writes changes no
  other data key. The row its handler answers is diffed against the row
  it was handed, and a key outside the set refuses the move, named. A
  door without :writes is not asked."
  [rdef row defn handled ctx]
  (when-some [ws (:writes defn)]
    (let [before (:data row)
          after (:data handled)
          allowed (into #{} (map name) ws)
          stray (->> (concat (keys before) (keys after))
                     (filter #(not= (get before %) (get after %)))
                     (map name)
                     (remove allowed)
                     distinct sort vec)]
      (when (seq stray)
        (throw (p/guard-refused
                (:name defn) (:state row)
                (str "This door writes only " (str/join ", " (sort allowed))
                     ", and it changed " (str/join ", " stray) ".")
                {:guard :writes
                 :remedies []
                 :evidence {:stray stray}}
                {:kind (:kind rdef) :id (:id row)
                 :summary (summary-of rdef row (:now ctx))}))))))

(defn- finish-leases!
  "A row whose work is now over (`machine/work-over?`) is held by no
  one: each `held` lease on it ends through the lease's own `finish`,
  in this transaction, under the leases actor (docs/spec-leases.md §1).
  An engine that serves no lease, a ctx with no inner-write hook, and
  a lease itself finish nothing. → true when one was finished."
  [engine rdef row ctx]
  (let [find' (:find ctx)
        invoke' (:invoke ctx)]
    (when (and find' invoke'
               (not= :lease (:kind rdef))
               (get (resources engine) :lease)
               (machine/work-over? rdef row))
      (let [held (find' :lease {:subject_kind (name (:kind rdef))
                                :subject_id (str (:id row))
                                :state "held"}
                        {:limit 50})]
        (doseq [l held]
          (invoke' :lease (:id l) :finish {} {:as leases/leaser}))
        (boolean (seq held))))))

(defn- chosen-landing
  "A door with several landings (`:to #{:a :b}`) lands where its
  handler says: the handler answers the row with `:state` set to one
  member of the set, and the door resolves to it here, so the commit
  and the log's :to-state read the state chosen. A choice outside the
  set, or none (the row's own state, when that is no member), is the
  declaration's fault, not the caller's. A rehearsal fires no handler
  (§23's iron rule), so it answers the set itself. Any other door is
  answered as it stands."
  [defn row handled]
  (if-not (machine/several? defn)
    defn
    (let [chosen (:state handled)]
      (when-not (contains? (:to defn) chosen)
        (throw (t/definition-error
                (str "handler for " (name (:name defn)) " chose "
                     (if (= chosen (:state row)) "no landing" (pr-str chosen))
                     "; its door lands in one of "
                     (str/join ", " (sort (map name (:to defn))))))))
      (assoc defn :to chosen))))

(defn unchanged
  "A handler's answer that says it moved nothing: `row` itself, marked.
  When the door's state is the row's own, invoke lands no transition,
  no version bump and no history entry, and answers the row with its
  latest transition, as a natural replay does (:replayed? :unchanged).
  It is for a door that must ASK on every call and writes only when
  the answer moved (mcp_server discover asks the server each time)."
  [row]
  (vary-meta row assoc :waymark10.server.invoke/unchanged true))

(defn answer
  "A handler's row with a value for its caller alone: `m` rides the
  invoke result as :answer, the HTTP door answers it once beside the
  envelope (router's \"answer\"), and it is written nowhere — not the
  row, not the log, not the idempotent replay's stored bytes. It is for
  a door that mints a secret its row keeps only the hash of (members
  :offer_reentry, ticket 2c9d2586)."
  [row m]
  (vary-meta row assoc :waymark10.server.invoke/answer m))

(defn finish!
  "Step 11, the handler, then `land!` — unless the handler answered
  `unchanged` on an in-state door, which writes nothing. A door that
  took `lines` replaces its row's lines in this transaction. A door
  with several landings lands in the one its handler chose
  (chosen-landing). The marks `invoke/unchanged` and `invoke/answer`
  leave are the parent namespace's keywords."
  [engine tx rdef row defn inp ctx opts]
  (let [handled (if-some [h (:handler defn)]
                  (let [out (h row (sans-lines rdef inp) ctx)]
                    (when-not (map? out)
                      (throw (t/definition-error
                              (str "handler for " (name (:name defn))
                                   " must return the row"))))
                    out)
                  row)
        defn (chosen-landing defn row handled)
        _ (refuse-stray-writes! rdef row defn handled ctx)
        ;; its totals (ledger step 6c) are computed here, in this
        ;; transaction, from the lines the door writes
        handled (cond-> handled
                  (and (:lines rdef) (map? inp) (contains? inp :lines))
                  (update :data with-totals rdef (:lines inp)))
        ;; :stamp (docs/spec-teams.md §4): who acted, written by the
        ;; engine in the door's own write — no input carries it
        handled (cond-> handled
                  (seq (:stamp defn))
                  (update :data merge (g/stamped (:stamp defn) (:principal opts))))]
    (if (and (:waymark10.server.invoke/unchanged (meta handled)) (= (:state row) (:to defn)))
      (let [[latest] (store/transitions (:storage engine) tx
                                        {:kind (:kind rdef) :resource-id (:id row)}
                                        {:newest-first true :limit 1})]
        {:row row :transition latest :replayed? :unchanged})
      (do
        ;; the outbox (§ 4): the call rides this transaction, so the
        ;; transition and its effect_call commit or roll back together
        (when (:effect defn)
          (outbox! engine tx rdef (assoc row :data (:data handled)) defn inp ctx opts))
        ;; the undo (§ 5) rides it too; an effect door unwinds when it lands
        (when (and (:unwinds defn) (not (:effect defn)))
          (unwind! engine tx rdef (assoc row :data (:data handled)) defn ctx opts))
        (let [res (cond-> (land! engine tx rdef row defn inp ctx handled opts)
                    (:waymark10.server.invoke/answer (meta handled))
                    (assoc :answer (:waymark10.server.invoke/answer (meta handled))))]
          (when (and (:lines rdef) (map? inp) (contains? inp :lines))
            (write-lines! engine tx rdef (:id row) (:lines inp)
                          (:principal opts) true))
          (when (seq (:makes defn))
            (make-rows! engine rdef (:row res) defn inp ctx))
          (if (or (finish-leases! engine rdef (:row res) ctx)
                  (seq (:makes defn)))
            ;; the made rows and the finished leases ride out beside
            ;; the handler's own inner writes, owed the same
            ;; post-commit pass
            (assoc res :inner-writes @(:inner-sink ctx))
            res))))))
