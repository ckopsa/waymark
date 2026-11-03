#!/usr/bin/env python3
"""The first-run walk (docs/spec-first-run.md R-10.2, R-10.3): SELFHOST.md's
three steps after `docker compose -f selfhost/compose.yml up -d` with no
.env, with a stub in Claude's chair.

    walk.py claim    the claim code from the engine's log; claim as the fixture's owner
    walk.py cards    the front page, card by card, until nothing waits on the owner
    walk.py submit   code-seat fires on the groom, sits, submits; the pull request opens
    walk.py mayor    the mayor's sit names the change, waiting on the merge

The cards' order is the quest's, not this script's: `cards` takes the
card the front page (GET /api/-/in-flight?holder=me) shows, with the
fixture's value or the stub's, and fails with the card's own sentence
when one does not clear. The walk counts what a person answers, pastes
and writes by hand, and fails above R-10.1's target.

The push and the pull request need the forge: a real fixture repository
on GitHub (ticket a01d303d) and FIXTURE_TOKEN, a token scoped to it.
Without it `submit` ends at code-seat's sit, and `mayor` is skipped.

Each stage keeps what the next reads in $WALK_STATE (a JSON file). A
failed check exits 1 with one `::error::` line naming the stage. The
stub is localfire's pull loop without Claude (spec-local-fire R-13.3,
R-13.4). Standard library only."""

import io
import json
import os
import re
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
COMPOSE = ["docker", "compose", "-f", os.path.join(HERE, "..", "compose.yml")]
ENGINE = os.environ.get("WAYMARK_URL", "http://127.0.0.1:8014")
# the fixture's two answers at the claim, and where a repository off
# GitHub is cloned from
OWNER = os.environ.get("WALK_OWNER", "tim")
REPO = os.environ["WALK_REPO"]
CLONE_URL = os.environ.get("FIXTURE_CLONE_URL", "")
# R-10.1's target: the name and the repository at the claim and the
# first ticket's send; GitHub's token and Claude's; no file
TARGET = {"answers": 3, "secrets": 2, "files": 0}
# the seed's pull link, the same id on every fresh engine
PULL_LINK = "10ca1f12-e000-4000-8000-000000000001"
STATE = os.environ.get("WALK_STATE", "walk-state.json")
FORGE_TOKEN = os.environ.get("FIXTURE_TOKEN", "")
STAGE = sys.argv[1] if len(sys.argv) > 1 else ""

AS_OWNER = {"X-Waymark-Principal": OWNER, "X-Waymark-Actor-Type": "human"}
# localfire's own headers for a seat's session
AS_CLAUDE = {"X-Waymark-Principal": "claude", "X-Waymark-Actor-Type": "agent",
             "X-Waymark-Acts-For": OWNER}


def fail(sentence):
    print(f"::error title=SELFHOST.md drifted ({STAGE})::{sentence}")
    sys.exit(1)


def say(line):
    # UTC, so the walk's steps line up with the compose logs
    stamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    print(f"{stamp} [{STAGE}] {line}", flush=True)


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


# -- the counts (R-10.3) ------------------------------------------------

def count(what, n=1):
    counts = load().get("counts") or dict.fromkeys(TARGET, 0)
    counts[what] += n
    keep(counts=counts)


def tally():
    """Print the three counts; fail when one passes R-10.1's target."""
    counts = load().get("counts") or dict.fromkeys(TARGET, 0)
    line = ", ".join(f"{counts[k]} {k} of {TARGET[k]}" for k in TARGET)
    print(f"::notice title=First run, as the walk counts it ({STAGE})::{line}", flush=True)
    over = [k for k in TARGET if counts[k] > TARGET[k]]
    if over:
        fail(f"{' and '.join(over)} passed R-10.1's target: {line}")


def runner():
    """localfire's header: the pull token the keys volume holds."""
    return {"Waymark-Runner-Token": load()["pull_token"]}


# -- claim ---------------------------------------------------------------

CLAIM_LINE = re.compile(r"claim code: (\S+)")


