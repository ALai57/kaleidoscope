(ns kaleidoscope.http-api.photo
  (:require
   [clojure.string :as str]
   [compojure.api.sweet :refer [context GET POST]]
   [compojure.api.middleware :as cmw]
   [image-resizer.core :as rc]
   [image-resizer.format :as rf]
   [kaleidoscope.api.albums :as albums-api]
   [kaleidoscope.http-api.http-utils :as http-utils]
   [kaleidoscope.utils.core :as u]
   [ring.util.http-response :refer [created not-found ok]]
   [steffan-westcott.clj-otel.api.trace.span :as span]
   [taoensso.timbre :as log]))

(def MEDIA-FOLDER
  "media")

(defn bucket-name
  "Getting host name is from ring.util.request"
  [request]
  (let [server-name (get-in request [:headers "host"])]
    (str/join "." (butlast (str/split server-name #"\.")))))

(def IMAGE-DIMENSIONS
  ;; category  wd   ht
  {:raw       nil
   :thumbnail [100  100 ]
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

(defn process-photo-upload!
  [{:keys [params] :as req}
   {:keys [filename tempfile] :as file}]
  (let [{:keys [static-content-adapters database]} (cmw/get-components req)

        hostname (http-utils/get-host req)
        bucket   (bucket-name req)

        static-content-adapter (get static-content-adapters bucket)

        photo-id  (java.util.UUID/randomUUID)
        extension (get-file-extension filename)]
    (log/infof "Processing file %s" filename)
    (let [photo   (albums-api/create-photo! database {:id photo-id :hostname hostname})
          resized (for [[image-category [w h :as resize]] IMAGE-DIMENSIONS

                        :let [image-stream (if resize
                                             (rf/as-stream (rc/resize tempfile w h) extension)
                                             (u/->file-input-stream tempfile))]]
                    {:filename   (format "%s.%s" (name image-category) extension)
                     :version-id (albums-api/create-photo-version-2! database
                                                                     (assoc static-content-adapter :photos-folder MEDIA-FOLDER)
                                                                     {:photo-id       photo-id
                                                                      :image-category (name image-category)
                                                                      :file           (-> params
                                                                                          (get "file")
                                                                                          (assoc :file-input-stream image-stream
                                                                                                 :extension         extension))})})]
      {:photo-id    photo-id
       :version-ids (flatten resized)})))

(def photo-routes
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
      (let [result (->> params
                        vals
                        (filter file-upload?)
                        (mapv (partial process-photo-upload! req)))]

        ;; Todo create a batch response
        (assoc-in (created "/v2/photos" result)
                  [:headers "Content-Type"]
                  "application/json"))
      )))


(comment
  ;; TODO: Delete `photos` version 1
  ;; TODO: Add mechanism to delete photos from index and from bucket.
  ;; TODO: Edit photo name and API for retrieving photos by name
  ;; TODO: Photos with authentication!

  ;; TODO: Websocket for upload progress? =D
  ;;

  ;; TODO: Dsitributed tracing FE->BE
  ;; TODO: K6 performance testing

  )
