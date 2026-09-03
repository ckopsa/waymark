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
  validation suite — response bodies reference the SHARED shapes
  (components.schemas: envelope, collection, problem, bulk_report —
  structural, not per-kind; the per-kind data model rides
  /api/schemas/{kind} and the envelope's own affordances), the
  surfaces routes document per declared surface (batch F), the SSE/
  attachment-bytes/collab/well-known routes stay undocumented,
  securitySchemes name both doors (the OIDC bearer and the dev
  header), and a grant-scoped request 404s the route (the document
  names every kind; concealment wins)."
  (:require [clojure.string :as str]
            [waymark10.machine :as machine]
            [waymark10.schema :as schema]
            [waymark10.server.collections :as collections]
            [waymark10.server.invoke :as inv]))

(set! *warn-on-reflection* true)

;; ── the shared shapes, referenced once (components.schemas) ─────────

(defn- schema-ref [k]
  {"$ref" (str "#/components/schemas/" k)})

(def ^:private envelope-schema
  {:type "object"
   :description "The resource envelope: identity, state, summary, data, and the affordances (actions/unavailable) the server projected for THIS caller."
   :properties {:waymark {:type "string"}
                :kind {:type "string"}
                :self {:type "string"}
                :state {:type "string"}
                :summary {:type "string"}
                :data {:type "object"}
                :parts {:type "object"}
                :actions {:type "object"}
                :unavailable {:type "object"}
                :links {:type "object"}
                :meta {:type "object"}}
   :required ["kind" "self" "state" "summary"]})

(def ^:private collection-schema
  {:type "object"
   :description "The collection envelope: items as envelope-minus-data summaries, the real filtered total, pagination, and the create/query/bulk affordances."
   :properties {:waymark {:type "string"}
                :kind {:type "string"}
                :self {:type "string"}
                :state {:type "string"}
                :summary {:type "string"}
                :data {:type "object"
                       :properties {:items {:type "array"
                                            :items (schema-ref "envelope")}
                                    :total {:type "integer"}
                                    :page {:type "object"
                                           :properties {:size {:type "integer"}
                                                        :number {:type "integer"}}}}}
                :actions {:type "object"}
                :links {:type "object"}}
   :required ["kind" "self" "data"]})

(def ^:private problem-schema
  {:type "object"
   :description "RFC 9457 problem details; refusals carry remedies and becomes_available beside the standard members."
   :properties {:type {:type "string"}
                :title {:type "string"}
                :status {:type "integer"}
                :detail {:type "string"}
                :errors {:type "object"}
                :remedies {:type "array" :items {:type "string"}}
                :becomes_available {:type "object"}}
   :required ["title" "status"]})

(def ^:private bulk-report-schema
  {:type "object"
   :description "The bulk/batch report: per-call counts and the refusal list, one entry per item that did not land."
   :properties {:kind {:type "string" :const "bulk_report"}
                :action {:type "string"}
                :data {:type "object"
                       :properties {:succeeded {:type "integer"}
                                    :refused {:type "integer"}
                                    :failed {:type "integer"}
                                    :refusals {:type "array"
                                               :items {:type "object"
                                                       :properties {:self {:type "string"}
                                                                    :reason {:type "string"}}}}}}
                :links {:type "object"}}
   :required ["kind" "action" "data"]})

;; ── the problem responses, referenced once ──────────────────────────

(def ^:private problem-content
  {"application/problem+json" {:schema (schema-ref "problem")}})

(def ^:private envelope-content
  {"application/waymark+json" {:schema (schema-ref "envelope")}})

(def ^:private collection-content
  {"application/waymark+json" {:schema (schema-ref "collection")}})

(def ^:private bulk-report-content
  {"application/waymark+json" {:schema (schema-ref "bulk_report")}})

(def components
  {:schemas
   {"envelope" envelope-schema
    "collection" collection-schema
    "problem" problem-schema
    "bulk_report" bulk-report-schema}
   :securitySchemes
   {"bearer" {:type "http" :scheme "bearer" :bearerFormat "JWT"
              :description "The OIDC relying party (engines configured with :oidc): an ID/access token whose claims resolve the principal."}
    "devHeader" {:type "apiKey" :in "header" :name "X-Waymark-Principal"
                 :description "The dev identity headers (engines without :oidc): X-Waymark-Principal names the principal; X-Waymark-Roles and X-Waymark-Actor-Type ride beside it."}}
   :responses
   {"not_found" {:description "No such resource, collection or action (concealment answers this too)"
                 :content problem-content}
    "refused" {:description "Guard refused, wrong state, or unacknowledged warnings — detail carries the advertised reason; remedies and becomes_available ride along"
               :content problem-content}
    "version_conflict" {:description "If-Match did not match the current etag; re-read and retry"
                        :content problem-content}
    "schema_invalid" {:description "Input failed validation, field-keyed errors"
                      :content problem-content}
    "unknown_field" {:description "fields= named a field outside this caller's published vocabulary (never declared, redacted by the grant, or secret — one answer for all three); the problem's fields member lists the vocabulary"
                     :content problem-content}
    "idempotency_key_required" {:description "The action is not idempotent; send an Idempotency-Key header"
                                :content problem-content}}})

