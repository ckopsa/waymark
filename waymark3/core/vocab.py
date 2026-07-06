"""Vocabularies as a type (design §6): one field declaration carrying its
filter operation, its index kind, its facet behavior, and its placeholder.

The v2 scar this retires, one commit at a time: array membership was an
engine feature threaded through four layers; ``faceted = ("themes",)`` had
to be declared *alongside* ``filterable(themes=…)`` plus an import-time
check keeping them agreeing; storage sniffed ``promoted == "array"`` to
pick operators; and "not yet chosen" was a sentinel string special-cased
in four files. In 3.0::

    class MealData(BaseModel):
        themes: Vocab[str] = VocabField(
            open=True,                # values minted by use
            facet=Observed(counts=True),
            description="Every theme night this meal can serve",
        )

One declaration generates: membership filtering (``Eq`` = tagged with the
value, ``In`` = tagged with any) over a GIN-indexed JSONB generated column,
the comma-list wire form, the per-render observed facet, and the
``x-vocab`` wire hint. The resource declares neither ``filterable`` nor
``faceted`` for the field — :class:`~.resource.Resource` merges vocab
fields into both at import, so the app-facing dual declaration is gone.

``placeholder=`` names a declared member meaning "not chosen yet": it
renders distinctly (``x-vocab.placeholder``) and application predicates
reference the declaration instead of threading a sentinel constant.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from pydantic import BaseModel, Field
from pydantic.fields import FieldInfo

from .types import DefinitionError


@dataclass(frozen=True)
class Observed:
    """Facet behavior: the filter's choices are its observed values,
    refreshed per render, with counts when asked."""

    counts: bool = True


class Vocab:
    """``Vocab[str]`` → ``list[str]`` (the marker lives on the field via
    :func:`VocabField`, mirroring how ``Ref``/``RefField`` split)."""

    def __class_getitem__(cls, item: type) -> Any:
        return list[item]


def VocabField(
    default: Any = ...,
    *,
    open: bool = True,
    facet: Observed | None = None,
    placeholder: str | None = None,
    values: tuple[str, ...] | None = None,
    **kwargs: Any,
) -> Any:
    """Field options for a ``Vocab``-typed field.

    - ``open`` — values are minted by use; ``open=False`` requires
      ``values=`` (a closed vocabulary is an enum that also facets).
    - ``facet`` — ``Observed(counts=True)`` renders the observed values as
      the filter's choices on every collection.
    - ``placeholder`` — the declared "not chosen yet" member.
    """
    if not open and not values:
        raise DefinitionError(
            "VocabField(open=False) requires values=(...) — a closed "
            "vocabulary must say what its members are")
    spec: dict[str, Any] = {"open": open}
    if facet is not None:
        spec["facet"] = {"observed": True, "counts": facet.counts}
    if placeholder is not None:
        spec["placeholder"] = placeholder
    if values:
        spec["values"] = list(values)
    extra = kwargs.pop("json_schema_extra", None) or {}
    extra["x-vocab"] = spec
    if not open:
        # a closed vocabulary is an enum that also facets (design §6): the
        # declared members ARE the item schema; enforcement reads the same
        # declaration in the invoker's validate step
        extra["items"] = {"type": "string", "enum": list(values or ())}
    return Field(default, json_schema_extra=extra, **kwargs)


def vocab_spec(field_info: FieldInfo) -> dict[str, Any] | None:
    extra = field_info.json_schema_extra
    if isinstance(extra, dict):
        spec = extra.get("x-vocab")
        if isinstance(spec, dict):
            return spec
    return None


def model_vocabs(model: type[BaseModel]) -> dict[str, dict[str, Any]]:
    """All Vocab-declared fields of a model, by field name."""
    out: dict[str, dict[str, Any]] = {}
    for name, f in model.model_fields.items():
        spec = vocab_spec(f)
        if spec is not None:
            out[name] = spec
    return out


def closed_vocab_errors(model: type[BaseModel],
                        inst: BaseModel) -> dict[str, list[str]]:
    """Members outside a closed vocabulary's declared set, per field —
    the enforcement half of the ``items.enum`` the schema advertises."""
    errors: dict[str, list[str]] = {}
    for name, spec in model_vocabs(model).items():
        if spec.get("open", True):
            continue
        allowed = set(spec.get("values") or ())
        unknown = sorted({str(v) for v in (getattr(inst, name, None) or ())
                          if v not in allowed})
        if unknown:
            errors[name] = [f"not in the declared vocabulary: "
                            f"{', '.join(unknown)} (declared: "
                            f"{', '.join(sorted(allowed))})"]
    return errors
