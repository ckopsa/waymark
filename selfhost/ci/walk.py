#!/usr/bin/env python3
"""The self-host acceptance walk (ticket ea4a9447): what tests.yml's
`selfhost` job does after `docker compose -f selfhost/compose.yml up -d`,
in SELFHOST.md's own order, with a stubbed runner in Claude's chair and
no secrets.

    walk.py connect          step 3: the engine answers as the owner
    walk.py link             step 4: the seed's pull link
    walk.py enroll <url>     step 5: the fixture's policy, cloned from <url>
    walk.py ticket           step 7: file, groom as draft-judge, sit as code-seat
    walk.py submit           step 7: code-seat submits; the pull request opens
    walk.py mayor            step 7: the mayor's sit names the change, waiting on the merge

submit and mayor need the forge: a real fixture repository on GitHub
(ticket a01d303d) and FIXTURE_TOKEN, a token scoped to it. Without the
token the job skips them and says so.

Each stage keeps what the next reads in $WALK_STATE (a JSON file). A
failed check exits 1 with one `::error::` line naming the stage, so
the step that drifted from SELFHOST.md is the step that goes red.

The stub is localfire's pull loop without Claude: it asks the engine
for fires with the link's token (spec-local-fire R-13.3), answers each
as a started run (R-13.4), and does by hand what the seat's session
would: draft-judge's sit, verdict and groom, then code-seat's sit.
Standard library only."""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

# selfhost/.env, as step 2 wrote it; the job's own env wins
with open(os.path.join(os.path.dirname(__file__), "..", ".env")) as f:
    for line in f:
        k, sep, v = line.strip().partition("=")
        if sep and not k.startswith("#"):
            os.environ.setdefault(k, v)

ENGINE = os.environ.get("WAYMARK_URL", "http://127.0.0.1:8014")
OWNER = os.environ["WAYMARK_OWNER"]
REPO = os.environ["WAYMARK_REPO"]
PULL_TOKEN = os.environ["WAYMARK_PULL_TOKEN"]
STATE = os.environ.get("WALK_STATE", "walk-state.json")
FORGE_TOKEN = os.environ.get("FIXTURE_TOKEN", "")
STAGE = sys.argv[1] if len(sys.argv) > 1 else ""

AS_OWNER = {"X-Waymark-Principal": OWNER, "X-Waymark-Actor-Type": "human"}
# localfire.edn's own headers (SELFHOST.md step 4)
AS_CLAUDE = {"X-Waymark-Principal": "claude", "X-Waymark-Actor-Type": "agent",
             "X-Waymark-Acts-For": OWNER}


def fail(sentence):
    print(f"::error title=SELFHOST.md drifted ({STAGE})::{sentence}")
    sys.exit(1)


def say(line):
    print(f"[{STAGE}] {line}", flush=True)


def load():
    try:
        with open(STATE) as f:
            return json.load(f)
    except FileNotFoundError:
        return {}


def keep(**kv):
    s = load()
    s.update(kv)
    with open(STATE, "w") as f:
        json.dump(s, f)


