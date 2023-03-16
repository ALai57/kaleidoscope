(ns kaleidoscope.clj.http-api.photo
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core :as amazon]
            [kaleidoscope.clj.api.albums :as albums-api]
            [kaleidoscope.clj.persistence.filesystem :as fs]
            [kaleidoscope.cljc.specs.albums] ;; Install specs
            [compojure.api.sweet :refer [context POST]]
            [ring.util.http-response :refer [created]]
            [taoensso.timbre :as log]))

(def MEDIA-FOLDER
  "media")

(defn ->file-input-stream
  [file]
  (java.io.FileInputStream. ^java.io.File file))

(defn now []
  (java.time.LocalDateTime/now))

(def photo-routes
  (context (format "/%s" MEDIA-FOLDER) []
    :coercion   :spec
    :components [static-content-adapter database]
    :tags       ["photos"]

    (POST "/" {:keys [uri params] :as req}
      :swagger {:summary     "Upload a new file"
                :description "Add a new image"
                :produces    #{"application/json"}
                :responses   {200 {:description "An album"
                                   :schema      :andrewslai.albums/album}}}
      (let [{:keys [filename tempfile] :as file-contents} (get params "file-contents")
            file-path                                     (format "%s/%s" (if (clojure.string/ends-with? uri "/")
                                                                            (subs uri 1 (dec (count uri)))
                                                                            (subs uri 1))
                                                                  filename)
            metadata                                      (dissoc file-contents :tempfile)
            now-time                                      (now)]
        (log/infof "Processing upload request with params:\n %s" (-> params
                                                                     clojure.pprint/pprint
                                                                     with-out-str))
        (log/infof "Creating file `%s` with metadata:\n %s" file-path (-> metadata
                                                                          clojure.pprint/pprint
                                                                          with-out-str))
        (fs/put-file static-content-adapter
                     file-path
                     (->file-input-stream tempfile)
                     metadata)
        (let [photo (albums-api/create-photo! database {:id          (java.util.UUID/randomUUID)
                                                        :photo-src   file-path
                                                        :created-at  now-time
                                                        :modified-at now-time})]
          (created (format "/%s" file-path)
                   photo))))))

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
