(ns waymark10.ui-test
  "Phase 10 acceptance, part three: the generic UI's automated floor —
  the page serves at GET /api/-/ui off the classpath, and it is
  SELF-CONTAINED: no reference to any external host survives review
  (the CSP-shaped promise a generic client page must keep — an
  envelope-driven UI that phones home is not generic, it is a leak).
  The behavioral verification is by hand against dev10, documented
  in docs/waymark10-design.md §10."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

(def ^:dynamic *h* nil)
(def ^:dynamic *st* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["meals" "plans" "definitions"
                           "waymark10_transitions" "waymark10_idempotency"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (binding [*st* st
                  *h* (engine/handler
                       (engine/engine {:storage st
                                       :resources [fx/meal fx/plan]}))]
          (f))
        (finally (pg/close! st))))))

(deftest ui-serves
  (let [resp (*h* {:request-method :get :uri "/api/-/ui" :headers {}})]
    (is (= 200 (:status resp)))
    (is (str/starts-with? (get-in resp [:headers "Content-Type"]) "text/html"))
    (is (str/includes? (:body resp) "waymark"))))

(deftest ui-serves-at-the-root
  ;; the root is the canonical address; /api/-/ui stays as the
  ;; back-compat spelling existing deep links already carry
  (let [root (*h* {:request-method :get :uri "/" :headers {}})]
    (is (= 200 (:status root)))
    (is (str/starts-with? (get-in root [:headers "Content-Type"]) "text/html"))
    (is (= (:body root)
           (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}})))
        "one page, two addresses")))

(def ^:private iphone-ua
  (str "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) "
       "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 "
       "Mobile/15E148 Safari/604.1"))

(deftest ui-serves-the-mobile-shell
  ;; one client, two shells: a phone's User-Agent gets the SAME page
  ;; stamped <html data-ui="mobile">; ?ui= overrides the sniff both ways
  (let [page (fn [req] (:body (*h* (merge {:request-method :get
                                           :uri "/api/-/ui"
                                           :headers {}}
                                          req))))
        stamped? #(str/includes? % "<html lang=\"en\" data-ui=\"mobile\">")]
    (is (not (stamped? (page {})))
        "no UA, no stamp — the desktop shell is the default")
    (is (stamped? (page {:headers {"user-agent" iphone-ua}})))
    (is (stamped? (page {:query-string "ui=mobile"}))
        "?ui=mobile beats the missing UA")
    (is (not (stamped? (page {:headers {"user-agent" iphone-ua}
                              :query-string "ui=desktop"})))
        "?ui=desktop beats the phone UA")))

(deftest ui-lite-serves
  ;; the original phase-10 page, preserved beside the ported client
  (let [resp (*h* {:request-method :get :uri "/api/-/ui-lite" :headers {}})]
    (is (= 200 (:status resp)))
    (is (str/starts-with? (get-in resp [:headers "Content-Type"]) "text/html"))
    (is (str/includes? (:body resp) "waymark"))
    (is (not= (:body resp)
              (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}})))
        "lite and full are distinct assets")))

(defn- check-page [path]
  (let [body (:body (*h* {:request-method :get :uri path :headers {}}))]
    (testing (str path ": no CDN scripts, stylesheets, fonts, or remote fetches")
      (is (empty? (re-seq #"(?i)(src|href)\s*=\s*[\"']https?://" body))
          "every asset attribute is same-origin")
      (is (empty? (re-seq #"(?i)@import|fonts\.googleapis|cdn\." body)))
      (is (empty? (re-seq #"(?i)fetch\(\s*[\"']https?://" body))
          "every fetch is a relative href off the wire"))
    (testing (str path ": the page consumes the wire, not a baked-in app")
      (is (str/includes? body "/api/.well-known/waymark")
          "discovery drives the nav")
      (is (str/includes? body "/api/-/events")
          "live updates ride the firehose")
      (is (str/includes? body "x-waymark-principal")
          "the dev principal header is the auth seam")
      (is (str/includes? body "Waymark-Acknowledge")
          "the acknowledge protocol is wired")
      (is (str/includes? body "Idempotency-Key"))
      (is (str/includes? body "If-Match")))
    (testing (str path ": the page dresses itself for the reader's theme")
      ;; waymark-88k — BOTH pages, and they agree: the system's
      ;; preference by default, an explicit data-theme winning in
      ;; either direction, and one storage key between them
      (is (str/includes? body "<meta name=\"color-scheme\" content=\"light dark\">"))
      (is (str/includes? body "@media (prefers-color-scheme: dark)"))
      (is (str/includes? body ":root:not([data-theme=\"light\"])"))
      (is (str/includes? body ":root[data-theme=\"dark\"]"))
      (is (str/includes? body "localStorage.getItem(\"waymark.theme\")")
          "the same key on the lite page and the full one")
      (is (< (str/index-of body "<script id=\"theme-boot\">")
             (str/index-of body "<style>"))
          "the stamp precedes the stylesheet: no flash of the wrong theme")
      (is (str/includes? body "data-theme-choice=\"system\"")
          "System is a seat of its own — it removes the stamp"))))

(deftest ui-references-no-external-hosts
  (check-page "/api/-/ui")
  (check-page "/api/-/ui-lite"))

(defn- twice-declared
  "The top-level function names `body` declares more than once. The
  ui/ files are joined into one script, so a second `function name(`
  is legal JavaScript and silently wins; top-level is column 0, the
  way every file under ui/ writes it."
  [body]
  (->> (re-seq #"(?m)^(?:async\s+)?function\s*\*?\s*([A-Za-z_$][\w$]*)\s*\(" body)
       (map second)
       frequencies
       (keep (fn [[fname n]] (when (< 1 n) fname)))
       sort))

(deftest ui-declares-no-top-level-function-twice
  ;; ticket 837f840c: a new replayWalk(r) beside the existing async
  ;; replayWalk(self) stalled the replay while every pinned string held
  (testing "the check sees a second declaration, async or not"
    (is (= ["replayWalk"]
           (twice-declared (str "<script>\n"
                                "async function replayWalk(self) {\n"
                                "  function step(i) {}\n"
                                "}\n"
                                "function once() {}\n"
                                "function replayWalk(r) {\n"
                                "  function step(i) {}\n"
                                "}\n"
                                "</script>")))
        "nested functions are not top-level; one name twice is named"))
  (testing "the served page"
    (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))
          twice (twice-declared body)]
      (is (seq (re-seq #"(?m)^(?:async\s+)?function\s" body))
          "the page still writes its top-level functions at column 0")
      (is (empty? twice)
          (str "declared twice at the top level, the later one wins: "
               (str/join ", " twice))))))

(deftest ui-port-keeps-the-ten-wire
  ;; the ported waymark9 client speaks wire 10, not 9: the grant scope
  ;; selector, the relay/2 draft socket, and the named SSE classes are
  ;; all in the page; the 9-only surfaces are not
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "X-Waymark-Grant"))
    (is (str/includes? body "/collab"))
    (is (str/includes? body "\"transition\""))
    (is (str/includes? body "\"derivation\""))
    (is (not (str/includes? body "X-Principal-Id"))
        "the waymark9 dev headers do not survive the port")
    (is (not (str/includes? body "wmk_"))
        "no minted bearer-token vocabulary on wire 10")))

(deftest ui-renders-an-invitation-from-its-row
  ;; docs/spec-guided-follow.md §3: the invitation's row page offers the
  ;; step to its subject; the dialog opens on the invited row with the
  ;; suggestions marked, the field scrolled to and lit, the note beside it
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "kind === \"invitation\""))
    (is (str/includes? body "onclick: () => openInvitation(doc)"))
    (is (str/includes? body "async function openInvitation(inv)"))
    (is (str/includes? body "suggest: d.suggest || {}"))
    (is (str/includes? body "suggested-value"))
    (is (str/includes? body "scrollIntoView({behavior: \"smooth\", block: \"center\"})"))
    (is (str/includes? body "data-invite-note"))
    (is (str/includes? body "@keyframes invited"))))

(deftest ui-lights-every-invited-field
  ;; docs/spec-walkthrough.md §4: every named field is lit, the note sits
  ;; beside the first, each further one shows its ordinal, and a row born
  ;; before `fields` reads as a list of one
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "fields: d.fields || (d.field ? [d.field] : [])"))
    (is (str/includes? body "l.spot.classList.add(\"invited\")"))
    (is (str/includes? body "if (spot) spot.after(note);"))
    (is (str/includes? body "data-invite-ordinal"))
    (is (str/includes? body ".invite-ordinal {"))))

