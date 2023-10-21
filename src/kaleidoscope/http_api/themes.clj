(ns kaleidoscope.http-api.themes
  (:require [kaleidoscope.api.authentication :as oidc]
            [kaleidoscope.api.themes :as themes-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [ring.util.http-response :refer [no-content ok unauthorized not-found]]
            [taoensso.timbre :as log]))

(def ThemeRequest
  [:map
   [:display-name :string]
   [:config :map]])

(def example-theme-request
  {:display-name "My Theme"
   :config       {:primary {:main "#ABC123"}}})

(def reitit-themes-routes
  ["/themes" {:tags ["themes"]
              ;; For testing only - this is a mechanism to always get results from a particular
              ;; host URL.
              ;;
              ;;:host      "andrewslai.localhost"
              }
   ["" {:get  {:summary    "Retrieve all themes"
               :responses  {200 {:description "A collection of themes"
                                 :content     {"application/json"
                                               {:schema [:sequential themes-api/Theme]}}}}
               :parameters {:query [:map]}
               :handler    (fn [{:keys [components parameters] :as request}]
                             (log/infof "Received params %s" parameters)
                             (let [query-params (:query parameters)
                                   themes       (themes-api/get-themes (:database components) query-params)]
                               (if (empty? themes)
                                 (not-found)
                                 (ok themes))))}
        :post {:summary   "Create a theme"
               :openapi   {:security [{:andrewslai-pkce ["roles" "profile"]}]}
               :responses (merge hu/openapi-401
                                 {200 {:description "The theme that was created"
                                       :content     {"application/json"
                                                     {:schema themes-api/Theme}}}})
               :request   {:description "The theme you want to create"
                           :content     {"application/json"
                                         {:schema   [:any]
                                          :examples {"example-theme-1" {:summary "Example theme 1"
                                                                        :value   example-theme-request}}}}}
               :handler   (fn [{:keys [components parameters] :as request}]
                            (try
                              (log/info "Creating theme!" parameters)
                              (let [theme    (merge (:request parameters)
                                                    {:hostname (hu/get-host request)
                                                     :owner-id (oidc/get-user-id (:identity request))})
                                    [result] (themes-api/create-theme! (:database components) theme)]
                                (log/infof "Created theme %s" result)
                                (ok result))
                              (catch Exception e
                                (log/error "Caught exception " e))))}}]])
