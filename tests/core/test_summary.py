from types import SimpleNamespace

import pytest

from waymark.core.summary import SUMMARY_BUDGET, render_summary, state_label, template_fields


def order(total=84.2, items=3):
    return SimpleNamespace(
        id="8812",
        state="awaiting_payment",
        data=SimpleNamespace(items=list(range(items)), total=total, currency="USD"),
    )


def test_basic_fields_and_format_specs():
    text = render_summary(
        "Order #{id} · {data.items|len} items · {data.total:.2f} {data.currency} · {state.label}",
        order(),
    )
    assert text == "Order #8812 · 3 items · 84.20 USD · Awaiting payment"


def test_state_renders_token_and_label():
    assert render_summary("{state}", order()) == "awaiting_payment"
    assert render_summary("{state.label}", order()) == "Awaiting payment"
    assert state_label("paid") == "Paid"


def test_len_filter():
    assert render_summary("{data.items|len}", order(items=7)) == "7"


def test_unknown_filter_raises():
    with pytest.raises(ValueError, match="unknown summary filter"):
        render_summary("{data.items|frobnicate}", order())


def test_budget_truncation():
    inst = SimpleNamespace(id="1", state="ok", data=SimpleNamespace(name="x" * 400))
    text = render_summary("{data.name}", inst)
    assert len(text) == SUMMARY_BUDGET
    assert text.endswith("…")


def test_template_fields_extraction():
    fields = template_fields("Order #{id} · {data.total:.2f} · {state.label} · {data.items|len}")
    assert fields == ["id", "data", "state", "data"]
