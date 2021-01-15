(ns andrewslai.clj.handler
  (:gen-class)
  (:require [andrewslai.clj.entities.user :as user]
            [andrewslai.clj.persistence.postgres2 :as pg]
            [andrewslai.clj.routes.admin :refer [admin-routes]]
            [andrewslai.clj.routes.articles :refer [articles-routes]]
            [andrewslai.clj.routes.login :refer [login-routes]]
            [andrewslai.clj.routes.ping :refer [ping-routes]]
            [andrewslai.clj.routes.portfolio :refer [portfolio-routes]]
            [andrewslai.clj.routes.swagger :refer [swagger-ui-routes]]
            [andrewslai.clj.routes.users :as user-routes :refer [users-routes]]
            [andrewslai.clj.utils :as util]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [compojure.api.middleware :as mw]
            [compojure.api.sweet :refer [api defroutes GET routes]]
            [org.httpkit.server :as httpkit]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :as mem]
            [ring.util.http-response :refer [content-type resource-response]]
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
(defroutes index-routes
  (GET "/" []
    (-> (resource-response "index.html" {:root "public"})
        (content-type "text/html"))))

(def app-routes
  (api index-routes
       ping-routes
       articles-routes
       users-routes
       portfolio-routes
       login-routes
       admin-routes
       swagger-ui-routes))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Middleware
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn wrap-logging [handler]
  (fn [request]
    (log/with-config (get-in request [::mw/components :logging])
      (log/info "Request received for: "
                (:request-method request)
                (:uri request))
      (handler request))))

(defn wrap-user [handler]
  (fn [{user-id :identity
        {database :database} ::mw/components
        :as req}]
    (handler (assoc req
                    :user (and user-id
                               database
                               (user/get-user-profile-by-id database user-id))))))

(defn wrap-middleware
  "Wraps a set of Compojure routes with middleware and adds
  components via the wrap-components middleware"
  [routes components]
  (-> routes
      wrap-logging
      wrap-user
      (wrap-authentication backend)
      (wrap-authorization backend)
      (wrap-session (or (:session components) {}))
      (wrap-resource "public")
      wrap-cookies
      (mw/wrap-components components)
      wrap-content-type))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Running the server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn -main
  "Start a server and run the application"
  [& {:keys [port]}]
  (println "Hello! Starting andrewslai on port" port)
  (httpkit/run-server
   (wrap-middleware app-routes
                    {:database (pg/->Database (util/pg-conn))
                     :logging  (merge log/*config* {:level :info})
                     :session  {:cookie-attrs {:max-age 3600 :secure true}
                                :store        (mem/memory-store (atom {}))}})
   {:port (or port
              (some-> (System/getenv "ANDREWSLAI_PORT")
                      int)
              5000)}))
