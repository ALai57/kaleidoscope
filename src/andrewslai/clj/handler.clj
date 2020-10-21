(ns andrewslai.clj.handler
  (:gen-class)
  (:require [andrewslai.clj.persistence.postgres2 :as postgres2]
            [andrewslai.clj.routes.admin :refer [admin-routes]]
            [andrewslai.clj.routes.articles :refer [articles-routes]]
            [andrewslai.clj.routes.login :refer [login-routes]]
            [andrewslai.clj.routes.ping :refer [ping-routes]]
            [andrewslai.clj.routes.portfolio :refer [portfolio-routes]]
            [andrewslai.clj.routes.users :as user-routes :refer [users-routes]]
            [andrewslai.clj.utils :as util]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [clojure.data.codec.base64 :as b64]
            [compojure.api.middleware :as mw]
            [compojure.api.swagger :as swag]
            [compojure.api.sweet :refer [api routes undocumented GET POST]]
            [org.httpkit.server :as httpkit]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :as mem]
            [ring.swagger.common :as rsc]
            [ring.swagger.middleware :as rsm]
            [ring.swagger.swagger-ui :as swagger-ui]
            [ring.swagger.swagger2 :as swagger2]
            [ring.util.http-response :refer [ok content-type resource-response]]
            [spec-tools.core :as st-core]
            [spec-tools.swagger.core :as st]
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
;; Example data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def example-b64-encoded-avatar (->> "Hello world!"
                                     (map (comp byte int))
                                     byte-array
                                     b64/encode
                                     String.))

(def example-user-1 {:username "new-user"
                     :avatar example-b64-encoded-avatar
                     :password "CactusGnarlObsidianTheft"
                     :first_name "new"
                     :last_name "user"
                     :email "newuser@andrewslai.com"})

(def example-article-1 {:title "My test article"
                        :article_tags "thoughts"
                        :article_url "my-test-article"
                        :author "Andrew Lai"
                        :content "<h1>Hello world!</h1>"})

(def example-data
  {:ArticleExample1 {:summary "An example article"
                     :value example-article-1}
   :UserExample1 {:summary "An example user"
                  :value example-user-1}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Swagger routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def swagger-ui-routes
  (routes
    (undocumented
     (swagger-ui/swagger-ui {:path "/swagger"
                             :swagger-docs "/swagger.json"}))
    (GET "/swagger.json" req
      (let [runtime-info1 (mw/get-swagger-data req)
            runtime-info2 (rsm/get-swagger-data req)
            base-path {:basePath (swag/base-path req)}
            options (:compojure.api.request/ring-swagger req)
            paths (:compojure.api.request/paths req)
            swagger (apply rsc/deep-merge
                           (keep identity [base-path
                                           paths
                                           runtime-info1
                                           runtime-info2]))
            spec (st/swagger-spec
                  (swagger2/swagger-json swagger options))]

        (-> spec
            (assoc :openapi "3.0.2"
                   :info {:title "andrewslai"
                          :description "My personal website"}
                   :components
                   {:schemas
                    {:Article (-> {:spec :andrewslai.article/article
                                   :description "An article for the website"}
                                  st-core/spec
                                  st/transform)

                     :User (-> {:spec :andrewslai.user/user
                                :description "A user on the website"}
                               st-core/spec
                               st/transform)}
                    :examples example-data})
            (dissoc :swagger)
            ok)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Compojure Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: #1 Refactor me into clear, separate components/layers. Use EBI
(def app-routes
  (api
   (GET "/" []
     (-> (resource-response "index.html" {:root "public"})
         (content-type "text/html")))

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
    (log/with-config (get-in request [:components :logging])
      (log/info "Request received for: "
                (:request-method request)
                (:uri request))
      (handler request))))

(defn wrap-components

  [handler components]
  (fn [request]
    (handler (assoc request :components components))))

(defn wrap-middleware
  "Wraps a set of Compojure routes with middleware and adds
  components via the wrap-components middleware"
  [routes app-components]
  (-> routes
      wrap-logging
      user-routes/wrap-user
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
  {:database (postgres2/->Database db-spec)
   :logging  (merge log/*config* {:level log-level})
   :session  {:cookie-attrs {:max-age 3600 :secure secure-session?}
              :store        (mem/memory-store session-atom)}})

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
  "Start a server and run the application"
  [& {:keys [port]}]
  (println "Hello! Starting service...")
  (httpkit/run-server (configure-app app-routes {:db-spec         (util/pg-conn)
                                                 :log-level       :info
                                                 :session-atom    (atom {})
                                                 :secure-session? true})
                      {:port (or port
                                 (some-> (System/getenv "ANDREWSLAI_PORT")
                                         int)
                                 5000)}))
