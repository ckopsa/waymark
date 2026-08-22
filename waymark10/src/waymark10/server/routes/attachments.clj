(ns waymark10.server.routes.attachments
  "The attachment bytes door: the two verbs that carry the payload the
  row only describes.

  Static, and its literal second segment is the point —
  /api/attachments/{id}/bytes reads as an address of its own, not as
  the plural grammar's row."
  (:require [waymark10.server.attachments :as attachments]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]))

(set! *warn-on-reflection* true)

(defn- attachment-rdef [eng id]
  (or (get (inv/resources eng) :attachment)
      (throw (p/not-found :attachment id))))

(defn- bytes-put [eng]
  (fn [{{:keys [id]} :path-params :as req}]
    (let [rdef (attachment-rdef eng id)]
      (router/check-row! req rdef id)
      (let [result (attachments/put-bytes! eng id (:body req))]
        (router/envelope-response eng rdef (:row result) req 200 nil)))))

(defn- bytes-get [eng]
  (fn [{{:keys [id]} :path-params :as req}]
    (router/check-row! req (attachment-rdef eng id) id)
    (attachments/get-bytes eng id)))

(defn routes [eng]
  {:module :attachments
   :static [["/api/attachments/:id/bytes" {:put (bytes-put eng)
                                           :get (bytes-get eng)}]]})
