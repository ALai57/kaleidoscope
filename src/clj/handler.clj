(ns clj.handler
  (:gen-class)
  (:require [clj.env :as env]
            [clj.mock :as mock]
            [clj.postgres :as db]
            [compojure.api.sweet :refer :all]
            [org.httpkit.server :as httpkit]
            [ring.util.http-response :refer :all]
            [ring.middleware.resource :refer [wrap-resource]]
            [clojure.java.jdbc :as sql]))

(defn init []
  (println "Hello! Starting service..."))


;; TO DO: Add base route that serves index.html
(def app
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

    (GET "/example" []
      (-> (resource-response "example.html" {:root "public"})
          (content-type "text/html")))
    
    (GET "/ping" []
      (ok {:service-status "ok"}))

    (GET "/mock-data" []
      (ok mock/mock-data))

    (GET "/test-figwheel" []
      (-> (resource-response "index.html" {:root "public"})
          (content-type "text/html")))

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

    ) "public"))

(defn -main [& _]
  (init)
  (httpkit/run-server #'app {:port (@env/env :port)}))

(comment
  (ok {:service-status "ok"})
  (-main)
  )
