# Serves the mealplan app: a waymark2 engine behind uvicorn.
# Build:  docker buildx build --platform linux/arm64 -t docker.kopsa.info/mealplan:<tag> --push .
FROM ghcr.io/astral-sh/uv:python3.13-bookworm-slim

WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy

# Dependencies only; sources are imported from /app, not installed as a wheel
# (the wheel ships waymark/waymark2 but not mealplan).
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-install-project

COPY README.md ./
COPY waymark/ waymark/
COPY waymark2/ waymark2/
COPY mealplan/ mealplan/

# Wheel of exactly this source tree, served by the app at /cli (see
# mealplan/main.py) so agents can install the waymark2 CLI from this host.
RUN uv build --wheel -o dist

EXPOSE 8000
# python -m puts /app on sys.path so the mealplan package resolves from source.
# --proxy-headers: trust traefik's X-Forwarded-Proto so generated URLs are https.
CMD ["uv", "run", "--no-sync", "python", "-m", "uvicorn", "mealplan.main:app", \
     "--host", "0.0.0.0", "--port", "8000", \
     "--proxy-headers", "--forwarded-allow-ips", "*"]
