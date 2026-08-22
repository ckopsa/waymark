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
  (phase 8) fold them into their own deftests.

  Batch A's parts/links/depth/effort battery lived next door in
  waymark10.test.envelope-obligations for two batches, with a fold-in
  written out step by step in its own ns docstring. waymark-db9.5
  performed it: those fns are the LAST FOUR SECTIONS of this file,
  verbatim but for the two helpers (`wire-name`, `where-of`) they
  had copied from here. One library, one home, one set of
  conventions — and the driver that selects from it
  (waymark10.test.suite) has one namespace to read.

  Selection is NOT here. These fns are the obligations' bodies; which
  of them an engine owes is waymark10.test.packs' answer, keyed off
  the module inventory. This namespace stays a library on purpose:
  the fns take documents, never engines-and-opinions, so a suite that
  wants one obligation can still call it by hand (a dozen batch tests
  do)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.demand :as demand]
            [waymark10.machine :as machine]
            [waymark10.server.collections :as collections]
            [waymark10.server.problems :as p]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.server.store.migrate :as migrate]
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

(def ^:private resolve-token
  "One token read FORWARD through a rename map — the migrate
  planner's own resolver, shared so replay and the planner can never
  disagree about where a chain ends."
  migrate/resolve-token)

(defn replay-violations
  "Every logged transition with a non-nil law-revision, checked
  against its revision's stored fingerprint. Returns a vector of
  violation maps — empty is conformance.

  The continuity map rides the judgment (migrate): logged names AND
  the stored law's names both read FORWARD through the resident
  declaration's :renames chain before comparing, so a grandfathered
  row acting after a state rename (new tokens logged under the old
  revision's stamp) still replays legal — waymark9's
  check_state_tokens promise, kept over history."
  [eng]
  (let [st (:storage eng)
        rdefs (if-some [reg (:registry eng)] (:kinds @reg) (:resources eng))
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
                                           :law-revision])
                   renames (get-in rdefs [(:kind t) :renames] {})
                   s-res #(resolve-token (:states renames) %)
                   a-res #(resolve-token (:actions renames) %)]
               (when (and rev
                          (:from-state t)
                          (not (engine-actions (:action t))))
                 (if-some [fp' (get laws [(name (:kind t)) rev])]
                   (let [actions (get-in fp' ["machine" "actions"])
                         logged-a (a-res (:action t))
                         a (or (get actions (name (:action t)))
                               (some (fn [[an av]]
                                       (when (= (a-res an) logged-a) av))
                                     actions))]
                     (if a
                       (when-not (and (some #(= (s-res %)
                                                (s-res (:from-state t)))
                                            (get a "from"))
                                      (= (s-res (:to-state t))
                                         (s-res (get a "to"))))
                         (assoc witness :violation :edge-not-in-law
                                :law {:from (get a "from") :to (get a "to")}))
                       (assoc witness :violation :action-not-in-law)))
                   (assoc witness :violation :no-stored-law))))))
          ts)))

(defn touches-violations
  "The blast-radius promise (waymark9 touches=, design §24): every
  logged transition whose action declares :touches must be
  accompanied — same correlation id — by a transition of each
  declared kind.action; :may true tolerates absence. Returns a
  vector of violation maps, empty is conformance. A transition
  without a correlation id has nothing to correlate and skips (the
  suites that assert touches pass one explicitly)."
  [eng]
  (let [st (:storage eng)
        rdefs (if-some [reg (:registry eng)] (:kinds @reg) (:resources eng))
        ts (store/with-tx st
             (fn [tx] (store/transitions st tx {} {:limit 100000})))
        by-cid (group-by :correlation-id ts)]
    (into []
          (mapcat
           (fn [t]
             (when-some [touches (seq (get-in rdefs [(:kind t) :actions
                                                     (:action t) :touches]))]
               (when-some [cid (:correlation-id t)]
                 (let [siblings (remove #(identical? % t) (get by-cid cid))]
                   (keep (fn [tc]
                           (when-not (or (:may tc)
                                         (some #(and (= (keyword (:kind %))
                                                        (:kind tc))
                                                     (= (keyword (:action %))
                                                        (:action tc)))
                                               siblings))
                             {:kind (:kind t)
                              :resource-id (:resource-id t)
                              :action (:action t)
                              :correlation-id cid
                              :violation :touch-did-not-fire
                              :touch {:kind (:kind tc) :action (:action tc)}}))
                         touches))))))
          ts)))

