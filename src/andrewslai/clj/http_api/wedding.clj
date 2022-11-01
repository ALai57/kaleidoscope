(ns andrewslai.clj.http-api.wedding
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core :as amazon]
            [andrewslai.clj.http-api.album :as album-routes]
            [andrewslai.clj.http-api.photo :as photo-routes]
            [andrewslai.clj.http-api.ping :refer [ping-routes]]
            [andrewslai.clj.http-api.swagger :as swagger]
            [clojure.stacktrace :as stacktrace]
            [compojure.api.sweet :refer [api GET]]
            [compojure.route :as route]
            [ring.util.response :as ring-resp]
            [taoensso.timbre :as log]))

(defn exception-handler
  [e data request]
  (log/errorf "Error: %s, %s"
              (ex-message e)
              (stacktrace/print-stack-trace e)))

;; Useful for local development so you don't have to set up a connection to S3
(def index
  (GET "/index.html" []
    (log/info "Fetching `wedding-index.html` locally")
    (-> (ring-resp/resource-response "wedding-index.html" {:root "public"})
        (ring-resp/content-type "text/html"))))

(defn wedding-app
  [{:keys [http-mw] :as components}]
  (api {:components (select-keys components [:storage :logging :database])
        :exceptions {:handlers {:compojure.api.exception/default exception-handler}}
        :middleware [http-mw]}
       ping-routes
       swagger/swagger-wedding-routes
       album-routes/album-routes
       ;; Useful for local debugging until I set up something better
       ;;index
       photo-routes/photo-routes
       (route/not-found "No matching route")))


(comment
  (require '[ring.mock.request :as mock])
  ((wedding-app {}) (mock/request :get "/swagger.json"))

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
