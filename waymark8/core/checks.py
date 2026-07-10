"""Import-time checks: fail fast, before the app serves a request.

2.0 (design §1, §3, §10): most of v1's usability *warnings* are now either
unrepresentable (their invariants moved into ``Safety``/``Edit``/``Guard``
constructors) or **errors** (the closure rule). What remains as warnings are
genuine heuristics — the waiver list is down to two tokens.

Every violation raises :class:`DefinitionError` naming the resource, the
action, and the rule. These run from ``Resource.__init_subclass__``, so a
broken definition module cannot even be imported.
"""
from __future__ import annotations

import inspect
import re
import string
import typing
import warnings
from typing import Any

from pydantic import BaseModel

from .actions import ActionDef
from .machine import StateMachine
from .refs import model_refs, ref_display
from .types import DefinitionError

_SNAKE = re.compile(r"^[a-z][a-z0-9_]*$")

WAIVABLE = frozenset({"altitude", "large_effort"})

LONG_TEXT_BUDGET = 280  # two summary budgets: beyond this, it's prose


class UsabilityWarning(UserWarning):
    """A definition is enforceable but likely to render a poor human surface."""


def run_all(cls: type, machine: StateMachine, *, allow_dead: frozenset[str],
            summary_template: str) -> None:
    check_tokens(cls, machine)
    check_reachability(cls, machine, allow_dead)
    check_terminal_no_exit(cls, machine)
    check_reversible(cls, machine)
    check_one_way(cls, machine)
    check_guard_declarations(cls, machine)
    check_guard_templates(cls, machine)
    check_create_guards(cls)
    check_closure(cls, machine)
    check_handler_signatures(cls, machine)
    check_summary_template(cls, summary_template)
    check_waive_tokens(cls, machine)
    check_place(cls, machine)
    check_edit(cls, machine)
    check_altitude(cls, machine)
    check_long_text(cls, machine)
    check_faceted(cls)
    check_oneof(cls)
    check_unique(cls)
    check_links(cls)
    check_derived(cls, machine)
    check_authored(cls, machine)
    check_unless(cls, machine)
    check_require(cls, machine)
    check_when(cls, machine)


def check_unless(cls: type, machine: StateMachine) -> None:
    """Relative visibility (design §4) reads the transition log, so the
    fact must name a transition this machine can have logged — a typo'd
    name would bar nobody, silently, forever."""
    for name, defn in machine.actions.items():
        if defn.unless is None:
            continue
        transition = defn.unless.fact.transition
        if transition not in machine.actions:
            raise _err(cls, f"action {name!r}: unless=Unless(actor_of("
                            f"{transition!r})) names no transition of this "
                            "resource — the log fact could never have an "
                            "actor")


def check_require(cls: type, machine: StateMachine) -> None:
    """``require()`` gates on a declared bool derivation (design §5): a
    non-derived field has no maintainer keeping the judged truth fresh,
    and a non-bool fact is not a gate — both are definition bugs, not
    runtime surprises. Create guards are held to the same rule (the
    inputs-and-identities wave, closing ledger6 seam #1): at create
    the fact is computed from the validated input through the same spec,
    so the spec must exist all the same.

    This is also where a ``FactRequired`` learns its owning class: with
    ``r=None`` there is no ``type(r)`` to find the Data model on, so the
    binding happens here, where the class is known — each ``require()``
    call site makes a fresh Guard, so per-class binding is safe, and an
    instance that ends up on two unrelated classes is refused."""
    from .derived import derived_specs
    from .guards import FactRequired

    data_cls = getattr(cls, "Data", None)
    specs = derived_specs(data_cls) if data_cls is not None else {}

    def _validate(where: str, g: FactRequired) -> None:
        if g.fact not in specs:
            raise _err(cls, f"{where}: require({g.fact!r}) "
                            "names no derived field of Data — only a "
                            "maintained fact can gate a transition "
                            "(design §5)")
        ann = _sans_none(data_cls.model_fields[g.fact].annotation)
        if ann is not bool:
            raise _err(cls, f"{where}: require({g.fact!r}) "
                            "gates on a non-bool derivation — a gate "
                            "judges a truth, not a value; derive the "
                            "predicate as its own bool field")
        g.bind_data(data_cls)

    for name, defn in machine.actions.items():
        for g in _leaf_guards(defn):
            if isinstance(g, FactRequired):
                _validate(f"action {name!r}", g)
    for top in getattr(cls, "create_guards", ()) or ():
        for g in top.iter_leaves():
            if isinstance(g, FactRequired):
                _validate("create guard", g)


def check_when(cls: type, machine: StateMachine) -> None:
    """Conditional demand (design §5): every When on an action's input
    model must name real fields — predicate comparands on the input (or,
    via ``Field.of``, on the resource's Data) and Optional ``requires=``
    fields, so the base branch can honestly omit them."""
    from .when import whens_of

    for name, defn in machine.actions.items():
        if defn.input is None:
            continue
        for w in whens_of(defn.input).values():
            try:
                w.validate_against(defn.input, cls.Data)
            except DefinitionError as e:
                raise _err(cls, f"action {name!r}: {e}") from None


def check_derived(cls: type, machine: StateMachine) -> None:
    """Field origins are exclusive (design §1), checked where the class is
    made: derived fields live on the Data root only; their own-field inputs
    exist; a Clock comparand is a stored date/datetime (or the declaration
    carries ``flips_at``); ``within=`` produces a bool; the dependency
    graph is acyclic; and no action's input model names a derived field —
    derived values are engine-computed, so a form that asks for one is a
    definition bug, not a runtime surprise."""
    from .derived import comparand_is_temporal, derived_specs, ordered_specs
    from .groups import _item_models

    data_cls = getattr(cls, "Data", None)
    if data_cls is None:
        return
    for dname, item in _item_models(data_cls).items():
        if derived_specs(item):
            raise _err(cls, f"data.{dname}[] declares derived fields — "
                            "derivations are document-level facts; declare "
                            "them on the Data root")
    specs = derived_specs(data_cls)
    if not specs:
        return
    ordered_specs(data_cls)  # raises on a dependency cycle
    for name, spec in specs.items():
        for inp in spec.own_inputs:
            if inp not in data_cls.model_fields:
                raise _err(cls, f"derived field {name!r}: over= names "
                                f"{inp!r}, not a Data field")
        if spec.tolerance is not None:
            ann = _sans_none(data_cls.model_fields[name].annotation)
            if ann is not bool:
                raise _err(cls, f"derived field {name!r}: within=Tolerance "
                                "produces a bool; annotate it as one")
        comparand = spec.clock_comparand
        if comparand is not None and not comparand_is_temporal(data_cls,
                                                               comparand):
            raise _err(cls, f"derived field {name!r}: Clock is compared "
                            f"against {comparand!r}, which is not a stored "
                            "date/datetime — the flip time is not "
                            "extractable; declare flips_at=lambda r: ... "
                            "(design §3)")
    for aname, defn in machine.actions.items():
        if defn.input is None:
            continue
        clash = sorted(f for f in defn.input.model_fields if f in specs)
        if clash:
            raise _err(cls, f"action {aname!r} input model names derived "
                            f"field(s) {clash} — derived values are "
                            "engine-computed; an action cannot write them "
                            "(design §1)")


