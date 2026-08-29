(ns waymark10.access-flow-test
  "The hand-in-hand loop, end to end over the ring handler on a
  memory engine: a human names an agent and mints its link; the
  agent's ONE request binds the invitation and files its ask (scope +
  proposed leash); the human approves (four-eyes held); the agent
  acts under the minted grant; the leash dies on schedule and the
  world 404s again.

  And the loop that keeps it alive (waymark-ycp): the anchored
  extend-ask, whose approval MERGES per kind into the grant it names
  instead of appending to it."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.dsl :as dsl]
            [waymark10.resource :as r]
            [waymark10.server.grants :as grants]
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

;; a :fields kind (generates editor actions no schema endpoint shows)
;; and a hyphenated action name — the joining probe's two vocabulary
;; traps, planted so the wire must teach them
(r/defresource memo
  {:kind :access_memo
   :initial :draft
   :terminal #{:sent}
   :summary "{data.title} · {state}"
   :fields {:at-create [[:title [:string {:min 1 :max 80}]]]
            :while-open [[:body (dsl/prose "Body")]]}
   :flow [[:draft :send :sent
           {:one-way "Sending records it; nothing external changes."
            :display {:label "Send"}}]]})

(r/defresource winch
  {:kind :access_winch
   :states [:slack :tight]
   :initial :slack
   :terminal #{:tight}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:min 1 :max 80}]]]
   :actions {:lock-in {:from #{:slack} :to :tight
                       :safety {:idempotent true :reversible false :confirm false
                                :one-way "Locking in records the set; nothing external changes."}
                       :display {:label "Lock in"}}}})

;; a kind with a FILTERABLE field, so a scope entry may be
;; filter-scoped — the shape the merge bug was found on
;; (feed.preview_as, filtered to one member, appearing twice)
(r/defresource pool
  {:kind :access_pool
   :initial :open
   :terminal #{:closed}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 80}]]
            [:tag [:string {:min 1 :max 40}]]]
   :filterable {:state #{:eq :in} :tag #{:eq}}
   :flow [[:open :close :closed
           {:one-way "Closing records it; nothing external changes."
            :display {:label "Close"}}]]})

