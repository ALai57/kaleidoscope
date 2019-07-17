(ns clj.handler
  (:gen-class)
  (:require [clj.env :as env]
            [clj.mock :as mock]
            [clj.postgres :as db]
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

     (GET "/mock-data" []
       (ok mock/mock-data))

     (GET "/get-content/:content-type/:content-name" [content-type content-name]
       ;;(Thread/sleep 2000)
       (ok {:content-type content-type
            :content-name content-name
            :article (db/get-content (first (db/get-article content-name)))
            }))

     (GET "/get-recent-content"
         [content-type content-name]
       (ok (db/get-articles 6)))

     (GET "/get-fruit/:content-type/:content-name" [content-type content-name]
       (Thread/sleep 2000)
       (ok {:content-type content-type
            :content-name content-name
            ;;:database (db/select-all :fruit)
            :fruit (db/select :fruit content-name)
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
