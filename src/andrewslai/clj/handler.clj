(ns andrewslai.clj.handler
  (:gen-class)
  (:require [andrewslai.clj.env :as env]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.persistence.projects-portfolio :as portfolio]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Global settings (Yuck!)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(log/merge-config!
 {:appenders {:spit (appenders/spit-appender {:fname "log.txt"})}})

(def backend (session-backend))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Compojure Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def app-routes
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Middleware
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn wrap-logging [handler]
  (fn [request]
    (log/with-config (get-in request [:components :logging])
      (log/info "Request received for: "
                (:request-method request)
                (:uri request))
      (handler request))))

(defn wrap-components
  "Middlware that adds components to the Ring request"
  [handler components]
  (fn [request]
    (handler (assoc request :components components))))

(defn wrap-middleware
  "Wraps a set of Compojure routes with middleware and adds
  components via the wrap-components middleware"
  [routes app-components]
  (-> routes
      wrap-logging
      users/wrap-user
      (wrap-authentication backend)
      (wrap-authorization backend)
      (wrap-session (or (:session app-components) {}))
      (wrap-resource "public")
      wrap-cookies
      (wrap-components app-components)
      wrap-content-type))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; App configuration helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn configure-components
  "Configures components that will be used in the application,
  e.g. Database connection, logging details, and session mgmt"
  [{:keys [db-spec session-atom secure-session? log-level]
    :or {session-atom (atom {})
         secure-session? true
         log-level :info}}]
  {:db (-> db-spec
           postgres/->Postgres
           articles/->ArticleDatabase)
   :user (-> db-spec
             postgres/->Postgres
             users/->UserDatabase)
   :portfolio (-> db-spec
                  postgres/->Postgres
                  portfolio/->ProjectPortfolioDatabase)
   :logging (merge log/*config* {:level log-level})
   :session {:cookie-attrs {:max-age 3600 :secure secure-session?}
             :store (mem/memory-store session-atom)}})

(defn configure-app
  "Configures the application by wrapping key middleware and adding
  all relevant components to the app"
  [routes component-config]
  (->> component-config
       configure-components
       (wrap-middleware routes)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Running the server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn -main
  "Invoked to start a server and run the application"
  [& _]
  (println "Hello! Starting service...")
  (let [component-config {:db-spec postgres/pg-db
                          :log-level :info
                          :session-atom (atom {})
                          :secure-session? true}]
    (httpkit/run-server (configure-app app-routes component-config)
                        {:port (@env/env :port)})))
