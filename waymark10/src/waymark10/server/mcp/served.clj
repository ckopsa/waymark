(ns waymark10.server.mcp.served
  "What the message layer does around each answered tool call, carved
  out of waymark10.server.mcp (ticket 70629989): the stamp a bench call
  wears, the closed-sitting refusal, the bytes, record and refusals a
  call counts on its sitting, the seat's inbox hint, and the
  list-changed notice. A pure move — mcp keeps a stub for each var it
  still calls, and this namespace requires mcp.wire, mcp.gate and
  mcp.bench rather than mcp back."
  (:require [clojure.string :as str]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.mcp.bench :as mcp-bench]
            [waymark10.server.mcp.gate :as mcp-gate]
            [waymark10.server.mcp.wire :as mcp-wire]
            [waymark10.server.problems :as p]
            [waymark10.server.quiet :as quiet]
            [waymark10.server.routes.seats :as seat-routes]
            [waymark10.server.seats :as seats]
            [waymark10.server.transcripts :as transcripts]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

;; mcp.wire's, mcp.gate's and mcp.bench's vars, one stub for each var
;; this namespace calls

(def ^:private bound-sitting mcp-wire/bound-sitting)
(def ^:private calling-sitting mcp-wire/calling-sitting)
(def ^:private bound-seat mcp-wire/bound-seat)
(def ^:private bound-bench mcp-wire/bound-bench)
(def ^:private body-json mcp-wire/body-json)
(def ^:private refusal mcp-wire/refusal)

(def ^:private result-bytes mcp-gate/result-bytes)
(def ^:private dropped-bytes mcp-gate/dropped-bytes)

(def ^:private row-of mcp-bench/row-of)

(defn bench-stamped
  "A bench call's arguments with the OFFICE on them (waymark-fp62.6.3.5,
  R-5): `seat` and `sitting`, the two ids this session is bound to.

  IT IS DONE HERE AND NOT IN THE DOOR. The power door is a function of
  a grant and a call, and a session is neither; the MCP dispatch is
  the one place that holds the binding, so the door stays pure and the
  stamp rides in as two more arguments the rig reads. A session with
  no bound sitting — a person's own hand, an agent that never sat —
  carries neither, and the rig's record then says what is true: nobody
  is sitting.

  The rig holds no seat between calls (the owner's ruling: the engine
  is the enforcement point, the rig is the hand), so the office is on
  every call or on none.

  THE WORKTREE RIDES TOO. The sit prepared one worktree and answered
  its `repo` and `branch` (R-12.29); a call that leaves either out gets
  the prepared one here, BEFORE `gate/invoke-for` judges the grant's
  filter, so the seat is never refused for law the engine already
  knew. A call that names its own `repo` or `branch` keeps it, and the
  filter still judges it; the prepared branch is filled only into a
  call on the prepared repository. A sitting with no prepared bench
  gets nothing filled."
  [eng session tool args]
  (if-not (gate/bench-tool? tool)
    args
    (let [sid (:mcp-session-id session)]
      (if-some [sitting (some-> (bound-sitting eng sid) str not-empty)]
        (let [bench (bound-bench eng sid)
              repo (some-> (:repo bench) str not-empty)
              branch (some-> (:branch bench) str not-empty)
              named-repo (some-> (:repo args) str not-empty)
              named-branch (some-> (:branch args) str not-empty)]
          (cond-> (assoc args :sitting sitting)
            (some-> (bound-seat eng sid) str not-empty)
            (assoc :seat (str (bound-seat eng sid)))

            (and repo (nil? named-repo))
            (assoc :repo repo)

            (and branch (nil? named-branch)
                 (or (nil? named-repo) (= named-repo repo)))
            (assoc :branch branch)))
        args))))

(defn with-inbox-hint
  "The answer, with the seat's inbox hint when the sitter's inbox grew
  since it was last hinted (`seat-routes/inbox-hint`, ticket
  ca540029): `inbox_hint` beside the content, and the same as a last
  text part, because a model reads the content. A principal sitting in
  no seat, a seat with no inbox and nothing new leave the answer as it
  was; a hint that cannot be read never costs the answer."
  [eng session out]
  (if-some [hint (when (map? out)
                   (try (some->> (calling-sitting eng session)
                                 (seat-routes/inbox-hint eng))
                        (catch Exception _ nil)))]
    (-> out
        (assoc :inbox_hint hint)
        (update :content #(conj (vec %) {:type "text"
                                         :text (str "inbox_hint: " (wire/write-json hint))})))
    out))

