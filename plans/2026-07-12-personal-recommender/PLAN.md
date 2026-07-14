# Personal Recommender Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A pull-based personal recommendation engine: users define interests with editable taste profiles, and a Librarian-agent curation workflow fills finite, explainable shelves honoring a user-controlled trusted/novel (explore/exploit) split.

**Architecture:** Each interest is backed by a `projects` row (Interest ≈ Project) so curation runs reuse the existing workflow engine (`project_workflow_runs` / `project_workflow_step_runs`) unchanged — the "Interest Curation" workflow is Refine (`clarify` step, existing planner machinery) → Discover (Librarian `text` step emitting scored candidate JSON), and the api layer deterministically applies the relevance threshold (scrutiny-config pattern) and the novelty-ratio split before shelving into a new `recommendations` table. The mock executor gains a deterministic Librarian discovery branch so the whole loop is testable without an API key; production needs zero LLM-executor changes because `get-system-prompt` dispatches the Librarian persona for `text` steps.

**Tech Stack:** Clojure, next.jdbc + HoneySQL, Migratus, Malli, reitit, Kaocha + matcher-combinators + ring-mock, Anthropic API (`claude-opus-4-6` via existing LLM executor), embedded H2/Postgres for tests.

## Global Constraints

- Strict 3-layer separation: `http_api/` → `api/` → `persistence/`; never call persistence from http_api, never put HTTP concerns in api.
- Every schema change is a new numbered Migratus `.up.sql`/`.down.sql` pair in `resources/migrations/` — never alter tables directly.
- `!` suffix on every side-effecting function.
- Malli validation at the HTTP boundary (request bodies, path/query params).
- SQL columns `snake_case`, Clojure keys `kebab-case`; conversion is automatic via the `rdbms.clj` helpers (`as-unqualified-kebab-maps`, `snake-kebab-opts`).
- Every task ships with automated tests; tests mirror source structure under `test/kaleidoscope/`.
- Tests and local dev use the mock executor (`workflows/mock.clj`); production uses the LLM executor — no test may require `ANTHROPIC_API_KEY`.
- Ownership is enforced in WHERE clauses (scoped update/delete), not by preceding checks that can be skipped.
- Comment-light: comments only where the WHY is non-obvious.
- No changes to `fly.toml`, `bin/`, or `Taskfile.yml` are made by this plan, so `docs/operations.md` needs no update.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `resources/migrations/20260714000001-add-personal-recommender.up.sql` | Create | `interests` + `recommendations` tables + indexes |
| `resources/migrations/20260714000001-add-personal-recommender.down.sql` | Create | Drop both tables |
| `src/kaleidoscope/persistence/interests.clj` | Create | Data access for `interests` (CRUD, backing-project transaction, JSONB taste_profile) |
| `src/kaleidoscope/persistence/recommendations.clj` | Create | Data access for `recommendations` (bulk shelve, finders, archive, status update) |
| `src/kaleidoscope/api/curation.clj` | Create | Pure novelty-split/threshold logic + curation workflow seed + orchestration (run, respond) |
| `src/kaleidoscope/api/interests.clj` | Create | Interest domain logic: default taste profile, CRUD wrappers, shelf reads, refinement folding |
| `src/kaleidoscope/scoring/agents.clj` | Modify (append after line 326, edit `get-system-prompt` at line 328) | Librarian (📚) persona prompt + taste-profile context block + dispatch |
| `src/kaleidoscope/workflows/mock.clj` | Modify (the `cond` in `execute-step!`, lines 25–57) | Deterministic Librarian discovery branch for tests/dev |
| `src/kaleidoscope/http_api/interests.clj` | Create | reitit routes + Malli schemas for interests/recommendations/curation |
| `src/kaleidoscope/http_api/kaleidoscope.clj` | Modify (require block ~line 12–26, ACL ~line 48, routes vector ~line 238) | Mount `/interests` routes + access-control entry |
| `test/kaleidoscope/persistence/interests_test.clj` | Create/Test | Migration smoke + interests CRUD/ownership/JSONB round-trip |
| `test/kaleidoscope/persistence/recommendations_test.clj` | Create/Test | Recommendations persistence |
| `test/kaleidoscope/api/curation_test.clj` | Create/Test | Novelty-split unit tests + curation orchestration vs mock executor + clarify folding/retune |
| `test/kaleidoscope/api/interests_test.clj` | Create/Test | Interest domain layer (defaults, shelf access gate, fold-refinement) |
| `test/kaleidoscope/scoring/agents_test.clj` | Create/Test | Librarian prompt dispatch + taste-profile context rendering |
| `test/kaleidoscope/workflows/mock_test.clj` | Create/Test | Mock Librarian discovery branch |
| `test/kaleidoscope/http_api/interests_test.clj` | Create/Test | HTTP validation, rate limits, CRUD, end-to-end curation loop |

Execution/lint check used throughout: `./bin/test --focus <test-namespace>` (same runner `task test` wraps).

---

## Task 1: Migration — `interests` and `recommendations` tables

**Files:**
- Create: `resources/migrations/20260714000001-add-personal-recommender.up.sql`
- Create: `resources/migrations/20260714000001-add-personal-recommender.down.sql`
- Test: `test/kaleidoscope/persistence/interests_test.clj`

**Interfaces:**
- Consumes: `kaleidoscope.persistence.rdbms/insert!`, `find-by-keys`; `kaleidoscope.persistence.projects/create-project!` (existing).
- Produces: tables `interests` (`id`, `user_id`, `project_id`, `intent`, `taste_profile` JSONB, `created_at`, `updated_at`) and `recommendations` (`id`, `interest_id`, `kind`, `title`, `source`, `url`, `est_time`, `why`, `origin`, `status`, `added_at`). Later tasks depend on these exact column names.

**Steps:**

- [ ] 1. Write the failing smoke test at `test/kaleidoscope/persistence/interests_test.clj`:

```clojure
(ns kaleidoscope.persistence.interests-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.utils.core :as utils]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(deftest interests-tables-exist-test
  (let [db      (embedded-h2/fresh-db!)
        user-id "reader@example.com"
        project (projects-persistence/create-project! db {:user-id user-id
                                                          :title   "Interest: tech journalism"})
        now     (utils/now)]
    (testing "interests accepts a row and round-trips the JSONB taste profile"
      (is (match? {:user-id       user-id
                   :intent        "Investigative journalism about technology"
                   :taste-profile {:novelty-ratio 0.5 :trusted-sources ["PBS Frontline"]}}
                  (first (rdbms/insert! db :interests
                                        {:id            (utils/uuid)
                                         :user-id       user-id
                                         :project-id    (:id project)
                                         :intent        "Investigative journalism about technology"
                                         :taste-profile {:novelty-ratio   0.5
                                                         :trusted-sources ["PBS Frontline"]}
                                         :created-at    now
                                         :updated-at    now})))))
    (testing "recommendations accepts a row and defaults origin/status"
      (let [interest-id (:id (first (rdbms/find-by-keys db :interests {:user-id user-id})))]
        (is (match? {:interest-id interest-id
                     :origin      "novel"
                     :status      "shelved"}
                    (first (rdbms/insert! db :recommendations
                                          {:id          (utils/uuid)
                                           :interest-id interest-id
                                           :kind        "article"
                                           :title       "The Age of Surveillance"
                                           :source      "Quanta Magazine"
                                           :url         "https://example.com/a"
                                           :est-time    "18 min"
                                           :why         "Directly on your stated intent."
                                           :added-at    now})))))))
```

- [ ] 2. Run it and confirm it fails because the tables don't exist:

```bash
./bin/test --focus kaleidoscope.persistence.interests-test
```

Expected: `ERROR in interests-tables-exist-test` with a `:PersistenceException` whose reason contains `Table "INTERESTS" not found`.

- [ ] 3. Create `resources/migrations/20260714000001-add-personal-recommender.up.sql`:

```sql
-- Personal recommender: interests (taste profiles) + recommendations (shelves).
-- See plans/2026-07-12-personal-recommender/DESIGN.md.
--
-- Interest ≈ Project made literal: each interest is backed by a projects row
-- because curation runs reuse project_workflow_runs, whose project_id FK
-- requires one. Deleting the backing project cascades here (and from here to
-- recommendations), so one delete tears down the whole interest.
CREATE TABLE interests (
  id            UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id       TEXT NOT NULL,
  project_id    UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  intent        TEXT NOT NULL,
  taste_profile JSONB NOT NULL DEFAULT '{}',  -- {keywords, formats, lengths, trusted_sources, novelty_ratio, cadence, refinements}
  created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  UNIQUE (project_id)
);

--;;

CREATE TABLE recommendations (
  id          UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  interest_id UUID NOT NULL REFERENCES interests(id) ON DELETE CASCADE,
  kind        TEXT NOT NULL,                   -- podcast/article/show/video/book/paper/newsletter/course
  title       TEXT NOT NULL,
  source      TEXT NOT NULL,                   -- e.g. "PBS Frontline"
  url         TEXT,
  est_time    TEXT,                            -- e.g. "18 min", "6 episodes"
  why         TEXT NOT NULL,                   -- one-line rationale surfaced on the card
  origin      TEXT NOT NULL DEFAULT 'novel',   -- trusted | novel (drives the "new source" tag)
  status      TEXT NOT NULL DEFAULT 'shelved', -- shelved | queued | archived
  added_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

--;;

CREATE INDEX idx_interests_user_id ON interests (user_id);
--;;
CREATE INDEX idx_recommendations_interest_id ON recommendations (interest_id);
```

- [ ] 4. Create `resources/migrations/20260714000001-add-personal-recommender.down.sql`:

```sql
DROP TABLE recommendations;
--;;
DROP TABLE interests;
```

- [ ] 5. Run the test again, expected PASS:

```bash
./bin/test --focus kaleidoscope.persistence.interests-test
```

Expected: `1 tests, 3 assertions, 0 failures`.

- [ ] 6. Commit:

```bash
git add resources/migrations/20260714000001-add-personal-recommender.up.sql resources/migrations/20260714000001-add-personal-recommender.down.sql test/kaleidoscope/persistence/interests_test.clj
git commit -m "feat(recommender): add interests and recommendations tables

- interests: per-user topic with JSONB taste_profile, backed by a projects row (UNIQUE project_id, ON DELETE CASCADE) so curation reuses project_workflow_runs
- recommendations: shelf items with kind/source/why rationale, origin (trusted|novel) and status (shelved|queued|archived) defaults
- indexes on interests.user_id and recommendations.interest_id
- smoke test proves JSONB taste_profile round-trips on embedded H2"
```

---

## Task 2: Persistence layer for interests

**Files:**
- Create: `src/kaleidoscope/persistence/interests.clj`
- Test: `test/kaleidoscope/persistence/interests_test.clj` (append)

**Interfaces:**
- Consumes: tables from Task 1; `rdbms/make-finder`, `rdbms/insert!`, `rdbms/scoped-update!`, `rdbms/scoped-delete!`; `projects-persistence/create-project!`.
- Produces (later tasks call these with exactly these signatures):
  - `(get-interests db user-id)` → seq of interest maps
  - `(get-interest db interest-id user-id)` → map | nil
  - `(get-interest-by-project-id db project-id)` → map | nil (unscoped; internal use by the mock executor)
  - `(create-interest! db {:keys [user-id intent taste-profile]})` → interest map incl. `:project-id`
  - `(update-interest! db interest-id user-id {:keys [intent taste-profile]})` → map | nil
  - `(delete-interest! db interest-id user-id)` → deleted interest map | nil

**Steps:**

- [ ] 1. Append failing tests to `test/kaleidoscope/persistence/interests_test.clj` (add `[kaleidoscope.persistence.interests :as interests-persistence]` and `[next.jdbc :as next]` to the `:require`):

