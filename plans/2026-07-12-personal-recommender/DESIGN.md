# Design: Personal recommender

## The problem

As a user, I find that I get completely sidetracked by the attention and
recommendations from different platforms. For example, when I go on Netflix,
I'm assaulted by recommendations, most of which are complete garbage.

Instead of having recommendations pushed, I'd like to build an app that's
intentional and **pulls** recommendations.

The user identifies several topics they're interested in, then the system
curates a set of resources/materials of different types within that category.
This gets tuned over time.

The goal is a personalized and relevant recommendation engine spanning
different kinds of media — podcasts, articles, shows, papers, books, etc.

---

## Product principles

The whole design is a deliberate inversion of the attention economy. Every
decision is checked against these:

| Attention economy | Personal recommender |
|---|---|
| Pushes content; infinite scroll | You **pull**; shelves are finite |
| Optimizes for engagement / time-on-app | Optimizes for **relevance to stated intent** |
| Learns from implicit tracking + one-tap reactions | Learns from an **explicit, editable taste profile** and deliberate check-ins |
| Hides why something was surfaced | Every item carries a one-line **"why this is here"** |
| The algorithm decides how adventurous you are | **You** set the explore/exploit ratio |

Two tuning mechanisms only — both explicit, both user-initiated:

1. **Taste profile** — a per-interest document the user edits directly.
2. **Check-ins** — a periodic, opt-in calibration pass.

Deliberately excluded: per-item thumbs, implicit "you watched X so here's Y"
tracking, autoplay, badges, and unbounded feeds. These are the mechanics the
product exists to escape.

---

## Core rhythm

A **standing library**, not a digest. Shelves are always available and
organized by interest. Nothing is pushed. The user pulls when they want to;
curation refreshes quietly in the background. There is no notification demanding
a return.

---

## The taste profile

The single source of truth for what an interest should surface. Fully
user-editable. Per interest:

| Field | Meaning |
|---|---|
| `intent` | Free-text statement of what the user cares about, in their words |
| `keywords` | Sharpened terms distilled from the intent (during refinement) |
| `formats` | Preferred media kinds (podcast, article, show, video, book, paper, newsletter, course) |
| `lengths` | Preferred time commitment (e.g. "under 20 min", "long-form OK") |
| `trusted_sources` | An allowlist of sources the user likes and trusts |
| `novelty_ratio` | 0.0–1.0 — the share of each shelf drawn from *outside* the trusted set |
| `cadence` | How often check-ins are offered |

### Source trust + the novelty dial (explore/exploit)

The user picks **sources they like and trust** and sets what percentage of the
material should be **novel** — drawn from sources outside their allowlist.

> Example: "I like PBS Frontline and The Hill, but I want 50% of the material
> to be novel."

- **`trusted_sources`** — the exploit set. Prioritized and always eligible.
- **`novelty_ratio`** — the explore dial. At `0.5`, roughly half of every shelf
  is filled from trusted sources and half is genuinely new sources the
  Librarian agent goes and finds.

This makes the explore/exploit trade-off a **user control**, not a hidden
algorithmic choice. Novel items are tagged **"new source"** on their cards, so
exploration is always visible — never smuggled in. Over time a novel source the
user keeps engaging with can be promoted into `trusted_sources` (proposed during
a check-in, never automatic).

---

## Kaleidoscope integration

The recommender is built on the existing AI workflow engine rather than beside
it. The mapping:

| Recommender concept | Kaleidoscope primitive |
|---|---|
| **Interest** (a topic) | ≈ a **Project** — owns the intent + taste profile |
| **Curation run** | a **Workflow run** (`workflow_runs` / `step_runs`) |
| Refinement Q&A | a **`clarify`** step (reuses the existing mechanism) |
| Relevance filtering | a **`score`** step + the scoring engine's threshold config |
| Curator persona | a new **Librarian (📚)** agent in `scoring/agents.clj` |
| Mock vs. real curation | mock executor in dev/tests, `llm` executor in prod |

### The curation workflow

A per-interest workflow, run through the existing sequential/score machinery:

1. **Clarify** (`output-kind: "clarify"`, agent `librarian`) — when the intent
   is too thin to curate well, ask 1–2 targeted refinement questions. Answers
   are folded into the taste profile. This is exactly what the existing clarify
   step does for project descriptions.
2. **Discover** (agent `librarian`) — propose candidate resources across media
   kinds. Honors the novelty dial: fill the trusted quota from `trusted_sources`
   first, then hunt for novel, non-allowlisted sources to fill the remainder.
3. **Relevance score** (`output-kind: "score"`) — score each candidate against
   the taste profile; drop anything below threshold. Reuses the
   `scrutiny`/threshold config pattern (`quick`/`standard`/`rigorous`).
