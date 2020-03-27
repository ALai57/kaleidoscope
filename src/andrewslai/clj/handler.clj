(ns andrewslai.clj.handler
  (:gen-class)
  (:require [andrewslai.clj.env :as env]
            [andrewslai.clj.persistence.config :as db-cfg]
            [andrewslai.clj.persistence.core :as db]
            [compojure.api.sweet :refer :all]
            [org.httpkit.server :as httpkit]
            [ring.util.http-response :refer :all]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [clojure.java.shell :as shell]
            ))

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
               :article (db/get-full-article (db-cfg/db-conn) article-name)}))

        (GET "/get-all-articles" [article-type article-name]
          (println "start request for all articles:")
          (ok (db/get-all-articles (db-cfg/db-conn))))

        (GET "/get-resume-info" []
          (ok (db/get-resume-info (db-cfg/db-conn))))

        ) "public")))

(defn -main [& _]
  (init)
  (httpkit/run-server #'app {:port (@env/env :port)}))

(comment
  (-main)

  (let [resume-info (db/get-resume-info (db-cfg/db-conn))]
    (clojure.pprint/pprint (:projects resume-info)))

  (db/get-full-article (db-cfg/db-conn) "my-first-article")

  (clojure.pprint/pprint
    (first (db/get-article (db-cfg/db-conn) "my-second-article")))

  )
