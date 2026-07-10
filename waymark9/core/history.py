"""History is an input (design §4): log facts as declarations.

The transition log has been a first-class *store* since v0.1 — audit,
outbox, idempotency anchor — but never a first-class *input to
declarations*. Four-eyes needed it and got enforcement-only (E3 reads
``ctx.actor_of`` at invoke); nothing let projection see the same fact, so
the button rendered and refused — the exact advertisement/enforcement
drift the whole series exists to kill, shipped as a feature.

4.0 admits the log fact as a declaration object. :func:`actor_of` names
"the principal who most recently performed this transition on this
resource" — indexable by construction (the log already carries
``(kind, resource_id, action, actor_id)``, and storage answers it with
one query, ``transition_actor``) and evaluable through the same
``ctx.actor_of`` at render time *and* invoke time, from one definition.
Future log facts (``count``, ``performed``) are further
:class:`LogFact` subclasses; the shape — a named, declared read the
engine can evaluate for ``(resource, ctx)`` — is fixed here.

:class:`Unless` is the per-action relative visibility the payouts
story asked for by name: ``@action(..., unless=Unless(actor_of("submit")))``
is ONE declaration with two consumers. The projector sorts the action
into ``unavailable`` for the matching principal (the conformance
invariant holds — the action lands in ``unavailable``, never vanishes),
and the invoker refuses the client that ignores advertisement — with the
same generated sentence, in the same guard-refused Problem shape. E3's
``four_eyes(of=…)`` survives as sugar for exactly this (guards.py),
which is the general pattern of the E-wave's fate: kept in behavior,
generalized in mechanism.
"""
from __future__ import annotations

from typing import Any

from .guards import Guard
from .types import Allow, Ctx, DefinitionError, Deny


class LogFact:
    """A declared, reusable fact over the transition log. Subclasses fix
    the query shape; instances carry its parameters. ``value(r, ctx)`` is
    the one evaluation both the projector and the invoker call — there is
    no render-side spelling to drift from the enforcement-side one."""

    name = "log_fact"

    async def value(self, r: Any, ctx: Ctx) -> Any:  # pragma: no cover
        raise NotImplementedError


class ActorOf(LogFact):
    """The principal id who most recently performed ``transition`` on
    this resource, or None if nobody has. Read through ``ctx.actor_of``,
    which storage answers from the log's own index — never a scan."""

    def __init__(self, transition: str):
        if not transition or not str(transition).strip():
            raise DefinitionError(
                "actor_of() requires a transition name — the fact is 'who "
                "last performed it', so it must name what was performed")
        self.transition = str(transition)
        self.name = f"actor_of:{self.transition}"

    async def value(self, r: Any, ctx: Ctx) -> str | None:
        return await ctx.actor_of(type(r), r.id, self.transition)

    def __repr__(self) -> str:  # pragma: no cover - debugging nicety
        return f"actor_of({self.transition!r})"


def actor_of(transition: str) -> ActorOf:
    """The log fact behind four-eyes (design §4): who last performed
    ``transition`` on this resource."""
    return ActorOf(transition)


class Unless:
    """Per-action relative visibility (design §4): the action is withheld
    from the principal the log fact names.

    ``@action(..., unless=Unless(actor_of("submit")))`` — one declaration,
    two consumers. It compiles to a single :class:`Guard` (``.guard``)
    that both surfaces run: the projector's probe folds a Deny into
    ``unavailable`` with the generated reason, and the invoker's guard
    pass raises the guard-refused Problem (409) with the *same* string.
    ``explain=`` overrides the generated sentence; ``hide=True`` conceals
    instead of explaining (the action appears in neither map, and the
    wire 404s — the existing concealment discipline).
    """

    def __init__(self, fact: LogFact, *, explain: str | None = None,
                 hide: bool = False, name: str | None = None):
        if not isinstance(fact, LogFact):
            raise DefinitionError(
                f"Unless takes a log fact (e.g. actor_of('submit')), got "
                f"{fact!r}")
        if not isinstance(fact, ActorOf):
            raise DefinitionError(
                f"Unless({fact.name}): only principal-valued log facts can "
                "bar a principal — actor_of(...) is the supported shape")
        self.fact = fact
        self.explain = explain or (
            f"You performed {fact.transition.replace('_', ' ')} on this "
            "resource; a different principal must do this.")
        self.hide = hide
        self.name = name or f"unless:{fact.name}"
        self.guard = self._compile()

    def _compile(self) -> Guard:
        fact = self.fact

        async def check(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
            actor = await fact.value(r, ctx)
            if actor is not None and actor == ctx.principal.id:
                return Deny()
            return Allow()

        return Guard(explain=self.explain, check=check,
                     reads=("transitions", "principal"),
                     hide=self.hide, name=self.name)

    def __repr__(self) -> str:  # pragma: no cover - debugging nicety
        return f"Unless({self.fact!r})"
