(ns andrewslai.clj.handler
  (:gen-class)
  (:require [andrewslai.clj.env :as env]
            [andrewslai.clj.persistence.core :as db]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.routes.admin :refer [admin-routes]]
            [andrewslai.clj.routes.articles :refer [articles-routes]]
            [andrewslai.clj.routes.login :refer [login-routes]]
            [andrewslai.clj.routes.ping :refer [ping-routes]]
            [andrewslai.clj.routes.projects-portfolio
             :refer [projects-portfolio-routes]]
            [andrewslai.clj.routes.users :refer [users-routes]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication
                                           wrap-authorization]]
            [compojure.api.sweet :refer :all]
            [org.httpkit.server :as httpkit]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :as mem]
            [ring.util.http-response :refer :all]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]))

;; https://adambard.com/blog/buddy-password-auth-example/

(log/merge-config!
  {:appenders {:spit (appenders/spit-appender {:fname "log.txt"})}})

(def backend (session-backend))

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
        articles-routes
        users-routes
        projects-portfolio-routes
        login-routes
        admin-routes)))

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

(defn wrap-middleware [handler app-components]
  (-> handler
      wrap-logging
      users/wrap-user
      (wrap-authentication backend)
      (wrap-authorization backend)
      (wrap-session (or (:session app-components) {}))
      (wrap-resource "public")
      wrap-cookies
      (wrap-components app-components)
      wrap-content-type))

;; This is for figwheel testing only
(def app
  (wrap-middleware bare-app
                   {:db (articles/->ArticleDatabase postgres/pg-db)
                    :user (users/->UserDatabase postgres/pg-db)
                    :logging (merge log/*config* {:level :debug})
                    :session {:cookie-attrs {:max-age 3600, :secure true}
                              :store (mem/memory-store (atom {}))}}))

(defn -main [& _]
  (println "Hello! Starting service...")
  (httpkit/run-server
    (wrap-middleware bare-app
                     {:db (articles/->ArticleDatabase postgres/pg-db)
                      :user (users/->UserDatabase postgres/pg-db)
                      :logging (merge log/*config* {:level :info})
                      :session {:cookie-attrs {:max-age 3600, :secure true}
                                :store (mem/memory-store (atom {}))}})
    {:port (@env/env :port)}))

(comment
  (-main)

  (let [resume-info (db/get-resume-info (articles/->ArticleDatabase postgres/pg-db))]
    (clojure.pprint/pprint (:projects resume-info)))

  (db/get-full-article (articles/->ArticleDatabase postgres/pg-db) "my-first-article")

  (clojure.pprint/pprint
    (first (db/get-article(articles/->ArticleDatabase postgres/pg-db) "my-second-article")))

  )
