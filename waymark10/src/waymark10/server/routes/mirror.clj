(ns waymark10.server.routes.mirror
  "The mirror's operator door: the manual sync trigger.

  The route mints a JOB, so this module leans on the jobs module for
  the envelope it answers with — recorded here rather than smoothed
  over: an engine assembled with mirror and without jobs would answer
  this door with a nil rdef. The enrollment table cannot express that
  dependency today (docs/spec-modularization.md's recorded punts), and
  nobody assembles that combination yet."
  (:require [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- mirror-sync-trigger
  "POST /api/-/mirrors/{plural}/{resync|discover}: the manual sync
  trigger. Mints the sync job the discovery daemon services on its
  next beat (mirror/request-sync!) and answers 202 with the job
  envelope and its Location — the defer seam's own shape; a job for
  this kind and flavor already queued or running answers 200 with
  ITS envelope instead (one pending pass per kind and flavor).
  Identity: anonymous gets no operational lever — the job records
  who asked. A SCOPED request is no longer turned away at the
  threshold (waymark-rci widened): it passes when its grant names
  the flavor as an action on the mirror kind — the flavors ride the
  kind's vocabulary (invoke/action-names), so an ask can name them —
  and EVERY scoped miss (unknown collection, ungranted kind,
  ungranted flavor, a kind that is no mirror) answers the ONE
  route-shaped 404, so a probing leash cannot tell 'denied to you'
  from 'never existed'. The requester watches the minted job through
  the grants own-surface."
  [eng]
  (fn [{{:keys [plural action]} :path-params :as req}]
    (let [principal (router/principal-of req)
          flavor (keyword action)
          route-404 (fn []
                      (throw (p/problem :not-found 404 "Not found"
                                        {:detail "No such route."})))
          mint (fn [rdef]
                 (let [{:keys [job existing?]}
                       (mirror/request-sync! eng (:kind rdef) flavor principal)]
                   (router/envelope-response
                    eng (get (inv/resources eng) :job) job req
                    (if existing? 200 202)
                    (if existing?
                      {}
                      {"Location" (str "/api/jobs/" (:id job))}))))]
      (if-some [vis (router/visibility-of req)]
        ;; the leashed door: judge everything, answer one way
        (let [rdef (some (fn [[_ r]] (when (= plural (:plural r)) r))
                         (inv/resources eng))]
          (if (and (contains? #{:resync :discover} flavor)
                   rdef (:mirror rdef)
                   ((:kind? vis) (:kind rdef))
                   ((:action? vis) (:kind rdef) flavor))
            (mint rdef)
            (route-404)))
        (do
          (when (= (:id principal) (:id t/anonymous))
            (throw (p/problem :authentication-required 401
                              "Authentication required"
                              {:detail (str "A sync trigger records who asked; "
                                            "authenticate and retry.")})))
          (when-not (contains? #{:resync :discover} flavor)
            (route-404))
          (let [rdef (router/rdef-by-plural eng plural)]
            (when-not (:mirror rdef)
              (throw (p/problem
                      :not-a-mirror 404 "Not a mirror"
                      {:detail (str "Sync passes belong to mirror kinds — "
                                    plural " holds its own truth.")})))
            (mint rdef)))))))

(defn routes [eng]
  {:module :mirror
   :static [["/api/-/mirrors/:plural/:action"
             {:post (mirror-sync-trigger eng)}]]})
