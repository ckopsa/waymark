"""OIDC relying party: one more implementation of the credential-resolver
interface (design §9).

AuthN is externalized — Keycloak (or any OpenID Connect IdP) owns login,
passwords, MFA, federation. Waymark ships only the relying-party dance:
authorization code + PKCE, id_token verification against the issuer's
JWKS, and a signed session cookie. The dev header resolver becomes what it
always secretly was: one resolver among several, kept for tests.

Authorization cannot follow identity out the door: affordances are
advertised per principal, so ``project(instance, principal)`` must know
what you may do in order to render what you may do. IdP claims are
*inputs*: first login binds the ``sub`` to an invited :class:`~.members.
Member` (an ordinary audited transition), and the member's roles ride the
session principal for the visibility computation.

Wiring::

    oidc = OIDCResolver(issuer="https://keycloak.local/realms/home",
                        client_id="mealplan", client_secret="…",
                        session_secret="…", redirect_path="/auth/callback")
    engine = Engine(..., principal=oidc)
    app.include_router(oidc.routes(engine), prefix="")

Invited-only by default: a subject that matches no member (by ``sub``,
then by verified email against an ``invited`` member) is refused —
membership is the admin's to extend, not the IdP's.
"""
from __future__ import annotations

import base64
import hashlib
import json
import secrets
import time
from typing import Any

import httpx
from fastapi import APIRouter, Request
from starlette.responses import JSONResponse, RedirectResponse

SESSION_COOKIE = "waymark_session"
STATE_COOKIE = "waymark_oidc_state"


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


