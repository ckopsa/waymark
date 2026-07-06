-- waymark3 migration: generated from the declared registry.
-- Review before applying; lines marked for review need a human.

CREATE OR REPLACE FUNCTION waymark3_ts(value text)
RETURNS timestamptz
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
RETURN value::timestamptz;

CREATE TABLE approval_requests (
	id VARCHAR(64) NOT NULL, 
	state VARCHAR(64) NOT NULL, 
	version INTEGER NOT NULL, 
	data JSONB NOT NULL, 
	shape INTEGER DEFAULT '1' NOT NULL, 
	owner VARCHAR(128), 
	created_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	grant_id TEXT GENERATED ALWAYS AS ((data->>'grant_id')) STORED, 
	PRIMARY KEY (id)
);

CREATE INDEX ix_approval_requests_grant_id ON approval_requests (grant_id);

CREATE INDEX ix_approval_requests_owner ON approval_requests (owner);

CREATE INDEX ix_approval_requests_state ON approval_requests (state);

CREATE TABLE attachments (
	id VARCHAR(64) NOT NULL, 
	state VARCHAR(64) NOT NULL, 
	version INTEGER NOT NULL, 
	data JSONB NOT NULL, 
	shape INTEGER DEFAULT '1' NOT NULL, 
	owner VARCHAR(128), 
	created_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	resource_id TEXT GENERATED ALWAYS AS ((data->>'resource_id')) STORED, 
	resource_kind TEXT GENERATED ALWAYS AS ((data->>'resource_kind')) STORED, 
	PRIMARY KEY (id)
);

CREATE INDEX ix_attachments_owner ON attachments (owner);

CREATE INDEX ix_attachments_resource_id ON attachments (resource_id);

CREATE INDEX ix_attachments_resource_kind ON attachments (resource_kind);

CREATE INDEX ix_attachments_state ON attachments (state);

CREATE TABLE grants (
	id VARCHAR(64) NOT NULL, 
	state VARCHAR(64) NOT NULL, 
	version INTEGER NOT NULL, 
	data JSONB NOT NULL, 
	shape INTEGER DEFAULT '1' NOT NULL, 
	owner VARCHAR(128), 
	created_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	holder_id TEXT GENERATED ALWAYS AS ((data->>'holder_id')) STORED, 
	holder_kind TEXT GENERATED ALWAYS AS ((data->>'holder_kind')) STORED, 
	token TEXT GENERATED ALWAYS AS ((data->>'token')) STORED, 
	PRIMARY KEY (id)
);

CREATE INDEX ix_grants_holder_id ON grants (holder_id);

CREATE INDEX ix_grants_holder_kind ON grants (holder_kind);

CREATE INDEX ix_grants_owner ON grants (owner);

CREATE INDEX ix_grants_state ON grants (state);

CREATE INDEX ix_grants_token ON grants (token);

CREATE TABLE grocery_lists (
	id VARCHAR(64) NOT NULL, 
	state VARCHAR(64) NOT NULL, 
	version INTEGER NOT NULL, 
	data JSONB NOT NULL, 
	shape INTEGER DEFAULT '1' NOT NULL, 
	owner VARCHAR(128), 
	created_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	plan_id TEXT GENERATED ALWAYS AS ((data->>'plan_id')) STORED, 
	PRIMARY KEY (id)
);

CREATE INDEX ix_grocery_lists_owner ON grocery_lists (owner);

CREATE INDEX ix_grocery_lists_plan_id ON grocery_lists (plan_id);

CREATE INDEX ix_grocery_lists_state ON grocery_lists (state);

CREATE TABLE jobs (
	id VARCHAR(64) NOT NULL, 
	state VARCHAR(64) NOT NULL, 
	version INTEGER NOT NULL, 
	data JSONB NOT NULL, 
	shape INTEGER DEFAULT '1' NOT NULL, 
	owner VARCHAR(128), 
	created_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	PRIMARY KEY (id)
);