```clojure
(deftest interest-crud-test
  (let [db       (embedded-h2/fresh-db!)
        user-id  "reader@example.com"
        interest (interests-persistence/create-interest!
                  db {:user-id       user-id
                      :intent        "Long-form journalism about technology and power"
                      :taste-profile {:trusted-sources ["PBS Frontline" "The Hill"]
                                      :novelty-ratio   0.5}})]
    (testing "create-interest! returns the interest with a backing project"
      (is (match? {:user-id       user-id
                   :intent        "Long-form journalism about technology and power"
                   :taste-profile {:novelty-ratio 0.5}}
                  interest))
      (is (uuid? (:project-id interest)))
      (is (some? (projects-persistence/get-project db (:project-id interest) user-id))))

    (testing "get-interest is scoped to the owner"
      (is (match? {:id (:id interest)} (interests-persistence/get-interest db (:id interest) user-id)))
      (is (nil? (interests-persistence/get-interest db (:id interest) "attacker@example.com"))))

    (testing "get-interests lists only the user's interests"
      (is (= 1 (count (interests-persistence/get-interests db user-id))))
      (is (empty? (interests-persistence/get-interests db "attacker@example.com"))))

    (testing "get-interest-by-project-id resolves the backing project"
      (is (match? {:id (:id interest)}
                  (interests-persistence/get-interest-by-project-id db (:project-id interest)))))

    (testing "update-interest! merges nothing implicitly — it sets what it is given, scoped to owner"
      (is (nil? (interests-persistence/update-interest! db (:id interest) "attacker@example.com"
                                                        {:intent "hijacked"})))
      (is (match? {:intent        "Refined intent"
                   :taste-profile {:novelty-ratio 1.0}}
                  (interests-persistence/update-interest! db (:id interest) user-id
                                                          {:intent        "Refined intent"
                                                           :taste-profile {:novelty-ratio 1.0}}))))

    (testing "delete-interest! is scoped and tears down the backing project"
      (is (nil? (interests-persistence/delete-interest! db (:id interest) "attacker@example.com")))
      (is (some? (interests-persistence/delete-interest! db (:id interest) user-id)))
      (is (nil? (interests-persistence/get-interest db (:id interest) user-id)))
      (is (nil? (projects-persistence/get-project db (:project-id interest) user-id))))))
```

- [ ] 2. Run and confirm failure:

```bash
./bin/test --focus kaleidoscope.persistence.interests-test
```

Expected: `ERROR` — `Could not locate kaleidoscope/persistence/interests__init.class ... on classpath`.

- [ ] 3. Create `src/kaleidoscope/persistence/interests.clj`:

```clojure
(ns kaleidoscope.persistence.interests
  (:require [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]
            [next.jdbc :as next]))

(def ^:private get-interests-raw
  (rdbms/make-finder :interests))

(defn get-interests
  "Return all interests for a user."
  [db user-id]
  (get-interests-raw db {:user-id user-id}))

(defn get-interest
  "Return a single interest, checking user ownership."
  [db interest-id user-id]
  (first (get-interests-raw db {:id interest-id :user-id user-id})))

(defn get-interest-by-project-id
  "Return the interest backed by a project, or nil. Unscoped — used internally
  by the workflow executor, which receives the project after the caller has
  already verified ownership."
  [db project-id]
  (first (get-interests-raw db {:project-id project-id})))

(defn- interest-title
  [intent]
  (let [prefix (str "Interest: " intent)]
    (if (> (count prefix) 120) (subs prefix 0 120) prefix)))

(defn create-interest!
  "Create an interest and its backing project in one transaction. The backing
  project is what curation workflow runs attach to (Interest ≈ Project)."
  [db {:keys [user-id intent taste-profile]}]
  (next/with-transaction [tx db]
    (let [now     (utils/now)
          project (projects-persistence/create-project! tx {:user-id     user-id
                                                            :title       (interest-title intent)
                                                            :description intent
                                                            :status      "interest"})]
      (first (rdbms/insert! tx
                            :interests
                            {:id            (utils/uuid)
                             :user-id       user-id
                             :project-id    (:id project)
                             :intent        intent
                             :taste-profile (or taste-profile {})
                             :created-at    now
                             :updated-at    now}
                            :ex-subtype :UnableToCreateInterest)))))

(defn update-interest!
  "Update an interest, scoped to user-id. Returns nil if not found or not
  owned — the WHERE clause enforces that, not a preceding check. Only
  intent/taste-profile are settable: the updates map is destructured, not
  passed through, so a caller can't smuggle in :user-id or :project-id."
  [db interest-id user-id {:keys [intent taste-profile]}]
  (first (rdbms/scoped-update! db
                               :interests
                               {:id interest-id :user-id user-id}
                               (cond-> {:updated-at (utils/now)}
                                 intent               (assoc :intent intent)
                                 (some? taste-profile) (assoc :taste-profile taste-profile)))))

(defn delete-interest!
  "Delete an interest by deleting its backing project row — the interests row,
  its recommendations, and any curation runs all cascade from it. Returns the
  deleted interest, or nil if not found or not owned."
  [db interest-id user-id]
  (when-let [interest (get-interest db interest-id user-id)]
    (rdbms/scoped-delete! db :projects {:id (:project-id interest) :user-id user-id}
                          :ex-subtype :UnableToDeleteInterest)
    interest))
```

- [ ] 4. Run the test again, expected PASS:

```bash
./bin/test --focus kaleidoscope.persistence.interests-test
```

Expected: `2 tests, 0 failures`.

- [ ] 5. Commit:

```bash
git add src/kaleidoscope/persistence/interests.clj test/kaleidoscope/persistence/interests_test.clj
git commit -m "feat(recommender): interests persistence layer

- create-interest! creates the backing project + interest in one transaction
- get-interest/update-interest!/delete-interest! scoped to user-id in the WHERE clause (nil for not-found and not-owned alike)
- update-interest! destructures intent/taste-profile only, so callers can't smuggle user-id/project-id into SET
- delete-interest! removes the backing project row and lets ON DELETE CASCADE tear down the rest
- get-interest-by-project-id for executor-side taste-profile lookups"
```

---

## Task 3: Persistence layer for recommendations

**Files:**
- Create: `src/kaleidoscope/persistence/recommendations.clj`
- Test: `test/kaleidoscope/persistence/recommendations_test.clj`

**Interfaces:**
- Consumes: Task 1 tables; Task 2 `create-interest!` (test setup only).
- Produces:
  - `(create-recommendations! db interest-id candidates)` → inserted rows; each candidate is `{:kind :title :source :url :est-time :why :origin}`
  - `(get-recommendations db interest-id {:keys [status kind]})` → seq, newest first
  - `(archive-shelved! db interest-id)` → archives all currently-shelved rows
  - `(update-recommendation-status! db recommendation-id interest-id status)` → map | nil

**Steps:**

- [ ] 1. Write the failing test at `test/kaleidoscope/persistence/recommendations_test.clj`:

```clojure
(ns kaleidoscope.persistence.recommendations-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.persistence.interests :as interests-persistence]
            [kaleidoscope.persistence.recommendations :as recommendations-persistence]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(def candidates
  [{:kind "article" :title "Trusted piece" :source "PBS Frontline"
    :url "https://example.com/1" :est-time "18 min"
    :why "Directly on your stated intent." :origin "trusted"}
   {:kind "podcast" :title "Novel find" :source "The Gradient"
    :url "https://example.com/2" :est-time "40 min"
    :why "New source covering your keywords." :origin "novel"}])

(deftest recommendations-crud-test
  (let [db          (embedded-h2/fresh-db!)
        interest    (interests-persistence/create-interest!
                     db {:user-id "reader@example.com" :intent "Tech journalism" :taste-profile {}})
        interest-id (:id interest)]

    (testing "empty candidate list inserts nothing and returns []"
      (is (= [] (recommendations-persistence/create-recommendations! db interest-id []))))

    (testing "bulk create shelves candidates with status shelved"
      (is (match? [{:status "shelved" :origin "trusted"} {:status "shelved" :origin "novel"}]
                  (sort-by :title (recommendations-persistence/create-recommendations!
                                   db interest-id candidates)))))

    (testing "get-recommendations filters by status and kind"
      (is (= 2 (count (recommendations-persistence/get-recommendations db interest-id {}))))
      (is (= 2 (count (recommendations-persistence/get-recommendations db interest-id {:status "shelved"}))))
      (is (match? [{:kind "podcast"}]
                  (recommendations-persistence/get-recommendations db interest-id {:kind "podcast"}))))

    (testing "update-recommendation-status! is scoped to the interest"
      (let [rec-id (:id (first (recommendations-persistence/get-recommendations db interest-id {})))]
        (is (nil? (recommendations-persistence/update-recommendation-status!
                   db rec-id (random-uuid) "queued")))
        (is (match? {:status "queued"}
                    (recommendations-persistence/update-recommendation-status!
                     db rec-id interest-id "queued")))))

    (testing "archive-shelved! archives the remaining shelved rows but not queued ones"
      (recommendations-persistence/archive-shelved! db interest-id)
      (is (= 1 (count (recommendations-persistence/get-recommendations db interest-id {:status "archived"}))))
      (is (= 1 (count (recommendations-persistence/get-recommendations db interest-id {:status "queued"}))))
      (is (= 0 (count (recommendations-persistence/get-recommendations db interest-id {:status "shelved"})))))))
```

- [ ] 2. Run and confirm failure:

```bash
./bin/test --focus kaleidoscope.persistence.recommendations-test
```

Expected: `ERROR` — `Could not locate kaleidoscope/persistence/recommendations__init.class ... on classpath`.

- [ ] 3. Create `src/kaleidoscope/persistence/recommendations.clj`:

```clojure
(ns kaleidoscope.persistence.recommendations
  (:require [honey.sql :as hsql]
            [honey.sql.helpers :as hh]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]))

(defn create-recommendations!
  "Shelve a batch of curated candidates for an interest. Every row starts as
  status=shelved; origin comes from the candidate (trusted | novel)."
  [db interest-id candidates]
  (if (empty? candidates)
    []
    (let [now (utils/now)]
      (rdbms/insert! db
                     :recommendations
                     (mapv (fn [{:keys [kind title source url est-time why origin]}]
                             {:id          (utils/uuid)
                              :interest-id interest-id
                              :kind        kind
                              :title       title
                              :source      source
                              :url         url
                              :est-time    est-time
                              :why         why
                              :origin      (or origin "novel")
                              :status      "shelved"
                              :added-at    now})
                           candidates)
                     :ex-subtype :UnableToCreateRecommendation))))

(defn get-recommendations
  "Return recommendations for an interest, newest first, optionally filtered
  by status and/or kind."
  [db interest-id {:keys [status kind]}]
  (next/execute! db
                 (hsql/format {:select   :*
                               :from     :recommendations
                               :where    (cond-> [:and [:= :interest-id interest-id]]
                                           status (conj [:= :status status])
                                           kind   (conj [:= :kind kind]))
                               :order-by [[:added-at :desc]]})
                 {:builder-fn rs/as-unqualified-kebab-maps}))

(defn archive-shelved!
  "Archive everything currently shelved for an interest. The shelf is finite:
  each curation run replaces it rather than growing it without bound."
  [db interest-id]
  (next/execute! db
                 (hsql/format (-> (hh/update :recommendations)
                                  (hh/set {:status "archived"})
                                  (hh/where [:and
                                             [:= :interest-id interest-id]
                                             [:= :status "shelved"]])))
                 {:builder-fn rs/as-unqualified-kebab-maps}))

(defn update-recommendation-status!
  "Update a recommendation's status, scoped to interest-id. Returns nil if not
  found or not in that interest — indistinguishable by design."
  [db recommendation-id interest-id status]
  (first (rdbms/scoped-update! db
                               :recommendations
                               {:id recommendation-id :interest-id interest-id}
                               {:status status})))
```

- [ ] 4. Run the test again, expected PASS:

```bash
./bin/test --focus kaleidoscope.persistence.recommendations-test
```

Expected: `1 tests, 0 failures`.

- [ ] 5. Commit:

```bash
git add src/kaleidoscope/persistence/recommendations.clj test/kaleidoscope/persistence/recommendations_test.clj
git commit -m "feat(recommender): recommendations persistence layer

- create-recommendations! bulk-shelves curated candidates (status=shelved, origin from candidate)
- get-recommendations with optional status/kind filters, newest first
- archive-shelved! keeps the shelf finite: each curation run replaces the previous shelf
- update-recommendation-status! scoped to interest-id in the WHERE clause"
```

---

## Task 4: Pure novelty-split and relevance-threshold logic

**Files:**
- Create: `src/kaleidoscope/api/curation.clj` (pure functions only in this task)
- Test: `test/kaleidoscope/api/curation_test.clj`

**Interfaces:**
- Consumes: nothing but Clojure core + cheshire + clojure.set/string.
- Produces (used by Tasks 8–9 and referenced by prompts in Task 5):
  - `default-shelf-size` = 6
  - `(relevance-config level)` → `{:scrutiny s :relevance-threshold t}` for `"quick"`/`"standard"`/`"rigorous"` (5.0/6.0/7.0), defaulting to standard
  - `(novelty-quota shelf-size novelty-ratio)` → `{:trusted n :novel m}`
  - `(tag-origin candidates trusted-sources)` → candidates with `:origin "trusted"|"novel"`
  - `(drop-below-threshold candidates threshold)` → filtered candidates
  - `(split-candidates candidates quota)` → selected candidates honoring the quota with backfill
  - `(parse-candidates output)` → candidate maps (normalizes `:est_time` → `:est-time`), `[]` on bad JSON

**Steps:**

- [ ] 1. Write the failing tests at `test/kaleidoscope/api/curation_test.clj`:

