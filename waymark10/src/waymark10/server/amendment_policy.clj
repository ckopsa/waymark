(ns waymark10.server.amendment-policy
  "Amendments step 10 (docs/spec-amendment.md §14): watch first.
  R-A.7.1-7.2: one `amendment_policy` row per amendment kind — the
  target kind and door, `seat.restate` or `promise.restate` — holds
  `auto_revert`, false until the owner taps `arm`. `adopt`
  (waymark10.server.amendments) copies `mode` from the policy in force,
  so a later flip never changes an amendment already on trial, and the
  trial (waymark10.server.amendment-trial) reverts a `worse` verdict
  only when that copied mode is `revert`. Opted into like `amendment`,
  through an app's resources vector."
  (:require [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.goals :as goals]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- text [v] (if (keyword? v) (name v) (some-> v str)))

(defn- fget [m k]
  (when (map? m)
    (let [v (get m (keyword (text k)))]
      (if (some? v) v (get m (text k))))))

(def targets
  "R-A.7.2: the amendment kinds, as target kind and door."
  ["seat.restate" "promise.restate"])

(defn mode-of
  "The mode an amendment of `kind` through `door` is adopted in, read
  through `ctx`'s :find in the adopt's own transaction: `revert` when
  that kind's policy is armed, `watch` otherwise (R-A.7.1)."
  [ctx kind door]
  (let [policy (when-some [find (:find ctx)]
                 (first (find :amendment_policy {:target (str (text kind) "." (text door))}
                              {:limit 1})))]
    (if (true? (fget (:data policy) :auto_revert)) "revert" "watch")))

(g/defguard the-owner-sets-it
  {:reads [:principal :member :domain]
   :explain "Whether the engine reverts on its own is the house owner's tap, one per kind of amendment; no seat arms or disarms it (R-A.7.2)."
   :open "The factory domain's owner taps it; the owner is set by restating that domain."}
  [_row _inp ctx]
  (let [p (:principal ctx)]
    (if (and (not= :system (:type p))
             (not (and (= :agent (:type p)) (nil? (some-> (:acts-for p) str not-empty))))
             (goals/goal-writer? ctx))
      (t/allow)
      (t/deny))))

(g/defguard one-policy-per-kind
  {:judges [:target]
   :reads [:amendment_policy]
   :explain "`{target}` already has its policy: arm or disarm that row."
   :open "Tap arm or disarm on the policy that names this kind."
   :vars [:target]}
  [_row inp ctx]
  (let [target (text (:target inp))]
    (if (and target (:find ctx) (seq ((:find ctx) :amendment_policy {:target target} {:limit 1})))
      (t/deny {:vars {:target target}})
      (t/allow))))

(defn- born-watch
  "R-A.7.1: a policy is born unarmed, whatever its create said."
  [row _ctx]
  (update row :data #(-> % (dissoc "auto_revert") (assoc :auto_revert false))))

(defhandler arm-policy
  [row _inp _ctx]
  (assoc-in row [:data :auto_revert] true))

(defhandler disarm-policy
  [row _inp _ctx]
  (assoc-in row [:data :auto_revert] false))

(defresource amendment-policy
  {:kind :amendment_policy
   :plural "amendment_policies"
   :nav :secondary
   :states [:watch :armed]
   :initial :watch
   :summary "{data.target} · {state}"
   :label-template "{data.target}"
   :schema [:map
            [:target {:x-display {:label "Amends" :help "The kind of amendment this policy rules: its target kind and door."}}
             (into [:enum] targets)]
            [:auto_revert {:optional true
                           :x-display {:label "Reverts on its own" :help "True: an amendment adopted while this holds is reverted by the engine when its trial reads worse. False: it is watched, and the owner taps revert."}}
             [:maybe :boolean]]]
   :filterable {:target #{:eq} :state #{:eq :in}}
   :create-guards [the-owner-sets-it one-policy-per-kind]
   :on-create born-watch
   :actions
   {:arm {:from #{:watch} :to :armed
          :guards [the-owner-sets-it]
          :safety {:idempotent true :reversible true :confirm false}
          :handler arm-policy
          :display {:label "Arm" :style :primary :order 1}}
    :disarm {:from #{:armed} :to :watch
             :guards [the-owner-sets-it]
             :safety {:idempotent true :reversible true :confirm false}
             :handler disarm-policy
             :display {:label "Disarm" :order 2}}}})
