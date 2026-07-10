(ns waymark10.server.render
  "The envelope projection: one decoded row + one declaration + one
  probe ctx → the v10 wire document. Pure in (rdef, row, ctx) — no
  storage reads; guards probe with inp nil, mode :probe.

  Envelope partition per action:
  - state ∈ :from, probe allows (or pends)     → actions entry
    (a :warning deny does not block — the invoke-side 409
    acknowledge protocol owns it)
  - state ∈ :from, :refuse deny                → unavailable entry
  - state ∈ :from, deny with :hide             → omitted (concealed)
  - state ∉ :from                              → unavailable with the
    in-states narration — unless a hide guard denies (concealed)."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.machine :as machine]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.judgment :as judgment]
            [waymark10.server.problems :as p]
            [waymark10.summary :as summary]
            [waymark10.types :as t])
  (:import (java.time LocalDate)))

(set! *warn-on-reflection* true)

;; ── the probe ───────────────────────────────────────────────────────

(defn- probe-transition
  "→ {:status :available|:unavailable|:hidden (:deny :denier)}."
  [defn' row ctx]
  (loop [gs (:guards defn')]
    (if-some [guard (first gs)]
      (let [[v d] (g/evaluate guard row nil ctx)]
        (if (and (t/deny? v) (not= :warning (:severity d)))
          (if (:hide d)
            {:status :hidden}
            {:status :unavailable :deny v :denier d})
          (recur (rest gs))))
      {:status :available})))

(defn- probe-hidden-only?
  "Concealment holds out-of-state too: a hide-flagged deny on probe
  means the action is omitted, never narrated."
  [defn' row ctx]
  (boolean
   (some (fn [guard]
           (let [[v d] (g/evaluate guard row nil ctx)]
             (and (t/deny? v) (:hide d))))
         (:guards defn'))))

;; ── acceptance-set folding ──────────────────────────────────────────

(defn- encode-enum [v]
  (if (instance? LocalDate v) (str v) v))

(defn- fold-acceptance
  "Each leaf guard's single-field acceptance set becomes that field's
  enum in the advertised input schema — the form never offers a value
  the server already knows it will refuse. Sets sharing a field
  intersect. Relations: skipped in phase 3 (TODO: tuple folding lands
  with the parts namespace)."
  [js defn' row ctx]
  (reduce
   (fn [js leaf]
     (if (or (:relation leaf)
             (nil? (:accepts leaf))
             (not= 1 (count (:judges leaf))))
       js
       (if-some [values (g/admitted leaf row ctx)]
         (let [field (first (:judges leaf))
               values (mapv encode-enum values)]
           (if (get-in js [:properties field])
             (update-in js [:properties field]
                        (fn [prop]
                          (assoc prop :enum
                                 (if-some [prev (:enum prop)]
                                   (filterv (set values) prev)
                                   values))))
             js))
         js)))
   js
   (mapcat g/iter-leaves (:guards defn'))))

;; ── entries ─────────────────────────────────────────────────────────

(defn- action-entry [defn' rdef self row ctx]
  (let [{:keys [to safety display]} defn']
    (cond-> {:method "POST"
             :href (str self "/-/" (name (:name defn')))}
      (:input defn')
      (assoc :input (fold-acceptance (schema/json-schema (:input defn'))
                                     defn' row ctx))
      true
      (assoc :effect (cond-> {:to (name to)}
                       (contains? (:terminal rdef) to) (assoc :terminal true)))
      true
      (assoc :safety (cond-> {:idempotent (boolean (:idempotent safety))
                              :reversible (boolean (:reversible safety))
                              :confirm (boolean (:confirm safety))}
                       (:fence safety) (assoc :fence true)))
      (seq display)
      (assoc :display display))))

(defn- unavailable-entry [denier deny row]
  (into {}
        (filter (comp some? val))
        {:reason (g/render-reason denier deny row)
         :remedies (not-empty (vec (:remedies denier)))
         :becomes-available (g/becomes-available denier deny row)}))

(defn- out-of-state-entry [defn' state]
  (let [states (sort (:from defn'))]
    {:reason (str "Available in state(s) " (str/join ", " (map name states))
                  "; the resource is " (name state) ".")
     :becomes-available {:in-states (mapv name states)}}))

;; ── the envelope ────────────────────────────────────────────────────

(defn envelope
  "The v10 wire document (snake string keys, JSON-ready values) for a
  DECODED row. ctx-opts: :principal (default anonymous), :now
  (Instant, required for time-reading guards), :services."
  [rdef row {:keys [principal now services]}]
  (let [ctx (t/ctx {:principal (or principal t/anonymous)
                    :now now :services services :mode :probe})
        self (str "/api/" (:plural rdef) "/" (:id row))
        state (:state row)
        {:keys [actions unavailable]}
        (reduce
         (fn [acc defn']
           (if (contains? (:from defn') state)
             (let [{:keys [status deny denier]} (probe-transition defn' row ctx)]
               (case status
                 :available (assoc-in acc [:actions (:name defn')]
                                      (action-entry defn' rdef self row ctx))
                 :unavailable (assoc-in acc [:unavailable (:name defn')]
                                        (unavailable-entry denier deny row))
                 :hidden acc))
             (if (probe-hidden-only? defn' row ctx)
               acc
               (assoc-in acc [:unavailable (:name defn')]
                         (out-of-state-entry defn' state)))))
         {:actions {} :unavailable {}}
         ;; the row's law resolves the probe's guards too (phase 5):
         ;; advertisement equals enforcement, per row
         (map #(judgment/resolve-action rdef % (:law-revision row))
              (remove :bulk (machine/actions-seq rdef))))
        ;; the engine-injected adopt (phase 5): a row living under an
        ;; older law than the kind's current one affords stepping
        ;; forward — unless the machine declares its own :adopt
        actions (if (and (:current-law rdef)
                         (:law-revision row)
                         (< (:law-revision row) (:current-law rdef))
                         (not (contains? (:terminal rdef) state))
                         (not (contains? (:actions rdef) :adopt)))
                  (assoc actions :adopt
                         {:method "POST"
                          :href (str self "/-/adopt")
                          :effect {:to (name state)}
                          :safety {:idempotent true :reversible false
                                   :confirm false}
                          :display {:label "Adopt the current law"}})
                  actions)]
    (p/wire-value
     {:waymark "10"
      :kind (name (:kind rdef))
      :self self
      :state (name state)
      :summary (summary/render (:summary rdef) (assoc row :kind (:kind rdef)))
      :data (schema/encode (:schema rdef) (:data row))
      :actions actions
      :unavailable unavailable
      :links {}
      :meta (cond-> {:version (:version row)
                     :etag (inv/etag (:kind rdef) (:id row) (:version row))}
              (:updated-at row) (assoc :updated-at (str (:updated-at row)))
              (:law-revision row) (assoc :law-revision (:law-revision row)))})))

(defn envelope-summary
  "Depth summary: the full envelope minus data — state, summary,
  actions, unavailable and meta stay (collection items)."
  [rdef row ctx-opts]
  (dissoc (envelope rdef row ctx-opts) "data"))
