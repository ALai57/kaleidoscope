# Frontend routing: persistent, deep-linkable page URLs

Date: 2026-07-20
Status: Design (spec) — pending review before an implementation plan is written.

## Summary

Direct navigation to a frontend page URL doesn't work. Navigating to (or
refreshing on) `https://andrewslai.com/recipes` returns the backend's **JSON
API response**, not the app; and a deep, parameterized path like
`https://andrewslai.com/library/54366250-01f0-4da4-a60d-48091558b50d/acquisitions`
doesn't render its view either. Links to app views are not persistent or
shareable — routing is effectively client-only and only survives from the home
page.

There are **two distinct failure modes** behind the one symptom:

1. **Namespace collision.** Several page paths (`/recipes`, `/articles`,
   `/interests`, …) are *also* backend JSON API routes at the root. A browser
   navigating to `/recipes` matches the `GET /recipes` API route and gets a JSON
   array — "the backend response."
2. **No client-side router reading the path.** For a path that *doesn't* collide
   (`/library/:id/acquisitions`), the backend already falls through to the SPA
   shell (see below) — but the frontend boots to its default view and ignores
   `location.pathname`, so the deep view never renders.

This spec fixes both by (a) giving the JSON API its own URL namespace
(`/api/v1/*`) so the root belongs exclusively to frontend pages, (b) hardening
the SPA fallback so any non-reserved path deep-links to the shell while genuine
misses still 404, and (c) specifying the coordinated `kaleidoscope-ui` change
(a real History-API router) without which the frontend cannot honor a deep URL.

## Problem

The frontend (`kaleidoscope-ui`, separate repo) uses client-side routing only.
Concretely, against the current backend:

- `GET /recipes` (browser navigation) → **`200` with a JSON array of recipes**,
  because `/recipes` is a mounted reitit API route
  (`http_api/recipes.clj:65-93`) marked `public-access` in the ACL
  (`http_api/kaleidoscope.clj` ACL, `GET /recipes.*`). The SPA never boots. The
  same shadowing applies to every root page path that matches an API route name.
- `GET /library/<uuid>/acquisitions` (browser navigation) → the backend's
  default handler (`http_api/kaleidoscope.clj:252-266`) already serves
  `index.html` from the shared `kaleidoscope.client` store. The app *loads* but
  renders its home view, because the frontend has no router that reads the path.

There is therefore **already** an SPA fallback (added 2024-06-11). It is not
absent — it is (a) shadowed by colliding API routes, and (b) too permissive: it
serves `index.html` for **every** unmatched path, including missing assets and
unknown API paths, so an XHR/`fetch` for a missing resource silently receives an
HTML page instead of a real `404` ("soft 404").

## Root cause

The root URL namespace is **shared** between two things that should never
compete for it: the JSON API (a machine contract) and the frontend page space
(a human/browser contract). Prod "works" for the home page only because nothing
forced the two to disagree; the moment a page path equals an API resource name,
the API wins and the page breaks. Nothing about page depth or parameters is the
issue — `/library/:id/acquisitions` proves the fallback handles arbitrary paths
fine; the issue is the **flat, shared namespace** plus a **missing client
router**.

The invariant to establish: **the backend owns a small, fixed set of reserved
prefixes; every other path on the root is a frontend page and resolves to the
SPA shell** — at any depth, with any parameters, without the backend knowing the
page exists.

## Goals

- Direct navigation / refresh on any frontend page URL renders that view —
  including deep, parameterized paths
  (`/library/54366250-01f0-4da4-a60d-48091558b50d/acquisitions`).
- Page links are persistent and shareable.
- A deliberately designed, stable **URL contract**: a fixed reserved-prefix set
  for the backend; the entire page URL space owned by the frontend.
- Genuine misses still fail correctly: unknown API path → `404` JSON; missing
  asset → `404`; only page-like requests get the shell (no soft-404s).
- **Zero behavior change in prod and local** until the retirement step; no
  lockstep frontend/backend deploy required.

## Non-goals

- A backend page-route manifest / allowlist. The backend must **not** enumerate
  pages — see "The backend stays page-ignorant" below.
- Per-tenant page *sets* (different sites exposing different navigation).
  Out of scope; the shell stays shared, per-tenant differentiation stays in the
  API + assets.
- Server-side rendering, prerendering, or per-page `<title>`/Open Graph meta.
  (A later concern if SEO/social previews are wanted.)
- Renaming or versioning individual API resources beyond relocating them under
  the prefix.

---

## Design

### 1. The contract: reserved prefixes; root is pages

A fixed set of prefixes belong to the backend forever. Everything else on the
root is a frontend page.

