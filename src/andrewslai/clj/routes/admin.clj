(ns andrewslai.clj.routes.admin
  (:require [buddy.auth.accessrules :refer [restrict]]
            [compojure.api.sweet :refer [context defroutes GET]]
            [ring.util.http-response :refer [ok]]
            [taoensso.timbre :as log]))

(defn is-authenticated? [{:keys [user] :as req}]
  (log/info "Is authenticated?" (not (empty? user)))
  (not (empty? user)))

(defn access-error [request value]
  (log/info "Not authorized for endpoint")
  {:status 401
   :headers {}
   :body "Not authorized"})

(defroutes admin-get
  (GET "/" request (do (log/info "User Authorized for /admin/ route")
                       (ok {:message "Got to the admin-route!"}))))

(defroutes admin-routes
  (context "/admin" []
    (restrict admin-get {:handler is-authenticated?
                         :on-error access-error})))