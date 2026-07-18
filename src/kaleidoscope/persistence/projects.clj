(ns kaleidoscope.persistence.projects
  (:require [camel-snake-kebab.core :as csk]
            [honey.sql :as hsql]
            [honey.sql.helpers :as hh]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.utils.core :as utils]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Projects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private get-projects-raw
  (rdbms/make-finder :projects))

(defn get-projects
  "Return all projects for a user, excluding interest-backing projects
  (status = \"interest\") — those are managed via /interests, not the
  projects API."
  [db user-id]
  (let [hostname (tenant/hostname-of db)]
    (next/execute! (tenant/unwrap db)
                   (if hostname
                     ["SELECT * FROM projects WHERE user_id = ? AND status IS DISTINCT FROM ? AND hostname = ?"
                      user-id "interest" hostname]
                     ["SELECT * FROM projects WHERE user_id = ? AND status IS DISTINCT FROM ?"
                      user-id "interest"])
                   {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-project
  "Return a single project by id, checking user ownership."
  [db project-id user-id]
  (first (get-projects-raw db {:id project-id :user-id user-id})))

(defn create-project!
  [db {:keys [user-id title description status] :as project}]
  (let [now (utils/now)]
    (first (rdbms/insert! db
                          :projects
                          {:id          (utils/uuid)
                           :user-id     user-id
                           :title       title
                           :description description
                           :status      (or status "idea")
                           :created-at  now
                           :updated-at  now}
                          :ex-subtype :UnableToCreateProject))))

(defn update-project!
  "Update a project, scoped to user-id. Returns nil if not found or not
  owned — the WHERE clause enforces that, not a preceding check. This
  closes the original Pattern A gap: user-id used to be accepted here and
  silently ignored.

  Only title/description/status/local-paths are settable — the caller's
  `updates` map is destructured, not passed through, so an HTTP body can't
  smuggle in :user-id (or anything else) via the SET clause. Passing
  :user-id through here used to let the current owner silently transfer
  the whole row, content and all, to an arbitrary account (verified
  exploitable 2026-07-03 — see PLAN.md)."
  [db project-id user-id {:keys [title description status local-paths]}]
  (first (rdbms/scoped-update! db
                               :projects
                               {:id project-id :user-id user-id}
                               (cond-> {:updated-at (utils/now)}
                                 title              (assoc :title title)
                                 description        (assoc :description description)
                                 status             (assoc :status status)
                                 (some? local-paths) (assoc :local-paths local-paths)))))

(defn delete-project!
  "Delete a project, scoped to user-id."
  [db project-id user-id]
  (rdbms/scoped-delete! db :projects {:id project-id :user-id user-id}
                        :ex-subtype :UnableToDeleteProject))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Score Definitions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private get-score-definitions-raw
  (rdbms/make-finder :score-definitions))

(def ^:private get-score-dimension-definitions-raw
  (rdbms/make-finder :score-dimension-definitions))

(defn get-score-definitions
  "Return all score definitions for a user."
  [db user-id]
  (get-score-definitions-raw db {:user-id user-id}))

(defn get-score-definition
  "Return a score definition with its dimensions, scoped to user-id. Returns
  nil if not found or not owned — the two are indistinguishable by design."
  [db definition-id user-id]
  (when-let [defn (first (get-score-definitions-raw db {:id definition-id :user-id user-id}))]
    (let [dims (get-score-dimension-definitions-raw db {:score-definition-id definition-id})]
      (assoc defn :dimensions (vec (sort-by :position dims))))))

(defn get-default-score-definition-by-scorer-type
  "Return the first is_default=true score definition matching a scorer type, with dimensions."
  [db user-id scorer-type]
  (when-let [defn (first (let [hostname (tenant/hostname-of db)]
                           (next/execute! (tenant/unwrap db)
                                          (hsql/format {:select :*
                                                        :from   :score-definitions
                                                        :where  (cond-> [:and
                                                                         [:= :user-id user-id]
                                                                         [:= :scorer-type scorer-type]
                                                                         [:= :is-default true]]
                                                                  hostname (conj [:= :hostname hostname]))
                                                        :limit  1})
                                          {:builder-fn rs/as-unqualified-kebab-maps})))]
    (let [dims (get-score-dimension-definitions-raw db {:score-definition-id (:id defn)})]
      (assoc defn :dimensions (vec (sort-by :position dims))))))

(defn get-default-score-definitions
  "Return all is_default=true score definitions with their dimensions."
  [db user-id]
  (->> (let [hostname (tenant/hostname-of db)]
         (next/execute! (tenant/unwrap db)
                        (hsql/format {:select :*
                                      :from   :score-definitions
                                      :where  (cond-> [:and
                                                       [:= :user-id user-id]
                                                       [:= :is-default true]]
                                                hostname (conj [:= :hostname hostname]))})
                        {:builder-fn rs/as-unqualified-kebab-maps}))
       (mapv (fn [defn]
               (let [dims (get-score-dimension-definitions-raw db {:score-definition-id (:id defn)})]
                 (assoc defn :dimensions (vec (sort-by :position dims))))))))

(defn create-score-definition!
  [db {:keys [user-id name description scorer-type is-default dimensions]}]
  (next/with-transaction [tx db]
    (let [now    (utils/now)
          def-id (utils/uuid)
          defn   (first (rdbms/insert! tx
                                       :score-definitions
                                       {:id          def-id
                                        :user-id     user-id
                                        :name        name
                                        :description description
                                        :scorer-type (or scorer-type "general")
                                        :is-default  (boolean is-default)
                                        :created-at  now
                                        :updated-at  now}
                                       :ex-subtype :UnableToCreateScoreDefinition))
          dims   (when (seq dimensions)
                   (rdbms/insert! tx
                                  :score-dimension-definitions
                                  (vec (map-indexed
                                         (fn [i {:keys [name criteria]}]
                                           {:id                  (utils/uuid)
                                            :score-definition-id def-id
                                            :name                name
                                            :criteria            criteria
                                            :position            i})
                                         dimensions))
                                  :ex-subtype :UnableToCreateScoreDimension))]
      (assoc defn :dimensions (vec (sort-by :position (or dims [])))))))

(defn update-score-definition!
  "Update a score definition, scoped to user-id. Returns nil if not found or
  not owned — ownership is enforced by the UPDATE's WHERE clause itself, not
  a preceding check."
  [db definition-id user-id {:keys [name description scorer-type dimensions]}]
  (next/with-transaction [tx db]
    (let [now  (utils/now)
          defn (first (rdbms/scoped-update! tx
                                            :score-definitions
                                            {:id definition-id :user-id user-id}
                                            (cond-> {:updated-at now}
                                              name        (assoc :name name)
                                              description (assoc :description description)
                                              scorer-type (assoc :scorer-type scorer-type))))]
      (when defn
        (when (some? dimensions)
          ;; Replace all dimensions (raw delete; unwrap so a scoped tx doesn't
          ;; reach next/execute!. Scoped by the FK, which is tenant-bound.)
          (next/execute! (tenant/unwrap tx)
                         (hsql/format (-> (hh/delete-from :score-dimension-definitions)
                                          (hh/where [:= :score-definition-id definition-id])))
                         {:builder-fn rs/as-unqualified-kebab-maps})
          (when (seq dimensions)
            (rdbms/insert! tx
                           :score-dimension-definitions
                           (vec (map-indexed
                                  (fn [i {:keys [name criteria]}]
                                    {:id                  (utils/uuid)
                                     :score-definition-id definition-id
                                     :name                name
                                     :criteria            criteria
                                     :position            i})
                                  dimensions))
                           :ex-subtype :UnableToUpdateScoreDimension)))
        (assoc defn :dimensions
               (vec (sort-by :position
                             (get-score-dimension-definitions-raw tx {:score-definition-id definition-id}))))))))

(defn delete-score-definition!
  "Delete a score definition, scoped to user-id. Blocked if is_default=true.
  Returns {:error :not-found} if not found or not owned (indistinguishable
  by design), {:error :cannot-delete-default} if it's a default."
  [db definition-id user-id]
  (if-let [defn (first (get-score-definitions-raw db {:id definition-id :user-id user-id}))]
    (if (:is-default defn)
      (do (log/warnf "Attempted to delete default score definition %s" definition-id)
          {:error :cannot-delete-default})
      (rdbms/scoped-delete! db :score-definitions {:id definition-id :user-id user-id}
                            :ex-subtype :UnableToDeleteScoreDefinition))
    {:error :not-found}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Score Runs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- next-version!
  "Get the next version number for a (project, definition) pair.
   Must be called inside a transaction."
  [tx project-id definition-id]
  (let [result (first (next/execute! (tenant/unwrap tx)
                                     (hsql/format {:select [[[:coalesce [:max :version] 0] :max-version]]
                                                   :from   :project-score-runs
                                                   :where  [:and
                                                            [:= :project-id project-id]
                                                            [:= :score-definition-id definition-id]]})
                                     {:builder-fn rs/as-unqualified-kebab-maps}))]
    (inc (:max-version result 0))))

(defn get-score-run
  "Return a score run with its dimension results."
  [db score-run-id]
  (when-let [run (first (next/execute! (tenant/unwrap db)
                                       (hsql/format {:select :*
                                                     :from   :project-score-runs
                                                     :where  [:= :id score-run-id]})
                                       {:builder-fn rs/as-unqualified-kebab-maps}))]
    (let [dims (next/execute! (tenant/unwrap db)
                              (hsql/format {:select :*
                                            :from   :project-score-dimensions
                                            :where  [:= :score-run-id score-run-id]})
                              {:builder-fn rs/as-unqualified-kebab-maps})]
      (assoc run :dimensions (vec dims)))))

(defn insert-score-run!
  "Insert a versioned score run and its dimension results within a transaction.
   brief-version (optional) links the score to a specific project brief version."
  [db project-id definition-id {:keys [overall dimensions brief-version]}]
  (next/with-transaction [tx db]
    (let [now      (utils/now)
          version  (next-version! tx project-id definition-id)
          run-id   (utils/uuid)
          run      (first (rdbms/insert! tx
                                         :project-score-runs
                                         (cond-> {:id                  run-id
                                                  :project-id          project-id
                                                  :score-definition-id definition-id
                                                  :version             version
                                                  :overall             overall
                                                  :scored-at           now}
                                           brief-version (assoc :brief-version brief-version))
                                         :ex-subtype :UnableToCreateScoreRun))
          dim-rows (vec (map (fn [{:keys [dimension-name value rationale]}]
                               {:id             (utils/uuid)
                                :score-run-id   run-id
                                :dimension-name dimension-name
                                :value          value
                                :rationale      rationale})
                             dimensions))]
      (when (seq dim-rows)
        (rdbms/insert! tx :project-score-dimensions dim-rows
                       :ex-subtype :UnableToCreateScoreDimension))
      (assoc run :dimensions dim-rows))))

(defn- get-score-dimensions-for-runs
  "Fetch all dimension results for a set of score run IDs."
  [db run-ids]
  (if (empty? run-ids)
    []
    (next/execute! (tenant/unwrap db)
                   (hsql/format {:select :*
                                 :from   :project-score-dimensions
                                 :where  [:in :score-run-id run-ids]})
                   {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-latest-score-runs
  "Return the latest score run per definition for a project, with dimensions and definition info."
  [db project-id]
  (let [hostname (tenant/hostname-of db)
        latest-runs
        (next/execute!
         (tenant/unwrap db)
         (into
          [(str "SELECT psr.id, psr.project_id, psr.score_definition_id, psr.version,"
                "       psr.overall, psr.scored_at,"
                "       sd.name AS definition_name, sd.scorer_type AS definition_scorer_type"
                "  FROM project_score_runs psr"
                "  JOIN score_definitions sd ON psr.score_definition_id = sd.id"
                " WHERE psr.project_id = ?"
                (when hostname "   AND psr.hostname = ?")
                "   AND psr.version = ("
                "       SELECT MAX(psr2.version)"
                "         FROM project_score_runs psr2"
                "        WHERE psr2.project_id = psr.project_id"
                "          AND psr2.score_definition_id = psr.score_definition_id"
                "   )")
           project-id]
          (when hostname [hostname]))
         {:builder-fn rs/as-unqualified-kebab-maps})]
    (when (seq latest-runs)
      (let [run-ids    (mapv :id latest-runs)
            all-dims   (get-score-dimensions-for-runs db run-ids)
            dims-by-run (group-by :score-run-id all-dims)]
        (mapv (fn [run]
                {:id         (:id run)
                 :version    (:version run)
                 :scored-at  (:scored-at run)
                 :definition {:id          (:score-definition-id run)
                              :name        (:definition-name run)
                              :scorer-type (:definition-scorer-type run)}
                 :overall    (:overall run)
                 :dimensions (vec (get dims-by-run (:id run) []))})
              latest-runs)))))

(defn get-score-history
  "Return all score runs for a project, all versions, all definitions."
  [db project-id]
  (let [hostname (tenant/hostname-of db)
        runs (next/execute! (tenant/unwrap db)
                            (hsql/format
                             {:select [:psr/id
                                       :psr/project-id
                                       :psr/score-definition-id
                                       :psr/version
                                       :psr/overall
                                       :psr/scored-at
                                       [:sd/name :definition-name]
                                       [:sd/scorer-type :definition-scorer-type]]
                              :from   [[:project-score-runs :psr]]
                              :join   [[:score-definitions :sd] [:= :psr/score-definition-id :sd/id]]
                              :where  (cond-> [:and [:= :psr/project-id project-id]]
                                        hostname (conj [:= :psr/hostname hostname]))
                              :order-by [[:psr/scored-at :desc]]})
                            {:builder-fn rs/as-unqualified-kebab-maps})]
    (when (seq runs)
      (let [run-ids   (mapv :id runs)
            all-dims  (get-score-dimensions-for-runs db run-ids)
            dims-by-run (group-by :score-run-id all-dims)]
        (mapv (fn [run]
                {:id         (:id run)
                 :version    (:version run)
                 :scored-at  (:scored-at run)
                 :definition {:id          (:score-definition-id run)
                              :name        (:definition-name run)
                              :scorer-type (:definition-scorer-type run)}
                 :overall    (:overall run)
                 :dimensions (vec (get dims-by-run (:id run) []))})
              runs)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Notes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private get-notes-raw
  (rdbms/make-finder :project-notes))

(defn get-notes
  [db project-id]
  (get-notes-raw db {:project-id project-id}))

(defn create-note!
  [db project-id {:keys [content source]}]
  (first (rdbms/insert! db
                        :project-notes
                        {:id         (utils/uuid)
                         :project-id project-id
                         :content    content
                         :source     (or source "text")
                         :created-at (utils/now)}
                        :ex-subtype :UnableToCreateNote)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Conversations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private get-conversations-raw
  (rdbms/make-finder :project-conversations))

(defn get-conversation
  "Return conversation history for a project + agent type, ordered by creation time."
  [db project-id agent-type]
  (let [hostname (tenant/hostname-of db)]
    (next/execute! (tenant/unwrap db)
                   (hsql/format {:select   :*
                                  :from     :project-conversations
                                  :where    (cond-> [:and
                                                     [:= :project-id project-id]
                                                     [:= :agent-type agent-type]]
                                              hostname (conj [:= :hostname hostname]))
                                  :order-by [[:created-at :asc]]})
                   {:builder-fn rs/as-unqualified-kebab-maps})))

(defn save-conversation-turn!
  "Persist a user message and the assistant reply as two rows."
  [db project-id agent-type user-message assistant-message]
  (let [now    (utils/now)
        rows   [{:id         (utils/uuid)
                 :project-id project-id
                 :agent-type agent-type
                 :role       "user"
                 :content    user-message
                 :created-at now}
                {:id         (utils/uuid)
                 :project-id project-id
                 :agent-type agent-type
                 :role       "assistant"
                 :content    assistant-message
                 :created-at now}]]
    (rdbms/insert! db :project-conversations rows
                   :ex-subtype :UnableToSaveConversation)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Skills
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private get-skills-raw
  (rdbms/make-finder :project-skills))

(defn get-skills
  "Return all skills for a project as a flat list."
  [db project-id]
  (let [hostname (tenant/hostname-of db)]
    (next/execute! (tenant/unwrap db)
                   (hsql/format {:select   :*
                                  :from     :project-skills
                                  :where    (cond-> [:and [:= :project-id project-id]]
                                              hostname (conj [:= :hostname hostname]))
                                  :order-by [[:position :asc]]})
                   {:builder-fn rs/as-unqualified-kebab-maps})))

(defn build-skill-tree
  "Convert a flat list of skills into a nested tree structure."
  [skills]
  (let [get-children (fn get-children [parent-id]
                       (->> skills
                            (filter #(= (:parent-id %) parent-id))
                            (sort-by :position)
                            (mapv (fn [skill]
                                    (assoc skill :children (get-children (:id skill)))))))]
    (->> skills
         (filter #(nil? (:parent-id %)))
         (sort-by :position)
         (mapv (fn [skill]
                 (assoc skill :children (get-children (:id skill))))))))

(defn get-skill-tree
  [db project-id]
  (build-skill-tree (get-skills db project-id)))

(defn replace-skills!
  "Replace all skills for a project with a new set.
   skill-nodes: [{:name :description :parent :position}] (flat list, parent is referenced by name)"
  [db project-id skill-nodes]
  (next/with-transaction [tx db]
    ;; Delete all existing skills (raw; unwrap the scoped tx. FK-scoped by
    ;; project-id, which is tenant-bound.)
    (next/execute! (tenant/unwrap tx)
                   (hsql/format (-> (hh/delete-from :project-skills)
                                    (hh/where [:= :project-id project-id])))
                   {:builder-fn rs/as-unqualified-kebab-maps})
    ;; Insert new skills, resolving parent references by name
    (when (seq skill-nodes)
      (let [now      (utils/now)
            with-ids (mapv (fn [n] (assoc n :id (utils/uuid) :created-at now)) skill-nodes)
            name->id (into {} (map (juxt :name :id) with-ids))
            rows     (mapv (fn [{:keys [id name description parent status position]}]
                             {:id          id
                              :project-id  project-id
                              :parent-id   (when parent (get name->id parent))
                              :name        name
                              :description description
                              :status      (or status "identified")
                              :position    (or position 0)
                              :created-at  now})
                           with-ids)]
        (rdbms/insert! tx :project-skills rows :ex-subtype :UnableToCreateSkills)))
    (get-skill-tree tx project-id)))

(defn update-skill!
  "Update a skill, scoped to project-id. This is the fix for the child-
  resource gap noted in PLAN.md §4b: project-id used to be accepted here and
  silently ignored, so ownership was enforced only by the caller's preceding
  get-project check, not by this statement itself.

  Only name/description/status/position are settable — the caller's
  `updates` map is destructured, not passed through, so an HTTP body can't
  smuggle in :project-id via the SET clause. Passing :project-id through
  here (even with the WHERE clause scoped correctly) used to let a skill be
  silently re-parented into a project the caller doesn't own after the
  ownership check passed (verified exploitable 2026-07-03 — see PLAN.md)."
  [db project-id skill-id {:keys [name description status position]}]
  (first (rdbms/scoped-update! db
                               :project-skills
                               {:id skill-id :project-id project-id}
                               (cond-> {}
                                 name        (assoc :name name)
                                 description (assoc :description description)
                                 status      (assoc :status status)
                                 (some? position) (assoc :position position)))))
