(ns waymark10.server.routes.dashboard
  "The dashboard measure's one route (dashboard measures 1/3): GET
  /api/dashboard_slots/{id}/-/measure answers the slot's number over
  its time window (waymark10.server.measure). An engine whose app did
  not opt into the dashboard kinds answers the not-found any unserved
  collection draws; a scoped reader who cannot see the slot, the same
  not-found the row's own GET gives."
  (:require [waymark10.dashboard :as dash]
            [waymark10.server.invoke :as inv]
            [waymark10.server.measure :as measure]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(defn- measure-get [eng]
  (fn [{{:keys [id]} :path-params :as req}]
    (let [rdef (or (get (inv/resources eng) dash/slot-kind)
                   (throw (p/not-found "collection" "dashboard_slots")))
          _ (router/check-kind! req rdef)
          _ (router/check-row! req rdef id)
          st (:storage eng)
          row (or (store/with-tx st #(store/load-row st % dash/slot-kind id {}))
                  (throw (p/not-found dash/slot-kind id)))]
      (router/json-response
       200 (measure/report eng row (router/visibility-of req))))))

(defn routes [eng]
  {:module :dashboard
   :static [["/api/dashboard_slots/:id/-/measure" {:get (measure-get eng)}]]})
