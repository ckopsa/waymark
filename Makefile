# Local Postgres for the test suites runs in docker.
PG_CONTAINER ?= waymark-test-pg
PG_USER      ?= ckopsa
PG_PORT      ?= 5433

# Image + deploy (the home cluster's only node is ARM64;
# cross-building needs qemu binfmt, one-time per boot:
# docker run --privileged --rm tonistiigi/binfmt --install arm64)
IMAGE_TAG  ?= $(shell git rev-parse --short HEAD)$(shell git diff --quiet HEAD 2>/dev/null || echo -dirty)
PLATFORM   ?= linux/arm64

# Cluster access for `make deploy-queue`, from the infra repo's
# secrets unless set.
INFRA_SECRETS ?= $(HOME)/dev/home-infrastructure/terraform/secrets.local.json
NOMAD_ADDR    ?= $(shell python3 -c "import json;print(json.load(open('$(INFRA_SECRETS)'))['nomad_address'])" 2>/dev/null)
NOMAD_TOKEN   ?= $(shell python3 -c "import json;print(json.load(open('$(INFRA_SECRETS)'))['nomad_token'])" 2>/dev/null)

.PHONY: migrate-queue-prod test-calendar probe-calendar db db10 test10 test-queue dev-queue migrate-queue check-queue image-queue deploy-queue

db:  ## start dockerized Postgres
	@docker start $(PG_CONTAINER) >/dev/null 2>&1 || \
		docker run -d --name $(PG_CONTAINER) \
			-e POSTGRES_USER=$(PG_USER) \
			-e POSTGRES_DB=waymark_test \
			-e POSTGRES_HOST_AUTH_METHOD=trust \
			-p $(PG_PORT):5432 postgres:16 >/dev/null
	@until docker exec $(PG_CONTAINER) pg_isready -U $(PG_USER) -q; do sleep 0.5; done

# The eight test databases live in scripts/test-databases.sh, shared
# with .github/workflows/tests.yml so a database a new test needs cannot
# be added to only one of them. This target used to create just
# waymark10_test; the other seven existed on THIS machine because they
# were made by hand once and the docker volume kept them, which is why
# a suite that passed here could not pass on a clean checkout.
db10: db  ## waymark10 databases on the shared :5433 container
	@./scripts/test-databases.sh | \
		docker exec -i $(PG_CONTAINER) psql -q -U $(PG_USER) -d postgres -f -
	@docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -tc \
		"SELECT 1 FROM pg_database WHERE datname='workqueue10_dev'" | grep -q 1 || \
		docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -c "CREATE DATABASE workqueue10_dev"

test10:  ## (moved to CI) waymark10 framework tests — GitHub Actions runs these
	@echo "Tests run in CI, not here. The GitHub Actions pipeline"
	@echo "(.github/workflows/tests.yml) runs test10, test-queue and"
	@echo "test-calendar sharded on every push, with more horsepower"
	@echo "than a laptop — push your branch and read the 'gate' check."
	@echo ""
	@echo "To run THIS suite by hand anyway, call clojure directly:"
	@echo "  make db10 && cd waymark10 && WAYMARK10_TEST_DSN=jdbc:postgresql://localhost:$(PG_PORT)/waymark10_test?user=$(PG_USER) clojure -M:test"

# The chore, meal and evening modules live under workqueue10/ since
# the consolidation cleanup (waymark-26j): check-queue is their
# declaration gate, test-queue their conformance run, dev-queue the
# one dev server, migrate-queue the one schema plan.

check-queue:  ## workqueue10 declaration-time checks + usability warnings (no database)
	cd workqueue10 && clojure -M:check

test-queue:  ## (moved to CI) the household suite — GitHub Actions runs these
	@echo "Tests run in CI, not here. The GitHub Actions pipeline"
	@echo "(.github/workflows/tests.yml) runs test10, test-queue and"
	@echo "test-calendar sharded on every push, with more horsepower"
	@echo "than a laptop — push your branch and read the 'gate' check."
	@echo ""
	@echo "To run THIS suite by hand anyway, call clojure directly:"
	@echo "  make db10 && cd workqueue10 && WAYMARK10_TEST_DSN=jdbc:postgresql://localhost:$(PG_PORT)/waymark10_test?user=$(PG_USER) clojure -M:test"