(deftest ui-opens-an-invitation-from-the-collection
  ;; the collection row of an open invitation to the viewer takes the
  ;; step in one tap, reading the full envelope first (summaries drop data)
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "function invitationRowOpen(item)"))
    (is (str/includes? body "async function openInvitationRow(item)"))
    (is (str/includes? body "const res = await api(item.self);"))
    (is (str/includes? body "openInvitationRow(item); }"))))

(deftest ui-decline-invokes-the-decline-door
  ;; decline is one button on the dialog, straight through the
  ;; invitation's own decline door
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "data-invite-decline"))
    (is (str/includes? body
                       "invokeBare(invitation.doc.actions.decline, invitation.doc)"))))

(deftest ui-draws-step-n-of-m-on-an-invited-dialog
  ;; docs/spec-walkthrough.md §5: an invitation that has a walkthrough
  ;; opens as any other does, with "Step 2 of 4 · <title>" above the form,
  ;; and the chip says the same on every screen
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "function stepLine(n, of, title)"))
    (is (str/includes? body "return `Step ${n} of ${of}`"))
    (is (str/includes? body "walkthrough: d.walkthrough ? ledStep(d) : null"))
    (is (str/includes? body "data-walk-step"))
    (is (str/includes? body "stepLine(led.step, led.of, led.title)"))
    (is (str/includes? body "<span id=\"walkchip\"></span>"))
    (is (str/includes? body "`step ${f.step} of ${f.of}`"))))

(deftest ui-offers-skip-and-stop-inside-a-walkthrough
  ;; the Decline button reads Skip and still walks the invitation's own
  ;; decline door; Stop sits beside it and walks the walkthrough's stop
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "led ? \"Skip\" : \"Decline\""))
    (is (str/includes? body "data-walk-stop"))
    (is (str/includes? body "async function stopLed()"))
    (is (str/includes? body "const stop = row.ok && (row.body.actions || {}).stop;"))
    (is (str/includes? body "btn(\"data-walk-skip\", \"Skip\", skipLedStep)"))))

(deftest ui-walkthrough-page-lists-its-steps
  ;; the row page lists every step's note in order, before the start
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "if (kind === \"walkthrough\") panel.append(walkthroughSteps(doc));"))
    (is (str/includes? body "function walkthroughSteps(doc)"))
    (is (str/includes? body "data-walk-steps"))))

(deftest ui-start-follows-the-author-in-guided-mode
  ;; docs/spec-walkthrough.md §5, watching an agent step: the person's own
  ;; Start or Resume, heard on the firehose, follows the author in guided
  ;; mode; an agent step takes the screen to its `self`; the next person
  ;; step opens over a guided dialog; and a follow the walkthrough began
  ;; ends when the walkthrough leaves the hand
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "function followAuthor()"))
    (is (str/includes? body "const tapped = !!me && [\"start\", \"resume\"].includes(ev.action) &&"))
    (is (str/includes? body "if (tapped) followAuthor();"))
    (is (str/includes? body "follow({id: author, display: held ? followName : author}, {ui: true});"))
    (is (str/includes? body "function showAgentStep()"))
    (is (str/includes? body "const self = ((d.steps || [])[(d.current || 1) - 1] || {}).self;"))
    (is (str/includes? body "setTimeout(showAgentStep, 0);"))
    (is (str/includes? body "if ($(\"dialog[open]:not([data-guided])\")) return;"))
    (is (str/includes? body "if (walkthroughFollow && followId === walkthroughFollow) unfollow();"))))

(deftest ui-an-agent-step-offers-stop-and-no-skip
  ;; the chip on an agent step names who is working and the step's note,
  ;; with Stop beside it; Skip is a person step's alone
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))
        from (str/index-of body "} else if (d.waiting_on === \"agent\") {")
        to (some->> from (str/index-of body "} else {"))
        branch (if (and from to) (subs body from to) "")]
    (is (some? to) "the chip has a branch for an agent step")
    (is (str/includes? branch "` is working: ${(steps[n - 1] || {}).note || \"\"}`"))
    (is (str/includes? branch "if (stop) chip.append(stop);"))
    (is (not (str/includes? branch "data-walk-skip")))
    (is (not (str/includes? branch "data-walk-take")))))