def check_authored(cls: type, machine: StateMachine) -> None:
    """Field origins are exclusive (design §1, §8), checked where the class
    is made: authored fields live on the Data root only; a field is never
    both derived and authored; every ``follows=`` entry names a transition
    of this machine (a typo'd name would move nothing, silently, forever);
    and no action's input model names an authored field — authored values
    are the authority's to write, so a form that asks for one is a
    definition bug, not a runtime surprise."""
    from .authored import authored_specs
    from .derived import derived_spec
    from .groups import _item_models

    data_cls = getattr(cls, "Data", None)
    if data_cls is None:
        return
    for dname, item in _item_models(data_cls).items():
        if authored_specs(item):
            raise _err(cls, f"data.{dname}[] declares authored fields — "
                            "authority is per document-level field; declare "
                            "them on the Data root")
    specs = authored_specs(data_cls)
    if not specs:
        return
    for name, spec in specs.items():
        if derived_spec(data_cls.model_fields[name]) is not None:
            raise _err(cls, f"field {name!r} is both derived and authored — "
                            "origins are exclusive (design §1)")
        for value, transition in spec.follows.items():
            if transition not in machine.actions:
                raise _err(cls, f"authored field {name!r}: follows maps "
                                f"{value!r} to {transition!r}, which is not "
                                "an action of this resource — the sync could "
                                "never move the machine (design §8)")
    for aname, defn in machine.actions.items():
        if defn.input is None:
            continue
        clash = sorted(f for f in defn.input.model_fields if f in specs)
        if clash:
            raise _err(cls, f"action {aname!r} input model names authored "
                            f"field(s) {clash} — authored values are the "
                            "authority's to write; an action cannot take "
                            "them (design §8)")


def check_derived_edges(registry: Any) -> None:
    """Owned-children derivation inputs validate at assembly, where every
    kind is known (the ``check_owns`` discipline): the edge is declared in
    the parent's ``owns=``, the child field is real, and ``where`` filters
    run on promoted child columns — the maintainer's recompute query must
    be indexable, like every declared selector."""
    from .derived import derived_specs
    from .owns import owns_of

    for rdef in registry.defs():
        cls = rdef.cls
        specs = derived_specs(cls.Data)
        for name, spec in specs.items():
            for cf in spec.child_inputs:
                edge = next((e for e in owns_of(cls)
                             if e.kind == cf.kind and e.via == cf.via), None)
                if edge is None:
                    raise _err(cls, f"derived field {name!r} reads children "
                                    f"of {cf.kind!r} via {cf.via!r}, but no "
                                    "matching Owns edge is declared — the "
                                    "derivation rides the edge (design §2)")
                child = registry.get(cf.kind)
                if child is None:
                    raise _err(cls, f"derived field {name!r}: child kind "
                                    f"{cf.kind!r} is not registered")
                if cf.field not in ("state", "id") \
                        and cf.field not in child.cls.Data.model_fields:
                    raise _err(cls, f"derived field {name!r}: {cf.field!r} "
                                    f"is not a data field of {cf.kind!r}")
                fspec = child.cls.filterable
                child_filterable = set(fspec.fields) if fspec else set()
                for f in cf.where:
                    if f != "state" and f not in child_filterable:
                        raise _err(cls, f"derived field {name!r}: where "
                                        f"field {f!r} is not filterable on "
                                        f"{cf.kind!r} — the recompute query "
                                        "runs on promoted columns")


def _join_family(rcls: type, fname: str) -> str:
    """The comparison family of one join field's promoted column:
    ``date`` / ``date-time`` / ``number`` / ``boolean`` / ``string`` /
    ``array`` — or ``missing`` / ``unpromoted`` when there is no column
    to serve the predicate at all."""
    from .schemas import _field_types

    if fname not in rcls.Data.model_fields:
        return "missing"
    promotable: set[str] = set()
    if rcls.filterable is not None:
        promotable |= set(rcls.filterable.fields)
    if rcls.sortable is not None:
        promotable |= set(rcls.sortable.fields)
    if fname not in promotable:
        return "unpromoted"
    t = _field_types(rcls).get(fname, {})
    if t.get("format") in ("date", "date-time"):
        return t["format"]
    if t.get("type") in ("integer", "number"):
        return "number"
    return t.get("type", "string")


def _check_related_edge(registry: Any, cls: type, where: str, kind: str,
                        on: tuple) -> Any:
    """One Related predicate, held to §1's admission rules: the target is
    registered, both join fields are promoted columns on their own
    sides, and the operator is one those column types can serve. Returns
    the target's ResourceDef."""
    target = registry.get(kind)
    if target is None:
        raise _err(cls, f"{where}: Related target kind {kind!r} is not "
                        "registered on this engine")
    for cond in on:
        if cond.theirs == "id":
            # identity join (the inputs-and-identities wave): the target's
            # primary key IS the indexed column — the promoted-fields law
            # is about what storage can serve, and nothing serves better.
            # op == "==" and ours != "id" were refused at On declaration;
            # the ours side still answers to the promotion rules, because
            # the inverted map is a point lookup on OUR column.
            fam = _join_family(cls, cond.ours)
            if fam == "missing":
                raise _err(cls, f"{where}: join field {cond.ours!r} (ours) "
                                f"is not a data field of {cls.kind!r}")
            if fam == "unpromoted":
                raise _err(cls, f"{where}: join field {cond.ours!r} (ours) "
                                f"is not a promoted (filterable or "
                                f"sortable) column on {cls.kind!r} — the "
                                "identity join's reverse map is an indexed "
                                "point lookup on it (design §1)")
            if fam != "string":
                raise _err(cls, f"{where}: identity join across mismatched "
                                f"column types ({cond.ours!r} is {fam}, "
                                f"'id' is string) — join an id against a "
                                "string (ideally Ref) column")
            continue
        sides = (("ours", cond.ours, cls, cls.kind),
                 ("theirs", cond.theirs, target.cls, kind))
        families = {}
        for side, fname, rcls, rkind in sides:
            fam = _join_family(rcls, fname)
            if fam == "missing":
                raise _err(cls, f"{where}: join field {fname!r} ({side}) "
                                f"is not a data field of {rkind!r}")
            if fam == "unpromoted":
                raise _err(cls, f"{where}: join field {fname!r} ({side}) "
                                f"is not a promoted (filterable or "
                                f"sortable) column on {rkind!r} — a "
                                "predicate the storage layer cannot index "
                                "is a predicate the maintainer cannot "
                                "honor (design §1)")
            if fam == "array":
                raise _err(cls, f"{where}: join field {fname!r} ({side}) "
                                "is an array column — a Related predicate "
                                "compares scalar promoted columns")
            families[side] = fam
        ours_fam, theirs_fam = families["ours"], families["theirs"]
        if cond.op != "==":
            if ours_fam not in ("number", "date", "date-time") \
                    or ours_fam != theirs_fam:
                raise _err(cls, f"{where}: op {cond.op!r} cannot be served "
                                f"by the promoted column types "
                                f"({cond.ours!r} is {ours_fam}, "
                                f"{cond.theirs!r} is {theirs_fam}) — "
                                "ordered joins need matching numeric or "
                                "temporal columns on both sides")
        elif ours_fam != theirs_fam:
            raise _err(cls, f"{where}: equality join across mismatched "
                            f"column types ({cond.ours!r} is {ours_fam}, "
                            f"{cond.theirs!r} is {theirs_fam})")
    return target


