(ns andrewslai.clj.figwheel-backend
  (:require [andrewslai.clj.env :as env]
            [andrewslai.clj.handler :refer [configure-components
                                            wrap-middleware
                                            app-routes]]))

(defn pg-conn []
  (-> @env/env
      (select-keys [:db-port :db-host
                    :db-name :db-user
                    :db-password])
      (clojure.set/rename-keys {:db-name     :dbname
                                :db-host     :host
                                :db-user     :user
                                :db-password :password})
      (assoc :dbtype "postgresql")))

;; This is for figwheel testing only
(def figwheel-app
  (let [component-config {:db-spec (pg-conn)
                          :log-level :debug
                          :session-atom (atom {})
                          :secure-session? false}]
    (->> component-config
         configure-components
         (wrap-middleware app-routes))))
