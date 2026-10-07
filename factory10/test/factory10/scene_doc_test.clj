(ns factory10.scene-doc-test
  "The worked scene of docs/spec-scenes.md, judged by the door it
  documents: the Quests demo is read out of the document, created as a
  scene beside the ticket kind, and `check` answers ready. The document
  also names every verb and op the kind declares. Memory storage;
  `check` runs nothing.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [factory10.main :as main]
            [jsonista.core :as json]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.scenes :as scenes]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private doc
  "The document's text, from the suite's directory or the repository's."
  (slurp (first (filter #(.exists ^java.io.File %)
                        [(io/file "../docs/spec-scenes.md")
                         (io/file "docs/spec-scenes.md")]))))

(def ^:private worked-scene
  "The document's one json block, with keyword keys."
  (json/read-value (second (re-find #"(?s)```json\n(.*?)\n```" doc))
                   json/keyword-keys-object-mapper))

(def ^:private mayor (t/principal {:id "mayor" :display "Mayor"}))

(deftest the-worked-scene-checks-ready
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))})
        opts {:principal mayor}
        id (str (:id (:row (inv/create! eng :scene worked-scene opts))))
        row (:row (inv/invoke! eng :scene id :check {}
                               (assoc opts :idempotency-key (str (random-uuid)))))]
    (is (= 12 (count (:shots worked-scene))))
    (is (= "ready" (name (:state row))))))

(deftest the-document-names-every-verb-and-op
  (doseq [word (concat scenes/verbs scenes/ops)]
    (is (str/includes? doc (str "`" word "`")) word)))