def claim():
    # a file written by hand: the steps leave none
    if os.path.exists(os.path.join(HERE, "..", ".env")):
        count("files")
    code, until = None, time.time() + 300
    while not code and time.time() < until:
        logs = subprocess.run(COMPOSE + ["logs", "--no-color", "engine"],
                              capture_output=True, text=True).stdout
        found = CLAIM_LINE.findall(logs)
        code = found[-1] if found else None
        if not code:
            time.sleep(5)
    if not code:
        fail("the engine's log printed no `claim code:` line in 300s")
    status, _, body = http("GET", "/api/-/in-flight?holder=me", AS_OWNER)
    if status != 503:
        fail(f"the unclaimed engine answered the front page {status}, not 503: {body}")
    # the code is read from the log; the name and the repository are answered
    status, _, body = http("POST", "/claim", {}, {"code": code, "member": OWNER, "repo": REPO})
    if status != 200:
        fail(f"the claim answered {status}: {body}")
    count("answers", 2)
    # localfire reads the pull token from the keys volume; the stub does too
    got = subprocess.run(COMPOSE + ["run", "--rm", "--no-deps", "-T", "--entrypoint", "cat",
                                    "keys", "/keys/pull.token"], capture_output=True, text=True)
    if got.returncode or not got.stdout.strip():
        fail(f"the keys volume gave no pull.token: {got.stderr.strip()[:300]}")
    keep(pull_token=got.stdout.strip())
    say(f"claimed as {OWNER}, building in {REPO}")
    no_house_orgs()
    tally()


def no_house_orgs():
    """A fresh engine serves the factory, not the house's live orgs: its
    discover answer and its home page name no vanilla_plus."""
    status, _, doc = http("GET", "/api/.well-known/waymark", AS_OWNER)
    if status != 200:
        fail(f"discover answered {status}: {doc}")
    req = urllib.request.Request(ENGINE + "/")
    for k, v in AS_OWNER.items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            home = r.read().decode()
    except urllib.error.HTTPError as e:
        fail(f"the home page answered {e.code}")
    for where, text in (("discover", json.dumps(doc)), ("the home page", home)):
        if "vanilla" in text.lower():
            fail(f"{where} names vanilla_plus: a self-hosted engine booted the house's orgs")


# -- cards ---------------------------------------------------------------

PLACE_KEY = re.compile(r"^    key:  (\S+)$", re.M)


def front_page():
    """The rows the owner's front page shows: one read (R-3.1)."""
    status, _, doc = http("GET", "/api/-/in-flight?holder=me", AS_OWNER)
    if status != 200 or not isinstance(doc, dict):
        fail(f"the front page answered {status}: {doc}")
    return [r for g in doc.get("groups") or [] for r in g.get("rows") or []]


def place_key(seat_id):
    """The key in the CLAUDE.md of the tar the mayor card's one line
    fetches (R-5.6)."""
    req = urllib.request.Request(f"{ENGINE}/api/seats/{seat_id}/-/place")
    for k, v in AS_OWNER.items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            raw = r.read()
    except urllib.error.HTTPError as e:
        fail(f"the seat's place answered {e.code}: {e.read().decode()[:300]}")
    with tarfile.open(fileobj=io.BytesIO(raw)) as tar:
        md = tar.extractfile("CLAUDE.md").read().decode()
    found = PLACE_KEY.search(md)
    if not found:
        fail("the place's CLAUDE.md carries no key")
    return found.group(1)


def take(owner, row, first):
    """One card's door, with the fixture's value or the stub's. Only a
    first take counts: a retry is the walk's, not the person's."""
    kind, rid = row["kind"], row["id"]
    if kind == "secret":
        # GitHub access: the fixture's token, or a stand-in with no forge
        owner.call("waymark_invoke", {"kind": "secret", "id": rid, "action": "replace",
                                      "return": "summary",
                                      "input": {"value": FORGE_TOKEN or "no-forge-in-ci"}})
        if first:
            count("secrets")
    elif kind == "runner_link":
        # the person gives localfire their Claude token, and localfire
        # asks the link for its fires: the stub asks in its place
        status, _, body = http("GET", f"/api/-/runner-links/{rid}/fires", runner())
        if status not in (200, 204):
            fail(f"the pull link refused its own token: {status} {body}")
        if status == 200:
            drop_fire(body)
        keep(link_id=rid)
        if first:
            count("secrets")
    elif kind == "seat":
        # the one line in a new folder, then Claude Code there: its first sit
        m = Mcp(AS_OWNER)
        m.call("waymark_sit", {"key": place_key(rid), "seat": rid, "session": m.session})
    elif kind == "ticket":
        # the first ticket's send is the person's groom (R-5.7)
        got, _ = owner.call("waymark_get", {"kind": "ticket", "id": rid, "return": "summary"})
        prio = (got.get("data") or {}).get("priority") or 3
        doc, refused = owner.call("waymark_invoke", {"kind": "ticket", "id": rid, "action": "groom",
                                                     "input": {"priority": prio},
                                                     "return": "summary"}, ok=False)
        if not refused:
            count("answers")
            keep(ticket=rid)
        elif "the-bench-holds-the-repository" in json.dumps(doc):
            enroll(CLONE_URL)
        else:
            fail(f"the first ticket's groom refused: {json.dumps(doc)[:800]}")
    else:
        fail(f"a {kind} card the walk has no value for: {row.get('summary')}")