| Reserved for backend | Purpose |
|---|---|
| `/api/v1/*` | JSON API — all resource routes |
| `/assets/*`, `/static/*`, `/media/*` | Compiled SPA assets + tenant static/media |
| `/favicon.ico`, `/index.html`, `/openapi.json`, `/api-docs/*`, `/ping` | Infra |

Any other path resolves to the SPA shell. This is the public contract the URL
design hangs off of.

**API prefix decision:** `/api/v1`. Version-agnostic root stays free for pages;
`/v1` resets the confusing lone `/v2/photos` (which had no `/v1` predecessor) and
gives an explicit versioning anchor. `/v2/photos` folds in as `/api/v1/photos`
(kept alive at `/v2/photos` during transition).

### 2. The backend stays page-ignorant (why: your example)

`/library/:id/acquisitions` does not — and must not — exist as a backend route.
If the backend enumerated known pages, every new frontend route would `404`
until the backend was redeployed, coupling the two repos exactly where they
should be decoupled. Therefore the backend's *only* knowledge is the reserved
prefix set; the entire page URL space (nesting, slugs, UUID params) lives solely
in `kaleidoscope-ui`. Arbitrary-depth parameterized paths work with **zero**
backend changes because "not reserved → shell" is depth- and param-agnostic.

### 3. Backend changes (three, all in `http_api/`)

**(a) Dual-mount the API under `/api/v1`.** Factor the API route groups
(`reitit-recipes-routes`, `reitit-articles-routes`, … everything that is not
index/ping/openapi/static) into one vector, and mount that vector **twice** in
the reitit router: once at root (existing, unchanged) and once under an
`["/api/v1" …]` context. Fold `/v2/photos` in as `/api/v1/photos`. No behavior
change — both address spaces resolve to the same handlers.

**(b) Mirror the ACL.** For each root pattern in
`KALEIDOSCOPE-ACCESS-CONTROL-LIST`, add an `/api/v1/…` twin with identical auth
(method + handler). The fail-closed catch-all stays last. This is the
correctness-critical step: a missed twin is a silent auth hole (see the ACL's
own comment on "invisible data exposure"), so it is covered by a test asserting
every root pattern has a prefixed twin.

**(c) Replace the permissive default handler with a reserved-prefix
discriminator.** Today the default handler
(`http_api/kaleidoscope.clj:252-266`) serves the shell for *any* unmatched path.
New logic:

- Serve `index.html` (forced host `kaleidoscope.client`) only when: method is
  `GET`/`HEAD` **and** the path is **not** under a reserved backend prefix.
- Otherwise return a real `404` — JSON for `/api/v1/*`, plain otherwise.

This kills soft-404s and is what lets any *page* path deep-link. The handler
continues to bypass the reitit middleware stack (as today), manually forcing
host/uri and injecting `:components`; it only ever serves the public shell, so
bypassing auth is safe.

### 4. The collision fix lands at retirement (explicit trade-off)

While both mounts are live, root `/recipes` still matches the API route and
still returns JSON to a browser. **The collision is only resolved when the root
API mount is retired** (Sequencing step 4). Dual-mount is the safe runway;
retirement is the fix. This is the deliberate cost of avoiding a lockstep
frontend/backend deploy. Non-colliding deep paths (`/library/:id/…`) are fixed
earlier — as soon as the frontend router ships (step 2) — because they never hit
an API route.

### 5. Frontend contract (`kaleidoscope-ui` — coordinated work)

The backend cannot satisfy the acceptance criterion alone. In `kaleidoscope-ui`:

- Add a **History-API client router** that renders the view matching
  `location.pathname`, including nested parameterized routes
  (`/library/:id/acquisitions`). This is the piece that makes the example URL
  actually render.
- Repoint **all API calls** to `/api/v1/*`.
- Add an in-app **404 view** for unknown client paths.

### 6. Clean canonical URL structure

The backend enforces only the reserved prefixes; the concrete page list is
owned by the frontend. Proposed conventions as the contract:

- Lowercase kebab-case slugs; **no trailing slash** is canonical.
- Collections plural, items nested singular: `/recipes` → `/recipes/:slug`;
  `/library/:id/acquisitions` follows the same nesting shape.

The concrete route table is defined and maintained in `kaleidoscope-ui`, not
here.

### 7. Interaction with the tenant-resolution seam

Orthogonal and compatible with `plans/2026-07-16-tenant-resolution`. Once that
seam lands, `http_utils/get-resource` and the fallback key the adapter by
`tenant/site-value` instead of the raw Host; this redesign inherits that for
free and does not block it. The shell keeps coming from the shared
`kaleidoscope.client` store (consistent with CLAUDE.md: static chrome is shared
across tenants); per-tenant differentiation stays in the API + assets.