;; ── the list-changed notice ─────────────────────────────────────────
;;
;; `tools/list` is computed per call from the visibility the router
;; resolved for THIS request (listing, above), so a grant that moved
;; is already on the wire the next time a client asks. What a client
;; does not know is WHEN to ask: MCP's answer is the server pushing
;; notifications/tools/list_changed, and the transport (routes/mcp.clj)
;; pushes it on the GET stream whenever a transition below says so.

(def list-changed
  "The JSON-RPC notification the stream carries when the caller's
  tool list may have changed — no params, by the protocol."
  {:jsonrpc "2.0" :method "notifications/tools/list_changed"})

(defn grant-moved-for?
  "Did this transition's ROW move the powers of principal `pid`?
  What waymark_powers answers is a function of the grant the caller
  wears (the tool list itself is static since waymark-912p; the
  notice stays as the nudge to read the powers document again), so
  the rows that can change it are the caller's own grants
  (audience = pid: accept, extend, expire, revoke) and the caller's
  own asks (requested_by = pid: an approve mints or widens). Any
  other row — another principal's grant, a kind that is not a grant —
  answers false, and the stream says nothing: a principal is told
  about ITS leash and nobody else's."
  [kind row pid]
  (boolean
   (and pid
        (case (keyword kind)
          :grant (= pid (get-in row [:data :audience]))
          :approval_request (= pid (get-in row [:data :requested_by]))
          false))))

(defn served-name
  "The name this answer is counted under (R-10.6a, and
  waymark-fp62.6.3.2's R-8).

  Every tool counts under its own name. A BENCH power is the one
  exception, and it is the same rule read one layer down: the ledger's
  question is which TOOL a sitting spent its bytes on, and a code seat
  whose whole bill read `waymark_power` would answer it with one line
  for a read, a find, an edit and a pull together. So a bench call
  counts under the rig's own tool — `bench__read`, `bench__find` — and
  every other power counts under `waymark_power` as it always has.

  A SEAT MAY SPELL THE TOKEN (waymark-fp62.6.3.12), and the door
  resolves it to the tool. This counter resolves it the same way, so
  one line of the ledger says `bench__read` whichever spelling the
  seat typed."
  [eng tool-name params]
  (if (= "waymark_power" tool-name)
    (let [asked (str (get-in params [:arguments :tool]))
          ;; the name the door resolved (waymark-fp62.6.3.12): a call
          ;; made with `bench.read` is counted under `bench__read`,
          ;; as the same call made with the tool name is. The counter
          ;; never costs a tool its answer, so a resolution that
          ;; throws leaves the name as the caller typed it.
          inner (if (gate/bench-tool? asked)
                  asked
                  (try (gate/tool-name-of eng asked)
                       (catch Exception _ asked)))]
      (if (gate/bench-tool? inner) inner tool-name))
    tool-name))

