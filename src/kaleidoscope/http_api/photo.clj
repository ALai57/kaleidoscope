(ns kaleidoscope.http-api.photo
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [kaleidoscope.api.albums :as albums-api]
            [kaleidoscope.api.resize :as resize]
            [kaleidoscope.http-api.http-utils :as hu]
            [kaleidoscope.http-api.tenant :as http-tenant]
            [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.persistence.filesystem :as fs]
            [kaleidoscope.utils.core :as utils]
            [kaleidoscope.utils.core :as u]
            [ring.util.http-response :refer [created not-found ok]]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [taoensso.timbre :as log]))

(defn get-file-extension
  "Return a safe file extension (letters/digits only, max 10 chars) from an
  uploaded filename, or \"bin\" if none can be extracted."
  [path]
  (let [ext (or (second (re-find #"\.([a-zA-Z0-9]{1,10})$" (or path ""))) "bin")]
    (str/lower-case ext)))

(defn file-upload?
  "TODO replace with Malli spec"
  [x]
  (and (map? x)
       (:filename x)
       (:tempfile x)))

(defn process-photo-upload!
  [{:keys [components] :as req}
   {:keys [filename tempfile] :as file}]
  (span/with-span! {:name "kaleidoscope.api.photo.upload-and-process"}
    (log/infof "Processing file %s" filename)
    (let [{:keys [static-content-adapters database]} components
          hostname (hu/tenant-hostname req)
          extension (get-file-extension filename)

          ;; The single per-env media store when registered (bucket from config),
          ;; else the request's per-tenant asset-store adapter (prod pre-cutover).
          static-content-adapter (get static-content-adapters
                                       http-tenant/media-store
                                       (get static-content-adapters (hu/asset-store req)))]
      (try
        (albums-api/new-image (assoc components :static-content-adapter static-content-adapter)
                              hostname
                              (assoc file :extension extension))
        (catch Throwable e
          (log/errorf "Caught error processing photo upload" filename)
          (log/error (ex-message e))
          (throw e))))))

(def Version
  [:map
   [:id :uuid]
   [:photo-id :uuid]
   [:image-category :string]
   [:path :string]
   [:modified-at inst?]
   [:created-at inst?]])

(defn- filename->category+ext
  "\"gallery.jpg\" -> [\"gallery\" \"jpg\"]; nil if `filename` isn't a single
  dotted name (see `albums-api/make-image-version`, the only thing that ever
  names these objects — every rendition/raw filename has exactly this shape)."
  [filename]
  (when-let [[_ category ext] (re-matches #"(.+)\.([a-zA-Z0-9]+)" (or filename ""))]
    [category ext]))

(defn- raw-media-path
  ^String [photo-id ext]
  (format "%s/%s/raw.%s" albums-api/MEDIA-FOLDER photo-id ext))

(defn serve-photo
  "Serve a photo version's bytes. Reads the version row (tenant-scoped) for its
  intrinsic `path`, then serves from the single per-env media store when
  registered, else from the request's per-tenant asset-store adapter (prod
  pre-cutover — behavior identical to before).

  Serve-path self-heal: a rendition GET that 404s (the resize gate hasn't
  produced it yet — e.g. the async warm from upload is still queued/running)
  gets one chance to heal right here, rather than making every viewer wait
  on `media:reconcile` or a retried upload. Only engaged when the store the
  request just missed against IS the resize gate's own store — never for the
  legacy per-tenant asset-store fallback, and never for a non-rendition path
  (e.g. `raw.<ext>`) where there's nothing to regenerate.
    - `{:made bytes}` from `heal-or-enqueue!` — a decode permit was free, the
      rendition now exists: serve those bytes directly, 200, no re-read.
    - `:busy` — no permit was free (or the resize attempt failed transiently);
      a warm task for this category was enqueued and the caller falls back
      to serving the *raw* bytes at the rendition's URL, marked `no-store` so
      nothing caches the raw under the rendition's key.
    - `:bad-source`/`:no-raw` — permanent problems; fall through to the
      original 404 (already ERROR-logged by `heal-or-enqueue!`/`resize-one!`
      — breakage stays visible, not silently masked)."
  [{:keys [components parameters] :as request}]
  (span/with-span! {:name "kaleidoscope.photos.get-file"}
    (let [db               (tenant/scope (:database components) (hu/tenant-hostname request))
          [{:keys [path]}] (albums-api/get-full-photos db (:path parameters))
          media-store      (get (:static-content-adapters components) http-tenant/media-store)
          response         (if media-store
                             (hu/adapter-response media-store (assoc request :uri path))
                             (hu/get-resource (:static-content-adapters components) (assoc request :uri path)))
          resize-gate      (:resize-gate components)]
      (if (and (= 404 (:status response))
               media-store
               resize-gate
               (= media-store (:store resize-gate)))
        (let [{:keys [photo-id filename]} (:path parameters)
              [category ext]              (filename->category+ext filename)]
          (if (and category (contains? resize/RENDITIONS category))
            (let [heal-result (resize/heal-or-enqueue! resize-gate photo-id category ext)]
              (cond
                (map? heal-result)
                {:status 200 :body (:made heal-result)}

                (= :busy heal-result)
                (hu/adapter-response-no-store media-store (assoc request :uri (raw-media-path photo-id ext)))

                :else response))
            response))
        response))))

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
   ["" {:get  {:summary   "Get photos"
               :responses (merge hu/openapi-401
                                 {200 {:description "A collection of groups the user owns"
                                       :content     {"application/json"
                                                     {:schema [:any]}}}})

               :handler   (fn [{:keys [components parameters] :as req}]
                            (let [query-params (:query parameters)
                                  _ (log/infof "Getting photos matching %s" query-params)
                                  db (tenant/scope (:database components) (hu/tenant-hostname req))
                                  photos (albums-api/get-full-photos db query-params)]
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
                                    result (mapv (partial process-photo-upload! req) file-uploads)]

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
                        :parameters {:path {:photo-id :uuid}}
                        :handler    (fn [{:keys [components parameters] :as request}]
                                      (let [{:keys [photo-id]} (:path parameters)

                                            _ (log/infof "Getting photo %s" photo-id)
                                            db (tenant/scope (:database components) (hu/tenant-hostname request))
                                            photos (albums-api/get-full-photos db {:id photo-id})]
                                        (if (empty? photos)
                                          (not-found {:reason "Missing"})
                                          (ok (map (fn [{:keys [id filename] :as photo}]
                                                     (assoc photo :path (format "/v2/photos/%s/%s" id filename))) photos)))))}

                  :put {:summary    "Update photo"
                        :responses  (merge hu/openapi-401
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
                        :parameters {:path {:photo-id :uuid}}
                        :handler    (fn [{:keys [components body-params parameters] :as request}]
                                      (let [{:keys [photo-id]} (:path parameters)
                                            hostname (hu/tenant-hostname request)]
                                        (log/infof "Updating photo %s for %s" photo-id hostname)
                                        (if-let [updated (albums-api/update-photo!
                                                           (:database components) photo-id hostname
                                                           {:photo-title (:title body-params)})]
                                          (ok updated)
                                          (do
                                            (log/warnf "Photo `%s` does not exist for `%s`" photo-id hostname)
                                            (not-found {:reason "Missing"})))))}
                  }]

   ["/:photo-id/:filename" {:get {:summary    "Get a particular photo"
                                  :responses  (merge hu/openapi-401
                                                     {200 {:description "The photo"
                                                           :content     {"application/json"
                                                                         {:schema [:any]}}}})
                                  :parameters {:path {:photo-id :uuid
                                                      :filename string?}}
                                  :handler    serve-photo}}]

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