(deftest ui-offers-do-this-later
  ;; docs/spec-scheduled-actions.md R-7.3: a row's dialog schedules the
  ;; same call, with the zone named and the rule in plain words; a confirm
  ;; door's sentence rides the scheduling tap; and the row's page lists
  ;; what waits on it, with reschedule and cancel
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "\"Do this later\""))
    (is (str/includes? body "Intl.DateTimeFormat().resolvedOptions().timeZone"))
    (is (str/includes? body "rule(\"strict\", \"Only if nothing about it changes\")"))
    (is (str/includes? body "rule(\"state\", \"As long as it is still \""))
    (is (str/includes? body "rule(\"conditions\","))
    (is (str/includes? body "filterPopover(query, new URLSearchParams()")
        "a condition is written with the collection's filter control")
    (is (str/includes? body "api(\"/api/scheduled_actions\","))
    (is (str/includes? body "call.acknowledge = consequence"))
    (is (str/includes? body "function scheduledSection(doc)"))
    (is (str/includes? body "name: \"reschedule\", entry: acts.reschedule"))
    (is (str/includes? body "invokeBare(acts.cancel, item)"))))

(deftest ui-follow-offers-guided-mode
  ;; docs/spec-guided-follow.md §2: the follow chip offers guided mode,
  ;; which reopens the one live stream with ?ui=<pid> and applies the
  ;; followed principal's ui frames read-only, behind the old guards
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "follow({id: followId, display: followName}, {ui: true})"))
    (is (str/includes? body "\"/api/-/live?ui=\" + encodeURIComponent(followId)"))
    (is (str/includes? body "sse(liveHref, frame =>"))
    (is (str/includes? body "if (wasUi) sseReopen(liveHref);"))
    (is (str/includes? body "if (f.event === \"ui\") applyGuidedUi(f);"))
    (is (str/includes? body "is filling this in"))
    (is (str/includes? body "dialog[open]:not([data-guided])"))
    (is (str/includes? body "data-guided-mark"))
    (testing "a guided dialog's read that fails or is not answered gives its key back"
      (is (str/includes? body "await Promise.race([api(d.self),\n      new Promise(done => setTimeout(done, GUIDED_READ_MS, null))]);"))
      (is (str/includes? body "} catch (_e) { res = null; }\n  finally {\n    mine = guidedOpening === key;"))
      (is (str/includes? body "if (mine) guidedOpening = null;"))
      (is (not (str/includes? body "const res = replay ? replayDialogDoc(d) : await api(d.self);"))))))

(deftest ui-sharing-is-off-by-default
  ;; the reporting side's opt-in: a per-tab toggle, off until pressed,
  ;; and a beat carries a ui part only while it is on
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "id=\"sharebtn\""))
    (is (str/includes? body "type=\"button\" aria-pressed=\"false\""))
    (is (str/includes? body "sessionStorage.getItem(\"wm10.share.ui\") === \"1\""))
    (is (str/includes? body "if (uiSharing()) body.ui = uiShareState();"))
    (is (str/includes? body "shareableValues(collectValues(form, input), input)"))))

(deftest ui-replay-paces-a-burst
  ;; docs/spec-agent-demo-walks.md §2: the beats of one connector call
  ;; are recorded milliseconds apart, and replay plays two frames less
  ;; than 50 ms apart the one floor (1000 ms) apart, under the long-silence cut
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "const REPLAY_BURST_MS = 50, REPLAY_BURST_GAP = REPLAY_MIN_STILL;"))
    (is (str/includes? body "const dt = Math.max(0, (r.frames[r.at].t || 0) - prev);"))
    (is (str/includes? body "r.at && dt < REPLAY_BURST_MS ? REPLAY_BURST_GAP : dt);"))
    (is (str/includes? body "r.timer = setTimeout(replayStep, gap / r.speed);"))))

(deftest ui-replay-shows-a-caption-and-holds-for-it
  ;; docs/spec-agent-demo-walks.md §3: a caption frame's line is shown in
  ;; a band until the next caption or an empty one, and the frame after
  ;; it waits 55 ms a character, between 1500 and 6000 ms
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "const REPLAY_READ_MS = 55, REPLAY_READ_MIN = 1500, REPLAY_READ_MAX = 6000;"))
    (is (str/includes? body "replay.caption = f.text ? f : null;"))
    (is (str/includes? body "el(\"div\", {id: \"replaycaption\", role: \"status\"})"))
    (is (str/includes? body "band.textContent = c ? c.text : \"\";"))
    (is (str/includes? body "#replaycaption {"))
    (is (str/includes? body "Math.max(REPLAY_READ_MIN, REPLAY_READ_MS * f.text.length));"))
    (is (str/includes? body "const read = replayReadingTime(r.at ? r.frames[r.at - 1] : null);"))
    (is (str/includes? body "const gap = read + Math.min(REPLAY_MAX_GAP,"))))

(deftest ui-replay-holds-on-a-write
  ;; the screen is still at least 1500 ms after a `transition`; a `move`
  ;; has no hold of its own, only the one floor. The hold is a floor
  ;; under the gap and no addition to it, so a recorded 60-second silence
  ;; still plays in REPLAY_MAX_GAP; film mode schedules by the same code
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "const REPLAY_MAX_GAP = 3000;"))
    (is (str/includes? body "const REPLAY_WRITE_HOLD = 1500;"))
    (is (str/includes? body "if (f.type === \"transition\") return REPLAY_WRITE_HOLD;"))
    (is (not (re-find #"REPLAY_MOVE_HOLD|replayActHold" body))
        "a move has no hold of its own: the one floor is under it")
    (is (str/includes? body "const still = replayHoldTime(r.frames, r.at);"))
    (is (str/includes? body "const gap = Math.max(replayGap(r),\n    still && left + REPLAY_GLIDE_MS + REPLAY_PRESS_MS);"))
    (is (str/includes? body "r.timer = setTimeout(replayStep, gap / r.speed);"))))

