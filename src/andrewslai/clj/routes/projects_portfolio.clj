(ns andrewslai.clj.routes.projects-portfolio
  (:require [andrewslai.clj.persistence.projects-portfolio :as portfolio]
            [compojure.api.sweet :refer [context defroutes GET]]
            [ring.util.http-response :refer [ok]]))

(defroutes projects-portfolio-routes
  (context "/projects-portfolio" {:keys [components]}
    :tags ["projects-portfolio"]
    (GET "/" []
      (ok (portfolio/get-project-portfolio (:portfolio components))))))
