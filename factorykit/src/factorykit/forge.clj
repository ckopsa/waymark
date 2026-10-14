(ns factorykit.forge
  "The seam between a forge and a factory, and ONE reading of a build
  state (ticket 6b716e98).

  THE PROTOCOL is `ForgeSource`, lifted from factory10's
  `sources/forge.clj`. A forge adapter (GitHub today, Bitbucket next)
  implements it and speaks the factory's own documents.

  THE TABLE is data: `resources/build-states.edn`, and the same content
  as `build-states.json` for a reader that is not Clojure. Each forge's
  raw state maps to green, red, pending or interrupted, and no adapter
  judges a raw state on its own. A state the table does not name has no
  verdict: it is never green.

  THE CONFORMANCE SUITE is data too: `resources/forge-conformance.edn`
  and its JSON copy. Each case gives raw statuses and required checks
  and the answer every adapter must give. `conformance-failures` runs
  them against one judge."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ── the seam ────────────────────────────────────────────────────────

(defprotocol ForgeSource
  "One forge, already speaking the factory's own documents. Six
  verbs: what moved, one log tail, one label, the call count the
  census prints, the checks on one head, and the head of a base
  branch with its checks."
  (forge-poll [s]
    "→ {:changes [change-doc …] :checks [check-doc …]
        :repositories [name …] :complete? bool}.

    A change document carries the `change` kind's own fields plus
    `:forge_state` (\"open\", \"merged\" or \"closed\"). A check
    document carries the `ci_run` kind's own fields plus
    `:change_id` (the change it ran on) and whatever the forge needs
    to find the log later. `:complete?` is false when a repository
    did not answer; the source holds that repository's cursor where it
    was, and moves the cursor of each one that answered. A source
    may also answer `:answered` (the repositories that did answer) and
    `:refusals` ({repository {:status :route}} for each whose pulls
    listing refused the token), which the pass writes on the policy
    rows (ticket 116dfb0d). A pull request whose head branch a row of
    the house already names is adopted by that branch, not minted.")
  (forge-log-tail [s check]
    "→ {:excerpt \"…\" :note \"…\" } for one check document: the tail
    of the failed job's log. A log the forge will not hand over in
    plain text answers an empty excerpt and a note that says why.
    Never throws — a missing log costs the excerpt, never the row.")
  (forge-label! [s change label]
    "Push ONE label to the pull request the change document names.
    The only write this source makes. Throws when the forge refuses.")
  (forge-calls [s]
    "→ how many calls this source made since the last `forge-poll`
    started. The census line's first number.")
  (forge-checks [s repository head-sha]
    "→ [{:check_name :status :conclusion} …] for every check run on
    one head, the latest of each (ticket d1742908). The poll reads
    checks only on pull requests that moved, and a check that finishes
    moves no pull request, so the failing pass asks head by head.
    Commit statuses count as checks. A forge with one raw state for a
    build (Bitbucket) answers it as `:state` in place of `:status` and
    `:conclusion`. A check may carry its `ci_run` document on its
    metadata (`:doc`), from which the run pass mints a red that
    finished after the push (ticket 3aca3ae8).
    Throws when the forge does not answer.")
  (forge-base [s repository branch]
    "→ {:head_sha sha :checks [check-doc …]} for the head of one branch,
    or nil when the branch has none (ticket ade81ae9). Each check
    carries `:check_name`, `:status` and `:conclusion`, which
    `verdict` reads, and whatever `forge-log-tail` needs to find
    its log. Throws when the forge does not answer."))

;; ── the build-state table ───────────────────────────────────────────

(defn- read-resource [n]
  (edn/read-string (slurp (io/resource n))))

(def build-states
  "The table as it is written: {:verdicts [...] :forges {forge {verdict
  [raw-state …]}}}."
  (read-resource "build-states.edn"))

(def ^:private state-index
  (into {}
        (for [[forge by-verdict] (:forges build-states)]
          [forge (into {} (for [[v raws] by-verdict, raw raws] [raw v]))])))

(defn raw-states
  "The raw states of one forge that read as one verdict, as a set."
  [forge verdict]
  (set (get-in build-states [:forges forge verdict])))

(defn build-state
  "What one check document says: :green, :red, :pending or :interrupted,
  or nil for a finished state the table does not name. A document with
  `:state` is read by it. One with `:status` is pending until the
  status is `completed`, and is read by its `:conclusion` after."
  [forge check]
  (let [table (get state-index forge)]
    (cond
      (contains? check :state) (get table (str (:state check)))
      (= "completed" (str (:status check))) (get table (str (:conclusion check)))
      :else :pending)))

;; ── the verdict on one head ─────────────────────────────────────────

(defn- run-order
  "Where one run of a check stands among the runs of the same name:
  its forge id first, which grows with every run the forge starts, and
  its start time beside it. A run that carries neither sorts first."
  [check]
  (let [id (str (:id check))]
    [(if (re-matches #"\d{1,18}" id) (Long/parseLong id) -1)
     (str (:started_at check))]))

(defn newest-runs
  "The runs of one check name that speak for it: the newest alone when
  every run says where it stands (ticket 6bdaf6fe) — a re-run that went
  green on the same head answers for the red run before it — and every
  run as it came when one of them does not, because then there is no
  telling which is newer."
  [runs]
  (if (and (next runs) (every? #(or (some? (:id %)) (some? (:started_at %))) runs))
    [(last (sort-by run-order runs))]
    runs))

(defn verdict
  "What the checks on one head say, read against a policy:
  {:verdict :red :names […]} when every required check finished and at
  least one went red, {:verdict :green} when every one finished green,
  {:verdict :interrupted :names […]} when none went red and at least
  one was interrupted — it died without a verdict, it is never green
  and never a recovery — and nil while one is pending, has not started,
  or ended in a state the table does not name. A policy that names no
  required check requires every check on the head. Of several runs of
  one name, the newest speaks for it (`newest-runs`).

  `:advisory` names the checks that count only when red: a red one is
  named beside the required reds, and one that is pending, interrupted
  or absent holds nothing. The policy declares them; none is built in."
  [{:keys [forge required advisory] :or {forge :github}} checks]
  (let [by-name (update-vals (group-by #(str (:check_name %)) checks)
                             newest-runs)
        advisory (set advisory)
        names (if (seq required)
                (vec (distinct required))
                (vec (sort (remove #(or (str/blank? %) (contains? advisory %))
                                   (keys by-name)))))
        states (fn [n] (map #(build-state forge %) (get by-name n)))
        named (fn [v of] (filterv (fn [n] (some #(= v %) (states n))) of))
        runs (mapv states names)
        all? (fn [pred] (every? (fn [ss] (and (seq ss) (every? pred ss))) runs))
        advised (named :red (sort (remove (set names) advisory)))]
    (cond
      (empty? names) (when (seq advised) {:verdict :red :names advised})
      (not (all? #(not= :pending %))) nil
      :else
      (let [red (into (named :red names) advised)]
        (cond
          (seq red) {:verdict :red :names red}
          (all? #(= :green %)) {:verdict :green}
          :else
          (let [cut (named :interrupted names)]
            (when (seq cut) {:verdict :interrupted :names cut})))))))

(defn behind?
  "Is a head behind its base, given how many of the base's commits it
  lacks? None, or a count the forge did not give, is not behind."
  [behind-by]
  (pos? (long (or behind-by 0))))

;; ── the conformance suite ───────────────────────────────────────────

(def conformance
  "The cases as they are written: {:cases [{:name :asks :expect …} …]}."
  (read-resource "forge-conformance.edn"))

(defn judge
  "The kit's own answer to one conformance case. `:asks` is \"verdict\"
  (`:forge`, `:required`, `:advisory`, `:checks`), \"state\" (`:forge`,
  `:check`) or \"behind\" (`:behind_by`)."
  [{:keys [asks forge required advisory checks check behind_by]}]
  (case asks
    "verdict" (verdict {:forge (keyword forge) :required required
                        :advisory advisory}
                       checks)
    "state" (build-state (keyword forge) check)
    "behind" (behind? behind_by)))

(defn- spelled
  "An answer as the data files spell it: a verdict is a string."
  [answer]
  (cond
    (keyword? answer) (name answer)
    (map? answer) (update answer :verdict name)
    :else answer))

(defn conformance-failures
  "The cases `judge-fn` answers wrongly, as [{:case :expect :actual} …];
  empty when the adapter conforms. `judge-fn` takes one case and answers
  as `judge` does. An adapter passes its own judge for the cases it
  judges itself and hands the rest to `judge`."
  ([] (conformance-failures judge))
  ([judge-fn]
   (vec (for [c (:cases conformance)
              :let [actual (spelled (judge-fn c))]
              :when (not= (:expect c) actual)]
          {:case (:name c) :expect (:expect c) :actual actual}))))
