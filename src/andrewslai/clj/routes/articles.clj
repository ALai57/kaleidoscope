(ns andrewslai.clj.routes.articles
  (:require [andrewslai.clj.persistence.core :as db]
            [compojure.api.sweet :refer [context defroutes GET]]
            [ring.util.http-response :refer [ok]]))

(defroutes articles-routes
  (context "/articles" {:keys [components]}
    (GET "/" []
      (ok (db/get-all-articles (:db components))))

    (GET "/:article-name" [article-name :as request]
      (ok (db/get-full-article (get-in request [:components :db]) article-name)))))
