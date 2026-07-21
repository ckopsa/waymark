(ns waymark10.server.surface
  "Surfaces (phase 9b): the composed decision screen, declared once.
  A surface is {:name :anchor :members :showcase :attention} — an
  anchor kind, members naming DECLARED edges of the anchor (related
  joins or owns — the surface composes what the law relates, it
  smuggles no new joins), the anchor actions it showcases, and the
  attention flags (anchor data fields whose nominated value flags the
  row for the client's dashboard). A member may carry :where
  {field #{string-values}} — its standing filter (:state or a data
  field of the TARGET, equality over the set), so a board shows the
  actionable rows, not the archive (choreplan10's day board demanded
  it: runs due by the day that are still due, not every run ever
  done). Served at GET
  /api/surfaces/{name}/{anchor-id}: the anchor's FULL envelope, each
  member's rows as envelope-minus-data summaries, and the attention
  map evaluated against the stored facts. Engines declare surfaces at
  boot ({:surfaces […]}, validated against the assembled registry —
  the check_related tradition: refusals happen where every kind is
  known, never at first request); well-known lists them.

  Scope, honestly (what waymark9's core/surface.py has that this does
  not, each a sentence):
  - Surfaces are not fingerprinted: no definition row, no revise
    transition when the composition changes — the law-of-the-surface
    is a named punt (waymark9 stamped surface:{name} definition rows).
  - Not grantable: a scoped (X-Waymark-Grant) request 404s the
    surface routes, like SSE — per-member grant projection waits with
    grant-projected streams.
  - Members carry no table= column hints and the declaration no title
    template; arrangement is entirely the renderer's.
  - Attention is equality over declared anchor fields (validated
    against the schema), not waymark9's full query-grammar filter.
  - The anchor's envelope does not link back to its surfaces (the
    envelope's links are still the phase-3 punt)."
  (:require [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── assembly (the boot-time gate) ───────────────────────────────────

(defn- err [surface-name msg]
  (t/definition-error (str "surface " surface-name ": " msg)))

(def ^:private name-re #"[a-z][a-z0-9]*(?:-[a-z0-9]+)*")

(defn- check-where
  "A member's standing filter: every field is :state or a data field
  of the TARGET kind, every value set non-empty strings (the derived
  count :where convention — states and stored facts compare as their
  wire text)."
  [sname mname target-rdef where]
  (let [fields (set (schema/entry-keys (:schema target-rdef)))]
    (doseq [[f vs] where]
      (when-not (or (= :state f) (contains? fields f))
        (throw (err sname (str "member " (name mname) " :where field " f
                               " is not :state or a data field of "
                               (name (:kind target-rdef))))))
      (when-not (and (set? vs) (seq vs) (every? string? vs))
        (throw (err sname (str "member " (name mname) " :where " f
                               " wants a non-empty set of strings")))))))

(defn- resolve-member
  "One member declaration → its resolved edge: {:name … :target kind,
  :related edge-map | :owns edge-map, :where standing-filter?}."
  [sname kinds anchor-rdef m]
  (let [mname (:name m)
        resolved
        (do
          (when-not (keyword? mname)
            (throw (err sname (str "member " (pr-str m) " declares no :name"))))
          (cond
            (:related m)
            (let [edge (or (get (:related anchor-rdef) (:related m))
                           (throw (err sname
                                       (str "member " (name mname)
                                            " cites related "
                                            "edge " (:related m) ", which "
                                            (name (:kind anchor-rdef))
                                            " does not declare — the surface "
                                            "composes what the law relates"))))]
              (when (and (:kind m) (not= (:kind m) (:kind edge)))
                (throw (err sname (str "member " (name mname) " declares :kind "
                                       (:kind m) " but the cited edge targets "
                                       (:kind edge)))))
              {:name mname :target (:kind edge) :related edge})

            (:owns m)
            (let [edge (or (some #(when (= (:owns m) (:kind %)) %)
                                 (:owns anchor-rdef))
                           (throw (err sname
                                       (str "member " (name mname)
                                            " cites owns "
                                            "edge " (:owns m) ", which "
                                            (name (:kind anchor-rdef))
                                            " does not declare"))))]
              {:name mname :target (:kind edge) :owns edge})

            :else
            (throw (err sname (str "member " (name mname) " names no edge — "
                                   "declare :related or :owns")))))]
    (if-some [where (not-empty (:where m))]
      (let [target-rdef (or (get kinds (:target resolved))
                            (throw (err sname
                                        (str "member " (name mname)
                                             " targets unregistered kind "
                                             (:target resolved)))))]
        (check-where sname mname target-rdef where)
        (assoc resolved :where where))
      resolved)))

(defn assemble
  "Validate every declared surface against the assembled registry and
  return wire-name → sdef. Throws DefinitionError on the first
  refusal — a surface the boot refuses never serves a request."
  [reg decls]
  (let [kinds (:kinds reg)]
    (reduce
     (fn [out {:keys [name anchor members showcase attention]}]
       (let [sname (clojure.core/name (or name
                                          (throw (t/definition-error
                                                  "a surface declares :name"))))]
         (when-not (re-matches name-re sname)
           (throw (err sname "surface names are kebab-case")))
         (when (contains? out sname)
           (throw (err sname "declared twice")))
         (let [anchor-rdef (or (get kinds anchor)
                               (throw (err sname (str "anchor kind " anchor
                                                      " is not registered on "
                                                      "this engine"))))
               resolved (mapv #(resolve-member sname kinds anchor-rdef %)
                              members)
               _ (when-not (apply distinct? ::none (map :name resolved))
                   (throw (err sname "member names must be distinct")))
               _ (doseq [a showcase]
                   (when-not (get-in anchor-rdef [:actions a])
                     (throw (err sname (str "showcase names unknown action "
                                            a " of " anchor)))))
               fields (set (schema/entry-keys (:schema anchor-rdef)))
               _ (doseq [[f _] attention]
                   (when-not (contains? fields f)
                     (throw (err sname (str "attention field " f " is not a "
                                            "data field of " anchor)))))]
           (assoc out sname {:name sname
                             :anchor anchor
                             :members resolved
                             :showcase (vec showcase)
                             :attention (into {} attention)}))))
     {}
     decls)))

;; ── member resolution (the maintainer's join inversion, read-side) ──

(defn- sql-cast [rdef field]
  (let [s (schema/field-schema (:schema rdef) field)
        head (if (vector? s) (first s) s)]
    (case head
      :waymark/date "date"
      :waymark/instant "timestamptz"
      :boolean "boolean"
      :int "bigint"
      (:double :decimal) "numeric"
      "text")))

(def ^:private flip-op {:= := :< :> :<= :>= :>= :<= :> :<})

(defn- where-conds
  "The member's standing filter as store conds — :state and stored
  facts compare as their wire text, equality over the declared set."
  [member]
  (mapv (fn [[f vs]]
          (if (= :state f)
            {:target :state :op :in :values (vec (sort vs))}
            {:target :data :field (name f) :cast "text"
             :op :in :values (vec (sort vs))}))
        (:where member)))

(defn- member-rows
  "The member's target rows for one anchor row: the FK dereference for
  owns, join conditions bound to the anchor's stored values for
  related, the declared :where riding along. A nil join value relates
  to nothing."
  [eng anchor-row member]
  (let [st (:storage eng)
        target (:target member)
        target-rdef (get (inv/resources eng) target)
        conds
        (if-some [edge (:owns member)]
          [{:target :data :field (name (:via edge)) :cast "text"
            :op := :value (:id anchor-row)}]
          (let [edge (:related member)
                joins (map (fn [[ours op theirs]]
                             [(get-in anchor-row [:data ours]) op theirs])
                           (:on edge))]
            (when (every? (comp some? first) joins)
              (mapv (fn [[v op theirs]]
                      ;; ours op theirs, ours bound to v ⇒ theirs
                      ;; (flip op) v — the maintainer's inversion
                      (if (= :id theirs)
                        {:target :id :op (flip-op op) :value (str v)}
                        {:target :data :field (name theirs)
                         :cast (sql-cast target-rdef theirs)
                         :op (flip-op op) :value (str v)}))
                    joins))))]
    (if (nil? conds)
      []
      (mapv #(inv/decode-row target-rdef %)
            (store/with-tx st
              (fn [tx] (store/search-rows st tx target
                                          (into conds (where-conds member))
                                          {:limit 200})))))))

;; ── the envelope ────────────────────────────────────────────────────

(defn envelope
  "The composed wire document for one anchor row: {waymark, kind
  \"surface\", self, name, showcase, attention (each declared flag
  evaluated against the stored facts), anchor (the full envelope),
  members {name {items [envelope-minus-data …]}}}. 404 when the
  anchor row does not exist."
  [eng sdef anchor-id ctx-opts]
  (let [anchor-rdef (get (inv/resources eng) (:anchor sdef))
        raw (store/with-tx (:storage eng)
              (fn [tx] (store/load-row (:storage eng) tx (:anchor sdef)
                                       anchor-id {})))
        _ (when-not raw (throw (p/not-found (:anchor sdef) anchor-id)))
        row (inv/decode-row anchor-rdef raw)
        attention (into {}
                        (map (fn [[f nominated]]
                               [f (= nominated (get-in row [:data f]))]))
                        (:attention sdef))
        members (into {}
                      (map (fn [m]
                             [(p/wire-key (:name m))
                              {"items"
                               (mapv (fn [r]
                                       (render/envelope-summary
                                        (get (inv/resources eng) (:target m))
                                        r ctx-opts))
                                     (member-rows eng row m))}]))
                      (:members sdef))]
    (merge
     (p/wire-value
      {:waymark "10"
       :kind "surface"
       :self (str "/api/surfaces/" (:name sdef) "/" anchor-id)
       :name (:name sdef)
       :showcase (:showcase sdef)
       :attention attention})
     {"anchor" (render/envelope anchor-rdef row ctx-opts)
      "members" members})))

(defn well-known-entry
  "The surfaces map the well-known document lists: name → href
  template."
  [surfaces]
  (into (sorted-map)
        (map (fn [[sname _]]
               [sname {:href (str "/api/surfaces/" sname "/{anchor-id}")}]))
        surfaces))
