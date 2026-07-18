(ns kaleidoscope.persistence.tenant
  "A tenant-scoped database handle.

  Multi-tenancy in this codebase hinges on threading `:hostname` into every
  tenant-scoped query (articles, recipes, themes, photos, audiences, ...).
  When that is a convention — an optional key someone has to remember on each
  call — forgetting it silently leaks one site's data to another (see the
  cross-site tests, and the handlers that historically queried by
  url/branch-id/photo-id with no hostname at all).

  `scope` turns the hostname into a *place you cannot query without*: reads
  through the returned handle are automatically confined to its hostname —
  but ONLY for the `tenant-scoped-tables` (those with a hostname column), so
  the same handle is safe to pass to a finder for a non-tenant table
  (groups, workflows, ...), where it is simply unwrapped. The handle survives
  `next.jdbc/with-transaction` so reads inside a transaction stay confined
  too. Writes pass straight through to the underlying datasource."
  (:require [camel-snake-kebab.core :as csk]
            [next.jdbc.protocols :as jdbc.p]))

(def tenant-scoped-tables
  "The tables and views that carry a `hostname` column — the ONLY ones a
  scoped handle may inject `:hostname` into. Injecting into any other table
  (groups, workflows, projects, ...) would be a SQL error. This set is the
  authoritative declaration of the tenant boundary; a schema tripwire test
  (tenant-scoped-tables-match-schema-test) fails if it ever drifts from the
  actual set of hostname-bearing tables."
  #{"articles" "full_article_audiences" "full_branches" "full_versions"
    "full_photos" "photos" "published_articles" "themes" "tags"
    "article_branches" "article_versions" "article_tags" "article_audiences"
    "photo_versions" "photos_in_albums"
    "groups" "user_group_memberships" "full_memberships"
    "albums" "enhanced_albums" "album_contents"
    "portfolio_entries" "portfolio_links"
    "recipes" "recipe_labels" "recipe_label_groups" "recipe_label_assignments"
    "recipe_audiences" "raw_scrapes" "processing_runs"
    ;; AI engine (owner axis is user_id; hostname is the tenant axis on top)
    "projects" "workflows" "score_definitions" "agent_definitions"
    "user_workspace_roots" "interests" "workflow_steps" "project_workflow_runs"
    "project_workflow_step_runs" "project_score_runs" "project_score_dimensions"
    "score_dimension_definitions" "project_notes" "project_conversations"
    "project_skills" "workflow_rounds" "workflow_judge_records" "project_briefs"
    "project_task_generation_runs" "project_tasks" "task_artifacts" "recommendations"})

(defn tenant-scoped-table?
  "Does `table` (a keyword, kebab or snake) carry a hostname column?"
  [table]
  (contains? tenant-scoped-tables (csk/->snake_case_string table)))

(defrecord TenantConn [ds hostname])

(defn scope
  "Bind a datasource/connection to a single tenant hostname. A nil hostname
  (e.g. a request with no Host header) is allowed and fails *closed*: reads of
  tenant-scoped tables match `hostname = NULL`, i.e. nothing — never another
  tenant's data. Crashing here would turn a malformed request into a 500."
  [ds hostname]
  (->TenantConn ds hostname))

(defn scoped?
  [db]
  (instance? TenantConn db))

(defn unwrap
  "The underlying datasource/connection behind a (possibly scoped) handle."
  [db]
  (if (scoped? db) (:ds db) db))

(defn hostname-of
  "The tenant hostname a handle is scoped to, or nil for an unscoped db."
  [db]
  (when (scoped? db) (:hostname db)))

(defn scope-query
  "Inject the handle's hostname into a query map for `table`. A no-op —
  returns the query map unchanged — for an unscoped db, or for a table that
  has no hostname column (so a scoped handle is safe to pass to any finder)."
  [db table query-map]
  (if (and (scoped? db) (tenant-scoped-table? table))
    (assoc query-map :hostname (:hostname db))
    query-map))

(defn inject-row
  "Stamp the handle's hostname onto a row map (or each row in a seq) being
  written to a tenant-scoped `table`. A no-op for an unscoped db or a
  non-tenant table — so writes through a raw datasource must still set
  :hostname themselves, and a scoped handle can't accidentally stamp a table
  with no hostname column. A single request only ever writes its own tenant,
  so stamping the request hostname is correct; the composite (parent_id,
  hostname) FKs then verify children match their parent's tenant."
  [db table m]
  (if (and (scoped? db) (tenant-scoped-table? table))
    (let [h (:hostname db)]
      (if (map? m) (assoc m :hostname h) (mapv #(assoc % :hostname h) m)))
    m))

;; A scoped handle must behave like its datasource under `with-transaction`,
;; but hand the body a handle that is *still scoped* — otherwise reads inside
;; the transaction would silently see every tenant.
(extend-protocol jdbc.p/Transactable
  TenantConn
  (-transact [this body-fn opts]
    (jdbc.p/-transact (:ds this)
                      (fn [tx] (body-fn (->TenantConn tx (:hostname this))))
                      opts)))
