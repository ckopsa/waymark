# Local Postgres for the test suites runs in docker.
PG_CONTAINER ?= waymark-test-pg
PG_USER      ?= ckopsa
PG_PORT      ?= 5433

# mealplan10 image + deploy (the home cluster's only node is ARM64;
# cross-building needs qemu binfmt, one-time per boot:
# docker run --privileged --rm tonistiigi/binfmt --install arm64)
IMAGE      ?= docker.kopsa.info/mealplan10
IMAGE_TAG  ?= $(shell git rev-parse --short HEAD)$(shell git diff --quiet HEAD 2>/dev/null || echo -dirty)
PLATFORM   ?= linux/arm64

# Cluster access for `make deploy10`, from the infra repo's secrets
# unless set.
INFRA_SECRETS ?= $(HOME)/dev/home-infrastructure/terraform/secrets.local.json
NOMAD_ADDR    ?= $(shell python3 -c "import json;print(json.load(open('$(INFRA_SECRETS)'))['nomad_address'])" 2>/dev/null)
NOMAD_TOKEN   ?= $(shell python3 -c "import json;print(json.load(open('$(INFRA_SECRETS)'))['nomad_token'])" 2>/dev/null)

# paydesk's dev warehouse mirror, over the standard `ssh -fN paydesk-db-dev`
# tunnel (:5432); credential is the paydesk_app_dev Proton Pass item.
# Unset (tunnel down / pass-cli unavailable) ⇒ paydesk falls back to its
# in-memory fake mirrors.
PAYDESK_WAREHOUSE_PORT ?= 5432
PAYDESK_WAREHOUSE_DSN  ?= $(shell scripts/paydesk-warehouse-dsn.sh $(PAYDESK_WAREHOUSE_PORT) 2>/dev/null)

# paydesk-prod runs on the laptop: its own database is the local paydesk_prod
# on :5433 (created host-side — the docker publish is shadowed by a
# native Postgres on this port, so createdb must run against whatever
# actually answers), and the mirror boundary is the PROD warehouse
# (`ssh -fN paydesk-db-prod` → :15435, db_readwrite — read/write on
# client_assignment per the push posture).
PAYDESK_PROD_DSN           ?= jdbc:postgresql://localhost:$(PG_PORT)/paydesk_prod?user=$(PG_USER)
PAYDESK_PROD_WAREHOUSE_DSN ?= $(shell scripts/paydesk-warehouse-dsn.sh 15435 db_readwrite devdb 2>/dev/null)

.PHONY: db db10 test10 check10 test-mealplan10 migrate-paydesk-prod paydesk-prod db-paydesk-prod dev10 migrate10 test-eveningplan10 dev-eveningplan10 migrate-eveningplan10 check-eveningplan10 test-paydesk dev-paydesk migrate-paydesk check-paydesk test-chores dev-chores migrate-chores check-chores image-chores deploy-chores test-queue dev-queue migrate-queue check-queue image-queue deploy-queue image10 deploy10

db:  ## start dockerized Postgres
	@docker start $(PG_CONTAINER) >/dev/null 2>&1 || \
		docker run -d --name $(PG_CONTAINER) \
			-e POSTGRES_USER=$(PG_USER) \
			-e POSTGRES_DB=waymark_test \
			-e POSTGRES_HOST_AUTH_METHOD=trust \
			-p $(PG_PORT):5432 postgres:16 >/dev/null
	@until docker exec $(PG_CONTAINER) pg_isready -U $(PG_USER) -q; do sleep 0.5; done

db10: db  ## waymark10 databases on the shared :5433 container
	@docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -tc \
		"SELECT 1 FROM pg_database WHERE datname='waymark10_test'" | grep -q 1 || \
		docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -c "CREATE DATABASE waymark10_test"
	@docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -tc \
		"SELECT 1 FROM pg_database WHERE datname='mealplan10_dev'" | grep -q 1 || \
		docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -c "CREATE DATABASE mealplan10_dev"
	@docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -tc \
		"SELECT 1 FROM pg_database WHERE datname='eveningplan10_dev'" | grep -q 1 || \
		docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -c "CREATE DATABASE eveningplan10_dev"
	@docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -tc \
		"SELECT 1 FROM pg_database WHERE datname='paydesk_dev'" | grep -q 1 || \
		docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -c "CREATE DATABASE paydesk_dev"
	@docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -tc \
		"SELECT 1 FROM pg_database WHERE datname='choreplan10_dev'" | grep -q 1 || \
		docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -c "CREATE DATABASE choreplan10_dev"
	@docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -tc \
		"SELECT 1 FROM pg_database WHERE datname='workqueue10_dev'" | grep -q 1 || \
		docker exec $(PG_CONTAINER) psql -U $(PG_USER) -d postgres -c "CREATE DATABASE workqueue10_dev"

