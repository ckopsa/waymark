(ns waymark10.test.conformance
  "Phase-5 conformance obligations over a booted engine — library fns
  the application suites call from their own deftests.

  The replay-history obligation (waymark9 design §3: \"the audit
  trail answers 'under which gate?' for free\"): every logged
  transition stamped with a law revision must be an action THAT
  revision's stored fingerprint declares, with matching from/to
  states. A nil stamp is the pre-law horizon (skipped); :create and
  :adopt are engine actions a machine never declares (allowed —
  :create additionally lands in declared create states the stored
  machine cannot see, waymark9's created_in).

  Phase 4b adds the ENVELOPE obligations: pure fns over one parsed
  wire document (wire/read-json output — keyword keys, so :meta
  holds :law_revision and action names arrive wire-keyed). Each
  returns a seq of violation STRINGS, empty on conformance, so a red
  run names every broken promise at once and the application suites
  (phase 8) fold them into their own deftests."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.machine :as machine]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.summary :as summary]))

(set! *warn-on-reflection* true)

(defn- wire-keys [v]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (into {} (map (fn [[k val]] [(if (keyword? k) (name k) k) val])) x)
       x))
   v))

(def ^:private engine-actions #{:create :adopt})

(defn replay-violations
  "Every logged transition with a non-nil law-revision, checked
  against its revision's stored fingerprint. Returns a vector of
  violation maps — empty is conformance."
  [eng]
  (let [st (:storage eng)
        laws (store/with-tx st
               (fn [tx]
                 (into {}
                       (map (fn [row]
                              [[(get-in row [:data :target_kind])
                                (get-in row [:data :revision])]
                               (wire-keys (get-in row [:data :fingerprint]))]))
                       (store/query-rows st tx :definition {} {:limit 1000}))))
        ts (store/with-tx st
             (fn [tx] (store/transitions st tx {} {:limit 100000})))]
    (into []
          (keep
           (fn [t]
             (let [rev (:law-revision t)
                   witness (select-keys t [:kind :resource-id :action
                                           :from-state :to-state
                                           :law-revision])]
               (when (and rev
                          (:from-state t)
                          (not (engine-actions (:action t))))
                 (if-some [fp' (get laws [(name (:kind t)) rev])]
                   (if-some [a (get-in fp' ["machine" "actions"
                                            (name (:action t))])]
                     (when-not (and (some #(= % (name (:from-state t)))
                                          (get a "from"))
                                    (= (name (:to-state t)) (get a "to")))
                       (assoc witness :violation :edge-not-in-law
                              :law {:from (get a "from") :to (get a "to")}))
                     (assoc witness :violation :action-not-in-law))
                   (assoc witness :violation :no-stored-law))))))
          ts)))

;; ── the envelope obligations (phase 4b) ─────────────────────────────

(def envelope-keys
  "The reserved envelope keys — every GET envelope has exactly these."
  #{:waymark :kind :self :state :summary :data :actions :unavailable
    :links :meta})

(defn wire-name
  "A declared keyword as it lands after read-json: kebab keys cross
  the wire snake (p/wire-key) and come back as keywords."
  [k]
  (keyword (p/wire-key k)))

(defn- where-of [env]
  (str (:kind env) "@" (:state env) " " (:self env)))