```clojure
(ns kaleidoscope.api.curation-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.curation :as curation]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(deftest novelty-quota-test
  (testing "0.0 — pure exploit: the whole shelf is trusted"
    (is (= {:novel 0 :trusted 6} (curation/novelty-quota 6 0.0))))
  (testing "1.0 — pure explore: the whole shelf is novel"
    (is (= {:novel 6 :trusted 0} (curation/novelty-quota 6 1.0))))
  (testing "0.5 on an even shelf splits exactly"
    (is (= {:novel 3 :trusted 3} (curation/novelty-quota 6 0.5))))
  (testing "0.5 on an odd shelf rounds the extra slot to novel (explore wins ties)"
    (is (= {:novel 3 :trusted 2} (curation/novelty-quota 5 0.5))))
  (testing "intermediate ratios round to the nearest slot"
    (is (= {:novel 2 :trusted 4} (curation/novelty-quota 6 0.25)))
    (is (= {:novel 4 :trusted 2} (curation/novelty-quota 6 0.7))))
  (testing "nil and out-of-range ratios are clamped, never thrown"
    (is (= {:novel 3 :trusted 3} (curation/novelty-quota 6 nil)))
    (is (= {:novel 0 :trusted 6} (curation/novelty-quota 6 -0.4)))
    (is (= {:novel 6 :trusted 0} (curation/novelty-quota 6 1.7)))))

(deftest tag-origin-test
  (let [candidates [{:title "a" :source "PBS Frontline"}
                    {:title "b" :source "pbs frontline"}
                    {:title "c" :source "The Gradient"}
                    {:title "d" :source nil}]]
    (testing "membership in trusted-sources is case-insensitive; everything else is novel"
      (is (match? [{:origin "trusted"} {:origin "trusted"} {:origin "novel"} {:origin "novel"}]
                  (curation/tag-origin candidates ["PBS Frontline"]))))
    (testing "no trusted sources means everything is novel"
      (is (every? #(= "novel" (:origin %)) (curation/tag-origin candidates []))))))

(deftest drop-below-threshold-test
  (let [candidates [{:title "keep" :relevance 8.0}
                    {:title "edge" :relevance 6.0}
                    {:title "drop" :relevance 5.9}
                    {:title "no-score"}]]
    (testing "candidates at or above threshold survive; missing relevance counts as 0.0"
      (is (= ["keep" "edge"] (mapv :title (curation/drop-below-threshold candidates 6.0)))))))

(deftest split-candidates-test
  (let [pool [{:title "t1" :origin "trusted" :relevance 9.0}
              {:title "t2" :origin "trusted" :relevance 8.0}
              {:title "t3" :origin "trusted" :relevance 7.0}
              {:title "n1" :origin "novel" :relevance 8.5}
              {:title "n2" :origin "novel" :relevance 7.5}
              {:title "n3" :origin "novel" :relevance 6.5}]]
    (testing "fills each quota with the best-relevance candidates of that origin"
      (is (= #{"t1" "t2" "n1"}
             (set (map :title (curation/split-candidates pool {:trusted 2 :novel 1}))))))
    (testing "quota of zero on one side selects none of that origin"
      (is (every? #(= "novel" (:origin %))
                  (curation/split-candidates pool {:trusted 0 :novel 3}))))
    (testing "a thin pool backfills from the other origin instead of under-filling"
      (is (= 5 (count (curation/split-candidates pool {:trusted 2 :novel 3}))))
      (is (= 6 (count (curation/split-candidates pool {:trusted 6 :novel 0}))))
      (is (= 3 (count (curation/split-candidates
                       (filter #(= "trusted" (:origin %)) pool)
                       {:trusted 0 :novel 6})))))
    (testing "an empty pool selects nothing"
      (is (= [] (curation/split-candidates [] {:trusted 3 :novel 3}))))))

(deftest parse-candidates-test
  (testing "parses the librarian JSON contract and normalizes est_time"
    (is (match? [{:title "A" :est-time "18 min" :relevance 8.0}]
                (curation/parse-candidates
                 (json/encode {:candidates [{:title "A" :est_time "18 min" :relevance 8.0}]})))))
  (testing "malformed output shelves nothing rather than throwing"
    (is (= [] (curation/parse-candidates "not json at all")))
    (is (= [] (curation/parse-candidates nil)))))

(deftest relevance-config-test
  (testing "thresholds mirror the scrutiny ladder and default to standard"
    (is (= 5.0 (:relevance-threshold (curation/relevance-config "quick"))))
    (is (= 6.0 (:relevance-threshold (curation/relevance-config "standard"))))
    (is (= 7.0 (:relevance-threshold (curation/relevance-config "rigorous"))))
    (is (= 6.0 (:relevance-threshold (curation/relevance-config nil))))))
```

- [ ] 2. Run and confirm failure:

```bash
./bin/test --focus kaleidoscope.api.curation-test
```

Expected: `ERROR` — `Could not locate kaleidoscope/api/curation__init.class ... on classpath`.

- [ ] 3. Create `src/kaleidoscope/api/curation.clj` with the pure logic:

```clojure
(ns kaleidoscope.api.curation
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Novelty split (explore/exploit) + relevance threshold
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-shelf-size 6)

(def ^:private relevance-configs
  {"quick"    {:scrutiny "quick"    :relevance-threshold 5.0}
   "standard" {:scrutiny "standard" :relevance-threshold 6.0}
   "rigorous" {:scrutiny "rigorous" :relevance-threshold 7.0}})

(defn relevance-config
  [level]
  (get relevance-configs (or level "standard") (get relevance-configs "standard")))

(defn novelty-quota
  "Split shelf-size slots into {:trusted n :novel m} from the explore dial.
  novelty-ratio 0.0 = all trusted, 1.0 = all novel. The novel share rounds to
  the nearest slot, so 0.5 on an odd shelf gives the extra slot to novel."
  [shelf-size novelty-ratio]
  (let [ratio (-> (double (or novelty-ratio 0.5)) (max 0.0) (min 1.0))
        novel (int (Math/round (* ratio shelf-size)))]
    {:novel novel :trusted (- shelf-size novel)}))

(defn tag-origin
  "Tag each candidate :origin trusted/novel by case-insensitive membership of
  its source in trusted-sources. Origin is decided here, in code — never by
  the LLM — so the \"new source\" tag can't be smuggled or forgotten."
  [candidates trusted-sources]
  (let [trusted? (into #{} (map str/lower-case) (or trusted-sources []))]
    (mapv (fn [{:keys [source] :as candidate}]
            (assoc candidate :origin
                   (if (trusted? (str/lower-case (or source ""))) "trusted" "novel")))
          candidates)))

(defn drop-below-threshold
  "Drop candidates whose relevance is below threshold (missing = 0.0)."
  [candidates threshold]
  (vec (filter #(>= (double (or (:relevance %) 0.0)) (double threshold)) candidates)))

(defn split-candidates
  "Fill the shelf from an origin-tagged pool: the trusted quota from trusted
  candidates (best relevance first), the novel quota from novel candidates,
  then backfill any shortfall from whatever remains so a thin pool still
  fills the shelf as far as it can."
  [candidates {:keys [trusted novel]}]
  (let [by-relevance  (fn [pool] (sort-by #(- (double (or (:relevance %) 0.0))) pool))
        pools         (group-by :origin candidates)
        trusted-picks (vec (take trusted (by-relevance (get pools "trusted"))))
        novel-picks   (vec (take novel (by-relevance (get pools "novel"))))
        picked?       (set (concat trusted-picks novel-picks))
        shortfall     (- (+ trusted novel)
                         (+ (count trusted-picks) (count novel-picks)))
        backfill      (take (max 0 shortfall)
                            (by-relevance (remove picked? candidates)))]
    (vec (concat trusted-picks novel-picks backfill))))

(defn parse-candidates
  "Parse a Discover step's output into candidate maps. The librarian JSON
  contract uses est_time; normalize to :est-time. Returns [] on any parse
  failure — a malformed discovery shelves nothing rather than throwing."
  [output]
  (try
    (->> (:candidates (json/decode output true))
         (mapv #(set/rename-keys % {:est_time :est-time})))
    (catch Exception _ [])))
```

- [ ] 4. Run the tests again, expected PASS:

```bash
./bin/test --focus kaleidoscope.api.curation-test
```

Expected: `6 tests, 0 failures`.

- [ ] 5. Commit:

```bash
git add src/kaleidoscope/api/curation.clj test/kaleidoscope/api/curation_test.clj
git commit -m "feat(recommender): pure novelty-split and relevance-threshold logic

- novelty-quota: user-controlled explore/exploit dial, clamped, tested at 0.0/1.0/midpoints/odd shelves
- tag-origin: trusted/novel decided in code by case-insensitive source match, never by the LLM
- split-candidates: per-origin quota fill by relevance with backfill for thin pools
- drop-below-threshold + relevance-config mirroring the quick/standard/rigorous scrutiny ladder
- parse-candidates: librarian JSON contract, [] on malformed output"
```

---

## Task 5: Librarian (📚) agent persona

**Files:**
- Modify: `src/kaleidoscope/scoring/agents.clj` (append defs after `advisor-refinement-system-prompt`, ~line 326; add one dispatch line inside `get-system-prompt`, lines 328–339)
- Test: `test/kaleidoscope/scoring/agents_test.clj`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `librarian-system-prompt` (string) — discovery + relevance-scoring persona; JSON contract `{"candidates":[{kind,title,source,url,est_time,why,relevance}]}`
  - `(format-taste-profile-context taste-profile)` → text block used to sync the backing project description (Task 8)
  - `(get-system-prompt "librarian")` → `librarian-system-prompt`

**Steps:**

- [ ] 1. Write the failing test at `test/kaleidoscope/scoring/agents_test.clj`:

```clojure
(ns kaleidoscope.scoring.agents-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kaleidoscope.scoring.agents :as agents]))

(deftest librarian-dispatch-test
  (testing "librarian agent-type resolves to the librarian persona (string and keyword)"
    (is (= agents/librarian-system-prompt (agents/get-system-prompt "librarian")))
    (is (= agents/librarian-system-prompt (agents/get-system-prompt :librarian))))
  (testing "existing dispatches are untouched"
    (is (= agents/pm-system-prompt (agents/get-system-prompt "pm")))
    (is (= agents/general-system-prompt (agents/get-system-prompt "unknown-agent")))))

(deftest librarian-prompt-contract-test
  (testing "the persona spells out the JSON candidate contract and the trusted/novel rules"
    (is (str/includes? agents/librarian-system-prompt "\"candidates\""))
    (is (str/includes? agents/librarian-system-prompt "relevance"))
    (is (str/includes? agents/librarian-system-prompt "trusted"))
    (is (str/includes? agents/librarian-system-prompt "est_time"))))

(deftest format-taste-profile-context-test
  (let [ctx (agents/format-taste-profile-context
             {:keywords        ["surveillance" "antitrust"]
              :formats         ["article" "podcast"]
              :lengths         ["under 20 min"]
              :trusted-sources ["PBS Frontline" "The Hill"]
              :novelty-ratio   0.5
              :refinements     ["Prefer primary reporting over commentary"]})]
    (testing "renders every taste-profile field the librarian needs"
      (is (str/includes? ctx "surveillance, antitrust"))
      (is (str/includes? ctx "article, podcast"))
      (is (str/includes? ctx "under 20 min"))
      (is (str/includes? ctx "PBS Frontline, The Hill"))
      (is (str/includes? ctx "0.5"))
      (is (str/includes? ctx "Prefer primary reporting over commentary"))))
  (testing "an empty profile still renders a well-formed block"
    (is (str/includes? (agents/format-taste-profile-context {}) "<taste_profile>"))))
```

- [ ] 2. Run and confirm failure:

```bash
./bin/test --focus kaleidoscope.scoring.agents-test
```

Expected: `ERROR` — `No such var: agents/librarian-system-prompt`.

- [ ] 3. In `src/kaleidoscope/scoring/agents.clj`, append after `advisor-refinement-system-prompt` (before `get-system-prompt`):

