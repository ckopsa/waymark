"""Unit tests for the ``waymark client`` CLI's pure helpers: header/principal
parsing, envelope rendering, and truncation. Wire behavior (confirm gate,
idempotency, dry-run) is the agent client's contract and is covered by the
client/server tests."""
from __future__ import annotations

import pytest
import typer

from waymark.cli.client import (_default_session, _flags, _input_summary,
                                _parse_headers, _print_doc, _trunc)
from waymark.client.py import Doc

ENVELOPE = {
    "waymark": "1", "kind": "order", "self": "/api/orders/1",
    "state": "draft", "summary": "Order #1 · draft",
    "data": {"total": 5, "note": "x" * 500},
    "actions": {
        "place": {"method": "POST", "href": "/api/orders/1/-/place",
                  "effect": {"to": "placed"},
                  "safety": {"idempotent": True, "reversible": False,
                             "confirm": False, "requires_if_match": True},
                  "input": {"type": "object", "required": ["when"],
                            "properties": {"when": {"type": "string"},
                                           "note": {"type": "string"}}}},
        "cancel": {"method": "POST", "href": "/api/orders/1/-/cancel",
                   "effect": {"to": "cancelled", "terminal": True},
                   "safety": {"idempotent": False, "reversible": False,
                              "confirm": True}},
    },
    "unavailable": {"refund": {"reason": "Not paid yet.",
                               "becomes_available": {"in_states": ["paid"]}}},
    "links": {"collection": {"href": "/api/orders", "summary": "All orders"}},
    "meta": {"version": 3, "etag": 'W/"order-1-v3"'},
}


def test_parse_headers_principal_and_pairs():
    headers = _parse_headers(["X-Extra: yes"], "claude:agent:Claude (for Dana)")
    assert headers == {"X-Principal-Id": "claude",
                       "X-Principal-Type": "agent",
                       "X-Principal-Display": "Claude (for Dana)",
                       "X-Extra": "yes"}


def test_parse_headers_principal_type_defaults_to_agent():
    assert _parse_headers([], "claude")["X-Principal-Type"] == "agent"


def test_parse_headers_rejects_malformed_pair():
    with pytest.raises(typer.BadParameter):
        _parse_headers(["not-a-header"], None)


def test_input_summary_marks_required_fields():
    assert _input_summary(ENVELOPE["actions"]["place"]) == "  input: when*, note?"
    assert _input_summary(ENVELOPE["actions"]["cancel"]) == ""
    assert _input_summary({"input": {"$ref": "/api/schemas/X"}}) == \
        "  input: /api/schemas/X"


def test_flags_surface_safety_semantics():
    assert _flags(ENVELOPE["actions"]["place"]) == " [if-match]"
    assert _flags(ENVELOPE["actions"]["cancel"]) == " [confirm, non-idempotent]"


def test_trunc_bounds_long_strings_recursively():
    out = _trunc({"a": ["y" * 300], "b": "short"}, limit=100)
    assert out["b"] == "short"
    assert out["a"][0].startswith("y" * 100) and "300 chars" in out["a"][0]


def test_print_doc_renders_envelope_sections(capsys):
    _print_doc(Doc(ENVELOPE))
    out = capsys.readouterr().out
    assert "order /api/orders/1 · state=draft · v3" in out
    assert "place" in out and "→ placed" in out
    assert "cancel" in out and "(terminal)" in out
    assert "refund: Not paid yet." in out
    assert "collection → /api/orders · All orders" in out
    assert "--raw for full" in out          # long data string was truncated
    assert "x" * 500 not in out


def test_print_doc_renders_collection_items(capsys):
    collection = {
        "waymark": "1", "kind": "order_collection", "self": "/api/orders",
        "state": "ok", "summary": "Orders · 1 shown",
        "data": {"items": [ENVELOPE], "total": 1},
        "actions": {}, "unavailable": {}, "links": {},
    }
    _print_doc(Doc(collection))
    out = capsys.readouterr().out
    assert "  /api/orders/1 · draft · Order #1 · draft" in out
    assert '"total": 1' in out


def test_default_session_path_is_per_server():
    a = _default_session("http://127.0.0.1:8000")
    b = _default_session("https://prod.example.com")
    assert a != b and a.parent == b.parent