;; ── the envelope obligations (phase 4b) ─────────────────────────────

(def envelope-keys
  "The reserved envelope keys — every GET envelope has exactly these.
  :fields (the bounded grid-column projection of :data — every
  vector/prose field excluded) is always present, full depth or
  summary alike, so it joins the base set rather than riding as
  optional. :parts (batch A) and :display (DX round 3 — the resolved
  page-title advertisement, carried only by kinds that declare one)
  are reserved-but-optional, so the shape check tolerates their
  absence; parts' co-conspirators are refused via the parts
  obligations below."
  #{:waymark :kind :self :state :summary :data :fields :actions
    :unavailable :links :meta})

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
      (not= envelope-keys (disj (set (keys env)) :parts :display))
      (conj (str where ": envelope keys " (vec (sort (keys env)))
                 " are not exactly the reserved " (vec (sort envelope-keys))
                 " (+ optional :parts/:display)"))

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
  data — exactly the reserved keys without :data, plus the same
  optional :display a full envelope may carry (a list row is where
  the resolved title earns its keep)."
  [item]
  (when (not= (disj envelope-keys :data)
              (disj (set (keys item)) :display))
    [(str (where-of item) ": collection item keys "
          (vec (sort (keys item))) " are not the envelope minus data")]))

;; ── the collection and fan-out obligations (phase 7) ────────────────

(defn collection-envelope-violations
  "The phase-7 collection shape for one parsed GET envelope: kind
  <kind>_collection, state ok, a prose summary, items as
  envelope-minus-data summaries, a real total ≥ the shown rows,
  1-based page bookkeeping, and the create/query affordances (query
  a GET with a generated object input schema)."
  [env {:keys [kind]}]
  (let [where (str (:kind env) " " (:self env))
        data (:data env)
        page (:page data)]
    (-> (cond-> []
          (not= "10" (:waymark env))
          (conj (str where ": waymark is " (pr-str (:waymark env)) ", not \"10\""))

          (and kind (not= (str (name kind) "_collection") (:kind env)))
          (conj (str where ": kind is " (pr-str (:kind env)) ", not "
                     (name kind) "_collection"))

          (not= "ok" (:state env))
          (conj (str where ": state is " (pr-str (:state env)) ", not \"ok\""))

          (or (not (string? (:summary env))) (str/blank? (:summary env)))
          (conj (str where ": summary " (pr-str (:summary env)) " is empty"))

          (not (vector? (:items data)))
          (conj (str where ": data.items " (pr-str (:items data))
                     " is not an array"))

          (not (int? (:total data)))
          (conj (str where ": data.total " (pr-str (:total data))
                     " is not an integer"))

          (and (int? (:total data)) (vector? (:items data))
               (< (:total data) (count (:items data))))
          (conj (str where ": total " (:total data) " is below the "
                     (count (:items data)) " shown rows"))

          (not (and (pos-int? (:size page)) (pos-int? (:number page))))
          (conj (str where ": data.page " (pr-str page)
                     " is not {size ≥1, number ≥1}"))

          (not= "POST" (get-in env [:actions :create :method]))
          (conj (str where ": actions.create is not a POST entry"))

          (not= "GET" (get-in env [:actions :query :method]))
          (conj (str where ": actions.query is not a GET entry"))

          (not (map? (get-in env [:actions :query :input :properties])))
          (conj (str where ": actions.query.input carries no generated "
                     "properties")))
        (into (mapcat collection-item-violations (:items data))))))

(defn bulk-report-violations
  "The bulk-report shape: kind bulk_report, the acted action's name,
  counts that add up to the fan-out, and one reasoned refusal entry
  per refused/failed item."
  [doc {:keys [action items]}]
  (let [where (str "bulk_report " (:action doc))
        data (:data doc)
        {:keys [succeeded refused failed refusals]} data]
    (cond-> []
      (not= "bulk_report" (:kind doc))
      (conj (str where ": kind is " (pr-str (:kind doc)) ", not bulk_report"))

      (and action (not= (name action) (:action doc)))
      (conj (str where ": action is " (pr-str (:action doc)) ", not "
                 (name action)))

      (not (every? nat-int? [succeeded refused failed]))
      (conj (str where ": counts " (pr-str (select-keys data [:succeeded
                                                              :refused
                                                              :failed]))
                 " are not non-negative integers"))

      (and items (every? nat-int? [succeeded refused failed])
           (not= items (+ succeeded refused failed)))
      (conj (str where ": counts sum to " (+ succeeded refused failed)
                 ", not the " items " items sent"))

      (and (every? nat-int? [refused failed])
           (not= (+ refused failed) (count refusals)))
      (conj (str where ": " (count refusals) " refusal entries for "
                 (+ refused failed) " refused+failed items"))

      (not (every? #(and (string? (:reason %)) (not (str/blank? (:reason %))))
                   refusals))
      (conj (str where ": a refusal entry carries no reason sentence: "
                 (pr-str refusals))))))