```clojure
(def librarian-system-prompt
  "You are a librarian curating a personal, pull-based library for one reader.
You work from an explicit, user-edited taste profile (provided in the request
as a <taste_profile> block): intent, keywords, preferred formats and lengths,
a trusted-source allowlist, and a novelty ratio — the share of the shelf that
must come from sources OUTSIDE the trusted list.

Propose candidate resources across media kinds (podcast, article, show, video,
book, paper, newsletter, course). Rules:
- Draw generously from the trusted sources AND from genuinely new sources the
  reader has not listed — the novelty ratio tells you roughly how much of each.
- Prefer breadth of media kinds over many items from one kind.
- Score each candidate's relevance to the taste profile from 0-10, honestly:
  10 = squarely on the stated intent; below 6 = tangential.
- Write a concise one-line \"why\" for every candidate — it is shown on the
  reader's card as \"why this is here\". Never omit it.

Return ONLY a JSON object with this structure, no additional text:
{
  \"candidates\": [
    {\"kind\": \"article\", \"title\": \"...\", \"source\": \"...\", \"url\": \"...\",
     \"est_time\": \"18 min\", \"why\": \"<one line>\", \"relevance\": <number 0-10>},
    ...
  ]
}

Propose 10-16 candidates so relevance filtering and the trusted/novel split
have room to work.")

(defn format-taste-profile-context
  "Render a taste profile as the <taste_profile> block librarian prompts read.
   Synced into the backing project description before each curation run."
  [{:keys [keywords formats lengths trusted-sources novelty-ratio refinements]}]
  (str "<taste_profile>\n"
       "Keywords: " (str/join ", " (or keywords [])) "\n"
       "Preferred formats: " (str/join ", " (or formats [])) "\n"
       "Preferred lengths: " (str/join ", " (or lengths [])) "\n"
       "Trusted sources: " (str/join ", " (or trusted-sources [])) "\n"
       "Novelty ratio: " (double (or novelty-ratio 0.5))
       " (share of the shelf that must come from sources OUTSIDE the trusted list)\n"
       (when (seq refinements)
         (str "Refinements from check-ins:\n- " (str/join "\n- " refinements) "\n"))
       "</taste_profile>"))
```

- [ ] 4. In the same file, add the librarian dispatch line to `get-system-prompt` (inside the `case`, before the `general-system-prompt` default):

```clojure
    ("librarian" :librarian)                      librarian-system-prompt
```

so the `case` reads:

```clojure
(defn get-system-prompt
  "Return the system prompt for a given scorer-type or agent-type."
  [agent-type]
  (case agent-type
    ("pm" :pm)                                    pm-system-prompt
    ("engineering_lead" :engineering-lead)        engineering-lead-system-prompt
    ("coach" :coach)                              coach-system-prompt
    ("pm_agent" :pm-agent)                        pm-agent-system-prompt
    ("eng_agent" :eng-agent)                      engineering-lead-agent-system-prompt
    ("task_planner" :task-planner)                task-planner-generation-system-prompt
    ("judge" :judge)                              team-lead-system-prompt
    ("librarian" :librarian)                      librarian-system-prompt
    general-system-prompt))
```

- [ ] 5. Run the tests again, expected PASS:

```bash
./bin/test --focus kaleidoscope.scoring.agents-test
```

Expected: `3 tests, 0 failures`.

- [ ] 6. Commit:

```bash
git add src/kaleidoscope/scoring/agents.clj test/kaleidoscope/scoring/agents_test.clj
git commit -m "feat(recommender): Librarian agent persona

- librarian-system-prompt: curator across media kinds, honors the trusted/novel split, scores per-candidate relevance 0-10, mandatory one-line why, strict JSON candidates contract
- format-taste-profile-context renders the <taste_profile> block prompts read
- get-system-prompt dispatches librarian, so LLM-executor text steps pick the persona up with zero executor changes"
```

---

## Task 6: Mock executor Librarian discovery branch

**Files:**
- Modify: `src/kaleidoscope/workflows/mock.clj` (add requires; add a branch to the `cond` inside `execute-step!`, lines 25–57; add `mock-novel-sources`/`mock-candidates` defs above the record)
- Test: `test/kaleidoscope/workflows/mock_test.clj`

**Interfaces:**
- Consumes: `interests-persistence/get-interest-by-project-id` (Task 2); the librarian JSON contract from Task 5; `curation/parse-candidates` shape from Task 4.
- Produces:
  - `mock-novel-sources` — 7 `[source relevance]` pairs, one deliberately below every threshold (`"Noise Weekly"` 2.0)
  - `(mock-candidates taste-profile)` → deterministic candidate pool: 3 per trusted source at relevance 9.0/8.0/7.0 + one per novel source
  - `MockExecutor.execute-step!` on a `text`+`librarian` step-run writes `{"candidates": [...]}` JSON as the step output and completes it

**Steps:**

- [ ] 1. Write the failing test at `test/kaleidoscope/workflows/mock_test.clj`:

```clojure
(ns kaleidoscope.workflows.mock-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.persistence.interests :as interests-persistence]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.workflows :as workflows-persistence]
            [kaleidoscope.workflows.mock :as workflow-mock]
            [kaleidoscope.workflows.protocol :as wf-protocol]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(deftest mock-candidates-shape-test
  (let [pool (workflow-mock/mock-candidates {:trusted-sources ["PBS Frontline" "The Hill"]
                                             :formats         ["article" "podcast"]})]
    (testing "3 candidates per trusted source + one per mock novel source"
      (is (= (+ 6 (count workflow-mock/mock-novel-sources)) (count pool))))
    (testing "every candidate satisfies the librarian JSON contract"
      (is (every? #(and (:kind %) (:title %) (:source %) (:url %)
                        (:est_time %) (seq (:why %)) (number? (:relevance %)))
                  pool)))
    (testing "one candidate is deliberately below every relevance threshold"
      (is (some #(< (:relevance %) 5.0) pool)))))

(deftest mock-librarian-discovery-step-test
  (let [db       (embedded-h2/fresh-db!)
        user-id  "reader@example.com"
        interest (interests-persistence/create-interest!
                  db {:user-id       user-id
                      :intent        "Tech journalism"
                      :taste-profile {:trusted-sources ["PBS Frontline"] :novelty-ratio 0.5}})
        project  {:id (:project-id interest) :user-id user-id :title "Interest: Tech journalism"}
        run      (workflows-persistence/create-workflow-run! db (:project-id interest) nil "manual" {})
        step-run (workflows-persistence/create-custom-step-run!
                  db (:id run) {:name        "Discover Resources"
                                :description "Propose candidates"
                                :agent-type  "librarian"
                                :position    0})
        ;; create-custom-step-run! has no output-kind arg; set it directly
        step-run (workflows-persistence/update-step-run! db (:id step-run) {:output-kind "text"})
        executor (workflow-mock/make-mock-executor)
        output   (wf-protocol/execute-step! executor db project step-run
                                            (java.io.ByteArrayOutputStream.))]
    (testing "the step output is the candidates JSON, drawn from the taste profile"
      (let [candidates (:candidates (json/decode output true))]
        (is (seq candidates))
        (is (some #(= "PBS Frontline" (:source %)) candidates))
        (is (some #(not= "PBS Frontline" (:source %)) candidates))))
    (testing "the step run is completed with the same output persisted"
      (is (match? {:status "completed"}
                  (workflows-persistence/get-step-run db (:id step-run))))
      (is (= output (:output (workflows-persistence/get-step-run db (:id step-run))))))))
```

- [ ] 2. Run and confirm failure:

```bash
./bin/test --focus kaleidoscope.workflows.mock-test
```

Expected: `ERROR` — `No such var: workflow-mock/mock-candidates` (and the discovery test failing on the default `"Mock output for step: ..."` text).

- [ ] 3. In `src/kaleidoscope/workflows/mock.clj`, add `[kaleidoscope.persistence.interests :as interests-persistence]` to the `:require` block, then add above `(defrecord MockExecutor ...)`:

```clojure
;; Deterministic discovery pool for tests/dev — the "prototype uses curated
;; mock data" path from the design. Trusted sources get 3 candidates each at
;; relevance 9.0/8.0/7.0; each mock novel source contributes one, including a
;; deliberately irrelevant one (2.0) so threshold-dropping is observable.
(def mock-novel-sources
  [["Quanta Magazine"    8.9]
   ["The Gradient"       8.3]
   ["Works in Progress"  7.7]
   ["Long Now Seminars"  7.1]
   ["Asterisk Magazine"  6.6]
   ["The Browser"        6.2]
   ["Noise Weekly"       2.0]])

(defn mock-candidates
  [{:keys [trusted-sources formats]}]
  (let [kinds   (if (seq formats) (vec formats) ["article" "podcast" "video" "book"])
        kind-at (fn [i] (nth kinds (mod i (count kinds))))]
    (vec
     (concat
      (for [[i source] (map-indexed vector (or trusted-sources []))
            [j rel]    (map-indexed vector [9.0 8.0 7.0])]
        {:kind      (kind-at (+ i j))
         :title     (format "%s pick %d" source (inc j))
         :source    source
         :url       (format "https://example.com/trusted/%d-%d" i j)
         :est_time  "15 min"
         :why       (format "Squarely on your stated intent; %s is on your trusted list." source)
         :relevance rel})
      (for [[i [source rel]] (map-indexed vector mock-novel-sources)]
        {:kind      (kind-at i)
         :title     (format "%s discovery" source)
         :source    source
         :url       (format "https://example.com/novel/%d" i)
         :est_time  "20 min"
         :why       (format "New source worth a look: %s covers this from a fresh angle." source)
         :relevance rel})))))
```

- [ ] 4. In `execute-step!`'s `cond` (currently `:clarify` / `:tasks` / `:else`), insert a librarian-discovery branch between the `:tasks` branch and `:else`:

```clojure
        (and (= output-kind :text)
             (= "librarian" (:agent-type step-run)))
        ;; Mock librarian discovery: deterministic candidates from the
        ;; interest's taste profile (design: mock data in dev/tests).
        (let [interest (interests-persistence/get-interest-by-project-id db (:id project))
              payload  (json/encode {:candidates (mock-candidates
                                                  (or (:taste-profile interest) {}))})]
          (write-event! output-stream {:event "token" :data payload})
          (let [completed (persistence/update-step-run! db (:id step-run)
                                                        {:status       "completed"
                                                         :output       payload
                                                         :completed-at (utils/now)})]
            (write-event! output-stream {:event "step_complete" :data completed})
            payload))
```

- [ ] 5. Run the tests again, expected PASS:

```bash
./bin/test --focus kaleidoscope.workflows.mock-test
```

Expected: `2 tests, 0 failures`. Also run `./bin/test --focus kaleidoscope.api.workflows-test` to confirm the mock executor change breaks no existing workflow tests (expected: `0 failures`).

- [ ] 6. Commit:

```bash
git add src/kaleidoscope/workflows/mock.clj test/kaleidoscope/workflows/mock_test.clj
git commit -m "feat(recommender): mock executor librarian discovery branch

- text steps with agent-type librarian emit deterministic candidate JSON built from the interest's taste profile
- 3 candidates per trusted source (relevance 9/8/7) + 7 fixed novel sources incl. one below-threshold (2.0) so dropping is observable
- looks up the interest via get-interest-by-project-id; step completes with the JSON as persisted output
- existing clarify/tasks/text mock behavior unchanged"
```

---

## Task 7: Interest domain layer (`api/interests.clj`)

**Files:**
- Create: `src/kaleidoscope/api/interests.clj`
- Test: `test/kaleidoscope/api/interests_test.clj`

**Interfaces:**
- Consumes: Task 2 + Task 3 persistence fns.
- Produces (HTTP layer and curation orchestration call exactly these):
  - `default-taste-profile` — `{:keywords [] :formats [] :lengths [] :trusted-sources [] :novelty-ratio 0.5 :cadence "weekly" :refinements []}`
  - `(get-interests db user-id)` / `(get-interest db user-id interest-id)`
  - `(create-interest! db user-id {:keys [intent taste-profile]})` — merges defaults
  - `(update-interest! db user-id interest-id updates)` — merges taste-profile edits over the stored profile
  - `(delete-interest! db user-id interest-id)`
  - `(get-shelf db user-id interest-id filters)` — nil unless owned
  - `(update-recommendation-status! db user-id interest-id recommendation-id status)` — nil unless owned
  - `(fold-refinement taste-profile answers)` (pure) and `(fold-refinement! db user-id interest-id answers)`

**Steps:**

- [ ] 1. Write the failing test at `test/kaleidoscope/api/interests_test.clj`:

