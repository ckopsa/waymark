(ns factory10.resources.org-plan-onboarding
  "An org_plan's onboarding seat (ticket 6b49540c), moved out of
  factory10.resources.org-plan to make room there.

  Filing a found plan seats `onboarding-<name>` from factorykit's
  onboarding template, in the same transaction, as the filer: the
  seat door opens only for a person's hand (seats/a-person), and the
  filer's create is the yes. The seat is born keyless (seat spec
  R-12.12): onboarding_note, which next_step shows until findings
  land, tells the person to offer it a key and sit.
  The birth is skipped, and onboarding_note says why, when the template's
  bench powers are not registered here, when no model resolves, or
  when the seat door would refuse it; the plan is filed either way.

  A PLAN WITH NO BRIEF SEATS NOTHING (ticket 9e59cdbd). Its
  onboarding_note asks for the brief, and the plan's `write_brief`
  door seats the onboarder when the brief is written."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.seats :as seats]))

(def ^:private onboarding-template "seats/onboarding.edn")

(defn- state-name [row] (some-> (:state row) name))

(defn- onboarding-seat-name [org] (str "onboarding-" org))

(defn- found-rows
  "`kind`'s rows matching `where`, through the ctx :find hook. A kind
  this engine does not serve reads as none."
  [ctx kind where]
  (when-some [find' (:find ctx)]
    (try (find' kind where {:limit 1000}) (catch Exception _ nil))))

(defn active-onboarding-seat [ctx org]
  (first (found-rows ctx :seat {:name (onboarding-seat-name org) :state "active"})))

(def ^:private kit-repository
  "The repository the orgkit lives in, and the orgs of a house that keeps
  no orgs repository of its own."
  "ckopsa/waymark")

(defn- lane-name [lane] (str (or (:name lane) (get lane "name"))))

(defn orgs-repository
  "The repository this house keeps its orgs in (ticket dce7a672): the one
  active repo_policy, other than the kit's, with a lane named `orgs`;
  else the kit's own. Two such policies name no one repository, and the
  kit's is taken."
  [ctx]
  (let [named (->> (found-rows ctx :repo_policy {})
                   (filter #(= "active" (state-name %)))
                   (filter #(some (comp #{"orgs"} lane-name) (get-in % [:data :lanes])))
                   (keep #(some-> (get-in % [:data :repository]) str str/trim not-empty))
                   (remove #{kit-repository})
                   distinct)]
    (if (= 1 (count named)) (first named) kit-repository)))

(defn- onboarding-body
  "The onboarding template's model name, its seat and its :wakes, each
  {{org}} filled with `org` and each {{repo}} with `repo`, the orgs
  repository its writes and its change are in. The wakes are written
  once the seat's deferred birth lands, in its `:then`, by
  `seats/wake-seat-through!`, as its owner's door subscriptions."
  [org repo]
  (let [t (edn/read-string (slurp (io/resource onboarding-template)))
        fill #(edn/read-string (-> (pr-str %)
                                   (str/replace "{{org}}" (str org))
                                   (str/replace "{{repo}}" (str repo))))]
    {:model (get-in t [:template :model])
     :seat (fill (:seat t))
     :wakes (fill (vec (:wakes t)))}))

(defn- powers-registered?
  "Does every power token `scope` names pass scope-names-real-kinds
  here: named by a non-retired mcp_server's powers, or an active
  capability?"
  [ctx scope]
  (let [served (delay (set (servers/tokens-of-rows
                            (remove #(= "retired" (state-name %))
                                    (found-rows ctx :mcp_server {})))))]
    (every? (fn [k] (or (contains? @served k)
                        (some #(= "active" (state-name %))
                              (found-rows ctx :capability {:token k}))))
            (filter #(str/includes? % ".") (map (comp str :kind) scope)))))

(defn- onboarding-model
  "The id of the model the onboarding seat is held for: the active
  model the template names; else the one the house's onboarding-*
  seats already hold; else nil."
  [ctx model-name]
  (or (some->> (found-rows ctx :model {})
               (filter #(and (= (str model-name) (str (get-in % [:data :name])))
                             (= "active" (state-name %))))
               first :id str)
      (some->> (found-rows ctx :seat {})
               (filter #(str/starts-with? (str (get-in % [:data :name])) "onboarding-"))
               (keep #(first (get-in % [:data :held_for])))
               first str)))

(defn- factory-domain [ctx]
  (some->> (found-rows ctx :domain {})
           (filter #(= "factory" (str (get-in % [:data :name]))))
           first :id str))

(defn- seat-step
  "The plan's onboarding_note while its onboarding seat is the next move."
  [org sentence]
  {:key "onboarding_seat" :severity "info" :class "onboarding"
   :subject (onboarding-seat-name org)
   :sentence (subs sentence 0 (min 240 (count sentence)))})

(defn seat-the-onboarder
  "A found plan's onboarding seat: seated, or the active one its filer
  already holds reused, and the next move said in onboarding_note. A
  plan whose brief is blank seats nothing, and the note asks for the
  brief. A ctx without the hooks (the render probe, a scenario) seats
  nothing. The caller judges that the plan is a found one."
  [row ctx]
  (let [org (str (get-in row [:data :name]))
        seat-name (onboarding-seat-name org)
        offer (str "Offer " seat-name " a key (seat offer_key) and sit with it to start.")
        step #(assoc-in row [:data :onboarding_note] (seat-step org %))]
    (cond
      (some nil? [(:find ctx) (:create ctx) (:rehearse ctx)])
      row

      (str/blank? (get-in row [:data :brief]))
      (step "Write the brief (write_brief): the onboarding seat starts from it.")

      (active-onboarding-seat ctx org)
      (step offer)

      :else
      (let [{:keys [model seat wakes]} (onboarding-body org (orgs-repository ctx))]
        (if-not (powers-registered? ctx (:scope seat))
          (step "No onboarding seat: this house has no bench powers")
          (if-some [m (onboarding-model ctx model)]
            (let [domain (factory-domain ctx)
                  body (cond-> (assoc seat :name seat-name :held_for [m])
                         domain (assoc :domain domain))
                  refused (try ((:rehearse ctx) :seat nil nil body) nil
                               (catch Exception e
                                 (or (ex-message e) "the seat door refused it")))]
              (if refused
                (step (str "No onboarding seat: " refused))
                (do ((:create ctx) :seat body
                     {:then (fn [{born :row} c]
                              (seats/wake-seat-through! (:create c) (:invoke c) (:find c)
                                                        born wakes))})
                    (step offer))))
            (step (str "Pick a model for " seat-name))))))))

(defn park-the-onboarder!
  "Best-effort, as change's merge is: the plan's active onboarding
  seat, when its owner is the plan's, parked. A door that refuses is
  said in the log, and the plan moves on."
  [row ctx]
  (when (:invoke ctx)
    (when-some [seat (active-onboarding-seat ctx (get-in row [:data :name]))]
      (when (= (some-> (get-in row [:data :owner]) str)
               (some-> (get-in seat [:data :owner]) str))
        (try
          ((:invoke ctx) :seat (str (:id seat)) :park {})
          (catch Exception e
            (binding [*out* *err*]
              (println "factory10 org_plan retire: the seat" (str (:id seat))
                       "did not park -" (ex-message e)))))))))
