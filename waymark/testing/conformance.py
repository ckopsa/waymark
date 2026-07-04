"""The generic conformance suite (Part III). Knows Waymark, not orders.

Collected when pytest runs with ``--waymark``. Parametrized per resource ×
state × principal-profile from the ``@state_factory`` registry.
"""
from __future__ import annotations

import uuid
from typing import Any

import pytest

from ..core.actions import ActionDef
from ..core.registry import ResourceDef
from . import factories as reg

pytestmark = pytest.mark.asyncio

ENVELOPE_KEYS = {"waymark", "kind", "self", "state", "summary", "data",
                 "actions", "unavailable", "links", "meta"}


def H(principal_name: str, **extra: str) -> dict[str, str]:
    return {"X-Waymark-Test-Principal": principal_name, **extra}


async def make(wm: Any, kind: str, state: str) -> Any:
    try:
        instance = await reg.make_state(kind, state, wm.engine)
    except reg.SkipState as e:
        pytest.skip(f"factory cannot produce {kind}:{state}: {e}")
    assert instance.state == state, (
        f"state factory dishonesty: asked for {kind}:{state}, "
        f"got {instance.state!r}")
    return instance


async def fetch(wm: Any, kind: str, id: str, pname: str) -> dict[str, Any]:
    rdef = wm.registry[kind]
    res = await wm.client.get(f"{wm.base}/{rdef.plural}/{id}", headers=H(pname))
    assert res.status_code == 200, res.text
    return res.json()


async def build_input(wm: Any, kind: str, defn: ActionDef) -> dict[str, Any] | None:
    if defn.input is None:
        return None
    example = await reg.example_for(kind, defn.name, wm.engine.services)
    if example is not None:
        return example
    schema = wm.registry[kind].action_schemas[defn.name][0]
    from hypothesis import HealthCheck, find, settings
    from hypothesis_jsonschema import from_schema

    return find(from_schema(schema), lambda _: True,
                settings=settings(database=None, max_examples=50,
                                  suppress_health_check=list(HealthCheck)))


def needs_example_msg(kind: str, defn: ActionDef) -> str:
    return (f"action {kind}.{defn.name} refused schema-generated input — its "
            f"guards are input-dependent; register "
            f"@example_input({kind!r}-resource, {defn.name!r}) in conftest")


def fail_if_needs_example(res: Any, kind: str, defn: ActionDef) -> None:
    """Generated input can be schema-valid yet semantically refused (409) or
    fail stricter Pydantic parsing (422, e.g. uuid formats). Both mean the
    action needs a registered example."""
    if res.status_code in (409, 422) and defn.input is not None \
            and reg._EXAMPLES.get((kind, defn.name)) is None:
        pytest.fail(needs_example_msg(kind, defn))


async def post(wm: Any, doc: dict[str, Any], defn: ActionDef, pname: str,
               body: dict[str, Any] | None, *, idem_key: str | None = None,
               etag: str | None = None, dry_run: bool = False) -> Any:
    href = f"{doc['self']}/-/{defn.name}" + ("?dry_run=1" if dry_run else "")
    headers = H(pname)
    if defn.safety.requires_if_match:
        headers["If-Match"] = etag or doc["meta"]["etag"]
    if not defn.safety.idempotent:
        headers["Idempotency-Key"] = idem_key or uuid.uuid4().hex
    return await wm.client.post(href, json=body, headers=headers)


async def storage_version(wm: Any, kind: str, id: str) -> int:
    async with wm.storage.session() as s:
        loaded = await wm.storage.load(s, kind, id)
    return loaded.version


async def transition_count(wm: Any) -> int:
    from sqlalchemy import func, select

    async with wm.storage.session() as s:
        return (await s.execute(
            select(func.count()).select_from(wm.storage.transitions))).scalar_one()


async def principals_with(wm: Any, kind: str, instance_state: str,
                          action: str, where: str) -> list[tuple[str, Any, dict]]:
    """(principal, fresh instance, doc) for each profile advertising the
    action under ``where`` ('actions' or 'unavailable')."""
    out = []
    for pname in sorted(wm.principals):
        instance = await make(wm, kind, instance_state)
        doc = await fetch(wm, kind, instance.id, pname)
        if action in (doc[where] or {}):
            out.append((pname, instance, doc))
    return out


