(ns andrewslai.clj.routes.articles
  (:require [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.routes.admin :as admin]
            [andrewslai.clj.utils :refer [parse-body]]
            [buddy.auth.accessrules :refer [restrict]]
            [compojure.api.sweet :refer [context defroutes GET POST]]
            [ring.util.http-response :refer [ok not-found]]))

(defroutes articles-routes
  (context "/articles" {:keys [components]}
    (GET "/" []
      (ok (articles/get-all-articles (:db components))))

    (GET "/:article-name" [article-name :as request]
      (let [article (-> request
                        (get-in [:components :db])
                        (articles/get-article article-name))]
        (if article
          (ok article)
          (not-found))))

    (restrict
     (POST "/" request
       (ok (-> request
               (get-in [:components :db])
               (articles/create-article! (parse-body request)))))
     {:handler admin/is-authenticated?
      :on-error admin/access-error})))
