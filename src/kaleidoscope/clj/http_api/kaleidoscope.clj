(ns kaleidoscope.clj.http-api.kaleidoscope
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [clojure.stacktrace :as stacktrace]
   [clojure.string :as str]
   [compojure.api.sweet :refer [api context GET]]
   [kaleidoscope.clj.api.authorization :as auth]
   [kaleidoscope.clj.http-api.admin :refer [admin-routes]]
   [kaleidoscope.clj.http-api.album :refer [album-routes]]
   [kaleidoscope.clj.http-api.articles :refer [articles-routes branches-routes compositions-routes]]
   [kaleidoscope.clj.http-api.cache-control :as cc]
   [kaleidoscope.clj.http-api.groups :refer [groups-routes]]
   [kaleidoscope.clj.http-api.photo :refer [photo-routes]]
   [kaleidoscope.clj.http-api.ping :refer [ping-routes]]
   [kaleidoscope.clj.http-api.portfolio :refer [portfolio-routes]]
   [kaleidoscope.clj.http-api.swagger :refer [swagger-ui-routes]]
   [kaleidoscope.clj.persistence.filesystem :as fs]
   [ring.util.http-response :refer [not-found not-modified]]
   [steffan-westcott.clj-otel.api.trace.span :as span]
   [taoensso.timbre :as log]))

(def KALEIDOSCOPE-ACCESS-CONTROL-LIST
  [{:pattern #"^/admin.*"        :handler auth/require-*-admin}
   {:pattern #"^/articles.*"     :handler auth/require-*-writer}
   {:pattern #"^/branches.*"     :handler auth/require-*-writer}
   {:pattern #"^/compositions.*" :handler auth/public-access}
   {:pattern #"^/$"              :handler auth/public-access}
   {:pattern #"^/index.html$"    :handler auth/public-access}
   {:pattern #"^/ping"           :handler auth/public-access}

   {:pattern #"^/groups.*"       :handler auth/require-*-writer}

   {:pattern #"^/media.*" :request-method :post :handler auth/require-*-writer}
   {:pattern #"^/media.*" :request-method :get  :handler auth/public-access}

   {:pattern #"^/albums.*"       :handler auth/require-*-admin}

   #_{:pattern #"^/.*" :handler (constantly false)}])

(defn exception-handler
  [e data request]
  (log/errorf "Error: %s, %s"
              (ex-message e)
              (stacktrace/print-stack-trace e)))

(defn bucket-name
  [{:keys [server-name] :as request}]
  (first (str/split server-name #"\.")))

(def index-routes
  (context "/" []
    (GET "/" request
      :components [static-content-adapters]
      (span/with-span! {:name (format "kaleidoscope.index.get")}
        {:status  200
         :headers {"Content-Type" "text/html"}
         :body    (-> static-content-adapters
                      (get (bucket-name request))
                      (fs/get "index.html")
                      (fs/object-content))}))
    (GET "/index.html" request
      :components [static-content-adapters]
      (span/with-span! {:name (format "kaleidoscope.index.get")}
        {:status  200
         :headers {"Content-Type" "text/html"}
         :body    (-> static-content-adapters
                      (get (bucket-name request))
                      (fs/get "index.html")
                      (fs/object-content))}))
    (GET "/silent-check-sso.html" request
      :components [static-content-adapters]
      (span/with-span! {:name (format "kaleidoscope.silent-check-sso.get")}
        {:status  200
         :headers {"Content-Type" "text/html"}
         :body    (-> static-content-adapters
                      (get (bucket-name request))
                      (fs/get "silent-check-sso.html")
                      (fs/object-content))}))
    ))

(def default-handler
  (GET "*" {:keys [uri headers] :as request}
    :components [static-content-adapters]
    (span/with-span! {:name (format "kaleidoscope.default.handler.get")}
      (let [request (cond-> request
                      headers (assoc :headers (cske/transform-keys csk/->kebab-case-keyword headers)))
            result  (-> static-content-adapters
                        (get (bucket-name request))
                        (fs/get uri (if-let [version (get-in request [:headers :if-none-match])]
                                      {:version version}
                                      {})))]
        (cond
          (fs/folder? uri)            (-> {:status 200
                                           :body   result}
                                          (cc/cache-control uri))
          (fs/does-not-exist? result) (not-found)
          (fs/not-modified? result)   (not-modified)
          :else                       (-> {:status  200
                                           :headers {"ETag" (fs/object-version result)}
                                           :body    (fs/object-content result)}
                                          (cc/cache-control uri)))))))

(defn kaleidoscope-app
  [{:keys [http-mw] :as components}]
  (api {:components components
        :exceptions {:handlers {:compojure.api.exception/default exception-handler}}
        :middleware [http-mw]}
       ping-routes
       index-routes
       articles-routes
       branches-routes
       compositions-routes
       portfolio-routes
       admin-routes
       swagger-ui-routes
       album-routes
       photo-routes
       groups-routes
       default-handler))


(comment
  ((kaleidoscope-app {:auth           identity
                      :static-content nil})
   {:request-method :get
    :uri    "hi"}))