# ── Representation ──────────────────────────────────────────────────────
async def test_representation(wm, wm_case):
    kind, state, pname = wm_case
    instance = await make(wm, kind, state)
    doc = await fetch(wm, kind, instance.id, pname)

    missing = ENVELOPE_KEYS - set(doc)
    assert not missing, f"envelope missing required keys: {missing}"
    assert doc["waymark"] == "1"
    assert doc["kind"] == kind
    assert doc["state"] == state
    assert doc["summary"].strip(), "summary must be non-empty"
    assert len(doc["summary"]) <= 140, "summary exceeds 140-char budget"

    import jsonschema

    schema_res = await wm.client.get(f"{wm.base}/schemas/{kind}")
    assert schema_res.status_code == 200
    jsonschema.validate(doc["data"], schema_res.json())

    assert doc["meta"]["version"] == await storage_version(wm, kind, instance.id)
    assert doc["meta"]["etag"].endswith(f'v{doc["meta"]["version"]}"')


# ── Affordance completeness ─────────────────────────────────────────────
async def test_affordance_completeness(wm, wm_case):
    kind, state, pname = wm_case
    rdef: ResourceDef = wm.registry[kind]
    instance = await make(wm, kind, state)
    doc = await fetch(wm, kind, instance.id, pname)

    declared = {n for n, d in rdef.machine.actions.items() if not d.bulk}
    advertised = set(doc["actions"]) | set(doc["unavailable"])
    assert advertised <= declared, \
        f"advertises undeclared transitions: {advertised - declared}"
    hidden = declared - advertised
    in_state = {d.name for d in rdef.machine.transitions_from(state) if not d.bulk}
    assert set(doc["actions"]) <= in_state, \
        "an action outside the current state's transitions is executable"

    for name, entry in doc["unavailable"].items():
        assert entry.get("reason", "").strip(), \
            f"unavailable[{name}] has no localized reason"

    # nothing falls through silently: hidden actions must 404 when invoked
    for name in hidden:
        defn = rdef.machine.actions[name]
        body = await build_input(wm, kind, defn)
        res = await post(wm, doc, defn, pname, body)
        assert res.status_code == 404, \
            f"hidden action {name} must return 404, got {res.status_code}"


