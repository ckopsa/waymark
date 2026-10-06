(ns waymark10.access-dev
  "The access UI-drive engine: the access and seat kinds (member,
  seat, held_call, invitation) and the two ref-labelling fixture kinds
  (ref_target, ref_card) served on a port against
  WAYMARK10_TEST_DSN, booted like waymark10.batch-a-dev. Every table
  drops at boot, and the boot seeds what the drive reads: a member, a
  seat whose sitter acts for that member, and one held seat-restate
  held_call whose owner is the member and whose caller and door author
  are the seat (held_call_test's seat and hold-door! seeds), one
  sealed walk the drive replays, and one three-step walkthrough the
  sitter offers the drive's viewer on a led_note fixture row
  (docs/spec-walkthrough.md § 7 item 5). The RP's
  session doors are composed (no identity provider, require-auth off,
  so the dev headers still speak): the drive signs a guest in through
  /auth/guest and reads the UI with a session cookie alone.

    WAYMARK10_TEST_DSN=jdbc:postgresql://localhost:5433/waymark10_ui_test?user=ckopsa \\
    clojure -Sdeps '{:aliases {:fx {:extra-paths [\"test\"]}}}' -M:fx \\
      -e \"(do ((requiring-resolve 'waymark10.access-dev/start!) 8124) nil) @(promise)\""
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.oidc-rp :as rp]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.server.walks :as walks]
            [waymark10.test.db :as db]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

