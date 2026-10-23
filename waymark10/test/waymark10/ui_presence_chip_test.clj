(ns waymark10.ui-presence-chip-test
  "The presence chip names a seat in words, not its key and \"(seat)\".

  A seat's sitter is displayed `<name> (seat)` (seats/sitter-display);
  the chip (ui/220-boot.js, presenceName) reads that as the name
  humanized: \"dress_rehearsal-mayor (seat)\" is \"Dress rehearsal
  mayor is here\". Same two halves as ui_hidden_sse_test: the source
  invariant runs everywhere; the behavioural half runs presenceName
  under node and SKIPS when `node` is not on PATH."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private fragment "waymark10/ui/220-boot.js")

(defn- source [] (slurp (io/resource fragment)))

(deftest the-chip-speaks-presence-name
  (let [src (source)]
    (testing "one function names the principal"
      (is (str/includes? src "function presenceName(p)")))
    (testing "the chip and its title both read it"
      (is (str/includes? src "` ${presenceName(p.principal)} is here`"))
      (is (str/includes? src "`${presenceName(p.principal)} is viewing this `")))
    (testing "the raw display no longer reaches the chip"
      (is (not (str/includes? src "${p.principal.display || p.principal.id} is here"))))))

(defn- humanize-js []
  (let [core (slurp (io/resource "waymark10/ui/100-core.js"))
        from (str/index-of core "const humanize = s =>")
        to (str/index-of core "\n};\n" from)]
    (assert (and from to) "humanize moved")
    (subs core from (+ to 4))))

(defn- presence-name-js []
  (let [src (source)
        from (str/index-of src "function presenceName(p)")
        to (str/index-of src "\n}\n" from)]
    (assert (and from to) "presenceName moved")
    ;; presenceName reads the shared helper, so it rides along
    (str (humanize-js) (subs src from (+ to 3)))))

(def ^:private cases-js "
const seat = (name) => ({id: 'seat:02eee915', type: 'agent', display: name + ' (seat)'});
const out = {
  dress: presenceName(seat('dress_rehearsal-mayor')),
  plain: presenceName(seat('mayor')),
  declared: presenceName({id: 'seat:x', type: 'agent', display: 'The Clerk'}),
  person: presenceName({id: 'colton', display: 'colton'}),
  bare: presenceName({id: 'ada'})
};
console.log(JSON.stringify(out));
")

(defn- node? []
  (try (zero? (:exit (sh "node" "--version")))
       (catch Exception _ false)))

(deftest a-seat-chip-shows-its-humanized-name
  (if-not (node?)
    (println "SKIP a-seat-chip-shows-its-humanized-name: no node on PATH")
    (let [{:keys [exit out err]} (sh "node" "-e" (str (presence-name-js) cases-js))
          got (some->> out str/trim not-empty (re-find #"\{.*\}"))]
      (is (zero? exit) err)
      (testing "a seat reads as words: no key, no underscore, no hyphen, no (seat)"
        (is (str/includes? got "\"dress\":\"Dress rehearsal mayor\""))
        (is (str/includes? got "\"plain\":\"Mayor\""))
        (is (not (re-find #"[_]|\(seat\)|seat:" got))))
      (testing "a seat's own display is read as given"
        (is (str/includes? got "\"declared\":\"The Clerk\"")))
      (testing "a person keeps their display, else their id"
        (is (str/includes? got "\"person\":\"colton\""))
        (is (str/includes? got "\"bare\":\"ada\""))))))