(defn envelope-violations
  "The envelope-shape obligation for one GET envelope: exactly the
  reserved keys, waymark \"10\", the declared kind/state tokens, an
  etag that matches the ETag header and embeds the version,
  law_revision when the engine is law-governed, and a non-empty
  summary within the 140-char budget."
  [env {:keys [kind state etag-header law?]}]
  (let [where (where-of env)
        m (:meta env)
        version (:version m)
        etag (:etag m)
        summary (:summary env)]
    (cond-> []
      (not= envelope-keys (set (keys env)))
      (conj (str where ": envelope keys " (vec (sort (keys env)))
                 " are not exactly the reserved " (vec (sort envelope-keys))))

      (not= "10" (:waymark env))
      (conj (str where ": waymark is " (pr-str (:waymark env)) ", not \"10\""))

      (and kind (not= (name kind) (:kind env)))
      (conj (str where ": kind is " (pr-str (:kind env))
                 ", not the declared " (name kind)))

      (and state (not= (name state) (:state env)))
      (conj (str where ": state is " (pr-str (:state env))
                 ", not the walked " (name state)))

      (not (pos-int? version))
      (conj (str where ": meta.version " (pr-str version) " is not a positive int"))

      (not (and (string? etag)
                (str/ends-with? (str etag) (str "-v" version "\""))))
      (conj (str where ": meta.etag " (pr-str etag)
                 " does not embed version " version))

      (and etag-header (not= etag-header etag))
      (conj (str where ": meta.etag " (pr-str etag)
                 " ≠ the ETag header " (pr-str etag-header)))

      (and law? (not (pos-int? (:law_revision m))))
      (conj (str where ": meta.law_revision " (pr-str (:law_revision m))
                 " is missing on a law-governed engine"))

      (or (not (string? summary)) (str/blank? summary))
      (conj (str where ": summary " (pr-str summary) " is empty"))

      (> (count (str summary)) 140)
      (conj (str where ": summary is " (count (str summary))
                 " chars, over the 140 budget")))))

(defn affordance-violations
  "The affordance-completeness obligation: keys(actions) ∪
  keys(unavailable) ⊆ the declared NON-BULK actions (+ :adopt when
  the caller expects the engine injection), the two maps are
  disjoint, and bulk actions appear in neither — render partitions
  every non-bulk action into available/unavailable/hidden and drops
  bulk actions from the envelope entirely (a bulk action is not a
  row affordance)."
  [rdef env & [{:keys [adopt-expected?]}]]
  (let [where (where-of env)
        declared (machine/actions-seq rdef)
        non-bulk (into #{} (comp (remove :bulk) (map (comp wire-name :name)))
                       declared)
        bulk (into #{} (comp (filter :bulk) (map (comp wire-name :name)))
                   declared)
        universe (cond-> non-bulk adopt-expected? (conj :adopt))
        advertised (set (keys (:actions env)))
        narrated (set (keys (:unavailable env)))]
    (cond-> []
      (seq (set/intersection advertised narrated))
      (conj (str where ": advertised AND narrated at once: "
                 (vec (sort (set/intersection advertised narrated)))))

      (seq (set/difference advertised universe))
      (conj (str where ": actions offers undeclared name(s) "
                 (vec (sort (set/difference advertised universe)))))

      (seq (set/difference narrated universe))
      (conj (str where ": unavailable narrates undeclared name(s) "
                 (vec (sort (set/difference narrated universe)))))

      (seq (set/intersection bulk (set/union advertised narrated)))
      (conj (str where ": bulk action(s) leaked into the envelope: "
                 (vec (sort (set/intersection bulk (set/union advertised narrated))))))

      (and adopt-expected? (not (contains? advertised :adopt)))
      (conj (str where ": a row lagging its kind's law does not advertise adopt")))))

