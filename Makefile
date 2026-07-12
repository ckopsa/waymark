# Local Postgres for the test suite runs in docker.
PG_CONTAINER ?= waymark-test-pg
PG_USER      ?= ckopsa
PG_PORT      ?= 5433
TEST_DSN     ?= postgresql+asyncpg://$(PG_USER)@localhost:$(PG_PORT)/waymark_test

# mealplan7 dev server + deploy
PORT         ?= 8005
ENGINE       ?= mealplan7.main:engine
MEALPLAN_DSN ?= postgresql+asyncpg://$(PG_USER)@localhost:$(PG_PORT)/mealplan7_dev
# The home cluster's only node is ARM64; cross-building needs qemu binfmt
# (one-time: docker run --privileged --rm tonistiigi/binfmt --install arm64).
IMAGE      ?= docker.kopsa.info/mealplan
TEST_IMAGE ?= docker.kopsa.info/waymark-test
IMAGE_TAG  ?= $(shell git rev-parse --short HEAD)$(shell git diff --quiet HEAD 2>/dev/null || echo -dirty)
PLATFORM   ?= linux/arm64

# Cluster access for `make deploy`/`make test-image`, from the infra repo's
# secrets unless set.
INFRA_SECRETS ?= $(HOME)/dev/home-infrastructure/terraform/secrets.local.json
NOMAD_ADDR    ?= $(shell python3 -c "import json;print(json.load(open('$(INFRA_SECRETS)'))['nomad_address'])" 2>/dev/null)
NOMAD_TOKEN   ?= $(shell python3 -c "import json;print(json.load(open('$(INFRA_SECRETS)'))['nomad_token'])" 2>/dev/null)

.PHONY: db test conformance conformance8 conformance9 check dist dev image deploy test-image db10 test10 test-mealplan10 dev10 migrate10 check10

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

conformance8: db  ## waymark8 conformance suite (mealplan8, the expression-law fork)
	WAYMARK_TEST_DSN=$(TEST_DSN) uv run pytest --waymark8 -n auto

conformance9: db  ## waymark9 conformance suite (mealplan9)
	WAYMARK_TEST_DSN=$(TEST_DSN) uv run pytest --waymark9 -n auto

check10:  ## mealplan10 declaration-time checks + usability warnings (no database)
	cd mealplan10 && clojure -M:check

test10: db10  ## waymark10 (Clojure) framework tests
	cd waymark10 && WAYMARK10_TEST_DSN="jdbc:postgresql://localhost:$(PG_PORT)/waymark10_test?user=$(PG_USER)" clojure -M:test

test-mealplan10: db10  ## mealplan10 conformance + family-week story
	cd mealplan10 && WAYMARK10_TEST_DSN="jdbc:postgresql://localhost:$(PG_PORT)/waymark10_test?user=$(PG_USER)" clojure -M:test

dev10: db10  ## serve mealplan10 on :8010 against mealplan10_dev (FakeEvents unless MEALPLAN_GCAL_ICS_URL is set)
	cd mealplan10 && MEALPLAN10_DSN="jdbc:postgresql://localhost:$(PG_PORT)/mealplan10_dev?user=$(PG_USER)" \
		WAYMARK10_AUTO_MIGRATE=1 clojure -M:dev

migrate10: db10  ## print mealplan10's schema plan against mealplan10_dev; APPLY=1 executes, DESTRUCTIVE=1 includes state renames
	cd mealplan10 && MEALPLAN10_DSN="jdbc:postgresql://localhost:$(PG_PORT)/mealplan10_dev?user=$(PG_USER)" \
		clojure -M:migrate

db10: db  ## waymark10 databases on the shared :5433 container
	@docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -tc \
		"SELECT 1 FROM pg_database WHERE datname='waymark10_test'" | grep -q 1 || \
		docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -c "CREATE DATABASE waymark10_test"
	@docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -tc \
		"SELECT 1 FROM pg_database WHERE datname='mealplan10_dev'" | grep -q 1 || \
		docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -c "CREATE DATABASE mealplan10_dev"

check:  ## import-time definition checks (CI fast path); pass ENGINE=module:attr
	uv run waymark7 check $(ENGINE)

dev: db dist  ## run the meal planner on waymark7 with auto-reload (mealplan7_dev)
	@createdb -h localhost -p $(PG_PORT) -U $(PG_USER) mealplan7_dev 2>/dev/null || true
	@echo "ui → http://localhost:$(PORT)/"
	MEALPLAN_DSN=$(MEALPLAN_DSN) uv run uvicorn mealplan7.main:app --reload \
		--port $(PORT) --timeout-graceful-shutdown 3

image:  ## build and push the mealplan image for the home cluster
	docker buildx build --platform $(PLATFORM) -t $(IMAGE):$(IMAGE_TAG) --push .
	@echo "pushed $(IMAGE):$(IMAGE_TAG)"

deploy: image  ## push image, then roll meals.kopsa.info onto it via nomad variable
	@NOMAD_ADDR=$(NOMAD_ADDR) NOMAD_TOKEN=$(NOMAD_TOKEN) \
		nomad var put -force nomad/jobs/mealplan/deploy image_tag=$(IMAGE_TAG) >/dev/null
	@echo "deploying $(IMAGE):$(IMAGE_TAG) — nomad restarts the server task on the new image"

test-image:  ## build+push the arm64 test/conformance image and point the nomad job at it
	docker buildx build --platform $(PLATFORM) -f Dockerfile.test -t $(TEST_IMAGE):$(IMAGE_TAG) --push .
	@echo "pushed $(TEST_IMAGE):$(IMAGE_TAG)"
	@NOMAD_ADDR=$(NOMAD_ADDR) NOMAD_TOKEN=$(NOMAD_TOKEN) \
		nomad var put -force nomad/jobs/waymark-conformance/deploy image_tag=$(IMAGE_TAG) >/dev/null
	@echo "set nomad/jobs/waymark-conformance/deploy image_tag=$(IMAGE_TAG) — next run uses it"
