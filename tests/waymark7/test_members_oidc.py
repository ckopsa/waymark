"""Identity is a resource; authN is externalized (design §9).

- ``member`` is an engine kind: invite → first-login bind → active are
  ordinary audited transitions; the admin console is a collection view.
- The OIDC resolver is one implementation of the credential-resolver
  interface: code + PKCE against a (faked) IdP, id_token verified against
  its JWKS, session in a signed cookie, ``sub`` bound to the invited
  member. An account nobody invited is refused — membership is the
  admin's to extend, not the IdP's.
- Per-resource grants (design §9): a grant narrowed with ``requested_over``
  applies to *that* meal plan, not the kind.
"""
from __future__ import annotations

import json
import os
import time
import uuid
from enum import StrEnum

import jwt
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient, MockTransport, Response
from pydantic import BaseModel, Field

import waymark7
from waymark7 import Ctx, Resource, Safety, action
from waymark7.server.bus import InProcessBus
from waymark7.server.engine import header_principal
from waymark7.server.oidc import SESSION_COOKIE, OIDCResolver
from waymark7.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

ISSUER = "https://idp.test/realms/home"


class NoteState(StrEnum):
    OPEN = "open"


class NoteData(BaseModel):
    text: str = Field(min_length=1, max_length=200)


class Note(Resource):
    kind = "note"
    State = NoteState
    Data = NoteData
    initial = NoteState.OPEN
    terminal: set = set()
    summary = "{data.text} · {state.label}"

    @action(from_=NoteState.OPEN, to=NoteState.OPEN,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Touch"))
    async def touch(self, inp: None, ctx: Ctx) -> None:
        pass


# ── a fake IdP: RSA key, JWKS, discovery, token endpoint ────────────────
from cryptography.hazmat.primitives.asymmetric import rsa  # noqa: E402

_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


class _FakeJWKSClient:
    def get_signing_key_from_jwt(self, token):
        class K:
            key = _KEY.public_key()
        return K()


def _id_token(sub: str, email: str, name: str) -> str:
    return jwt.encode(
        {"sub": sub, "email": email, "name": name, "iss": ISSUER,
         "aud": "mealplan", "exp": int(time.time()) + 300},
        _KEY, algorithm="RS256", headers={"kid": "k1"})


def _fake_idp(sub: str, email: str, name: str,
              end_session: bool = False) -> AsyncClient:
    def handler(request):
        if request.url.path.endswith("openid-configuration"):
            config = {
                "authorization_endpoint": f"{ISSUER}/auth",
                "token_endpoint": f"{ISSUER}/token",
                "jwks_uri": f"{ISSUER}/certs",
            }
            if end_session:
                config["end_session_endpoint"] = f"{ISSUER}/logout"
            return Response(200, json=config)
        if request.url.path.endswith("/token"):
            return Response(200, json={
                "id_token": _id_token(sub, email, name),
                "access_token": "at", "token_type": "Bearer"})
        return Response(404)
    return AsyncClient(transport=MockTransport(handler))


@pytest.fixture
async def env():
    engine = waymark7.Engine(resources=[Note], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    admin = AsyncClient(
        transport=ASGITransport(app=app), base_url="http://t",
        headers={"X-Principal-Id": "colton", "X-Principal-Display": "Colton"})
    try:
        yield engine, app, admin
    finally:
        await admin.aclose()
        await engine.shutdown()


async def _invite(admin, email="mom@example.com", name="Grandma",
                  roles=None) -> dict:
    # member roles validate at invite against the role registry (design
    # §10): register any role the invite names — a typo'd role used to
    # pass here and grant nobody until a grant failed weeks later
    for role in roles or []:
        await admin.post("/api/roles", json={"name": role},
                         headers={"Idempotency-Key": uuid.uuid4().hex})
    res = await admin.post(
        "/api/members",
        json={"email": email, "display_name": name, "roles": roles or []},
        headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    return res.json()


async def test_member_lifecycle_is_ordinary_waymark(env):
    engine, app, admin = env
    doc = await _invite(admin)
    assert doc["state"] == "invited"
    assert doc["data"]["invited_by"] == "colton"

    # the console is the collection; the binding is a transition
    listing = (await admin.get("/api/members?state=invited")).json()
    assert listing["data"]["total"] == 1
    res = await admin.post(f"{doc['self']}/-/activate",
                           json={"subject": "idp|abc123"},
                           headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 200 and res.json()["state"] == "active"
    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(
            s, "member", doc["self"].rsplit("/", 1)[-1])
    assert last.action == "activate"


async def test_oidc_first_login_binds_the_invited_member(env):
    engine, app, admin = env
    invited = await _invite(admin, email="mom@example.com", name="Grandma",
                            roles=["reader"])

    oidc = OIDCResolver(issuer=ISSUER, client_id="mealplan",
                        session_secret="s3cret",
                        http=_fake_idp("idp|mom", "mom@example.com", "Grandma"),
                        jwks_client=_FakeJWKSClient())
    app.include_router(oidc.routes(engine))
    browser = AsyncClient(transport=ASGITransport(app=app),
                          base_url="http://t")

    # login: a redirect to the IdP with PKCE, state stashed in a cookie
    res = await browser.get("/auth/login")
    assert res.status_code == 302
    location = res.headers["location"]
    assert location.startswith(f"{ISSUER}/auth?")
    assert "code_challenge=" in location
    from urllib.parse import parse_qs, urlsplit

    state = parse_qs(urlsplit(location).query)["state"][0]

    # callback: code → id_token (verified) → member bound → session cookie
    res = await browser.get(f"/auth/callback?code=xyz&state={state}")
    assert res.status_code == 302, res.text
    session = res.cookies.get(SESSION_COOKIE)
    assert session

    # the invited member is now active, bound to the sub — audited
    member = (await admin.get(invited["self"])).json()
    assert member["state"] == "active"
    assert member["data"]["subject"] == "idp|mom"

    # the session resolves to a member principal with the member's roles
    class FakeRequest:
        cookies = {SESSION_COOKIE: session}

    principal = await oidc(FakeRequest())
    assert principal.id == f"member:{invited['self'].rsplit('/', 1)[-1]}"
    assert principal.display == "Grandma"
    assert "reader" in principal.roles


async def test_oidc_uninvited_is_refused(env):
    engine, app, admin = env
    oidc = OIDCResolver(issuer=ISSUER, client_id="mealplan",
                        session_secret="s3cret",
                        http=_fake_idp("idp|stranger", "who@else.com", "Who"),
                        jwks_client=_FakeJWKSClient())
    app.include_router(oidc.routes(engine))
    browser = AsyncClient(transport=ASGITransport(app=app),
                          base_url="http://t")
    res = await browser.get("/auth/login")
    from urllib.parse import parse_qs, urlsplit

    state = parse_qs(urlsplit(res.headers["location"]).query)["state"][0]
    res = await browser.get(f"/auth/callback?code=xyz&state={state}")
    assert res.status_code == 403
    assert "invited" in res.json()["detail"]


async def test_logout_ends_the_idp_session_too(env):
    """RP-initiated logout: an IdP advertising end_session_endpoint gets
    the browser (302) with post_logout_redirect_uri + client_id; the local
    session cookie clears in the same response."""
    engine, app, admin = env
    oidc = OIDCResolver(issuer=ISSUER, client_id="mealplan",
                        session_secret="s3cret",
                        http=_fake_idp("idp|mom", "mom@example.com", "Grandma",
                                       end_session=True),
                        jwks_client=_FakeJWKSClient())
    app.include_router(oidc.routes(engine))
    browser = AsyncClient(transport=ASGITransport(app=app),
                          base_url="http://t")
    res = await browser.get("/auth/logout")
    assert res.status_code == 302
    from urllib.parse import parse_qs, urlsplit

    parts = urlsplit(res.headers["location"])
    assert res.headers["location"].startswith(f"{ISSUER}/logout?")
    q = parse_qs(parts.query)
    assert q["client_id"] == ["mealplan"]
    assert q["post_logout_redirect_uri"] == ["http://t/"]
    set_cookie = res.headers.get("set-cookie", "")
    assert SESSION_COOKIE in set_cookie, "the local session clears too"


async def test_logout_without_end_session_endpoint_stays_local(env):
    engine, app, admin = env
    oidc = OIDCResolver(issuer=ISSUER, client_id="mealplan",
                        session_secret="s3cret",
                        http=_fake_idp("idp|mom", "mom@example.com", "Grandma"),
                        jwks_client=_FakeJWKSClient())
    app.include_router(oidc.routes(engine))
    browser = AsyncClient(transport=ASGITransport(app=app),
                          base_url="http://t")
    res = await browser.get("/auth/logout")
    assert res.status_code == 302
    assert res.headers["location"] == "/"
    assert SESSION_COOKIE in res.headers.get("set-cookie", "")


async def test_tampered_session_is_anonymous(env):
    engine, app, admin = env
    oidc = OIDCResolver(issuer=ISSUER, client_id="mealplan",
                        session_secret="s3cret")
    good = oidc.session_cookie_for(principal="member:1", display="D",
                                   roles=[])
    body, _, _ = good.rpartition(".")
    forged = body + "." + "A" * 43

    class FakeRequest:
        cookies = {SESSION_COOKIE: forged}

    principal = await oidc(FakeRequest())
    assert principal.id == "anonymous"
