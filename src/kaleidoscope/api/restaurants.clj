(ns kaleidoscope.api.restaurants
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]
            [taoensso.timbre :as log]))

(defn get-owner
  [group]
  (:owner-id group))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Restaurants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-restaurants
  (rdbms/make-finder :articles))

;; rdbms/insert! and rdbms/update should handle created at and modified at
(defn create-restaurant!
  [db {:keys [url name] :as restaurant}]
  (log/infof "Creating Restaurant: %s" restaurant)
  (let [result (rdbms/insert! db restaurant :ex-subtype :UnableToCreateRestaurant)]
    (log/infof "Created Restaurant: %s" result)
    result))

(defn update-restaurant!
  [db {:keys [id] :as restaurant}]
  (log/infof "Updating Restaurant: %s" restaurant)
  (let [result (rdbms/update! db
                              :restaurants restaurant
                              :ex-subtype :UnableToCreateRestaurant)]
    (log/infof "Updated Restaurant: %s" result)
    result))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Groups
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^:private get-eater-groups
  (rdbms/make-finder :eater-groups))

(defn create-eater-group!
  [database {:keys [id] :as group}]
  (rdbms/insert! database
                 :eater-groups (assoc group :id (or id (utils/uuid)))
                 :ex-subtype :UnableToCreateEaterGroup))

(defn owns?
  [database requester-id group-id]
  (= requester-id (-> database
                      (get-eater-groups {:id group-id})
                      first
                      get-owner)))

(defn delete-eater-group!
  "Only allow a user to delete a group if they are the owner.
  The `user-id` is the identity of the user requesting the operation."
  [database requester-id group-id]
  (if (owns? database requester-id group-id)
    (rdbms/delete! database
                   :eater-groups     group-id
                   :ex-subtype :UnableToDeleteEaterGroup)
    (log/warnf "User %s does not have permissions to delete the group %s" requester-id group-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Members in groups
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-eater-group-memberships
  (rdbms/make-finder :full-eater-memberships))

(defn group-key
  [full-membership]
  (select-keys full-membership [:eater-group-id
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
  {:eater-group-id                \"123\"
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
                                  (assoc :memberships (if (some :membership-id memberships)
                                                        (map select-membership-keys memberships)
                                                        [])))))
                  [])))

(defn get-users-eater-groups
  "Only return the groups owned by the requesting user"
  [database requester-id]
  (log/infof "Getting groups that user `%s` owns" requester-id)
  (normalize-memberships (get-eater-group-memberships database {:owner-id requester-id})))

(defn add-users-to-eater-group!
  "This functionality makes a strong assumption: that users are uniquely identified
  by their email addresses.

  We do this in order to simplify the system: by adding email addresses to the
  group, we don't have to worry about the problem of 'What is this users' unique
  ID in Keycloak?"
  [database requester-id group-id users]
  (if (owns? database requester-id group-id)
    (let [memberships (vec (for [{:keys [email alias] :as user} (if (vector? users) users [users])]
                             {:id         (utils/uuid)
                              :email      email
                              :alias      alias
                              :group-id   group-id}))]
      (vec (rdbms/insert! database
                          :eater-group-memberships memberships
                          :ex-subtype             :UnableToAddUserToEaterGroup)))
    (log/warnf "User %s does not have permissions to add users to group %s" requester-id group-id)))

(defn remove-user-from-eater-group!
  [database requester-id group-id user-group-membership-id]
  (if (owns? database requester-id group-id)
    (rdbms/delete! database
                   :eater-group-memberships user-group-membership-id
                   :ex-subtype :UnableToDeleteUserFromEaterGroup)
    (log/warnf "User %s does not have permissions to delete users from group %s" requester-id group-id)))
