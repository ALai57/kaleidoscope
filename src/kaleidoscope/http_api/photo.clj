(ns kaleidoscope.http-api.photo
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core :as amazon]
            [clj-img-resize.core :as img]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.string :as string]
            [compojure.api.sweet :refer [context POST GET]]
            [kaleidoscope.api.albums :as albums-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [kaleidoscope.models.albums] ;; Install specs
            [kaleidoscope.persistence.filesystem :as fs]
            [kaleidoscope.utils.core :as utils]
            [kaleidoscope.utils.core :as u]
            [ring.util.http-response :refer [created ok not-found]]
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
  {:thumbnail [100  100  "jpeg"]
   :gallery   [165  165  "jpeg"]
   :monitor   [1920 1080 "jpeg"]
   :mobile    [1200 630  "jpeg"]})

(defn get-file-extension
  [path]
  (last (string/split path #"\.")))

(def photo-routes-v2
  (context "/v2/photos" []
    :coercion   :spec
    :components [static-content-adapters database]
    :tags       ["photos"]

    (GET "/:id" [id]
      :swagger {:summary  "Get photos"
                :produces #{"application/json"}
                :security [{:andrewslai-pkce ["roles" "profile"]}]}
      (log/infof "Getting photo %s" id)
      (let [photos (albums-api/get-full-photos database {:id id})]
        (if (empty? photos)
          (not-found {:reason "Missing"})
          (ok (map (fn [{:keys [id filename] :as photo}]
                     (assoc photo :path (format "/v2/photos/%s/%s" id filename))) photos)))))

    (POST "/" {:keys [uri params] :as req}
      :swagger {:summary     "Upload a new file"
                :description "Add a new image"
                :produces    #{"application/json"}
                :responses   {200 {:description "A photo"
                                   :schema      :kaleidoscope.albums/album}}}

      (log/infof "Processing upload request with params:\n %s" (-> params
                                                                   clojure.pprint/pprint
                                                                   with-out-str))
      (let [photo-id (java.util.UUID/randomUUID)
            hostname (hu/get-host req)
            bucket   (bucket-name req)

            static-content-adapter (get static-content-adapters bucket)

            {:keys [filename tempfile] :as file} (get params "file")]

        (let [photo (albums-api/create-photo! database {:id photo-id :hostname hostname})]
          (albums-api/create-photo-version-2! database
                                              static-content-adapter
                                              {:photo-id       photo-id
                                               :image-category "raw"
                                               :file           (assoc file
                                                                      :file-input-stream (u/->file-input-stream tempfile)
                                                                      :extension         (get-file-extension filename))})
          (doseq [[image-category [w h t]] IMAGE-DIMENSIONS
                  :let [resized-image (img/scale-image-to-dimension-limit tempfile w h t)]]
            (albums-api/create-photo-version-2! database
                                                static-content-adapter
                                                {:photo-id       photo-id
                                                 :image-category (name image-category)
                                                 :file           (-> params
                                                                     (get "file")
                                                                     (assoc :file-input-stream resized-image
                                                                            :extension         t))}))
          (created (format "/v2/photos/%s" photo-id) photo))))))




(comment

  ;; Create the photo and the raw version - a pointer to the S3 bucket folder
  #_(let [photo            (albums-api/create-photo! database {:id       id
                                                               :hostname hostname})
          original-version (albums-api/create-photo-version-2! database {:photo-id       id
                                                                         :filename       raw-file-name
                                                                         :image-category "raw"})]

      _                (fs/put-file (str images-path raw-file-name)
                                    (->file-input-stream tempfile)
                                    metadata)
      ;; Create resized photo versions
      #_(doseq [[image-category [w h t]] IMAGE-DIMENSIONS
                :let                     [version-src (format "%s/%s.%s" id (name image-category) t)]]
          (log/infof "Resizing %s image [%s] to %spx by %spx" file-path id w h)
          (-> static-content-adapters
              (get (bucket-name req))
              (fs/put-file version-src
                           (img/scale-image-to-dimension-limit tempfile w h t)
                           metadata))
          (albums-api/create-photo-version! database {:photo-id          id
                                                      :photo-version-src version-src
                                                      :image-category    (name image-category)}))
      (created (format "/v2/photos/%s" id) photo))



  (slurp xxx)

  (io/copy (io/input-stream (b64/decode (slurp xxx)))
           (io/file "/home/andrew/dev/example-image-out.png"))

  (img/scale-image-to-dimension-limit (io/input-stream xxx) 100 100 "jpeg")
  )
;; Java resizing and synchronous API with random UUID.

;; Job
;; -- Async, easy to change independent of server, less compute
;; -- Can do in another non java language
;;
;; Java
;; -- Sync, more compute for now
;; -- Must do in CLJ


;; S3 bucket with random UUID /bucket/UUID/400.png, /bucket/UUID/raw.png, etc...
;; -- easy to rename things
;; -- bucket doesn't have meaning - not human navigable
;; -- get all versions of a photo is easy in bucket:
;; -- getting all thumbnails could be harder.
;;
;; S3 bucket with name /bucket/name/400.png, /bucket/name/raw.png, etc...
;; -- medium to rename
;; -- bucket has meaning - can be human navigable
;; -- get all versions of a photo is easy in the bucket
;; -- getting all thumbnails could be harder.
;;
;;
;; S3 bucket with name /bucket/name-400.png, /bucket/name-raw.png, etc...
;; -- Difficult to rename anything and keep consistent
