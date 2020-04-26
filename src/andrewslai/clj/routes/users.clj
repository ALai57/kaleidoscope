(ns andrewslai.clj.routes.users
  (:require [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.utils :refer [file->bytes parse-body]]            
            [cheshire.core :as json]
            [clojure.data.codec.base64 :as b64]
            [compojure.api.sweet :refer [context defroutes GET PATCH POST]]
            [clojure.java.io :as io]
            [ring.util.http-response :refer [ok not-found created bad-request]]
            [ring.util.response :as response]
            [slingshot.slingshot :refer [throw+ try+]]
            [taoensso.timbre :as log]))

;; Extract to encoding ns
(defn decode-avatar [avatar]
  (if avatar
    (b64/decode (.getBytes avatar))
    (file->bytes (clojure.java.io/resource "avatars/happy_emoji.jpg"))))

(def default-avatar (-> "avatars/happy_emoji.jpg"
                        clojure.java.io/resource
                        file->bytes))

(defroutes users-routes
  (context "/users" {:keys [components]}

    (GET "/:username" [username]
      (ok (dissoc (users/get-user (:user components) username) :id)))

    (PATCH "/:username" {{:keys [username]} :route-params :as request}
      (let [{:keys [avatar] :as update-map}
            (-> request
                :body
                slurp
                (json/parse-string keyword))

            update-avatar (fn [m]
                            (if avatar
                              (assoc m :avatar (b64/decode (.getBytes avatar)))
                              m))]
        (ok (users/update-user (:user components)
                               username
                               (-> update-map
                                   (dissoc :username)
                                   update-avatar)))))

    (POST "/" request
      (try+ 
        (let [{:keys [username avatar password] :as payload} (parse-body request)

              decoded-avatar (or (some-> avatar
                                         .getBytes
                                         b64/decode)
                                 default-avatar)

              result (users/register-user! (:user components)
                                           (-> payload
                                               (assoc :avatar decoded-avatar)
                                               (dissoc :password))
                                           password)]
          (-> (created)
              (assoc :headers {"Location" (str "/users/" username)})
              (assoc :body result)
              (assoc-in [:body :avatar_url] (format "users/%s/avatar" username))))
        (catch [:type org.postgresql.util.PSQLException] e
          (-> (bad-request)
              (assoc :body (.getMessage e))))
        (catch [:type IllegalArgumentException] e
          (-> (bad-request)
              (select-keys e [:data :reason])))))

    (GET "/:username/avatar" [username]
      (let [{:keys [avatar]}
            (users/get-user (:user components) username)]
        (if avatar
          (-> (response/response (io/input-stream avatar))
              (response/content-type "image/png"))
          (not-found (format "Cannot find user: %s" username)))))))
