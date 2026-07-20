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
   [kaleidoscope.http-api.interests :refer [reitit-interests-routes]]
   [kaleidoscope.http-api.projects :refer [reitit-projects-routes]]
   [kaleidoscope.http-api.score-definitions :refer [reitit-score-definition-routes]]
   [kaleidoscope.http-api.agents :refer [reitit-agent-routes]]
   [kaleidoscope.http-api.tasks :refer [reitit-task-routes]]
   [kaleidoscope.http-api.workspace-roots :refer [reitit-workspace-roots-routes]]
   [kaleidoscope.http-api.workflows :refer [reitit-workflow-routes reitit-project-workflow-routes]]
   [kaleidoscope.http-api.http-utils :as http-utils]
   [kaleidoscope.http-api.middleware :as mw]
   [kaleidoscope.http-api.photo :refer [reitit-photos-routes]]
   [kaleidoscope.http-api.ping :refer [reitit-ping-routes]]
   [kaleidoscope.http-api.portfolio :refer [reitit-portfolio-routes]]
   [kaleidoscope.http-api.recipes :refer [reitit-recipes-routes reitit-recipe-labels-routes reitit-recipe-label-groups-routes reitit-recipe-audiences-routes]]
   [kaleidoscope.http-api.registration :refer [reitit-registration-routes]]
   [kaleidoscope.http-api.swagger :refer [reitit-openapi-routes]]
   [kaleidoscope.http-api.tenant :as tenant]
   [kaleidoscope.http-api.themes :refer [reitit-themes-routes]]
   [kaleidoscope.trace :as-alias trace]
   [reitit.ring :as ring]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.util.http-response :refer [found not-found]]
   [steffan-westcott.clj-otel.api.trace.span :as span]
   [taoensso.timbre :as log]))

