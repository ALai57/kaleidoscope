# Implementation Plan: Personal Project Manager

## Key Design Decisions

1. **Scores are user-definable, not hard-coded.** Intent Clarity and Architecture Clarity are seeded as defaults but the schema is fully data-driven.
2. **All scoring runs are versioned and retained** for auditing. The project response returns a `scores` array of self-documenting objects.
3. **Scorer protocol accepts a `score-definition`** at runtime, so any definition can be scored without code changes.
4. **Skill gap generation is agentic** — the Eng Lead agent produces the skill tree from the project description.
5. **Voice input** is captured in-browser (MediaRecorder) and transcribed server-side.

---

## Database Schema

**Migration 1 — projects**
```sql
CREATE TABLE projects (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     TEXT NOT NULL,
  title       TEXT NOT NULL,
  description TEXT,
  status      TEXT NOT NULL DEFAULT 'idea', -- idea | developing | executing
  created_at  TIMESTAMPTZ DEFAULT now(),
  updated_at  TIMESTAMPTZ DEFAULT now()
);
```

**Migration 2 — score definitions (user-owned, seeded with defaults)**
```sql
-- A named scorer with a plain-English description
CREATE TABLE score_definitions (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     TEXT NOT NULL,
  name        TEXT NOT NULL,
  description TEXT NOT NULL,
  scorer_type TEXT NOT NULL DEFAULT 'general', -- pm | engineering_lead | general
  is_default  BOOLEAN NOT NULL DEFAULT false,
  created_at  TIMESTAMPTZ DEFAULT now(),
  updated_at  TIMESTAMPTZ DEFAULT now()
);

-- Each criterion that makes up the overall score
CREATE TABLE score_dimension_definitions (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  score_definition_id UUID NOT NULL REFERENCES score_definitions(id) ON DELETE CASCADE,
  name                TEXT NOT NULL,
  criteria            TEXT NOT NULL,  -- plain English, fed to the LLM as scoring rubric
  position            INT NOT NULL DEFAULT 0
);
```

**Migration 3 — versioned score runs**
```sql
-- One run per (project × definition), version auto-incremented
CREATE TABLE project_score_runs (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id          UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  score_definition_id UUID NOT NULL REFERENCES score_definitions(id),
  version             INT NOT NULL,
  overall             NUMERIC(4,2),
  scored_at           TIMESTAMPTZ DEFAULT now()
);

-- Per-dimension results, denormalised name for historical fidelity
CREATE TABLE project_score_dimensions (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  score_run_id    UUID NOT NULL REFERENCES project_score_runs(id) ON DELETE CASCADE,
  dimension_name  TEXT NOT NULL,
  value           NUMERIC(4,2),
  rationale       TEXT
);
```

**Migration 4 — notes, conversations, skills**
```sql
CREATE TABLE project_notes (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  content    TEXT NOT NULL,
  source     TEXT NOT NULL DEFAULT 'text', -- text | voice
  created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE project_conversations (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  agent_type TEXT NOT NULL, -- coach | pm | engineering_lead
  role       TEXT NOT NULL, -- user | assistant
  content    TEXT NOT NULL,
  created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE project_skills (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id  UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  parent_id   UUID REFERENCES project_skills(id),
  name        TEXT NOT NULL,
  description TEXT,
  status      TEXT NOT NULL DEFAULT 'identified', -- identified | learning | mastered
  position    INT NOT NULL DEFAULT 0,
  created_at  TIMESTAMPTZ DEFAULT now()
);
```

---

## API Contracts

All routes gated by `auth/require-*-writer`. New ACL entries in `kaleidoscope.clj`:
```clojure
{:pattern #"^/projects.*"         :handler auth/require-*-writer}
{:pattern #"^/score-definitions.*" :handler auth/require-*-writer}
```

### Score Definitions (user-managed)

| Method | Path | Notes |
|--------|------|-------|
| `GET` | `/score-definitions` | List all definitions for the authenticated user |
| `POST` | `/score-definitions` | Create definition; body: `{name, description, scorer_type, dimensions: [{name, criteria}]}` |
| `GET` | `/score-definitions/:id` | Get definition + dimensions |
| `PUT` | `/score-definitions/:id` | Update name/description/dimensions |
| `DELETE` | `/score-definitions/:id` | Delete (blocked if `is_default`) |

### Projects

| Method | Path | Notes |
|--------|------|-------|
| `GET` | `/projects` | List projects; includes latest score run per definition |
| `POST` | `/projects` | `{title, description}` → triggers scoring against all default definitions |
| `GET` | `/projects/:id` | Full project with all latest score runs |
| `PUT` | `/projects/:id` | `{title?, description?, status?}` |
| `DELETE` | `/projects/:id` | Cascades to all children |

### Notes

| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/projects/:id/notes` | `{content, source}`. For `source=voice`, body is raw audio; server transcribes via Whisper, stores transcript |
| `GET` | `/projects/:id/notes` | → `[Note]` |

### Scoring

| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/projects/:id/scores` | Scores against all `is_default=true` definitions (or pass `{definition_ids: [...]}` to score specific ones). Writes new versioned run per definition. Returns updated project. |
| `GET` | `/projects/:id/scores` | Latest run per definition |
| `GET` | `/projects/:id/scores/history` | All runs, all versions, all definitions |

### Conversations (SSE)

| Method | Path | Notes |
|--------|------|-------|
| `GET` | `/projects/:id/conversations/:agent` | History (`coach` \| `pm` \| `engineering_lead`) |
| `POST` | `/projects/:id/conversations/:agent` | `{message}` → SSE stream of tokens; full turn persisted on completion |

### Skills

| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/projects/:id/skills/generate` | Eng Lead agent analyses project + description, writes skill tree (replaces existing) |
| `GET` | `/projects/:id/skills` | Nested tree: `[{id, name, status, children: [...]}]` |
| `PUT` | `/projects/:id/skills/:skill-id` | `{status}` — update mastery |

**Project response shape** (scores as self-documenting array):
```json
{
  "id": "uuid",
  "title": "...",
  "description": "...",
  "status": "idea",
  "scores": [
    {
      "id": "run-uuid",
      "version": 2,
      "scoredAt": "2026-04-12T10:00:00Z",
      "definition": { "id": "def-uuid", "name": "Architecture Clarity", "scorerType": "engineering_lead" },
      "overall": 6.0,
      "dimensions": [
        { "name": "moduleDesign",  "value": 4, "rationale": "..." },
        { "name": "apiDesign",     "value": 8, "rationale": "..." }
      ]
    },
    {
      "id": "run-uuid-2",
      "version": 2,
      "scoredAt": "2026-04-12T10:00:00Z",
      "definition": { "id": "def-uuid-2", "name": "Intent Clarity", "scorerType": "pm" },
      "overall": 4.2,
      "dimensions": [
        { "name": "userBehaviors", "value": 5, "rationale": "..." }
      ]
    }
  ]
}
```

---

## Backend Implementation

### Namespace layout
```
src/kaleidoscope/
  models/
    projects.cljc                  Malli schemas: Project, Note, Skill, ScoreDefinition, ScoreRun
  persistence/
    projects.clj                   HoneySQL + next.jdbc queries (find-by-keys, insert!, update!)
  scoring/
    protocol.clj                   IScorer defprotocol
    mock.clj                       Deterministic stub (all dimensions → 5.0)
    llm_scorer.clj                 Generic LLM scorer (wraps Claude API, uses definition as prompt)
    agents.clj                     PM and Eng Lead system prompts; skill generation
  api/
    projects.clj                   Business logic: orchestrates persistence + scoring
    score_definitions.clj          CRUD for score definitions + seeding defaults
  http_api/
    projects.clj                   Reitit routes + Ring handlers for all project routes
    score_definitions.clj          Reitit routes + Ring handlers for score definitions
