(ns waymark10.access-flow-test
  "The hand-in-hand loop, end to end over the ring handler on a
  memory engine: a human names an agent and mints its link; the
  agent's ONE request binds the invitation and files its ask (scope +
  proposed leash); the human approves (four-eyes held); the agent
  acts under the minted grant; the leash dies on schedule and the
  world 404s again."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.resource :as r]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(r/defresource chore
  {:kind :access_chore
   :initial :open
   :terminal #{:done :dropped}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:min 1 :max 80}]]]
   :flow [[:open :finish :done
           {:one-way "Finishing records reality; nothing external changes."
            :display {:label "Done"}}]
          [:open :drop :dropped
           {:confirm "The chore is dropped for good."
            :display {:label "Drop"}}]]})

(r/defresource errand
  {:kind :access_errand
   :initial :open
   :terminal #{:done}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:min 1 :max 80}]]]
   :flow [[:open :finish :done
           {:one-way "Finishing records reality; nothing external changes."
            :display {:label "Done"}}]]})

(defn- scratch []
  (let [clock (atom (Instant/parse "2026-07-13T08:00:00Z"))
        eng (dev/scratch! [chore errand] {:now-fn (fn [] @clock)})]
    {:clock clock :eng eng :h (dev/handler eng)}))

(defn- req [h method uri {:keys [body headers]}]
  (let [[path query] (clojure.string/split uri #"\?" 2)]
    (h (cond-> {:request-method method :uri path
                :headers (or headers {})}
         query (assoc :query-string query)
         body (assoc :body (wire/write-json body))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(def human {"x-waymark-principal" "colton"})
(def agent-id "sous-1")
(defn- agent-headers [& [grant]]
  (cond-> {"x-waymark-principal" agent-id
           "x-waymark-actor-type" "agent"}
    grant (assoc "x-waymark-grant" grant)))

(deftest the-whole-loop
  (let [{:keys [clock h]} (scratch)
        token "tok-sous-invite"]

    (testing "1 · the human names the agent; the link's document teaches cold"
      (let [created (req h :post "/api/members"
                         {:headers human
                          :body {:display "Sous" :actor_type "agent"
                                 :bind_token token}})]
        (is (= 201 (:status created)) (pr-str (json created))))
      (let [doc (json (req h :get (str "/api/-/welcome?invite=" token) {}))]
        (is (= "Sous" (:welcome doc)))
        (is (= "X-Waymark-Invite" (get-in doc [:bind :header])))
        (is (= "/api/approval_requests" (get-in doc [:ask :href])))
        (is (= 3600 (get-in doc [:ask :ttl :default_seconds])))
        (is (= 86400 (get-in doc [:ask :ttl :max_seconds]))))
      (is (= 404 (:status (req h :get "/api/-/welcome?invite=wrong" {})))))

    (testing "2 · the agent's ONE request binds the invite and files the ask"
      (let [resp (req h :post "/api/approval_requests"
                      {:headers (assoc (agent-headers)
                                       "x-waymark-invite" token)
                       :body {:task "Finish this week's chores."
                              :scope [{:kind "access_chore"
                                       :actions ["create" "finish"]}]
                              :expires_at "2026-07-13T10:00:00Z"}})]
        (is (= 201 (:status resp)) (pr-str (json resp)))
        (is (= "offered" (:state (json resp))))
        (is (= agent-id (get-in (json resp) [:data :requested_by]))))
      ;; the invitation spent itself: the link goes dark
      (is (= 404 (:status (req h :get (str "/api/-/welcome?invite=" token) {}))))
      ;; and the member bound to the agent's principal
      (let [members (json (req h :get "/api/members" {:headers human}))
            sous (first (filter #(re-find #"Sous" (str (:summary %)))
                                (get-in members [:data :items])))]
        (is (some? sous))
        (is (= "active" (:state sous)))))

    (testing "3 · the leash is capped; unstated means the short default"
      (let [over (req h :post "/api/approval_requests"
                      {:headers (agent-headers)
                       :body {:task "A very long errand."
                              :scope [{:kind "access_errand"
                                       :actions ["finish"]}]
                              :expires_at "2026-07-15T08:00:00Z"}})]
        (is (= 409 (:status over)))
        (is (re-find #"at most 24 hours" (str (:detail (json over))))))
      (let [bare (json (req h :post "/api/approval_requests"
                            {:headers (agent-headers)
                             :body {:task "A quick errand."
                                    :scope [{:kind "access_errand"
                                             :actions ["finish"]}]}}))]
        (is (= "2026-07-13T09:00:00Z"
               (str (get-in bare [:data :expires_at]))))))

    (testing "4 · four-eyes holds; the human's approve mints the leash"
      (let [asks (json (req h :get "/api/approval_requests?state=offered"
                            {:headers human}))
            ask (first (filter #(re-find #"chore"
                                         (pr-str %))
                               (map #(json (req h :get (:self %) {:headers human}))
                                    (get-in asks [:data :items]))))
            self (:self ask)]
        (is (some? ask) (pr-str asks))
        (is (= 409 (:status (req h :post (str self "/-/approve")
                                 {:headers (agent-headers)}))))
        (let [approved (req h :post (str self "/-/approve") {:headers human})]
          (is (= 200 (:status approved)) (pr-str (json approved)))
          (let [gid (get-in (json approved) [:data :grant_id])
                grant (json (req h :get (str "/api/grants/" gid)
                                 {:headers human}))]
            (is (some? gid))
            (is (= "accepted" (:state grant)))
            (is (= agent-id (get-in grant [:data :audience])))
            (is (= "2026-07-13T10:00:00Z"
                   (str (get-in grant [:data :expires_at]))))
            ;; the navigable edges: ask → grant, grant → member
            (is (= (str "/api/grants/" gid)
                   (get-in (json (req h :get self {:headers human}))
                           [:links :grant :href])))
            (is (re-find #"/api/members/"
                         (str (get-in grant [:links :member :href]))))))))

    (testing "5 · the agent acts inside the leash; outside is 404"
      (let [asks (json (req h :get "/api/approval_requests" {:headers (agent-headers)}))
            approved (first (filter #(= "approved" (:state %))
                                    (get-in asks [:data :items])))
            gid (get-in (json (req h :get (:self approved)
                                   {:headers (agent-headers)}))
                        [:data :grant_id])]
        (let [made (req h :post "/api/access_chores"
                        {:headers (agent-headers gid)
                         :body {:name "sweep"}})]
          (is (= 201 (:status made)) (pr-str (json made)))
          (let [self (:self (json made))]
            (is (= 200 (:status (req h :post (str self "/-/finish")
                                     {:headers (agent-headers gid)}))))
            ;; drop is outside the granted actions — concealed, 404
            (let [chore2 (json (req h :post "/api/access_chores"
                                    {:headers (agent-headers gid)
                                     :body {:name "mop"}}))]
              (is (= 404 (:status (req h :post (str (:self chore2) "/-/drop")
                                       {:headers (agent-headers gid)})))))))
        ;; the ungranted kind does not exist for the leash
        (is (= 404 (:status (req h :get "/api/access_errands"
                                 {:headers (agent-headers gid)}))))

        (testing "6 · the leash dies on schedule; the world 404s again"
          (reset! clock (Instant/parse "2026-07-13T11:00:00Z"))
          (is (= 404 (:status (req h :post "/api/access_chores"
                                   {:headers (agent-headers gid)
                                    :body {:name "late"}})))))))))
