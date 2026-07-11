(ns waymark10.session
  "The persisted session file (auth and the idempotency key-store),
  keyed per base-url, so one --as suffices for a whole shell session —
  hoisted from the CLI so any future front-end against the same
  base-url replays its in-flight retries instead of duplicating."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn default-path []
  (str (System/getProperty "user.home") "/.waymark10/session.edn"))

(defn load-file* [path]
  (try (or (edn/read-string (slurp path)) {})
       (catch Exception _ {})))

(defn save-file! [path data]
  (io/make-parents path)
  (spit path (pr-str data)))
