(ns andrewslai.clj.handler
  (:gen-class)
  (:require [andrewslai.clj.env :as env]
            [andrewslai.clj.db :as db]
            [andrewslai.clj.persistence.config :as db-cfg]
            [andrewslai.clj.persistence.core :as db2]
            [compojure.api.sweet :refer :all]
            [org.httpkit.server :as httpkit]
            [ring.util.http-response :refer :all]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [clojure.java.shell :as shell]
            [clojure.java.jdbc :as sql]))

(defn init []
  (println "Hello! Starting service..."))

(defn get-sha []
  (->> "HEAD"
       (shell/sh "git" "rev-parse" "--short")
       :out
       clojure.string/trim))

(def app
  (wrap-content-type
    (wrap-resource
      (api
        {:swagger
         {:ui "/swagger"
          :spec "/swagger.json"
          :data {:info {:title "andrewslai"
                        :description "My personal website"}
                 :tags [{:name "api", :description "some apis"}]}}}

        (GET "/" []
          (-> (resource-response "index.html" {:root "public"})
              (content-type "text/html")))

        (GET "/ping" []
          (ok {:service-status "ok"
               :sha (get-sha)}))

        (GET "/get-article/:article-type/:article-name"
            [article-type article-name]
          (ok {:article-type article-type
               :article-name article-name
               :article (db/get-full-article article-name)}))

        (GET "/get-all-articles" [article-type article-name]
          (ok (db2/get-all-articles (db-cfg/db-conn))))

        (GET "/get-resume-info" []
          (ok (db/get-resume-info)))

        ) "public")))

(defn -main [& _]
  (init)
  (httpkit/run-server #'app {:port (@env/env :port)}))

(comment
  (-main)

  (def resume-info (db/get-resume-info))

  (clojure.pprint/pprint (:projects resume-info))

  (db/get-full-article "my-first-article")

  (clojure.pprint/pprint
   (db/get-content (first (db/get-article "my-second-article"))))

  )