def cards():
    owner = Mcp(AS_OWNER)
    tries = {}
    until = time.time() + 900
    while time.time() < until:
        rows = [r for r in front_page() if r.get("kind") != "quest"]
        if not rows:
            say("the front page says: Nothing waits on you.")
            if not load().get("ticket"):
                fail("the page emptied with no send of the first ticket")
            tally()
            return
        # the ticket's groom is the quest's goal: every other card first
        rows.sort(key=lambda r: r.get("kind") == "ticket")
        row = rows[0]
        rid = row["id"]
        sentence = (row.get("waiting_on") or {}).get("verdict") or row.get("summary")
        tries[rid] = tries.get(rid, 0) + 1
        if tries[rid] > 4:
            fail(f"the {row['kind']} card did not clear: {sentence}")
        if tries[rid] > 1:
            time.sleep(15)
        say(f"{row['kind']} {rid}: {sentence}")
        take(owner, row, tries[rid] == 1)
    fail("the front page did not empty in 900s")


# -- the repository ------------------------------------------------------

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


# -- submit and mayor ----------------------------------------------------

SEAT_LINE = re.compile(r"^Seat: (\S+) \(([^)]+)\)\.$", re.M)
KEY_LINE = re.compile(r"^Key: (\S+)$", re.M)


def drop_fire(fire):
    """A fire this walk does not stand in for (a cadence wake of another
    seat): left unanswered, so it starts nothing and its lease (300 s)
    keeps it off the next polls. Not answered 429: the engine reads a
    429 as a throttle on the whole link, and fires nothing through it
    for an hour, the walk's own seats' fires with the rest."""
    say(f"left fire {fire['id']} unanswered")


def peek(mcp, tool, args):
    """A read for the record, never a failure: the doc, or {"error": why}."""
    try:
        status, _, body = mcp.rpc("tools/call", {"name": tool, "arguments": args})
    except Exception as e:  # a timeout or a dropped socket
        return {"error": repr(e)}
    if status != 200 or not isinstance(body, dict) or "result" not in body:
        return {"error": f"{status}: {json.dumps(body)[:300]}"}
    res = body["result"]
    text = "".join(c.get("text", "") for c in res.get("content", []) if c.get("type") == "text")
    if res.get("isError"):
        return {"error": text[:300]}
    try:
        return json.loads(text)
    except ValueError:
        return {"error": text[:300]}


def shown(row, keys):
    """A row's state and those of `keys` its data carries, for one log line."""
    if "error" in row and "data" not in row:
        return f"unread: {row['error']}"
    data = row.get("data") or {}
    picked = {k: data[k] for k in keys if k in data}
    return json.dumps({"id": row.get("id"), "state": row.get("state"), **(picked or data)})[:800]


