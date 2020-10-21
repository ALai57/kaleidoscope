(ns andrewslai.clj.figwheel-backend
  (:require [andrewslai.clj.handler :refer [configure-components
                                            wrap-middleware
                                            app-routes]]
            [andrewslai.clj.utils :as util]))

;; This is for figwheel testing only
(def figwheel-app
  (let [component-config {:db-spec (util/pg-conn)
                          :log-level :debug
                          :session-atom (atom {})
                          :secure-session? false}]
    (->> component-config
         configure-components
         (wrap-middleware app-routes))))
