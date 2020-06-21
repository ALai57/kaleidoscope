(ns andrewslai.clj.routes.login
  (:require [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.utils :refer [parse-body]]
            [cheshire.core :as json]
            [compojure.api.sweet :refer [defroutes GET POST]]
            [clojure.java.io :as io]
            [ring.util.http-response :refer [unauthorized ok not-found created]]
            [ring.util.response :as response]
            [taoensso.timbre :as log]
            [compojure.api.sweet :refer [context defroutes GET]]
            [ring.util.http-response :refer [ok]]))

(defroutes login-routes
  (context "/sessions" {:keys [components]}
    :tags ["sessions"]

    (POST "/login" {:keys [body session] :as request}
      (let [{:keys [username] :as credentials} (parse-body request)]
        (if-let [user-id (users/login (:user components) credentials)]
          (let [user (-> components
                         :user
                         (users/get-user-by-id user-id)
                         (assoc :avatar_url (format "users/%s/avatar" username)))]
            (log/info "Authenticated login!")
            (assoc (ok user)
                   :session (assoc session :identity user-id)))
          (do (log/info "Invalid username/password")
              (unauthorized)))))

    (POST "/logout" []
      (-> (ok)
          (assoc :session nil)))))
