(ns waymark10.saved-view-test
  "Views as resources (waymark-rla): the extracted view validator
  serves BOTH gates — the declaration-time battery keeps its exact
  refusals (checks_test holds that line), and the saved_view kind
  enforces the same rules at write time against the live registry.
  Store-backed acceptance drives the real ring handler: a POSTed
  saved_view merges into its target collection's envelope views
  (marked source=saved, carrying its own href), retire removes it,
  restore returns it, clone forks it through the same create gate,
  and a redeploy that strands a saved gesture skips the view with a
  warning instead of breaking the page — while the row stays visible
  in the saved_views collection for its owner to fix or retire."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.checks :as checks]
            [waymark10.resource :as r]
            [waymark10.saved-view :as sv]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

;; ── the target kind: collections_test's ticket, plus one honest
;;    one-way door (:discard) for the non-reversible refusal ─────────

(def ticket
  (r/resource
   {:kind :ticket
    :states [:pending :approved :flagged]
    :initial :pending
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 100}]]]
    :filterable {:state #{:eq :in}}
    :flow [[:pending :approve :approved {:undo :unapprove
                                         :display {:label "Approve"}}]
           [:approved :unapprove :pending {:undo :approve}]
           [:pending :flag :flagged {:undo :unflag
                                     :display {:label "Flag"}}]
           [:flagged :unflag :pending {:undo :flag}]
           [:pending :discard :flagged
            {:one-way "Discarding is acknowledged."}]]
    :views [{:name :triage :kind :deck :where {:state "pending"}
             :right :approve :left :flag
             :card [:title] :display {:label "Triage"}}
            {:name :review :kind :feed :where {:state "pending"}
             :display {:label "Review"}}]}))

(def ticket-v2
  "The redeploy: the flag pair is gone (its state grandfathered), the
  declared views with it — what a stored saved_view naming :flag must
  survive being advertised against."
  (r/resource
   {:kind :ticket
    :states [:pending :approved :flagged]
    :initial :pending
    :terminal #{}
    :allow-dead #{:flagged}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 100}]]]
    :filterable {:state #{:eq :in}}
    :flow [[:pending :approve :approved {:undo :unapprove
                                         :display {:label "Approve"}}]
           [:approved :unapprove :pending {:undo :approve}]]}))

;; ── the extracted validator, unit — both call sites read this ──────

(def triage {:name :triage :kind :deck :where {:state "pending"}
             :right :approve :left :flag :card [:title]})

(deftest view-problems-accepts-what-the-declaration-accepts
  (is (= [] (checks/view-problems ticket triage)))
  (is (= [] (checks/view-problems ticket {:kind :feed
                                          :where {:state "pending"}}))))

(deftest view-problems-names-every-declared-rule
  (testing "an unknown view kind"
    (is (some #(str/includes? % ":kind is :deck or :feed")
              (checks/view-problems ticket {:kind :grid}))))
  (testing "a where naming a non-filterable field"
    (is (some #(str/includes? % ":where names :title")
              (checks/view-problems ticket {:kind :feed
                                            :where {:title "x"}}))))
  (testing "a where value that is not a state"
    (is (some #(str/includes? % "is not a state")
              (checks/view-problems ticket (assoc triage :where
                                                  {:state "nonexistent"})))))
  (testing "a card field the schema does not declare"
    (is (some #(str/includes? % ":card names")
              (checks/view-problems ticket (assoc triage
                                                  :card [:title :priority])))))
  (testing "a gesture naming no declared action"
    (is (some #(str/includes? % "not a declared action")
              (checks/view-problems ticket (assoc triage :right :bless)))))
  (testing "a non-reversible gesture"
    (is (some #(str/includes? % "not reversible")
              (checks/view-problems ticket (assoc triage :left :discard)))))
  (testing "a deck that does not drain (gesture lands inside the where)"
    (is (some #(str/includes? % "lands in")
              (checks/view-problems ticket (assoc triage :where
                                                  {:state "pending,flagged"})))))
  (testing "a deck with no where at all"
    (is (some #(str/includes? % "is a :deck with no :where")
              (checks/view-problems ticket (dissoc triage :where)))))
  (testing "a deck whose where skips :state"
    (is (some #(str/includes? % "must constrain :state")
              (checks/view-problems ticket (assoc triage :where
                                                  {:title "x"})))))
  (testing "a feed refuses gestures"
    (is (some #(str/includes? % "takes no :right gesture")
              (checks/view-problems ticket {:kind :feed :right :approve})))))

;; ── the saved_view field fold, unit ─────────────────────────────────

(deftest parse-where-speaks-the-wire-grammar
  (is (= {:state "pending" :owner "ana b"}
         (sv/parse-where "state=pending&owner=ana%20b")))
  (is (nil? (sv/parse-where nil)))
  (is (nil? (sv/parse-where ""))))

(deftest problems-refuse-an-unknown-target
  (let [rdef-of {(keyword "ticket") ticket}]
    (is (some #(str/includes? % "names no kind this engine serves")
              (sv/problems (fn [t] (get rdef-of (keyword (name t))))
                           {:target "nonexistent" :view_kind "feed"})))
    (is (= [] (sv/problems (fn [t] (get rdef-of (keyword (name t))))
                           {:target "ticket" :view_kind "feed"
                            :where "state=pending"})))))

;; ── the store-backed acceptance: the real handler ───────────────────

(def ^:dynamic *h* nil)
(def ^:dynamic *st* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["tickets" "saved_views" "definitions"
                           "waymark10_transitions" "waymark10_idempotency"
                           "waymark10_drafts"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*h* (engine/handler
                       (engine/engine {:storage st
                                       :resources [ticket sv/saved-view]}))
                  *st* st]
          (f))
        (finally (pg/close! st))))))

