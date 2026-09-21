(ns localfire.process-test
  "R-9.3: one real process, through the real spawner.

  The rest of the suite replaces the spawner, which is the right trade
  everywhere except here: the two things that only a real process can
  show are that its stdout arrives whole on the drain thread and lands
  in `stdout.json` parsed, and that R-5.5's kill actually ends a
  process that will not end on its own. `sh -c` with a short script
  stands in for `claude -p`, so the suite still needs no binary, no
  network and no money.

  `execute-run!` is called with the argument vector directly rather
  than through the fire, because the argument vector of R-5.4 is
  `claude`'s and is proved in `server-test`; what is under test here
  is the machinery around the process."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [localfire.runs :as runs]
            [localfire.server :as server]
            [localfire.server-test :as w]
            [localfire.spawn :as spawn])
  (:import [java.util UUID]))

(defn- world
  "A config and a state with the REAL spawner. No server stands: these
  tests call `execute-run!` straight."
  []
  (let [place (w/make-place!)
        runs  (w/tmpdir "lf-proc")
        cfg   (w/make-config {:port 0 :place place :runs-dir runs})]
    {:config cfg :runs runs
     :state  {:config cfg :spawner (spawn/process-spawner) :running (atom {})}}))

(defn- begin! [{:keys [config]} id]
  (runs/begin! config {:id id :routine "sh" :model "none"
                       :fire-text "Seat: s\nKey: <withheld>\n"}))

(deftest a-run-that-exits-0-is-done-with-its-stdout-parsed
  (let [{:keys [state runs] :as world} (world)
        id (str (UUID/randomUUID))]
    (begin! world id)
    (let [rec (server/execute-run!
               state
               {:id id
                :argv ["sh" "-c"
                       (str "printf '%s' '{\"type\":\"result\",\"num_turns\":2,"
                            "\"total_cost_usd\":0.5,\"usage\":{\"input_tokens\":7}}';"
                            " echo 'a word on stderr' 1>&2")]
                :dir (runs/place-dir runs id)
                :max-run-seconds 30})]
      (is (= :done (:status rec)))
      (is (= 0 (:exit rec)))
      (is (string? (:ended-at rec)))
      (testing "the record on disk is the one the page reads"
        (is (= :done (:status (runs/read-run-edn runs id)))))
      (testing "stdout landed whole and parses as Claude Code's result JSON"
        (let [parsed (json/read-value (slurp (runs/stdout-file runs id)))]
          (is (= 2 (get parsed "num_turns")))
          (is (= 7 (get-in parsed ["usage" "input_tokens"])))))
      (testing "stderr landed too"
        (is (str/includes? (slurp (runs/stderr-file runs id)) "a word on stderr")))
      (testing "the run ran in its own copy of the place, hook still executable"
        (is (.canExecute (io/file (runs/place-dir runs id)
                                  ".claude/hooks/sitting-close.sh")))))))

(deftest a-run-that-exits-non-zero-is-failed
  (let [{:keys [state runs] :as world} (world)
        id (str (UUID/randomUUID))]
    (begin! world id)
    (let [rec (server/execute-run! state {:id id
                                          :argv ["sh" "-c" "exit 3"]
                                          :dir (runs/place-dir runs id)
                                          :max-run-seconds 30})]
      (is (= :failed (:status rec)))
      (is (= 3 (:exit rec))))))

(deftest a-run-past-its-max-run-seconds-is-killed
  (let [{:keys [state runs] :as world} (world)
        id (str (UUID/randomUUID))
        _  (begin! world id)
        t0 (System/currentTimeMillis)
        rec (server/execute-run! state {:id id
                                        :argv ["sh" "-c" "sleep 60"]
                                        :dir (runs/place-dir runs id)
                                        ;; R-5.5, with the one second the
                                        ;; spec's own test asks for
                                        :max-run-seconds 1})
        took (- (System/currentTimeMillis) t0)]
    (is (= :killed (:status rec)))
    (is (string? (:ended-at rec)))
    (testing "it did not wait out the sleep, and it did not return early"
      (is (>= took 1000))
      (is (< took 20000)))
    (testing "the record on disk says killed, which is what the sweep reads"
      (is (= :killed (:status (runs/read-run-edn runs id)))))))