def check_related(registry: Any) -> None:
    """Related edges validate at assembly (design 6.0 §1), where every
    kind is known — the ``check_derived_edges`` tradition: "the recompute
    query must be indexable" was already law for ``where=``. Every edge a
    consumer cites is held to the admission rules of
    ``_check_related_edge``; a ``RelatedField`` input additionally reads
    a real target field through filterable ``where=`` columns; an
    edge-cited link must
    compile onto the public range grammar (``_gte``/``_lte`` — strict
    comparisons have no query parameter, deliberately)."""
    from .derived import derived_specs
    from .related import Related

    for rdef in registry.defs():
        cls = rdef.cls
        for name, spec in derived_specs(cls.Data).items():
            for rf in spec.related_inputs:
                target = _check_related_edge(
                    registry, cls, f"derived field {name!r}", rf.kind, rf.on)
                if rf.field not in ("state", "id") \
                        and rf.field not in target.cls.Data.model_fields:
                    raise _err(cls, f"derived field {name!r}: {rf.field!r} "
                                    f"is not a data field of {rf.kind!r}")
                fspec = target.cls.filterable
                target_filterable = set(fspec.fields) if fspec else set()
                for f in rf.where:
                    if f != "state" and f not in target_filterable:
                        raise _err(cls, f"derived field {name!r}: where "
                                        f"field {f!r} is not filterable on "
                                        f"{rf.kind!r} — the recompute query "
                                        "runs on promoted columns")
        for ld in getattr(cls, "links", ()) or ():
            edge = getattr(ld, "edge", None)
            if edge is None:
                continue
            if not isinstance(edge, Related):
                raise _err(cls, f"link {ld.rel!r}: edge= takes a Related "
                                "declaration")
            _check_related_edge(registry, cls, f"link {ld.rel!r}",
                                edge.kind, edge.on)
            for cond in edge.on:
                if cond.op in ("<", ">"):
                    raise _err(cls, f"link {ld.rel!r}: op {cond.op!r} has "
                                    "no query parameter — the compiled "
                                    "href speaks the public range grammar "
                                    "(_gte/_lte); use '<=', '>=', or '=='")
                if cond.theirs == "id":
                    raise _err(cls, f"link {ld.rel!r}: theirs='id' has no "
                                    "collection query parameter — an "
                                    "identity join serves §2 facts; the "
                                    "Ref field itself already renders the "
                                    "navigable reference to the parent")


def check_derived_cycles(registry: Any) -> None:
    """Cross-kind derived-fact cycles are refused at assembly (the
    inputs-and-identities wave): with identity joins, facts can flow both
    parent→child and child→parent for the first time, so two kinds could
    each derive over the other's derived fact. The maintainer's chained
    recompute (``recompute_owners`` propagating flips) terminates exactly
    because this graph is a DAG — each hop settles a strictly deeper
    fact, and the depth is bounded by the graph's longest path. A cycle
    would flip forever, so it is unrepresentable, not throttled.

    Nodes are ``kind.field`` derived facts; edges are the derived fields
    a fact reads — own-field derivation deps, plus the *derived* target
    fields reached through ``ChildField``/``RelatedField`` inputs (the
    read field, the ``theirs`` join keys, and ``where=`` filters — each
    is a value whose flip moves this fact). Same-kind cycles through own
    inputs are already refused at import (``ordered_specs``); everything
    caught here runs through an edge."""
    from .derived import ChildField, derived_specs
    from .related import RelatedField

    all_specs = {rdef.kind: derived_specs(rdef.cls.Data)
                 for rdef in registry.defs()}
    graph: dict[tuple[str, str], set[tuple[str, str]]] = {}
    for kind, specs in all_specs.items():
        for name, spec in specs.items():
            deps = graph.setdefault((kind, name), set())
            for inp in spec.over:
                if isinstance(inp, str):
                    if inp in specs:
                        deps.add((kind, inp))
                elif isinstance(inp, (ChildField, RelatedField)):
                    tspecs = all_specs.get(inp.kind, {})
                    if inp.field in tspecs:
                        deps.add((inp.kind, inp.field))
                    for f in inp.where:
                        if f in tspecs:
                            deps.add((inp.kind, f))
                    for cond in getattr(inp, "on", ()) or ():
                        if cond.ours in specs:
                            deps.add((kind, cond.ours))
                        if cond.theirs in tspecs:
                            deps.add((inp.kind, cond.theirs))

    state: dict[tuple[str, str], int] = {}  # 1 = walking, 2 = done
    stack: list[tuple[str, str]] = []

    def visit(node: tuple[str, str]) -> None:
        state[node] = 1
        stack.append(node)
        for dep in sorted(graph.get(node, ())):
            mark = state.get(dep)
            if mark == 2:
                continue
            if mark == 1:
                loop = stack[stack.index(dep):] + [dep]
                pretty = " → ".join(f"{k}.{f}" for k, f in loop)
                raise DefinitionError(
                    f"derived facts form a cross-kind cycle: {pretty} — "
                    "a fact defined in terms of itself defines nothing, "
                    "and the maintainer's chained recompute could never "
                    "settle it (design 6.0 §2)")
            visit(dep)
        stack.pop()
        state[node] = 2

    for node in sorted(graph):
        if state.get(node) != 2:
            visit(node)


def check_unique(cls: type) -> None:
    """Declared uniqueness (design E2) is enforced on promoted columns, so
    every unique field must be filterable/sortable — and scalar: array
    membership has no single-value uniqueness to promise."""
    from .resource import unique_groups
    from .vocab import model_vocabs

    groups = unique_groups(cls)
    if not groups:
        return
    promotable: set[str] = set()
    fspec = getattr(cls, "filterable", None)
    if fspec is not None:
        promotable |= set(fspec.fields)
    sspec = getattr(cls, "sortable", None)
    if sspec is not None:
        promotable |= set(sspec.fields)
    promotable.discard("state")
    vocabs = set(model_vocabs(cls.Data))
    for fields in groups:
        if not fields:
            raise _err(cls, "unique declares an empty field group")
        for f in fields:
            if f not in cls.Data.model_fields:
                raise _err(cls, f"unique field {f!r} is not a data field")
            if f in vocabs:
                raise _err(cls, f"unique field {f!r} is a Vocab (array) — "
                                "membership has no single-value uniqueness")
            if f not in promotable:
                raise _err(cls, f"unique field {f!r} must be filterable or "
                                "sortable — uniqueness is enforced on the "
                                "promoted column")