(deftest ui-replay-keeps-a-floor-under-every-visible-change
  ;; walk 082fefa5's shape (move, doc, ui open, ui typed, transition,
  ;; doc) is recorded milliseconds apart. After any change of the
  ;; screen (a move, a dialog opening or closing, a typing beat, a
  ;; caption, a doc that draws the screen again) the screen is still at
  ;; least 1000 ms, counted from the moment the change is drawn,
  ;; before speed and before the gesture. A doc of the screen already shown, or a ui beat equal
  ;; to the one before it, waits nothing and is not drawn again
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "const REPLAY_MIN_STILL = 1000;"))
    (testing "the one floor is under the gap the schedule plays, which speed divides"
      (is (str/includes? body "if (!replayShows(frames, at)) return 0;"))
      (is (str/includes? body "if (f.type === \"transition\") return REPLAY_WRITE_HOLD;\n  return REPLAY_MIN_STILL;"))
      (is (not (re-find #"REPLAY_STILL_SCREEN|REPLAY_STILL_TYPED|replayTyped" body))
          "no kind of change has a shorter floor of its own")
      (is (str/includes? body "const still = replayHoldTime(r.frames, r.at);")))
    (testing "a frame that changes nothing visible adds no wait"
      (is (str/includes? body "if (!read && replayStill(r.frames, r.at)) return 0;"))
      (is (str/includes? body "if ((f.t || 0) - (frames[at - 1].t || 0) < REPLAY_BURST_MS) return true;"))
      (is (str/includes? body "return JSON.stringify(frames[i].doc) === JSON.stringify(f.doc);"))
      (is (str/includes? body "replayBeat(frames[i]) === replayBeat(f);")))
    (testing "the floor is counted from the last frame that changed the screen"
      (is (str/includes? body "while (i >= 0 && !replayShows(frames, i)) i--;")))
    (testing "a doc that draws the screen again is a change, and one for another address is not"
      (is (str/includes? body "return !!f.doc && !!row(f.self) && row(f.self) === row(hereHref());")))
    (testing "the floor is counted from the moment the change is drawn"
      (is (str/includes? body "return replayDrawn(replay, renderReplay(view, href));"))
      (is (str/includes? body "const left = Math.max(0, still - (performance.now() - r.drawn) * r.speed);"))
      (is (str/includes? body "return r.drawn + r.still / r.speed - now;")))
    (testing "a frame that changes nothing is not drawn again"
      (is (str/includes? body "if (!same && self === String(hereHref() || \"\").split(\"?\")[0]) render();"))
      (is (str/includes? body "} else if (f.type === \"ui\" && !again) {")))))

(deftest ui-replay-leaves-a-screen-only-after-it-has-been-still
  ;; the floors are stillness: for a dialog with one typed field and a
  ;; submit, the form is still at least 1000 ms after it opens before the
  ;; pointer moves, the typed value at least 1000 ms before the pointer
  ;; heads to the submit, and a hop's list at least 1000 ms. The
  ;; gesture's 900 ms come after the stillness and not inside it
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))
        [_ floor] (re-find #"const REPLAY_MIN_STILL = (\d+);" body)]
    (is (and floor (>= (Long/parseLong floor) 1000)) "every change of the screen is still at least 1000 ms")
    (is (not (re-find #"REPLAY_SCREEN_MIN|REPLAY_TYPE_MIN" body))
        "no floor is measured from the screen's change to the next frame")
    (testing "an opened form and a typed value owe the one floor"
      (is (str/includes? body "return REPLAY_MIN_STILL;")))
    (testing "the frame waits the stillness and then the gesture, when its recorded gap is shorter"
      (is (str/includes? body "const gap = Math.max(replayGap(r),\n    still && left + REPLAY_GLIDE_MS + REPLAY_PRESS_MS);")))
    (testing "the gesture never starts inside the stillness"
      (is (str/includes? body "Math.max(left, gap - REPLAY_GLIDE_MS - REPLAY_PRESS_MS) / r.speed);")))
    (testing "a hop's list and a walk's row are still for the screen floor before the next gesture"
      (is (str/includes? body "replayGaze(\"list\", list);\n  replayLinger(r);"))
      (is (str/includes? body "replayGaze(walk.list ? \"list\" : \"row\", walk.self);\n  replayLinger(r);"))
      (is (str/includes? body "r.lead = setTimeout(() => replayGesture(r), REPLAY_MIN_STILL / r.speed);")))
    (testing "the arrival outline lasts as long as the stillness, so it is off when the pointer leaves"
      (is (str/includes? body "const REPLAY_GAZE_MS = REPLAY_MIN_STILL;")))))

(deftest ui-replay-anchors-a-caption-to-its-field
  ;; docs/spec-agent-demo-walks.md §3: a caption that names a field is
  ;; drawn beside it, with the field lit, by the code that draws an
  ;; invitation's note; the recorded dialog has that field to light
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "function markInvited(form, names, text) {"))
    (is (str/includes? body "dlg.guidedMark = (names, text) => markInvited(form, names, text);"))
    (is (str/includes? body "g.guidedMark(c.field ? [c.field] : [], c.text)"))
    (is (str/includes? body ".setAttribute(\"data-caption-note\", \"\");"))
    (is (str/includes? body "(f.type === \"invitation\" || (f.type === \"caption\" && f.field))"))
    (is (str/includes? body "if (replay) replayCaption();"))))

(deftest ui-replay-presses-the-submit-before-the-close
  ;; the moment of a write: for the frame that closes a form after its
  ;; write, the pointer goes to the guided dialog's submit button, drawn
  ;; unlit, and presses it before the frame is applied; a form closed
  ;; with no transition has no press, and no separate light is made
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))
        mark (str/index-of body "const wait = replayGestureWait(r);")
        close (str/index-of body "applyReplayFrame(r.frames[r.at++]);")]
    (is (not (str/includes? body "replayMarkWrite")))
    (is (str/includes? body "dlg.guidedWrite = () => {"))
    (is (str/includes? body "const lit = el(\"button\", {class: write.className + \" invited\","))
    (is (str/includes? body "if (!replayWrote(replay, key)) return null;"))
    (is (str/includes? body "let write = g.querySelector(\"[data-replay-write]\");"))
    (is (str/includes? body "write = g.guidedWrite();\n      write.classList.remove(\"invited\");"))
    (is (str/includes? body ".dlgfoot button.invited { animation: none; }"))
    (is (and mark close (< mark close))
        "the submit is pressed before the frame that closes the form is applied")))

