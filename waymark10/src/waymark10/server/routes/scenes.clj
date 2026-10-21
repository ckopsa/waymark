(ns waymark10.server.routes.scenes
  "The scene's wire door (docs/spec-scenes.md, Holes in a say): POST
  /api/-/scenes/say fills one shot's `say` for the performer, which
  draws only a plain string, keeping one take run per caller and take
  so a `$day` hole of `window: \"shot\"` reads since that take's last
  day caption."
  (:require [clojure.string :as str]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.server.scenes :as scenes]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def say-path "/api/-/scenes/say")

(defn- sees
  "Whether a caller of visibility `vis` sees a kind whole; no visibility
  is a caller no grant narrows."
  [vis]
  (fn [kind]
    (or (nil? vis)
        (boolean (some-> (:whole-kind? vis) (apply [kind]))))))

(defn- say-doc
  "POST /api/-/scenes/say {say, take} → {text}: the shot's `say` as the
  scene holds it, filled through the caller's grant (`fill-say-as`), on
  the run of the take the caller names `take` (`take-run-of`). A say
  `check` would refuse is a 422 with its reason; anonymous gets the
  concealment 404 the other doors give."
  [eng]
  (fn [req]
    (let [principal (router/principal-of req)]
      (when (or (nil? principal) (= (:id principal) (:id t/anonymous)))
        (throw (p/problem :not-found 404 "Not found"
                          {:detail "No such route."})))
      (let [{say :say take-name :take} (router/read-body req)
            take-name (some-> take-name str str/trim not-empty)]
        (when-not take-name
          (throw (p/problem :invalid-params 422 "Invalid parameters"
                            {:detail "take is a string that names the take."})))
        (when-some [why (scenes/say-refusal eng say)]
          (throw (p/problem :invalid-params 422 "Invalid parameters"
                            {:detail why})))
        (router/json-response
         200 {:text (scenes/fill-say-as
                     eng say
                     (scenes/take-run-of [(str (:id principal)) take-name]
                                         (java.time.Instant/now) (:now-fn eng))
                     (sees (router/visibility-of req)))})))))

(defn routes [eng]
  {:module :scenes
   :static [[say-path {:post (say-doc eng)}]]})