;; ── the identity-and-access obligations (phase 9a) ──────────────────

(defn grant-concealment-violations
  "The grant-concealment obligation for one parsed envelope rendered
  to a SCOPED principal: no action name outside the granted set
  survives anywhere — not advertised, not narrated as unavailable
  (absence IS the promise; narration would leak what concealment
  hides)."
  [env granted]
  (let [where (where-of env)
        allowed (into #{} (map (comp keyword name)) granted)]
    (concat
     (for [n (sort (keys (:actions env)))
           :when (not (contains? allowed n))]
       (str where ": a scoped envelope advertises ungranted " (name n)))
     (for [n (sort (keys (:unavailable env)))
           :when (not (contains? allowed n))]
       (str where ": a scoped envelope NARRATES ungranted " (name n)
            " — concealment demands absence")))))

(defn suspension-violations
  "The suspended-member refusal shape: a 403 problem (RFC 9457 like
  every refusal) whose type names member-suspended — the same answer
  on every route, because the gate runs before any handler."
  [status ctype body & [{:keys [where]}]]
  (let [where (or where "suspended member")]
    (concat
     (when (not= 403 status)
       [(str where ": answered " status ", not 403")])
     (problem-violations status ctype body {:where where})
     (when-not (str/ends-with? (str (:type body)) "member-suspended")
       [(str where ": problem type " (pr-str (:type body))
             " does not name member-suspended")]))))

(defn attachment-roundtrip-violations
  "The byte round-trip obligation: the PUT answered 200 with the
  stored envelope whose data.size tells the byte count, and the GET
  served the same bytes back under the declared media type."
  [{:keys [sent put-status put-env get-status get-ctype got media-type]}]
  (let [n (count (seq sent))]
    (cond-> []
      (not= 200 put-status)
      (conj (str "bytes PUT answered " put-status ", not 200"))

      (not= "stored" (:state put-env))
      (conj (str "the PUT's envelope is " (pr-str (:state put-env))
                 ", not stored"))

      (not= n (get-in put-env [:data :size]))
      (conj (str "data.size " (pr-str (get-in put-env [:data :size]))
                 " is not the " n " bytes sent"))

      (not= 200 get-status)
      (conj (str "bytes GET answered " get-status ", not 200"))

      (and media-type (not= media-type get-ctype))
      (conj (str "bytes GET Content-Type " (pr-str get-ctype)
                 " is not the declared " (pr-str media-type)))

      (not= (seq sent) (seq got))
      (conj "the bytes that came back are not the bytes sent"))))

;; ── parts (batch A, folded in from envelope-obligations) ────────────
;;
;; The parts shape being enforced (recorded in render.clj's ns
;; docstring): parts is a REFINEMENT — a placed action renders
;; top-level AND per item; the exclusive shape was rejected because
;; depth=summary drops parts and would conceal live affordances.


(defn- placed-actions
  "wire action name → {:path wire-kw :key wire-kw} for the declared
  placed actions of one rdef."
  [rdef]
  (into {}
        (keep (fn [[aname a]]
                (when-some [scope (get-in rdef [:part-scopes (:place a)])]
                  [(wire-name aname) {:path (wire-name (:path scope))
                                      :key (wire-name (:key scope))}])))
        (:actions rdef)))

(defn parts-violations
  "The parts-shape obligation for one full GET envelope:
  - every parts group belongs to a declared part scope and names the
    declared key;
  - the group mirrors its data array (same length, same key values,
    :item equal to the data item);
  - every part action is a declared placed action of that scope, ALSO
    advertised top-level (refinement, never a replacement), with the
    key field const-bound to the item's key and a demand class on the
    entry;
  - every advertised placed action with a non-empty scope array
    re-renders in parts;
  - per-item narrations carry a reason sentence."
  [rdef env]
  (let [where (where-of env)
        placed (placed-actions rdef)
        scope-of (into {} (map (fn [[_ s]] [(:path s) s])) placed)
        advertised (set (keys (:actions env)))]
    (concat
     ;; every group is declared, keyed right, and mirrors the data
     (mapcat
      (fn [[path group]]
        (let [scope (get scope-of path)
              data-items (vec (get-in env [:data path]))
              items (vec (:items group))]
          (concat
           (when-not scope
             [(str where ": parts group " (name path)
                   " names no declared part scope")])
           (when (and scope (not= (name (:key scope)) (str (:key group))))
             [(str where ": parts." (name path) " key " (pr-str (:key group))
                   " is not the declared " (name (:key scope)))])
           (when (not= (count data-items) (count items))
             [(str where ": parts." (name path) " has " (count items)
                   " items for " (count data-items) " data rows — the group"
                   " mirrors the array")])
           (mapcat
            (fn [idx item]
              (let [data-item (get data-items idx)
                    kf (:key scope)
                    where' (str where " parts." (name path)
                                "[" idx "]")]
                (concat
                 (when (and scope
                            (not= (str (:key item))
                                  (str (get data-item kf))))
                   [(str where' ": key " (pr-str (:key item))
                         " is not the data row's " (name kf) " "
                         (pr-str (get data-item kf)))])
                 (when (not= (:item item) data-item)
                   [(str where' ": item payload diverges from data."
                         (name path) "[" idx "]")])
                 (mapcat
                  (fn [[aname entry]]
                    (let [scope' (get placed aname)
                          const (get-in entry [:input :properties
                                               (when scope' (:key scope'))
                                               :const])]
                      (concat
                       (when-not scope'
                         [(str where' " actions." (name aname)
                               ": not a declared placed action")])
                       (when (and scope' (not= (:path scope') path))
                         [(str where' " actions." (name aname)
                               ": placed on " (name (:path scope'))
                               ", rendered under " (name path))])
                       (when-not (contains? advertised aname)
                         [(str where' " actions." (name aname)
                               ": in parts but NOT top-level — parts is a"
                               " refinement, never a replacement")])
                       (when (and scope' (not= (str const) (str (:key item))))
                         [(str where' " actions." (name aname)
                               ": the key field's const " (pr-str const)
                               " is not the item key " (pr-str (:key item)))])
                       (when-not (contains? (set demand/classes)
                                            (:effort entry))
                         [(str where' " actions." (name aname)
                               ": effort " (pr-str (:effort entry))
                               " is not a demand class")]))))
                  (:actions item))
                 (for [[aname entry] (:unavailable item)
                       :when (or (not (string? (:reason entry)))
                                 (str/blank? (:reason entry)))]
                   (str where' " unavailable." (name aname)
                        ": reason " (pr-str (:reason entry))
                        " is missing or blank")))))
            (range) items))))
      (:parts env))
     ;; every advertised placed action over a non-empty array re-renders
     (for [[aname scope] placed
           :when (and (contains? advertised aname)
                      (seq (get-in env [:data (:path scope)]))
                      (not (get-in env [:parts (:path scope)])))]
       (str where ": placed action " (name aname) " is advertised over "
            (count (get-in env [:data (:path scope)])) " data rows but"
            " renders no parts group")))))