test10: db10  ## waymark10 (Clojure) framework tests
	cd waymark10 && WAYMARK10_TEST_DSN="jdbc:postgresql://localhost:$(PG_PORT)/waymark10_test?user=$(PG_USER)" clojure -M:test

check10:  ## mealplan10 declaration-time checks + usability warnings (no database)
	cd mealplan10 && clojure -M:check

test-mealplan10: db10  ## mealplan10 conformance + family-week story
	cd mealplan10 && WAYMARK10_TEST_DSN="jdbc:postgresql://localhost:$(PG_PORT)/waymark10_test?user=$(PG_USER)" clojure -M:test

dev10: db10  ## serve mealplan10 on :8010 against mealplan10_dev (FakeEvents unless MEALPLAN_GCAL_ICS_URL is set)
	cd mealplan10 && MEALPLAN10_DSN="jdbc:postgresql://localhost:$(PG_PORT)/mealplan10_dev?user=$(PG_USER)" \
		WAYMARK10_AUTO_MIGRATE=1 clojure -M:dev

migrate10: db10  ## print mealplan10's schema plan against mealplan10_dev; APPLY=1 executes, DESTRUCTIVE=1 includes state renames
	cd mealplan10 && MEALPLAN10_DSN="jdbc:postgresql://localhost:$(PG_PORT)/mealplan10_dev?user=$(PG_USER)" \
		clojure -M:migrate

image10:  ## RETIRED (waymark-bwu.2): mealplan10 folded into workqueue10 — use deploy-queue
	@echo "mealplan10 folded into workqueue10 (waymark-bwu.2, 2026-07-24) — use 'make deploy-queue'." >&2; exit 1

deploy10:  ## RETIRED (waymark-bwu.2): mealplan10 folded into workqueue10 — use deploy-queue
	@echo "mealplan10 folded into workqueue10 (waymark-bwu.2, 2026-07-24) — use 'make deploy-queue'." >&2; exit 1

check-eveningplan10:  ## eveningplan10 declaration-time checks + usability warnings (no database)
	cd eveningplan10 && clojure -M:check

test-eveningplan10: db10  ## eveningplan10 conformance suite
	cd eveningplan10 && WAYMARK10_TEST_DSN="jdbc:postgresql://localhost:$(PG_PORT)/waymark10_test?user=$(PG_USER)" clojure -M:test

dev-eveningplan10: db10  ## serve eveningplan10 on :8011 against eveningplan10_dev
	cd eveningplan10 && EVENINGPLAN10_DSN="jdbc:postgresql://localhost:$(PG_PORT)/eveningplan10_dev?user=$(PG_USER)" \
		WAYMARK10_AUTO_MIGRATE=1 clojure -M:dev

migrate-eveningplan10: db10  ## print eveningplan10's schema plan against eveningplan10_dev; APPLY=1 executes, DESTRUCTIVE=1 includes state renames
	cd eveningplan10 && EVENINGPLAN10_DSN="jdbc:postgresql://localhost:$(PG_PORT)/eveningplan10_dev?user=$(PG_USER)" \
		clojure -M:migrate

check-paydesk:  ## paydesk declaration-time checks + usability warnings (no database)
	cd paydesk && clojure -M:check

test-paydesk: db10  ## paydesk conformance suite
	cd paydesk && WAYMARK10_TEST_DSN="jdbc:postgresql://localhost:$(PG_PORT)/waymark10_test?user=$(PG_USER)" clojure -M:test

dev-paydesk: db10  ## serve paydesk on :8012 against paydesk_dev; mirrors the dev warehouse over :5432 if PAYDESK_WAREHOUSE_DSN resolves (see PAYDESK_WAREHOUSE_PORT), else fake adapters
	@cd paydesk && PAYDESK_DSN="jdbc:postgresql://localhost:$(PG_PORT)/paydesk_dev?user=$(PG_USER)" \
		PAYDESK_WAREHOUSE_DSN="$(PAYDESK_WAREHOUSE_DSN)" \
		WAYMARK10_AUTO_MIGRATE=1 clojure -M:dev

migrate-paydesk: db10  ## print paydesk's schema plan against paydesk_dev; APPLY=1 executes, DESTRUCTIVE=1 includes state renames
	cd paydesk && PAYDESK_DSN="jdbc:postgresql://localhost:$(PG_PORT)/paydesk_dev?user=$(PG_USER)" \
		clojure -M:migrate

db-paydesk-prod:  ## the paydesk_prod database on the host-reachable :5433
	@psql -h localhost -p $(PG_PORT) -U $(PG_USER) -d postgres -tc \
		"SELECT 1 FROM pg_database WHERE datname='paydesk_prod'" | grep -q 1 || \
		createdb -h localhost -p $(PG_PORT) -U $(PG_USER) paydesk_prod

