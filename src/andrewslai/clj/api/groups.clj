(ns andrewslai.clj.api.groups
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.utils.core :as utils]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as log]
            [clojure.set :as set]))

(defn get-id
  [group]
  (:id group))

(defn get-owner
  [group]
  (:owner-id group))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Groups
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^:private get-groups
  (rdbms/make-finder :groups))

#_(defn get-users-groups
    "Only return the groups owned by the requesting user"
    [database requester-id]
    (get-groups database {:owner-id requester-id}))

(defn create-group!
  [database {:keys [id] :as group}]
  (let [now (utils/now)]
    (rdbms/insert! database
                   :groups     (assoc group
                                      :created-at  now
                                      :modified-at now
                                      :id          (or id (utils/uuid)))
                   :ex-subtype :UnableToCreateGroup)))

(defn owns?
  [database requester-id group-id]
  (= requester-id (get-owner (first (get-groups database {:id group-id})))))

(defn delete-group!
  "Only allow a user to delete a group if they are the owner.
  The `user-id` is the identity of the user requesting the operation."
  [database requester-id group-id]
  (if (owns? database requester-id group-id)
    (rdbms/delete! database
                   :groups     group-id
                   :ex-subtype :UnableToDeleteGroup)
    (log/warnf "User %s does not have permissions to delete the group %s" requester-id group-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Members in groups
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^:private get-group-memberships
  (rdbms/make-finder :full-memberships))

(defn group-key
  [full-membership]
  (select-keys full-membership [:group-id
                                :group-modified-at :group-created-at
                                :display-name :owner-id]))

(defn select-membership-keys
  [membership]
  (select-keys membership [:membership-id
                           :membership-created-at
                           :alias
                           :email]))

(defn normalize-memberships
  "Group memberships are denormalized - normalize them in Clojure so we have a
  nested data structure of the form:
  {:group-id                \"123\"
   ...
   :other-group-information \"stuff\"
   ...
   :memberships [{:email                 string?
                  :alias                 string?
                  :membership-id         string?
                  :membership-created-at inst?}]}"
  [group-memberships]
  (->> group-memberships
       (group-by group-key)
       (reduce-kv (fn [acc group-key memberships]
                    (conj acc (-> group-key
                                  (assoc :memberships (map select-membership-keys memberships)))))
                  [])))

(defn get-users-groups
  "Only return the groups owned by the requesting user"
  [database requester-id]
  (normalize-memberships (get-group-memberships database {:owner-id requester-id})))

(defn add-users-to-group!
  "This functionality makes a strong assumption: that users are uniquely identified
  by their email addresses.

  We do this in order to simplify the system: by adding email addresses to the
  group, we don't have to worry about the problem of 'What is this users' unique
  ID in Keycloak?"
  [database requester-id group-id users]
  (if (owns? database requester-id group-id)
    (let [now-time    (utils/now)
          memberships (vec (for [{:keys [email alias] :as user} (if (vector? users) users [users])]
                             {:id         (utils/uuid)
                              :email      email
                              :alias      alias
                              :group-id   group-id
                              :created-at now-time}))]
      (vec (rdbms/insert! database
                          :user-group-memberships memberships
                          :ex-subtype             :UnableToAddUserToGroup)))
    (log/warnf "User %s does not have permissions to add users to group %s" requester-id group-id)))

(defn remove-user-from-group!
  [database requester-id group-id user-group-membership-id]
  (if (owns? database requester-id group-id)
    (rdbms/delete! database
                   :user-group-memberships user-group-membership-id
                   :ex-subtype :UnableToDeleteUserFromGroup)
    (log/warnf "User %s does not have permissions to delete users from group %s" requester-id group-id)))

(comment


  )
