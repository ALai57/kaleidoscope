# JSON structured-output fix for LLM call sites

Date: 2026-07-04

## Problem

`workflows/llm_executor.clj`, `scoring/llm_scorer.clj`, and `tasks/planner.clj`
each independently ask Claude to "return ONLY a JSON object/array, no
additional text" in the system prompt, then recover that JSON from free-form
response text with hand-rolled scraping (`extract-json`, `strip-markdown-fences`,
`strip-fences` — three near-identical copies of the same markdown-fence-stripping
+ brace-scanning logic). Every one of these call sites has a
`(catch Exception ...)` fallback ("Parse error — proceeding", default score of
5.0, empty question list, etc.) that exists purely to cope with unreliable
text-based JSON — not a rare edge case, but a self-inflicted format-reliability
problem baked into the design. This was flagged as the highest-leverage fix in
a broader design-critique pass on the workflow engine.

Anthropic's Messages API has a purpose-built feature for exactly this:
**structured outputs** (`output_config: {format: {type: "json_schema", schema:
...}}`). It constrains the model's response so the returned text block is
guaranteed to validate against the given JSON Schema — no markdown fences, no
prose preamble, no scanning for `{`/`}`. This plan replaces the prose-scraping
fallback chain at all 7 JSON-producing call sites with schema-constrained
requests, and consolidates the 3x-duplicated HTTP/parsing boilerplate into one
shared client namespace in the process (unavoidable — fixing the parsing means
touching the same `post-anthropic(-sync)` helper in all three files anyway).

**Free-text call sites are untouched.** The `:text`/`:clarify`/`:refine`
workflow steps, the coach/PM/eng-agent conversational prompts, and the SSE
token-streaming paths (`stream-step-to-output!`, `stream-conversation!`) return
prose by design — they stay exactly as they are.

## Required prerequisite: bump the model

Anthropic's structured-outputs feature is confirmed supported on Claude Fable
5, Opus 4.8, Sonnet 5, Haiku 4.5, and legacy Opus 4.5/4.1 — **Opus 4.6 (the
model this codebase currently pins via `default-model`) is not on that list.**
Since this fix depends entirely on structured outputs working, bump
`default-model` from `"claude-opus-4-6"` to `"claude-opus-4-8"` as part of this
change. Same pricing tier ($5/$25 per MTok on both), no `thinking` parameter is
set anywhere in these three files today, so there's no adaptive-thinking
migration wrinkle — this is a clean model-ID swap. Update the one-line mention
of `claude-opus-4-6` in `CLAUDE.md`'s "AI workflow system" section to match.

## Approach

### 1. New shared client namespace: `kaleidoscope.clients.anthropic`

Following the existing `kaleidoscope.clients.*` pattern (`stripe.clj`,
`bugsnag.clj`, `route53.clj`, `session_tracker.clj`), add one namespace that
consolidates what's currently duplicated three times:

- `default-model` ("claude-opus-4-8"), `anthropic-messages-url`,
  `anthropic-version` — single definitions instead of three copies.
- A single reused `HttpClient` instance instead of constructing one per call.
- `extract-text` — pulls the first text block's `:text` (unchanged behavior,
  just de-duplicated).
- `call!` — the synchronous non-streaming POST helper (replaces
  `post-anthropic-sync` in `llm_executor.clj`/`tasks/planner.clj` and
  `post-anthropic` in `llm_scorer.clj`). Takes `{:keys [system messages
  max-tokens output-schema]}`; when `output-schema` is supplied, adds
  `{:output_config {:format {:type "json_schema" :schema output-schema}}}` to
  the request body. Callers that don't need structured output simply omit
  `output-schema`.
