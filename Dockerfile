# Serves the mealplan7 app: a waymark7 engine behind uvicorn.
# Build:  docker buildx build --platform linux/arm64 -t docker.kopsa.info/mealplan:<tag> --push .
FROM ghcr.io/astral-sh/uv:python3.13-bookworm-slim

WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy

# Dependencies only; sources are imported from /app, not installed as a wheel
# (the wheel ships the waymark packages but not mealplan7).
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-install-project

COPY README.md ./
# mealplan7 runs on waymark7 alone; the earlier waymark packages are copied
# only so the wheel build (hatch packages = waymark…waymark7) resolves and
# the /cli wheel ships the waymark7 client agents bootstrap with.
COPY waymark/ waymark/
COPY waymark2/ waymark2/
COPY waymark3/ waymark3/
COPY waymark4/ waymark4/
COPY waymark5/ waymark5/
COPY waymark6/ waymark6/
COPY waymark7/ waymark7/
COPY mealplan7/ mealplan7/

# Wheel of exactly this source tree, served by the app at /cli (see
# mealplan7/main.py) so agents can install the waymark7 CLI from this host.
RUN uv build --wheel -o dist

EXPOSE 8000
# python -m puts /app on sys.path so the mealplan7 package resolves from source.
# --proxy-headers: trust traefik's X-Forwarded-Proto so generated URLs are https.
CMD ["uv", "run", "--no-sync", "python", "-m", "uvicorn", "mealplan7.main:app", \
     "--host", "0.0.0.0", "--port", "8000", \
     "--proxy-headers", "--forwarded-allow-ips", "*"]
