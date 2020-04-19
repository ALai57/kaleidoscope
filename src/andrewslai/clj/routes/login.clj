(ns andrewslai.clj.routes.login
  (:require [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.utils :refer [parse-body]]
            [cheshire.core :as json]
            [compojure.api.sweet :refer [defroutes GET POST]]
            [clojure.java.io :as io]
            [ring.util.http-response :refer [ok not-found created]]
            [ring.util.response :as response]
            [taoensso.timbre :as log]
            [compojure.api.sweet :refer [context defroutes GET]]
            [ring.util.http-response :refer [ok]]))

(defroutes login-routes
  (GET "/login" []
    (ok {:message "Login get message"}))

  (POST "/login" {:keys [components body session] :as request}
    (let [credentials (parse-body request)]
      (if-let [user-id (users/login (:user components) credentials)]
        (let [user (users/get-user-by-id (:user components) user-id)]
          (log/info "Authenticated login!")
          (assoc (ok user)
                 :session (assoc session :identity user-id)))
        (do (log/info "Invalid username/password")
            (ok nil)))))

  (POST "/logout" []
    (-> (ok)
        (assoc :session nil))))
