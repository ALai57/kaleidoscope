(ns kaleidoscope.api.themes
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]
            [cheshire.core :as json]
            [malli.core :as m]
            [malli.transform :as mt]
            [taoensso.timbre :as log]))

(defn get-owner
  [theme]
  (:owner-id theme))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Themes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn decode-themes
  [themes]
  (map (fn [theme]
         (update theme :config (comp (fn [s]
                                       (json/decode s keyword)) json/decode slurp)))
       themes))

(def -get-themes
  (rdbms/make-finder :themes))

(defn get-themes
  ([database]
   (decode-themes (-get-themes database)))
  ([database query]
   (decode-themes (-get-themes database
                               (if (map? (:config query))
                                 (update query :config json/encode)
                                 query)))))

(defn create-theme!
  [database {:keys [id] :as theme}]
  (let [now (utils/now)
        row (-> theme
                (update :config json/encode)
                (assoc :created-at  now
                       :modified-at now
                       :id          (or id (utils/uuid))))]
    (log/infof "ROW: %s" row)
    (rdbms/insert! database
                   :themes row
                   :ex-subtype :UnableToCreateTheme)))

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
