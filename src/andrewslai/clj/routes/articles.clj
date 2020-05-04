(ns andrewslai.clj.routes.articles
  (:require [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.routes.admin :as admin]
            [buddy.auth.accessrules :refer [restrict]]
            [compojure.api.sweet :refer [context defroutes GET POST]]
            [ring.util.http-response :refer [ok]]))

(defroutes articles-routes
  (context "/articles" {:keys [components]}
    (GET "/" []
      (ok (articles/get-all-articles (:db components))))

    (GET "/:article-name" [article-name :as request]
      (ok (-> request
              (get-in [:components :db])
              (articles/get-full-article article-name))))

    (restrict
      (POST "/:article-name" [article-name :as request]
        (ok #_(-> request
                  (get-in [:components :db])
                  (articles/create-full-article! article-payload))))
      {:handler admin/is-authenticated?
       :on-error admin/access-error})))
