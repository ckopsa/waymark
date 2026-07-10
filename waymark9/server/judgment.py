"""The judgment overlay (design 9.0 §1): the row's law judges the row.

7.0 §3 wrote "writes are judged by the row's law" and it held by
prohibition — the pilot gate refused judgment diffs, so two live
revisions could never disagree about a guard. 8.0 stored every
expression guard's verdict as a tree the fingerprint can read back.
This module is the mechanism that retires the prohibition: the three
paths that apply judgment to a row — render's probe, the out-of-state
concealment probe, and the invoker's enforcement loop — resolve the
row's guards through :func:`resolve_action`, which substitutes a
non-resident revision's stored trees for the resident declaration's.

Structure mirrors the derived-law overlay (``server/derived.py``)
exactly, one level up: where ``LawOverride`` carries a fact's stored
parameters, a judgment override carries a guard's stored verdict — the
``when`` tree, the ``vars`` expression garnish, ``explain``,
``remedies``, ``hide``, ``severity``, ``requires_token`` — rebuilt into
an ordinary :func:`~..core.guards.guard.expr` guard, so evaluation,
reason rendering, and the E1 warning protocol are the same code paths
resident guards use. What a fingerprint cannot hold rides the resident
guard at the same position: ``becomes_available_at`` is a callable, so
structured hope evaluates resident (the recorded deviation — v7 #6's
family, confined to scheduling garnish).

Substitution is positional AND name-checked: an override applies only
to the resident guard at the same index with the same name. Across a
code-or-shape grandfather the lists may differ — a mismatch means the
resident guard serves, which is exactly v8's behavior, never worse.

Two stores per ``ResourceDef``, installed and dropped by
``definitions.py`` at the same sites that manage the derived overlay:

- ``judgment_served`` — the CURRENT revision's overrides, while a
  judgment proposal is held or piloted (the resident objects are the
  NEW law; the current one serves from the store);
- ``judgment_laws`` — revision number → overrides, one entry per
  non-resident revision with rows still living under it
  (grandfathered laws; a parameter-served pilot across a reboot).
"""
from __future__ import annotations

import logging
from dataclasses import replace as dc_replace
from typing import Any

from ..core.actions import ActionDef
from ..core.guards import Guard

log = logging.getLogger("waymark9.judgment")

# {action name: {guard index: raw fingerprint guard entry}} — the wire
# half, carried on KindRevise; reconstruction happens where the resident
# machine is at hand (build_overlay)
RawJudgment = dict[str, dict[int, dict[str, Any]]]
# {action name: {guard index: rebuilt Guard}} — the resolved half,
# stored on the rdef
JudgmentOverlay = dict[str, dict[int, Guard]]


def judgment_raw(fp: dict[str, Any]) -> RawJudgment:
    """Every recoverable judgment in one revision's fingerprint: the
    top-level expression-guard entries, by action and position. A
    ``check`` hash, a composite (``all``/``any``), a Relation — anything
    without a stored tree — is not recoverable and is simply absent:
    the resident guard serves for it (design §2's boundary)."""
    out: RawJudgment = {}
    actions = (fp.get("machine") or {}).get("actions") or {}
    for name, adef in actions.items():
        per: dict[int, dict[str, Any]] = {}
        for i, entry in enumerate(adef.get("guards") or ()):
            if isinstance(entry, dict) and entry.get("expr") is not None:
                per[i] = entry
        if per:
            out[name] = per
    return out


def _rebuild(entry: dict[str, Any], resident: Guard) -> Guard | None:
    """One stored judgment, made an ordinary guard again — the
    ``from_wire`` seam, applied to verdicts. A stored entry that cannot
    be read back does not crash the boot: the resident guard serves and
    the log says so (a law served approximately is named, never
    silent)."""
    from ..core.expr import from_wire
    from ..core.guards import guard as factory

    try:
        return factory.expr(
            when=from_wire(entry["expr"]),
            explain=entry.get("explain") or resident.explain,
            vars={k: from_wire(v)
                  for k, v in (entry.get("vars_exprs") or {}).items()},
            severity=entry.get("severity") or "refuse",
            hide=bool(entry.get("hide")),
            remedies=tuple(entry.get("remedies") or ()),
            requires_token=entry.get("requires_token"),
            name=entry.get("name"),
            becomes_available_at=resident.becomes_available_at,
        )
    except Exception:
        log.warning("stored judgment for guard %r could not be rebuilt; "
                    "the resident guard serves", entry.get("name"),
                    exc_info=True)
        return None


def build_overlay(rdef: Any, raw: RawJudgment | None) -> JudgmentOverlay:
    """Reconstruct one revision's judgments against the resident machine.
    Positional + name matching: where the lists diverged (a
    code-or-shape grandfather), the mismatched position keeps its
    resident guard — the pre-9.0 behavior, recorded as the deviation."""
    out: JudgmentOverlay = {}
    for name, per in (raw or {}).items():
        defn = rdef.machine.actions.get(name)
        if defn is None:
            continue
        built: dict[int, Guard] = {}
        for i, entry in per.items():
            i = int(i)
            if i >= len(defn.guards):
                continue
            resident = defn.guards[i]
            if entry.get("name") != resident.name:
                continue
            g = _rebuild(entry, resident)
            if g is not None:
                built[i] = g
        if built:
            out[name] = built
    return out


def _select(rdef: Any, revision: Any) -> JudgmentOverlay | None:
    """Which overlay judges a row under ``revision`` — the
    ``specs_for`` resolution order, verbatim: the per-revision store
    first (a grandfathered law, or a parameter-served pilot whose code
    is no longer resident), then piloted-resident-verbatim, then the
    served law's overlay (installed while a hold or pilot keeps newer
    code resident), then nothing (the resident declaration IS the
    law)."""
    if isinstance(revision, int):
        per = (getattr(rdef, "judgment_laws", None) or {}).get(revision)
        if per is not None:
            return per
        if revision == getattr(rdef, "piloted_law_revision", None):
            return None
    return getattr(rdef, "judgment_served", None) or None


def resolve_action(rdef: Any, defn: ActionDef, revision: Any) -> ActionDef:
    """THE per-row judgment seam (design §1): the ActionDef whose guards
    are the row's law's. Returns ``defn`` untouched when nothing
    overlays — the common case costs two attribute reads. Everything
    else about the action (safety, display, input schema, handler) is
    resident by construction: the §2 gate refuses to let those differ
    between live revisions."""
    per_action = _select(rdef, revision)
    if not per_action:
        return defn
    per = per_action.get(defn.name)
    if not per:
        return defn
    guards = tuple(per.get(i, g) for i, g in enumerate(defn.guards))
    return dc_replace(defn, guards=guards)
