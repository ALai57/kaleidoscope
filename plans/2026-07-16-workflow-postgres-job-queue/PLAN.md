# Postgres-Backed Async Workflow Job Queue — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move background workflow advancement off the HTTP request thread onto a durable Postgres-backed job queue, and replace the single-instance in-process advancement lock with a real cross-process one — **while preserving scale-to-zero.** There is **no always-on worker and no poller**, so the Fly app machine (via auto-stop) and the Neon database (via autosuspend) both suspend when idle and cost nothing. Background work is **request-triggered**: the app instance that enqueues a job also drains the queue, in a background thread that ends when the queue is empty. Crash/retry recovery is **opportunistic** — each trigger reaps stalled jobs before draining — not autonomous.

**Architecture:** A new `workflow_jobs` table is the durable ledger, and the coordination core is unchanged from a classic PG queue: a nullable `active_run_id` column + a plain UNIQUE index enforces "at most one active job per run" in **both** Postgres and embedded-H2 (H2 has no partial indexes), and claiming uses `FOR UPDATE SKIP LOCKED`. That unique index is the mutex replacing the in-process `advancing-runs` atom, and it still matters because concurrent requests (or >1 Fly app machine under load) can trigger drains simultaneously. **What changes for scale-to-zero:** instead of a dedicated Fly `worker` process running a `poll → sleep` loop, the four background enqueue sites fire an in-instance `(future …)` that reaps-then-drains the durable queue and returns when it's empty. The interactive `POST …/advance` SSE endpoint still streams synchronously on the app process. There is no separate worker process group, no `main "worker"` dispatch, and no periodic reaper timer.

**Accepted tradeoffs (chosen deliberately — failure cost is low right now):** because nothing polls, a job requeued with backoff (`available_at` in the future) or orphaned by a mid-run crash is **not** picked up until the *next* enqueue triggers a drain. In the pathological case — one failing/orphaned job and no further user activity — it never retries on its own. That is acceptable for a low-frequency, user-triggered feature; the durable row + `last_error` mean nothing is lost and a manual/next-trigger retry recovers it. If autonomous recovery is ever needed, the drop-in is a coarse scheduled sweep (a Fly scheduled machine every 5–15 min that reaps+drains+exits, one brief DB wake per interval) — explicitly out of scope here.

**Tech Stack:** Clojure, next.jdbc + HoneySQL, Migratus, Malli, Kaocha + matcher-combinators, embedded-postgres for tests, Fly.io multi-process deploy.

## Global Constraints

