(ns kaleidoscope.persistence.agents
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]))

(def ^:private get-agent-definitions-raw
  (rdbms/make-finder :agent-definitions))

(defn get-agent-definitions
  [db user-id]
  (get-agent-definitions-raw db {:user-id user-id}))

(defn get-agent-definition
  [db definition-id]
  (first (get-agent-definitions-raw db {:id definition-id})))

(defn create-agent-definition!
  [db {:keys [user-id agent-type name short-name avatar color system-prompt is-default]}]
  (let [now (utils/now)]
    (first (rdbms/insert! db
                          :agent-definitions
                          {:id            (utils/uuid)
                           :user-id       user-id
                           :agent-type    agent-type
                           :name          name
                           :short-name    (or short-name "")
                           :avatar        avatar
                           :color         (or color "")
                           :system-prompt system-prompt
                           :is-default    (boolean is-default)
                           :created-at    now
                           :updated-at    now}
                          :ex-subtype :UnableToCreateAgentDefinition))))

(defn update-agent-definition!
  [db definition-id {:keys [name short-name avatar color system-prompt]}]
  (let [now (utils/now)]
    (first (rdbms/update! db
                          :agent-definitions
                          (cond-> {:id         definition-id
                                   :updated-at now}
                            name          (assoc :name name)
                            short-name    (assoc :short-name short-name)
                            avatar        (assoc :avatar avatar)
                            color         (assoc :color color)
                            system-prompt (assoc :system-prompt system-prompt))))))
