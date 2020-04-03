(ns andrewslai.clj.handler
  (:gen-class)
  (:require [andrewslai.clj.auth.mock :as auth-mock]
            [andrewslai.clj.env :as env]
            [andrewslai.clj.persistence.config :as db-cfg]
            [andrewslai.clj.persistence.core :as db]
            [andrewslai.clj.persistence.postgres :as postgres]
            [buddy.auth.accessrules :refer [restrict]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication
                                           wrap-authorization]]
            [compojure.api.sweet :refer :all]
            [org.httpkit.server :as httpkit]
            [ring.util.http-response :refer :all]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [clojure.java.shell :as shell]
            ))

;; https://adambard.com/blog/buddy-password-auth-example/

(defn init []
  (println "Hello! Starting service..."))

(defn get-sha []
  (->> "HEAD"
       (shell/sh "git" "rev-parse" "--short")
       :out
       clojure.string/trim))

(def backend (session-backend))

(defroutes admin-routes
  (GET "/" [] (ok {:message "Got to the admin-route!"})))

(defn app [components]
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

        (GET "/ping" []
          (ok {:service-status "ok"
               :sha (get-sha)}))

        (context "/articles" [request]
          (GET "/" []
            (ok (db/get-all-articles (:db components))))

          (GET "/:article-name" [article-name]
            (ok {:article-name article-name
                 :article (db/get-full-article (db-cfg/db-conn) article-name)})))

        (GET "/get-resume-info" []
          (ok (db/get-resume-info (db-cfg/db-conn))))

        (GET "/login/" []
          (ok {:message "Login get message"}))

        (POST "/login/" []
          auth-mock/post-login)

        (POST "/logout/" []
          auth-mock/post-logout)

        (context "/admin" []
          (restrict admin-routes {:handler auth-mock/is-authenticated?}))
        )
      auth-mock/wrap-user
      (wrap-authentication backend)
      (wrap-authorization backend)
      wrap-session
      wrap-params
      (wrap-resource "public")
      wrap-content-type))

(defn -main [& _]
  (init)
  (let [app-with-components (app {:db (postgres/make-db)})]
    (httpkit/run-server app-with-components {:port (@env/env :port)})))

(comment
  (-main)

  (let [resume-info (db/get-resume-info (db-cfg/db-conn))]
    (clojure.pprint/pprint (:projects resume-info)))

  (db/get-full-article (db-cfg/db-conn) "my-first-article")

  (clojure.pprint/pprint
    (first (db/get-article (db-cfg/db-conn) "my-second-article")))

  )
