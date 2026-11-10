(ns waymark10.server.mcp.gate
  "The MCP-facing half of the two power tools, carved out of
  waymark10.server.mcp (ticket 5ef47ef7): the `waymark_powers` and
  `waymark_power` tool definitions, the shape a caller may ask a power
  answer for (`text_only`, `max_chars`), the bytes a shape or a rig
  dropped, and which power answer counts as a refusal.

  The forwarding itself is gate-proxy's; the dispatch, the office stamp
  and the sitting's counters stay in mcp, which reads these vars.

  The metadata keys stay spelled in mcp's namespace
  (`:waymark10.server.mcp/dropped`, `:waymark10.server.mcp/status`), so
  a result shaped here and counted there reads the same key it did
  before the carve."
  (:require [waymark10.text :as text])
  (:import (java.nio.charset StandardCharsets)))

(def powers-tool
  {:name "waymark_powers"
   :title "The external powers your grant admits"
   :description
   (str "External powers reached THROUGH this engine — the live servers' "
        "tools intersected with your grant, read from each server's row "
        "on every call. Reads under `links`, mutations under `actions`; each "
        "entry names its capability token, its description and its input "
        "schema (the server's own, with a required `why` spelled out). Invoke "
        "one with waymark_power. Before any power is granted this "
        "document is empty and carries the ask door; the moment an ask "
        "naming a dotted token (messages.read, email.read …) is "
        "approved, the same call fills in — no reconnect, no tool list "
        "change. waymark_discover's doors.ask.powers lists the tokens.")
   :input-schema {:type "object" :properties {} :additionalProperties false}})

(def power-tool
  {:name "waymark_power"
   :title "Use one external power"
   :description
   (str "Invoke one of the tools waymark_powers lists, by name, with the "
        "arguments its input schema names. The grant is judged here "
        "before any wire is touched: an ungranted tool refuses naming "
        "the exact scope entry to ask for, and a tool outside this "
        "engine's policy does not exist. A granted call forwards to the "
        "server and answers its result VERBATIM — its content, its isError, "
        "its own approval refusals. Mutations carry a `why`: one "
        "sentence the human who approves the action reads. "
        "ASK FOR A SMALLER ANSWER when you only need the words: "
        "`text_only` gives you the plain text of each part, with the "
        "tags, the scripts and the styles removed, and `max_chars` "
        "cuts each part at that many characters. One mail answer of "
        "179 KB was 80 percent of what a whole sitting read, and each "
        "turn after it read the same bytes again. Without these two "
        "the payload passes through byte for byte, because this "
        "engine never rewrites an answer you did not ask it to.")
   :input-schema
   {:type "object"
    :properties
    {:tool {:type "string"
            :description "A tool name from waymark_powers (links or actions)."}
     :arguments {:type "object"
                 :description "The tool's arguments, per its input schema in waymark_powers."
                 :additionalProperties true}
     :text_only {:type "boolean"
                 :description
                 (str "True gives you the plain words of each text part: a "
                      "text/plain part when the answer carries one, and "
                      "otherwise the HTML with the scripts, the styles and "
                      "the tags removed and the spaces made even. Leave it "
                      "out for the payload as the server sent it.")}
     :max_chars {:type "integer"
                 :minimum 1
                 :description
                 (str "Cut each text part at this many characters. Say 4000 "
                      "for a mail message: it is enough to decide with, and "
                      "you can ask again for the whole of the one message "
                      "that needs it.")}
     :at {:type "string"
          :description
          (str "When to make this call instead of now: RFC 3339 with an "
               "offset (2026-10-02T08:30:00-06:00), or a local date and "
               "time (2026-10-02T08:30) read in `zone`. The call is not "
               "made: it is stored as a scheduled_action, and the answer "
               "is {scheduled, scheduled_action, run_at}. A tool that "
               "waits on a person is approved now, for that time.")}
     :zone {:type "string"
            :description
            (str "With at: the IANA zone a local time is written in and "
                 "shown in, e.g. America/Denver.")}
     :validity {:type "string" :enum ["strict" "state"]
                :description
                (str "With at: what must still hold at the time. state "
                     "(default): the tool is still among your powers and "
                     "its server is serving. strict: and the server's row "
                     "has not changed since.")}
     :grace_seconds {:type "integer" :minimum 0 :maximum 86400
                     :description
                     (str "With at: how late the run may be when the engine "
                          "was down at its time. An hour unless said.")}}
    :required ["tool"]
    :additionalProperties false}})

