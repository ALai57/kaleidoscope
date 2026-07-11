(ns kaleidoscope.api.access
  "Shared visibility/audience logic for hostname-scoped, group-shareable
  content. Recipes and articles are the same concept underneath — public flag
  plus per-group audiences plus tenant scoping — so the rule for 'which ids may
  this user see' lives here once instead of being copied per domain.

  (Articles still carry their own copy in `api.articles/get-published-articles`
  for now; new domains use this and articles can adopt it without touching the
  legacy CMS code path.)"
  (:require [kaleidoscope.api.groups :as api.groups]))

(defn user-group-ids
  "The set of group-ids the user (by email) belongs to. An anonymous/unknown
  user belongs to no groups, so only public content is visible to them."
  [db email]
  (if email
    (->> {:email email}
         (api.groups/get-group-memberships db)
         (map :group-id)
         (into #{}))
    #{}))

(defn visible-ids
  "Compute the set of entity ids a user may view:
     public-ids ∪ (audience rows whose group the user is a member of)

  - public-ids:   ids that are publicly visible
  - audiences:    audience rows for the tenant, each with :group-id and the
                  entity id under `id-key`
  - users-groups: set of group-ids the user belongs to (see `user-group-ids`)
  - id-key:       key in each audience row holding the entity id
                  (e.g. :recipe-id, :article-id)"
  [{:keys [public-ids audiences users-groups id-key]}]
  (let [restricted (->> audiences
                        (filter (fn [{:keys [group-id]}] (contains? users-groups group-id)))
                        (map id-key))]
    (into #{} (concat public-ids restricted))))
