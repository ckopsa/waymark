"""The typed Python client: a thin, honest wrapper over Waymark documents."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import httpx


def merge_params(href: str, **params: Any) -> str:
    """Merge query parameters into an advertised href, preserving the
    href's own query (design §7: hrefs are authoritative; clients merge,
    they never rebuild). Passing params to an HTTP library *replaces* the
    query string — that variant silently dropped ``?state=active`` from a
    collection href in production and linked a prep task to the wrong
    plan. ``None`` values are skipped; a param already present in the href
    is overridden by the explicit one."""
    url = httpx.URL(href)
    supplied = {k: str(v) for k, v in params.items() if v is not None}
    return str(url.copy_merge_params(supplied)) if supplied else href


class WaymarkError(Exception):
    pass


@dataclass
class Problem(WaymarkError):
    """An RFC 9457 problem response, affordances included (§7.2)."""

    status: int
    type: str
    title: str
    detail: str
    errors: dict[str, list[str]] | None
    actions: dict[str, Any]
    resource: dict[str, Any] | None
    raw: dict[str, Any]

    def __str__(self) -> str:
        return f"{self.status} {self.title}: {self.detail}"

    @classmethod
    def from_response(cls, res: httpx.Response) -> "Problem":
        body = res.json()
        return cls(status=res.status_code, type=body.get("type", ""),
                   title=body.get("title", ""), detail=body.get("detail", ""),
                   errors=body.get("errors"), actions=body.get("actions", {}),
                   resource=body.get("resource"), raw=body)


class Doc:
    """A resource document. Attribute access reads the envelope; ``.data``
    is the plain-JSON payload a Waymark-ignorant client would see."""

    def __init__(self, body: dict[str, Any], response: httpx.Response | None = None):
        self.body = body
        self.response = response

    @property
    def kind(self) -> str:
        return self.body["kind"]

    @property
    def self_href(self) -> str:
        return self.body["self"]

    @property
    def state(self) -> str:
        return self.body["state"]

    @property
    def summary(self) -> str:
        return self.body["summary"]

    @property
    def data(self) -> dict[str, Any]:
        return self.body["data"]

    @property
    def actions(self) -> dict[str, Any]:
        return self.body.get("actions") or {}

    @property
    def unavailable(self) -> dict[str, Any]:
        return self.body.get("unavailable") or {}

    @property
    def links(self) -> dict[str, Any]:
        return {k: v for k, v in (self.body.get("links") or {}).items() if v}

    @property
    def etag(self) -> str | None:
        return (self.body.get("meta") or {}).get("etag")

    @property
    def version(self) -> int | None:
        return (self.body.get("meta") or {}).get("version")

    @property
    def items(self) -> list["Doc"]:
        """Collection items, as documents."""
        return [Doc(i) for i in self.data.get("items", [])]

    def why_not(self, action: str) -> str | None:
        entry = self.unavailable.get(action)
        return entry["reason"] if entry else None

    def __repr__(self) -> str:
        return f"<Doc {self.kind} {self.self_href} state={self.state}>"


class WaymarkClient:
    """Reads are GET on links; writes are POST on actions — nothing else."""

    def __init__(self, base_url: str = "", *, http: httpx.AsyncClient | None = None,
                 headers: dict[str, str] | None = None):
        self.http = http or httpx.AsyncClient(base_url=base_url)
        self.headers = headers or {}

    async def index(self, base: str = "/api") -> dict[str, Any]:
        res = await self.http.get(f"{base}/.well-known/waymark",
                                  headers=self.headers)
        res.raise_for_status()
        return res.json()

    async def get(self, href: str, *, depth: str | None = None) -> Doc:
        res = await self.http.get(merge_params(href, depth=depth),
                                  headers=self.headers)
        if res.status_code >= 400:
            raise Problem.from_response(res)
        return Doc(res.json(), res)

    async def follow(self, doc: Doc, rel: str, *, depth: str | None = None) -> Doc:
        link = doc.links.get(rel)
        if link is None:
            raise WaymarkError(f"{doc.kind} has no link {rel!r}")
        if "embedded" in link:
            return Doc(link["embedded"])
        return await self.get(link["href"], depth=depth)

    async def post(self, href: str, body: dict[str, Any] | None,
                   headers: dict[str, str]) -> Doc:
        res = await self.http.post(href, json=body,
                                   headers={**self.headers, **headers})
        if res.status_code >= 400:
            raise Problem.from_response(res)
        return Doc(res.json(), res)

    async def aclose(self) -> None:
        await self.http.aclose()
