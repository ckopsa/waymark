(ns waymark10.scheduled-notice-test
  "Telling the person how a scheduled action ended
  (docs/spec-scheduled-actions.md, child 6, R-6): the three declared
  notice rules, `tell`, who is told, and the kept sentence. Memory
  storage, the real engine, a clock a test moves by hand and a fake
  chat server whose log is the witness of what was sent."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.scheduled :as scheduled]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private chore
  "The row a scheduled call acts on."
  (r/resource
   {:kind :told_chore
    :plural "told_chores"
    :states [:open :done]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 1}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 2}}}}))

(def ^:private chat-tools
  [{:name "send_message" :description "Send a chat message."
    :inputSchema {:type "object"
                  :properties {:chat_id {:type "string"}
                               :text {:type "string"}}}}])

(def ^:private t0 (Instant/parse "2026-10-01T12:00:00Z"))

(def ^:private person (t/principal {:id "colton" :display "Colton"}))
(def ^:private stranger (t/principal {:id "iris" :display "Iris"}))
(def ^:private clerk
  "An agent that acts for Colton."
  (assoc (t/principal {:id "told-clerk" :type :agent}) :acts-for "colton"))

(defn- member! [eng id data]
  (:row (inv/create! eng :member
                     (merge {:display (str id) :actor_type "human"} data)
                     {:principal scheduled/engine-actor :id id})))

(defn- drain! [eng]
  (consumers/drain-consumer! eng held/notifier-consumer
                             (held/notifier-consumer-fn eng)))

(defn- world
  "An engine whose clock is the atom's instant, a chat server that
  keeps what it was sent, one notifier, and Colton, who says how he is
  reached. The clerk is an agent that acts for him. The notifier's
  consumer has read the log to here, so a test's drain reads only what
  the test did."
  []
  (let [clock (atom t0)
        log (atom [])
        chat (fn [method params]
               (swap! log conj {:method method :params params})
               (case method
                 "tools/list" {:tools chat-tools}
                 "tools/call" {:content [{:type "text" :text "sent"}]
                               :isError false}))
        eng (engine/engine {:storage (memory/storage)
                            :resources [caps/capability chore]
                            :now-fn (fn [] @clock)
                            :services {:mcp-servers
                                       {:client-fn (fn [row]
                                                     (when (= "tgrambot"
                                                              (get-in row [:data :name]))
                                                       chat))}}})
        _ (inv/create! eng :capability
                       {:token "chat.send"
                        :description "chat.send through a server row."
                        :enforced_by "this engine's own power door"}
                       {:principal person})
        server (:row (inv/create! eng :mcp_server
                                  {:name "tgrambot" :transport "http"
                                   :url "http://fake.invalid/mcp/"
                                   :powers [{:power "chat.send"
                                             :tools ["send_message"]
                                             :approval "person"}]}
                                  {:principal person}))
        notifier (:row (inv/create! eng :notifier
                                    {:name "the house chat"
                                     :server (str (:id server))
                                     :tool "tgrambot__send_message"
                                     :input_template {:chat_id "0"
                                                      :text "{kind} {action}: {summary}"}
                                     :on [{:kind "nothing_here"}]
                                     :audience "colton"
                                     :link_base "https://work.example.org/"}
                                    {:principal person}))
        colton (member! eng "colton" {})]
    (member! eng "told-clerk" {:actor_type "agent" :acts_for "colton"})
    ;; how a member is reached is their own hand: the member sets it
    (inv/invoke! eng :member "colton" :set_notify
                 {:notify {:notifier (str (:id notifier)) :input {:chat_id "42"}}}
                 {:principal person
                  :if-match (inv/etag :member (:id colton) (:version colton))})
    (drain! eng)
    {:eng eng :clock clock :log log :notifier-id (str (:id notifier))}))

(defn- at! [clock s] (reset! clock (Instant/parse s)))

(defn- row-of [eng kind id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) kind)]
    (store/with-tx st
      (fn [tx]
        (some->> (store/load-row st tx kind (str id) {})
                 (inv/decode-row rdef))))))

(defn- state-of [eng id] (some-> (row-of eng :scheduled_action id) :state name))
(defn- data-of [eng id] (:data (row-of eng :scheduled_action id)))
(defn- why-of [eng id] (:outcome_why (data-of eng id)))

(defn- chore! [eng]
  (:id (:row (inv/create! eng :told_chore {:title "Dishes"} {:principal person}))))

(defn- finish! [eng c]
  (inv/invoke! eng :told_chore (str c) :finish {} {:principal stranger}))

(defn- schedule!
  "Schedule `finish` on a new chore for 12:05, five minutes ahead, with
  `extra` laid over the body. → the row's id."
  ([eng extra] (schedule! eng (chore! eng) extra {:principal person}))
  ([eng c extra opts]
   (:id (:row (inv/create! eng :scheduled_action
                           (merge {:target {:kind "told_chore" :action "finish" :id (str c)}
                                   :run_at "2026-10-01T12:05:00Z"}
                                  extra)
                           opts)))))

(defn- worn
  "The clerk at the wire, under a grant a person minted and it accepted."
  [eng]
  (let [gid (str (:id (:row (inv/create! eng :grant
                                         {:audience (:id clerk)
                                          :scope [{:kind "told_chore" :actions ["finish"]}]}
                                         {:principal person}))))]
    (inv/invoke! eng :grant gid :accept {} {:principal clerk})
    {:principal clerk
     :grant {:id gid :action? (fn [_ _] true) :row? (fn [_ _] true)}}))

