(ns kaleidoscope.http-api.workflows
  (:require [kaleidoscope.api.workflows :as workflows-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [ring.util.http-response :refer [conflict not-found ok]]
            [taoensso.timbre :as log]))

;; Each step position runs at least one Claude call, and parallel steps fan
;; out into concurrent calls (see api.workflows/run-parallel-steps!) — this
;; caps the worst-case cost/fan-out of a single user-defined workflow.
;; Execution-mode/output-kind are restricted to the values the execution
;; engine actually dispatches on; anything else is silently inert there but
;; rejecting it here is cheaper than debugging a step that never runs.
(def WorkflowStep
  [:map
   [:name [:string {:min 1 :max 200}]]
   [:description {:optional true} [:string {:max 2000}]]
   [:position {:optional true} :int]
   [:agent-type {:optional true} [:string {:max 100}]]
   [:output-kind {:optional true} [:enum "clarify" "text" "score" "decision" "tasks"]]
   [:execution-mode {:optional true} [:enum "sequential" "parallel" "fan_in"]]
   [:loop-until {:optional true} [:maybe [:string {:max 200}]]]
   [:requires {:optional true} [:vector [:string {:max 100}]]]])

(def WorkflowRequest
  [:map
   [:name [:string {:min 1 :max 200}]]
   [:description {:optional true} [:string {:max 5000}]]
   [:is-default {:optional true} :boolean]
   [:steps {:optional true} [:vector {:max 20} WorkflowStep]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; /workflows  — CRUD
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def reitit-workflow-routes
  ["/workflows"
   {:tags     ["workflows"]
    :security [{:andrewslai-pkce ["roles" "profile"]}]}

   ["" {:get {:summary "List workflows for the authenticated user"
              :handler (fn [{:keys [components] :as request}]
                         (ok (workflows-api/get-workflows (:database components)
                                                          (:user-id (:identity request)))))}

        :post {:summary    "Create a workflow"
               ;; Creating a workflow is cheap (no LLM call), but it defines
               ;; how many Claude calls *running* it will make later — rate
               ;; limit creation too so the step-count cap can't be
               ;; sidestepped by rapidly creating many 20-step workflows.
               :rate-limit {:max-requests 10 :window-ms 60000}
               :parameters {:body WorkflowRequest}
               :handler    (fn [{:keys [components parameters] :as request}]
                             (ok (workflows-api/create-workflow!
                                  (:database components)
                                  (:user-id (:identity request))
                                  (:body parameters))))}}]

   ["/:workflow-id"
    {:parameters {:path {:workflow-id :uuid}}}

    ["" {:get {:summary "Get a workflow with its steps"
               :handler (fn [{:keys [components parameters] :as request}]
                          (let [user-id     (:user-id (:identity request))
                                workflow-id (:workflow-id (:path parameters))]
                            (if-let [wf (workflows-api/get-workflow
                                         (:database components) user-id workflow-id)]
                              (ok wf)
                              (not-found {:reason "Workflow not found"}))))}

         :put {:summary "Update a workflow (status, name, description, steps)"
               :handler (fn [{:keys [components body-params parameters] :as request}]
                          (let [user-id     (:user-id (:identity request))
                                workflow-id (:workflow-id (:path parameters))]
                            (if-let [wf (workflows-api/update-workflow!
                                         (:database components) user-id workflow-id body-params)]
                              (ok wf)
                              (not-found {:reason "Workflow not found"}))))}

         :delete {:summary "Delete a workflow (blocked if is_default=true)"
                  :handler (fn [{:keys [components parameters] :as request}]
                             (let [user-id     (:user-id (:identity request))
                                   workflow-id (:workflow-id (:path parameters))
                                   result      (workflows-api/delete-workflow!
                                                (:database components) user-id workflow-id)]
                               (cond
                                 (:error result) (case (:error result)
                                                   :cannot-delete-default
                                                   (conflict {:reason "Cannot delete a default workflow"})
                                                   (not-found {:reason "Workflow not found"}))
                                 :else           {:status 204})))}}]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; /projects/:project-id/workflow-*  — nested under projects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def reitit-project-workflow-routes
  ["/projects/:project-id"
   {:tags     ["workflows"]
    :security [{:andrewslai-pkce ["roles" "profile"]}]
    :parameters {:path {:project-id :uuid}}}

   ;; --- Recommendation ---
   ["/workflow-recommendation"
    {:post {:summary    "Rank live workflows against this project"
            :rate-limit {:max-requests 10 :window-ms 60000}
            :handler (fn [{:keys [components parameters] :as request}]
                       (let [user-id    (:user-id (:identity request))
                             project-id (:project-id (:path parameters))
                             db         (:database components)
                             executor   (:workflow-executor components)]
                         (if-let [recs (workflows-api/get-workflow-recommendation
                                        db executor project-id user-id)]
                           (ok recs)
                           (not-found {:reason "Project not found"}))))}}]

   ;; --- Runs collection ---
   ["/workflow-runs"
    ["" {:get {:summary "List workflow runs for a project"
               :handler (fn [{:keys [components parameters] :as request}]
                          (let [user-id    (:user-id (:identity request))
                                project-id (:project-id (:path parameters))]
                            (if-let [runs (workflows-api/get-workflow-runs
                                           (:database components) project-id user-id)]
                              (ok runs)
                              (not-found {:reason "Project not found"}))))}

         :post {:summary    "Start a new workflow run (body: {workflow_id?, mode, scrutiny?, target_score?})"
                :rate-limit {:max-requests 5 :window-ms 60000}
                :parameters {:body [:map
                                    [:workflow-id {:optional true} :uuid]
                                    [:mode {:optional true} [:enum "manual" "autonomous"]]
                                    [:scrutiny {:optional true} [:enum "quick" "standard" "rigorous"]]
                                    [:target-score {:optional true} [:double {:min 0 :max 10}]]]}
                :handler    (fn [{:keys [components parameters] :as request}]
                              (let [user-id      (:user-id (:identity request))
                                    project-id   (:project-id (:path parameters))
                                    body         (:body parameters)
                                    workflow-id  (:workflow-id body)
                                    mode         (:mode body "manual")
                                    scrutiny     (:scrutiny body)
                                    target-score (:target-score body)]
                                (if-let [run (workflows-api/create-run!
                                              (:database components)
                                              project-id user-id
                                              {:workflow-id  workflow-id
                                               :mode         mode
                                               :scrutiny     scrutiny
                                               :target-score target-score})]
                                  (ok run)
                                  (not-found {:reason "Project not found"}))))}}]

    ;; --- Individual run ---
    ["/:run-id"
     {:parameters {:path {:project-id :uuid :run-id :uuid}}}

     ["" {:get {:summary "Get a workflow run with all step runs"
                :handler (fn [{:keys [components parameters] :as request}]
                           (let [user-id    (:user-id (:identity request))
                                 project-id (:project-id (:path parameters))
                                 run-id     (:run-id (:path parameters))]
                             (if-let [run (workflows-api/get-workflow-run
                                           (:database components) run-id project-id user-id)]
                               (ok run)
                               (not-found {:reason "Run not found"}))))}

          :put {:summary "Update run mode (manual | autonomous)"
                :handler (fn [{:keys [components body-params parameters] :as request}]
                           (let [user-id    (:user-id (:identity request))
                                 project-id (:project-id (:path parameters))
                                 run-id     (:run-id (:path parameters))
                                 mode       (:mode body-params)]
                             (if-let [run (workflows-api/update-run-mode!
                                           (:database components)
                                           run-id project-id user-id mode)]
                               (ok run)
                               (not-found {:reason "Run not found"}))))}}]

     ;; --- Rounds timeline ---
     ["/rounds"
      {:get {:summary "Get the rounds timeline for a loop workflow run"
             :handler (fn [{:keys [components parameters] :as request}]
                        (let [user-id    (:user-id (:identity request))
                              project-id (:project-id (:path parameters))
                              run-id     (:run-id (:path parameters))]
                          (if-let [rounds (workflows-api/get-run-rounds
                                           (:database components) run-id project-id user-id)]
                            (ok rounds)
                            (not-found {:reason "Run not found"}))))}}]

     ;; --- Force proceed ---
     ["/force-proceed"
      {:post {:summary    "Skip remaining advisor rounds and immediately generate tasks"
              :rate-limit {:max-requests 10 :window-ms 60000}
              :handler (fn [{:keys [components parameters] :as request}]
                         (let [user-id    (:user-id (:identity request))
                               project-id (:project-id (:path parameters))
                               run-id     (:run-id (:path parameters))
                               db         (:database components)
                               executor   (:workflow-executor components)]
                           (if-let [run (workflows-api/force-proceed!
                                         db executor project-id user-id run-id)]
                             (ok run)
                             (not-found {:reason "Run not found"}))))}}]

     ;; --- Advance (SSE) ---
     ["/advance"
      {:post {:summary    "Execute the current pending step (SSE). In autonomous mode, continues until all steps are done."
              ;; Autonomous mode loops server-side through every remaining
              ;; step on a single call, so this limit bounds how many such
              ;; loops a caller can *start* per minute, not steps within one.
              :rate-limit {:max-requests 10 :window-ms 60000}
              :handler (fn [{:keys [components parameters] :as request}]
                         (let [user-id    (:user-id (:identity request))
                               project-id (:project-id (:path parameters))
                               run-id     (:run-id (:path parameters))
                               db         (:database components)
                               executor   (:workflow-executor components)]
                           (hu/sse-response
                            (fn [output-stream]
                              (try
                                (let [result (workflows-api/advance-step!
                                              db executor project-id user-id run-id output-stream)
                                      writer  (java.io.OutputStreamWriter. output-stream "UTF-8")]
                                  (when (= :already-advancing (:error result))
                                    (hu/write-sse-event! writer
                                                         {:event "error"
                                                          :data  {:reason "already-advancing"}}))
                                  (.write writer "data: [DONE]\n\n")
                                  (.flush writer))
                                (catch Exception e
                                  (log/errorf "advance-step! error: %s" e)))))))}}]

     ;; --- Skip step ---
     ["/steps/:step-run-id/skip"
      {:parameters {:path {:project-id :uuid :run-id :uuid :step-run-id :uuid}}
       :post {:summary "Skip a pending step"
              :handler (fn [{:keys [components parameters] :as request}]
                         (let [user-id     (:user-id (:identity request))
                               project-id  (:project-id (:path parameters))
                               run-id      (:run-id (:path parameters))
                               step-run-id (:step-run-id (:path parameters))]
                           (if-let [run (workflows-api/skip-step!
                                         (:database components)
                                         project-id user-id run-id step-run-id)]
                             (ok run)
                             (not-found {:reason "Run or step not found"}))))}}]

     ;; --- Respond to awaiting_input step ---
     ["/steps/:step-run-id/respond"
      {:parameters {:path {:project-id :uuid :run-id :uuid :step-run-id :uuid}}
       :post {:summary    "Submit answers for an awaiting_input step; appends answers to project.description and resumes the workflow"
              :rate-limit {:max-requests 10 :window-ms 60000}
              :handler (fn [{:keys [components body-params parameters] :as request}]
                         (let [user-id     (:user-id (:identity request))
                               project-id  (:project-id (:path parameters))
                               run-id      (:run-id (:path parameters))
                               step-run-id (:step-run-id (:path parameters))
                               answers     (or (:answers body-params) [])]
                           (if-let [run (workflows-api/respond-to-step!
                                         (:database components)
                                         (:workflow-executor components)
                                         project-id user-id run-id step-run-id answers)]
                             (ok run)
                             (not-found {:reason "Run or step not found, or step not awaiting input"}))))}}]

     ;; --- Custom step (SSE) ---
     ["/custom-step"
      {:post {:summary    "Inject and execute a custom ad-hoc step (SSE). Returns step_complete + recommendation events."
              :rate-limit {:max-requests 5 :window-ms 60000}
              :parameters {:body [:map
                                  [:name {:optional true} [:string {:max 200}]]
                                  [:description {:optional true} [:string {:max 2000}]]
                                  [:agent-type {:optional true} [:string {:max 100}]]]}
              :handler (fn [{:keys [components parameters] :as request}]
                         (let [user-id    (:user-id (:identity request))
                               project-id (:project-id (:path parameters))
                               run-id     (:run-id (:path parameters))
                               db         (:database components)
                               executor   (:workflow-executor components)]
                           (hu/sse-response
                            (fn [output-stream]
                              (try
                                (let [result (workflows-api/run-custom-step!
                                              db executor project-id user-id run-id
                                              (:body parameters)
                                              output-stream)
                                      writer (java.io.OutputStreamWriter. output-stream "UTF-8")]
                                  (hu/write-sse-event! writer {:event "recommendation"
                                                               :data  (:recommendation result)})
                                  (.write writer "data: [DONE]\n\n")
                                  (.flush writer))
                                (catch Exception e
                                  (log/errorf "custom-step! error: %s" e)))))))}}]]]])
