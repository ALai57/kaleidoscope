(ns kaleidoscope.api.tasks
  (:require [cheshire.core :as json]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.tasks :as persistence]
            [kaleidoscope.persistence.workflows :as workflows-persistence]
            [kaleidoscope.tasks.planner :as planner]
            [kaleidoscope.utils.core :as utils]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Internal helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- write-sse-event!
  [^java.io.OutputStream output-stream data]
  (let [writer (java.io.OutputStreamWriter. output-stream "UTF-8")]
    (.write writer (str "data: " (json/encode data) "\n\n"))
    (.flush writer)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tasks CRUD
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn list-tasks
  "Return tasks for a project. Optional :status filter."
  [db project-id user-id {:keys [status]}]
  (when (projects-persistence/get-project db project-id user-id)
    (if status
      (persistence/list-tasks db project-id status)
      (persistence/list-tasks db project-id))))

(defn create-task!
  "Create a user-authored task (not agent-generated). Returns the new task."
  [db project-id user-id {:keys [title description task-type estimated-minutes]}]
  (when (projects-persistence/get-project db project-id user-id)
    (persistence/create-task! db {:project-id        project-id
                                  :user-id           user-id
                                  :title             title
                                  :description       description
                                  :task-type         (or task-type "action")
                                  :estimated-minutes estimated-minutes})))

(defn update-task!
  "Partial update on a task. Returns the updated task."
  [db project-id user-id task-id updates]
  (when (projects-persistence/get-project db project-id user-id)
    (persistence/update-task! db task-id updates)))

(defn delete-task!
  "Hard-delete a task. Returns the deleted rows."
  [db project-id user-id task-id]
  (when (projects-persistence/get-project db project-id user-id)
    (persistence/delete-task! db task-id)))

(defn reorder-tasks!
  "Replace the position sequence for a project's tasks.
   positions is a seq of {:id uuid :position int}."
  [db project-id user-id positions]
  (when (projects-persistence/get-project db project-id user-id)
    (persistence/bulk-reorder! db positions)))

(defn get-task-status
  "Return {:pending-count N :total-count N} for a project."
  [db project-id user-id]
  (when (projects-persistence/get-project db project-id user-id)
    (persistence/get-task-status db project-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task generation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-task-generation!
  "Generate tasks for a project using the task planner, persist them, and
   optionally stream a tasks_generated SSE event to output-stream.
   Returns the inserted task rows.
   opts may contain :step-run-id for workflow traceability."
  [db task-planner project user-id output-stream {:keys [step-run-id]}]
  (let [project-id     (:id project)
        generation-run (persistence/create-generation-run! db project-id user-id)
        task-maps      (planner/generate-task-list task-planner project [])
        tasks          (if (seq task-maps)
                         (persistence/bulk-create-tasks! db project-id user-id task-maps
                                                         {:generation-run-id    (:id generation-run)
                                                          :workflow-step-run-id step-run-id})
                         [])]
    (when output-stream
      (write-sse-event! output-stream {:event "tasks_generated" :data tasks}))
    tasks))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Clarification step (called by workflow executor)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn clarify-description-step!
  "Called by the workflow executor for a :clarify output-kind step.
   Synchronously calls assess-description. If ready, marks step completed.
   If not ready, sets step to awaiting_input with questions as output.
   Returns {:awaiting-input bool}."
  [db task-planner project step-run output-stream]
  (let [step-run-id           (:id step-run)
        {:keys [ready reply]} (planner/assess-description task-planner project [])]
    (if ready
      (do
        (let [completed (workflows-persistence/update-step-run!
                         db step-run-id
                         {:status       "completed"
                          :output       (or reply "Description is sufficient.")
                          :completed-at (utils/now)})]
          (when output-stream
            (write-sse-event! output-stream {:event "step_complete" :data completed})))
        {:awaiting-input false})
      (do
        (let [updated (workflows-persistence/update-step-run!
                       db step-run-id
                       {:status "awaiting_input"
                        :output (json/encode {:reply reply})})]
          (when output-stream
            (write-sse-event! output-stream {:event "step_complete" :data updated})))
        {:awaiting-input true}))))
