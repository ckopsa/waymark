(ns waymark10.server.definitions
  "The definition resource and the law lifecycle (waymark9
  server/definitions.py, made synchronous): the law is inside the
  envelope. One row per revision per target kind, whose data is the
  canonical fingerprint — the record and the anchor, never the
  source. Git still holds the text; this holds \"which text was live,
  when, and what it meant\".

  The boot IS the revise: boot-revise! fingerprints every resident
  application kind (never :definition itself — the law of the law is
  a named punt, with waymark9's __registry__ row), compares to the
  stored current row, and deploys per the engine's :deploy-mode.
  :promote (the default) is the single-breath revise: mint N+1
  current, retire N. :propose holds a data-law diff at proposed —
  the boot keeps serving the current law by installing its stored
  fingerprint in the rdef's :judgment-laws slot, so every row stamped
  with it is judged (and rendered) from the store while the resident
  code — which IS the proposed law — waits for promote.

  The lifecycle EFFECTS (waymark9's DefinitionLifecycle) run in the
  engine seam: invoke!'s after-write! hook calls `lifecycle` after a
  committed, non-replayed write, in the same call but after the
  write's transaction — pilot installs the population and restamps
  its where-matches; promote flips the served law, restamps immediate
  kinds, grandfathers-or-supersedes the prior revision; withdraw
  returns piloted rows to the current law; an adopt anywhere runs the
  supersede-when-empty sweep.

  Recorded deviations and named punts (each a sentence, per the
  discipline):
  - Boot writes go through create!/invoke! (the law rides the same
    envelope/log machinery — dogfood); a held revision is born
    :proposed via :on-create, logged as :create (waymark9 spelled it
    `propose`/`revise`; v10 keeps one create action name).
  - Withdraw semantics, honestly: after a withdraw the resident code
    STILL expresses the withdrawn law — there is no code rollback —
    so :judgment-laws KEEPS serving the current law's stored trees
    for every row until a boot with reverted code or a promote
    (waymark9 has the same property, recorded on its withdraw).
  - Lifecycle restamps (pilot where=, promote's immediate adopt,
    withdraw's return) are one bulk UPDATE with no per-row log —
    waymark9 restamped row-by-row; v10 records the deviation and
    keeps the per-row :adopt action for the logged path.
  - supersede-when-empty counts EVERY stamped row, terminal included
    (waymark9 counted non-terminal survivors): a law lives while
    anything cites it; adopting a closed row is the maintenance act
    that retires it.
  - Populations are not validated against the query grammar
    (waymark9's check_population) — the restamp accepts any stored
    field; the grammar check is a named punt.
  - No unique (target_kind, revision) constraint yet: two racing
    boots could double-mint; single-process deploys until the
    storage grows declared uniqueness.
  - Derived-law overlay, blast-radius meter (measure), settle/backfill
    markers, renames: named punts, waiting on their v10 features.
  - The proposal-is-resident guard (waymark9's _resident_only) is
    unported: after a reboot-during-hold the re-adopted proposal is
    promotable by construction (its hash matched the resident code);
    a stale piloted revision is parameter-served but also promotable —
    the residency refusal is a named punt."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.fingerprint :as fp]
            [waymark10.guards :as g]
            [waymark10.resource :as r :refer [defresource defhandler]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def deploy
  "The deploy actor: the process identity that revises the law at
  boot and drives the bookkeeping transitions."
  (t/principal {:id "waymark10-deploy" :type :system :display "Deploy"}))

;; ── guards ──────────────────────────────────────────────────────────

(defn- system? [ctx]
  (= :system (get-in ctx [:principal :type])))

(def ^:private deploy-only
  (g/guard {:name :deploy-writes-the-law
            :explain "The law is revised by the deploy at boot, never over the wire."
            :reads [:principal]
            :check (fn [_ _ ctx] (if (system? ctx) (t/allow) (t/deny)))}))

(def ^:private deploy-only-hidden
  (g/guard {:name :deploy-writes-the-law
            :explain "The law is revised by the deploy at boot, never over the wire."
            :reads [:principal]
            :hide true
            :check (fn [_ _ ctx] (if (system? ctx) (t/allow) (t/deny)))}))

(def ^:private population-shape
  (g/guard {:name :population-shape
            :explain "A population is where={…} or after=true — exactly one."
            :needs-input true
            :check (fn [_ inp _]
                     (let [w (:where inp)]
                       (if (not= (boolean (and (map? w) (seq w)))
                                 (boolean (:after inp)))
                         (t/allow)
                         (t/deny))))}))

(def ^:private data-law-only
  (g/expr {:name :data-law-pilots
           :when '(= (data :diff_class) "data_law")
           :explain "Only a data-law diff can pilot per-population — code does not interpret per-row; promote totally instead."}))

;; ── the resource ────────────────────────────────────────────────────

(defhandler record-population [row inp _ctx]
  (assoc-in row [:data :population]
            (if (:after inp) {:after true} {:where (:where inp)})))

(defresource definition
  {:kind :definition
   :plural "definitions"
   :states [:proposed :piloted :current :grandfathered :superseded :withdrawn]
   :initial :current
   :terminal #{:superseded :withdrawn}
   ;; proposed is entered at creation (a held propose-mode boot);
   ;; piloted and withdrawn are reachable only through it
   :allow-dead #{:proposed :piloted :withdrawn}
   :nav :secondary
   :summary "Law of {data.target_kind} · revision {data.revision} · {state}"
   :schema [:map
            [:target_kind [:string {:min 1 :max 64}]]
            [:revision [:int {:min 1}]]
            [:fingerprint_hash [:string {:min 64 :max 64}]]
            ;; the canonical projection, verbatim — the record IS the law
            [:fingerprint :any]
            [:diff {:optional true} :any]
            [:diff_class [:enum "initial" "data_law" "code_or_shape"]]
            [:change_summary {:optional true} [:maybe [:string {:max 120}]]]
            [:population {:optional true} :any]
            [:deploy_note {:optional true} [:maybe [:string {:max 120}]]]
            [:held {:optional true} [:maybe :boolean]]]
   :filterable {:state #{:eq :in}
                :target_kind #{:eq :in}}
   :create-guards [deploy-only]
   ;; a held proposal is born :proposed — the declared create landing
   :on-create (fn [row _ctx]
                (if (get-in row [:data :held])
                  (assoc row :state :proposed)
                  row))
   :actions
   {:pilot {:from #{:proposed} :to :piloted
            :input [:map
                    [:where {:optional true} :any]
                    [:after {:optional true} [:maybe :boolean]]]
            :record true
            :guards [population-shape data-law-only]
            :safety {:idempotent false :reversible false :confirm true
                     :consequence "The declared population's rows begin living under this revision; the current law keeps governing everything else. The way back is withdraw."}
            :handler record-population
            :display {:label "Pilot" :order 2}}
    ;; four-eyes on the CREATE action (waymark9 barred `propose`; v10
    ;; logs creation as :create, so the actual logged action name
    ;; carries the bar): the deploy actor that proposed cannot promote
    :promote {:from #{:proposed :piloted} :to :current
              :guards [(g/four-eyes :create)]
              :safety {:idempotent true :reversible false :confirm true
                       :consequence "This proposal becomes the served law for every row of its kind; rows adopt or grandfather per the kind's adoption declaration."}
              :display {:label "Promote" :order 1}}
    :withdraw {:from #{:proposed :piloted} :to :withdrawn
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "The proposal closes; the current law continues to govern — served from its stored trees while the withdrawn code stays resident."}
               :display {:label "Withdraw" :order 3}}
    :grandfather {:from #{:current} :to :grandfathered
                  :guards [deploy-only]
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "Rows still live under this revision: it is law, not history — it supersedes the day its last row adopts."}
                  :display {:label "Grandfather" :order 9}}
    :supersede {:from #{:current :grandfathered} :to :superseded
                :guards [deploy-only-hidden]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "Superseding is the engine's bookkeeping; a rollback is a new revision, never an un-supersede."}
                :display {:label "Supersede" :order 9}}}})

;; ── stored-row plumbing ─────────────────────────────────────────────

(defn- wire-keys
  "Stored fingerprints come back from JSONB with keyword keys (the
  one JSON mapper keywordizes); the fingerprint vocabulary is string
  keys. One normalization at the load boundary."
  [v]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (into {} (map (fn [[k val]] [(if (keyword? k) (name k) k) val])) x)
       x))
   v))