def http(method, path, headers, body=None, timeout=40):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(ENGINE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")
    for k, v in headers.items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return r.status, dict(r.headers), (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, dict(e.headers), json.loads(raw)
        except ValueError:
            return e.code, dict(e.headers), raw


class Mcp:
    """One MCP session on the engine's Streamable HTTP door, as one
    identity. The session id is what waymark_sit binds."""

    def __init__(self, headers, session=None):
        self.headers = dict(headers)
        self.n = 0
        if session:
            # an earlier stage's session, and the sitting bound to it
            self.session = session
            self.headers["Mcp-Session-Id"] = session
            return
        status, hs, body = self.rpc("initialize", {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "selfhost-walk", "version": "1"}})
        sid = next((v for k, v in hs.items() if k.lower() == "mcp-session-id"), None)
        if status != 200 or not sid:
            fail(f"initialize answered {status} with no Mcp-Session-Id: {body}")
        self.session = sid
        self.headers["Mcp-Session-Id"] = sid
        self.rpc("notifications/initialized", None, note=True)

    def rpc(self, method, params, note=False):
        self.n += 1
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        if not note:
            msg["id"] = self.n
        return http("POST", "/api/-/mcp", self.headers, msg, timeout=120)

    def call(self, tool, args, ok=True):
        status, _, body = self.rpc("tools/call", {"name": tool, "arguments": args})
        if status != 200 or not isinstance(body, dict) or "result" not in body:
            fail(f"{tool} answered {status}: {json.dumps(body)[:800]}")
        res = body["result"]
        text = "".join(c.get("text", "") for c in res.get("content", []) if c.get("type") == "text")
        try:
            doc = json.loads(text)
        except ValueError:
            doc = {"text": text}
        if res.get("isError") and ok:
            fail(f"{tool} {json.dumps(args)[:300]} refused: {text[:800]}")
        return doc, bool(res.get("isError"))


def github(path):
    """GET on GitHub's API with the fixture's token → the parsed body."""
    req = urllib.request.Request("https://api.github.com" + path)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Authorization", f"Bearer {FORGE_TOKEN}")
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        fail(f"GitHub answered {e.code} for GET {path}: {e.read().decode()[:300]}")


def items_of(doc):
    for k in ("items", "rows"):
        if isinstance(doc.get(k), list):
            return doc[k]
    return []


def named(mcp, kind, name):
    """The one row of `kind` whose data.name is `name`."""
    doc, refused = mcp.call("waymark_query", {"kind": kind, "filter": {"name": name},
                                              "return": "summary"}, ok=False)
    rows = [] if refused else items_of(doc)
    if not rows:
        doc, _ = mcp.call("waymark_query", {"kind": kind, "page_size": 100, "return": "summary"})
        rows = items_of(doc)
    for r in rows:
        row, _ = mcp.call("waymark_get", {"kind": kind, "id": r["id"], "return": "summary"})
        if (row.get("data") or {}).get("name") == name:
            return row
    fail(f"no {kind} named {name}")


# -- step 3 ------------------------------------------------------------

def connect():
    mcp = Mcp(AS_OWNER)
    doc, _ = mcp.call("waymark_discover", {})
    text = json.dumps(doc)
    who = doc.get("principal") or doc.get("you") or doc.get("whoami")
    if OWNER not in json.dumps(who if who is not None else text):
        fail(f"waymark_discover did not resolve to the owner {OWNER}: {json.dumps(who)[:300]}")
    missing = [k for k in ("ticket", "seat", "runner_link") if f'"{k}"' not in text]
    if missing:
        fail(f"waymark_discover lists no {', '.join(missing)}")
    say(f"discover answers as {OWNER} and lists ticket, seat and runner_link")


# -- step 4 ------------------------------------------------------------

def link():
    mcp = Mcp(AS_OWNER)
    # delivery is no filterable field of runner_link: read every link, keep the pull one
    doc, _ = mcp.call("waymark_query", {"kind": "runner_link", "page_size": 100,
                                        "return": "summary"})
    rows = []
    for r in items_of(doc):
        row, _ = mcp.call("waymark_get", {"kind": "runner_link", "id": r["id"], "return": "summary"})
        if (row.get("data") or {}).get("delivery") == "pull":
            rows.append(row)
    if len(rows) != 1:
        fail(f"runner_link with delivery pull answered {len(rows)} rows, not one")
    link_id = rows[0]["id"]
    # the stub's first poll is localfire's: the token the seed gave the link
    status, _, body = http("GET", f"/api/-/runner-links/{link_id}/fires",
                           {"Waymark-Runner-Token": PULL_TOKEN})
    if status not in (200, 204):
        fail(f"the pull link refused its own token: {status} {body}")
    if status == 200:
        refuse_fire(link_id, body)
    keep(link_id=link_id)
    say(f"pull link {link_id} answers its token")


# -- step 5 ------------------------------------------------------------

def enroll(clone_url):
    # empty is a GitHub repository: the bench clones github.com/<REPO>
    clone_url = clone_url or None
    mcp = Mcp(AS_OWNER)
    bench = named(mcp, "mcp_server", "bench")
    if bench.get("state") != "live":
        say(f"the bench row is {bench.get('state')}; mark_live")
        mcp.call("waymark_invoke", {"kind": "mcp_server", "id": bench["id"],
                                    "action": "mark_live", "return": "summary"})
    doc, _ = mcp.call("waymark_query", {"kind": "repo_policy", "filter": {"repository": REPO},
                                        "return": "summary"})
    rows = items_of(doc)
    if len(rows) != 1:
        fail(f"the seed made {len(rows)} repo_policy rows for {REPO}, not one")
    policy = rows[0]["id"]
    # the seed's policy, restated with the clone_url a repository off
    # GitHub needs; the restate enrols it on the bench again
    mcp.call("waymark_invoke", {"kind": "repo_policy", "id": policy, "action": "restate",
                                "return": "summary",
                                "input": {"repository": REPO, "clone_url": clone_url,
                                          "required_checks": ["tests"],
                                          "test": {"workflow": "tests.yml", "input": "only"},
                                          "orientation": "docs/orientation.md"}})
    row, _ = mcp.call("waymark_get", {"kind": "repo_policy", "id": policy, "return": "summary"})
    data = row.get("data") or {}
    if not data.get("enrolled_at") or data.get("note"):
        fail(f"the bench did not enrol {clone_url}: {data.get('note')}")
    say(f"{REPO} enrolled from {clone_url or 'GitHub'}")


# -- step 7 ------------------------------------------------------------

SEAT_LINE = re.compile(r"^Seat: (\S+) \(([^)]+)\)\.$", re.M)
KEY_LINE = re.compile(r"^Key: (\S+)$", re.M)


def refuse_fire(link_id, fire):
    """A fire this walk does not stand in for (a cadence wake of another
    seat): answered as a throttle, so it waits and starts nothing."""
    http("POST", f"/api/-/runner-links/{link_id}/fires/{fire['id']}",
         {"Waymark-Runner-Token": PULL_TOKEN}, {"status": 429, "retry_after": 3600})


def next_fire(link_id, seat_name, seconds):
    """Poll the pull link until a fire for `seat_name` arrives; answer it
    as a started run and return (seat id, key)."""
    until = time.time() + seconds
    while time.time() < until:
        status, _, fire = http("GET", f"/api/-/runner-links/{link_id}/fires",
                               {"Waymark-Runner-Token": PULL_TOKEN})
        if status == 204:
            continue
        if status != 200:
            fail(f"the pull link answered {status}: {fire}")
        text = fire.get("text") or ""
        seat, key = SEAT_LINE.search(text), KEY_LINE.search(text)
        if not seat or seat.group(2) != seat_name:
            say(f"a fire for {seat.group(2) if seat else 'no seat'}; not this walk's")
            refuse_fire(link_id, fire)
            continue
        if not key:
            fail(f"{seat_name}'s fire carries no Key: line")
        http("POST", f"/api/-/runner-links/{link_id}/fires/{fire['id']}",
             {"Waymark-Runner-Token": PULL_TOKEN},
             {"claude_code_session_id": f"stub-{fire['id']}",
              "claude_code_session_url": f"http://stub.invalid/{fire['id']}"})
        say(f"{seat_name} fired ({fire['id']})")
        return seat.group(1), key.group(1)
    fail(f"no fire for {seat_name} in {seconds}s")


def walked(sit, ticket):
    rows = (sit.get("walk") or {}).get("rows") or []
    return any(r.get("id") == ticket for r in rows)


def ticket():
    link_id = load()["link_id"]
    owner = Mcp(AS_OWNER)
    row, _ = owner.call("waymark_invoke", {
        "kind": "ticket", "action": "create", "return": "summary",
        "input": {"title": "Say hello in the README",
                  "detail": "Add one line to README.md that says hello.",
                  "type": "feature", "priority": 3, "repo": REPO}})
    tid = row.get("id")
    if not tid or row.get("state") != "draft":
        fail(f"the ticket was not filed as a draft: {json.dumps(row)[:400]}")
    say(f"ticket {tid} filed, draft")

    # draft-judge wakes on a create after its settle (30 minutes); the
    # owner's fire is the same wake without the wait
    judge = named(owner, "seat", "draft-judge")
    owner.call("waymark_invoke", {"kind": "seat", "id": judge["id"], "action": "fire",
                                  "input": {"text": tid}, "return": "summary"})
    seat, key = next_fire(link_id, "draft-judge", 180)
    s = Mcp(AS_CLAUDE)
    sit, _ = s.call("waymark_sit", {"key": key, "seat": seat, "session": s.session})
    if not walked(sit, tid):
        fail(f"draft-judge's sit does not offer {tid}: {json.dumps(sit.get('walk'))[:600]}")
    s.call("waymark_invoke", {"kind": "verdict", "action": "judge", "return": "summary",
                              "input": {"subject_kind": "ticket", "subject_id": tid,
                                        "verdict": "ready",
                                        "remedy": "A one-line README change on a path the fixture uses."}})
    groomed, _ = s.call("waymark_invoke", {"kind": "ticket", "id": tid, "action": "groom",
                                           "input": {"priority": 3}, "return": "summary"})
    if groomed.get("state") != "open":
        fail(f"the groom left the ticket {groomed.get('state')}")
    say(f"draft-judge groomed {tid}: open")

    # the groom is what wakes code-seat: no fire by hand here
    seat, key = next_fire(link_id, "code-seat", 300)
    c = Mcp(AS_CLAUDE)
    sit, _ = c.call("waymark_sit", {"key": key, "seat": seat, "session": c.session})
    if not walked(sit, tid):
        fail(f"code-seat's sit does not offer {tid}: {json.dumps(sit.get('walk'))[:600]}")
    bench = sit.get("bench") or {}
    head = os.environ.get("FIXTURE_HEAD")
    if bench.get("repo") != REPO or not bench.get("branch") or not bench.get("head"):
        fail(f"code-seat's sit prepared no worktree: {json.dumps(bench)[:600]}")
    if head and bench["head"] != head:
        fail(f"the worktree is at {bench['head']}, not the fixture's {head}")
    change = (sit.get("change") or {}).get("id")
    keep(ticket=tid, session=c.session, branch=bench["branch"], change=change)
    say(f"code-seat sits on {tid}; the bench prepared {bench['branch']} at {bench['head']}")


def submit():
    s = load()
    if not s.get("change"):
        fail(f"code-seat's sit named no change beside {s.get('ticket')}")
    c = Mcp(AS_CLAUDE, session=s["session"])
    # the one-line change, through the bench as the seat's session makes it
    doc, _ = c.call("waymark_power", {"tool": "bench__read",
                                     "arguments": {"path": "README.md", "limit": 1}})
    lines = (doc.get("result") or {}).get("lines") or []
    if not lines or not lines[0].get("text"):
        fail(f"bench__read gave no first line of README.md: {json.dumps(doc)[:400]}")
    first = lines[0]["text"]
    c.call("waymark_power", {"tool": "bench__edit", "arguments": {
        "path": "README.md", "old": first, "new": first + "\n\nHello.",
        "why": "Say hello in the README, as the walk's ticket asks."}})
    row, _ = c.call("waymark_invoke", {"kind": "change", "id": s["change"], "action": "submit",
                                       "return": "summary",
                                       "input": {"why": "Say hello in the README, as the ticket asks."}})
    if row.get("state") != "submitted":
        fail(f"the submit left the change {row.get('state')}: {json.dumps(row)[:400]}")
    say(f"code-seat submitted {s['change']}")
    owner = REPO.split("/")[0]
    until = time.time() + 300
    while time.time() < until:
        pulls = github(f"/repos/{REPO}/pulls?state=open&head={owner}:{s['branch']}")
        if pulls:
            keep(pr=pulls[0]["number"])
            say(f"{REPO}#{pulls[0]['number']} is open from {s['branch']}")
            return
        time.sleep(10)
    fail(f"the push of {s['branch']} opened no pull request on {REPO} in 300s")


def mayor():
    s = load()
    owner = Mcp(AS_OWNER)
    seat = named(owner, "seat", "mayor")
    key = (seat.get("data") or {}).get("sitter_key")
    if not key:
        fail("the owner reads no sitter_key on the mayor seat (SELFHOST.md step 6)")
    m = Mcp(AS_OWNER)
    sit, _ = m.call("waymark_sit", {"key": key, "seat": "mayor", "session": m.session})
    if s["change"] not in json.dumps(sit):
        fail(f"the mayor's sit does not name the change {s['change']}: {json.dumps(sit)[:600]}")
    row, _ = owner.call("waymark_get", {"kind": "change", "id": s["change"], "return": "summary"})
    nxt = (row.get("waiting_on") or {}).get("next") or []
    if not any(n.get("door") == "merge" and n.get("held_by") == "person" for n in nxt):
        fail(f"the change does not wait on the person's merge: {json.dumps(row.get('waiting_on'))[:600]}")
    say(f"the mayor names {s['change']} (#{s.get('pr')}), waiting on the merge")


if __name__ == "__main__":
    stages = {"connect": connect, "link": link, "ticket": ticket,
              "submit": submit, "mayor": mayor,
              "enroll": lambda: enroll(sys.argv[2])}
    if STAGE not in stages:
        sys.exit(f"usage: walk.py {'|'.join(stages)}")
    stages[STAGE]()
