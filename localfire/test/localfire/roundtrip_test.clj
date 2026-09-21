(ns localfire.roundtrip-test
  "R-9.2: the round trip, which pins the wire from both sides.

  Every other test in this module asserts what THIS module believes
  the engine sends and reads. That is worth little on its own: the
  wire of §2 is the engine's, recorded in `schedules.clj`, and a
  belief about it can drift. So this one test stands the server on an
  ephemeral port and fires at it with `schedules/fire-routine` on
  `schedules/routine-fire` — the engine's OWN adapter, the same record
  a cloud firing goes through, with its own headers, its own twenty
  second timeout and its own reading of the answer.

  The four outcomes are the four rows of §2's failure table, and each
  is checked against the engine's own sentence for it
  (`schedules/provider-note`), because that sentence is what lands on
  the schedule row.

  waymark10 is on the classpath for the `:test` alias only (R-4.1);
  the server itself depends on nothing of the engine at run time."
  (:require [clojure.test :refer [deftest is testing]]
            [localfire.server :as server]
            [localfire.server-test :as w]
            [waymark10.server.schedules :as sch]))

(defn- caught
  "Fire and return the ex-info the adapter threw, or nil."
  [adapter url token text]
  (try (sch/fire-routine adapter url token text) nil
       (catch clojure.lang.ExceptionInfo e e)))

(deftest the-engines-own-adapter-fires-at-this-server
  (let [gate    (promise)
        world   (w/world {:routines {"sonnet" {:model "claude-sonnet-4-5"
                                               :max-concurrent 1}}
                          :gate gate})
        adapter (sch/routine-fire)
        url     (str (:base world) "/fire/sonnet")
        text    (str "You sit in the seat `ci-classifier`.\n"
                     "Seat: ci-classifier\n"
                     "Key: sk-round-trip\n")]
    (try
      (testing "the right token answers :session-id and :session-url"
        (let [{:keys [session-id session-url]}
              (sch/fire-routine adapter url "the-token" text)]
          (is (string? session-id))
          (is (= session-id (str (java.util.UUID/fromString session-id))))
          (is (= (str (:base world) "/runs/" session-id) session-url))
          (testing "and that URL is the page the engine puts on the row"
            (is (= 200 (:status (w/GET session-url)))))))

      ;; the one slot of the routine is now held open by the fake
      ;; spawner's gate, which is what makes the 429 below reachable
      (testing "a wrong token throws with :status 401"
        (let [e (caught adapter url "not-the-token" text)]
          (is (some? e))
          (is (= 401 (:status (ex-data e))))
          (is (= "The Routine refused the token." (sch/provider-note 401 nil)))))

      (testing "an unknown routine throws with :status 404"
        (let [e (caught adapter (str (:base world) "/fire/no-such") "the-token" text)]
          (is (some? e))
          (is (= 404 (:status (ex-data e))))
          (is (= "No Routine answers the fire URL." (sch/provider-note 404 nil)))))

      (testing "a routine at its cap throws with :status 429 and :retry-after"
        (let [e (caught adapter url "the-token" text)]
          (is (some? e))
          (is (= 429 (:status (ex-data e))))
          (is (= "60" (:retry-after (ex-data e))))
          (is (= "The Routine has no free run. Try again after 60."
                 (sch/provider-note 429 (:retry-after (ex-data e)))))))

      (testing "a paused routine throws with :status 400, before the cap is read"
        (is (= 200 (:status (w/POST (str (:base world) "/routines/sonnet/pause")
                                    "the-token"))))
        (let [e (caught adapter url "the-token" text)]
          (is (some? e))
          (is (= 400 (:status (ex-data e))))
          (is (= "The Routine is paused at the provider." (sch/provider-note 400 nil)))))

      (testing "a fire with no text at all is a fire (the adapter sends {})"
        (is (= 200 (:status (w/POST (str (:base world) "/routines/sonnet/resume")
                                    "the-token"))))
        (deliver gate true)
        (is (w/wait-for #(zero? (server/running-count world "sonnet"))))
        (is (string? (:session-id (sch/fire-routine adapter url "the-token" nil)))))
      (finally
        (deliver gate true)
        (server/stop! world)))))
