(ns andrewslai.clj.handler
  (:gen-class)
  (:require [andrewslai.clj.auth.mock :as auth-mock]
            [andrewslai.clj.env :as env]
            [andrewslai.clj.routes.ping :refer [ping-routes]]
            [andrewslai.clj.routes.admin :refer [admin-routes]]
            [andrewslai.clj.routes.users :refer [users-routes]]
            [andrewslai.clj.routes.login :refer [login-routes]]
            [andrewslai.clj.routes.articles :refer [articles-routes]]
            [andrewslai.clj.routes.projects-portfolio
             :refer [projects-portfolio-routes]]
            [andrewslai.clj.persistence.core :as db]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.users :as users]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication
                                           wrap-authorization]]
            [cheshire.core :as json]
            [clojure.data.codec.base64 :as b64]

            [clojure.java.io :as io]
            [compojure.api.sweet :refer :all]
            [org.httpkit.server :as httpkit]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :as mem]
            [ring.util.http-response :refer :all]
            [ring.util.response :refer [redirect] :as response]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]))

;; https://adambard.com/blog/buddy-password-auth-example/

(log/merge-config!
  {:appenders {:spit (appenders/spit-appender {:fname "log.txt"})}})

(def backend (session-backend))

(defn wrap-logging [handler]
  (fn [request]
    (log/with-config (get-in request [:components :logging])
      (log/info "Request received for: "
                (:request-method request)
                (:uri request))
      (handler request))))

(defn wrap-components [handler components]
  (fn [request]
    (handler (assoc request :components components))))

(def bare-app
  (-> {:swagger
       {:ui "/swagger"
        :spec "/swagger.json"
        :data {:info {:title "andrewslai"
                      :description "My personal website"}
               :tags [{:name "api", :description "some apis"}]}}}
      (api
        (GET "/" []
          (-> (resource-response "index.html" {:root "public"})
              (content-type "text/html")))

        ping-routes
        article-routes
        users-routes
        projects-portfolio-routes
        login-routes
        admin-routes)))

(defn wrap-middleware [handler app-components]
  (-> handler
      wrap-logging
      users/wrap-user
      (wrap-authentication backend)
      (wrap-authorization backend)
      (wrap-session (or (:session-options app-components) {}))
      (wrap-resource "public")
      wrap-cookies
      (wrap-components app-components)
      wrap-content-type
      ))

(def app
  (wrap-middleware bare-app
                   {:db (postgres/->Database postgres/pg-db)
                    :user (users/->UserDatabase postgres/pg-db)
                    :logging (merge log/*config* {:level :debug})
                    :session-options {:cookie-attrs {:max-age 3600
                                                     :secure true}
                                      :store (mem/memory-store (atom {}))}}))

(defn -main [& _]
  (println "Hello! Starting service...")
  (httpkit/run-server
    (wrap-middleware bare-app
                     {:db (postgres/->Database postgres/pg-db)
                      :user (users/->UserDatabase postgres/pg-db)
                      :logging (merge log/*config* {:level :info})
                      :session-options
                      {:cookie-attrs {:max-age 3600
                                      :secure true}
                       :store (mem/memory-store (atom {}))}})
    {:port (@env/env :port)}))

(comment
  (-main)

  (let [resume-info (db/get-resume-info (postgres/->Database postgres/pg-db))]
    (clojure.pprint/pprint (:projects resume-info)))

  (db/get-full-article (postgres/->Database postgres/pg-db) "my-first-article")

  (clojure.pprint/pprint
    (first (db/get-article(postgres/->Database postgres/pg-db) "my-second-article")))

  )
