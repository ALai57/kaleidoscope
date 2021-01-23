(ns andrewslai.clj.routes.login
  (:require [andrewslai.clj.api.users :as users-api]
            [andrewslai.clj.entities.user :as user]
            [clojure.spec.alpha :as s]
            [compojure.api.sweet :refer [context defroutes POST]]
            [ring.util.http-response :refer [ok unauthorized]]
            [taoensso.timbre :as log]))

(s/def ::username string?)
(s/def ::password string?)
(s/def ::credentials (s/keys :req-un [::username
                                      ::password]))

(defroutes login-routes
  (context "/sessions" []
    :tags ["sessions"]
    :coercion :spec
    :components [database]

    (POST "/login" {:keys [body session body-params] :as request}
      :swagger {:summary "Login"
                :consumes #{"application/json"}
                :produces #{"application/json"}
                :parameters {:body ::credentials}
                #_#_:responses {200 {:description "The user that just authenticated"}}}


      (let [{:keys [username] :as credentials} body-params]
        (if-let [user-id (users-api/login database credentials)]
          (let [user (-> database
                         (user/get-user-profile-by-id user-id)
                         (assoc :avatar_url (format "users/%s/avatar" username)))]
            (log/info "Authenticated login!")
            (assoc (ok user)
                   :cookies {:access-token {:value "SOME-VALUE"}}
                   :session (assoc session :identity user-id)))
          (do (log/info "Invalid username/password")
              (unauthorized {:message "Unable to login"})))))

    (POST "/logout" []
      (-> (ok {:message "Logout successful"})
          (assoc :session nil)))))