(deftest ui-replay-changes-no-screen-behind-an-open-form
  ;; walk 082fefa5's write (dialog open, typing, transition, doc, close):
  ;; the transition and the doc recorded while the form is open wait, with
  ;; no time of their own, so the submit is pressed and the form closes
  ;; first. The frame that closes the form draws them on the screen the
  ;; form was over, outlined, and that screen is still for the write's
  ;; hold. A form the recording never closes has them drawn by the next
  ;; frame that is not its own, or when the last frame has played
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))
        hold (str/index-of body "replay.held.push(f);")
        land (str/index-of body "const note = landed || replayKeepsForm(f) ? null : replayLand(replay);")
        close (str/index-of body "applyUiFrame({self: f.self, ui: f.ui || {}, principal: actor});")]
    (testing "a transition or a doc behind an open form is held, not drawn"
      (is (str/includes? body "const form = f.type === \"ui\" && !!(f.ui || {}).dialog;"))
      (is (str/includes? body "if (!landed && replay.held && (f.type === \"transition\" || f.type === \"doc\")) {"))
      (is (str/includes? body "if (form && !replay.held) replay.held = [];")))
    (testing "a held frame waits nothing, and the floor before the close is the typed value's"
      (is (str/includes? body "if (t === \"ui\") return !!(frames[i].ui || {}).dialog;"))
      (is (str/includes? body "if (replayHeld(frames, at) &&\n      frames.some((g, k) => k > at && !replayKeepsForm(g))) return true;")))
    (testing "the frame that ends the form draws what waited, in order, on the screen shown"
      (is (str/includes? body "for (const f of held) {\n    applyReplayFrame(f, true);"))
      (is (str/includes? body "else if (landed) { /* the screen stays */ }"))
      (is (and hold land close (< hold land close))
          "the held frames are drawn by the frame that closes the form, not before it"))
    (testing "the screen wears the arrival outline and is still for the write's hold"
      (is (str/includes? body "r.gaze = null;\n    replayGaze(\"row\", wrote);"))
      (is (str/includes? body "if (frames[j].type === \"transition\") return replayHeld(frames, j);"))
      (is (str/includes? body "if (replayLands(frames, i)) return REPLAY_WRITE_HOLD;")))
    (testing "a form never closed loses nothing: the next frame that is not its own draws what waited"
      (is (str/includes? body "return f.type === \"transition\" || f.type === \"doc\" || f.type === \"caption\" ||\n    (f.type === \"ui\" && !!(f.ui || {}).dialog);"))
      (is (str/includes? body "if (t !== \"transition\" && t !== \"doc\" && t !== \"caption\") return false;")))
    (testing "with no frame left, the end draws it under the form the recording left open"
      (is (str/includes? body "if (!open) closeGuided();"))
      (is (str/includes? body "if (r.at >= r.frames.length && r.held) {\n    r.notice = replayLand(r, true) || r.notice;"))
      (is (str/includes? body "r.walked = null;\n    r.held = null;")))))

(deftest ui-replay-walks-to-a-row-that-is-not-on-screen
  ;; no jumps: a move whose link is not on screen presses the navigation
  ;; entry of its kind, that list is drawn as a screen of its own, still
  ;; for 1000 ms with the arrival outline, and the row's link is pressed
  ;; there; until then the frame is not applied, so no hash goes to the
  ;; row. A list already shown is no hop, and one hop is made for a frame
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))
        hop (str/index-of body "if (r.gesture && r.gesture.hop) { replayHop(r); return; }")
        act (str/index-of body "applyReplayFrame(r.frames[r.at++]);")]
    (is (str/includes? body "if (f.type !== \"move\" || !to.closest(\"#kinds\")) return null;"))
    (is (str/includes? body "return list.split(\"?\")[0] === String(f.self).split(\"?\")[0] ? null : list;"))
    (is (str/includes? body "hop: r.hopped === r.at ? null : replayHopOf(f, to)};"))
    (testing "the hop draws the list, outlined, and waits the screen floor for the next gesture"
      (is (str/includes? body "location.hash = \"#\" + list;\n  replayGaze(\"list\", list);"))
      (is (str/includes? body "const wait = REPLAY_MIN_STILL + REPLAY_GLIDE_MS + REPLAY_PRESS_MS;"))
      (is (str/includes? body "r.timer = setTimeout(replayStep, wait / r.speed);"))
      (is (str/includes? body "r.lead = setTimeout(() => replayGesture(r), REPLAY_MIN_STILL / r.speed);")))
    (is (and hop act (< hop act))
        "the hop is made before the frame is applied, and in its place")))

(deftest ui-replay-walks-to-the-row-of-a-dialog-a-write-or-an-invitation
  ;; no jumps for the three other frames either: a dialog beat with no
  ;; action button on screen, a write of the recorder's and an invitation,
  ;; on a row that is not the screen shown, make the gesture of a move to
  ;; that row first (navigation entry, list, the row's link); the row is
  ;; drawn as a screen of its own, and the frame is applied after it
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))
        walk (str/index-of body "if (r.gesture && r.gesture.walk) { replayArrive(r); return; }")
        act (str/index-of body "applyReplayFrame(r.frames[r.at++]);")]
    (testing "a dialog beat whose door has no button on screen"
      (is (str/includes? body "const to = ui.dialog ? !replayGestureTarget(f) && (list ? collectionHrefOf(c) : f.self)")))
    (testing "a write of the recorder's, and not a step with no click behind it"
      (is (str/includes? body ": f.type === \"transition\" ? !replayNotice(r, f) && f.self")))
    (testing "an invitation"
      (is (str/includes? body ": f.type === \"invitation\" && f.action ? row(f.self) : null;")))
    (testing "the screen shown is no walk, nor is one under an open dialog, and one walk is made for a frame"
      (is (str/includes? body "if (!f || r.walked === r.at || r.door || $(\"dialog[open]\")) return null;"))
      (is (str/includes? body "return to && row(to) !== hereHref() ? {type: \"move\", who: f.who, self: to, list} : null;")))
    (testing "the gesture is a move's, so it goes by the kind's list as a move does"
      (is (str/includes? body "const walk = replayWalkOf(r, r.frames[r.at]);\n  const f = walk || r.frames[r.at];")))
    (testing "the walk draws the row, outlined, and waits the screen floor for the frame's own gesture"
      (is (str/includes? body "applyFollowMove(walk.self);\n  replayGaze(walk.list ? \"list\" : \"row\", walk.self);")))
    (is (and walk act (< walk act))
        "the walk is made before the frame is applied, and in its place")))

(deftest ui-replay-points-at-the-field-a-typing-beat-types
  ;; the pointer fills the form: a typing beat's gesture goes to the
  ;; field it types into, and the click there, before the frame shows the
  ;; value, gives that field the ring a focused field wears. Replay makes
  ;; no separate lit-field cue. The pointer is drawn inside the dialog
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "? g.guidedField(replayTypingOf(f)) : null;"))
    (is (str/includes? body "if (typeof ui.focus === \"string\" && !ui.focus.startsWith(\"/\")) return ui.focus;"))
    (is (str/includes? body "if (form && r.gesture && r.gesture.field) { form.guidedClick(r.gesture.field); return; }"))
    (is (str/includes? body "if (spot) spot.setAttribute(\"data-replay-click\", \"\");"))
    (is (str/includes? body "[data-replay-click] { outline: 2px solid Highlight;"))
    (testing "the lit-field cue is a live follow's alone"
      (is (str/includes? body "if (!replay) g.guidedLight(guidedTyping);"))
      (is (str/includes? body "if (g && g.guidedLight && !replay) g.guidedLight(guidedTyping);")))
    (testing "the pointer is drawn inside the form's dialog, and stays on the page when it closes"
      (is (str/includes? body "const host = target.closest(\"dialog[open]\") || document.body;"))
      (is (str/includes? body "if (p.parentElement !== host) host.append(p);"))
      (is (str/includes? body "else if (p && p.parentElement !== document.body && !p.closest(\"dialog[open]\"))")))))

