(ns kaleidoscope.api.recipes
  "Recipe domain logic. Mirrors the shape of `api.articles` but shares the
  visibility rule via `api.access`. See plans/2026-07-10-recipes-feature/PLAN.md.

  Identity is a single opaque UUID; `recipe-url` is the slug/address. A recipe's
  content (title, ingredients, instructions, servings, times) is one JSONB value
  under `:content`; the immutable scrape is the same shape under
  `:original-content`."
  (:require [cheshire.core :as json]
            [kaleidoscope.api.access :as access]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]
            [honey.sql :as hsql]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]
            [taoensso.timbre :as log]))

(defn- ->content
  "Normalize a JSONB content column to a Clojure map. Postgres returns it
  already decoded; embedded H2 returns the raw JSON string — decode so the
  domain always hands callers structured content regardless of backend."
  [v]
  (cond
    (string? v) (json/decode v true)
    :else       v))

(defn- parse-content-columns
  [recipe]
  (-> recipe
      (update :content ->content)
      (cond-> (:original-content recipe) (update :original-content ->content))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Labels + groups
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-label-groups
  [db {:keys [hostname] :as query}]
  (rdbms/find-by-keys db :recipe-label-groups query))

(defn create-label-group!
  [db {:keys [hostname name] :as group}]
  (first (rdbms/insert! db
                        :recipe-label-groups {:id         (utils/uuid)
                                              :name       name
                                              :hostname   hostname
                                              :created-at (utils/now)}
                        :ex-subtype :UnableToCreateRecipeLabelGroup)))

(defn rename-label-group!
  [db id hostname new-name]
  (first (rdbms/scoped-update! db :recipe-label-groups
                               {:id id :hostname hostname}
                               {:name new-name})))

(defn delete-label-group!
  "Delete a group (cascades to its labels and their assignments), scoped to
  hostname so one tenant can't delete another's group."
  [db id hostname]
  (rdbms/scoped-delete! db :recipe-label-groups {:id id :hostname hostname}
                        :ex-subtype :UnableToDeleteRecipeLabelGroup))

(defn get-labels
  "All labels for a tenant, each with its group name joined so callers can
  render `group/label` (e.g. `ethnicity/indian`)."
  [db {:keys [hostname] :as query}]
  (next/execute! db
                 (hsql/format {:select   [:l.id :l.name :l.group-id :l.hostname :l.created-at
                                          [:g.name :group-name]]
                               :from     [[:recipe-labels :l]]
                               :left-join [[:recipe-label-groups :g] [:= :g.id :l.group-id]]
                               :where    [:= :l.hostname hostname]
                               :order-by [[:group-name :asc] [:l.name :asc]]})
                 {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-labels-by-ids
  "Labels for the given ids, scoped to hostname. Used to resolve a submitted
  label set to its group-ids (and to reject cross-tenant/unknown ids)."
  [db label-ids hostname]
  (when (seq label-ids)
    (next/execute! db
                   (hsql/format {:select :*
                                 :from   :recipe-labels
                                 :where  [:and
                                          [:= :hostname hostname]
                                          [:in :id (vec label-ids)]]})
                   {:builder-fn rs/as-unqualified-kebab-maps})))

(defn create-label!
  "Create a label, optionally in a group. When a group-id is given it must
  belong to the same tenant (the DB composite FK enforces this too)."
  [db {:keys [hostname name group-id] :as label}]
  (first (rdbms/insert! db
                        :recipe-labels {:id         (utils/uuid)
                                        :name       name
                                        :group-id   group-id
                                        :hostname   hostname
                                        :created-at (utils/now)}
                        :ex-subtype :UnableToCreateRecipeLabel)))

(defn rename-label!
  [db id hostname new-name]
  (first (rdbms/scoped-update! db :recipe-labels
                               {:id id :hostname hostname}
                               {:name new-name})))

(defn delete-label!
  [db id hostname]
  (rdbms/scoped-delete! db :recipe-labels {:id id :hostname hostname}
                        :ex-subtype :UnableToDeleteRecipeLabel))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Recipes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- attach-labels
  "Attach each recipe's labels (with group name) as :labels. One extra query
  for the whole batch, grouped in Clojure."
  [db recipes]
  (let [recipe-ids (mapv :id recipes)]
    (if (empty? recipe-ids)
      recipes
      (let [rows      (next/execute! db
                                     (hsql/format {:select    [[:a.recipe-id :recipe-id]
                                                               [:l.id :id] [:l.name :name]
                                                               [:l.group-id :group-id]
                                                               [:g.name :group-name]]
                                                   :from      [[:recipe-label-assignments :a]]
                                                   :join      [[:recipe-labels :l] [:= :l.id :a.label-id]]
                                                   :left-join [[:recipe-label-groups :g] [:= :g.id :l.group-id]]
                                                   :where     [:in :a.recipe-id recipe-ids]})
                                     {:builder-fn rs/as-unqualified-kebab-maps})
            by-recipe (group-by :recipe-id rows)]
        (mapv (fn [{:keys [id] :as recipe}]
                (-> recipe
                    parse-content-columns
                    (assoc :labels (mapv #(dissoc % :recipe-id) (get by-recipe id [])))))
              recipes)))))

(defn get-recipes
  "Recipes for a tenant, labels attached. Optional filters:
   - :recipe-url  exact slug
   - :ingredient  text-contains over the ingredients array (Postgres only)
   - :label-id    recipes assigned that label

  NOTE: :ingredient uses `jsonb_array_elements_text`, a Postgres function — it
  is not supported on embedded H2 (which is why recipe tests run on
  embedded-postgres)."
  [db {:keys [hostname recipe-url ingredient label-id] :as _query}]
  (let [where (cond-> [:and [:= :hostname hostname]]
                recipe-url (conj [:= :recipe-url recipe-url])
                ingredient (conj [:exists {:select [[[:inline 1]]]
                                           :from   [[[:raw "jsonb_array_elements_text(content -> 'ingredients')"] :i]]
                                           :where  [:ilike :i (str "%" ingredient "%")]}])
                label-id   (conj [:exists {:select [[[:inline 1]]]
                                           :from   [[:recipe-label-assignments :a]]
                                           :where  [:and
                                                    [:= :a.recipe-id :recipes.id]
                                                    [:= :a.label-id label-id]]}]))
        recipes (next/execute! db
                               (hsql/format {:select   :*
                                             :from     :recipes
                                             :where    where
                                             :order-by [[:modified-at :desc]]})
                               {:builder-fn rs/as-unqualified-kebab-maps})]
    (attach-labels db recipes)))

(defn get-recipe
  "A single recipe by slug (labels attached), scoped to hostname."
  [db hostname recipe-url]
  (first (get-recipes db {:hostname hostname :recipe-url recipe-url})))

(defn get-recipe-audiences
  [db query]
  (rdbms/find-by-keys db :recipe-audiences query))

(defn get-visible-recipes
  "Recipes the caller may view: public ∪ shared-with-a-group-they're-in ∪
  (writer-sees-all). A writer/admin for the tenant sees every recipe for that
  host, including their own not-yet-public drafts — without this a freshly
  created private recipe would be invisible even to its author (see PLAN.md,
  `writer-sees-all`). Optional :recipe-url narrows to one. Uses the shared
  `api.access` rule for the non-writer case."
  [db {:keys [hostname recipe-url] :as query} {:keys [email] :as _user} writer?]
  (let [matching (delay (get-recipes db (select-keys query [:hostname :recipe-url :ingredient :label-id])))]
    (if writer?
      @matching
      (let [users-groups (access/user-group-ids db email)
            audiences    (get-recipe-audiences db {:hostname hostname})
            public-ids   (mapv :id (rdbms/find-by-keys db :recipes {:hostname hostname
                                                                    :public-visibility true}))
            allowed      (access/visible-ids {:public-ids   public-ids
                                              :audiences    audiences
                                              :users-groups users-groups
                                              :id-key       :recipe-id})]
        (log/infof "User `%s` may view recipe-ids %s" email allowed)
        (if (empty? allowed)
          []
          (filterv #(contains? allowed (:id %)) @matching))))))

(defn- one-per-group-violation
  "Return the offending group-id if two labels share a non-null group, else nil."
  [labels]
  (let [groups (keep :group-id labels)]
    (->> (frequencies groups)
         (some (fn [[g n]] (when (> n 1) g))))))

(defn- validate-label-set!
  "Resolve label-ids to rows (scoped to hostname); throw if any id is unknown
  for this tenant or if two labels share a group. Returns the resolved labels."
  [db label-ids hostname]
  (let [labels (get-labels-by-ids db label-ids hostname)]
    (when (not= (count (set label-ids)) (count labels))
      (throw (ex-info "One or more labels do not exist for this site"
                      {:type :validation :label-ids label-ids})))
    (when-let [g (one-per-group-violation labels)]
      (throw (ex-info "At most one label per group may apply to a recipe"
                      {:type :validation :group-id g})))
    labels))

(defn- replace-label-assignments!
  "Delete a recipe's assignments and insert the given set atomically. Each
  assignment carries its label's group-id so the one-per-group DB constraint
  has what it needs. Assumes label-ids already validated."
  [tx recipe-id hostname labels]
  (rdbms/scoped-delete! tx :recipe-label-assignments {:recipe-id recipe-id})
  (when (seq labels)
    (let [now (utils/now)]
      (rdbms/insert! tx :recipe-label-assignments
                     (vec (for [{:keys [id group-id]} labels]
                            {:id         (utils/uuid)
                             :recipe-id  recipe-id
                             :label-id   id
                             :group-id   group-id
                             :hostname   hostname
                             :created-at now}))
                     :ex-subtype :UnableToAssignRecipeLabel))))

(defn create-recipe!
  "Create a recipe. `:content` is the current recipe; `:original-content`, if
  given, is the immutable scrape (never modified after). Accepts `:label-ids`;
  validates one-per-group before writing."
  [db {:keys [hostname recipe-url content original-content source-url author
              public-visibility label-ids] :as recipe}]
  (log/infof "Creating recipe %s for %s" recipe-url hostname)
  (next/with-transaction [tx db]
    (let [labels (validate-label-set! tx label-ids hostname)
          now    (utils/now)
          [{:keys [id] :as created}]
          (rdbms/insert! tx :recipes
                         {:id                (utils/uuid)
                          :recipe-url        recipe-url
                          :hostname          hostname
                          :content           content
                          :original-content  original-content
                          :source-url        source-url
                          :author            author
                          :public-visibility (boolean public-visibility)
                          :created-at        now
                          :modified-at       now}
                         :ex-subtype :UnableToCreateRecipe)]
      (replace-label-assignments! tx id hostname labels)
      (get-recipe tx hostname recipe-url))))

(defn update-recipe!
  "Update a recipe's editable fields (content, source-url, visibility) and its
  label set, scoped to hostname. Never touches `:original-content`. Returns nil
  if no recipe with that slug exists for the tenant."
  [db hostname recipe-url {:keys [content source-url public-visibility label-ids] :as patch}]
  (log/infof "Updating recipe %s for %s" recipe-url hostname)
  (next/with-transaction [tx db]
    (if-let [{:keys [id]} (get-recipe tx hostname recipe-url)]
      (let [labels (validate-label-set! tx label-ids hostname)
            set-map (cond-> {:modified-at (utils/now)}
                      (contains? patch :content)           (assoc :content content)
                      (contains? patch :source-url)        (assoc :source-url source-url)
                      (contains? patch :public-visibility) (assoc :public-visibility (boolean public-visibility)))]
        (rdbms/scoped-update! tx :recipes {:id id :hostname hostname} set-map)
        (when (contains? patch :label-ids)
          (replace-label-assignments! tx id hostname labels))
        (get-recipe tx hostname recipe-url))
      (do (log/warnf "No recipe %s for %s" recipe-url hostname)
          nil))))

(defn delete-recipe!
  "Delete a recipe (cascades to assignments + audiences), scoped to hostname."
  [db hostname recipe-url]
  (rdbms/scoped-delete! db :recipes {:recipe-url recipe-url :hostname hostname}
                        :ex-subtype :UnableToDeleteRecipe))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Audiences
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn add-audience-to-recipe!
  "Share a recipe with a group. Idempotent: a duplicate share returns the
  existing row instead of erroring (mirrors article audiences)."
  [db {:keys [id hostname] :as recipe} group]
  (let [existing (get-recipe-audiences db {:recipe-id id :group-id (str (:id group))})]
    (cond
      (nil? hostname)                       (log/warnf "Recipe missing hostname %s" recipe)
      (empty? (rdbms/find-by-keys db :recipes {:id id :hostname hostname})) (log/warnf "No recipe matching %s" recipe)
      (not-empty existing)                  existing
      :else                                 (rdbms/insert! db :recipe-audiences
                                                           {:id         (utils/uuid)
                                                            :group-id   (str (:id group))
                                                            :recipe-id  id
                                                            :hostname   hostname
                                                            :created-at (utils/now)}
                                                           :ex-subtype :UnableToAddRecipeAudience))))

(defn delete-recipe-audience!
  [db audience-id]
  (rdbms/delete! db :recipe-audiences audience-id
                 :ex-subtype :UnableToDeleteRecipeAudience))
