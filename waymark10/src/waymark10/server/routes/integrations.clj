(ns waymark10.server.routes.integrations
  "An integration's receiver (docs/spec-integrations.md § 3, build step
  2): POST /api/-/events/<name>. The source is no principal of the
  house: its signature is its credential, judged in
  integrations/receive!, so the door reads no token. It answers 202 the
  moment the receipt commits and 200 with the first receipt for a
  duplicate; every refusal is the problem receive! throws."
  (:require [waymark10.server.integrations :as integrations]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(defn- receiver [eng]
  (fn [{{nm :name} :path-params :keys [headers body]}]
    (let [{:keys [status receipt duplicate]}
          (integrations/receive! eng nm headers body)]
      {:status status
       :headers {"Content-Type" "application/json"}
       :body (wire/write-json {:receipt receipt :duplicate duplicate})})))

(defn routes [eng]
  {:module :integrations
   :static [["/api/-/events/:name"
             {:post (receiver eng)}]]})
