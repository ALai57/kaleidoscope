(ns andrewslai.clj.handler
  (:gen-class)
  (:require [andrewslai.clj.env :as env]
            [andrewslai.clj.mock :as mock]
            [andrewslai.clj.postgres :as db]
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
       :data {:info {:title "Full stack template"
                     :description "Template for a full stack app"}
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
            :article (db/get-content (first (db/get-article article-name)))}))

     (GET "/get-recent-articles"
         [article-type article-name]
       (ok (db/get-articles 6)))

     (GET "/get-fruit/:content-type/:article-name" [content-type article-name]
       (Thread/sleep 2000)
       (ok {:content-type content-type
            :article-name article-name
            ;;:database (db/select-all :fruit)
            :fruit (db/select :fruit article-name)
            }))

     ;; TO DO: Make this a POST or PUT
     (GET "/test-create" []
       (db/create-table :fruit [[:name "varchar(32)"]
                                [:appearance "varchar(32)"]
                                [:cost :int]
                                [:grade :real]])
       (ok {:status 200
            :create "success"}))

     ;; TO DO: Make this a POST or PUT
     (GET "/test-insert" []
       (db/insert :fruit
                  [{:name "Ap" :appearance "rosy" :cost 24}
                   {:name "Or" :appearance "round" :cost 49}])
       (ok {:status 200
            :write "success"}))

     (GET "/test-select" []
       (db/select-all :fruit))

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