def check_links(cls: type) -> None:
    """A link ``badge`` is scent (§4): the render reads the named field's
    current value off the instance's Data, so the declaration must name a
    real field — a typo'd badge would ride nothing, silently, forever."""
    for ld in getattr(cls, "links", ()) or ():
        if ld.badge is None:
            continue
        if ld.badge not in cls.Data.model_fields:
            raise _err(cls, f"link {ld.rel!r}: badge={ld.badge!r} is not a "
                            "data field — the badge renders the instance's "
                            "current value of that field")


def check_owns(registry: Any) -> None:
    """Ownership edges (design E4) validate at assembly, where every kind
    is known: the child exists, ``via`` is a Ref to the parent and an
    Eq-filterable (promoted) column, cascade endpoints are real actions the
    runner can drive, and rollup filters name filterable child fields."""
    from .refs import ref_meta
    from .resource import FilterOp
    from .owns import owns_of

    for rdef in registry.defs():
        cls = rdef.cls
        for edge in owns_of(cls):
            child = registry.get(edge.kind)
            if child is None:
                raise _err(cls, f"owns: child kind {edge.kind!r} is not "
                                "registered on this engine")
            via_field = child.cls.Data.model_fields.get(edge.via)
            if via_field is None:
                raise _err(cls, f"owns({edge.kind!r}): via={edge.via!r} is "
                                "not a field of the child's Data")
            meta = ref_meta(via_field)
            if meta is None or meta.kind != cls.kind:
                raise _err(cls, f"owns({edge.kind!r}): via={edge.via!r} must "
                                f"be a Ref[{cls.kind!r}] on the child")
            fspec = child.cls.filterable
            ops = fspec.fields.get(edge.via) if fspec else None
            if ops is None or not ops & FilterOp.EQ:
                raise _err(cls, f"owns({edge.kind!r}): via={edge.via!r} must "
                                "be Eq-filterable on the child — the cascade "
                                "query and rollup GROUP BY run on the "
                                "promoted column")
            for parent_action, child_action in edge.on.items():
                if parent_action not in cls.__waymark_machine__.actions:
                    raise _err(cls, f"owns({edge.kind!r}): cascade key "
                                    f"{parent_action!r} is not an action of "
                                    f"{cls.kind!r}")
                target = child.machine.actions.get(child_action)
                if target is None:
                    raise _err(cls, f"owns({edge.kind!r}): cascade target "
                                    f"{child_action!r} is not an action of "
                                    f"{edge.kind!r}")
                if target.input is not None:
                    raise _err(cls, f"owns({edge.kind!r}): cascade target "
                                    f"{child_action!r} takes input — the "
                                    "runner sends none (deferred, design E4)")
                if target.safety.fence:
                    raise _err(cls, f"owns({edge.kind!r}): cascade target "
                                    f"{child_action!r} is fenced — the "
                                    "runner holds no etag (deferred, "
                                    "design E4)")
            child_filterable = set(fspec.fields) if fspec else set()
            child_promoted = set(child_filterable)
            if child.cls.sortable is not None:
                child_promoted |= set(child.cls.sortable.fields)
            parent_params = {"sort", "state"}
            if cls.filterable is not None:
                parent_params |= set(cls.filterable.fields)
            if cls.sortable is not None:
                parent_params |= set(cls.sortable.fields)
            for rollup_name, rollup in edge.rollups.items():
                if rollup_name in parent_params:
                    # rollups become the parent collection's query params;
                    # a name collision would shadow a real filter
                    raise _err(cls, f"owns({edge.kind!r}) rollup "
                                    f"{rollup_name!r} collides with a "
                                    "parent filter/sort name")
                for f in rollup.filters:
                    if f != "state" and f not in child_filterable:
                        raise _err(cls, f"owns({edge.kind!r}) rollup "
                                        f"{rollup_name!r}: filter field "
                                        f"{f!r} is not filterable on the "
                                        "child")
                if rollup.agg == "sum":
                    if rollup.of not in child.cls.Data.model_fields:
                        raise _err(cls, f"owns({edge.kind!r}) rollup "
                                        f"{rollup_name!r}: of={rollup.of!r} "
                                        "is not a child data field")
                    if rollup.of not in child_promoted:
                        raise _err(cls, f"owns({edge.kind!r}) rollup "
                                        f"{rollup_name!r}: of={rollup.of!r} "
                                        "must be filterable or sortable — "
                                        "the SUM runs on the promoted column")
            if edge.seed is not None:
                _check_seed(registry, cls, edge, child)


def _check_seed(registry: Any, cls: type, edge: Any, child: Any) -> None:
    """Seeds (design E4): the source kind exists, its filters are
    queryable, and every copied/defaulted field is real on both sides."""
    source = registry.get(edge.seed.kind)
    if source is None:
        raise _err(cls, f"owns({edge.kind!r}) seed: source kind "
                        f"{edge.seed.kind!r} is not registered")
    sspec = source.cls.filterable
    source_filterable = set(sspec.fields) if sspec else set()
    for f in edge.seed.where:
        if f != "state" and f not in source_filterable:
            raise _err(cls, f"owns({edge.kind!r}) seed: where field {f!r} "
                            "is not filterable on the source")
    for child_field, source_field in edge.seed.copy.items():
        if child_field not in child.cls.Data.model_fields:
            raise _err(cls, f"owns({edge.kind!r}) seed: copy target "
                            f"{child_field!r} is not a child data field")
        if source_field not in source.cls.Data.model_fields:
            raise _err(cls, f"owns({edge.kind!r}) seed: copy source "
                            f"{source_field!r} is not a data field of "
                            f"{edge.seed.kind!r}")
    for child_field in edge.seed.defaults:
        if child_field not in child.cls.Data.model_fields:
            raise _err(cls, f"owns({edge.kind!r}) seed: default "
                            f"{child_field!r} is not a child data field")


def check_oneof(cls: type) -> None:
    """Every OneOf group (on the data root or a list-item model) must name
    real fields — a typo'd arm would silently enforce nothing."""
    from .groups import _item_models, groups_of

    data_cls = getattr(cls, "Data", None)
    if data_cls is None:
        return
    for group in groups_of(data_cls).values():
        group.validate_against(data_cls)
    for item_cls in _item_models(data_cls).values():
        for group in groups_of(item_cls).values():
            group.validate_against(item_cls)