```clojure
(ns kaleidoscope.api.interests-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.interests :as interests]
            [kaleidoscope.persistence.recommendations :as recommendations-persistence]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(def user-id "reader@example.com")

(deftest create-interest-applies-default-taste-profile-test
  (let [db (embedded-h2/fresh-db!)]
    (testing "an interest created from bare intent gets the full default profile"
      (is (match? {:intent        "Modern jazz history"
                   :taste-profile {:novelty-ratio 0.5 :cadence "weekly" :trusted-sources []}}
                  (interests/create-interest! db user-id {:intent "Modern jazz history"}))))
    (testing "user-supplied fields override defaults without losing the rest"
      (is (match? {:taste-profile {:novelty-ratio 0.8 :cadence "weekly"}}
                  (interests/create-interest! db user-id
                                              {:intent        "Bread baking"
                                               :taste-profile {:novelty-ratio 0.8}}))))))

(deftest update-interest-merges-taste-profile-test
  (let [db       (embedded-h2/fresh-db!)
        interest (interests/create-interest! db user-id
                                             {:intent        "Tech journalism"
                                              :taste-profile {:trusted-sources ["PBS Frontline"]}})]
    (testing "a partial taste-profile edit merges over the stored profile"
      (is (match? {:taste-profile {:novelty-ratio   1.0
                                   :trusted-sources ["PBS Frontline"]
                                   :cadence         "weekly"}}
                  (interests/update-interest! db user-id (:id interest)
                                              {:taste-profile {:novelty-ratio 1.0}}))))
    (testing "non-owners get nil"
      (is (nil? (interests/update-interest! db "attacker@example.com" (:id interest)
                                            {:taste-profile {:novelty-ratio 0.0}}))))))

(deftest shelf-access-is-gated-by-interest-ownership-test
  (let [db       (embedded-h2/fresh-db!)
        interest (interests/create-interest! db user-id {:intent "Tech journalism"})
        [rec]    (recommendations-persistence/create-recommendations!
                  db (:id interest)
                  [{:kind "article" :title "T" :source "S" :url "u"
                    :est-time "5 min" :why "w" :origin "novel"}])]
    (testing "the owner reads the shelf; others get nil (not an empty shelf)"
      (is (= 1 (count (interests/get-shelf db user-id (:id interest) {}))))
      (is (nil? (interests/get-shelf db "attacker@example.com" (:id interest) {}))))
    (testing "status updates are gated the same way"
      (is (nil? (interests/update-recommendation-status!
                 db "attacker@example.com" (:id interest) (:id rec) "queued")))
      (is (match? {:status "queued"}
                  (interests/update-recommendation-status!
                   db user-id (:id interest) (:id rec) "queued"))))))

(deftest fold-refinement-test
  (testing "answers append to :refinements; blanks are dropped; intent untouched"
    (is (= {:intent "x" :refinements ["Only long-form" "No paywalls"]}
           (interests/fold-refinement {:intent "x" :refinements ["Only long-form"]}
                                      ["No paywalls" "" "   "]))))
  (testing "folds into a profile with no :refinements key yet"
    (is (= {:refinements ["a"]} (interests/fold-refinement {} ["a"]))))
  (testing "fold-refinement! persists the fold, scoped to owner"
    (let [db       (embedded-h2/fresh-db!)
          interest (interests/create-interest! db user-id {:intent "Tech journalism"})]
      (is (nil? (interests/fold-refinement! db "attacker@example.com" (:id interest) ["a"])))
      (is (match? {:taste-profile {:refinements ["Prefer primary sources"]}}
                  (interests/fold-refinement! db user-id (:id interest)
                                              ["Prefer primary sources"]))))))
```

- [ ] 2. Run and confirm failure:

```bash
./bin/test --focus kaleidoscope.api.interests-test
```

Expected: `ERROR` — `Could not locate kaleidoscope/api/interests__init.class ... on classpath`.

- [ ] 3. Create `src/kaleidoscope/api/interests.clj`:

```clojure
(ns kaleidoscope.api.interests
  (:require [clojure.string :as str]
            [kaleidoscope.persistence.interests :as persistence]
            [kaleidoscope.persistence.recommendations :as recommendations-persistence]))

(def default-taste-profile
  {:keywords        []
   :formats         []
   :lengths         []
   :trusted-sources []
   :novelty-ratio   0.5
   :cadence         "weekly"
   :refinements     []})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interest CRUD
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-interests
  [db user-id]
  (persistence/get-interests db user-id))

(defn get-interest
  "Return a single interest. Ownership is enforced by the persistence layer's
  WHERE clause, not a check here."
  [db user-id interest-id]
  (persistence/get-interest db interest-id user-id))

(defn create-interest!
  [db user-id {:keys [intent taste-profile]}]
  (persistence/create-interest! db {:user-id       user-id
                                    :intent        intent
                                    :taste-profile (merge default-taste-profile taste-profile)}))

(defn update-interest!
  "Update intent and/or taste profile. Taste-profile edits merge over the
  stored profile so a partial edit (e.g. just the novelty dial) never wipes
  the rest. Returns nil if not found or not owned."
  [db user-id interest-id {:keys [intent taste-profile]}]
  (when-let [interest (persistence/get-interest db interest-id user-id)]
    (persistence/update-interest! db interest-id user-id
                                  (cond-> {}
                                    intent                (assoc :intent intent)
                                    (some? taste-profile) (assoc :taste-profile
                                                                 (merge (:taste-profile interest)
                                                                        taste-profile))))))

(defn delete-interest!
  [db user-id interest-id]
  (persistence/delete-interest! db interest-id user-id))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shelf (recommendations) — always gated by interest ownership
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-shelf
  "Return the interest's recommendations (optionally filtered by status/kind).
  Returns nil — not [] — when the interest isn't owned, so the HTTP layer can
  404 instead of leaking an empty-but-real shelf."
  [db user-id interest-id filters]
  (when (persistence/get-interest db interest-id user-id)
    (recommendations-persistence/get-recommendations db interest-id filters)))

(defn update-recommendation-status!
  [db user-id interest-id recommendation-id status]
  (when (persistence/get-interest db interest-id user-id)
    (recommendations-persistence/update-recommendation-status!
     db recommendation-id interest-id status)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Refinement folding (clarify answers + check-ins)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fold-refinement
  "Fold clarify/check-in answers into a taste profile: answers append to
  :refinements. The intent stays user-owned and untouched."
  [taste-profile answers]
  (update taste-profile :refinements
          (fnil into []) (remove str/blank? answers)))

(defn fold-refinement!
  [db user-id interest-id answers]
  (when-let [interest (persistence/get-interest db interest-id user-id)]
    (persistence/update-interest! db interest-id user-id
                                  {:taste-profile (fold-refinement (:taste-profile interest)
                                                                   answers)})))
```

- [ ] 4. Run the tests again, expected PASS:

```bash
./bin/test --focus kaleidoscope.api.interests-test
```

Expected: `4 tests, 0 failures`.

- [ ] 5. Commit:

```bash
git add src/kaleidoscope/api/interests.clj test/kaleidoscope/api/interests_test.clj
git commit -m "feat(recommender): interest domain layer

- create-interest! merges the default taste profile (novelty 0.5, weekly cadence, empty allowlist)
- update-interest! merges partial taste-profile edits over the stored profile
- get-shelf/update-recommendation-status! gate child access on interest ownership, nil (not []) for non-owners
- fold-refinement folds clarify/check-in answers into :refinements, dropping blanks, leaving intent user-owned"
```

---

## Task 8: Curation orchestration — seed workflow, run, shelve (mock executor)

**Files:**
- Modify: `src/kaleidoscope/api/curation.clj` (append below the pure fns from Task 4; extend `ns` requires)
- Test: `test/kaleidoscope/api/curation_test.clj` (append)

**Interfaces:**
- Consumes: `workflows-api/advance-step!` (existing, `[db executor project-id user-id run-id output-stream]`); `workflows-persistence/create-workflow-run!`, `get-workflow-run`, `get-workflows`, `get-workflow`, `create-workflow!`, `update-workflow!`; `projects-persistence/update-project!`; `interests-persistence/get-interest`; `recommendations-persistence/archive-shelved!`, `create-recommendations!`; `agents/format-taste-profile-context`; Tasks 4–6.
- Produces:
  - `interest-curation-workflow` — 2 steps: `Refine Interest` (librarian, `clarify`) → `Discover Resources` (librarian, `text`)
  - `(seed-curation-workflow! db user-id)` and `(get-curation-workflow db user-id)`
  - `(run-curation! db executor user-id interest-id {:keys [scrutiny shelf-size]})` → `{:status "completed" :run-id ... :summary {:total :trusted :novel} :shelved [...]}` | `{:status "awaiting_input" :run-id ... :questions [...]}` | nil
  - private `advance-and-shelve!` reused by Task 9's respond path

**Steps:**

- [ ] 1. Append failing tests to `test/kaleidoscope/api/curation_test.clj` (extend the `:require` with `[clojure.string :as str]`, `[kaleidoscope.api.interests :as interests-api]`, `[kaleidoscope.persistence.interests :as interests-persistence]`, `[kaleidoscope.persistence.projects :as projects-persistence]`, `[kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]`, `[kaleidoscope.workflows.mock :as workflow-mock]`):

```clojure
(def user-id "reader@example.com")

(defn- make-interest!
  [db & {:as taste-overrides}]
  (interests-api/create-interest!
   db user-id
   {:intent        "Investigative journalism about technology and power"
    :taste-profile (merge {:trusted-sources ["PBS Frontline" "The Hill"]
                           :novelty-ratio   0.5
                           :formats         ["article" "podcast"]}
                          taste-overrides)}))

(deftest seed-curation-workflow-test
  (let [db (embedded-h2/fresh-db!)]
    (curation/seed-curation-workflow! db user-id)
    (let [wf (curation/get-curation-workflow db user-id)]
      (testing "the Interest Curation workflow is live with clarify → discover steps"
        (is (match? {:name   "Interest Curation"
                     :status "live"
                     :steps  [{:name "Refine Interest" :agent-type "librarian" :output-kind "clarify"}
                              {:name "Discover Resources" :agent-type "librarian" :output-kind "text"}]}
                    wf)))
      (testing "re-seeding is idempotent"
        (curation/seed-curation-workflow! db user-id)
        (is (= (:id wf) (:id (curation/get-curation-workflow db user-id))))))))

(deftest run-curation-shelves-with-novelty-split-test
  (let [db       (embedded-h2/fresh-db!)
        executor (workflow-mock/make-mock-executor)
        interest (make-interest! db)
        result   (curation/run-curation! db executor user-id (:id interest) {})]
    (testing "the run completes and reports the finite shelf's composition"
      (is (match? {:status  "completed"
                   :summary {:total 6 :trusted 3 :novel 3}}
                  result)))
    (testing "shelved cards persist with a why rationale and origin tags"
      (let [shelf (interests-api/get-shelf db user-id (:id interest) {:status "shelved"})]
        (is (= 6 (count shelf)))
        (is (every? (comp seq :why) shelf))
        (is (= 3 (count (filter #(= "trusted" (:origin %)) shelf))))
        (is (= 3 (count (filter #(= "novel" (:origin %)) shelf))))
        (is (every? #(contains? #{"PBS Frontline" "The Hill"} (:source %))
                    (filter #(= "trusted" (:origin %)) shelf)))))
    (testing "below-threshold candidates are dropped (Noise Weekly scores 2.0 in the mock pool)"
      (is (empty? (filter #(= "Noise Weekly" (:source %))
                          (interests-api/get-shelf db user-id (:id interest) {})))))
    (testing "the backing project description carries the taste-profile context for LLM prompts"
      (is (str/includes?
           (:description (projects-persistence/get-project db (:project-id interest) user-id))
           "<taste_profile>")))))

(deftest run-curation-ownership-test
  (let [db       (embedded-h2/fresh-db!)
        executor (workflow-mock/make-mock-executor)
        interest (make-interest! db)]
    (testing "a non-owner cannot curate someone else's interest"
      (is (nil? (curation/run-curation! db executor "attacker@example.com" (:id interest) {}))))))

(deftest re-curation-replaces-the-shelf-test
  (let [db       (embedded-h2/fresh-db!)
        executor (workflow-mock/make-mock-executor)
        interest (make-interest! db)]
    (curation/run-curation! db executor user-id (:id interest) {})
    (curation/run-curation! db executor user-id (:id interest) {})
    (testing "the shelf stays finite: the previous run's items are archived, not accumulated"
      (is (= 6 (count (interests-api/get-shelf db user-id (:id interest) {:status "shelved"}))))
      (is (= 6 (count (interests-api/get-shelf db user-id (:id interest) {:status "archived"})))))))

(deftest run-curation-respects-shelf-size-and-scrutiny-test
  (let [db       (embedded-h2/fresh-db!)
        executor (workflow-mock/make-mock-executor)
        interest (make-interest! db)
        result   (curation/run-curation! db executor user-id (:id interest)
                                         {:shelf-size 4 :scrutiny "rigorous"})]
    (testing "shelf size is honored and rigorous threshold (7.0) drops the 6.x novel candidates"
      (is (match? {:summary {:total 4 :trusted 2 :novel 2}} result))
      (is (every? #(not (contains? #{"Asterisk Magazine" "The Browser" "Noise Weekly"} (:source %)))
                  (interests-api/get-shelf db user-id (:id interest) {:status "shelved"}))))))
```

- [ ] 2. Run and confirm failure:

```bash
./bin/test --focus kaleidoscope.api.curation-test
```

Expected: `ERROR` — `No such var: curation/seed-curation-workflow!`.