(defn parts-enforcement-violations
  "The parts-honesty wire half: every part item's action, dry-run
  invoked with the pre-bound key (plus the caller's :fill for the
  other required fields), is ACCEPTED; every per-item narration,
  dry-run invoked the same way, refuses 409 with the narrated reason
  as its detail — advertisement equals enforcement, per item.

  opts: :post (fn [href body] → {:status int :body parsed}) — the
  caller's transport, dry_run applied here; :fill (fn [action-kw
  item] → input map for the OTHER fields), defaults to {}."
  [env {:keys [post fill]}]
  (let [fill (or fill (constantly {}))
        where (where-of env)
        dry (fn [href body] (post (str href "?dry_run=1") body))]
    (mapcat
     (fn [[path group]]
       (mapcat
        (fn [idx item]
          (let [kf (keyword (str (:key group)))
                where' (str where " parts." (name path) "[" idx "]")]
            (concat
             (mapcat
              (fn [[aname entry]]
                (let [input (merge (fill aname item)
                                   {kf (get-in entry [:input :properties
                                                      kf :const])})
                      {:keys [status body]} (dry (:href entry) input)]
                  (when-not (and (= 200 status) (true? (:valid body)))
                    [(str where' " actions." (name aname)
                          ": dry-run with the pre-bound key answered "
                          status " " (pr-str (or (:detail body)
                                                 (:errors body)))
                          " — the part advertised what enforcement refuses")])))
              (:actions item))
             (mapcat
              (fn [[aname entry]]
                (let [href (str (:self env) "/-/" (name aname))
                      input (merge (fill aname item) {kf (:key item)})
                      {:keys [status body]} (dry href input)]
                  (concat
                   (when (and (= 200 status) (true? (:valid body)))
                     [(str where' " unavailable." (name aname)
                           ": narrated per item yet dry-run ACCEPTS the"
                           " item key " (pr-str (:key item)))])
                   (when (and (= 409 status)
                              (not= (:detail body) (:reason entry)))
                     [(str where' " unavailable." (name aname)
                           ": narrated " (pr-str (:reason entry))
                           " but enforcement said "
                           (pr-str (:detail body)))]))))
              (:unavailable item)))))
        (range) (:items group)))
     (:parts env))))

