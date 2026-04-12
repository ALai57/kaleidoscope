(ns kaleidoscope.http-api.kaleidoscope
  (:require
   [clojure.stacktrace :as stacktrace]
   [clojure.string :as str]
   [kaleidoscope.api.authorization :as auth]
   [kaleidoscope.clients.stripe :as stripe]
   [kaleidoscope.http-api.admin :refer [reitit-admin-routes]]
   [kaleidoscope.http-api.album :refer [reitit-albums-routes]]
   [kaleidoscope.http-api.articles :refer [reitit-articles-routes reitit-branches-routes reitit-compositions-routes]]
   [kaleidoscope.http-api.audiences :refer [reitit-audiences-routes]]
   [kaleidoscope.http-api.groups :refer [reitit-groups-routes]]
   [kaleidoscope.http-api.projects :refer [reitit-projects-routes]]
   [kaleidoscope.http-api.score-definitions :refer [reitit-score-definition-routes]]
   [kaleidoscope.http-api.http-utils :as http-utils]
   [kaleidoscope.http-api.middleware :as mw]
   [kaleidoscope.http-api.photo :refer [reitit-photos-routes]]
   [kaleidoscope.http-api.ping :refer [reitit-ping-routes]]
   [kaleidoscope.http-api.portfolio :refer [reitit-portfolio-routes]]
   [kaleidoscope.http-api.registration :refer [reitit-registration-routes]]
   [kaleidoscope.http-api.swagger :refer [reitit-openapi-routes]]
   [kaleidoscope.http-api.themes :refer [reitit-themes-routes]]
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

   {:pattern #"^/groups.*"            :handler auth/require-*-writer}
   {:pattern #"^/projects.*"          :handler auth/require-*-writer}
   {:pattern #"^/score-definitions.*" :handler auth/require-*-writer}

   {:pattern #"^/media.*" :request-method :post :handler auth/require-*-writer}
   {:pattern #"^/media.*" :request-method :get  :handler auth/public-access}

   {:pattern #"^/v2/photos.*"  :request-method :post :handler auth/require-*-writer}
   {:pattern #"^/v2/photos.*"  :request-method :put  :handler auth/require-*-admin}
   {:pattern #"^/v2/photos"    :request-method :get  :handler auth/require-*-writer}
   {:pattern #"^/v2/photos/.*" :request-method :get  :handler auth/public-access}

   {:pattern #"^/themes.*"    :request-method :put  :handler auth/require-*-writer}
   {:pattern #"^/themes.*"    :request-method :post :handler auth/require-*-writer}
   {:pattern #"^/themes.*"    :request-method :get  :handler auth/public-access}


   {:pattern #"^/albums.*"            :handler auth/require-*-admin}
   {:pattern #"^/article-audiences.*" :handler auth/require-*-admin}

   #_{:pattern #"^/.*" :handler (constantly false)}])

;; Add a tracing middleware data

(defn get-static-resource
  [{:keys [components] :as request}]
  (http-utils/get-resource (:static-content-adapters components) request))

(def reitit-index-routes
  "All served from a common bucket: the Kaleidoscope app bucket."
  ["" {:no-doc true}
   ["/index.html" {:get {:handler (partial found "/")}}]
   ["/favicon.ico" {:get {:span-name "kaleidoscope.favicon.get"
                          :uri       "static/favicon.ico"
                          :handler   get-static-resource}}]
   ["/"           {:get {:span-name "kaleidoscope.index.get"
                         :uri       "index.html"
                         :host      "kaleidoscope.client"
                         :handler   get-static-resource}}]

   ["/assets/*"
    {:get         {:span-name (fn [{:keys [uri] :as _request}] (format "kaleidoscope.%s.get" (str/replace uri #"/" ".")))
                   ;; Load all compiled JS assets from the kaleidoscope-client bucket
                   :host      "kaleidoscope.client"
                   :handler   get-static-resource}}]

   ["/static/*" {:conflicting true
                 :get         {:span-name (fn [{:keys [uri] :as _request}] (format "kaleidoscope.%s.get" (str/replace uri #"/" ".")))
                               ;; Probably shouldn't load all static resources from kaleidoscope
                               ;;:host      "kaleidoscope.pub"
                               :handler   get-static-resource}}]
   ["/media/*" {:get {:span-name (fn [{:keys [uri] :as _request}] (format "kaleidoscope.%s.get" (str/replace uri #"/" ".")))
                      :handler   get-static-resource}}]])

(def PaymentIntent
  [:map
   [:client-secret :string]])

(def reitit-stripe-routes
  "Stripe requires the frontend to receive a payment intent secret that the backend generates

  https://docs.stripe.com/payments/accept-a-payment?ui=elements&architecture-style=resources&shell=true&api=true&resource=payment_intents&action=create"
  ["" {:no-doc true}
   ["/v1/payments"
    {:post {:span-name "kaleidoscope.payments.create"

            :responses {200 {:description "Payment secret"
                             :content     {"application/json"
                                           {:schema [:map
                                                     [:client-secret :string]]}}}}
            :request   {:description "Version"
                        :content     {"application/json"
                                      {:schema   [:map
                                                  [:amount :int]
                                                  [:currency :string]]
                                       :examples {"example-price" {:summary "Example price"
                                                                   :value   {:amount   12
                                                                             :currency "USD"}}}}}}
            :handler   (fn [{:keys [components body-params] :as request}]
                         {:status 200
                          :body   (stripe/payment-intent body-params)})}}]])

(defn inject-components
  [components]
  (fn wrap [handler]
    (fn new-handler [request]
      (handler (assoc request :components components)))))

(defn kaleidoscope-app
  ([]
   (kaleidoscope-app {}))
  ([components]
   (let [reitit-config (update-in mw/reitit-configuration
                                  [:data :middleware]
                                  (fn [mw]
                                    (concat mw [(inject-components components)
                                                (:session-tracking components)
                                                (:auth-stack components)])))]
     (ring/ring-handler
      (ring/router
       [
        ;; Administrative/helpers
        reitit-ping-routes
        reitit-openapi-routes
        reitit-index-routes
        reitit-admin-routes

        ;; API routes
        reitit-albums-routes
        reitit-articles-routes
        reitit-audiences-routes
        reitit-branches-routes
        reitit-compositions-routes
        reitit-groups-routes
        reitit-photos-routes
        reitit-projects-routes
        reitit-score-definition-routes
        reitit-portfolio-routes
        reitit-themes-routes
        reitit-stripe-routes
        reitit-registration-routes
        ]
       reitit-config)
      (ring/create-default-handler
       {:not-found (fn [request]
                     (tap> {:req        request
                            :components components})
                     ;; May not have the middleware components!
                     ;; Redirect to index.html for client-side routing.
                     ;; Must replicate wrap-force-host/wrap-force-uri manually since this
                     ;; handler bypasses the reitit middleware stack.
                     (span/with-span! {:name (format "kaleidoscope.default.handler.get")}
                       (get-static-resource (-> request
                                                (mw/set-host "kaleidoscope.client")
                                                (assoc :uri "index.html")
                                                ;; Components must be added here because this isn't
                                                ;; wrapped with middleware the same way other routes are
                                                (assoc :components components)))))}
       )))))

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