(defn- resp-ref [k]
  {"$ref" (str "#/components/responses/" k)})

(def ^:private act-responses
  {"200" {:description "The post-transition envelope (or {valid: true} on dry_run)"
          :content envelope-content}
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
        ;; the secret disposition (waymark-kyg): a secret field drops
        ;; from the PUBLISHED create body though the door accepts it —
        ;; the union covers a separate :create-schema spelling the
        ;; mark either place
        secret (into (schema/secret-fields (:schema rdef))
                     (schema/secret-fields create-model))
        base
        {col
         {:get {:tags [kname]
                :summary (str "Query " (:plural rdef))
                :parameters (conj
                             (mapv (fn [[pname pschema]]
                                     {:name pname :in "query" :required false
                                      :schema pschema})
                                   (sort-by key (:properties qs)))
                             ;; the caller's projection (waymark-pywy.2)
                             ;; lives beside the grammar, not in it: the
                             ;; query input schema is the FILTER/SORT
                             ;; vocabulary an embed reuses as its columns,
                             ;; and an embed carries no fields=
                             {:name "fields" :in "query" :required false
                              :schema {:type "string"}
                              :description (str "Comma-separated field names: each item's "
                                                "fields narrows to exactly these — always a "
                                                "subset of what your grant projects, never "
                                                "more. A name outside your published schema "
                                                "(/api/schemas/" kname ") is a 400 naming "
                                                "the vocabulary.")})
                :responses {"200" {:description (str kname " collection envelope")
                                   :content collection-content}
                            "400" (resp-ref "unknown_field")
                            "422" (resp-ref "schema_invalid")}}
          :post {:tags [kname]
                 :summary (str "Create a " kname " (initial-state transition)")
                 :requestBody (body-of (schema/conceal
                                        (schema/json-schema create-model)
                                        secret))
                 :responses {"201" {:description "The new resource envelope; Location carries its self"
                                    :content envelope-content}
                             "409" (resp-ref "refused")
                             "422" (resp-ref "schema_invalid")}}}
         (str col "/{id}")
         {:get {:tags [kname]
                :summary (str "Fetch a " kname " envelope")
                :parameters [id-param]
                :responses {"200" {:description "The resource envelope"
                                   :content envelope-content}
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
                                         "200" {:description "The bulk report"
                                                :content bulk-report-content}
                                         "202" {:description "Deferred: the job envelope; Location carries the job"
                                                :content envelope-content}))})
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
                                 (update :responses assoc
                                         "200" {:description "The batch report"
                                                :content bulk-report-content})
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

(defn- surface-paths
  "One documented path per declared surface (batch F): the composed
  decision screen at /api/surfaces/{name}/{anchor-id}, or the bare
  /api/surfaces/{name} for an anchorless surface's standing queue."
  [surfaces]
  (into {}
        (map (fn [[sname sdef]]
               (if (:anchor sdef)
                 [(str "/api/surfaces/" (name sname) "/{id}")
                  {:get {:tags ["surfaces"]
                         :summary (str "The " (name sname) " surface")
                         :description (str "The composed decision screen anchored on one "
                                           (name (get sdef :anchor "resource"))
                                           " — panels of related envelopes, assembled per request.")
                         :parameters [{:name "id" :in "path" :required true
                                       :schema {:type "string"}
                                       :description "The anchor resource's id"}]
                         :responses {"200" {:description "The surface envelope: the anchor plus its panels"}
                                     "404" (resp-ref "not_found")}}}]
                 [(str "/api/surfaces/" (name sname))
                  {:get {:tags ["surfaces"]
                         :summary (str "The " (name sname) " surface")
                         :description "The anchorless composed queue — collection panels with truthful counts, assembled per request."
                         :responses {"200" {:description "The surface envelope: the member panels, each count beside its items"}
                                     "404" (resp-ref "not_found")}}}])))
        surfaces))

(defn document
  "The derived OpenAPI 3.1 document for everything this engine
  serves — every kind's paths, the declared surfaces, the shared
  response shapes (components.schemas), and both identity doors
  (securitySchemes: the OIDC bearer and the dev header)."
  [eng]
  {:openapi "3.1.0"
   :info {:title "waymark10"
          :version "10"
          :description (str "Derived from the resource declarations — "
                            "every action with its real input schema.")}
   :security [{"bearer" []} {"devHeader" []}]
   :paths (into (sorted-map)
                (concat
                 (mapcat (fn [[_ rdef]] (kind-paths rdef))
                         (sort-by key (inv/resources eng)))
                 (surface-paths (:surfaces eng))))
   :components components})
