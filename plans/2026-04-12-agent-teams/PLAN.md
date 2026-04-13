# Agent Teams — Implementation Plan

**Date:** 2026-04-12
**Scope:** Clojure backend + React/TypeScript frontend

---

## Context and Key Decisions

**Agent type vocabulary.** The existing codebase uses five string identifiers (`"coach"`, `"pm"`, `"engineering_lead"`, `"pm_agent"`, `"eng_agent"`). The feature surfaces three of them to users as the "agent team": `coach`, `pm`, `engineering_lead`. The `pm_agent` and `eng_agent` types are used only in conversations and scoring, not in workflow steps. Keep the vocabulary intact; `agent_definitions` rows use the same three team-facing keys.

**Seeding on first access.** Follow the pattern established by `api.workflows/seed-default-workflows!`: seed lazily on the first `GET /agents`.

**User-scoped definitions.** Agent definitions are rows keyed by `user_id` (email string), exactly like `score_definitions` and `workflows`.

**LLM executor user-id threading.** `LLMExecutor/execute-step!` receives `db`, `project`, `step-run`, and `output-stream`. Rather than change the protocol, read `(:user-id project)` — `persistence.projects/get-project` does `SELECT *` so `user_id` is always present in the result.

**Frontend snake\_case convention.** The `request()` wrapper converts outgoing body keys from kebab to snake (`display-name` → `display_name`), and incoming response keys are left as-is (server returns snake\_case). TypeScript interfaces use `snake_case` keys throughout. Follow this existing pattern.

---

## Backend Steps

### Step B1 — Migration: `agent_definitions` table + `agent_type` on `workflow_steps`

**File to create:** `resources/migrations/20260412000007-add-agent-definitions.up.sql`
**File to create:** `resources/migrations/20260412000007-add-agent-definitions.down.sql`

**Up:**

```sql
CREATE TABLE agent_definitions (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       TEXT NOT NULL,
  agent_type    TEXT NOT NULL,
  display_name  TEXT NOT NULL,
  avatar        TEXT NOT NULL,
  system_prompt TEXT NOT NULL,
  is_default    BOOLEAN NOT NULL DEFAULT false,
  created_at    TIMESTAMP DEFAULT now(),
  updated_at    TIMESTAMP DEFAULT now(),
  UNIQUE (user_id, agent_type)
);

--;;

ALTER TABLE workflow_steps
  ADD COLUMN agent_type TEXT NOT NULL DEFAULT 'coach';
```

**Down:**

```sql
ALTER TABLE workflow_steps DROP COLUMN agent_type;
--;;
DROP TABLE agent_definitions;
```

**Notes:**
- `agent_type` is constrained by application logic (not a DB CHECK), so the set of types can grow without a migration.
- `UNIQUE (user_id, agent_type)` — exactly one row per agent type per user; makes upsert-on-conflict safe.
- `workflow_steps.agent_type` defaults to `'coach'` — no backfill needed; existing rows read as `coach`, matching what was previously hardcoded in `create-workflow-run!`.

---

### Step B2 — Persistence layer: `src/kaleidoscope/persistence/agents.clj`

**File to create:** `src/kaleidoscope/persistence/agents.clj`

```clojure
(ns kaleidoscope.persistence.agents
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]))

(def ^:private get-agent-definitions-raw
  (rdbms/make-finder :agent-definitions))

(defn get-agent-definitions
  [db user-id]
  (get-agent-definitions-raw db {:user-id user-id}))

(defn get-agent-definition
  [db definition-id]
  (first (get-agent-definitions-raw db {:id definition-id})))

(defn create-agent-definition!
  [db {:keys [user-id agent-type display-name avatar system-prompt is-default]}]
  (let [now (utils/now)]
    (first (rdbms/insert! db
                          :agent-definitions
                          {:id            (utils/uuid)
                           :user-id       user-id
                           :agent-type    agent-type
                           :display-name  display-name
                           :avatar        avatar
                           :system-prompt system-prompt
                           :is-default    (boolean is-default)
                           :created-at    now
                           :updated-at    now}
                          :ex-subtype :UnableToCreateAgentDefinition))))

(defn update-agent-definition!
  [db definition-id {:keys [display-name avatar system-prompt]}]
  (let [now (utils/now)]
    (first (rdbms/update! db
                          :agent-definitions
                          (cond-> {:id         definition-id
                                   :updated-at now}
                            display-name  (assoc :display-name display-name)
                            avatar        (assoc :avatar avatar)
                            system-prompt (assoc :system-prompt system-prompt))))))
```