CREATE INDEX ix_jobs_owner ON jobs (owner);

CREATE INDEX ix_jobs_state ON jobs (state);

CREATE TABLE meals (
	id VARCHAR(64) NOT NULL, 
	state VARCHAR(64) NOT NULL, 
	version INTEGER NOT NULL, 
	data JSONB NOT NULL, 
	shape INTEGER DEFAULT '1' NOT NULL, 
	owner VARCHAR(128), 
	created_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	name TEXT GENERATED ALWAYS AS ((data->>'name')) STORED, 
	themes JSONB GENERATED ALWAYS AS ((data->'themes')) STORED, 
	PRIMARY KEY (id)
);

CREATE INDEX ix_meals_name ON meals (name);

CREATE INDEX ix_meals_owner ON meals (owner);

CREATE INDEX ix_meals_state ON meals (state);

CREATE INDEX ix_meals_themes ON meals USING gin (themes);

CREATE TABLE members (
	id VARCHAR(64) NOT NULL, 
	state VARCHAR(64) NOT NULL, 
	version INTEGER NOT NULL, 
	data JSONB NOT NULL, 
	shape INTEGER DEFAULT '1' NOT NULL, 
	owner VARCHAR(128), 
	created_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	email TEXT GENERATED ALWAYS AS ((data->>'email')) STORED, 
	subject TEXT GENERATED ALWAYS AS ((data->>'subject')) STORED, 
	PRIMARY KEY (id)
);

CREATE INDEX ix_members_email ON members (email);

CREATE INDEX ix_members_owner ON members (owner);

CREATE INDEX ix_members_state ON members (state);

CREATE INDEX ix_members_subject ON members (subject);

CREATE TABLE plans (
	id VARCHAR(64) NOT NULL, 
	state VARCHAR(64) NOT NULL, 
	version INTEGER NOT NULL, 
	data JSONB NOT NULL, 
	shape INTEGER DEFAULT '1' NOT NULL, 
	owner VARCHAR(128), 
	created_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	start_date TEXT GENERATED ALWAYS AS ((data->>'start_date')) STORED, 
	PRIMARY KEY (id)
);

CREATE INDEX ix_plans_owner ON plans (owner);

CREATE INDEX ix_plans_start_date ON plans (start_date);

CREATE INDEX ix_plans_state ON plans (state);

CREATE TABLE prep_tasks (
	id VARCHAR(64) NOT NULL, 
	state VARCHAR(64) NOT NULL, 
	version INTEGER NOT NULL, 
	data JSONB NOT NULL, 
	shape INTEGER DEFAULT '1' NOT NULL, 
	owner VARCHAR(128), 
	created_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	due_at TIMESTAMP WITH TIME ZONE GENERATED ALWAYS AS (waymark3_ts(data->>'due_at')) STORED, 
	plan_id TEXT GENERATED ALWAYS AS ((data->>'plan_id')) STORED, 
	task_type TEXT GENERATED ALWAYS AS ((data->>'task_type')) STORED, 
	PRIMARY KEY (id)
);

CREATE INDEX ix_prep_tasks_due_at ON prep_tasks (due_at);

CREATE INDEX ix_prep_tasks_owner ON prep_tasks (owner);

CREATE INDEX ix_prep_tasks_plan_id ON prep_tasks (plan_id);

CREATE INDEX ix_prep_tasks_state ON prep_tasks (state);

CREATE INDEX ix_prep_tasks_task_type ON prep_tasks (task_type);

CREATE TABLE roles (
	id VARCHAR(64) NOT NULL, 
	state VARCHAR(64) NOT NULL, 
	version INTEGER NOT NULL, 
	data JSONB NOT NULL, 
	shape INTEGER DEFAULT '1' NOT NULL, 
	owner VARCHAR(128), 
	created_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	name TEXT GENERATED ALWAYS AS ((data->>'name')) STORED, 
	PRIMARY KEY (id), 
	CONSTRAINT uq_roles_name UNIQUE (name)
);

