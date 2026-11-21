(ns factory10.law-text-names-test
  "Ticket db92fc88: the law page names the people and domains a seat is
  filled with, and tells an effect's landing doors under the effect door,
  not as doors of their own. The fixture is factorykit's
  clinic_integration, a deps root of the :test alias. No database."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [factory10.law-text :refer [law-text]]))

(def ^:private dir "../factorykit/fixtures/orgs/clinic_integration")

(deftest the-mayor's-charter-names-the-owner-and-the-domain
  (let [page (law-text dir)]
    (is (not (re-find #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" page)))
    (is (str/includes? page "You sit with The Owner, the person who owns it."))
    (is (str/includes? page "(only where domain is Clinic)"))
    (is (not (str/includes? page "[:ref")))))

(deftest an-effect's-landing-doors-fold-into-its-door
  (let [page (law-text dir)]
    (is (not (re-find #"\*\*\w+_(landed|failed)\*\*" page)))
    (is (re-find #"- \*\*submit\*\*[^\n]*\n(  - [^\n]*\n)*  - If its call fails, the claim goes back to draft\." page))
    (is (re-find #"- \*\*submit\*\*[^\n]*\n(  - [^\n]*\n)*  - When its call answers, the claim moves on to submitted\." page))))

(deftest a-whole-seat's-charter-names-the-owner
  ;; Ticket a301d66b: a member id typed into a whole seat's own charter
  ;; reads as that member's name; a word that only contains it does not.
  (let [id "0b6c1f0e-3d2a-4c55-9a7e-5f1e2d3c4b5a"
        seed {:cast {:owner {:id id :display "The Owner" :type :human}}
              :seats [{:seat {:name "clerk"
                              :charter (str "Ask " id " first.\nNot x" id ".")}}]}
        seat-of (partial #'factory10.law-text/seat-of (#'factory10.law-text/named-by seed))
        page (str/join "\n" (#'factory10.law-text/seats-section [] (mapv seat-of (:seats seed))))]
    (is (str/includes? page "> Ask The Owner first."))
    (is (str/includes? page (str "> Not x" id ".")))))
