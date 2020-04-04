(ns andrewslai.clj.handler
  (:gen-class)
  (:require [andrewslai.clj.auth.mock :as auth-mock]
            [andrewslai.clj.env :as env]
            [andrewslai.clj.persistence.core :as db]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.users :as users]
            [buddy.auth.accessrules :refer [restrict]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication
                                           wrap-authorization]]
            [cheshire.core :as json]
            [compojure.api.sweet :refer :all]
            [org.httpkit.server :as httpkit]
            [ring.util.http-response :refer :all]
            [ring.util.response :refer [redirect]]
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
            (ok (db/get-full-article (:db components) article-name))))

        (GET "/get-resume-info" []
          (ok (db/get-resume-info (:db components))))

        (GET "/login" []
          (ok {:message "Login get message"}))

        (POST "/login" [body session :as request]
          (let [credentials (-> request
                                :body
                                slurp
                                (json/parse-string keyword))
                login-response (users/login (:user components) credentials)
                updated-session (assoc session :identity login-response)]
            (if login-response
              (assoc (redirect "/") :session updated-session)
              (redirect "/login/"))))

        (POST "/logout/" []
          auth-mock/post-logout)

        (context "/admin" []
          (restrict admin-routes {:handler auth-mock/is-authenticated?}))
        )
      auth-mock/wrap-user
      (wrap-authentication backend)
      (wrap-authorization backend)
      wrap-session
      (wrap-resource "public")
      wrap-content-type))

(defn -main [& _]
  (init)
  (let [app-with-components (app {:db (postgres/->Database postgres/pg-db)
                                  :user (users/->UserDatabase postgres/pg-db)})]
    (httpkit/run-server app-with-components {:port (@env/env :port)})))

(comment
  (-main)

  (let [resume-info (db/get-resume-info (postgres/->Database postgres/pg-db))]
    (clojure.pprint/pprint (:projects resume-info)))

  (db/get-full-article (postgres/->Database postgres/pg-db) "my-first-article")

  (clojure.pprint/pprint
    (first (db/get-article(postgres/->Database postgres/pg-db) "my-second-article")))

  )