(defn cancelled-run
  "The run id of a bench.test run this answer says was cancelled — \"\"
  when the rig named no id — or nil when the answer says no such
  thing (ticket 39b2c934). Read off the rig's own result map, the one
  `bench-result` reads, for `bench__test` and `bench__test_result`."
  [served-tool out]
  (when (contains? #{"bench__test" "bench__test_result"} (str served-tool))
    (when-some [r (mcp-bench/bench-result out)]
      (when (= "cancelled" (some-> (or (:conclusion r) (:status r)) name))
        (str (or (:run_id r) ""))))))

(defn count-served!
  "R-10.6a: the bytes this tool answered, on the open sitting of the
  session's grant.

  `bump-counter!`'s rule, one field over. The lookup is R-10.6's own
  — one query by grant and state — and a session with no open sitting
  counts nothing, which is the honest reading: nothing was read under
  a leash that opened no wake.

  It runs on every `tools/call` this door ANSWERS, a refusal as well
  as an allowance, because the model reads both. An unknown tool name
  never reaches here: the protocol error carries no tool result, and
  there is no tool to name the bytes after.

  `waymark_sit` IS COUNTED, and it needs its own lookup to be
  (R-12.28). The transport resolves the session's visibility BEFORE
  the message runs, so the sit still wears the leash it arrived with
  and the grant it opened the sitting under is not the one on the
  session; the sitting the bind just wrote is. Its answer now carries
  the walk, which is the largest thing this door serves a firing, and
  a lever nobody can read is a lever nobody pulls.

  It never throws: a counter that could fail a tool answer would cost
  the model the very bytes it is there to measure."
  [eng session tool-name result]
  (try
    ;; a quiet engine counts nothing (server/quiet)
    (when-some [sitting-id (when-not (quiet/quiet? eng)
                             (calling-sitting eng session))]
      (seats/add-served! eng sitting-id tool-name (result-bytes result)
                         (dropped-bytes result)
                         ;; an answer that is no error: the close reads
                         ;; it against the last refusal (ticket 28a5d117)
                         (not (and (map? result) (true? (:isError result)))))
      (when-some [run (cancelled-run tool-name result)]
        (seats/add-cancelled-run! eng sitting-id run)))
    (catch Exception e
      (binding [*out* *err*]
        (println "waymark10 mcp served counter" tool-name "failed -"
                 (ex-message e)))
      nil)))

(def ^:private power-refusal? mcp-gate/power-refusal?)

(defn record-call!
  "Ticket dc9e5b96: one line of the sitting's record of calls, beside
  the count `count-served!` keeps — what the call read or moved, and
  when. It never throws, for `count-served!`'s reason."
  [eng session tool-name args out]
  (try
    ;; a quiet engine records nothing (server/quiet)
    (when-some [sitting-id (when-not (quiet/quiet? eng)
                             (calling-sitting eng session))]
      (transcripts/record-call! eng sitting-id tool-name args
                                (body-json {:body (some-> out :content first :text)})
                                (and (map? out) (true? (:isError out)))))
    (catch Exception e
      (binding [*out* *err*]
        (println "waymark10 mcp call record" tool-name "failed -"
                 (ex-message e)))
      nil)))

(defn count-refused!
  "R-10.6 at the power door: one refusal on the open sitting of the
  session's grant, for each `waymark_power` answer `power-refusal?`
  names. Every OTHER tool's refusal is counted where it is thrown: a
  `waymark_invoke` 409 leaves through the door's own
  `router/wrap-refusals-counted`, so counting it here too would count
  it twice. The power tool never goes through that door, which is why
  this line exists.

  It never throws, for `count-served!`'s reason."
  [eng session tool-name out]
  (try
    (when (power-refusal? tool-name out)
      (when-some [sitting-id (calling-sitting eng session)]
        (let [doc (body-json {:body (-> out :content first :text)})
              field (fn [k] (when (map? doc) (or (get doc k) (get doc (name k)))))]
          (seats/bump-counter! eng sitting-id :refusals
                               {:type (or (field :type) "power-refused")
                                :guard (field :guard)}))))
    (catch Exception e
      (binding [*out* *err*]
        (println "waymark10 mcp refusal counter" tool-name "failed -"
                 (ex-message e)))
      nil)))

(defn closed-sitting-refusal
  "The `sitting_closed` refusal for a call on a session whose sitting
  is no longer open, or nil (ticket f6c8d5ce). The call is never
  re-attributed to another sitting of the seat: a run whose close came
  while it was still working learns that its sitting is over rather
  than spending on a sibling's bill. `waymark_sit` is the one door left
  open, because a re-sit is how a run whose connector dropped finds
  its own sitting again. A bench tool through `waymark_power` is left to
  the gate's hold guard, which names the ticket the closed sitting no
  longer holds and refuses its writes, while its reads still forward."
  [eng session tool-name arguments]
  (when-not (or (= "waymark_sit" (str tool-name))
                (and (= "waymark_power" (str tool-name))
                     (str/starts-with? (str (or (get arguments :tool)
                                                (get arguments "tool")))
                                       "bench__")))
    (when-some [sid (some-> (bound-sitting eng (:mcp-session-id session))
                            str not-empty)]
      (when-some [row (row-of eng :sitting sid)]
        (let [state (some-> (:state row) name)]
          (when (not= "open" state)
            (refusal
             (p/problem :sitting_closed 409 "Sitting closed"
                        {:detail (str "This session sat in sitting " sid
                                      ", and it is " state
                                      (when-some [e (get-in row [:data :ended_at])]
                                        (str " since " e))
                                      ". A call is never counted on another"
                                      " sitting of the seat; this run's"
                                      " sitting is over.")
                         :sitting sid
                         :state state}))))))))
