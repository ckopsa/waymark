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

.PHONY: db test conformance conformance8 check dist dev image deploy test-image

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
