(ns kaleidoscope.http-api.registration
  (:require [kaleidoscope.clients.route53 :as r53]
            [ring.util.http-response :refer [ok]]))

;; RFC 1035-ish hostname shape: dot-separated labels of letters/digits/hyphens
;; (no leading/trailing hyphen per label), 2-63 char alphabetic TLD, overall
;; length capped at 253. This is a public, unauthenticated endpoint that fans
;; out to AWS on every call, so the query param is constrained before it's
;; ever passed to the AWS SDK rather than accepted as an arbitrary string.
(def domain-pattern
  #"^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}$")

(def reitit-registration-routes
  [""
   ["/registration" {:get {:handler (fn [{:keys [components parameters] :as request}])}}]
   ["/check-domain"
    {:get {:summary    "Check domain for availability"
           ;; Public + unauthenticated (pre-auth signup flow) and fans out
           ;; to AWS Route53 on every call — rate limit it so it can't be
           ;; used to run up Route53 API volume.
           :rate-limit {:max-requests 20 :window-ms 60000}
           :responses  {200 {:description "Availability"
                             :content     {"application/json"
                                           {:schema   [:map
                                                       [:domain :string]
                                                       [:error {:optional true} :string]
                                                       [:available {:optional true} :boolean]
                                                       [:prices {:optional true} :any]]}}}
                        409 {:body [:= "Cannot change a published branch"]}}
           :parameters {:query [:map
                                [:domain [:re domain-pattern]]]}
           :handler    (fn [{:keys [components parameters] :as request}]
                         (let [domain (get-in parameters [:query :domain])]
                           (ok (r53/check-availability domain))))}}]])
