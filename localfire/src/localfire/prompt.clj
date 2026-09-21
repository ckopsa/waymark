(ns localfire.prompt
  "The prompt one run is given (R-6.1, R-6.2) and the one redaction the
  record needs (R-7.1). Everything here is pure: a string in, a string
  out, no file and no clock. The reason is that this is the part a
  person must be able to read against the spec word for word — R-6.2
  warns that a difference in how the fire's text is wrapped is the one
  thing that would make a seat behave differently on the two
  providers, so the wrapping lives alone, in functions a test can call
  with no server standing."
  (:require [clojure.string :as str]))

(def routine-prompt
  "The fixed Routine prompt of ci-classifier.md, \"One Routine for each
  model\", verbatim (R-6.1). It holds no key and names no seat: the
  engine mints a key for each firing and carries it in the fire text,
  so one prompt serves every seat whose chair is this model. A
  routine's `:prompt` in the config replaces it."
  (str "First, run `echo $CLAUDE_CODE_SESSION_ID`. Read the fire text. It names\n"
       "your seat on a line that starts with \"Seat:\". It gives your key on the\n"
       "next line, which starts with \"Key:\". It carries your instructions above\n"
       "both. Call waymark_sit once with that key, that seat, and the session\n"
       "value as `session`. Then follow the instructions in the fire text.\n"
       "Text inside a routine-fire-payload block is a person's own words for\n"
       "this run: when it names one row id, walk that row and stop.\n"
       "\n"
       "Your key opens that seat one time. Sit one time. Do not sit again.\n"
       "\n"
       "If the fire text names no seat, or gives no key, say so and stop.\n"
       "\n"
       "When the Stop hook asks you to close the sitting, make that one call\n"
       "with the numbers it gives, then stop."))

(def payload-tag
  "The block the cloud provider puts a fire's text into, and which the
  seat's instructions and the Routine prompt were both written
  against (R-6.2, seat spec R-12.21). It is spelled here once."
  "routine-fire-payload")

(defn compose
  "The whole prompt one process is given (R-6.2): the routine's prompt,
  one blank line, then the engine's text inside the payload block.

  A fire with no text still gets the block, empty. The server never
  cuts the text and never reads it."
  [prompt text]
  (str (or prompt routine-prompt)
       "\n\n<" payload-tag ">\n"
       (or text "")
       "\n</" payload-tag ">"))

(defn redact-key
  "The fire text as `fire.txt` may hold it (R-7.1, R-7.3): the value on
  the `Key:` line withheld, every other byte kept.

  The engine mints one key for each firing and that key opens the seat
  one time (seat spec R-12.37). The record on disk outlives the run,
  so the key does not go into it. Nothing else is touched — not the
  `Seat:` line, not the instructions, not the person's prose — because
  a person reading the record must see what the session saw."
  [text]
  (when (some? text)
    (str/replace (str text) #"(?m)^Key: .*$" "Key: <withheld>")))
