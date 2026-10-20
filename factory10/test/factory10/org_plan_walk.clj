(ns factory10.org-plan-walk
  "An org_plan's walk to live through its real doors (ticket c8e131fb,
  M2e), for every test that needs a going_live or live plan: the plan
  goes up, its film lands, the audit records a run whose org.edn says
  live and whose seed seats a mayor, the owner goes live, and the
  engine marks it live. No reseed.

  Propose opens only on a plan audited at its change's head (ticket
  db8b35bb), so `proposed!` names a change and records the audit's run
  at its head first."
  (:require [factory10.mirror :as mirror]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]))

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

(defn- row-of [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/load-row st tx kind (str id) {})))))

(defn a-change!
  "The pull request of the plan `id`, born from it as the sit's mint is,
  at `head`. → its id."
  [eng id head]
  (str (:id (:row (inv/create! eng :change
                               {:change_id (str "org_plan:" id)
                                :born_from (str "org_plan:" id)
                                :repository "ckopsa/waymark"
                                :title "An org's pull request"
                                :head_branch (str "bench/" id)
                                :base_branch "main"
                                :head_sha head}
                               the-engine)))))

(defn linked!
  "The sit's link on the drafting plan `id`: a change at `head`, named
  on the plan by the engine's hand. → the change id."
  [eng id head]
  (let [change (a-change! eng id head)]
    (inv/invoke! eng :org_plan (str id) :link_change {:change change} the-engine)
    change))

(defn audited!
  "The audit's run on the drafting plan `id` at the head of `change`
  (one made at `head` when none is named), keeping the plan's own
  findings: what propose asks of an audited plan. → the change id."
  [eng id {:keys [change head] :or {head a-head}}]
  (let [id (str id)
        change (or change (a-change! eng id head))
        at (or (some-> (row-of eng :change change) (get-in [:data :head_sha])
                       str not-empty)
               head)]
    (inv/invoke! eng :org_plan id :record_findings
                 {:head_sha at
                  :findings (vec (get-in (row-of eng :org_plan id) [:data :findings]))}
                 the-engine)
    change))

(defn proposed!
  "Audit the drafting plan `id` (`audited!`) and propose it as `owner`
  with its change. A `change` named in `opts` may be born from anything,
  so the engine links it first (`linked!`'s door) and propose names none:
  propose takes only a change opened for the plan (ticket b02c9808).
  → propose's answer."
  ([eng id owner] (proposed! eng id owner {}))
  ([eng id owner opts]
   (let [change (audited! eng id opts)]
     (when (:change opts)
       (inv/invoke! eng :org_plan (str id) :link_change {:change change} the-engine))
     (inv/invoke! eng :org_plan (str id) :propose
                  (if (:change opts) {} {:change change}) {:principal owner}))))

(defn going-live!
  "Walk the drafting plan `id` to going_live as `owner`: propose
  (`proposed!`, with `change` when named), the film handed in and
  landed by the engine, the flip at `head` and the owner's go_live.
  → the id."
  ([eng id owner] (going-live! eng id owner {}))
  ([eng id owner {:keys [head] :or {head a-head} :as opts}]
   (let [id (str id)
         as {:principal owner}]
     (proposed! eng id owner opts)
     (inv/invoke! eng :org_plan id :hand_in_film
                  {:film_url "https://example.com/greenleaf.mp4"
                   :take_report "{}" :head_sha head} as)
     (inv/invoke! eng :org_plan id :attach_film
                  {:film_url "https://example.com/greenleaf.mp4"} the-engine)
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
