(ns waymark10.prose-markdown-test
  "A prose field holds markdown (split from 9f5acc5c): every field whose
  x-display widget is `prose` publishes contentMediaType text/markdown
  in its JSON Schema, said once by the framework; a title-like field
  does not; and the value comes back from the API byte-identical to
  what was written.

  Memory storage, the real ring handler, no database."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.server.engine :as engine]
            [waymark10.server.store.memory :as memory]
            [waymark10.wire :as wire]))

(def ^:private note-schema
  [:map
   [:title {:x-display {:label "Title"}} [:string {:min 1 :max 60}]]
   [:summary [:string {:max 120}]]
   [:body {:x-display {:widget "prose" :label "Body"}}
    [:string {:min 1 :max 2000}]]
   [:notes {:optional true :x-display {:widget "prose"}}
    [:maybe [:string {:max 400}]]]])

(def ^:private note
  (r/resource
   {:kind :pm_note
    :plural "pm_notes"
    :states [:open :filed]
    :initial :open
    :terminal #{:filed}
    :summary "{data.title} · {state}"
    :schema note-schema
    :actions
    {:file {:from #{:open} :to :filed
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A filed note is history."}}}}))

(defn- call!
  [eng method uri & {:keys [body]}]
  (let [resp ((engine/handler eng)
              (cond-> {:request-method method :uri uri
                       :headers {"content-type" "application/json"
                                 "x-waymark-principal" "owner"}}
                body (assoc :body (wire/write-json body))))
        text (let [b (:body resp)]
               (cond (nil? b) "" (string? b) b :else (slurp b)))]
    (assoc resp :text text
           :doc (when-not (str/blank? text) (wire/read-json text)))))

(deftest prose-fields-publish-markdown
  (let [props (:properties (schema/json-schema note-schema))]
    (testing "every prose field carries contentMediaType text/markdown"
      (is (= "text/markdown" (get-in props [:body :contentMediaType])))
      (is (= "text/markdown" (get-in props [:notes :contentMediaType]))))
    (testing "a title-like field stays plain"
      (is (not (contains? (:title props) :contentMediaType)))
      (is (not (contains? (:summary props) :contentMediaType))))
    (testing "maxLength still counts raw characters"
      (is (= 2000 (get-in props [:body :maxLength]))))
    (testing "a keyword widget spells the same thing"
      (is (= "text/markdown"
             (get-in (schema/json-schema
                      [:map [:x {:x-display {:widget :prose}} :string]])
                     [:properties :x :contentMediaType]))))))

(deftest prose-is-served-byte-identical
  (let [eng (engine/engine {:storage (memory/storage) :resources [note]})
        body "# Heading\n\n- *one*  \n- `two` <b>x</b> & [link](http://e.x)\n\n> quoted\t\n"
        created (call! eng :post "/api/pm_notes"
                       :body {:title "A note" :summary "plain" :body body})
        _ (is (= 201 (:status created)) (pr-str (:doc created)))
        id (last (str/split (str (get-in created [:doc :self])) #"/"))
        got (call! eng :get (str "/api/pm_notes/" id))]
    (is (= 200 (:status got)) (:text got))
    (is (= body (get-in got [:doc :data :body])))
    (is (str/includes? (:text got) (wire/write-json body)))))
