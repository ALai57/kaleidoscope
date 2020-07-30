(ns andrewslai.clj.routes.login
  (:require [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.utils :refer [parse-body]]
            [andrewslai.clj.api.users :as users-api]
            [cheshire.core :as json]
            [compojure.api.sweet :refer [defroutes GET POST]]
            [clojure.java.io :as io]
            [ring.util.http-response :refer [unauthorized ok not-found created]]
            [ring.util.response :as response]
            [taoensso.timbre :as log]
            [compojure.api.sweet :refer [context defroutes GET]]
            [clojure.spec.alpha :as s]
            [ring.util.http-response :refer [ok]]))

(s/def ::username string?)
(s/def ::password string?)
(s/def ::credentials (s/keys :req-un [::username
                                      ::password]))

(defroutes login-routes
  (context "/sessions" {:keys [components]}
    :tags ["sessions"]
    :coercion :spec

    (POST "/login" {:keys [body session body-params] :as request}
      :swagger {:summary "Login"
                :consumes #{"application/json"}
                :produces #{"application/json"}
                :parameters {:body ::credentials}
                #_#_:responses {200 {:description "The user that just authenticated"}}}


      (let [{:keys [username] :as credentials} body-params]
        (if-let [user-id (users-api/login (:user components) credentials)]
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