def check_faceted(cls: type) -> None:
    """``faceted`` fields must be Eq/In-filterable: a facet is a filter
    value with a count, so a field the query cannot filter on has nothing
    to facet."""
    from .resource import FilterOp

    faceted = tuple(getattr(cls, "faceted", ()) or ())
    if not faceted:
        return
    fspec = getattr(cls, "filterable", None)
    fields = fspec.fields if fspec is not None else {}
    for f in faceted:
        if f == "state":
            continue  # state facets are automatic; the declaration is moot
        ops = fields.get(f)
        if ops is None or not ops & (FilterOp.EQ | FilterOp.IN):
            raise _err(cls, f"faceted field {f!r} is not Eq/In-filterable; "
                            "declare it in filterable(...) first")


def _err(cls: type, msg: str) -> DefinitionError:
    return DefinitionError(f"{cls.__module__}.{cls.__qualname__}: {msg}")


def check_tokens(cls: type, machine: StateMachine) -> None:
    kind = getattr(cls, "kind", None)
    if not kind or not _SNAKE.match(kind):
        raise _err(cls, f"kind must be a snake_case token, got {kind!r}")
    for s in machine.states:
        if not _SNAKE.match(s):
            raise _err(cls, f"state token {s!r} is not snake_case")
    if machine.initial not in machine.states:
        raise _err(cls, f"initial state {machine.initial!r} is not a declared state")
    unknown = machine.terminal - set(machine.states)
    if unknown:
        raise _err(cls, f"terminal states {sorted(unknown)} are not declared states")
    for name, defn in machine.actions.items():
        bad = (defn.from_ | {defn.to}) - set(machine.states)
        if bad:
            raise _err(cls, f"action {name!r} references undeclared states {sorted(bad)}")


def check_reachability(cls: type, machine: StateMachine,
                       allow_dead: frozenset[str]) -> None:
    reachable = machine.reachable_states()
    for s in machine.states:
        if s == machine.initial or s in allow_dead:
            continue
        if s not in reachable:
            raise _err(cls, f"state {s!r} is unreachable from {machine.initial!r} "
                            "(annotate allow_dead if intentional)")
        if s not in machine.terminal and not machine.transitions_from(s):
            raise _err(cls, f"state {s!r} is a dead end: non-terminal but has no "
                            "outgoing transitions (annotate allow_dead if intentional)")


def check_terminal_no_exit(cls: type, machine: StateMachine) -> None:
    for name, defn in machine.actions.items():
        dead = defn.from_ & machine.terminal
        if dead:
            raise _err(cls, f"action {name!r} exits terminal state(s) {sorted(dead)}")


def check_reversible(cls: type, machine: StateMachine) -> None:
    """``reversible=True`` is verified against the graph, not trusted.

    A reverse transition ``to → src`` must exist for *every* source state,
    and its guards must be unconditional-or-time-based — approximated as:
    not hidden and not permission-token-gated.
    """
    for name, defn in machine.actions.items():
        if not defn.safety.reversible:
            continue
        for src, reverses in machine.reverse_edges(defn).items():
            usable = [
                r for r in reverses
                if not any(g.hide or (g.requires_token or "").startswith("role:")
                           for g in r.guards)
            ]
            if not usable:
                raise _err(cls, f"action {name!r} declares reversible=True but no "
                                f"unconditional transition {defn.to!r} → {src!r} exists")


def check_one_way(cls: type, machine: StateMachine) -> None:
    """A button that cannot be undone and doesn't pause is the #1 trust
    destroyer (v1's planned ``one_way_door``). An irreversible, unconfirmed,
    state-leaving action must carry ``Safety(one_way=Acknowledged('…'))``.
    Self-loops are exempt: re-doing is its own undo."""
    for name, defn in machine.actions.items():
        s = defn.safety
        if s.reversible or s.confirm or s.one_way is not None:
            continue
        leaves = any(src != defn.to for src in defn.from_)
        if leaves:
            raise _err(cls, f"action {name!r} is irreversible, unconfirmed, and "
                            f"leaves the current state — a silent one-way door. "
                            "Declare confirm (with its consequence), make it "
                            "honestly reversible, or acknowledge with "
                            "Safety(one_way=Acknowledged('why this is low-stakes'))")


def check_guard_declarations(cls: type, machine: StateMachine) -> None:
    """Judged fields must exist on the action's input model — a typo'd
    ``judges`` entry would silently advertise and enforce nothing."""
    for name, defn in machine.actions.items():
        for g in _leaf_guards(defn):
            if not g.judges:
                continue
            if defn.input is None:
                raise _err(cls, f"guard {g.name!r} on action {name!r} judges "
                                f"{sorted(g.judges)} but the action takes no input")
            missing = [f for f in g.judges if f not in defn.input.model_fields]
            if missing:
                raise _err(cls, f"guard {g.name!r} on action {name!r} judges "
                                f"{missing}, not field(s) of {defn.input.__name__}")


def check_guard_templates(cls: type, machine: StateMachine) -> None:
    """Static template check, now total: a ``vars_fn`` must declare its
    names via ``vars=`` (design §8), so there is no empirical escape."""
    for name, defn in machine.actions.items():
        for g in _leaf_guards(defn):
            placeholders = {
                field.partition(".")[0].partition("[")[0]
                for _, field, _, _ in string.Formatter().parse(g.explain)
                if field
            }
            known = set(g.declared_vars) | set(g.judges)
            unknown = placeholders - known
            if unknown:
                raise _err(cls, f"guard {g.name!r} on action {name!r}: explain "
                                f"template references {sorted(unknown)}, which "
                                "neither vars=… nor its judged fields supply")


def check_create_guards(cls: type) -> None:
    """Create guards (design E9) judge the validated create input, so
    their judged fields must exist on the create model — and their
    explain templates obey the same coverage rule as action guards
    (``check_guard_templates``): every placeholder is a declared var or
    a judged field."""
    guards = getattr(cls, "create_guards", ()) or ()
    if not guards:
        return
    model = getattr(cls, "Create", None) or cls.Data
    for top in guards:
        for g in top.iter_leaves():
            missing = [f for f in g.judges if f not in model.model_fields]
            if missing:
                raise _err(cls, f"create guard {g.name!r} judges {missing}, "
                                f"not field(s) of {model.__name__}")
            placeholders = {
                field.partition(".")[0].partition("[")[0]
                for _, field, _, _ in string.Formatter().parse(g.explain)
                if field
            }
            unknown = placeholders - (set(g.declared_vars) | set(g.judges))
            if unknown:
                raise _err(cls, f"create guard {g.name!r}: explain template "
                                f"references {sorted(unknown)}, which neither "
                                "vars=… nor its judged fields supply")


