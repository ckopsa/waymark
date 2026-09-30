(ns waymark10.patch-test
  "An edit door takes a patch (ticket 5120da15). On a kind whose edit
  declares :prefill [:title :tags], a caller names only what changes:
  an omitted field keeps its value, a list takes {add, remove}, a stale
  version names the field that moved, a field added later takes its
  default, and a held call carries the diff rather than the row.
  Memory storage, real rows through the engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(r/defhandler jot-revise [row inp _ctx]
  ;; wholesale: an omitted optional is cleared
  (update row :data merge {:title (:title inp) :tags (:tags inp) :note (:note inp)}))

(def jot
  (r/resource
   {:kind :jot
    :states [:open :archived]
    :initial :open
    :terminal #{:archived}
    :summary "{data.title} · {state}"
    ;; the retained document is what names a moved field
    :retain {:data true}
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:tags {:optional true} [:maybe [:vector [:string {:max 20}]]]]
             [:note {:optional true} [:maybe [:string {:max 40}]]]]
    :actions
    {:revise {:from #{:open} :to :open
              :input [:map
                      [:title [:string {:min 1 :max 40}]]
                      [:tags {:optional true} [:vector [:string {:max 20}]]]
                      ;; added after the rows were written: never
                      ;; stored, never demanded, its default fills it
                      [:note {:default "none"} [:string {:max 40}]]]
              :edit {:prefill [:title :tags]}
              :safety {:idempotent true :reversible true :confirm false}
              :handler jot-revise}
     :archive {:from #{:open} :to :archived
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "An archived jot rests."}}}}))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(defn- world [] (engine/engine {:storage (memory/storage) :resources [jot]}))

(defn- born! [eng data]
  (str (:id (:row (inv/create! eng :jot data {:principal colton})))))

(defn- current
  "The row's etag as it stands: an edit is fenced, so every revise
  names the version it read."
  [eng id]
  (let [st (:storage eng)]
    (inv/etag :jot id (:version (store/with-tx st #(store/load-row st % :jot id {}))))))

(defn- revise!
  "The row's data after one revise, asked for as a patch."
  ([eng id body] (revise! eng id body {:if-match (current eng id)}))
  ([eng id body opts]
   (:data (:row (inv/invoke! eng :jot id :revise (assoc body :patch true)
                             (merge {:principal colton} opts))))))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest a-patch-names-only-what-changes
  (let [eng (world)
        id (born! eng {:title "first" :tags ["p" "q"]})]
    (testing "a patch naming only title keeps tags"
      (is (= {:title "second" :tags ["p" "q"]}
             (select-keys (revise! eng id {:title "second"}) [:title :tags]))))
    (testing "a field added later with a default is filled, never demanded"
      (is (= "none" (:note (revise! eng id {:title "third"})))))
    (testing "a list delta appends"
      (is (= ["p" "q" "x"] (:tags (revise! eng id {:tags {:add ["x"]}})))))
    (testing "remove runs first, by whole value, then add"
      (is (= ["q" "x" "y"]
             (:tags (revise! eng id {:tags {:remove ["p"] :add ["y"]}})))))
    (testing "a whole input works as ever"
      (is (= {:title "whole" :tags ["z"] :note "n"}
             (select-keys (revise! eng id {:title "whole" :tags ["z"] :note "n"})
                          [:title :tags :note]))))))

(deftest without-patch-the-input-is-whole
  (let [eng (world)
        id (born! eng {:title "first" :tags ["p" "q"]})
        data (:data (:row (inv/invoke! eng :jot id :revise {:title "second"}
                                       {:principal colton
                                        :if-match (current eng id)})))]
    (testing "an omitted optional is cleared, as today"
      (is (= "second" (:title data)))
      (is (nil? (:tags data))))))

(deftest a-remove-of-an-absent-entry-refuses-patch-miss
  (let [eng (world)
        id (born! eng {:title "first" :tags ["p" "q"]})
        e (refusal #(revise! eng id {:tags {:remove ["nope"] :add ["r"]}}))]
    (is (= :patch-miss (:waymark10/problem e)))
    (is (str/includes? (:detail e) "nope"))
    (testing "and nothing moved"
      (is (= ["p" "q"] (:tags (revise! eng id {})))))))

(deftest a-stale-version-names-the-moved-field
  (let [eng (world)
        id (born! eng {:title "first" :tags ["p"]})
        read-at (inv/etag :jot id 1)]
    (revise! eng id {:title "moved"})
    (let [e (refusal #(revise! eng id {:tags {:add ["q"]}} {:if-match read-at}))]
      (is (= :stale (:waymark10/problem e)))
      (is (= ["title"] (:moved e)))
      (is (str/includes? (:detail e) "title")))
    (testing "the bare version number is the same fence"
      (is (= :stale (:waymark10/problem
                     (refusal #(revise! eng id {:tags {:add ["q"]}} {:if-match "1"}))))))
    (testing "the version as it stands writes"
      (is (= ["p" "q"] (:tags (revise! eng id {:tags {:add ["q"]}} {:if-match "2"})))))))

(deftest a-stale-wholesale-write-names-the-moved-field
  ;; ticket 760ae5f8: without `patch` the refusal stays version-conflict
  ;; and its 412, and carries the same moved list stale does
  (let [eng (world)
        id (born! eng {:title "first" :tags ["p"]})
        read-at (inv/etag :jot id 1)]
    (revise! eng id {:title "moved"})
    (let [e (refusal #(inv/invoke! eng :jot id :revise {:title "whole" :tags ["q"]}
                                   {:principal colton :if-match read-at}))]
      (is (= :version-conflict (:waymark10/problem e)))
      (is (= 412 (:status e)))
      (is (= ["title"] (:moved e)))
      (is (str/includes? (:detail e) "title")))))

(deftest the-door-says-omitted-fields-keep-their-values
  (let [eng (world)
        id (born! eng {:title "first" :tags ["p"]})
        h (engine/handler eng)
        env (wire/read-json (:body (h {:request-method :get
                                       :uri (str "/api/jots/" id)
                                       :headers {"x-waymark-principal" "colton"}})))
        input (get-in env [:actions :revise :input])]
    (is (= "boolean" (get-in input [:properties :patch :type])))
    (is (some #{"title"} (map name (:required input)))
        "without patch the input is whole, so what it requires stays required")
    (is (str/includes? (:description input) "keeps its stored value"))
    (is (str/includes? (:description input) "tags"))))

(deftest a-held-patch-carries-its-diff
  (let [eng (world)
        id (born! eng {:title "held" :tags ["a" "b"]})
        hold #(held/hold-door! eng {:kind :jot :action :revise :id id
                                    :body {:patch true :tags {:remove ["a"] :add ["c"]}}
                                    :caller "seat:jotter" :owner "colton"
                                    :why "Swap one tag."
                                    :if-match (inv/etag :jot id 1)})
        res (try {:row (hold)}
                 (catch clojure.lang.ExceptionInfo e {:refused (ex-data e)}))
        _ (is (nil? (:refused res)))
        data (:data (:row res))]
    (testing "the person reads the change, not the row"
      (is (= {:added ["c"] :removed ["a"]}
             (update-keys (get-in data [:changes :tags]) keyword)))
      (is (str/includes? (:shown data) "tags")))
    (testing "the fence reads only the field the patch names"
      (is (= #{"tags"} (set (map name (keys (get-in data [:door :prefill_digests])))))))
    (testing "the call is the patch, and small"
      (is (< (count (wire/write-json data)) 1024)))))
