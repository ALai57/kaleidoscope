(ns andrewslai.clj.routes.users
  (:require [andrewslai.clj.persistence.users :as users]
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
            [slingshot.slingshot :refer [try+]]))

;; TODO: Add spec based schema validation.
;; https://www.metosin.fi/blog/clojure-spec-with-ring-and-swagger/
;; TODO: Make inbound adapter for data

;; Extract to encoding ns
(defn decode-avatar [avatar]
  (if avatar
    (b64/decode (.getBytes avatar))
    (file->bytes (clojure.java.io/resource "avatars/happy_emoji.jpg"))))

(s/def ::avatar string?)
(s/def ::user (s/keys :req-un [::avatar
                               ::users/first_name
                               ::users/last_name
                               ::users/username
                               ::users/email
                               ::users/role_id]))

(defroutes users-routes
  (context "/users" {:keys [components]}

    (GET "/:username" [username]
      (let [result (dissoc (users/get-user (:user components) username) :id)]
        (if result
          (ok result)
          (not-found))))

    (DELETE "/:username" request
      (let [credentials (parse-body request)
            verified? (users/verify-credentials (:user components) credentials)]
        (if verified?
          (do (when (= 1 (users/delete-user! (:user components) credentials))
                (no-content)))
          access-error)))

    (PATCH "/:username" {{:keys [username]} :route-params :as request}
      (try+
       (let [update-map (parse-body request)

             decode-avatar (fn [{:keys [avatar] :as m}]
                             (if avatar
                               (assoc m :avatar (b64/decode (.getBytes avatar)))
                               m))

             response (-> components
                          :user
                          (users/update-user username (decode-avatar update-map))
                          ok)]

         (assoc-in response
                   [:body :avatar_url]
                   (format "users/%s/avatar" username)))
       (catch [:type :IllegalArgumentException] e
         (-> (bad-request)
             (assoc :body e)))))

    (POST "/" request
      ;; Call inbound adapter -> register-user -> dispatch to create-user/login
      ;; -> database
      (try+ 
       (let [{:keys [username password] :as payload} (parse-body request)
             result (users/register-user! (:user components)
                                          (dissoc payload :password)
                                          password)]
         (-> (created)
             (assoc :headers {"Location" (str "/users/" username)})
             (assoc :body result)
             (assoc-in [:body :avatar_url] (format "users/%s/avatar" username))))
       (catch [:type :PSQLException] e
         (-> (bad-request)
             (assoc :body e)))
       (catch [:type :IllegalArgumentException] e
         (-> (bad-request)
             (assoc :body e)))))

    (GET "/:username/avatar" [username]
      (let [{:keys [avatar]}
            (users/get-user (:user components) username)]
        (if avatar
          (-> (response/response (io/input-stream avatar))
              (response/content-type "image/png")
              (response/header "Cache-control" "max-age=0, must-revalidate"))
          (not-found (format "Cannot find user: %s" username)))))))
