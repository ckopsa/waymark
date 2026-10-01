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
    (is (str/includes? body "data-guided-mark"))))

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
  ;; than 50 ms apart 450 ms apart, under the long-silence cut
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "const REPLAY_BURST_MS = 50, REPLAY_BURST_GAP = 450;"))
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

(deftest ui-replay-draws-a-row-from-its-doc
  ;; docs/spec-agent-demo-walks.md §8a: a `doc` frame is kept as the
  ;; replay passes it, and the screen it names is drawn by the code that
  ;; draws a live row or collection, inert, with every request held
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "replay.docs.set(self, f.doc);"))
    (is (str/includes? body "if (doc && doc.kind) return renderReplayDoc(view, doc);"))
    (is (str/includes? body "\"data-replay-doc\": \"\", inert: \"\"});"))
    (is (str/includes? body "if (String(doc.kind).endsWith(\"_collection\")) renderCollection(screen, doc, hints);"))
    (is (str/includes? body "else renderResource(screen, doc, hints).catch(() => {});"))
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
