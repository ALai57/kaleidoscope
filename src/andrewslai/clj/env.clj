(ns andrewslai.clj.env
  (:require  [defenv.core :as env :refer [env->map
                                          parse-boolean
                                          parse-int]]))

(def env
  (delay
    (let [spec
          {:port {:env-name "ANDREWSLAI_PORT"
                  :tfn parse-int
                  :default "5000"
                  :doc "Port to listen on"}

           :db-port {:env-name "ANDREWSLAI_DB_PORT"
                     :default "5432"
                     :doc "Postgres Database Port"}

           :db-host {:env-name "ANDREWSLAI_DB_HOST"
                     :default "localhost"
                     :doc "Postgres Database Host URL"}

           :db-name {:env-name "ANDREWSLAI_DB_NAME"
                     :default "andrewslai_db"
                     :doc "Postgres Database Name"}

           :db-user {:env-name "ANDREWSLAI_DB_USER"
                     :default "andrewslai"
                     :doc "Postgres Database User"}

           :db-password {:env-name "ANDREWSLAI_DB_PASSWORD"
                         :default "andrewslai"
                         :doc "Postgres Database Password"}

           :live-db? {:env-name "ANDREWSLAI_LIVE_DB"
                      :tfn parse-boolean
                      :default "true"
                      :doc "Using a live database or mock?"}

           :work-factor {:env-name "ANDREWSLAI_ENCRYPTION_WORK FACTOR"
                         :default 12
                         :doc "Work factor for encryption"}
           }
          env (env->map spec)]
      (env/display-env spec)
      env)))

(comment)