(defn- req
  ([method uri] (req method uri nil))
  ([method uri body] (req method uri body *h*))
  ([method uri body h] (req method uri body h nil))
  ([method uri body h headers]
   ((or h *h*)
    (cond-> {:request-method method
             :uri uri
             :headers (merge {"x-waymark-principal" "colton"} headers)}
      body (assoc :body (wire/write-json body))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))
(defn- views-of [resp] (:views (json resp)))
(defn- saved-views [resp] (filterv #(= "saved" (:source %)) (views-of resp)))

(deftest saved-views-live-on-the-target-collection
  (let [_ (req :post "/api/tickets" {:title "Fix the door"})
        made (req :post "/api/saved_views"
                  {:label "Pending review" :target "ticket"
                   :view_kind "feed" :where "state=pending"})
        vid (id-of made)]
    (is (= 201 (:status made)) (:body made))

    (testing "the envelope merges the saved view beside the declared ones"
      (let [resp (req :get "/api/tickets")
            vs (views-of resp)]
        (is (= 200 (:status resp)))
        (is (= ["triage" "review" (str "sv-" vid)] (mapv :name vs))
            "declared entries first, saved after")
        (is (= {:name (str "sv-" vid)
                :kind "feed"
                :source "saved"
                :href (str "/api/saved_views/" vid)
                :display {:label "Pending review"}
                :where {:state "pending"}}
               (last vs))
            "the declared entries' exact wire shape, plus the marker and href")))

    (testing "a saved deck rides with gestures wearing the actions' labels"
      (let [deck (req :post "/api/saved_views"
                      {:label "Swipe triage" :target "tickets"
                       :view_kind "deck" :where "state=pending"
                       :card ["title"] :right "approve" :left "flag"})
            did (id-of deck)
            entry (->> (saved-views (req :get "/api/tickets"))
                       (filter #(= (str "sv-" did) (:name %)))
                       first)]
        (is (= 201 (:status deck)) (:body deck))
        (is (= "deck" (:kind entry)) "the plural names the target too")
        (is (= ["title"] (:card entry)))
        (is (= {:right {:action "approve" :label "Approve"}
                :left {:action "flag" :label "Flag"}}
               (:gestures entry)))
        ;; leave the board tidy for the neighbors
        (is (= 200 (:status (req :post (str "/api/saved_views/" did
                                            "/-/retire")))))))

    (testing "revise rewrites the advertised slice through the same gate"
      (let [resp (req :post (str "/api/saved_views/" vid "/-/revise")
                      {:label "Approved history" :target "ticket"
                       :view_kind "feed" :where "state=approved"}
                      *h*
                      ;; an :edit wears the fence — the etag says which
                      ;; version this rewrite read
                      {"if-match" (str "W/\"saved_view-" vid "-v1\"")})
            entry (->> (saved-views (req :get "/api/tickets"))
                       (filter #(= (str "sv-" vid) (:name %)))
                       first)]
        (is (= 200 (:status resp)) (:body resp))
        (is (= {:state "approved"} (:where entry)))
        (is (= {:label "Approved history"} (:display entry)))))

    (testing "clone forks a copy through the create gate"
      (let [resp (req :post (str "/api/saved_views/" vid "/-/clone")
                      nil *h* {"idempotency-key" "clone-once"})
            entries (saved-views (req :get "/api/tickets"))
            copy (->> entries
                      (filter #(= {:label "Approved history (copy)"}
                                  (:display %)))
                      first)]
        (is (= 200 (:status resp)) (:body resp))
        (is (some? copy) "the copy advertises beside its original")
        (is (not= (str "sv-" vid) (:name copy)))
        (is (= 200 (:status (req :post (str (:href copy) "/-/retire")))))))

    (testing "retire takes the view off the envelope; restore returns it"
      (is (= 200 (:status (req :post (str "/api/saved_views/" vid
                                          "/-/retire")))))
      (is (= ["triage" "review"]
             (mapv :name (views-of (req :get "/api/tickets"))))
          "only the declared views remain")
      (is (= 200 (:status (req :post (str "/api/saved_views/" vid
                                          "/-/restore")))))
      (is (some #(= (str "sv-" vid) (:name %))
                (views-of (req :get "/api/tickets"))))
      ;; tidy again
      (is (= 200 (:status (req :post (str "/api/saved_views/" vid
                                          "/-/retire"))))))))

(deftest the-write-gate-refuses-what-the-declaration-would
  (let [refuse (fn [body]
                 (let [resp (req :post "/api/saved_views" body)
                       p (json resp)]
                   (is (= 409 (:status resp)) (:body resp))
                   (:detail p)))]
    (testing "a target this engine does not serve"
      (is (str/includes?
           (refuse {:label "Nowhere" :target "unicorn" :view_kind "feed"})
           "names no kind this engine serves")))
    (testing "a deck gesture with no honest reverse"
      (is (str/includes?
           (refuse {:label "Harsh" :target "ticket" :view_kind "deck"
                    :where "state=pending" :right "approve" :left "discard"})
           "not reversible")))
    (testing "a deck that would never drain"
      (is (str/includes?
           (refuse {:label "Stuck" :target "ticket" :view_kind "deck"
                    :where "state=pending,flagged"
                    :right "approve" :left "flag"})
           "lands in")))
    (testing "a card field the target schema does not declare"
      (is (str/includes?
           (refuse {:label "Ghost column" :target "ticket" :view_kind "feed"
                    :where "state=pending" :card ["title" "priority"]})
           ":card names")))
    (testing "a where the target's filter grammar does not serve"
      (is (str/includes?
           (refuse {:label "Unfilterable" :target "ticket" :view_kind "feed"
                    :where "title=x"})
           ":where names")))
    (testing "a feed carrying gestures"
      (is (str/includes?
           (refuse {:label "Swiping feed" :target "ticket" :view_kind "feed"
                    :where "state=pending" :right "approve"})
           "takes no")))))

(deftest a-redeploy-strands-the-saved-gesture-not-the-page
  ;; authored under ticket v1, where :flag is a declared reversible action
  (let [made (req :post "/api/saved_views"
                  {:label "V1 triage" :target "ticket" :view_kind "deck"
                   :where "state=pending" :right "approve" :left "flag"})
        kept (req :post "/api/saved_views"
                  {:label "V1 review" :target "ticket" :view_kind "feed"
                   :where "state=pending"})
        vid (id-of made)
        kid (id-of kept)
        _ (is (= 201 (:status made)) (:body made))
        _ (is (= 201 (:status kept)) (:body kept))
        ;; the redeploy: same storage, the flag pair gone
        h2 (engine/handler
            (engine/engine {:storage *st*
                            :resources [ticket-v2 sv/saved-view]}))
        resp (req :get "/api/tickets" nil h2)
        vs (views-of resp)]
    (testing "the collection page survives, the stale view silently absent"
      (is (= 200 (:status resp)))
      (is (not-any? #(= (str "sv-" vid) (:name %)) vs)
          "the deck naming the retired :flag is skipped, not served broken")
      (is (some #(= (str "sv-" kid) (:name %)) vs)
          "the still-valid saved feed keeps advertising"))
    (testing "the stranded row stays visible in its own collection"
      (let [col (json (req :get "/api/saved_views" nil h2))]
        (is (some #(str/ends-with? (:self %) vid)
                  (get-in col [:data :items]))
            "its owner can fix or retire it there")))
    ;; tidy for the neighbors
    (req :post (str "/api/saved_views/" vid "/-/retire"))
    (req :post (str "/api/saved_views/" kid "/-/retire"))))