---

## Sequencing

1. **Ship backend dual-mount + mirrored ACL + discriminating fallback.** API
   reachable at both root and `/api/v1/*`; soft-404s fixed. Prod/local
   behavior-identical for existing (root) callers.
2. **Ship `kaleidoscope-ui`:** History-API router (fixes the `/library/:id/…`
   class immediately) + repoint API calls to `/api/v1/*` + 404 view.
3. **Update Checkly checks** to the `/api/v1` paths.
4. **Retire the root API mounts and their ACL twins.** Root now serves only
   pages → `/recipes` and every colliding path deep-link correctly. Feature
   done.

## Rejected alternatives

- **Backend page-route manifest / allowlist.** Register known page paths
  (`/recipes`, `/library/:id/acquisitions`, …) as HTML-serving routes. Rejected:
  couples the repos (a new frontend route 404s until backend redeploy) and
  cannot express the open-ended page space; the reserved-prefix rule covers
  arbitrary paths with zero backend knowledge.
- **Content negotiation on shared paths** (branch `GET /recipes` on `Accept:
  text/html` vs `application/json`). Rejected: spreads a routing concern into
  every resource handler, is fragile against `Accept: */*` from `fetch`, and
  leaves the namespace collision intact.
- **Hard cutover** (rename API to `/api/v1` in one change, deploy frontend +
  backend + Checkly in lockstep). Rejected in favor of dual-mount: avoids a
  synchronized multi-repo deploy at the cost of deferring the collision fix to
  retirement.
- **Prefix the pages instead of the API** (`/app/recipes`). Rejected: the whole
  point of the redesign is clean, shareable root URLs for humans; the machine
  contract is the one that should carry the prefix.

## Sharp edges / risks

- **ACL twin coverage.** Every root pattern needs an `/api/v1` twin with
  identical method+handler auth; a miss is a silent, total data exposure. Guard
  with a test that derives twins from the root patterns.
- **Retirement is the actual fix for colliders.** Don't mistake "dual-mount
  shipped" for "deep links work" — `/recipes` stays JSON to browsers until the
  root mount is retired (step 4).
- **Soft-404 discrimination must be exhaustive.** The reserved-prefix check must
  cover every backend prefix (`/api/v1`, `/assets`, `/static`, `/media`, infra),
  or a missing asset/API path leaks the HTML shell to a fetch caller again.
- **Middleware bypass in the fallback.** The default handler runs outside the
  reitit stack; it must keep manually forcing host/uri and injecting components,
  and must only ever serve the public shell.
- **Frontend is the gating dependency for the example.** The `/library/:id/…`
  acceptance path cannot pass on backend changes alone — it needs the
  `kaleidoscope-ui` router (step 2).

## Docs / ops to update (in the same change)

Per CLAUDE.md sharp edges #5 and #6:

- `docs/operations.md` — the `/api/v1` prefix and the reserved-prefix contract;
  the Checkly path move (step 3).
- Any Checkly check definitions that call root API paths → `/api/v1`.
- README / API docs — note the `/api/v1` base path.

## Testing strategy

Kaocha + matcher-combinators + ring-mock, embedded H2/Postgres, per CLAUDE.md.

- **Dual-mount:** each resource reachable at both root and `/api/v1/*`
  (pre-retirement); auth identical on both address spaces.
- **ACL twin coverage:** a test asserting every root pattern has an `/api/v1`
  twin with the same method + handler.
- **Fallback discrimination:**
  - unknown page + `Accept: text/html` → `200` SPA shell;
  - deep param path `GET /library/<uuid>/acquisitions` + `Accept: text/html` →
    `200` SPA shell (the acceptance test), at arbitrary depth;
  - unknown `GET /api/v1/nope` → `404` JSON (not the shell);
  - missing `GET /assets/nope.js` → `404` (not the shell).
- **Post-retirement:** `GET /recipes` (browser `Accept`) → `200` shell;
  `GET /api/v1/recipes` → `200` JSON list.
- **Auth preserved:** `POST /api/v1/recipes` requires a writer; public GETs stay
  public — mirroring current root behavior.

## Open questions

1. Concrete canonical page route table — owned and finalized in
   `kaleidoscope-ui`, not blocking this backend spec.
2. Whether `/media/*` should eventually move under `/api/v1` too, or stay a
   sibling static prefix (it is asset-like, not JSON; default: keep it a
   sibling). Non-blocking.