# ── Transition truth ────────────────────────────────────────────────────
async def test_transition_truth_available(wm, wm_action_case):
    kind, state, action = wm_action_case
    rdef = wm.registry[kind]
    defn = rdef.machine.actions[action]
    cases = await principals_with(wm, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")

    for pname, instance, doc in cases:
        body = await build_input(wm, kind, defn)
        res = await post(wm, doc, defn, pname, body)
        fail_if_needs_example(res, kind, defn)
        assert res.status_code == 200, \
            f"advertised action {action} refused for {pname}: {res.text}"
        after = res.json()
        assert after["state"] == defn.to, \
            f"effect.to promised {defn.to}, landed in {after['state']}"
        assert after["meta"]["version"] == doc["meta"]["version"] + 1

        async with wm.storage.session() as s:
            last = await wm.storage.last_transition(s, kind, instance.id)
        assert last.action == action
        assert last.from_state == state and last.to_state == defn.to
        assert last.actor_id == wm.principals[pname].id
        assert last.actor_type == wm.principals[pname].type

        refetched = await fetch(wm, kind, instance.id, pname)
        for field in ("state", "data", "meta", "actions"):
            assert refetched[field] == after[field], \
                "response is not the post-transition document"


async def test_transition_truth_unavailable(wm, wm_action_case):
    kind, state, action = wm_action_case
    rdef = wm.registry[kind]
    defn = rdef.machine.actions[action]
    cases = await principals_with(wm, kind, state, action, "unavailable")
    if not cases:
        pytest.skip(f"{action} not advertised unavailable in {state}")

    for pname, instance, doc in cases:
        body = await build_input(wm, kind, defn)
        version_before = doc["meta"]["version"]
        res = await post(wm, doc, defn, pname, body)
        if res.status_code == 200:
            # legitimate only as an idempotent natural replay: state already
            # matches the outcome and nothing may have advanced
            assert defn.safety.idempotent and state == defn.to, \
                f"unavailable action {action} executed ({res.status_code})"
            assert res.json()["meta"]["version"] == version_before
            continue
        assert res.status_code == 409, \
            f"unavailable action must refuse with 409, got {res.status_code}: {res.text}"
        problem = res.json()
        assert problem["detail"] == doc["unavailable"][action]["reason"], (
            "enforcement disagrees with advertisement: "
            f"{problem['detail']!r} != {doc['unavailable'][action]['reason']!r}")


# ── Safety truth ────────────────────────────────────────────────────────
async def test_safety_idempotent_double_invoke(wm, wm_action_case):
    kind, state, action = wm_action_case
    defn = wm.registry[kind].machine.actions[action]
    if not defn.safety.idempotent:
        pytest.skip("action is not idempotent")
    cases = await principals_with(wm, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")

    pname, instance, doc = cases[0]
    body = await build_input(wm, kind, defn)
    first = await post(wm, doc, defn, pname, body)
    fail_if_needs_example(first, kind, defn)
    assert first.status_code == 200
    v1 = first.json()["meta"]["version"]

    second = await post(wm, doc, defn, pname, body,
                        etag=first.json()["meta"]["etag"])
    assert second.status_code == 200, \
        f"idempotent double-invoke must succeed: {second.text}"
    assert second.json()["meta"]["version"] == v1, \
        "second identical invoke advanced the version again"


async def test_safety_non_idempotent_key_discipline(wm, wm_action_case):
    kind, state, action = wm_action_case
    defn = wm.registry[kind].machine.actions[action]
    if defn.safety.idempotent:
        pytest.skip("action is idempotent")
    cases = await principals_with(wm, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")

    pname, instance, doc = cases[0]
    body = await build_input(wm, kind, defn)

    bare = await wm.client.post(
        f"{doc['self']}/-/{action}", json=body,
        headers=H(pname, **({"If-Match": doc["meta"]["etag"]}
                            if defn.safety.requires_if_match else {})))
    assert bare.status_code == 428, \
        f"non-idempotent action without Idempotency-Key must 428, got {bare.status_code}"

    key = uuid.uuid4().hex
    first = await post(wm, doc, defn, pname, body, idem_key=key)
    fail_if_needs_example(first, kind, defn)
    assert first.status_code == 200, first.text

    replay = await post(wm, doc, defn, pname, body, idem_key=key)
    assert replay.status_code == first.status_code
    assert replay.content == first.content, \
        "same key + same body must replay byte-for-byte"

    if defn.input is not None:
        mutated = {**(body or {}), "$conformance": "different"}
        reuse = await post(wm, doc, defn, pname, mutated, idem_key=key)
        assert reuse.status_code == 409, \
            f"key reuse with different body must 409, got {reuse.status_code}"


async def test_safety_if_match(wm, wm_action_case):
    kind, state, action = wm_action_case
    defn = wm.registry[kind].machine.actions[action]
    if not defn.safety.requires_if_match:
        pytest.skip("action does not require If-Match")
    cases = await principals_with(wm, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")

    pname, instance, doc = cases[0]
    body = await build_input(wm, kind, defn)
    stale = await post(wm, doc, defn, pname, body, etag='W/"conformance-stale"')
    assert stale.status_code == 412, \
        f"stale If-Match must 412, got {stale.status_code}"
    problem = stale.json()
    assert problem.get("resource", {}).get("meta", {}).get("etag") == \
        doc["meta"]["etag"], "412 must carry a fresh representation"


async def test_safety_reversible(wm, wm_action_case):
    kind, state, action = wm_action_case
    defn = wm.registry[kind].machine.actions[action]
    if not defn.safety.reversible:
        pytest.skip("action does not claim reversibility")
    cases = await principals_with(wm, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")

    pname, instance, doc = cases[0]
    body = await build_input(wm, kind, defn)
    res = await post(wm, doc, defn, pname, body)
    assert res.status_code == 200, res.text
    after = res.json()
    back = [n for n, entry in (after["actions"] or {}).items()
            if entry.get("effect", {}).get("to") == state]
    assert back, (f"{action} claims reversible=True but the post-transition "
                  f"document offers no way back to {state!r}")


# ── Input contract ──────────────────────────────────────────────────────
async def test_input_contract(wm, wm_action_case):
    kind, state, action = wm_action_case
    rdef = wm.registry[kind]
    defn = rdef.machine.actions[action]
    cases = await principals_with(wm, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")
    pname, instance, doc = cases[0]

    if defn.input is not None:
        junk = await post(wm, doc, defn, pname, {"__conformance_bogus__": 1})
        assert junk.status_code == 422, \
            f"additionalProperties must be rejected with 422, got {junk.status_code}"
        problem = junk.json()
        assert problem.get("errors"), "422 must carry per-field errors"

    body = await build_input(wm, kind, defn)
    v_before = await storage_version(wm, kind, instance.id)
    t_before = await transition_count(wm)
    dry = await post(wm, doc, defn, pname, body, dry_run=True)
    fail_if_needs_example(dry, kind, defn)
    assert dry.status_code == 200, f"dry_run of valid input failed: {dry.text}"
    assert dry.json() == {"valid": True}
    assert await storage_version(wm, kind, instance.id) == v_before, \
        "dry_run changed the resource version"
    assert await transition_count(wm) == t_before, \
        "dry_run appended a transition"


# ── Usability: the schema-guard gap ─────────────────────────────────────
def _fuzz_schema(schema: dict[str, Any], n: int) -> list[dict[str, Any]]:
    from hypothesis import HealthCheck, given, settings
    from hypothesis_jsonschema import from_schema

    out: list[dict[str, Any]] = []

    @given(from_schema(schema))
    @settings(max_examples=n, database=None, deadline=None,
              suppress_health_check=list(HealthCheck))
    def collect(value: dict[str, Any]) -> None:
        out.append(value)

    collect()
    return out


async def test_schema_guard_gap(wm, wm_action_case):
    """Error prevention (Part III usability): the *rendered* input schema must
    not offer values the server can refuse using only the document.

    Guards that never read ctx are document-derivable — their verdict is a
    pure function of (resource, input), so anything they refuse was knowable
    at render time. Fuzz the rendered schema, evaluate those guards directly:

    - a guard declaring ``admits`` must accept every input drawn from its own
      advertisement (the enum it rendered) — any deny is a lying schema;
    - an undeclared guard judging exactly one input field (the same scope the
      import-time ``open_input`` warning covers, honoring the same waive
      token) that refuses the majority of schema-valid inputs is a
      schema-guard gap: the form is mostly dead ends. Declare ``admits``,
      constrain the field, or scope the action.
    """
    kind, state, action = wm_action_case
    defn = wm.registry[kind].machine.actions[action]
    if defn.input is None or defn.bulk:
        pytest.skip("no input schema to gap-check")
    derivable = [
        g for top in defn.guards for g in top.iter_leaves()
        if g.admits is not None
        or (not g.reads_ctx and g.input_fields and len(g.input_fields) == 1
            and "open_input" not in defn.waives)]
    if not derivable:
        pytest.skip("no document-derivable guards")
    cases = await principals_with(wm, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")
    pname, instance, doc = cases[0]

    samples = _fuzz_schema(doc["actions"][action]["input"], n=30)
    parsed = []
    for sample in samples:
        try:
            parsed.append(defn.input.model_validate(sample))
        except Exception:
            continue  # schema-valid but fails stricter pydantic parsing
    if len(parsed) < 5:
        pytest.skip("could not draw enough parseable samples from the schema")

    from ..core.types import Deny

    tallies: list[int] = []
    # an engine-wired ctx (reader/finder/services) so ctx-reading guards and
    # their admits declarations evaluate exactly as the render path does
    async with wm.storage.session() as s:
        ctx = wm.engine.invoker._ctx(wm.principals[pname], s, mode="dry_run")
        for g in derivable:
            denies = 0
            for inp in parsed:
                verdict, _ = await g.evaluate(instance, inp, ctx)
                denies += isinstance(verdict, Deny)
            tallies.append(denies)
    for g, denies in zip(derivable, tallies):
        if g.admits is not None:
            assert denies == 0, (
                f"guard {g.name!r} on {kind}.{action} refused {denies}/"
                f"{len(parsed)} inputs drawn from its own admits advertisement "
                "— the rendered enum offers values the guard denies")
        else:
            rate = denies / len(parsed)
            assert rate <= 0.5, (
                f"schema-guard gap on {kind}.{action}: guard {g.name!r} "
                f"refused {rate:.0%} of schema-valid inputs using only the "
                "document — the advertised schema is mostly dead ends. "
                "Declare admits=(field, fn) on the guard, constrain the "
                "field's schema, or scope the action to the item")


# ── Usability: prefill truth and draft protection ───────────────────────
async def test_prefill_truth(wm, wm_action_case):
    """Editing is not re-authoring: a prefilled field's rendered ``default``
    must equal the document's current value — a stale or invented default is
    an advertisement lying about the resource."""
    kind, state, action = wm_action_case
    defn = wm.registry[kind].machine.actions[action]
    if not defn.prefill:
        pytest.skip("action declares no prefill")
    cases = await principals_with(wm, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")
    pname, instance, doc = cases[0]
    props = doc["actions"][action]["input"]["properties"]
    for f in defn.prefill:
        current = doc["data"].get(f)
        if current is None:
            continue  # nothing to prefill from
        assert props[f].get("default") == current, (
            f"{kind}.{action} prefills {f!r} but the rendered default "
            f"{props[f].get('default')!r} is not the document's current "
            f"value {current!r}")


async def test_draft_protection(wm, wm_action_case):
    """Declared effort must not be losable: a draft=True action persists
    partial input per principal, renders it back to its author (and only its
    author), and consumes it when the action lands."""
    kind, state, action = wm_action_case
    defn = wm.registry[kind].machine.actions[action]
    if not defn.draft:
        pytest.skip("action declares no draft")
    cases = await principals_with(wm, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")
    pname, instance, doc = cases[0]
    entry = doc["actions"][action]
    assert entry.get("draft", {}).get("href"), \
        "draft=True action must advertise its draft href"
    assert "values" not in entry["draft"], "no draft saved yet"

    href = entry["draft"]["href"]
    empty = await wm.client.get(href, headers=H(pname))
    assert empty.status_code == 204, \
        f"GET with no draft must 204, got {empty.status_code}"

    body = await build_input(wm, kind, defn)
    res = await wm.client.put(href, json=body, headers=H(pname))
    assert res.status_code == 200, res.text
    assert res.json().get("saved_at")

    # GET returns the draft's current truth — this is what a form open reads,
    # so it must reflect the save even though the page envelope predates it
    fresh = await wm.client.get(href, headers=H(pname))
    assert fresh.status_code == 200 and fresh.json()["values"] == body, \
        "GET draft must return what PUT stored"

    refetched = await fetch(wm, kind, instance.id, pname)
    d = refetched["actions"][action]["draft"]
    assert d["values"] == body, "the draft must render back to its author"
    assert d.get("stale") is False

    for other in sorted(wm.principals):
        if other == pname:
            continue
        other_doc = await fetch(wm, kind, instance.id, other)
        other_entry = (other_doc["actions"] or {}).get(action)
        if other_entry and "draft" in other_entry:
            assert "values" not in other_entry["draft"], \
                "a draft is private to its author"
        other_get = await wm.client.get(href, headers=H(other))
        assert other_get.status_code == 204, \
            "GET draft must not expose another principal's draft"
        break

    junk = await wm.client.put(entry["draft"]["href"],
                               json={"__not_a_field__": 1}, headers=H(pname))
    assert junk.status_code == 422, \
        "draft fields outside the action's schema must be rejected"

    res = await post(wm, refetched, defn, pname, body)
    fail_if_needs_example(res, kind, defn)
    assert res.status_code == 200, res.text
    after = res.json()
    if action in (after["actions"] or {}):
        assert "values" not in after["actions"][action].get("draft", {}), \
            "a successful invoke must consume the draft"


# ── Collection contract ─────────────────────────────────────────────────
async def test_collection_contract(wm, wm_kind):
    kind = wm_kind
    rdef = wm.registry[kind]
    cls = rdef.cls
    made = []
    for state in cls.__waymark_machine__.states:
        try:
            made.append(await reg.make_state(kind, state, wm.engine))
        except reg.SkipState:
            continue
    if not made:
        pytest.skip("factory produced no states")
    pname = sorted(wm.principals)[0]
    base = f"{wm.base}/{rdef.plural}"

    async def query(qs: str) -> dict:
        res = await wm.client.get(f"{base}?{qs}", headers=H(pname))
        assert res.status_code == 200, res.text
        return res.json()

    all_doc = await query("page[size]=100")
    all_items = all_doc["data"]["items"]
    assert all_doc["data"]["total"] == len(made)
    assert {i["self"].rsplit("/", 1)[-1] for i in all_items} == \
        {m.id for m in made}

    fspec = cls.filterable
    if fspec is not None:
        from ..core.resource import FilterOp

        for field, ops in fspec.fields.items():
            values = [i["state"] if field == "state" else i["data"].get(field)
                      for i in all_items]
            present = [v for v in values if v is not None]
            if not present:
                continue
            if ops & (FilterOp.EQ | FilterOp.IN):
                target = present[0]
                doc = await query(f"{field}={target}&page[size]=100")
                got = [i["state"] if field == "state" else i["data"].get(field)
                       for i in doc["data"]["items"]]
                assert all(v == target for v in got), \
                    f"filter {field}={target} returned non-matching items"
                assert doc["data"]["total"] == sum(1 for v in values if v == target), \
                    f"filter {field}={target} count wrong"
            if ops & FilterOp.RANGE:
                cut = sorted(present)[len(present) // 2]
                doc = await query(f"{field}_gte={cut}&page[size]=100")
                got = [i["data"].get(field) for i in doc["data"]["items"]]
                assert all(v is not None and v >= cut for v in got)
                assert doc["data"]["total"] == \
                    sum(1 for v in present if v >= cut)
            if ops & FilterOp.AFTER:
                cut = sorted(present)[0]
                param = field.removesuffix("_at") + "_after"
                doc = await query(f"{param}={cut}&page[size]=100")
                got = [i["data"].get(field) for i in doc["data"]["items"]]
                assert all(v is not None and v > cut for v in got)
                assert doc["data"]["total"] == sum(1 for v in present if v > cut)

    sspec = cls.sortable
    if sspec is not None:
        for field in sspec.fields:
            for prefix, reverse in (("", False), ("-", True)):
                doc = await query(f"sort={prefix}{field}&page[size]=100")
                vals = [i["data"].get(field) for i in doc["data"]["items"]]
                vals = [v for v in vals if v is not None]
                assert vals == sorted(vals, reverse=reverse), \
                    f"sort={prefix}{field} is out of order: {vals}"

    # pagination walks the full set exactly once
    seen: list[str] = []
    doc = await query("page[size]=1")
    while True:
        seen.extend(i["self"] for i in doc["data"]["items"])
        nxt = doc["links"].get("next")
        if not nxt:
            break
        res = await wm.client.get(nxt["href"], headers=H(pname))
        assert res.status_code == 200, res.text
        doc = res.json()
    assert len(seen) == len(set(seen)) == len(made), \
        "pagination must visit every row exactly once"

    # the query affordance is the machine-readable filter contract
    q = all_doc["actions"]["query"]
    assert q["method"] == "GET"
    assert q["input"]["type"] == "object"


async def test_bulk_report(wm, wm_kind):
    """Bulk actions evaluate guards per item and return a partial-success
    report whose refusals carry the guard's honest reasons (§5, §7.4)."""
    kind = wm_kind
    rdef = wm.registry[kind]
    machine = rdef.cls.__waymark_machine__
    bulk_actions = [d for d in machine.actions.values() if d.bulk and not d.atomic]
    if not bulk_actions:
        pytest.skip("no bulk actions declared")

    made = []
    for state in machine.states:
        try:
            made.append(await reg.make_state(kind, state, wm.engine))
        except reg.SkipState:
            continue
    ids = [m.id for m in made]

    for defn in bulk_actions:
        if defn.defer_over is not None and len(ids) > defn.defer_over:
            ids = ids[:defn.defer_over]
        body = dict(await build_input(wm, kind, defn) or {})
        body["ids"] = ids
        headers = H(sorted(wm.principals)[0])
        if not defn.safety.idempotent:
            headers["Idempotency-Key"] = uuid.uuid4().hex
        res = await wm.client.post(f"{wm.base}/{rdef.plural}/-/{defn.name}",
                                   json=body, headers=headers)
        fail_if_needs_example(res, kind, defn)
        assert res.status_code == 200, res.text
        report = res.json()
        assert report["kind"] == "bulk_report"
        data = report["data"]
        assert data["succeeded"] + data["refused"] + data["failed"] == len(ids)
        assert data["failed"] == 0, f"bulk items errored: {data['refusals']}"
        assert len(data["refusals"]) == data["refused"]
        for refusal in data["refusals"]:
            assert refusal["reason"].strip(), "refusal without a guard reason"
            assert refusal["self"], "refusal must name the item"
        # per-item truth: refused items did not transition
        expected_succeed = sum(1 for m in made[:len(ids)]
                               if m.state in defn.from_)
        assert data["succeeded"] <= expected_succeed or defn.safety.idempotent


# ── Events ──────────────────────────────────────────────────────────────
async def test_events(wm, wm_kind):
    """Executing a transition while subscribed yields exactly one
    ``transition`` event with matching fields, after commit (§6, Part III)."""
    import asyncio

    kind = wm_kind
    rdef = wm.registry[kind]
    machine = rdef.cls.__waymark_machine__
    instance = await make(wm, kind, machine.initial)

    action = None
    for pname in sorted(wm.principals):
        doc = await fetch(wm, kind, instance.id, pname)
        for name in doc["actions"]:
            if not machine.actions[name].bulk:
                action, principal, chosen_doc = name, pname, doc
                break
        if action:
            break
    if action is None:
        pytest.skip(f"no executable action from {machine.initial} for any profile")
    defn = machine.actions[action]

    sub = wm.engine.dispatcher.subscribe(resource=(kind, instance.id))
    try:
        body = await build_input(wm, kind, defn)
        res = await post(wm, chosen_doc, defn, principal, body)
        fail_if_needs_example(res, kind, defn)
        assert res.status_code == 200, res.text
        after = res.json()

        # at-least-once: earlier backlog (e.g. the factory's own `create`) may
        # arrive first; the invoked transition's event must arrive, once
        async def next_matching():
            while True:
                record = await sub.queue.get()
                if record.action == action and \
                        record.version == after["meta"]["version"]:
                    return record

        record = await asyncio.wait_for(next_matching(), timeout=10)
        assert (record.action, record.from_state, record.to_state) == \
            (action, machine.initial, defn.to)
        assert record.actor_id == wm.principals[principal].id
        # after commit: the version the event names is already readable
        assert await storage_version(wm, kind, instance.id) >= record.version
        # exactly one event for one transition: nothing newer may follow
        await asyncio.sleep(wm.engine.dispatcher.poll_interval + 0.5)
        while not sub.queue.empty():
            extra = sub.queue.get_nowait()
            assert extra.version < record.version, \
                f"duplicate/extra event for a single transition: {extra}"
    finally:
        wm.engine.dispatcher.unsubscribe(sub)


# ── Suite integrity ─────────────────────────────────────────────────────
async def test_every_resource_has_a_state_factory(wm):
    missing = [k for k in wm.registry.kinds() if k not in reg.factories()]
    assert not missing, (
        f"resources without @state_factory: {missing} — the conformance "
        "suite needs one per resource (Part III)")
