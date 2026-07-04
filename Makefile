# Local Postgres runs in docker (host :5432 belongs to another project).
PORT         ?= 8000
PG_CONTAINER ?= waymark-test-pg
PG_USER      ?= ckopsa
PG_PORT      ?= 5433
DEV_DSN      ?= postgresql+asyncpg://$(PG_USER)@localhost:$(PG_PORT)/waymark_dev
TEST_DSN     ?= postgresql+asyncpg://$(PG_USER)@localhost:$(PG_PORT)/waymark_test

MEALPLAN_DSN ?= postgresql+asyncpg://$(PG_USER)@localhost:$(PG_PORT)/mealplan_dev

.PHONY: dev db test conformance check demo mealplan

dev: db  ## run the example shop with auto-reload
	@echo "ui  → http://localhost:$(PORT)/api/-/ui"
	@echo "api → http://localhost:$(PORT)/api/.well-known/waymark"
	WAYMARK_DSN=$(DEV_DSN) uv run uvicorn app.main:app --reload --port $(PORT)

db:  ## start dockerized Postgres and ensure waymark_dev exists
	@docker start $(PG_CONTAINER) >/dev/null 2>&1 || \
		docker run -d --name $(PG_CONTAINER) \
			-e POSTGRES_USER=$(PG_USER) \
			-e POSTGRES_DB=waymark_test \
			-e POSTGRES_HOST_AUTH_METHOD=trust \
			-p $(PG_PORT):5432 postgres:16 >/dev/null
	@until docker exec $(PG_CONTAINER) pg_isready -U $(PG_USER) -q; do sleep 0.5; done
	@for db in waymark_dev mealplan_dev; do \
		docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d waymark_test -Atc \
			"SELECT 1 FROM pg_database WHERE datname='$$db'" | grep -q 1 || \
			docker exec $(PG_CONTAINER) createdb -U $(PG_USER) $$db; \
	done

test: db  ## framework tests
	WAYMARK_TEST_DSN=$(TEST_DSN) uv run pytest

conformance: db  ## conformance suite against the example app
	WAYMARK_TEST_DSN=$(TEST_DSN) uv run pytest --waymark

check:  ## import-time definition checks (CI fast path)
	uv run waymark check

mealplan: db  ## run the family meal planner with auto-reload
	@echo "ui  → http://localhost:$(PORT)/api/-/ui"
	MEALPLAN_DSN=$(MEALPLAN_DSN) uv run uvicorn mealplan.main:app --reload --port $(PORT)

demo: db  ## agent demo (plans over effect.to, stops at safety.confirm)
	WAYMARK_DSN=$(DEV_DSN) uv run python scripts/agent_demo.py
