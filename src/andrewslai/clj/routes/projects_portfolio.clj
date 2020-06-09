(ns andrewslai.clj.routes.projects-portfolio
  (:require [andrewslai.clj.persistence.projects-portfolio :as portfolio]
            [compojure.api.sweet :refer [defroutes GET]]
            [ring.util.http-response :refer [ok]]))

(defroutes projects-portfolio-routes
  (GET "/get-resume-info" {:keys [components]}
    (ok (portfolio/get-project-portfolio (:portfolio components)))))
