(ns andrewslai.clj.http-api.admin
  (:require [buddy.auth.accessrules :refer [restrict]]
            [compojure.api.sweet :refer [context defroutes GET]]
            [ring.util.http-response :refer [ok]]
            [taoensso.timbre :as log]))

(defn is-authenticated? [req]
  (try
    (log/info "Is authenticated?" (not (empty? (:identity req))))
    (not (empty? (:identity req)))
    (catch Throwable t
      nil)))

(defn access-error [request value]
  (log/info "Not authorized for endpoint")
  {:status 401
   :headers {}
   :body {:reason "Not authorized"}})

(defroutes admin-get
  (GET "/" []
    :swagger {:summary "An Echo route to see if a user is authenticated"
              :produces #{"application/json"}
              :responses {200 {:description "A collection of all articles"}}}
    (do (log/info "User Authorized for /admin/ route")
        (ok {:message "Got to the admin-route!"}))))

(defroutes admin-routes
  (context "/admin" []
    :tags ["admin"]
    (restrict admin-get {:handler is-authenticated?
                         :on-error access-error})))
