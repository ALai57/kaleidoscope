(ns andrewslai.clj.routes.wedding
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core :as amazon]
            [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.persistence.filesystem :as fs]
            [buddy.auth.accessrules :as ar]
            [clojure.java.io :as io]
            [compojure.api.sweet :refer [context defroutes GET POST PUT]]
            [ring.util.http-response :refer [ok created]]
            [ring.util.response :as ring-resp]
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
  [{:pattern #"^/media/$"
    :handler (partial require-role "wedding")}
   {:pattern #"^/media.*"
    :request-method :put
    :handler (partial require-role "wedding")}])

(defn ->file-input-stream
  [file]
  (java.io.FileInputStream. ^java.io.File file))

;; For local development
(defroutes index
  (GET "/index.html" []
    (log/info "Fetching `wedding-index.html` locally")
    (-> (ring-resp/resource-response "wedding-index.html" {:root "public"})
        (ring-resp/content-type "text/html"))))

(defroutes upload-routes
  (context (format "/%s" MEDIA-FOLDER) []
    :components [storage]

    (POST "/" {:keys [uri params] :as req}
      (let [{:keys [filename tempfile] :as file-contents} (get params "file-contents")
            file-path                                     (format "%s/%s" (if (clojure.string/ends-with? uri "/")
                                                                            (subs uri 1 (dec (count uri)))
                                                                            (subs uri 1))
                                                                  filename)
            metadata                                      (dissoc file-contents :tempfile)]
        (log/infof "Processing upload request with params:\n %s" (-> params
                                                                     clojure.pprint/pprint
                                                                     with-out-str))
        (log/infof "Creating file `%s` with metadata:\n %s" file-path (-> metadata
                                                                          clojure.pprint/pprint
                                                                          with-out-str))
        (fs/put-file storage
                     file-path
                     (->file-input-stream tempfile)
                     metadata)
        (created (format "/%s" file-path)
                 "Created file")))))

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