No `delete-agent-definition!` — the team is fixed at three types per user.

---

### Step B3 — API layer: `src/kaleidoscope/api/agents.clj`

**File to create:** `src/kaleidoscope/api/agents.clj`

```clojure
(ns kaleidoscope.api.agents
  (:require [honey.sql :as hsql]
            [kaleidoscope.persistence.agents :as persistence]
            [kaleidoscope.scoring.agents :as agents]
            [kaleidoscope.utils.core :as utils]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]
            [taoensso.timbre :as log]))

(def default-agent-definitions
  [{:agent-type    "coach"
    :display-name  "Project Coach"
    :avatar        "🐬"
    :system-prompt agents/coach-system-prompt
    :is-default    true}
   {:agent-type    "pm"
    :display-name  "Product Manager"
    :avatar        "🦊"
    :system-prompt agents/pm-agent-system-prompt
    :is-default    true}
   {:agent-type    "engineering_lead"
    :display-name  "Engineering Lead"
    :avatar        "🦉"
    :system-prompt agents/engineering-lead-agent-system-prompt
    :is-default    true}])

(defn seed-default-agent-definitions!
  "Idempotently seed the three pre-defined agent definitions for a user.
   Uses INSERT ... ON CONFLICT DO NOTHING for race safety."
  [db user-id]
  (doseq [defn default-agent-definitions]
    (next/execute! db
                   (hsql/format
                    {:insert-into :agent-definitions
                     :values      [(assoc defn
                                          :user-id    user-id
                                          :id         (utils/uuid)
                                          :created-at (utils/now)
                                          :updated-at (utils/now))]
                     :on-conflict [:user-id :agent-type]
                     :do-nothing  true})
                   {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-agent-definitions
  "Return all agent definitions for a user, seeding defaults on first access."
  [db user-id]
  (seed-default-agent-definitions! db user-id)
  (persistence/get-agent-definitions db user-id))

(defn update-agent-definition!
  "Update a single agent definition. Verifies ownership. Returns nil if not found."
  [db user-id definition-id updates]
  (when-let [defn (persistence/get-agent-definition db definition-id)]
    (when (= (:user-id defn) user-id)
      (persistence/update-agent-definition! db definition-id updates))))

(defn get-custom-system-prompt
  "Return the user's custom system prompt for agent-type, or nil if not customised.
   Does NOT seed defaults — called on every step execution, keep it fast."
  [db user-id agent-type]
  (when-let [defn (first (filter #(= (:agent-type %) agent-type)
                                 (persistence/get-agent-definitions db user-id)))]
    (:system-prompt defn)))
```

---

### Step B4 — HTTP routes: `src/kaleidoscope/http_api/agents.clj`

**File to create:** `src/kaleidoscope/http_api/agents.clj`

```clojure
(ns kaleidoscope.http-api.agents
  (:require [kaleidoscope.api.agents :as agents-api]
            [kaleidoscope.api.authentication :as oidc]
            [ring.util.http-response :refer [not-found ok]]))

(def reitit-agent-routes
  ["/agents"
   {:tags     ["agents"]
    :security [{:andrewslai-pkce ["roles" "profile"]}]}

   ["" {:get {:summary "List agent definitions for the authenticated user (seeds defaults on first access)"
              :handler (fn [{:keys [components] :as request}]
                         (let [user-id (oidc/get-verified-email (:identity request))]
                           (ok (agents-api/get-agent-definitions (:database components) user-id))))}}]

   ["/:definition-id"
    {:parameters {:path {:definition-id string?}}}

    ["" {:put {:summary "Update an agent's display-name, avatar, or system-prompt"
               :handler (fn [{:keys [components body-params path-params] :as request}]
                          (let [user-id       (oidc/get-verified-email (:identity request))
                                definition-id (parse-uuid (:definition-id path-params))]
                            (if-let [updated (agents-api/update-agent-definition!
                                              (:database components)
                                              user-id
                                              definition-id
                                              body-params)]
                              (ok updated)
                              (not-found {:reason "Agent definition not found"}))))}}]]])
```

Routes exposed:
- `GET /agents` — list (and seed) the user's three agent definitions
- `PUT /agents/:definition-id` — update `display_name`, `avatar`, and/or `system_prompt`

No POST (agent types are fixed) and no DELETE (team members can only be edited).

