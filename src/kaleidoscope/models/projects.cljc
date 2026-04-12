(ns kaleidoscope.models.projects)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Projects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ProjectStatus
  [:enum "idea" "developing" "executing"])

(def Project
  [:map
   [:id          :uuid]
   [:user-id     :string]
   [:title       :string]
   [:description [:maybe :string]]
   [:status      ProjectStatus]
   [:created-at  inst?]
   [:updated-at  inst?]])

(def CreateProjectRequest
  [:map
   [:title       :string]
   [:description {:optional true} [:maybe :string]]])

(def UpdateProjectRequest
  [:map
   [:title       {:optional true} :string]
   [:description {:optional true} [:maybe :string]]
   [:status      {:optional true} ProjectStatus]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Score Definitions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ScorerType
  [:enum "pm" "engineering_lead" "general"])

(def ScoreDimensionDefinition
  [:map
   [:id                  {:optional true} :uuid]
   [:score-definition-id {:optional true} :uuid]
   [:name                :string]
   [:criteria            :string]
   [:position            {:optional true} :int]])

(def ScoreDefinition
  [:map
   [:id          :uuid]
   [:user-id     :string]
   [:name        :string]
   [:description :string]
   [:scorer-type ScorerType]
   [:is-default  :boolean]
   [:created-at  inst?]
   [:updated-at  inst?]])

(def ScoreDefinitionWithDimensions
  [:map
   [:id          :uuid]
   [:user-id     :string]
   [:name        :string]
   [:description :string]
   [:scorer-type ScorerType]
   [:is-default  :boolean]
   [:created-at  inst?]
   [:updated-at  inst?]
   [:dimensions  [:sequential ScoreDimensionDefinition]]])

(def CreateScoreDefinitionRequest
  [:map
   [:name        :string]
   [:description :string]
   [:scorer-type {:optional true} ScorerType]
   [:dimensions  [:sequential
                  [:map
                   [:name     :string]
                   [:criteria :string]]]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Score Runs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ScoreDimensionResult
  [:map
   [:id             {:optional true} :uuid]
   [:score-run-id   {:optional true} :uuid]
   [:dimension-name :string]
   [:value          [:maybe number?]]
   [:rationale      [:maybe :string]]])

(def ScoreRun
  [:map
   [:id                  :uuid]
   [:project-id          :uuid]
   [:score-definition-id :uuid]
   [:version             :int]
   [:overall             [:maybe number?]]
   [:scored-at           inst?]])

(def ScoreRunWithDetails
  [:map
   [:id         :uuid]
   [:version    :int]
   [:scored-at  inst?]
   [:definition [:map
                 [:id          :uuid]
                 [:name        :string]
                 [:scorer-type ScorerType]]]
   [:overall    [:maybe number?]]
   [:dimensions [:sequential ScoreDimensionResult]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Notes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def NoteSource
  [:enum "text" "voice"])

(def Note
  [:map
   [:id         :uuid]
   [:project-id :uuid]
   [:content    :string]
   [:source     NoteSource]
   [:created-at inst?]])

(def CreateNoteRequest
  [:map
   [:content :string]
   [:source  {:optional true} NoteSource]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Conversations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def AgentType
  [:enum "coach" "pm" "engineering_lead"])

(def ConversationRole
  [:enum "user" "assistant"])

(def ConversationMessage
  [:map
   [:id         :uuid]
   [:project-id :uuid]
   [:agent-type AgentType]
   [:role       ConversationRole]
   [:content    :string]
   [:created-at inst?]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Skills
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def SkillStatus
  [:enum "identified" "learning" "mastered"])

(def Skill
  [:map
   [:id          :uuid]
   [:project-id  :uuid]
   [:parent-id   [:maybe :uuid]]
   [:name        :string]
   [:description [:maybe :string]]
   [:status      SkillStatus]
   [:position    :int]
   [:created-at  inst?]])

(def SkillNode
  [:map
   [:id          :uuid]
   [:name        :string]
   [:description [:maybe :string]]
   [:status      SkillStatus]
   [:position    :int]
   [:children    [:sequential [:ref ::SkillNode]]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Example data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def example-project
  {:id          #uuid "00000000-0000-0000-0000-000000000001"
   :user-id     "test@test.com"
   :title       "Personal Project Manager"
   :description "A tool to manage personal side projects with AI-assisted scoring"
   :status      "idea"
   :created-at  "2026-04-12T00:00:00Z"
   :updated-at  "2026-04-12T00:00:00Z"})

(def example-score-definition
  {:id          #uuid "00000000-0000-0000-0000-000000000010"
   :user-id     "test@test.com"
   :name        "Intent Clarity"
   :description "Evaluates how clearly the project's purpose and goals are articulated"
   :scorer-type "pm"
   :is-default  true
   :created-at  "2026-04-12T00:00:00Z"
   :updated-at  "2026-04-12T00:00:00Z"})
