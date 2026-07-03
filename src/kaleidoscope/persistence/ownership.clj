(ns kaleidoscope.persistence.ownership
  "Generic ownership-scoped access for resources that belong to exactly one
  user. Each entry in `resource-specs` is data, not code — adding a new
  owned resource means adding a table entry, not hand-writing get/update/
  delete-X! functions that each re-implement the same owner check.

  No admin-override path: every function here scopes strictly to the given
  owner-id (and site, where applicable). An admin role gets a caller past
  route-level RBAC (see api/authorization.clj); it grants nothing here."
  (:require [kaleidoscope.persistence.rdbms :as rdbms]))

(def resource-specs
  "table       — the backing SQL table.
   owner-col   — the column that scopes a row to its owner.
   site-col    — (optional) a second scoping column for resources that are
                 also tied to a specific site/tenant, not just a user.
                 Only `themes` has one today: a theme's `hostname` is load-
                 bearing in a way a project's isn't (see PLAN.md, \"Gap:
                 themes need a compound ownership key\")."
  {:projects          {:table :projects :owner-col :user-id}
   :workflows         {:table :workflows :owner-col :user-id}
   :score-definitions {:table :score-definitions :owner-col :user-id}
   :agent-definitions {:table :agent-definitions :owner-col :user-id}
   :groups            {:table :groups :owner-col :owner-id}
   :themes            {:table :themes :owner-col :owner-id :site-col :hostname}})

(defn- resource-spec
  [resource]
  (or (get resource-specs resource)
      (throw (ex-info (str "Unknown owned resource: " resource)
                       {:resource resource :known (keys resource-specs)}))))

(defn- owner-where
  "Build the scoping where-map: always :id + owner-col, plus :site-col (bound
  to `site`) when the caller actually supplies one. Site-scoping is opt-in
  per call, not automatic just because the resource has a :site-col — a
  4-arity call (no site) checks ownership globally, e.g. `owns?`-style
  predicates that intentionally don't care which site a theme belongs to.
  Passing `nil` explicitly as `site`, on the other hand, is NOT the same as
  omitting it and would filter on `site-col IS NULL`, matching no real row —
  callers that want site-scoping must pass an actual site value."
  [{:keys [owner-col site-col]} id owner-id site]
  (cond-> {:id id owner-col owner-id}
    (and site-col site) (assoc site-col site)))

(defn get-owned
  "Fetch a row by id, scoped to owner-id (and site, for resources with a
  :site-col). Returns nil if not found or not owned — the two are
  indistinguishable by design, so a non-owner probing ids can't tell which
  case they hit."
  ([db resource id owner-id]
   (get-owned db resource id owner-id nil))
  ([db resource id owner-id site]
   (let [{:keys [table] :as spec} (resource-spec resource)
         finder (rdbms/make-finder table)]
     (first (finder db (owner-where spec id owner-id site))))))

(defn- require-site!
  "update-owned!/delete-owned! must not silently drop site-scoping for a
  resource that has a :site-col — unlike get-owned (used for coarser,
  intentionally site-agnostic checks like `owns?`), a mutation on a
  site-scoped resource with no site supplied is almost certainly a bug, not
  an intentional global operation. Fail loudly instead of quietly matching
  `site_col IS NULL` (i.e. no real row)."
  [{:keys [site-col]} resource site]
  (when (and site-col (nil? site))
    (throw (ex-info (str "update-owned!/delete-owned! on " resource
                         " requires a :site value (it has a :site-col) — "
                         "pass one explicitly, don't rely on the no-site arity.")
                     {:resource resource :site-col site-col}))))

(defn update-owned!
  "Update a row by id, scoped to owner-id (and site, for resources with a
  :site-col — required for those, see require-site!). Returns the updated
  row, or nil if not found/not owned. This replaces the fetch-then-compare
  pattern: the WHERE clause itself enforces ownership, so there's no
  preceding check a call site can forget."
  ([db resource id owner-id updates]
   (update-owned! db resource id owner-id updates nil))
  ([db resource id owner-id updates site]
   (let [{:keys [table] :as spec} (resource-spec resource)]
     (require-site! spec resource site)
     (first (rdbms/scoped-update! db table (owner-where spec id owner-id site) updates)))))

(defn delete-owned!
  "Delete a row by id, scoped to owner-id (and site, for resources with a
  :site-col — required for those, see require-site!). Returns true iff a
  row was actually deleted, false if not found/not owned.

  The delete itself is always safely scoped by the WHERE clause regardless
  of the check below — the existence check exists only to give callers a
  reliable true/false signal, since the DELETE statement's own return value
  is backend-inconsistent (see `rdbms/scoped-delete!`), not because the
  check is load-bearing for ownership enforcement."
  ([db resource id owner-id]
   (delete-owned! db resource id owner-id nil))
  ([db resource id owner-id site]
   (let [{:keys [table] :as spec} (resource-spec resource)]
     (require-site! spec resource site)
     (if (get-owned db resource id owner-id site)
       (do (rdbms/scoped-delete! db table (owner-where spec id owner-id site))
           true)
       false))))
