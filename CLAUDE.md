# Kaleidoscope

Multi-tenant Clojure CMS that serves multiple user sites (andrewslai.com, caheriaguilar.com, sahiltalkingcents.com) from a single server by inspecting the HTTP `Host` header. The backend also hosts an AI-powered workflow engine that orchestrates multi-agent project evaluation and task planning via the Anthropic API.

The frontend lives in a separate repo (`kaleidoscope-ui`) and is not built or served from here.

---

## Architecture

Strict 3-layer separation — never cross layers in the wrong direction:

```
http_api/      → HTTP handlers, routing, middleware, auth
api/           → Domain logic, business rules (no HTTP, no DB)
persistence/   → Data access only (RDBMS, filesystem)
```

All backends are pluggable via protocols: DB, storage, auth, scoring, and workflow execution each have a protocol + multiple implementations (real + mock/embedded for testing).

---

## Commands

Tasks are run via [Taskfile](https://taskfile.dev) (`brew install go-task`). Run `task --list` to see all available tasks.

| Task | Description |
|---|---|
| `task run` | Start the development server on port 5001 |
| `task test` | Run the full test suite |
| `task db:migrate` | Run pending migrations |
| `task db:reset` | Drop all tables and re-run migrations (**destructive** — prompts for confirmation) |
| `task db:connect` | Open a psql shell to the database |
| `task build:uberjar` | Compile and package the standalone JAR |
| `task build:docker` | Build the Docker image (run `build:uberjar` first) |
| `task deploy` | Login to ECR, push image, trigger ECS deployment |
| `task clean` | Remove build artifacts |

Tasks that talk to a database accept an `ENV` variable to select the env file (default: `.env.local`):
```bash
task db:migrate                        # uses .env.local
task db:migrate ENV=.env.aws           # targets cloud DB
task db:connect ENV=.env.aws
```

The underlying implementations live in `bin/` — the Taskfile is the interface, the scripts are canonical. Keep `Taskfile.yml` in sync when `bin/` scripts change.

---

## Running locally

```bash
task run
```

Copy `env.local.example` to `.env.local`. Default local config:

| Variable | Default | Notes |
|---|---|---|
| `KALEIDOSCOPE_DB_TYPE` | `embedded-h2` | In-memory, wiped on restart |
| `KALEIDOSCOPE_AUTH_TYPE` | `custom-authenticated-user` | Fakes a logged-in user |
| `KALEIDOSCOPE_AUTHORIZATION_TYPE` | `public-access` | No ACL enforcement |
| `KALEIDOSCOPE_STATIC_CONTENT_TYPE` | `local-filesystem` | Points to local kaleidoscope-ui build |
| `KALEIDOSCOPE_SCORER_TYPE` | `mock` (default off) | Set to `llm` + add `ANTHROPIC_API_KEY` to use real scoring |
| `KALEIDOSCOPE_WORKFLOW_EXECUTOR_TYPE` | `mock` (default off) | Set to `llm` to use real Claude |

Server runs on port 5001 by default.

---

## Testing

```bash
task test
```

Framework: **Kaocha** + **matcher-combinators** + **ring-mock** / **peridot**.

**Every feature must have automated tests.** The layer is flexible — discuss whether unit or end-to-end tests are appropriate for each case, but no feature ships without some form of automated coverage.

Tests use embedded databases — no external DB needed:
- `embedded-h2/fresh-db!` — fast, in-memory, default for most tests
- `embedded-postgres/fresh-db!` — closer to production, use when behavior diverges

Test files mirror the source structure under `test/kaleidoscope/`. Common helpers are in `test/kaleidoscope/test_utils.clj` (JWT creation, app wrapping, response parsing).

---

## Database migrations

Migrations live in `resources/migrations/` and are managed by **Migratus**.

```bash
task db:migrate                   # run pending migrations
task db:reset                     # drop all tables + re-migrate (prompts first)
task db:connect ENV=.env.aws      # connect to cloud DB
```

**Never change the DB schema without a migration file.** Always create a new numbered `.up.sql` / `.down.sql` pair.

---

## Key namespaces

| Namespace | Role |
|---|---|
| `kaleidoscope.main` | Entry point, server startup, OTEL/logging init |
| `kaleidoscope.dev` | REPL utilities, hot-reload dev server |
| `kaleidoscope.init.env` | Boot instructions — selects implementations from env vars |
| `kaleidoscope.http-api.kaleidoscope` | Main router, aggregates all routes |
| `kaleidoscope.http-api.virtual-hosting` | Host-header based multi-tenant routing |
| `kaleidoscope.workflows.llm-executor` | Claude integration, SSE streaming, workflow orchestration |
| `kaleidoscope.scoring.llm-scorer` | Claude-based project scoring |
| `kaleidoscope.scoring.agents` | System prompts for all agent personas |
| `kaleidoscope.tasks.planner` | Task generation from workflow outputs |
| `kaleidoscope.persistence.rdbms` | next.jdbc utilities, JSON/JSONB helpers |

---

## Code conventions

- `!` suffix on all side-effecting functions (`create-article!`, `seed-default-agent-definitions!`)
- SQL columns are `snake_case`; Clojure uses `kebab-case` — conversion is automatic via `camel-snake-kebab`
- Logging: Timbre with structured JSON in production; plain text in dev
- Tracing: wrap key functions with `span/with-span! {:name "..."}` for OTEL
- DB queries: next.jdbc + HoneySQL; use helpers in `persistence/rdbms.clj`
- Schema validation: Malli; validate at system boundaries (HTTP input, external APIs)
- Comments: only when the WHY is non-obvious — the codebase is intentionally comment-light

---

## AI workflow system

**Data model:** Projects → Workflows → Workflow Steps → Step Runs → Tasks

**Default workflows** (defined in `api/workflows.clj`):
- `autonomous-team-review`: PM Review → Eng Review → Judge → Task Generation (parallel PM+Eng, then fan-in)
- `feature-development`: Clarify → PM Review → Eng Review → Task Generation

**Agent personas** (prompts in `scoring/agents.clj`):
- Coach (🐬), Product Manager (🦊), Engineering Lead (🦉), Task Planner (📋)

**Step output kinds:** `clarify`, `text`, `score`, `decision`, `tasks`

**Execution modes:** `sequential`, `parallel`, `fan_in`

**Anthropic API:** All LLM calls currently use `claude-opus-4-6` with no prompt caching. SSE streaming is used for interactive steps. The mock executor (`workflows/mock.clj`) and mock scorer (`scoring/mock.clj`) are used in tests and local dev when `ANTHROPIC_API_KEY` is absent.

---

## Debugging tests

When debugging test failures, never pipe full test output directly into the conversation — stack traces are expensive and rarely useful. Instead:

1. Use `task test:summary` to get failure names and assertions with no stack traces. For DB migration errors, fall back to saving output to the scratchpad and grepping selectively:
   ```bash
   ./bin/test 2>&1 > $SCRATCHPAD/test.log
   grep -E "^(FAIL|ERROR) in" $SCRATCHPAD/test.log        # list failures
   grep -A4 "^FAIL" $SCRATCHPAD/test.log                   # assertions only
   grep "failed to execute\|Syntax error\|Exception:" $SCRATCHPAD/test.log  # DB errors
   ```
2. Use `--focus` to run one test at a time — never read the full suite output to diagnose a single failure.
3. Only escalate to reading more output when the targeted grep doesn't have enough signal.

`$SCRATCHPAD` is `/private/tmp/claude-501/-Users-alai-code-kaleidoscope/8c87afcf-5e99-4f43-89ce-c2a7171dd6ac/scratchpad`.

---

## Sharp edges

1. **Never bypass the 3-layer separation.** No persistence calls from `http_api/`; no HTTP concerns in `api/`.
2. **All schema changes require a migration file.** Never alter tables directly.
3. **Don't refactor legacy CMS code** (articles, albums, portfolio) unless it's explicitly the task.
4. **Every feature needs automated tests.** Unit or end-to-end — discuss the right layer, but "no tests" is not acceptable.
5. **Keep `Taskfile.yml` in sync with `bin/`.** If a bin script is added, renamed, or changes its interface, update the Taskfile.

---

## Deployment

- **Platform:** Fly.io (`fly.toml`), primary region `iad`
- **DB:** Neon (managed PostgreSQL) in production
- **Build:** Multi-stage Docker build — custom JRE via jlink, distroless runtime, AppCDS for fast startup
- **Deploy:** `scripts/deployment/push-to-ecr` → Fly.io pull from ECR
