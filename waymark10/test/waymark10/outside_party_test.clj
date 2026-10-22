(ns waymark10.outside-party-test
  "Outside parties (docs/spec-teams.md §7): an outside member is born by
  link, its grant holds only :names-me entries and admits the rows that
  name them, the mint refuses any other entry, the link page lists their
  rows and doors, and they allow only a hold addressed to them. Over the
  ring handler on a memory engine."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.declare :refer [defguard hold]]
            [waymark10.dev :as dev]
            [waymark10.resource :as r]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.oidc-rp :as rp]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(defguard the-carrier-reports
  (hold "A parcel is reported lost by its carrier." :by '(data :carrier)))

(defguard a-clerk-files
  (hold "A claim is filed only on a clerk's tap." :for :role "clerk"))

(def ^:private safe {:idempotent true :reversible true :confirm false})

(def ^:private parcel
  (r/resource
   {:kind :parcel
    :plural "parcels"
    :states [:shipped :delivered :lost]
    :initial :shipped
    :terminal #{}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 40}]]
             [:carrier {:kind :member} :waymark/ref]]
    :actions
    {:confirm_delivery {:from #{:shipped} :to :delivered :safety safe
                        :display {:label "Confirm delivery" :order 1}}
     :report_lost {:from #{:shipped} :to :lost :guards [the-carrier-reports]
                   :safety safe :display {:label "Report lost" :order 2}}
     :file_claim {:from #{:shipped} :to :lost :guards [a-clerk-files]
                  :safety safe :display {:label "File claim" :order 3}}
     :reship {:from #{:delivered :lost} :to :shipped :safety safe
              :display {:label "Reship" :order 4}}}}))

(defn- world []
  (let [eng (dev/scratch! [parcel])]
    {:eng eng :h (dev/handler eng)}))

(defn- req [h method uri {:keys [body headers]}]
  (h (cond-> {:request-method method :uri uri :headers (or headers {})}
       body (assoc :body (wire/write-json body)))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- person [id] (t/principal {:id id :display id}))

(defn- member! [eng id]
  (:row (inv/create! eng :member {:display id :actor_type "human"}
                     {:principal members/registrar :id id})))

(defn- outside! [eng id inp]
  (:row (inv/create! eng :member (merge {:display id :actor_type "outside"} inp)
                     {:principal members/registrar :id id})))

(defn- parcel! [eng id carrier]
  (str (:id (:row (inv/create! eng :parcel {:name id :carrier carrier}
                               {:principal members/registrar :id id})))))

(defn- row-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))

(defn- refused [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- refused-by [f]
  (some-> (refused f) :guard name keyword))

(def ^:private carrier-scope
  [{:kind "parcel" :actions ["confirm_delivery"] :names-me ["carrier"]}])

(defn- linked!
  "parcelco, a carrier sent a link with a grant of the rows that name
  them, the grant accepted as their first arrival accepts it.
  → [principal grant-id]"
  [eng]
  (outside! eng "parcelco" {:invite true :grant {:scope carrier-scope}})
  (let [p (t/principal {:id "parcelco" :type :agent :display "parcelco"})
        gid (str (:id (grants/standing-grant-for eng "parcelco")))]
    (inv/invoke! eng :grant gid :accept {} {:principal p})
    [p gid]))

(deftest an-outside-member-is-born-by-link
  (let [{:keys [eng]} (world)
        m (outside! eng "parcelco" {:invite true :grant {:scope carrier-scope}})
        g (grants/standing-grant-for eng "parcelco")]
    (is (= "link" (get-in m [:data :provenance])))
    (is (= "invited" (name (:state m))) "the link is a token, spent on first arrival")
    (testing "the link ends in 30 days when the invite names no end"
      (let [ends (some-> (get-in g [:data :expires_at]) str java.time.Instant/parse)
            now (java.time.Instant/now)]
        (is (some? ends) (pr-str (:data g)))
        (is (.isAfter ends (.plus now (java.time.Duration/ofDays 29))))
        (is (.isBefore ends (.plus now (java.time.Duration/ofDays 31))))))))

(deftest an-outside-grant-admits-only-the-rows-that-name-them
  (let [{:keys [eng h]} (world)
        _ (outside! eng "shipfast" {})
        [p gid] (linked! eng)
        mine (parcel! eng "p1" "parcelco")
        theirs (parcel! eng "p2" "shipfast")
        vis (grants/visibility eng gid p)
        st (:storage eng)
        conds (cond-> (vec ((:conds-of vis) :parcel))
                (some? ((:ids-of vis) :parcel))
                (conj {:target :id :op :in :values ((:ids-of vis) :parcel)}))
        listed (into #{} (map (comp str :id))
                     (store/with-tx st
                       (fn [tx] (store/search-rows st tx :parcel conds {:limit 100}))))]
    (testing "the row judgment and the listing agree: only the row naming them"
      (is ((:row? vis) :parcel mine))
      (is (not ((:row? vis) :parcel theirs)))
      (is (= #{mine} listed)))
    (testing "their doors open on their rows alone"
      (let [confirm (fn [id]
                      (:status (req h :post (str "/api/parcels/" id "/-/confirm_delivery")
                                    {:headers {"x-waymark-principal" "parcelco"
                                               "x-waymark-actor-type" "agent"
                                               "x-waymark-grant" gid}})))]
        (is (= 404 (confirm theirs)))
        (is (= "shipped" (name (:state (row-of eng :parcel theirs)))))
        (is (= 200 (confirm mine)))))
    (testing "the house's own kinds stay shut"
      (is (not ((:kind? vis) :member))))))

(deftest the-mint-refuses-an-outside-entry-that-does-not-name-them
  (let [{:keys [eng]} (world)
        wide [{:kind "parcel" :actions ["confirm_delivery"]}]]
    (outside! eng "shipfast" {})
    (testing "a grant to an outside member"
      (let [e (refused #(inv/create! eng :grant {:audience "shipfast" :scope wide}
                                     {:principal (person "olive") :id "grant-wide"}))]
        (is (= :an-outside-grant-names-them (some-> (:guard e) name keyword)) (pr-str e))
        (is (re-find #"admits rows that name them, and this entry admits every parcel"
                     (pr-str e)))))
    (testing "an outside member's invite"
      (is (= :an-outside-invite-names-them
             (refused-by #(outside! eng "boxco" {:invite true :grant {:scope wide}})))))
    (testing "a names-me field that names no member"
      (is (re-find #"names-me name"
                   (pr-str (refused #(inv/create! eng :grant
                                                  {:audience "shipfast"
                                                   :scope [{:kind "parcel" :actions []
                                                            :names-me ["name"]}]}
                                                  {:principal (person "olive") :id "grant-name"}))))))))

(deftest the-link-page-lists-their-rows-and-doors
  (let [{:keys [eng h]} (world)
        _ (outside! eng "shipfast" {})
        [_ gid] (linked! eng)
        mine (parcel! eng "p1" "parcelco")
        _ (parcel! eng "p2" "shipfast")
        page (req h :get "/api/-/named"
                  {:headers {"x-waymark-principal" "parcelco"
                             "x-waymark-actor-type" "agent"
                             "x-waymark-grant" gid}})
        doc (json page)]
    (is (= 200 (:status page)) (pr-str doc))
    (is (= [{:kind "parcel" :id mine :doors ["confirm_delivery"]}]
           (mapv #(select-keys % [:kind :id :doors]) (:rows doc)))
        (pr-str doc))
    (testing "with no grant it is concealed"
      (is (= 404 (:status (req h :get "/api/-/named" {})))))))

(deftest their-link-lands-on-the-link-page
  (let [{:keys [eng]} (world)
        m (outside! eng "parcelco" {:invite true :grant {:scope carrier-scope}})]
    (is (= "/#/api/-/named" (rp/guest-landing m))
        "an outside party's guest door opens on the rows that name them")
    (is (= "/" (rp/guest-landing (member! eng "olive")))
        "every other guest still lands on the house's home")))

(deftest an-outside-member-allows-only-what-is-addressed-to-them
  (let [{:keys [eng h]} (world)
        _ (doseq [id ["pat" "helper"]] (member! eng id))
        _ (outside! eng "parcelco" {})
        id (parcel! eng "p1" "parcelco")
        g (:row (inv/create! eng :grant
                             {:audience "helper"
                              :scope [{:kind "parcel" :actions ["file_claim"]}]}
                             {:principal (person "pat")}))
        agent (assoc (t/principal {:id "helper" :type :agent :display "helper"})
                     :acts-for "pat")
        _ (inv/invoke! eng :grant (str (:id g)) :accept {} {:principal agent})
        leash {"x-waymark-principal" "helper"
               "x-waymark-actor-type" "agent"
               "x-waymark-acts-for" "pat"
               "x-waymark-grant" (str (:id g))}]
    (testing "a hold addressed to nobody is not theirs to allow"
      (let [asked (req h :post (str "/api/parcels/" id "/-/file_claim") {:headers leash})
            hid (str (:held_call (json asked)))]
        (is (= 202 (:status asked)) (pr-str (json asked)))
        (is (= :an-outside-party-answers-what-names-it
               (refused-by #(inv/invoke! eng :held_call hid :allow {}
                                         {:principal (person "parcelco")}))))
        (is (= "shipped" (name (:state (row-of eng :parcel id)))))))
    (testing "a hold addressed to them by :by is"
      (let [asked (req h :post (str "/api/parcels/" id "/-/report_lost")
                       {:headers {"x-waymark-principal" "pat"}})
            hid (str (:held_call (json asked)))]
        (is (= 202 (:status asked)) (pr-str (json asked)))
        (let [ok (req h :post (str "/api/held_calls/" hid "/-/allow")
                      {:headers {"x-waymark-principal" "parcelco"}})]
          (is (= 200 (:status ok)) (pr-str (json ok))))
        (is (= "lost" (name (:state (row-of eng :parcel id)))))))))
