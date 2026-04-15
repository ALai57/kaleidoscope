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
    :name          "Project Coach"
    :short-name    "Coach"
    :avatar        "🐬"
    :color         "#0891b2"
    :system-prompt agents/coach-system-prompt
    :is-default    true}
   {:agent-type    "pm"
    :name          "Product Manager"
    :short-name    "Product"
    :avatar        "🦊"
    :color         "#7c3aed"
    :system-prompt agents/pm-agent-system-prompt
    :is-default    true}
   {:agent-type    "engineering_lead"
    :name          "Engineering Lead"
    :short-name    "Architect"
    :avatar        "🦉"
    :color         "#0369a1"
    :system-prompt agents/engineering-lead-agent-system-prompt
    :is-default    true}
   {:agent-type    "task_planner"
    :name          "Task Planner"
    :short-name    "Planner"
    :avatar        "📋"
    :color         "#059669"
    :system-prompt agents/task-planner-generation-system-prompt
    :is-default    true}])

(defn seed-default-agent-definitions!
  "Idempotently seed the pre-defined agent definitions for a user.
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

(defn create-agent-definition!
  "Create a new (custom) agent definition for a user."
  [db user-id body]
  (persistence/create-agent-definition! db (assoc body :user-id user-id :is-default false)))

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