(def api-resource-access-rules
  "Authorization for the JSON API resource routes — the rules that get an
  /api/v1 twin. The twins are DERIVED from this one list (see
  KALEIDOSCOPE-ACCESS-CONTROL-LIST), so the two address spaces cannot drift.
  Order matters where patterns overlap under buddy's whole-string matching:
  `^/projects-portfolio` (public) must precede `^/projects.*` (writer)."
  [{:pattern #"^/articles.*"     :handler auth/require-*-writer}
   {:pattern #"^/branches.*"     :handler auth/require-*-writer}
   {:pattern #"^/compositions.*" :handler auth/public-access}
   {:pattern #"^/groups.*"            :handler auth/require-*-writer}
   {:pattern #"^/interests.*"         :handler auth/require-*-writer}
   {:pattern #"^/projects-portfolio"  :handler auth/public-access}
   {:pattern #"^/projects.*"          :handler auth/require-*-writer}
   {:pattern #"^/score-definitions.*" :handler auth/require-*-writer}
   {:pattern #"^/agents.*"            :handler auth/require-*-writer}
   {:pattern #"^/workflows.*"         :handler auth/require-*-writer}
   {:pattern #"^/workspace-roots.*"   :handler auth/require-*-writer}
   {:pattern #"^/themes.*"    :request-method :put    :handler auth/require-*-writer}
   {:pattern #"^/themes.*"    :request-method :post   :handler auth/require-*-writer}
   {:pattern #"^/themes.*"    :request-method :delete :handler auth/require-*-writer}
   {:pattern #"^/themes.*"    :request-method :get    :handler auth/public-access}
   {:pattern #"^/albums.*"            :handler auth/require-*-admin}
   {:pattern #"^/article-audiences.*" :handler auth/require-*-admin}
   ;; Recipes: GETs public so shared/public recipes render for anonymous
   ;; readers (list is access-filtered internally); writes require a writer.
   {:pattern #"^/recipes.*"             :request-method :get    :handler auth/public-access}
   {:pattern #"^/recipes.*"             :request-method :post   :handler auth/require-*-writer}
   {:pattern #"^/recipes.*"             :request-method :put    :handler auth/require-*-writer}
   {:pattern #"^/recipes.*"             :request-method :delete :handler auth/require-*-writer}
   {:pattern #"^/recipe-labels.*"       :request-method :get    :handler auth/public-access}
   {:pattern #"^/recipe-labels.*"       :handler auth/require-*-writer}
   {:pattern #"^/recipe-label-groups.*" :request-method :get    :handler auth/public-access}
   {:pattern #"^/recipe-label-groups.*" :handler auth/require-*-writer}
   {:pattern #"^/recipe-audiences.*"    :handler auth/require-*-writer}])

(defn with-api-v1-prefix
  "Rewrite a resource rule's :pattern from ^/foo… to ^/api/v1/foo…, preserving
  :request-method and :handler. Used to derive each root resource rule's
  /api/v1 twin so authorization can't diverge between the two address spaces."
  [rule]
  (update rule :pattern
          (fn [p] (re-pattern (str/replace-first (str p) #"^\^/" "^/api/v1/")))))

(def KALEIDOSCOPE-ACCESS-CONTROL-LIST
  (vec
   (concat
    ;; Root-only: infra, admin, media, and the already-namespaced photos.
    ;; None of these get an /api/v1 twin (admin/photos/media are not part of
    ;; the dual-mounted resource surface; infra is single-address).
    [{:pattern #"^/admin.*"        :handler auth/require-*-admin}
     {:pattern #"^/$"              :handler auth/public-access}
     {:pattern #"^/index.html$"    :handler auth/public-access}
     {:pattern #"^/ping"           :handler auth/public-access}

     {:pattern #"^/media.*" :request-method :post :handler auth/require-*-writer}
     {:pattern #"^/media.*" :request-method :get  :handler auth/public-access}

     {:pattern #"^/v2/photos.*"  :request-method :post :handler auth/require-*-writer}
     {:pattern #"^/v2/photos.*"  :request-method :put  :handler auth/require-*-admin}
     {:pattern #"^/v2/photos"    :request-method :get  :handler auth/require-*-writer}
     {:pattern #"^/v2/photos/.*" :request-method :get  :handler auth/public-access}]

    ;; API resource rules at their legacy root paths …
    api-resource-access-rules
    ;; … and their derived /api/v1 twins. Kept in the same order so overlapping
    ;; rules (projects-portfolio before projects.*) retain their precedence.
    (map with-api-v1-prefix api-resource-access-rules)

    ;; Everything below is intentionally public — listed explicitly so the
    ;; fail-closed catch-all can safely reject anything NOT named here.
    ;; A single missed/mistyped pattern is a full, invisible data exposure
    ;; with no error and no test failure to catch it (see 2026-07-03 PLAN.md,
    ;; "Critical finding #4").
    [{:pattern #"^/openapi\.json$" :handler auth/public-access}
     {:pattern #"^/api-docs.*"     :handler auth/public-access}
     {:pattern #"^/favicon\.ico$"  :handler auth/public-access}
     {:pattern #"^/assets.*"       :handler auth/public-access}
     {:pattern #"^/static.*"       :handler auth/public-access}
     ;; `/` and `/favicon.ico` carry route-level :uri that wrap-force-uri
     ;; rewrites before wrap-access-rules runs, so both forms need a pattern.
     {:pattern #"^index\.html$"         :handler auth/public-access}
     {:pattern #"^static/favicon\.ico$" :handler auth/public-access}
     {:pattern #"^/registration.*" :handler auth/public-access}
     {:pattern #"^/check-domain.*" :handler auth/public-access}
     {:pattern #"^/v1/payments.*"  :handler auth/public-access}

     ;; Fail closed: anything not explicitly named above is rejected. MUST
     ;; stay last.
     {:pattern #"^/.*" :handler (constantly false)}])))

;; Add a tracing middleware data

(defn get-static-resource
  [{:keys [components] :as request}]
  (http-utils/get-resource (:static-content-adapters components) request))

(def reserved-backend-prefixes
  "Path prefixes the backend owns. A request whose URI is one of these (or nested
  under it) is never a frontend page: an *unmatched* path under one must 404, not
  serve the SPA shell, so a bad asset/API URL fails honestly instead of returning
  HTML to a fetch caller (a soft 404)."
  ["/api/v1" "/api-docs" "/assets" "/static" "/media" "/v2/photos"
   "/openapi.json" "/favicon.ico" "/ping"])

(defn reserved-backend-path?
  [uri]
  (boolean (some (fn [p] (or (= uri p) (str/starts-with? uri (str p "/"))))
                 reserved-backend-prefixes)))

(defn spa-shell-request?
  "True when an unmatched request should fall through to the SPA shell for
  client-side routing: a GET/HEAD navigation for a path the backend does not
  reserve. Anything else — a non-GET, or an unmatched path under a reserved
  backend prefix — is a genuine miss and must 404."
  [{:keys [request-method uri]}]
  (and (contains? #{:get :head} request-method)
       (not (reserved-backend-path? uri))))

(def reitit-index-routes
  "All served from a common bucket: the Kaleidoscope app bucket."
  ["" {:no-doc true}
   ["/index.html" {:get {:handler (partial found "/")}}]
   ["/favicon.ico" {:get {:span-name "kaleidoscope.favicon.get"
                          :uri       "static/favicon.ico"
                          :store     "kaleidoscope.client"
                          :handler   get-static-resource}}]
   ["/"           {:get {:span-name "kaleidoscope.index.get"
                         :uri       "index.html"
                         :store     "kaleidoscope.client"
                         :handler   get-static-resource}}]

   ["/assets/*"
    {:get         {:span-name (fn [{:keys [uri] :as _request}] (format "kaleidoscope.%s.get" (str/replace uri #"/" ".")))
                   ;; Load all compiled JS assets from the kaleidoscope-client bucket
                   :store     "kaleidoscope.client"
                   :handler   get-static-resource}}]

   ["/static/*" {:conflicting true
                 :get         {:span-name (fn [{:keys [uri] :as _request}] (format "kaleidoscope.%s.get" (str/replace uri #"/" ".")))
                               :store     "kaleidoscope.client"
                               :handler   get-static-resource}}]
   ["/media/*" {:get {:span-name (fn [{:keys [uri] :as _request}] (format "kaleidoscope.%s.get" (str/replace uri #"/" ".")))
                      :handler   get-static-resource}}]])

(def PaymentIntent
  [:map
   [:client-secret :string]])

;; Stripe's own minimum charge amount is ~50 cents in USD; the upper bound is
;; an app-level guardrail, not a Stripe requirement — this route is
;; unauthenticated (see KALEIDOSCOPE-ACCESS-CONTROL-LIST above), so nothing
;; else stops a caller from requesting an arbitrarily large PaymentIntent.
;; Extend this set if the storefront ever needs to charge in another currency.
(def supported-payment-currencies
  #{"usd"})

(def PaymentRequest
  [:map
   [:amount [:int {:min 50 :max 100000}]]
   [:currency (into [:enum] supported-payment-currencies)]
   ;; Clients should generate and reuse this across retries of the *same*
   ;; logical payment so Stripe can dedupe them into one PaymentIntent
   ;; instead of creating a new object per retry.
   [:idempotency-key {:optional true} [:string {:min 1 :max 255}]]])

(def reitit-stripe-routes
  "Stripe requires the frontend to receive a payment intent secret that the backend generates

  https://docs.stripe.com/payments/accept-a-payment?ui=elements&architecture-style=resources&shell=true&api=true&resource=payment_intents&action=create"
  ["" {:no-doc true}
   ["/v1/payments"
    {:post {:span-name  "kaleidoscope.payments.create"
            ;; Public + unauthenticated (see KALEIDOSCOPE-ACCESS-CONTROL-LIST)
            ;; and fans out to a paid third-party API on every call — rate
            ;; limit it so it can't be used to run up Stripe API volume.
            :rate-limit {:max-requests 10 :window-ms 60000}

            :responses {200 {:description "Payment secret"
                             :content     {"application/json"
                                           {:schema [:map
                                                     [:client-secret :string]]}}}}
            :parameters {:body PaymentRequest}
            :handler    (fn [{:keys [components parameters] :as request}]
                          {:status 200
                           :body   (stripe/payment-intent (:body parameters))})}}]])

(defn inject-components
  [components]
  (fn wrap [handler]
    (fn new-handler [request]
      (handler (assoc request :components components)))))

(def api-route-groups
  "Root-rooted JSON API resource route groups, dual-mounted at their legacy root
  paths and under /api/v1 during the transition. Excludes reitit-photos-routes
  (already namespaced at /v2/photos — folding it under /api/v1 is deferred) and
  the ping/openapi/index/admin infra routes."
  [reitit-albums-routes
   reitit-articles-routes
   reitit-audiences-routes
   reitit-branches-routes
   reitit-compositions-routes
   reitit-groups-routes
   reitit-interests-routes
   reitit-projects-routes
   reitit-score-definition-routes
   reitit-agent-routes
   reitit-task-routes
   reitit-workflow-routes
   reitit-project-workflow-routes
   reitit-workspace-roots-routes
   reitit-portfolio-routes
   reitit-recipes-routes
   reitit-recipe-labels-routes
   reitit-recipe-label-groups-routes
   reitit-recipe-audiences-routes
   reitit-themes-routes])

(defn kaleidoscope-app
  ([]
   (kaleidoscope-app {}))
  ([components]
   (let [reitit-config (assoc-in mw/reitit-configuration
                                 [:data :middleware]
                                 (concat mw/base-middleware
                                         [(tenant/wrap-resolve-tenant (or (:tenant-resolver components) tenant/host-resolver))
                                          (mw/wrap-exception-reporter (:exception-reporter components))
                                          (inject-components components)
                                          (:session-tracking components)
                                          (:auth-stack components)]
                                         mw/coercion-middleware))]
     (ring/ring-handler
      (ring/router
       (into [;; Administrative/helpers + already-namespaced photos (root only)
              reitit-ping-routes
              reitit-openapi-routes
              reitit-index-routes
              reitit-admin-routes
              reitit-photos-routes]
             (concat
              ;; API resource groups at their legacy root paths …
              api-route-groups
              ;; … and the same groups under /api/v1 (:no-doc keeps the
              ;; transition duplicates out of the OpenAPI spec).
              [(into ["/api/v1" {:no-doc true}] api-route-groups)]))
       ;; reitit-stripe-routes and reitit-registration-routes intentionally
       ;; not mounted yet - not ready for production.
       reitit-config)
      (ring/create-default-handler
       {:not-found (fn [request]
                     (if (spa-shell-request? request)
                       ;; Client-side routing: serve the SPA shell. This handler
                       ;; bypasses the reitit middleware stack, so set the
                       ;; shared-shell store (:asset-store) and :uri manually —
                       ;; the same values wrap-resolve-tenant/wrap-force-uri
                       ;; would apply — and inject :components directly. It also
                       ;; skips base-middleware's wrap-content-type, so apply it
                       ;; here explicitly (same middleware, same :uri-based
                       ;; guess matched routes get) or the shell would come back
                       ;; without a Content-Type header.
                       (span/with-span! {:name "kaleidoscope.default.handler.get"}
                         ((wrap-content-type get-static-resource)
                          (-> request
                              (assoc :tenant {:asset-store "kaleidoscope.client"})
                              (assoc :uri "index.html")
                              (assoc :components components))))
                       (not-found {:reason "Not found"})))})))))

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
