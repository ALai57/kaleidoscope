(ns andrewslai.clj.http-api.wedding
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core :as amazon]
            [andrewslai.clj.http-api.album :as album-routes]
            [andrewslai.clj.http-api.cache-control :as cc]
            [andrewslai.clj.http-api.ping :refer [ping-routes]]
            [andrewslai.clj.http-api.swagger :as swagger]
            [andrewslai.clj.http-api.photo :as photo-routes]
            [andrewslai.clj.persistence.filesystem :as fs]
            [clojure.stacktrace :as stacktrace]
            [compojure.api.sweet :refer [api context GET ANY]]
            [ring.util.response :as ring-resp]
            [taoensso.timbre :as log]))

(defn exception-handler
  [e data request]
  (log/errorf "Error: %s, %s"
              (ex-message e)
              (stacktrace/print-stack-trace e)))

;; Useful for local development so you don't have to set up a connection to S3
(def index-routes
  (context "/" []
    (GET "/" []
      :components [static-content-adapter]
      {:status  200
       :headers {"Content-Type" "text/html"}
       :body    (fs/get static-content-adapter "index.html")})
    (GET "/index.html" []
      :components [static-content-adapter]
      {:status  200
       :headers {"Content-Type" "text/html"}
       :body    (fs/get static-content-adapter "index.html")})))

(def default-handler
  (GET "*" {:keys [uri] :as request}
    :components [static-content-adapter]
    (if-let [response (fs/get static-content-adapter uri)]
      (cc/cache-control uri (ring-resp/response response))
      (ring-resp/not-found "No matching route"))))

(defn wedding-app
  [{:keys [http-mw] :as components}]
  (api {:components components
        :exceptions {:handlers {:compojure.api.exception/default exception-handler}}
        :middleware [http-mw]}
       ping-routes
       index-routes
       swagger/swagger-wedding-routes
       album-routes/album-routes
       photo-routes/photo-routes
       default-handler))


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
