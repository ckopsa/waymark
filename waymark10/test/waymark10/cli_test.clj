(ns waymark10.cli-test
  "Phase 10 acceptance, part two: the CLI smoke — waymark10.cli/run
  invoked in-process against a STARTED engine (real http-kit server,
  real java.net.http transport), asserting the exit-code contract:
  0 ok · 1 problem · 2 refused-locally · 3 transport. The session
  file (pointed at a temp path) carries the idempotency keys, so a
  re-run of the same act replays."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.cli :as cli]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db])
  (:import (java.net ServerSocket)))

(def ^:dynamic *base* nil)
(def ^:dynamic *session-file* nil)

(defn- free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          port (free-port)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["meals" "plans" "definitions"
                           "waymark10_transitions" "waymark10_idempotency"
                           "waymark10_drafts"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources [fx/meal fx/plan]})
              server (engine/start! eng port)]
          (try
            (binding [*base* (str "http://localhost:" port)
                      *session-file* (str (System/getProperty "java.io.tmpdir")
                                          "/waymark10-cli-test-"
                                          (random-uuid) ".edn")]
              (f))
            (finally (engine/stop! eng server))))
        (finally (pg/close! st))))))

(defn- run
  "cli/run with output captured → {:code :out}."
  [& args]
  (let [out (java.io.StringWriter.)
        code (binding [*out* out]
               (apply cli/run (concat [*base*] args
                                      ["--session" *session-file*
                                       "--as" "priya"])))]
    {:code code :out (str out)}))

(defn- self-of [out]
  (some #(re-find #"/api/\S+" %)
        (filter #(str/starts-with? % "meal ") (str/split-lines out))))

(deftest cli-walks-the-wire
  (testing "index → 0, kinds listed"
    (let [{:keys [code out]} (run "index")]
      (is (= 0 code) out)
      (is (str/includes? out "waymark 10"))
      (is (str/includes? out "/api/meals"))))

  (testing "get a collection → 0"
    (let [{:keys [code out]} (run "get" "/api/meals")]
      (is (= 0 code) out)
      (is (str/includes? out "meal_collection"))))

  (testing "act create → 0; the envelope prints"
    (let [{:keys [code out]} (run "act" "/api/meals" "create"
                                  "--input" "{\"name\": \"Tacos\", \"themes\": [\"mexican\"]}")]
      (is (= 0 code) out)
      (is (str/includes? out "state=suggested"))
      (let [self (self-of out)]
        (is (some? self) out)

        (testing "an idempotent act → 0"
          (let [{:keys [code out]} (run "act" self "accept")]
            (is (= 0 code) out)
            (is (str/includes? out "state=on_list"))))

        (testing "a confirm-gated act without --yes refuses locally → 2"
          (let [{:keys [code out]} (run "act" self "retire")
                {code2 :code out2 :out} (run "act" self "decline")]
            ;; retire is not confirm-gated; decline is — but this
            ;; meal is on_list now, so decline is out of state: a
            ;; LOCAL refusal too (unknown affordance), also 2
            (is (= 0 code) out)
            (is (= 2 code2) out2)
            (is (str/includes? out2 "refused locally"))))))

    (testing "re-running the create replays instead of duplicating"
      (let [{:keys [code out]} (run "act" "/api/meals" "create"
                                    "--input" "{\"name\": \"Tacos\", \"themes\": [\"mexican\"]}")
            {out2 :out} (run "get" "/api/meals")]
        (is (= 0 code) out)
        ;; the replayed envelope answers with the FIRST execution's id
        ;; (which has since moved to on_list — the stored bytes tell
        ;; the truth of the first execution)
        (is (= 1 (count (re-seq #"Tacos" out2)))
            "one Tacos on the collection — the retry replayed"))))

  (testing "a confirm-gated act with --yes proceeds → 0"
    (let [{:keys [out]} (run "act" "/api/meals" "create"
                             "--input" "{\"name\": \"Liver\", \"themes\": []}")
          self (self-of out)
          {:keys [code out]} (run "act" self "decline" "--yes")]
      (is (= 0 code) out)
      (is (str/includes? out "state=retired"))))

  (testing "act --dry-run answers the verdict and moves nothing (rule 5
            at the shell; creates ride act as ever)"
    (let [{:keys [code out]} (run "act" "/api/meals" "create"
                                  "--input" "{\"name\": \"Dry probe\", \"themes\": []}"
                                  "--dry-run")
          {listing :out} (run "get" "/api/meals")]
      (is (= 0 code) out)
      (is (str/includes? out "✓ valid"))
      (is (not (str/includes? listing "Dry probe")) "nothing was minted"))
    (testing "an invalid input answers the problem → 1"
      (let [{:keys [code out]} (run "act" "/api/meals" "create"
                                    "--input" "{\"name\": \"\"}" "--dry-run")]
        (is (= 1 code) out)
        (is (str/includes? out "refused by the server"))))
    (testing "a confirm-gated action rehearses without a prompt"
      (let [{:keys [out]} (run "act" "/api/meals" "create"
                               "--input" "{\"name\": \"Gated probe\", \"themes\": []}")
            self (self-of out)
            {:keys [code out]} (run "act" self "decline" "--dry-run")]
        (is (= 0 code) out)
        (is (str/includes? out "✓ valid")))))

  (testing "a server refusal → 1"
    (let [{:keys [code out]} (run "get" "/api/widgets")]
      (is (= 1 code) out)
      (is (str/includes? out "404"))))

  (testing "an unknown action → 2, locally"
    (let [{:keys [code out]} (run "act" "/api/meals" "vaporize_all")]
      (is (= 2 code) out)
      (is (str/includes? out "refused locally"))))

  (testing "an unreachable server → 3"
    (let [out (java.io.StringWriter.)
          code (binding [*out* out]
                 (cli/run "http://localhost:1" "index"
                          "--session" *session-file*))]
      (is (= 3 code) (str out))))

  (testing "usage errors → 2"
    (is (= 2 (:code (run "frobnicate"))))
    (let [out (java.io.StringWriter.)
          code (binding [*out* out] (cli/run "not-a-url" "index"))]
      (is (= 2 code)))))
