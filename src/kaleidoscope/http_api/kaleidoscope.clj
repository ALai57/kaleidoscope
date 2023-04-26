(ns kaleidoscope.http-api.kaleidoscope
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [clojure.stacktrace :as stacktrace]
   [clojure.string :as str]
   [compojure.api.sweet :refer [api context GET]]
   [kaleidoscope.api.authorization :as auth]
   [kaleidoscope.http-api.admin :refer [admin-routes]]
   [kaleidoscope.http-api.album :refer [album-routes]]
   [kaleidoscope.http-api.articles :refer [articles-routes branches-routes compositions-routes]]
   [kaleidoscope.http-api.cache-control :as cc]
   [kaleidoscope.http-api.groups :refer [groups-routes]]
   [kaleidoscope.http-api.photo :refer [photo-routes]]
   [kaleidoscope.http-api.ping :refer [ping-routes]]
   [kaleidoscope.http-api.portfolio :refer [portfolio-routes]]
   [kaleidoscope.http-api.swagger :refer [swagger-ui-routes]]
   [kaleidoscope.persistence.filesystem :as fs]
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
  "Getting host name is from ring.util.request"
  [request]
  (let [server-name (get-in request [:headers "host"])]
    (when (nil? server-name)
      (log/warnf "Request without a host. Cannot lookup associated bucket."))
    (str/join "." (butlast (str/split server-name #"\.")))))

;; Emacs xwidgets
(comment
  (require '[portal.api :as p])
  (def p-e
    (p/open {:launcher     :emacs
             :window-title "Kaleidoscope Portal"}))
  (add-tap #'p/submit)
  (tap> "Stuff")
  )

(defn get-resource
  [static-content-adapters {:keys [uri headers] :as request}]
  (let [bucket  (bucket-name request)
        adapter (get static-content-adapters bucket)
        result  (when adapter
                  (fs/get adapter uri (if-let [version (get-in request [:headers "if-none-match"])]
                                        {:version version}
                                        {})))]
    (cond
      (nil? adapter)              (do (log/warnf "Invalid request to bucket associated with host %s" (get-in request [:headers "host"]))
                                      {:status 404})
      (fs/folder? uri)            (-> {:status 200
                                       :body   result}
                                      (cc/cache-control uri))
      (fs/does-not-exist? result) (not-found)
      (fs/not-modified? result)   (not-modified)
      :else                       (-> {:status  200
                                       :headers {"ETag" (fs/object-version result)}
                                       :body    (fs/object-content result)}
                                      (cc/cache-control uri)))))

(defn kebab-case-headers
  [{:keys [headers] :as request}]
  (cond-> request
    headers (assoc :headers (cske/transform-keys csk/->kebab-case headers))))

(defn set-host
  [request host]
  (assoc-in request [:headers "host"] host))

(defn text-html
  [response]
  (assoc-in response [:headers "Content-Type"] "text/html"))

(def index-routes
  "All served from a common bucket: the Kaleidoscope app bucket."
  (context "/" []
    (GET "/" request
      :components [static-content-adapters]
      (span/with-span! {:name "kaleidoscope.index.get"}
        (text-html (get-resource static-content-adapters (assoc request :uri "/index.html")))))
    (GET "/index.html" request
      :components [static-content-adapters]
      (span/with-span! {:name "kaleidoscope.index.get"}
        (text-html (get-resource static-content-adapters request))))
    (GET "/silent-check-sso.html" request
      :components [static-content-adapters]
      (span/with-span! {:name "kaleidoscope.silent-check-sso.get"}
        (text-html (get-resource static-content-adapters (-> request
                                                             kebab-case-headers
                                                             (set-host "kaleidoscope.pub"))))))

    ;; Get frontend app from the Kaleidoscope bucket, so all sites don't need to
    ;; have separate copies of the app (and all updates immediately apply to all
    ;; sites).
    (GET "/js/compiled/andrewslai/*" request
      :components [static-content-adapters]
      (let [uri (:uri request)]
        (span/with-span! {:name (format "kaleidoscope.%s.get" (str/replace uri #"/" "."))}
          (get-resource static-content-adapters (-> request
                                                    kebab-case-headers
                                                    (set-host "kaleidoscope.pub"))))))))

(def default-handler
  (GET "*" {:keys [uri headers] :as request}
    :components [static-content-adapters]
    (span/with-span! {:name (format "kaleidoscope.default.handler.get")}
      (get-resource static-content-adapters (-> request
                                                kebab-case-headers)))))

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