class OIDCResolver:
    def __init__(self, *, issuer: str, client_id: str,
                 client_secret: str | None = None,
                 session_secret: str,
                 redirect_path: str = "/auth/callback",
                 scope: str = "openid profile email",
                 session_hours: int = 12,
                 http: httpx.AsyncClient | None = None,
                 jwks_client: Any = None,
                 open_registration: bool = False):
        self.issuer = issuer.rstrip("/")
        self.client_id = client_id
        self.client_secret = client_secret
        self.session_secret = session_secret.encode()
        self.redirect_path = redirect_path
        self.scope = scope
        self.session_hours = session_hours
        self.http = http or httpx.AsyncClient()
        self._jwks_client = jwks_client  # injectable for tests
        self.open_registration = open_registration
        self._config: dict[str, Any] | None = None

    # ── the resolver interface: request → Principal ─────────────────────
    async def __call__(self, request: Any) -> Any:
        from ..core.types import Principal

        claims = self._read_session(request.cookies.get(SESSION_COOKIE))
        if claims is None:
            return Principal.anonymous()
        return Principal(
            id=claims["principal"], type="human",
            roles=frozenset(claims.get("roles") or []),
            display=claims.get("display") or claims["principal"])

    # ── session cookie: HS256-signed claims, engine-side only ───────────
    def _sign(self, payload: bytes) -> str:
        import hmac

        sig = hmac.new(self.session_secret, payload, hashlib.sha256).digest()
        return _b64url(payload) + "." + _b64url(sig)

    def _read_session(self, cookie: str | None) -> dict[str, Any] | None:
        import hmac

        if not cookie or "." not in cookie:
            return None
        body, _, sig = cookie.rpartition(".")
        try:
            payload = base64.urlsafe_b64decode(body + "==")
            want = hmac.new(self.session_secret, payload,
                            hashlib.sha256).digest()
            if not hmac.compare_digest(_b64url(want), sig):
                return None
            claims = json.loads(payload)
        except Exception:
            return None
        if claims.get("exp", 0) < time.time():
            return None
        return claims

    def session_cookie_for(self, *, principal: str, display: str,
                           roles: list[str]) -> str:
        payload = json.dumps({
            "principal": principal, "display": display, "roles": roles,
            "exp": int(time.time()) + self.session_hours * 3600,
        }).encode()
        return self._sign(payload)

    # ── the OIDC dance ───────────────────────────────────────────────────
    async def _configuration(self) -> dict[str, Any]:
        if self._config is None:
            res = await self.http.get(
                f"{self.issuer}/.well-known/openid-configuration")
            res.raise_for_status()
            self._config = res.json()
        return self._config

    def _verify_id_token(self, id_token: str, jwks_uri: str) -> dict[str, Any]:
        import jwt

        client = self._jwks_client
        if client is None:
            client = jwt.PyJWKClient(jwks_uri)
            self._jwks_client = client
        key = client.get_signing_key_from_jwt(id_token)
        return jwt.decode(id_token, key.key,
                          algorithms=["RS256", "ES256"],
                          audience=self.client_id, issuer=self.issuer)

    async def _bind_member(self, engine: Any, claims: dict[str, Any]) -> Any:
        """sub → member. First login binds an invited member (an audited
        ``activate`` transition run as the system resolver); an unknown
        subject is refused unless open_registration."""
        from ..core.types import Principal

        sub = claims["sub"]
        resolver = Principal(id="oidc-resolver", type="system",
                             display="OIDC resolver")
        async with engine.storage.session() as s:
            bound, _ = await engine.storage.query(
                s, "member", filters={"subject": sub}, sort=None,
                page_size=1, page_number=1)
        if bound:
            return bound[0]
        email = (claims.get("email") or "").lower()
        invited = []
        if email:
            async with engine.storage.session() as s:
                invited, _ = await engine.storage.query(
                    s, "member", filters={"email": email, "state": "invited"},
                    sort=None, page_size=1, page_number=1)
        if invited:
            member = invited[0]
            await engine.invoker.invoke(
                "member", member.id, "activate", {"subject": sub},
                principal=resolver)
            async with engine.storage.session() as s:
                return await engine.storage.load(s, "member", member.id)
        if self.open_registration:
            result = await engine.invoker.create(
                "member", {"email": email or f"{sub}@unknown.invalid",
                           "display_name": claims.get("name") or sub},
                principal=resolver, idempotency_key=f"oidc-join-{sub}")
            member_id = result.doc["self"].rsplit("/", 1)[-1]
            await engine.invoker.invoke(
                "member", member_id, "activate", {"subject": sub},
                principal=resolver)
            async with engine.storage.session() as s:
                return await engine.storage.load(s, "member", member_id)
        return None

    def routes(self, engine: Any) -> Any:
        """The login/callback/logout routes. Mount beside the API."""
        router = APIRouter()

        @router.get("/auth/login")
        async def login(request: Request) -> Any:
            config = await self._configuration()
            verifier = _b64url(secrets.token_bytes(32))
            challenge = _b64url(hashlib.sha256(verifier.encode()).digest())
            state = _b64url(secrets.token_bytes(16))
            redirect_uri = str(request.base_url).rstrip("/") + self.redirect_path
            from urllib.parse import urlencode

            url = config["authorization_endpoint"] + "?" + urlencode({
                "response_type": "code", "client_id": self.client_id,
                "redirect_uri": redirect_uri, "scope": self.scope,
                "state": state, "code_challenge": challenge,
                "code_challenge_method": "S256",
            })
            response = RedirectResponse(url, status_code=302)
            response.set_cookie(
                STATE_COOKIE, self._sign(json.dumps(
                    {"state": state, "verifier": verifier,
                     "redirect_uri": redirect_uri,
                     "exp": int(time.time()) + 600}).encode()),
                httponly=True, samesite="lax", max_age=600)
            return response

        @router.get(self.redirect_path)
        async def callback(request: Request) -> Any:
            stashed = self._read_session(request.cookies.get(STATE_COOKIE))
            if stashed is None \
                    or request.query_params.get("state") != stashed["state"]:
                return JSONResponse({"detail": "state mismatch"}, status_code=400)
            config = await self._configuration()
            form = {
                "grant_type": "authorization_code",
                "code": request.query_params.get("code"),
                "redirect_uri": stashed["redirect_uri"],
                "client_id": self.client_id,
                "code_verifier": stashed["verifier"],
            }
            if self.client_secret:
                form["client_secret"] = self.client_secret
            res = await self.http.post(config["token_endpoint"], data=form)
            if res.status_code != 200:
                return JSONResponse({"detail": "token exchange failed"},
                                    status_code=502)
            claims = self._verify_id_token(res.json()["id_token"],
                                           config["jwks_uri"])
            member = await self._bind_member(engine, claims)
            if member is None or member.state != "active":
                return JSONResponse(
                    {"detail": "No membership for this account. Membership "
                               "is invited — ask an admin to invite you."},
                    status_code=403)
            response = RedirectResponse(f"{engine.base_path}/-/ui",
                                        status_code=302)
            response.set_cookie(
                SESSION_COOKIE,
                self.session_cookie_for(
                    principal=f"member:{member.id}",
                    display=member.data.display_name,
                    roles=list(member.data.roles)),
                httponly=True, samesite="lax",
                max_age=self.session_hours * 3600)
            response.delete_cookie(STATE_COOKIE)
            return response

        @router.get("/auth/logout")
        async def logout(request: Request) -> Any:
            # RP-initiated logout: an IdP that advertises
            # end_session_endpoint gets the browser sent there (and back),
            # so the IdP session dies with ours. The local cookie clears
            # either way — an unreachable IdP must not pin a session.
            target = "/"
            try:
                config = await self._configuration()
            except Exception:
                config = {}
            end_session = config.get("end_session_endpoint")
            if end_session:
                from urllib.parse import urlencode

                target = end_session + "?" + urlencode({
                    "post_logout_redirect_uri": str(request.base_url),
                    "client_id": self.client_id,
                })
            response = RedirectResponse(target, status_code=302)
            response.delete_cookie(SESSION_COOKIE)
            return response

        return router
