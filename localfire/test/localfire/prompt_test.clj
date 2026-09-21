(ns localfire.prompt-test
  "R-9.4: the prompt of R-6.2 with and without a text, and the
  `fire.txt` of R-7.1 with the key withheld and every other line kept.

  This is the suite's most literal test on purpose. R-6.2 records that
  the way the fire's text is wrapped is the one thing that would make
  a seat behave differently on the two providers, and R-7.3 records
  that the firing key must not reach the disk. Both are one string
  function, so both are proved with no server standing and no process
  started."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [localfire.prompt :as prompt]))

(deftest routine-prompt-is-the-fixed-one
  (testing "R-6.1: it holds no key and names no seat"
    (is (not (str/includes? prompt/routine-prompt "Key: ")))
    (is (str/starts-with? prompt/routine-prompt
                          "First, run `echo $CLAUDE_CODE_SESSION_ID`."))
    (is (str/ends-with? prompt/routine-prompt
                        "with the numbers it gives, then stop."))
    (is (str/includes? prompt/routine-prompt "Call waymark_sit once with that key"))))

(deftest compose-wraps-the-text-in-the-block
  (testing "R-6.2: the prompt, one blank line, the text inside the block"
    (let [text (str "You sit in the seat `ci-classifier`.\n"
                    "Seat: ci-classifier\n"
                    "Key: sk-abc-123\n"
                    "\n<routine-fire-payload>\nwalk row 7\n</routine-fire-payload>")
          out  (prompt/compose prompt/routine-prompt text)]
      (is (= (str prompt/routine-prompt
                  "\n\n<routine-fire-payload>\n" text "\n</routine-fire-payload>")
             out))
      (testing "the text is carried verbatim and is never cut"
        (is (str/includes? out "Key: sk-abc-123")))))

  (testing "a fire with no text gets the prompt and an empty block"
    (is (= (str prompt/routine-prompt
                "\n\n<routine-fire-payload>\n\n</routine-fire-payload>")
           (prompt/compose prompt/routine-prompt nil)))
    (is (= (prompt/compose prompt/routine-prompt nil)
           (prompt/compose prompt/routine-prompt ""))))

  (testing "a routine's own prompt replaces the fixed one (R-6.1)"
    (is (str/starts-with? (prompt/compose "do the thing" "x") "do the thing\n\n<"))))

(deftest redaction-withholds-the-key-and-nothing-else
  (testing "R-7.1: the Key line's value goes, every other line stays"
    (let [text (str "You sit in the seat `ci-classifier`.\n"
                    "Read the end of the log.\n"
                    "Seat: ci-classifier\n"
                    "Key: sk-live-9f3a-not-in-the-record\n"
                    "<routine-fire-payload>\n"
                    "Keys are mentioned here: not a Key: line prefix.\n"
                    "</routine-fire-payload>")
          out  (prompt/redact-key text)]
      (is (not (str/includes? out "sk-live-9f3a-not-in-the-record")))
      (is (str/includes? out "Key: <withheld>"))
      (is (str/includes? out "Seat: ci-classifier"))
      (is (str/includes? out "You sit in the seat `ci-classifier`."))
      (is (str/includes? out "Read the end of the log."))
      (is (str/includes? out "Keys are mentioned here: not a Key: line prefix."))
      (testing "line for line, only the key line changed"
        (is (= (count (str/split-lines text)) (count (str/split-lines out))))
        (is (= 1 (count (remove true? (map = (str/split-lines text)
                                           (str/split-lines out)))))))))

  (testing "two keys, and a CRLF text, are both handled"
    (is (= "Key: <withheld>\nKey: <withheld>"
           (prompt/redact-key "Key: one\nKey: two")))
    (is (= "Key: <withheld>\r\nSeat: s"
           (prompt/redact-key "Key: one\r\nSeat: s"))))

  (testing "a text with no key is untouched, and nil stays nil"
    (is (= "nothing to hide" (prompt/redact-key "nothing to hide")))
    (is (nil? (prompt/redact-key nil)))))
