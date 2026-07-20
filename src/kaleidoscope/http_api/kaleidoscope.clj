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
   {:pattern #"^/interests.*"         :handler auth/require-*-writer}
   {:pattern #"^/projects-portfolio"  :handler auth/public-access}
   {:pattern #"^/projects.*"          :handler auth/require-*-writer}
   {:pattern #"^/score-definitions.*" :handler auth/require-*-writer}
   {:pattern #"^/agents.*"             :handler auth/require-*-writer}
   {:pattern #"^/workflows.*"         :handler auth/require-*-writer}
   {:pattern #"^/workspace-roots.*"   :handler auth/require-*-writer}

   {:pattern #"^/media.*" :request-method :post :handler auth/require-*-writer}
   {:pattern #"^/media.*" :request-method :get  :handler auth/public-access}

   {:pattern #"^/v2/photos.*"  :request-method :post :handler auth/require-*-writer}
   {:pattern #"^/v2/photos.*"  :request-method :put  :handler auth/require-*-admin}
   {:pattern #"^/v2/photos"    :request-method :get  :handler auth/require-*-writer}
   {:pattern #"^/v2/photos/.*" :request-method :get  :handler auth/public-access}

   {:pattern #"^/themes.*"    :request-method :put    :handler auth/require-*-writer}
   {:pattern #"^/themes.*"    :request-method :post   :handler auth/require-*-writer}
   {:pattern #"^/themes.*"    :request-method :delete :handler auth/require-*-writer}
   {:pattern #"^/themes.*"    :request-method :get    :handler auth/public-access}


   {:pattern #"^/albums.*"            :handler auth/require-*-admin}
   {:pattern #"^/article-audiences.*" :handler auth/require-*-admin}

   ;; Recipes: GETs are public so shared/public recipes and their label chips
   ;; render for anonymous readers (the recipe list itself is access-filtered
   ;; internally by get-visible-recipes); writes require a writer. Sharing uses
   ;; writer (not admin like article-audiences) to keep it usable — see PLAN.md.
   {:pattern #"^/recipes.*"             :request-method :get    :handler auth/public-access}
   {:pattern #"^/recipes.*"             :request-method :post   :handler auth/require-*-writer}
   {:pattern #"^/recipes.*"             :request-method :put    :handler auth/require-*-writer}
   {:pattern #"^/recipes.*"             :request-method :delete :handler auth/require-*-writer}
   {:pattern #"^/recipe-labels.*"       :request-method :get    :handler auth/public-access}
   {:pattern #"^/recipe-labels.*"       :handler auth/require-*-writer}
   {:pattern #"^/recipe-label-groups.*" :request-method :get    :handler auth/public-access}
   {:pattern #"^/recipe-label-groups.*" :handler auth/require-*-writer}
   {:pattern #"^/recipe-audiences.*"    :handler auth/require-*-writer}

   ;; Everything below is intentionally public — listed explicitly so the
   ;; catch-all at the bottom can safely reject anything NOT named here.
   ;;
   ;; Before 2026-07-03 this list relied on buddy-auth's default :policy
   ;; :allow for any URI that didn't match a pattern above — meaning a new
   ;; route mounted in kaleidoscope-app that didn't happen to match one of
   ;; these regexes was served with *zero* authorization enforcement, not
   ;; "denied by default." No sensitive route was found relying on that gap
   ;; at the time (see PLAN.md, "Critical finding #4"), but the failure mode
   ;; is silent and total — a single missed or mistyped pattern is a full,
   ;; invisible data exposure with no error and no test failure to catch it.
   {:pattern #"^/openapi\.json$" :handler auth/public-access}
   {:pattern #"^/api-docs.*"     :handler auth/public-access}
   {:pattern #"^/favicon\.ico$"  :handler auth/public-access}
   {:pattern #"^/assets.*"       :handler auth/public-access}
   {:pattern #"^/static.*"       :handler auth/public-access}
   ;; `/` and `/favicon.ico` carry route-level :uri route-data
   ;; ("index.html", "static/favicon.ico") that wrap-force-uri rewrites
   ;; :uri to *before* wrap-access-rules runs (wrap-force-uri is earlier —
   ;; more outer — in the middleware stack). Access rules see the rewritten
   ;; value, not the original request path, so both forms need a pattern.
   {:pattern #"^index\.html$"         :handler auth/public-access}
   {:pattern #"^static/favicon\.ico$" :handler auth/public-access}
   ;; Pre-auth signup flow — must be reachable before the caller has an
   ;; identity to check a role against.
   {:pattern #"^/registration.*" :handler auth/public-access}
   {:pattern #"^/check-domain.*" :handler auth/public-access}
   ;; Stripe payment-intent creation — must be reachable before checkout
   ;; completes (standard Stripe Elements pattern: the client needs a
   ;; client-secret before the payer is necessarily authenticated). Not
   ;; itself a data-exposure risk (a PaymentIntent doesn't charge anything
   ;; until confirmed with a real payment method), but it is an
   ;; unauthenticated write to a paid third-party API with no rate limiting
   ;; in front of it — worth revisiting as a cost/abuse question separately
   ;; from authorization correctness.
   {:pattern #"^/v1/payments.*"  :handler auth/public-access}

   ;; Fail closed: anything not explicitly named above is rejected, not
   ;; allowed. This was already written by a previous author and left
   ;; disabled (commented out) — re-enabled as the load-bearing fix here.
   {:pattern #"^/.*" :handler (constantly false)}])

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
        reitit-interests-routes
        reitit-photos-routes
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
        reitit-themes-routes
        ;; reitit-stripe-routes and reitit-registration-routes intentionally
        ;; not mounted yet - not ready for production.
        ]
       reitit-config)
      (ring/create-default-handler
       {:not-found (fn [request]
                     (tap> {:req        request
                            :components components})
                     ;; May not have the middleware components!
                     ;; Redirect to index.html for client-side routing.
                     ;; This handler bypasses the reitit middleware stack, so set the
                     ;; shared-shell store (:asset-store) and :uri manually — the same
                     ;; values wrap-resolve-tenant/wrap-force-uri would apply.
                     (span/with-span! {:name (format "kaleidoscope.default.handler.get")}
                       (get-static-resource (-> request
                                                (assoc :tenant {:asset-store "kaleidoscope.client"})
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
