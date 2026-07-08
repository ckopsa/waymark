"""The generic conformance suite. Knows Waymark, not meal plans.

Collected when pytest runs with ``--waymark7``. Parametrized per resource ×
state × principal-profile from the enrolled-resource registry; states are
reached by the derived machine walker unless a ``@state_factory`` overrides
it (design §9).

2.0 additions: token-prose and orientation checks ship in the box (design
§10 — the suite already renders every state and provokes every refusal, so
asserting the prose is honest is cheap), the draft tests exercise the draft
sub-resource envelope, and the migration round-trip proves the emitted SQL
builds the declared schema (design §8).
"""
from __future__ import annotations

import re
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


async def make(wm7: Any, kind: str, state: str) -> Any:
    try:
        instance = await reg.make_state(kind, state, wm7.engine)
    except reg.SkipState as e:
        pytest.skip(f"cannot produce {kind}:{state}: {e}")
    assert instance.state == state, (
        f"state factory dishonesty: asked for {kind}:{state}, "
        f"got {instance.state!r}")
    return instance


async def fetch(wm7: Any, kind: str, id: str, pname: str) -> dict[str, Any]:
    rdef = wm7.registry[kind]
    res = await wm7.client.get(f"{wm7.base}/{rdef.plural}/{id}", headers=H(pname))
    assert res.status_code == 200, res.text
    return res.json()


async def build_input(wm7: Any, kind: str, defn: ActionDef,
                      instance: Any = None) -> dict[str, Any] | None:
    """Registered example → schema sample ∩ declared acceptance sets. The
    same declarations that tighten the rendered form feed the suite."""
    try:
        return await reg.synthesize_input(wm7.engine, wm7.registry[kind],
                                          defn, instance)
    except reg.SkipState as e:
        pytest.skip(str(e))


def needs_example_msg(kind: str, defn: ActionDef) -> str:
    return (f"action {kind}.{defn.name} refused synthesized input — a guard "
            f"judges beyond its declarations; register "
            f"@example_input({kind!r}-resource, {defn.name!r}) in conftest")


def fail_if_needs_example(res: Any, kind: str, defn: ActionDef) -> None:
    """Synthesized input can be schema-valid yet semantically refused (409)
    or fail stricter Pydantic parsing (422). Both mean the action needs a
    registered example — and probably an acceptance-set declaration."""
    if res.status_code in (409, 422) and defn.input is not None \
            and reg._EXAMPLES.get((kind, defn.name)) is None:
        pytest.fail(needs_example_msg(kind, defn))


async def post(wm7: Any, doc: dict[str, Any], defn: ActionDef, pname: str,
               body: dict[str, Any] | None, *, idem_key: str | None = None,
               etag: str | None = None, dry_run: bool = False) -> Any:
    href = f"{doc['self']}/-/{defn.name}" + ("?dry_run=1" if dry_run else "")
    headers = H(pname)
    if defn.safety.fence:
        headers["If-Match"] = etag or doc["meta"]["etag"]
    if not defn.safety.idempotent:
        headers["Idempotency-Key"] = idem_key or uuid.uuid4().hex
    return await wm7.client.post(href, json=body, headers=headers)


async def storage_version(wm7: Any, kind: str, id: str) -> int:
    async with wm7.storage.session() as s:
        loaded = await wm7.storage.load(s, kind, id)
    return loaded.version


async def transition_count(wm7: Any) -> int:
    from sqlalchemy import func, select

    async with wm7.storage.session() as s:
        return (await s.execute(
            select(func.count()).select_from(wm7.storage.transitions))).scalar_one()


async def principals_with(wm7: Any, kind: str, instance_state: str,
                          action: str, where: str) -> list[tuple[str, Any, dict]]:
    """(principal, fresh instance, doc) for each profile advertising the
    action under ``where`` ('actions' or 'unavailable')."""
    out = []
    for pname in sorted(wm7.principals):
        instance = await make(wm7, kind, instance_state)
        doc = await fetch(wm7, kind, instance.id, pname)
        if action in (doc[where] or {}):
            out.append((pname, instance, doc))
    return out


def _draft_advert(doc: dict[str, Any], defn: ActionDef) -> dict[str, Any] | None:
    """The draft advert for an action: on the entry (unplaced) or on the
    first part entry (placed)."""
    if defn.place is None:
        return (doc["actions"].get(defn.name) or {}).get("draft")
    for group in (doc.get("parts") or {}).values():
        for item in group["items"]:
            entry = item["actions"].get(defn.name)
            if entry and "draft" in entry:
                return entry["draft"]
    return None


