(ns factory10.vanilla-plus-release-test
  "vanilla_plus gathers approved content and rolls it out as one
  release (ticket 64d7a1d6): over a memory engine with the org alone,
  two approved pieces gathered, planned and applied both reach live
  from one go_live with the release's sha; a piece sent back while
  gathered leaves the release; abandon frees the pieces. No database.

  Run: cd factory10 && clojure -M:test --focus factory10.vanilla-plus-release-test"
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private colton
  (t/principal {:id "colton" :type :human :display "colton"}))

(defn- org-engine []
  (engine/engine {:storage (memory/storage)
                  :resources ((requiring-resolve 'vanilla_plus.org/resources))
                  :suppress-mirror-refresh true}))

(defn- load-row [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))

(defn- state-of [eng kind id] (name (:state (load-row eng kind id))))

(defn- data-of [eng kind id k] (get-in (load-row eng kind id) [:data k]))

(defn- walk!
  "One door, at the row's current version: an edit door is fenced."
  [eng kind id action body]
  (inv/invoke! eng kind (str id) action body
               {:principal colton
                :if-match (inv/etag kind (str id) (:version (load-row eng kind id)))}))

(defn- approved-piece!
  "A content row walked from its idea to approved."
  [eng title live-mode]
  (let [id (str (:id (:row (inv/create! eng :content {:title title} {:principal colton}))))]
    (walk! eng :content id :design {:pitch "A piece." :content_type "quest" :live_mode live-mode})
    (walk! eng :content id :start_build {:build_ticket "t-1"})
    (walk! eng :content id :submit {:content_file (str "content/" title ".sql")})
    (walk! eng :content id :approve {:why "The plan matches the design."})
    id))

(defn- release! [eng]
  (str (:id (:row (inv/create! eng :release {:title "The first wave"} {:principal colton})))))

(deftest two-gathered-pieces-go-live-from-one-go-live
  (let [eng (org-engine)
        a (approved-piece! eng "a" "reload")
        b (approved-piece! eng "b" "restart")
        rel (release! eng)]
    (walk! eng :release rel :gather {:add [a b]})
    (testing "gathering writes each piece's release and the release's live mode"
      (is (= [a b] (data-of eng :release rel :content)))
      (is (= rel (str (data-of eng :content a :release))))
      (is (= "restart" (data-of eng :release rel :live_mode))))
    (walk! eng :release rel :plan {:sha "abc1234def" :plan_summary "Two files."})
    (walk! eng :release rel :applied {})
    (walk! eng :release rel :go_live {})
    (is (= "live" (state-of eng :release rel)))
    (doseq [id [a b]]
      (is (= "live" (state-of eng :content id)))
      (is (= "abc1234def" (data-of eng :content id :released_sha))))))

(deftest a-piece-sent-back-while-gathered-leaves-the-release
  (let [eng (org-engine)
        a (approved-piece! eng "a" "reload")
        b (approved-piece! eng "b" "restart")
        rel (release! eng)]
    (walk! eng :release rel :gather {:add [a b]})
    (walk! eng :content b :send_back {})
    (is (= "building" (state-of eng :content b)))
    (is (nil? (data-of eng :content b :release)))
    (is (= [a] (data-of eng :release rel :content)))
    (is (= "reload" (data-of eng :release rel :live_mode)))))

(deftest abandon-frees-the-pieces
  (let [eng (org-engine)
        a (approved-piece! eng "a" "reload")
        rel (release! eng)]
    (walk! eng :release rel :gather {:add [a]})
    (walk! eng :release rel :abandon {})
    (is (= "abandoned" (state-of eng :release rel)))
    (is (= "approved" (state-of eng :content a)))
    (is (nil? (data-of eng :content a :release)))
    (testing "a freed piece joins another release"
      (let [again (release! eng)]
        (walk! eng :release again :gather {:add [a]})
        (is (= again (str (data-of eng :content a :release))))))))