(deftest ui-replay-shows-a-notice-for-a-step-with-no-click
  ;; a transition by a principal other than the recorder, a clock_shift
  ;; and an invitation the recorder did not write have no click behind
  ;; them: the pointer and the screen stay where they were, and the
  ;; caption band says the step
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "if (!r.recorder && (f.type === \"move\" || f.type === \"ui\")) r.recorder = f.who || null;"))
    (is (str/includes? body "const other = !!r.recorder && !!f.who && f.who !== r.recorder;"))
    (is (str/includes? body "if (f.kind === \"clock_shift\") return \"Later: \" + what;"))
    (is (str/includes? body "return (actor.type === \"system\" ? \"Scheduled\" : actor.display) + \": \" + what;"))
    (is (str/includes? body "return \"Invited: \" + (f.note || pretty(f.action || \"\"));"))
    (is (str/includes? body "replay.notice = replayNotice(replay, f);"))
    (testing "it moves no screen and makes no gesture"
      (is (str/includes? body "else if (!$(\"dialog[open]\") && !replay.notice) location.hash = \"#\" + f.self;")))
    (testing "the band shows it"
      (is (str/includes? body "band.toggleAttribute(\"data-replay-notice\", !!n);"))
      (is (str/includes? body "#replaycaption[data-replay-notice] { font-style: italic; }")))))

(deftest ui-replay-outlines-the-row-or-list-the-gaze-moves-to
  ;; the moment of a look: a move to a row, or a `collection` ui, marks
  ;; the root element for the screen's stillness, 1000 ms, and the main panel is
  ;; outlined while it does; film mode hides neither mark
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "const REPLAY_GAZE_MS = REPLAY_MIN_STILL;"))
    (is (str/includes? body "if (f.self) { applyFollowMove(f.self); replayGaze(\"row\", f.self); }"))
    (is (str/includes? body "replayGaze(\"list\", collectionHrefOf(c));"))
    (is (str/includes? body "root.setAttribute(\"data-replay-gaze\", what);"))
    (is (str/includes? body "html[data-replay-gaze] .panel[data-replay-screen],"))
    (is (str/includes? body "html[data-replay-gaze] [data-replay-doc] > .panel:first-of-type {"))
    (is (not (re-find #"html\[data-film\][^{]*(data-replay-gaze|data-guided-write|\.dlgfoot)[^{]*\{[^}]*display: none" body))
        "film mode hides no part of either mark")))

(deftest ui-replay-points-at-the-link-or-button-before-the-act
  ;; the gesture before an act: a pointer glides for 600 ms to the link a
  ;; move would follow, or to the action button a dialog beat would
  ;; press, and that element is lit for 300 ms in the invitation's lit
  ;; style; the frame is applied after it. With no link on screen there
  ;; is no gesture and the move still happens. Film mode draws the pointer
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))
        wait (str/index-of body "const wait = replayGestureWait(r);")
        act (str/index-of body "applyReplayFrame(r.frames[r.at++]);")]
    (is (str/includes? body "const REPLAY_GLIDE_MS = 600, REPLAY_PRESS_MS = 300;"))
    (testing "a move goes to the row's link on the page, then to the navigation bar"
      (is (str/includes? body "return link(\"#view\", h => row(h) === to) || nav(to) ||"))
      (is (str/includes? body "(list !== row(here) && nav(list)) || null;")))
    (testing "a dialog beat goes to that row's action button for the door"
      (is (str/includes? body ".filter(b => seen(b) && b.dataset.action === d.action);"))
      (is (str/includes? body "return doors.find(b => rowOf(b) === d.self) ||")))
    (testing "a query beat goes to the filter control or the navigation entry"
      (is (str/includes? body "link(\"#view\", h => h === target) || nav(row(c.self)) || null;")))
    (testing "the target is lit after the glide, and the frame waits for the press"
      (is (str/includes? body "lit.classList.add(\"invited\");"))
      (is (str/includes? body "lit.setAttribute(\"data-replay-press\", \"\");"))
      (is (str/includes? body "}, REPLAY_GLIDE_MS / r.speed);"))
      (is (and wait act (< wait act))
          "the gesture is waited for before the frame is applied"))
    (testing "with no link on screen there is no gesture, and the frame is applied"
      (is (str/includes? body "if (!to) return;"))
      (is (str/includes? body "return r.gesture ? r.gesture.until - performance.now() : 0;")))
    (testing "the gesture ends as the wait before its frame does, after the stillness"
      (is (str/includes? body "Math.max(left, gap - REPLAY_GLIDE_MS - REPLAY_PRESS_MS) / r.speed);")))
    (testing "the pointer is drawn over the page, in film mode as well"
      (is (str/includes? body "document.body.append(p = el(\"div\", {id: \"replaypointer\", \"aria-hidden\": \"true\"}));"))
      (is (str/includes? body "#replaypointer { position: fixed; left: 0; top: 0; z-index: 35;"))
      (is (str/includes? body "[data-replay-press].invited { animation: none; }"))
      (is (not (re-find #"html\[data-film\][^{]*(#replaypointer|data-replay-press)[^{]*\{[^}]*display: none" body))))))

(deftest ui-replay-lights-the-field-a-staged-call-types
  ;; docs/spec-agent-demo-walks.md §2: a typing beat names its argument
  ;; in `focus`, and replay lights that field alone, in the invitation's
  ;; lit style, in the dialog the beat draws
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "const typing = !!d && typeof ui.focus === \"string\" && !ui.focus.startsWith(\"/\");"))
    (is (str/includes? body "guidedTyping = typing ? ui.focus : null;"))
    (is (str/includes? body "guidedFocus = typing ? null : ui.focus || null;"))
    (is (str/includes? body "g.guidedLight(guidedTyping);"))
    (is (str/includes? body "dlg.guidedLight = name => markTyped(form, name);"))
    (is (str/includes? body "function markTyped(form, name) {"))
    (is (str/includes? body "for (const s of form.querySelectorAll(\"[data-typed]\")) {"))
    (is (str/includes? body "spot.classList.add(\"invited\");"))
    (is (str/includes? body "spot.setAttribute(\"data-typed\", \"\");"))))

(deftest ui-replay-names-a-ref-fields-row-and-not-its-id
  ;; ticket 097e60da: a replay fetches no collection, so a ref picker has
  ;; no entry of its own. A typing beat seats it with one, [id, label]:
  ;; the beat's own `labels`, else the summary of the walk's last `doc`
  ;; for that row, else the id
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))
        said "if (typeof said === \"string\" && said) return said;"
        held "for (const [self, doc] of replay.docs)"]
    (testing "the beat's labels are kept beside its fields"
      (is (str/includes? body "guidedLastLabels = ui.labels || {};"))
      (is (str/includes? body "const said = Array.isArray(own) ? own[i] : i === undefined ? own : null;"))
      (is (str/includes? body said)))
    (testing "with no label, a doc the walk already holds names the row"
      (is (str/includes? body held))
      (is (str/includes? body "doc && typeof doc.summary === \"string\" && doc.summary)"))
      (is (< (str/index-of body said) (str/index-of body held))
          "the beat's own label is read first"))
    (testing "a replay alone seats the picker: a live follow's is fetched"
      (is (str/includes? body "g.guidedSet(guidedLastFields, replay ? guidedLabel : null);"))
      (is (not (str/includes? body "g.guidedSet(guidedLastFields);"))))
    (testing "the one entry is the row by its label, and the id when none is known"
      (is (str/includes? body "if (!seat) node.append(seat = el(\"option\", {value: v}, named[0] || v));"))
      (is (str/includes? body "else if (named[0] && seat.textContent === v) seat.textContent = named[0];")))))

