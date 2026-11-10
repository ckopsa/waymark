(ns waymark10.mcp-gate-test
  "waymark10.server.mcp.gate, carved out of mcp (ticket 5ef47ef7): the
  power tools' definitions and the shape of a power answer, read
  without a Gate. mcp_power_shape_test proves the same through the
  dispatch; this file pins the carve itself — mcp still lists the
  tools it now reads from here, and the metadata keys kept mcp's
  spelling."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.mcp.gate :as mcp-gate]))

(deftest mcp-lists-the-carved-tools
  (is (some #{mcp-gate/powers-tool} mcp/tools))
  (is (some #{mcp-gate/power-tool} mcp/tools)))

(deftest a-shape-nobody-asked-for-is-no-shape
  (let [payload {:content [{:type "text" :text "<p>hello</p>"}]}]
    (is (identical? payload (mcp-gate/shaped payload {})))
    (is (= 0 (mcp-gate/dropped-bytes (mcp-gate/shaped payload {}))))))

(deftest a-cap-records-what-it-dropped
  (let [payload {:content [{:type "text" :text (apply str (repeat 500 "a"))}]}
        out (mcp-gate/shaped payload {:max_chars 100})]
    (is (< (mcp-gate/result-bytes out) (mcp-gate/result-bytes payload)))
    (is (pos? (mcp-gate/dropped-bytes out)))
    (testing "under the key mcp has always counted"
      (is (pos? (long (:waymark10.server.mcp/dropped (meta out))))))))

(deftest a-refusal-is-never-shaped
  (let [payload {:isError true :content [{:type "text" :text "no"}]}]
    (is (identical? payload (mcp-gate/shaped payload {:text_only true})))))

(deftest the-rig-adds-what-it-dropped
  (let [out (mcp-gate/rig-dropped {:structuredContent {:result {:dropped 7}}})]
    (is (= 7 (mcp-gate/dropped-bytes out)))))

(deftest which-power-answers-are-refusals
  (is (true? (mcp-gate/power-refusal? "waymark_power" {:isError true})))
  (is (false? (mcp-gate/power-refusal? "waymark_power" {:isError false})))
  (is (false? (mcp-gate/power-refusal? "waymark_get" {:isError true})))
  (is (false? (mcp-gate/power-refusal?
               "waymark_power"
               (with-meta {:isError true} {:waymark10.server.mcp/status 502})))
      "a dark Gate is the wire failing, not law"))

(deftest the-seat-ceiling-is-read-off-the-session
  (is (nil? (mcp-gate/seat-byte-ceiling {})))
  (is (= 4096 (mcp-gate/seat-byte-ceiling
               {:visibility {:seat {:bench_max_bytes 4096}}}))))
