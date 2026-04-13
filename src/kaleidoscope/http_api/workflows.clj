(ns kaleidoscope.http-api.workflows
  (:require [kaleidoscope.api.authentication :as oidc]
            [kaleidoscope.api.workflows :as workflows-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [ring.util.http-response :refer [conflict not-found ok]]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; /workflows  — CRUD
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def reitit-workflow-routes
  ["/workflows"
   {:tags     ["workflows"]
    :security [{:andrewslai-pkce ["roles" "profile"]}]}

   ["" {:get {:summary "List workflows for the authenticated user"
              :handler (fn [{:keys [components] :as request}]
                         (let [user-id (oidc/get-verified-email (:identity request))]
                           (ok (workflows-api/get-workflows (:database components) user-id))))}

        :post {:summary "Create a workflow"
               :handler (fn [{:keys [components body-params] :as request}]
                          (let [user-id (oidc/get-verified-email (:identity request))]
                            (ok (workflows-api/create-workflow!
                                 (:database components) user-id body-params))))}}]

   ["/:workflow-id"
    {:parameters {:path {:workflow-id string?}}}

    ["" {:get {:summary "Get a workflow with its steps"
               :handler (fn [{:keys [components path-params] :as request}]
                          (let [workflow-id (parse-uuid (:workflow-id path-params))]
                            (if-let [wf (workflows-api/get-workflow
                                         (:database components) workflow-id)]
                              (ok wf)
                              (not-found {:reason "Workflow not found"}))))}

         :put {:summary "Update a workflow (status, name, description, steps)"
               :handler (fn [{:keys [components body-params path-params] :as request}]
                          (let [workflow-id (parse-uuid (:workflow-id path-params))]
                            (if-let [wf (workflows-api/update-workflow!
                                         (:database components) workflow-id body-params)]
                              (ok wf)
                              (not-found {:reason "Workflow not found"}))))}

         :delete {:summary "Delete a workflow (blocked if is_default=true)"
                  :handler (fn [{:keys [components path-params] :as request}]
                             (let [workflow-id (parse-uuid (:workflow-id path-params))
                                   result      (workflows-api/delete-workflow!
                                                (:database components) workflow-id)]
                               (cond
                                 (:error result) (case (:error result)
                                                   :cannot-delete-default
                                                   (conflict {:reason "Cannot delete a default workflow"})
                                                   (not-found {:reason "Workflow not found"}))
                                 :else           {:status 204}))))}]]]

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; /projects/:project-id/workflow-*  — nested under projects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def reitit-project-workflow-routes
  ["/projects/:project-id"
   {:tags     ["workflows"]
    :security [{:andrewslai-pkce ["roles" "profile"]}]
    :parameters {:path {:project-id string?}}}

   ;; --- Recommendation ---
   ["/workflow-recommendation"
    {:post {:summary "Rank live workflows against this project"
            :handler (fn [{:keys [components path-params] :as request}]
                       (let [user-id    (oidc/get-verified-email (:identity request))
                             project-id (parse-uuid (:project-id path-params))
                             db         (:database components)
                             executor   (:workflow-executor components)]
                         (if-let [recs (workflows-api/get-workflow-recommendation
                                        db executor project-id user-id)]
                           (ok recs)
                           (not-found {:reason "Project not found"}))))}}]

   ;; --- Runs collection ---
   ["/workflow-runs"
    ["" {:get {:summary "List workflow runs for a project"
               :handler (fn [{:keys [components path-params] :as request}]
                          (let [user-id    (oidc/get-verified-email (:identity request))
                                project-id (parse-uuid (:project-id path-params))]
                            (if-let [runs (workflows-api/get-workflow-runs
                                           (:database components) project-id user-id)]
                              (ok runs)
                              (not-found {:reason "Project not found"}))))}

          :post {:summary "Start a new workflow run (body: {workflow_id?, mode})"
                 :handler (fn [{:keys [components body-params path-params] :as request}]
                            (let [user-id     (oidc/get-verified-email (:identity request))
                                  project-id  (parse-uuid (:project-id path-params))
                                  workflow-id (when-let [id (:workflow-id body-params)]
                                               (parse-uuid id))
                                  mode        (:mode body-params "manual")]
                              (if-let [run (workflows-api/create-run!
                                            (:database components)
                                            project-id user-id
                                            {:workflow-id workflow-id :mode mode})]
                                (ok run)
                                (not-found {:reason "Project not found"}))))}}]

    ;; --- Individual run ---
    ["/:run-id"
     {:parameters {:path {:run-id string?}}}

     ["" {:get {:summary "Get a workflow run with all step runs"
                :handler (fn [{:keys [components path-params] :as request}]
                           (let [user-id    (oidc/get-verified-email (:identity request))
                                 project-id (parse-uuid (:project-id path-params))
                                 run-id     (parse-uuid (:run-id path-params))]
                             (if-let [run (workflows-api/get-workflow-run
                                           (:database components) run-id project-id user-id)]
                               (ok run)
                               (not-found {:reason "Run not found"}))))}

          :put {:summary "Update run mode (manual | autonomous)"
                :handler (fn [{:keys [components body-params path-params] :as request}]
                           (let [user-id    (oidc/get-verified-email (:identity request))
                                 project-id (parse-uuid (:project-id path-params))
                                 run-id     (parse-uuid (:run-id path-params))
                                 mode       (:mode body-params)]
                             (if-let [run (workflows-api/update-run-mode!
                                           (:database components)
                                           run-id project-id user-id mode)]
                               (ok run)
                               (not-found {:reason "Run not found"}))))}}]

     ;; --- Advance (SSE) ---
     ["/advance"
      {:post {:summary "Execute the current pending step (SSE). In autonomous mode, continues until all steps are done."
              :handler (fn [{:keys [components path-params] :as request}]
                         (let [user-id    (oidc/get-verified-email (:identity request))
                               project-id (parse-uuid (:project-id path-params))
                               run-id     (parse-uuid (:run-id path-params))
                               db         (:database components)
                               executor   (:workflow-executor components)]
                           (hu/sse-response
                            (fn [output-stream]
                              (try
                                (workflows-api/advance-step!
                                 db executor project-id user-id run-id output-stream)
                                (let [writer (java.io.OutputStreamWriter. output-stream "UTF-8")]
                                  (.write writer "data: [DONE]\n\n")
                                  (.flush writer))
                                (catch Exception e
                                  (log/errorf "advance-step! error: %s" e)))))))}]

     ;; --- Skip step ---
     ["/steps/:step-run-id/skip"
      {:parameters {:path {:step-run-id string?}}
       :post {:summary "Skip a pending step"
              :handler (fn [{:keys [components path-params] :as request}]
                         (let [user-id     (oidc/get-verified-email (:identity request))
                               project-id  (parse-uuid (:project-id path-params))
                               run-id      (parse-uuid (:run-id path-params))
                               step-run-id (parse-uuid (:step-run-id path-params))]
                           (if-let [run (workflows-api/skip-step!
                                         (:database components)
                                         project-id user-id run-id step-run-id)]
                             (ok run)
                             (not-found {:reason "Run or step not found"}))))}}]

     ;; --- Custom step (SSE) ---
     ["/custom-step"
      {:post {:summary "Inject and execute a custom ad-hoc step (SSE). Returns step_complete + recommendation events."
              :handler (fn [{:keys [components body-params path-params] :as request}]
                         (let [user-id    (oidc/get-verified-email (:identity request))
                               project-id (parse-uuid (:project-id path-params))
                               run-id     (parse-uuid (:run-id path-params))
                               db         (:database components)
                               executor   (:workflow-executor components)]
                           (hu/sse-response
                            (fn [output-stream]
                              (try
                                (let [result (workflows-api/run-custom-step!
                                              db executor project-id user-id run-id
                                              body-params
                                              output-stream)
                                      writer (java.io.OutputStreamWriter. output-stream "UTF-8")]
                                  (hu/write-sse-event! writer {:event "recommendation"
                                                               :data  (:recommendation result)})
                                  (.write writer "data: [DONE]\n\n")
                                  (.flush writer))
                                (catch Exception e
                                  (log/errorf "custom-step! error: %s" e)))))))}]]]]])
