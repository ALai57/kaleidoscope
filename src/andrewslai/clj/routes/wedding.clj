(ns andrewslai.clj.routes.wedding
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core :as amazon]
            [andrewslai.clj.auth.core :as auth]
            [buddy.auth.accessrules :as ar]
            [compojure.api.sweet :refer [context defroutes PUT]]
            [ring.util.http-response :refer [ok]]
            [andrewslai.clj.persistence.filesystem :as fs]))

(def MEDIA-FOLDER
  "media")

(defn require-role
  [role {:keys [identity] :as request}]
  (if (contains? (auth/get-realm-roles identity) role)
    true
    (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                      role
                      (auth/get-realm-roles identity)))))

(def access-rules
  [{:pattern #"^/media/$"
    :handler (partial require-role "wedding")}
   {:pattern #"^/media.*"
    :request-method :post
    :handler (partial require-role "wedding")}])

(defroutes upload-routes
  (context (format "/%s" MEDIA-FOLDER) []
    :components [wedding-storage]
    (PUT "/:path" [path req]
      (fs/put-file wedding-storage
                   path
                   (:params req)
                   (:params req))
      (ok (str "GOT TO PUT" path)))))

(comment
  (s3-path [MEDIA-FOLDER "something.ptg"])

  (try
    (-> (s3/get-object WEDDING-BUCKET (s3-path [MEDIA-FOLDER "id"]))
        :input-stream)
    (catch Exception e
      (amazon/ex->map e)))

  (s3/list-objects-v2 {:bucket-name WEDDING-BUCKET
                       :prefix      (str MEDIA-FOLDER "/")})

  (s3/get-object WEDDING-BUCKET (s3-path [MEDIA-FOLDER "SOMETHING"]))

  )
