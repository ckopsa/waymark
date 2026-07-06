# Local Postgres runs in docker (host :5432 belongs to another project).
PORT         ?= 8000
PORT3        ?= 8001  # mealplan3 dev server; :8000 stays free for the waymark2 mealplan
PG_CONTAINER ?= waymark-test-pg
PG_USER      ?= ckopsa
PG_PORT      ?= 5433
DEV_DSN      ?= postgresql+asyncpg://$(PG_USER)@localhost:$(PG_PORT)/waymark_dev
TEST_DSN     ?= postgresql+asyncpg://$(PG_USER)@localhost:$(PG_PORT)/waymark_test

MEALPLAN_DSN ?= postgresql+asyncpg://$(PG_USER)@localhost:$(PG_PORT)/mealplan_dev

# The home cluster's only node is ARM64; cross-building needs qemu binfmt
# (one-time: docker run --privileged --rm tonistiigi/binfmt --install arm64).
IMAGE     ?= docker.kopsa.info/mealplan
IMAGE_TAG ?= $(shell git rev-parse --short HEAD)$(shell git diff --quiet HEAD 2>/dev/null || echo -dirty)
PLATFORM  ?= linux/arm64

# Cluster access for `make deploy`, from the infra repo's secrets unless set.
INFRA_SECRETS ?= $(HOME)/dev/home-infrastructure/terraform/secrets.local.json
NOMAD_ADDR    ?= $(shell python3 -c "import json;print(json.load(open('$(INFRA_SECRETS)'))['nomad_address'])" 2>/dev/null)
NOMAD_TOKEN   ?= $(shell python3 -c "import json;print(json.load(open('$(INFRA_SECRETS)'))['nomad_token'])" 2>/dev/null)

.PHONY: dev db dist test conformance conformance3 check demo mealplan mealplan3 image deploy

dist:  ## rebuild the CLI wheel served at /cli (stale wheels break agent bootstrap)
	uv build

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

test: db  ## framework tests (xdist: one database per worker)
	WAYMARK_TEST_DSN=$(TEST_DSN) uv run pytest -n auto

conformance: db  ## conformance suite against the example app
	WAYMARK_TEST_DSN=$(TEST_DSN) uv run pytest --waymark -n auto

conformance3: db  ## waymark3 conformance against the mealplan3 dogfood
	WAYMARK_TEST_DSN=$(TEST_DSN) uv run pytest --waymark3 -n auto

check:  ## import-time definition checks (CI fast path)
	uv run waymark check

mealplan: db  ## run the family meal planner with auto-reload
	@echo "ui  → http://localhost:$(PORT)/api/-/ui"
	MEALPLAN_DSN=$(MEALPLAN_DSN) uv run uvicorn mealplan.main:app --reload --port $(PORT)

mealplan3: db dist  ## run the meal planner on waymark3 (mealplan3_dev)
	@docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d waymark_test -Atc \
		"SELECT 1 FROM pg_database WHERE datname='mealplan3_dev'" | grep -q 1 || \
		docker exec $(PG_CONTAINER) createdb -U $(PG_USER) mealplan3_dev
	@echo "ui  → http://localhost:$(PORT3)/api/-/ui"
	MEALPLAN_DSN=postgresql+asyncpg://$(PG_USER)@localhost:$(PG_PORT)/mealplan3_dev \
		uv run uvicorn mealplan3.main:app --reload --port $(PORT3) \
		--timeout-graceful-shutdown 3  # open SSE streams otherwise wedge every reload

demo: db  ## agent demo (plans over effect.to, stops at safety.confirm)
	WAYMARK_DSN=$(DEV_DSN) uv run python scripts/agent_demo.py

image:  ## build and push the mealplan image for the home cluster
	docker buildx build --platform $(PLATFORM) -t $(IMAGE):$(IMAGE_TAG) --push .
	@echo "pushed $(IMAGE):$(IMAGE_TAG)"

deploy: image  ## push image, then roll meals.kopsa.info onto it via nomad variable
	@NOMAD_ADDR=$(NOMAD_ADDR) NOMAD_TOKEN=$(NOMAD_TOKEN) \
		nomad var put -force nomad/jobs/mealplan/deploy image_tag=$(IMAGE_TAG) >/dev/null
	@echo "deploying $(IMAGE):$(IMAGE_TAG) — nomad restarts the server task on the new image"
	@echo "note: a repeat -dirty tag at the same commit will NOT redeploy; commit or pass IMAGE_TAG="
