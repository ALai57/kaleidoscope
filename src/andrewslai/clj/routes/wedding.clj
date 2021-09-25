(ns andrewslai.clj.routes.wedding
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core :as amazon]
            [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.persistence.filesystem :as fs]
            [andrewslai.clj.routes.middleware :as mw]
            [andrewslai.clj.routes.ping :refer [ping-routes]]
            [andrewslai.clj.static-content :as sc]
            [buddy.auth.accessrules :as ar :refer [wrap-access-rules]]
            [buddy.auth.middleware :as ba]
            [compojure.api.sweet :refer [api context defroutes GET POST]]
            [compojure.route :as route]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.http-response :refer [created unauthorized]]
            [ring.util.response :as ring-resp]
            [taoensso.timbre :as log]))

(defn exception-handler
  [e data request]
  (log/errorf "Error: %s, %s"
              (ex-message e)
              (clojure.stacktrace/print-stack-trace e)))

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

;; Useful for local development so you don't have to set up a connection to S3
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


(defn wedding-app
  [{:keys [auth logging storage access-rules] :as components}]
  (log/with-config logging
    (api {:components (select-keys components [:storage :logging])
          :exceptions {:handlers {:compojure.api.exception/default exception-handler}}
          :middleware [mw/wrap-request-identifier
                       mw/wrap-redirect-to-index
                       wrap-content-type
                       wrap-json-response
                       wrap-multipart-params
                       wrap-params
                       mw/log-request!

                       (sc/static-content storage)
                       #(ba/wrap-authorization % auth)
                       #(ba/wrap-authentication % auth)
                       #(wrap-access-rules % {:rules access-rules
                                              :reject-handler (fn [& args]
                                                                (unauthorized))})
                       #_(partial debug-log-request! "Finished middleware processing")
                       ]}
         ping-routes
         ;; Useful for local debugging until I set up something better
         ;;wedding/index
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