(defn- def-rows
  "Every stored definition row of one target kind, revision-descending,
  fingerprints normalized to the string-keyed vocabulary."
  [eng kind]
  (store/with-tx (:storage eng)
    (fn [tx]
      (->> (store/query-rows (:storage eng) tx :definition
                             {:target_kind (name kind)} {:limit 1000})
           (mapv #(update-in % [:data :fingerprint] wire-keys))
           (sort-by (comp :revision :data) >)))))

(defn- rev-of [row] (get-in row [:data :revision]))
(defn- hash-of [row] (get-in row [:data :fingerprint_hash]))
(defn- fp-of [row] (get-in row [:data :fingerprint]))

(defn- install!
  "Swap new law slots into the kind's rdef; every install resets the
  judgment cache (rebuilt guard vectors key off the slots)."
  [eng kind f]
  (swap! (:registry eng) update-in [:kinds kind]
         (fn [rd] (assoc (f rd) :judgment-cache (atom {})))))

(defn- rdef-now [eng kind] (get (inv/resources eng) kind))

;; ── readers ─────────────────────────────────────────────────────────

(defn current-law
  "The revision number governing the kind, nil pre-law."
  [eng kind]
  (:current-law (rdef-now eng kind)))

(defn proposed-law [eng kind] (:proposed-law (rdef-now eng kind)))
(defn piloted-law [eng kind] (:piloted-law (rdef-now eng kind)))

;; ── the deploy story helpers ────────────────────────────────────────

(defn- describe [diff]
  (let [counts (frequencies (map :class (mapcat diff [:added :removed :changed])))]
    (if (empty? counts)
      "revised with no path-level change"
      (let [s (str "changes: "
                   (str/join ", " (map (fn [[c n]] (str n " " (name c)))
                                       (sort-by (comp name key) counts))))]
        (subs s 0 (min 120 (count s)))))))

(defn- mint!
  "One definition row via the ordinary create path — the law lives in
  the same envelope/log machinery as everything else."
  [eng corr body]
  (:row (inv/create! eng :definition
                     (into {} (filter (comp some? val)) body)
                     {:principal deploy :correlation-id corr})))

(defn- withdraw! [eng corr row-id]
  (inv/invoke! eng :definition row-id :withdraw nil
               {:principal deploy :correlation-id corr}))

;; ── the sweep ───────────────────────────────────────────────────────

(defn sweep!
  "Supersede-when-empty (waymark9 design §1/§3): every grandfathered
  revision with zero stamped rows transitions to superseded and drops
  its :judgment-laws entry — laws die when they are empty, and the
  log knows the day."
  ([eng]
   (doseq [kind (sort (keys (dissoc (inv/resources eng) :definition)))]
     (sweep! eng kind)))
  ([eng kind]
   (let [storage (:storage eng)]
     (doseq [row (filter #(= :grandfathered (:state %)) (def-rows eng kind))]
       (let [rev (rev-of row)
             n (store/with-tx storage
                 (fn [tx] (store/law-count storage tx kind rev)))]
         (when (zero? n)
           (inv/invoke! eng :definition (:id row) :supersede nil
                        {:principal deploy})
           (install! eng kind
                     (fn [rd] (update rd :judgment-laws dissoc rev)))))))))

;; ── the promote effect (shared by boot auto-promote and the human
;;    promote) ────────────────────────────────────────────────────────

(defn- install-current!
  "new-row (a definition row in state :current) becomes the served
  law of its kind: prior current rows retire — an adoption :immediate
  kind's rows restamp first, so the prior supersedes empty; a kind
  with surviving stamped rows grandfathers instead and its stored
  fingerprint joins :judgment-laws — slots flip, the sweep runs."
  [eng kind new-row]
  (let [target-rdef (rdef-now eng kind)
        new-rev (rev-of new-row)
        storage (:storage eng)
        priors (filter #(and (= :current (:state %))
                             (not= (:id %) (:id new-row)))
                       (def-rows eng kind))]
    (doseq [prior priors]
      (let [old-rev (rev-of prior)]
        (when (= :immediate (:adoption target-rdef))
          (store/with-tx storage
            (fn [tx] (store/restamp-law! storage tx kind
                                         {:law-revision old-rev} new-rev))))
        (let [survivors (store/with-tx storage
                          (fn [tx] (store/law-count storage tx kind old-rev)))
              retire (if (pos? survivors) :grandfather :supersede)]
          (inv/invoke! eng :definition (:id prior) retire nil
                       {:principal deploy})
          (install! eng kind
                    (fn [rd]
                      (update rd :judgment-laws
                              (fn [j] (if (pos? survivors)
                                        (assoc (or j {}) old-rev (fp-of prior))
                                        (dissoc (or j {}) old-rev)))))))))
    (install! eng kind
              (fn [rd]
                (-> rd
                    (assoc :current-law new-rev)
                    (assoc-in [:law-ids new-rev] (:id new-row))
                    ;; the promoted revision is resident now
                    (update :judgment-laws (fnil dissoc {}) new-rev)
                    (assoc :proposed-law nil :piloted-law nil))))
    (sweep! eng kind)))

;; ── the lifecycle effects ───────────────────────────────────────────

(defn- after-pilot! [eng row]
  (let [kind (keyword (get-in row [:data :target_kind]))
        rev (rev-of row)
        pop (get-in row [:data :population])]
    (when (rdef-now eng kind)
      (install! eng kind
                (fn [rd]
                  (-> rd
                      (assoc :piloted-law {:revision rev
                                           :definition-id (:id row)
                                           :population pop})
                      (assoc :proposed-law nil)
                      (assoc-in [:law-ids rev] (:id row)))))
      ;; existing where-matches restamp to the piloted revision —
      ;; judged under it from now on (the resident code IS that law)
      (when-some [where (:where pop)]
        (store/with-tx (:storage eng)
          (fn [tx] (store/restamp-law! (:storage eng) tx kind where rev)))))))

(defn- after-promote! [eng row]
  (let [kind (keyword (get-in row [:data :target_kind]))]
    (when (rdef-now eng kind)
      (install-current! eng kind
                        (update-in row [:data :fingerprint] wire-keys)))))

(defn- after-withdraw! [eng row]
  (let [kind (keyword (get-in row [:data :target_kind]))
        rev (rev-of row)]
    (when-some [rd (rdef-now eng kind)]
      (install! eng kind
                (fn [rd]
                  (cond-> rd
                    (= rev (get-in rd [:proposed-law :revision]))
                    (assoc :proposed-law nil)
                    (= rev (get-in rd [:piloted-law :revision]))
                    (assoc :piloted-law nil))))
      ;; piloted rows return to the current law; a withdrawn proposal
      ;; moved no row. NOTE the docstring's withdraw semantics: the
      ;; :judgment-laws entry for the current law STAYS — the resident
      ;; code still expresses the withdrawn law.
      (when-some [cur (:current-law rd)]
        (store/with-tx (:storage eng)
          (fn [tx] (store/restamp-law! (:storage eng) tx kind
                                       {:law-revision rev} cur)))))))

(defn lifecycle
  "The engine seam invoke!'s after-write! calls: definition
  transitions carry their effects; an adopt anywhere runs the sweep."
  [eng kind action res]
  (if (= :definition kind)
    (case action
      :pilot (after-pilot! eng (:row res))
      :promote (after-promote! eng (:row res))
      :withdraw (after-withdraw! eng (:row res))
      nil)
    (when (= :adopt action)
      (sweep! eng kind)))
  nil)

;; ── boot ────────────────────────────────────────────────────────────

(defn- revise-kind!
  "Compare one resident kind's fingerprint to its stored law; write
  nothing, or deploy per :deploy-mode, and fill the law slots."
  [eng corr kind]
  (let [rdef (rdef-now eng kind)
        fp' (:fingerprint rdef)
        fph (:fingerprint-hash rdef)
        rows (def-rows eng kind)
        by-state (group-by :state rows)
        current (first (:current by-state))
        proposed (first (:proposed by-state))
        piloted (first (:piloted by-state))
        law-ids (into {} (map (juxt rev-of :id)) rows)
        ;; grandfathered laws always serve their rows from the store
        jlaws (into {} (map (juxt rev-of fp-of)) (:grandfathered by-state))
        next-rev (inc (reduce max 0 (map rev-of rows)))]
    (cond
      ;; (a) the first law: mint revision 1, born current
      (nil? current)
      (let [row (mint! eng corr {:target_kind (name kind)
                                 :revision next-rev
                                 :fingerprint_hash fph
                                 :fingerprint fp'
                                 :diff_class "initial"
                                 :change_summary "the law as first recorded"})]
        (install! eng kind
                  #(merge % {:current-law next-rev
                             :proposed-law nil
                             :piloted-law nil
                             :law-ids (assoc law-ids next-rev (:id row))
                             :judgment-laws jlaws})))

      ;; (b) unchanged: a reboot costs nothing — adopt the stored
      ;; numbers; a lingering proposal whose code is no longer
      ;; resident exits the honest way
      (= fph (hash-of current))
      (do
        (doseq [prop (:proposed by-state)]
          (withdraw! eng corr (:id prop)))
        (install! eng kind
                  #(merge % {:current-law (rev-of current)
                             :proposed-law nil
                             :piloted-law (when piloted
                                            {:revision (rev-of piloted)
                                             :definition-id (:id piloted)
                                             :population (get-in piloted [:data :population])})
                             :law-ids law-ids
                             ;; a pilot whose code is not resident is
                             ;; parameter-served from its stored trees
                             :judgment-laws (cond-> jlaws
                                              piloted (assoc (rev-of piloted)
                                                             (fp-of piloted)))})))

      ;; (b') the resident code IS the held proposal — the hold
      ;; continues across the reboot
      (and proposed (= fph (hash-of proposed)))
      (install! eng kind
                #(merge % {:current-law (rev-of current)
                           :proposed-law {:revision (rev-of proposed)
                                          :definition-id (:id proposed)}
                           :piloted-law nil
                           :law-ids law-ids
                           :judgment-laws (assoc jlaws (rev-of current)
                                                 (fp-of current))}))

      ;; (b'') the resident code IS the piloted revision — the pilot
      ;; continues across the reboot
      (and piloted (= fph (hash-of piloted)))
      (install! eng kind
                #(merge % {:current-law (rev-of current)
                           :proposed-law nil
                           :piloted-law {:revision (rev-of piloted)
                                         :definition-id (:id piloted)
                                         :population (get-in piloted [:data :population])}
                           :law-ids law-ids
                           :judgment-laws (assoc jlaws (rev-of current)
                                                 (fp-of current))}))

      ;; (c) the law moved
      :else
      (let [diff (fp/diff-fingerprints (fp-of current) fp')
            class (fp/classify-diff diff)
            summary' (describe diff)
            hold? (and (= :data-law class) (= :propose (:deploy-mode eng)))]
        (doseq [prop (:proposed by-state)]
          (withdraw! eng corr (:id prop)))
        (if hold?
          ;; propose mode + data-law diff: register and HOLD — the
          ;; overlay serves the current law from its stored trees
          (let [row (mint! eng corr {:target_kind (name kind)
                                     :revision next-rev
                                     :fingerprint_hash fph
                                     :fingerprint fp'
                                     :diff (p/wire-value diff)
                                     :diff_class "data_law"
                                     :change_summary summary'
                                     :held true})]
            (install! eng kind
                      #(merge % {:current-law (rev-of current)
                                 :proposed-law {:revision next-rev
                                                :definition-id (:id row)}
                                 :piloted-law nil
                                 :law-ids (assoc law-ids next-rev (:id row))
                                 :judgment-laws (assoc jlaws (rev-of current)
                                                       (fp-of current))})))
          ;; auto-promote: the single-breath revise (a code-or-shape
          ;; diff in propose mode carries the recorded marker — the
          ;; resident objects ARE that law; holding would be a lie)
          (let [row (mint! eng corr
                           {:target_kind (name kind)
                            :revision next-rev
                            :fingerprint_hash fph
                            :fingerprint fp'
                            :diff (p/wire-value diff)
                            :diff_class (if (= :data-law class)
                                          "data_law" "code_or_shape")
                            :change_summary summary'
                            :deploy_note (when (and (= :propose (:deploy-mode eng))
                                                    (= :code-or-shape class))
                                           "promoted without hold: diff exceeds data-law")})]
            (install! eng kind
                      #(merge % {:law-ids (assoc law-ids next-rev (:id row))
                                 :judgment-laws jlaws
                                 :proposed-law nil
                                 :piloted-law nil}))
            (install-current! eng kind row)))))))

(defn boot-revise!
  "Fingerprint every resident application kind, revise where the hash
  moved, fill the law slots. One correlation id spans the deploy."
  [eng]
  (let [corr (str (random-uuid))]
    (doseq [kind (sort (keys (dissoc (inv/resources eng) :definition)))]
      (revise-kind! eng corr kind))))
