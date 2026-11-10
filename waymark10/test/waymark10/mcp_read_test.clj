(ns waymark10.mcp-read-test
  "waymark10.server.mcp.read, carved out of mcp (ticket c7c2b65a): the
  read tools' definitions. The MCP conformance pack proves the tools
  through the dispatch; this file pins the carve itself — mcp still
  lists every tool it now reads from here, under the same wire names."
  (:require [clojure.test :refer [deftest is]]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.mcp.read :as mcp-read]))

(def ^:private carved
  [mcp-read/discover-tool mcp-read/schema-tool mcp-read/query-tool
   mcp-read/get-tool mcp-read/history-tool mcp-read/resolve-tool
   mcp-read/in-flight-tool])

(deftest mcp-lists-the-carved-tools
  (doseq [t carved]
    (is (some #{t} mcp/tools) (:name t))))

(deftest the-wire-names-did-not-move
  (is (= ["waymark_discover" "waymark_schema" "waymark_query" "waymark_get"
          "waymark_history" "waymark_resolve" "waymark_in_flight"]
         (mapv :name carved))))

(deftest the-staged-reads-take-a-caption
  (is (= mcp-read/caption-arg
         (get-in mcp-read/query-tool [:input-schema :properties :caption])))
  (is (= mcp-read/caption-arg
         (get-in mcp-read/get-tool [:input-schema :properties :caption]))))