# ── Representation ──────────────────────────────────────────────────────
async def test_representation(wm7, wm3_case):
    kind, state, pname = wm3_case
    instance = await make(wm7, kind, state)
    doc = await fetch(wm7, kind, instance.id, pname)

    missing = ENVELOPE_KEYS - set(doc)
    assert not missing, f"envelope missing required keys: {missing}"
    assert doc["waymark"] == "7"
    assert doc["kind"] == kind
    assert doc["state"] == state
    assert doc["summary"].strip(), "summary must be non-empty"
    assert len(doc["summary"]) <= 140, "summary exceeds 140-char budget"

    import jsonschema

    schema_res = await wm7.client.get(f"{wm7.base}/schemas/{kind}")
    assert schema_res.status_code == 200
    jsonschema.validate(doc["data"], schema_res.json())

    assert doc["meta"]["version"] == await storage_version(wm7, kind, instance.id)
    assert doc["meta"]["etag"].endswith(f'v{doc["meta"]["version"]}"')

    # every rendered action carries its computed demand class (design §10)
    for name, entry in doc["actions"].items():
        assert entry.get("effort") in ("assent", "selection", "recall",
                                       "composition"), \
            f"action {name} carries no demand class"


# ── Affordance completeness ─────────────────────────────────────────────
async def test_affordance_completeness(wm7, wm3_case):
    kind, state, pname = wm3_case
    rdef: ResourceDef = wm7.registry[kind]
    instance = await make(wm7, kind, state)
    doc = await fetch(wm7, kind, instance.id, pname)

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
        body = await build_input(wm7, kind, defn, instance)
        res = await post(wm7, doc, defn, pname, body)
        assert res.status_code == 404, \
            f"hidden action {name} must return 404, got {res.status_code}"


# ── Prose honesty (design §10: v1's planned token_prose, in the box) ────
_UUIDISH = re.compile(
    r"\b[0-9a-f]{32}\b|\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b",
    re.I)
_SNAKE_TOKEN = re.compile(r"\b[a-z0-9]+(?:_[a-z0-9]+)+\b")
_PLACEHOLDER = re.compile(r"\{[a-z_][a-z0-9_.]*\}", re.I)


def _assert_prose(where: str, text: str) -> None:
    assert not _UUIDISH.search(text), \
        f"{where} renders a raw id into prose: {text!r}"
    assert not _PLACEHOLDER.search(text), \
        f"{where} contains an unresolved placeholder: {text!r}"
    assert not _SNAKE_TOKEN.search(text), \
        f"{where} leaks a machine token into prose: {text!r}"


async def test_token_prose(wm7, wm3_case):
    """Human-facing strings must not contain UUIDs, machine tokens, or
    unresolved placeholders — a token shown where a name was known is a
    usability defect. The suite renders every state and provokes every
    refusal; this holds the prose to account."""
    kind, state, pname = wm3_case
    rdef = wm7.registry[kind]
    instance = await make(wm7, kind, state)
    doc = await fetch(wm7, kind, instance.id, pname)

    _assert_prose(f"{kind}:{state} summary", doc["summary"])
    for name, entry in doc["unavailable"].items():
        _assert_prose(f"{kind}:{state} unavailable[{name}].reason",
                      entry["reason"])
    # provoked refusals: a guard-refused 409's detail is the same prose
    for name, defn in rdef.machine.actions.items():
        if defn.bulk or name in doc["actions"] or name not in doc["unavailable"]:
            continue
        body = await build_input(wm7, kind, defn, instance)
        res = await post(wm7, doc, defn, pname, body)
        if res.status_code == 409:
            _assert_prose(f"{kind}.{name} refusal detail",
                          res.json().get("detail", ""))


async def test_orientation(wm7, wm3_kind):
    """The summary must orient, not identify (v1's planned rule 3.2): no id
    interpolation in the template, and summaries must differ across states —
    a summary that never changes isn't summarizing."""
    kind = wm3_kind
    rdef = wm7.registry[kind]
    template = rdef.summary_template
    assert "{id" not in template and "_id}" not in template.replace(
        "{id}", ""), (
        f"{kind} summary template interpolates an id — reference the "
        "referent's denormalized name instead")

    seen: dict[str, str] = {}
    for state in rdef.machine.states:
        try:
            instance = await reg.make_state(kind, state, wm7.engine)
        except reg.SkipState:
            continue
        doc = await fetch(wm7, kind, instance.id, sorted(wm7.principals)[0])
        seen[state] = doc["summary"]
    if len(seen) >= 2:
        values = list(seen.values())
        assert len(set(values)) > 1, (
            f"{kind} summary is identical across states {sorted(seen)} — "
            "it identifies but does not orient")


