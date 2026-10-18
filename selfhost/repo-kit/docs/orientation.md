# Orientation

<!-- selfhost/repo-kit: copy this file to docs/orientation.md in your
repository and fill in each section. The factory's repo_policy names
this path; code-seat reads it first, every round, before the ticket.
Keep it short: every line is read on every round. -->

## What this repository is

One paragraph: what it builds, and for whom.

## Where things are

- `src/` — what lives here.
- `test/` — what lives here.

## How it is tested

CI runs `.github/workflows/tests.yml`. Its one job, `tests`, is the
check the factory merges on. A seat runs a part of it through the
bench's `test` tool, which dispatches that workflow with `only` set to
what the seat touched. Say here what `only` takes in this repository
(a file, a module, a test name) and how to name one.

## Conventions

What a change here must look like: naming, comments, the idioms the
code already uses.

## Do not touch

Files and directories a change must leave alone, and why.
