(ns waymark10.drafts-test
  "Phase-7 acceptance: the draft sub-resource. Obligations: a draft
  round-trips (PUT → GET, base_version = the row's current version,
  prefill = the declared fields' current values), unknown draft
  fields are 422 while requiredness is waived, acting consumes the
  draft, a shared draft is visible to a second principal and a
  private one is not, DELETE discards, and the envelope's :edit
  actions afford their draft."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

;; memo: a PRIVATE draft policy (the fixture meal's update_recipe is
;; the shared one)

(r/defhandler memo-revise [row inp _ctx]
  (assoc-in row [:data :body] (:body inp)))

(r/defhandler memo-rename [row inp _ctx]
  (assoc-in row [:data :title] (:title inp)))

(def memo
  (r/resource
   {:kind :memo
    :states [:open :archived]
    :initial :open
    :terminal #{:archived}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:body {:optional true :x-display {:widget "prose"}}
              [:maybe [:string {:max 4000}]]]]
    :actions
    {:revise {:from #{:open} :to :open
              :input [:map [:body {:x-display {:widget "prose"}}
                            [:string {:max 4000}]]]
              :edit {:prefill [:body] :draft {:shared false}}
              :safety {:idempotent true :reversible true :confirm false}
              :handler memo-revise}
     ;; an :edit with a prefill and NO draft policy — the shape whose
     ;; declared prefill reached nothing but the draft view
     :rename {:from #{:open} :to :open
              :input [:map [:title [:string {:min 1 :max 40}]]]
              :edit {:prefill [:title]}
              :safety {:idempotent true :reversible true :confirm false}
              :handler memo-rename}
     :archive {:from #{:open} :to :archived
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "An archived memo rests."}}}}))

(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["meals" "memos" "definitions"
                           "waymark10_transitions" "waymark10_idempotency"
                           "waymark10_drafts"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*h* (engine/handler
                       (engine/engine {:storage st
                                       :resources [fx/meal memo]}))]
          (f))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(defn- req
  ([method uri] (req method uri nil nil))
  ([method uri body] (req method uri body nil))
  ([method uri body headers]
   (*h* (cond-> {:request-method method
                 :uri uri
                 :headers (merge {"x-waymark-principal" "colton"} headers)}
          body (assoc :body (wire/write-json body))))))

(def as-bob {"x-waymark-principal" "bob"})

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))

