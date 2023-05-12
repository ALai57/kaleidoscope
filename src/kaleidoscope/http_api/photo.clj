(ns kaleidoscope.http-api.photo
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core :as amazon]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clj-img-resize.core :as img]
            [compojure.api.sweet :refer [context POST]]
            [kaleidoscope.api.albums :as albums-api]
            [kaleidoscope.persistence.filesystem :as fs]
            [kaleidoscope.models.albums] ;; Install specs
            [kaleidoscope.utils.core :as utils]
            [ring.util.http-response :refer [created]]
            [taoensso.timbre :as log]))

(def MEDIA-FOLDER
  "media")

(defn ->file-input-stream
  [file]
  (java.io.FileInputStream. ^java.io.File file))

(defn bucket-name
  "Getting host name is from ring.util.request"
  [request]
  (let [server-name (get-in request [:headers "host"])]
    (str/join "." (butlast (str/split server-name #"\.")))))

(def photo-routes
  (context (format "/%s" MEDIA-FOLDER) []
    :coercion   :spec
    :components [static-content-adapters database]
    :tags       ["photos"]

    (POST "/" {:keys [uri params] :as req}
      :swagger {:summary     "Upload a new file"
                :description "Add a new image"
                :produces    #{"application/json"}
                :responses   {200 {:description "An album"
                                   :schema      :kaleidoscope.albums/album}}}
      (let [{:keys [filename tempfile] :as file-contents} (get params "file-contents")
            file-path                                     (format "%s/%s" (if (clojure.string/ends-with? uri "/")
                                                                            (subs uri 1 (dec (count uri)))
                                                                            (subs uri 1))
                                                                  filename)
            metadata                                      (dissoc file-contents :tempfile)
            now-time                                      (utils/now)]
        (log/infof "Processing upload request with params:\n %s" (-> params
                                                                     clojure.pprint/pprint
                                                                     with-out-str))
        (log/infof "Creating file `%s` with metadata:\n %s" file-path (-> metadata
                                                                          clojure.pprint/pprint
                                                                          with-out-str))
        (-> static-content-adapters
            (get (bucket-name req))
            (fs/put-file file-path
                         (->file-input-stream tempfile)
                         metadata))
        (let [photo (albums-api/create-photo! database {:id          (java.util.UUID/randomUUID)
                                                        :photo-src   file-path
                                                        :created-at  now-time
                                                        :modified-at now-time})]
          (created (format "/%s" file-path)
                   photo))))))

(comment
  (def image
    (clojure.java.io/resource "public//images/example-image.png"))

  (slurp image)

  (slurp (img/scale-image-to-dimension-limit image 10 10 "jpeg"))

  (io/copy (img/scale-image-to-dimension-limit image 100 100 "jpeg")
           (io/file "/home/andrew/dev/example-image-out.jpeg"))

  )

(def IMAGE-DIMENSIONS
  ;; category  wd   ht   type
  {:thumbnail [100  100 "jpeg"]
   :gallery   [165  165 "jpeg"]
   :monitor   [1920 1080 "jpeg"]
   :mobile    [1200 630 "jpeg"]})

(def photo-routes-v2
  (context "/v2/photos" []
    :coercion   :spec
    :components [static-content-adapters database]
    :tags       ["photos"]

    (POST "/" {:keys [uri params] :as req}
      :swagger {:summary     "Upload a new file"
                :description "Add a new image"
                :produces    #{"application/json"}
                :responses   {200 {:description "A photo"
                                   :schema      :kaleidoscope.albums/album}}}
      (let [{:keys [filename tempfile] :as file-contents} (get params "file")
            file-path                                     (format "%s/%s" (if (clojure.string/ends-with? uri "/")
                                                                            (subs uri 1 (dec (count uri)))
                                                                            (subs uri 1))
                                                                  filename)
            metadata                                      (dissoc file-contents :tempfile)
            now-time                                      (utils/now)
            id                                            (java.util.UUID/randomUUID)
            new-url                                       (format "/v2/photos/%s" id)]
        (log/infof "Processing upload request with params:\n %s" (-> params
                                                                     clojure.pprint/pprint
                                                                     with-out-str))
        (log/infof "Creating file `%s` with metadata:\n %s" file-path (-> metadata
                                                                          clojure.pprint/pprint
                                                                          with-out-str))
        (doseq [[image-type [w h t]] IMAGE-DIMENSIONS]
          (log/infof "Resizing %s image [%s] to %spx by %spx" file-path id w h)
          (-> static-content-adapters
              (get (bucket-name req))
              (fs/put-file (format "%s/%s/%s.%s" MEDIA-FOLDER id image-type t)
                           (img/scale-image-to-dimension-limit tempfile w h t)
                           metadata)))
        (let [photo (albums-api/create-photo! database {:id          (java.util.UUID/randomUUID)
                                                        :photo-src   new-url
                                                        :created-at  now-time
                                                        :modified-at now-time})]
          (created new-url photo))))))
