(ns andrewslai.clj.figwheel-backend
  (:require [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.handler :refer [configure-components
                                            wrap-middleware
                                            app-routes]]))

;; This is for figwheel testing only
(def figwheel-app
  (let [component-config {:db-spec postgres/pg-db
                          :log-level :debug
                          :session-atom (atom {})
                          :secure-session? false}]
    (->> component-config
         configure-components
         (wrap-middleware app-routes))))
