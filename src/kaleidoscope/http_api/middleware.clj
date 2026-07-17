(ns kaleidoscope.http-api.middleware
  (:require [buddy.auth.accessrules :as ar]
            [buddy.auth.middleware :as ba]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [kaleidoscope.api.authentication :as oidc]
            [kaleidoscope.clients.session-tracker :as st]
            [kaleidoscope.http-api.http-utils :as http-utils]
            [muuntaja.core :as m]
            [reitit.coercion.malli :as rcm]
            [reitit.openapi :as openapi]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.dev :as dev]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger :as swagger]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.gzip :refer [wrap-gzip]]
            [ring.middleware.multipart-params :as mp :refer [wrap-multipart-params]]
            [ring.middleware.params :as params :refer [wrap-params]]
            [ring.util.http-response :refer [unauthorized]]
            [ring.util.response :as resp]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [taoensso.timbre :as log]))


(def ^:dynamic *request-id*
  "Unbound")

(def ^:dynamic *user-context*
  "Bound to `{:user-id ... :email ... :type ...}` for the duration of an
  authenticated request by `wrap-bind-user-context`. `nil` for unauthenticated
  requests. Read by log output functions and used to tag the request's span."
  nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Custom middlewares
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn log-request! [handler]
  (fn [request]
    (handler (do (log/infof "Request received\n %s"
                            (-> request
                                (select-keys [:request-method
                                              :uri
                                              ;;:body
                                              ;;:params
                                              ;;:multipart-params
                                              ;;:request-id
                                              ])
                                pprint/pprint
                                with-out-str))
                 request))))

(defn wrap-request-identifier
  [handler]
  (fn [request]
    (span/with-span! {:name (format "kaleidoscope.mw.request-id")}
      (let [request-id (str (java.util.UUID/randomUUID))]
        (binding [*request-id* request-id]
          (handler (let [modified-request (assoc request :request-id request-id)]
                     (log/debugf "Adding request-id %s to ring request" request-id)
                     modified-request)))))))

(defn wrap-trace
  [handler]
  (fn [{:keys [uri request-method] :as request}]

    (span/with-span! {:name (format "kaleidoscope.%s.%s"
                                    (string/replace uri "/" ".")
                                    request-method)}
      ;;(log/infof "Inside wrap-trace span %s" (span/get-span))
      (handler request))))



(defn wrap-exception-reporter
  [report-fn]
  (fn [handler]
    (fn [request]
      (try
        (handler request)
        (catch Exception e
          (log/errorf "Unhandled exception on %s %s: %s"
                      (:request-method request) (:uri request) e)
          (when report-fn (report-fn e))
          {:status  500
           :headers {"Content-Type" "application/json"}
           :body    "{\"error\":\"Internal server error\"}"})))))

(defn session-tracking-stack
  [session-tracker]
  (fn wrap-session-tracking [handler]
    (fn [request]
      (span/with-span! {:name (format "kaleidoscope.mw.bugsnag-session-init")}
        (handler (do (when session-tracker
                       (st/start! session-tracker))
                     request))))))

(defonce ^:private rate-limit-buckets
  (atom {}))

(defn reset-rate-limits!
  "Test hook - clears all rate-limit counters."
  []
  (reset! rate-limit-buckets {}))

