(ns waymark10.types
  "The universal base: verdicts, safety, principals, the invocation
  context. Everything is a plain map with a constructor that validates
  at declaration time — definition errors are import-time errors.

  Ported from waymark9 core/types.py."
  (:require [clojure.string :as str]))

(defn definition-error
  "A declaration refused at load time. ex-info tagged for the
  defresource boundary."
  [msg & [data]]
  (ex-info (str "definition error: " msg)
           (merge {:waymark10/definition-error true} data)))

(defn acknowledged!
  "The written escape hatch: a non-blank sentence acknowledging a
  usability cost (one-way doors, open judged fields). Returns the
  trimmed reason."
  [reason what]
  (when-not (and (string? reason) (not (str/blank? reason)))
    (throw (definition-error
            (str what " demands a written acknowledgement — a non-blank reason sentence"))))
  reason)

;; ── verdicts ────────────────────────────────────────────────────────

(defn allow
  ([] {:verdict :allow :pending-input false})
  ([{:keys [pending-input]}] {:verdict :allow :pending-input (boolean pending-input)}))

(defn deny
  ([] {:verdict :deny})
  ([{:keys [vars errors retry-at]}]
   (cond-> {:verdict :deny}
     vars (assoc :vars vars)
     errors (assoc :errors errors)
     retry-at (assoc :retry-at retry-at))))

(defn allow? [v] (= :allow (:verdict v)))
(defn deny? [v] (= :deny (:verdict v)))
(defn pending-input? [v] (boolean (:pending-input v)))

;; ── safety ──────────────────────────────────────────────────────────

(defn safety
  "Safety is declared, never inferred: :idempotent, :reversible and
  :confirm must all be present booleans. :confirm demands a
  :consequence sentence (no blind confirms); :one-way (an acknowledged
  irreversible door) excludes :reversible and :confirm.

  Batch H: :consequence may be a {from-state sentence} map — an action
  reachable from several states may cost something different from each
  (cancelling a draft discards nothing; cancelling a completed
  transaction is a different sentence). Every origin's sentence must
  be written — a state without one is a blind confirm from that state.
  The render layer selects by the row's CURRENT state; the fingerprint
  is untouched (consequence sentences are advertisement, never law)."
  [{:keys [idempotent reversible confirm fence consequence one-way]
    :as s}]
  (doseq [k [:idempotent :reversible :confirm]]
    (when-not (boolean? (get s k))
      (throw (definition-error
              (str "safety declares all of :idempotent/:reversible/:confirm explicitly; "
                   k " is missing or not a boolean")))))
  (when (map? consequence)
    (when (empty? consequence)
      (throw (definition-error
              "a per-origin :consequence map with no entries says nothing — write the sentences")))
    (doseq [[from sentence] consequence]
      (when-not (keyword? from)
        (throw (definition-error
                (str "per-origin :consequence keys are from-state keywords, got "
                     (pr-str from)))))
      (when (or (not (string? sentence)) (str/blank? sentence))
        (throw (definition-error
                (str "per-origin :consequence for state " from
                     " is blank — a blind confirm from that state"))))))
  (when (and confirm (or (nil? consequence)
                         (and (string? consequence) (str/blank? consequence))))
    (throw (definition-error
            "confirm=true without a :consequence is a blind confirm — say what happens")))
  (when (and one-way (or reversible confirm))
    (throw (definition-error
            ":one-way acknowledges an irreversible, unconfirmed door; it excludes :reversible and :confirm")))
  (when one-way (acknowledged! one-way ":one-way"))
  (cond-> {:idempotent idempotent
           :reversible reversible
           :confirm confirm
           :fence (boolean fence)}
    consequence (assoc :consequence consequence)
    one-way (assoc :one-way one-way)))

;; ── principals and context ──────────────────────────────────────────

(def actor-types #{:human :agent :system})

(defn principal
  [{:keys [id type roles display locale]
    :or {type :human roles #{} display "" locale "en"}}]
  (when-not (contains? actor-types type)
    (throw (definition-error (str "actor type " type " is not one of " actor-types))))
  {:id id :type type :roles (set roles) :display display :locale locale})

(def anonymous (principal {:id "anonymous"}))

(defn ctx
  "The invocation context: :principal, :now (Instant), :services,
  :locale, :mode (:probe | :invoke | :dry-run), :within (the
  {:kind :action} of the write this ctx was opened inside of by a
  ctx :invoke / :create door, nil at the wire — waymark-jfv.20), plus
  engine-injected hooks in later phases (:actor-of, :last-transition,
  :rate, :read, :find, :invoke)."
  [{:keys [principal now services locale mode correlation-id]
    :or {locale "en" mode :invoke}
    :as extra}]
  (when-not (#{:probe :invoke :dry-run} mode)
    (throw (definition-error (str "ctx mode " mode " is not probe/invoke/dry-run"))))
  (merge extra
         {:principal principal :now now :services services
          :locale locale :mode mode :correlation-id correlation-id}))
