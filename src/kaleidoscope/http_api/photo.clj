(ns kaleidoscope.http-api.photo
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [compojure.api.sweet :refer [context GET POST]]
   [image-resizer.core :as rc]
   [image-resizer.format :as rf]
   [kaleidoscope.api.albums :as albums-api]
   [kaleidoscope.http-api.http-utils :as http-utils]
   [kaleidoscope.persistence.filesystem :as fs]
   [kaleidoscope.utils.core :as u]
   [ring.util.http-response :refer [created multi-status not-found ok]]
   [steffan-westcott.clj-otel.api.trace.span :as span]
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
            file-path                                     (format "%s/%s" (if (str/ends-with? uri "/")
                                                                            (subs uri 1 (dec (count uri)))
                                                                            (subs uri 1))
                                                                  filename)
            metadata                                      (dissoc file-contents :tempfile)
            now-time                                      (u/now)]
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
  ;; category  wd   ht
  {:thumbnail [100  100 ]
   :gallery   [165  165 ]
   :monitor   [1920 1080]
   :mobile    [1200 630 ]})

(defn get-file-extension
  [path]
  (last (str/split path #"\.")))

(defn file-upload?
  "TODO replace with Malli spec"
  [x]
  (and (map? x)
       (:filename x)
       (:tempfile x)))

(def photo-routes-v2
  (context "/v2/photos" []
    :coercion   :spec
    :components [static-content-adapters database]
    :tags       ["photos"]

    (GET "/" req
      :swagger {:summary  "Get photos"
                :produces #{"application/json"}
                :security [{:andrewslai-pkce ["roles" "profile"]}]}
      (log/infof "Getting photos matchng %s" (:params req))
      (let [hostname (http-utils/get-host req)
            photos   (albums-api/get-full-photos database (assoc (:params req) :hostname hostname))]
        (ok (map (fn [{:keys [id filename] :as photo}]
                   (assoc photo :path (format "/v2/photos/%s/%s" id filename))) photos))))

    (GET "/:photo-id" [photo-id :as request]
      :swagger {:summary  "Get photos"
                :produces #{"application/json"}
                :security [{:andrewslai-pkce ["roles" "profile"]}]}
      (log/infof "Getting photo %s" photo-id)
      (let [hostname (http-utils/get-host request)
            photos   (albums-api/get-full-photos database {:id       photo-id
                                                           :hostname hostname})]
        (if (empty? photos)
          (not-found {:reason "Missing"})
          (ok (map (fn [{:keys [id filename] :as photo}]
                     (assoc photo :path (format "/v2/photos/%s/%s" id filename))) photos)))))

    (GET "/:photo-id/:filename" request
      :swagger {:summary  "Get photos"
                :produces #{"application/json"}
                :security [{:andrewslai-pkce ["roles" "profile"]}]}
      (span/with-span! {:name (format "kaleidoscope.photos.get-file")}
        (let [[version] (albums-api/get-full-photos database (:params request))]
          (http-utils/get-resource static-content-adapters (-> request
                                                               (assoc :uri (:path version))
                                                               http-utils/kebab-case-headers)))))

    (POST "/" {:keys [uri params] :as req}
      :swagger {:summary     "Upload a new file"
                :description "Add a new image"
                :produces    #{"application/json"}
                :responses   {200 {:description "A photo"
                                   :schema      :kaleidoscope.albums/album}}}

      (log/infof "Processing upload request with params:\n %s" (-> params
                                                                   clojure.pprint/pprint
                                                                   with-out-str))
      (let [hostname (http-utils/get-host req)
            bucket   (bucket-name req)

            static-content-adapter (get static-content-adapters bucket)]

        (doseq [{:keys [filename tempfile] :as file} (->> params
                                                          vals
                                                          (filter file-upload?))
                :let [photo-id (java.util.UUID/randomUUID)
                      extension (get-file-extension filename)]]
          (log/infof "Processing file %s" filename)
          (let [photo (albums-api/create-photo! database {:id photo-id :hostname hostname})]
            (albums-api/create-photo-version-2! database
                                                (assoc static-content-adapter :photos-folder MEDIA-FOLDER)
                                                {:photo-id       photo-id
                                                 :image-category "raw"
                                                 :file           (assoc file
                                                                        :file-input-stream (u/->file-input-stream tempfile)
                                                                        :extension         extension)})
            (doseq [[image-category [w h t]] IMAGE-DIMENSIONS
                    :let [resized-image (rf/as-stream (rc/resize tempfile w h) extension)]]
              (albums-api/create-photo-version-2! database
                                                  (assoc static-content-adapter :photos-folder MEDIA-FOLDER)
                                                  {:photo-id       photo-id
                                                   :image-category (name image-category)
                                                   :file           (-> params
                                                                       (get "file")
                                                                       (assoc :file-input-stream resized-image
                                                                              :extension         extension))}))
            )))

      ;; Todo create a batch response
      (assoc-in (multi-status (json/generate-string {:created true}))
                [:headers "Content-Type"]
                "application/json")
      )))




(comment
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
