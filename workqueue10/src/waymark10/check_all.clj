(ns waymark10.check-all
  "The declaration gate over EVERY module at once (ticket c09e2f88):
  waymark10's enrolled kinds, workqueue10's (the chore, meal, calendar
  and day-plan kinds folded in) and factory10's, assembled into ONE
  registry — the union CI's suites see.

      cd workqueue10 && clojure -M:check

  WHY THE UNION. checks-assembly runs when the registry is built, and
  some of its findings need another module's kind to fire: a plain
  string field named after a kind (`[unref'd-ids]`) is only a finding
  where that kind is registered. PR #542 met three of them in CI's
  suites while the module's own check answered clean.

  It lives on workqueue10's classpath because that module is the one
  that depends on all the others.

  THE ORGS (ticket 8f46a0a4). Beside the registry it judges every org
  under orgs/ that the worktree touches against its base, with
  factorykit.conform/conform — the function CI's `org-findings (<org>)`
  job runs through `clojure -M:conform`, so the two never drift. An org
  that does not load, or whose kinds do not pass the gate or assemble,
  fails this check instead of a CI round. CHECK_ORGS (comma-separated
  names) picks the orgs by hand; CHECK_BASE names the base, which is
  otherwise the merge-base with origin/main, or with main.

  THE LAW PAGE (ticket ade71a1e). Each org that loads also has its
  law page rendered with factory10.law-text — what CI's `org-law
  (<org>)` job runs — with the org on the classpath as conform puts
  it there, so a page that throws is a finding here too.

  THE WEEK (ticket 62297746). Each org that loads and has a
  resources/<org>/week.edn has it played with workqueue10.week — what
  CI's `org-findings (<org>)` job plays — and judged with
  factory10.week/judge-week, so a week that stalls shows `[week-stalls]`
  here before a submit. An org whose manifest says :fixture true
  (ticket 911a4c1e) is a test-only org whose week may stall on purpose:
  its week is not judged, and the check says so in one line."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [factory10.law-text :as law]
            [factory10.main :as factory]
            [factory10.week :as week]
            [factorykit.conform :as conform]
            [waymark10.check :as check]
            [workqueue10.main :as queue]
            [workqueue10.week :as player])
  (:import (java.io File)))

(defn resources
  "Every module's kinds, each once. workqueue10's check set already
  folds factory10's in when FACTORY10=1, and a kind named twice would
  die in the registry as `one law per kind`."
  []
  (let [seen (volatile! #{})]
    (into []
          (filter (fn [r]
                    (when-not (contains? @seen (:kind r))
                      (vswap! seen conj (:kind r)))))
          (concat (queue/check-resources) (factory/check-resources)))))

(defn- git-lines
  "git's answer in `root`, line by line; nil when git fails."
  [root & args]
  (let [{:keys [exit out]} (apply sh/sh "git" "-C" (str root) args)]
    (when (zero? (long exit))
      (remove str/blank? (str/split-lines out)))))

(defn orgs-in
  "The org names that repo-relative `paths` touch: orgs/<org>/…, as
  CI's `changes` job reads them, each once, sorted."
  [paths]
  (->> paths
       (keep #(second (re-matches #"orgs/([^/]+)/.+" %)))
       distinct
       sort
       vec))

(defn touched-orgs
  "The org directories under `root` this check judges: CHECK_ORGS when
  set, else the orgs the worktree touches against its base — committed,
  uncommitted and untracked — that are still there."
  [^File root]
  (let [named (System/getenv "CHECK_ORGS")
        names (if (some? named)
                (vec (remove str/blank? (map str/trim (str/split named #","))))
                (let [base (or (System/getenv "CHECK_BASE")
                               (first (git-lines root "merge-base" "HEAD" "origin/main"))
                               (first (git-lines root "merge-base" "HEAD" "main")))]
                  (orgs-in (concat (when base (git-lines root "diff" "--name-only" base))
                                   (git-lines root "ls-files" "--others" "--exclude-standard")))))]
    (filterv #(.isDirectory ^File %) (map #(io/file root "orgs" %) names))))

(defn- org-errors
  "The error findings factorykit's conformance gives the org at `dir`;
  a conformance that throws is one load finding."
  [^File dir]
  (try
    (filterv #(= :error (:severity %)) (conform/conform dir))
    (catch Throwable t
      [{:severity :error :class :load :key (str "load/" (.getName dir))
        :sentence (str "the conformance could not run: " (or (ex-message t) (str t)))
        :remedy "Run `clojure -M:conform` on the org from factorykit/ to see the whole trace."}])))

(defn- law-errors
  "The org at `dir`'s law page rendered as CI's org-law job renders it,
  with the org's src and resources on the classpath; a render that
  throws is one law finding."
  [^File dir]
  (try
    (conform/with-org-classpath dir #(law/law-text (str dir)))
    []
    (catch Throwable t
      [{:severity :error :class :law :key (str "law/" (.getName dir))
        :sentence (str "the law page could not render: " (or (ex-message t) (str t)))
        :remedy (str "Run `clojure -M:org -m factory10.law-text ../orgs/" (.getName dir)
                     "` from factory10/ to see the whole trace.")}])))