(defn result-bytes
  "How many bytes of text this tool result carries: the UTF-8 length
  of every `:text` part under `:content`, added up. It is what the
  model READS, so a refusal's sentence counts exactly as an
  allowance's document does. A part that carries no text — an image,
  a resource link — adds nothing here, because this counter only
  claims to speak for text."
  [result]
  (reduce (fn [n part]
            (if-some [t (:text part)]
              (+ (long n)
                 (alength (.getBytes ^String (str t) StandardCharsets/UTF_8)))
              (long n)))
          0
          (:content result)))

;; ── the shape a caller may ask a power for (waymark-fp62.7.16) ──────

(def ^:private dropped-key
  "Where a shaped answer records what the shape removed. It is
  METADATA on the result, not a field in it: the payload that goes to
  the client is Gate's own shape, and the counter in mcp is the only
  reader. Spelled in mcp's namespace, where it was born."
  :waymark10.server.mcp/dropped)

(defn- shape-part
  "One `:text` part, in the shape the caller asked for: the plain
  words when `plain?`, then the cap when the caller named one. Each
  step is skipped when it was not asked for. A part that carries no
  text — an image, a resource link — is untouched."
  [part plain? n]
  (if-some [t (:text part)]
    (let [words (if plain?
                  (text/plain-text (or (text/message-text t) ""))
                  (str t))]
      (assoc part :text (if n (:text (text/cap words n)) words)))
    part))

(defn shaped
  "Gate's payload, as the caller asked to read it (R-5). `text_only`
  takes the plain words of each text part — a text/plain part when
  the answer carries one, the tags removed when it does not.
  `max_chars` cuts each part at that many characters. Each acts
  alone, and a call that asks for NEITHER gets the payload byte for
  byte, because this engine never rewrites an answer nobody asked it
  to.

  The bytes the shape removed ride out as metadata under
  `dropped-key`, measured with `result-bytes` — the same arithmetic
  the sitting's counter makes, so `served` plus `dropped` is what the
  power itself answered.

  AN `isError` ANSWER IS NEVER SHAPED. A refusal is Gate's own
  sentence about the rig, not the document the caller asked for, and
  a model learns from a refusal only when it reads the whole of it."
  [payload args]
  (let [plain? (true? (:text_only args))
        n (when-some [v (:max_chars args)]
            (when (number? v) (let [n (long v)] (when (pos? n) n))))]
    (if (or (not (map? payload))
            (:isError payload)
            (empty? (:content payload))
            (and (not plain?) (nil? n)))
      payload
      (let [out (update payload :content
                        (fn [parts] (mapv #(shape-part % plain? n) parts)))
            dropped (- (result-bytes payload) (result-bytes out))]
        (cond-> out
          (pos? dropped) (vary-meta assoc dropped-key dropped))))))

(defn seat-byte-ceiling
  "The ceiling this session's seat puts on one bench answer, in bytes,
  or nil when it names none — and nil is today's every answer: no seat
  field carries one yet, so `bench-capped` clamps to the rig's own
  ceiling. The read is here, on the session the transport resolved, so
  the day a seat names a ceiling the power door already honours it."
  [session]
  (let [n (get-in session [:visibility :seat :bench_max_bytes])]
    (when (and (number? n) (pos? (long n))) (long n))))

(defn rig-dropped
  "The bytes the BENCH RIG itself removed, added to what the shape
  removed (R-2). The rig caps its own answers and says so in
  `dropped`; the sitting's `served` line already carries what this
  door dropped, and a reader adding the two reads what the seat would
  have paid for the whole answer. A tool that says nothing about
  dropping adds nothing."
  [result]
  (let [n (get-in result [:structuredContent :result :dropped])]
    (if (and (number? n) (pos? (long n)) (instance? clojure.lang.IObj result))
      (vary-meta result update dropped-key (fnil + 0) (long n))
      result)))

(defn dropped-bytes
  "What a shaped answer said it removed, or 0."
  [result]
  (long (or (when (instance? clojure.lang.IObj result)
              (get (meta result) dropped-key))
            0)))

(defn power-refusal?
  "Is this `waymark_power` answer a refusal the model should have
  known ahead of time? An `isError` answer is — the power door's 403
  (ungranted, or outside the grant's filter), its 404 and 422, and a
  rig's own input refusal, which comes back as Gate's isError payload
  with no status on it. A held call is NOT: it answers `isError`
  false, because it is an answer and never a refusal (held_calls.clj,
  R-2). Nor is a 5xx: a Gate or rig that is dark or unreachable (502,
  503), or this door's own anonymous 500, is the wire failing, not law
  the model did not know. The status rides as mcp's
  `:waymark10.server.mcp/status` metadata."
  [tool-name out]
  (boolean
   (and (= "waymark_power" tool-name)
        (map? out)
        (true? (:isError out))
        (let [status (:waymark10.server.mcp/status (meta out))]
          (not (and (number? status) (<= 500 (long status))))))))
