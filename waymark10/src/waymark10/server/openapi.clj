(ns waymark10.server.openapi
  "The OpenAPI overlay (phase 9b, waymark9 server/openapi.py): GET
  /api/openapi.json answers a derived 3.1 document — per kind, the
  collection/create/get paths and every action's act (+bulk/batch/
  draft) path with the REAL input schemas from the declarations,
  action descriptions from display and the machine, and the problem
  responses referenced once (components.responses). Derived from the
  registry per request, like everything else — the document can never
  drift from what the router serves.

  Scope, recorded: enough for /docs-style tooling, not a full OAS
  validation suite — response BODY schemas are stubs (the envelope's
  shape lives in the conformance library, not here), the SSE/
  attachment-bytes/surfaces/collab/well-known routes are undocumented,
  securitySchemes are absent (identity is dev headers or the engine's
  OIDC config), and a grant-scoped request 404s the route (the
  document names every kind; concealment wins)."
  (:require [clojure.string :as str]
            [waymark10.machine :as machine]
            [waymark10.schema :as schema]
            [waymark10.server.collections :as collections]
            [waymark10.server.invoke :as inv]))

(set! *warn-on-reflection* true)

;; ── the problem responses, referenced once ──────────────────────────

(def ^:private problem-content
  {"application/problem+json" {:schema {:type "object"}}})

(def components
  {:responses
   {"not_found" {:description "No such resource, collection or action (concealment answers this too)"
                 :content problem-content}
    "refused" {:description "Guard refused, wrong state, or unacknowledged warnings — detail carries the advertised reason; remedies and becomes_available ride along"
               :content problem-content}
    "version_conflict" {:description "If-Match did not match the current etag; re-read and retry"
                        :content problem-content}
    "schema_invalid" {:description "Input failed validation, field-keyed errors"
                      :content problem-content}
    "idempotency_key_required" {:description "The action is not idempotent; send an Idempotency-Key header"
                                :content problem-content}}})

(defn- resp-ref [k]
  {"$ref" (str "#/components/responses/" k)})

(def ^:private act-responses
  {"200" {:description "The post-transition envelope (or {valid: true} on dry_run)"}
   "404" (resp-ref "not_found")
   "409" (resp-ref "refused")
   "412" (resp-ref "version_conflict")
   "422" (resp-ref "schema_invalid")
   "428" (resp-ref "idempotency_key_required")})

;; ── per-kind paths ──────────────────────────────────────────────────

(defn- body-of [js]
  {:content {"application/json" {:schema js}}})

(defn- action-description [defn' rdef]
  (let [safety (:safety defn')]
    (str "Transition: "
         (str/join ", " (sort (map name (:from defn'))))
         " → " (name (:to defn'))
         (when (contains? (:terminal rdef) (:to defn')) " (terminal)")
         ". Safety: idempotent=" (boolean (:idempotent safety))
         ", reversible=" (boolean (:reversible safety))
         ", confirm=" (boolean (:confirm safety))
         (when (:fence safety) ", fenced (If-Match required)") "."
         (when-some [d (get-in defn' [:display :description])]
           (str " " d)))))

(defn- act-op [rdef defn']
  (let [label (or (get-in defn' [:display :label]) (name (:name defn')))]
    (cond-> {:tags [(name (:kind rdef))]
             :summary label
             :description (action-description defn' rdef)
             :parameters [{:name "dry_run" :in "query" :required false
                           :schema {:type "string" :enum ["1"]}}]
             :responses act-responses}
      (:input defn')
      (assoc :requestBody (body-of (schema/json-schema (:input defn')))))))

(def ^:private id-param
  {:name "id" :in "path" :required true :schema {:type "string"}})

(defn- bulk-body [defn']
  (let [spec (if (map? (:bulk defn')) (:bulk defn') {})
        base (if (:input defn')
               (schema/json-schema (:input defn'))
               {:type "object" :properties {}})]
    (body-of
     (-> base
         (update :properties assoc
                 :ids {:type "array" :items {:type "string"}
                       :minItems 1 :maxItems (:max-items spec 100)})
         (update :required (fnil conj []) "ids")))))

(defn- kind-paths [rdef]
  (let [kname (name (:kind rdef))
        col (str "/api/" (:plural rdef))
        qs (collections/query-input-schema rdef)
        create-model (or (:create-schema rdef) (:schema rdef))
        base
        {col
         {:get {:tags [kname]
                :summary (str "Query " (:plural rdef))
                :parameters (mapv (fn [[pname pschema]]
                                    {:name pname :in "query" :required false
                                     :schema pschema})
                                  (sort-by key (:properties qs)))
                :responses {"200" {:description (str kname " collection envelope")}
                            "422" (resp-ref "schema_invalid")}}
          :post {:tags [kname]
                 :summary (str "Create a " kname " (initial-state transition)")
                 :requestBody (body-of (schema/json-schema create-model))
                 :responses {"201" {:description "The new resource envelope; Location carries its self"}
                             "409" (resp-ref "refused")
                             "422" (resp-ref "schema_invalid")}}}
         (str col "/{id}")
         {:get {:tags [kname]
                :summary (str "Fetch a " kname " envelope")
                :parameters [id-param]
                :responses {"200" {:description "The resource envelope"}
                            "404" (resp-ref "not_found")}}}}]
    (reduce
     (fn [paths defn']
       (let [aname (name (:name defn'))
             op (act-op rdef defn')
             paths
             (if (:bulk defn')
               (assoc paths (str col "/-/" aname)
                      {:post (-> op
                                 (assoc :summary (str (:summary op) " (bulk)"))
                                 (assoc :requestBody (bulk-body defn'))
                                 (update :responses assoc
                                         "202" {:description "Deferred: the job envelope; Location carries the job"}))})
               (assoc paths (str col "/{id}/-/" aname)
                      {:post (update op :parameters #(into [id-param] %))}))
             paths
             (if (:batch defn')
               (assoc paths (str col "/{id}/-/" aname "/batch")
                      {:post (-> op
                                 (assoc :summary (str (:summary op) " (batch)"))
                                 (assoc :requestBody
                                        (body-of {:type "object"
                                                  :properties {:inputs {:type "array"
                                                                        :items (schema/json-schema (:input defn'))}}
                                                  :required ["inputs"]}))
                                 (assoc :parameters [id-param]))})
               paths)]
         (if (get-in defn' [:edit :draft])
           (assoc paths (str col "/{id}/-/" aname "/draft")
                  {:get {:tags [kname]
                         :summary (str "The stored draft of " aname)
                         :parameters [id-param]
                         :responses {"200" {:description "{values, base_version, prefill}"}
                                     "404" (resp-ref "not_found")}}
                   :put {:tags [kname]
                         :summary (str "Save a draft of " aname " (partial input, validated)")
                         :parameters [id-param]
                         :requestBody (body-of (schema/json-schema (:input defn')))
                         :responses {"200" {:description "The saved draft view"}
                                     "422" (resp-ref "schema_invalid")}}
                   :delete {:tags [kname]
                            :summary (str "Discard the draft of " aname)
                            :parameters [id-param]
                            :responses {"204" {:description "Discarded"}}}})
           paths)))
     base
     (machine/actions-seq rdef))))

(defn document
  "The derived OpenAPI 3.1 document for everything this engine
  serves."
  [eng]
  {:openapi "3.1.0"
   :info {:title "waymark10"
          :version "10"
          :description (str "Derived from the resource declarations — "
                            "every action with its real input schema.")}
   :paths (into (sorted-map)
                (mapcat (fn [[_ rdef]] (kind-paths rdef)))
                (sort-by key (inv/resources eng)))
   :components components})
