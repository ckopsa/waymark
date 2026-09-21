(ns localfire.runs
  "One run's record on disk (R-7.1), and the directory it runs in
  (R-5.3).

  The record IS the state. The server holds no queue and no database:
  a run is a directory under `:runs-dir` named by the session id the
  engine already holds, and everything a person or a restart needs to
  know is in the four files there. That is what makes R-5.6 a
  three-line rule — a restart reads the records that say `running`,
  calls them `lost`, and starts nothing again."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [jsonista.core :as json])
  (:import [java.io File]
           [java.nio.file CopyOption FileVisitOption Files LinkOption Path
            StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(def ^:private no-link-options (make-array LinkOption 0))
(def ^:private no-attrs (make-array FileAttribute 0))
(def ^:private no-visit-options (make-array FileVisitOption 0))

(defn now-iso
  "An instant as the record spells it: ISO-8601, so `run.edn` stays
  plain EDN that any reader opens with no tagged-literal table."
  []
  (str (Instant/now)))

(defn run-dir ^File [runs-dir id] (io/file (str runs-dir) (str id)))
(defn place-dir ^File [runs-dir id] (io/file (run-dir runs-dir id) "place"))
(defn mcp-file ^File [runs-dir id] (io/file (run-dir runs-dir id) "mcp.json"))
(defn run-edn-file ^File [runs-dir id] (io/file (run-dir runs-dir id) "run.edn"))
(defn fire-file ^File [runs-dir id] (io/file (run-dir runs-dir id) "fire.txt"))
(defn stdout-file ^File [runs-dir id] (io/file (run-dir runs-dir id) "stdout.json"))
(defn stderr-file ^File [runs-dir id] (io/file (run-dir runs-dir id) "stderr.log"))
(defn paused-file ^File [runs-dir routine] (io/file (str runs-dir) "paused" (str routine)))

;; ── the place ───────────────────────────────────────────────────────

(defn copy-tree!
  "Copy `src` onto `dest`, whole (R-5.3). The server copies the place
  for each run and never writes in it.

  COPY_ATTRIBUTES is not decoration: `.claude/hooks/sitting-close.sh`
  must still be executable in the copy or the session's Stop hook does
  not run at all (R-8.3, R-8.4). The explicit `setExecutable` after it
  is for a filesystem that drops the POSIX bits."
  [^File src ^File dest]
  (let [s (.toPath src)
        d (.toPath dest)]
    (Files/createDirectories d no-attrs)
    (with-open [walk (Files/walk s no-visit-options)]
      (doseq [^Path p (iterator-seq (.iterator walk))]
        (let [rel    (str (.relativize s p))
              target (if (str/blank? rel) d (.resolve d rel))]
          (if (Files/isDirectory p no-link-options)
            (Files/createDirectories target no-attrs)
            (do (Files/createDirectories (.getParent target) no-attrs)
                (Files/copy p target
                            ^"[Ljava.nio.file.CopyOption;"
                            (into-array CopyOption
                                        [StandardCopyOption/REPLACE_EXISTING
                                         StandardCopyOption/COPY_ATTRIBUTES]))
                (when (.canExecute (.toFile p))
                  (.setExecutable (.toFile target) true false)))))))))

(defn write-mcp!
  "The one file the server writes beside the copied place (R-5.3): the
  engine's MCP door, named so `--strict-mcp-config` lets the session
  reach that door and nothing else."
  [^File f {:keys [name url]}]
  (io/make-parents f)
  (spit f (json/write-value-as-string
           {"mcpServers" {(str name) {"type" "http" "url" (str url)}}})))

;; ── the record ──────────────────────────────────────────────────────

(defn write-run-edn! [runs-dir id m]
  (let [f (run-edn-file runs-dir id)]
    (io/make-parents f)
    (spit f (with-out-str (pprint/pprint m)))))

(defn read-run-edn
  "One run's record, or nil. A record a half-written restart left
  behind reads as nil rather than throwing: a broken record must not
  take the run list down with it."
  [runs-dir id]
  (let [f (run-edn-file runs-dir id)]
    (when (.isFile f)
      (try (let [m (edn/read-string (slurp f))] (when (map? m) m))
           (catch Exception _ nil)))))

(defn begin!
  "Make the run's directory and write what is known at the start
  (R-5.3, R-7.1): the place copied, `mcp.json`, `fire.txt` with the
  key withheld, and `run.edn` saying `running`.

  It happens before the process starts, so that the URL the engine was
  just handed already answers."
  [cfg {:keys [id routine model fire-text]}]
  (let [runs-dir (:runs-dir cfg)
        dir      (run-dir runs-dir id)]
    (.mkdirs dir)
    (copy-tree! (io/file (:place cfg)) (place-dir runs-dir id))
    (write-mcp! (mcp-file runs-dir id) (:mcp cfg))
    (spit (fire-file runs-dir id) (or fire-text ""))
    (let [rec {:id id :routine routine :model model :status :running
               :started-at (now-iso) :ended-at nil :exit nil}]
      (write-run-edn! runs-dir id rec)
      rec)))

(defn finish!
  "Rewrite the record when the process ends (R-7.1). `status` is one of
  `done`, `failed`, `killed`."
  [cfg id status exit]
  (let [runs-dir (:runs-dir cfg)
        rec      (assoc (or (read-run-edn runs-dir id) {:id id})
                        :status status :exit exit :ended-at (now-iso))]
    (write-run-edn! runs-dir id rec)
    rec))

(defn list-runs
  "Every record under `:runs-dir`, newest first by `:started-at`, at
  most 100 (R-4.4). `paused/` is the pause files' directory, not a
  run, so it is skipped."
  [runs-dir]
  (->> (.listFiles (io/file (str runs-dir)))
       (filter #(and (.isDirectory ^File %) (not= "paused" (.getName ^File %))))
       (keep (fn [^File d] (some-> (read-run-edn runs-dir (.getName d))
                                   (assoc :id (.getName d)))))
       (sort-by #(str (:started-at %)))
       reverse
       (take 100)
       vec))

(defn mark-lost!
  "R-5.6: a restart starts nothing again, and a run that was in flight
  when the server went down is recorded `lost` at the next start. The
  engine's `already-fired?` is what keeps a replay from becoming a
  second run, so the server needs no queue to be honest about this —
  only a name for the runs whose end it did not see.

  → the number of records it changed."
  [runs-dir]
  (let [dir (io/file (str runs-dir))]
    (if-not (.isDirectory dir)
      0
      (count
       (for [^File d (.listFiles dir)
             :when (and (.isDirectory d) (not= "paused" (.getName d)))
             :let  [id  (.getName d)
                    rec (read-run-edn runs-dir id)]
             :when (= :running (:status rec))]
         (write-run-edn! runs-dir id
                         (assoc rec :status :lost :ended-at (now-iso))))))))

;; ── the pause (R-4.4) ───────────────────────────────────────────────

(defn paused?
  "A pause is a file, so it survives a restart."
  [runs-dir routine]
  (.isFile (paused-file runs-dir routine)))

(defn pause! [runs-dir routine]
  (let [f (paused-file runs-dir routine)]
    (io/make-parents f)
    (spit f (str (now-iso) "\n"))
    true))

(defn resume! [runs-dir routine]
  (.delete (paused-file runs-dir routine))
  false)

;; ── reading a record back for the page ──────────────────────────────

(defn tail
  "The last `n` characters of a file, or nil. The page shows the tail
  of `stderr.log` (R-7.2) because a run that failed says why at the
  end, and a run that did not can print megabytes."
  [^File f n]
  (when (.isFile f)
    (let [s (slurp f)]
      (if (> (count s) n) (subs s (- (count s) n)) s))))

(defn slurp-file [^File f]
  (when (.isFile f) (slurp f)))