;; ── links ───────────────────────────────────────────────────────────

(defn- effective-limit
  "A link's own :limit if declared, else the framework default — every
  :embed link is grid mode now, no bool-form exemption."
  [ld]
  (or (:limit (when (map? (:embed ld)) (:embed ld)))
      collections/page-size-default))

(defn links-violations
  "The links-shape obligation for one envelope: every rendered rel is
  declared, carries an href, and its badge equals the envelope's OWN
  data value for the declared badge field (the no-N+1 rule made
  checkable: scent comes from materialized facts, so the envelope
  already carries the truth). Every embed is grid mode: embedded
  inlines are envelope-minus-data items within the link's own
  effective limit, and total/page are present whenever embedded is."
  [rdef env]
  (let [where (where-of env)
        declared (into {} (map (juxt (comp wire-name :rel) identity))
                       (:links rdef))]
    (mapcat
     (fn [[rel link]]
       (let [ld (get declared rel)]
         (concat
          (when-not ld
            [(str where " links." (name rel) ": not a declared link")])
          (when-not (and (string? (:href link))
                         (not (str/blank? (:href link))))
            [(str where " links." (name rel) ": href "
                  (pr-str (:href link)) " is missing or blank")])
          (when-some [badge-field (:badge ld)]
            (let [data-value (get-in env [:data (wire-name badge-field)])]
              (cond
                (and (some? data-value)
                     (not= (str (:badge link)) (str data-value)))
                [(str where " links." (name rel) ": badge "
                      (pr-str (:badge link)) " ≠ data."
                      (name badge-field) " " (pr-str data-value))]

                (and (nil? data-value) (contains? link :badge))
                [(str where " links." (name rel) ": badge "
                      (pr-str (:badge link))
                      " rides a null fact — absence, not zero")])))
          (when (:embed ld)
            (concat
             (when-not (and (contains? link :total) (contains? link :page))
               [(str where " links." (name rel)
                     ": an :embed link carries no total/page — every "
                     "embed is grid mode now")])
             (when-some [embedded (:embedded link)]
               (let [limit (effective-limit ld)]
                 (concat
                  (when (< limit (count embedded))
                    [(str where " links." (name rel) ": " (count embedded)
                          " embedded items exceed the effective limit " limit)])
                  (for [item embedded
                        :when (contains? item :data)]
                    (str where " links." (name rel)
                         ": an embedded item carries data — inlines are"
                         " envelope-minus-data"))))))))))
     (:links env))))

(defn links-wire-violations
  "The links-honesty wire half: every rendered href GETs 200 for the
  acting principal. get-fn: (fn [href] → {:status int :body parsed})."
  [env get-fn]
  (let [where (where-of env)]
    (mapcat
     (fn [[rel link]]
       (let [{:keys [status]} (get-fn (:href link))]
         (when (not= 200 status)
           [(str where " links." (name rel) ": GET " (:href link)
                 " answered " status " — a link that does not resolve"
                 " for its reader is a lie")])))
     (:links env))))

;; ── depth ───────────────────────────────────────────────────────────

(defn fields-violations
  "The grid-column obligation for one envelope (full or summary
  alike — :fields is always present at both depths, unlike :data):
  every key rides render/grid-fields' own rule (no :vector, no prose
  unless teaser-flagged — the SAME fn envelope itself calls, so this
  checks the rule was actually applied, not a second copy of it that
  could drift), and :fields is never absent."
  [rdef env]
  (let [where (where-of env)
        eligible (render/grid-fields rdef)]
    (cond-> []
      (not (contains? env :fields))
      (conj (str where ": no :fields — every depth carries a grid projection"))

      (seq (remove eligible (keys (:fields env))))
      (conj (str where ": fields " (vec (remove eligible (keys (:fields env))))
                 " are not grid-eligible (vector) per "
                 (name (:kind rdef)) "'s own declaration")))))

