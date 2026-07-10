"""The summary-template mini-language (§1, §10.1).

Templates are ``str.format``-style with dotted paths resolved against the
resource instance (``{id}``, ``{state}``, ``{data.total:.2f}``) plus a
``|filter`` suffix inside the field name (``{data.items|len}``).

``{state.label}`` renders the state token title-cased (display override is a
render-time concern; the template layer stays pure). A plain snake_case
``Data`` enum field gets the same treatment via the ``|label`` filter —
e.g. ``{data.transaction_type_id|label}`` renders ``capital_call`` as
``Capital call`` rather than leaking the machine token into prose.
"""
from __future__ import annotations

import string
from typing import Any

SUMMARY_BUDGET = 140

def state_label(token: str) -> str:
    return token.replace("_", " ").capitalize()


_FILTERS = {
    "len": len,
    "upper": lambda v: str(v).upper(),
    "lower": lambda v: str(v).lower(),
    "join": lambda v: ", ".join(str(x) for x in v),
    # a snake_case enum value rendered as prose (e.g. a StrEnum field on
    # Data, not the resource's own state) — same humanization as
    # {state.label}, available to any field via |label
    "label": lambda v: state_label(str(v)),
}


class _StateProxy:
    def __init__(self, token: str):
        self.token = token
        self.label = state_label(token)

    def __format__(self, spec: str) -> str:
        return format(self.token, spec)

    def __str__(self) -> str:
        return self.token


class _SummaryFormatter(string.Formatter):
    def __init__(self, instance: Any):
        self.instance = instance

    def get_field(self, field_name: str, args: Any, kwargs: Any) -> tuple[Any, str]:
        path, _, filter_name = field_name.partition("|")
        parts = path.split(".")
        root, rest = parts[0], parts[1:]
        if root == "state":
            value: Any = _StateProxy(str(self.instance.state))
        elif root == "":
            raise ValueError("empty field in summary template")
        else:
            value = getattr(self.instance, root)
        for part in rest:
            if isinstance(value, dict):
                value = value[part]
            else:
                value = getattr(value, part)
        if filter_name:
            try:
                value = _FILTERS[filter_name](value)
            except KeyError:
                raise ValueError(f"unknown summary filter {filter_name!r}") from None
        return value, field_name


def template_fields(template: str) -> list[str]:
    """Root field names referenced by a template (for import-time validation)."""
    out = []
    for _, field_name, _, _ in string.Formatter().parse(template):
        if field_name:
            path = field_name.partition("|")[0]
            out.append(path.split(".")[0])
    return out


def render_summary(template: str, instance: Any) -> str:
    text = _SummaryFormatter(instance).vformat(template, (), {})
    if len(text) > SUMMARY_BUDGET:
        text = text[: SUMMARY_BUDGET - 1] + "…"
    return text
