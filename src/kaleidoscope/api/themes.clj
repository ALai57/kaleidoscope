(ns kaleidoscope.api.themes
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]
            [cheshire.core :as json]
            [malli.core :as m]
            [malli.transform :as mt]
            [taoensso.timbre :as log]))

(def Theme
  [:map
   [:id :uuid]
   [:display-name :string]
   [:config :any]
   [:owner-id :string]
   [:created-at inst?]
   [:modified-at inst?]])

(defn get-owner
  [theme]
  (:owner-id theme))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Themes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def -get-themes
  (rdbms/make-finder :themes))

(defn get-themes
  ([database]
   (-get-themes database))
  ([database query]
   (let [raw-result (-get-themes database (dissoc query :config))]
     (map (fn [theme]
            (if (map? (:config theme))
              theme
              (update theme :config (fn [x]
                                      (json/decode x true)))))
          raw-result))))

(defn create-theme!
  [database {:keys [id] :as theme}]
  (let [now (utils/now)
        row (-> theme
                ;;(update :config json/encode)
                (update :config #(with-meta % {:pgtype "json"}))
                (assoc :created-at  now
                       :modified-at now
                       :id          (or id (utils/uuid))))]
    (log/infof "ROW: %s" row)
    (rdbms/insert! database
                   :themes row
                   :ex-subtype :UnableToCreateTheme)))

(defn update-theme!
  [database {:keys [id] :as theme}]
  (let [now (utils/now)
        row (-> theme
                (update :config #(with-meta % {:pgtype "json"}))
                (assoc :modified-at now))]
    (rdbms/update! database
                   :themes row
                   :ex-subtype :UnableToUpdateTheme)))

(defn owns?
  [database requester-id theme-id]
  (= requester-id (-> database
                      (get-themes {:id theme-id})
                      first
                      get-owner)))

(defn delete-theme!
  "Only allow a user to delete a theme if they are the owner.
  The `user-id` is the identity of the user requesting the operation."
  [database requester-id theme-id]
  (if (owns? database requester-id theme-id)
    (rdbms/delete! database
                   :themes     theme-id
                   :ex-subtype :UnableToDeleteGroup)
    (log/warnf "User %s does not have permissions to delete the theme %s" requester-id theme-id)))
