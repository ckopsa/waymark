(ns waymark10.server.clock
  "The one clock loop every background sweep in waymark10 runs on.

  `every!` owns what each sweep used to write for itself: the latch,
  the daemon thread and its name, and the catch around each pass. A
  pass that throws is logged and survived — one failed pass never
  kills the loop, and the next pass runs one period later. The first
  pass is one period after the start unless `:first` names something
  to run at once."
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

(defn log-failure!
  "How a pass that threw is told: one line on stderr, naming the loop."
  [loop-name ^Throwable e]
  (binding [*out* *err*]
    (println (str loop-name ": a pass failed (" (ex-message e)
                  "); the next pass still runs"))))

(defn every!
  "Run `f` every `:period-ms` on a daemon thread named `:name`. `:first`,
  when given, runs once on that thread before the first wait (a sweep
  whose first pass is at the start passes its `f` again here). Each pass
  — `:first` included — is caught and logged by `log-failure!`.
  → a stop fn: calling it ends the loop at its next wait."
  [{loop-name :name :keys [period-ms f first]}]
  (let [stop (CountDownLatch. 1)
        pass! (fn [g]
                (try (g)
                     (catch Exception e
                       (log-failure! loop-name e))))
        t (Thread. ^Runnable
                   (fn []
                     (when first (pass! first))
                     (loop []
                       (when-not (.await stop (long period-ms)
                                         TimeUnit/MILLISECONDS)
                         (pass! f)
                         (recur))))
                   ^String loop-name)]
    (doto t (.setDaemon true) (.start))
    (fn stop! [] (.countDown stop) nil)))
