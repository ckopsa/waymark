"""The canonical fingerprint (design §1): the definition, as data.

A resource kind's declaration already exists as one set of objects — the
machine, the guards, the derivations, the query surface, the storage
shape — and the engine consumes exactly those objects to advertise,
enforce, compute, and store. The fingerprint is a *projection of that
same set* into a deterministic, JSON-serializable dict: not a second
description that can drift, but the first description, serialized.
Anything that changes what the engine would advertise, enforce, compute,
or store changes the hash; a deploy whose registry hashes match the
stored ones is a deploy that changed no law (§2 writes nothing for it).

What is covered, per declaration surface:

- the machine — states, initial, terminal, and every transition with its
  ``from``/``to``, safety flags (idempotent/reversible/confirm/fence,
  the confirm consequence, the one-way acknowledgment), emits, touches,
  edit/draft/place/bulk/batch declarations, compound blast radius, and
  the rendered input schema;
- every guard — name, severity, ``judges``, ``reads``, the ``explain``
  template, declared vars, remedies, hide, ``requires_token``, the
  relation op, the ``open=`` acknowledgment, and content hashes of its
  callables (``accepts``, ``check``, ``vars_fn``,
  ``becomes_available_at``). Composites keep their tree shape
  (``all``/``any``);
- every ``Derived`` — the ``over=`` inputs (own fields, child fields
  with their edge and filters, the clock), the ``fn`` source hash, the
  ``Tolerance`` literal, ``explain``/``vars``/``flips_at``;
- every ``Authored`` field — authority and ``follows`` map;
- ``When`` predicates, ``OneOf`` groups, ``unique=`` groups, ``Owns``
  edges (cascade map, rollups, seed shape), ``Vocab`` declarations,
  ``unless=`` (also present as its compiled guard), and the query
  surface (filterable ops, sortable fields, faceted set);
- the Data schema, create schema, and per-action input schemas as
  rendered — the advertisement facet, byte-for-byte;
- the storage facet: the kind's table serialized by the same per-table
  code ``schema_snapshot`` uses (``snapshot.json`` absorbed, §1);
- ``shape``/``upcasts`` (hashes), ``renames``, summary/display/labels,
  links and profiles, and the transition handlers' source hashes — a
  handler mutates data, so its text changes what the engine stores.

**Callable semantics (the accepted liberty, stated once):** callables
hash by *source text* via ``inspect.getsource``, normalized (dedent +
per-line trailing-whitespace strip). The fingerprint therefore hashes
the declaration's text: a textual edit of a lambda revises the law even
when behavior is identical. That is cheap, and it is honest — the text
is the law; review reads text, and a diff a reviewer saw is a diff the
log records. When source is unavailable (builtins, C callables), the
fallback is the qualname plus a hash of the code object's ``co_code``;
when even that is missing, the type name — stable, weaker, and rare.

**Deliberately out:** visibility is not a per-kind declaration in this
engine — it is grant *data* (already resourceful, already audited) plus
engine configuration; runtime wiring (services instances, blob stores,
tick intervals) is deployment, not law; and row contents are what the
law governs, never part of it.

Determinism is load-bearing: the same module must hash identically
across processes and pytest workers (everything is sorted or
declaration-ordered; nothing depends on ``id()``, set iteration, or
``PYTHONHASHSEED``), because §2 compares a fresh boot's hash against a
stored one and a spurious mismatch would mint a lying revision.
"""
from __future__ import annotations

import hashlib
import inspect
import json
import re
import textwrap
from enum import Enum
from typing import Any

from .actions import ActionDef
from .derived import ChildField, Clock, derived_specs
from .guards import Guard, _AllGuard, _AnyGuard
from .related import RelatedField, on_wire
from .registry import ResourceDef
from .resource import FilterOp, unique_groups
from .types import DefinitionError


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


def callable_hash(fn: Any) -> str:
    """A content hash of one callable's declaration — see the module
    docstring for the source-text semantics and the fallback chain."""
    try:
        src = inspect.getsource(fn)
    except (OSError, TypeError):
        code = getattr(fn, "__code__", None)
        qual = getattr(fn, "__qualname__", None) or type(fn).__name__
        if code is not None:
            return _sha(f"{qual}:{hashlib.sha256(code.co_code).hexdigest()}")
        return _sha(f"opaque:{qual}")
    normalized = "\n".join(
        line.rstrip() for line in textwrap.dedent(src).splitlines()).strip()
    return _sha(normalized)


