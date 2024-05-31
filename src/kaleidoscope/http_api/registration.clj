(ns kaleidoscope.http-api.registration
  (:require [kaleidoscope.clients.route53 :as r53]
            [ring.util.http-response :refer [ok]]))

(def reitit-registration-routes
  [""
   ["/registration" {:get {:handler (fn [{:keys [components parameters] :as request}])}}]
   ["/check-domain"
    {:get {:summary    "Check domain for availability"
           :responses  {200 {:description "Availability"
                             :content     {"application/json"
                                           {:schema   [:map
                                                       [:domain :string]
                                                       [:error {:optional true} :string]
                                                       [:available {:optional true} :boolean]
                                                       [:prices {:optional true} :any]]}}}
                        409 {:body [:= "Cannot change a published branch"]}}
           :parameters {:query [:map
                                [:domain :string]]}
           :handler    (fn [{:keys [components parameters] :as request}]
                         (let [domain (get-in parameters [:query :domain])]
                           (println "DOMAIN" domain)
                           (ok (r53/check-availability domain))))}}]])