dev-queue: db10  ## serve workqueue10 on :8014 against workqueue10_dev (fake sources unless WORKQUEUE10_CHOREPLAN_URL / _MEALPLAN_URL are set)
	cd workqueue10 && WORKQUEUE10_DSN="jdbc:postgresql://localhost:$(PG_PORT)/workqueue10_dev?user=$(PG_USER)" \
		WAYMARK10_AUTO_MIGRATE=1 WAYMARK10_WATCH=1 clojure -M:dev

migrate-queue: db10  ## print workqueue10's schema plan against workqueue10_dev; APPLY=1 executes, DESTRUCTIVE=1 includes state renames
	cd workqueue10 && WORKQUEUE10_DSN="jdbc:postgresql://localhost:$(PG_PORT)/workqueue10_dev?user=$(PG_USER)" \
		clojure -M:migrate

migrate-queue-prod:  ## print PRODUCTION's real schema plan (read-only; refuses APPLY). Needs the LAN.
	@# The read half of the deploy gate, and only the read half
	@# (waymark-yqs). `migrate-queue` plans against workqueue10_dev,
	@# which answers the wrong question in both of its states — stale,
	@# it reports columns prod already has; current, it reports
	@# nothing. This asks prod.
	@#
	@# APPLY is refused on purpose. workqueue10 leaves
	@# WAYMARK10_AUTO_MIGRATE unset deliberately, and a target that
	@# both reads and writes prod's schema would quietly become the
	@# auto-migrate that posture rejects. Reading the plan and running
	@# it deserve different amounts of friction: the statements print
	@# here, a person runs them through `nomad alloc exec … psql`.
	@[ -z "$(APPLY)" ] && [ -z "$(DESTRUCTIVE)" ] || { \
		echo "migrate-queue-prod is READ-ONLY — it prints production's plan and never runs it." >&2; \
		echo "Apply by hand, one statement at a time:" >&2; \
		echo "  nomad alloc exec -task postgres <alloc> psql -U workqueue -d workqueue10 -c 'ALTER TABLE …'" >&2; \
		exit 2; }
	@cd workqueue10 && WORKQUEUE10_DSN="$$(../scripts/queue-prod-dsn.sh)" clojure -M:migrate

test-calendar:  ## (moved to CI) calendar10 transport tests — GitHub Actions runs these
	@echo "Tests run in CI, not here. The GitHub Actions pipeline"
	@echo "(.github/workflows/tests.yml) runs test10, test-queue and"
	@echo "test-calendar sharded on every push, with more horsepower"
	@echo "than a laptop — push your branch and read the 'gate' check."
	@echo ""
	@echo "To run THIS suite by hand anyway, call clojure directly:"
	@echo "  cd calendar10 && clojure -M:test   (no database, no network)"

probe-calendar:  ## prove the calendar transport against the REAL family calendar; WRITE=1 also round-trips a scratch event
	cd calendar10 && clojure -M:probe

QUEUE_IMAGE ?= docker.kopsa.info/workqueue10

image-queue:  ## build and push the workqueue10 image for the home cluster
	docker buildx build --platform $(PLATFORM) -f Dockerfile.workqueue10 -t $(QUEUE_IMAGE):$(IMAGE_TAG) --push .
	@echo "pushed $(QUEUE_IMAGE):$(IMAGE_TAG)"

deploy-queue: image-queue  ## push image, then roll the workqueue10 nomad job onto it
	@NOMAD_ADDR=$(NOMAD_ADDR) NOMAD_TOKEN=$(NOMAD_TOKEN) \
		nomad var put -force nomad/jobs/workqueue10/deploy image_tag=$(IMAGE_TAG) >/dev/null
	@echo "deploying $(QUEUE_IMAGE):$(IMAGE_TAG) — nomad restarts the server task on the new image"