def check_closure(cls: type, machine: StateMachine) -> None:
    """The closure rule (design §1) — v1's schema-guard gap, made a
    definition-time error.

    A guard whose ``judges`` names an input field must give clients
    something to go on for that field: an ``accepts`` set, a declared
    ``relates`` relation, a schema constraint (enum / const / boolean /
    format / bounds / widget), or an explicit ``open=Acknowledged('…')``.
    A blank the server will grade post-hoc is unrepresentable.
    """
    from . import schemas as schemagen

    for name, defn in machine.actions.items():
        for g in _leaf_guards(defn):
            if not g.judges or g.open is not None:
                continue
            if defn.input is None:
                continue  # check_guard_declarations already raised
            schema = schemagen.input_schema(defn.input, dict(defn.field_display))[0]
            covered = set()
            if g.accepts is not None:
                # a Relation's acceptance set closes every field it judges
                covered.update(g.judges if g.is_relation else (g.judges[0],))
            if g.is_relation and g.op is not None:
                # a comparison closes both fields it relates
                covered.update(g.judges)
            for f in g.judges:
                if f in covered:
                    continue
                prop = _resolve_property(schema, f)
                if prop is not None and _is_constrained(prop):
                    continue
                raise _err(
                    cls,
                    f"guard {g.name!r} on action {name!r} judges {f!r}, but "
                    f"nothing tells the client what {f!r} wants: no accepts=, "
                    "no relates=, and no schema constraint. Declare the "
                    "acceptance set, constrain the schema, or acknowledge "
                    "the open judgment with open=Acknowledged('…')")


def _is_constrained(prop: dict[str, Any]) -> bool:
    if any(k in prop for k in ("enum", "const", "format", "minimum", "maximum",
                               "exclusiveMinimum", "exclusiveMaximum",
                               "minLength", "maxLength", "pattern")):
        return True
    if prop.get("type") == "boolean":
        return True
    if (prop.get("x-display") or {}).get("widget"):
        return True
    for branch in prop.get("anyOf", ()) or prop.get("oneOf", ()):
        if isinstance(branch, dict) and branch.get("type") != "null" \
                and _is_constrained(branch):
            return True
    return False


def check_handler_signatures(cls: type, machine: StateMachine) -> None:
    for name, defn in machine.actions.items():
        fn = defn.handler
        if defn.bulk is False and not inspect.iscoroutinefunction(fn):
            raise _err(cls, f"action handler {name!r} must be `async def`")
        params = list(inspect.signature(fn).parameters.values())
        if len(params) != 3:
            raise _err(cls, f"action handler {name!r} must have signature "
                            "(self, inp, ctx)")
        try:
            hints = typing.get_type_hints(fn, vars(inspect.getmodule(fn)) if
                                          inspect.getmodule(fn) else None)
        except NameError as e:
            raise _err(cls, f"action handler {name!r}: unresolvable annotation "
                            f"({e}) — define input models before the resource class")
        inp_ann: Any = hints.get(params[1].name, None)
        if defn.input is None:
            if inp_ann not in (None, type(None), Any):
                raise _err(cls, f"action {name!r} declares no input but handler "
                                f"annotates inp as {inp_ann!r}")
        elif inp_ann is not None and inp_ann is not defn.input:
            raise _err(cls, f"action {name!r}: handler annotates inp as {inp_ann!r} "
                            f"but the action declares input={defn.input.__name__}")


def check_summary_template(cls: type, template: str) -> None:
    from .summary import template_fields

    if not template:
        raise _err(cls, "summary template is required")
    allowed = {"id", "state", "data", "kind", "version"}
    unknown = set(template_fields(template)) - allowed
    if unknown:
        raise _err(cls, f"summary template references unknown roots {sorted(unknown)}; "
                        f"allowed: {sorted(allowed)}")


def check_waive_tokens(cls: type, machine: StateMachine) -> None:
    for name, defn in machine.actions.items():
        unknown = defn.waives - WAIVABLE
        if unknown:
            raise _err(cls, f"action {name!r} waives unknown usability checks "
                            f"{sorted(unknown)}; waivable: {sorted(WAIVABLE)}")


def check_place(cls: type, machine: StateMachine) -> None:
    """``place=PartScope(array, key)`` must name a list-of-model Data field
    whose item model and the action's input model both carry the key."""
    for name, defn in machine.actions.items():
        if defn.place is None:
            continue
        array, key = defn.place.array, defn.place.key
        if defn.input is None:
            raise _err(cls, f"action {name!r} declares place={defn.place!r} but "
                            "takes no input to bind the key into")
        item = _item_model(cls, array)
        if item is None:
            raise _err(cls, f"action {name!r} place names data.{array}, which "
                            "is not a list-of-model Data field")
        if key not in item.model_fields:
            raise _err(cls, f"action {name!r} place key {key!r} is not a field "
                            f"of data.{array} items ({item.__name__})")
        if key not in defn.input.model_fields:
            raise _err(cls, f"action {name!r} place key {key!r} is not a field "
                            f"of its input model {defn.input.__name__}")


def check_edit(cls: type, machine: StateMachine) -> None:
    """Edit declarations validated hard; edit-*shaped* actions that never
    declared Edit get the heuristic warning (the shape is detectable, the
    intent is not — that's the one honest warning left in this family)."""
    for name, defn in machine.actions.items():
        if defn.edit is not None:
            if defn.draft and defn.input is None:
                raise _err(cls, f"action {name!r} declares a draft but takes "
                                "no input — there is nothing to draft")
            for f in defn.edit.prefill:
                if defn.input is None or f not in defn.input.model_fields:
                    raise _err(cls, f"action {name!r} prefills {f!r}, not a "
                                    "field of its input model")
                if f not in cls.Data.model_fields:
                    raise _err(cls, f"action {name!r} prefills {f!r}, but Data "
                                    "has no such field to prefill from")
        if defn.input is None or defn.bulk:
            continue
        if defn.edit is None:
            mirrored = [
                f for f, ff in defn.input.model_fields.items()
                if (df := cls.Data.model_fields.get(f)) is not None
                and _sans_none(df.annotation) == _sans_none(ff.annotation)]
            if mirrored:
                warnings.warn(
                    f"{cls.__module__}.{cls.__qualname__}: action {name!r} is "
                    f"edit-shaped — input field(s) {mirrored} mirror Data "
                    f"fields the document already holds, but no edit=Edit(…) "
                    f"is declared. The form renders blank and unfenced. "
                    f"Declare edit=Edit(prefill=({', '.join(repr(f) for f in mirrored)},)).",
                    UsabilityWarning, stacklevel=3)
        # the knowledge floor (design §10): composition demands a draft
        required = set(defn.input.model_json_schema().get("required", []))
        prose_required = [
            f for f, ff in defn.input.model_fields.items()
            if f in required
            and isinstance(ff.json_schema_extra, dict)
            and (ff.json_schema_extra.get("x-display") or {}).get("widget")
            == "prose"]
        if prose_required and not defn.draft \
                and "large_effort" not in defn.waives:
            warnings.warn(
                f"{cls.__module__}.{cls.__qualname__}: action {name!r} "
                f"demands composition ({prose_required}) with no draft — a "
                f"mis-click discards everything the user typed. Declare "
                f"edit=Edit(draft=DraftPolicy()) or acknowledge with "
                f"waives=('large_effort',).",
                UsabilityWarning, stacklevel=3)