(defn- well-known [h]
  (-> (h {:request-method :get :uri "/api/.well-known/waymark"
          :headers {"x-waymark-principal" "reader"}})
      :body
      wire/read-json))

(deftest ui-shows-no-banner-on-an-engine-without-an-expiry
  ;; docs/spec-demo-clones.md §3: a working engine sets no expiry, so
  ;; its well-known document carries none and the banner stays hidden
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (not (contains? (well-known *h*) :expires_at)))
    (is (str/includes? body "<div id=\"demobanner\" role=\"status\" aria-live=\"polite\" hidden></div>"))
    (is (str/includes? body "#demobanner[hidden] { display: none; }"))
    (is (str/includes? body "const at = Date.parse(w.expires_at || \"\");"))
    (is (str/includes? body "if (Number.isNaN(at)) return;"))
    (is (str/includes? body "if (demoEnds === null) { box.hidden = true; return; }"))))

(deftest ui-warns-before-a-demo-engine-ends
  ;; the engine reads its expiry at boot and answers it on well-known
  ;; beside its name; the banner says when, and turns to a warning at
  ;; 15 minutes and again at 5
  (let [ends "2026-10-01T16:40:00Z"
        opts {:storage *st* :resources [fx/meal fx/plan]}
        h (engine/handler (engine/engine (assoc opts :expires-at ends)))
        body (:body (h {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (= ends (:expires_at (well-known h))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (engine/engine (assoc opts :expires-at "soon")))
        "an expiry that is not an instant refuses the boot")
    (is (str/includes? body "Demo engine. It ends at ${at} and takes everything with it."))
    (is (str/includes? body "const DEMO_WARN_MS = 15 * 60000, DEMO_LAST_MS = 5 * 60000;"))
    (is (str/includes? body "left <= DEMO_LAST_MS ? \"last\" : left <= DEMO_WARN_MS ? \"warn\" : \"note\""))
    (is (str/includes? body "#demobanner[data-demo-level=\"warn\"]"))
    (is (str/includes? body "#demobanner[data-demo-level=\"last\"]"))
    (is (str/includes? body "setInterval(demoRefresh, DEMO_TICK_MS);"))))

(deftest ui-names-an-unexported-walk-in-the-warning
  ;; the banner names each walk of the viewer's that is still recording,
  ;; or sealed and not exported in this browser; the export button is
  ;; what marks a walk exported
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "?recorder=${encodeURIComponent(door.me)}&state="))
    (is (str/includes? body "(i.state === \"recording\" || (i.state === \"sealed\" && !done.has(i.self)))"))
    (is (str/includes? body "\"Your walk is not exported. \""))
    (is (str/includes? body "\"Seal and export it now: \""))
    (is (str/includes? body "el(\"a\", {href: \"#\" + w.self}, w.summary || \"the walk\")"))
    (is (str/includes? body "localStorage.getItem(\"wm10.walk.exported\")"))
    (is (str/includes? body "markWalkExported(self);"))
    (is (str/includes? body "\"data-export-walk\""))
    (is (str/includes? body "onclick: () => exportWalk(doc.self)"))))

(deftest ui-replay-draws-an-invitation-frame
  ;; docs/spec-walkthrough.md §6: replay opens an `invitation` frame's
  ;; dialog read-only, with every named field lit, the note and the
  ;; "Step 2 of 4" line, and the next `transition` frame closes it
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "async function openReplayInvitation(f, actor)"))
    (is (str/includes? body "openReplayInvitation(f, actor);"))
    (is (str/includes? body "g.setAttribute(\"data-replay-invite\", \"\");"))
    (testing "every named field has an input and is lit"
      (is (str/includes? body "const typed = inv ? [f.field, ...(f.fields || []),"))
      (is (str/includes? body "fields: f.fields || (f.field ? [f.field] : [])},")))
    (testing "the step line is drawn from the frame's own `step` and `of`"
      (is (str/includes? body "invitation: {doc: {}, note: f.note, step: f.step, of: f.of,"))
      (is (str/includes? body "if (invitation && invitation.step && invitation.of)"))
      (is (str/includes? body "`Step ${invitation.step} of ${invitation.of}`"))
      (is (str/includes? body "data-invite-step"))
      (is (str/includes? body ".invite-step {")))
    (testing "the transition that answers it closes it"
      (is (str/includes? body "const inv = $(\"dialog[open][data-replay-invite]\");"))
      (is (str/includes? body "if (inv && inv.getAttribute(\"data-guided\") === f.self + \" \" + f.action)")))))

