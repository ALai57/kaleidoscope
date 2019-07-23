(ns andrewslai.clj.handler
  (:gen-class)
  (:require [andrewslai.clj.env :as env]
            [andrewslai.clj.db :as db]
            [compojure.api.sweet :refer :all]
            [org.httpkit.server :as httpkit]
            [ring.util.http-response :refer :all]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [clojure.java.jdbc :as sql]))

(defn init []
  (println "Hello! Starting service..."))

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
       (ok {:service-status "ok"}))

     (GET "/get-article/:article-type/:article-name"
         [article-type article-name]
       (ok {:article-type article-type
            :article-name article-name
            :article (db/get-full-article article-name)}))

     (GET "/get-recent-articles" [article-type article-name]
       (ok (db/get-recent-articles 6)))

     ) "public")))

(defn -main [& _]
  (init)
  (httpkit/run-server #'app {:port (@env/env :port)}))

(comment
  (-main)
  (db/get-article "my-first-article")

  (clojure.pprint/pprint
   (db/get-content (first (db/get-article "my-second-article"))))

  (ok {:content-type content-type
       :content-name content-name
       ;;:article (first (db/get-article content-name))
       :article (db/get-content (first (db/get-article content-name)))
       })
  )