migrate-paydesk-prod: db-paydesk-prod  ## print paydesk's schema plan against the local paydesk_prod; APPLY=1 executes
	@cd paydesk && PAYDESK_DSN="$(PAYDESK_PROD_DSN)" clojure -M:migrate

paydesk-prod: db-paydesk-prod  ## serve paydesk on :8013 against local paydesk_prod + the PROD warehouse (needs `ssh -fN paydesk-db-prod`); refuses on schema drift — migrate-paydesk-prod first
	@cd paydesk && PAYDESK_DSN="$(PAYDESK_PROD_DSN)" \
		PAYDESK_WAREHOUSE_DSN="$(PAYDESK_PROD_WAREHOUSE_DSN)" \
		PAYDESK_PORT=8013 clojure -M:dev

check-chores:  ## choreplan10 declaration-time checks + usability warnings (no database)
	cd choreplan10 && clojure -M:check

test-chores: db10  ## choreplan10 conformance suite
	cd choreplan10 && WAYMARK10_TEST_DSN="jdbc:postgresql://localhost:$(PG_PORT)/waymark10_test?user=$(PG_USER)" clojure -M:test

dev-chores: db10  ## serve choreplan10 on :8013 against choreplan10_dev
	cd choreplan10 && CHOREPLAN10_DSN="jdbc:postgresql://localhost:$(PG_PORT)/choreplan10_dev?user=$(PG_USER)" \
		WAYMARK10_AUTO_MIGRATE=1 clojure -M:dev

migrate-chores: db10  ## print choreplan10's schema plan against choreplan10_dev; APPLY=1 executes, DESTRUCTIVE=1 includes state renames
	cd choreplan10 && CHOREPLAN10_DSN="jdbc:postgresql://localhost:$(PG_PORT)/choreplan10_dev?user=$(PG_USER)" \
		clojure -M:migrate

CHORES_IMAGE ?= docker.kopsa.info/choreplan10

image-chores:  ## RETIRED (waymark-bwu.1): choreplan10 folded into workqueue10 — use deploy-queue
	@echo "choreplan10 folded into workqueue10 (waymark-bwu.1, 2026-07-24) — use 'make deploy-queue'." >&2; exit 1

deploy-chores:  ## RETIRED (waymark-bwu.1): choreplan10 folded into workqueue10 — use deploy-queue
	@echo "choreplan10 folded into workqueue10 (waymark-bwu.1, 2026-07-24) — use 'make deploy-queue'." >&2; exit 1

check-queue:  ## workqueue10 declaration-time checks + usability warnings (no database)
	cd workqueue10 && clojure -M:check

test-queue: db10  ## workqueue10 conformance + family-queue story
	cd workqueue10 && WAYMARK10_TEST_DSN="jdbc:postgresql://localhost:$(PG_PORT)/waymark10_test?user=$(PG_USER)" clojure -M:test

dev-queue: db10  ## serve workqueue10 on :8014 against workqueue10_dev (fake sources unless WORKQUEUE10_CHOREPLAN_URL / _MEALPLAN_URL are set)
	cd workqueue10 && WORKQUEUE10_DSN="jdbc:postgresql://localhost:$(PG_PORT)/workqueue10_dev?user=$(PG_USER)" \
		WAYMARK10_AUTO_MIGRATE=1 clojure -M:dev

migrate-queue: db10  ## print workqueue10's schema plan against workqueue10_dev; APPLY=1 executes, DESTRUCTIVE=1 includes state renames
	cd workqueue10 && WORKQUEUE10_DSN="jdbc:postgresql://localhost:$(PG_PORT)/workqueue10_dev?user=$(PG_USER)" \
		clojure -M:migrate

QUEUE_IMAGE ?= docker.kopsa.info/workqueue10

image-queue:  ## build and push the workqueue10 image for the home cluster
	docker buildx build --platform $(PLATFORM) -f Dockerfile.workqueue10 -t $(QUEUE_IMAGE):$(IMAGE_TAG) --push .
	@echo "pushed $(QUEUE_IMAGE):$(IMAGE_TAG)"

deploy-queue: image-queue  ## push image, then roll the workqueue10 nomad job onto it
	@NOMAD_ADDR=$(NOMAD_ADDR) NOMAD_TOKEN=$(NOMAD_TOKEN) \
		nomad var put -force nomad/jobs/workqueue10/deploy image_tag=$(IMAGE_TAG) >/dev/null
	@echo "deploying $(QUEUE_IMAGE):$(IMAGE_TAG) — nomad restarts the server task on the new image"
