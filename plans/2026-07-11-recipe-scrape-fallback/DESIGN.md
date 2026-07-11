# Recipe Scrape — Cloudflare Fallback + Clojure HTTP Client

> **Status (2026-07-11):** design approved in brainstorming; spec pending user
> review before an implementation plan is written. Builds on the shipped
> recipes feature (`plans/2026-07-10-recipes-feature/PLAN.md`) — this only
> touches the scraper's fetch tier, the HTTP client, and the scrape handler's
> error handling. Extraction (JSON-LD + LLM) is unchanged.

## Problem

`POST /recipes/scrape` fetches a page server-side with the JDK
`java.net.http.HttpClient` and extracts a recipe from it. Cloudflare-protected
sites (observed: `breadbyelise.com/cardamom-buns/` via
`https://kal-eph-recipes.fly.dev/recipes/scrape`) return **403** from an
**interactive / managed challenge**. That challenge requires a real browser to
run JavaScript and often solve a Turnstile widget. **No plain HTTP client can
pass it** — swapping HTTP libraries does not help, because the blocker is "we
are not a browser," not client ergonomics.

## Approved decisions (from brainstorming)

1. **Fallback via a managed scraping service, not a self-hosted browser.** A
   managed API (Firecrawl) runs a real browser + residential proxies + challenge
   solving and returns rendered bytes. Fits the distroless-JRE-on-Fly deploy
   (an HTTPS call + one secret) far better than embedding Playwright in a JVM
   image, which is heavy and still frequently blocked.
2. **Fallback tier, not always-on.** Direct JDK fetch stays tier 1 (free, works
   on most sites). Only a Cloudflare-class block escalates to the paid tier —
   cost stays near zero.
3. **Provider: Firecrawl.** Returns clean rendered output and pairs naturally
   with our existing JSON-LD/LLM extraction.
4. **Firecrawl's only job is "clear Cloudflare and return `rawHtml`."**
   Extraction stays 100% ours: free deterministic JSON-LD first, our cheap
   Haiku LLM fallback second. We do **not** use Firecrawl's `json` (LLM
   extraction, +4 credits/page) format — it would pay Firecrawl to redo the
   extraction we already do, and skip the free JSON-LD path entirely. See
   "Firecrawl request/response" for why `rawHtml`, not `html`.
5. **HTTP client is a Clojure wrapper, not raw `java.net.http` interop.** New
   requirement (see below).
6. **Lean on Bugsnag for the unexpected tail.** Expected scrape outcomes become
   a `422` with a reason; anything else propagates and Bugsnag middleware
   reports it. No defensive catch-alls just to avoid a 500 — an escaped
   exception is observability, not a hole.

## Architecture & layering

No layer boundaries move. The scraper stays in `api/recipe_scraper.clj`
(domain logic that already performs an outbound fetch, mirroring the Anthropic
client in `workflows/llm_executor.clj`). The Firecrawl client is an external
data-access concern and lives under `persistence/`. Wiring is via
`init/env.clj` boot-instructions, exactly like `scorer` and
`workflow-executor`.

```
scrape(fetcher, {:api-key ...}, url)
 ├─ tier 1: fetch-direct           — SSRF-guarded Clojure-wrapped HTTP (free)
 │            200            → html
 │            403/429/503 or challenge-marker body → :reason :bot-blocked
 │            other non-2xx  → :reason :fetch-failed
 ├─ tier 2: only when tier 1 is :bot-blocked AND `fetcher` is present
 │            fetch-rendered via Firecrawl → rawHtml (paid, clears Cloudflare)
 └─ extraction (UNCHANGED): parse-json-ld → extract-with-llm
```

## The Clojure HTTP client (new requirement)

Replace raw `java.net.http` interop **in the scraper** with **hato**
(`hato/hato`) — an idiomatic Clojure wrapper built *on top of*
`java.net.http`. Chosen over clj-http because it keeps the same underlying HTTP
engine the codebase already standardized on (no Apache HttpComponents
transitive weight, which matters for the jlink/distroless image), while giving
us Clojure maps in/out instead of `.header`/`.build`/`.send` interop chains.