def check_altitude(cls: type, machine: StateMachine) -> None:
    """Wrong-altitude heuristic: an input field that keys the items of a
    ``data`` array re-asks the user to identify the item they are already
    looking at. The fix is ``place=``; the signature of identification is a
    document-derivable guard judging the field."""
    arrays = [(dname, item) for dname in cls.Data.model_fields
              if (item := _item_model(cls, dname)) is not None]
    if not arrays:
        return
    for name, defn in machine.actions.items():
        if defn.input is None or defn.bulk or "altitude" in defn.waives:
            continue
        judged = {f for g in _leaf_guards(defn)
                  if not g.reads_ctx
                  for f in g.judges}
        for dname, item in arrays:
            if defn.place is not None and defn.place.array == dname:
                continue  # already placed on this array: the fix is applied
            matched = [fname for fname, ffield in defn.input.model_fields.items()
                       if fname in judged
                       and (target := item.model_fields.get(fname)) is not None
                       and target.annotation == ffield.annotation]
            if matched:
                warnings.warn(
                    f"{cls.__module__}.{cls.__qualname__}: action {name!r} "
                    f"input field(s) {matched} mirror the items of "
                    f"data.{dname} — the form re-asks the user to identify "
                    f"an item they are already looking at. Declare a "
                    f"PartScope({dname!r}, key=…) on the resource and place "
                    f"the action on it, or acknowledge with "
                    f"waives=('altitude',).",
                    UsabilityWarning, stacklevel=3)


def check_long_text(cls: type, machine: StateMachine) -> None:
    """Long-form text is a document, not a datum. A string field with no
    length budget (or ≥ 280 chars) must say what it is: ``x-display
    {widget: 'prose'}``, a real ``max_length``, or ``hidden``/``raw``.
    Length budgets are a wire contract, not a style preference."""
    import types as _types

    models: list[tuple[str, type[BaseModel]]] = [("data", cls.Data)]
    for dname in cls.Data.model_fields:
        item = _item_model(cls, dname)
        if item is not None:
            models.append((f"data.{dname}[]", item))
    seen_inputs: set[type] = set()
    for name, defn in machine.actions.items():
        if defn.input is not None and defn.input not in seen_inputs:
            seen_inputs.add(defn.input)
            models.append((f"input {defn.input.__name__}", defn.input))

    for where, model in models:
        for fname, f in model.model_fields.items():
            ann = f.annotation
            is_text = ann is str or (
                typing.get_origin(ann) in (typing.Union, _types.UnionType)
                and str in typing.get_args(ann))
            if not is_text:
                continue
            if ref_display(f) is not None:
                continue  # Ref fields render as references, never as prose
            max_len = next((m.max_length for m in f.metadata
                            if getattr(m, "max_length", None) is not None),
                           None)
            if max_len is not None and max_len < LONG_TEXT_BUDGET:
                continue
            extra = f.json_schema_extra \
                if isinstance(f.json_schema_extra, dict) else {}
            xd = extra.get("x-display") or {}
            if xd.get("widget") or xd.get("hidden") or xd.get("raw"):
                continue
            budget = f"max_length={max_len}" if max_len is not None \
                else "no max_length"
            warnings.warn(
                f"{cls.__module__}.{cls.__qualname__}: {where}.{fname} is a "
                f"text field with {budget} — long-form text breaks any "
                f"table a generic client derives. Declare x-display "
                f"{{'widget': 'prose'}} for long-form content, set "
                f"max_length under {LONG_TEXT_BUDGET} if it's genuinely "
                f"short, or mark {{'hidden': True}} / {{'raw': True}}.",
                UsabilityWarning, stacklevel=3)


def check_touches(registry: Any) -> None:
    """Declared touches (design E8) validate at assembly: every touched
    kind is registered and every advanced action exists on its machine
    and is invocable through ctx.invoke (non-bulk)."""
    from .touches import Advances, Creates

    for rdef in registry.defs():
        for name, defn in rdef.machine.actions.items():
            for t in defn.touches:
                if isinstance(t, Creates):
                    if registry.get(t.kind) is None:
                        raise _err(rdef.cls,
                                   f"{name}: Creates({t.kind!r}) names an "
                                   "unregistered kind")
                elif isinstance(t, Advances):
                    target = registry.get(t.kind)
                    if target is None:
                        raise _err(rdef.cls,
                                   f"{name}: Advances({t.kind!r}, …) names "
                                   "an unregistered kind")
                    tdefn = target.machine.actions.get(t.action)
                    if tdefn is None:
                        raise _err(rdef.cls,
                                   f"{name}: Advances({t.kind!r}, "
                                   f"{t.action!r}) names no action of "
                                   f"{t.kind!r}")
                    if tdefn.bulk:
                        raise _err(rdef.cls,
                                   f"{name}: Advances({t.kind!r}, "
                                   f"{t.action!r}) targets a bulk action — "
                                   "ctx.invoke drives per-resource "
                                   "transitions")


def check_compounds(registry: Any, services: Any) -> None:
    """Compound declarations validate at assembly (design §6), where every
    kind — and the engine's declared services — are known. (The child
    kinds/actions themselves are the compound's compiled touches, already
    covered by ``check_touches``.)

    - a Create's seed writes real fields of the child's create input;
    - an Each's ``where`` runs on promoted (filterable) child columns;
    - an advanced child action is ctx.invoke-able: not fenced (the
      compound holds no child etag) and its declared input body names
      real fields of the child's input model;
    - every effect names a declared Service on this engine whose adapter
      actually has the operation — and the compensator.
    """
    for rdef in registry.defs():
        for name, defn in rdef.machine.actions.items():
            comp = getattr(defn, "compound", None)
            if comp is None:
                continue
            for c in comp.creates:
                child = registry.get(c.kind)
                if child is None:
                    continue  # check_touches names this one
                model = child.extra.get("create_model") or child.cls.Data
                unknown = sorted(k for k in c.seed
                                 if k not in model.model_fields)
                if unknown:
                    raise _err(rdef.cls,
                               f"{name}: Create({c.kind!r}) seeds {unknown}, "
                               f"not field(s) of its create input "
                               f"{model.__name__}")
            for st in comp._steps:
                child = registry.get(st.kind)
                if child is None:
                    continue  # check_touches names this one
                tdefn = child.machine.actions.get(st.action)
                if tdefn is None:
                    continue  # check_touches names this one
                if tdefn.safety.fence:
                    raise _err(rdef.cls,
                               f"{name}: advances {st.kind}.{st.action}, "
                               "which is fenced — the compound holds no "
                               "child etag (design §6)")
                if st.input:
                    if tdefn.input is None:
                        raise _err(rdef.cls,
                                   f"{name}: {st.kind}.{st.action} takes no "
                                   "input, but the compound declares a body "
                                   "for it")
                    unknown = sorted(k for k in st.input
                                     if k not in tdefn.input.model_fields)
                    if unknown:
                        raise _err(rdef.cls,
                                   f"{name}: input for {st.kind}."
                                   f"{st.action} names {unknown}, not "
                                   "field(s) of its input model")
                if st.via is not None:
                    fspec = child.cls.filterable
                    child_filterable = set(fspec.fields) if fspec else set()
                    for f in st.where:
                        if f != "state" and f not in child_filterable:
                            raise _err(rdef.cls,
                                       f"{name}: Each({st.kind!r}) where "
                                       f"field {f!r} is not filterable on "
                                       "the child — the fan-out query runs "
                                       "on promoted columns (design §6)")
            for e in comp.effects:
                svc = getattr(services, e.service, None) \
                    if services is not None else None
                if svc is None or not hasattr(svc, "call_op"):
                    raise _err(rdef.cls,
                               f"{name}: effect names service {e.service!r}, "
                               "which is not a declared Service on this "
                               "engine (design §6)")
                handler = getattr(svc, "handler", None)
                for op in (e.op, e.compensate.op):
                    if not callable(getattr(handler, op, None)):
                        raise _err(rdef.cls,
                                   f"{name}: service {e.service!r} has no "
                                   f"operation {op!r} — an effect (and its "
                                   "compensator) invokes a declared adapter "
                                   "operation (design §6)")


