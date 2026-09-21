(ns localfire.spawn
  "The seam that starts a process (§3, \"the spawner\"; R-9.1).

  Everything else in this module is deterministic — the argument
  vector, the prompt, the record, the page — and the one thing that is
  not is a headless Claude Code taking minutes and costing money. So
  starting a process is a protocol with one operation, and the tests
  give a fake that records the argument vector and the directory,
  writes a canned `stdout.json` on the handle's stdout and exits as
  told. The suite then needs no `claude` binary and no network."
  (:require [clojure.java.io :as io])
  (:import [java.util.concurrent TimeUnit]))

(defprotocol Spawner
  (start [s argv dir env]
    "Start one process: `argv` a vector of strings, `dir` the working
    directory, `env` a map of extra variables to add to the inherited
    environment → a `Handle`."))

(defprotocol Handle
  (stdout [h] "The process's standard output, as a stream.")
  (stderr [h] "The process's standard error, as a stream.")
  (await-exit [h timeout-ms]
    "Block up to `timeout-ms` for the process to end → the exit code,
    or nil when the time ran out (R-5.5 turns that nil into a kill).

    It is not called `wait`: a protocol method of that name puts a
    `wait` on the generated interface, which every implementation
    already inherits from `Object` in three final overloads, and the
    compiler then refuses the call site.")
  (destroy [h] "Ask the process to end.")
  (destroy-forcibly [h] "Make the process end."))

(deftype ProcessHandle [^Process p]
  Handle
  (stdout [_] (.getInputStream p))
  (stderr [_] (.getErrorStream p))
  (await-exit [_ timeout-ms]
    (when (.waitFor p (long timeout-ms) TimeUnit/MILLISECONDS)
      (.exitValue p)))
  (destroy [_] (.destroy p))
  (destroy-forcibly [_] (.destroyForcibly p)))

(deftype ProcessBuilderSpawner []
  Spawner
  (start [_ argv dir env]
    (let [pb (ProcessBuilder. ^java.util.List (vec (map str argv)))]
      (.directory pb (io/file dir))
      ;; the environment is INHERITED and added to. R-8.3: the server
      ;; sets no seat variable, so the Stop hook takes its second path
      ;; and the session closes its own sitting through the connector.
      (doseq [[k v] env] (.put (.environment pb) (str k) (str v)))
      (->ProcessHandle (.start pb)))))

(defn process-spawner
  "The real spawner. It is the only thing in the module that starts a
  process, and `clojure -M:serve` is the only caller that uses it."
  []
  (->ProcessBuilderSpawner))
