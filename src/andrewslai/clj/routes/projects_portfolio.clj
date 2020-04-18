(ns andrewslai.clj.routes.projects-portfolio
  (:require [andrewslai.clj.persistence.core :as db]
            [compojure.api.sweet :refer [defroutes GET]]
            [ring.util.http-response :refer [ok]]))

(defroutes projects-portfolio-routes
  (GET "/get-resume-info" {:keys [components]}
    (ok (db/get-resume-info (:db components)))))