(deftest ui-replay-draws-a-row-from-its-doc
  ;; docs/spec-agent-demo-walks.md §8a: a `doc` frame is kept as the
  ;; replay passes it, and the screen it names is drawn by the code that
  ;; draws a live row or collection, inert, with every request held
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "replay.docs.set(self, f.doc);"))
    (is (str/includes? body "if (doc && doc.kind) return renderReplayDoc(view, doc);"))
    (is (str/includes? body "\"data-replay-doc\": \"\", inert: \"\"});"))
    (is (str/includes? body "if (String(doc.kind).endsWith(\"_collection\")) renderCollection(screen, doc, hints);"))
    (is (str/includes? body "else drawn = renderResource(screen, doc, hints).catch(() => {});"))
    (testing "a dialog is drawn from the document's input schema"
      (is (str/includes? body "if (held && (held.actions || {})[d.action]) return {ok: true, body: held};")))
    (testing "it makes no read and no write"
      (is (str/includes? body "apiHeld = walk.frames.some(f => f.type === \"doc\");"))
      (is (str/includes? body "if (apiHeld) return {res: null, status: 0, ok: false, body: null, etag: null};"))
      (is (str/includes? body "if (apiHeld) return dataHintsCache[kind] || {};"))
      (is (str/includes? body "if (loaded || apiHeld) return;")))))

(deftest ui-replay-falls-back-without-a-doc
  ;; a screen the walk holds no document for is today's panel, and a
  ;; walk with no document at all holds no request
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))
        from-doc "if (doc && doc.kind) return renderReplayDoc(view, doc);"
        panel "const panel = el(\"div\", {class: \"panel\", \"data-replay-screen\": self});"]
    (is (str/includes? body "const doc = r.docs.get(self);"))
    (is (str/includes? body panel))
    (is (< (str/index-of body from-doc) (str/index-of body panel))
        "the panel is what is left when no document is held")
    (is (str/includes? body "let apiHeld = false;"))
    (testing "playing again forgets the documents, and stopping lets requests go"
      (is (str/includes? body "r.rows.clear();\n    r.docs.clear();"))
      (is (str/includes? body "replay = null;\n  apiHeld = false;")))))

(deftest ui-film-mode-hides-the-chrome
  ;; docs/spec-agent-demo-walks.md §8b: /#/api/walks/<id>?film=1 plays a
  ;; sealed walk for a camera; the dev principal box, the replay chip,
  ;; the demo banner and every toast are hidden, and the film's own
  ;; address is no screen and no gaze
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "return /^\\/api\\/walks\\/[^/]+$/.test(path) &&"))
    (is (str/includes? body "new URLSearchParams(query || \"\").get(\"film\") === \"1\" ? path : null;"))
    (is (str/includes? body "html[data-film] #who, html[data-film] #replaychip,"))
    (is (str/includes? body "html[data-film] #demobanner, html[data-film] #toast { display: none !important; }"))
    (testing "the chrome is gone before the walk is read"
      (is (< (str/index-of body "filmState(\"\");")
             (str/index-of body "const res = await fetch(self + \"/export\", {headers: principalHeaders()});\n    if (res.ok) text = await res.text();\n  } catch (_e) { /* never ready */ }"))))
    (is (str/includes? body "if (filmWalkOf(raw)) {"))
    (is (str/includes? body "if (film) return;"))))

(deftest ui-film-mode-says-when-it-has-ended
  ;; the root element's data-film is what the camera reads: `ready` under
  ;; the title card for 2 s, `playing` once play starts by itself at 1×,
  ;; and `ended` when the last screen has held for 1.5 s
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))
        ready "filmState(\"ready\");"
        playing "filmState(\"playing\");\n    startReplay(text);\n  }, FILM_TITLE_MS);"]
    (is (str/includes? body "const FILM_TITLE_MS = 2000, FILM_HOLD_MS = 1500;"))
    (is (str/includes? body "function filmState(s) { document.documentElement.setAttribute(\"data-film\", s); }"))
    (is (str/includes? body "el(\"h1\", {}, walk.header.title || \"a walk\"));"))
    (is (str/includes? body ready))
    (is (str/includes? body playing))
    (is (< (str/index-of body ready) (str/index-of body playing))
        "the title card comes before the play")
    (is (str/includes? body "if (film && r.at >= r.frames.length) filmEnd();"))
    (is (str/includes? body "setTimeout(() => filmState(\"ended\"), FILM_HOLD_MS);"))
    (is (str/includes? body "speed: 1, playing: false")
        "a replay starts at 1×")))

(deftest ui-film-mode-draws-a-caption-as-a-band
  ;; a caption on video is a band across the bottom, two lines at most
  ;; and in large type; one that names a field is drawn beside the lit
  ;; field instead, once its form is open
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "html[data-film] #replaycaption { left: 0; right: 0; bottom: 0; transform: none;"))
    (is (str/includes? body "border-radius: 0; box-shadow: none; font-size: 34px;"))
    (is (str/includes? body "line-height: 1.3; max-height: 2.6em; overflow: hidden; }"))
    (is (str/includes? body "band.style.display = c && !filmBeside(c) ? \"block\" : \"none\";"))
    (is (str/includes? body "const g = film && c.field && c.action &&"))
    (is (str/includes? body "String(c.self).split(\"?\")[0] + \" \" + c.action === g.getAttribute(\"data-guided\");"))))

(defn- render! [headers body]
  (let [resp (*h* {:request-method :post :uri "/api/-/render/markdown"
                   :headers (merge {"content-type" "application/json"} headers)
                   :body (wire/write-json body)})]
    (assoc resp :parsed (some-> (:body resp) wire/read-json))))

(deftest render-door-answers-in-order
  (let [resp (render! {"x-waymark-principal" "reader"}
                      {:texts ["# One" "two *words*"]})]
    (is (= 200 (:status resp)))
    (is (= ["<h1>One</h1>\n" "<p>two <em>words</em></p>\n"]
           (get-in resp [:parsed :html])))))

(deftest render-door-refuses-over-the-cap
  (testing "too many texts"
    (is (= 413 (:status (render! {"x-waymark-principal" "reader"}
                                 {:texts (vec (repeat 51 "x"))})))))
  (testing "too many bytes"
    (is (= 413 (:status (render! {"x-waymark-principal" "reader"}
                                 {:texts [(apply str (repeat (inc (* 200 1024)) "x"))]})))))
  (testing "not a list of strings"
    (is (= 422 (:status (render! {"x-waymark-principal" "reader"}
                                 {:texts "# One"})))))
  (testing "anonymous is not let in"
    (is (= 404 (:status (render! {} {:texts ["# One"]}))))))
