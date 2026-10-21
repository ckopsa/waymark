(ns waymark10.role-hold-test
  "A role hold, end to end (ticket c933d44d): an agent's call at a door
  guarded by (hold \"…\" :for :role \"x\") is held by the router, the
  held call names the role as `door.for_role`, a person holding the role
  allows it and the replay runs, and a person without it — the agent's
  own person included — is refused by `an-approver-decides`. Over the
  ring handler on a memory engine."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.declare :refer [defguard hold]]
            [waymark10.dev :as dev]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(defguard a-filer-files
  (hold "A dossier is filed only on a filer's tap." :for :role "filer"))

(def ^:private dossier
  (r/resource
   {:kind :dossier
    :plural "dossiers"
    :states [:draft :filed]
    :initial :draft
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 40}]]]
    :actions
    {:file {:from #{:draft} :to :filed
            :guards [a-filer-files]
            :safety {:idempotent true :reversible true :confirm false}
            :display {:label "File" :order 1}}
     :reopen {:from #{:filed} :to :draft
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 2}}}}))

(defn- world []
  (let [eng (dev/scratch! [dossier])]
    {:eng eng :h (dev/handler eng)}))

(defn- req [h method uri {:keys [body headers]}]
  (h (cond-> {:request-method method :uri uri :headers (or headers {})}
       body (assoc :body (wire/write-json body)))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(def ^:private helper "pat-helper")

(defn- as [id] {:principal (t/principal {:id id :display id})})

(defn- member! [eng id]
  (:row (inv/create! eng :member {:display id :actor_type "human"}
                     {:principal members/registrar :id id})))

(defn- row-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))

(defn- leash!
  "The helper's leash, minted by its person and accepted: it may file
  dossiers. → the headers it presents."
  [eng]
  (let [g (:row (inv/create! eng :grant
                             {:audience helper
                              :scope [{:kind "dossier" :actions ["file"]}]}
                             (as "pat")))
        agent (assoc (t/principal {:id helper :type :agent :display helper})
                     :acts-for "pat")]
    (inv/invoke! eng :grant (str (:id g)) :accept {} {:principal agent})
    {"x-waymark-principal" helper
     "x-waymark-actor-type" "agent"
     "x-waymark-acts-for" "pat"
     "x-waymark-grant" (str (:id g))}))

(defn- refused-by
  "The guard that refused `f`'s write, or nil when it went through."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (some-> (:guard (ex-data e)) name keyword))))

(deftest a-role-hold-is-allowed-only-by-a-holder-of-the-role
  (let [{:keys [eng h]} (world)
        _ (doseq [id ["pat" "sam" "rita"]] (member! eng id))
        id (str (:id (:row (inv/create! eng :dossier {:title "Q3 return"} (as "pat")))))
        asked (req h :post (str "/api/dossiers/" id "/-/file") {:headers (leash! eng)})
        hid (str (:held_call (json asked)))
        allow! #(inv/invoke! eng :held_call hid :allow {} {:principal %})]
    (testing "the agent's call is held, and the held call names the role"
      (is (= 202 (:status asked)) (pr-str (json asked)))
      (is (= "filer" (str (get-in (row-of eng :held_call hid) [:data :door :for_role]))))
      (is (= "draft" (name (:state (row-of eng :dossier id)))) "nothing written yet"))
    (testing "a person without the role is refused by an-approver-decides"
      (is (= :an-approver-decides
             (refused-by #(allow! (t/principal {:id "sam" :display "sam"})))))
      (is (= :an-approver-decides
             (refused-by #(allow! (t/principal {:id "pat" :display "pat"}))))
          "the agent's own person too: the hold waits on the role, not on them")
      (is (= "draft" (name (:state (row-of eng :dossier id))))))
    (testing "a person holding the role allows it, and the replay files the dossier"
      (let [ok (req h :post (str "/api/held_calls/" hid "/-/allow")
                    {:headers {"x-waymark-principal" "rita" "x-waymark-roles" "filer"}})]
        (is (= 200 (:status ok)) (pr-str (json ok))))
      (is (= "filed" (name (:state (row-of eng :dossier id))))
          (let [held (row-of eng :held_call hid)]
            (pr-str [(:state held) (get-in held [:data :reason]) (get-in held [:data :door])]))))))

