(ns factory10.org-plan-walk
  "An org_plan's walk to live through its real doors (ticket c8e131fb,
  M2e), for every test that needs a going_live or live plan: the plan
  goes up, its film lands, the audit records a run whose org.edn says
  live and whose seed seats a mayor, the owner goes live, and the
  engine marks it live. No reseed."
  (:require [factory10.mirror :as mirror]
            [waymark10.server.invoke :as inv]))

(def a-head "5e469c6166edf6a3f28ae2e6bdb88b775025f14d")

(def a-mayor {:name "greenleaf-mayor" :budget_usd_per_week "8"})

(def the-engine {:principal mirror/source-principal})

(defn flipped!
  "The audit's run at `head` on a simulated plan: no findings, org.edn's
  :live true, and the seed's mayor."
  [eng id head]
  (inv/invoke! eng :org_plan (str id) :record_findings_simulated
               {:head_sha head :findings [] :live_flag true :mayor a-mayor}
               the-engine))

(defn going-live!
  "Walk the drafting plan `id` to going_live as `owner`: propose (with
  `change`, when named), the film, the flip at `head` and the owner's
  go_live. → the id."
  ([eng id owner] (going-live! eng id owner {}))
  ([eng id owner {:keys [change head] :or {head a-head}}]
   (let [id (str id)
         as {:principal owner}]
     (inv/invoke! eng :org_plan id :propose
                  (cond-> {} change (assoc :change change)) as)
     (inv/invoke! eng :org_plan id :attach_film
                  {:film_url "https://example.com/greenleaf.mp4"} as)
     (flipped! eng id head)
     (inv/invoke! eng :org_plan id :go_live {} as)
     id)))

(defn live!
  "Walk the drafting plan `id` to live as `owner`: going_live, then the
  engine's mark_live. → the id."
  [eng id owner]
  (going-live! eng id owner)
  (inv/invoke! eng :org_plan (str id) :mark_live {} the-engine)
  (str id))