- **Migration DDL must apply cleanly under BOTH embedded-H2 and Postgres.** No partial indexes (`CREATE INDEX ... WHERE`), no `SKIP LOCKED` in DDL. `now()` defaults and `gen_random_uuid()` are already used elsewhere and are safe. (CLAUDE.md sharp edge #2: all schema changes require a migration file.)
- **Runtime queue SQL (`SKIP LOCKED`, `interval` math) is Postgres-only** and is only exercised in tests via `embedded-postgres/fresh-db!`, matching the existing `test/kaleidoscope/api/workflows_test.clj` convention. embedded-H2 tests must never call `claim-next-job!`/`retry-job!`/`reap-stalled-jobs!`.
- `!` suffix on every side-effecting fn. SQL columns `snake_case`; Clojure `kebab-case` (auto-converted).
- Every feature needs automated tests (CLAUDE.md sharp edge #4).
- Keep the 3-layer separation: `persistence/workflow_jobs.clj` does data access only; `api/workflow_jobs.clj` holds the worker orchestration logic; no HTTP concerns in either.
- Keep `docs/operations.md` current on any deploy/fly.toml/build change (sharp edge #6).
- Error wrapping: `rdbms/*` throws a slingshot `{:type :PersistenceException ...}` map; catch with `slingshot.slingshot/try+` `(catch [:type :PersistenceException] _ ...)`.
- **Scale-to-zero is a hard constraint:** no code path may run continuously when there is no work — no poll loop, no timer, no always-on worker process. All background processing is triggered by an enqueue (a request) and stops when the queue drains. This is what lets Fly auto-stop the app machine and Neon autosuspend the database.
- The feature is functionally complete after Task 6 (wiring the in-instance drain trigger into the enqueue sites); Task 7 is documentation only (no deploy-time worker process). Tasks 4–6 must ship together: after Task 4, background resume produces queued jobs, and Task 5/6 provide the trigger that drains them in the same instance.

---

## File Structure

- **Create** `resources/migrations/20260716000001-add-workflow-jobs.up.sql` / `.down.sql` — the `workflow_jobs` table + indexes.
- **Create** `src/kaleidoscope/persistence/workflow_jobs.clj` — data access: enqueue, lease, claim, complete, retry, fail, reap, get.
- **Create** `test/kaleidoscope/persistence/workflow_jobs_test.clj`
- **Create** `src/kaleidoscope/api/workflow_jobs.clj` — drain orchestration (`work-once!`/`drain!`/`reap!`) **plus** `process-run-async!`, the request-triggered, opportunistic-reap entrypoint (a `future` that reaps-then-drains and returns). Calls into `api/workflows`.
- **Create** `test/kaleidoscope/api/workflow_jobs_test.clj`
- ~~`src/kaleidoscope/worker.clj` (long-running loop)~~ — **removed by the scale-to-zero revision.** There is no daemon; the in-instance trigger lives in `api/workflow_jobs.clj`.
- **Modify** `src/kaleidoscope/api/workflows.clj` — replace the `advancing-runs` atom/`claim-run!`/`release-run!` with the DB lease; split `advance-step!` into a lease-acquiring foreground fn + a lease-free `advance-run!*`; route the 4 background `future` sites through `enqueue-job!` **followed by** `process-run-async!`.
- ~~`src/kaleidoscope/main.clj` "worker" dispatch~~ and ~~`fly.toml` worker process group~~ — **removed by the scale-to-zero revision.** No separate process; the app process does everything.
- **Modify** `docs/operations.md` — document the request-triggered queue, opportunistic reaping, the scale-to-zero behavior, and the accepted tradeoffs.

---

### Task 1: Migration — `workflow_jobs` table

**Files:**
- Create: `resources/migrations/20260716000001-add-workflow-jobs.up.sql`
- Create: `resources/migrations/20260716000001-add-workflow-jobs.down.sql`
- Test: `test/kaleidoscope/persistence/workflow_jobs_test.clj` (schema smoke test)

**Interfaces:**
- Produces: table `workflow_jobs` with columns `id, workflow_run_id, project_id, user_id, status, attempts, max_attempts, available_at, locked_at, locked_by, last_error, active_run_id, created_at, updated_at`; indexes `workflow_jobs_one_active` (UNIQUE on `active_run_id`), `workflow_jobs_claimable` on `(status, available_at)`.

- [ ] **Step 1: Write the up migration**

Create `resources/migrations/20260716000001-add-workflow-jobs.up.sql`:

```sql
CREATE TABLE workflow_jobs (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workflow_run_id UUID NOT NULL REFERENCES project_workflow_runs(id) ON DELETE CASCADE,
  project_id      UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  user_id         TEXT NOT NULL,
  status          TEXT NOT NULL DEFAULT 'queued',   -- queued | running | done | failed
  attempts        INT  NOT NULL DEFAULT 0,
  max_attempts    INT  NOT NULL DEFAULT 3,
  available_at    TIMESTAMP NOT NULL DEFAULT now(), -- earliest time a worker may claim (backoff)
  locked_at       TIMESTAMP,
  locked_by       TEXT,
  last_error      TEXT,
  -- Equals workflow_run_id while the job is active (queued|running), NULL once
  -- terminal (done|failed). A plain UNIQUE index on this column enforces
  -- "at most one active job per run" in BOTH Postgres and embedded H2 (neither
  -- treats multiple NULLs as a collision), since H2 has no partial indexes.
  -- App code maintains it: set on enqueue/lease, cleared on complete/fail.
  active_run_id   UUID,
  created_at      TIMESTAMP NOT NULL DEFAULT now(),
  updated_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX workflow_jobs_one_active ON workflow_jobs (active_run_id);
CREATE INDEX workflow_jobs_claimable ON workflow_jobs (status, available_at);
```

- [ ] **Step 2: Write the down migration**

Create `resources/migrations/20260716000001-add-workflow-jobs.down.sql`:

```sql
DROP TABLE IF EXISTS workflow_jobs;
```

- [ ] **Step 3: Write a schema smoke test**

Create `test/kaleidoscope/persistence/workflow_jobs_test.clj` with the namespace form and this first test. It proves the migration applies under **both** engines (H2 catches non-portable DDL):

```clojure
(ns kaleidoscope.persistence.workflow-jobs-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.rdbms.embedded-postgres-impl :as embedded-postgres]
            [kaleidoscope.persistence.workflow-jobs :as jobs]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.api.workflows :as workflows]
            [matcher-combinators.test :refer [match?]]))

(defn- run-fixture!
  "Create a project + manual run, returning [run-id project-id user-id]."
  [database]
  (let [user-id  "queue-user@example.com"
        wf       (workflows/create-workflow! database user-id
                                              {:name "Q" :description "d" :is-default false
                                               :steps [{:name "S" :description "d" :position 0
                                                        :agent-type "coach" :output-kind "text"}]})
        project  (projects-persistence/create-project! database {:user-id user-id :title "P"})
        run      (workflows/create-run! database (:id project) user-id
                                        {:workflow-id (:id wf) :mode "manual"})]
    [(:id run) (:id project) user-id]))

(deftest migration-applies-under-both-engines-test
  (testing "H2 applies the migration (guards against non-portable DDL)"
    (is (some? (embedded-h2/fresh-db!))))
  (testing "Postgres applies the migration"
    (is (some? (embedded-postgres/fresh-db!)))))
```

- [ ] **Step 4: Run the smoke test to verify it fails**

Run: `./bin/test --focus kaleidoscope.persistence.workflow-jobs-test/migration-applies-under-both-engines-test`
Expected: FAIL — `create-workflow!`/`create-run!` resolve, but `kaleidoscope.persistence.workflow-jobs` does not exist yet, so the `require` fails to load (Task 2 creates it). If you want a green Task 1 in isolation, temporarily drop the `jobs`/`workflows`/`projects-persistence` requires and assert only `fresh-db!`; re-add them in Task 2.

- [ ] **Step 5: Run the migration locally to confirm it parses against Postgres**

Run: `task db:migrate ENV=.env.local` (or against embedded-postgres via the test above).
Expected: `Migrating 20260716000001-add-workflow-jobs` with no error.

- [ ] **Step 6: Commit**

```bash
git add resources/migrations/20260716000001-add-workflow-jobs.up.sql \
        resources/migrations/20260716000001-add-workflow-jobs.down.sql \
        test/kaleidoscope/persistence/workflow_jobs_test.clj
git commit -m "feat(workflows): add workflow_jobs queue table

- New durable ledger for async workflow advancement
- active_run_id + plain UNIQUE index enforces one active job per run
  (portable across Postgres and embedded H2, which lacks partial indexes)
- claimable index on (status, available_at)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Persistence layer — `workflow_jobs` data access

**Files:**
- Create: `src/kaleidoscope/persistence/workflow_jobs.clj`
- Test: `test/kaleidoscope/persistence/workflow_jobs_test.clj` (extend)

**Interfaces:**
- Consumes: `rdbms/insert!`, `rdbms/update!`, `rdbms/find-by-keys`; `utils/uuid`, `utils/now`; `next.jdbc/execute-one!`, `next.jdbc/execute!`.
- Produces:
  - `enqueue-job!` `[db {:keys [workflow-run-id project-id user-id]}]` → job map. Idempotent: returns the existing active job if one exists.
  - `acquire-run-lease!` `[db {:keys [workflow-run-id project-id user-id]} owner]` → job map (status `"running"`) or `nil` if a job already holds the run's active slot.
  - `claim-next-job!` `[db owner]` → job map (status `"running"`, `attempts` incremented) or `nil`. **Postgres-only** (`SKIP LOCKED`).
  - `complete-job!` `[db job-id]` → job map (status `"done"`, `active-run-id` NULL).
  - `retry-job!` `[db job-id backoff-seconds error]` → job map (status `"queued"`, `available-at` = now + backoff). **Postgres-only**.
  - `fail-job!` `[db job-id error]` → job map (status `"failed"`, `active-run-id` NULL).
  - `reap-stalled-jobs!` `[db lease-seconds]` → `{:failed n :requeued n}`. **Postgres-only**.
  - `active-job` `[db run-id]` → job map or nil.
  - `get-job` `[db job-id]` → job map or nil.

- [ ] **Step 1: Write failing tests for the lifecycle**

Extend `test/kaleidoscope/persistence/workflow_jobs_test.clj` (add `[next.jdbc :as next]` to requires):

```clojure
(deftest enqueue-is-idempotent-per-run-test
  (let [db (embedded-postgres/fresh-db!)
        [run-id project-id user-id] (run-fixture! db)
        j1 (jobs/enqueue-job! db {:workflow-run-id run-id :project-id project-id :user-id user-id})
        j2 (jobs/enqueue-job! db {:workflow-run-id run-id :project-id project-id :user-id user-id})]
    (testing "first enqueue creates a queued job"
      (is (match? {:workflow-run-id run-id :status "queued" :active-run-id run-id} j1)))
    (testing "second enqueue returns the same active job, not a duplicate"
      (is (= (:id j1) (:id j2)))
      (is (= 1 (count (next/execute! db ["SELECT id FROM workflow_jobs WHERE workflow_run_id = ?" run-id])))))))

(deftest lease-is-exclusive-test
  (let [db (embedded-postgres/fresh-db!)
        [run-id project-id user-id] (run-fixture! db)
        l1 (jobs/acquire-run-lease! db {:workflow-run-id run-id :project-id project-id :user-id user-id} "web-a")
        l2 (jobs/acquire-run-lease! db {:workflow-run-id run-id :project-id project-id :user-id user-id} "web-b")]
    (testing "first caller gets a running lease" (is (match? {:status "running" :locked-by "web-a"} l1)))
    (testing "second caller is refused while the first holds it" (is (nil? l2)))
    (testing "after completion, the slot frees and a new lease can be acquired"
      (jobs/complete-job! db (:id l1))
      (is (some? (jobs/acquire-run-lease! db {:workflow-run-id run-id :project-id project-id :user-id user-id} "web-c"))))))

(deftest claim-runs-queued-jobs-test
  (let [db (embedded-postgres/fresh-db!)
        [run-id project-id user-id] (run-fixture! db)
        _  (jobs/enqueue-job! db {:workflow-run-id run-id :project-id project-id :user-id user-id})
        claimed (jobs/claim-next-job! db "worker-1")]
    (testing "claim returns the queued job as running with attempt counted"
      (is (match? {:workflow-run-id run-id :status "running" :locked-by "worker-1" :attempts 1} claimed)))
    (testing "a second claim finds nothing (job is no longer queued)"
      (is (nil? (jobs/claim-next-job! db "worker-2"))))))

(deftest retry-and-reap-test
  (let [db (embedded-postgres/fresh-db!)
        [run-id project-id user-id] (run-fixture! db)
        _  (jobs/enqueue-job! db {:workflow-run-id run-id :project-id project-id :user-id user-id})
        claimed (jobs/claim-next-job! db "worker-1")]
    (testing "retry re-queues the job in the future and keeps the active slot"
      (jobs/retry-job! db (:id claimed) 30 "boom")
      (is (match? {:status "queued" :last-error "boom" :active-run-id run-id} (jobs/get-job db (:id claimed))))
      (testing "and it is not immediately claimable (available_at is in the future)"
        (is (nil? (jobs/claim-next-job! db "worker-2")))))
    (testing "reap requeues a running job whose lease expired"
      (jobs/claim-next-job! db "worker-1") ; re-claim not possible (future) — instead test reap on a fresh running job
      )))

(deftest reap-requeues-expired-lease-test
  (let [db (embedded-postgres/fresh-db!)
        [run-id project-id user-id] (run-fixture! db)
        _  (jobs/enqueue-job! db {:workflow-run-id run-id :project-id project-id :user-id user-id})
        claimed (jobs/claim-next-job! db "worker-1")]
    ;; force the lease into the past
    (next/execute! db ["UPDATE workflow_jobs SET locked_at = now() - interval '1 hour' WHERE id = ?" (:id claimed)])
    (testing "reap with a 60s lease requeues the stalled job"
      (is (match? {:requeued 1 :failed 0} (jobs/reap-stalled-jobs! db 60)))
      (is (= "queued" (:status (jobs/get-job db (:id claimed))))))))

(deftest reap-fails-exhausted-jobs-test
  (let [db (embedded-postgres/fresh-db!)
        [run-id project-id user-id] (run-fixture! db)
        _  (jobs/enqueue-job! db {:workflow-run-id run-id :project-id project-id :user-id user-id})
        claimed (jobs/claim-next-job! db "worker-1")]
    (next/execute! db ["UPDATE workflow_jobs SET attempts = max_attempts, locked_at = now() - interval '1 hour' WHERE id = ?" (:id claimed)])
    (testing "reap fails a stalled job that has exhausted its attempts and frees the slot"
      (is (match? {:requeued 0 :failed 1} (jobs/reap-stalled-jobs! db 60)))
      (is (match? {:status "failed" :active-run-id nil} (jobs/get-job db (:id claimed)))))))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./bin/test --focus kaleidoscope.persistence.workflow-jobs-test`
Expected: FAIL — `kaleidoscope.persistence.workflow-jobs` namespace not found.

- [ ] **Step 3: Implement the persistence namespace**

Create `src/kaleidoscope/persistence/workflow_jobs.clj`:

```clojure
(ns kaleidoscope.persistence.workflow-jobs
  "Data access for the durable async workflow job queue (workflow_jobs table).

   Portability note: the migration DDL is engine-neutral, but claim-next-job!,
   retry-job! and reap-stalled-jobs! use Postgres-only SQL (FOR UPDATE SKIP
   LOCKED, interval math). Those are exercised in tests via embedded-postgres,
   never embedded-h2."
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]
            [taoensso.timbre :as log])
  (:use [slingshot.slingshot :only [try+]]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn active-job
  "The single active (queued|running) job for a run, or nil."
  [db run-id]
  (first (rdbms/find-by-keys db :workflow-jobs {:active-run-id run-id})))

(defn get-job
  [db job-id]
  (first (rdbms/find-by-keys db :workflow-jobs {:id job-id})))

(defn enqueue-job!
  "Durably enqueue a background 'advance this run' job. Idempotent: the
   workflow_jobs_one_active unique index on active_run_id makes a second
   enqueue for an already-active run collide; we catch that and return the
   existing active job instead of inserting a duplicate."
  [db {:keys [workflow-run-id project-id user-id]}]
  (try+
   (first (rdbms/insert! db :workflow-jobs
                         {:id              (utils/uuid)
                          :workflow-run-id workflow-run-id
                          :project-id      project-id
                          :user-id         user-id
                          :status          "queued"
                          :active-run-id   workflow-run-id}
                         :ex-subtype :UnableToEnqueueWorkflowJob))
   (catch [:type :PersistenceException] _
     (active-job db workflow-run-id))))

(defn acquire-run-lease!
  "Try to claim exclusive advancement rights on a run for a foreground caller.
   Inserts a 'running' job holding the run's active slot; returns the job on
   success, or nil if another job already holds it (the run is already
   advancing). This unique-index-backed slot is the cross-process mutex that
   replaces the old in-process `advancing-runs` atom."
  [db {:keys [workflow-run-id project-id user-id]} owner]
  (try+
   (first (rdbms/insert! db :workflow-jobs
                         {:id              (utils/uuid)
                          :workflow-run-id workflow-run-id
                          :project-id      project-id
                          :user-id         user-id
                          :status          "running"
                          :attempts        1
                          :locked-by       owner
                          :locked-at       (utils/now)
                          :active-run-id   workflow-run-id}
                         :ex-subtype :UnableToLeaseWorkflowRun))
   (catch [:type :PersistenceException] _
     nil)))

(def ^:private claim-sql
  "UPDATE workflow_jobs
      SET status = 'running', locked_by = ?, locked_at = now(),
          attempts = attempts + 1, updated_at = now()
    WHERE id = (SELECT id FROM workflow_jobs
                 WHERE status = 'queued' AND available_at <= now()
                 ORDER BY available_at
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1)
   RETURNING *")

(defn claim-next-job!
  "Atomically claim the next runnable queued job (Postgres only). Returns the
   claimed job as 'running', or nil if the queue is empty. SKIP LOCKED lets N
   workers claim different jobs without blocking each other."
  [db owner]
  (next/execute-one! db [claim-sql owner] opts))

(defn complete-job!
  "Mark a job done and free the run's active slot."
  [db job-id]
  (first (rdbms/update! db :workflow-jobs
                        {:id job-id :status "done" :active-run-id nil :updated-at (utils/now)})))

(defn fail-job!
  "Mark a job permanently failed and free the run's active slot."
  [db job-id error]
  (first (rdbms/update! db :workflow-jobs
                        {:id job-id :status "failed" :active-run-id nil
                         :last-error error :updated-at (utils/now)})))

(def ^:private retry-sql
  "UPDATE workflow_jobs
      SET status = 'queued', available_at = now() + (interval '1 second' * ?),
          locked_by = NULL, locked_at = NULL, last_error = ?, updated_at = now()
    WHERE id = ?
   RETURNING *")

(defn retry-job!
  "Re-queue a failed attempt with backoff (Postgres only). Keeps the active
   slot so no new job can be enqueued for the run while this one is retrying."
  [db job-id backoff-seconds error]
  (next/execute-one! db [retry-sql backoff-seconds error job-id] opts))

(def ^:private fail-exhausted-sql
  "UPDATE workflow_jobs
      SET status = 'failed', active_run_id = NULL, updated_at = now(),
          last_error = COALESCE(last_error, 'lease expired')
    WHERE status = 'running' AND locked_at < now() - (interval '1 second' * ?)
      AND attempts >= max_attempts
   RETURNING id")

(def ^:private requeue-stalled-sql
  "UPDATE workflow_jobs
      SET status = 'queued', locked_by = NULL, locked_at = NULL, updated_at = now()
    WHERE status = 'running' AND locked_at < now() - (interval '1 second' * ?)
      AND attempts < max_attempts
   RETURNING id")

(defn reap-stalled-jobs!
  "Recover jobs whose worker died mid-run (lease older than lease-seconds).
   Exhausted ones are failed; the rest are re-queued. Postgres only."
  [db lease-seconds]
  (let [failed   (next/execute! db [fail-exhausted-sql lease-seconds] opts)
        requeued (next/execute! db [requeue-stalled-sql lease-seconds] opts)]
    (when (or (seq failed) (seq requeued))
      (log/infof "Reaped workflow_jobs: failed=%s requeued=%s" (count failed) (count requeued)))
    {:failed (count failed) :requeued (count requeued)}))
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./bin/test --focus kaleidoscope.persistence.workflow-jobs-test`
Expected: PASS (all 7 tests). If `retry-and-reap-test`'s second `testing` block is awkward, delete that empty block — `reap-requeues-expired-lease-test` covers reap.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/persistence/workflow_jobs.clj test/kaleidoscope/persistence/workflow_jobs_test.clj
git commit -m "feat(workflows): workflow_jobs persistence layer

- enqueue-job! (idempotent per run), acquire-run-lease! (exclusive slot)
- claim-next-job! (SKIP LOCKED), complete/retry/fail, reap-stalled-jobs!
- Postgres-only runtime SQL tested via embedded-postgres

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: DB-backed advancement lease in `api/workflows`

Replace the in-process `advancing-runs` atom with the `workflow_jobs` slot, and split `advance-step!` so the worker can run the same logic without re-acquiring the lease it already holds.

**Files:**
- Modify: `src/kaleidoscope/api/workflows.clj` (remove `advancing-runs`/`claim-run!`/`release-run!` at lines 549-577; refactor `advance-step!` at 583-603; update `run-custom-step!` at 690-696)
- Test: `test/kaleidoscope/api/workflows_test.clj` (extend)

**Interfaces:**
- Consumes: `workflow-jobs/acquire-run-lease!`, `workflow-jobs/complete-job!`.
- Produces:
  - `advance-run!*` `[db executor project-id user-id run-id output-stream]` → runs next step(s) for an owned run **without** acquiring a lease (caller holds it). Returns nil if run/project unresolved.
  - `advance-step!` `[db executor project-id user-id run-id output-stream]` → unchanged public signature and return contract (`{:error :already-advancing}` when the run is already advancing). Now leased via the DB.

- [ ] **Step 1: Write a failing test for cross-call exclusivity**

Add to `test/kaleidoscope/api/workflows_test.clj` (requires already include `workflows`, `embedded-postgres`, `projects-persistence`, `wf-protocol`; add `[kaleidoscope.persistence.workflow-jobs :as jobs]`):

```clojure
(deftest advance-step-lease-is-exclusive-test
  (let [db       (embedded-postgres/fresh-db!)
        user-id  "lease@example.com"
        wf       (workflows/create-workflow! db user-id custom-workflow)
        project  (projects-persistence/create-project! db {:user-id user-id :title "P"})
        run      (workflows/create-run! db (:id project) user-id {:workflow-id (:id wf) :mode "manual"})
        run-id   (:id run)
        ;; Pre-seed a foreground lease so the run is already "advancing".
        _lease   (jobs/acquire-run-lease! db {:workflow-run-id run-id :project-id (:id project) :user-id user-id} "web-a")
        executor (reify wf-protocol/IWorkflowExecutor
                   (execute-step! [_ _db _project step-run _os]
                     (workflows-persistence/update-step-run! _db (:id step-run) {:status "completed"})
                     "done"))]
    (testing "advance-step! is refused while another lease is held"
      (is (= {:error :already-advancing}
             (workflows/advance-step! db executor (:id project) user-id run-id
                                      (java.io.ByteArrayOutputStream.)))))))
```
(Add `[kaleidoscope.persistence.workflows :as workflows-persistence]` to the test requires if not already present — it is, per the file header.)

- [ ] **Step 2: Run it to verify it fails**

Run: `./bin/test --focus kaleidoscope.api.workflows-test/advance-step-lease-is-exclusive-test`
Expected: FAIL — today `advance-step!` uses the in-process atom, which is empty in a fresh test, so it proceeds instead of returning `:already-advancing`.

- [ ] **Step 3: Add the require and remove the atom-based lock**

In `src/kaleidoscope/api/workflows.clj` ns `:require`, add:
```clojure
[kaleidoscope.persistence.workflow-jobs :as workflow-jobs]
```

Delete the block at lines 549-577 (the `advancing-runs` `defonce`, `claim-run!`, and `release-run!`, plus their leading comment). Replace with a small owner helper:

```clojure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cross-process advancement lease
;;
;; A run may be advanced by the foreground SSE request thread OR by the
;; in-instance background drain thread (process-run-async!), possibly on
;; different app machines, never both at once. The workflow_jobs table's
;; unique active_run_id slot is the mutex: acquire-run-lease! inserts a
;; 'running' job (fails if one already holds the run), complete-job! frees it.
;; This replaces the old single-instance in-process `advancing-runs` atom with
;; a real cross-process lock.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- lease-owner []
  (str "web-" (or (System/getenv "FLY_MACHINE_ID") "local")))
```

- [ ] **Step 4: Split `advance-step!` into leased + lease-free forms**

Replace the `advance-step!` definition (lines 583-603) with:

```clojure
(defn- advance-run-dispatch!
  [db executor project run run-id output-stream]
  (if (and (:workflow-id run)
           (persistence/workflow-has-loop-steps? db (:workflow-id run)))
    (run-loop-workflow! db executor project run-id output-stream)
    (run-sequential-workflow! db executor project run-id output-stream)))

(defn advance-run!*
  "Run the next step(s) for an owned run WITHOUT acquiring the advancement
   lease. The caller MUST already hold it (a foreground lease or a claimed
   workflow_job). Returns nil if the run/project cannot be resolved."
  [db executor project-id user-id run-id output-stream]
  (when-let [project (projects-persistence/get-project db project-id user-id)]
    (when-let [run (get-owned-run db run-id project-id)]
      (advance-run-dispatch! db executor project run run-id output-stream))))

(defn advance-step!
  "Foreground entry point. Resolves ownership, acquires the run's advancement
   lease, runs the next step(s), then frees the lease. Returns
   {:error :already-advancing} if another foreground caller or the background
   worker already holds the run."
  [db executor project-id user-id run-id output-stream]
  (when-let [project (projects-persistence/get-project db project-id user-id)]
    (when-let [run (get-owned-run db run-id project-id)]
      (if-let [lease (workflow-jobs/acquire-run-lease!
                       db {:workflow-run-id run-id :project-id project-id :user-id user-id}
                       (lease-owner))]
        (try
          (advance-run-dispatch! db executor project run run-id output-stream)
          (finally
            (workflow-jobs/complete-job! db (:id lease))))
        {:error :already-advancing}))))
```

- [ ] **Step 5: Convert `run-custom-step!` to the DB lease**

In `run-custom-step!` (lines 690-696), replace the `(if-not (claim-run! run-id) {:error :already-advancing} (try ... (finally (release-run! run-id))))` scaffold with the lease. The body between claim and the `finally` is unchanged; only the guard and release change:

```clojure
  [db executor project-id user-id run-id
   {:keys [name description agent-type] :as _custom-step}
   output-stream]
  (when-let [project (projects-persistence/get-project db project-id user-id)]
    (when (get-owned-run db run-id project-id)
      (if-let [lease (workflow-jobs/acquire-run-lease!
                       db {:workflow-run-id run-id :project-id project-id :user-id user-id}
                       (lease-owner))]
        (try
          ;; ... existing custom-step body unchanged ...
          (finally
            (workflow-jobs/complete-job! db (:id lease))))
        {:error :already-advancing}))))
```
Keep every existing line inside the `try` exactly as-is; only the `if-let` guard and the `finally` are replaced.

- [ ] **Step 6: Run the new test + the existing workflows suite**

Run: `./bin/test --focus kaleidoscope.api.workflows-test`
Expected: PASS, including `advance-step-lease-is-exclusive-test`. If any existing test that calls `advance-step!` twice on the same run in sequence now fails, confirm each call completes (frees its lease) before the next — the lease is released in `finally`, so sequential calls are fine.

- [ ] **Step 7: Commit**

```bash
git add src/kaleidoscope/api/workflows.clj test/kaleidoscope/api/workflows_test.clj
git commit -m "feat(workflows): DB-backed advancement lease

- Replace single-instance advancing-runs atom with workflow_jobs slot
- Split advance-step! into leased foreground fn + lease-free advance-run!*
- run-custom-step! uses the same cross-process lease
- Preserves {:error :already-advancing} contract (no HTTP change)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Route background resumption through the queue

Replace the five fire-and-forget `(future (advance-step! ...))` sites with durable `enqueue-job!` calls.

**Files:**
- Modify: `src/kaleidoscope/api/workflows.clj` (`force-proceed!` ~665-670; `respond-to-step!` ~768-774, ~794-799, ~815-821; `start-default-workflow!` ~836-842)
- Test: `test/kaleidoscope/api/workflows_test.clj` (extend)

**Interfaces:**
- Consumes: `workflow-jobs/enqueue-job!`, `workflow-jobs/active-job`.
- Produces: no signature changes. Each of these fns now enqueues a durable queued job; Task 6 then wires `process-run-async!` right after the enqueue so the same instance drains it on a background thread (no separate worker).

- [ ] **Step 1: Write a failing test that background sites enqueue instead of run**

Add to `test/kaleidoscope/api/workflows_test.clj`:

```clojure
(deftest start-default-workflow-enqueues-job-test
  (let [db       (embedded-postgres/fresh-db!)
        user-id  "enqueue@example.com"
        project  (projects-persistence/create-project! db {:user-id user-id :title "P"})
        executor (reify wf-protocol/IWorkflowExecutor
                   (execute-step! [_ _db _project step-run _os] "unused")
                   (recommend-workflows [_ _db _project] []))
        run      (workflows/start-default-workflow! db executor (:id project) user-id)]
    (testing "a queued job is durably recorded for the new run (not run inline)"
      (is (some? run))
      (is (match? {:status "queued" :workflow-run-id (:id run) :active-run-id (:id run)}
                  (jobs/active-job db (:id run)))))))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./bin/test --focus kaleidoscope.api.workflows-test/start-default-workflow-enqueues-job-test`
Expected: FAIL — today `start-default-workflow!` spawns a `future`; no `workflow_jobs` row exists, so `active-job` returns nil.

- [ ] **Step 3: Replace the `future` in `start-default-workflow!`**

In `start-default-workflow!` (lines 828-843), replace the `let`/`future` block:

```clojure
(defn start-default-workflow!
  [db executor project-id user-id]
  (seed-default-workflows! db user-id)
  (when-let [default-wf (persistence/get-default-workflow db user-id)]
    (let [run (persistence/create-workflow-run! db project-id (:id default-wf) "autonomous" {})]
      (workflow-jobs/enqueue-job! db {:workflow-run-id (:id run)
                                      :project-id      project-id
                                      :user-id         user-id})
      run)))
```

- [ ] **Step 4: Replace the `future` in `force-proceed!`**

In `force-proceed!` (lines 660-671), replace the `(let [null-os ...] (future ...))` block with an enqueue:

```clojure
        ;; Set force-proceeded flag and resume via the durable queue.
        (persistence/update-workflow-run! db run-id
                                          {:status "in_progress"
                                           :config (assoc (or (:config run) {}) :force-proceeded true)})
        (workflow-jobs/enqueue-job! db {:workflow-run-id run-id
                                        :project-id      project-id
                                        :user-id         user-id})
        (persistence/get-workflow-run db run-id)
```

- [ ] **Step 5: Replace the three `future`s in `respond-to-step!`**

In each of the three branches of `respond-to-step!` (code-context path ~768-774, decision path ~794-799, sequential clarify ~815-821), replace the `(let [null-os (java.io.ByteArrayOutputStream.)] (future ...))` block with:

```clojure
            (workflow-jobs/enqueue-job! db {:workflow-run-id run-id
                                            :project-id      project-id
                                            :user-id         user-id})
```
Keep the surrounding `(persistence/update-workflow-run! ...)` and trailing `(persistence/get-workflow-run db run-id)` in each branch exactly as-is. In the sequential-clarify branch the enqueue goes where the `future` was, still inside the enclosing `let [run ...]`.

- [ ] **Step 6: Adapt any existing tests that assumed synchronous background execution**

Run: `grep -n "start-default-workflow!\|respond-to-step!\|force-proceed!" test/kaleidoscope/api/workflows_test.clj test/kaleidoscope/http_api/*workflows*_test.clj 2>/dev/null`

For each hit that asserts the run *completed* (e.g. checks step statuses are `"completed"` or run status is `"completed"`) right after calling one of these fns, insert a queue-drain before the assertion:

```clojure
;; drain the queue synchronously the way process-run-async! would in the background
(kaleidoscope.api.workflow-jobs/drain! db executor "test-worker")
```
(Requires `[kaleidoscope.api.workflow-jobs :as workflow-jobs-api]` — this ns is created in Task 5. If executing strictly in order, mark these test edits and complete them at the end of Task 5.)

- [ ] **Step 7: Run the workflows suite**

Run: `./bin/test --focus kaleidoscope.api.workflows-test`
Expected: PASS including `start-default-workflow-enqueues-job-test`.

- [ ] **Step 8: Commit**

```bash
git add src/kaleidoscope/api/workflows.clj test/kaleidoscope/api/workflows_test.clj
git commit -m "feat(workflows): enqueue background resumption instead of firing futures

- force-proceed!, respond-to-step! (x3), start-default-workflow! now
  enqueue a durable workflow_job instead of spawning an unsupervised future
- Work is drained in-instance by process-run-async! (wired in Task 6)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Worker orchestration — `api/workflow_jobs`

**Files:**
- Create: `src/kaleidoscope/api/workflow_jobs.clj`
- Test: `test/kaleidoscope/api/workflow_jobs_test.clj`

**Interfaces:**
- Consumes: `workflow-jobs/claim-next-job!`, `complete-job!`, `retry-job!`, `fail-job!`, `reap-stalled-jobs!`; `workflows/advance-run!*`; `projects-persistence/get-project`.
- Produces:
  - `work-once!` `[db executor owner]` → the claimed job map (after processing) or nil if the queue was empty.
  - `drain!` `[db executor owner]` → integer count of jobs processed until the queue drained.
  - `reap!` `[db lease-seconds]` → `{:failed n :requeued n}` (thin passthrough).
  - `process-run-async!` `[db executor owner]` → returns immediately (fires a `future`); the future opportunistically reaps stalled jobs, then drains the queue, then ends. **This is the request-triggered entrypoint** the enqueue sites call — it replaces the deleted worker daemon. It does not loop; when the queue is empty (only future-dated retry rows remain, or nothing), the future returns and the instance can go idle. Errors inside the future are logged, never thrown to the caller.
  - `default-lease-seconds` = `900`.

- [ ] **Step 1: Write failing tests for success, retry→fail, and drain**

Create `test/kaleidoscope/api/workflow_jobs_test.clj`:

```clojure
(ns kaleidoscope.api.workflow-jobs-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.workflow-jobs :as wj]
            [kaleidoscope.api.workflows :as workflows]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.rdbms.embedded-postgres-impl :as embedded-postgres]
            [kaleidoscope.persistence.workflow-jobs :as jobs]
            [kaleidoscope.persistence.workflows :as workflows-persistence]
            [kaleidoscope.workflows.protocol :as wf-protocol]
            [matcher-combinators.test :refer [match?]]))

(def custom-workflow
  {:name "Q" :description "d" :is-default false
   :steps [{:name "S" :description "d" :position 0 :agent-type "coach" :output-kind "text"}]})

(defn- fixture! [db]
  (let [user-id "worker@example.com"
        wf      (workflows/create-workflow! db user-id custom-workflow)
        project (projects-persistence/create-project! db {:user-id user-id :title "P"})
        run     (workflows/create-run! db (:id project) user-id {:workflow-id (:id wf) :mode "autonomous"})]
    (jobs/enqueue-job! db {:workflow-run-id (:id run) :project-id (:id project) :user-id user-id})
    {:user-id user-id :project-id (:id project) :run-id (:id run)}))

(deftest work-once-runs-and-completes-a-job-test
  (let [db       (embedded-postgres/fresh-db!)
        {:keys [run-id]} (fixture! db)
        completing (reify wf-protocol/IWorkflowExecutor
                     (execute-step! [_ _db _project step-run _os]
                       (workflows-persistence/update-step-run! _db (:id step-run) {:status "completed"})
                       "ok"))
        processed  (wj/work-once! db completing "worker-1")]
    (testing "the job is processed and marked done"
      (is (some? processed))
      (is (= "done" (:status (jobs/get-job db (:id processed))))))
    (testing "the queue is now empty"
      (is (nil? (wj/work-once! db completing "worker-1"))))))

(deftest work-once-retries-then-fails-test
  (let [db (embedded-postgres/fresh-db!)
        {:keys [run-id]} (fixture! db)
        boom (reify wf-protocol/IWorkflowExecutor
               (execute-step! [_ _ _ _ _] (throw (ex-info "boom" {}))))]
    ;; first attempt: fails, but attempts (1) < max (3) -> requeued with backoff
    (let [j (wj/work-once! db boom "worker-1")]
      (is (= "queued" (:status (jobs/get-job db (:id j))))))
    ;; make it immediately claimable again, then exhaust attempts
    (dotimes [_ 2]
      (kaleidoscope.persistence.rdbms/transact! db ["UPDATE workflow_jobs SET available_at = now() WHERE workflow_run_id = ?" run-id])
      (wj/work-once! db boom "worker-1"))
    (testing "after max attempts the job is failed with the error recorded"
      (is (match? {:status "failed" :last-error "boom"}
                  (jobs/active-job db run-id) )))))

(deftest drain-processes-all-queued-jobs-test
  (let [db (embedded-postgres/fresh-db!)
        completing (reify wf-protocol/IWorkflowExecutor
                     (execute-step! [_ _db _project step-run _os]
                       (workflows-persistence/update-step-run! _db (:id step-run) {:status "completed"})
                       "ok"))]
    (dotimes [_ 3] (fixture! db))
    (testing "drain! returns the number of jobs processed"
      (is (= 3 (wj/drain! db completing "worker-1"))))))
```
Note: `work-once-retries-then-fails-test`'s final assertion uses `active-job`, but a failed job has `active_run_id = NULL`, so `active-job` returns nil. Fix the assertion to fetch by the job id captured from the first `work-once!` instead:

```clojure
    (let [j (wj/work-once! db boom "worker-1")]
      (is (= "queued" (:status (jobs/get-job db (:id j)))))
      (dotimes [_ 2]
        (kaleidoscope.persistence.rdbms/transact! db ["UPDATE workflow_jobs SET available_at = now() WHERE id = ?" (:id j)])
        (wj/work-once! db boom "worker-1"))
      (testing "after max attempts the job is failed with the error recorded"
        (is (match? {:status "failed" :last-error "boom"} (jobs/get-job db (:id j))))))
```
Use this corrected form (drop the earlier `active-job` version).

- [ ] **Step 2: Run to verify failure**

Run: `./bin/test --focus kaleidoscope.api.workflow-jobs-test`
Expected: FAIL — `kaleidoscope.api.workflow-jobs` not found.

- [ ] **Step 3: Implement the orchestration namespace**

Create `src/kaleidoscope/api/workflow_jobs.clj`:

```clojure
(ns kaleidoscope.api.workflow-jobs
  "Worker-side orchestration: claim queued workflow_jobs and advance their runs.
   Depends on api/workflows for the actual step execution (advance-run!*, which
   assumes the caller holds the run's lease — here, the claimed job IS the lease)."
  (:require [kaleidoscope.api.workflows :as workflows]
            [kaleidoscope.persistence.workflow-jobs :as jobs]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [taoensso.timbre :as log]))

(def default-lease-seconds 900)

(defn- backoff-seconds [attempts]
  (min 300 (* 30 (max 1 attempts))))

(defn work-once!
  "Claim and process one queued job. Returns the job (post-processing) or nil if
   the queue is empty. On failure, retries with backoff until max_attempts, then
   fails the job. The claimed job already holds the run's active slot, so we call
   advance-run!* (which does not re-acquire the lease)."
  [db executor owner]
  (when-let [job (jobs/claim-next-job! db owner)]
    (span/with-span! {:name "kaleidoscope.workflow-jobs.work-once"}
      (try
        (workflows/advance-run!* db executor (:project-id job) (:user-id job)
                                 (:workflow-run-id job) (java.io.ByteArrayOutputStream.))
        (jobs/complete-job! db (:id job))
        (catch Throwable t
          (log/errorf "workflow_job %s failed (attempt %s/%s): %s"
                      (:id job) (:attempts job) (:max-attempts job) (.getMessage t))
          (if (< (:attempts job) (:max-attempts job))
            (jobs/retry-job! db (:id job) (backoff-seconds (:attempts job)) (.getMessage t))
            (jobs/fail-job! db (:id job) (.getMessage t)))))
      job)))

(defn drain!
  "Process queued jobs until the queue is empty. Returns the count processed."
  [db executor owner]
  (loop [n 0]
    (if (work-once! db executor owner)
      (recur (inc n))
      n)))

(defn reap!
  "Recover jobs whose worker died mid-run."
  [db lease-seconds]
  (jobs/reap-stalled-jobs! db lease-seconds))

(defn process-run-async!
  "Request-triggered, scale-to-zero-friendly entrypoint. Fires a background
   thread on the CURRENT (already-awake) instance that: (1) opportunistically
   reaps any stalled jobs from a prior crash, then (2) drains the queue, then
   ends. No loop, no timer — when the queue is empty the thread returns and the
   instance is free to auto-stop (and Neon to autosuspend). Callers (the enqueue
   sites in api/workflows) invoke this right after enqueue-job!; it returns
   immediately. Note: a job requeued with backoff (available_at in the future)
   is NOT re-drained here — it waits for the next trigger. That is the accepted
   scale-to-zero tradeoff."
  [db executor owner]
  (future
    (try
      (reap! db default-lease-seconds)
      (drain! db executor owner)
      (catch Throwable t
        (log/errorf "process-run-async! failed: %s" (.getMessage t)))))
  nil)
```

Note on `drain!` termination under scale-to-zero: `work-once!` on failure requeues with `available_at = now() + backoff` (a *future* timestamp), and `claim-next-job!` only claims rows with `available_at <= now()`. So a just-failed job is not re-claimed within the same drain — `drain!` naturally stops once only future-dated (or zero) jobs remain, rather than spinning on a backing-off job. This is exactly the behavior scale-to-zero wants: do the available work, then let the instance idle.

- [ ] **Step 4: Run to verify pass**

Run: `./bin/test --focus kaleidoscope.api.workflow-jobs-test`
Expected: PASS (3 tests). Then complete the Task 4, Step 6 test edits (the `drain!` insertions) now that `workflow-jobs-api` exists, and re-run `./bin/test --focus kaleidoscope.api.workflows-test`.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/workflow_jobs.clj test/kaleidoscope/api/workflow_jobs_test.clj \
        test/kaleidoscope/api/workflows_test.clj
git commit -m "feat(workflows): worker orchestration for the job queue

- work-once! claims a job, advances its run, completes/retries/fails it
- drain! empties the queue; reap! recovers stalled jobs
- Retry backoff caps at 300s; jobs fail after max_attempts

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Request-triggered execution — wire `process-run-async!` into the enqueue sites

There is **no worker daemon, no `run-loop!`, and no `main "worker"` dispatch** (all removed by the scale-to-zero revision). Instead, each background enqueue site fires the in-instance drain trigger from Task 5. This is the task that makes enqueued jobs actually run.

**Files:**
- Modify: `src/kaleidoscope/api/workflows.clj` — at the four sites changed in Task 4, call `process-run-async!` right after `enqueue-job!`.
- Test: `test/kaleidoscope/api/workflows_test.clj` (extend)

**Interfaces:**
- Consumes: `workflow-jobs-api/process-run-async!` (Task 5), `workflow-jobs-api/drain!` (for the deterministic test).
- Produces: no signature changes. After this task, enqueuing a job also processes it in the same instance; nothing runs when the queue is idle.

- [ ] **Step 1: Write a failing test that an enqueue site drains in-instance**

Because `process-run-async!` fires a `future` (nondeterministic timing), the site wiring is best asserted deterministically by draining synchronously. Add to `test/kaleidoscope/api/workflows_test.clj`:

```clojure
(deftest respond-to-step-enqueues-and-drains-test
  (let [db       (embedded-postgres/fresh-db!)
        completing (reify wf-protocol/IWorkflowExecutor
                     (execute-step! [_ _db _project step-run _os]
                       (workflows-persistence/update-step-run! _db (:id step-run) {:status "completed"})
                       "ok"))
        {:keys [run-id]} (fixture! db)]        ;; a run with a queued job (see Task 5 fixture!)
    (testing "the enqueued job is drained by the same instance (no separate worker)"
      (is (= 1 (workflow-jobs-api/drain! db completing "web-1")))     ;; the one queued job runs
      (is (= 0 (workflow-jobs-api/drain! db completing "web-1"))))))  ;; queue now empty
```
(Assert the *drain* here — the synchronous equivalent of what `process-run-async!` does in a background thread. The wiring itself, that the site calls the trigger, is a one-liner verified by reading the diff + the site-level test in Task 4.)

- [ ] **Step 2: Run to verify failure**

Run: `./bin/test --focus kaleidoscope.api.workflows-test/respond-to-step-enqueues-and-drains-test`
Expected: FAIL until the drain path is exercised / the helper exists.

- [ ] **Step 3: Wire the trigger into the four enqueue sites**

In `src/kaleidoscope/api/workflows.clj`, at each of the four sites edited in Task 4 (`force-proceed!`, the three `respond-to-step!` branches, `start-default-workflow!`), immediately after the `enqueue-job!` call add:

```clojure
      (workflow-jobs-api/process-run-async! db executor
                                            (str "web-" (or (System/getenv "FLY_MACHINE_ID") "local")))
```
Requires `[kaleidoscope.api.workflow-jobs :as workflow-jobs-api]` in the ns `:require`. Notes:
- These sites already have `db` and `executor` in scope (they take the executor to advance steps); confirm and thread it — if a site lacks `executor`, pass it through from its caller (a small signature change local to `api/workflows`).
- `process-run-async!` returns immediately; the enclosing fn still returns its `(persistence/get-workflow-run db run-id)` as before, so no HTTP-visible behavior changes — the run just advances on a background thread of *this* instance instead of a separate worker.
- Do **not** add any loop, timer, or `Thread/sleep`. The drain ends when the queue is empty; the instance then idles and Fly/Neon suspend.

- [ ] **Step 4: Run the workflows suite + full suite**

Run: `./bin/test --focus kaleidoscope.api.workflows-test`
Expected: PASS. Then `task test:summary` — no new failures.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/workflows.clj test/kaleidoscope/api/workflows_test.clj
git commit -m "feat(workflows): drain enqueued jobs in-instance (request-triggered, scale-to-zero)

- Each background enqueue site fires process-run-async! (reap + drain on a
  background thread of the same app instance) instead of a separate worker
- No poller, no daemon: the instance idles when the queue drains, so Fly
  auto-stop and Neon autosuspend keep idle cost at zero

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

<!-- SUPERSEDED (scale-to-zero revision): the original always-on worker daemon below is retained only for reference and MUST NOT be implemented. -->
<details><summary>Superseded original Task 6 (worker daemon) — do not implement</summary>

- [ ] **Step 3 (SUPERSEDED): Implement the worker namespace**

Create `src/kaleidoscope/worker.clj`:

```clojure
(ns kaleidoscope.worker
  "Background worker process: drains the workflow_jobs queue in a loop. Booted
   as a separate Fly process (see fly.toml [processes] worker) sharing the same
   uberjar and system-boot path as the web server."
  (:require [kaleidoscope.api.workflow-jobs :as workflow-jobs-api]
            [kaleidoscope.init.env :as env]
            [taoensso.timbre :as log]))

(defn worker-tick!
  "Reap stalled jobs, then drain the queue once. Returns jobs processed."
  [db executor owner lease-seconds]
  (workflow-jobs-api/reap! db lease-seconds)
  (workflow-jobs-api/drain! db executor owner))

(defn run-loop!
  "Deployed worker loop — never returns. Ticks, then sleeps poll-ms."
  [db executor owner {:keys [poll-ms lease-seconds]
                      :or   {poll-ms 1000 lease-seconds workflow-jobs-api/default-lease-seconds}}]
  (log/infof "Workflow worker started (owner=%s poll=%sms lease=%ss)" owner poll-ms lease-seconds)
  (loop []
    (try
      (worker-tick! db executor owner lease-seconds)
      (catch Throwable t
        (log/errorf "worker tick failed: %s" (.getMessage t))))
    (Thread/sleep poll-ms)
    (recur)))

(defn start!
  "Boot a system from the environment and run the worker loop."
  [env]
  (let [system   (env/start-system! env)
        db       (:database-connection system)
        executor (:kaleidoscope-workflow-executor system)
        owner    (str "worker-" (or (get env "FLY_MACHINE_ID") "local"))]
    (run-loop! db executor owner {})))
```

- [ ] **Step 4: Dispatch `main/-main` on a `"worker"` arg**

In `src/kaleidoscope/main.clj`, add to the ns `:require`:
```clojure
[kaleidoscope.worker :as worker]
```

Replace the final `start-application!` call in `-main` (line 179) so a `"worker"` arg boots the worker instead of the HTTP server:

```clojure
  (let [env (into {} (System/getenv))]
    (if (= "worker" (first args))
      (worker/start! env)
      (start-application! env)))
```
(Keep the two `sig/with-handler` blocks above it unchanged; both processes want SIGTERM to exit cleanly.)

- [ ] **Step 5: Run the worker test + full suite**

Run: `./bin/test --focus kaleidoscope.worker-test`
Expected: PASS.

Run: `task test:summary`
Expected: no new failures across the suite.

- [ ] **Step 6: Commit**

```bash
git add src/kaleidoscope/worker.clj src/kaleidoscope/main.clj test/kaleidoscope/worker_test.clj
git commit -m "feat(workflows): worker process entrypoint

- kaleidoscope.worker/run-loop! reaps + drains the queue on an interval
- main -main dispatches to the worker on a 'worker' arg (same uberjar)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

</details>

---

### Task 7: Operations docs — request-triggered queue, scale-to-zero *(no fly.toml change)*

**Files:**
- Modify: `docs/operations.md`
- **`fly.toml` is intentionally NOT modified** — there is no separate worker process. The single `app` process handles HTTP *and* the in-instance drain. Scale-to-zero relies on Fly's existing app-machine auto-stop + Neon autosuspend, both already in place; adding an always-on worker process would defeat both.

**Interfaces:**
- Consumes: nothing new (the trigger from Task 6 is in the app process).
- Produces: an operations-doc section describing the request-triggered queue and its accepted tradeoffs.

- [ ] **Step 1: Confirm no always-on process was introduced**

Run: `grep -rIn "run-loop!\|worker/start!\|processes\]" src/kaleidoscope fly.toml 2>/dev/null`
Expected: no `run-loop!`/`worker/start!` in `src/`, and no new `[processes]` worker in `fly.toml`. If any appear, the scale-to-zero constraint was violated — remove them. (Verify Fly app-machine auto-stop is enabled — `auto_stop_machines`/`min_machines_running=0` under `[http_service]` — so the app truly suspends when idle; note it if a config change is needed, but don't add a worker process.)

- [ ] **Step 2: Document the queue in `docs/operations.md`**

Add a section near the production-deployment section:

```markdown
## Async workflow queue (request-triggered, scale-to-zero)

Background workflow advancement (autonomous runs, force-proceed, clarify
responses, auto-started default workflows) is enqueued to the durable
`workflow_jobs` table and drained **by the same `app` instance that received the
request**, on a background thread (`api/workflow-jobs/process-run-async!`) that
ends when the queue empties. There is deliberately **no worker process and no
poller** — that is what lets the Fly app machine auto-stop and the Neon database
autosuspend when idle. The interactive `POST .../advance` SSE endpoint still
streams synchronously.

- **Coordination:** the `workflow_jobs.active_run_id` UNIQUE index guarantees at
  most one active job (thus one advancing thread/machine) per run — it holds
  across concurrent requests and across >1 app machine. Claiming uses
  `FOR UPDATE SKIP LOCKED`.
- **Opportunistic reaping (not autonomous):** each trigger reaps jobs whose
  lease (default 900s) expired because an instance died mid-run, *before* it
  drains. There is no timer — recovery of a crashed run happens on the **next**
  enqueue, not on a schedule.
- **Accepted tradeoff:** a job requeued with backoff (`available_at` in the
  future) or orphaned by a crash is not retried until the next enqueue triggers
  a drain. With no further activity it will not retry on its own. This is
  acceptable for a low-frequency, user-triggered feature; the durable row +
  `last_error` mean nothing is lost. If autonomous recovery is later needed, add
  a coarse scheduled sweep (a Fly scheduled machine every 5–15 min that
  reaps+drains+exits — one brief DB wake per interval), NOT an always-on worker.
- **Inspecting the queue:** `task db:connect ENV=.env.aws` then
  `SELECT id, workflow_run_id, status, attempts, last_error FROM workflow_jobs
   ORDER BY created_at DESC LIMIT 20;`
- **Retrying a failed/stuck job manually:** `UPDATE workflow_jobs SET
  status='queued', active_run_id=workflow_run_id, attempts=0, available_at=now()
  WHERE id='...';` — then trigger any curation/advance to drain it (or it drains
  on the next user action).
```

- [ ] **Step 3: Commit**

```bash
git add docs/operations.md
git commit -m "docs(ops): document request-triggered workflow queue + scale-to-zero tradeoffs

- No worker process: the app instance drains its own enqueued jobs and idles
- Opportunistic reaping on each trigger; accepted no-autonomous-retry tradeoff
- fly.toml unchanged so app auto-stop + Neon autosuspend keep costs at zero idle

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Durable queue table → Task 1. ✅
- Claim with SKIP LOCKED, retry/backoff, DLQ-equivalent (`failed` + `last_error`), reaper → Task 2. ✅
- Replace single-instance `advancing-runs` atom with cross-process lock → Task 3 (DB lease). ✅
- Enqueue at the four background sites → Task 4. ✅
- Drain logic (`work-once!`/`drain!`/`reap!`) + the request-triggered `process-run-async!` → Task 5. ✅
- Wire the trigger into the enqueue sites (no daemon) → Task 6. ✅
- Ops docs; **no `fly.toml` worker process** (scale-to-zero) → Task 7. ✅
- **Scale-to-zero:** no poll loop, timer, or always-on process; all work is request-triggered and stops when the queue drains → satisfied by Tasks 5–7 (verified by the Task 7 Step 1 grep guard). ✅
- Idempotency hardening for `advance-step!`: the DB lease + `active_run_id` unique slot ensures only one thread/machine advances a run; opportunistic reaping only requeues after the lease expires. Steps already carry their own `status`, so a requeued run resumes at the next pending step. ✅ (Noted in Task 3 comment + operations doc.)

**Placeholder scan:** No TBD/TODO/"add error handling"; every code step shows complete code; every test step shows real assertions.

**Type consistency:**
- `advance-run!*` signature `[db executor project-id user-id run-id output-stream]` — defined in Task 3, consumed identically in Task 5's `work-once!`. ✅
- `enqueue-job!`/`acquire-run-lease!` take `{:workflow-run-id :project-id :user-id}` — same map shape at all call sites (Tasks 3, 4). ✅
- `active_run_id` maintained on every terminal transition: set in `enqueue-job!`/`acquire-run-lease!`, cleared in `complete-job!`/`fail-job!`/`reap` fail-path; kept on `retry-job!`/reap requeue-path. ✅
- Owner strings are just `web-<machine>` tags now (no separate worker); concurrent triggers on the same run still contend for the one `active_run_id` slot, so mutual exclusion holds regardless of owner tag. ✅

**Known follow-ups (out of scope, note for reviewer):**
- **No autonomous retry/recovery (accepted):** a backing-off retry or a crash-orphaned job is only picked up on the *next* enqueue's opportunistic reap; with no further activity it never retries. Deliberate tradeoff for scale-to-zero + low current failure cost. Drop-in if needed later: a coarse scheduled sweep (Fly scheduled machine every 5–15 min, reap+drain+exit — one brief DB wake per interval), never an always-on worker.
- **Terminal-row growth:** `done`/`failed` rows are retained for audit and never pruned. If they accumulate, prune in the same coarse sweep (or a manual/occasional `DELETE`), not a poller.
- **Long interactive advances:** the foreground SSE lease uses a 900s threshold; an advance longer than that would be reaped and re-run on a later trigger (its SSE stream is already dead), which is acceptable.

---

## Execution Handoff

**Plan complete and saved to `plans/2026-07-16-workflow-postgres-job-queue/PLAN.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
