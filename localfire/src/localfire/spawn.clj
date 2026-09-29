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
  (:import [java.lang ProcessBuilder$Redirect]
           [java.util.concurrent TimeUnit]))

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
      ;; an empty stdin: nothing is ever written to it, and a pipe left
      ;; open makes `claude -p` wait three seconds for input first
      (.redirectInput pb (ProcessBuilder$Redirect/from (io/file "/dev/null")))
      (->ProcessHandle (.start pb)))))

(defn run-to-end
  "Run one short process to its end with `input` written on its stdin →
  `{:exit :out}`, `:out` its standard output trimmed, `:exit` nil when
  it outlived `timeout-ms` and was killed. This is the sitting's close
  (R-5.6): a hook that reads its JSON on stdin and answers one line. It
  is not a run, so it is not the Spawner, whose processes get an empty
  stdin."
  ([argv dir input] (run-to-end argv dir input 60000))
  ([argv dir input timeout-ms]
   (let [pb (ProcessBuilder. ^java.util.List (vec (map str argv)))]
     (.directory pb (io/file dir))
     (.redirectError pb ProcessBuilder$Redirect/DISCARD)
     (let [p   (.start pb)
           out (future (slurp (.getInputStream p)))]
       ;; a hook that exits before it reads its stdin breaks the pipe
       (try (with-open [w (io/writer (.getOutputStream p))] (.write w (str input)))
            (catch java.io.IOException _ nil))
       (if (.waitFor p (long timeout-ms) TimeUnit/MILLISECONDS)
         {:exit (.exitValue p) :out (.trim (str (deref out 5000 "")))}
         (do (.destroyForcibly p)
             {:exit nil :out "failed (none): the hook outlived its time and was killed"}))))))

(defn process-spawner
  "The real spawner. It and `run-to-end` are the only things in the
  module that start a process, and `clojure -M:serve` is the only caller
  that uses it."
  []
  (->ProcessBuilderSpawner))
