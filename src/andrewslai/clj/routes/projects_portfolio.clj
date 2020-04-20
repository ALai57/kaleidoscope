(ns andrewslai.clj.routes.projects-portfolio
  (:require [andrewslai.clj.persistence.articles :as articles]
            [compojure.api.sweet :refer [defroutes GET]]
            [ring.util.http-response :refer [ok]]))

(defroutes projects-portfolio-routes
  (GET "/get-resume-info" {:keys [components]}
    (ok (articles/get-resume-info (:db components)))))