- Add `hato/hato {:mvn/version "1.0.0"}` to `deps.edn` (pin the current latest
  at implementation time).
- **SSRF-critical:** configure the client with `:redirect-policy :never` so
  redirects are **not** auto-followed. `fetch-direct` keeps following redirects
  manually, revalidating every hop with the existing `safe-url?` /
  `blocked-address?` guard. A wrapper that silently auto-follows would defeat
  the per-hop SSRF check — this must be explicit.
- Scope: `fetch-direct` (rewritten) and the new Firecrawl client both use hato.
  `workflows/llm_executor.clj` keeps its raw interop — out of scope here (do
  not refactor unrelated code); noted as a possible follow-up.

## Components

### `persistence/firecrawl.clj` — the fetcher
- A `RecipeFetcher` protocol with one method, `fetch-rendered [this url] → html`,
  plus a real Firecrawl record and a mock record. Protocol so tests inject a
  mock and never hit the network (same pattern as scorer/executor).
- Real impl: `POST https://api.firecrawl.dev/v1/scrape` via hato, header
  `Authorization: Bearer <FIRECRAWL_API_KEY>`, JSON body
  `{:url url :formats ["rawHtml"]}`. Parse `data.rawHtml` from the response and
  return it. Non-success → throw `ex-info` with `{:type :scrape :reason
  :render-failed}` (an *unexpected* provider failure — see error taxonomy).
- Constructor `make-firecrawl-fetcher {:api-key ...}`; a `mock` fetcher whose
  `fetch-rendered` returns canned HTML for tests; a `nil`/absent fetcher means
  "no fallback available."

### `init/env.clj` — wiring
- New boot-instructions `recipe-fetcher`, `:path "KALEIDOSCOPE_RECIPE_FETCHER_TYPE"`,
  launchers:
  - `"none"` (default) → no fetcher; today's behavior, zero new prod dependency
    until the secret is set.
  - `"mock"` → mock fetcher (local dev / tests).
  - `"firecrawl"` → `make-firecrawl-fetcher {:api-key (get env "FIRECRAWL_API_KEY")}`.
- Add `:recipe-fetcher` to the assembled system/components map so the handler
  can reach it, alongside `:scorer` and `:workflow-executor`.

