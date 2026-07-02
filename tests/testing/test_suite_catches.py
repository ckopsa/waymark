"""Negative proof: the conformance suite fails for the right reasons.

A green ``--waymark`` is only meaningful if we've watched the suite catch
violations. Each test here rigs one violation and asserts the specific
conformance check rejects it with an actionable message.
"""
import pytest
from _pytest.outcomes import Failed

from waymark.testing import conformance
from waymark.testing import factories as reg

from app.resources.order import Order


async def test_catches_factory_dishonesty(wm, monkeypatch):
    honest = reg._FACTORIES["order"][1]

    async def dishonest(state, engine, services):
        return await honest("draft", engine, services)

    monkeypatch.setitem(reg._FACTORIES, "order", (Order, dishonest))
    with pytest.raises(AssertionError, match="state factory dishonesty"):
        await conformance.make(wm, "order", "paid")


async def test_catches_missing_example_for_semantic_guard(wm, monkeypatch):
    monkeypatch.delitem(reg._EXAMPLES, ("order", "submit_payment"))
    with pytest.raises(Failed, match="register @example_input"):
        await conformance.test_transition_truth_available(
            wm, ("order", "awaiting_payment", "submit_payment"))


async def test_catches_resource_without_factory(wm, monkeypatch):
    monkeypatch.delitem(reg._FACTORIES, "order")
    with pytest.raises(AssertionError, match="without @state_factory.*order"):
        await conformance.test_every_resource_has_a_state_factory(wm)


async def test_catches_advertisement_enforcement_drift(wm, monkeypatch):
    """Simulate a server whose 409 detail drifts from the advertised reason
    (impossible through the DSL — which is the point — so we rig the
    enforcement layer) and watch transition-truth-unavailable reject it."""
    from waymark.server import invoke as invoke_mod
    from waymark.server.problems import GuardRefused

    async def drifted_refuse(self, s, rdef, instance, defn, deny, denier, ctx):
        raise GuardRefused("A hand-edited reason that drifted from render.",
                           action_attempted=defn.name, state=instance.state)

    monkeypatch.setattr(invoke_mod.Invoker, "_refuse", drifted_refuse)
    with pytest.raises(AssertionError, match="enforcement disagrees"):
        await conformance.test_transition_truth_unavailable(
            wm, ("order", "paid", "refund"))
