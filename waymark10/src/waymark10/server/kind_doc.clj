(ns waymark10.server.kind-doc
  "What the MCP door says about a kind before any row is held: one
  action's static digest (waymark_schema's `actions`), and the kind's
  declared purpose (ticket a1f19281) — at waymark_schema's top and as
  one line of the connect-time instructions. Split out of
  waymark10.server.mcp, which may not grow (ci/ns-size-grandfather.edn)."
  (:require [clojure.string :as str]
            [waymark10.declaration :as declaration]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]))

(set! *warn-on-reflection* true)

(defn action-digest
  "One action of one kind as a static reading of the declaration:
  everything an agent needs to plan with, before it holds a row.

  It is not the envelope's entry and does not pretend to be — no row
  exists here, so acceptance sets are unfolded, availability is
  unjudged and the per-origin consequence map arrives whole rather
  than resolved. What it IS is projected: an action the grant does not
  admit never appears, and an argument the grant denies is not in the
  input schema (`render/project-input-js`, the same projection the
  envelope's own entry wears)."
  [vis rdef a]
  (let [input-js (when (:input a)
                   (-> (render/project-input-js
                        (schema/json-schema (:input a))
                        (when-some [arg? (:arg? vis)]
                          #(arg? (:kind rdef) (:name a) %)))
                       (render/edit-input-js a)
                       (render/engine-written-js a rdef)))]
    (cond-> {:name (name (:name a))
             :from (mapv name (sort (:from a)))
             :to (if (set? (:to a)) (mapv name (sort (:to a))) (name (:to a)))
             :safety (cond-> {:idempotent (boolean (get-in a [:safety :idempotent]))
                              :reversible (boolean (get-in a [:safety :reversible]))
                              :confirm (boolean (get-in a [:safety :confirm]))}
                       (get-in a [:safety :fence]) (assoc :fence true)
                       (get-in a [:safety :consequence])
                       (assoc :consequence (get-in a [:safety :consequence]))
                       (get-in a [:safety :one-way])
                       (assoc :one_way (get-in a [:safety :one-way]))
                       (get-in a [:safety :final])
                       (assoc :final (get-in a [:safety :final])))}
      input-js (assoc :input input-js)
      (seq (:display a)) (assoc :display (:display a)))))

(defn- served-kinds
  "The kinds this caller may hear named: served by the engine, and
  seen by the grant or open as vocabulary — discover's own rule."
  [eng vis]
  (let [served (inv/resources eng)]
    (fn [k]
      (and (contains? served k)
           (or (nil? vis) (:vocabulary-open? vis) ((:kind? vis) k))))))

(defn purpose
  "The kind's declared purpose as waymark_schema shows it, a
  neighbour this caller may not hear named left out."
  [rdef eng vis]
  (declaration/purpose-wire rdef (served-kinds eng vis)))

(defn purpose-lines
  "What the connect-time instructions carry after their own account:
  one line per kind this caller may hear named that declares a
  :purpose — what it is, when to use it, the neighbours it is not.
  \"\" when no such kind is served."
  [eng session]
  (let [served? (served-kinds eng (:visibility session))
        lines (for [[k r] (sort-by key (inv/resources eng))
                    :when (and (:purpose r) (served? k))
                    :let [p (declaration/purpose-wire r served?)]]
                (str "- " (name k) ": " (:what p) " " (:when p)
                     (when (seq (:not p))
                       (str " Not " (str/join ", " (map :kind (:not p))) "."))))]
    (if (seq lines)
      (str "\n\nWHAT THE KINDS ARE FOR:\n" (str/join "\n" lines))
      "")))
