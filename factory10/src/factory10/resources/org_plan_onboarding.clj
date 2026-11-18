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
  bench powers are not registered here, when no model resolves, when
  no runner link is in that model's pool to fire the seat through
  (ticket 941ec5ff), or when the seat door would refuse it; the plan is
  filed either way.

  A PLAN WITH NO BRIEF SEATS NOTHING (ticket 9e59cdbd). Its
  onboarding_note asks for the brief, and the plan's `write_brief`
  door seats the onboarder when the brief is written."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.seats :as seats]
            [waymark10.server.seats.pools :as pools]))

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
  kit's is taken. The factory seed's policy carries that lane (ticket
  36b1cd39), so a seeded house's orgs live in the seed's `repo`."
  [ctx]
  (let [named (->> (found-rows ctx :repo_policy {})
                   (filter #(= "active" (state-name %)))
                   (filter #(some (comp #{"orgs"} lane-name) (get-in % [:data :lanes])))
                   (keep #(some-> (get-in % [:data :repository]) str str/trim not-empty))
                   (remove #{kit-repository})
                   distinct)]
    (if (= 1 (count named)) (first named) kit-repository)))

(defn- one-each
  "`scope` with each entry's `repo` filter naming a repository once: the
  kit's house fills `{{repo}},ckopsa/waymark` with the kit twice
  (ticket 36b1cd39)."
  [scope]
  (mapv (fn [entry]
          (if-some [r (get-in entry [:filter :repo])]
            (assoc-in entry [:filter :repo]
                      (str/join "," (distinct (map str/trim (str/split (str r) #",")))))
            entry))
        scope))

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
     :seat (update (fill (:seat t)) :scope one-each)
     :wakes (fill (vec (:wakes t)))}))

(defn- missing-powers
  "The power tokens `scope` names that fail scope-names-real-kinds
  here, sorted: named by no non-retired mcp_server's powers and no
  active capability."
  [ctx scope]
  (let [served (delay (set (servers/tokens-of-rows
                            (remove #(= "retired" (state-name %))
                                    (found-rows ctx :mcp_server {})))))]
    (->> (map (comp str :kind) scope)
         (filter #(str/includes? % "."))
         (remove (fn [k] (or (contains? @served k)
                             (some #(= "active" (state-name %))
                                   (found-rows ctx :capability {:token k})))))
         (into (sorted-set))
         vec)))

(defn- no-powers-sentence
  "The step of a house missing `missing` (ticket b696cda5): the powers
  named, the first four and a count of the rest."
  [missing]
  (let [shown (take 4 missing)
        more (- (count missing) (count shown))]
    (str "No onboarding seat: this house has no bench powers "
         (str/join ", " shown)
         (when (pos? more) (str " and " more " more"))
         "; register the mcp_server that serves them, then take seat_onboarder.")))

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

(defn- model-pool?
  "Whether a runner link not retired is in the pool of the model `m`:
  the pool a newborn seat held for `m` fires through, its chair's, as it
  has no schedule pool of its own yet (ticket 941ec5ff)."
  [ctx m]
  (some? (pools/pooled-ids (found-rows ctx :runner_link {}) :model m)))

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

(defn- offer-sentence
  "The step once the seat sits; a house that names no orgs repository
  is told, in the same sentence, that its org is written in the kit's
  (ticket 36b1cd39)."
  [ctx seat-name]
  (str "Offer " seat-name " a key you choose (seat offer_key, its `key` input) and sit with it to start"
       (if (= kit-repository (orgs-repository ctx))
         (str "; this house names no orgs repository (a repo_policy with an orgs lane), "
              "so its org is written in " kit-repository ".")
         ".")))

(defn- sat?
  "Has a sitting of the seat `seat` opened (ticket b6896893)?"
  [ctx seat]
  (boolean (seq (found-rows ctx :sitting {:seat (str (:id seat))}))))

(defn- change-submitted?
  "Is the plan's change submitted: the one it names, read through the
  ctx :read hook, or one born from it?"
  [row ctx]
  (let [named (when-some [cid (some-> (get-in row [:data :change]) str not-empty)]
                (when-some [read' (:read ctx)]
                  (try (read' :change cid) (catch Exception _ nil))))
        born (found-rows ctx :change {:born_from (str "org_plan:" (:id row))})]
    (boolean (some #(= "submitted" (state-name %)) (cons named born)))))

(defn- seated-sentence
  "The step once the seat is born (ticket b6896893): offer it a key;
  once a sitting of it has opened, it is at work; once its change is
  submitted, the findings are awaited."
  [row ctx seat seat-name]
  (cond
    (not (sat? ctx seat)) (offer-sentence ctx seat-name)
    (change-submitted? row ctx)
    "The change is submitted: the findings pass reads its head's findings next, and the first question for you follows."
    :else
    (str seat-name " is at work: it drafts the parts for your yes, then writes the org and submits its change.")))

(defn- onboarding-plan
  "What seating `row`'s onboarding seat takes now, read without a write
  (ticket b696cda5): {:sentence s} when something comes first — the
  brief, a power, a model, the model's runner pool (ticket 941ec5ff) —
  or the seat already sits; else the :body
  and :wakes the seat door is given."
  [row ctx]
  (let [org (str (get-in row [:data :name]))
        seat-name (onboarding-seat-name org)]
    (cond
      (str/blank? (get-in row [:data :brief]))
      {:sentence "Write the brief (write_brief): the onboarding seat starts from it."}

      (active-onboarding-seat ctx org)
      {:sentence (seated-sentence row ctx (active-onboarding-seat ctx org) seat-name)}

      :else
      (let [{:keys [model seat wakes]} (onboarding-body org (orgs-repository ctx))
            missing (missing-powers ctx (:scope seat))
            m (when (empty? missing) (onboarding-model ctx model))]
        (cond
          (seq missing)
          {:sentence (no-powers-sentence missing)}

          (nil? m)
          {:sentence (str "Pick a model for " seat-name ": no active model is named "
                          model "; add it, then take seat_onboarder.")}

          (not (model-pool? ctx m))
          {:sentence (str "No runner pool for " seat-name ": no runner link is in model "
                          model "'s pool to fire it; add one to it (Add to a pool), "
                          "then take seat_onboarder.")}

          :else
          (let [domain (factory-domain ctx)]
            {:body (cond-> (assoc seat :name seat-name :held_for [m])
                     domain (assoc :domain domain))
             :wakes wakes}))))))

(defn onboarding-step
  "A found plan's onboarding step read now (ticket b696cda5): what its
  onboarding_note would say were it filed this minute, so a house that
  gained its bench powers or its model after the filing is not told it
  lacks them. A plan the house could seat now is told to take
  seat_onboarder. A ctx with no :find reads the stored note."
  [row ctx]
  (if (nil? (:find ctx))
    (get-in row [:data :onboarding_note])
    (let [org (str (get-in row [:data :name]))]
      (seat-step org (or (:sentence (onboarding-plan row ctx))
                         (str "Seat the onboarder (seat_onboarder): this house now has what "
                              (onboarding-seat-name org) " needs."))))))

(defn seat-the-onboarder
  "A found plan's onboarding seat: seated, or the active one its filer
  already holds reused, and the next move said in onboarding_note. A
  plan whose brief is blank seats nothing, and the note asks for the
  brief. A ctx without the hooks (the render probe, a scenario) seats
  nothing. The caller judges that the plan is a found one."
  [row ctx]
  (let [org (str (get-in row [:data :name]))
        step #(assoc-in row [:data :onboarding_note] (seat-step org %))]
    (if (some nil? [(:find ctx) (:create ctx) (:rehearse ctx)])
      row
      (let [{:keys [sentence body wakes]} (onboarding-plan row ctx)]
        (if sentence
          (step sentence)
          (if-some [refused (try ((:rehearse ctx) :seat nil nil body) nil
                                 (catch Exception e
                                   (or (ex-message e) "the seat door refused it")))]
            (step (str "No onboarding seat: " refused))
            (do ((:create ctx) :seat body
                 {:then (fn [{born :row} c]
                          (seats/wake-seat-through! (:create c) (:invoke c) (:find c)
                                                    born wakes))})
                (step (offer-sentence ctx (onboarding-seat-name org))))))))))

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
