(ns andrewslai.clj.routes.articles
  (:require [andrewslai.clj.persistence.articles :as articles]
            [compojure.api.sweet :refer [context defroutes GET]]
            [ring.util.http-response :refer [ok]]))

(defroutes articles-routes
  (context "/articles" {:keys [components]}
    (GET "/" []
      (ok (articles/get-all-articles (:db components))))

    (GET "/:article-name" [article-name :as request]
      (ok (-> request
              (get-in [:components :db])
              (articles/get-full-article article-name))))))
