(ns waymark10.server.problems
  "Refusals as data: every problem is a tagged ex-info the router
  projects to RFC 9457 problem+json (->response). Every refusal
  answers \"what would a competent person do next\" — reasons,
  remedies, becomes-available, acknowledge instructions ride the
  data. This namespace also owns the kebab→snake key converter the
  whole wire boundary shares (wire-value)."
  (:require [clojure.string :as str]
            [waymark10.summary :as summary]
            [waymark10.wire :as wire]))

(def base-uri "https://waymark.dev/problems/")

(defn problem
  [type status title data]
  (ex-info (str title (when-some [d (:detail data)] (str ": " d)))
           (merge {:waymark10/problem type
                   :type (str base-uri (name type))
                   :status status
                   :title title}
                  data)))

(defn problem? [e]
  (boolean (some-> e ex-data :waymark10/problem)))

(defn not-found [kind id]
  (problem :not-found 404 "Not found"
           {:detail (str "No " (name kind) " " (pr-str id) ".")}))

(defn no-such-action [kind action]
  (problem :not-found 404 "Not found"
           {:detail (str (name kind) " has no action " (name action) ".")}))

(defn wrong-state [action state from resource]
  (problem :wrong-state 409 "Wrong state"
           {:detail (str "Available in state(s) "
                         (str/join ", " (map summary/state-label (sort from)))
                         "; the resource is " (summary/state-label state) ".")
            :action-attempted action
            :state state
            :becomes-available {:in-states (vec (sort from))}
            :resource resource}))

(defn version-conflict [action resource]
  (problem :version-conflict 412 "Version conflict"
           {:detail "The resource changed since you read it. Re-read and retry with the current etag."
            :action-attempted action
            :resource resource}))

(defn schema-invalid [action errors]
  (problem :schema-invalid 422 "Input failed validation"
           {:action-attempted action :errors errors}))

(defn guard-refused [action state reason denier resource]
  (problem :guard-refused 409 "Refused"
           {:detail reason
            :action-attempted action
            :state state
            :guard (:guard denier)
            :remedies (:remedies denier)
            :becomes-available (:becomes-available denier)
            :resource resource}))

(defn warning-refused
  "The E1 acknowledge protocol: one problem carries every warning; the
  client re-sends with Waymark-Acknowledge naming what it accepts."
  [action warnings]
  (problem :warning-required 409 "Acknowledgement required"
           {:detail "Advisory guard(s) warned; acknowledge them to proceed."
            :action-attempted action
            :severity "warning"
            :warnings (vec warnings)
            :acknowledge {:header "Waymark-Acknowledge"
                          :names (mapv :name warnings)}}))

(defn idempotency-key-required [action]
  (problem :idempotency-key-required 428 "Idempotency-Key required"
           {:detail (str "Action " (name action) " is not idempotent; send an "
                         "Idempotency-Key header so retries are safe.")
            :action-attempted action}))

(defn idempotency-key-reuse [action]
  (problem :idempotency-key-reuse 409 "Idempotency-Key reused"
           {:detail "This key was used with a different request body."
            :action-attempted action}))

(defn derived-tampered [action facts]
  (problem :derived-tampered 500 "Handler wrote derived facts"
           {:detail (str "One fact, one definition: handler for " (name action)
                         " wrote " (pr-str facts) ", which only their derivations may write.")
            :action-attempted action}))

(defn malformed-body [detail]
  (problem :malformed-body 400 "Malformed JSON body"
           {:detail detail}))

;; ── the wire projection ─────────────────────────────────────────────

(defn wire-key
  "Internal kebab keyword → snake wire string. JSON-Schema extension
  keys (x-display, x-ref, …) keep their spec-conventional hyphens."
  ^String [k]
  (let [s (if (keyword? k) (name k) (str k))]
    (if (str/starts-with? s "x-") s (str/replace s "-" "_"))))

(defn wire-value
  "The one kebab→snake boundary: keyword map keys become snake
  strings, keyword values render as names (:plan/assign_meal →
  \"plan.assign_meal\"). Values are otherwise untouched — nils are
  data (an envelope's null fields), pruning is the caller's call."
  [v]
  (cond
    (map? v) (into {} (map (fn [[k x]] [(wire-key k) (wire-value x)])) v)
    (sequential? v) (mapv wire-value v)
    (keyword? v) (if-some [ns' (namespace v)]
                   (str ns' "." (name v))
                   (name v))
    :else v))

(defn prune
  "Drop nil-valued map entries, recursively. For refusal bodies —
  never for envelope :data, where null is a value."
  [v]
  (cond
    (map? v) (into {} (keep (fn [[k x]] (when (some? x) [k (prune x)]))) v)
    (sequential? v) (mapv prune v)
    :else v))

(defn ->response
  "The tagged ex-info as an RFC 9457 ring response: status from the
  data, application/problem+json, body = the ex-data minus the tag,
  snake-cased, present keys only."
  [e]
  (let [d (ex-data e)]
    {:status (:status d 500)
     :headers {"Content-Type" "application/problem+json"}
     :body (wire/write-json (-> (dissoc d :waymark10/problem)
                                prune
                                wire-value))}))