4. **Shelve** — survivors are written to the library with their rationale.

**Check-in** is a separate lightweight run: surface a handful of items, record
which land, and update the taste profile (adjust keywords, promote a novel
source, nudge the novelty dial).

### New agent persona

`Librarian (📚)` — a curator persona following the existing pattern in
`scoring/agents.clj`. It performs discovery (step 2) and relevance scoring
(step 3), and asks the clarifying questions (step 1). System prompt emphasizes
breadth across media kinds, honoring the trusted/novel split, and writing a
concise "why this is here" rationale for every recommendation.

---

## Data model

New tables, added via Migratus migrations (never altered directly):

### `interests`
| Column | Type | Notes |
|---|---|---|
| `id` | uuid | PK |
| `user_id` | text | owner |
| `intent` | text | free-text statement |
| `taste_profile` | jsonb | `{keywords, formats, lengths, trusted_sources, novelty_ratio, cadence}` |
| `created_at` / `updated_at` | timestamptz | |

### `recommendations`
| Column | Type | Notes |
|---|---|---|
| `id` | uuid | PK |
| `interest_id` | uuid | FK → interests |
| `kind` | text | podcast / article / show / video / book / paper / newsletter / course |
| `title` | text | |
| `source` | text | e.g. "PBS Frontline" |
| `url` | text | |
| `est_time` | text | e.g. "18 min", "6 episodes" |
| `why` | text | one-line rationale surfaced on the card |
| `origin` | text | `trusted` or `novel` (drives the "new source" tag) |
| `status` | text | `shelved` / `queued` / `archived` |
| `added_at` | timestamptz | |

Curation reuses `workflow_runs` and `step_runs` — no new run tables.

---

## Screens (the prototype)

The interactive prototype demonstrates the full loop with mock/curated data and
makes the multi-agent machinery visible.

1. **Shelves (home)** — the standing library. Left rail lists interests; the
   main stage shows the selected interest's shelf as a finite set of catalog
   cards. Media-kind filter chips. Each card: media kind (as a catalog code) ·
   title · source · est. time · one-line "why" · trust/novel tag.
2. **Acquisitions pipeline** — the visible machinery. Discover → Relevance
   Score → Shelve, showing the Librarian and Scorer agents at work and the
   novelty split of the result ("6 shelved · 3 trusted · 3 novel").
3. **Taste profile editor** — keywords, formats, lengths, and a **Sources**
   section: the trusted allowlist (add/remove) plus the **novelty dial** (a live
   slider that re-balances the trusted/novel mix of the shelf).
4. **Onboarding** — add an interest in free text → the refinement Q&A exchange →
   a new shelf is created.
5. **Check-in** — periodic calibration: "Here are five — which land?" Updates
   the taste profile and can promote a novel source to trusted.

### Visual direction

Because the recommender plugs into Kaleidoscope, it adopts the **Prism design
system** (`kaleidoscope-ui/src/theme`, `components/prism/`) rather than a
bespoke look — a **dark-committed "instrument panel"**: the Prism dark planes
(`#0A0E15` base / `#10151E` raised / `#1D2634` sunken), the cyan interactive
accent (`#45D6E8`), monospace "data-voice" headings and eyebrows over a sans
body, pill chips with categorical dots, mono metadata footers, and Prism's
spring motion. The core mechanic maps onto Prism's **categorical spectrum**:
**purple `#9085E9` for trusted** (exploit) and **amber `#C98500` for novel**
(explore) — held distinct from the cyan accent and from semantic status colors;
the novelty dial gradient visibly mixes the two. Prism is dark-only by design,
so there is no light theme.

Two prototypes exist for comparison:
- `prototype-prism.html` — **the adopted Prism adaptation** (the direction to build).
- `prototype.html` — an earlier standalone "reading-room" exploration (warm
  editorial, serif titles, light/dark) kept as a design alternative.

---

## Testing

Every layer that ships gets automated coverage (per CLAUDE.md):

- **Curation workflow** — end-to-end against the mock executor: an interest with
  a taste profile produces a shelf; the trusted/novel split matches the
  `novelty_ratio` within tolerance; below-threshold candidates are dropped.
- **Novelty ratio** — unit tests on the trusted/novel quota split for ratios at
  the edges (0.0, 1.0) and midpoints.
- **Taste profile edits** — updating `trusted_sources` / `novelty_ratio` changes
  the next curation's composition.
- **Clarify reuse** — refinement answers are folded into the taste profile.
- **Persistence** — `interests` / `recommendations` CRUD against embedded H2.

---

## Phase 2: Pluggable source retrieval

Phase 1's **Discover** step is the Librarian LLM *proposing* candidates from its
own knowledge (title, source, url, relevance, why) — mock data in dev, model
recall in production. There is no real fetch. Phase 2 makes retrieval real:
candidates come from actual sources (YouTube, NPR, RSS, …), and the Librarian
shifts from *authoring* candidates to *planning queries and re-ranking real
results* — a retrieval-augmented pattern that also removes hallucinated URLs.

