(ns kaleidoscope.http-api.photo
  (:require [clojure.string :as str]
            [compojure.api.middleware :as cmw]
            [image-resizer.core :as rc]
            [image-resizer.format :as rf]
            [kaleidoscope.api.albums :as albums-api]
            [kaleidoscope.http-api.http-utils :as hu]
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
  [{:keys [params components] :as req}
   {:keys [filename tempfile] :as file}]
  (let [{:keys [static-content-adapters database]} components

        hostname (hu/get-host req)
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

(def reitit-photos-routes
  ["/v2/photos" {:tags     ["photos"]
                 :security [{:andrewslai-pkce ["roles" "profile"]}]
                 ;; For testing only - this is a mechanism to always get results from a particular
                 ;; host URL.
                 ;;
                 ;;:host      "andrewslai.localhost"
                 }
   ["" {:get {:summary   "Get photos"
              :responses (merge hu/openapi-401
                                {200 {:description "A collection of groups the user owns"
                                      :content     {"application/json"
                                                    {:schema [:any]}}}})

              :handler (fn [{:keys [components parameters] :as req}]
                         (let [query-params (:query parameters)
                               _            (log/infof "Getting photos matching %s" query-params)
                               hostname     (hu/get-host req)
                               photos       (albums-api/get-full-photos (:database components) (assoc query-params :hostname hostname))]
                           (ok (map (fn [{:keys [id filename] :as photo}]
                                      (assoc photo :path (format "/v2/photos/%s/%s" id filename))) photos))))}
        :post {:summary     "Upload a new file"
               :description "Add a new image"
               :responses   (merge hu/openapi-401
                                   {200 {:description "The group that was created"
                                         :content     {"application/json"
                                                       {:schema [:any]}}}})
               ;; Uses params because form-multipart isn't automatically included in the
               ;; `parameters`, or `body-params` keys
               :handler     (fn [{:keys [components params] :as req}]
                              (log/infof "Processing upload request with params:\n %s" (-> params
                                                                                           clojure.pprint/pprint
                                                                                           with-out-str))
                              (let [file-uploads (->> params
                                                      vals
                                                      (filter file-upload?))
                                    result       (mapv (partial process-photo-upload! req) file-uploads)]

                                ;; Todo create a batch response
                                (assoc-in (created "/v2/photos" result)
                                          [:headers "Content-Type"]
                                          "application/json")))}}]

   ["/:photo-id" {:get {:summary    "Get photo"
                        :responses  (merge hu/openapi-401
                                           {200 {:description "The group that was created"
                                                 :content     {"application/json"
                                                               {:schema [:any]}}}})
                        :parameters {:path {:photo-id string?}}
                        :handler    (fn [{:keys [components body-params path-params] :as request}]
                                      (let [{:keys [photo-id]} path-params

                                            _        (log/infof "Getting photo %s" photo-id)
                                            hostname (hu/get-host request)
                                            photos   (albums-api/get-full-photos (:database components) {:id       photo-id
                                                                                                         :hostname hostname})]
                                        (if (empty? photos)
                                          (not-found {:reason "Missing"})
                                          (ok (map (fn [{:keys [id filename] :as photo}]
                                                     (assoc photo :path (format "/v2/photos/%s/%s" id filename))) photos)))))}}]

   ["/:photo-id/:filename" {:get {:summary    "Get a particular photo"
                                  :responses  (merge hu/openapi-401
                                                     {200 {:description "The photo"
                                                           :content     {"application/json"
                                                                         {:schema [:any]}}}})
                                  :parameters {:path {:photo-id string?}}
                                  :handler    (fn [{:keys [components parameters] :as request}]
                                                (span/with-span! {:name (format "kaleidoscope.photos.get-file")}
                                                  (let [path-params                  (:path parameters)
                                                        [{:keys [path] :as version}] (albums-api/get-full-photos (:database components) path-params)]
                                                    (hu/get-resource (:static-content-adapters components) (-> request
                                                                                                               (assoc :uri path)
                                                                                                               hu/kebab-case-headers)))))}}]

   ])


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
