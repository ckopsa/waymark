# Local Postgres for the test suites runs in docker.
PG_CONTAINER ?= waymark-test-pg
PG_USER      ?= ckopsa
PG_PORT      ?= 5433

.PHONY: db db10 test10 check10 test-mealplan10 dev10 migrate10 test-eveningplan10 dev-eveningplan10 migrate-eveningplan10 check-eveningplan10 test-paydesk dev-paydesk migrate-paydesk check-paydesk

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

dev-paydesk: db10  ## serve paydesk on :8012 against paydesk_dev
	cd paydesk && PAYDESK_DSN="jdbc:postgresql://localhost:$(PG_PORT)/paydesk_dev?user=$(PG_USER)" \
		WAYMARK10_AUTO_MIGRATE=1 clojure -M:dev

migrate-paydesk: db10  ## print paydesk's schema plan against paydesk_dev; APPLY=1 executes, DESTRUCTIVE=1 includes state renames
	cd paydesk && PAYDESK_DSN="jdbc:postgresql://localhost:$(PG_PORT)/paydesk_dev?user=$(PG_USER)" \
		clojure -M:migrate
