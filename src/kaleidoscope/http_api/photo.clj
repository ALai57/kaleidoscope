(ns kaleidoscope.http-api.photo
  (:require [clojure.string :as str]
            [cheshire.core :as json]
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

        static-content-adapter (get static-content-adapters hostname)

        photo-id  (java.util.UUID/randomUUID)
        extension (get-file-extension filename)]
    (log/infof "Processing file %s" filename)
    (let [photo   (albums-api/create-photo! database {:id photo-id :hostname hostname})
          resized (for [[image-category [w h :as resize]] IMAGE-DIMENSIONS

                        :let [image-stream (if resize
                                             (rf/as-stream (rc/resize tempfile w h) extension)
                                             (u/->file-input-stream tempfile))]]
                    (first (albums-api/create-photo-version-2! database
                                                               (assoc static-content-adapter :photos-folder MEDIA-FOLDER)
                                                               {:photo-id       photo-id
                                                                :image-category (name image-category)
                                                                :file           (-> params
                                                                                    (get "file")
                                                                                    (assoc :file-input-stream image-stream
                                                                                           :extension         extension))})))]
      {:photo-id photo-id
       :versions (vec resized)})))

(def Version
  [:map
   [:id :uuid]
   [:photo-id :uuid]
   [:image-category :string]
   [:path :string]
   [:storage-driver :string]
   [:storage-root :string]
   [:modified-at inst?]
   [:created-at inst?]])

(def CreatePhotoResponse
  [:map
   [:photo-id :uuid]
   [:versions [:sequential Version]]])

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
                                                       {:schema CreatePhotoResponse}}}})
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
                                ;; Must be JSON encoded because Jetty doesn't know how to serialize
                                ;; Clojure vectors
                                (assoc-in (created "/v2/photos" (json/encode result))
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
                                                     (assoc photo :path (format "/v2/photos/%s/%s" id filename))) photos)))))}

                  :put {:summary   "Update photo"
                        :responses (merge hu/openapi-401
                                          {200 {:description "The photo metadata that was updated"
                                                :content     {"application/json"
                                                              {:schema [:any]}}}})

                        :request    {:description "Photo metadata"
                                     :content     {"application/json"
                                                   {:schema   [:map
                                                               [:title {:optional true} :string]
                                                               [:description {:optional true} :string]]
                                                    :examples {"example-update" {:summary "Example update"
                                                                                 :value   {:title       "My title"
                                                                                           :description "My photo taken somewhere"}}}}}}
                        :parameters {:path {:photo-id uuid?}}
                        :handler    (fn [{:keys [components body-params path-params] :as request}]
                                      (let [{:keys [photo-id]} path-params

                                            id       (parse-uuid photo-id)
                                            _        (log/infof "Getting photo %s" photo-id)
                                            hostname (hu/get-host request)
                                            photo   (albums-api/get-photos (:database components) {:id       id
                                                                                                   :hostname hostname})]
                                        (if (empty? photo)
                                          (do
                                            (log/warnf "Photo `%s` does not exist for `%s`" photo-id hostname)
                                            (not-found {:reason "Missing"}))
                                          (ok (albums-api/update-photo! (:database components) (merge {:id id}
                                                                                                      body-params))))))}
                  }]

   ["/:photo-id/:filename" {:get {:summary    "Get a particular photo"
                                  :responses  (merge hu/openapi-401
                                                     {200 {:description "The photo"
                                                           :content     {"application/json"
                                                                         {:schema [:any]}}}})
                                  :parameters {:path {:photo-id string?
                                                      :filename string?}}
                                  :handler    (fn [{:keys [components parameters] :as request}]
                                                (span/with-span! {:name (format "kaleidoscope.photos.get-file")}
                                                  (let [path-params                                     (:path parameters)
                                                        [{:keys [path] :as version} :as photo-versions] (albums-api/get-full-photos (:database components) path-params)]
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
