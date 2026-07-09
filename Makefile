# Local Postgres for the test suite runs in docker.
PG_CONTAINER ?= waymark-test-pg
PG_USER      ?= ckopsa
PG_PORT      ?= 5433
TEST_DSN     ?= postgresql+asyncpg://$(PG_USER)@localhost:$(PG_PORT)/waymark_test

.PHONY: db test conformance check dist

dist:  ## rebuild the CLI wheel served at /cli (stale wheels break agent bootstrap)
	uv build

db:  ## start dockerized Postgres and ensure waymark_test exists
	@docker start $(PG_CONTAINER) >/dev/null 2>&1 || \
		docker run -d --name $(PG_CONTAINER) \
			-e POSTGRES_USER=$(PG_USER) \
			-e POSTGRES_DB=waymark_test \
			-e POSTGRES_HOST_AUTH_METHOD=trust \
			-p $(PG_PORT):5432 postgres:16 >/dev/null
	@until docker exec $(PG_CONTAINER) pg_isready -U $(PG_USER) -q; do sleep 0.5; done

test: db  ## framework tests (xdist: one database per worker)
	WAYMARK_TEST_DSN=$(TEST_DSN) uv run pytest -n auto

conformance: db  ## waymark7 conformance suite (walks the app enrolled on this branch)
	WAYMARK_TEST_DSN=$(TEST_DSN) uv run pytest --waymark7 -n auto

check:  ## import-time definition checks (CI fast path); pass ENGINE=module:attr
	uv run waymark7 check $(ENGINE)