def dump_fire_path(mcp, link_id, seat_name):
    """What the owner reads on the way to `seat_name`'s fire, so a red run
    says which held: (a) the seat has no schedule row, (b) the pool skipped
    the link and the schedule throttled, (c) a fire queued, never handed out."""
    found = peek(mcp, "waymark_query", {"kind": "seat", "filter": {"name": seat_name},
                                        "return": "summary"})
    seat = None
    for r in items_of(found):
        row = peek(mcp, "waymark_get", {"kind": "seat", "id": r["id"], "return": "summary"})
        if (row.get("data") or {}).get("name") == seat_name:
            seat = row
            break
    if not seat:
        say(f"dump: no seat row named {seat_name} ({found.get('error', 'no match')})")
    else:
        say(f"dump: seat {shown(seat, ('model', 'runners', 'cadence_seconds'))}")
        found = peek(mcp, "waymark_query", {"kind": "schedule", "filter": {"seat": seat["id"]},
                                            "return": "summary"})
        rows = items_of(found)
        if not rows:
            say(f"dump: (a)? no schedule row for {seat_name} ({found.get('error', 'none')})")
        model = (seat.get("data") or {}).get("model")
        for r in rows:
            row = peek(mcp, "waymark_get", {"kind": "schedule", "id": r["id"], "return": "summary"})
            say(f"dump: schedule {shown(row, ('note', 'retry_after', 'last_fired_at', 'wake_pending', 'runners', 'model'))}")
            model = (row.get("data") or {}).get("model") or model
        if isinstance(model, str):
            row = peek(mcp, "waymark_get", {"kind": "model", "id": model, "return": "summary"})
            say(f"dump: model {shown(row, ('name', 'runners'))}")
    row = peek(mcp, "waymark_get", {"kind": "runner_link", "id": link_id, "return": "summary"})
    say(f"dump: pull link {shown(row, ('owner', 'puller_seen_at', 'retry_after', 'note'))}")


def next_fire(link_id, seat_name, seconds, owner):
    """Poll the pull link until a fire for `seat_name` arrives; answer it
    as a started run and return (seat id, key). With none, `owner` reads
    what held it up before the walk fails."""
    until = time.time() + seconds
    while time.time() < until:
        status, _, fire = http("GET", f"/api/-/runner-links/{link_id}/fires", runner())
        if status == 204:
            continue
        if status != 200:
            fail(f"the pull link answered {status}: {fire}")
        text = fire.get("text") or ""
        seat, key = SEAT_LINE.search(text), KEY_LINE.search(text)
        if not seat or seat.group(2) != seat_name:
            say(f"a fire for {seat.group(2) if seat else 'no seat'}; not this walk's")
            drop_fire(fire)
            continue
        if not key:
            fail(f"{seat_name}'s fire carries no Key: line")
        http("POST", f"/api/-/runner-links/{link_id}/fires/{fire['id']}", runner(),
             {"claude_code_session_id": f"stub-{fire['id']}",
              "claude_code_session_url": f"http://stub.invalid/{fire['id']}"})
        say(f"{seat_name} fired ({fire['id']})")
        return seat.group(1), key.group(1)
    dump_fire_path(owner, link_id, seat_name)
    fail(f"no fire for {seat_name} in {seconds}s")


def walked(sit, ticket):
    rows = (sit.get("walk") or {}).get("rows") or []
    return any(r.get("id") == ticket for r in rows)


def submit():
    s = load()
    link_id, tid = s.get("link_id") or PULL_LINK, s.get("ticket")
    if not tid:
        fail("the cards stage sent no first ticket")
    owner = Mcp(AS_OWNER)

    # the groom is what wakes code-seat: no fire by hand here
    seat, key = next_fire(link_id, "code-seat", 300, owner)
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
    keep(change=change)
    say(f"code-seat sits on {tid}; the bench prepared {bench['branch']} at {bench['head']}")
    if not FORGE_TOKEN:
        say("no FIXTURE_TOKEN: the push and the pull request are skipped")
        return
    if not change:
        fail(f"code-seat's sit named no change beside {tid}")
    s = dict(load(), branch=bench["branch"])
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
    m = Mcp(AS_OWNER)
    sit, _ = m.call("waymark_sit", {"key": place_key(seat["id"]), "seat": "mayor",
                                    "session": m.session})
    if s["change"] not in json.dumps(sit):
        fail(f"the mayor's sit does not name the change {s['change']}: {json.dumps(sit)[:600]}")
    row, _ = owner.call("waymark_get", {"kind": "change", "id": s["change"], "return": "summary"})
    nxt = (row.get("waiting_on") or {}).get("next") or []
    if not any(n.get("door") == "merge" and n.get("held_by") == "person" for n in nxt):
        fail(f"the change does not wait on the person's merge: {json.dumps(row.get('waiting_on'))[:600]}")
    say(f"the mayor names {s['change']} (#{s.get('pr')}), waiting on the merge")


if __name__ == "__main__":
    stages = {"claim": claim, "cards": cards, "submit": submit, "mayor": mayor}
    if STAGE not in stages:
        sys.exit(f"usage: walk.py {'|'.join(stages)}")
    stages[STAGE]()