```

**Scoring protocol:**
```clojure
(defprotocol IScorer
  (score [this project score-definition]
    "score-definition: {:name str :description str :scorer-type kw
                        :dimensions [{:name str :criteria str}]}
     Returns: {:overall n :dimensions [{:name str :value n :rationale str}]}"))
```

The `llm-scorer` implementation builds a system prompt from `score-definition`'s description and dimensions, calls Claude with the project title + description as user content, and parses the structured response. PM/Eng Lead personas are applied by `scorer-type`.

**Version increment:** On `POST /projects/:id/scores`, the next version is `(SELECT COALESCE(MAX(version), 0) + 1 FROM project_score_runs WHERE project_id = ? AND score_definition_id = ?)`, executed in a transaction.

**Default seeding:** On first user login (or `POST /score-definitions/seed`), the two default definitions (Intent Clarity with 5 dimensions, Architecture Clarity with 7 dimensions) are inserted with `is_default=true`. Users cannot delete defaults but can add their own.

**Component wiring** (`init/env.clj`): `IScorer` implementation added to the component map alongside existing adapters. Handlers access it via `(-> request :components :scorer)`.

---

## Frontend Implementation

### Pages (`/src/pages/projects/`)
```
ProjectsPage.tsx            /projects                  list ↔ graph toggle; maturity summary
ProjectDetailPage.tsx       /projects/:id              score runs, notes, action buttons
ProjectDevelopPage.tsx      /projects/:id/develop      SSE coach chat
ProjectSkillsPage.tsx       /projects/:id/skills       skill tree + mastery toggle
ScoreDefinitionsPage.tsx    /score-definitions         manage score definitions + dimensions
```

### Components (`/src/components/projects/`)
| Component | Purpose |
|-----------|---------|
| `ProjectCard` | Title, status chip, sparkline of maturity across score types |
| `ScoreRunCard` | Self-documenting: renders any score definition's dimensions dynamically |
| `ScoreHistory` | Timeline of versioned runs for one definition |
| `ProjectGraph` | `react-force-graph-2d` — nodes = projects, clustered by status |
| `SkillTree` | D3 tree layout; node colour = mastery status |
| `AgentChat` | SSE-backed streaming chat; reused across coach/PM/Eng Lead |
| `VoiceCapture` | MediaRecorder → ArrayBuffer → POST as `source=voice` |
| `ScoreDefinitionForm` | Add/edit definition: name, description, scorer_type, dimension list |

**New library:** `react-force-graph-2d` for the mind map. D3 used directly for SkillTree.

### API layer (`/src/api/projects.ts`, `/src/api/scoreDefinitions.ts`)

Functions follow existing pattern — each accepts `token?: string`, wraps `request<T>()`:
```ts
// projects.ts
getProjects(token?)                             GET /projects
getProject(id, token?)                          GET /projects/:id
createProject(body, token?)                     POST /projects
updateProject(id, body, token?)                 PUT /projects/:id
deleteProject(id, token?)                       DELETE /projects/:id
addNote(projectId, body, token?)                POST /projects/:id/notes  (text or audio blob)
triggerScore(projectId, body?, token?)          POST /projects/:id/scores
getScoreHistory(projectId, token?)              GET /projects/:id/scores/history
getConversation(projectId, agent, token?)       GET /projects/:id/conversations/:agent
generateSkills(projectId, token?)               POST /projects/:id/skills/generate
getSkillTree(projectId, token?)                 GET /projects/:id/skills
updateSkill(projectId, skillId, body, token?)   PUT /projects/:id/skills/:skill-id

// scoreDefinitions.ts
getScoreDefinitions(token?)
createScoreDefinition(body, token?)
updateScoreDefinition(id, body, token?)
deleteScoreDefinition(id, token?)
```

React Query hooks wrap these with appropriate `queryKey` hierarchies:
- `['projects']`, `['projects', id]`, `['projects', id, 'scores', 'history']`
- `['score-definitions']`, `['score-definitions', id]`

`useSendMessage(projectId, agent)` opens an `EventSource`, appends tokens to local state, and triggers `invalidateQueries(['projects', projectId, 'conversations', agent])` on close.

### Routes added to `App.tsx`
```ts
{ path: '/projects',                  element: <ProjectsPage /> },
{ path: '/projects/:id',              element: <ProjectDetailPage /> },
{ path: '/projects/:id/develop',      element: <ProjectDevelopPage /> },
{ path: '/projects/:id/skills',       element: <ProjectSkillsPage /> },
{ path: '/score-definitions',         element: <ScoreDefinitionsPage /> },
```

---

## Testing Plan

### Backend

| Layer | Scope | Approach |
|-------|-------|----------|
| Unit | Score aggregation math (version increment, overall = mean of dimensions) | Pure Clojure, no DB |
| Unit | `mock.clj` returns correct shape for any `score-definition` | Plain assertions |
| Contract | All three implementations satisfy `IScorer` protocol | `satisfies?` in a shared test ns |
| Integration | Projects CRUD, note creation, score trigger, skill tree | Ring mock + embedded H2, mock scorer injected via components |
| Integration | Score versioning: two consecutive triggers produce version 1 then 2 | Embedded H2 |
| Integration | Score history endpoint returns all runs in order | Embedded H2 |

LLM implementations (`llm_scorer` with PM/Eng Lead prompts) are covered by a manual smoke test against a fixed example project (separate test tag, requires real API key).

### Frontend

| Layer | Scope | Approach |
|-------|-------|----------|
| Unit | `ScoreRunCard` renders correct dimension names/values for arbitrary definition | Vitest + Testing Library |
| Unit | `SkillTree` renders nodes and edges for fixture tree data | Vitest + Testing Library |
| Unit | `VoiceCapture` calls `addNote` mutation with audio blob | Vitest + mock MediaRecorder |
| Integration | `ProjectsPage` — list, graph toggle, empty state | MSW + React Query `QueryClientProvider` wrapper |
| Integration | `AgentChat` — sends message, renders streamed tokens, persists on close | MSW streaming mock |
| Integration | `ScoreDefinitionsPage` — create, edit, delete; blocks delete on defaults | MSW |

---

## Build Sequence

1. DB migrations (all four)
2. Malli schemas + persistence layer
3. `IScorer` protocol + `mock.clj` + score aggregation
4. HTTP handlers + routes (backend testable end-to-end with mock scorer)
5. Default score definition seeding
6. `llm_scorer` with PM / Eng Lead personas
7. Projects list + detail + score display (dynamic, definition-driven)
8. Score definitions management UI
9. Voice capture + notes
10. Agent chat (streaming)
11. Graph view (mind map)
12. Skill tree generation + mastery UI
