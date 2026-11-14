(ns factory10.week
  "An org's first week, judged (ticket 7fba2e9b, scripted week 2a).

  THE REPORT IS workqueue10.week's. The audit plays the org's week.edn
  and hands the report beside the findings as `week_report`; the
  forge's findings pass judges it here and records it on the plan.

  THREE WEEK CHECKS, EACH AN ERROR, so a failing one blocks propose:
  `[week-stalls]` one finding per refused call, `[week-no-ask]` when
  nothing was held for the owner's tap, `[week-no-payoff]` when the
  goal counts no row. An org with no week.edn (the audit hands
  `week_report` as anything but a report) is `[week-missing]`, a
  warning for now, so orgs already in flight are not blocked.

  THE SCRIPT RIDES TOO. `-main` prints the org's week.edn as JSON
  (`week-of`), which the audit's run puts in the same artifact as
  `week_script` with its git blob sha as `week_sha`; the findings doors
  write it on the plan, where the week page reads each call's words."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [jsonista.core :as j]))

(set! *warn-on-reflection* true)

(def ^:private dry-week-cap 500)

(defn- finding [key sentence remedy]
  {:key key :severity "error" :class "week" :subject "week"
   :sentence sentence :remedy remedy})

(defn- stalls [calls]
  (keep-indexed
   (fn [i {:keys [day kind action guard detail remedies]}]
     (finding (str "week-stalls/" i)
              (str "day " day ": " kind " " action " refused"
                   (when (seq (str guard)) (str " by " guard))
                   " (" detail ")")
              (if (seq remedies)
                (str/join " " (map str remedies))
                "Make the call the law affords on that day, or change the law that refused it.")))
   calls))

(defn- payoff-words [{:keys [kind state words]}]
  (or (some-> words str not-empty) (str state " (" kind ")")))

(defn judge-week
  "The week report → its findings: none when the week plays clean."
  [report]
  (let [{:keys [calls person_asks goal]} (walk/keywordize-keys report)
        refused (filter #(= "refused" (str (:outcome %))) calls)
        last-day (or (some->> (seq calls) (map :day) (filter number?) seq (apply max)) 7)]
    (vec
     (concat
      (stalls refused)
      (when (zero? (long (or person_asks 0)))
        [(finding "week-no-ask"
                  "Nothing in the week waits on the owner's tap."
                  "Script a day whose call is held for the owner, so the week shows what they decide.")])
      (when (zero? (long (or (:count goal) 0)))
        [(finding "week-no-payoff"
                  (if goal
                    (str "By day " last-day " nothing is " (payoff-words goal) ".")
                    "The week names no goal, so nothing in it pays off.")
                  "Script the calls that bring the goal's row to its state by the week's end.")])))))

(defn dry-week
  "The report, compact: per call its day, as, kind, action, outcome and
  guard."
  [report]
  (into []
        (comp (map #(select-keys % [:day :as :kind :action :outcome :guard]))
              (map #(into {} (keep (fn [[k v]] (when (some? v) [k (if (= :day k) v (str v))]))) %))
              (take dry-week-cap))
        (:calls (walk/keywordize-keys report))))

(def missing
  {:key "week-missing" :severity "warning" :class "week" :subject "week"
   :sentence "The org has no week.edn, so its first week is not played."
   :remedy "Write resources/<org>/week.edn: each day's calls and who makes them."})

(defn of-artifact
  "What the audit's artifact says of the week: nil when it carries no
  `week_report`, `{:findings [missing]}` when it carries one that is no
  report, else the report's findings and its `:dry_week`."
  [doc]
  (when (and (map? doc) (or (contains? doc :week_report) (contains? doc "week_report")))
    (let [report (or (get doc :week_report) (get doc "week_report"))]
      (if (map? report)
        {:findings (judge-week report) :dry_week (dry-week report)}
        {:findings [missing]}))))

(defn- blob-sha
  "The git blob sha of `bs`, as `git hash-object` names the file."
  [^bytes bs]
  (let [md (java.security.MessageDigest/getInstance "SHA-1")]
    (.update md (.getBytes (str "blob " (alength bs) "\u0000") "UTF-8"))
    (.update md bs)
    (apply str (map #(format "%02x" (bit-and (long %) 0xff)) (.digest md)))))

(defn week-of
  "The week script of the org at `org-dir` (ticket 4c70e261): {:week_script
  its resources/<org>/week.edn as data, :week_sha that file's git blob
  sha}. nil when the org has no week.edn."
  [org-dir]
  (when-some [^java.io.File f (->> (.listFiles (io/file org-dir "resources"))
                                   (sort)
                                   (map #(io/file % "week.edn"))
                                   (filter #(.isFile ^java.io.File %))
                                   first)]
    (let [bs (java.nio.file.Files/readAllBytes (.toPath f))]
      {:week_script (edn/read-string (String. bs "UTF-8"))
       :week_sha (blob-sha bs)})))

(defn -main
  "Print the week script of the org at `org-dir` as one line of JSON,
  {week_script, week_sha}, or {} when it has no week.edn."
  [org-dir]
  (println (j/write-value-as-string (or (week-of org-dir) {}))))
