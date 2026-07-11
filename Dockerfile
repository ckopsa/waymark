# Serves the mealplan9 app: a waymark9 engine behind uvicorn.
# Build:  docker buildx build --platform linux/arm64 -t docker.kopsa.info/mealplan:<tag> --push .
FROM ghcr.io/astral-sh/uv:python3.13-bookworm-slim

WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy

# Dependencies only; sources are imported from /app, not installed as a wheel
# (the wheel ships the waymark packages but not mealplan9).
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-install-project

COPY README.md ./
# mealplan9 runs on waymark9; the hatch wheel build ships every waymark
# package (7/8/9), so all three are copied even though only 9 serves.
COPY waymark7/ waymark7/
COPY waymark8/ waymark8/
COPY waymark9/ waymark9/
COPY mealplan9/ mealplan9/

# Wheel of exactly this source tree, served by the app at /cli (see
# mealplan9/main.py) so agents can install the waymark9 CLI from this host.
RUN uv build --wheel -o dist

EXPOSE 8000
# python -m puts /app on sys.path so the mealplan9 package resolves from source.
# --proxy-headers: trust traefik's X-Forwarded-Proto so generated URLs are https.
CMD ["uv", "run", "--no-sync", "python", "-m", "uvicorn", "mealplan9.main:app", \
     "--host", "0.0.0.0", "--port", "8000", \
     "--proxy-headers", "--forwarded-allow-ips", "*"]