# ── Transition truth ────────────────────────────────────────────────────
async def test_transition_truth_available(wm7, wm3_action_case):
    kind, state, action = wm3_action_case
    rdef = wm7.registry[kind]
    defn = rdef.machine.actions[action]
    cases = await principals_with(wm7, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")

    for pname, instance, doc in cases:
        body = await build_input(wm7, kind, defn, instance)
        res = await post(wm7, doc, defn, pname, body)
        fail_if_needs_example(res, kind, defn)
        assert res.status_code == 200, \
            f"advertised action {action} refused for {pname}: {res.text}"
        after = res.json()
        assert after["state"] == defn.to, \
            f"effect.to promised {defn.to}, landed in {after['state']}"
        assert after["meta"]["version"] == doc["meta"]["version"] + 1

        async with wm7.storage.session() as s:
            last = await wm7.storage.last_transition(s, kind, instance.id)
        assert last.action == action
        assert last.from_state == state and last.to_state == defn.to
        assert last.actor_id == wm7.principals[pname].id
        assert last.actor_type == wm7.principals[pname].type

        refetched = await fetch(wm7, kind, instance.id, pname)
        for field in ("state", "data", "meta", "actions"):
            assert refetched[field] == after[field], \
                "response is not the post-transition document"


async def test_transition_truth_unavailable(wm7, wm3_action_case):
    kind, state, action = wm3_action_case
    rdef = wm7.registry[kind]
    defn = rdef.machine.actions[action]
    cases = await principals_with(wm7, kind, state, action, "unavailable")
    if not cases:
        pytest.skip(f"{action} not advertised unavailable in {state}")

    for pname, instance, doc in cases:
        body = await build_input(wm7, kind, defn, instance)
        version_before = doc["meta"]["version"]
        res = await post(wm7, doc, defn, pname, body)
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


async def test_touch_truth(wm7, wm3_action_case):
    """Declared touches (design E8) tell the truth twice: the advertised
    entry carries the declaration's wire form, and the log's correlated
    same-actor rows are exactly the declared touches (non-``may`` ones
    must occur; undeclared ones cannot — the enforcer guarantees it, this
    observes it). ``Delegated`` sets verify nothing here — the resource's
    data is the declaration there."""
    from ..core.touches import Advances, Creates, Delegated
    from ..core.owns import owns_of

    kind, state, action = wm3_action_case
    rdef = wm7.registry[kind]
    defn = rdef.machine.actions[action]
    if not defn.touches:
        pytest.skip("action declares no touches")
    if any(isinstance(t, Delegated) for t in defn.touches):
        pytest.skip("delegated touch set: the resource's data declares")
    cases = await principals_with(wm7, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")

    pname, instance, doc = cases[0]
    assert doc["actions"][action]["effect"].get("touches") \
        == [t.to_wire() for t in defn.touches], \
        "the entry's effect must advertise the declared touches"
    body = await build_input(wm7, kind, defn, instance)
    res = await post(wm7, doc, defn, pname, body)
    fail_if_needs_example(res, kind, defn)
    assert res.status_code == 200, res.text

    async with wm7.storage.session() as s:
        root = await wm7.storage.last_transition(s, kind, instance.id)
        story = await wm7.storage.transitions_by_correlation(
            s, root.correlation_id)
    actor = wm7.principals[pname].id
    mine = [t for t in story if t.actor_id == actor
            and not (t.kind == kind and t.resource_id == instance.id)]
    # seeded descendants of created kinds are the child's own declaration
    seeded = set()
    for t in defn.touches:
        if isinstance(t, Creates):
            created = wm7.registry.get(t.kind)
            for edge in owns_of(created.cls) if created else ():
                if edge.seed is not None:
                    seeded.add((edge.kind, "create"))
    declared = {("create", t.kind) if isinstance(t, Creates)
                else (t.action, t.kind) for t in defn.touches}
    observed = {(t.action, t.kind) for t in mine
                if (t.kind, t.action) not in seeded}
    assert observed <= declared, (
        f"undeclared touches reached the log: {observed - declared}")
    required = {("create", t.kind) if isinstance(t, Creates)
                else (t.action, t.kind)
                for t in defn.touches if not t.may}
    assert required <= observed, (
        f"declared touches never happened: {required - observed} — "
        "an advertised touch that cannot occur on a walked instance "
        "needs may=True or a @state_factory that arranges it")


# ── Safety truth ────────────────────────────────────────────────────────
async def test_safety_idempotent_double_invoke(wm7, wm3_action_case):
    kind, state, action = wm3_action_case
    defn = wm7.registry[kind].machine.actions[action]
    if not defn.safety.idempotent:
        pytest.skip("action is not idempotent")
    cases = await principals_with(wm7, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")

    pname, instance, doc = cases[0]
    body = await build_input(wm7, kind, defn, instance)
    first = await post(wm7, doc, defn, pname, body)
    fail_if_needs_example(first, kind, defn)
    assert first.status_code == 200
    v1 = first.json()["meta"]["version"]

    second = await post(wm7, doc, defn, pname, body,
                        etag=first.json()["meta"]["etag"])
    assert second.status_code == 200, \
        f"idempotent double-invoke must succeed: {second.text}"
    assert second.json()["meta"]["version"] == v1, \
        "second identical invoke advanced the version again"


async def test_safety_non_idempotent_key_discipline(wm7, wm3_action_case):
    kind, state, action = wm3_action_case
    defn = wm7.registry[kind].machine.actions[action]
    if defn.safety.idempotent:
        pytest.skip("action is idempotent")
    cases = await principals_with(wm7, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")

    pname, instance, doc = cases[0]
    body = await build_input(wm7, kind, defn, instance)

    bare = await wm7.client.post(
        f"{doc['self']}/-/{action}", json=body,
        headers=H(pname, **({"If-Match": doc["meta"]["etag"]}
                            if defn.safety.fence else {})))
    assert bare.status_code == 428, \
        f"non-idempotent action without Idempotency-Key must 428, got {bare.status_code}"

    key = uuid.uuid4().hex
    first = await post(wm7, doc, defn, pname, body, idem_key=key)
    fail_if_needs_example(first, kind, defn)
    assert first.status_code == 200, first.text

    replay = await post(wm7, doc, defn, pname, body, idem_key=key)
    assert replay.status_code == first.status_code
    assert replay.content == first.content, \
        "same key + same body must replay byte-for-byte"

    if defn.input is not None:
        mutated = {**(body or {}), "$conformance": "different"}
        reuse = await post(wm7, doc, defn, pname, mutated, idem_key=key)
        assert reuse.status_code == 409, \
            f"key reuse with different body must 409, got {reuse.status_code}"


async def test_safety_if_match(wm7, wm3_action_case):
    kind, state, action = wm3_action_case
    defn = wm7.registry[kind].machine.actions[action]
    if not defn.safety.fence:
        pytest.skip("action does not require If-Match")
    cases = await principals_with(wm7, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")

    pname, instance, doc = cases[0]
    body = await build_input(wm7, kind, defn, instance)
    stale = await post(wm7, doc, defn, pname, body, etag='W/"conformance-stale"')
    assert stale.status_code == 412, \
        f"stale If-Match must 412, got {stale.status_code}"
    problem = stale.json()
    assert problem.get("resource", {}).get("meta", {}).get("etag") == \
        doc["meta"]["etag"], "412 must carry a fresh representation"


async def test_safety_reversible(wm7, wm3_action_case):
    kind, state, action = wm3_action_case
    defn = wm7.registry[kind].machine.actions[action]
    if not defn.safety.reversible:
        pytest.skip("action does not claim reversibility")
    cases = await principals_with(wm7, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")

    pname, instance, doc = cases[0]
    body = await build_input(wm7, kind, defn, instance)
    res = await post(wm7, doc, defn, pname, body)
    assert res.status_code == 200, res.text
    after = res.json()
    back = [n for n, entry in (after["actions"] or {}).items()
            if entry.get("effect", {}).get("to") == state]
    assert back, (f"{action} claims reversible=True but the post-transition "
                  f"document offers no way back to {state!r}")


# ── Input contract ──────────────────────────────────────────────────────
async def test_input_contract(wm7, wm3_action_case):
    kind, state, action = wm3_action_case
    rdef = wm7.registry[kind]
    defn = rdef.machine.actions[action]
    cases = await principals_with(wm7, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")
    pname, instance, doc = cases[0]

    if defn.input is not None:
        junk = await post(wm7, doc, defn, pname, {"__conformance_bogus__": 1})
        assert junk.status_code == 422, \
            f"additionalProperties must be rejected with 422, got {junk.status_code}"
        problem = junk.json()
        assert problem.get("errors"), "422 must carry per-field errors"

    body = await build_input(wm7, kind, defn, instance)
    v_before = await storage_version(wm7, kind, instance.id)
    t_before = await transition_count(wm7)
    dry = await post(wm7, doc, defn, pname, body, dry_run=True)
    fail_if_needs_example(dry, kind, defn)
    assert dry.status_code == 200, f"dry_run of valid input failed: {dry.text}"
    assert dry.json() == {"valid": True}
    assert await storage_version(wm7, kind, instance.id) == v_before, \
        "dry_run changed the resource version"
    assert await transition_count(wm7) == t_before, \
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


async def test_schema_guard_gap(wm7, wm3_action_case):
    """Error prevention: the *rendered* input schema must not offer values
    the server will refuse.

    2.0 sharpens v1's fuzzer: acceptance sets are declarations
    (``accepts``), so a guard refusing anything drawn from its own
    advertisement is definitionally lying; and the closure rule means every
    judged field carries *some* constraint — this test measures whether the
    constraint is honest (a schema-valid sample should mostly pass pure
    single-field guards)."""
    kind, state, action = wm3_action_case
    defn = wm7.registry[kind].machine.actions[action]
    if defn.input is None or defn.bulk:
        pytest.skip("no input schema to gap-check")
    relations = [g for top in defn.guards for g in top.iter_leaves()
                 if g.is_relation]
    derivable = [
        g for top in defn.guards for g in top.iter_leaves()
        if not g.is_relation
        and (g.accepts is not None
             or (not g.reads_ctx and len(g.judges) == 1 and g.open is None))]
    if not derivable and not relations:
        pytest.skip("no document-derivable guards")
    cases = await principals_with(wm7, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")
    pname, instance, doc = cases[0]

    entry = doc["actions"][action]
    samples = _fuzz_schema(entry["input"], n=30)
    parsed = []
    for sample in samples:
        try:
            parsed.append(defn.input.model_validate(sample))
        except Exception:
            continue  # schema-valid but fails stricter pydantic parsing
    if derivable and len(parsed) < 5:
        pytest.skip("could not draw enough parseable samples from the schema")

    from ..core.types import Deny

    tallies: list[int] = []
    # an engine-wired ctx (reader/finder/services) so reads-declaring guards
    # and their acceptance sets evaluate exactly as the resolve path does
    async with wm7.storage.session() as s:
        ctx = wm7.engine.invoker._ctx(wm7.principals[pname], s, mode="dry_run")
        for g in derivable:
            denies = 0
            for inp in parsed:
                verdict, _ = await g.evaluate(instance, inp, ctx)
                denies += isinstance(verdict, Deny)
            tallies.append(denies)

        # Relations advertise *tuples* (design §5): a schema-random pairing
        # of judged fields is not the relation's advertisement — its
        # admitted set is, and refusing a member of that set is the
        # definitional lie this test exists to catch.
        for g in relations:
            admitted = await g.admitted(instance, ctx) or []
            checked = rel_denies = 0
            for i, tup in enumerate(list(admitted)[:30]):
                base = dict(samples[i % len(samples)]) if samples else {}
                base.update(dict(zip(g.judges, tup)))
                try:
                    inp = defn.input.model_validate(base)
                except Exception:
                    continue
                verdict, _ = await g.evaluate(instance, inp, ctx)
                rel_denies += isinstance(verdict, Deny)
                checked += 1
            if checked:
                assert rel_denies == 0, (
                    f"relation {g.name!r} on {kind}.{action} refused "
                    f"{rel_denies}/{checked} tuples drawn from its own "
                    "admitted set — the per-binding enums offer pairings "
                    "the enforcement denies")

    for g, denies in zip(derivable, tallies):
        if g.accepts is not None:
            assert denies == 0, (
                f"guard {g.name!r} on {kind}.{action} refused {denies}/"
                f"{len(parsed)} inputs drawn from its own accepts "
                "advertisement — the rendered enum offers values the guard "
                "denies")
        else:
            rate = denies / len(parsed)
            assert rate <= 0.5, (
                f"schema-guard gap on {kind}.{action}: guard {g.name!r} "
                f"refused {rate:.0%} of schema-valid inputs using only the "
                "document — the advertised schema is mostly dead ends. "
                "Declare accepts= on the guard or tighten the field's schema")


# ── Usability: prefill truth and the draft sub-resource ─────────────────
async def test_prefill_truth(wm7, wm3_action_case):
    """Editing is not re-authoring: a prefilled field's rendered ``default``
    must equal the document's current value — a stale or invented default is
    an advertisement lying about the resource."""
    kind, state, action = wm3_action_case
    defn = wm7.registry[kind].machine.actions[action]
    if not defn.prefill:
        pytest.skip("action declares no prefill")
    cases = await principals_with(wm7, kind, state, action, "actions")
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


async def test_draft_protection(wm7, wm3_action_case):
    """Declared effort must not be losable (design §4): the draft is a
    sub-resource with an envelope — saved through one write path, versioned
    per field, scoped to its declared audience, consumed by the invoke."""
    kind, state, action = wm3_action_case
    defn = wm7.registry[kind].machine.actions[action]
    if not defn.draft:
        pytest.skip("action declares no draft")
    cases = await principals_with(wm7, kind, state, action, "actions")
    if not cases:
        pytest.skip(f"{action} not executable in {state} for any profile")
    pname, instance, doc = cases[0]
    advert = _draft_advert(doc, defn)
    assert advert and advert.get("href"), \
        "a draftable action must advertise its draft sub-resource"
    assert "values" not in advert, \
        "the advert is a link with status, never an inlined values blob"
    shared = bool(defn.draft_policy and defn.draft_policy.shared)
    assert bool(advert.get("shared")) == shared
    if defn.collab:
        assert advert.get("collab", {}).get("href") and \
            advert["collab"].get("protocol") == "waymark-relay/2", \
            "a live draft must advertise its relay/2 channel"

    href = advert["href"]
    empty = await wm7.client.get(href, headers=H(pname))
    assert empty.status_code == 200, empty.text
    env = empty.json()
    assert env["kind"] == "draft" and env["state"] == "open"
    assert env["data"]["values"] == {}, "an absent draft is an empty open one"
    assert {"save", "discard"} <= set(env["actions"])

    body = await build_input(wm7, kind, defn, instance)
    res = await wm7.client.put(href, json=body, headers=H(pname))
    assert res.status_code == 200, res.text
    saved = res.json()
    assert saved["data"]["values"] == body, "PUT must return the draft envelope"
    for field in body:
        assert saved["data"]["revs"].get(field, 0) >= 1, \
            f"field {field} has no revision after a save"

    fresh = await wm7.client.get(href, headers=H(pname))
    assert fresh.status_code == 200 and \
        fresh.json()["data"]["values"] == body, \
        "GET draft must return what the save stored"

    refetched = await fetch(wm7, kind, instance.id, pname)
    re_advert = _draft_advert(refetched, defn)
    assert re_advert.get("exists") is True
    assert re_advert.get("stale") is False

    for other in sorted(wm7.principals):
        if other == pname:
            continue
        other_get = await wm7.client.get(href, headers=H(other))
        assert other_get.status_code == 200
        other_values = other_get.json()["data"]["values"]
        if shared:
            assert other_values == body, \
                "a shared draft is the same truth for every collaborator"
        else:
            assert other_values == {}, \
                "a private draft must not leak to another principal"
        break

    junk = await wm7.client.put(href, json={"__not_a_field__": 1},
                                headers=H(pname))
    assert junk.status_code == 422, \
        "draft fields outside the action's schema must be rejected"

    res = await post(wm7, refetched, defn, pname, body)
    fail_if_needs_example(res, kind, defn)
    assert res.status_code == 200, res.text
    after_get = await wm7.client.get(href, headers=H(pname))
    assert after_get.status_code == 200 and \
        after_get.json()["data"]["values"] == {}, \
        "a successful invoke must consume the draft"


# ── Collection contract ─────────────────────────────────────────────────
async def test_collection_contract(wm7, wm3_kind):
    kind = wm3_kind
    rdef = wm7.registry[kind]
    cls = rdef.cls
    pname = sorted(wm7.principals)[0]
    base = f"{wm7.base}/{rdef.plural}"

    async def query(qs: str) -> dict:
        res = await wm7.client.get(f"{base}?{qs}", headers=H(pname))
        assert res.status_code == 200, res.text
        return res.json()

    # some kinds have engine-minted rows before the suite makes any (the
    # `definition` deploy history exists from boot, design §1); the
    # contract is counted over the delta, not from an assumed-empty table
    pre_ids = {i["self"].rsplit("/", 1)[-1]
               for i in (await query("page[size]=100"))["data"]["items"]}

    made = []
    for state in cls.__waymark_machine__.states:
        try:
            made.append(await reg.make_state(kind, state, wm7.engine))
        except reg.SkipState:
            continue
    if not made:
        pytest.skip("no states producible")

    all_doc = await query("page[size]=100")
    all_items = all_doc["data"]["items"]
    assert all_doc["data"]["total"] == len(pre_ids) + len(made)
    assert {i["self"].rsplit("/", 1)[-1] for i in all_items} == \
        pre_ids | {m.id for m in made}

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
                if isinstance(target, list):
                    # membership semantics (design §6): Eq on a list-valued
                    # field means "tagged with the value" — the query is one
                    # tag, never a stringified list (§7's production bug)
                    if not target:
                        continue
                    tag = target[0]
                    doc = await query(f"{field}={tag}&page[size]=100")
                    got = [i["data"].get(field)
                           for i in doc["data"]["items"]]
                    assert all(isinstance(v, list) and tag in v for v in got), \
                        f"filter {field}={tag} returned untagged items"
                    assert doc["data"]["total"] == sum(
                        1 for v in values if isinstance(v, list) and tag in v), \
                        f"filter {field}={tag} membership count wrong"
                    continue
                # the grammar speaks JSON scalars: a boolean serializes as
                # true/false, never Python's str(True) (design §7/§9 — one
                # grammar, every client, this suite included)
                wire = ("true" if target else "false") \
                    if isinstance(target, bool) else target
                doc = await query(f"{field}={wire}&page[size]=100")
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
        res = await wm7.client.get(nxt["href"], headers=H(pname))
        assert res.status_code == 200, res.text
        doc = res.json()
    assert len(seen) == len(set(seen)) == all_doc["data"]["total"], \
        "pagination must visit every row exactly once"

    # the query affordance is the machine-readable filter contract
    q = all_doc["actions"]["query"]
    assert q["method"] == "GET"
    assert q["input"]["type"] == "object"


async def test_bulk_report(wm7, wm3_kind):
    """Bulk actions evaluate guards per item and return a partial-success
    report whose refusals carry the guard's honest reasons."""
    kind = wm3_kind
    rdef = wm7.registry[kind]
    machine = rdef.cls.__waymark_machine__
    bulk_actions = [d for d in machine.actions.values() if d.bulk and not d.atomic]
    if not bulk_actions:
        pytest.skip("no bulk actions declared")

    made = []
    for state in machine.states:
        try:
            made.append(await reg.make_state(kind, state, wm7.engine))
        except reg.SkipState:
            continue
    ids = [m.id for m in made]

    for defn in bulk_actions:
        if defn.defer_over is not None and len(ids) > defn.defer_over:
            ids = ids[:defn.defer_over]
        body = dict(await build_input(wm7, kind, defn) or {})
        body["ids"] = ids
        headers = H(sorted(wm7.principals)[0])
        if not defn.safety.idempotent:
            headers["Idempotency-Key"] = uuid.uuid4().hex
        res = await wm7.client.post(f"{wm7.base}/{rdef.plural}/-/{defn.name}",
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
async def test_events(wm7, wm3_kind):
    """Executing a transition while subscribed yields exactly one
    ``transition`` event with matching fields, after commit."""
    import asyncio

    kind = wm3_kind
    rdef = wm7.registry[kind]
    machine = rdef.cls.__waymark_machine__
    instance = await make(wm7, kind, machine.initial)

    action = None
    for pname in sorted(wm7.principals):
        doc = await fetch(wm7, kind, instance.id, pname)
        for name in doc["actions"]:
            if not machine.actions[name].bulk:
                action, principal, chosen_doc = name, pname, doc
                break
        if action:
            break
    if action is None:
        pytest.skip(f"no executable action from {machine.initial} for any profile")
    defn = machine.actions[action]

    sub = wm7.engine.dispatcher.subscribe(resource=(kind, instance.id))
    try:
        body = await build_input(wm7, kind, defn, instance)
        res = await post(wm7, chosen_doc, defn, principal, body)
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
        assert record.actor_id == wm7.principals[principal].id
        # after commit: the version the event names is already readable
        assert await storage_version(wm7, kind, instance.id) >= record.version
        # exactly one event for one transition: nothing newer may follow
        await asyncio.sleep(wm7.engine.dispatcher.poll_interval + 0.5)
        while not sub.queue.empty():
            extra = sub.queue.get_nowait()
            assert extra.version < record.version, \
                f"duplicate/extra event for a single transition: {extra}"
    finally:
        wm7.engine.dispatcher.unsubscribe(sub)


# ── History keeps its own law (design §5) ───────────────────────────────
async def _all_transitions(storage: Any, kind: str) -> list[Any]:
    out: list[Any] = []
    after = 0
    while True:
        async with storage.session() as s:
            rows = await storage.transitions_since(s, after, kinds=[kind],
                                                   limit=500)
        out.extend(rows)
        if len(rows) < 500:
            return out
        after = rows[-1].id


async def _revisions_of(storage: Any, kind: str) -> dict[str, dict[str, Any]]:
    """Every stored definition revision of ``kind``, id → fingerprint."""
    async with storage.session() as s:
        rows, _ = await storage.query(
            s, "definition", filters={"target_kind": kind}, sort="revision",
            page_size=1000, page_number=1)
    return {r.id: r.data.fingerprint for r in rows}


async def replay_history(storage: Any, registry: Any, kind: str) -> int:
    """The §5 replay check: every stored transition with a non-NULL
    ``defined_by`` must (a) anchor to an existing definition revision of
    its kind, and (b) be legal — ``(action, from_state, to_state)`` —
    under THAT revision's machine, reading names forward through the
    declared rename chains (``renamed_actions``, ``renames``) when the
    anchored revision postdates them. NULL anchors are the pre-law
    horizon (migration sketch) and are skipped, never guessed at.
    Returns the number of anchored rows checked; raises AssertionError
    on the first corrupted anchor, hand-edited row, or mis-mapped
    rename."""
    from ..server.storage.postgres import ENGINE_ACTIONS, resolve_renamed

    rdef = registry[kind]
    action_renames = dict(rdef.cls.renamed_actions)
    state_renames = dict(rdef.cls.renames)
    revisions = await _revisions_of(storage, kind)
    checked = 0
    for t in await _all_transitions(storage, kind):
        if t.defined_by is None:
            continue  # pre-law: honesty about the horizon
        assert t.defined_by in revisions, (
            f"{kind} transition {t.id} ({t.action!r}) is anchored to "
            f"{t.defined_by!r}, which names no definition revision of "
            f"{kind!r} — a corrupted anchor is a conformance failure, "
            "not a mystery")
        machine = revisions[t.defined_by]["machine"]
        states = set(machine["states"])
        where = (f"{kind} transition {t.id} ({t.action!r}, "
                 f"{t.from_state!r} → {t.to_state!r}) under its anchored "
                 f"revision {t.defined_by}")
        name = t.action
        # the kind's declared create spellings (Resource.created_as,
        # design §2): read from the CURRENT class like renamed_actions —
        # the continuity vocabulary covers history written under
        # revisions that predate the declaration (a `revise` row anchors
        # to the previous law of the law, whose fingerprint cannot have
        # known the new spelling), and older boots' plain `create` rows
        # for revisions >1 stay legal because `create` never leaves the
        # engine vocabulary
        create_names = set(rdef.cls.create_action_names) | {"create"}
        known = set(machine["actions"]) | ENGINE_ACTIONS | create_names
        if name not in known:
            # the anchored revision postdates a rename: the declared
            # chain must carry the old spelling forward
            name = resolve_renamed(name, action_renames, known)
            assert name is not None, (
                f"{where}: the action is not legal and no declared "
                "rename chain reaches one")
        if name in create_names:
            assert t.from_state == "", f"{where}: create must come from ''"
            # a creation lands in the machine's initial state, or in a
            # state the CURRENT class declares as a create landing (design
            # 7.0 §1, Resource.created_in — the definition kind's
            # propose-mode rows are born `proposed`); the vocabulary lives
            # on the current class, like create_action_names above
            landings = {machine["initial"], *rdef.cls.create_state_names}
            assert resolve_renamed(t.to_state, state_renames,
                                   landings) is not None, (
                f"{where}: create must land in that revision's initial "
                f"state {machine['initial']!r} or a declared create "
                f"landing {sorted(rdef.cls.create_state_names)}")
        elif name in ENGINE_ACTIONS:
            # the engine's same-state write tails (authored sync marks)
            assert t.from_state == t.to_state and resolve_renamed(
                t.to_state, state_renames, states) is not None, (
                f"{where}: an engine bookkeeping write must hold its "
                "state, in that revision's machine")
        else:
            adef = machine["actions"][name]
            assert resolve_renamed(t.from_state, state_renames,
                                   set(adef["from"])) is not None, (
                f"{where}: from_state is not among that revision's "
                f"{adef['from']}")
            assert resolve_renamed(t.to_state, state_renames,
                                   {adef["to"]}) is not None, (
                f"{where}: to_state is not that revision's {adef['to']!r}")
        checked += 1
    return checked


async def test_replay_history(wm7, wm3_kind):
    """Conformance proves the past (design §5): produce history in every
    producible state, then prove every recorded write agreed with *its
    own* law — and that the walked engine anchored what it just wrote to
    the current law."""
    kind = wm3_kind
    machine = wm7.registry[kind].cls.__waymark_machine__
    # boot-minted rows may predate the law (the definition kind's own
    # revision 1 is honestly pre-law); the anchoring assertion covers
    # what THIS walk writes, the replay covers everything
    pre = await _all_transitions(wm7.storage, kind)
    horizon = pre[-1].id if pre else 0
    made = 0
    for state in machine.states:
        try:
            await reg.make_state(kind, state, wm7.engine)
            made += 1
        except reg.SkipState:
            continue
    if not made:
        pytest.skip("no states producible")
    fresh = [t for t in await _all_transitions(wm7.storage, kind)
             if t.id > horizon]
    assert fresh, "the walk wrote no history to replay"
    law = wm7.engine.current_law(kind)
    assert law is not None
    assert all(t.defined_by == law for t in fresh), \
        "a write under this boot must anchor to this boot's law"
    checked = await replay_history(wm7.storage, wm7.registry, kind)
    assert checked >= len(fresh)


async def test_log_prose_never_rerendered(wm7, wm3_kind):
    """Log prose is never re-rendered (design §5): the summary a feed,
    SSE frame, or webhook delivery carries is byte-identical to the
    stored row — written at write time, under the law the row is
    anchored to, staying what the actor actually saw."""
    import json as _json

    from ..server.events import event_payload

    kind = wm3_kind
    machine = wm7.registry[kind].cls.__waymark_machine__
    await make(wm7, kind, machine.initial)
    rows = await _all_transitions(wm7.storage, kind)
    assert rows, "creating an instance must have logged a transition"
    for t in rows:
        payload = event_payload(t, wm7.registry, wm7.base)
        assert payload["summary"] == t.summary, \
            "the event surface re-rendered stored log prose"
        assert payload["defined_by"] == t.defined_by, \
            "the event surface must carry the row's own anchor"
        again = event_payload(t, wm7.registry, wm7.base)
        assert _json.dumps(payload, sort_keys=True) == \
            _json.dumps(again, sort_keys=True), \
            "rendering the same row twice must be byte-identical"


# ── Migrations (design §8): the round-trip that keeps the promise ───────
async def test_migration_roundtrip(wm7):
    """The emitted initial revision, applied to an empty schema, must build
    every declared table and column — the migration path can never silently
    fall behind ``create_all``."""
    from sqlalchemy import text

    from ..server import migrate as m

    snapshot = wm7.storage.schema_snapshot()
    stmts = m.diff_statements(wm7.storage, None)

    scratch = "waymark7_conformance_migration"
    async with wm7.storage.engine.begin() as conn:
        await conn.execute(text(f'DROP SCHEMA IF EXISTS {scratch} CASCADE'))
        await conn.execute(text(f'CREATE SCHEMA {scratch}'))
    try:
        async with wm7.storage.engine.begin() as conn:
            await conn.execute(text(f'SET LOCAL search_path TO {scratch}'))
            for stmt in stmts:
                for piece in m._split_sql(stmt):
                    await conn.execute(text(piece))
            for table, spec in snapshot["tables"].items():
                rows = await conn.execute(text(
                    "SELECT column_name FROM information_schema.columns "
                    "WHERE table_schema = :s AND table_name = :t"),
                    {"s": scratch, "t": table})
                got = {r[0] for r in rows}
                want = set(spec["columns"])
                assert got == want, (
                    f"migrated {table} has columns {sorted(got)}, declared "
                    f"{sorted(want)} — the emitted revision fell behind the "
                    "declaration")
                # declared uniqueness (design E2) round-trips too
                rows = await conn.execute(text(
                    "SELECT constraint_name FROM "
                    "information_schema.table_constraints WHERE "
                    "table_schema = :s AND table_name = :t AND "
                    "constraint_type = 'UNIQUE'"),
                    {"s": scratch, "t": table})
                got_uq = {r[0] for r in rows}
                want_uq = set(spec.get("unique", {}))
                assert want_uq <= got_uq, (
                    f"migrated {table} lacks unique constraints "
                    f"{sorted(want_uq - got_uq)} — the revision fell behind "
                    "the declaration")
    finally:
        async with wm7.storage.engine.begin() as conn:
            await conn.execute(text(f'DROP SCHEMA IF EXISTS {scratch} CASCADE'))


# ── Suite integrity ─────────────────────────────────────────────────────
async def test_every_resource_is_enrolled(wm7):
    missing = [k for k in wm7.registry.kinds() if k not in reg.resources()]
    assert not missing, (
        f"resources not enrolled in conformance: {missing} — add "
        "@conformance_resource (derived walker) or @state_factory")
