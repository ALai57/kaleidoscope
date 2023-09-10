(ns kaleidoscope.http-api.kaleidoscope
  (:require
   [clojure.stacktrace :as stacktrace]
   [clojure.string :as str]
   [compojure.api.sweet :refer [api context GET]]
   [kaleidoscope.api.authorization :as auth]
   [kaleidoscope.http-api.middleware :as mw]
   [kaleidoscope.http-api.admin :refer [admin-routes]]
   [kaleidoscope.http-api.album :refer [album-routes]]
   [kaleidoscope.http-api.articles :refer [articles-routes branches-routes compositions-routes
                                           reitit-articles-routes]]
   [kaleidoscope.http-api.audiences :refer [audiences-routes]]
   [kaleidoscope.http-api.groups :refer [groups-routes]]
   [kaleidoscope.http-api.photo :refer [photo-routes]]
   [kaleidoscope.http-api.ping :refer [reitit-ping-routes]]
   [kaleidoscope.http-api.portfolio :refer [portfolio-routes]]
   [kaleidoscope.http-api.swagger :refer [swagger-ui-routes reitit-openapi-routes]]
   [kaleidoscope.http-api.http-utils :as http-utils]
   [kaleidoscope.trace :as-alias trace]
   [reitit.ring :as ring]
   [ring.util.http-response :refer [found]]
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

   {:pattern #"^/albums.*"            :handler auth/require-*-admin}
   {:pattern #"^/article-audiences.*" :handler auth/require-*-admin}

   #_{:pattern #"^/.*" :handler (constantly false)}])

(defn exception-handler
  [exception-reporter]
  (fn [e data request]
    (log/errorf "Error: %s, %s"
                (ex-message e)
                (stacktrace/print-stack-trace e))
    (when exception-reporter
      (exception-reporter e))))

;; Emacs xwidgets
(comment
  (require '[portal.api :as p])
  (def p-e
    (p/open {:launcher     :emacs
             :window-title "Kaleidoscope Portal"}))
  (add-tap #'p/submit)
  (tap> "Stuff")
  )

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
        (text-html (http-utils/get-resource static-content-adapters (assoc request :uri "/index.html")))))
    (GET "/index.html" request
      :components [static-content-adapters]
      (span/with-span! {:name "kaleidoscope.index.get"}
        (text-html (http-utils/get-resource static-content-adapters request))))
    (GET "/silent-check-sso.html" request
      :components [static-content-adapters]
      (span/with-span! {:name "kaleidoscope.silent-check-sso.get"}
        (text-html (http-utils/get-resource static-content-adapters (-> request
                                                                        http-utils/kebab-case-headers
                                                                        (set-host "kaleidoscope.pub"))))))

    ;; Get frontend app from the Kaleidoscope bucket, so all sites don't need to
    ;; have separate copies of the app (and all updates immediately apply to all
    ;; sites).
    (GET "/js/compiled/kaleidoscope/*" request
      :components [static-content-adapters]
      (let [uri (:uri request)]
        (span/with-span! {:name (format "kaleidoscope.%s.get" (str/replace uri #"/" "."))}
          (http-utils/get-resource static-content-adapters (-> request
                                                               http-utils/kebab-case-headers
                                                               (set-host "kaleidoscope.pub"))))))))

(def default-handler
  (GET "*" {:keys [uri headers] :as request}
    :components [static-content-adapters]
    (span/with-span! {:name (format "kaleidoscope.default.handler.get")}
      (http-utils/get-resource static-content-adapters (-> request
                                                           http-utils/kebab-case-headers)))))

(defn kaleidoscope-compojure-app
  [{:keys [http-mw exception-reporter] :as components}]
  (api {:components components
        :exceptions {:handlers {:compojure.api.exception/default (exception-handler exception-reporter)}}
        :middleware [http-mw]}
       index-routes
       articles-routes
       audiences-routes
       branches-routes
       compositions-routes
       portfolio-routes
       admin-routes
       swagger-ui-routes
       album-routes
       photo-routes
       groups-routes
       default-handler))

;;
;; Reitit versions of routes
;;

;; Add a tracing middleware data

(defn get-resource
  [{:keys [components] :as request}]
  (http-utils/get-resource (:static-content-adapters components) request))

(def reitit-index-routes
  "All served from a common bucket: the Kaleidoscope app bucket."
  ["" {:no-doc true}
   ["/index.html" {:get {:handler (partial found "/v2/")}}]
   ["/"           {:get {:span-name "kaleidoscope.index.get"
                         :uri       "index.html"
                         :handler   get-resource}}]

   ["/silent-check-sso.html"      {:get {:span-name "kaleidoscope.silent-check-sso.get"
                                         :host      "kaleidoscope.pub"
                                         :handler   get-resource}}]

   ["/js/compiled/kaleidoscope/*" {:get {:span-name (fn [{:keys [uri] :as _request}] (format "kaleidoscope.%s.get" (str/replace uri #"/" ".")))
                                         :handler   get-resource}}]])

(defn inject-components
  [components]
  (fn wrap [handler]
    (fn new-handler [request]
      (handler (assoc request :components components)))))

(defn kaleidoscope-reitit-app
  ([]
   (kaleidoscope-reitit-app {}))
  ([components]
   (ring/ring-handler
    (ring/router
     [reitit-ping-routes
      reitit-openapi-routes
      reitit-index-routes
      reitit-articles-routes]
     (update-in mw/reitit-configuration
                [:data :middleware]
                (partial concat [(inject-components components)
                                 (:http-mw components)]))))))

;;
;; Temporary dispatch to reitit or Compojure as I port app to use reitit
;;
(defn kaleidoscope-app
  [components]
  (fn [request]
    (if-let [response ((kaleidoscope-reitit-app components) request)]
      response
      ((kaleidoscope-compojure-app components) request))))

(comment

  ((kaleidoscope-app {:auth           identity
                      :static-content nil})
   {:request-method :get
    :uri    "hi"}))
