(ns clj.env
  (:require  [defenv.core :as env :refer [env->map
                                          parse-boolean
                                          parse-int]]))

(def env
  (delay
   (let [spec
         {:port
          {:env-name "FULL_STACK_TEMPLATE_PORT"
           :tfn parse-int
           :default "5000"
           :doc "Port to listen on"}

          :db-port
          {:env-name "FULL_STACK_TEMPLATE_DB_PORT"
           :default "5432"
           :doc "Postgres Database Port"}

          :db-host
          {:env-name "FULL_STACK_TEMPLATE_DB_HOST"
           :default "localhost"
           :doc "Postgres Database Host URL"}

          :db-name
          {:env-name "FULL_STACK_TEMPLATE_DB_NAME"
           :default "full-stack-template-postgres-db"
           :doc "Postgres Database Name"}

          :db-user
          {:env-name "FULL_STACK_TEMPLATE_DB_USER"
           :default "db_user"
           :doc "Postgres Database User"}

          :db-password
          {:env-name "FULL_STACK_TEMPLATE_DB_PASSWORD"
           :default "password"
           :doc "Postgres Database Password"}

          :live-db?
          {:env-name "FULL_STACK_TEMPLATE_LIVE_DB"
           :default "false"
           :tfn parse-boolean
           :doc "Postgres Database - Live DB on RDS? If so, need SSL"}

          :ssl-factory
          {:env-name "FULL_STACK_TEMPLATE_SSL_FACTORY"
           :default "org.postgresql.ssl.NonValidatingFactory"
           :doc "Postgres Database - ssl factory"}

          :test-env
          {:env-name "FULL_STACK_TEMPLATE_TEST_ENV"
           :default "something fun"
           :doc "Testing an environment var"}
          }
         env (env->map spec)]
     (env/display-env spec)
     env)))

(comment)
