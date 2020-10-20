(ns andrewslai.clj.routes.projects-portfolio
  (:require [andrewslai.clj.entities.portfolio :as portfolio]
            [compojure.api.sweet :refer [context defroutes GET]]
            [ring.util.http-response :refer [ok]]))

(defroutes projects-portfolio-routes
  (context "/projects-portfolio" {{database :database} :components}
    :tags ["projects-portfolio"]
    (GET "/" []
      (ok (portfolio/get-portfolio database)))))