- No new "extract JSON from text" function is needed — with
  `output_config.format` set, the guaranteed-valid JSON is already the full
  text of the response's text block, so callers do a plain `(json/decode
  (extract-text response) true)`. This is what eliminates
  `extract-json`/`strip-markdown-fences`/`strip-fences` entirely rather than
  just consolidating them.

The streaming helpers (`stream-step-to-output!` in `llm_executor.clj`,
`stream-conversation!` in `llm_scorer.clj`) are **not** moved into this
namespace — out of scope, they're prose paths.

### 2. Convert the 7 JSON-producing call sites

Each gets an explicit JSON Schema (object schemas need `additionalProperties:
false` per Anthropic's structured-outputs requirements) passed as
`output-schema` to `call!`, and its parsing collapses to a direct
`json/decode` — no fence-stripping, no brace-scanning:

| File | Function | Output shape |
|---|---|---|
| `scoring/llm_scorer.clj` | `parse-score-response` / `score` / `score-with-user-prompt` | Object: `{overall: number, dimensions: [{name, value, rationale}]}` — dimension names stay dynamic (schema doesn't need to know them, just the per-item shape) |
| `scoring/llm_scorer.clj` | `generate-skills` | Array of `{name, description, parent, position}` — top-level array schemas are supported directly by `output_config.format` (unlike tool `input_schema`, which requires an object), so no `{"skills": [...]}` wrapper is needed |
| `scoring/llm_scorer.clj` | `generate-section-questions` | Object: `{questions: [string]}` |
| `tasks/planner.clj` | `assess-description` | Object: `{ready: bool, reply: string}` |
| `tasks/planner.clj` | `generate-task-list` | Array of `{title, description, task_type, estimated_minutes}` |
| `workflows/llm_executor.clj` | `recommend-workflows` (classifier) | Array of `{workflow_id, confidence, rationale}` |
| `workflows/llm_executor.clj` | `execute-step-by-kind! :decision` (judge) | Object, one flat schema covering all three actions (see below) |

**Judge decision schema — a superset, not a `oneOf`.** The judge's response
shape varies by `action` (`proceed`/`refine`/`clarify`), each with its own
extra fields (`unresolved` vs. `agent_to_refine`+`refinement_prompt` vs.
`questions`). Rather than modeling this as a JSON Schema `anyOf` discriminated
union, use one flat object schema with `action` required (enum of the three
values) and every other field optional — the downstream code in
`api/workflows.clj` already reads these with `get`/defaults and branches on
`(:action decision)`, so it's already written to tolerate "some fields present
depending on action." This keeps the schema simple and avoids fighting the
model into picking the right variant of a `oneOf`.

### 3. Delete the now-dead parsing helpers

`extract-json` (`llm_executor.clj`), `strip-markdown-fences`
(`llm_scorer.clj`), `strip-fences` (`tasks/planner.clj`) all get deleted —
nothing calls them once every JSON-producing site uses `output_config.format`.
The existing `(catch Exception ...)` fallback branches around each parse stay
in place as defense-in-depth (the API can still theoretically emit something
unparseable, or `max_tokens` can truncate output mid-JSON), but they should
now be effectively dead code in normal operation rather than the routine
safety net they are today.

### 4. Update `agents.clj` system prompts (optional cleanup, not required)

The system prompts in `scoring/agents.clj` already say things like "Return
ONLY the JSON object, no additional text" — with `output_config.format`
enforced, that instruction becomes redundant (the API guarantees it
structurally) but is harmless to leave as-is. No prompt text changes are
required for this fix to work; flagging only so it's not mistaken for
something forgotten.

## Files touched

- **New:** `src/kaleidoscope/clients/anthropic.clj`
- **Modified:** `src/kaleidoscope/scoring/llm_scorer.clj`,
  `src/kaleidoscope/tasks/planner.clj`,
  `src/kaleidoscope/workflows/llm_executor.clj`
- **Modified:** `CLAUDE.md` (the `claude-opus-4-6` mention under "AI workflow
  system")
- **New tests:** `test/kaleidoscope/clients/anthropic_test.clj` — pure unit
  tests for `call!`'s request-body construction (with/without
  `output-schema`) and `extract-text`, using fake response maps (no network).
  These are the first tests these HTTP-calling namespaces will have had at
  all.

## Verification

1. `task test` (or `task test:summary` per this repo's debugging convention)
   — confirm the existing suite (mock executor/scorer paths, which don't
   touch the real API) is unaffected, plus the new `anthropic_test.clj` unit
   tests pass.
2. Manual, against the real API: set `ANTHROPIC_API_KEY`,
   `KALEIDOSCOPE_SCORER_TYPE=llm`, `KALEIDOSCOPE_WORKFLOW_EXECUTOR_TYPE=llm`
   in `.env.local`, `task run`, then drive a real `autonomous-team-review`
   workflow run through to a judge decision (PM/Eng score steps → judge →
   proceed/refine) and a task-generation step. Confirm in logs that none of
   the "Failed to parse ... parse error" fallback branches fire.
3. `grep -rn "extract-json\|strip-markdown-fences\|strip-fences"` across
   `src/` to confirm no leftover references after deletion.
