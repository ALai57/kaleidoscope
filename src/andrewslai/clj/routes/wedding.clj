(ns andrewslai.clj.routes.wedding
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core :as amazon]
            [andrewslai.clj.auth.core :as auth]
            [buddy.auth.accessrules :as ar]
            [compojure.api.sweet :refer [context defroutes PUT]]
            [ring.util.http-response :refer [ok]]
            [andrewslai.clj.persistence.filesystem :as fs]
            [clojure.java.io :as io]))

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
    :request-method :put
    :handler (partial require-role "wedding")}])

(defn get-filename
  [params]
  (get params "name"))

(defn get-upload-map
  [params]
  (get params (get-filename params)))

(defn get-file
  [params]
  (get-in params [(get-filename params) :tempfile]))

(defroutes upload-routes
  (context (format "/%s" MEDIA-FOLDER) []
    :components [storage]
    (PUT "/:path" {:keys [uri params] :as req}
      (let [content (get-file params)]
        (fs/put-file storage
                     (subs uri 1) ;; Trim leading /
                     (java.io.FileInputStream. ^java.io.File content)
                     (-> params
                         (dissoc (get-filename params))
                         (assoc :content-length (:size (get-upload-map params)))
                         (assoc :content-type (get params "content-type")))))
      (ok (str "GOT TO PUT" uri)))))

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