(defn hidden-actions
  "The concealed remainder: declared non-bulk action names (original
  keywords) absent from both actions and unavailable. Render conceals
  only on a hide-flagged deny, so each of these, invoked directly,
  must keep the concealment on the wire."
  [rdef env]
  (let [advertised (set (keys (:actions env)))
        narrated (set (keys (:unavailable env)))]
    (into []
          (comp (remove :bulk)
                (map :name)
                (remove #(or (advertised (wire-name %)) (narrated (wire-name %)))))
          (machine/actions-seq rdef))))

(defn unavailable-violations
  "The unavailable-honesty advertisement half: every entry carries a
  non-empty reason string. (Enforcement equality — the 409 detail —
  needs the wire and lives with the caller.)"
  [env]
  (for [[aname entry] (:unavailable env)
        :when (or (not (string? (:reason entry)))
                  (str/blank? (:reason entry)))]
    (str (where-of env) " unavailable." (name aname)
         ": reason " (pr-str (:reason entry)) " is missing or blank")))

(defn prose-violations
  "The token-prose obligation over one envelope: no unresolved
  {placeholder} survives in the summary or any unavailable reason,
  no raw snake_case state token leaks into the summary, and when the
  declared template speaks {state} the summary carries the humanized
  label."
  [rdef env]
  (let [where (where-of env)
        summary (str (:summary env))
        template (str (:summary rdef))
        snake-states (into [] (comp (map name) (filter #(str/includes? % "_")))
                           (:states rdef))]
    (concat
     (when (str/includes? summary "{")
       [(str where ": summary " (pr-str summary) " holds an unresolved {placeholder}")])
     (for [[aname entry] (:unavailable env)
           :when (str/includes? (str (:reason entry)) "{")]
       (str where " unavailable." (name aname) ": reason "
            (pr-str (:reason entry)) " holds an unresolved {placeholder}"))
     (for [tok snake-states :when (str/includes? summary tok)]
       (str where ": summary " (pr-str summary)
            " leaks the raw state token " tok))
     (when (and (str/includes? template "{state}")
                (not (str/includes? summary (summary/state-label (:state env)))))
       [(str where ": the template speaks {state} but the summary "
             (pr-str summary) " does not carry the humanized "
             (pr-str (summary/state-label (:state env))))]))))

(defn input-schema-violations
  "The input-schema-honesty advertisement half: every actions entry
  with input carries a JSON schema object, and every folded enum is
  non-empty. (Enum members surviving dry_run is the caller's wire
  half.)"
  [env]
  (let [where (where-of env)]
    (concat
     (for [[aname entry] (:actions env)
           :when (contains? entry :input)
           :let [in (:input entry)]
           :when (not (and (map? in) (= "object" (:type in))))]
       (str where " actions." (name aname) ": input " (pr-str in)
            " is not a JSON schema object"))
     (for [[aname entry] (:actions env)
           [field prop] (get-in entry [:input :properties])
           :when (and (contains? prop :enum) (empty? (:enum prop)))]
       (str where " actions." (name aname) ": the folded enum for "
            (name field) " is empty — the form offers nothing")))))

(defn folded-enums
  "Every acceptance-folded enum an envelope advertises:
  [{:action wire-kw :field kw :enum [wire values]} …]."
  [env]
  (for [[aname entry] (:actions env)
        [field prop] (get-in entry [:input :properties])
        :when (seq (:enum prop))]
    {:action aname :field field :enum (:enum prop)}))

(defn problem-violations
  "The problem-shape obligation for one non-2xx response: RFC 9457
  media type, type/title/status in the body (status agreeing with
  the response line), field-keyed errors on 422, and acknowledge
  instructions whenever warnings ride a 409."
  [status ctype body & [{:keys [where]}]]
  (let [where (or where (str "problem " status))]
    (cond-> []
      (not= "application/problem+json" ctype)
      (conj (str where ": Content-Type " (pr-str ctype)
                 " is not application/problem+json"))

      (or (not (string? (:type body))) (str/blank? (:type body)))
      (conj (str where ": body carries no problem type"))

      (or (not (string? (:title body))) (str/blank? (:title body)))
      (conj (str where ": body carries no title"))

      (not= status (:status body))
      (conj (str where ": body status " (pr-str (:status body))
                 " ≠ response status " status))

      (and (= 422 status)
           (or (not (map? (:errors body))) (empty? (:errors body))))
      (conj (str where ": a 422 without field-keyed errors: "
                 (pr-str (:errors body))))

      (and (seq (:warnings body))
           (or (not= "Waymark-Acknowledge" (get-in body [:acknowledge :header]))
               (empty? (get-in body [:acknowledge :names]))))
      (conj (str where ": warnings ride without acknowledge header + names: "
                 (pr-str (:acknowledge body)))))))

(defn collection-item-violations
  "The collection-honesty shape half: an item is the envelope minus
  data — exactly the reserved keys without :data."
  [item]
  (when (not= (disj envelope-keys :data) (set (keys item)))
    [(str (where-of item) ": collection item keys "
          (vec (sort (keys item))) " are not the envelope minus data")]))
