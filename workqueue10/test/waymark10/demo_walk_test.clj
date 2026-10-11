(ns waymark10.demo-walk-test
  "The demo walker's whole path (docs/spec-agent-demo-walks.md § 6b): one
  agent, alone in an engine that holds only the demo seed, records a
  self walk through the connector, moves the clock, seals the walk and
  reads its file back. The file's cast holds only seeded names and its
  header names a demo engine: the two checks docs/routines/demo-walker.md
  asks of the seat before it films.

  The factory's kinds over the in-memory twin, the demo clock laid over
  the engine as a boot with WAYMARK10_SEED lays it, and the real handler
  through POST /api/-/mcp as the seed's agent member, which is the call
  the clone door forwards (§ 6a). No database, no network, no person.
  The real clock steps a millisecond on every read, so each frame has
  its own `t`.

  Run: cd workqueue10 && clojure -M:test --focus waymark10.demo-walk-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as factory]
            [waymark10.server.clock-shift :as clock-shift]
            [waymark10.server.engine :as engine]
            [waymark10.server.presence :as presence]
            [waymark10.server.seed :as seed]
            [waymark10.server.store.memory :as memory]
            [waymark10.wire :as wire])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Instant)))

(def ^:private demo (seed/read-seed "demo"))

(def ^:private engine-name "demo-walk")

(def ^:private title "Juniper puts the chore list first")

;; ── the door ────────────────────────────────────────────────────────

(def ^:private headers
  "The seed's agent member, as the clone door's `as` names it."
  {"x-waymark-principal" (get-in demo [:cast :plan :id])})

(defn- call
  "One tools/call as the agent → {:tool :ok? :text :doc}."
  [h tool-name args]
  (let [resp (h {:request-method :post :uri "/api/-/mcp" :headers headers
                 :body (wire/write-json
                        {:jsonrpc "2.0" :id 1 :method "tools/call"
                         :params {:name tool-name :arguments args}})})
        result (:result (wire/read-json (:body resp)))
        text (get-in result [:content 0 :text])]
    {:tool tool-name
     :ok? (and (= 200 (:status resp)) (not (:isError result)))
     :text text
     :doc (try (some-> text wire/read-json) (catch Exception _ nil))}))

(defn- id-of [doc]
  (or (some-> (:id doc) str not-empty)
      (some-> (:self doc) str (str/split #"/") last)))

(defn- utf8 ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- sha256 [^String s]
  (apply str (map #(format "%02x" %)
                  (.digest (MessageDigest/getInstance "SHA-256") (utf8 s)))))

;; ── the sitting's steps 2 to 4, with nobody else in the engine ──────

(defn- walk-alone!
  "Seed an engine, then make every call the agent makes inside a clone:
  the walk, the work with a caption on each step, a shift of the clock,
  the seal and the export. → {:calls :export :file}, where `:calls` are
  the answers in order, `:export` is the connector's answer and `:file`
  is the route's bytes."
  []
  (let [clock (atom (Instant/parse "2026-10-01T12:00:00Z"))
        eng (clock-shift/install!
             (engine/engine
              (clock-shift/with-clock
                {:storage (memory/storage)
                 :resources (factory/resources)
                 :name engine-name
                 :now-fn (fn [] (swap! clock (fn [^Instant i] (.plusMillis i 1))))})))
        reg (presence/start! eng {:hb-ms 600000})]
    (swap! (:runtime eng) assoc :presence reg)
    (try
      (let [seeded (seed/load! eng demo {})
            h (engine/handler eng)
            ticket (get-in seeded [:refs :h-next :id])
            born (call h "waymark_invoke"
                       {:kind "walk" :action "create"
                        :input {:followed (get headers "x-waymark-principal")
                                :title title :docs true}})
            w (id-of (:doc born))
            work [(call h "waymark_query"
                        {:kind "ticket" :filter {:state "open"}
                         :caption "The house keeps its work in one queue."})
                  (call h "waymark_get"
                        {:kind "ticket" :id ticket
                         :caption "Juniper opens the chore list ticket."})
                  (call h "waymark_invoke"
                        {:kind "ticket" :id ticket :action "prioritize"
                         :input {:priority 1}
                         :caption "The house wants it soon, so it goes first."
                         :caption_field "priority"})
                  (call h "waymark_invoke"
                        {:kind "clock_shift" :action "create"
                         :input {:by "PT18H"}
                         :caption "The next morning."})
                  (call h "waymark_query"
                        {:kind "ticket" :filter {:state "open"}
                         :caption "The chore list is at the top of the queue."})]
            sealed (call h "waymark_invoke" {:kind "walk" :id w :action "seal"})
            export (call h "waymark_get" {:kind "walk" :id w :return "export"})
            file (:body (h {:request-method :get
                            :uri (str "/api/walks/" w "/export")
                            :headers headers}))]
        {:calls (into [born] (conj work sealed export))
         :export export
         :file (str file)})
      (finally (presence/stop! reg)))))

(def ^:private walked
  "One walk for the three tests: they read the same file."
  (delay (walk-alone!)))

(defn- lines-of [file] (mapv wire/read-json (str/split-lines file)))

;; ── the tests ───────────────────────────────────────────────────────

(deftest an-agent-alone-records-seals-and-exports-a-demo-walk
  (let [{:keys [calls export file]} @walked
        d (:doc export)
        frames (rest (lines-of file))
        types (set (map :type frames))]
    (testing "every call the agent made was answered"
      (doseq [c calls]
        (is (:ok? c) (str (:tool c) " " (:text c)))))
    (testing "the sealed walk's file is read back through the connector"
      (is (= "waymark-walk/1" (:format d)))
      (is (pos? (long (or (:frames d) 0))))
      (is (= (alength (utf8 file)) (:bytes d)))
      (is (= (sha256 file) (:sha256 d))))
    (testing "the file holds the screens, the words and the writes"
      (doseq [type ["move" "ui" "caption" "transition" "doc"]]
        (is (contains? types type) type)))
    (testing "each caption the agent wrote is a line of the file"
      (is (= ["The house keeps its work in one queue."
              "Juniper opens the chore list ticket."
              "The house wants it soon, so it goes first."
              "The next morning."
              "The chore list is at the top of the queue."]
             (->> frames (filter #(= "caption" (:type %))) (mapv :text)))))
    (testing "nobody but the agent is in a frame"
      (is (= #{"a1"} (set (keep :who frames)))))))

(deftest the-demo-walks-cast-holds-only-seeded-names
  (let [{:keys [export]} @walked
        cast (get-in export [:doc :header :cast])
        seeded (set (map :display (vals (:cast demo))))]
    (is (seq cast))
    (is (= {:display "Juniper" :type "agent"} (:a1 cast))
        "the agent is the seed's agent member, and never a seat")
    (doseq [[alias member] cast]
      (is (contains? seeded (:display member)) (str alias " " (pr-str member))))))

(deftest the-demo-walks-header-names-a-demo-engine
  (let [{:keys [export file]} @walked
        header (get-in export [:doc :header])]
    (is (= engine-name (:engine header)))
    (is (str/starts-with? (str (:engine header)) "demo-"))
    (is (= title (:title header)))
    (is (= header (first (lines-of file)))
        "the connector's header is the file's first line")))