;; the ref-labelling fixture (ticket f0eb54a6): the two x-ref forms no
;; access kind carries. A ref_card names a ref_target by a plain :kind
;; ref and by typed address; the drive's guest grant admits ref_card
;; and not ref_target, so the same card is also read with its target
;; hidden. The drive creates both rows through the API.
(def ref-target
  (r/resource
   {:kind :ref_target
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.name} · {state}"
    :schema [:map [:name [:string {:min 1 :max 40}]]]
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A finished target is history."}}}}))

(def ref-card
  (r/resource
   {:kind :ref_card
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 40}]]
             [:target_id {:kind :ref_target} :waymark/ref]
             [:about {:x-ref {:address true}} [:string {:max 100}]]
             [:lost {:x-ref {:address true}} [:string {:max 100}]]]
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A finished card is history."}}}}))

;; the walkthrough fixture (docs/spec-walkthrough.md § 7 item 5): the row
;; the seeded steps act on. One door with two plain arguments, so a
;; person step can name two fields.
;;
;; shelve is the quest offer's door (ui-drive.mjs, "Accept as quest"):
;; both of its guards judge the input, so the dialog opens and the POST
;; refuses. The attic refuses with no remedy; the high shelf refuses a
;; note with no room, and rename is its remedy.
;;
;; restate is the path step's door (ui-drive.mjs, "a quest step whose
;; need is a path"): its one argument is a map that holds a map, as a
;; ticket's showcase.evidence.film_url is.
(def ^:private showcase
  [:maybe [:map
           [:evidence {:optional true :x-display {:label "Evidence"}}
            [:maybe [:map
                     [:film_url {:optional true :x-display {:label "The film"}}
                      [:maybe [:string {:max 500}]]]]]]]])

(def ^:private shelf-holds-notes
  (g/expr {:name :shelf-holds-notes
           :when '(not= (input :shelf) "attic")
           :explain "The attic holds no notes."
           :open "No door changes this: the attic holds no notes."}))

(def ^:private high-shelf-filed-by-room
  (g/expr {:name :high-shelf-filed-by-room
           :when '(or (not= (input :shelf) "high") (data :room))
           :explain "The high shelf is filed by room, and this note names no room."
           :remedies [:led_note/rename]}))

(def led-note
  (r/resource
   {:kind :led_note
    :plural "led_notes"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 80}]]
             [:room {:optional true} [:maybe [:string {:max 40}]]]
             [:shelf {:optional true} [:maybe [:enum "low" "high" "attic"]]]
             [:showcase {:optional true} showcase]]
    :actions
    {:rename {:from #{:open} :to :open
              :input [:map
                      [:title [:string {:min 1 :max 80}]]
                      [:room {:optional true} [:maybe [:string {:max 40}]]]]
              :handler (fn [row inp _ctx]
                         (update row :data merge (select-keys inp [:title :room])))
              :safety {:idempotent true :reversible true :confirm false}}
     :shelve {:from #{:open} :to :open
              :input [:map [:shelf [:enum "low" "high" "attic"]]]
              :guards [shelf-holds-notes high-shelf-filed-by-room]
              :handler (fn [row inp _ctx]
                         (assoc-in row [:data :shelf] (:shelf inp)))
              :safety {:idempotent true :reversible true :confirm false}}
     :restate {:from #{:open} :to :open
               :input [:map
                       [:showcase {:optional true :x-display {:label "Showcase"}}
                        showcase]]
               :handler (fn [row inp _ctx]
                          (update row :data merge (select-keys inp [:showcase])))
               :safety {:idempotent true :reversible true :confirm false}}
     :finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A finished note is history."}}}}))

;; the 'not yet' fixture (ticket dfcd5c9a; ui-drive.mjs, "the 'not yet'
;; button"): a led_task ends after its children, as a ticket does. Both
;; of its guards judge the row, so the row page draws both doors shut.
;; complete names the oldest open child's complete as its way out, and
;; takes a close_reason; discard names no way out.
(g/defguard led-children-are-finished
  {:reads [:led_task]
   :evidence [:child_id]
   :remedies [{:door :led_task/complete :id '(evidence :child_id)}]
   :explain "A child of this task is not finished. A parent ends after its children: complete each one first, and then this door opens."}
  [row _inp ctx]
  (if-some [find' (:find ctx)]
    (let [mine (str (:id row))
          child (->> (find' :led_task {} {:limit 500})
                     (filter #(and (= mine (get-in % [:data :parent]))
                                   (= "open" (name (:state %)))))
                     (sort-by (comp str :id))
                     first)]
      (if child
        (t/deny {:evidence {:child_id (str (:id child))}})
        (t/allow)))
    (t/allow)))

(def ^:private desk-keeps-tasks
  (g/expr {:name :desk-keeps-tasks
           :when '(data :loose)
           :explain "The desk keeps every task."
           :open "No door changes this: the desk keeps every task."}))

(def led-task
  (r/resource
   {:kind :led_task
    :plural "led_tasks"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 80}]]
             [:parent {:optional true} [:maybe [:string {:max 60}]]]
             [:loose {:optional true} [:maybe :boolean]]
             [:close_reason {:optional true} [:maybe [:string {:max 480}]]]]
    :actions
    {:complete {:from #{:open} :to :done
                :input [:map [:close_reason [:string {:min 1 :max 480}]]]
                :guards [led-children-are-finished]
                :handler (fn [row inp _ctx]
                           (assoc-in row [:data :close_reason] (:close_reason inp)))
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A completed task is history."}}
     :discard {:from #{:open} :to :done
               :guards [desk-keeps-tasks]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A discarded task is history."}}}}))

(def ^:private viewer
  "The principal the drive's first tab signs in as (ui-drive.mjs,
  accessStory)."
  "priya")

(defn- lead!
  "One open three-step walkthrough by the seat's sitter for the drive's
  viewer, on one led_note: a person step naming two fields, an agent
  step with no action, a person step. → its id string. The author is
  the sitter's principal id with no agent type: an agent's create is
  judged under its grant, and this boot holds none."
  [eng sitter]
  (let [note (:row (inv/create! eng :led_note {:title "Unsorted mail"}
                                {:principal colton}))
        self (str "/api/led_notes/" (:id note))
        led (:row (inv/create!
                   eng :walkthrough
                   {:subject viewer
                    :title "Sorting the desk's mail"
                    :steps [{:who "person" :self self :action "rename"
                             :fields ["title" "room"]
                             :note "Name the pile, then say which room it is in."}
                            {:who "agent" :note "Filing what you named."}
                            {:who "person" :self self :action "rename"
                             :fields ["title"]
                             :note "Give the pile its last name."}]}
                   {:principal (t/principal {:id sitter :display "mail-desk"})}))]
    (str (:id led))))

(defn seed!
  "The member, the seat, its one held restate, one sealed walk of
  three frames by the seat's sitter (a move to the seat, a ui frame
  with the restate dialog open, a transition) for the drive's replay,
  and the sitter's open walkthrough for the drive's viewer (lead!).
  → {:member :seat :held :walk :walkthrough}, each an id string."
  [eng]
  (let [member (:row (inv/create! eng :member
                                  {:display "Jack Tester" :actor_type "human"}
                                  {:principal colton}))
        mid (str (:id member))
        seat (:row (inv/create! eng :seat
                                {:name "mail-desk"
                                 :charter "Answer the household's mail."
                                 :scope [{:kind "capability" :actions []}]
                                 :standing_ttl_seconds 604800
                                 :cadence_seconds 3600
                                 :budget_usd_per_week 5M
                                 :sitting_budget_tokens 1000000}
                                {:principal colton}))
        sid (str (:id seat))
        sitter (str "seat:" sid)
        _ (members/ensure-sitter! eng sitter "mail-desk" mid)
        h (held/hold-door! eng {:kind "seat" :action "restate" :id sid
                                :body {:charter "Answer the household's mail, briefly."}
                                :caller sitter :author sitter :owner mid
                                :why "Restate the desk's charter."})
        walk (:row (inv/create! eng :walk
                                {:followed sitter
                                 :title "Restating the desk's charter"}
                                {:principal colton}))
        wid (str (:id walk))
        self (str "/api/seats/" sid)
        by {:id sitter :type "agent" :display "mail-desk"}
        state (or (some-> (:state seat) name) "open")
        ;; a pause before each frame: the replay has a gap to keep
        frame! (fn [type body]
                 (Thread/sleep 300)
                 (walks/record-frame! eng wid nil {:type type :body body}))]
    (frame! "move" {:principal by :self self :event "move" :source "ui"})
    (frame! "ui" {:principal by :self self :event "ui"
                  :ui {:dialog {:self self :action "restate"}
                       :fields {:charter "Answer the household's mail, briefly."}
                       :collection nil
                       :focus nil}})
    (frame! "transition" {:kind "seat" :self self :action "restate"
                          :from state :to state
                          :at (str (Instant/now))
                          :summary "mail-desk, restated"
                          :actor by})
    (inv/invoke! eng :walk wid :seal {} {:principal colton})
    {:member mid :seat sid :held (str (:id h)) :walk wid
     :walkthrough (lead! eng sitter)}))

(defn start! [port]
  (let [st (pg/storage db/dsn)]
    (store/with-tx st
      (fn [tx]
        (doseq [{t :tablename} (jdbc/execute!
                                tx ["SELECT tablename FROM pg_tables WHERE schemaname = 'public'"]
                                {:builder-fn rs/as-unqualified-lower-maps})]
          (jdbc/execute! tx [(str "DROP TABLE IF EXISTS \"" t "\" CASCADE")]))))
    (let [eng (engine/engine {:storage st
                              :resources [caps/capability ref-target ref-card
                                          led-note led-task]
                              :auto-migrate true
                              ;; a led_task's complete is judged by its
                              ;; children: the row page's probe reads them
                              :probe-reads true
                              :oidc {:issuer "https://idp.test/realms/access-dev"
                                     :audience "access-dev"
                                     :jwks {:keys []}
                                     :rp {:client-id "access-dev"
                                          :client-secret "access-dev"
                                          :app-url (str "http://localhost:" port)
                                          :session-secret "a-32-byte-session-secret-access!"}}})
          ids (seed! eng)]
      ;; start! runs the engine's hooks, so both log consumers drain here:
      ;; the invitations' answers a step and the walkthroughs' opens the next
      (engine/start! eng port {:wrap-handler (rp/wrap-handler eng)})
      (println (str "access engine: http://localhost:" port "/api/-/ui"))
      (println (str "seeded: " (pr-str ids)))
      eng)))
