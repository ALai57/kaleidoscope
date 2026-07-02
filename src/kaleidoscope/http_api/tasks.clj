(ns kaleidoscope.http-api.tasks
  (:require [kaleidoscope.api.authentication :as oidc]
            [kaleidoscope.api.tasks :as tasks-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.tasks.planner :as planner]
            [ring.util.http-response :refer [not-found ok]]
            [taoensso.timbre :as log]))

(defn- get-task-planner
  "Resolve a task planner from the request components.
   Uses LLMTaskPlanner when an api-key is available; falls back to mock."
  [components]
  (if-let [api-key (:api-key (:workflow-executor components))]
    (planner/make-llm-planner api-key)
    (planner/make-mock-planner)))

(def reitit-task-routes
  ["/projects/:project-id/tasks"
   {:tags     ["tasks"]
    :security [{:andrewslai-pkce ["roles" "profile"]}]
    :parameters {:path {:project-id :uuid}}}

   ;; --- Task collection ---
   ["" {:get  {:summary "List tasks for a project (ordered by position)"
               :handler (fn [{:keys [components parameters query-params] :as request}]
                          (let [user-id    (oidc/get-verified-email (:identity request))
                                project-id (:project-id (:path parameters))
                                status     (:status query-params)]
                            (if-let [tasks (tasks-api/list-tasks
                                            (:database components) project-id user-id
                                            {:status status})]
                              (ok tasks)
                              (not-found {:reason "Project not found"}))))}

        :post {:summary "Create a task"
               :handler (fn [{:keys [components body-params parameters] :as request}]
                          (let [user-id    (oidc/get-verified-email (:identity request))
                                project-id (:project-id (:path parameters))]
                            (if-let [task (tasks-api/create-task!
                                           (:database components) project-id user-id body-params)]
                              (ok task)
                              (not-found {:reason "Project not found"}))))}}]

   ;; --- Task status summary (for low-task nudge) ---
   ["/status"
    {:get {:summary "Return pending and total task counts for a project"
           :handler (fn [{:keys [components parameters] :as request}]
                      (let [user-id    (oidc/get-verified-email (:identity request))
                            project-id (:project-id (:path parameters))]
                        (if-let [status (tasks-api/get-task-status
                                         (:database components) project-id user-id)]
                          (ok status)
                          (not-found {:reason "Project not found"}))))}}]

   ;; --- Reorder (must be before /:task-id to avoid route collision) ---
   ["/reorder"
    {:put {:summary "Replace the full position sequence for a project's tasks"
           :handler (fn [{:keys [components body-params parameters] :as request}]
                      (let [user-id    (oidc/get-verified-email (:identity request))
                            project-id (:project-id (:path parameters))
                            positions  (mapv (fn [{:keys [id position]}]
                                               {:id       (parse-uuid (str id))
                                                :position position})
                                             body-params)]
                        (if (nil? (tasks-api/reorder-tasks!
                                   (:database components) project-id user-id positions))
                          (not-found {:reason "Project not found"})
                          {:status 204})))}}]

   ;; --- Task generation (SSE) ---
   ["/generate"
    {:post {:summary "Generate tasks and append them to the task list (SSE)"
            :handler (fn [{:keys [components parameters] :as request}]
                       (let [user-id    (oidc/get-verified-email (:identity request))
                             project-id (:project-id (:path parameters))
                             db         (:database components)
                             task-planner (get-task-planner components)]
                         (if-let [project (projects-persistence/get-project
                                           db project-id user-id)]
                           (hu/sse-response
                            (fn [output-stream]
                              (try
                                (tasks-api/run-task-generation!
                                 db task-planner project user-id output-stream {})
                                (let [writer (java.io.OutputStreamWriter. output-stream "UTF-8")]
                                  (.write writer "data: [DONE]\n\n")
                                  (.flush writer))
                                (catch Exception e
                                  (log/errorf "Task generation error for project %s: %s"
                                              project-id e)))))
                           (not-found {:reason "Project not found"}))))}}]

   ;; --- Individual task ---
   ["/:task-id"
    {:parameters {:path {:project-id :uuid :task-id :uuid}}}

    ["" {:put    {:summary "Partially update a task (title, description, status, task_type, estimated_minutes)"
                  :handler (fn [{:keys [components body-params parameters] :as request}]
                             (let [user-id    (oidc/get-verified-email (:identity request))
                                   project-id (:project-id (:path parameters))
                                   task-id    (:task-id (:path parameters))]
                               (if-let [task (tasks-api/update-task!
                                              (:database components) project-id user-id task-id
                                              body-params)]
                                 (ok task)
                                 (not-found {:reason "Project or task not found"}))))}

         :delete {:summary "Hard delete a task"
                  :handler (fn [{:keys [components parameters] :as request}]
                             (let [user-id    (oidc/get-verified-email (:identity request))
                                   project-id (:project-id (:path parameters))
                                   task-id    (:task-id (:path parameters))]
                               (if (nil? (tasks-api/delete-task!
                                          (:database components) project-id user-id task-id))
                                 (not-found {:reason "Project not found"})
                                 {:status 204})))}}]]])