def _norm(value: Any) -> Any:
    """Normalize a declaration value to JSON-serializable, deterministic
    data. Callables become content hashes; sets sort; enums take their
    value; anything unknown falls back to ``str`` (declaration surfaces
    hold plain values, so the fallback is a safety net, not a path)."""
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, dict):
        return {str(k): _norm(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_norm(v) for v in value]
    if isinstance(value, (set, frozenset)):
        return sorted(_norm(v) for v in value)
    if callable(value):
        return {"callable": callable_hash(value)}
    return str(value)


# ── guards ───────────────────────────────────────────────────────────────
def _guard_fp(g: Guard) -> dict[str, Any]:
    if isinstance(g, _AllGuard):
        return {"all": [_guard_fp(p) for p in g.parts]}
    if isinstance(g, _AnyGuard):
        return {"any": [_guard_fp(p) for p in g.parts]}
    fp: dict[str, Any] = {
        "name": g.name,
        "severity": g.severity,
        "explain": g.explain,
        "judges": list(g.judges),
        "reads": list(g.reads),
        "accepts": callable_hash(g.accepts) if g.accepts is not None else None,
        "check": callable_hash(g.check) if g.check is not None else None,
        "op": g.op,
        "open": g.open.reason if g.open is not None else None,
        "vars": sorted(g.declared_vars),
        "vars_fn": (callable_hash(g.vars_fn)
                    if g.vars_fn is not None else None),
        "hide": g.hide,
        "remedies": list(g.remedies),
        "becomes_available_at": (callable_hash(g.becomes_available_at)
                                 if g.becomes_available_at is not None
                                 else None),
        "requires_token": g.requires_token,
        "needs_input": g.needs_input,
    }
    fact = getattr(g, "fact", None)  # require(): the gated derived field
    if fact is not None:
        fp["fact"] = fact
    return fp


# ── the machine ─────────────────────────────────────────────────────────
def _safety_fp(defn: ActionDef) -> dict[str, Any]:
    s = defn.safety
    return {
        "idempotent": s.idempotent, "reversible": s.reversible,
        "confirm": s.confirm, "fence": s.fence,
        "consequence": s.consequence,
        "one_way": s.one_way.reason if s.one_way is not None else None,
    }


def _action_fp(rdef: ResourceDef, name: str, defn: ActionDef) -> dict[str, Any]:
    from .when import Field as WhenField, whens_of

    edit = None
    if defn.edit is not None:
        e = defn.edit
        edit = {"prefill": list(e.prefill), "fence": e.fence,
                "unfenced_reason": e.unfenced_reason,
                "draft": ({"shared": e.draft.shared, "live": e.draft.live}
                          if e.draft is not None else None)}
    bulk = None
    if defn.bulk_spec is not None:
        b = defn.bulk_spec
        bulk = {"atomic": b.atomic, "max_items": b.max_items,
                "defer_over": b.defer_over}
    batch = None
    if defn.batch_spec is not None:
        batch = {"atomic": defn.batch_spec.atomic,
                 "max_items": defn.batch_spec.max_items}
    compound = None
    if defn.compound is not None:
        compound = {**defn.compound.blast_radius(),
                    "defer": defn.compound.defer}
    whens = {}
    if defn.input is not None:
        for wname, w in sorted(whens_of(defn.input).items()):
            whens[wname] = {
                "left": w.left, "op": w.op,
                "right": ({"data_field": w.right.name}
                          if isinstance(w.right, WhenField) else w.right),
                "requires": list(w.requires),
                "forbids_otherwise": w.forbids_otherwise,
            }
    input_schema = None
    if defn.input is not None:
        input_schema = rdef.action_schemas.get(name, (None, b""))[0]
    return {
        "from": sorted(defn.from_),
        "to": defn.to,
        "terminal": defn.terminal,
        "safety": _safety_fp(defn),
        "emits": list(defn.emits),
        "guards": [_guard_fp(g) for g in defn.guards],
        "unless": ({"name": defn.unless.name, "fact": defn.unless.fact.name,
                    "explain": defn.unless.explain, "hide": defn.unless.hide}
                   if defn.unless is not None else None),
        "when": whens,
        "input_schema": input_schema,
        "display": _norm(dict(defn.display)),
        "field_display": _norm(dict(defn.field_display)),
        "edit": edit,
        "place": ({"array": defn.place.array, "key": defn.place.key}
                  if defn.place is not None else None),
        "bulk": bulk,
        "batch": batch,
        "compound": compound,
        "touches": [t.to_wire() for t in defn.touches],
        "waives": sorted(defn.waives),
        "handler": callable_hash(defn.handler),
        # declared input retention (design 7.0 §5): what the log keeps of
        # an act is law. Emitted only when declared — adding the mechanism
        # must not re-hash every action that never touched it.
        **({"record": "inputs"} if defn.record is not None else {}),
    }


# ── derived / authored / groups / owns / query ──────────────────────────
def _derived_fp(cls: type) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for fname, spec in derived_specs(cls.Data).items():
        over: list[Any] = []
        for inp in spec.over:
            if inp is Clock:
                over.append("@clock")
            elif isinstance(inp, ChildField):
                over.append({"child": {"kind": inp.kind, "via": inp.via,
                                       "field": inp.field,
                                       "where": _norm(dict(inp.where))}})
            elif isinstance(inp, RelatedField):
                # the predicate rides the fingerprint (design 6.0 §1): a
                # changed join is a changed law, and the fan-out direction
                # a Related edge declares is recorded where it is declared
                over.append({"related": {"kind": inp.kind,
                                         "on": on_wire(inp.on),
                                         "field": inp.field,
                                         "where": _norm(dict(inp.where))}})
            else:
                over.append(inp)
        out[fname] = {
            "over": over,
            "fn": callable_hash(spec.fn) if spec.fn is not None else None,
            "tolerance": (spec.tolerance.value
                          if spec.tolerance is not None else None),
            "explain": spec.explain,
            "vars": callable_hash(spec.vars) if spec.vars is not None else None,
            "flips_at": (callable_hash(spec.flips_at)
                         if spec.flips_at is not None else None),
        }
    return out


def _authored_fp(cls: type) -> dict[str, Any]:
    from .authored import authored_specs

    return {fname: {"by": spec.by,
                    "follows": {str(k): v for k, v in
                                sorted(spec.follows.items(),
                                       key=lambda kv: str(kv[0]))}}
            for fname, spec in authored_specs(cls.Data).items()}


def _groups_fp(cls: type) -> dict[str, Any]:
    from .groups import _item_models, groups_of

    out: dict[str, Any] = {name: g.to_wire()
                           for name, g in groups_of(cls.Data).items()}
    for dname, item in _item_models(cls.Data).items():
        for name, g in groups_of(item).items():
            out[f"{dname}[].{name}"] = g.to_wire()
    return out


def _owns_fp(cls: type) -> list[dict[str, Any]]:
    from .owns import owns_of

    out = []
    for edge in owns_of(cls):
        seed = None
        if edge.seed is not None:
            seed = {"kind": edge.seed.kind,
                    "where": _norm(dict(edge.seed.where)),
                    "copy": _norm(dict(edge.seed.copy)),
                    "defaults": _norm(dict(edge.seed.defaults)),
                    # the declared retro policy (design §4): E4's "template
                    # edits never retro-propagate" as a fingerprinted
                    # declaration with a diff class, not prose
                    "retro": edge.seed.retro.__name__}
        out.append({
            "kind": edge.kind, "via": edge.via,
            "on": dict(sorted(edge.on.items())),
            "rollups": {name: {"filters": _norm(dict(r.filters)),
                               "agg": r.agg, "of": r.of}
                        for name, r in sorted(edge.rollups.items())},
            "seed": seed,
        })
    return out


def _query_fp(cls: type) -> dict[str, Any]:
    filterable = {}
    if cls.filterable is not None:
        filterable = {field: [f.name for f in FilterOp if f in ops]
                      for field, ops in sorted(cls.filterable.fields.items())}
    sortable = None
    if cls.sortable is not None:
        sortable = {"fields": list(cls.sortable.fields),
                    "default": cls.sortable.default}
    return {"filterable": filterable, "sortable": sortable,
            "faceted": list(cls.faceted)}


def _backfill_fp(cls: type) -> dict[str, Any] | None:
    """The declared backfill deferral (design §4), as a fingerprint facet:
    deferring the catch-up changes what the engine serves after a
    redefinition, so declaring (or dropping) it is a law change."""
    from .derived import Deferred

    declared = getattr(cls, "backfill", None)
    if declared is None:
        return None
    if not isinstance(declared, Deferred):
        raise DefinitionError(
            f"{cls.__name__}.backfill must be a Deferred(...) declaration "
            "(design §4) or absent — the immediate boot-time backfill "
            "needs no declaration")
    return {"deferred": {"batch": declared.batch, "pause": declared.pause}}


def _storage_fp(rdef: ResourceDef) -> dict[str, Any] | None:
    """The storage facet — the per-table half of ``schema_snapshot``,
    reused verbatim (design §1: ``snapshot.json`` becomes one facet of
    the revision). None when the kind has no assembled table (a bare
    registry outside an engine); every booted engine has one."""
    table = rdef.row_model
    if table is None:
        return None
    # lazy: core stays importable without SQLAlchemy for definition-only
    # consumers; the boot path always has it
    from sqlalchemy.dialects import postgresql

    from ..server.storage.postgres import table_snapshot

    return table_snapshot(table, postgresql.dialect())


# ── the fingerprint ─────────────────────────────────────────────────────
def fingerprint_of(rdef: ResourceDef) -> dict[str, Any]:
    """The canonical description of one kind's declaration — see the
    module docstring for coverage and semantics."""
    from .vocab import model_vocabs

    cls = rdef.cls
    machine = rdef.machine
    raw = {
        "kind": rdef.kind,
        "plural": rdef.plural,
        "summary": cls.summary,
        "display": _norm(dict(cls.display)),
        "label_template": cls.label_template,
        "row_affordances": cls.row_affordances,
        "machine": {
            "states": list(machine.states),
            "initial": machine.initial,
            "terminal": sorted(machine.terminal),
            "allow_dead": sorted(str(s) for s in cls.allow_dead),
            "actions": {name: _action_fp(rdef, name, defn)
                        for name, defn in machine.actions.items()},
        },
        "create": {
            "schema": rdef.extra.get("create_schema"),
            "guards": [_guard_fp(g) for g in cls.create_guards],
        },
        "data_schema": rdef.data_schema,
        "derived": _derived_fp(cls),
        "backfill": _backfill_fp(cls),
        "authored": _authored_fp(cls),
        "one_of": _groups_fp(cls),
        "unique": [list(group) for group in unique_groups(cls)],
        "owns": _owns_fp(cls),
        "vocab": _norm(model_vocabs(cls.Data)),
        "query": _query_fp(cls),
        # an edge-cited link's predicate is law like its compiled href
        # (design 6.0 §1); the key is emitted only when an edge is cited,
        # so kinds that never touched the mechanism keep their hashes
        "links": [{"rel": l.rel, "kind": l.kind, "href": l.href,
                   "summary": l.summary,
                   **({"edge": {"kind": l.edge.kind,
                                "on": on_wire(l.edge.on)}}
                      if getattr(l, "edge", None) is not None else {})}
                  for l in cls.links],
        "profiles": {name: _norm(dict(p.embed))
                     for name, p in sorted(cls.profiles.items())},
        "shape": cls.shape,
        "upcasts": {str(n): callable_hash(fn)
                    for n, fn in sorted(cls.upcasts.items())},
        "renames": dict(sorted(cls.renames.items())),
        # the continuity map (design §5): the rename maps are data on the
        # revise — declaring one changes the law, and the revision's diff
        # names it, so the audit trail reads continuously across renames
        "renamed_actions": dict(sorted(cls.renamed_actions.items())),
        "renamed_fields": dict(sorted(cls.renamed_fields.items())),
        # the declared create spellings (design §2, Resource.created_as):
        # what the log may name a creation is vocabulary, and vocabulary
        # is law. Emitted only when a kind declares beyond the default —
        # adding the mechanism must not re-hash every kind that never
        # touched it.
        **({"created_as": sorted(cls.create_action_names)}
           if set(cls.create_action_names) != {"create"} else {}),
        # the declared create landings (design 7.0 §1, Resource.created_in):
        # which states a creation may be born in is machine vocabulary, and
        # vocabulary is law — same emission discipline as created_as
        **({"create_states": sorted(cls.create_state_names)}
           if cls.create_state_names else {}),
        # unconditional input retention (design 7.0 §5): a kind whose whole
        # log keeps payloads changed what the engine stores
        **({"record_inputs": True} if cls.record_inputs else {}),
        # the declared adoption policy (design 7.0 §3): whether a newer
        # current law strikes every row at once (Immediate, the default)
        # or grandfathers the living (Never). Emitted only when declared
        # beyond the default — adding the mechanism must not re-hash every
        # kind that never touched it.
        **({"adoption": cls.adoption.__name__}
           if cls.adoption.__name__ != "Immediate" else {}),
        "storage": _storage_fp(rdef),
    }
    return _norm(raw)


def surface_fingerprint(sdef: Any) -> dict[str, Any]:
    """The canonical description of one declared surface (design 6.0 §4):
    anchor, members — each with its cited link's compiled/templated href
    and, when the link cites a ``Related`` edge, the edge's predicate
    (the edges carry ``on=`` specs since Phase 1) — showcase, title, and
    attention. A surface has no rows, so there is no storage facet and
    no ``derived`` key: a surface revise never marks facts stale. The
    ``__registry__`` row is the precedent for a definition target that
    is not an ordinary kind; surfaces get the same treatment."""
    raw = {
        "surface": sdef.name,
        "anchor": sdef.anchor.kind,
        "title": sdef.title,
        "members": [
            {"rel": m.rel,
             "kind": m.target.kind,
             "href": m.link.href,
             "table": list(m.table),
             **({"edge": {"kind": m.link.edge.kind,
                          "on": on_wire(m.link.edge.on)}}
                if getattr(m.link, "edge", None) is not None else {})}
            for m in sdef.members],
        "showcase": list(sdef.showcase),
        "attention": sdef.attention,
    }
    return _norm(raw)


def canonical_json(fp: dict[str, Any]) -> str:
    return json.dumps(fp, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False)


def fingerprint_hash(fp: dict[str, Any]) -> str:
    """sha256 of the canonical (sorted-keys) JSON — THE hash §2 compares."""
    return _sha(canonical_json(fp))


# ── the diff (§2's revise payload; the class taxonomy and policies: §4) ──
# Classification of a changed declaration path into design §4's four
# classes. Rules are ordered: a guard's explain under
# machine.actions.*.guards is a judgment change, not a machine change —
# the innermost owning surface wins. The derived surface splits the same
# way: a derivation's semantic surface (fn, over, tolerance, flips_at) is
# what the maintained values were computed under, while its explain=/vars=
# are refusal-surface garnish — display, so advertisement. Unmatched
# paths default to "truth": an unclassified change is conservatively a
# change of meaning, never silently cosmetic.
_SHAPE = frozenset({"storage", "shape", "upcasts"})
_JUDGMENT = frozenset({"guards", "unless", "when", "unique", "vocab",
                       "safety", "tolerance"})
_TRUTH = frozenset({"derived", "machine", "authored", "owns", "compound",
                    "touches", "batch", "bulk", "handler", "renames",
                    "renamed_actions", "renamed_fields"})
_ADVERTISEMENT = frozenset({"display", "field_display", "summary",
                            "label_template", "explain", "links",
                            "profiles", "query", "data_schema",
                            "input_schema", "schema", "edit", "place",
                            "row_affordances", "plural"})


# the garnish leaves of a derivation's fingerprint entry: carried for the
# refusal surface's rendering, never inputs to the stored value
_DERIVED_GARNISH = frozenset({"explain", "vars"})


def classify_path(path: str) -> str:
    segments = path.split(".")
    if segments[0] == "derived" and any(
            seg in _DERIVED_GARNISH for seg in segments[2:]):
        return "advertisement"
    for family, cls in ((_SHAPE, "shape"), (_JUDGMENT, "judgment"),
                        (_TRUTH, "truth"), (_ADVERTISEMENT, "advertisement")):
        if any(seg in family for seg in segments):
            return cls
    return "truth"


def stale_facts(diff: dict[str, Any]) -> tuple[str, ...]:
    """The derived facts a revision marks **stale by definition** (design
    §4): every fact named by an added or changed path under its
    ``derived.<fact>.`` fingerprint entry, garnish excluded — the fn
    hash, the ``over=`` inputs, the ``Tolerance`` literal, ``flips_at``.
    A materialized value computed under the previous law can disagree
    with any of those; a changed ``explain=`` cannot. Removed paths count
    too (a shrunk ``over=`` is a semantic change) — a fact removed
    entirely simply no longer appears in the fresh fingerprint, which is
    the caller's filter (there is no field left to recompute)."""
    facts: set[str] = set()
    for key in ("added", "changed", "removed"):
        for entry in diff.get(key, ()):
            segments = entry["path"].split(".")
            if (segments[0] == "derived" and len(segments) >= 3
                    and not any(seg in _DERIVED_GARNISH
                                for seg in segments[2:])):
                facts.add(segments[1])
    return tuple(sorted(facts))


# ── the deploy-mode gate (design 7.0 §1): what the overlay can serve ─────
# The propose-mode hold serves the CURRENT law from stored parameters while
# the resident Python objects are the NEW law. That is honest exactly when
# every changed path is *data the engine already evaluates from stored
# declarations*: a Derived's ``Tolerance`` literal and the ``where=``
# filters on its child/related inputs. Everything else — fn/check source,
# machine shape, added/removed actions/fields/kinds, schemas, guards, even
# pure-advertisement text (which render reads from the resident objects,
# so a hold would serve new prose under an old law id) — is
# ``code_or_shape``: the resident objects ARE that law, and propose mode
# auto-promotes with a recorded marker rather than pretending to hold.
_DATA_LAW_PATH = re.compile(
    r"^derived\.[^.]+\.(?:tolerance$"
    r"|over\.\d+\.(?:child|related)\.where(?:\..+)?$)")


def classify_diff(diff: dict[str, Any]) -> str:
    """``data_law`` or ``code_or_shape`` (design 7.0 §1): may a propose-mode
    boot hold this diff at ``proposed`` behind the §1 overlay, or must it
    promote totally? Every added/removed/changed path must be overlayable
    data for the hold to be honest; an empty diff cannot reach here (the
    hash moved)."""
    paths = [entry["path"] for key in ("added", "removed", "changed")
             for entry in diff.get(key, ())]
    if paths and all(_DATA_LAW_PATH.match(p) for p in paths):
        return "data_law"
    return "code_or_shape"


def _flatten(value: Any, prefix: str, out: dict[str, Any]) -> None:
    if isinstance(value, dict) and value:
        for k, v in value.items():
            _flatten(v, f"{prefix}{k}.", out)
    elif isinstance(value, list) and value:
        for i, v in enumerate(value):
            _flatten(v, f"{prefix}{i}.", out)
    else:
        out[prefix.rstrip(".")] = (value if not isinstance(value, (dict, list))
                                   else "<empty>")


def diff_fingerprints(old: dict[str, Any],
                      new: dict[str, Any]) -> dict[str, Any]:
    """Path-level diff of two fingerprints, each path tagged with its §4
    class. List paths are positional — a reordered guard list reads as
    changed paths, which is honest: order is evaluation order."""
    a: dict[str, Any] = {}
    b: dict[str, Any] = {}
    _flatten(old, "", a)
    _flatten(new, "", b)

    def entries(paths: Any) -> list[dict[str, str]]:
        return [{"path": p, "class": classify_path(p)} for p in sorted(paths)]

    return {
        "added": entries(set(b) - set(a)),
        "removed": entries(set(a) - set(b)),
        "changed": entries(p for p in set(a) & set(b) if a[p] != b[p]),
    }
