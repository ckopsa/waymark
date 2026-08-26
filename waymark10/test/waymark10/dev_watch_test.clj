(ns waymark10.dev-watch-test
  "watch! (waymark-6ba), over a temp source tree: a good save
  load-files then reboots; a save that refuses to compile reboots
  nothing and the previous definitions keep serving; the next good
  save heals both. load-file is classpath-independent, which is what
  lets this suite watch a directory the JVM has never heard of."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "waymark10-watch"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- await-until
  "True once f yields truthy, polling; false at the deadline."
  [f]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (cond (f) true
            (< (System/currentTimeMillis) deadline)
            (do (Thread/sleep 50) (recur))
            :else false))))

(defn- fixture-x []
  (some-> (resolve 'waymark10-watch-fixture.alpha/x) deref))

(defn- save!
  "An editor's save: write beside the file, then rename over it, so the
  watcher never sees the file between truncate and write. `spit` does
  see that window — it truncates first — and the watcher stamps on
  [mtime length], so a poll landing inside it load-files an EMPTY
  source (defines nothing, refuses nothing) and reboots on it. On an
  idle laptop the window is microseconds; on a loaded 8-core node
  running six shards it was caught, and the suite read the old value
  after a reboot that had loaded nothing."
  [^java.io.File f ^String content]
  (let [tmp (io/file (.getParentFile f) (str "." (.getName f) ".saving"))]
    (spit tmp content)
    (java.nio.file.Files/move
     (.toPath tmp) (.toPath f)
     (into-array java.nio.file.CopyOption
                 [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                  java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))

(deftest watch-reloads-then-reboots
  (let [dir (temp-dir)
        src (io/file dir "waymark10_watch_fixture" "alpha.clj")
        _ (io/make-parents src)
        _ (spit src "(ns waymark10-watch-fixture.alpha) (def x 1)")
        reboots (atom 0)
        ;; quiet the watcher's narration — the suite asserts on state
        out (java.io.StringWriter.)
        handle (binding [*out* out]
                 (dev/watch! {:paths [(.getPath dir)]
                              :interval-ms 25
                              :restart! #(swap! reboots inc)}))]
    (try
      (testing "a good save load-files the source, then reboots"
        (save! src "(ns waymark10-watch-fixture.alpha) (def x 2)")
        (is (await-until #(= 1 @reboots)))
        (is (= 2 (fixture-x))))
      (testing "a refused save reboots nothing and keeps the old defs"
        (save! src "(ns waymark10-watch-fixture.alpha) (def x")
        (is (await-until #(re-find #"REFUSED" (str out))))
        (is (= 1 @reboots))
        (is (= 2 (fixture-x))))
      (testing "the next good save heals — reload and reboot resume"
        (save! src "(ns waymark10-watch-fixture.alpha) (def x 3)")
        (is (await-until #(= 2 @reboots)))
        (is (= 3 (fixture-x))))
      (testing "a new file is picked up, not only edits"
        (let [beta (io/file dir "waymark10_watch_fixture" "beta.clj")]
          (save! beta "(ns waymark10-watch-fixture.beta) (def y 41)")
          (is (await-until #(= 3 @reboots)))
          (is (= 41 (some-> (resolve 'waymark10-watch-fixture.beta/y) deref)))))
      (finally
        ((:stop! handle))
        (remove-ns 'waymark10-watch-fixture.alpha)
        (remove-ns 'waymark10-watch-fixture.beta)))))
