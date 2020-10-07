(ns andrewslai.clj.routes.users
  (:require [andrewslai.clj.api.users :as users-api]
            [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.routes.admin :refer [access-error]]
            [andrewslai.clj.utils :refer [file->bytes parse-body]]
            [buddy.auth.accessrules :refer [restrict]]
            [cheshire.core :as json]
            [clojure.data.codec.base64 :as b64]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [compojure.api.sweet :refer [context defroutes DELETE GET PATCH POST]]
            [ring.util.http-response :refer [bad-request created not-found ok no-content]]
            [ring.util.response :as response]
            [slingshot.slingshot :refer [try+]]
            [taoensso.timbre :as log]))

;; TODO: Add spec based schema validation.
;; https://www.metosin.fi/blog/clojure-spec-with-ring-and-swagger/

(s/def ::user (s/keys :req-un [::users/avatar
                               ::users/first_name
                               ::users/last_name
                               ::users/username
                               ::users/email
                               ::users/password]))

(s/def ::avatar string?)
(s/def ::avatar_url string?)
(s/def ::created_user (s/keys :req-un [::avatar
                                       ::users/first_name
                                       ::users/last_name
                                       ::users/username
                                       ::users/email
                                       ::avatar_url]))

(defn decode-avatar [{:keys [avatar] :as m}]
  (if avatar
    (assoc m :avatar (b64/decode (.getBytes avatar)))
    m))



(defroutes users-routes
  ;; TODO: #2 - rename db to something more meaningful - this is not a db, it's
  ;; a component that has a DB backing it
  (context "/users" {{db :user database :database} :components}
    :tags ["users"]
    :coercion :spec

    (GET "/:username" [username]
      :swagger {:summary "Retrieve a user's profile"
                :produces #{"application/json"}
                :responses {200 {:description "User profile"
                                 :schema ::users/user}
                            404 {:description "Not found"
                                 :schema string?}}}
      (let [result (users-api/get-user db username)]
        (if result
          (ok result)
          (not-found))))

    (DELETE "/:username" {credentials :body-params}
      (let [result (users-api/delete-user! db credentials)]
        (if (= 1 result)
          (no-content)
          (not-found))))

    (PATCH "/:username" {{:keys [username]} :route-params
                         update-map :body-params
                         :as request}
      :swagger {:summary "Update a user"
                :consumes #{"application/json"}
                :produces #{"application/json"}
                :parameters {:body ::users/user-update}
                :responses {200 {:description "The updated fields"
                                 :schema ::users/user-update}}}
      (try+
       (let [result (users-api/update-user! db
                                            username
                                            (decode-avatar update-map))]
         ;; TODO: Find a nice way to transform this to a URL
         (-> result
             (assoc :avatar_url (format "users/%s/avatar" username))
             ok))
       (catch [:type :IllegalArgumentException] e
         (-> (bad-request)
             (assoc :body e)))))

    (POST "/" request
      :swagger {:summary "Create a user"
                :consumes #{"application/json"}
                :produces #{"application/json"}
                :parameters {:body ::user}
                :responses {200 {:description "The user that was created"
                                 :schema ::created_user}}}
      (try+ 
       (let [{:keys [username password] :as payload} (:body-params request)
             result (users-api/register-user! database
                                              (-> payload
                                                  (dissoc :password)
                                                  decode-avatar)
                                              password)]
         (log/info "User created:" result)
         (-> (created)
             (assoc :headers {"Location" (str "/users/" username)})
             (assoc :body result)
             (assoc-in [:body :avatar_url] (format "users/%s/avatar" username))))
       (catch [:type :PersistenceException] e
         (log/info (pr-str e))
         (-> (bad-request)
             (assoc :body e)))
       (catch [:type :IllegalArgumentException] e
         (log/info (pr-str e))
         (-> (bad-request)
             (assoc :body e)))))

    (GET "/:username/avatar" [username]
      :swagger {:summary "Get a user's avatar"
                :produces #{"image/png"}
                :responses {200 {:description "User's avatar"
                                 :schema bytes?}
                            404 {:description "Not found"
                                 :schema string?}}}
      (let [{:keys [avatar]}
            (users/get-user db username)]
        (if avatar
          (-> (response/response (io/input-stream avatar))
              (response/content-type "image/png")
              (response/header "Cache-control" "max-age=0, must-revalidate"))
          (not-found (format "Cannot find user: %s" username)))))))
