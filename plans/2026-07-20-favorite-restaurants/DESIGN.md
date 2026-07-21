# Favorite Restaurants — Design Spec

**Date:** 2026-07-20
**Status:** Approved by Andrew (design mockup: `claude.ai/code/artifact/2607cb35-82d0-4421-bb16-914e1b982dc3`)
**Repos:** `kaleidoscope` (backend), `kaleidoscope-ui` (frontend, Prism preset)

## Purpose

Track restaurants you actually eat at, imported automatically from card
transactions, and browse them on a map or card grid to answer "where should we
eat?" Each restaurant carries meal tags, the people who liked it, average cost
per meal, visit history, and an embedded menu linking to the original.

## Decisions (settled with Andrew)

| Decision | Choice |
|---|---|
| Transaction source | **Plaid** (`/transactions/sync`, Link flow; sandbox for dev) |
| Favorite definition | **Suggest + confirm** — ≥3 visits auto-suggests; user confirms |
| Menus | **Firecrawl scrape + cache** (reuse `api/firecrawl.clj`), always hyperlink the original |
| People who liked it | **Free-text name chips** (JSONB on the restaurant; linkable to users later) |
| Map | Leaflet + OpenStreetMap tiles; **scannable** — permanent name labels on pins; click opens a **side panel**, not a popup |
| Views | Map view + card view over the same collection, shared meal filter |
| Design system | Prism preset (dark instrument panel, spectrum categorical palette, mono data voice) |

## User experience

### Map + card views (one collection, two views)

- Toolbar: meal-filter chips (`all / breakfast / lunch / dinner / coffee / drinks`),
  map|cards view toggle, search box. The filter applies to both views.
- **Map view:** every pin displays its restaurant name on a permanent label —
  the map is scannable without clicking. Pins are colored by primary meal tag
  (Prism spectrum) and sized by visit count. Favorites are emphasized (star);
  non-favorite visited places still render, de-emphasized. Clicking a pin
  slides in a **side panel** (right edge) with: name + favorite star, address,
  meal tags, avg-cost/meal and visit-count stat tiles, liked-by faces, menu
  link, and "Full details →".
- **Card view:** grid sorted by visit count desc. Each card: name (+ star),
  address, meal tags, `$avg/meal · N visits`, liked-by avatars. Click → detail.

### Restaurant detail

- Header: name, favorite star (toggleable), address, distance.
- Meal tags: editable chips; initial guess from transaction time-of-day
  (breakfast <11am, lunch 11–4, dinner >4; coffee/drinks inferred from
  Plaid category detail when available). Always user-editable.
- Stat tiles: **avg cost / meal**, **last visit**, **total spent** — all
  computed from linked transactions.
- Visits-by-month bar strip from transaction history.
- **Liked by:** appendable free-text name chips (add/remove).
- **Menu panel:** rendered from cached scrape (sections, items, prices),
  "View original menu ↗" link to `menu_url`, manual "re-scrape" button,
  "cached N days ago" indicator.

### Transaction sync (Plaid)

1. **Connect:** one-time Plaid Link flow in the UI. Server exchanges the public
   token; stores `access_token` + sync cursor per item. Bank credentials never
   touch Kaleidoscope.
2. **Sync:** nightly scheduled job + manual "sync now" button. Incremental via
   `/transactions/sync` cursors. Keep `FOOD_AND_DRINK` rows; Plaid enrichment
   (cleaned merchant name, category, usually lat/lng + website) seeds/matches
   restaurants. Rows with no location fall back to Nominatim geocoding; rows
   that still can't be placed queue for manual review. Non-food rows are
   recorded as skipped (id only, for cursor correctness) — not stored as
   transactions.
3. **Confirm:** places reaching ≥3 visits are suggested as favorites; the user
   confirms and tags meals. Everything else remains browsable history.

## Architecture (backend)

Standard 3-layer shape; tenant-scoped by `hostname` like the rest of the
current tenant work.

### `http_api/restaurants.clj`

- `GET /restaurants` (with computed aggregates), `GET /restaurants/:id`,
  `PATCH /restaurants/:id` (favorite, tags, liked_by, menu_url)
- `POST /plaid/link-token` — create a Link token for the UI
- `POST /plaid/items` — exchange public token, persist item
- `POST /restaurants/sync` — manual sync trigger
- `POST /restaurants/:id/menu/scrape` — (re)scrape menu
- Auth: admin-only routes (same middleware as other authenticated domains).

### `api/restaurants.clj`

- Sync orchestration over a **`TransactionSource` protocol** — `plaid` impl +
  `mock` impl, selected by `KALEIDOSCOPE_TRANSACTION_SOURCE_TYPE` env var
  (mirrors the scorer/executor pattern in `init/env.clj`). Plaid credentials
  via `PLAID_CLIENT_ID` / `PLAID_SECRET` / `PLAID_ENV`.
- Merchant→restaurant matching: prefer Plaid `merchant_entity_id`, else
  normalized merchant name + proximity.
- Meal-tag heuristics from transaction timestamp + category detail.
- Favorite suggestion at the ≥3-visit threshold (suggested ≠ favorite until
  confirmed).
- Cost aggregation: avg/meal (overall), last visit, total spent, visits/month.
- Geocoding behind a small protocol seam: Nominatim impl + mock. Results
  cached on the restaurant row — geocode once per restaurant, not per sync.
- Menu scraping delegates to existing `api/firecrawl.clj`; parsed into
  structured sections/items JSONB; scrape failures keep the previous cache and
  surface an error.