### `api/recipe_scraper.clj` — orchestration
- `fetch-direct` (renamed from today's `fetch-html`): same SSRF + manual
  redirect loop + 2 MB body cap, rewritten on hato. Classify the terminal
  status:
  - `200` → return html.
  - `403 | 429 | 503`, **or** a 200/non-2xx body containing a challenge marker
    (`"Just a moment"`, `cf-mitigated`, `cf-browser-verification`) → throw
    `{:type :scrape :reason :bot-blocked}`.
  - other non-2xx → `{:type :scrape :reason :fetch-failed}`.
- `scrape` gains a `fetcher` argument and orchestrates the tiers: call
  `fetch-direct`; on `:bot-blocked`, if `fetcher` is present call
  `fetch-rendered` and continue extraction on that HTML, else rethrow. All
  other reasons propagate. Extraction code is untouched.

### `http_api/recipes.clj` — handler + error taxonomy
Restore/finish the currently-commented `try/catch` in the `/scrape` handler and
pass the fetcher through:

```clojure
(let [url     (get-in parameters [:body :url])
      api-key (:api-key (:workflow-executor components))
      fetcher (:recipe-fetcher components)]
  (try
    (ok (scraper/scrape fetcher {:api-key api-key} url))
    (catch clojure.lang.ExceptionInfo e
      (if (= :scrape (:type (ex-data e)))
        (unprocessable-entity {:reason (name (:reason (ex-data e)))})
        (throw e)))))          ; unexpected → propagates → Bugsnag reports it
```

**Error taxonomy → HTTP status:**

| Reason                | Meaning                              | Response |
|-----------------------|--------------------------------------|----------|
| `:bot-blocked`        | Cloudflare block, no fetcher to retry | `422 {:reason "bot-blocked"}` |
| `:fetch-failed`       | 404/500/timeout, non-challenge       | `422 {:reason "fetch-failed"}` |
| `:no-recipe-found`    | fetched but nothing extractable      | `422 {:reason "no-recipe-found"}` |
| `:blocked-url`        | SSRF guard rejected the URL          | `422 {:reason "blocked-url"}` |
| `:render-failed`      | Firecrawl itself errored             | **propagates → Bugsnag** |
| any other exception   | bug, NPE, etc.                       | **propagates → Bugsnag** |

`:render-failed` is deliberately *not* in the 422 set: a fallback provider
failing is an operational problem we want alerted, not a routine client answer.

## Firecrawl request/response

Request: `POST https://api.firecrawl.dev/v1/scrape`, `Authorization: Bearer …`,
body `{"url": "<url>", "formats": ["rawHtml"]}`.

Response (relevant fields): `{"success": true, "data": {"rawHtml": "<…>", …}}`.

**Why `rawHtml` and not `html`:** Firecrawl's `html` format is *cleaned* — it
rewrites/strips `<script>` tags, which removes the
`<script type="application/ld+json">` blocks our `parse-json-ld` depends on.
`rawHtml` is the unprocessed source, so the JSON-LD rides along and the free
deterministic path still fires. Getting `html` back would silently push every
scrape onto the paid LLM path. Firecrawl does **not** expose parsed JSON-LD as
a metadata field (its `metadata` is title/description/OpenGraph only), so
receiving the raw bytes and parsing them ourselves is the correct division of
labor.

## Config / secrets

- New Fly secret `FIRECRAWL_API_KEY` (per the ephemeral-env config rule: supply
  via secret, not templated `[env]` TOML).
- `KALEIDOSCOPE_RECIPE_FETCHER_TYPE` defaults to `none`; set to `firecrawl` on
  the environment(s) that should have the paid fallback (at minimum the
  ephemeral recipes env where the bug was seen).
- Local dev: `none` (or `mock`). No behavior change without the secret.

## Testing

Embedded, no live network (mock fetcher). Per CLAUDE.md every feature needs
tests; these live under `test/kaleidoscope/api/recipe_scraper_test.clj` and
`test/kaleidoscope/http_api/recipes_test.clj`.

1. Direct `200` with JSON-LD → extracts via JSON-LD; fetcher never called
   (assert the mock records zero calls).
2. Direct `403` + fetcher present → falls back, extracts from the mock's
   rendered `rawHtml`.
3. Direct `403` + no fetcher (`none`) → `:bot-blocked` → route returns
   `422 {:reason "bot-blocked"}`.
4. Challenge-marker body (200 with `"Just a moment"`) → treated as
   `:bot-blocked`, same fallback path as case 2.
5. Fetcher throws `:render-failed` → `scrape` propagates it (assert we do
   **not** swallow it into a 422 — proves the Bugsnag path).
6. Route-level: `422` body shape for an expected reason.

Stub `fetch-direct`'s HTTP at the hato boundary (or inject a fake status/body)
so no test makes a real outbound request.

## Out of scope / follow-ups

- Migrating `workflows/llm_executor.clj` off raw `java.net.http` interop onto
  hato — consistent direction, but unrelated to this fix.
- A manual "paste the recipe text" UI path for sites even Firecrawl can't clear
  (frontend repo).
- Caching / rate-limiting Firecrawl calls — only worth it if the paid tier is
  hit often; not now.

## Open decisions for user review

1. **hato vs clj-http** — recommending hato (same `java.net.http` engine, light
   deps). Say if you'd rather standardize on clj-http.
2. **Fetcher location** — `persistence/firecrawl.clj`. Alternative: alongside
   the scraper in `api/`. Recommending `persistence/` (external data access).
3. **Challenge-marker sniffing** — matching `"Just a moment"` / `cf-mitigated`
   in the body is heuristic. Acceptable to also treat plain `403` as
   `:bot-blocked` without body-sniffing if you'd prefer fewer moving parts.
