(ns kaleidoscope.models.tasks)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Enums
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; task_type is an open label — not validated as a closed set.
;; Well-known values: action, research, purchase, review, development, investigate

(def TaskStatus
  [:enum "pending" "in_progress" "completed"])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task entities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ProjectTask
  [:map
   [:id                    :uuid]
   [:project-id            :uuid]
   [:user-id               :string]
   [:title                 :string]
   [:description           {:optional true} [:maybe :string]]
   [:task-type             :string]
   [:status                TaskStatus]
   [:position              :int]
   [:estimated-minutes     {:optional true} [:maybe :int]]
   [:generation-run-id     {:optional true} [:maybe :uuid]]
   [:workflow-step-run-id  {:optional true} [:maybe :uuid]]
   [:created-at            inst?]
   [:updated-at            inst?]])

(def CreateTaskRequest
  [:map
   [:title             :string]
   [:description       {:optional true} [:maybe :string]]
   [:task-type         {:optional true} :string]
   [:estimated-minutes {:optional true} [:maybe :int]]])

(def UpdateTaskRequest
  [:map
   [:title             {:optional true} :string]
   [:description       {:optional true} [:maybe :string]]
   [:status            {:optional true} TaskStatus]
   [:task-type         {:optional true} :string]
   [:estimated-minutes {:optional true} [:maybe :int]]])

(def TaskReorderItem
  [:map
   [:id       :uuid]
   [:position :int]])

(def TaskGenerationRun
  [:map
   [:id         :uuid]
   [:project-id :uuid]
   [:user-id    :string]
   [:created-at inst?]])

(def TaskStatus-response
  [:map
   [:pending-count :int]
   [:total-count   :int]])