- [ ] 3. Extend the `ns` form of `src/kaleidoscope/api/curation.clj` to:

```clojure
(ns kaleidoscope.api.curation
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [kaleidoscope.api.workflows :as workflows-api]
            [kaleidoscope.persistence.interests :as interests-persistence]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.recommendations :as recommendations-persistence]
            [kaleidoscope.persistence.workflows :as workflows-persistence]
            [kaleidoscope.scoring.agents :as agents]
            [kaleidoscope.utils.core :as utils]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [taoensso.timbre :as log]))
```

- [ ] 4. Append the workflow definition + seeding below the pure fns:

```clojure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interest Curation workflow (seeded lazily, like the default workflows)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def interest-curation-workflow
  {:name        "Interest Curation"
   :description (str "Refine an interest's taste profile if it is too thin, then have the "
                     "Librarian discover and relevance-score candidate resources for its shelf.")
   :is-default  false
   :steps       [{:name        "Refine Interest"
                  :description (str "If the interest's intent is too thin to curate well, ask 1-2 "
                                    "targeted refinement questions. Answers are folded into the "
                                    "taste profile before discovery runs.")
                  :position    0
                  :agent-type  "librarian"
                  :output-kind "clarify"}
                 {:name        "Discover Resources"
                  :description (str "Propose candidate resources across media kinds, honoring the "
                                    "trusted-source allowlist and the novelty dial in the "
                                    "<taste_profile> block. Score each candidate's relevance to the "
                                    "taste profile (0-10) and give each a one-line why.")
                  :position    1
                  :agent-type  "librarian"
                  :output-kind "text"}]})

(defn get-curation-workflow
  [db user-id]
  (when-let [wf (->> (workflows-persistence/get-workflows db user-id)
                     (filter #(= "Interest Curation" (:name %)))
                     first)]
    (workflows-persistence/get-workflow db (:id wf) user-id)))

(defn seed-curation-workflow!
  "Create the Interest Curation workflow for a user if missing, or reconcile
  its steps if the code definition drifted — same lazy-seed pattern as
  api.workflows/seed-default-workflows!."
  [db user-id]
  (if-let [existing (get-curation-workflow db user-id)]
    (let [signature #(select-keys % [:name :position :agent-type :output-kind])
          drifted?  (not= (mapv signature (sort-by :position (:steps existing)))
                          (mapv signature (:steps interest-curation-workflow)))]
      (when drifted?
        (log/infof "Reconciling Interest Curation workflow for user %s" user-id)
        (workflows-persistence/update-workflow! db (:id existing) user-id
                                                {:steps (:steps interest-curation-workflow)}))
      (get-curation-workflow db user-id))
    (do (log/infof "Seeding Interest Curation workflow for user %s" user-id)
        (workflows-persistence/create-workflow! db
                                                (assoc interest-curation-workflow
                                                       :user-id user-id
                                                       :status "live")))))
```

- [ ] 5. Append the orchestration:

```clojure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Curation runs: discover → score (threshold) → shelve
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- sync-backing-project!
  "Write intent + taste-profile context into the backing project description
  so the planner (clarify) and Librarian (discover) prompts see the current
  profile. The mock executor reads the interests row directly instead."
  [db {:keys [project-id user-id intent taste-profile]}]
  (projects-persistence/update-project!
   db project-id user-id
   {:description (str intent "\n\n" (agents/format-taste-profile-context taste-profile))}))

(defn- discover-output
  [run]
  (->> (:steps run)
       (filter #(= "Discover Resources" (:name %)))
       first
       :output))

(defn- pending-questions
  [run]
  (when-let [paused (->> (:steps run)
                         (filter #(= "awaiting_input" (:status %)))
                         first)]
    (let [reply (:reply (try (json/decode (:output paused) true)
                             (catch Exception _ nil)))]
      (if reply [reply] []))))

(defn- shelve!
  "The deterministic score→shelve tail of a curation run: parse the Discover
  output, drop below-threshold candidates, tag + split by the novelty dial,
  archive the old shelf, and persist the new one."
  [db interest run]
  (let [taste      (:taste-profile interest)
        config     (:config run)
        threshold  (or (:relevance-threshold config) 6.0)
        shelf-size (or (:shelf-size config) default-shelf-size)
        selected   (-> (parse-candidates (discover-output run))
                       (drop-below-threshold threshold)
                       (tag-origin (:trusted-sources taste))
                       (split-candidates (novelty-quota shelf-size (:novelty-ratio taste))))
        _          (recommendations-persistence/archive-shelved! db (:id interest))
        shelved    (recommendations-persistence/create-recommendations! db (:id interest) selected)]
    {:status  "completed"
     :run-id  (:id run)
     :shelved shelved
     :summary {:total   (count shelved)
               :trusted (count (filter #(= "trusted" (:origin %)) shelved))
               :novel   (count (filter #(= "novel" (:origin %)) shelved))}}))

(defn- advance-and-shelve!
  "Advance the curation run to completion (or a clarify pause) and shelve the
  results. Shared by run-curation! and respond-to-curation-step!."
  [db executor user-id interest-id run-id]
  (let [interest (interests-persistence/get-interest db interest-id user-id)
        run      (workflows-api/advance-step! db executor (:project-id interest)
                                              user-id run-id
                                              (java.io.ByteArrayOutputStream.))]
    (cond
      (:error run)
      {:status "error" :error (:error run)}

      (= "awaiting_input" (:status run))
      {:status    "awaiting_input"
       :run-id    run-id
       :questions (pending-questions run)}

      :else
      (shelve! db interest (workflows-persistence/get-workflow-run db run-id)))))

(defn run-curation!
  "Run the Interest Curation workflow for an interest and refresh its shelf.
  Returns the shelf summary, an awaiting_input pause (clarify questions), or
  nil when the interest isn't owned by user-id."
  [db executor user-id interest-id {:keys [scrutiny shelf-size]}]
  (span/with-span! {:name "kaleidoscope.curation.run"}
    (when-let [interest (interests-persistence/get-interest db interest-id user-id)]
      (seed-curation-workflow! db user-id)
      (sync-backing-project! db interest)
      (let [wf     (get-curation-workflow db user-id)
            config (assoc (relevance-config scrutiny)
                          :shelf-size (or shelf-size default-shelf-size))
            run    (workflows-persistence/create-workflow-run!
                    db (:project-id interest) (:id wf) "autonomous" config)]
        (advance-and-shelve! db executor user-id interest-id (:id run))))))
```

- [ ] 6. Run the tests again, expected PASS:

```bash
./bin/test --focus kaleidoscope.api.curation-test
```

Expected: `11 tests, 0 failures` (6 pure + 5 orchestration).

- [ ] 7. Commit:

```bash
git add src/kaleidoscope/api/curation.clj test/kaleidoscope/api/curation_test.clj
git commit -m "feat(recommender): curation orchestration on the workflow engine

- Interest Curation workflow (Refine=clarify -> Discover=librarian text), lazily seeded/reconciled per user like the default workflows
- run-curation! creates an autonomous run on the interest's backing project and drives it via workflows-api/advance-step!
- shelve! applies the relevance threshold (scrutiny ladder) then the novelty-quota split, archives the old shelf, persists the new one with why + origin
- sync-backing-project! writes intent + <taste_profile> into the backing project description so LLM prompts see the profile
- clarify pauses surface as {:status awaiting_input :questions [...]}"
```

---

## Task 9: Clarify-answer folding and taste-profile retuning

**Files:**
- Modify: `src/kaleidoscope/api/curation.clj` (append `respond-to-curation-step!`; add `[kaleidoscope.api.interests :as interests-api]` to requires)
- Test: `test/kaleidoscope/api/curation_test.clj` (append)

**Interfaces:**
- Consumes: Task 7 `interests-api/fold-refinement!`; Task 8 `advance-and-shelve!`, `seed-curation-workflow!`, `get-curation-workflow`, `sync-backing-project!`; `workflows-persistence/get-workflow-run`, `get-step-run`, `update-step-run!`, `update-workflow-run!`, `create-workflow-run!`.
- Produces:
  - `(respond-to-curation-step! db executor user-id interest-id run-id step-run-id answers)` → same result shapes as `run-curation!` | nil when any ownership/linkage/status check fails.

**Steps:**

- [ ] 1. Append failing tests to `test/kaleidoscope/api/curation_test.clj` (extend `:require` with `[cheshire.core :as json]` — already present — and `[kaleidoscope.persistence.workflows :as workflows-persistence]`):

```clojure
(defn- paused-curation-run!
  "Create a curation run whose clarify step is awaiting input. The mock
  planner always reports ready, so pause the step manually to exercise the
  respond path deterministically."
  [db interest]
  (curation/seed-curation-workflow! db user-id)
  (let [wf   (curation/get-curation-workflow db user-id)
        run  (workflows-persistence/create-workflow-run!
              db (:project-id interest) (:id wf) "autonomous"
              (assoc (curation/relevance-config nil) :shelf-size 6))
        step (->> (:steps run) (filter #(= "Refine Interest" (:name %))) first)]
    (workflows-persistence/update-step-run!
     db (:id step) {:status "awaiting_input"
                    :output (json/encode {:reply "Which beats matter most to you?"})})
    (workflows-persistence/update-workflow-run! db (:id run) {:status "awaiting_input"})
    {:run run :step step}))

(deftest respond-to-curation-step-folds-answers-and-resumes-test
  (let [db         (embedded-h2/fresh-db!)
        executor   (workflow-mock/make-mock-executor)
        interest   (make-interest! db)
        {:keys [run step]} (paused-curation-run! db interest)
        result     (curation/respond-to-curation-step!
                    db executor user-id (:id interest) (:id run) (:id step)
                    ["Prefer primary reporting over commentary"])]
    (testing "the answer is folded into the taste profile's refinements"
      (is (match? {:taste-profile {:refinements ["Prefer primary reporting over commentary"]}}
                  (interests-api/get-interest db user-id (:id interest)))))
    (testing "the run resumes through discovery and shelves synchronously"
      (is (match? {:status "completed" :summary {:total 6}} result)))
    (testing "the clarify step is completed, not re-runnable"
      (is (match? {:status "completed"}
                  (workflows-persistence/get-step-run db (:id step)))))))

(deftest respond-to-curation-step-guards-test
  (let [db       (embedded-h2/fresh-db!)
        executor (workflow-mock/make-mock-executor)
        interest (make-interest! db)
        {:keys [run step]} (paused-curation-run! db interest)]
    (testing "non-owners get nil"
      (is (nil? (curation/respond-to-curation-step!
                 db executor "attacker@example.com" (:id interest) (:id run) (:id step) ["x"]))))
    (testing "a run that doesn't belong to the interest gets nil"
      (let [other (make-interest! db)]
        (is (nil? (curation/respond-to-curation-step!
                   db executor user-id (:id other) (:id run) (:id step) ["x"])))))
    (testing "a step that isn't awaiting input gets nil"
      (workflows-persistence/update-step-run! db (:id step) {:status "completed"})
      (is (nil? (curation/respond-to-curation-step!
                 db executor user-id (:id interest) (:id run) (:id step) ["x"]))))))

(deftest taste-profile-retune-changes-composition-test
  (let [db       (embedded-h2/fresh-db!)
        executor (workflow-mock/make-mock-executor)
        interest (make-interest! db)]
    (testing "novelty 1.0 — the next shelf is entirely novel"
      (interests-api/update-interest! db user-id (:id interest)
                                      {:taste-profile {:novelty-ratio 1.0}})
      (is (match? {:summary {:total 6 :trusted 0 :novel 6}}
                  (curation/run-curation! db executor user-id (:id interest) {}))))
    (testing "novelty 0.0 — the next shelf is entirely trusted"
      (interests-api/update-interest! db user-id (:id interest)
                                      {:taste-profile {:novelty-ratio 0.0}})
      (is (match? {:summary {:total 6 :trusted 6 :novel 0}}
                  (curation/run-curation! db executor user-id (:id interest) {}))))
    (testing "shrinking the allowlist changes what counts as trusted"
      (interests-api/update-interest! db user-id (:id interest)
                                      {:taste-profile {:novelty-ratio   0.0
                                                       :trusted-sources ["The Hill"]}})
      (let [result (curation/run-curation! db executor user-id (:id interest) {})]
        ;; only 3 mock candidates come from the single trusted source; the
        ;; rest of the shelf backfills from novel candidates
        (is (match? {:summary {:total 6 :trusted 3 :novel 3}} result))))))
```

- [ ] 2. Run and confirm failure:

```bash
./bin/test --focus kaleidoscope.api.curation-test
```

Expected: `ERROR` — `No such var: curation/respond-to-curation-step!`.

