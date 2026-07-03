(ns kaleidoscope.api.themes
  (:require [kaleidoscope.persistence.ownership :as ownership]
            [kaleidoscope.persistence.rdbms :as rdbms]
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

(defn owns?
  "Whether requester-id owns theme-id, regardless of site. Delegates to the
  shared ownership primitive. update-theme!/delete-theme! use the stricter,
  site-bound check directly (see below) rather than this predicate — a
  theme's hostname is load-bearing for those operations, not just its
  owner-id (see PLAN.md, \"Gap: themes need a compound ownership key\")."
  [database requester-id theme-id]
  (boolean (ownership/get-owned database :themes theme-id requester-id)))

(defn update-theme!
  "Update a theme, scoped to requester-id AND site (the request's Host
  header). A theme belonging to a different site than the one the request
  was authorized against is treated as not found, even if requester-id owns
  it — this closes the cross-site gap where a writer with roles on multiple
  sites could touch another site's theme by routing the request through the
  wrong Host header. Returns nil if not found or not owned."
  [database requester-id site {:keys [id] :as theme}]
  (let [now (utils/now)
        row (-> theme
                (dissoc :id)
                (update :config #(with-meta % {:pgtype "json"}))
                (assoc :modified-at now))]
    (when-let [updated (ownership/update-owned! database :themes id requester-id row site)]
      [updated])))

(defn delete-theme!
  "Delete a theme, scoped to requester-id AND site — see update-theme!."
  [database requester-id site theme-id]
  (if (ownership/delete-owned! database :themes theme-id requester-id site)
    true
    (log/warnf "User %s does not have permissions to delete the theme %s" requester-id theme-id)))
