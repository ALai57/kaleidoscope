(ns andrewslai.clj.http-api.wedding
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core :as amazon]
            [andrewslai.clj.api.authorization :as auth]
            [andrewslai.clj.http-api.album :as album-routes]
            [andrewslai.clj.http-api.cache-control :as cc]
            [andrewslai.clj.http-api.photo :as photo-routes]
            [andrewslai.clj.http-api.ping :refer [ping-routes]]
            [andrewslai.clj.http-api.swagger :as swagger]
            [andrewslai.clj.persistence.filesystem :as fs]
            [clojure.stacktrace :as stacktrace]
            [compojure.api.sweet :refer [api context GET]]
            [ring.util.response :as ring-resp]
            [ring.util.http-response :refer [not-found not-modified]]
            [taoensso.timbre :as log]))

(def WEDDING-ACCESS-CONTROL-LIST
  [{:pattern #"^/media.*"  :handler (partial auth/require-role "wedding")}
   {:pattern #"^/albums.*" :handler (partial auth/require-role "wedding")}])

(defn exception-handler
  [e data request]
  (log/errorf "Error: %s, %s"
              (ex-message e)
              (stacktrace/print-stack-trace e)))

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
    (log/infof "Didn't find route match. Attempting to lookup using static content")
    (let [result (fs/get static-content-adapter uri)]
      (cond
        (fs/folder? uri)            (-> {:status 200
                                         :body   result}
                                        (cc/cache-control uri))
        (fs/does-not-exist? result) (ring-resp/not-found "No matching route")
        (fs/not-modified? result)   (not-modified)
        :else                       (-> {:status  200
                                         :headers {"ETag" (fs/object-version result)}
                                         :body    (fs/object-content result)}
                                        (cc/cache-control uri))))))

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
