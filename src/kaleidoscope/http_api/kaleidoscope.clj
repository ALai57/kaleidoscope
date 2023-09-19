(ns kaleidoscope.http-api.kaleidoscope
  (:require
   [clojure.stacktrace :as stacktrace]
   [clojure.string :as str]
   [compojure.api.sweet :refer [api context GET]]
   [kaleidoscope.api.authorization :as auth]
   [kaleidoscope.http-api.middleware :as mw]
   [kaleidoscope.http-api.admin :refer [admin-routes]]
   [kaleidoscope.http-api.album :refer [album-routes]]
   [kaleidoscope.http-api.articles :refer [reitit-articles-routes reitit-branches-routes reitit-compositions-routes]]
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
       audiences-routes
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

(defn get-static-resource
  [{:keys [components] :as request}]
  (http-utils/get-resource (:static-content-adapters components) request))

(def reitit-index-routes
  "All served from a common bucket: the Kaleidoscope app bucket."
  ["" {:no-doc true}
   ["/index.html" {:get {:handler (partial found "/")}}]
   ["/"           {:get {:span-name "kaleidoscope.index.get"
                         :uri       "index.html"
                         :handler   get-static-resource}}]

   ["/silent-check-sso.html"      {:get {:span-name "kaleidoscope.silent-check-sso.get"
                                         :host      "kaleidoscope.pub"
                                         :handler   get-static-resource}}]

   ["/js/compiled/kaleidoscope/*" {:get {:span-name (fn [{:keys [uri] :as _request}] (format "kaleidoscope.%s.get" (str/replace uri #"/" ".")))
                                         :host      "kaleidoscope.pub"
                                         :handler   get-static-resource}}]])

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
      reitit-articles-routes
      reitit-branches-routes
      reitit-compositions-routes]
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

;; Emacs xwidgets
(comment
  (require '[portal.api :as p])
  (def p-e
    (p/open {:launcher     :emacs
             :window-title "Kaleidoscope Portal"}))
  (add-tap #'p/submit)
  (tap> "Stuff")
  )
