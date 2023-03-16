(ns kaleidoscope.clj.http-api.portfolio
  (:require [kaleidoscope.clj.api.portfolio :as portfolio-api]
            [compojure.api.sweet :refer [context defroutes GET]]
            [ring.util.http-response :refer [ok]]))

(defroutes portfolio-routes
  (context "/projects-portfolio" []
    :tags ["projects-portfolio"]
    :components [database]
    (GET "/" []
      (ok (portfolio-api/get-portfolio database)))))

(comment
  (def portfolio-routes-2
    ["/projects-portfolio"
     ["/" {:get {:responses {200 {:body any?}}
                 :handler   (fn [request]
                              (ok (portfolio-api/get-portfolio database)))}}]]))