(defn- meal-on-list!
  "A meal walked to on_list (where update_recipe affords), → [id env]."
  [name']
  (let [id (id-of (req :post "/api/meals" {:name name' :themes ["bbq"]}))
        env (json (req :post (str "/api/meals/" id "/-/accept")))]
    [id env]))

;; ── 0. the envelope advertises the declared prefill ─────────────────

(deftest edit-actions-advertise-their-prefill
  (let [mid (id-of (req :post "/api/memos" {:title "packing list"}))
        menv (json (req :get (str "/api/memos/" mid)))]
    (testing "an :edit with no draft policy still names its prefill —
              the form has to know what the row already answers"
      (is (= ["title"] (get-in menv [:actions :rename :prefill]))))
    (testing "a drafted :edit names it too, beside the draft advert"
      (is (= ["body"] (get-in menv [:actions :revise :prefill])))
      (is (some? (get-in menv [:actions :revise :draft]))))
    (testing "an action that declares no :edit advertises no prefill"
      (is (nil? (get-in menv [:actions :archive :prefill]))))))

;; ── 1. the envelope affords the draft ───────────────────────────────

(deftest edit-actions-afford-their-draft
  (let [[id env] (meal-on-list! "Brisket")]
    (is (= {:href (str "/api/meals/" id "/-/update_recipe/draft")
            :shared true}
           (get-in env [:actions :update_recipe :draft])))
    (let [mid (id-of (req :post "/api/memos" {:title "packing list"}))
          menv (json (req :get (str "/api/memos/" mid)))]
      (is (= {:href (str "/api/memos/" mid "/-/revise/draft")
              :shared false}
             (get-in menv [:actions :revise :draft])))
      (is (nil? (get-in menv [:actions :archive :draft]))
          "an editless action affords no draft"))))

;; ── 2. the round-trip ───────────────────────────────────────────────

(deftest draft-round-trips
  (let [[id env] (meal-on-list! "Pulled pork")
        uri (str "/api/meals/" id "/-/update_recipe/draft")]
    (testing "no draft yet is 404"
      (is (= 404 (:status (req :get uri)))))
    (testing "PUT saves partial values against the row's current version"
      (let [resp (req :put uri {:recipe "pork shoulder 1800g"})
            b (json resp)]
        (is (= 200 (:status resp)))
        (is (= {:recipe "pork shoulder 1800g"} (:values b)))
        (is (= (get-in env [:meta :version]) (:base_version b))
            "base_version is the row's current version")
        (is (= {} (:prefill b)) "no recipe on the row yet — nothing prefills")))
    (testing "GET answers the stored draft"
      (let [b (json (req :get uri))]
        (is (= {:recipe "pork shoulder 1800g"} (:values b)))
        (is (= 2 (:base_version b)))))
    (testing "an empty partial is a legal draft — requiredness is waived"
      (is (= 200 (:status (req :put uri {})))))
    (testing "unknown fields are 422, field-keyed"
      (let [resp (req :put uri {:recipe "x" :evil 1})
            b (json resp)]
        (is (= 422 (:status resp)))
        (is (= ["disallowed key"] (get-in b [:errors :evil])))))
    (testing "a broken value is refused too"
      (is (= 422 (:status (req :put uri {:recipe 42})))))
    (testing "DELETE discards; discarding again stays 204"
      (is (= 200 (:status (req :put uri {:recipe "keep?"}))))
      (is (= 204 (:status (req :delete uri))))
      (is (= 404 (:status (req :get uri))))
      (is (= 204 (:status (req :delete uri)))))))

;; ── 3. prefill speaks the row's current values ──────────────────────

(deftest prefill-tracks-the-row
  (let [[id env] (meal-on-list! "Ribs")
        self (str "/api/meals/" id)
        uri (str self "/-/update_recipe/draft")
        etag (get-in env [:meta :etag])
        _ (req :post (str self "/-/update_recipe")
               {:recipe "ribs 2000g, Traeger at 225F"}
               {"if-match" etag})
        b (json (req :put uri {:recipe "ribs 2200g, Traeger at 250F"}))]
    (is (= {:recipe "ribs 2000g, Traeger at 225F"} (:prefill b))
        "prefill carries the declared :edit :prefill fields' current values")
    (is (= 3 (:base_version b)) "the edit moved the row before the draft")))

;; ── 4. acting consumes the draft ────────────────────────────────────

(deftest acting-consumes-the-draft
  (let [[id env] (meal-on-list! "Burnt ends")
        self (str "/api/meals/" id)
        uri (str self "/-/update_recipe/draft")]
    (is (= 200 (:status (req :put uri {:recipe "point cut 1500g"}))))
    (is (= 200 (:status (req :get uri))))
    (let [resp (req :post (str self "/-/update_recipe")
                    {:recipe "point cut 1500g"}
                    {"if-match" (get-in env [:meta :etag])})]
      (is (= 200 (:status resp)))
      (is (= "point cut 1500g" (get-in (json resp) [:data :recipe]))))
    (is (= 404 (:status (req :get uri)))
        "the landed effort consumed its draft")))

;; ── 5. audiences: shared is visible, private is not ─────────────────

(deftest draft-audiences
  (testing "a shared draft is one composition surface for everyone"
    (let [[id _] (meal-on-list! "Chuck roast")
          uri (str "/api/meals/" id "/-/update_recipe/draft")]
      (is (= 200 (:status (req :put uri {:recipe "colton's half"}))))
      (let [b (json (req :get uri nil as-bob))]
        (is (= {:recipe "colton's half"} (:values b))
            "the second principal sees the shared draft"))
      (is (= 200 (:status (req :put uri {:recipe "bob's turn"} as-bob))))
      (is (= {:recipe "bob's turn"} (:values (json (req :get uri)))))))
  (testing "a private draft does not exist for anyone else"
    (let [mid (id-of (req :post "/api/memos" {:title "gift ideas"}))
          uri (str "/api/memos/" mid "/-/revise/draft")]
      (is (= 200 (:status (req :put uri {:body "a kayak"}))))
      (is (= 404 (:status (req :get uri nil as-bob))))
      (testing "each principal composes alone"
        (is (= 200 (:status (req :put uri {:body "socks"} as-bob))))
        (is (= {:body "a kayak"} (:values (json (req :get uri)))))
        (is (= {:body "socks"} (:values (json (req :get uri nil as-bob)))))))))

;; ── 6. the surface refuses what does not exist ──────────────────────

(deftest draft-surface-404s
  (let [[id _] (meal-on-list! "Tri-tip")]
    (testing "an undraftable action has no draft route"
      (doseq [action ["accept" "retire" "zap"]]
        (is (= 404 (:status (req :get (str "/api/meals/" id "/-/" action
                                           "/draft")))))))
    (testing "an unknown resource has no drafts"
      (is (= 404 (:status (req :put "/api/meals/nope/-/update_recipe/draft"
                               {:recipe "x"})))))))