def _check_predecessor(registry: Any, rdef: Any, where: str, fname: str,
                       field: Any, meta: Any) -> None:
    """Predecessor refs (design E7): the resolving query runs on promoted
    columns, so ``order`` must be filterable/sortable on the target and a
    ``partition`` must be Eq-filterable there and a field of the declaring
    Data (its value comes from the new instance)."""
    from .refs import ref_predecessor
    from .resource import FilterOp

    pred = ref_predecessor(field)
    if pred is None:
        return
    if where != "data":
        raise _err(rdef.cls, f"{where}.{fname}: predecessor refs live on "
                             "Data — inputs have no instance to resolve for")
    target = registry.get(meta.kind)
    promotable: set[str] = set()
    if target.cls.filterable is not None:
        promotable |= set(target.cls.filterable.fields)
    if target.cls.sortable is not None:
        promotable |= set(target.cls.sortable.fields)
    if pred.order not in promotable:
        raise _err(rdef.cls, f"data.{fname}: predecessor order "
                             f"{pred.order!r} must be filterable or sortable "
                             f"on {meta.kind!r}")
    if pred.partition is not None:
        fspec = target.cls.filterable
        ops = fspec.fields.get(pred.partition) if fspec else None
        if ops is None or not ops & FilterOp.EQ:
            raise _err(rdef.cls, f"data.{fname}: predecessor partition "
                                 f"{pred.partition!r} must be Eq-filterable "
                                 f"on {meta.kind!r}")
        if pred.partition not in rdef.cls.Data.model_fields:
            raise _err(rdef.cls, f"data.{fname}: predecessor partition "
                                 f"{pred.partition!r} is not a field of the "
                                 "declaring Data")
    if pred.order not in rdef.cls.Data.model_fields:
        # the ≤ comparison seeds from the new instance's own value
        raise _err(rdef.cls, f"data.{fname}: predecessor order "
                             f"{pred.order!r} is not a field of the "
                             "declaring Data")


def check_refs(registry: Any) -> None:
    """Assembly-time reference checks (design §2).

    Errors: a ``Ref[kind]`` naming an unregistered kind is a broken
    declaration. Warnings: a ``{kind}_id``-named field for a registered kind
    without a ``Ref`` type is the v1 heuristic demoted to a lint — it tells
    you to use the declaration that retires it.
    """
    kinds = set(registry.kinds())
    for rdef in registry.defs():
        models: list[tuple[str, type[BaseModel]]] = [("data", rdef.cls.Data)]
        for dname in rdef.cls.Data.model_fields:
            item = _item_model(rdef.cls, dname)
            if item is not None:
                models.append((f"data.{dname}[]", item))
        for name, defn in rdef.machine.actions.items():
            if defn.input is not None:
                models.append((f"input {defn.input.__name__}", defn.input))
        for where, model in models:
            refs = model_refs(model)
            for fname, meta in refs.items():
                if meta.kind not in kinds:
                    raise DefinitionError(
                        f"{rdef.cls.__module__}.{rdef.cls.__qualname__}: "
                        f"{where}.{fname} is Ref[{meta.kind!r}], but no such "
                        "kind is registered on this engine")
                _check_predecessor(registry, rdef, where, fname,
                                   model.model_fields[fname], meta)
            for fname, f in model.model_fields.items():
                if fname in refs:
                    continue
                target = fname.removesuffix("_id")
                if target == fname or target not in kinds:
                    continue
                extra = f.json_schema_extra \
                    if isinstance(f.json_schema_extra, dict) else {}
                xd = extra.get("x-display") or {}
                if xd.get("widget") or xd.get("hidden") or xd.get("raw"):
                    continue
                warnings.warn(
                    f"{rdef.cls.__module__}.{rdef.cls.__qualname__}: "
                    f"{where}.{fname} references a {target!r} by naming "
                    f"convention but is not Ref[{target!r}] — type it as a "
                    f"Ref (with RefField(label=…, pick=…, raw=…) options) so "
                    "the picker, the navigable reference, and the "
                    "dangling-ref check all come from one declaration.",
                    UsabilityWarning, stacklevel=2)


def _item_model(cls: type, array: str) -> type[BaseModel] | None:
    dfield = cls.Data.model_fields.get(array)
    if dfield is None or typing.get_origin(dfield.annotation) is not list:
        return None
    args = typing.get_args(dfield.annotation)
    if args and isinstance(args[0], type) and issubclass(args[0], BaseModel):
        return args[0]
    return None


def _sans_none(ann: Any) -> Any:
    """``str | None`` → ``str`` so edit-shape comparison ignores optionality."""
    import types as _types

    if typing.get_origin(ann) in (typing.Union, _types.UnionType):
        rest = [a for a in typing.get_args(ann) if a is not type(None)]
        if len(rest) == 1:
            return rest[0]
    return ann


def _leaf_guards(defn: ActionDef):
    for g in defn.guards:
        yield from g.iter_leaves()


def _resolve_property(schema: dict[str, Any], field: str) -> dict[str, Any] | None:
    """Follow one level of local $ref / single-element allOf to the field's
    effective schema (pydantic wraps enums this way)."""
    prop = (schema.get("properties") or {}).get(field)
    if prop is None:
        return None
    if len(prop.get("allOf", ())) == 1:
        prop = {**prop, **prop["allOf"][0]}
    ref = prop.get("$ref", "")
    if ref.startswith("#/$defs/"):
        target = (schema.get("$defs") or {}).get(ref.rsplit("/", 1)[-1], {})
        prop = {**target, **{k: v for k, v in prop.items() if k != "$ref"}}
    return prop
