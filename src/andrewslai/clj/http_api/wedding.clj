(ns andrewslai.clj.http-api.wedding
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core :as amazon]
            [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.entities.album :as album]
            [andrewslai.clj.http-api.middleware :as mw]
            [andrewslai.clj.http-api.ping :refer [ping-routes]]
            [andrewslai.clj.http-api.static-content :as sc]
            [andrewslai.clj.persistence.filesystem :as fs]
            [buddy.auth.accessrules :as ar :refer [wrap-access-rules]]
            [buddy.auth.middleware :as ba]
            [clojure.stacktrace :as stacktrace]
            [compojure.api.sweet :refer [api context defroutes GET POST PUT]]
            [compojure.route :as route]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.http-response :refer [created unauthorized ok]]
            [ring.util.response :as ring-resp]
            [taoensso.timbre :as log]))

(defn exception-handler
  [e data request]
  (log/errorf "Error: %s, %s"
              (ex-message e)
              (stacktrace/print-stack-trace e)))

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
  [{:pattern #"^/media.*"
    :handler (partial require-role "wedding")}
   {:pattern #"^/albums.*"
    :handler (partial require-role "wedding")}])

(defn ->file-input-stream
  [file]
  (java.io.FileInputStream. ^java.io.File file))

;; Useful for local development so you don't have to set up a connection to S3
(defroutes index
  (GET "/index.html" []
    (log/info "Fetching `wedding-index.html` locally")
    (-> (ring-resp/resource-response "wedding-index.html" {:root "public"})
        (ring-resp/content-type "text/html"))))

(defroutes album-routes
  (context "/albums" []
    :components [database]

    (GET "/" []
      :swagger {:summary     "Retrieve all albums"
                :description (str "This endpoint retrieves all albums. "
                                  "The endpoint is currently not paginated")
                :produces    #{"application/json"}
                :responses   {200 {:description "A collection of all albums"
                                   :schema      :andrewslai.albums/albums}}}
      (log/info "Getting albums")
      (ok (album/get-all-albums database)))

    (POST "/" {params :params}
      :swagger {:summary     "Add an album"
                :description "This endpoint inserts an album into the database"
                :consumes    #{"application/json"}
                :produces    #{"application/json"}
                :request     :andrewslai.album/album
                :responses   {200 {:description "Success!"
                                   :schema      :andrewslai.albums/album}}}
      (log/info "Creating album" params)
      (let [now (java.time.LocalDateTime/now)]
        (ok (album/create-album! database (assoc params
                                                 :created-at now
                                                 :modified-at now)))))

    (GET "/:id" [id]
      :swagger {:summary     "Retrieve an album"
                :description "This endpoint retrieves an album by ID"
                :produces    #{"application/json"}
                :responses   {200 {:description "An album"
                                   :schema      :andrewslai.albums/album}}}
      (log/info "Getting album" id)
      (ok (album/get-album-by-id database id)))

    (PUT "/:id" {params :params}
      :swagger {:summary     "Update an album"
                :description "This endpoint updates an album"
                :produces    #{"application/json"}
                :responses   {200 {:description "An album"
                                   :schema      :andrewslai.albums/album}}}
      (log/info "Updating album" params)
      (ok (album/update-album! database params)))
    ))

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


(defn wedding-app
  [{:keys [auth logging storage access-rules] :as components}]
  (log/with-config logging
    (api {:components (select-keys components [:storage :logging :database])
          :exceptions {:handlers {:compojure.api.exception/default exception-handler}}
          :middleware [mw/wrap-request-identifier
                       mw/wrap-redirect-to-index
                       wrap-content-type
                       wrap-json-response
                       wrap-multipart-params
                       wrap-params
                       mw/log-request!

                       ;; TODO: Don't use this - pass in a wrapper
                       (sc/static-content storage)
                       #(ba/wrap-authorization % auth)
                       #(ba/wrap-authentication % auth)
                       #(wrap-access-rules % {:rules access-rules
                                              :reject-handler (fn [& args]
                                                                (unauthorized))})
                       #_(partial debug-log-request! "Finished middleware processing")
                       ]}
         ping-routes
         album-routes
         ;; Useful for local debugging until I set up something better
         ;;index
         upload-routes
         (route/not-found "No matching route"))))


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
