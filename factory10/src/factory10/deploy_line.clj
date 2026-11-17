(ns factory10.deploy-line
  "A red deploy and the change that fixes it (ticket 7adcfde7). A red
  deploy holds its repository's line (`factory10.bench/deploy-held`),
  but a line that waits on the deploy its front is meant to fix never
  moves by waiting: `deploy_let_go` is the house's alone. So a change
  whose ticket's `found_in` names a waited merge's ticket or change goes
  past the red deploy, and so does the front change when its ticket is
  a bug at priority 0 or 1 of the same repository. When nothing in the
  line qualifies and the deploy has been red past `deploy_wait_seconds`,
  the domain's mayor is told once, the way
  `waymark10.server.maintainer/tell-mayor!` tells a refused fact door."
  (:require [clojure.string :as str]
            [waymark10.time :refer [instant-of]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store])
  (:import (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(defn red?
  "Whether the policy's last deploy verdict is red."
  [policy]
  (= "red" (str (get-in policy [:data :deploy_state]))))

(defn- ticket-of
  "The ticket row `tid` names, decoded, or nil."
  [eng tid]
  (when-some [rd (when tid (get (inv/resources eng) :ticket))]
    (let [st (:storage eng)]
      (some->> (try (store/with-tx st (fn [tx] (store/load-row st tx :ticket tid {})))
                    (catch Exception _ nil))
               (inv/decode-row rd)))))

(defn fixes-red?
  "Whether a change born from `ticket` may go past the red deploy of
  `waits` ([{:change :ticket :number}]) in `repo`: its ticket's
  `found_in` names a waited merge's ticket or change, or it is the
  `front?` of the line and its ticket is a `bug` at priority 0 or 1 of
  that repository."
  [ticket waits repo front?]
  (let [d (:data ticket)
        named (into #{} (comp (mapcat (juxt :ticket :change)) (keep identity) (map str))
                    waits)]
    (boolean
     (or (contains? named (some-> (:found_in d) str not-empty))
         (and front?
              (= "bug" (some-> (:type d) name))
              (contains? #{0 1} (some-> (:priority d) long))
              (= (str repo) (some-> (:repo d) str)))))))

(defn passes
  "Repository -> the part of its line that goes past its red deploy, in
  line order, for each `held` repository whose policy in `by-repo` is
  red. `waits-of` is policy -> its waits, `ticket-id-of` change -> the
  id of the ticket it was born from. A repository none of whose line
  qualifies is not in the map."
  [eng {:keys [by-repo held lines waits-of ticket-id-of]}]
  (into {}
        (keep (fn [repo]
                (let [policy (get by-repo repo)
                      line (get lines repo)
                      front (first line)]
                  (when (and (red? policy) (seq line))
                    (let [waits (waits-of policy)
                          through (filterv #(fixes-red? (ticket-of eng (ticket-id-of %))
                                                        waits repo (identical? % front))
                                           line)]
                      (when (seq through) [repo through]))))))
        held))

(defn- numbers [changes]
  (str/join ", " (map #(if-some [n (get-in % [:data :number])]
                         (str "#" n)
                         (str (:id %)))
                      changes)))

(defn held-note
  "What a held repository's policy says, from the sentence its line
  waits on (`why`), the changes that go past its red deploy
  (`through`) and its `cap` in seconds at `now`: {:note, :tell}. The
  note states the rule when the line moved; `:tell` is the sentence for
  the mayor when the deploy is red past the cap and nothing goes past."
  [policy why through cap ^Instant now]
  (let [base (if (str/ends-with? (str why) ".") (subs why 0 (dec (count why))) (str why))
        since (instant-of (get-in policy [:data :deploy_waiting_since]))]
    (cond
      (seq through)
      {:note (str base "; " (numbers through) " goes past it: a red deploy lets"
                  " through a change whose ticket's found_in names the red"
                  " deploy's ticket or change, and a front change whose"
                  " ticket is a priority 0 or 1 bug of this repository.")}

      (and (red? policy) since cap
           (>= (.getSeconds (Duration/between since now)) (long cap)))
      (let [stalled (str "The deploy of " (get-in policy [:data :repository])
                         " has been red past " cap " seconds and nothing in its"
                         " line names it; only deploy_let_go, which the house's"
                         " merge and forge passes alone walk, lets the line go.")]
        {:note (str base ". " stalled) :tell stalled})

      :else {:note why})))

(defn tell-mayor!
  "Tell the mayor of the repo_policy kind's domain `text`, by that
  seat's own `fire` door, once per red episode of `policy` (keyed on
  its waiting since). -> true when told; never throws."
  [eng policy text]
  (try
    (let [rs (inv/resources eng)
          st (:storage eng)]
      (when-some [d (some-> (get rs :repo_policy) :domain name)]
        (when (get rs :domain)
          (when-some [mayor (some-> (store/with-tx st
                                      (fn [tx] (store/query-rows st tx :domain
                                                                 {:name d} {:limit 1})))
                                    first (get-in [:data :mayor]) str not-empty)]
            (inv/invoke! eng :seat mayor :fire
                         {:text (str "repo_policy " (:id policy) ": " text)}
                         {:principal @(requiring-resolve 'waymark10.server.schedules/system-actor)
                          :idempotency-key (str "deploy-red:" (:id policy) ":"
                                                (get-in policy [:data :deploy_waiting_since])
                                                ":told")})
            true))))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "factory10 deploy-line: the mayor was not told of the red deploy of "
                      (:id policy) " - " (ex-message e))))
      nil)))