(defn depth-violations
  "The depth contract over one row read twice: the summary is the
  full envelope minus data and parts — same identity, same COMPLETE
  action partition (the refinement shape is what keeps a summary
  honest), and the SAME :fields (both depths project from the same
  :data, so they must agree byte-for-byte)."
  [{:keys [full summary]}]
  (let [where (where-of full)]
    (cond-> []
      (contains? summary :data)
      (conj (str where ": depth=summary still carries data"))

      (contains? summary :parts)
      (conj (str where ": depth=summary still carries parts"))

      (not= (:fields full) (:fields summary))
      (conj (str where ": summary depth changed :fields "
                 (pr-str (:fields summary)) " ≠ " (pr-str (:fields full))))

      (not= (select-keys full [:kind :self :state :summary])
            (select-keys summary [:kind :self :state :summary]))
      (conj (str where ": summary depth changed the envelope's identity"))

      (not= (set (keys (:actions full))) (set (keys (:actions summary))))
      (conj (str where ": summary depth changed the advertised actions "
                 (vec (sort (keys (:actions summary)))) " ≠ "
                 (vec (sort (keys (:actions full))))))

      (not= (set (keys (:unavailable full)))
            (set (keys (:unavailable summary))))
      (conj (str where ": summary depth changed the narrated unavailable")))))

(defn rows-none-violations
  "The rows=none contract for one collection item: actions AND
  unavailable are null — explicitly unknown, never {} — and the
  follow-up GET of the item's self answers 200 with a real actions
  map. get-fn: (fn [href] → {:status int :body parsed})."
  [item get-fn]
  (let [where (where-of item)
        {:keys [status body]} (get-fn (:self item))]
    (cond-> []
      (not (and (contains? item :actions) (nil? (:actions item))))
      (conj (str where ": rows=none item actions "
                 (pr-str (:actions item)) " is not an explicit null"))

      (not (and (contains? item :unavailable) (nil? (:unavailable item))))
      (conj (str where ": rows=none item unavailable "
                 (pr-str (:unavailable item)) " is not an explicit null"))

      (not= 200 status)
      (conj (str where ": the follow-up GET answered " status))

      (and (= 200 status) (not (map? (:actions body))))
      (conj (str where ": the follow-up GET carries no actions map —"
                 " unknown never resolved")))))

;; ── effort ──────────────────────────────────────────────────────────

(defn- prose-prop? [prop]
  (= "prose" (get-in prop [:x-display :widget])))

(defn- entry-effort-violations [where aname entry]
  (let [effort (:effort entry)
        props (get-in entry [:input :properties])
        required (into #{} (map keyword) (get-in entry [:input :required]))
        demanding (into {}
                        (remove (fn [[_f prop]]
                                  (contains? prop :const)))
                        props)]
    (cond-> []
      (not (contains? (set demand/classes) effort))
      (conj (str where " actions." (name aname) ": effort "
                 (pr-str effort) " is not one of " demand/classes))

      (and (= "assent" effort)
           (seq (filter #(contains? demanding %) required)))
      (conj (str where " actions." (name aname) ": effort assent with"
                 " required un-bound field(s) "
                 (vec (sort (filter #(contains? demanding %) required)))
                 " — assent is one click"))

      (and (= "composition" effort)
           (not (some prose-prop? (vals demanding))))
      (conj (str where " actions." (name aname) ": effort composition"
                 " without a prose field — nothing unbounded is asked")))))

(defn effort-violations
  "The effort-truth obligation over one envelope: every action entry
  — top-level and part-bound — carries a demand class; assent entries
  demand no required un-bound input; composition entries carry a
  prose field."
  [env]
  (let [where (where-of env)]
    (concat
     (mapcat (fn [[aname entry]]
               (entry-effort-violations where aname entry))
             (:actions env))
     (mapcat
      (fn [[path group]]
        (mapcat
         (fn [idx item]
           (mapcat (fn [[aname entry]]
                     (entry-effort-violations
                      (str where " parts." (name path) "[" idx "]")
                      aname entry))
                   (:actions item)))
         (range) (:items group)))
      (:parts env)))))