This is deliberately a **separate subsystem**, not a rewrite. Phase 1 leaves the
seams for it: the `recommendations` shape is the normalization target, scoring
is source-blind, and the workflow engine already fans out.

### Source connector abstraction

A new `sources/` layer follows the codebase's house pattern — protocol + swappable
impls + a mock sibling, selected in `init/env.clj`, exactly like DB / storage /
auth / scoring / workflow execution:

```clojure
;; sources/protocol.clj
(defprotocol SourceConnector
  (search      [_ query opts])   ; → normalized candidates ({:kind :title :source :url :est-time})
  (fetch       [_ external-id])  ; → metadata for one item
  (auth-status [_ ctx]))         ; → :ok | :needs-login | :no-access

;; sources/youtube.clj  — YouTube Data API impl
;; sources/npr.clj       — NPR API impl
;; sources/rss.clj       — generic RSS/Atom (covers a long tail of sources)
;; sources/mock.clj      — deterministic, no network (keeps tests API-key-free)
```

### Curation workflow, retrieval-augmented

The curation workflow gains two stages, both natural fits for the engine's
existing `parallel` / `fan_in` execution modes:

```
Refine (clarify)
  → Plan queries   (Librarian turns the taste profile into per-source queries)
  → Retrieve       (fan out to connectors in parallel — real results)
  → Rank           (Librarian/Scorer re-ranks the retrieved candidates)
  → threshold → novelty split → shelve   (unchanged, source-blind)
```

Nothing downstream of Rank changes: `tag-origin`, `novelty-quota`,
`drop-below-threshold`, and the `recommendations` table are all indifferent to
which connector a candidate came from. `taste_profile.trusted_sources` and
`formats` select *which* connectors to query (e.g. "The Hill" → RSS, "video" →
YouTube).

### Auth is four problems, not one

The hard, heterogeneous part. Source auth spans the full spectrum; each
connector encapsulates its own credential lookup and `auth-status`, so the api
orchestrator only ever sees candidates-or-`:needs-login`:

| Bucket | Examples | What it needs |
|---|---|---|
| **Open / no auth** | RSS/Atom, podcast feeds, arXiv, per-channel YouTube RSS | Nothing — ship these first. |
| **App-level API key** | YouTube Data API, NPR, NYT/Guardian | One shared secret via Fly secrets → `init/env`; **per-connector outbound quota/backoff** (distinct from the inbound `wrap-rate-limit`). |
| **Per-user OAuth** | Google/YouTube (subscriptions), Spotify library | A `connections` table (per-user tokens + refresh) and **new OAuth callback routes** in `http_api/`. Per-user, not per-app. |
| **Paywall / no open API** | WSJ, most newspapers | No clean API, and scraping violates ToS. Realistic path: **link-out + an "I subscribe" entitlement flag** (the user's own session gates reading), or licensed enterprise feeds (Dow Jones/Factiva) — a contract, not an API key. |

### New surface Phase 2 introduces

- **Credential/connection model** — a new table + secret strategy; per-app vs
  per-user is a real fork (OAuth token storage + refresh + revocation).
- **OAuth callback routes** for per-user sources — new `http_api` surface.
- **Dedup + fetch cache + freshness** — the same story from several sources,
  pagination, and a cache so curation doesn't re-burn quotas. None exists in
  Phase 1.
- **Mock connector per source** — to keep the "no API key in tests" rule.
- **Librarian contract change** — from "emit candidates" to "emit per-source
  queries" + a re-rank prompt (supersedes the Phase 1 JSON candidate contract).

### Sequencing

1. **Generic RSS connector first** (bucket 1, no auth) — immediately covers
   The Hill, NPR, most blogs and podcasts, and proves the `SourceConnector`
   seam end-to-end before any OAuth work.
2. App-level API-key connectors (YouTube, NPR API) — one connector at a time.
3. Per-user OAuth (Google/Spotify) — only once the connection model + callback
   routes exist.
4. Paywalled sources — link-out + entitlement only; no full-text retrieval.

**Legal note:** WSJ-style full-text retrieval is a licensing problem, not an
engineering one. Phase 2 surfaces such sources as links gated by the reader's
own subscription; it never scrapes paywalled content.

---

## Out of scope (YAGNI)

- Real source ingestion / crawling — **deferred to Phase 2** (above); Phase 1
  uses LLM-proposed candidates with curated mock data in dev/tests.
- Social features, sharing, multi-user shelves.
- Per-item thumbs and implicit tracking — deliberately excluded by principle.
- Mobile-native app — the prototype is responsive web.