CREATE INDEX ix_roles_name ON roles (name);

CREATE INDEX ix_roles_owner ON roles (owner);

CREATE INDEX ix_roles_state ON roles (state);

CREATE TABLE rotations (
	id VARCHAR(64) NOT NULL, 
	state VARCHAR(64) NOT NULL, 
	version INTEGER NOT NULL, 
	data JSONB NOT NULL, 
	shape INTEGER DEFAULT '1' NOT NULL, 
	owner VARCHAR(128), 
	created_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	PRIMARY KEY (id)
);

CREATE INDEX ix_rotations_owner ON rotations (owner);

CREATE INDEX ix_rotations_state ON rotations (state);

CREATE TABLE subscriptions (
	id VARCHAR(64) NOT NULL, 
	state VARCHAR(64) NOT NULL, 
	version INTEGER NOT NULL, 
	data JSONB NOT NULL, 
	shape INTEGER DEFAULT '1' NOT NULL, 
	owner VARCHAR(128), 
	created_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	PRIMARY KEY (id)
);

CREATE INDEX ix_subscriptions_owner ON subscriptions (owner);

CREATE INDEX ix_subscriptions_state ON subscriptions (state);

CREATE TABLE waymark3_cursors (
	consumer VARCHAR(64) NOT NULL, 
	last_id BIGINT NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	PRIMARY KEY (consumer)
);

CREATE TABLE waymark3_drafts (
	kind VARCHAR(64) NOT NULL, 
	resource_id VARCHAR(64) NOT NULL, 
	action VARCHAR(128) NOT NULL, 
	part_key VARCHAR(128) NOT NULL, 
	audience VARCHAR(128) NOT NULL, 
	values JSONB NOT NULL, 
	revs JSONB NOT NULL, 
	authors JSONB NOT NULL, 
	base_version INTEGER NOT NULL, 
	saved_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	PRIMARY KEY (kind, resource_id, action, part_key, audience)
);

CREATE TABLE waymark3_idempotency (
	key VARCHAR(256) NOT NULL, 
	kind VARCHAR(64) NOT NULL, 
	action VARCHAR(128) NOT NULL, 
	body_digest VARCHAR(64) NOT NULL, 
	status INTEGER NOT NULL, 
	response_body BYTEA NOT NULL, 
	media_type VARCHAR(128) NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	PRIMARY KEY (key)
);

CREATE TABLE waymark3_job_leases (
	job_id VARCHAR(64) NOT NULL, 
	worker VARCHAR(64) NOT NULL, 
	expires_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	PRIMARY KEY (job_id)
);

CREATE TABLE waymark3_transitions (
	id BIGINT GENERATED BY DEFAULT AS IDENTITY, 
	kind VARCHAR(64) NOT NULL, 
	resource_id VARCHAR(64) NOT NULL, 
	action VARCHAR(128) NOT NULL, 
	from_state VARCHAR(64) NOT NULL, 
	to_state VARCHAR(64) NOT NULL, 
	version INTEGER NOT NULL, 
	actor_type VARCHAR(16) NOT NULL, 
	actor_id VARCHAR(128) NOT NULL, 
	actor_display VARCHAR(256) NOT NULL, 
	input_digest VARCHAR(64) NOT NULL, 
	correlation_id VARCHAR(64), 
	summary TEXT NOT NULL, 
	at TIMESTAMP WITH TIME ZONE NOT NULL, 
	acknowledged JSONB, 
	PRIMARY KEY (id)
);

CREATE INDEX ix_waymark3_transitions_resource ON waymark3_transitions (kind, resource_id, id);

CREATE TABLE waymark3_webhook_cursors (
	subscription_id VARCHAR(64) NOT NULL, 
	last_id BIGINT NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	PRIMARY KEY (subscription_id)
);
