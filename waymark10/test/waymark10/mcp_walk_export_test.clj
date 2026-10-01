(ns waymark10.mcp-walk-export-test
  "`return: \"export\"` on waymark_get (docs/spec-agent-demo-walks.md
  § 4): the agent reads what its walk's file is without anyone pressing
  Export. The answer is the file's size, digest and first lines, and
  never more than the cap; a walk that is not sealed is refused with
  `seal` as the remedy.

  Memory storage, the real routes through the in-process door."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.walks :as walks]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def ^:private chore
  "A kind a frame can be about."
  (r/resource
   {:kind :chore
    :plural "chores"
    :states [:open]
    :initial :open
    :terminal #{:open}
    :summary "{data.title} · {state}"
    :schema [:map [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}}}))

(def ^:private person (t/principal {:id "colton" :display "Colton"}))

(defn- boot []
  (engine/engine {:storage (memory/storage) :resources [chore]}))

(defn- walk!
  "A recording walk of `n` frames → its id."
  [eng n]
  (let [id (str (:id (:row (inv/create! eng :walk
                                        {:followed "planner" :title "Filing a ticket"}
                                        {:principal person}))))]
    (dotimes [_ n]
      (walks/record-frame! eng id nil {:type "move" :body {:self "/api/chores"}}))
    id))

(defn- seal! [eng id]
  (inv/invoke! eng :walk id :seal {} {:principal person}))

(defn- export-of [eng id]
  (mcp/call-tool eng (mcp/door eng) {:principal person} "waymark_get"
                 {:kind "walk" :id id :return "export"}))

(defn- text [out] (get-in out [:content 0 :text]))
(defn- doc [out] (wire/read-json (text out)))

(defn- route-export
  "GET /api/walks/{id}/export at the real door, as the same person."
  [eng id]
  (:body ((engine/handler eng)
          {:request-method :get
           :uri (str "/api/walks/" id "/export")
           :headers {"x-waymark-principal" "colton"}})))

(defn- utf8 ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- sha256 [^String s]
  (apply str (map #(format "%02x" %)
                  (.digest (MessageDigest/getInstance "SHA-256") (utf8 s)))))

(deftest an-export-answers-its-size-digest-and-first-lines
  (let [eng (boot)
        id (walk! eng 3)]
    (seal! eng id)
    (let [out (export-of eng id)
          d (doc out)
          body (route-export eng id)]
      (is (not (:isError out)))
      (is (= "waymark-walk/1" (:format d)))
      (is (= "Filing a ticket" (get-in d [:header :title])))
      (is (= 3 (:frames d)))
      (is (= (alength (utf8 body)) (:bytes d)))
      (is (= (sha256 body) (:sha256 d)))
      (is (= (str "/api/walks/" id "/export") (:href d)))
      (is (= (str/split-lines body) (:lines d))
          "the first line is the header line, as the file has it")
      (is (= (:header d) (wire/read-json (first (:lines d)))))
      (is (false? (:truncated d))))))

(deftest an-export-over-the-cap-is-truncated-and-says-so
  (with-redefs [mcp/export-lines-cap 300]
    (let [eng (boot)
          id (walk! eng 20)]
      (seal! eng id)
      (let [d (doc (export-of eng id))
            body (route-export eng id)
            all (str/split-lines body)]
        (is (true? (:truncated d)))
        (is (seq (:lines d)))
        (is (< (count (:lines d)) (count all)))
        (is (= (:lines d) (vec (take (count (:lines d)) all)))
            "what is carried is the file's first lines, whole")
        (is (<= (alength (utf8 (apply str (map #(str % "\n") (:lines d))))) 300))
        (testing "the size, the count and the digest are still the whole file's"
          (is (= 20 (:frames d)))
          (is (= (alength (utf8 body)) (:bytes d)))
          (is (= (sha256 body) (:sha256 d))))))))

(deftest an-unsealed-walk-is-refused-with-seal-as-the-remedy
  (let [eng (boot)
        id (walk! eng 1)
        out (export-of eng id)
        d (doc out)]
    (is (true? (:isError out)))
    (is (= 409 (:status d)))
    (is (= "recording" (:state d)))
    (is (= ["walk.seal"] (:remedies d)))
    (is (nil? (:lines d)))
    (testing "sealed, the same call answers"
      (seal! eng id)
      (is (not (:isError (export-of eng id)))))
    (testing "a walk nobody recorded is the row's own not-found"
      (let [out (mcp/call-tool eng (mcp/door eng) {:principal person} "waymark_get"
                               {:kind "walk" :id "no-such-walk" :return "export"})]
        (is (true? (:isError out)))
        (is (= 404 (:status (doc out))))))
    (testing "a kind that is not a walk has no export"
      (let [chore-id (str (:id (:row (inv/create! eng :chore {:title "Dishes"}
                                                  {:principal person}))))
            out (mcp/call-tool eng (mcp/door eng) {:principal person} "waymark_get"
                               {:kind "chore" :id chore-id :return "export"})]
        (is (true? (:isError out)))
        (is (= 422 (:status (doc out))))))))

(deftest the-connector-export-is-the-routes-bytes
  (let [eng (boot)
        id (walk! eng 5)]
    (seal! eng id)
    (let [d (doc (export-of eng id))
          carried (apply str (map #(str % "\n") (:lines d)))]
      (is (false? (:truncated d)))
      (is (= (route-export eng id) carried))
      (is (= (walks/export eng id nil) carried))
      (is (= (sha256 carried) (:sha256 d))))))
