(ns andrewslai.clj.routes.wedding
  (:require [amazonica.core :as amazon]
            [amazonica.aws.s3 :as s3]
            [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.persistence.filesystem :as fs]
            [andrewslai.clj.routes.admin :as admin]
            [buddy.auth.accessrules :as ar]
            [clojure.string :as string]
            [compojure.api.sweet :refer [context defroutes GET]]
            [ring.util.http-response :refer [content-type not-found ok
                                             bad-gateway
                                             internal-server-error
                                             resource-response]]
            [spec-tools.swagger.core :as swagger]
            [taoensso.timbre :as log]))

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
  [{:pattern #"^/media.*"
    :handler (partial require-role "wedding")}])


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