- Nightly sync as a scheduled job (integrates with the postgres job queue plan
  if landed; otherwise a simple scheduled thread like existing periodic work).

### `persistence/restaurants.clj`

Tables:

- `plaid_items` — id, hostname, access_token, sync_cursor, institution_name,
  created_at, modified_at
- `restaurants` — id, hostname, name, normalized_name, merchant_entity_id,
  address, lat, lng, website, menu_url, menu_cache JSONB, menu_scraped_at,
  favorite BOOLEAN, favorite_suggested BOOLEAN, tags JSONB, liked_by JSONB,
  created_at, modified_at
- `restaurant_transactions` — id, hostname, restaurant_id (nullable — null =
  needs review), plaid_transaction_id, date, raw_merchant, amount, meal,
  created_at

### Multi-tenancy (consistent with the established pattern)

`hostname` is the tenant axis for every app table; restaurants follow the
existing mechanism exactly:

- **Schema (recipes gold standard):** `hostname VARCHAR NOT NULL` on all three
  tables. `restaurants` and `plaid_items` get `UNIQUE (id, hostname)`;
  `restaurant_transactions.restaurant_id` references restaurants via the
  **composite FK `(restaurant_id, hostname) → restaurants (id, hostname)`**,
  so the database itself forbids attaching a transaction to another tenant's
  restaurant. Plaid transaction de-dup is per-tenant:
  `UNIQUE (hostname, plaid_transaction_id)`.
- **TenantConn registration:** add `restaurants`, `plaid_items`, and
  `restaurant_transactions` to `tenant-scoped-tables` in
  `persistence/tenant.clj`. The schema tripwire test
  (`tenant-scoped-tables-match-schema-test`) enforces that this set matches
  the actual hostname-bearing tables, so the migration and the set land
  together.
- **Request path:** handlers never thread hostname manually. Each handler
  scopes once — `(tenant/scope (:database components) (hu/tenant-hostname
  request))` — exactly as `http_api/tasks.clj` et al. do, relying on the
  global `wrap-resolve-tenant` middleware (Host header). The scoped handle
  flows through `api/` into `persistence/`, where `scope-query`/`inject-row`
  confine reads and stamp writes automatically.
- **Sync job path (no request):** the nightly job has no Host header. It
  iterates `plaid_items` rows grouped by hostname and creates an explicit
  per-tenant scoped handle (`(tenant/scope ds hostname)`) for each item's
  sync, so one tenant's Plaid data can never write into another tenant's
  restaurants. Manual "sync now" runs under the request's scoped handle and
  only syncs that tenant's items.
- **External caches are per-tenant:** menu_cache and geocode results live on
  the tenant's own restaurant row; nothing is shared across tenants.

One Migratus migration pair. **Prod caution:** prod carries dead ghost objects
from a deleted 2022 restaurants feature (`restaurants`, `eater_groups`,
`eater_group_memberships`, `restaurant_audiences`, view
`full_eater_memberships` — all 0 rows, absent from the 2026-07-18 baseline).
The up migration must `DROP ... IF EXISTS` those five objects before creating
the new tables so it runs cleanly on both prod and fresh DBs.

### Security

- Plaid `access_token` is a bearer credential for read access to bank data:
  never returned by any API response, never logged. (At-rest encryption can
  follow later; Neon is encrypted at rest.)
- All Plaid/Nominatim/Firecrawl calls happen server-side.

## Frontend (kaleidoscope-ui, Prism preset)

- New route/section under the Prism subtree (`makePrismTheme` scoping, like
  Recipes). Components: `RestaurantsMap` (Leaflet + OSM tiles, permanent
  labels via tooltips pinned open, custom pin markers colored by meal tag,
  sized by visit count), `RestaurantSidePanel`, `RestaurantCardGrid`,
  `RestaurantDetail` (stat tiles, visits bars, people chips, menu panel),
  `MealFilterChips`, `PlaidLinkButton` (Plaid Link SDK), sync-status snackbar.
- Meal-tag colors from the Prism spectrum (`theme.tokens.color.categorical`),
  in fixed order: coffee, dinner, breakfast, lunch, drinks. Never hardcoded
  hexes (lint-enforced).
- Leaflet added as a dependency; tiles from OSM (no API key). Attribution per
  OSM policy.

## Testing

- **Backend:** unit tests for meal heuristics, merchant matching, favorite
  suggestion, and aggregates; end-to-end sync test through the mock
  `TransactionSource` (canned Plaid-shaped payloads incl. enriched, missing-
  location, and non-food rows) against embedded H2; migration runs on both H2
  and embedded Postgres (ghost-drop compatibility); menu scrape test with a
  mock Firecrawl response.
- **Cross-tenant tests** (same style as the existing cross-site tests): two
  hostnames, restaurants + transactions in each — reads under one Host header
  never see the other tenant's rows; a sync for tenant A creates rows only
  under A; attaching a transaction to the other tenant's restaurant is
  rejected by the composite FK. The `tenant-scoped-tables` tripwire test
  covers registration of the three new tables automatically.
- **Frontend:** component tests for filter/toggle/side-panel behavior; a
  Storybook story per new common component (Prism convention).
- Mock-first local dev: no Plaid/Firecrawl keys needed for `task run` or tests.

## Out of scope (explicit)

- Multi-card/multi-institution management UI beyond a simple item list.
- Linking liked-by people to Kaleidoscope users/groups (future).
- Automatic menu-price → transaction reconciliation.
- Plaid webhooks (nightly + manual sync is enough to start).
