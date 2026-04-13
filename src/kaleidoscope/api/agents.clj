(ns kaleidoscope.api.agents
  (:require [honey.sql :as hsql]
            [kaleidoscope.persistence.agents :as persistence]
            [kaleidoscope.scoring.agents :as agents]
            [kaleidoscope.utils.core :as utils]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]
            [taoensso.timbre :as log]))

(def default-agent-definitions
  [{:agent-type    "coach"
    :display-name  "Project Coach"
    :avatar        "🐬"
    :system-prompt agents/coach-system-prompt
    :is-default    true}
   {:agent-type    "pm"
    :display-name  "Product Manager"
    :avatar        "🦊"
    :system-prompt agents/pm-agent-system-prompt
    :is-default    true}
   {:agent-type    "engineering_lead"
    :display-name  "Engineering Lead"
    :avatar        "🦉"
    :system-prompt agents/engineering-lead-agent-system-prompt
    :is-default    true}
   {:agent-type    "task_planner"
    :display-name  "Task Planner"
    :avatar        "📋"
    :system-prompt agents/task-planner-generation-system-prompt
    :is-default    true}])

(defn seed-default-agent-definitions!
  "Idempotently seed the three pre-defined agent definitions for a user.
   Uses INSERT ... ON CONFLICT DO NOTHING for race safety."
  [db user-id]
  (doseq [defn default-agent-definitions]
    (next/execute! db
                   (hsql/format
                    {:insert-into :agent-definitions
                     :values      [(assoc defn
                                          :user-id    user-id
                                          :id         (utils/uuid)
                                          :created-at (utils/now)
                                          :updated-at (utils/now))]
                     :on-conflict [:user-id :agent-type]
                     :do-nothing  true})
                   {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-agent-definitions
  "Return all agent definitions for a user, seeding defaults on first access."
  [db user-id]
  (seed-default-agent-definitions! db user-id)
  (persistence/get-agent-definitions db user-id))

(defn update-agent-definition!
  "Update a single agent definition. Verifies ownership. Returns nil if not found."
  [db user-id definition-id updates]
  (when-let [defn (persistence/get-agent-definition db definition-id)]
    (when (= (:user-id defn) user-id)
      (persistence/update-agent-definition! db definition-id updates))))

(defn get-custom-system-prompt
  "Return the user's custom system prompt for agent-type, or nil if not customised.
   Does NOT seed defaults — called on every step execution, keep it fast."
  [db user-id agent-type]
  (when-let [defn (first (filter #(= (:agent-type %) agent-type)
                                 (persistence/get-agent-definitions db user-id)))]
    (:system-prompt defn)))
