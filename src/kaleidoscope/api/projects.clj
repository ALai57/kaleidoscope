(ns kaleidoscope.api.projects
  (:require [kaleidoscope.api.score-definitions :as score-defs-api]
            [kaleidoscope.api.workflows :as workflows-api]
            [kaleidoscope.persistence.projects :as persistence]
            [kaleidoscope.scoring.protocol :as scoring]
            [taoensso.timbre :as log]))

(declare score-project!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Projects CRUD
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-projects
  "Return all projects for a user, each with their latest score runs."
  [db user-id]
  (let [projects (persistence/get-projects db user-id)]
    (mapv (fn [project]
            (assoc project :scores (or (persistence/get-latest-score-runs db (:id project)) [])))
          projects)))

(defn get-project
  "Return a single project with all latest score runs."
  [db project-id user-id]
  (when-let [project (persistence/get-project db project-id user-id)]
    (assoc project :scores (or (persistence/get-latest-score-runs db project-id) []))))

(defn create-project!
  "Create a project, trigger scoring, and start the default workflow automatically."
  [db scorer executor user-id {:keys [title description] :as body}]
  ;; Ensure default definitions exist for this user
  (score-defs-api/seed-default-definitions! db user-id)
  (let [project (persistence/create-project! db {:user-id     user-id
                                                  :title       title
                                                  :description description})]
    (score-project! db scorer (:id project) user-id nil)
    (when executor
      (workflows-api/start-default-workflow! db executor (:id project) user-id))
    (get-project db (:id project) user-id)))

(defn update-project!
  [db project-id user-id updates]
  (when (persistence/get-project db project-id user-id)
    (persistence/update-project! db project-id user-id updates)
    (get-project db project-id user-id)))

(defn delete-project!
  [db project-id user-id]
  (when (persistence/get-project db project-id user-id)
    (persistence/delete-project! db project-id user-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scoring
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-score!
  "Score a project against a single score definition. Returns the score run."
  [db scorer project definition]
  (let [score-result (scoring/score scorer
                                    (select-keys project [:title :description])
                                    definition)]
    (log/infof "Scored project %s against definition '%s': overall=%.2f"
               (:id project) (:name definition) (:overall score-result))
    (persistence/insert-score-run! db (:id project) (:id definition) score-result)))

(defn score-project!
  "Score a project against specified definition IDs (or all defaults if nil).
   Returns the updated project."
  [db scorer project-id user-id definition-ids]
  (when-let [project (persistence/get-project db project-id user-id)]
    (let [definitions (if (seq definition-ids)
                        ;; Specific definitions requested
                        (keep (partial persistence/get-score-definition db) definition-ids)
                        ;; Default: all is_default definitions for the user
                        (persistence/get-default-score-definitions db user-id))]
      (doseq [defn definitions]
        (try
          (run-score! db scorer project defn)
          (catch Exception e
            (log/errorf "Failed to score project %s against definition %s: %s"
                        project-id (:id defn) e))))
      (get-project db project-id user-id))))

(defn get-score-history
  [db project-id user-id]
  (when (persistence/get-project db project-id user-id)
    (persistence/get-score-history db project-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Notes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-notes
  [db project-id user-id]
  (when (persistence/get-project db project-id user-id)
    (persistence/get-notes db project-id)))

(defn create-note!
  "Create a text note. For voice source, content should already be transcribed."
  [db project-id user-id {:keys [content source]}]
  (when (persistence/get-project db project-id user-id)
    (persistence/create-note! db project-id {:content content
                                             :source  (or source "text")})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Conversations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-conversation
  [db project-id user-id agent-type]
  (when (persistence/get-project db project-id user-id)
    (persistence/get-conversation db project-id agent-type)))

(defn save-conversation-turn!
  "Persist both sides of a conversation turn."
  [db project-id user-id agent-type user-message assistant-response]
  (persistence/save-conversation-turn! db project-id agent-type
                                        user-message assistant-response))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Skills
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-skill-tree
  [db project-id user-id]
  (when (persistence/get-project db project-id user-id)
    (persistence/get-skill-tree db project-id)))

(defn generate-skills!
  "Generate a skill tree for a project.

   generate-fn is a zero-arg fn that returns a vector of skill nodes:
   [{:name str :description str :parent str-or-nil :position int}]

   Pass `(fn [] (llm-scorer/generate-skills api-key project))` from the handler,
   or a stub fn for non-LLM scorers."
  [db project-id user-id generate-fn]
  (when (persistence/get-project db project-id user-id)
    (let [skill-nodes (try (generate-fn)
                           (catch Exception e
                             (log/errorf "Skill generation failed: %s" e)
                             []))]
      (persistence/replace-skills! db project-id skill-nodes))))

(defn update-skill!
  [db project-id user-id skill-id updates]
  (when (persistence/get-project db project-id user-id)
    (persistence/update-skill! db project-id skill-id updates)
    (persistence/get-skill-tree db project-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Section questions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-section-questions
  "Generate guiding questions for a score dimension using generate-fn.

   generate-fn is a zero-arg fn that returns {:questions [\"Q1\" \"Q2\" ...]}.
   Pass `(fn [] (llm-scorer/generate-section-questions api-key params))` from
   the handler, or a stub fn for non-LLM scorers."
  [db project-id user-id generate-fn]
  (when (persistence/get-project db project-id user-id)
    (try
      (generate-fn)
      (catch Exception e
        (log/errorf "Section questions generation failed: %s" e)
        {:questions []}))))