(defn- request-ip
  [{:keys [remote-addr headers] :as _request}]
  (or (get headers "fly-client-ip")
      (some-> (get headers "x-forwarded-for") (string/split #",\s*") first)
      remote-addr
      "unknown"))

(def wrap-rate-limit
  "Fixed-window rate limiter keyed by client IP + route (not by the literal
  request path).

  Enabled per-route via route data `:rate-limit {:max-requests N :window-ms
  M}`; routes without that key are unaffected. Exists to bound abuse of
  unauthenticated routes that write to paid third-party APIs (Stripe) or fan
  out to external services (AWS Route53) with no ACL protecting them, and
  more generally to cap request volume on any route that triggers a paid
  LLM call.

  `:compile` runs exactly once per route at router-build time, not per
  request — so `route-key` below is a single gensym captured once in the
  closure, identical for every request that matches this route regardless
  of its path-parameter values. Keying on `(:uri request)` instead (the
  literal path, e.g. `/projects/<uuid>/scores`) was tried first and is
  wrong: it gives every distinct resource id its own independent bucket, so
  a caller who owns N projects gets N times the effective limit on any
  route with a `:project-id`/`:run-id` segment — silently defeating the
  limit's stated purpose on every parameterized route in the app.

  State is a single in-process atom - correct for a single-instance deploy
  only. A multi-instance deploy would need a shared store (e.g. Redis)
  instead."
  {:name    ::wrap-rate-limit
   :compile (fn [{:keys [rate-limit] :as _route-data} _opts]
              (when rate-limit
                (let [{:keys [max-requests window-ms]} rate-limit
                      route-key (gensym "rate-limited-route")]
                  (fn wrapper [handler]
                    (fn new-handler [request]
                      (let [bucket-key      [(request-ip request) route-key]
                            now             (System/currentTimeMillis)
                            window-start    (- now (mod now window-ms))
                            {:keys [count]} (get (swap! rate-limit-buckets update bucket-key
                                                         (fn [entry]
                                                           (if (= (:window entry) window-start)
                                                             (update entry :count inc)
                                                             {:window window-start :count 1})))
                                                  bucket-key)]
                        (if (> count max-requests)
                          {:status  429
                           :headers {"Content-Type" "application/json"}
                           :body    "{\"error\":\"Too many requests\"}"}
                          (handler request))))))))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configured middleware stacks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn wrap-classify-identity
  "Merges classified identity fields (:type, :user-id, :roles) into the raw JWT
  claims map in :identity. Running after wrap-authentication and before
  wrap-access-rules means authorization functions receive :type/:user-id/:roles
  while legacy handlers that read :email or :realm_access still find those fields."
  [handler]
  (fn [request]
    (handler (cond-> request
               (:identity request) (update :identity #(merge % (oidc/classify-identity %)))))))

(defn wrap-bind-user-context
  "Binds *user-context* to {:user-id :email :type} derived from the classified
  :identity, and tags the currently open request span with the same fields
  (as `enduser.*` attributes) so both logs and traces can be correlated back
  to the user that made the request. No-ops for unauthenticated requests."
  [handler]
  (fn [request]
    (if-let [identity (:identity request)]
      (let [user-context {:user-id (:user-id identity)
                          :email   (oidc/get-email identity)
                          :type    (:type identity)}]
        (span/add-span-data! {:attributes {"enduser.id"    (:user-id user-context)
                                           "enduser.email" (:email user-context)
                                           "enduser.type"  (str (:type user-context))}})
        (binding [*user-context* user-context]
          (handler request)))
      (handler request))))

(defn auth-stack
  "Stack is applied from top down"
  [authentication-backend access-rules]
  [#(ba/wrap-authentication % authentication-backend)
   wrap-classify-identity
   wrap-bind-user-context
   ;; :policy :reject is the load-bearing option here, not a default worth
   ;; leaving implicit. buddy-auth's own default is :policy :allow — any
   ;; URI that doesn't match a pattern in `access-rules` is served with NO
   ;; authorization check at all unless this is set. This is an independent
   ;; safety net alongside the explicit catch-all rule at the bottom of
   ;; KALEIDOSCOPE-ACCESS-CONTROL-LIST — that rule protects as long as it
   ;; stays last in the list; this protects even if the list is ever
   ;; reordered, replaced, or supplied from somewhere else entirely.
   #(ar/wrap-access-rules % {:rules          access-rules
                             :policy         :reject
                             :reject-handler (fn [& args]
                                               (-> "Not authorized"
                                                   (unauthorized)
                                                   (resp/content-type "application/text")))})])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reitit configuration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def wrap-add-http-spans
  "Add OTLP spans at the point where the HTTP request is received"
  {:name    ::wrap-add-http-spans
   :compile (fn [{:keys [span-name] :as _route-data} opts]
              ;;(tap> {:route-data _route-data})
              (fn wrapper [handler]
                (fn new-handler [request]
                  (if span-name
                    (span/with-span! {:name (if (fn? span-name)
                                              (span-name request)
                                              span-name)}
                      (handler request))
                    (handler request)))))})

(defn wrap-kebab-case-headers
  "Normalize request header keys to kebab-case for every request, so handlers
  and downstream middleware never apply `http-utils/kebab-case-headers`
  a-la-carte."
  [handler]
  (fn [request] (handler (http-utils/kebab-case-headers request))))

(def wrap-force-uri
  "If the reitit route has a `:uri` key, force the request to search for that
  specific uri. Useful for serving `index.html` from a `/` route."
  {:name    ::wrap-force-uri
   :compile (fn [{:keys [uri] :as _route-data} opts]
              (fn wrapper [handler]
                (fn new-handler [request]
                  (span/with-span! {:name (format "kaleidoscope.mw.force-uri")}
                    (handler (if uri (assoc request :uri uri) request))))))})

(def base-middleware
  "Request setup shared by every route, applied before auth and before
  coercion. Kept separate from `coercion-middleware` so `kaleidoscope-app`
  can splice the auth stack in between the two — auth must run before body
  coercion, otherwise an unauthenticated request with a missing/invalid body
  gets rejected by Malli (400) before it ever reaches the access-rules check
  that should return 401."
  [wrap-add-http-spans
   wrap-kebab-case-headers
   wrap-force-uri
   wrap-rate-limit
   wrap-gzip
   wrap-content-type

   wrap-request-identifier
   wrap-trace
   wrap-multipart-params
   log-request!

   parameters/parameters-middleware ;; Add :query-params and :form-params (if url-encoded body), and params (merged)
   muuntaja/format-middleware       ;; Add :body-params

   openapi/openapi-feature])

(def coercion-middleware
  "Must run after authentication/access-rules - see `base-middleware`."
  [rrc/coerce-exceptions-middleware ;; Coerce malli/Coercion objects
   rrc/coerce-request-middleware    ;; Add :parameters
   rrc/coerce-response-middleware   ;; ?
   ])

(def reitit-configuration
  "Router data affecting all routes"
  {;;:reitit.middleware/transform dev/print-request-diffs
   :conflicts nil ;; literals take precedence over wildcards in the trie; suppress conflict exception

   :data {:coercion   rcm/coercion
          :muuntaja   m/instance
          :middleware (concat base-middleware coercion-middleware)}})



(comment
  (defn log-transformation-diffs
    [{:keys [name handler]}]
    (fn [x]
      (let [modified-x (handler x)]
        (log/debugf "Difference introduced by %s: %s"
                    name
                    (string-diff x modified-x))
        modified-x)))

  (defn add-request-identifier
    [request]
    (let [request-id       (str (java.util.UUID/randomUUID))
          modified-request (assoc request :request-id request-id)]
      modified-request))

  (require '[ring.mock.request :as mock])
  ((log-transformation-diffs {:name    :Hello
                              :handler add-request-identifier})
   (mock/request :get "/endpoint")))
