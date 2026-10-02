(ns waymark10.sit-bench-prepare-test
  "The sit's bench prepare waits for a slow rig (ticket 57fda9cb): the
  prepare gets its own bound in place of the client's, a prepare that
  ran out that bound is tried once more, and a rig that refuses the
  connection is not waited on. The bounds are scaled down to
  milliseconds; the client's own bound stands in for the old 30 s."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.mcp-client :as client]))

(def ^:private prepare-answer @#'mcp/prepare-answer)

(def ^:private answered {:content [{:type "text" :text "{}"}]})

(defn- slow-rig
  "A rig whose calls answer after `delay-ms`, bounded by the client's
  own `timeout-ms` as every row's client is, counting its calls."
  [calls delay-ms timeout-ms]
  (client/with-timeout
    (fn [_method _params]
      (swap! calls inc)
      (Thread/sleep (long delay-ms))
      answered)
    timeout-ms))

(deftest a-prepare-slower-than-the-client-bound-hands-the-worktree
  (let [calls (atom 0)
        rig (slow-rig calls 250 100)]
    (testing "the client's own bound alone gives up on it"
      (is (thrown? Exception (rig "tools/call" {}))))
    (reset! calls 0)
    (testing "the prepare's own bound waits for it"
      (is (= answered (prepare-answer rig {:repo "r" :branch "b"} 1000)))
      (is (= 1 @calls)))))

(deftest a-prepare-that-times-out-twice-gives-no-bench
  (let [calls (atom 0)
        rig (slow-rig calls 2000 100)]
    (is (nil? (prepare-answer rig {:repo "r" :branch "b"} 150)))
    (is (= 2 @calls) "tried once more after the first timeout, then no more")))

(deftest a-prepare-that-times-out-once-is-tried-again
  (let [calls (atom 0)
        rig (client/with-timeout
              (fn [_method _params]
                (when (= 1 (swap! calls inc)) (Thread/sleep 2000))
                answered)
              100)]
    (is (= answered (prepare-answer rig {:repo "r" :branch "b"} 150)))
    (is (= 2 @calls))))

(deftest a-refused-connection-gives-no-bench-without-waiting
  (let [calls (atom 0)
        http (client/http-client "http://127.0.0.1:1/mcp" {:timeout-ms 30000})
        rig (fn [method params] (swap! calls inc) (http method params))
        t0 (System/nanoTime)
        out (prepare-answer rig {:repo "r" :branch "b"} 5000)
        ms (quot (- (System/nanoTime) t0) 1000000)]
    (is (nil? out))
    (is (= 1 @calls) "a refusal is not a timeout, so it is not tried again")
    (is (< ms 5000) "it answered before the prepare's bound ran out")))
