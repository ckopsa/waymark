(ns waymark10.mcp-bench-test
  "waymark10.server.mcp.bench, carved out of mcp (ticket 31bf1d7c): the
  bench and the change a code seat's sit hands it, read without an
  engine. mcp_sit_test proves the same through the sit; this file pins
  the carve itself — the minting left mcp, and what mcp still reads
  from here answers as it did."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.mcp]
            [waymark10.server.mcp.bench :as mcp-bench]))

(deftest the-minting-left-mcp
  (doseq [sym '[minted-change change-of-sitting bench-branch
                seat-repositories code-seat? bench-payload]]
    (is (nil? (ns-resolve 'waymark10.server.mcp sym)) (str sym))))

(deftest a-change-names-its-own-branch-first
  (is (= "feat/x" (mcp-bench/bench-branch {:id "c1" :data {:branch "feat/x"}} nil)))
  (is (= "bench/t1" (mcp-bench/bench-branch {:id "c1" :data {:head_branch "bench/t1"}} nil)))
  (testing "else the policy's pattern, else waymark/*, with the change's id"
    (is (= "wip/c1" (mcp-bench/bench-branch {:id "c1" :data {}}
                                            {:data {:branch_pattern "wip/*"}})))
    (is (= "waymark/c1" (mcp-bench/bench-branch {:id "c1" :data {}} nil)))))

(deftest a-refusal-is-no-worktree
  (let [made {:structuredContent {:result {:repo "r" :branch "b"}}}
        refused {:structuredContent {:result {:refused "bench" :reason "no"}}}]
    (is (= {:repo "r" :branch "b"} (mcp-bench/bench-payload made)))
    (is (nil? (mcp-bench/bench-refusal made)))
    (is (nil? (mcp-bench/bench-payload refused)))
    (is (= "no" (:reason (mcp-bench/bench-refusal refused))))
    (is (nil? (mcp-bench/bench-payload (assoc made :isError true))))))

(deftest a-seat-with-no-bench-is-handed-no-change
  (is (= [nil nil] (mcp-bench/change-of-sitting nil {:data {:scope []}} nil)))
  (is (= [nil nil] (mcp-bench/change-of-sitting
                    nil {:data {:scope []}}
                    {"kind" "ticket" "rows" [{"id" "t1"}]}))))

(deftest a-seat-that-writes-one-repository-works-it
  (let [entry (fn [kind repo] {:kind kind :actions [] :filter {:repo repo}})
        seat {:data {:scope [(entry "bench.edit" "o/a")
                             (entry "bench.read" "o/a,o/b")]}}]
    (is (= {:repo "o/a" :reference ["o/b"]} (mcp-bench/seat-repositories seat)))
    (is (true? (mcp-bench/code-seat? seat)))))
