(ns factory10.resources.org-plan-audit
  "The org plan's head check, as the audit answers it (ticket 5c4bf05c).
  The take answered one question: has this head been checked? The audit
  already answers it: `record_findings` writes `findings_sha` and, with
  the scripted week, `dry_week_sha` for the head it read. So a plan is
  checked at a head when its findings and its week are both of it
  (`audited-at?`), or, while the film doors stand, its take is
  (`take-of?`).

  A `proposed` plan audited at its change's head, with no error finding
  open, is moved to `simulated` by the engine's `mark_simulated`
  (`simulate-pass!`, run by the forge's findings pass after it records).
  `go_live` asks that the week is of the head the audit last read
  (`the-week-is-of-the-audited-head`), the head its yes approves."
  (:require [clojure.string :as str]
            [factory10.bench :as bench]
            [waymark10.dsl :refer [defguardfn defhandler]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- short-sha [sha] (let [s (str sha)] (subs s 0 (min 7 (count s)))))

(defn take-of?
  "Is the take `take-sha` a film of `head` (ticket 87fc7996)? The
  director may hand in a short sha — hand_in_film takes seven
  characters and up — so a take the head begins with names it."
  [take-sha head]
  (let [tk (str take-sha)
        hd (str head)]
    (and (<= 7 (count tk)) (str/starts-with? hd tk))))

(defn audited-at?
  "Are `plan`'s findings and its scripted week both of `head`?"
  [plan head]
  (let [hd (some-> head str not-empty)
        d (:data plan)]
    (boolean (and hd
                  (= hd (some-> (:findings_sha d) str))
                  (= hd (some-> (:dry_week_sha d) str))))))

(defn checked-at?
  "Has `plan` been checked at `head`: audited there, or filmed there?"
  [plan head]
  (or (audited-at? plan head)
      (take-of? (get-in plan [:data :take_sha]) head)))

(defn- an-error-is-open? [plan]
  (boolean (some #(= "error" (str (:severity %)))
                 (get-in plan [:data :findings]))))

;; ── the doors' law ──────────────────────────────────────────────────

(defguardfn the-week-is-of-the-audited-head
  {:reads []
   :vars [:findings :week]
   :explain "The scripted week has not been recorded for the head the audit last read: the findings are of {findings} and the week of {week}."
   :open "Let the audit run at the change's head, so it records the findings and the week together, then ask go_live again."}
  ;; go_live's head check. approve-the-head approves `findings_sha`, so
  ;; the week the owner reads must be of that same head.
  [row _inp _ctx]
  (let [found (some-> (get-in row [:data :findings_sha]) str not-empty)
        week (some-> (get-in row [:data :dry_week_sha]) str not-empty)]
    (if (and found (= found week))
      (t/allow)
      (t/deny {:vars {:findings (if found (short-sha found) "no head")
                      :week (if week (short-sha week) "no head")}}))))

(defguardfn the-week-was-played-at-its-head
  {:reads []
   :vars [:head]
   :explain "The audit has not recorded both its findings and the scripted week at the head of this plan's pull request ({head})."
   :open "Let the audit run at the change's head."}
  ;; The change's head is read through the ctx hook, as org_plan's
  ;; `the-plan-was-audited-at-its-head` reads it; a ctx with no hook, or
  ;; a plan that names no change, judges the head the pass named.
  [row inp ctx]
  (let [read' (:read ctx)
        named (some-> (get-in row [:data :change]) str not-empty)
        head (or (when (and read' named)
                   (some-> (read' :change named) (get-in [:data :head_sha]) str not-empty))
                 (some-> (:head_sha inp) str not-empty))]
    (if (audited-at? row head)
      (t/allow)
      (t/deny {:vars {:head (if head (short-sha head) "none yet")}}))))

(defhandler the-week-stands [row _inp _ctx]
  ;; The findings and the week of the change's head stand for the take:
  ;; nothing more is written.
  row)

;; ── the pass ────────────────────────────────────────────────────────

(def simulate-scan-limit
  "How many proposed plans one pass reads."
  50)

(defn- head-of-change [eng id]
  (let [st (:storage eng)]
    (when-not (str/blank? (str id))
      (some-> (store/with-tx st (fn [tx] (store/load-row st tx :change (str id) {})))
              (get-in [:data :head_sha]) str not-empty))))

(defn simulate-pass!
  "Every proposed org_plan audited at its change's head (`audited-at?`)
  with no error finding open → `mark_simulated`, invoked with `opts`.
  A door the engine refuses costs that plan one pass. → the census, with
  `:simulated` counted."
  [eng opts census log-fn]
  (let [st (:storage eng)
        plans (store/with-tx st
                (fn [tx]
                  (store/query-rows st tx :org_plan {:state "proposed"}
                                    {:limit simulate-scan-limit})))]
    (reduce
     (fn [census plan]
       (let [head (head-of-change eng (bench/org-plan-change-id eng plan))]
         (if (or (not (audited-at? plan head)) (an-error-is-open? plan))
           census
           (try
             (inv/invoke! eng :org_plan (str (:id plan)) :mark_simulated
                          {:head_sha head} opts)
             (update census :simulated (fnil inc 0))
             (catch Exception e
               (log-fn "the plan " (get-in plan [:data :name])
                       " was not simulated at " head " (" (ex-message e) ")")
               census)))))
     census
     plans)))
