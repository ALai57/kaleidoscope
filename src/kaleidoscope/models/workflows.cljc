(ns kaleidoscope.models.workflows)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Enums
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def WorkflowStatus
  [:enum "draft" "live" "archived"])

(def RunStatus
  [:enum "pending" "in_progress" "completed" "failed"])

(def StepRunStatus
  [:enum "pending" "running" "completed" "skipped" "failed"])

(def RunMode
  [:enum "manual" "autonomous"])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow definitions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def WorkflowStep
  [:map
   [:id          :uuid]
   [:workflow-id :uuid]
   [:position    :int]
   [:name        :string]
   [:description :string]
   [:created-at  inst?]
   [:updated-at  inst?]])

(def Workflow
  [:map
   [:id          :uuid]
   [:user-id     :string]
   [:name        :string]
   [:description [:maybe :string]]
   [:status      WorkflowStatus]
   [:is-default  :boolean]
   [:created-at  inst?]
   [:updated-at  inst?]])

(def WorkflowWithSteps
  [:map
   [:id          :uuid]
   [:user-id     :string]
   [:name        :string]
   [:description [:maybe :string]]
   [:status      WorkflowStatus]
   [:is-default  :boolean]
   [:steps       [:sequential WorkflowStep]]
   [:created-at  inst?]
   [:updated-at  inst?]])

(def CreateWorkflowStep
  [:map
   [:name        :string]
   [:description :string]
   [:position    {:optional true} :int]])

(def CreateWorkflowRequest
  [:map
   [:name        :string]
   [:description {:optional true} [:maybe :string]]
   [:steps       {:optional true} [:sequential CreateWorkflowStep]]])

(def UpdateWorkflowRequest
  [:map
   [:name        {:optional true} :string]
   [:description {:optional true} [:maybe :string]]
   [:status      {:optional true} WorkflowStatus]
   [:steps       {:optional true} [:sequential CreateWorkflowStep]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow runs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def StepRun
  [:map
   [:id              :uuid]
   [:workflow-run-id :uuid]
   [:step-id         [:maybe :uuid]]
   [:position        :int]
   [:name            :string]
   [:description     :string]
   [:agent-type      :string]
   [:is-custom       :boolean]
   [:status          StepRunStatus]
   [:output          {:optional true} [:maybe :string]]
   [:started-at      {:optional true} [:maybe inst?]]
   [:completed-at    {:optional true} [:maybe inst?]]])

(def WorkflowRun
  [:map
   [:id           :uuid]
   [:project-id   :uuid]
   [:workflow-id  [:maybe :uuid]]
   [:status       RunStatus]
   [:mode         RunMode]
   [:current-step :int]
   [:created-at   inst?]])

(def WorkflowRunWithSteps
  [:map
   [:id            :uuid]
   [:project-id    :uuid]
   [:workflow-id   [:maybe :uuid]]
   [:workflow-name [:maybe :string]]
   [:status        RunStatus]
   [:mode          RunMode]
   [:current-step  :int]
   [:steps         [:sequential StepRun]]
   [:started-at    {:optional true} [:maybe inst?]]
   [:completed-at  {:optional true} [:maybe inst?]]
   [:created-at    inst?]])

(def WorkflowRecommendation
  [:map
   [:workflow-id :uuid]
   [:name        :string]
   [:confidence  number?]
   [:rationale   :string]])