---

### Step B5 — Route registration

**File to modify:** `src/kaleidoscope/http_api/kaleidoscope.clj`

1. Add require:
   ```clojure
   [kaleidoscope.http-api.agents :refer [reitit-agent-routes]]
   ```

2. Add to the ACL vector alongside existing entries:
   ```clojure
   {:pattern #"^/agents.*" :handler auth/require-*-writer}
   ```

3. Add to the reitit router routes vector alongside `reitit-workflow-routes`:
   ```clojure
   reitit-agent-routes
   ```

---

### Step B6 — Update `workflow_steps` persistence to carry `agent_type`

**File to modify:** `src/kaleidoscope/persistence/workflows.clj`

In both `create-workflow!` and `update-workflow!`, add `:agent-type` to each step insert row:

```clojure
;; In the step-building fn passed to map-indexed:
{:id          (utils/uuid)
 :workflow-id (:id wf)          ;; or workflow-id depending on context
 :position    (or position i)
 :name        name
 :description description
 :agent-type  (or agent-type "coach")   ;; NEW — from the step input map
 :created-at  now
 :updated-at  now}
```

No change needed to the read path — `SELECT *` now returns the `agent_type` column automatically.

---

### Step B7 — Update `create-workflow-run!` to copy `agent_type` from `workflow_steps`

**File to modify:** `src/kaleidoscope/persistence/workflows.clj`, function `create-workflow-run!`

In the `map-indexed` call that builds step run rows (~line 203–214), change the hardcoded value:

```clojure
;; BEFORE
:agent-type "coach"

;; AFTER
:agent-type (or (:agent-type step) "coach")
```

The `step` map (from `SELECT * FROM workflow_steps`) now includes `agent_type`. The `or` guard is a belt-and-suspenders fallback.

---

### Step B8 — Update `LLMExecutor` to use custom agent definitions

**File to modify:** `src/kaleidoscope/workflows/llm_executor.clj`

**a) Add require:**

```clojure
[kaleidoscope.api.agents :as agents-api]
```

**b) Update `build-step-system-prompt`** to accept an optional custom prompt:

```clojure
(defn- build-step-system-prompt
  [step-run project custom-prompt]
  (let [base-prompt (or custom-prompt
                        (agents/get-system-prompt (:agent-type step-run)))
        project-ctx (format "\n\n---\nProject context:\nTitle: %s\nDescription: %s"
                            (:title project)
                            (or (:description project) "No description provided"))]
    (str base-prompt project-ctx)))
```

**c) Update `execute-step!`** to look up the custom prompt:

```clojure
(execute-step! [_this db project step-run output-stream]
  ...
  (try
    (let [user-id       (:user-id project)
          custom-prompt (agents-api/get-custom-system-prompt
                         db user-id (:agent-type step-run))
          system-prompt (build-step-system-prompt step-run project custom-prompt)
          ...]
```