(defn- week-errors
  "The org at `dir`'s week.edn played and judged as CI's org-findings
  job judges it, each finding classed by its check (`week-stalls`,
  `week-no-ask`, `week-no-payoff`); a script that does not play is one
  `week-broken` finding. An org with no week.edn has none here."
  [^File dir]
  (let [org (.getName dir)]
    (if-not (.isFile (io/file dir "resources" org "week.edn"))
      []
      (try
        (mapv (fn [{:keys [key] :as f}]
                (assoc f :severity :error :class (keyword (first (str/split (str key) #"/")))))
              (week/judge-week (player/play dir)))
        (catch Throwable t
          [{:severity :error :class :week-broken :key (str "week/" org)
            :sentence (str "the week could not play: " (or (ex-message t) (str t)))
            :remedy (str "Run `clojure -M -m workqueue10.week ../orgs/" org
                         "` from workqueue10/ to see why.")}])))))

(defn report-orgs
  "Judge each org directory and render its law page, printing each
  error as a finding with its file and message; answers how many
  errors there were. An org that does not load is not rendered: the
  page would fail on the same load."
  [dirs]
  (reduce
   (fn [n ^File dir]
     (let [file (str "orgs/" (.getName dir))
           fixture? (conform/fixture? dir)
           errs (org-errors dir)
           errs (if (some #(= :load (:class %)) errs)
                  errs
                  (-> errs (into (law-errors dir))
                      (into (when-not fixture? (week-errors dir)))))]
       (when fixture?
         (println (str "  [org] " file " is a fixture; its week is not judged")))
       (if (empty? errs)
         (println (str "  [org] " file ": conforms, its law page renders"))
         (doseq [{:keys [class key sentence remedy]} errs]
           (println (str "  ✗ " file " [" (name class) "] " key ": " sentence))
           (when (seq remedy)
             (println (str "      " remedy)))))
       (+ n (count errs))))
   0
   dirs))

(defn- repo-root
  "The worktree's top: git's answer, else the parent of workqueue10/."
  ^File []
  (io/file (or (first (git-lines "." "rev-parse" "--show-toplevel"))
               (.getParent (.getCanonicalFile (io/file "."))))))

(defn -main [& _]
  (let [code (long (check/run "waymark10.check-all/resources"))
        dirs (touched-orgs (repo-root))
        errors (long (report-orgs dirs))]
    (when (seq dirs)
      (println (str (if (pos? errors) "✗ " "✓ ")
                    (count dirs) " org" (when (not= 1 (count dirs)) "s")
                    ", " errors " error" (when (not= 1 errors) "s"))))
    (shutdown-agents)
    (System/exit (max code (if (pos? errors) 1 0)))))