(defn- scratch []
  (let [clock (atom (Instant/parse "2026-07-13T08:00:00Z"))
        eng (dev/scratch! [chore errand memo winch pool]
                          {:now-fn (fn [] @clock)})]
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
      ;; state= is NAMED, not assumed: approval_requests opens on the
      ;; asks still waiting (:default-filters), so an unfiltered read
      ;; here would hand back none of the approved ones and the lookup
      ;; below would find nothing. A caller that wants a state says so.
      (let [asks (json (req h :get "/api/approval_requests?state=approved"
                            {:headers (agent-headers)}))
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

(deftest the-wire-teaches-the-vocabulary
  ;; the joining probe's findings 4–6: the exact scope strings —
  ;; hyphens kept, generated editors included, the create verb named —
  ;; live on well-known, no source read
  (let [{:keys [h]} (scratch)
        w (json (req h :get "/api/.well-known/waymark" {:headers human}))
        actions-of #(set (get-in w [:resources % :actions]))]
    (is (contains? (actions-of :access_winch) "lock-in")
        "hyphenated action names keep their exact spelling")
    (is (contains? (actions-of :access_memo) "update_fields")
        "generated field editors are in the vocabulary")
    (is (contains? (actions-of :access_memo) "create"))
    (is (contains? (actions-of :access_chore) "finish"))))

(deftest the-cautious-path-and-the-retry-truth
  ;; the joining probe's finding 3: binding spends the invite, not the
  ;; ask — both escape hatches the doc now teaches actually work
  (let [{:keys [h]} (scratch)
        token "tok-scout-invite"]
    (is (= 201 (:status (req h :post "/api/members"
                             {:headers human
                              :body {:display "Scout" :actor_type "agent"
                                     :bind_token token}}))))
    (let [doc (json (req h :get (str "/api/-/welcome?invite=" token) {}))]
      (is (string? (get-in doc [:bind :if_it_goes_wrong])))
      (is (string? (get-in doc [:bind :cautious_path])))
      (is (= "/api/.well-known/waymark" (get-in doc [:ask :vocabulary :href])))
      ;; presence taught where joining is taught: how to be seen
      (is (= "/api/-/presence" (get-in doc [:presence :href])))
      (is (re-find #"following you" (str (get-in doc [:presence :note])))))
    (testing "bind rides a harmless read; the ask follows bare"
      (is (= 200 (:status (req h :get "/api/.well-known/waymark"
                               {:headers (assoc (agent-headers)
                                                "x-waymark-principal" "scout-1"
                                                "x-waymark-invite" token)}))))
      (is (= 404 (:status (req h :get (str "/api/-/welcome?invite=" token) {})))
          "the read spent the invite")
      (let [ask (req h :post "/api/approval_requests"
                     {:headers {"x-waymark-principal" "scout-1"
                                "x-waymark-actor-type" "agent"}
                      :body {:task "Watch the errands, touch nothing."
                             :scope [{:kind "access_errand" :actions []}]}})]
        (is (= 201 (:status ask)) (pr-str (json ask)))
        (testing "an empty :actions grants read-only sight"
          (let [self (:self (json ask))
                _ (req h :post (str self "/-/approve") {:headers human})
                gid (get-in (json (req h :get self {:headers human}))
                            [:data :grant_id])
                gh {"x-waymark-principal" "scout-1"
                    "x-waymark-grant" gid}]
            (is (= 200 (:status (req h :get "/api/access_errands"
                                     {:headers gh}))))
            (is (= 404 (:status (req h :post "/api/access_errands"
                                     {:headers gh
                                      :body {:name "nope"}}))))))))))

;; ── the renewal loop that used to eat itself (waymark-ycp) ──────────

(deftest merge-scope-folds-one-entry-per-kind
  ;; the rule, on data: what an approval writes into the grant.
  (testing "actions and ids union; the kind appears once"
    (is (= [{:kind "task" :actions ["claim" "complete"] :ids ["a" "b"]}]
           (grants/merge-scope [{:kind "task" :actions ["claim"] :ids ["a"]}]
                               [{:kind "task" :actions ["complete"] :ids ["b"]}]))))
  (testing "openness absorbs on ids: an entry naming none is the whole
            kind, and the whole kind swallows a sibling's list"
    (is (= [{:kind "task" :actions ["claim"]}]
           (grants/merge-scope [{:kind "task" :actions ["claim"] :ids ["a"]}]
                               [{:kind "task" :actions ["claim"]}]))))
  (testing "a filtered capability stays SINGLE — a second entry is the
            refusal that started this bead"
    (is (= [{:kind "feed.preview_as" :actions [] :filter {:member "colton"}}]
           (grants/merge-scope [{:kind "feed.preview_as" :actions []
                                 :filter {:member "colton"}}]
                               [{:kind "feed.preview_as" :actions []
                                 :filter {:member "colton"}}]))))
  (testing "silence about a narrowing INHERITS it; an explicit null clears it"
    (is (= {:member "colton"}
           (:filter (first (grants/merge-scope
                            [{:kind "feed.preview_as" :actions []
                              :filter {:member "colton"}}]
                            [{:kind "feed.preview_as" :actions ["preview"]}])))))
    (is (nil? (:filter (first (grants/merge-scope
                               [{:kind "feed.preview_as" :actions []
                                 :filter {:member "colton"}}]
                               [{:kind "feed.preview_as" :actions []
                                 :filter nil}]))))))
  (testing "hashing dominates: a tokenised field is never absorbed"
    (is (= ["email"]
           (:hashed (first (grants/merge-scope
                            [{:kind "member" :actions [] :hashed ["email"]}]
                            [{:kind "member" :actions []}]))))))
  (testing "the heal: twenty kinds spelled seventy-four times collapse"
    (let [fat (mapv (fn [i] {:kind (str "k" (mod i 20)) :actions ["read"]})
                    (range 74))]
      (is (= 20 (count (grants/merge-scope fat))))
      (is (= 20 (count (grants/merge-scope fat fat))))))
  (testing "kinds keep the order a person built them in"
    (is (= ["b" "a"] (mapv :kind (grants/merge-scope
                                  [{:kind "b" :actions []}
                                   {:kind "a" :actions []}
                                   {:kind "b" :actions ["x"]}]))))))

(deftest the-anchored-extension-never-appends
  ;; THE FIELD BUG. The standing agent's driver copies its grant's
  ;; scope into an anchored ask and asks for more time. While the
  ;; approval APPENDED, the stored scope doubled on every renewal
  ;; until a filter-scoped kind appeared twice — and then the door
  ;; refused the next ask ("only ONE entry may filter a kind"), so no
  ;; ask could stand and the leash lapsed with nobody asking.
  (let [{:keys [h]} (scratch)
        ask! (fn [body] (req h :post "/api/approval_requests"
                             {:headers (agent-headers) :body body}))
        approve! (fn [resp]
                   (let [self (:self (json resp))]
                     (is (= 201 (:status resp)) (pr-str (json resp)))
                     (is (= 200 (:status (req h :post (str self "/-/approve")
                                              {:headers human}))))
                     (get-in (json (req h :get self {:headers human}))
                             [:data :grant_id])))
        scope-of (fn [gid] (get-in (json (req h :get (str "/api/grants/" gid)
                                              {:headers human}))
                                   [:data :scope]))
        entry (fn [gid k] (first (filter #(= k (:kind %)) (scope-of gid))))
        gid (approve!
             (ask! {:task "Watch the chores and the kitchen pool."
                    :scope [{:kind "access_chore" :actions ["finish"] :ids ["c1"]}
                            {:kind "access_pool" :actions []
                             :filter {:tag "kitchen"}}]}))]

    (testing "the bootstrap ask mints the grant with its scope as asked"
      (is (some? gid))
      (is (= 2 (count (scope-of gid)))))

    (testing "the anchored extension merges: one entry per kind, actions
              and ids unioned, the filtered kind still single"
      (approve! (ask! {:grant_id gid
                       :task "A little more of the same."
                       :scope [{:kind "access_chore" :actions ["drop"] :ids ["c2"]}
                               {:kind "access_pool" :actions []
                                :filter {:tag "kitchen"}}]}))
      (is (= 2 (count (scope-of gid))))
      (let [e (entry gid "access_chore")]
        (is (= #{"finish" "drop"} (set (:actions e))))
        (is (= #{"c1" "c2"} (set (:ids e)))))
      (is (= {:tag "kitchen"} (:filter (entry gid "access_pool")))))

    (testing "the driver's own loop — copy the grant's scope, ask for
              more time — is accepted every time, and the scope stands still"
      (dotimes [_ 3]
        (approve! (ask! {:grant_id gid
                         :task "Keep my standing leash: the same scope, another day."
                         :scope (scope-of gid)
                         :expires_at "2026-07-14T07:00:00Z"})))
      (is (= 2 (count (scope-of gid)))
          "three renewals later, still one entry per kind")
      (is (= "2026-07-14T07:00:00Z"
             (str (get-in (json (req h :get (str "/api/grants/" gid)
                                     {:headers human}))
                          [:data :expires_at])))
          "and the time is what the extension came for"))

    (testing "a silent ask does not drop the filter the grant already carries"
      (approve! (ask! {:grant_id gid
                       :task "Close pools too."
                       :scope [{:kind "access_pool" :actions ["close"]}]}))
      (let [e (entry gid "access_pool")]
        (is (= ["close"] (:actions e)))
        (is (= {:tag "kitchen"} (:filter e)))))

    (testing "the door that started it all still refuses a kind
              filter-scoped twice — the merge is what keeps an ask from
              ever spelling one"
      (let [resp (ask! {:grant_id gid
                        :task "Two filters on one kind."
                        :scope [{:kind "access_pool" :actions []
                                 :filter {:tag "kitchen"}}
                                {:kind "access_pool" :actions []
                                 :filter {:tag "garage"}}]})]
        (is (= 409 (:status resp)))
        (is (re-find #"cannot be filter-scoped" (str (:detail (json resp)))))))))