**Fallback logic:** `get-custom-system-prompt` returns `nil` if the user has no rows in `agent_definitions` yet (i.e., they've never visited the Agent Team tab). The `or` in `build-step-system-prompt` then selects the built-in prompt — correct zero-configuration behaviour.

---

## Frontend Steps

### Step F1 — Types: `src/types/agents.ts`

**File to create:** `src/types/agents.ts`

```typescript
export type AgentType = 'coach' | 'pm' | 'engineering_lead';

export interface AgentDefinition {
  id: string;
  user_id: string;
  agent_type: AgentType;
  display_name: string;
  avatar: string;
  system_prompt: string;
  is_default: boolean;
  created_at: string;
  updated_at: string;
}

export interface UpdateAgentBody {
  display_name?: string;
  avatar?: string;
  system_prompt?: string;
}
```

If `AgentType` is already defined in `src/types/project.ts`, remove it from there and import it from `./agents`.

---

### Step F2 — API functions: `src/api/agents.ts`

**File to create:** `src/api/agents.ts`

```typescript
import { request } from './client';
import type { AgentDefinition, UpdateAgentBody } from '../types/agents';

export function getAgents(token?: string): Promise<AgentDefinition[]> {
  return request<AgentDefinition[]>('/agents', { token });
}

export function updateAgent(
  id: string,
  body: UpdateAgentBody,
  token?: string
): Promise<AgentDefinition> {
  return request<AgentDefinition>(`/agents/${id}`, {
    method: 'PUT',
    body,
    token,
  });
}
```

---

### Step F3 — AgentCard component: `src/components/agent-team/AgentCard.tsx`

**File to create:** `src/components/agent-team/AgentCard.tsx`

**Props:**

```typescript
interface AgentCardProps {
  agent: AgentDefinition;
  onEdit: (agent: AgentDefinition) => void;
}
```

**Structure (MUI):**

- `Box` with `border: 1, borderColor: 'divider', borderRadius: 2, p: 2`
- Avatar circle (64px, rounded): renders `agent.avatar` (emoji), bgcolor from a per-type color map:
  ```typescript
  const AGENT_COLORS: Record<string, string> = {
    coach:            '#0891b2',
    pm:               '#7c3aed',
    engineering_lead: '#0369a1',
  };
  ```
- `Typography` bold for `display_name`, caption muted for `agent_type`
- First ~120 chars of `system_prompt` as `Typography variant="body2" color="text.secondary"`
- Edit `IconButton` in the top-right corner calling `onEdit(agent)`

---

### Step F4 — AgentEditor component: `src/components/agent-team/AgentEditor.tsx`

**File to create:** `src/components/agent-team/AgentEditor.tsx`

**Props:**

```typescript
interface AgentEditorProps {
  open: boolean;
  agent: AgentDefinition | null;
  onClose: () => void;
  onSave: (id: string, updates: UpdateAgentBody) => Promise<void>;
  saving: boolean;
}
```

**Internal state:** `avatar`, `displayName`, `systemPrompt` — populated from `agent` in a `useEffect([agent])`.

**Structure:**

- `DialogTitle`: "Edit {agent.display_name}"
- `DialogContent`:
  - `TextField` label="Avatar (emoji or URL)"
  - Preview: small `Box` rendering the emoji
  - `TextField` label="Display Name"
  - `TextField` label="System Prompt" `multiline rows={8} fullWidth`
  - Helper text: "The project context is appended automatically."
- `DialogActions`: Cancel (calls `onClose`) + Save (calls `onSave`, disabled while `saving`, shows `CircularProgress` in `startIcon`)

---

### Step F5 — AgentTeamPanel: `src/components/agent-team/AgentTeamPanel.tsx`

**File to create:** `src/components/agent-team/AgentTeamPanel.tsx`

**Props:** `{ token: string | undefined }`

**Wiring:**

```typescript
const { data: agents = [], isLoading } = useQuery({
  queryKey: ['agents'],
  queryFn: () => getAgents(token),
});

const [editingAgent, setEditingAgent] = useState<AgentDefinition | null>(null);

const updateMutation = useMutation({
  mutationFn: ({ id, updates }: { id: string; updates: UpdateAgentBody }) =>
    updateAgent(id, updates, token),
  onSuccess: () => {
    void queryClient.invalidateQueries({ queryKey: ['agents'] });
    setEditingAgent(null);
  },
});
```

**Structure:**

- While loading: `CircularProgress`
- Header: `Typography variant="h6"` "Agent Team" + explainer
- `Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}` with one `AgentCard` per agent
- `AgentEditor` dialog wired to `editingAgent` state

The `queryKey: ['agents']` is global (user-scoped, not project-scoped).

---

### Step F6 — Tab in ProjectDetailPage

**File to modify:** `src/pages/projects/ProjectDetailPage.tsx` (or wherever the project detail tabs live)

**a) Add imports:**

```typescript
import GroupsIcon from '@mui/icons-material/Groups';
import { AgentTeamPanel } from '../../components/agent-team/AgentTeamPanel';
```

**b) Add tab** after the Workflow tab:

```tsx
<Tab label="Agent Team" icon={<GroupsIcon />} iconPosition="start" />
```

**c) Add TabPanel** for the new tab:

```tsx
<TabPanel value={tab} index={3}>
  <AgentTeamPanel token={token} />
</TabPanel>
```

---

### Step F7 — Agent selector on workflow steps

#### F7a — `src/api/workflows.ts`

Add `agent_type` to the existing `WorkflowStepInput` interface:

```typescript
export interface WorkflowStepInput {
  name: string;
  description: string;
  position: number;
  agent_type?: string;
}
```

#### F7b — `src/types/workflow.ts` (or wherever `WorkflowStep` is defined)

Add `agent_type` to the read-side type:

```typescript
export interface WorkflowStep {
  id: string;
  workflow_id: string;
  position: number;
  name: string;
  description: string;
  agent_type: string;   // NEW
}
```

