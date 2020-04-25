(ns andrewslai.clj.routes.users
  (:require [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.utils :refer [file->bytes]]            
            [cheshire.core :as json]
            [clojure.data.codec.base64 :as b64]
            [compojure.api.sweet :refer [context defroutes GET PATCH POST]]
            [clojure.java.io :as io]
            [ring.util.http-response :refer [ok not-found created]]
            [ring.util.response :as response]
            [taoensso.timbre :as log]))

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
      (let [{:keys [username avatar password] :as payload}
            (-> request
                :body
                slurp
                (json/parse-string keyword))

            decoded-avatar (if avatar
                             (b64/decode (.getBytes avatar))
                             (file->bytes (clojure.java.io/resource
                                            "avatars/happy_emoji.jpg")))

            {:keys [username] :as result}
            (users/register-user! (:user components)
                                  (-> payload
                                      (assoc :avatar decoded-avatar)
                                      (dissoc :password))
                                  password)]
        (-> (created)
            (assoc :headers {"Location" (str "/users/" username)})
            (assoc :body result)
            (assoc-in [:body :avatar_url] (format "users/%s/avatar" username)))))

    (GET "/:username/avatar" [username]
      (let [{:keys [avatar]}
            (users/get-user (:user components) username)]
        (if avatar
          (-> (response/response (io/input-stream avatar))
              (response/content-type "image/png"))
          (not-found (format "Cannot find user: %s" username)))))))
