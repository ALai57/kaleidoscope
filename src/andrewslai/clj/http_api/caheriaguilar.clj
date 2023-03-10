(ns andrewslai.clj.http-api.caheriaguilar
  (:require [andrewslai.clj.api.authorization :as auth]
            [andrewslai.clj.http-api.admin :refer [admin-routes]]
            [andrewslai.clj.http-api.articles :refer [articles-routes branches-routes compositions-routes]]
            [andrewslai.clj.http-api.cache-control :as cc]
            [andrewslai.clj.http-api.groups :refer [groups-routes]]
            [andrewslai.clj.http-api.photo :refer [photo-routes]]
            [andrewslai.clj.http-api.ping :refer [ping-routes]]
            [andrewslai.clj.http-api.portfolio :refer [portfolio-routes]]
            [andrewslai.clj.http-api.swagger :refer [swagger-ui-routes]]
            [andrewslai.clj.persistence.filesystem :as fs]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.stacktrace :as stacktrace]
            [compojure.api.sweet :refer [api context GET]]
            [ring.util.response :as ring.response]
            [ring.util.http-response :refer [not-found not-modified]]
            [taoensso.timbre :as log]))

(def public-access
  (constantly true))

(def CAHERIAGUILAR-ACCESS-CONTROL-LIST
  [{:pattern #"^/admin.*"        :handler (partial auth/require-role "caheriaguilar")}
   {:pattern #"^/articles.*"     :handler (partial auth/require-role "caheriaguilar")}
   {:pattern #"^/branches.*"     :handler (partial auth/require-role "caheriaguilar")}
   {:pattern #"^/compositions.*" :handler public-access}
   {:pattern #"^/$"              :handler public-access}
   {:pattern #"^/index.html$"    :handler public-access}
   {:pattern #"^/ping"           :handler public-access}

   {:pattern #"^/groups.*"       :handler (partial auth/require-role "caheriaguilar")}

   {:pattern #"^/media.*" :request-method :post :handler (partial auth/require-role "caheriaguilar")}
   {:pattern #"^/media.*" :request-method :get  :handler public-access}

   #_{:pattern #"^/.*" :handler (constantly false)}])

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
       :body    (fs/object-content (fs/get static-content-adapter "index.html"))})
    (GET "/index.html" []
      :components [static-content-adapter]
      {:status  200
       :headers {"Content-Type" "text/html"}
       :body    (fs/object-content (fs/get static-content-adapter "index.html"))})
    (GET "/silent-check-sso.html" []
      :components [static-content-adapter]
      {:status  200
       :headers {"Content-Type" "text/html"}
       :body    (fs/object-content (fs/get static-content-adapter "silent-check-sso.html"))})
    ))

(def default-handler
  (GET "*" {:keys [uri headers] :as request}
    :components [static-content-adapter]
    ;; Also create a link to editor from homepage
    ;; Also create a link to homepage from editor
    (let [request (cond-> request
                    headers (assoc :headers (cske/transform-keys csk/->kebab-case-keyword headers)))
          result  (fs/get static-content-adapter uri (if-let [version (get-in request [:headers :if-none-match])]
                                                       {:version version}
                                                       {}))]
      (cond
        (fs/folder? uri)            (-> {:status 200
                                         :body   result}
                                        (cc/cache-control uri))
        (fs/does-not-exist? result) (not-found)
        (fs/not-modified? result)   (not-modified)
        :else                       (-> {:status  200
                                         :headers {"ETag" (fs/object-version result)}
                                         :body    (fs/object-content result)}
                                        (cc/cache-control uri))))))

(defn caheriaguilar-app
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
       photo-routes
       groups-routes
       default-handler))


(comment
  ((caheriaguilar-app {:auth           identity
                       :static-content nil})
   {:request-method :get
    :uri    "hi"}))
