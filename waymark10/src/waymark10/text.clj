(ns waymark10.text
  "Plain text out of a message, and the cap that keeps it small
  (bead waymark-fp62.7.16).

  The engine reads mail for the model. One power call answered 179 KB
  of HTML for three messages, which was 80 percent of what the model
  read in that sitting, and each turn after it read the same bytes
  again. The model needs the sender, the subject, the date and the
  first part of the text to decide. This namespace makes that first
  part.

  TWO CALLERS, ONE FUNCTION. The research door of `inbox_item` writes
  an excerpt on the row, and `waymark_power` shapes a payload when the
  caller asks for `text_only`. Two spellings of one extraction would
  become two different excerpts of one message, so the rule is here
  and both callers read it.

  THE RULE:

  1. Take a `text/plain` part when the answer has one.
  2. If there is no plain part, take a plain-text field, then an
     HTML field, then the text the value itself carries.
  3. Remove the script blocks and the style blocks. Remove the
     comments. Remove the tags.
  4. Decode the common entities.
  5. Make each run of spaces, tabs and new lines one space.
  6. Cut the text at the cap, and count the characters you cut.

  NO NEW DEPENDENCY. A parser for HTML would read this better than a
  regular expression does, and it would also be a library this
  repository does not have. The rule above is small, it is honest
  about what it does, and an excerpt that keeps a stray angle bracket
  costs a reader nothing."
  (:require [clojure.string :as str]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(def default-max-chars
  "How many characters of one message the engine keeps. Four thousand
  is approximately one thousand tokens: enough for the sender's ask,
  and small enough that each turn after it pays little to read it
  again. The seat can carry its own cap later."
  4000)

;; ── entities ────────────────────────────────────────────────────────

(def ^:private named-entities
  "The entities mail actually carries. A name that is not here stays
  as it was written: an excerpt with `&copy;` in it is readable, and
  a table of every HTML entity is a table nobody maintains."
  {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'" "nbsp" " "
   "ndash" "-" "mdash" "-" "hellip" "..." "middot" "-" "bull" "-"
   "lsquo" "'" "rsquo" "'" "ldquo" "\"" "rdquo" "\"" "laquo" "\""
   "raquo" "\"" "copy" "(c)" "reg" "(r)" "trade" "(tm)" "deg" " deg"
   "euro" "EUR" "pound" "GBP" "cent" "c" "times" "x" "shy" ""})

(defn- code-point
  "One numeric entity as the character it names, or nothing when the
  number names no character."
  [^long n]
  (if (and (pos? n) (<= n 0x10FFFF))
    (String. (Character/toChars (int n)))
    ""))

(defn decode-entities
  "The common entities as the characters they stand for: the named
  ones above, and the numeric ones in decimal and in hexadecimal."
  [s]
  (str/replace
   (str s)
   #"&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,9});"
   (fn [[whole body]]
     (try
       (cond
         (str/starts-with? (str/lower-case body) "#x")
         (code-point (Long/parseLong (subs body 2) 16))

         (str/starts-with? body "#")
         (code-point (Long/parseLong (subs body 1)))

         :else (get named-entities (str/lower-case body) whole))
       (catch Exception _ whole)))))

;; ── the extraction ──────────────────────────────────────────────────

(defn plain-text
  "One string of HTML, or of plain text, as the words in it. Steps 3
  to 5 of the rule: the script blocks and the style blocks go first,
  because what is inside them is code and not words; then the
  comments; then the tags. The entities are decoded AFTER the tags
  are gone, so an escaped `&lt;b&gt;` in the body is not read as a
  tag. A string that carries no markup comes back with its spaces
  made even and nothing else changed."
  [v]
  (-> (str v)
      (str/replace #"(?is)<(script|style)\b[^>]*>.*?(?:</\1\s*>|\z)" " ")
      (str/replace #"(?s)<!--.*?-->" " ")
      (str/replace #"(?s)<[^>]*>" " ")
      decode-entities
      (str/replace #"\s+" " ")
      str/trim))

(defn cap
  "The string, no longer than `max-chars` → {:text … :cut n}. `:cut`
  is how many characters the cap removed, and it is 0 when the cap
  removed none. The count is said out loud because a reader must know
  that there is more of this message than the row holds."
  ([s] (cap s default-max-chars))
  ([s max-chars]
   (let [s (str s)
         n (long (or max-chars default-max-chars))]
     (if (and (pos? n) (> (count s) n))
       {:text (subs s 0 n) :cut (- (count s) n)}
       {:text s :cut 0}))))

(def ^:private plain-keys
  "Where a rig puts the plain half of a message."
  [:text_plain :plain_text :body_plain :plain_body :body_text
   :text_body :plain :snippet])

(def ^:private html-keys
  "…and where it puts the HTML half."
  [:body_html :html_body :html])

(def ^:private body-keys
  "The keys that carry a body of either kind, and the MCP content
  part's own `:text`. They are read last, because a rig that spells
  the two halves apart says more than a rig that does not."
  [:body :text :content :message :value])

(defn- as-text [v]
  (when (string? v) (not-empty (str/trim v))))

(defn- mime
  "The media type this map declares, in lower case, or an empty
  string. The MCP `:type` of a content part is deliberately not read
  here: it says `text`, which is not `text/plain`."
  [m]
  (str/lower-case (str (or (:mimeType m) (:mime_type m) (:contentType m)
                           (:content_type m) (:mime m) ""))))

(defn- maybe-json
  "A string that carries a JSON object or a JSON array, as the value
  it carries — a rig answers its message document as the text of one
  MCP part, and the words are inside that document. Anything else
  reads as nothing, and the string stays a string to its caller."
  [s]
  (let [t (str/triml (str s))]
    (when (or (str/starts-with? t "{") (str/starts-with? t "["))
      (try (let [v (wire/read-json t)] (when (coll? v) v))
           (catch Exception _ nil)))))

(defn- deeper [v depth f]
  (when (pos? depth)
    (some #(f % (dec depth)) v)))

(defn- plain-part
  "Step 1: the text of a `text/plain` part, wherever the rig keeps
  its parts."
  [v depth]
  (cond
    (sequential? v) (deeper v depth plain-part)

    (map? v)
    (or (when (str/starts-with? (mime v) "text/plain")
          (some #(as-text (get v %)) (concat plain-keys body-keys [:data])))
        (deeper (filter coll? (vals v)) depth plain-part))

    :else nil))

(defn- any-text
  "Step 2: a plain-text field, then an HTML field, then the text the
  value itself carries. A part whose text is a JSON document is read
  as that document, because that is where the rig put the words."
  [v depth]
  (cond
    (string? v) (or (when (pos? depth)
                      (some-> (maybe-json v) (any-text (dec depth))))
                    (as-text v))

    (sequential? v) (deeper v depth any-text)

    (map? v)
    (or (some #(as-text (get v %)) plain-keys)
        (some #(as-text (get v %)) html-keys)
        (some (fn [k] (let [s (as-text (get v k))]
                        (when s
                          (or (when (pos? depth)
                                (some-> (maybe-json s) (any-text (dec depth))))
                              s))))
              body-keys)
        (deeper (filter coll? (vals v)) depth any-text))

    :else nil))

(def ^:private max-depth
  "How far into an answer this namespace reads. A message is two or
  three maps deep; a bound keeps a strange answer from costing a
  stack."
  8)

(defn message-text
  "Gate's answer (or any part of it) → the text it carries, or nil.
  Steps 1 and 2 of the rule. The value is what the MCP client already
  parsed: a map, a list, or a string."
  [v]
  (or (plain-part v max-depth) (any-text v max-depth)))

(defn excerpt
  "The whole rule: Gate's answer → {:text … :cut n}, the plain words
  of the message capped at `max-chars`. An answer that carries no
  text at all reads as {:text \"\" :cut 0}, which the caller is free
  to write nothing for."
  ([v] (excerpt v default-max-chars))
  ([v max-chars]
   (cap (plain-text (or (message-text v) "")) max-chars)))