(defn- sends
  "What the chat server was asked to send, oldest first: each call's
  arguments."
  [log]
  (into [] (comp (filter #(= "tools/call" (:method %)))
                 (map #(get-in % [:params :arguments])))
        @log))

(defn- notifier-data [eng id]
  (:data (store/with-tx (:storage eng)
           #(store/load-row (:storage eng) % :notifier (str id) {}))))

;; ── `tell` (R-6.1) ──────────────────────────────────────────────────

(deftest done-tells-under-all-and-not-under-problems
  (let [{:keys [eng clock log notifier-id]} (world)
        told (schedule! eng {})
        untold (schedule! eng {:tell "problems"})]
    (testing "the birth stamps who each ending is told to"
      (is (= "colton" (:tell_done (data-of eng told))))
      (is (= "colton" (:tell_problem (data-of eng told))))
      (is (nil? (:tell_done (data-of eng untold))))
      (is (= "colton" (:tell_problem (data-of eng untold)))))
    (at! clock "2026-10-01T12:05:20Z")
    (is (= {:ran 2 :late 0 :recovered 0} (scheduled/sweep-due! eng)))
    (is (= "done" (state-of eng told)))
    (is (= "done" (state-of eng untold)))
    (drain! eng)
    (let [s (sends log)
          text (str (:text (first s)))]
      (is (= 1 (count s)) "`all` is told that it ran, `problems` is not")
      (is (= "42" (str (:chat_id (first s)))) "the member says where")
      (is (= "Ran `finish` on told_chore at 12:05, as scheduled." (why-of eng told)))
      (is (str/includes? text (why-of eng told)) "the notice says the kept sentence")
      (is (str/includes? text (str "https://work.example.org/#/api/scheduled_actions/" told)))
      (is (= 1 (:sent (notifier-data eng notifier-id)))
          "a declared rule has no row, so the notifier that carried it counts"))))

(deftest skipped-and-failed-tell-under-problems
  (let [{:keys [eng clock log]} (world)
        c (chore! eng)
        skipped (schedule! eng c {:tell "problems"} {:principal person})
        failed (schedule! eng {:tell "problems"})]
    (finish! eng c)
    (at! clock "2026-10-01T12:05:20Z")
    (testing "a skip: somebody finished the chore first"
      (is (some? (scheduled/start! eng skipped)))
      (is (= :skipped (scheduled/run! eng skipped)))
      (drain! eng)
      (let [s (sends log)]
        (is (= 1 (count s)))
        (is (= "42" (str (:chat_id (first s)))))
        (is (str/includes? (str (:text (first s))) (why-of eng skipped)))))
    (testing "a failure, written by the engine's own hand"
      (is (some? (scheduled/start! eng failed)))
      (inv/invoke! eng :scheduled_action (str failed) :fail
                   {:outcome_why "`finish` on told_chore failed: the store did not answer."}
                   {:principal scheduled/engine-actor})
      (is (= "failed" (state-of eng failed)))
      (drain! eng)
      (let [s (sends log)]
        (is (= 2 (count s)))
        (is (str/includes? (str (:text (second s)))
                           "`finish` on told_chore failed: the store did not answer."))))))

(deftest none-tells-nothing
  (let [{:keys [eng clock log]} (world)
        c (chore! eng)
        ran (schedule! eng {:tell "none"})
        skipped (schedule! eng c {:tell "none"} {:principal person})]
    (is (nil? (:tell_done (data-of eng ran))))
    (is (nil? (:tell_problem (data-of eng ran))))
    (finish! eng c)
    (at! clock "2026-10-01T12:05:20Z")
    (scheduled/sweep-due! eng)
    (is (= "done" (state-of eng ran)))
    (is (= "skipped" (state-of eng skipped)))
    (drain! eng)
    (is (empty? (sends log)))))

(deftest cancelled-never-tells
  (let [{:keys [eng log]} (world)
        ;; the clerk cancels, so it is no rule and not the telling's own
        ;; silence toward whoever moved the row that keeps this quiet
        as-clerk (worn eng)
        id (schedule! eng (chore! eng) {} as-clerk)]
    (is (= "colton" (:tell_problem (data-of eng id))))
    (inv/invoke! eng :scheduled_action (str id) :cancel {} as-clerk)
    (is (= "cancelled" (state-of eng id)))
    (drain! eng)
    (is (empty? (sends log)))))

;; ── who is told (R-6.1) ─────────────────────────────────────────────

(deftest an-agents-row-tells-the-person-it-acts-for
  (let [{:keys [eng clock log]} (world)
        id (schedule! eng (chore! eng) {} (worn eng))]
    (is (= "told-clerk" (:scheduler (data-of eng id))))
    (is (= "colton" (:tell_done (data-of eng id))))
    (at! clock "2026-10-01T12:05:20Z")
    (scheduled/sweep-due! eng)
    (is (= "done" (state-of eng id)))
    (drain! eng)
    (let [s (sends log)]
      (is (= 1 (count s)))
      (is (= "42" (str (:chat_id (first s)))) "Colton's chat, not the clerk's")
      (is (str/includes? (str (:text (first s))) (why-of eng id))))))

;; ── the outcome line (R-6.2) ────────────────────────────────────────

(deftest the-late-sentence-names-both-times-in-the-schedulers-zone
  (let [{:keys [eng clock log]} (world)
        id (schedule! eng {:zone "America/Denver"})]
    (at! clock "2026-10-01T12:47:10Z")
    (is (= {:ran 1 :late 0 :recovered 0} (scheduled/sweep-due! eng)))
    (is (= "Ran `finish` on told_chore at 06:47; it was scheduled for 06:05."
           (why-of eng id)))
    (is (<= (count (why-of eng id)) 240))
    (drain! eng)
    (is (str/includes? (str (:text (first (sends log)))) (why-of eng id))
        "the sentence was written at the run and is what the person reads")))
