(ns factory10.week-page-test
  "A plan's first week in words (ticket 3f448fe7): a fixture plan's
  dry week, scene and law page yield the page the owner reads.

  Run: cd factory10 && clojure -M:test --focus factory10.week-page-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.week-page :as week-page]))

(def ^:private law
  (str "# The law of Birch Studio\n\n"
       "This is what you approve when you merge.\n\n"
       "## Invoice\n\n"
       "- **send**: an invoice moves from draft to sent.\n"
       "  - Waits for a person's tap: The owner sends every invoice.\n\n"
       "## Doors a person taps\n\n"
       "- invoice **send**: The owner sends every invoice.\n\n"
       "## Seats\n"))

(def ^:private scene
  (pr-str {:scene {:shots []}
           :day_one "2026-10-26"
           :week_script {:day-one "2026-10-26"
                         :days [{:day 1
                                 :calls [{:as :world :kind "invoice" :action "create" :name "birch-2025"
                                          :input {:title "Birch study" :amount_usd 9600 :due_on "2026-10-29"}
                                          :why "The study is billed."}
                                         {:as :mayor :kind "invoice" :action "send" :row "birch-2025"}]}
                                {:day 2
                                 :calls [{:as :person :kind "invoice" :action "send" :row "birch-2025"
                                          :why "The owner sends it."}]}]}}))

(def ^:private dry-week
  [{:day 1 :as "world" :kind "invoice" :action "create" :outcome "moved"}
   {:day 1 :as "mayor" :kind "invoice" :action "send" :outcome "held"}
   {:day 2 :as "person" :kind "invoice" :action "send" :outcome "moved"}])

(def ^:private plan
  {:name "birch_studio" :law_page law :first_week_scene scene :dry_week dry-week})

(deftest a-plan's-week-is-written-in-words
  (let [page (week-page/week-page plan)]
    (is (= (str "# A week at Birch Studio\n\n"
                "Mon Oct 26 – Tue Oct 27\n\n"
                "$9,600 invoiced · 1 tap from you\n\n"
                "## Monday · Oct 26\n\n"
                "- **Outside** Create · Birch 2025 · $9,600 · Oct 29\n"
                "  The study is billed.\n"
                "- **AI** Send · Birch 2025 — *waits on you:* The owner sends every invoice.\n\n"
                "## Tuesday · Oct 27\n\n"
                "- **You** Send · Birch 2025\n"
                "  The owner sends it.\n")
           page))
    (testing "two day headings, the held bullet with its sentence, the tally"
      (is (= 2 (count (re-seq #"(?m)^## " page))))
      (is (str/includes? page "*waits on you:* The owner sends every invoice."))
      (is (str/includes? page "$9,600 invoiced · 1 tap from you")))))

(deftest a-goal-reached-leads-the-tally
  (let [script (pr-str {:week_script {:day-one "2026-10-26"
                                      :goal {:kind "study" :state "delivered"}
                                      :days [{:day 1 :calls [{:as :mayor :kind "study" :action "deliver" :row "birch"}]}]}})
        page (week-page/week-page {:first_week_scene script
                                   :dry_week [{:day 1 :as "mayor" :kind "study" :action "deliver" :outcome "moved"}]})]
    (is (str/includes? page "\n\n1 study delivered\n\n"))
    (is (not (str/includes? page "tap")))))

(def ^:private labels
  (str "{\"prospect\":{\"title\":\"company\",\"fields\":{\"estimated_credit_usd\":\"Estimated credit\"},"
       "\"doors\":{\"qualify\":\"Qualify\",\"records_in\":\"Records complete\"},\"money\":[]},"
       "\"invoice\":{\"title\":\"client\",\"fields\":{\"amount_usd\":\"Amount\",\"due_on\":\"Due\"},"
       "\"doors\":{\"send\":\"Send to client\"},\"money\":[\"amount_usd\"]}}"))

(def ^:private labelled-scene
  (pr-str {:week_script
           {:day-one "2026-10-26"
            :days [{:day 1
                    :calls [{:as :world :kind "prospect" :action "create" :name "acme"
                             :input {:company "Acme Robotics" :estimated_credit_usd 60000}}
                            {:as :mayor :kind "prospect" :action "qualify" :row "acme"
                             :input {:estimated_credit_usd 60000}}
                            {:as :person :kind "prospect" :action "records_in" :row "acme"
                             :input {:records_on "2026-10-26"}}]}
                   {:day 2
                    :calls [{:as :mayor :kind "invoice" :action "create" :name "acme-invoice"
                             :input {:client "acme" :amount_usd 9600 :due_on "2026-10-27" :sent_on "2026-10-27"}}
                            {:as :mayor :kind "invoice" :action "send" :row "acme-invoice"}]}]}}))

(deftest a-plan-with-labels-reads-in-the-org's-words
  (let [page (week-page/week-page
              {:name "birch_studio"
               :week_labels labels
               :first_week_scene labelled-scene
               :dry_week [{:day 1 :as "world" :kind "prospect" :action "create" :outcome "moved"}
                          {:day 1 :as "mayor" :kind "prospect" :action "qualify" :outcome "moved"}
                          {:day 1 :as "person" :kind "prospect" :action "records_in" :outcome "moved"}
                          {:day 2 :as "mayor" :kind "invoice" :action "create" :outcome "moved"}
                          {:day 2 :as "mayor" :kind "invoice" :action "send" :outcome "held"}]})]
    (testing "the qualify line: the door's label, the row's name, one labelled value"
      (is (str/includes? page "- **AI** Qualify · Acme Robotics · estimated credit $60,000\n")))
    (testing "the door's own label, not its humanized key"
      (is (str/includes? page "- **You** Records complete · Acme Robotics\n"))
      (is (str/includes? page "- **AI** Send to client · Acme Robotics's invoice — *waits on you.*"))
      (is (not (str/includes? page "Records in"))))
    (testing "a date that is the day's own is dropped, and no value repeats"
      (is (str/includes? page "- **AI** Create · Acme Robotics's invoice · amount $9,600\n")))
    (testing "only real money in the tally"
      (is (str/includes? page "\n\n$9,600 invoiced · 1 tap from you\n\n"))
      (is (not (str/includes? page "prospected"))))
    (testing "no slug appears"
      (is (not (str/includes? page "acme")))
      (is (not (str/includes? page "Acme Invoice")))
      (is (not (re-find #"Acme(?! Robotics)" page))))))

(deftest no-played-week-is-no-page
  (is (nil? (week-page/week-page {:law_page law})))
  (is (nil? (week-page/week-page {:dry_week []}))))