#### F7c — `src/components/workflows/WorkflowStepList.tsx`

Add `agents?: AgentDefinition[]` to props. Inside each step card, add a `Select` below the step name input:

```tsx
const DEFAULT_AGENT_OPTIONS = [
  { agent_type: 'coach',            display_name: 'Project Coach',   avatar: '🐬' },
  { agent_type: 'pm',               display_name: 'Product Manager', avatar: '🦊' },
  { agent_type: 'engineering_lead', display_name: 'Engineering Lead', avatar: '🦉' },
];

// In the step card:
<FormControl size="small" sx={{ minWidth: 160 }}>
  <InputLabel>Agent</InputLabel>
  <Select
    value={step.agent_type ?? 'coach'}
    label="Agent"
    onChange={(e) => updateStep(index, 'agent_type', e.target.value)}
  >
    {(agents ?? DEFAULT_AGENT_OPTIONS).map((a) => (
      <MenuItem key={a.agent_type} value={a.agent_type}>
        {a.avatar} {a.display_name}
      </MenuItem>
    ))}
  </Select>
</FormControl>
```

`DEFAULT_AGENT_OPTIONS` is a fallback for when agents haven't loaded yet — the editor is never blocked.

#### F7d — `src/pages/WorkflowEditorPage.tsx`

**a) Fetch agents:**

```typescript
const { data: agents } = useQuery({
  queryKey: ['agents'],
  queryFn: () => getAgents(token),
});
```

**b) Populate `agent_type` when loading existing workflow steps:**

```typescript
setSteps(
  (workflow.steps ?? []).map((s) => ({
    name:        s.name,
    description: s.description,
    position:    s.position,
    agent_type:  s.agent_type ?? 'coach',  // NEW
  }))
);
```

**c) Pass agents to the step list:**

```tsx
<WorkflowStepList steps={steps} onChange={setSteps} agents={agents} />
```

The existing save handlers already pass `steps` to the API body, so `agent_type` is included automatically.

---

## Suggested Implementation Order

1. **B1** — migration (run first, all other backend steps depend on the schema)
2. **B2** — persistence layer (pure data, no dependencies)
3. **B3** — API layer (depends on B2)
4. **B4** — HTTP routes (depends on B3)
5. **B5** — route registration (depends on B4)
6. **B6** — workflow_steps persistence (depends on B1, independent of B2–B5)
7. **B7** — create-workflow-run! fix (depends on B6)
8. **B8** — LLM executor (depends on B3)
9. **F1** — TypeScript types
10. **F2** — API functions (depends on F1)
11. **F3** — AgentCard (depends on F1)
12. **F4** — AgentEditor (depends on F1, F3)
13. **F5** — AgentTeamPanel (depends on F2, F3, F4)
14. **F6** — ProjectDetailPage tab (depends on F5)
15. **F7** — workflow step agent selector (depends on F1, F2; can run in parallel with F3–F6)

---

## Critical Files

| File | Change type |
|------|-------------|
| `resources/migrations/20260412000007-add-agent-definitions.{up,down}.sql` | New |
| `src/kaleidoscope/persistence/agents.clj` | New |
| `src/kaleidoscope/api/agents.clj` | New |
| `src/kaleidoscope/http_api/agents.clj` | New |
| `src/kaleidoscope/http_api/kaleidoscope.clj` | Modify (register routes + ACL) |
| `src/kaleidoscope/persistence/workflows.clj` | Modify (`create-workflow!`, `update-workflow!`, `create-workflow-run!`) |
| `src/kaleidoscope/workflows/llm_executor.clj` | Modify (`build-step-system-prompt`, `execute-step!`) |
| `kaleidoscope-ui/src/types/agents.ts` | New |
| `kaleidoscope-ui/src/api/agents.ts` | New |
| `kaleidoscope-ui/src/components/agent-team/AgentCard.tsx` | New |
| `kaleidoscope-ui/src/components/agent-team/AgentEditor.tsx` | New |
| `kaleidoscope-ui/src/components/agent-team/AgentTeamPanel.tsx` | New |
| `kaleidoscope-ui/src/pages/projects/ProjectDetailPage.tsx` | Modify (add tab) |
| `kaleidoscope-ui/src/components/workflows/WorkflowStepList.tsx` | Modify (add agent Select) |
| `kaleidoscope-ui/src/pages/WorkflowEditorPage.tsx` | Modify (fetch agents, pass to step list) |