- [ ] 3. Add `[kaleidoscope.api.interests :as interests-api]` to the `ns` requires of `src/kaleidoscope/api/curation.clj`, then append:

```clojure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Clarify answers: fold into the taste profile, then resume
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn respond-to-curation-step!
  "Handle a user's answers to a curation clarify step. Unlike the generic
  workflows respond path (which appends answers to project.description and
  resumes in a background future), this folds the answers into the interest's
  editable taste profile, re-syncs the backing project, and resumes the run
  synchronously through to shelving so the caller gets the shelf summary.
  Returns nil unless the interest is owned, the run belongs to the interest's
  backing project, and the step is awaiting input on that run."
  [db executor user-id interest-id run-id step-run-id answers]
  (span/with-span! {:name "kaleidoscope.curation.respond"}
    (when-let [interest (interests-persistence/get-interest db interest-id user-id)]
      (when-let [run (workflows-persistence/get-workflow-run db run-id)]
        (when (= (:project-id run) (:project-id interest))
          (let [step-run (workflows-persistence/get-step-run db step-run-id)]
            (when (and step-run
                       (= run-id (:workflow-run-id step-run))
                       (= "awaiting_input" (:status step-run)))
              (interests-api/fold-refinement! db user-id interest-id answers)
              (sync-backing-project! db (interests-persistence/get-interest db interest-id user-id))
              (workflows-persistence/update-step-run! db step-run-id
                                                      {:status       "completed"
                                                       :completed-at (utils/now)})
              (workflows-persistence/update-workflow-run! db run-id
                                                          {:status       "in_progress"
                                                           :current-step (inc (:current-step run))})
              (advance-and-shelve! db executor user-id interest-id run-id))))))))
```

- [ ] 4. Run the tests again, expected PASS:

```bash
./bin/test --focus kaleidoscope.api.curation-test
```

Expected: `14 tests, 0 failures`.

- [ ] 5. Commit:

```bash
git add src/kaleidoscope/api/curation.clj test/kaleidoscope/api/curation_test.clj
git commit -m "feat(recommender): clarify folding and taste-profile retuning

- respond-to-curation-step! folds answers into taste-profile refinements, re-syncs the backing project, completes the paused step, and resumes synchronously to shelving
- guards: interest ownership, run belongs to the interest's backing project, step awaiting_input on that run — nil otherwise
- tests prove retuning works: novelty 1.0 -> all novel, 0.0 -> all trusted, shrinking the allowlist changes composition with novel backfill"
```

---

## Task 10: HTTP routes + Malli schemas

**Files:**
- Create: `src/kaleidoscope/http_api/interests.clj`
- Modify: `src/kaleidoscope/http_api/kaleidoscope.clj` — add the require (in the require block, lines 12–26), the ACL entry (after the `#"^/workspace-roots.*"` line, ~line 48), and `reitit-interests-routes` in the routes vector (after `reitit-workspace-roots-routes`, ~line 238)
- Test: `test/kaleidoscope/http_api/interests_test.clj`

**Interfaces:**
- Consumes: Task 7 `interests-api/*`; Task 8/9 `curation/run-curation!`, `curation/respond-to-curation-step!`; components `:database` and `:workflow-executor`.
- Produces: `reitit-interests-routes` mounted at `/interests`; schemas `TasteProfile`, `InterestRequest`, `InterestUpdateRequest`, `CurationRequest`, `RecommendationStatusRequest`, `RespondRequest`.

**Steps:**

- [ ] 1. Write the failing tests at `test/kaleidoscope/http_api/interests_test.clj`:

```clojure
(ns kaleidoscope.http-api.interests-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.http-api.interests :refer [reitit-interests-routes]]
            [kaleidoscope.http-api.kaleidoscope :as kal]
            [kaleidoscope.http-api.middleware :as mw]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.test-utils :as tu]
            [kaleidoscope.workflows.mock :as workflow-mock]
            [matcher-combinators.test :refer [match?]]
            [reitit.ring :as ring]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

;; wrap-clojure-response parses JSON bodies back to keywordized maps so the
;; tests below can assert on body content, not just status.
(defn- test-app
  [components]
  (let [config (update-in mw/reitit-configuration
                          [:data :middleware]
                          (fn [middleware] (concat middleware [(kal/inject-components components)])))]
    (tu/wrap-clojure-response
     (ring/ring-handler
      (ring/router [reitit-interests-routes] config)))))

(def user-id "reader@example.com")

(defn- as-user
  [request]
  (assoc request :identity {:user-id user-id}))

;; Coercion-failure and rate-limit paths — both run in middleware ahead of the
;; handler, so none of these trigger a Claude call.
(deftest interest-validation-test
  (let [app (test-app {:database (embedded-h2/fresh-db!)})]
    (mw/reset-rate-limits!)
    (testing "Missing intent is rejected"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/interests")
                           (mock/json-body {}))))))
    (testing "Oversized intent is rejected before it can inflate a prompt"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/interests")
                           (mock/json-body {:intent (apply str (repeat 5001 "a"))}))))))
    (testing "A novelty ratio outside 0.0-1.0 is rejected — the dial is a user control with hard bounds"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/interests")
                           (mock/json-body {:intent        "ok"
                                            :taste-profile {:novelty-ratio 1.5}}))))))
    (testing "An unrecognized format is rejected"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/interests")
                           (mock/json-body {:intent        "ok"
                                            :taste-profile {:formats ["doomscroll"]}}))))))
    (testing "A malformed interest-id path segment is rejected by coercion"
      (is (match? {:status 400}
                  (app (mock/request :get "/interests/not-a-uuid")))))))

(deftest curate-rate-limit-test
  (let [app     (test-app {:database (embedded-h2/fresh-db!)})
        request #(-> (mock/request :post (str "/interests/" (random-uuid) "/curate"))
                     (mock/json-body {}))]
    (mw/reset-rate-limits!)
    (dotimes [_ 5]
      (app (request)))
    (testing "6th curate request from the same IP within the window is rate limited"
      (is (match? {:status 429}
                  (app (request)))))))

(deftest interest-crud-http-test
  (let [db  (embedded-h2/fresh-db!)
        app (test-app {:database db :workflow-executor (workflow-mock/make-mock-executor)})]
    (mw/reset-rate-limits!)
    (let [created (app (-> (mock/request :post "/interests")
                           (mock/json-body {:intent "Modern jazz history"})
                           as-user))]
      (testing "POST /interests creates an interest with the default taste profile"
        (is (match? {:status 200
                     :body   {:intent        "Modern jazz history"
                              :taste-profile {:novelty-ratio 0.5}}}
                    created)))
      (let [interest-id (get-in created [:body :id])]
        (testing "GET /interests lists it; GET by id returns it; both scoped to the identity"
          (is (match? {:status 200 :body [{:intent "Modern jazz history"}]}
                      (app (as-user (mock/request :get "/interests")))))
          (is (match? {:status 200 :body {:intent "Modern jazz history"}}
                      (app (as-user (mock/request :get (str "/interests/" interest-id))))))
          (is (match? {:status 404}
                      (app (-> (mock/request :get (str "/interests/" interest-id))
                               (assoc :identity {:user-id "attacker@example.com"}))))))
        (testing "PUT /interests/:id merges a taste-profile edit"
          (is (match? {:status 200 :body {:taste-profile {:novelty-ratio 0.8 :cadence "weekly"}}}
                      (app (-> (mock/request :put (str "/interests/" interest-id))
                               (mock/json-body {:taste-profile {:novelty-ratio 0.8}})
                               as-user)))))
        (testing "DELETE /interests/:id removes it"
          (is (match? {:status 204}
                      (app (as-user (mock/request :delete (str "/interests/" interest-id))))))
          (is (match? {:status 404}
                      (app (as-user (mock/request :get (str "/interests/" interest-id)))))))))))
```

- [ ] 2. Run and confirm failure:

```bash
./bin/test --focus kaleidoscope.http-api.interests-test
```

Expected: `ERROR` — `Could not locate kaleidoscope/http_api/interests__init.class ... on classpath`.

- [ ] 3. Create `src/kaleidoscope/http_api/interests.clj`:

```clojure
(ns kaleidoscope.http-api.interests
  (:require [kaleidoscope.api.curation :as curation]
            [kaleidoscope.api.interests :as interests-api]
            [ring.util.http-response :refer [not-found ok]]))

(def TasteProfile
  [:map
   [:keywords {:optional true} [:vector {:max 50} [:string {:max 100}]]]
   [:formats {:optional true} [:vector {:max 8}
                               [:enum "podcast" "article" "show" "video"
                                "book" "paper" "newsletter" "course"]]]
   [:lengths {:optional true} [:vector {:max 10} [:string {:max 100}]]]
   [:trusted-sources {:optional true} [:vector {:max 50} [:string {:max 200}]]]
   ;; The explore/exploit dial is a *user* control with hard bounds — reject,
   ;; don't clamp, at the boundary so a bad client can't silently mis-set it.
   [:novelty-ratio {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:cadence {:optional true} [:string {:max 50}]]
   [:refinements {:optional true} [:vector {:max 50} [:string {:max 2000}]]]])

(def InterestRequest
  [:map
   [:intent [:string {:min 1 :max 5000}]]
   [:taste-profile {:optional true} TasteProfile]])

(def InterestUpdateRequest
  [:map
   [:intent {:optional true} [:string {:min 1 :max 5000}]]
   [:taste-profile {:optional true} TasteProfile]])

(def CurationRequest
  [:map
   [:scrutiny {:optional true} [:enum "quick" "standard" "rigorous"]]
   [:shelf-size {:optional true} [:int {:min 1 :max 24}]]])

(def RecommendationStatusRequest
  [:map
   [:status [:enum "shelved" "queued" "archived"]]])

(def RespondRequest
  [:map
   [:answers [:vector {:max 5} [:string {:max 2000}]]]])

(def reitit-interests-routes
  ["/interests"
   {:tags     ["interests"]
    :security [{:andrewslai-pkce ["roles" "profile"]}]}

   ["" {:get {:summary "List interests for the authenticated user"
              :handler (fn [{:keys [components] :as request}]
                         (ok (interests-api/get-interests (:database components)
                                                          (:user-id (:identity request)))))}

        :post {:summary    "Create an interest (free-text intent + optional taste profile)"
               :rate-limit {:max-requests 10 :window-ms 60000}
               :parameters {:body InterestRequest}
               :handler    (fn [{:keys [components parameters] :as request}]
                             (ok (interests-api/create-interest!
                                  (:database components)
                                  (:user-id (:identity request))
                                  (:body parameters))))}}]

   ["/:interest-id"
    {:parameters {:path {:interest-id :uuid}}}

    ["" {:get {:summary "Get an interest with its taste profile"
               :handler (fn [{:keys [components parameters] :as request}]
                          (let [user-id     (:user-id (:identity request))
                                interest-id (:interest-id (:path parameters))]
                            (if-let [interest (interests-api/get-interest
                                               (:database components) user-id interest-id)]
                              (ok interest)
                              (not-found {:reason "Interest not found"}))))}

         :put {:summary    "Update intent and/or taste profile (partial edits merge)"
               :parameters {:body InterestUpdateRequest}
               :handler    (fn [{:keys [components parameters] :as request}]
                             (let [user-id     (:user-id (:identity request))
                                   interest-id (:interest-id (:path parameters))]
                               (if-let [interest (interests-api/update-interest!
                                                  (:database components) user-id interest-id
                                                  (:body parameters))]
                                 (ok interest)
                                 (not-found {:reason "Interest not found"}))))}

         :delete {:summary "Delete an interest (its shelf and curation runs go with it)"
                  :handler (fn [{:keys [components parameters] :as request}]
                             (let [user-id     (:user-id (:identity request))
                                   interest-id (:interest-id (:path parameters))]
                               (if (interests-api/delete-interest!
                                    (:database components) user-id interest-id)
                                 {:status 204}
                                 (not-found {:reason "Interest not found"}))))}}]

    ["/recommendations"
     ["" {:get {:summary    "Read the interest's shelf (optionally filtered by status/kind)"
                :parameters {:query [:map
                                     [:status {:optional true} [:enum "shelved" "queued" "archived"]]
                                     [:kind {:optional true} [:string {:max 50}]]]}
                :handler    (fn [{:keys [components parameters] :as request}]
                              (let [user-id     (:user-id (:identity request))
                                    interest-id (:interest-id (:path parameters))]
                                (if-let [shelf (interests-api/get-shelf
                                                (:database components) user-id interest-id
                                                (:query parameters))]
                                  (ok shelf)
                                  (not-found {:reason "Interest not found"}))))}}]

     ["/:recommendation-id"
      {:parameters {:path {:interest-id :uuid :recommendation-id :uuid}}
       :put {:summary    "Update a recommendation's status (shelve / queue / archive)"
             :parameters {:body RecommendationStatusRequest}
             :handler    (fn [{:keys [components parameters] :as request}]
                           (let [user-id           (:user-id (:identity request))
                                 interest-id       (:interest-id (:path parameters))
                                 recommendation-id (:recommendation-id (:path parameters))]
                             (if-let [rec (interests-api/update-recommendation-status!
                                           (:database components) user-id interest-id
                                           recommendation-id (:status (:body parameters)))]
                               (ok rec)
                               (not-found {:reason "Recommendation not found"}))))}}]]

    ["/curate"
     {:post {:summary    "Run the curation workflow and refresh this interest's shelf"
             ;; One curation run is at least one Claude call in production —
             ;; rate limited like the other LLM-triggering routes.
             :rate-limit {:max-requests 5 :window-ms 60000}
             :parameters {:body CurationRequest}
             :handler    (fn [{:keys [components parameters] :as request}]
                           (let [user-id     (:user-id (:identity request))
                                 interest-id (:interest-id (:path parameters))]
                             (if-let [result (curation/run-curation!
                                              (:database components)
                                              (:workflow-executor components)
                                              user-id interest-id
                                              (:body parameters))]
                               (ok result)
                               (not-found {:reason "Interest not found"}))))}}]

    ["/curation-runs/:run-id/steps/:step-run-id/respond"
     {:parameters {:path {:interest-id :uuid :run-id :uuid :step-run-id :uuid}}
      :post {:summary    "Answer refinement questions; folds answers into the taste profile and resumes curation"
             :rate-limit {:max-requests 10 :window-ms 60000}
             :parameters {:body RespondRequest}
             :handler    (fn [{:keys [components parameters] :as request}]
                           (let [user-id     (:user-id (:identity request))
                                 {:keys [interest-id run-id step-run-id]} (:path parameters)]
                             (if-let [result (curation/respond-to-curation-step!
                                              (:database components)
                                              (:workflow-executor components)
                                              user-id interest-id run-id step-run-id
                                              (:answers (:body parameters)))]
                               (ok result)
                               (not-found {:reason "Run or step not found, or step not awaiting input"}))))}}]]])
```

