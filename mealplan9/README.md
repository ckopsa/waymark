# The family meal planner (waymark9)

The meal planner on Waymark 7.0 — the mealplan6 app forked onto the
"law binds rows" runtime (`docs/waymark9-design.md`). The calendar
story it carries is the 6.0 design's driving story
(`docs/waymark6-design.md` §1–§2): **the calendar the plan
decision consults is nobody's child.** The new `event` kind is the
family calendar; the plan declares `Related("event", …)` over its
stored week boundaries, carries `calendar_conflicts`/`has_conflicts`
as maintained facts, warns (acknowledgeably) on finalizing a week with
conflicts, and wears the edge-compiled, badge-bearing calendar link.
Everything else is the same app: a Tuesday-to-Tuesday week (sometimes
two, to save grocery trips), a fixed theme per weekday, a rotating
Sunday, AI-suggested meals and recipes, an AI-compiled grocery list,
and thaw/prep reminders that end up on the calendar — which now lives
here, as `event` rows, instead of in an external system's id strings.

```bash
make mealplan9                               # db + wheel + uvicorn on :8005
open http://127.0.0.1:8005/                  # the generic human client
```

## The weekly calendar

| Day | Theme |
|---|---|
| Tuesday | Taco Tuesday (Mexican/Latin American) |
| Wednesday | American (no BBQ — that's Saturday's job) |
| Thursday | Asian |
| Friday | Pizza |
| Saturday | BBQ |
| Sunday | Rotating (drawn from the `rotation` resource) |
| Monday | Italian |

The map lives in [`themes.py`](themes.py); a plan's days are populated from
it automatically at create time.

## Resources

- **`meal`** — the meal library. The AI creates suggestions (with a full
  markdown recipe, `prep_minutes`, `thaw_hours`); humans `accept` the keepers
  onto the list or `decline`. Recipes are never written by hand:
  `update_recipe` exists so the AI can revise them. A meal carries `themes`,
  a tag list of every theme night it can serve (fajitas are `mexican` *and*
  `american`); `update_themes` retags it, and filtering `themes=bbq` means
  "tagged bbq" (membership, not equality).
- **`rotation`** — the dynamic lists of Sunday themes. Several can exist
  (seasonal lists, experiments); `activate` stamps `activated_at` and new
  plans draw from the most recently activated `active` rotation — an
  action's effect belongs to its own resource, so activating one never
  mutates the others. `add_theme` / `remove_theme` / `advance` (the pointer
  names the next Sunday's suggestion) work on the active one.
- **`plan`** — one or two themed weeks starting on a Tuesday. Creating one
  takes zero required fields: the `Create` model defaults `start_date` to
  the coming Tuesday, hides the derived `days`, and renders `rotation_id`
  as a picker over existing rotations (`x-display` resource widget). Left
  blank, `on_create` selects the freshest active rotation and pre-themes each
  rotating Sunday from it, walking the list from `position` — so a
  two-week plan gets two consecutive rotation themes. `set_sunday_theme`
  still overrides (guarded to rotation membership).
  `assign_meal` enforces the day's theme as a guard (the refusal explains
  itself and names the remedies); Sundays refuse assignment until
  `set_sunday_theme` picks something from the rotation; `assign_off_theme`
  is the confirm-gated override; `mark_eating_out` covers a day without a
  meal, optionally noting where (a restaurant, grandma's, …). `finalize` refuses until every day is covered — and the same guard
  is why the button is disabled, with the missing dates in the tooltip.
- **`grocery_list`** — compiled by the AI from a finalized plan (`finalize`
  is guarded on the plan actually being `planned`). In `ready` you shop and
  `check_item`; `complete` refuses while anything is unchecked.
- **`prep_task`** — "start thawing the pork Saturday 6pm" derived by the AI
  from recipes and the plan. `schedule` (recording the calendar event id) is
  `confirm=true`: the agent hard-stops for human approval before anything
  touches the family calendar.
- **`ingredient`** — the canonical pantry concept ("chicken thighs" —
  store-agnostic, measured in grams). The AI proposes ingredients while
  parsing receipts and recipes; humans `accept`/`decline` exactly as with
  meal suggestions, which is what keeps the list canonical instead of
  accreting three spellings of the same thing. `aliases` carries every
  name it goes by (confirmed matches fold their spelling in);
  `preferred_stores` says where the family buys it, and
  `?preferred_stores=costco` is the trip planner's entry point. `absorb`
  is the dedupe verdict: the survivor takes the duplicate's names,
  repoints its products (declared touches), and retires it.
- **`product`** — how a store sells an ingredient ("Kirkland chicken
  thighs 2.72 kg" at costco), carrying the two machine keys the ingestion
  paths converge on: `upc` for receipt lines, `url` for the scraper. Born
  `suggested` from an unknown line with the AI's best-guess match and the
  price sighting that minted it; `confirm_match`/`rematch` is the human
  verdict — the match is the error-prone step, never the price. Sightings
  embed one-per-day (a same-day re-record replaces, so retries are safe);
  `latest_price_cents`, `cents_per_100g`, and `price_is_stale` are derived
  facts, so `?store=costco&sort=cents_per_100g` answers "what would the
  trip cost where" and `?state=tracked&price_is_stale=true` *is* the
  scraper's work queue — no agent keeps a private list.

## Where the AI sits

The AI is a **client** of this app, not a service inside it. Point an agent
at `/api/.well-known/waymark` (or project the tool surface with
`waymark2.client.mcp_tools`) and the whole workflow is just declared
affordances:

1. *"Suggest three Asian meals for Thursday"* → the agent `create`s meals in
   `suggested` with recipes attached; you `accept` from the UI or chat.
2. Planning → `assign_meal` day by day; the guards keep it on-theme and the
   409s tell the agent exactly what to fix (pick the Sunday theme, accept
   the meal first, use the override).
3. *"Make the grocery list"* → the agent reads the plan's meals and recipes,
   `create`s a `grocery_list`, `add_item`s everything, `finalize`s it.
4. *"Schedule the thawing"* → the agent `create`s `prep_task`s from
   `thaw_hours`/`prep_minutes`, then must get your confirmation on each
   `schedule` before creating calendar events.
5. *"Here's the Costco receipt"* → the agent looks each line up by `upc`
   and `record_sighting`s the price; an unknown line becomes a `suggested`
   product (and a `suggested` ingredient, when even the concept is new)
   for you to confirm or rematch.

Because presence is permission, a prompt-injected recipe can at most *ask* —
the agent only acts through these declared, guarded, confirm-gated actions.

## Conformance

This app runs on **waymark9** (see `docs/waymark9-design.md`). Meal,
rotation, prep task, and the new event need no factories at all — the
suite's derived walker reaches every state from their declarations; only
the plan, grocery list, ingredient, and product register factories (their
states need semantic setup), and the only example inputs left are the ones
that need a *real* meal, ingredient, or product id. Everything is in the
repo-root `conftest.py`:

```bash
uv run waymark9 check mealplan9.main:engine
make conformance7                     # pytest --waymark9 -n auto
uv run pytest --waymark9 plan         # or meal, event, rotation, …
```
