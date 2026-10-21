(ns waymark10.server.routes.scenes
  "The scene's wire door (docs/spec-scenes.md, Holes in a say): POST
  /api/-/scenes/say fills one shot's `say` for the performer, which
  draws only a plain string, keeping one take run per caller and take
  so a `$day` hole of `window: \"shot\"` reads since that take's last
  day caption. POST /api/-/scenes/open resolves one shot's `open` the
  same way: a `$day-row` open to the screen of its clock day's top move."
  (:require [clojure.string :as str]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]
            [waymark10.server.scenes :as scenes]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def say-path "/api/-/scenes/say")

(def open-path "/api/-/scenes/open")

(defn- sees
  "Whether a caller of visibility `vis` sees a kind whole; no visibility
  is a caller no grant narrows."
  [vis]
  (fn [kind]
    (or (nil? vis)
        (boolean (some-> (:whole-kind? vis) (apply [kind]))))))

(defn- refuse [why]
  (throw (p/problem :invalid-params 422 "Invalid parameters" {:detail why})))

(defn- call-of
  "A scene door's call as [principal body take]: anonymous gets the
  concealment 404 the other doors give, and a call that names no take
  a 422."
  [req]
  (let [principal (router/principal-of req)]
    (when (or (nil? principal) (= (:id principal) (:id t/anonymous)))
      (throw (p/problem :not-found 404 "Not found"
                        {:detail "No such route."})))
    (let [body (router/read-body req)
          take-name (some-> (:take body) str str/trim not-empty)]
      (when-not take-name
        (refuse "take is a string that names the take."))
      [principal body take-name])))

(defn- say-doc
  "POST /api/-/scenes/say {say, take} → {text}: the shot's `say` as the
  scene holds it, filled through the caller's grant (`fill-say-as`), on
  the run of the take the caller names `take` (`take-run-of`). A say
  `check` would refuse is a 422 with its reason; anonymous gets the
  concealment 404 the other doors give."
  [eng]
  (fn [req]
    (let [[principal {say :say} take-name] (call-of req)]
      (when-some [why (scenes/say-refusal eng say)]
        (refuse why))
      (router/json-response
       200 {:text (scenes/fill-say-as
                   eng say
                   (scenes/take-run-of [(str (:id principal)) take-name])
                   (sees (router/visibility-of req)))}))))

(defn- open-doc
  "POST /api/-/scenes/open {open, take} → {open}: the shot's `open` as
  the scene holds it, as an open the performer already takes
  (`open-as`): a `$day-row` open resolved through the caller's grant, any
  other open as given. An open `check` would refuse is a 422 with its
  reason, and so is a domain of which the caller sees no kind;
  anonymous gets the concealment 404 the other doors give."
  [eng]
  (fn [req]
    (let [[_ {open :open}] (call-of req)]
      (when-not (map? open)
        (refuse "open is the shot's open, a map."))
      (when-some [why (scenes/open-refusal open)]
        (refuse why))
      (let [o (scenes/open-as eng open (sees (router/visibility-of req)))]
        (when-not o
          (refuse "the open's domain has no kind this caller sees."))
        (router/json-response 200 {:open o})))))

(defn routes [eng]
  {:module :scenes
   :static [[say-path {:post (say-doc eng)}]
            [open-path {:post (open-doc eng)}]]})