- [ ] 4. Wire into `src/kaleidoscope/http_api/kaleidoscope.clj`:
  - Add to the require block (after the `kaleidoscope.http-api.workflows` line):

```clojure
   [kaleidoscope.http-api.interests :refer [reitit-interests-routes]]
```

  - Add to `KALEIDOSCOPE-ACCESS-CONTROL-LIST` (after the `#"^/workspace-roots.*"` entry):

```clojure
   {:pattern #"^/interests.*"         :handler auth/require-*-writer}
```

  - Add to the routes vector (after `reitit-workspace-roots-routes`):

```clojure
        reitit-interests-routes
```

- [ ] 5. Run the tests again, expected PASS, plus the router smoke test:

```bash
./bin/test --focus kaleidoscope.http-api.interests-test
./bin/test --focus kaleidoscope.http-api.kaleidoscope-test
```

Expected: `3 tests, 0 failures` for interests; existing kaleidoscope router tests still `0 failures` (proves the new routes don't break router construction or the fail-closed ACL).

- [ ] 6. Commit:

```bash
git add src/kaleidoscope/http_api/interests.clj src/kaleidoscope/http_api/kaleidoscope.clj test/kaleidoscope/http_api/interests_test.clj
git commit -m "feat(recommender): /interests HTTP routes with Malli boundary validation

- CRUD for interests, shelf reads with status/kind filters, recommendation status updates
- POST /interests/:id/curate runs curation (rate limited 5/min like other LLM-triggering routes)
- respond route folds refinement answers and resumes curation
- TasteProfile schema hard-bounds novelty-ratio to [0.0, 1.0] and formats to the 8 media kinds — reject, don't clamp
- mounted in kaleidoscope-app + writer-role ACL entry (fail-closed list)"
```

---

## Task 11: End-to-end curation loop test

**Files:**
- Test: `test/kaleidoscope/http_api/interests_test.clj` (append)

**Interfaces:**
- Consumes: everything above; no new production code. If any assertion fails here, fix the referenced task's code — do not weaken the test.

**Steps:**

- [ ] 1. Append the end-to-end test to `test/kaleidoscope/http_api/interests_test.clj`:

```clojure
;; The full design loop over HTTP with the mock executor: onboard an interest,
;; curate, read the shelf, retune the novelty dial, re-curate, act on a card.
(deftest personal-recommender-end-to-end-test
  (let [db  (embedded-h2/fresh-db!)
        app (test-app {:database db :workflow-executor (workflow-mock/make-mock-executor)})]
    (mw/reset-rate-limits!)
    (let [interest-id
          (get-in (app (-> (mock/request :post "/interests")
                           (mock/json-body {:intent        "Investigative journalism about technology and power"
                                            :taste-profile {:trusted-sources ["PBS Frontline" "The Hill"]
                                                            :novelty-ratio   0.5
                                                            :formats         ["article" "podcast"]}})
                           as-user))
                  [:body :id])]

      (testing "curating fills a finite shelf split by the novelty dial"
        (is (match? {:status 200
                     :body   {:status  "completed"
                              :summary {:total 6 :trusted 3 :novel 3}}}
                    (app (-> (mock/request :post (str "/interests/" interest-id "/curate"))
                             (mock/json-body {})
                             as-user)))))

      (testing "every shelf card carries a why rationale and a trust/novel origin tag"
        (let [{:keys [body]} (app (as-user (mock/request
                                            :get (str "/interests/" interest-id
                                                      "/recommendations?status=shelved"))))]
          (is (= 6 (count body)))
          (is (every? #(and (seq (:why %)) (contains? #{"trusted" "novel"} (:origin %))) body))))

      (testing "the shelf filters by media kind"
        (let [{:keys [body]} (app (as-user (mock/request
                                            :get (str "/interests/" interest-id
                                                      "/recommendations?status=shelved&kind=article"))))]
          (is (seq body))
          (is (every? #(= "article" (:kind %)) body))))

      (testing "retuning the novelty dial to 1.0 makes the next shelf entirely novel"
        (app (-> (mock/request :put (str "/interests/" interest-id))
                 (mock/json-body {:taste-profile {:novelty-ratio 1.0}})
                 as-user))
        (is (match? {:status 200
                     :body   {:summary {:total 6 :trusted 0 :novel 6}}}
                    (app (-> (mock/request :post (str "/interests/" interest-id "/curate"))
                             (mock/json-body {})
                             as-user)))))

      (testing "re-curation replaced the shelf instead of growing it (finite by principle)"
        (is (= 6 (count (:body (app (as-user (mock/request
                                              :get (str "/interests/" interest-id
                                                        "/recommendations?status=shelved")))))))))

      (testing "the reader can queue a card off the shelf"
        (let [rec-id (:id (first (:body (app (as-user (mock/request
                                                       :get (str "/interests/" interest-id
                                                                 "/recommendations?status=shelved")))))))]
          (is (match? {:status 200 :body {:status "queued"}}
                      (app (-> (mock/request :put (str "/interests/" interest-id
                                                       "/recommendations/" rec-id))
                               (mock/json-body {:status "queued"})
                               as-user))))))

      (testing "responding to a step that isn't awaiting input is a 404, not a mutation"
        (is (match? {:status 404}
                    (app (-> (mock/request :post (format "/interests/%s/curation-runs/%s/steps/%s/respond"
                                                         interest-id (random-uuid) (random-uuid)))
                             (mock/json-body {:answers ["x"]})
                             as-user))))))))
```

- [ ] 2. Run the new test — it should PASS immediately if Tasks 1–10 are correct; any failure points at a real integration bug:

```bash
./bin/test --focus kaleidoscope.http-api.interests-test
```

Expected: `4 tests, 0 failures`.

- [ ] 3. Run the full suite to prove nothing regressed:

```bash
task test:summary
```

Expected: no `FAIL`/`ERROR` lines.

- [ ] 4. Commit:

```bash
git add test/kaleidoscope/http_api/interests_test.clj
git commit -m "test(recommender): end-to-end curation loop over HTTP

- onboarding -> curate -> 6-card shelf split 3 trusted / 3 novel per the dial
- every card carries a why rationale and origin tag; shelf filters by kind
- retune to novelty 1.0 -> next shelf all novel; re-curation replaces, never grows, the shelf
- queueing a card and the respond-guard 404 path covered
- full suite green via task test:summary"
```

---

## Design-requirement → task traceability (self-review)

| DESIGN.md requirement | Task(s) |
|---|---|
| `interests` + `recommendations` tables via Migratus, exact columns (kind/source/url/est_time/why/origin/status, taste_profile JSONB) | 1 |
| Taste profile: intent, keywords, formats, lengths, trusted_sources, novelty_ratio, cadence — user-editable | 1, 2, 7, 10 (`TasteProfile` schema; `refinements` added for check-in folds) |
| Interest ≈ Project; curation reuses `workflow_runs`/`step_runs`, no new run tables | 1 (backing `project_id`), 2, 8 |
| Curation as a Workflow; clarify step reuse; Librarian persona (📚) in `scoring/agents.clj` | 5, 8 |
| Relevance filtering with `quick`/`standard`/`rigorous` threshold config; below-threshold dropped | 4 (`relevance-config`, `drop-below-threshold`), 8, and tests in 8/11 |
| Novelty ratio (explore/exploit): trusted quota filled first, novel fills remainder, user-set 0.0–1.0, edge-tested | 4 (unit tests at 0.0/1.0/midpoints), 9, 11 |
| Novel items visibly tagged (`origin`), decided by allowlist membership, never by the LLM | 4 (`tag-origin`), 8 |
| Every item carries a one-line "why this is here" | 5 (prompt contract), 6 (mock), 8/11 (asserted non-empty) |
| Mock data in prototype/dev, LLM executor in prod | 6 (mock branch), 5+8 (prod path via `get-system-prompt` + synced project description, zero llm-executor changes) |
| Shelves are finite; curation replaces rather than grows | 3 (`archive-shelved!`), 8, 11 |
| Clarify answers folded into the taste profile | 7 (`fold-refinement`), 9 |
| Taste-profile edits change the next curation's composition | 9, 11 |
| Testing matrix: e2e vs mock executor, novelty unit tests, taste-edit effect, clarify reuse, persistence CRUD on embedded H2 | 11, 4, 9, 9, 2+3 |
| Check-in promoting a novel source | Covered by the same primitives (`PUT /interests/:id` adding to `trusted-sources`, `refinements` fold); a dedicated check-in workflow is not built — see Assumptions |
| Screens/prototype (Prism UI) | Out of scope: frontend lives in `kaleidoscope-ui`; prototypes already exist in this plan directory |

---

## Assumptions and resolved ambiguities

1. **Migration number** `20260714000001` — today's date, after the latest existing migration (`20260712000002`).
2. **Interest ≈ Project made literal**: `project_workflow_runs.project_id` is a `NOT NULL` FK to `projects(id)`, so reusing the run tables (design requirement) forces each interest to own a backing `projects` row (created transactionally, `status "interest"`, `UNIQUE (project_id)`). Deleting an interest deletes the backing project and cascades.
3. **Relevance "score" step**: the existing `score` output-kind machinery scores *a project against a score definition* (one score-run per step), not per-candidate lists. Bolting per-candidate scoring onto it would require a Librarian score definition + new score-run semantics for marginal value. Instead the Librarian assigns per-candidate `relevance` in the Discover output and the api layer applies the threshold with the same `quick`/`standard`/`rigorous` config ladder — the design's observable requirements (threshold config pattern reused; below-threshold dropped) hold, with `discover → score → shelve` as stages of `run-curation!`.
4. **Clarify reuse is literal**: the clarify step runs the existing planner machinery against the backing project (whose description is synced from intent + taste profile each run). The curation-specific respond path folds answers into `taste_profile.refinements` (deterministic; keyword *distillation* is LLM work and out of scope) and resumes synchronously so the caller gets the shelf.
5. **No `init/env.clj` changes**: the executor/scorer components are already wired; the curation workflow is seeded lazily inside `run-curation!` (mirroring `get-workflows` → `seed-default-workflows!`), so boot wiring needs nothing.
6. **No `llm_executor.clj` changes**: `text` steps already pull the system prompt via `agents/get-system-prompt (:agent-type step-run)`, so adding the `librarian` dispatch makes production discovery work; the taste profile reaches the prompt through the synced backing-project description.
7. **Check-ins** ride on existing primitives (profile PUT + refinement fold + re-curate); a scheduled check-in workflow and novel-source *promotion suggestions* are deferred (design marks cadence as profile data; nothing pushes by principle).
8. **`refinements` field** added to the taste profile (not in the design's table) as the landing spot for folded answers — the design requires folding but names no field.
